-- Local collection only. Not a Flyway migration; never apply to smartbis_db.
DO $$ BEGIN
 IF current_database()<>'smartbis_db_copy2' THEN RAISE EXCEPTION 'LOCAL_COLLECTION_DATABASE_REQUIRED'; END IF;
 IF to_regclass('public.tago_policy') IS NOT NULL THEN RAISE EXCEPTION 'POLICY_TABLE_PRESENT'; END IF;
END $$;
ALTER TABLE bus_positions
 ADD COLUMN IF NOT EXISTS requested_at timestamptz,
 ADD COLUMN IF NOT EXISTS responded_at timestamptz,
 ADD COLUMN IF NOT EXISTS call_id uuid,
 ADD COLUMN IF NOT EXISTS call_lane varchar(1);
ALTER TABLE arrival_predictions
 ADD COLUMN IF NOT EXISTS requested_at timestamptz,
 ADD COLUMN IF NOT EXISTS arrprevstationcnt integer,
 ADD COLUMN IF NOT EXISTS visit_id uuid,
 ADD COLUMN IF NOT EXISTS retry_count integer NOT NULL DEFAULT 0,
 ADD COLUMN IF NOT EXISTS is_final boolean NOT NULL DEFAULT false,
 ADD COLUMN IF NOT EXISTS interpolated boolean NOT NULL DEFAULT false,
 ADD COLUMN IF NOT EXISTS failure_reason varchar(100) NOT NULL DEFAULT '';
ALTER TABLE arrival_query_visits ADD COLUMN IF NOT EXISTS visit_id uuid NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX IF NOT EXISTS local_arrival_final_visit ON arrival_predictions(visit_id) WHERE is_final;
CREATE INDEX IF NOT EXISTS local_position_call_id ON bus_positions(call_id);
CREATE TABLE IF NOT EXISTS collection_pause_intervals (
 pause_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 started_at timestamptz NOT NULL,
 ended_at timestamptz,
 reason varchar(20) NOT NULL CHECK(reason IN ('재부팅','업데이트','절전','시스템 적용','기타')),
 exclude_from_analysis boolean NOT NULL DEFAULT true,
 CHECK(ended_at IS NULL OR ended_at>=started_at)
);
CREATE TABLE IF NOT EXISTS collection_process_runs (
 run_id uuid PRIMARY KEY,
 started_at timestamptz NOT NULL,
 last_heartbeat_at timestamptz NOT NULL,
 stopped_at timestamptz,
 stop_reason varchar(20),
 CHECK(stopped_at IS NULL OR stopped_at>=started_at)
);
ALTER TABLE route_call_plan
 ADD COLUMN IF NOT EXISTS plan_revision_id uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
 ADD COLUMN IF NOT EXISTS deactivated_at timestamptz,
 ADD COLUMN IF NOT EXISTS deactivation_reason text;
DO $$ DECLARE old_pk text; BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='route_call_plan'::regclass AND contype='p'
   AND pg_get_constraintdef(oid)='PRIMARY KEY (plan_revision_id, route_id)') THEN
   SELECT conname INTO old_pk FROM pg_constraint WHERE conrelid='route_call_plan'::regclass AND contype='p';
   IF old_pk IS NOT NULL THEN EXECUTE format('ALTER TABLE route_call_plan DROP CONSTRAINT %I',old_pk); END IF;
   ALTER TABLE route_call_plan ADD PRIMARY KEY(plan_revision_id,route_id);
 END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS local_one_active_route_plan ON route_call_plan(route_id) WHERE enabled;
