BEGIN;

-- Links a provisioned MDM terminal ID (BIS_JS_NNN) to the operating bus number.
-- The MDM identity lock remains the physical-device ownership record; this table
-- is the separate, changeable vehicle assignment.
CREATE TABLE IF NOT EXISTS android_device_vehicle_assignments (
    device_number VARCHAR(50) PRIMARY KEY
        REFERENCES android_device_identity_lock(vehicle_number) ON DELETE RESTRICT,
    vehicle_number VARCHAR(100) NOT NULL UNIQUE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
