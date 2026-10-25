package com.smartbis.backend.display;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayServiceTest {
    @Test
    void globalContentRegionFilterAcceptsSqlNullAndJsonNull() {
        assertTrue(DisplayService.UNRESTRICTED_REGION_SQL.contains("c.target_regions IS NULL"));
        assertTrue(DisplayService.UNRESTRICTED_REGION_SQL.contains("c.target_regions = 'null'::jsonb"));
    }

    @Test
    void regionalDisasterFilterAllowsGlobalMessagesAndMatchesVehicleRegion() {
        assertTrue(DisplayService.DISASTER_REGION_FILTER_SQL.contains("d.target_regions IS NULL"));
        assertTrue(DisplayService.DISASTER_REGION_FILTER_SQL.contains("d.target_regions = 'null'::jsonb"));
        assertTrue(DisplayService.DISASTER_REGION_FILTER_SQL.contains("jsonb_exists(d.target_regions"));
    }

    @Test
    void onlyAnExplicitlyEnabledPolicyIsSelectable() {
        assertTrue(DisplayService.policyEnabled(Map.of("enabled", true)));
        assertFalse(DisplayService.policyEnabled(Map.of("enabled", false)));
        assertFalse(DisplayService.policyEnabled(Map.of()));
    }

    @Test
    void configuredPolicyPriorityControlsSelectionAcrossDisplayTypes() {
        var arrival = candidate("ARRIVAL", 1000, 3);
        var disaster = candidate("DISASTER", 900, 2);
        var notice = candidate("NOTICE", 1100, 1);

        var selected = DisplayService.selectCandidate(List.of(arrival, disaster, notice));

        org.junit.jupiter.api.Assertions.assertEquals("NOTICE", selected.displayType());
    }

    @Test
    void arrivalWinsWhenConfiguredPrioritiesTie() {
        var disaster = candidate("DISASTER", 500, 2);
        var arrival = candidate("ARRIVAL", 500, 3);

        var selected = DisplayService.selectCandidate(List.of(disaster, arrival));

        org.junit.jupiter.api.Assertions.assertEquals("ARRIVAL", selected.displayType());
    }

    private DisplayService.DisplayCandidate candidate(String type, int priority, int tieBreak) {
        var item = new DisplayItem(type, priority, false, null, null, BigDecimal.ZERO,
                null, type, "test", null, null, 10, 60);
        return new DisplayService.DisplayCandidate(item, priority, tieBreak,
                "DISASTER".equals(type), type, type + ":test");
    }
}
