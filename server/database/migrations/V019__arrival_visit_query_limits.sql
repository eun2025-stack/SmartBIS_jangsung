-- No seed/configuration/activation changes. All request reservations persist before HTTP.
BEGIN;
SET LOCAL lock_timeout='10s';
ALTER TABLE public.arrival_predictions ADD COLUMN arrival_direction VARCHAR(10);
CREATE TABLE public.arrival_query_visits (
 vehicle_number VARCHAR(100) PRIMARY KEY,
 route_id VARCHAR(100) NOT NULL,
 stop_id VARCHAR(100) NOT NULL,
 direction VARCHAR(10) NOT NULL,
 visit_started_at TIMESTAMPTZ NOT NULL,
 query_count INTEGER NOT NULL DEFAULT 0 CHECK(query_count BETWEEN 0 AND 2),
 queried_at TIMESTAMPTZ,
 estimated_arrival_at TIMESTAMPTZ,
 next_query_at TIMESTAMPTZ,
 failure_reason VARCHAR(100) NOT NULL DEFAULT ''
);
COMMIT;
