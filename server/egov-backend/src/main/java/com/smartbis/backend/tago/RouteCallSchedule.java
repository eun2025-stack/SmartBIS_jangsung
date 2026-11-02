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

    static Day compileLanes(List<Plan> plans, LocalDate day, ZoneId zone, LocationCollectionProperties settings) {
        return compileLanes(plans,day,zone,settings,(route,window)->null);
    }
    static Day compileLanes(List<Plan> plans, LocalDate day, ZoneId zone, LocationCollectionProperties settings,
            java.util.function.BiFunction<String,Window,Instant> terminalObservation) {
        var slots=new TreeMap<String,Slot>();
        var counts=new int[Role.values().length];
        var seen=new HashSet<String>();
        var enabled=new ArrayList<Plan>();
        Instant midnight=day.atStartOfDay(zone).toInstant();
        Window laneA = new Window(seconds(settings.getLaneAStart()), seconds(settings.getLaneAEnd()), settings.getLaneAIntervalSeconds());
        Window laneC = new Window(seconds(settings.getLaneCStart()), seconds(settings.getLaneCEnd()), settings.getLaneCIntervalSeconds());
        for (Plan plan : plans) {
            if(!seen.add(RouteIdNormalizer.normalize(plan.routeId()))) throw new IllegalArgumentException("LOCATION_PLAN_DUPLICATE_ROUTE");
            if (!plan.enabled()) continue;
            if(!RouteTimetable.DEFAULT.all().contains(plan.routeId())) {
                org.slf4j.LoggerFactory.getLogger(RouteCallSchedule.class).warn("UNKNOWN_ROUTE_ID excluded: routeId={}",plan.routeId());
                continue;
            }
            enabled.add(plan);
            boolean laneAEnabled = settings.getHundred().isEnabled()
                    && settings.getHundred().getRouteIds().contains(plan.routeId());
            boolean variantEnabled = settings.getVariantRouteIds().contains(plan.routeId());
            boolean localEnabled = settings.getLocal().isEnabled()
                    && settings.getLocal().getRouteIds().contains(plan.routeId());
            if(laneAEnabled) add(slots,counts,plan.routeId(),laneA,Role.HUNDRED,midnight);
            // Lane A IDs and the two routes without timetables cannot enter lane B.
            if(!RouteTimetable.DEFAULT.laneA().contains(plan.routeId()) && (localEnabled||variantEnabled)) {
                for(Window window:RouteTimetable.DEFAULT.windows(plan.routeId(),day,settings)) {
                    Instant terminal=terminalObservation.apply(plan.routeId(),window);
                    var closed=LocationLaneSchedule.close(new LocationLaneSchedule.DepartureWindow(window.start(),window.end(),window.interval()),
                        terminal==null?null:(int)java.time.Duration.between(midnight,terminal).getSeconds(),settings.getCloseAfterLastMinutes());
                    add(slots,counts,plan.routeId(),new Window(closed.start(),closed.end(),closed.intervalSeconds()),Role.LOCAL_WINDOW,midnight);
                }
            }
        }
        enabled.sort(Comparator.comparing(Plan::routeId));
        for(int cycle=laneC.start();cycle<laneC.end();cycle+=laneC.interval()) {
            var round=LocationLaneSchedule.laneCRound(enabled.stream().map(Plan::routeId).toList(),cycle,laneC.end(),settings.getLaneCInterRouteDelaySeconds());
            for(var item:round) {
                int end=Math.min(laneC.end(),cycle+laneC.interval());
                add(slots,counts,item.routeId(),new Window(item.second(),end,end-item.second()),Role.BACKUP,midnight);
            }
        }
        return new Day(slots.values().stream().sorted(Comparator.comparing(Slot::at).thenComparing(Slot::routeId)).toList(),
            new Summary(counts[0],counts[1],counts[2],slots.size()));
    }

    private static List<Window> mergeWindows(List<Window> windows) {
        var result = new ArrayList<Window>();
        for (var window : windows.stream().sorted(Comparator.comparingInt(Window::start)).toList()) {
            if (result.isEmpty() || window.start() > result.get(result.size() - 1).end()) result.add(window);
            else {
                var old = result.remove(result.size() - 1);
                result.add(new Window(old.start(), Math.max(old.end(), window.end()), Math.min(old.interval(), window.interval())));
            }
        }
        return List.copyOf(result);
    }

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
                if (!plan.windows().isEmpty()) throw new IllegalArgumentException("LOCATION_PLAN_INVALID_HUNDRED");
                if (plan.service() != null) add(slots, counts, plan.routeId(), plan.service(), Role.HUNDRED, midnight);
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
