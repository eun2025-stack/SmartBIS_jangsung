package com.smartbis.backend.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Read-only dashboard queries; an error record never deletes received data. */
@RestController
@RequestMapping("/v1/admin/ingest-errors")
public class IngestErrorController {
    private final JdbcTemplate jdbc;

    public IngestErrorController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return jdbc.queryForMap("SELECT * FROM dashboard_error_summary");
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "false") boolean all,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        String search = q.trim();
        // Escape LIKE metacharacters so user input is a literal substring.
        String pattern = "%" + search.replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.queryForList("""
                SELECT e.error_id, e.batch_id, e.content_id, e.error_code,
                       e.error_message, e.error_detail, e.status,
                       e.created_at, e.resolved_at, b.source_path,
                       b.status AS batch_status, b.retry_count, b.last_error
                  FROM content_validation_errors e
                  JOIN content_ingest_batches b ON b.batch_id = e.batch_id
                 WHERE (? OR e.created_at >= CURRENT_TIMESTAMP - INTERVAL '3 days')
                   AND (? = '' OR e.status = ?)
                   AND (? = '' OR concat_ws(' ', e.batch_id, e.content_id,
                        e.error_code, e.error_message, e.error_detail::text,
                        e.status, b.source_path, b.last_error) ILIKE ? ESCAPE '!')
                 ORDER BY e.created_at DESC, e.error_id DESC
                 LIMIT ? OFFSET ?
                """, all, status.trim(), status.trim(), search, pattern,
                Math.max(1, Math.min(limit, 500)), Math.max(0, offset));
    }
}
