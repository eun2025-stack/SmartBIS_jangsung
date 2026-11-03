-- Local copy only; deliberately outside Flyway and not V020.
DO $$ BEGIN
 IF current_database()<>'smartbis_db_copy2' THEN RAISE EXCEPTION 'LOCAL_COLLECTION_DATABASE_REQUIRED'; END IF;
END $$;
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
