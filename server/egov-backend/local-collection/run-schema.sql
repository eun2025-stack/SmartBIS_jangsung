-- Explicitly approved target only; deliberately outside Flyway and not V020.
-- rev6 sections 3, 10.3: raw order is unique across the whole route.
-- Existing duplicate orders cause an error; never renumber or delete them.
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM bus_route_stops GROUP BY route_id,node_order HAVING count(*)>1) THEN
   RAISE EXCEPTION 'CATALOG_ORDER_DUPLICATE';
 END IF;
END $$;
ALTER TABLE bus_route_stops DROP CONSTRAINT IF EXISTS bus_route_stops_pkey;
ALTER TABLE bus_route_stops ADD CONSTRAINT bus_route_stops_pkey PRIMARY KEY (route_id, node_order);
-- rev6 section 6.2: one final visit result, independent of HTTP retry attempts.
-- Existing count=2 rows require a separate decision; this script never resets them.
ALTER TABLE arrival_query_visits DROP CONSTRAINT IF EXISTS arrival_query_visits_query_count_check;
ALTER TABLE arrival_query_visits ADD CONSTRAINT arrival_query_visits_query_count_check CHECK (query_count BETWEEN 0 AND 1);
CREATE TABLE IF NOT EXISTS collection_runs (
 collection_run_id uuid PRIMARY KEY,
 prepared_at timestamptz NOT NULL,
 started_at timestamptz,
 commit_hash varchar(40) NOT NULL,
 config_hash char(64) NOT NULL,
 archived boolean NOT NULL DEFAULT false
);
INSERT INTO collection_runs(collection_run_id,prepared_at,commit_hash,config_hash,archived)
VALUES('00000000-0000-0000-0000-000000000000','1970-01-01T00:00:00Z','pre_run',repeat('0',64),true)
ON CONFLICT(collection_run_id) DO NOTHING;
-- Defaults label existing rows as archived pre_run without deleting history.
ALTER TABLE bus_positions ADD COLUMN IF NOT EXISTS collection_run_id uuid NOT NULL
 DEFAULT '00000000-0000-0000-0000-000000000000' REFERENCES collection_runs(collection_run_id);
ALTER TABLE arrival_predictions ADD COLUMN IF NOT EXISTS collection_run_id uuid NOT NULL
 DEFAULT '00000000-0000-0000-0000-000000000000' REFERENCES collection_runs(collection_run_id);
ALTER TABLE collection_pause_intervals ADD COLUMN IF NOT EXISTS collection_run_id uuid NOT NULL
 DEFAULT '00000000-0000-0000-0000-000000000000' REFERENCES collection_runs(collection_run_id);
ALTER TABLE collection_process_runs ADD COLUMN IF NOT EXISTS collection_run_id uuid NOT NULL
 DEFAULT '00000000-0000-0000-0000-000000000000' REFERENCES collection_runs(collection_run_id);
CREATE INDEX IF NOT EXISTS local_position_run_time ON bus_positions(collection_run_id,observed_at);
CREATE INDEX IF NOT EXISTS local_arrival_run_time ON arrival_predictions(collection_run_id,observed_at);
CREATE INDEX IF NOT EXISTS local_pause_run_time ON collection_pause_intervals(collection_run_id,started_at);
