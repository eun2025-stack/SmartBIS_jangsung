package com.smartbis.backend.arrival;

import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

/** Read-only learning: same trip/day, increasing orders, <=5 missed stops, <=30 minutes.
 * Linear intermediate crossings are used only inside this learning query, never as operational positions.
 */
@Service
public class ArrivalTravelEstimator {
    private final JdbcTemplate jdbc;
    @Value("${tago.arrival.default-minutes-per-stop:1.2}") private double placeholderMinutes=1.2;
    public ArrivalTravelEstimator(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public int estimateSeconds(String route,int from,int to,Instant now) {
        int steps=Math.max(1,to-from);
        Double learned=jdbc.queryForObject("""
            WITH sequence AS (
              SELECT vehicle_number,route_id,node_order,observed_at,
                LAG(route_id) OVER w previous_route,LAG(node_order) OVER w previous_order,
                LAG(observed_at) OVER w previous_at
              FROM bus_positions WHERE source IN ('TAGO','TAGO_OBSERVATION')
                AND COALESCE(raw_payload->>'interpolated','false')<>'true'
              WINDOW w AS (PARTITION BY vehicle_number ORDER BY observed_at,position_id)
            ), valid AS (
              SELECT *,EXTRACT(EPOCH FROM observed_at-previous_at)/NULLIF(node_order-previous_order,0) seconds
              FROM sequence WHERE route_id=? AND previous_route=route_id
                AND previous_order IS NOT NULL AND node_order>previous_order AND node_order-previous_order<=6
                AND observed_at>previous_at AND observed_at-previous_at<=INTERVAL '30 minutes'
                AND (observed_at AT TIME ZONE 'Asia/Seoul')::date=(previous_at AT TIME ZONE 'Asia/Seoul')::date
                AND EXTRACT(HOUR FROM previous_at AT TIME ZONE 'Asia/Seoul')=?
            ), edges AS (
              SELECT edge,percentile_cont(0.5) WITHIN GROUP(ORDER BY seconds) median
              FROM valid CROSS JOIN LATERAL generate_series(previous_order,node_order-1) edge
              WHERE edge>=? AND edge<? GROUP BY edge
            )
            SELECT CASE WHEN COUNT(*)=? THEN SUM(median) END FROM edges
            """,Double.class,route,now.atZone(ZoneId.of("Asia/Seoul")).getHour(),from,to,steps);
        return learned!=null && learned>=0 && Double.isFinite(learned) ? (int)Math.ceil(learned)
            : (int)Math.ceil(steps*placeholderMinutes*60);
    }
}
