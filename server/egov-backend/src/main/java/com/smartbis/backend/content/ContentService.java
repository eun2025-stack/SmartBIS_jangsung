package com.smartbis.backend.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

@Service
public class ContentService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ContentService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ContentSummary> findAvailable(String region) {
        String sql = """
                SELECT content_id,
                       content_type,
                       title,
                       target_file_name,
                       display_start_date,
                       display_end_date,
                       target_regions::text AS target_regions,
                       template
                 FROM contents
                 WHERE (display_start_date IS NULL OR display_start_date <= CURRENT_DATE)
                   AND (display_end_date IS NULL OR display_end_date >= CURRENT_DATE)
                   AND NOT EXISTS (
                       SELECT 1
                         FROM content_publication_controls pc
                        WHERE pc.content_id = contents.content_id
                   )
                   AND (
                         CAST(? AS text) IS NULL
                         OR target_regions IS NULL
                         OR target_regions @> jsonb_build_array(CAST(? AS text))
                       )
                 ORDER BY content_id
                """;

        return jdbcTemplate.query(
                sql,
                this::mapRow,
                region,
                region
        );
    }

    private ContentSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
        String contentId = rs.getString("content_id");
        String regionsJson = rs.getString("target_regions");

        List<String> targetRegions = null;

        if (regionsJson != null && !regionsJson.isBlank()) {
            try {
                targetRegions = objectMapper.readValue(
                        regionsJson,
                        new TypeReference<List<String>>() {
                        }
                );
            } catch (Exception e) {
                throw new IllegalStateException(
                        "target_regions JSON 처리 실패: " + contentId,
                        e
                );
            }
        }

        return new ContentSummary(
                contentId,
                rs.getString("content_type"),
                rs.getString("title"),
                rs.getString("target_file_name"),
                getDate(rs, "display_start_date"),
                getDate(rs, "display_end_date"),
                targetRegions,
                rs.getString("template"),
                "/api/v1/media/" + contentId
        );
    }

    private LocalDate getDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
}
