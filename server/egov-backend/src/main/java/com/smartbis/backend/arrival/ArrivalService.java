package com.smartbis.backend.arrival;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ArrivalService {
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final JdbcTemplate jdbcTemplate;

    public ArrivalService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ArrivalPrediction predict(String vehicleNumber) {
        Map<String, Object> position = jdbcTemplate.query("""
                SELECT vehicle_number, route_id, node_id, node_order,
                       latitude, longitude
                  FROM bus_positions
                 WHERE vehicle_number = ?
                 ORDER BY observed_at DESC
                 LIMIT 1
                """, rs -> {
            if (!rs.next()) {
                return null;
            }

            return Map.of(
                    "vehicleNumber", rs.getString("vehicle_number"),
                    "routeId", rs.getString("route_id"),
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
                SELECT current_stop.node_name AS current_name,
                       next_stop.node_name AS next_name,
                       next_stop.latitude,
                       next_stop.longitude
                  FROM bus_stops current_stop
                  JOIN bus_route_stops current_route_stop
                    ON current_route_stop.node_id = current_stop.node_id
                  JOIN bus_route_stops next_route_stop
                    ON next_route_stop.route_id = current_route_stop.route_id
                   AND next_route_stop.direction = current_route_stop.direction
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
                    "currentName", rs.getString("current_name"),
                    "nextName", rs.getString("next_name"),
                    "latitude", rs.getBigDecimal("latitude"),
                    "longitude", rs.getBigDecimal("longitude")
            );
        },
        position.get("routeId"),
        position.get("nodeId"),
        position.get("nodeOrder"));

        if (stop == null) {
            throw new ResponseStatusException(
                    NOT_FOUND,
                    "다음 정류소를 찾을 수 없습니다."
            );
        }

        double distance = distanceMeters(
                ((BigDecimal) position.get("latitude")).doubleValue(),
                ((BigDecimal) position.get("longitude")).doubleValue(),
                ((BigDecimal) stop.get("latitude")).doubleValue(),
                ((BigDecimal) stop.get("longitude")).doubleValue()
        );

        boolean within100m = distance <= 100.0;
        BigDecimal roundedDistance = BigDecimal.valueOf(distance)
                .setScale(2, RoundingMode.HALF_UP);

        jdbcTemplate.update("""
                INSERT INTO arrival_predictions (
                    vehicle_number,
                    route_id,
                    current_stop_text,
                    next_stop_text,
                    distance_meters,
                    within_100m,
                    observed_at,
                    expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '2 minutes')
                """,
                vehicleNumber,
                position.get("routeId"),
                stop.get("currentName"),
                stop.get("nextName"),
                roundedDistance,
                within100m
        );

        return new ArrivalPrediction(
                vehicleNumber,
                String.valueOf(position.get("routeId")),
                String.valueOf(stop.get("currentName")),
                String.valueOf(stop.get("nextName")),
                roundedDistance,
                within100m
        );
    }

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
