-- =====================================================================================================
-- V6 — Durable reconciliation jobs (docs/runbook.md#reconciliation-jobs).
--
-- Jobs are claimed from the database by pollers instead of being handed to an in-memory executor that could
-- reject them or lose them on restart. A claim takes a time-bounded lease (lease_owner, lease_expires_at, in
-- database time); the runner renews it as it makes progress. A job whose lease expires (process crash, kill,
-- lost connection) is claimed again by any poller as the next attempt. Failed attempts are retried after a
-- backoff (next_attempt_at) until max_attempts, then the job is FAILED with the last error visible.
-- The one-active-job-per-target index (PENDING, RUNNING) and the idempotency key are unchanged.
-- =====================================================================================================

ALTER TABLE reconciliation_jobs
    ADD COLUMN attempt          INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN max_attempts     INTEGER      NOT NULL DEFAULT 3,
    ADD COLUMN next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ADD COLUMN lease_owner      VARCHAR(128),
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

-- Jobs left RUNNING by the previous release had no lease: give them an expired one so pollers recover them.
UPDATE reconciliation_jobs
SET lease_owner = 'pre-v6', lease_expires_at = now(), attempt = 1
WHERE status = 'RUNNING';

ALTER TABLE reconciliation_jobs
    ADD CONSTRAINT ck_reconciliation_jobs_attempts CHECK (attempt >= 0 AND max_attempts >= 1 AND attempt <= max_attempts),
    ADD CONSTRAINT ck_reconciliation_jobs_lease
        CHECK ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL));

-- Poller scans: due PENDING jobs, and RUNNING jobs whose lease ran out. Both stay tiny (only active jobs).
CREATE INDEX ix_reconciliation_jobs_due ON reconciliation_jobs (next_attempt_at, job_id) WHERE status = 'PENDING';
CREATE INDEX ix_reconciliation_jobs_leases ON reconciliation_jobs (lease_expires_at, job_id) WHERE status = 'RUNNING';
