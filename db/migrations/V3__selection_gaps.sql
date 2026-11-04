-- =====================================================================================================
-- V3 — Selection gaps: reconciliation jobs, gaps with lifecycle and evidence, and an append-only audit
-- trail of human review decisions.
-- =====================================================================================================

CREATE TABLE reconciliation_jobs
(
    job_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_source_id    BIGINT       NOT NULL REFERENCES catalog_sources (source_id),
    reference_source_id BIGINT REFERENCES catalog_sources (source_id),
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    requested_by        VARCHAR(128) NOT NULL,
    idempotency_key     VARCHAR(128),
    stats               JSONB        NOT NULL DEFAULT '{}'::jsonb,
    error_message       VARCHAR(2000),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_reconciliation_jobs_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_reconciliation_jobs_sources CHECK (reference_source_id IS DISTINCT FROM target_source_id),
    CONSTRAINT uq_reconciliation_jobs_idempotency UNIQUE (idempotency_key)
);

CREATE TRIGGER trg_reconciliation_jobs_touch
    BEFORE UPDATE ON reconciliation_jobs
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- At most one queued or running reconciliation per target marketplace: concurrent runs would race on the
-- same gaps. Enforced by the database, not by application locks.
CREATE UNIQUE INDEX uq_reconciliation_jobs_active_target ON reconciliation_jobs (target_source_id)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX ix_reconciliation_jobs_created ON reconciliation_jobs (created_at DESC, job_id DESC);

-- -----------------------------------------------------------------------------------------------------
-- selection_gaps: a canonical product carried by a reference source but absent (with sufficient
-- confidence) from a target marketplace.
-- -----------------------------------------------------------------------------------------------------
CREATE TABLE selection_gaps
(
    gap_id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    canonical_product_id      BIGINT        NOT NULL REFERENCES canonical_products (canonical_product_id),
    target_source_id          BIGINT        NOT NULL REFERENCES catalog_sources (source_id),
    reference_listing_id      BIGINT        NOT NULL REFERENCES product_listings (listing_id),
    status                    VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    reason                    VARCHAR(40)   NOT NULL,
    confidence                NUMERIC(5, 4) NOT NULL,
    priority_score            NUMERIC(6, 2) NOT NULL,
    priority                  VARCHAR(2)    NOT NULL,
    best_candidate_listing_id BIGINT REFERENCES product_listings (listing_id),
    best_candidate_score      NUMERIC(5, 4),
    evidence                  JSONB         NOT NULL,
    detection_count           INTEGER       NOT NULL DEFAULT 1,
    first_detected_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_detected_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_job_id               BIGINT REFERENCES reconciliation_jobs (job_id),
    resolved_at               TIMESTAMPTZ,
    resolution                VARCHAR(40),
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_selection_gaps_status CHECK (status IN ('OPEN', 'CONFIRMED', 'DISMISSED', 'RESOLVED')),
    CONSTRAINT ck_selection_gaps_reason CHECK (reason IN
        ('NO_CANDIDATE', 'ONLY_NON_MATCHING_CANDIDATES', 'VARIANT_NOT_CARRIED', 'UNCONFIRMED_CANDIDATE',
         'CANDIDATE_REJECTED')),
    CONSTRAINT ck_selection_gaps_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_selection_gaps_priority CHECK (priority IN ('P1', 'P2', 'P3')),
    CONSTRAINT ck_selection_gaps_resolved CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL AND resolution IS NOT NULL)),
    CONSTRAINT ck_selection_gaps_detections CHECK (detection_count >= 1)
);

CREATE TRIGGER trg_selection_gaps_touch
    BEFORE UPDATE ON selection_gaps
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- Deduplication: at most one non-resolved gap per (canonical product, target marketplace). DISMISSED is
-- included so that a dismissal suppresses re-detection; RESOLVED rows are history, so a product that
-- disappears again after being resolved opens a fresh gap.
CREATE UNIQUE INDEX uq_selection_gaps_active ON selection_gaps (canonical_product_id, target_source_id)
    WHERE status IN ('OPEN', 'CONFIRMED', 'DISMISSED');
-- Triage views: per marketplace and status, highest priority first, keyset-paginated.
CREATE INDEX ix_selection_gaps_triage ON selection_gaps (target_source_id, status, priority_score DESC, gap_id DESC);
CREATE INDEX ix_selection_gaps_status_priority ON selection_gaps (status, priority_score DESC, gap_id DESC);

-- -----------------------------------------------------------------------------------------------------
-- review_decisions: append-only audit trail for match and gap reviews. Exactly one subject per row.
-- -----------------------------------------------------------------------------------------------------
CREATE TABLE review_decisions
(
    decision_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    match_candidate_id BIGINT REFERENCES match_candidates (candidate_id),
    gap_id             BIGINT REFERENCES selection_gaps (gap_id),
    decision           VARCHAR(32)  NOT NULL,
    previous_status    VARCHAR(16)  NOT NULL,
    new_status         VARCHAR(16)  NOT NULL,
    reviewer           VARCHAR(128) NOT NULL,
    reason             VARCHAR(2000),
    idempotency_key    VARCHAR(128),
    decided_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_review_decisions_subject CHECK (num_nonnulls(match_candidate_id, gap_id) = 1),
    CONSTRAINT ck_review_decisions_decision CHECK (decision IN
        ('APPROVE', 'REJECT', 'CONFIRM', 'DISMISS', 'RESOLVE', 'REOPEN')),
    CONSTRAINT uq_review_decisions_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX ix_review_decisions_candidate ON review_decisions (match_candidate_id, decided_at)
    WHERE match_candidate_id IS NOT NULL;
CREATE INDEX ix_review_decisions_gap ON review_decisions (gap_id, decided_at)
    WHERE gap_id IS NOT NULL;

CREATE FUNCTION forbid_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'review_decisions is append-only (attempted %)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER trg_review_decisions_append_only
    BEFORE UPDATE OR DELETE ON review_decisions
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();
-- Statement-level TRUNCATE guard (row triggers do not fire on TRUNCATE).
CREATE TRIGGER trg_review_decisions_no_truncate
    BEFORE TRUNCATE ON review_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_audit_mutation();
