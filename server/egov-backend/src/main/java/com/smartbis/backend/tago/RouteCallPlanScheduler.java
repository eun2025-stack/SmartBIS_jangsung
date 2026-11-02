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
    private final Map<String,java.util.List<java.time.Instant>> terminalTimes=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> successfulLaneCRound=new java.util.HashSet<>();
    private long successfulLaneCCycle = Long.MIN_VALUE;
    RouteCallPlanScheduler(JdbcRouteCallPlanStore store, Clock clock, ZoneId zone) {
        this(store, clock, zone, null);
    }
    RouteCallPlanScheduler(JdbcRouteCallPlanStore store, Clock clock, ZoneId zone, LocationCollectionProperties settings) {
        this.store = store; this.clock = clock; this.zone = zone; this.settings = settings;
        this.laneCDelaySeconds = settings == null ? 0 : settings.getLaneCInterRouteDelaySeconds();
    }
    Optional<String> next() {
        return next(null);
    }
    Optional<String> next(RouteCallSchedule.Role lane) {
        var plans = store.read();
        var now = clock.instant();
        var attempts = plans.stream().filter(p -> p.lastAttempt() != null)
            .collect(Collectors.toMap(RouteCallSchedule.Plan::routeId, RouteCallSchedule.Plan::lastAttempt));
        var day = settings == null
                ? RouteCallSchedule.compile(plans, LocalDate.now(clock.withZone(zone)), zone, false)
                : RouteCallSchedule.compileLanes(plans, LocalDate.now(clock.withZone(zone)), zone, settings,(route,window)->{
                    var midnight=LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant();
                    var start=midnight.plusSeconds(window.start()); var end=midnight.plusSeconds(window.end());
                    var saved=store.firstTerminal(route,start,end.isAfter(now)?now.plusNanos(1):end);
                    var live=terminalTimes.getOrDefault(route,java.util.List.of()).stream().filter(t->!t.isBefore(start)&&t.isBefore(end)).min(java.time.Instant::compareTo).orElse(null);
                    return saved==null?live:live==null?saved:saved.isBefore(live)?saved:live;
                });
        for (var slot : day.slots()) {
            if(lane!=null && !slot.roles().contains(lane)) continue;
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
    void observed(String route,Integer order,java.time.Instant at) {
        if(order==null || !store.isTerminal(route,order)) return;
        terminalTimes.compute(route,(key,old)->{
            var list=new java.util.ArrayList<>(old==null?java.util.List.of():old);
            list.removeIf(t->t.isBefore(at.minusSeconds(86400)));
            list.add(at);return java.util.List.copyOf(list);
        });
    }
    synchronized void laneCSucceeded(String route) {
        long cycle = Math.floorDiv(clock.instant().getEpochSecond(), settings.getLaneCIntervalSeconds());
        if (cycle != successfulLaneCCycle) {
            successfulLaneCRound.clear();
            successfulLaneCCycle = cycle;
        }
        successfulLaneCRound.add(route);
        if(successfulLaneCRound.containsAll(RouteTimetable.DEFAULT.all())) {
            int next=laneCDelaySeconds>settings.getLaneC429DelayStep2Seconds()?settings.getLaneC429DelayStep2Seconds():settings.getLaneCInterRouteDelaySeconds();
            if(next!=laneCDelaySeconds) org.slf4j.LoggerFactory.getLogger(RouteCallPlanScheduler.class).info("TAGO lane C successful round: delaySeconds={}",next);
            laneCDelaySeconds=next;successfulLaneCRound.clear();
        }
    }
    synchronized void laneCRateLimited() {
        if (settings == null) return;
        successfulLaneCRound.clear();
        int next = LocationLaneSchedule.nextLaneCDelayAfter429(laneCDelaySeconds,
                settings.getLaneC429DelayStep2Seconds(), settings.getLaneC429DelayStep3Seconds());
        if (next != laneCDelaySeconds) {
            laneCDelaySeconds = next;
            org.slf4j.LoggerFactory.getLogger(RouteCallPlanScheduler.class)
                    .warn("TAGO lane C 429 backoff increased: delaySeconds={}", next);
        }
    }
    void failure(String route, String reason) { store.failure(route, reason); }
    long gapMillis(RouteCallSchedule.Role lane) { return lane==RouteCallSchedule.Role.BACKUP?laneCDelaySeconds*1000L:settings.getMinGapMs(); }
}
