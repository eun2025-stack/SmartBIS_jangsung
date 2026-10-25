BEGIN;

CREATE TABLE IF NOT EXISTS android_device_status (
    vehicle_number VARCHAR(50) PRIMARY KEY,
    playback_status VARCHAR(30) NOT NULL
        CHECK (playback_status IN ('CONNECTING', 'PLAYING', 'WAITING', 'ERROR', 'OFFLINE_PLAYBACK')),
    display_type VARCHAR(30),
    content_id VARCHAR(100),
    content_title VARCHAR(300),
    app_version VARCHAR(40),
    error_message VARCHAR(1000),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_android_device_status_last_seen
    ON android_device_status (last_seen_at DESC);

COMMIT;
