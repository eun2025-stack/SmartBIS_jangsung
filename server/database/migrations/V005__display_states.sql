CREATE TABLE IF NOT EXISTS display_states (
    vehicle_number VARCHAR(50) PRIMARY KEY,
    cycle_no BIGINT NOT NULL DEFAULT 0,
    last_display_key VARCHAR(150),
    last_display_type VARCHAR(30),
    last_displayed_at TIMESTAMPTZ,
    disaster_last_displayed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
