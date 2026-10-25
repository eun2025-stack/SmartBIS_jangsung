-- Additive operational scaffold only. No seed data, no changes to existing relations.
-- Existing names are treated as a collision, not silently reused.
BEGIN;
-- Serialize this preflight with another V015 executor, without locking application tables.
SELECT pg_advisory_xact_lock(15, 53424953);
DO $$
BEGIN
    IF to_regclass('public.vehicle_route_pattern_daily') IS NOT NULL
       OR to_regclass('public.route_call_plan') IS NOT NULL
       OR to_regclass('public.guard_usage_daily') IS NOT NULL THEN
        RAISE EXCEPTION 'V015 table-name collision; stop and inspect existing relations';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS public.vehicle_route_pattern_daily (
    vehicle_number VARCHAR(100) NOT NULL,
    service_date DATE NOT NULL,
    day_type VARCHAR(10) NOT NULL CHECK (day_type IN ('평일', '휴일')),
    pattern_code VARCHAR(30) NOT NULL,
    pattern_family VARCHAR(10) NOT NULL CHECK (pattern_family IN ('ud','ld','js','gj','hjs','hgj')),
    matched_count INTEGER NOT NULL DEFAULT 0 CHECK (matched_count >= 0),
    source VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (vehicle_number, service_date)
);
CREATE TABLE IF NOT EXISTS public.route_call_plan (
    route_id VARCHAR(100) PRIMARY KEY,
    route_group VARCHAR(10) NOT NULL CHECK (route_group IN ('관내','100번')),
    interval_seconds INTEGER NOT NULL CHECK (interval_seconds > 0),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS public.guard_usage_daily (
    api_name VARCHAR(50) NOT NULL,
    usage_date DATE NOT NULL,
    call_count INTEGER NOT NULL DEFAULT 0 CHECK (call_count >= 0),
    limit_count INTEGER NOT NULL CHECK (limit_count > 0),
    blocked_at TIMESTAMPTZ,
    PRIMARY KEY (api_name, usage_date)
);
COMMIT;
