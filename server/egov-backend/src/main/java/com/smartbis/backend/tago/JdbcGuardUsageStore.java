package com.smartbis.backend.tago;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcGuardUsageStore implements GuardUsageStore {
    private final JdbcTemplate jdbc;
    JdbcGuardUsageStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public int read(String api, LocalDate day) {
        var counts = jdbc.query("SELECT call_count FROM public.guard_usage_daily WHERE api_name = ? AND usage_date = ?",
                (rs, row) -> rs.getInt(1), api, day);
        return counts.isEmpty() ? 0 : counts.get(0);
    }

    @Override
    public int reserve(String api, LocalDate day, int limit, Instant now) {
        // A single statement prevents over-spending even if two callers reach the DB together.
        var counts = jdbc.query("""
                INSERT INTO public.guard_usage_daily(api_name, usage_date, call_count, limit_count, blocked_at)
                VALUES (?, ?, 1, ?, CASE WHEN ? = 1 THEN ?::timestamptz ELSE NULL END)
                ON CONFLICT (api_name, usage_date) DO UPDATE
                SET call_count = guard_usage_daily.call_count + 1,
                    limit_count = EXCLUDED.limit_count,
                    blocked_at = CASE WHEN guard_usage_daily.call_count + 1 >= EXCLUDED.limit_count
                                 THEN COALESCE(guard_usage_daily.blocked_at, ?::timestamptz)
                                 ELSE guard_usage_daily.blocked_at END
                WHERE guard_usage_daily.call_count < EXCLUDED.limit_count
                RETURNING call_count
                """, (rs, row) -> rs.getInt(1), api, day, limit, limit, Timestamp.from(now), Timestamp.from(now));
        if (!counts.isEmpty()) return counts.get(0);
        jdbc.update("""
                UPDATE public.guard_usage_daily SET blocked_at = COALESCE(blocked_at, ?), limit_count = ?
                WHERE api_name = ? AND usage_date = ? AND call_count >= ?
                """, Timestamp.from(now), limit, api, day, limit);
        return -1;
    }
}
