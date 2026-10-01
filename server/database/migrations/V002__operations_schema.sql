BEGIN;

CREATE TABLE IF NOT EXISTS regions (
    region_id bigserial PRIMARY KEY,
    region_code varchar(50) UNIQUE,
    region_name varchar(100) NOT NULL UNIQUE,
    parent_region_name varchar(100),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bus_stops (
    node_id varchar(100) PRIMARY KEY,
    node_name varchar(200) NOT NULL,
    city_code varchar(20) NOT NULL,
    latitude numeric(10,7),
    longitude numeric(10,7),
    region_id bigint REFERENCES regions(region_id),
    region_name varchar(100),
    active boolean NOT NULL DEFAULT true,
    source varchar(30) NOT NULL DEFAULT 'TAGO',
    last_synced_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bus_stops_region ON bus_stops(region_id);
CREATE INDEX IF NOT EXISTS idx_bus_stops_city ON bus_stops(city_code);
CREATE INDEX IF NOT EXISTS idx_bus_stops_location ON bus_stops(latitude, longitude);

CREATE TABLE IF NOT EXISTS bus_routes (
    route_id varchar(100) PRIMARY KEY,
    route_name varchar(100) NOT NULL,
    city_code varchar(20) NOT NULL,
    route_type varchar(50),
    direction varchar(50),
    active boolean NOT NULL DEFAULT true,
    source varchar(30) NOT NULL DEFAULT 'TAGO',
    last_synced_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bus_routes_city ON bus_routes(city_code);

CREATE TABLE IF NOT EXISTS bus_route_stops (
    route_id varchar(100) NOT NULL REFERENCES bus_routes(route_id),
    node_id varchar(100) NOT NULL REFERENCES bus_stops(node_id),
    node_order integer NOT NULL,
    direction varchar(50),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (route_id, node_id, direction),
    UNIQUE (route_id, direction, node_order)
);

CREATE INDEX IF NOT EXISTS idx_route_stops_node ON bus_route_stops(node_id);

CREATE TABLE IF NOT EXISTS bus_vehicles (
    vehicle_id bigserial PRIMARY KEY,
    vehicle_number varchar(100) NOT NULL UNIQUE,
    city_code varchar(20) NOT NULL,
    route_id varchar(100) REFERENCES bus_routes(route_id),
    active boolean NOT NULL DEFAULT true,
    last_seen_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bus_vehicles_route ON bus_vehicles(route_id);

CREATE TABLE IF NOT EXISTS bus_positions (
    position_id bigserial PRIMARY KEY,
    vehicle_id bigint REFERENCES bus_vehicles(vehicle_id),
    vehicle_number varchar(100) NOT NULL,
    route_id varchar(100),
    node_id varchar(100),
    node_order integer,
    latitude numeric(10,7),
    longitude numeric(10,7),
    observed_at timestamptz NOT NULL,
    source varchar(30) NOT NULL DEFAULT 'TAGO',
    raw_payload jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bus_positions_vehicle_time
    ON bus_positions(vehicle_number, observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_bus_positions_route_time
    ON bus_positions(route_id, observed_at DESC);

CREATE TABLE IF NOT EXISTS arrival_predictions (
    prediction_id bigserial PRIMARY KEY,
    vehicle_number varchar(100) NOT NULL,
    route_id varchar(100),
    current_node_id varchar(100) REFERENCES bus_stops(node_id),
    next_node_id varchar(100) REFERENCES bus_stops(node_id),
    distance_meters numeric(10,2),
    within_100m boolean NOT NULL DEFAULT false,
    current_stop_text varchar(200),
    next_stop_text varchar(200),
    observed_at timestamptz NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_arrival_vehicle_time
    ON arrival_predictions(vehicle_number, observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_arrival_active
    ON arrival_predictions(within_100m, expires_at);

CREATE TABLE IF NOT EXISTS content_requests (
    request_id bigserial PRIMARY KEY,
    content_id varchar(100) NOT NULL REFERENCES contents(content_id),
    requester varchar(100),
    request_reason text,
    status varchar(30) NOT NULL DEFAULT 'DRAFT',
    submitted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_content_requests_status
    ON content_requests(status, updated_at DESC);

CREATE TABLE IF NOT EXISTS content_approvals (
    approval_id bigserial PRIMARY KEY,
    request_id bigint NOT NULL REFERENCES content_requests(request_id),
    decision varchar(30) NOT NULL,
    approver varchar(100) NOT NULL,
    comment text,
    decided_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_content_approvals_request
    ON content_approvals(request_id, decided_at DESC);

CREATE TABLE IF NOT EXISTS content_schedules (
    schedule_id bigserial PRIMARY KEY,
    content_id varchar(100) NOT NULL REFERENCES contents(content_id),
    priority integer NOT NULL DEFAULT 0,
    repeat_interval_seconds integer,
    min_display_seconds integer NOT NULL DEFAULT 10,
    force_display boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_content_schedules_active
    ON content_schedules(active, force_display, priority DESC);

CREATE TABLE IF NOT EXISTS disaster_messages (
    disaster_id varchar(150) PRIMARY KEY,
    message_text text NOT NULL,
    disaster_type varchar(50),
    issued_at timestamptz NOT NULL,
    expires_at timestamptz,
    target_regions jsonb,
    severity integer NOT NULL DEFAULT 1,
    status varchar(30) NOT NULL DEFAULT 'ACTIVE',
    source varchar(50) NOT NULL DEFAULT 'SAMPLE',
    raw_payload jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_disaster_active
    ON disaster_messages(status, issued_at DESC, expires_at);

CREATE TABLE IF NOT EXISTS display_queue (
    display_id bigserial PRIMARY KEY,
    vehicle_number varchar(100) NOT NULL,
    region_name varchar(100),
    display_type varchar(30) NOT NULL,
    priority integer NOT NULL,
    content_id varchar(100) REFERENCES contents(content_id),
    disaster_id varchar(150) REFERENCES disaster_messages(disaster_id),
    prediction_id bigint REFERENCES arrival_predictions(prediction_id),
    display_text text,
    template varchar(100) NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    status varchar(30) NOT NULL DEFAULT 'READY',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_display_queue_vehicle
    ON display_queue(vehicle_number, status, priority DESC, valid_from);

CREATE TABLE IF NOT EXISTS content_ingest_batches (
    batch_id varchar(100) PRIMARY KEY,
    source_path text NOT NULL,
    status varchar(30) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    validated_at timestamptz,
    retry_count integer NOT NULL DEFAULT 0,
    last_error text,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ingest_batches_status
    ON content_ingest_batches(status, updated_at DESC);

CREATE TABLE IF NOT EXISTS content_validation_errors (
    error_id bigserial PRIMARY KEY,
    batch_id varchar(100) NOT NULL REFERENCES content_ingest_batches(batch_id),
    content_id varchar(100),
    error_code varchar(100) NOT NULL,
    error_message text NOT NULL,
    error_detail jsonb,
    status varchar(30) NOT NULL DEFAULT 'OPEN',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_validation_errors_recent
    ON content_validation_errors(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_validation_errors_status
    ON content_validation_errors(status, created_at DESC);

CREATE OR REPLACE VIEW dashboard_error_summary AS
SELECT
    COUNT(*) FILTER (WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '3 days') AS recent_3day_count,
    COUNT(*) FILTER (WHERE status IN ('OPEN', 'WAITING_PROVIDER', 'RETRY_REQUESTED')) AS open_count,
    COUNT(*) FILTER (WHERE status = 'RESOLVED') AS resolved_count
FROM content_validation_errors;

COMMIT;

