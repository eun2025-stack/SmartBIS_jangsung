package com.smartbis.backend.tago;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TagoVehicleAssignmentJoinTest {
    private TagoVehicleLocationService service(JdbcTemplate jdbc) {
        return new TagoVehicleLocationService(jdbc, "", "AUTO", "fixture-city", "",
                false, false, 10000, true, true, mock(HttpClient.class));
    }

    private boolean persist(TagoVehicleLocationService service) throws Exception {
        var now = Instant.parse("2026-11-05T00:00:00Z");
        var trace = new TagoVehicleLocationService.PositionTrace(UUID.randomUUID(), now, now, "A");
        var position = new TagoVehicleLocationService.VehiclePosition(
                "fixture-vehicle", "fixture-route", "fixture-node", 0, null, null, null, "{}", trace);
        var method = TagoVehicleLocationService.class.getDeclaredMethod(
                "persist", TagoVehicleLocationService.VehiclePosition.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, position);
    }

    private void assertStorageContract(JdbcTemplate jdbc) {
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        String query = sql.getValue();
        assertTrue(query.contains("INSERT INTO bus_positions"));
        assertTrue(query.contains("WHERE EXISTS"));
        assertTrue(query.contains("JOIN android_device_vehicle_assignments a"));
        assertTrue(query.contains("a.vehicle_number = v.vehicle_number"));
        assertTrue(query.contains("v.active = true"));
        assertTrue(query.contains("v.vehicle_number = ?"));
        assertFalse(query.contains("v.route_id"));
        assertFalse(query.contains("INSERT INTO bus_vehicles"));
        assertFalse(query.contains("UPDATE bus_vehicles"));
        assertEquals("fixture-vehicle", args.getValue()[args.getValue().length - 1]);
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void catalogRoutesRemainAvailableWithoutVehicleRouteAssignments() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        List<String> routes = IntStream.range(0, 105).mapToObj(i -> "fixture-route-" + i).toList();
        doReturn(routes).when(jdbc).query(anyString(), any(RowMapper.class), eq("fixture-city"));
        var method = TagoVehicleLocationService.class.getDeclaredMethod("assignedActiveRouteIds");
        method.setAccessible(true);
        assertEquals(routes, method.invoke(service(jdbc)));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("fixture-city"));
        assertTrue(sql.getValue().contains("bus_routes"));
        assertTrue(sql.getValue().contains("active = true"));
        assertFalse(sql.getValue().contains("bus_vehicles"));
        assertFalse(sql.getValue().contains("android_device_vehicle_assignments"));
        assertFalse(sql.getValue().contains("v.route_id"));
    }

    @Test void zeroAssignmentsSaveZeroVehicles() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertFalse(persist(service(jdbc)));
        assertStorageContract(jdbc);
    }

    @Test void assignedActiveVehicleIsSaved() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertTrue(persist(service(jdbc)));
        assertStorageContract(jdbc);
    }

    @Test void assignmentWithoutVehicleIsExcluded() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertFalse(persist(service(jdbc)));
        assertStorageContract(jdbc);
    }

    @Test void assignmentWithInactiveVehicleIsExcluded() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertFalse(persist(service(jdbc)));
        assertStorageContract(jdbc);
    }

    @Test void metadataDisabledStillRequiresAssignment() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var service = service(jdbc);
        ReflectionTestUtils.setField(service, "captureMetadata", false);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertFalse(persist(service));
        assertStorageContract(jdbc);
    }

    @Test void localRunStorageStillRequiresAssignment() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var service = service(jdbc);
        ReflectionTestUtils.setField(service, "localControl", mock(LocalCollectionControl.class));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertFalse(persist(service));
        assertStorageContract(jdbc);
    }
}
