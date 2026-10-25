-- A route may visit the same physical stop more than once (loops/repeated branches).
-- Keep each occurrence by sequence position; only duplicate positions are invalid.
BEGIN;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM bus_route_stops
         GROUP BY route_id, direction, node_order
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot change bus_route_stops key: duplicate route/direction/order rows exist';
    END IF;
END $$;

ALTER TABLE bus_route_stops
    DROP CONSTRAINT bus_route_stops_pkey;

ALTER TABLE bus_route_stops
    DROP CONSTRAINT IF EXISTS bus_route_stops_route_id_direction_node_order_key;

ALTER TABLE bus_route_stops
    ADD CONSTRAINT bus_route_stops_pkey PRIMARY KEY (route_id, direction, node_order);

COMMIT;
