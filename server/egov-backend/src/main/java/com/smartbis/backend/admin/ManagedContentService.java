package com.smartbis.backend.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ManagedContentService {
    private static final List<String> VALID_FILTERS =
            List.of("ACTIVE", "PAUSED", "REMOVED", "ALL");
    private static final List<String> VALID_STATES =
            List.of("ACTIVE", "PAUSED", "REMOVED");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ManagedContentService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ManagedContent> list(String requestedStatus) {
        String status = normalize(requestedStatus);
        if (!VALID_FILTERS.contains(status)) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "status는 ACTIVE, PAUSED, REMOVED 또는 ALL이어야 합니다."
            );
        }

        return jdbcTemplate.query("""
                SELECT c.content_id,
                       c.content_type,
                       c.title,
                       c.target_file_name,
                       c.display_start_date,
                       c.display_end_date,
                       c.target_regions::text AS target_regions,
                       c.template,
                       COALESCE(pc.status, 'ACTIVE') AS publication_status,
                       pc.updated_by,
                       pc.updated_at
                  FROM contents c
                  LEFT JOIN content_publication_controls pc
                    ON pc.content_id = c.content_id
                 WHERE (c.display_start_date IS NULL
                        OR c.display_start_date <= CURRENT_DATE)
                   AND (c.display_end_date IS NULL
                        OR c.display_end_date >= CURRENT_DATE)
                   AND (CAST(? AS varchar) = 'ALL'
                        OR COALESCE(pc.status, 'ACTIVE') = CAST(? AS varchar))
                 ORDER BY c.content_type, c.title, c.content_id
                """, this::mapRow, status, status);
    }

    @Transactional
    public ManagedContentController.PublicationStateResponse updateState(
            String contentId,
            ManagedContentController.PublicationStateRequest request,
            String authenticatedUsername
    ) {
        if (request == null || request.status() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "status가 필요합니다.");
        }
        String status = normalize(request.status());
        String updatedBy = authenticatedUsername == null ? "" : authenticatedUsername.trim();
        if (!VALID_STATES.contains(status)) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "status는 ACTIVE, PAUSED 또는 REMOVED이어야 합니다."
            );
        }
        if (updatedBy.isBlank() || updatedBy.length() > 100) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "변경 담당자는 1~100자여야 합니다."
            );
        }

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contents WHERE content_id = ?",
                Integer.class,
                contentId
        );
        if (exists == null || exists == 0) {
            throw new ResponseStatusException(NOT_FOUND, "콘텐츠를 찾을 수 없습니다.");
        }

        if ("ACTIVE".equals(status)) {
            // Active is the default state: removing the override resumes publication.
            jdbcTemplate.update(
                    "DELETE FROM content_publication_controls WHERE content_id = ?",
                    contentId
            );
        } else {
            jdbcTemplate.update("""
                    INSERT INTO content_publication_controls (
                        content_id, status, updated_by, updated_at
                    ) VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (content_id) DO UPDATE SET
                        status = EXCLUDED.status,
                        updated_by = EXCLUDED.updated_by,
                        updated_at = CURRENT_TIMESTAMP
                    """, contentId, status, updatedBy);
        }

        return new ManagedContentController.PublicationStateResponse(
                contentId,
                status,
                updatedBy
        );
    }

    private ManagedContent mapRow(ResultSet rs, int rowNum) throws SQLException {
        String regionsJson = rs.getString("target_regions");
        List<String> regions = null;
        if (regionsJson != null && !regionsJson.isBlank()) {
            try {
                regions = objectMapper.readValue(
                        regionsJson,
                        new TypeReference<List<String>>() { }
                );
            } catch (Exception e) {
                throw new IllegalStateException(
                        "target_regions JSON 처리 실패: "
                                + rs.getString("content_id"),
                        e
                );
            }
        }

        java.sql.Date start = rs.getDate("display_start_date");
        java.sql.Date end = rs.getDate("display_end_date");
        java.sql.Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ManagedContent(
                rs.getString("content_id"),
                rs.getString("content_type"),
                rs.getString("title"),
                rs.getString("target_file_name"),
                start == null ? null : start.toLocalDate(),
                end == null ? null : end.toLocalDate(),
                regions,
                rs.getString("template"),
                rs.getString("publication_status"),
                rs.getString("updated_by"),
                updatedAt == null ? null : updatedAt.toInstant()
                        .atOffset(java.time.ZoneOffset.UTC)
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
