package com.smartbis.backend.display;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class DisplayService {

    private final JdbcTemplate jdbcTemplate;

    public DisplayService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DisplayItem next(String vehicleNumber) {
        Map<String, Object> arrival = findArrival(vehicleNumber);

        if (arrival != null) {
            DisplayItem item = arrivalItem(arrival);
            saveState(vehicleNumber, "ARRIVAL", "ARRIVAL", false);
            return item;
        }

        Map<String, Object> state = findState(vehicleNumber);
        Map<String, Object> disasterPolicy = policy("DISASTER");

        if (shouldDisplayDisaster(state, disasterPolicy)) {
            Map<String, Object> disaster = findDisaster();

            if (disaster != null) {
                DisplayItem item = disasterItem(disaster, disasterPolicy);
                saveState(vehicleNumber, "DISASTER",
                        "DISASTER:" + disaster.get("message"), true);
                return item;
            }
        }

        Map<String, Object> content = findNextContent(state);

        if (content != null) {
            String contentType = String.valueOf(content.get("contentType"));
            Map<String, Object> contentPolicy = policy(contentType);

            DisplayItem item = contentItem(content, contentPolicy);

            saveState(
                    vehicleNumber,
                    contentType,
                    String.valueOf(content.get("contentId")),
                    false
            );

            return item;
        }

        throw new ResponseStatusException(
                NOT_FOUND,
                "표출 가능한 항목이 없습니다."
        );
    }

    private Map<String, Object> findArrival(String vehicleNumber) {
        return jdbcTemplate.query("""
                SELECT current_stop_text,
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
            row.put("currentStop", rs.getString("current_stop_text"));
            row.put("nextStop", rs.getString("next_stop_text"));
            row.put("distanceMeters", rs.getBigDecimal("distance_meters"));
            row.put("expiresAt", rs.getTimestamp("expires_at"));
            return row;
        }, vehicleNumber);
    }

    private Map<String, Object> findDisaster() {
        return jdbcTemplate.query("""
                SELECT message_text,
                       expires_at
                  FROM disaster_messages
                 WHERE status = 'ACTIVE'
                   AND issued_at <= CURRENT_TIMESTAMP
                   AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                 ORDER BY severity DESC, issued_at DESC
                 LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;

            Map<String, Object> row = new HashMap<>();
            row.put("message", rs.getString("message_text"));
            row.put("expiresAt", rs.getTimestamp("expires_at"));
            return row;
        });
    }

    private Map<String, Object> findNextContent(
            Map<String, Object> state
    ) {
        String lastKey = null;

        if (state != null
                && state.get("lastDisplayType") != null
                && !"DISASTER".equals(state.get("lastDisplayType"))
                && !"ARRIVAL".equals(state.get("lastDisplayType"))) {
            lastKey = (String) state.get("lastDisplayKey");
        }

        Map<String, Object> content = jdbcTemplate.query("""
                SELECT c.content_id,
                       c.content_type,
                       c.content
                  FROM contents c
                  JOIN content_schedules s
                    ON s.content_id = c.content_id
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
                        CAST(? AS varchar) IS NULL
                        OR c.content_id > CAST(? AS varchar)
                   )
                 ORDER BY s.force_display DESC,
                          s.priority DESC,
                          c.content_id
                 LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;

            Map<String, Object> row = new HashMap<>();
            row.put("contentId", rs.getString("content_id"));
            row.put("contentType", rs.getString("content_type"));
            row.put("content", rs.getString("content"));
            return row;
        }, lastKey, lastKey);

        if (content != null) {
            return content;
        }

        // 마지막 콘텐츠 이후가 없으면 순환의 처음부터 다시 시작
        return jdbcTemplate.query("""
                SELECT c.content_id,
                       c.content_type,
                       c.content
                  FROM contents c
                  JOIN content_schedules s
                    ON s.content_id = c.content_id
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
                 ORDER BY s.force_display DESC,
                          s.priority DESC,
                          c.content_id
                 LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;

            Map<String, Object> row = new HashMap<>();
            row.put("contentId", rs.getString("content_id"));
            row.put("contentType", rs.getString("content_type"));
            row.put("content", rs.getString("content"));
            return row;
        });
    }

    private Map<String, Object> findState(String vehicleNumber) {
        return jdbcTemplate.query("""
                SELECT last_display_key,
                       last_display_type,
                       last_displayed_at,
                       disaster_last_displayed_at
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

    private DisplayItem arrivalItem(Map<String, Object> arrival) {
        Map<String, Object> p = policy("ARRIVAL");

        return new DisplayItem(
                "ARRIVAL",
                1000,
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

        String mediaUrl =
                "CARD".equals(contentType)
                        ? null
                        : "/api/v1/media/" + contentId;

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
                integer(p, "repeatIntervalSeconds")
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
                    updated_at
                )
                VALUES (?, 1, ?, ?, CURRENT_TIMESTAMP,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CURRENT_TIMESTAMP)
                ON CONFLICT (vehicle_number)
                DO UPDATE SET
                    cycle_no = display_states.cycle_no + 1,
                    last_display_key = EXCLUDED.last_display_key,
                    last_display_type = EXCLUDED.last_display_type,
                    last_displayed_at = CURRENT_TIMESTAMP,
                    disaster_last_displayed_at =
                        CASE
                            WHEN EXCLUDED.disaster_last_displayed_at IS NOT NULL
                            THEN CURRENT_TIMESTAMP
                            ELSE display_states.disaster_last_displayed_at
                        END,
                    updated_at = CURRENT_TIMESTAMP
                """,
                vehicleNumber,
                displayKey,
                displayType,
                disaster
        );
    }

    private Map<String, Object> policy(String displayType) {
        return jdbcTemplate.query("""
                SELECT display_seconds,
                       repeat_interval_seconds,
                       priority
                  FROM display_policies
                 WHERE display_type = ?
                   AND enabled = true
                   AND effective_from <= CURRENT_TIMESTAMP
                   AND (effective_to IS NULL
                        OR effective_to > CURRENT_TIMESTAMP)
                 ORDER BY updated_at DESC
                 LIMIT 1
                """, rs -> {
            Map<String, Object> row = new HashMap<>();

            if (rs.next()) {
                row.put("displaySeconds", rs.getObject("display_seconds"));
                row.put("repeatIntervalSeconds",
                        rs.getObject("repeat_interval_seconds"));
                row.put("priority", rs.getInt("priority"));
            }

            return row;
        }, displayType);
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
