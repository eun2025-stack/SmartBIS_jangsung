ALTER TABLE contents
    ADD COLUMN IF NOT EXISTS batch_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_contents_batch_id
    ON contents(batch_id);

