CREATE TABLE news_items (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    provider_news_id VARCHAR(256) NOT NULL,
    latest_event_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_news_items_provider
        CHECK (provider ~ '^[a-z][a-z0-9_-]+$'),
    CONSTRAINT ck_news_items_provider_news_id
        CHECK (provider_news_id ~ '[^[:space:]]'),
    CONSTRAINT uq_news_items_provider_news_id UNIQUE (provider, provider_news_id),
    CONSTRAINT uq_news_items_id_provider_news_id UNIQUE (id, provider, provider_news_id)
);

CREATE TABLE news_revisions (
    event_id UUID PRIMARY KEY,
    news_item_id UUID NOT NULL REFERENCES news_items (id) ON DELETE RESTRICT,
    provider VARCHAR(32) NOT NULL,
    provider_news_id VARCHAR(256) NOT NULL,
    provider_revision_id VARCHAR(256) NOT NULL,
    canonical_url VARCHAR(2048) NOT NULL,
    source_created_at TIMESTAMPTZ NOT NULL,
    source_updated_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    provider_fingerprint VARCHAR(64) NOT NULL,
    event_payload JSONB NOT NULL,
    admission_state VARCHAR(32) NOT NULL,
    persisted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_news_revisions_provider_revision_id
        CHECK (provider_revision_id ~ '[^[:space:]]'),
    CONSTRAINT ck_news_revisions_canonical_url
        CHECK (canonical_url ~ '^https://'),
    CONSTRAINT ck_news_revisions_content_hash
        CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_news_revisions_provider_fingerprint
        CHECK (provider_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_news_revisions_timestamp_order
        CHECK (source_created_at <= source_updated_at AND received_at < deadline_at),
    CONSTRAINT ck_news_revisions_admission_state
        CHECK (admission_state IN ('QUEUED', 'OUT_OF_ORDER')),
    CONSTRAINT ck_news_revisions_payload_object
        CHECK (jsonb_typeof(event_payload) = 'object'),
    CONSTRAINT ck_news_revisions_payload_schema_version
        CHECK ((event_payload ->> 'schema_version') IS NOT DISTINCT FROM '1.0.0'),
    CONSTRAINT ck_news_revisions_payload_event_id
        CHECK ((event_payload ->> 'event_id') IS NOT DISTINCT FROM event_id::TEXT),
    CONSTRAINT ck_news_revisions_payload_content_hash
        CHECK ((event_payload ->> 'content_hash') IS NOT DISTINCT FROM content_hash),
    CONSTRAINT ck_news_revisions_payload_provider
        CHECK ((event_payload #>> '{source,provider}') IS NOT DISTINCT FROM provider),
    CONSTRAINT ck_news_revisions_payload_news_id
        CHECK ((event_payload #>> '{source,news_id}') IS NOT DISTINCT FROM provider_news_id),
    CONSTRAINT ck_news_revisions_payload_revision_id
        CHECK (
            (event_payload #>> '{source,revision_id}') IS NOT DISTINCT FROM provider_revision_id
        ),
    CONSTRAINT ck_news_revisions_payload_canonical_url
        CHECK ((event_payload #>> '{source,canonical_url}') IS NOT DISTINCT FROM canonical_url),
    CONSTRAINT ck_news_revisions_payload_source_created_at
        CHECK (
            ((event_payload ->> 'source_created_at')::TIMESTAMPTZ)
            IS NOT DISTINCT FROM source_created_at
        ),
    CONSTRAINT ck_news_revisions_payload_source_updated_at
        CHECK (
            ((event_payload ->> 'source_updated_at')::TIMESTAMPTZ)
            IS NOT DISTINCT FROM source_updated_at
        ),
    CONSTRAINT ck_news_revisions_payload_received_at
        CHECK (((event_payload ->> 'received_at')::TIMESTAMPTZ) IS NOT DISTINCT FROM received_at),
    CONSTRAINT ck_news_revisions_payload_deadline_at
        CHECK (((event_payload ->> 'deadline_at')::TIMESTAMPTZ) IS NOT DISTINCT FROM deadline_at),
    CONSTRAINT uq_news_revisions_item_provider_revision
        UNIQUE (news_item_id, provider_revision_id),
    CONSTRAINT uq_news_revisions_event_item UNIQUE (event_id, news_item_id),
    CONSTRAINT fk_news_revisions_item_identity
        FOREIGN KEY (news_item_id, provider, provider_news_id)
        REFERENCES news_items (id, provider, provider_news_id)
        ON DELETE RESTRICT
);

ALTER TABLE news_items
    ADD CONSTRAINT fk_news_items_latest_revision
    FOREIGN KEY (latest_event_id, id)
    REFERENCES news_revisions (event_id, news_item_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE inference_jobs (
    job_id UUID PRIMARY KEY,
    news_revision_id UUID NOT NULL REFERENCES news_revisions (event_id) ON DELETE RESTRICT,
    job_kind VARCHAR(32) NOT NULL,
    input_schema_version VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at TIMESTAMPTZ,
    superseded_by_event_id UUID REFERENCES news_revisions (event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_inference_jobs_kind CHECK (job_kind = 'NEWS_INFERENCE'),
    CONSTRAINT ck_inference_jobs_schema_version CHECK (input_schema_version = '1.0.0'),
    CONSTRAINT ck_inference_jobs_idempotency_key
        CHECK (idempotency_key ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_inference_jobs_status CHECK (status IN ('PENDING', 'SUPERSEDED')),
    CONSTRAINT ck_inference_jobs_supersession
        CHECK (
            (status = 'PENDING' AND superseded_at IS NULL AND superseded_by_event_id IS NULL)
            OR (status = 'SUPERSEDED' AND superseded_at IS NOT NULL AND superseded_by_event_id IS NOT NULL)
        ),
    CONSTRAINT uq_inference_jobs_revision_kind_schema
        UNIQUE (news_revision_id, job_kind, input_schema_version),
    CONSTRAINT uq_inference_jobs_idempotency_key UNIQUE (idempotency_key)
);

CREATE TABLE queue_admission_locks (
    queue_name VARCHAR(32) PRIMARY KEY,
    CONSTRAINT ck_queue_admission_locks_name CHECK (queue_name = 'NEWS_INFERENCE')
);

INSERT INTO queue_admission_locks (queue_name) VALUES ('NEWS_INFERENCE');

CREATE INDEX ix_news_revisions_item_source_updated
    ON news_revisions (news_item_id, source_updated_at DESC, persisted_at DESC);

CREATE INDEX ix_inference_jobs_pending
    ON inference_jobs (created_at)
    WHERE status = 'PENDING';

CREATE FUNCTION prevent_news_revision_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'news revisions are immutable';
END;
$$;

CREATE TRIGGER trg_prevent_news_revision_mutation
BEFORE UPDATE OR DELETE ON news_revisions
FOR EACH ROW
EXECUTE FUNCTION prevent_news_revision_mutation();

UPDATE platform_metadata
SET metadata_value = '2A', recorded_at = CURRENT_TIMESTAMP
WHERE metadata_key = 'milestone';
