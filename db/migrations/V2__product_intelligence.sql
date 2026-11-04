-- =====================================================================================================
-- V2 — Product intelligence: canonical product identities, listing-to-product links, scored match
-- candidates, and the retrieval indexes that make candidate generation sub-linear.
-- =====================================================================================================

-- Candidate retrieval ("blocking"). Partial indexes on ACTIVE rows: withdrawn listings are never candidates.
-- 1) Exact identifier: GTIN.
CREATE INDEX ix_product_listings_gtin_active ON product_listings (gtin)
    WHERE gtin IS NOT NULL AND status = 'ACTIVE';
-- 2) Normalized brand + model (equality, and prefix scans on model via text_pattern_ops).
CREATE INDEX ix_product_listings_brand_model_active ON product_listings (normalized_brand, normalized_model text_pattern_ops)
    WHERE status = 'ACTIVE';
-- 3) Fuzzy title similarity via trigrams (supports the % operator and similarity() ordering, and also
--    accelerates ILIKE '%term%' searches in the catalog explorer).
CREATE INDEX ix_product_listings_title_trgm_active ON product_listings USING gin (normalized_title gin_trgm_ops)
    WHERE status = 'ACTIVE';

-- -----------------------------------------------------------------------------------------------------
-- canonical_products: platform-owned product identities, independent of any one supplier's listing.
-- -----------------------------------------------------------------------------------------------------
CREATE TABLE canonical_products
(
    canonical_product_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title                     VARCHAR(500) NOT NULL,
    brand                     VARCHAR(200),
    model                     VARCHAR(200),
    category                  VARCHAR(200),
    gtin                      CHAR(14),
    normalized_brand          VARCHAR(200),
    normalized_model          VARCHAR(200),
    category_leaf             VARCHAR(200),
    extracted_attributes      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    representative_listing_id BIGINT REFERENCES product_listings (listing_id),
    status                    VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    merged_into_id            BIGINT REFERENCES canonical_products (canonical_product_id),
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_canonical_products_status CHECK (status IN ('ACTIVE', 'MERGED')),
    CONSTRAINT ck_canonical_products_merged CHECK ((status = 'MERGED') = (merged_into_id IS NOT NULL)),
    CONSTRAINT ck_canonical_products_not_self CHECK (merged_into_id IS DISTINCT FROM canonical_product_id),
    CONSTRAINT ck_canonical_products_gtin CHECK (gtin IS NULL OR gtin ~ '^[0-9]{14}$')
);

CREATE TRIGGER trg_canonical_products_touch
    BEFORE UPDATE ON canonical_products
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE INDEX ix_canonical_products_brand ON canonical_products (normalized_brand, canonical_product_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_canonical_products_category ON canonical_products (category_leaf, canonical_product_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_canonical_products_title_trgm ON canonical_products USING gin (lower(title) gin_trgm_ops);

-- -----------------------------------------------------------------------------------------------------
-- match_candidates: scored pairs produced by the matcher, with per-feature scores and evidence. The pair
-- is stored once in canonical order (left < right). Human review state lives here.
-- -----------------------------------------------------------------------------------------------------
CREATE TABLE match_candidates
(
    candidate_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    left_listing_id    BIGINT       NOT NULL REFERENCES product_listings (listing_id),
    right_listing_id   BIGINT       NOT NULL REFERENCES product_listings (listing_id),
    score              NUMERIC(5, 4) NOT NULL,
    decision           VARCHAR(16)  NOT NULL,
    feature_scores     JSONB        NOT NULL,
    evidence           JSONB        NOT NULL,
    matcher_version    VARCHAR(32)  NOT NULL,
    retrieval_method   VARCHAR(32)  NOT NULL,
    left_content_hash  CHAR(64)     NOT NULL,
    right_content_hash CHAR(64)     NOT NULL,
    review_status      VARCHAR(16)  NOT NULL,
    reviewed_by        VARCHAR(128),
    reviewed_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_match_candidates_order CHECK (left_listing_id < right_listing_id),
    CONSTRAINT uq_match_candidates_pair UNIQUE (left_listing_id, right_listing_id),
    CONSTRAINT ck_match_candidates_score CHECK (score BETWEEN 0 AND 1),
    CONSTRAINT ck_match_candidates_decision CHECK (decision IN ('AUTO_MATCH', 'REVIEW', 'NO_MATCH')),
    CONSTRAINT ck_match_candidates_retrieval CHECK (retrieval_method IN ('GTIN', 'BRAND_MODEL', 'TITLE_TRIGRAM')),
    CONSTRAINT ck_match_candidates_review CHECK (review_status IN
        ('PENDING', 'APPROVED', 'REJECTED', 'AUTO_ACCEPTED', 'NOT_APPLICABLE', 'SUPERSEDED')),
    CONSTRAINT ck_match_candidates_reviewed CHECK
        ((review_status IN ('APPROVED', 'REJECTED')) = (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);

CREATE TRIGGER trg_match_candidates_touch
    BEFORE UPDATE ON match_candidates
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- Reviewer queue: highest-scoring pending pairs first. Partial index = only the actionable backlog.
CREATE INDEX ix_match_candidates_review_queue ON match_candidates (score DESC, candidate_id DESC)
    WHERE review_status = 'PENDING';
-- Lookups by the right-hand listing (the unique constraint already covers lookups by left_listing_id).
CREATE INDEX ix_match_candidates_right ON match_candidates (right_listing_id);

-- -----------------------------------------------------------------------------------------------------
-- listing_product_links: which canonical product each listing belongs to (at most one per listing).
-- -----------------------------------------------------------------------------------------------------
CREATE TABLE listing_product_links
(
    listing_id           BIGINT PRIMARY KEY REFERENCES product_listings (listing_id),
    canonical_product_id BIGINT        NOT NULL REFERENCES canonical_products (canonical_product_id),
    confidence           NUMERIC(5, 4) NOT NULL,
    match_method         VARCHAR(32)   NOT NULL,
    review_status        VARCHAR(16)   NOT NULL,
    matcher_version      VARCHAR(32)   NOT NULL,
    via_candidate_id     BIGINT REFERENCES match_candidates (candidate_id),
    linked_content_hash  CHAR(64),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_listing_product_links_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_listing_product_links_method CHECK (match_method IN
        ('NEW_IDENTITY', 'GTIN_EXACT', 'RULES_AUTO', 'REVIEW_APPROVED', 'MERGED')),
    CONSTRAINT ck_listing_product_links_review CHECK (review_status IN ('AUTO', 'CONFIRMED'))
);

CREATE TRIGGER trg_listing_product_links_touch
    BEFORE UPDATE ON listing_product_links
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- "Which listings belong to product X" (gap detection, canonical product detail page).
CREATE INDEX ix_listing_product_links_canonical ON listing_product_links (canonical_product_id, listing_id);
