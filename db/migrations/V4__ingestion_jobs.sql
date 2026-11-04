-- =====================================================================================================
-- V4 — Batch ingestion jobs. A job is identified by a deterministic key derived from its manifest so that
-- re-submitting the same manifest (restart after a crash) resumes the same job instead of duplicating it.
-- Per-event progress is NOT counted on the job row (that would make it a hot row under concurrent
-- workers); it is derived from processed_events.ingestion_job_id instead.
-- =====================================================================================================

CREATE TABLE ingestion_jobs
(
    ingestion_job_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_key          VARCHAR(128) NOT NULL,
    source_id        BIGINT       NOT NULL REFERENCES catalog_sources (source_id),
    status           VARCHAR(16)  NOT NULL DEFAULT 'REGISTERED',
    manifest         JSONB        NOT NULL,
    input_uri        VARCHAR(1000),
    output_uri       VARCHAR(1000),
    rejected_uri     VARCHAR(1000),
    rows_total       BIGINT,
    rows_valid       BIGINT,
    rows_rejected    BIGINT,
    events_published BIGINT,
    error_summary    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    attempt          INTEGER      NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ingestion_jobs_job_key UNIQUE (job_key),
    CONSTRAINT ck_ingestion_jobs_status CHECK (status IN ('REGISTERED', 'RUNNING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_ingestion_jobs_counts CHECK (
        (rows_total IS NULL OR rows_total >= 0) AND (rows_valid IS NULL OR rows_valid >= 0)
            AND (rows_rejected IS NULL OR rows_rejected >= 0) AND (events_published IS NULL OR events_published >= 0)),
    CONSTRAINT ck_ingestion_jobs_attempt CHECK (attempt >= 1),
    CONSTRAINT ck_ingestion_jobs_errors CHECK (jsonb_typeof(error_summary) = 'array')
);

CREATE TRIGGER trg_ingestion_jobs_touch
    BEFORE UPDATE ON ingestion_jobs
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE INDEX ix_ingestion_jobs_created ON ingestion_jobs (created_at DESC, ingestion_job_id DESC);

ALTER TABLE processed_events
    ADD CONSTRAINT fk_processed_events_ingestion_job
        FOREIGN KEY (ingestion_job_id) REFERENCES ingestion_jobs (ingestion_job_id);

-- Job progress: outcome counts per job without touching the job row.
CREATE INDEX ix_processed_events_job_outcome ON processed_events (ingestion_job_id, outcome)
    WHERE ingestion_job_id IS NOT NULL;
