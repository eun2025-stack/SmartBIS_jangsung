package com.smartbis.backend.admin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleDirectoryControllerTest {
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String DEVICE_ID = "BIS_JS_001";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final VehicleDirectoryController controller = new VehicleDirectoryController(jdbc);

    @Test
    void searchesRegisteredVehiclesByPartialNumber() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("%12%")))
                .thenReturn(List.of("전남 72자 1234"));

        assertEquals(List.of("전남 72자 1234"), controller.search("12"));
        verify(jdbc).queryForList(anyString(), eq(String.class), eq("%12%"));
    }

    @Test
    void returnsVehicleDirectoryForBlankInitialQuery() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("%%")))
                .thenReturn(List.of("전남 72자 1234", "전남 72자 5678"));

        assertEquals(List.of("전남 72자 1234", "전남 72자 5678"), controller.search(""));
    }

    @Test
    void supportsOneCharacterPartialSearch() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("%1%")))
                .thenReturn(List.of("전남 72자 1234"));

        assertEquals(List.of("전남 72자 1234"), controller.search("1"));
    }

    @Test
    void distinguishesThisDeviceOtherDevicesAndUnassignedVehicles() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(FINGERPRINT))).thenReturn(List.of(DEVICE_ID));
        when(jdbc.queryForList(anyString(), eq(DEVICE_ID), eq("%%"))).thenReturn(List.of(
                Map.of("vehicle_number", "전남 72자 1234", "assignment_status", "ASSIGNED_TO_THIS_DEVICE"),
                Map.of("vehicle_number", "전남 72자 5678", "assignment_status", "UNASSIGNED"),
                Map.of("vehicle_number", "전남 72자 9999", "assignment_status", "ASSIGNED_TO_OTHER_DEVICE")));

        assertEquals(List.of(
                new VehicleDirectoryController.VehicleAvailability("전남 72자 1234", "ASSIGNED_TO_THIS_DEVICE"),
                new VehicleDirectoryController.VehicleAvailability("전남 72자 5678", "UNASSIGNED"),
                new VehicleDirectoryController.VehicleAvailability("전남 72자 9999", "ASSIGNED_TO_OTHER_DEVICE")),
                controller.availability("", FINGERPRINT));
    }
}
