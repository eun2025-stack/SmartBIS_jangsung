-- Keep the TAGO UP/DOWN path selected for each vehicle position so arrival
-- prediction never picks an arbitrary branch when a stop occurs in both paths.
ALTER TABLE bus_positions
    ADD COLUMN IF NOT EXISTS direction varchar(20);

CREATE INDEX IF NOT EXISTS idx_bus_positions_vehicle_direction_time
    ON bus_positions(vehicle_number, direction, observed_at DESC);
