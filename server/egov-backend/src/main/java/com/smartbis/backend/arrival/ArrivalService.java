package com.smartbis.backend.arrival;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ArrivalService {
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final JdbcTemplate jdbcTemplate;
    @Value("${tago.arrival.soon-seconds:30}") private long soonSeconds=30;
    @Value("${tago.arrival.passage-grace-seconds:60}") private long passageGraceSeconds=60;

    public ArrivalService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** App API: SELECT only. Never computes, stores or fetches an external prediction. */
    public ArrivalPrediction predict(String vehicleNumber) {
        return predict(vehicleNumber,null);
    }

    /** Optional read-only cursor after the client has briefly displayed a confirmed passage. */
    public ArrivalPrediction predict(String vehicleNumber,String afterPassedNodeId) {
        ArrivalPrediction saved = jdbcTemplate.query("""
                WITH saved AS (
                    SELECT * FROM arrival_predictions
                    WHERE vehicle_number=? AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)
                      AND (CAST(? AS varchar) IS NULL OR next_node_id IS DISTINCT FROM CAST(? AS varchar))
                ), enriched AS (
                    SELECT a.*,
                       (SELECT MIN(p.observed_at) FROM bus_positions p
                        JOIN bus_route_stops s ON s.route_id=a.route_id AND s.node_id=a.next_node_id
                          AND s.direction=a.arrival_direction
                        WHERE p.vehicle_number=a.vehicle_number AND p.route_id=a.route_id
                          AND p.direction=a.arrival_direction AND p.node_order>s.node_order
                          AND p.observed_at>=a.eta_queried_at AND p.observed_at<=CURRENT_TIMESTAMP
                          AND COALESCE(p.raw_payload->>'interpolated','false') NOT IN ('true','1')
                       ) AS passed_at
                    FROM saved a
                )
                SELECT vehicle_number,route_id,current_stop_text,next_stop_text,
                       current_node_id,next_node_id,distance_meters,within_100m,
                       distance_from_current_stop_meters,
                       CASE WHEN estimated_arrival_at >= CURRENT_TIMESTAMP THEN
                         CEIL(EXTRACT(EPOCH FROM (estimated_arrival_at-CURRENT_TIMESTAMP))/60)::integer
                       END AS eta_minutes,estimated_arrival_at,CURRENT_TIMESTAMP AS database_now,
                       EXTRACT(EPOCH FROM (estimated_arrival_at-CURRENT_TIMESTAMP))::bigint AS eta_seconds,
                       passed_at
                FROM enriched
                ORDER BY CASE WHEN passed_at>=CURRENT_TIMESTAMP-make_interval(secs=>?) THEN 0 ELSE 1 END,
                         observed_at DESC,prediction_id DESC LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;
            return new ArrivalPrediction(rs.getString("vehicle_number"),rs.getString("route_id"),
                    rs.getString("current_stop_text"),rs.getString("next_stop_text"),
                    rs.getString("current_node_id"),rs.getString("next_node_id"),
                    rs.getBigDecimal("distance_meters"),rs.getBoolean("within_100m"),
                    rs.getBigDecimal("distance_from_current_stop_meters"),(Integer) rs.getObject("eta_minutes"),
                    (Long)rs.getObject("eta_seconds"),new ArrivalRulePolicy(60,180,60,soonSeconds,.5,30,passageGraceSeconds).display(
                        instant(rs.getTimestamp("estimated_arrival_at")),instant(rs.getTimestamp("passed_at")),
                        rs.getTimestamp("database_now")==null?Instant.now():rs.getTimestamp("database_now").toInstant()),
                    instant(rs.getTimestamp("estimated_arrival_at")));
        }, vehicleNumber,afterPassedNodeId,afterPassedNodeId,passageGraceSeconds);
        if (saved == null) throw new ResponseStatusException(NOT_FOUND, "정보 없음");
        return saved;
    }

    /** Collector-only refresh state. Survives process restart; no memory ETA cache. */
    public EtaState savedEta(String routeId, String stopId) {
        return jdbcTemplate.query("""
                SELECT eta_queried_at,estimated_arrival_at FROM arrival_predictions
                WHERE route_id=? AND next_node_id=? AND eta_queried_at IS NOT NULL
                ORDER BY eta_queried_at DESC,prediction_id DESC LIMIT 1
                """, rs -> !rs.next() ? null : new EtaState(
                    rs.getTimestamp("eta_queried_at").toInstant(),
                    rs.getTimestamp("estimated_arrival_at") == null ? null
                        : rs.getTimestamp("estimated_arrival_at").toInstant()), routeId, stopId);
    }

    public record EtaState(Instant queriedAt, Instant arrivalAt) { }

    /** Collector-only: detect a new trip/next-stop transition from persisted geometry. */
    public boolean needsInitialQuery(String vehicleNumber,String routeId,String stopId) {
        Boolean needed=jdbcTemplate.query("""
                SELECT route_id,next_node_id,observed_at<CURRENT_TIMESTAMP-INTERVAL '2 minutes' AS stale
                FROM arrival_predictions WHERE vehicle_number=?
                ORDER BY observed_at DESC,prediction_id DESC LIMIT 1
                """, rs -> !rs.next() || rs.getBoolean("stale")
                    || !routeId.equals(rs.getString("route_id")) || !stopId.equals(rs.getString("next_node_id")), vehicleNumber);
        return Boolean.TRUE.equals(needed);
    }

    /** Called only by the arrival collector; retains the original prediction INSERT. */
    public void collectPrediction(String vehicleNumber,String expectedRoute,String expectedStop, Instant queriedAt, Instant arrivalAt) {
        Map<String, Object> position = jdbcTemplate.query("""
                SELECT vehicle_number, route_id, direction, node_id, node_order,
                       latitude, longitude
                  FROM bus_positions
                 WHERE vehicle_number = ?
                   AND direction IN ('UP', 'DOWN')
                   AND observed_at >= CURRENT_TIMESTAMP - INTERVAL '2 minutes'
                 ORDER BY observed_at DESC
                 LIMIT 1
                """, rs -> {
            if (!rs.next()) {
                return null;
            }

            return Map.of(
                    "vehicleNumber", rs.getString("vehicle_number"),
                    "routeId", rs.getString("route_id"),
                    "direction", rs.getString("direction"),
                    "nodeId", rs.getString("node_id"),
                    "nodeOrder", rs.getInt("node_order"),
                    "latitude", rs.getBigDecimal("latitude"),
                    "longitude", rs.getBigDecimal("longitude")
            );
        }, vehicleNumber);

        if (position == null) {
            throw new ResponseStatusException(
                    NOT_FOUND,
                    "차량 위치정보를 찾을 수 없습니다."
            );
        }

        Map<String, Object> stop = jdbcTemplate.query("""
                SELECT current_stop.node_id AS current_node_id,
                       next_stop.node_id AS next_node_id,
                       current_stop.node_name AS current_name,
                       next_stop.node_name AS next_name,
                       current_stop.latitude AS current_latitude,
                       current_stop.longitude AS current_longitude,
                       next_stop.latitude,
                       next_stop.longitude
                  FROM bus_stops current_stop
                  JOIN bus_route_stops current_route_stop
                    ON current_route_stop.node_id = current_stop.node_id
                  JOIN bus_route_stops next_route_stop
                    ON next_route_stop.route_id = current_route_stop.route_id
                   AND next_route_stop.direction = current_route_stop.direction
                   AND next_route_stop.direction = ?
                   AND next_route_stop.node_order = current_route_stop.node_order + 1
                  JOIN bus_stops next_stop
                    ON next_stop.node_id = next_route_stop.node_id
                 WHERE current_route_stop.route_id = ?
                   AND current_route_stop.node_id = ?
                   AND current_route_stop.node_order = ?
                LIMIT 1
                """, rs -> {
            if (!rs.next()) {
                return null;
            }

            return Map.of(
                    "currentNodeId", rs.getString("current_node_id"),
                    "nextNodeId", rs.getString("next_node_id"),
                    "currentName", rs.getString("current_name"),
                    "nextName", rs.getString("next_name"),
                    "currentLatitude", rs.getBigDecimal("current_latitude"),
                    "currentLongitude", rs.getBigDecimal("current_longitude"),
                    "latitude", rs.getBigDecimal("latitude"),
                    "longitude", rs.getBigDecimal("longitude")
            );
        },
        position.get("direction"),
        position.get("routeId"),
        position.get("nodeId"),
        position.get("nodeOrder"));

        if (stop == null) {
            throw new ResponseStatusException(
                    NOT_FOUND,
                    "다음 정류소를 찾을 수 없습니다."
            );
        }

        // A position may change after target selection. Never attach an old target's ETA to a new stop.
        if (!expectedRoute.equals(position.get("routeId")) || !expectedStop.equals(stop.get("nextNodeId"))) return;

        double distance = distanceMeters(
                ((BigDecimal) position.get("latitude")).doubleValue(),
                ((BigDecimal) position.get("longitude")).doubleValue(),
                ((BigDecimal) stop.get("latitude")).doubleValue(),
                ((BigDecimal) stop.get("longitude")).doubleValue()
        );
        double distanceFromCurrentStop = distanceMeters(
                ((BigDecimal) position.get("latitude")).doubleValue(),
                ((BigDecimal) position.get("longitude")).doubleValue(),
                ((BigDecimal) stop.get("currentLatitude")).doubleValue(),
                ((BigDecimal) stop.get("currentLongitude")).doubleValue()
        );

        boolean within100m = distance <= 100.0;
        BigDecimal roundedDistance = BigDecimal.valueOf(distance)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal roundedDistanceFromCurrentStop = BigDecimal.valueOf(distanceFromCurrentStop)
                .setScale(2, RoundingMode.HALF_UP);

        jdbcTemplate.update("""
                INSERT INTO arrival_predictions (
                    vehicle_number,
                    route_id,
                    current_node_id,
                    next_node_id,
                    current_stop_text,
                    next_stop_text,
                    distance_meters,
                    within_100m,
                    observed_at,
                    expires_at,
                    distance_from_current_stop_meters,
                    eta_queried_at,
                    estimated_arrival_at,
                    arrival_direction
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '2 minutes', ?, ?, ?, ?)
                """,
                vehicleNumber,
                position.get("routeId"),
                stop.get("currentNodeId"),
                stop.get("nextNodeId"),
                stop.get("currentName"),
                stop.get("nextName"),
                roundedDistance,
                within100m,
                roundedDistanceFromCurrentStop,
                queriedAt == null ? null : Timestamp.from(queriedAt),
                arrivalAt == null ? null : Timestamp.from(arrivalAt),
                position.get("direction")
        );
    }

    private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }

    private double distanceMeters(
            double lat1,
            double lon1,
            double lat2,
            double lon2
    ) {
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2)
                * Math.sin(lonDistance / 2);

        return EARTH_RADIUS_METERS
                * 2
                * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
