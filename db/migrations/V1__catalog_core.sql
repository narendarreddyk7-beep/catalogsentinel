-- =====================================================================================================
-- V1 — Catalog core: sources, versioned listings, the event idempotency ledger and the transactional
-- outbox. See docs/database.md for the rationale behind each constraint and index.
-- =====================================================================================================

-- pg_trgm is a "trusted" extension (PostgreSQL 13+): a database owner can create it without superuser.
-- It is also on the Amazon RDS for PostgreSQL supported-extension list.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Keeps updated_at honest even for ad-hoc SQL fixes run by operators.
CREATE FUNCTION touch_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

-- -----------------------------------------------------------------------------------------------------
-- catalog_sources: marketplaces (selection being monitored) and reference catalogs (the benchmark).
-- -----------------------------------------------------------------------------------------------------
CREATE TABLE catalog_sources
(
    source_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(200) NOT NULL,
    role       VARCHAR(16)  NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_catalog_sources_code UNIQUE (code),
    CONSTRAINT ck_catalog_sources_code CHECK (code ~ '^[a-z0-9][a-z0-9_-]{1,63}$'),
    CONSTRAINT ck_catalog_sources_role CHECK (role IN ('MARKETPLACE', 'REFERENCE')),
    CONSTRAINT ck_catalog_sources_name CHECK (length(btrim(name)) > 0)
);

CREATE TRIGGER trg_catalog_sources_touch
    BEFORE UPDATE ON catalog_sources
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- -----------------------------------------------------------------------------------------------------
-- product_listings: one row per (source, external product id). Holds the latest applied version, raw
-- content, normalized fields used for matching, and the lifecycle status. A withdrawn listing is kept as
-- a tombstone so that stale events can never resurrect it.
-- -----------------------------------------------------------------------------------------------------
CREATE TABLE product_listings
(
    listing_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id             BIGINT       NOT NULL REFERENCES catalog_sources (source_id),
    external_product_id   VARCHAR(128) NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    source_version        BIGINT       NOT NULL,
    title                 VARCHAR(500),
    brand                 VARCHAR(200),
    model                 VARCHAR(200),
    category              VARCHAR(200),
    gtin                  CHAR(14),
    attributes            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    normalized_title      VARCHAR(700),
    normalized_brand      VARCHAR(200),
    normalized_model      VARCHAR(200),
    normalized_category   VARCHAR(300),
    category_leaf         VARCHAR(200),
    extracted_attributes  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    normalization_version VARCHAR(16),
    content_hash          CHAR(64),
    last_event_id         UUID         NOT NULL,
    source_updated_at     TIMESTAMPTZ  NOT NULL,
    withdrawn_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_listings_source_external UNIQUE (source_id, external_product_id),
    CONSTRAINT ck_product_listings_status CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT ck_product_listings_version CHECK (source_version >= 0),
    -- An active listing must have content; a tombstone may not (withdrawal seen before any upsert).
    CONSTRAINT ck_product_listings_active_has_content
        CHECK (status = 'WITHDRAWN' OR (title IS NOT NULL AND content_hash IS NOT NULL)),
    CONSTRAINT ck_product_listings_withdrawn_at CHECK ((status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL)),
    CONSTRAINT ck_product_listings_gtin CHECK (gtin IS NULL OR gtin ~ '^[0-9]{14}$'),
    CONSTRAINT ck_product_listings_attributes CHECK (jsonb_typeof(attributes) = 'object'),
    CONSTRAINT ck_product_listings_hash CHECK (content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$')
);

CREATE TRIGGER trg_product_listings_touch
    BEFORE UPDATE ON product_listings
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- Catalog explorer: browse one source, newest first, with keyset pagination on listing_id.
CREATE INDEX ix_product_listings_source_id ON product_listings (source_id, listing_id DESC);
-- Status filters across all sources (dashboard counts, reconciliation scans).
CREATE INDEX ix_product_listings_status_id ON product_listings (status, listing_id DESC);

-- -----------------------------------------------------------------------------------------------------
-- processed_events: the idempotency ledger. The primary key on event_id makes "process at most once per
-- event id" a database guarantee; the fingerprint distinguishes an exact replay (return the stored
-- outcome) from a reused id carrying different data (conflict).
-- -----------------------------------------------------------------------------------------------------
CREATE TABLE processed_events
(
    event_id            UUID PRIMARY KEY,
    source_id           BIGINT       NOT NULL REFERENCES catalog_sources (source_id),
    external_product_id VARCHAR(128) NOT NULL,
    listing_id          BIGINT       NOT NULL REFERENCES product_listings (listing_id),
    event_type          VARCHAR(32)  NOT NULL,
    request_fingerprint CHAR(64)     NOT NULL,
    requested_version   BIGINT       NOT NULL,
    applied_version     BIGINT,
    outcome             VARCHAR(32)  NOT NULL,
    reason              VARCHAR(300) NOT NULL,
    ingestion_path      VARCHAR(16)  NOT NULL,
    ingestion_job_id    BIGINT,
    correlation_id      VARCHAR(100),
    occurred_at         TIMESTAMPTZ  NOT NULL,
    received_at         TIMESTAMPTZ  NOT NULL,
    processed_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_processed_events_type CHECK (event_type IN ('LISTING_UPSERTED', 'LISTING_WITHDRAWN')),
    CONSTRAINT ck_processed_events_outcome CHECK (outcome IN
        ('CREATED', 'UPDATED', 'UNCHANGED', 'WITHDRAWN', 'REACTIVATED', 'STALE_IGNORED', 'VERSION_CONFLICT')),
    CONSTRAINT ck_processed_events_path CHECK (ingestion_path IN ('SYNC_API', 'ASYNC_QUEUE', 'BATCH')),
    -- applied_version is the listing version produced by the event; NULL exactly when nothing was applied.
    CONSTRAINT ck_processed_events_applied
        CHECK ((outcome IN ('STALE_IGNORED', 'VERSION_CONFLICT')) = (applied_version IS NULL)),
    CONSTRAINT ck_processed_events_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

-- Listing history (GET /listings/{id} shows recent events).
CREATE INDEX ix_processed_events_listing ON processed_events (listing_id, processed_at DESC);
-- Time-window metrics. The table is append-only and processed_at grows monotonically, which is the ideal
-- case for a BRIN index: a few pages instead of a B-tree the size of the table.
CREATE INDEX ix_processed_events_processed_at_brin ON processed_events USING brin (processed_at);

-- -----------------------------------------------------------------------------------------------------
-- outbox_events: events written in the same transaction as the state change they describe, published to
-- SQS afterwards by the relay. Guarantees no lost and no phantom events (dual-write problem).
-- -----------------------------------------------------------------------------------------------------
CREATE TABLE outbox_events
(
    outbox_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        UUID          NOT NULL,
    aggregate_type  VARCHAR(64)   NOT NULL,
    aggregate_id    VARCHAR(64)   NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    payload         JSONB         NOT NULL,
    headers         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    CONSTRAINT uq_outbox_events_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_events_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
    CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0)
);

-- The relay polls "due" rows in id order; the partial index contains only unpublished rows, so it stays
-- small no matter how much history accumulates.
CREATE INDEX ix_outbox_events_due ON outbox_events (next_attempt_at, outbox_id) WHERE status = 'PENDING';
-- Retention purge of published rows.
CREATE INDEX ix_outbox_events_published_at ON outbox_events (published_at) WHERE status = 'PUBLISHED';
-- Operator view / alerting on rows that exhausted their publish attempts.
CREATE INDEX ix_outbox_events_failed ON outbox_events (outbox_id) WHERE status = 'FAILED';
