-- Audit of decisions only. No plan/configuration/observation updates.
BEGIN;
SET LOCAL lock_timeout='10s';
SELECT pg_advisory_xact_lock(17,53424953);
ALTER TABLE public.vehicle_route_pattern_daily
 ALTER COLUMN pattern_code DROP NOT NULL,
 ALTER COLUMN pattern_family DROP NOT NULL,
 ADD COLUMN decision_status VARCHAR(30) NOT NULL DEFAULT 'LEGACY',
 ADD COLUMN evidence_date_count INTEGER NOT NULL DEFAULT 0 CHECK(evidence_date_count>=0),
 ADD COLUMN decision_details JSONB NOT NULL DEFAULT '{}'::jsonb,
 ADD COLUMN evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
COMMIT;
