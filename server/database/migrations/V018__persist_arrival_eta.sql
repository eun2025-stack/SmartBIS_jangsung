-- Arrival collector persistence only. No seed data, activation or location-plan changes.
BEGIN;
SET LOCAL lock_timeout='10s';
ALTER TABLE public.arrival_predictions
 ADD COLUMN distance_from_current_stop_meters NUMERIC(10,2),
 ADD COLUMN eta_queried_at TIMESTAMPTZ,
 ADD COLUMN estimated_arrival_at TIMESTAMPTZ;
CREATE INDEX idx_arrival_route_stop_eta
 ON public.arrival_predictions(route_id,next_node_id,eta_queried_at DESC)
 WHERE eta_queried_at IS NOT NULL;
COMMIT;
