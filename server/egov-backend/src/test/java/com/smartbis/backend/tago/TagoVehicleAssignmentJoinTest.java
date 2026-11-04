package com.smartbis.backend.tago;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TagoVehicleAssignmentJoinTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void activeVehicleRoutesDoNotRequireAnAndroidAssignment() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var http = mock(HttpClient.class);
        var service = new TagoVehicleLocationService(jdbc, "", "AUTO", "36450", "",
                false, false, 10000, true, false, http);
        var method = TagoVehicleLocationService.class.getDeclaredMethod("assignedActiveRouteIds");
        method.setAccessible(true);
        method.invoke(service);

        var query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(query.capture(), any(RowMapper.class), eq("36450"));
        assertTrue(query.getValue().contains("JOIN bus_vehicles v"));
        assertTrue(query.getValue().contains("v.active = true"));
        assertTrue(query.getValue().contains("r.active = true"));
        assertTrue(query.getValue().contains("v.route_id = r.route_id"));
        assertFalse(query.getValue().contains("android_device_vehicle_assignments"));
        verifyNoInteractions(http);
    }
}
