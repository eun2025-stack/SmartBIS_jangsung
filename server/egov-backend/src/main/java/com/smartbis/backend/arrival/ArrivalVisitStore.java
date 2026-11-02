package com.smartbis.backend.arrival;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** Collector-only persistence, not injected into the app API. No TTL-based counter reset. */
public class ArrivalVisitStore {
    private final JdbcTemplate jdbc;
    private boolean metadataEnabled;
    public void setMetadataEnabled(boolean value) { metadataEnabled=value; }
    public ArrivalVisitStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Visit(int attempts,Instant queriedAt,Instant arrivalAt,java.util.UUID id) {
        public Visit(int attempts,Instant queriedAt,Instant arrivalAt) { this(attempts,queriedAt,arrivalAt,java.util.UUID.randomUUID()); }
    }
    public void ensure(String vehicle,String route,String stop,String direction,Instant now) {
        if(metadataEnabled) {
            jdbc.update("""
                INSERT INTO arrival_query_visits(vehicle_number,route_id,stop_id,direction,visit_started_at,next_query_at,visit_id)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(vehicle_number) DO UPDATE SET
                  route_id=EXCLUDED.route_id,stop_id=EXCLUDED.stop_id,direction=EXCLUDED.direction,
                  visit_started_at=EXCLUDED.visit_started_at,query_count=0,queried_at=NULL,
                  estimated_arrival_at=NULL,next_query_at=EXCLUDED.next_query_at,failure_reason='',visit_id=EXCLUDED.visit_id
                WHERE (arrival_query_visits.route_id,arrival_query_visits.stop_id,arrival_query_visits.direction)
                  IS DISTINCT FROM (EXCLUDED.route_id,EXCLUDED.stop_id,EXCLUDED.direction)
                """,vehicle,route,stop,direction,Timestamp.from(now),Timestamp.from(now),java.util.UUID.randomUUID());
            return;
        }
        jdbc.update("""
            INSERT INTO arrival_query_visits(vehicle_number,route_id,stop_id,direction,visit_started_at,next_query_at)
            VALUES(?,?,?,?,?,?) ON CONFLICT(vehicle_number) DO UPDATE SET
              route_id=EXCLUDED.route_id,stop_id=EXCLUDED.stop_id,direction=EXCLUDED.direction,
              visit_started_at=EXCLUDED.visit_started_at,query_count=0,queried_at=NULL,
              estimated_arrival_at=NULL,next_query_at=EXCLUDED.next_query_at,failure_reason=''
            WHERE (arrival_query_visits.route_id,arrival_query_visits.stop_id,arrival_query_visits.direction)
             IS DISTINCT FROM (EXCLUDED.route_id,EXCLUDED.stop_id,EXCLUDED.direction)
            """,vehicle,route,stop,direction,Timestamp.from(now),Timestamp.from(now));
    }
    public Visit read(String vehicle) {
        return jdbc.query("""
            SELECT query_count,queried_at,estimated_arrival_at,to_jsonb(arrival_query_visits)->>'visit_id' visit_id FROM arrival_query_visits WHERE vehicle_number=?
            """,rs->!rs.next()?null:new Visit(rs.getInt(1),instant(rs.getTimestamp(2)),instant(rs.getTimestamp(3)),uuid(rs.getString("visit_id"))),vehicle);
    }
    public Visit claim(String vehicle,String route,String stop,String direction,Instant now) {
        return jdbc.query("""
            UPDATE arrival_query_visits SET query_count=query_count+1,queried_at=?,next_query_at=NULL
            WHERE vehicle_number=? AND route_id=? AND stop_id=? AND direction=?
              AND query_count<1 AND next_query_at<=?
            RETURNING query_count,queried_at,estimated_arrival_at,to_jsonb(arrival_query_visits)->>'visit_id' visit_id
            """,rs->!rs.next()?null:new Visit(rs.getInt(1),instant(rs.getTimestamp(2)),instant(rs.getTimestamp(3)),uuid(rs.getString("visit_id"))),
            Timestamp.from(now),vehicle,route,stop,direction,Timestamp.from(now));
    }
    public void complete(String vehicle,Visit reservation,Integer etaSeconds,ArrivalRulePolicy policy,String failure) {
        complete(vehicle,reservation,etaSeconds,policy,failure,reservation.queriedAt());
    }
    public void complete(String vehicle,Visit reservation,Integer etaSeconds,ArrivalRulePolicy policy,String failure,Instant completedAt) {
        Instant arrival=!failure.isEmpty()?reservation.arrivalAt():etaSeconds==null?null:completedAt.plusSeconds(etaSeconds);
        Instant next=policy.nextQuery(completedAt,etaSeconds,reservation.attempts());
        jdbc.update("""
            UPDATE arrival_query_visits SET estimated_arrival_at=?,next_query_at=?,failure_reason=?,queried_at=?
            WHERE vehicle_number=? AND query_count=? AND queried_at=?
            """,timestamp(arrival),timestamp(next),failure,timestamp(completedAt),vehicle,reservation.attempts(),timestamp(reservation.queriedAt()));
    }
    private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
    private static java.util.UUID uuid(String value) { return value==null?null:java.util.UUID.fromString(value); }
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
}
