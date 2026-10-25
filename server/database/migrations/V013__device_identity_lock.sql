BEGIN;

-- One immutable Android installation identity per SmartBIS vehicle/device ID.
-- Only a SHA-256 digest is stored; Android identifiers are never persisted in clear text.
CREATE TABLE IF NOT EXISTS android_device_identity_lock (
    vehicle_number VARCHAR(50) PRIMARY KEY,
    identity_sha256 CHAR(64) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
