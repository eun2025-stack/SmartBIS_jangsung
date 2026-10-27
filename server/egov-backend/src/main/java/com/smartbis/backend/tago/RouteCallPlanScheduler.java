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
    private final LocationCollectionProperties settings;
    private volatile long lastLaneCCallAt = Long.MIN_VALUE;
    private volatile int laneCDelaySeconds;
    RouteCallPlanScheduler(JdbcRouteCallPlanStore store, Clock clock, ZoneId zone) {
        this(store, clock, zone, null);
    }
    RouteCallPlanScheduler(JdbcRouteCallPlanStore store, Clock clock, ZoneId zone, LocationCollectionProperties settings) {
        this.store = store; this.clock = clock; this.zone = zone; this.settings = settings;
        this.laneCDelaySeconds = settings == null ? 0 : settings.getLaneCInterRouteDelaySeconds();
    }
    Optional<String> next() {
        var plans = store.read();
        var now = clock.instant();
        var attempts = plans.stream().filter(p -> p.lastAttempt() != null)
            .collect(Collectors.toMap(RouteCallSchedule.Plan::routeId, RouteCallSchedule.Plan::lastAttempt));
        var day = settings == null
                ? RouteCallSchedule.compile(plans, LocalDate.now(clock.withZone(zone)), zone, false)
                : RouteCallSchedule.compileLanes(plans, LocalDate.now(clock.withZone(zone)), zone, settings);
        for (var slot : day.slots()) {
            if (slot.at().isAfter(now)) break;
            if (!now.isBefore(slot.expiresAt())) continue;
            var last = attempts.get(slot.routeId());
            if (last != null && !last.isBefore(slot.at())) continue;
            if (settings != null && slot.roles().contains(RouteCallSchedule.Role.BACKUP)
                    && now.toEpochMilli() < lastLaneCCallAt + laneCDelaySeconds * 1000L) continue;
            if (store.claim(slot, now)) {
                if (settings != null && slot.roles().contains(RouteCallSchedule.Role.BACKUP)) lastLaneCCallAt = now.toEpochMilli();
                return Optional.of(slot.routeId());
            }
        }
        return Optional.empty();
    }
    void laneCRateLimited() {
        if (settings == null) return;
        int next = LocationLaneSchedule.nextLaneCDelayAfter429(laneCDelaySeconds,
                settings.getLaneC429DelayStep2Seconds(), settings.getLaneC429DelayStep3Seconds());
        if (next != laneCDelaySeconds) {
            laneCDelaySeconds = next;
            org.slf4j.LoggerFactory.getLogger(RouteCallPlanScheduler.class)
                    .warn("TAGO lane C 429 backoff increased: delaySeconds={}", next);
        }
    }
    void failure(String route, String reason) { store.failure(route, reason); }
}
