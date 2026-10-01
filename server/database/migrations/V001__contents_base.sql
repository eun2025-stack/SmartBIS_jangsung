CREATE TABLE IF NOT EXISTS contents (
    content_id VARCHAR(100) PRIMARY KEY,
    content_type VARCHAR(20) NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    target_file_name VARCHAR(255),
    display_start_date DATE,
    display_end_date DATE,
    target_regions JSONB,
    template VARCHAR(100) NOT NULL,
    source_path TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT contents_type_check
        CHECK (content_type IN ('VIDEO', 'IMAGE', 'CARD')),

    CONSTRAINT contents_date_check
        CHECK (
            display_start_date IS NULL
            OR display_end_date IS NULL
            OR display_end_date >= display_start_date
        )
);
