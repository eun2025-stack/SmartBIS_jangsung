CREATE TABLE IF NOT EXISTS display_policies (
    policy_id BIGSERIAL PRIMARY KEY,
    policy_key VARCHAR(100) NOT NULL UNIQUE,
    display_type VARCHAR(30) NOT NULL,
    display_seconds INTEGER,
    repeat_interval_seconds INTEGER,
    priority INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_to TIMESTAMPTZ,
    updated_by VARCHAR(100),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT display_seconds_positive
        CHECK (display_seconds IS NULL OR display_seconds > 0),
    CONSTRAINT repeat_interval_positive
        CHECK (
            repeat_interval_seconds IS NULL
            OR repeat_interval_seconds > 0
        )
);

INSERT INTO display_policies
(policy_key, display_type, display_seconds, repeat_interval_seconds, priority)
VALUES
('ARRIVAL',  'ARRIVAL',  NULL, NULL, 1000),
('DISASTER', 'DISASTER', 15,   60, 900),
('CARD',     'CARD',     10,   60, 200),
('IMAGE',    'IMAGE',    10,   60, 150),
('VIDEO',    'VIDEO',    NULL, 60, 100),
('NOTICE',   'NOTICE',   10,   60, 100)
ON CONFLICT (policy_key) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_display_policies_active
ON display_policies (enabled, effective_from, effective_to);
