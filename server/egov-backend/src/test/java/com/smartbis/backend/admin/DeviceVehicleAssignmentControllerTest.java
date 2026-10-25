package com.smartbis.backend.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class DeviceVehicleAssignmentControllerTest {
    private static final String ID = "BIS_JS_001";
    private static final String FINGERPRINT = "a".repeat(64);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DeviceVehicleAssignmentController controller = new DeviceVehicleAssignmentController(jdbc);

    @Test
    void currentAssignmentResolvesTheRegisteredTerminalAndVehicle() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(FINGERPRINT))).thenReturn(List.of(ID));
        when(jdbc.queryForList(anyString(), eq(String.class), eq(ID))).thenReturn(List.of("전남 72자 1234"));

        var result = controller.current(FINGERPRINT);

        assertEquals(ID, result.deviceNumber());
        assertEquals("전남 72자 1234", result.vehicleNumber());
    }

    @Test
    void savesTheVehicleAgainstThePhysicallyClaimedTerminal() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("전남 72자 1234"))).thenReturn(1);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(FINGERPRINT))).thenReturn(List.of(ID));

        var result = controller.assign(new DeviceVehicleAssignmentController.AssignmentRequest(
                FINGERPRINT, "전남 72자 1234"));

        assertEquals(ID, result.getBody().deviceNumber());
        assertEquals("전남 72자 1234", result.getBody().vehicleNumber());
        verify(jdbc).update(anyString(), eq(ID), eq("전남 72자 1234"));
    }

    @Test
    void refusesAssigningOneVehicleToTwoTerminals() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("전남 72자 1234"))).thenReturn(1);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(FINGERPRINT))).thenReturn(List.of(ID));
        doThrow(new DuplicateKeyException("duplicate vehicle"))
                .when(jdbc).update(anyString(), eq(ID), eq("전남 72자 1234"));

        var error = assertThrows(ResponseStatusException.class, () -> controller.assign(
                new DeviceVehicleAssignmentController.AssignmentRequest(FINGERPRINT, "전남 72자 1234")));

        assertEquals(409, error.getStatusCode().value());
        assertEquals("VEHICLE_ALREADY_ASSIGNED_TO_ANOTHER_DEVICE", error.getReason());
    }

    @Test
    void unassignsVehicleWithoutDeletingTerminalIdentity() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(FINGERPRINT))).thenReturn(List.of(ID));

        var result = controller.unassign(FINGERPRINT);

        assertEquals(ID, result.deviceNumber());
        assertEquals(null, result.vehicleNumber());
        verify(jdbc).update("DELETE FROM android_device_vehicle_assignments WHERE device_number = ?", ID);
    }
}
