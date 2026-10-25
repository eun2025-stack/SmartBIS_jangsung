package com.smartbis.backend.display;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class DisplayService {

    static final String UNRESTRICTED_REGION_SQL =
            "(c.target_regions IS NULL OR c.target_regions = 'null'::jsonb)";
    static final String DISASTER_REGION_FILTER_SQL = """
            (d.target_regions IS NULL OR d.target_regions = 'null'::jsonb
             OR (CAST(? AS varchar) IS NOT NULL
                 AND jsonb_exists(d.target_regions, CAST(? AS text))))
            """;

    private final JdbcTemplate jdbcTemplate;

    public DisplayService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DisplayItem next(String vehicleNumber) {
        Map<String, Object> state = findState(vehicleNumber);
        Map<String, Object> arrival = findArrival(vehicleNumber);

        if (arrival == null) {
            clearExpiredArrivalState(vehicleNumber);
        }

        List<DisplayCandidate> candidates = new ArrayList<>();
        Map<String, Object> arrivalPolicy = policy("ARRIVAL");
        if (arrival != null && policyEnabled(arrivalPolicy)
                && shouldDisplayArrival(state, arrival, arrivalPolicy)) {
            candidates.add(new DisplayCandidate(arrivalItem(arrival, arrivalPolicy),
                    integerOrDefault(arrivalPolicy, "priority", 0), 3, false,
                    "ARRIVAL", arrivalKey(arrival)));
        }

        Map<String, Object> disasterPolicy = policy("DISASTER");
        if (policyEnabled(disasterPolicy) && shouldDisplayDisaster(state, disasterPolicy)) {
            Map<String, Object> disaster = findDisaster(vehicleNumber);
            if (disaster != null) {
                candidates.add(new DisplayCandidate(disasterItem(disaster, disasterPolicy),
                        integerOrDefault(disasterPolicy, "priority", 0), 2, true,
                        "DISASTER", "DISASTER:" + disaster.get("message")));
            }
        }

        Map<String, Object> content = findNextContent(state, vehicleNumber);
        if (content != null) {
            String contentType = String.valueOf(content.get("contentType"));
            Map<String, Object> contentPolicy = contentPolicy(content);
            int priority = integerOrDefault(content, "forced", 0) == 1
                    ? Integer.MAX_VALUE : integerOrDefault(contentPolicy, "priority", 0);
            DisplayItem item = contentItem(content, contentPolicy);
            candidates.add(new DisplayCandidate(item, priority, 1, false, contentType,
                    String.valueOf(content.get("contentId"))));
        }

        DisplayCandidate selected = selectCandidate(candidates);
        if (selected == null) {
            throw new ResponseStatusException(NOT_FOUND, "표출 가능한 항목이 없습니다.");
        }
        saveState(vehicleNumber, selected.displayType(), selected.displayKey(), selected.disaster());
        return selected.item();
    }

    static DisplayCandidate selectCandidate(List<DisplayCandidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparingInt(DisplayCandidate::priority)
                        .thenComparingInt(DisplayCandidate::tieBreakRank))
                .orElse(null);
    }

    record DisplayCandidate(DisplayItem item, int priority, int tieBreakRank,
                            boolean disaster, String displayType, String displayKey) { }

    private Map<String, Object> contentPolicy(Map<String, Object> content) {
        Map<String, Object> result = new HashMap<>();
        result.put("priority", content.get("policyPriority"));
        result.put("displaySeconds", content.get("policyDisplaySeconds"));
        result.put("repeatIntervalSeconds", content.get("policyRepeatIntervalSeconds"));
        result.put("enabled", true);
        return result;
    }

    private boolean shouldDisplayArrival(
            Map<String, Object> state,
            Map<String, Object> arrival,
            Map<String, Object> policy
    ) {
        if (state == null || state.get("arrivalLastDisplayKey") == null
                || !arrivalKey(arrival).equals(state.get("arrivalLastDisplayKey"))) {
            return true;
        }

        Timestamp last = (Timestamp) state.get("arrivalLastDisplayedAt");
        Integer interval = integer(policy, "repeatIntervalSeconds");
        if (last == null || interval == null) {
            return false;
        }
        long elapsedSeconds = (System.currentTimeMillis() - last.getTime()) / 1000;
        return elapsedSeconds >= interval;
    }

    private String arrivalKey(Map<String, Object> arrival) {
        return "ARRIVAL:" + (arrival.get("nextNodeId") == null
                ? "UNKNOWN" : arrival.get("nextNodeId"));
    }

    private void clearExpiredArrivalState(String vehicleNumber) {
        jdbcTemplate.update("""
                UPDATE display_states
                   SET arrival_last_display_key = NULL,
                       arrival_last_displayed_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE vehicle_number = ?
                   AND arrival_last_display_key IS NOT NULL
                """, vehicleNumber);
    }

    private Map<String, Object> findArrival(String vehicleNumber) {
        return jdbcTemplate.query("""
                SELECT route_id,
                       current_node_id,
                       next_node_id,
                       current_stop_text,
                       next_stop_text,
                       distance_meters,
                       expires_at
                  FROM arrival_predictions
                 WHERE vehicle_number = ?
                   AND within_100m = true
                   AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                 ORDER BY observed_at DESC
                 LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;

            Map<String, Object> row = new HashMap<>();
            row.put("routeId", rs.getString("route_id"));
            row.put("currentNodeId", rs.getString("current_node_id"));
            row.put("nextNodeId", rs.getString("next_node_id"));
            row.put("currentStop", rs.getString("current_stop_text"));
            row.put("nextStop", rs.getString("next_stop_text"));
            row.put("distanceMeters", rs.getBigDecimal("distance_meters"));
            row.put("expiresAt", rs.getTimestamp("expires_at"));
            return row;
        }, vehicleNumber);
    }

    private Map<String, Object> findDisaster(String vehicleNumber) {
        String vehicleRegion = findVehicleRegion(vehicleNumber);
        return jdbcTemplate.query("""
                SELECT d.message_text,
                       d.expires_at
                  FROM disaster_messages d
                 WHERE d.status = 'ACTIVE'
                   AND d.issued_at <= CURRENT_TIMESTAMP
                   AND (d.expires_at IS NULL OR d.expires_at > CURRENT_TIMESTAMP)
                   AND """ + DISASTER_REGION_FILTER_SQL + """
                 ORDER BY d.severity DESC, d.issued_at DESC
                 LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;

            Map<String, Object> row = new HashMap<>();
            row.put("message", rs.getString("message_text"));
            row.put("expiresAt", rs.getTimestamp("expires_at"));
            return row;
        }, vehicleRegion, vehicleRegion);
    }

    private Map<String, Object> findNextContent(
            Map<String, Object> state,
            String vehicleNumber
    ) {
        String vehicleRegion = findVehicleRegion(vehicleNumber);
        String lastKey = null;

        if (state != null && state.get("lastDisplayKey") != null) {
            String stateKey = (String) state.get("lastDisplayKey");
            if (!stateKey.startsWith("DISASTER:")
                    && !stateKey.startsWith("ARRIVAL:")) {
                lastKey = stateKey;
            }
        }

        return jdbcTemplate.query("""
                WITH eligible AS (
                SELECT c.content_id,
                       c.content_type,
                       c.category,
                       c.presentation_type,
                       c.title,
                       c.content,
                       s.force_display,
                       dp.priority AS policy_priority,
                       dp.display_seconds AS policy_display_seconds,
                       dp.repeat_interval_seconds AS policy_repeat_interval_seconds,
                       ROW_NUMBER() OVER (
                           ORDER BY s.force_display DESC,
                                    dp.priority DESC,
                                    s.priority DESC,
                                    c.content_id
                       ) AS sequence_no
                  FROM contents c
                  JOIN content_schedules s
                    ON s.content_id = c.content_id
                  JOIN LATERAL (
                       SELECT p.priority, p.display_seconds, p.repeat_interval_seconds
                         FROM display_policies p
                        WHERE p.display_type = c.content_type
                          AND p.enabled = true
                          AND p.effective_from <= CURRENT_TIMESTAMP
                          AND (p.effective_to IS NULL OR p.effective_to > CURRENT_TIMESTAMP)
                        ORDER BY p.updated_at DESC
                        LIMIT 1
                  ) dp ON true
                 WHERE s.active = true
                   AND (c.display_start_date IS NULL
                        OR c.display_start_date <= CURRENT_DATE)
                   AND (c.display_end_date IS NULL
                        OR c.display_end_date >= CURRENT_DATE)
                   AND NOT EXISTS (
                        SELECT 1
                          FROM content_publication_controls pc
                         WHERE pc.content_id = c.content_id
                   )
                   AND (
                """ + UNRESTRICTED_REGION_SQL + """
                        OR (
                            CAST(? AS varchar) IS NOT NULL
                            AND jsonb_exists(c.target_regions, CAST(? AS text))
                        )
                   )
                ), cursor AS (
                    SELECT sequence_no
                      FROM eligible
                     WHERE content_id = CAST(? AS varchar)
                )
                SELECT e.content_id,
                       e.content_type,
                       e.category,
                       e.presentation_type,
                       e.title,
                       e.content,
                       e.force_display,
                       e.policy_priority,
                       e.policy_display_seconds,
                       e.policy_repeat_interval_seconds
                  FROM eligible e
                  LEFT JOIN cursor c ON true
                 ORDER BY CASE
                              WHEN c.sequence_no IS NULL OR e.sequence_no > c.sequence_no THEN 0
                              ELSE 1
                          END,
                          e.sequence_no
                 LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;

            Map<String, Object> row = new HashMap<>();
            row.put("contentId", rs.getString("content_id"));
            row.put("contentType", rs.getString("content_type"));
            row.put("category", rs.getString("category"));
            row.put("presentationType", rs.getString("presentation_type"));
            row.put("title", rs.getString("title"));
            row.put("content", rs.getString("content"));
            row.put("forced", rs.getBoolean("force_display") ? 1 : 0);
            row.put("policyPriority", rs.getInt("policy_priority"));
            row.put("policyDisplaySeconds", rs.getObject("policy_display_seconds"));
            row.put("policyRepeatIntervalSeconds", rs.getObject("policy_repeat_interval_seconds"));
            return row;
        }, vehicleRegion, vehicleRegion, lastKey);
    }

    private String findVehicleRegion(String vehicleNumber) {
        return jdbcTemplate.query("""
                SELECT CASE
                           WHEN ap.within_100m
                           THEN COALESCE(bs.region_name, r.region_name)
                           ELSE NULL
                       END AS region_name
                  FROM arrival_predictions ap
                  LEFT JOIN bus_stops bs
                    ON bs.node_id = ap.next_node_id
                  LEFT JOIN regions r
                    ON r.region_id = bs.region_id
                 WHERE ap.vehicle_number = ?
                   AND (ap.expires_at IS NULL
                        OR ap.expires_at > CURRENT_TIMESTAMP)
                 ORDER BY ap.observed_at DESC
                 LIMIT 1
                """, rs -> rs.next() ? rs.getString("region_name") : null,
                vehicleNumber);
    }

    private Map<String, Object> findState(String vehicleNumber) {
        return jdbcTemplate.query("""
                SELECT last_display_key,
                       last_display_type,
                       last_displayed_at,
                       disaster_last_displayed_at,
                       arrival_last_display_key,
                       arrival_last_displayed_at
                  FROM display_states
                 WHERE vehicle_number = ?
                """, rs -> {
            if (!rs.next()) return null;

            Map<String, Object> row = new HashMap<>();
            row.put("lastDisplayKey", rs.getString("last_display_key"));
            row.put("lastDisplayType", rs.getString("last_display_type"));
            row.put("lastDisplayedAt", rs.getTimestamp("last_displayed_at"));
            row.put("disasterLastDisplayedAt",
                    rs.getTimestamp("disaster_last_displayed_at"));
            row.put("arrivalLastDisplayKey",
                    rs.getString("arrival_last_display_key"));
            row.put("arrivalLastDisplayedAt",
                    rs.getTimestamp("arrival_last_displayed_at"));
            return row;
        }, vehicleNumber);
    }

    private boolean shouldDisplayDisaster(
            Map<String, Object> state,
            Map<String, Object> policy
    ) {
        if (state == null) {
            return true;
        }

        Timestamp last = (Timestamp) state.get("disasterLastDisplayedAt");

        if (last == null) {
            return true;
        }

        Integer interval = integer(policy, "repeatIntervalSeconds");

        if (interval == null) {
            return true;
        }

        long elapsedSeconds =
                (System.currentTimeMillis() - last.getTime()) / 1000;

        return elapsedSeconds >= interval;
    }

    private DisplayItem arrivalItem(
            Map<String, Object> arrival,
            Map<String, Object> p
    ) {
        return new DisplayItem(
                "ARRIVAL",
                integerOrDefault(p, "priority", 1000),
                true,
                (String) arrival.get("currentStop"),
                (String) arrival.get("nextStop"),
                (BigDecimal) arrival.get("distanceMeters"),
                null,
                "ARRIVAL",
                "이번 정류소 "
                        + arrival.get("currentStop")
                        + "\n다음 정류소 "
                        + arrival.get("nextStop"),
                null,
                toOffsetDateTime((Timestamp) arrival.get("expiresAt")),
                integer(p, "displaySeconds"),
                integer(p, "repeatIntervalSeconds")
        );
    }

    private DisplayItem disasterItem(
            Map<String, Object> disaster,
            Map<String, Object> p
    ) {
        return new DisplayItem(
                "DISASTER",
                integerOrDefault(p, "priority", 900),
                false,
                null,
                null,
                null,
                null,
                "DISASTER",
                String.valueOf(disaster.get("message")),
                null,
                toOffsetDateTime((Timestamp) disaster.get("expiresAt")),
                integer(p, "displaySeconds"),
                integer(p, "repeatIntervalSeconds")
        );
    }

    private DisplayItem contentItem(
            Map<String, Object> content,
            Map<String, Object> p
    ) {
        String contentType = String.valueOf(content.get("contentType"));
        String contentId = String.valueOf(content.get("contentId"));

        List<com.smartbis.backend.content.ContentAssetSummary> assets = jdbcTemplate.query("""
                SELECT asset_role, file_name, sequence_no, mime_type,
                       file_size_bytes, sha256
                  FROM content_assets
                 WHERE content_id = ?
                 ORDER BY sequence_no
                """, (rs, rowNum) -> new com.smartbis.backend.content.ContentAssetSummary(
                rs.getString("asset_role"),
                rs.getString("file_name"),
                rs.getInt("sequence_no"),
                rs.getString("mime_type"),
                (Long) rs.getObject("file_size_bytes"),
                rs.getString("sha256"),
                "/api/v1/media/" + contentId + "/assets/" + rs.getInt("sequence_no")
        ), contentId);

        String presentationType = String.valueOf(content.get("presentationType"));
        String mediaUrl = assets.isEmpty() ? null
                : "/api/v1/media/" + contentId + "/assets/1";

        return new DisplayItem(
                contentType,
                integerOrDefault(p, "priority", 100),
                false,
                null,
                null,
                null,
                contentId,
                contentType,
                String.valueOf(content.get("content")),
                mediaUrl,
                null,
                integer(p, "displaySeconds"),
                integer(p, "repeatIntervalSeconds"),
                String.valueOf(content.get("category")),
                presentationType,
                assets,
                String.valueOf(content.get("title"))
        );
    }

    private void saveState(
            String vehicleNumber,
            String displayType,
            String displayKey,
            boolean disaster
    ) {
        jdbcTemplate.update("""
                INSERT INTO display_states (
                    vehicle_number,
                    cycle_no,
                    last_display_key,
                    last_display_type,
                    last_displayed_at,
                    disaster_last_displayed_at,
                    arrival_last_display_key,
                    arrival_last_displayed_at,
                    updated_at
                )
                VALUES (?, 1, ?, ?, CURRENT_TIMESTAMP,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CASE WHEN ? = 'ARRIVAL' THEN ? ELSE NULL END,
                        CASE WHEN ? = 'ARRIVAL' THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CURRENT_TIMESTAMP)
                ON CONFLICT (vehicle_number)
                DO UPDATE SET
                    cycle_no = display_states.cycle_no + 1,
                    last_display_key = CASE
                        WHEN EXCLUDED.last_display_type IN ('ARRIVAL', 'DISASTER')
                        THEN display_states.last_display_key
                        ELSE EXCLUDED.last_display_key
                    END,
                    last_display_type = EXCLUDED.last_display_type,
                    last_displayed_at = CURRENT_TIMESTAMP,
                    disaster_last_displayed_at =
                        CASE
                            WHEN EXCLUDED.disaster_last_displayed_at IS NOT NULL
                            THEN CURRENT_TIMESTAMP
                            ELSE display_states.disaster_last_displayed_at
                        END,
                    arrival_last_display_key = CASE
                        WHEN EXCLUDED.last_display_type = 'ARRIVAL'
                        THEN EXCLUDED.arrival_last_display_key
                        ELSE display_states.arrival_last_display_key
                    END,
                    arrival_last_displayed_at = CASE
                        WHEN EXCLUDED.last_display_type = 'ARRIVAL'
                        THEN CURRENT_TIMESTAMP
                        ELSE display_states.arrival_last_displayed_at
                    END,
                    updated_at = CURRENT_TIMESTAMP
                """,
                vehicleNumber,
                displayKey,
                displayType,
                disaster,
                displayType,
                displayKey,
                displayType
        );
    }

    private Map<String, Object> policy(String displayType) {
        return jdbcTemplate.query("""
                SELECT display_seconds,
                       repeat_interval_seconds,
                       priority,
                       enabled
                  FROM display_policies
                 WHERE display_type = ?
                   AND effective_from <= CURRENT_TIMESTAMP
                   AND (effective_to IS NULL
                        OR effective_to > CURRENT_TIMESTAMP)
                 ORDER BY updated_at DESC
                 LIMIT 1
                """, rs -> {
            Map<String, Object> row = new HashMap<>();
            row.put("enabled", false);

            if (rs.next()) {
                row.put("displaySeconds", rs.getObject("display_seconds"));
                row.put("repeatIntervalSeconds",
                        rs.getObject("repeat_interval_seconds"));
                row.put("priority", rs.getInt("priority"));
                row.put("enabled", rs.getBoolean("enabled"));
            }

            return row;
        }, displayType);
    }

    static boolean policyEnabled(Map<String, Object> policy) {
        return Boolean.TRUE.equals(policy.get("enabled"));
    }

    private Integer integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : ((Number) value).intValue();
    }

    private int integerOrDefault(
            Map<String, Object> map,
            String key,
            int defaultValue
    ) {
        Integer value = integer(map, key);
        return value == null ? defaultValue : value;
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null
                ? null
                : timestamp.toInstant().atOffset(ZoneOffset.ofHours(9));
    }
}
