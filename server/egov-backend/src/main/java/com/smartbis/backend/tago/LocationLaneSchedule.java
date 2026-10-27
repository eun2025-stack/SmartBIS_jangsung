package com.smartbis.backend.tago;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure lane calculations. It never performs an HTTP request or reads a database. */
final class LocationLaneSchedule {
    record DepartureWindow(int start, int end, int intervalSeconds) { }
    record LaneCSlot(String routeId, int second) { }

    private LocationLaneSchedule() { }

    static DepartureWindow window(int departureSecond, int travelMinutes, int leadMinutes, int endPaddingMinutes, int intervalSeconds) {
        int start = Math.max(0, departureSecond - leadMinutes * 60);
        int end = Math.min(86400, departureSecond + travelMinutes * 60 + endPaddingMinutes * 60);
        if (start >= end) throw new IllegalArgumentException("LOCATION_PLAN_INVALID_DEPARTURE_WINDOW");
        return new DepartureWindow(start, end, intervalSeconds);
    }

    static List<DepartureWindow> merge(List<DepartureWindow> input) {
        var sorted = input.stream().sorted(Comparator.comparingInt(DepartureWindow::start)).toList();
        var result = new ArrayList<DepartureWindow>();
        for (var current : sorted) {
            if (result.isEmpty() || current.start() > result.get(result.size() - 1).end()) {
                result.add(current);
            } else {
                var previous = result.remove(result.size() - 1);
                result.add(new DepartureWindow(previous.start(), Math.max(previous.end(), current.end()),
                        Math.min(previous.intervalSeconds(), current.intervalSeconds())));
            }
        }
        return List.copyOf(result);
    }

    static List<LaneCSlot> laneCRound(List<String> routeIds, int start, int end, int delaySeconds) {
        var result = new ArrayList<LaneCSlot>();
        int second = start;
        for (String routeId : routeIds) {
            result.add(new LaneCSlot(routeId, second));
            second += delaySeconds;
            if (second >= end) break;
        }
        return List.copyOf(result);
    }

    static boolean locationCallsAllowed(LocalTime time, int start, int end) {
        int second = time.toSecondOfDay();
        return second >= start && second < end;
    }

    static int nextLaneCDelayAfter429(int currentDelay, int step2, int step3) {
        if (currentDelay < step2) return step2;
        if (currentDelay < step3) return step3;
        return step3;
    }
}
