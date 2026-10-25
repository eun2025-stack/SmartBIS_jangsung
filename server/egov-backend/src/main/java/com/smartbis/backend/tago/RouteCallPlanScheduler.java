package com.smartbis.backend.tago;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** A single due route per tick, no replay of expired slots, no HTTP implementation here. */
final class RouteCallPlanScheduler {
    private final JdbcRouteCallPlanStore store;
    private final Clock clock;
    private final ZoneId zone;
    RouteCallPlanScheduler(JdbcRouteCallPlanStore store, Clock clock, ZoneId zone) {
        this.store = store; this.clock = clock; this.zone = zone;
    }
    Optional<String> next() {
        var plans = store.read();
        var now = clock.instant();
        var attempts = plans.stream().filter(p -> p.lastAttempt() != null)
            .collect(Collectors.toMap(RouteCallSchedule.Plan::routeId, RouteCallSchedule.Plan::lastAttempt));
        var day = RouteCallSchedule.compile(plans, LocalDate.now(clock.withZone(zone)), zone, false);
        for (var slot : day.slots()) {
            if (slot.at().isAfter(now)) break;
            if (!now.isBefore(slot.expiresAt())) continue;
            var last = attempts.get(slot.routeId());
            if (last != null && !last.isBefore(slot.at())) continue;
            if (store.claim(slot, now)) return Optional.of(slot.routeId());
        }
        return Optional.empty();
    }
    void failure(String route, String reason) { store.failure(route, reason); }
}
