-- Dashboard-managed publication state. Content rows and media files are retained.
BEGIN;

CREATE TABLE IF NOT EXISTS content_publication_controls (
    content_id VARCHAR(100) PRIMARY KEY
        REFERENCES contents(content_id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('PAUSED', 'REMOVED')),
    updated_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_content_publication_controls_status
    ON content_publication_controls (status, updated_at DESC);

COMMIT;
