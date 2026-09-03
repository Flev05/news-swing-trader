CREATE TABLE platform_metadata (
    metadata_key VARCHAR(128) PRIMARY KEY,
    metadata_value VARCHAR(256) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_metadata (metadata_key, metadata_value)
VALUES ('milestone', '0');
