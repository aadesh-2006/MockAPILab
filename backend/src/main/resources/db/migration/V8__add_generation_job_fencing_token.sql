-- =========================================================================
-- V8: Monotonic Fencing Token for Generation Jobs
-- =========================================================================

CREATE SEQUENCE IF NOT EXISTS generation_job_fencing_seq START WITH 1 INCREMENT BY 1;

ALTER TABLE generation_jobs
    ADD COLUMN fencing_token BIGINT;

UPDATE generation_jobs
SET fencing_token = NEXTVAL('generation_job_fencing_seq')
WHERE fencing_token IS NULL;

ALTER TABLE generation_jobs
    ALTER COLUMN fencing_token SET NOT NULL;
