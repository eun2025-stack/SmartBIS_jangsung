package com.smartbis.backend.tago;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocationLaneScheduleTest {
    @Test void t1WindowIncludesOneCallAndExcludesOutside() {
        var window = LocationLaneSchedule.window(7 * 3600 + 15 * 60, 60, 15, 10, 600);
        assertTrue(window.start() <= 7 * 3600);
        assertTrue(window.end() > 7 * 3600 + 15 * 60);
        assertFalse(window.start() > 7 * 3600);
        assertEquals(8 * 3600 + 25 * 60, window.end());
    }

    @Test void t2NightWindowHasNoLocationCalls() {
        assertFalse(LocationLaneSchedule.locationCallsAllowed(LocalTime.of(22, 0), 5 * 3600 + 15 * 60, 21 * 3600 + 15 * 60));
        assertFalse(LocationLaneSchedule.locationCallsAllowed(LocalTime.of(4, 59), 5 * 3600 + 15 * 60, 21 * 3600 + 15 * 60));
        assertTrue(LocationLaneSchedule.locationCallsAllowed(LocalTime.of(5, 15), 5 * 3600 + 15 * 60, 21 * 3600 + 15 * 60));
    }

    @Test void t3OverlappingWindowsMerge() {
        var merged = LocationLaneSchedule.merge(List.of(
                new LocationLaneSchedule.DepartureWindow(100, 300, 600),
                new LocationLaneSchedule.DepartureWindow(250, 500, 600)));
        assertEquals(List.of(new LocationLaneSchedule.DepartureWindow(100, 500, 600)), merged);
    }

    @Test void t4LaneCProducesSequentialRoundWithTwoSecondSpacing() {
        var routeIds = java.util.stream.IntStream.range(0, 105).mapToObj(i -> "R" + i).toList();
        var slots = LocationLaneSchedule.laneCRound(routeIds, 6 * 3600, 21 * 3600, 2);
        assertEquals(105, slots.size());
        assertEquals(2, slots.get(1).second() - slots.get(0).second());
        assertEquals(2, slots.get(2).second() - slots.get(1).second());
    }

    @Test void t5LaneC429BackoffIsTwoThreeFive() {
        assertEquals(3, LocationLaneSchedule.nextLaneCDelayAfter429(2, 3, 5));
        assertEquals(5, LocationLaneSchedule.nextLaneCDelayAfter429(3, 3, 5));
        assertEquals(5, LocationLaneSchedule.nextLaneCDelayAfter429(5, 3, 5));
    }

    @Test void t6VariantDepartureUsesTenMinuteLead() {
        var window118 = LocationLaneSchedule.window(7 * 3600 + 15 * 60, 60, 10, 10, 600);
        var window153 = LocationLaneSchedule.window(6 * 3600 + 45 * 60, 60, 10, 10, 600);
        assertEquals(7 * 3600 + 5 * 60, window118.start());
        assertEquals(6 * 3600 + 35 * 60, window153.start());
    }

    @Test void t7LaneAIdsAreNotAutomaticallyLaneBIds() {
        var settings = new LocationCollectionProperties();
        settings.getHundred().setRouteIds(List.of("R102", "R103"));
        settings.setVariantRouteIds(List.of());
        assertFalse(settings.getVariantRouteIds().contains("R102"));
        assertFalse(settings.getVariantRouteIds().contains("R103"));
    }

    @Test void emptyLaneAConfigurationProducesNoLaneACalls() {
        var settings = new LocationCollectionProperties();
        settings.getHundred().setEnabled(true);
        settings.getHundred().setRouteIds(List.of());
        settings.validate();
        assertTrue(settings.getHundred().getRouteIds().isEmpty());
    }
}
