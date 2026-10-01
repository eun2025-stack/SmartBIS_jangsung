package com.smartbis.backend.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class DisplayPolicyService {

    private final JdbcTemplate jdbcTemplate;

    public DisplayPolicyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> findAll() {
        return jdbcTemplate.queryForList("""
                SELECT policy_key,
                       display_type,
                       display_seconds,
                       repeat_interval_seconds,
                       priority,
                       enabled,
                       effective_from,
                       effective_to,
                       updated_by,
                       updated_at
                  FROM display_policies
                 ORDER BY priority DESC, policy_key
                """);
    }

    public Map<String, Object> update(
            String policyKey,
            Integer displaySeconds,
            Integer repeatIntervalSeconds,
            Integer priority,
            Boolean enabled,
            String updatedBy
    ) {
        if (displaySeconds != null && displaySeconds <= 0) {
            throw new IllegalArgumentException(
                    "displaySeconds는 1 이상이어야 합니다."
            );
        }

        if (repeatIntervalSeconds != null && repeatIntervalSeconds <= 0) {
            throw new IllegalArgumentException(
                    "repeatIntervalSeconds는 1 이상이어야 합니다."
            );
        }

        int updated = jdbcTemplate.update("""
                UPDATE display_policies
                   SET display_seconds = ?,
                       repeat_interval_seconds = ?,
                       priority = ?,
                       enabled = ?,
                       updated_by = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE policy_key = ?
                """,
                displaySeconds,
                repeatIntervalSeconds,
                priority,
                enabled,
                updatedBy,
                policyKey
        );

        if (updated == 0) {
            throw new ResponseStatusException(
                    NOT_FOUND,
                    "정책을 찾을 수 없습니다: " + policyKey
            );
        }

        return jdbcTemplate.queryForMap("""
                SELECT policy_key,
                       display_type,
                       display_seconds,
                       repeat_interval_seconds,
                       priority,
                       enabled,
                       effective_from,
                       effective_to,
                       updated_by,
                       updated_at
                  FROM display_policies
                 WHERE policy_key = ?
                """, policyKey);
    }
}
