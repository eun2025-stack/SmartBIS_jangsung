package com.smartbis.backend.admin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeviceStatusControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DeviceStatusController controller = new DeviceStatusController(jdbc);

    @Test
    void acceptsBoundedDeviceHeartbeatAndUpsertsIt() {
        var response = controller.report(new DeviceStatusController.DeviceStatusRequest(
                "SAMPLE-001", "PLAYING", "VIDEO", "CNT-1", "홍보 영상", "1.0", null));

        assertEquals(204, response.getStatusCode().value());
        verify(jdbc).update(anyString(), eq("SAMPLE-001"), eq("PLAYING"), eq("VIDEO"),
                eq("CNT-1"), eq("홍보 영상"), eq("1.0"), any());
    }

    @Test
    void rejectsInvalidPlaybackState() {
        var error = assertThrows(ResponseStatusException.class, () -> controller.report(
                new DeviceStatusController.DeviceStatusRequest(
                        "SAMPLE-001", "MAINTENANCE", null, null, null, null, null)));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rejectsInvalidVehicleNumber() {
        var error = assertThrows(ResponseStatusException.class, () -> controller.report(
                new DeviceStatusController.DeviceStatusRequest(
                        "../admin", "PLAYING", null, null, null, null, null)));
        assertEquals(400, error.getStatusCode().value());
    }
}
