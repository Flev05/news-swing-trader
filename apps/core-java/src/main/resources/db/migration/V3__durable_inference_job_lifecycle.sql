-- M3A1 schema support only. Lifecycle operations arrive in subsequent checkpoints.
ALTER TABLE inference_jobs
    DROP CONSTRAINT ck_inference_jobs_status,
    DROP CONSTRAINT ck_inference_jobs_supersession,
    ADD COLUMN assignment_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN max_assignments INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN expected_backend VARCHAR(16) NOT NULL DEFAULT 'fake',
    ADD COLUMN ownership_token UUID,
    ADD COLUMN assignment_inference_id UUID,
    ADD COLUMN assigned_at TIMESTAMPTZ,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN terminal_at TIMESTAMPTZ,
    ADD COLUMN last_failure_code VARCHAR(32);

UPDATE inference_jobs SET terminal_at = superseded_at WHERE status = 'SUPERSEDED';

ALTER TABLE inference_jobs
    ADD CONSTRAINT ck_inference_jobs_status
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'SUPERSEDED', 'EXPIRED')),
    ADD CONSTRAINT ck_inference_jobs_assignments
        CHECK (max_assignments > 0 AND assignment_count >= 0 AND assignment_count <= max_assignments),
    ADD CONSTRAINT ck_inference_jobs_assignment_eligibility
        CHECK (
            (status NOT IN ('PENDING', 'RETRY_WAIT') OR assignment_count < max_assignments)
            AND (status NOT IN ('RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED') OR assignment_count > 0)
        ),
    ADD CONSTRAINT ck_inference_jobs_backend
        CHECK (expected_backend IN ('fake', 'llama.cpp', 'ollama')),
    ADD CONSTRAINT ck_inference_jobs_ownership
        CHECK (
            (status = 'RUNNING'
                AND ownership_token IS NOT NULL AND assignment_inference_id IS NOT NULL
                AND assigned_at IS NOT NULL AND lease_expires_at IS NOT NULL
                AND lease_expires_at > assigned_at)
            OR (status <> 'RUNNING'
                AND ownership_token IS NULL AND assignment_inference_id IS NULL
                AND assigned_at IS NULL AND lease_expires_at IS NULL)
        ),
    ADD CONSTRAINT ck_inference_jobs_terminal
        CHECK (
            (status IN ('SUCCEEDED', 'FAILED', 'SUPERSEDED', 'EXPIRED') AND terminal_at IS NOT NULL)
            OR (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT') AND terminal_at IS NULL)
        ),
    ADD CONSTRAINT ck_inference_jobs_supersession
        CHECK (
            (status = 'SUPERSEDED' AND superseded_at IS NOT NULL
                AND superseded_by_event_id IS NOT NULL AND terminal_at = superseded_at)
            OR (status <> 'SUPERSEDED' AND superseded_at IS NULL AND superseded_by_event_id IS NULL)
        ),
    ADD CONSTRAINT ck_inference_jobs_failure_code
        CHECK (
            last_failure_code IS NULL
            OR (assignment_count > 0 AND last_failure_code IN (
                'WORKER_RECOVERABLE', 'WORKER_PERMANENT', 'LEASE_EXPIRED',
                'INVALID_RESULT', 'PROVENANCE_MISMATCH'))
        ),
    ADD CONSTRAINT ck_inference_jobs_failure_state
        CHECK (
            (status <> 'FAILED' OR last_failure_code IS NOT NULL)
            AND (status <> 'RETRY_WAIT' OR (
                last_failure_code IS NOT NULL
                AND last_failure_code IN ('WORKER_RECOVERABLE', 'LEASE_EXPIRED')))
            AND (status <> 'FAILED' OR last_failure_code NOT IN ('WORKER_RECOVERABLE', 'LEASE_EXPIRED')
                OR assignment_count = max_assignments)
        ),
    ADD CONSTRAINT uq_inference_jobs_result_assignment
        UNIQUE (job_id, news_revision_id, assignment_count, expected_backend);

ALTER TABLE news_revisions
    ADD CONSTRAINT uq_news_revisions_event_hash UNIQUE (event_id, content_hash);

CREATE TABLE inference_results (
    job_id UUID PRIMARY KEY,
    news_revision_id UUID NOT NULL,
    inference_id UUID NOT NULL UNIQUE,
    news_event_sha256 VARCHAR(64) NOT NULL,
    result_schema_version VARCHAR(16) NOT NULL,
    backend VARCHAR(16) NOT NULL,
    result_payload JSONB NOT NULL,
    assignment_number INTEGER NOT NULL,
    accepted_owner_token UUID NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_inference_results_job_assignment
        FOREIGN KEY (job_id, news_revision_id, assignment_number, backend)
        REFERENCES inference_jobs (job_id, news_revision_id, assignment_count, expected_backend)
        ON DELETE RESTRICT,
    CONSTRAINT fk_inference_results_revision_hash
        FOREIGN KEY (news_revision_id, news_event_sha256)
        REFERENCES news_revisions (event_id, content_hash)
        ON DELETE RESTRICT,
    CONSTRAINT ck_inference_results_assignment CHECK (assignment_number > 0),
    CONSTRAINT ck_inference_results_hash CHECK (news_event_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inference_results_schema CHECK (result_schema_version = '1.0.0'),
    CONSTRAINT ck_inference_results_backend CHECK (backend IN ('fake', 'llama.cpp', 'ollama')),
    CONSTRAINT ck_inference_results_payload_object
        CHECK (jsonb_typeof(result_payload) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT ck_inference_results_payload_schema
        CHECK ((result_payload ->> 'schema_version') IS NOT DISTINCT FROM result_schema_version),
    CONSTRAINT ck_inference_results_payload_inference_id
        CHECK (((result_payload ->> 'inference_id')::UUID) IS NOT DISTINCT FROM inference_id),
    CONSTRAINT ck_inference_results_payload_event_id
        CHECK (((result_payload ->> 'news_event_id')::UUID) IS NOT DISTINCT FROM news_revision_id),
    CONSTRAINT ck_inference_results_payload_hash
        CHECK ((result_payload ->> 'news_event_sha256') IS NOT DISTINCT FROM news_event_sha256),
    CONSTRAINT ck_inference_results_payload_backend
        CHECK ((result_payload #>> '{model,backend}') IS NOT DISTINCT FROM backend)
);

-- Full v1 validation and the SUCCEEDED/result pairing belong to the later Java completion transaction.
CREATE FUNCTION prevent_inference_result_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'inference results are immutable';
END;
$$;

CREATE TRIGGER trg_prevent_inference_result_mutation
BEFORE UPDATE OR DELETE ON inference_results
FOR EACH ROW
EXECUTE FUNCTION prevent_inference_result_mutation();

DROP INDEX ix_inference_jobs_pending;
CREATE INDEX ix_inference_jobs_pending ON inference_jobs (created_at, job_id) WHERE status = 'PENDING';
CREATE INDEX ix_inference_jobs_running_lease ON inference_jobs (lease_expires_at, job_id) WHERE status = 'RUNNING';
CREATE INDEX ix_inference_jobs_retry_wait ON inference_jobs (created_at, job_id) WHERE status = 'RETRY_WAIT';

UPDATE platform_metadata
SET metadata_value = '3A', recorded_at = CURRENT_TIMESTAMP
WHERE metadata_key = 'milestone';
