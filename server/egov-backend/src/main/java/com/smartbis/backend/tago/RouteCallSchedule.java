package com.smartbis.backend.tago;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Pure daily schedule. End-exclusive; coincident roles share one HTTP call. */
final class RouteCallSchedule {
    enum Role { LOCAL_WINDOW, HUNDRED, BACKUP }
    record Window(int start, int end, int interval) {
        Window {
            if (start < 0 || end > 86400 || start >= end || interval < 1) throw new IllegalArgumentException("LOCATION_PLAN_INVALID_WINDOW");
        }
    }
    record Plan(String routeId, String group, boolean enabled, List<Window> windows,
                Window service, Window backup, Instant lastAttempt) {}
    record Slot(String routeId, Instant at, Instant expiresAt, Set<Role> roles) {}
    record Summary(int local, int hundred, int backup, int uniqueCalls) {
        int total() { return local + hundred + backup; }
    }
    record Day(List<Slot> slots, Summary summary) {}

    static Day compile(List<Plan> plans, LocalDate day, ZoneId zone, boolean previewDisabled) {
        var slots = new TreeMap<String,Slot>();
        var counts = new int[Role.values().length];
        var ids = new HashSet<String>();
        Instant midnight = day.atStartOfDay(zone).toInstant();
        for (Plan plan : plans) {
            if (!ids.add(plan.routeId())) throw new IllegalArgumentException("LOCATION_PLAN_DUPLICATE_ROUTE");
            if (!plan.enabled() && !previewDisabled) continue;
            if (!Set.of("관내", "100번").contains(plan.group())) throw new IllegalArgumentException("LOCATION_PLAN_INVALID_GROUP");
            if (plan.group().equals("100번")) {
                if (!plan.windows().isEmpty() || plan.service() == null) throw new IllegalArgumentException("LOCATION_PLAN_INVALID_HUNDRED");
                add(slots, counts, plan.routeId(), plan.service(), Role.HUNDRED, midnight);
            } else {
                var sorted = plan.windows().stream().sorted(Comparator.comparingInt(Window::start)).toList();
                int previousEnd = -1;
                for (Window window : sorted) {
                    if (window.start() < previousEnd) throw new IllegalArgumentException("LOCATION_PLAN_OVERLAPPING_WINDOWS");
                    add(slots, counts, plan.routeId(), window, Role.LOCAL_WINDOW, midnight);
                    previousEnd = window.end();
                }
            }
            if (plan.backup() == null) throw new IllegalArgumentException("LOCATION_PLAN_MISSING_BACKUP");
            add(slots, counts, plan.routeId(), plan.backup(), Role.BACKUP, midnight);
        }
        var ordered = slots.values().stream().sorted(Comparator.comparing(Slot::at).thenComparing(Slot::routeId)).toList();
        return new Day(ordered, new Summary(counts[0], counts[1], counts[2], ordered.size()));
    }
    private static void add(Map<String,Slot> slots, int[] counts, String route, Window window, Role role, Instant midnight) {
        for (long second = window.start(); second < window.end(); second += window.interval()) {
            counts[role.ordinal()]++;
            Instant at = midnight.plusSeconds(second), end = midnight.plusSeconds(Math.min(86400, second + window.interval()));
            String key = route + "@" + second;
            Slot old = slots.get(key);
            var roles = EnumSet.of(role);
            if (old != null) { roles.addAll(old.roles()); if (old.expiresAt().isAfter(end)) end = old.expiresAt(); }
            slots.put(key, new Slot(route, at, end, Set.copyOf(roles)));
        }
    }
    static int seconds(String value) {
        String[] parts = value.split(":");
        if (parts.length < 2 || parts.length > 3) throw new IllegalArgumentException("LOCATION_PLAN_INVALID_TIME");
        int h = Integer.parseInt(parts[0]), m = Integer.parseInt(parts[1]), s = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
        if (h < 0 || h > 24 || m < 0 || m > 59 || s < 0 || s > 59 || h == 24 && (m != 0 || s != 0)) throw new IllegalArgumentException("LOCATION_PLAN_INVALID_TIME");
        return h * 3600 + m * 60 + s;
    }
}
