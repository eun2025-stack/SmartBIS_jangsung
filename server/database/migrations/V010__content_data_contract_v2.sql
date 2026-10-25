BEGIN;

ALTER TABLE contents
    ADD COLUMN IF NOT EXISTS category VARCHAR(20),
    ADD COLUMN IF NOT EXISTS presentation_type VARCHAR(30);

UPDATE contents
   SET category = CASE
           WHEN lower(template) IN ('행사', 'event') THEN 'EVENT'
           ELSE 'NOTICE'
       END
 WHERE category IS NULL;

UPDATE contents
   SET presentation_type = CASE content_type
           WHEN 'VIDEO' THEN 'VIDEO'
           WHEN 'IMAGE' THEN 'IMAGE'
           WHEN 'CARD' THEN 'TEXT'
           ELSE 'TEXT'
       END
 WHERE presentation_type IS NULL;

ALTER TABLE contents
    ALTER COLUMN category SET NOT NULL,
    ALTER COLUMN presentation_type SET NOT NULL;

ALTER TABLE contents
    DROP CONSTRAINT IF EXISTS contents_category_check,
    DROP CONSTRAINT IF EXISTS contents_presentation_type_check;

ALTER TABLE contents
    ADD CONSTRAINT contents_category_check
        CHECK (category IN ('NOTICE', 'EVENT')),
    ADD CONSTRAINT contents_presentation_type_check
        CHECK (presentation_type IN ('POSTER_TEXT', 'TEXT', 'CARD_NEWS', 'IMAGE', 'VIDEO'));

CREATE TABLE IF NOT EXISTS content_assets (
    asset_id BIGSERIAL PRIMARY KEY,
    content_id VARCHAR(100) NOT NULL
        REFERENCES contents(content_id) ON DELETE CASCADE,
    asset_role VARCHAR(20) NOT NULL
        CHECK (asset_role IN ('POSTER', 'IMAGE', 'VIDEO')),
    sequence_no INTEGER NOT NULL CHECK (sequence_no > 0),
    file_name VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    mime_type VARCHAR(150) NOT NULL,
    file_size_bytes BIGINT CHECK (file_size_bytes >= 0),
    sha256 CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT content_assets_content_sequence_unique UNIQUE (content_id, sequence_no)
);

CREATE INDEX IF NOT EXISTS idx_content_assets_content_order
    ON content_assets(content_id, sequence_no);

-- Migrate legacy single-file references without removing the original columns.
INSERT INTO content_assets (
    content_id, asset_role, sequence_no, file_name, file_path,
    mime_type, file_size_bytes, sha256
)
SELECT c.content_id,
       CASE c.content_type WHEN 'VIDEO' THEN 'VIDEO' ELSE 'IMAGE' END,
       1,
       c.target_file_name,
       c.source_path,
       CASE c.content_type
           WHEN 'VIDEO' THEN 'video/mp4'
           WHEN 'IMAGE' THEN CASE
               WHEN lower(c.target_file_name) LIKE '%.png' THEN 'image/png'
               WHEN lower(c.target_file_name) LIKE '%.gif' THEN 'image/gif'
               WHEN lower(c.target_file_name) LIKE '%.webp' THEN 'image/webp'
               ELSE 'image/jpeg'
           END
           ELSE 'application/octet-stream'
       END,
       NULL,
       NULL
  FROM contents c
 WHERE c.target_file_name IS NOT NULL
   AND c.source_path IS NOT NULL
   AND (c.source_path NOT LIKE '/%'
        OR c.source_path LIKE '/app/content-store/%')
   AND NOT EXISTS (
       SELECT 1 FROM content_assets a
        WHERE a.content_id = c.content_id AND a.sequence_no = 1
   );

COMMIT;
