-- DB-backed schedule metadata only; no collector is enabled by this migration.
BEGIN;
SET LOCAL lock_timeout = '10s';
SELECT pg_advisory_xact_lock(16, 53424953);
ALTER TABLE public.route_call_plan
    ADD COLUMN local_windows JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(local_windows) = 'array'),
    ADD COLUMN service_start_seconds INTEGER CHECK (service_start_seconds BETWEEN 0 AND 86399),
    ADD COLUMN service_end_seconds INTEGER CHECK (service_end_seconds BETWEEN 1 AND 86400),
    ADD COLUMN backup_interval_seconds INTEGER NOT NULL DEFAULT 1800 CHECK (backup_interval_seconds > 0),
    ADD COLUMN backup_start_seconds INTEGER NOT NULL DEFAULT 0 CHECK (backup_start_seconds BETWEEN 0 AND 86399),
    ADD COLUMN backup_end_seconds INTEGER NOT NULL DEFAULT 86400 CHECK (backup_end_seconds BETWEEN 1 AND 86400),
    ADD COLUMN plan_date DATE,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_failure_at TIMESTAMPTZ,
    ADD COLUMN failure_reason VARCHAR(100) NOT NULL DEFAULT '',
    ADD CONSTRAINT route_call_plan_backup_window_check CHECK (backup_start_seconds < backup_end_seconds),
    ADD CONSTRAINT route_call_plan_service_window_check CHECK
        ((service_start_seconds IS NULL AND service_end_seconds IS NULL) OR
         (service_start_seconds IS NOT NULL AND service_end_seconds IS NOT NULL AND service_start_seconds < service_end_seconds));
COMMIT;
