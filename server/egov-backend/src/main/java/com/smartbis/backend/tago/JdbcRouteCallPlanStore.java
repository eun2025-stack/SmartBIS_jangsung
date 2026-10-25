package com.smartbis.backend.tago;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** No schema creation, seed data, HTTP or configuration writes. */
class JdbcRouteCallPlanStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    JdbcRouteCallPlanStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    List<RouteCallSchedule.Plan> read() {
        return jdbc.query("""
            SELECT route_id,route_group,interval_seconds,enabled,local_windows::text,
                   service_start_seconds,service_end_seconds,backup_interval_seconds,
                   backup_start_seconds,backup_end_seconds,last_attempt_at
            FROM public.route_call_plan ORDER BY route_id
            """, (rs, row) -> {
                var windows = new ArrayList<RouteCallSchedule.Window>();
                try {
                    var json = JSON.readTree(rs.getString("local_windows"));
                    if (!json.isArray()) throw new IllegalArgumentException("LOCATION_PLAN_INVALID_WINDOWS");
                    for (var item : json) windows.add(new RouteCallSchedule.Window(
                        RouteCallSchedule.seconds(item.required("start").asText()),
                        RouteCallSchedule.seconds(item.required("end").asText()),
                        item.required("interval_seconds").asInt()));
                } catch (Exception error) { throw new IllegalArgumentException("LOCATION_PLAN_INVALID_WINDOWS", error); }
                Integer start = (Integer) rs.getObject("service_start_seconds"), end = (Integer) rs.getObject("service_end_seconds");
                var service = start == null || end == null ? null : new RouteCallSchedule.Window(start, end, rs.getInt("interval_seconds"));
                var backup = new RouteCallSchedule.Window(rs.getInt("backup_start_seconds"), rs.getInt("backup_end_seconds"), rs.getInt("backup_interval_seconds"));
                var last = rs.getTimestamp("last_attempt_at");
                return new RouteCallSchedule.Plan(rs.getString("route_id"), rs.getString("route_group"), rs.getBoolean("enabled"),
                    windows, service, backup, last == null ? null : last.toInstant());
            });
    }
    boolean claim(RouteCallSchedule.Slot slot, Instant now) {
        return jdbc.update("""
            UPDATE public.route_call_plan SET last_attempt_at=?
            WHERE route_id=? AND enabled=true AND (last_attempt_at IS NULL OR last_attempt_at < ?)
            """, Timestamp.from(now), slot.routeId(), Timestamp.from(slot.at())) == 1;
    }
    void failure(String route, String reason) {
        jdbc.update("""
            UPDATE public.route_call_plan SET failure_reason=?,last_failure_at=CURRENT_TIMESTAMP
            WHERE route_id=? AND enabled=true
            """, reason, route);
    }
}
