BEGIN;

-- Keeps an auditable status for automatic and dashboard-triggered TAGO refreshes.
-- Catalog entities are soft-deactivated when absent from a later complete snapshot;
-- historical route-stop rows are retained.
CREATE TABLE IF NOT EXISTS tago_catalog_sync_runs (
    sync_run_id BIGSERIAL PRIMARY KEY,
    city_code VARCHAR(20) NOT NULL,
    trigger_source VARCHAR(20) NOT NULL
        CHECK (trigger_source IN ('AUTOMATIC', 'MANUAL')),
    status VARCHAR(30) NOT NULL
        CHECK (status IN ('RUNNING', 'SUCCESS', 'SUCCESS_WITH_WARNINGS', 'FAILED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    route_count INTEGER,
    route_stop_count INTEGER,
    unique_stop_count INTEGER,
    inferred_direction_count INTEGER,
    unresolved_direction_count INTEGER,
    inferred_order_count INTEGER,
    stale_route_count INTEGER NOT NULL DEFAULT 0,
    catalog_changed BOOLEAN,
    snapshot_sha256 CHAR(64),
    failure_code VARCHAR(120)
);

CREATE INDEX IF NOT EXISTS idx_tago_catalog_sync_runs_city_started
    ON tago_catalog_sync_runs (city_code, started_at DESC);

ALTER TABLE tago_catalog_sync_runs
    ADD COLUMN IF NOT EXISTS catalog_changed BOOLEAN,
    ADD COLUMN IF NOT EXISTS snapshot_sha256 CHAR(64),
    ADD COLUMN IF NOT EXISTS inferred_order_count INTEGER;

ALTER TABLE tago_catalog_sync_runs
    DROP CONSTRAINT IF EXISTS tago_catalog_sync_runs_status_check;

ALTER TABLE tago_catalog_sync_runs
    ADD CONSTRAINT tago_catalog_sync_runs_status_check
        CHECK (status IN ('RUNNING', 'SUCCESS', 'SUCCESS_WITH_WARNINGS', 'FAILED'));

COMMIT;
