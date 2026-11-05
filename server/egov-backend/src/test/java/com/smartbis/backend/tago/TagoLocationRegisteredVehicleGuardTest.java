package com.smartbis.backend.tago;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TagoLocationRegisteredVehicleGuardTest {
    private TagoVehicleLocationService service(JdbcTemplate jdbc,HttpClient http) {
        return new TagoVehicleLocationService(jdbc,"fixture","AUTO","fixture-city","https://stub.invalid",
                true,true,10000,false,true,http);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private void routesAndResponse(JdbcTemplate jdbc,HttpClient http) throws Exception {
        var routes=IntStream.range(0,105).mapToObj(i->"fixture-route-"+i).toList();
        doReturn(routes).when(jdbc).query(anyString(),any(RowMapper.class),eq("fixture-city"));
        var response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(("<response><header><resultCode>00</resultCode></header><body><items><item>"
                +"<vehicleno>fixture-vehicle</vehicleno><nodeord>0</nodeord></item></items><totalCount>1</totalCount></body></response>").getBytes(StandardCharsets.UTF_8));
        doReturn(response).when(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }

    @Test void zeroRegisteredDefaultsBlockHttpQuotaStorageAndObservation() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var http=mock(HttpClient.class);var service=service(jdbc,http);
        when(jdbc.queryForObject(contains("android_device_vehicle_assignments"),eq(Long.class))).thenReturn(0L);
        var properties=new LocationCollectionProperties();properties.setEnabled(true);
        var store=mock(GuardUsageStore.class);
        var budget=new TagoQuotaGuard(properties,store,Clock.fixed(Instant.parse("2026-11-05T01:00:00Z"),ZoneId.of("Asia/Seoul")));
        var scheduler=mock(RouteCallPlanScheduler.class);var snapshots=mock(PositionSnapshots.class);
        service.setOperationalExecution(new TagoLocationExecution(properties,budget,mock(LocationCollectorLock.class),ms->{}));
        service.setOperationalScheduler(scheduler);service.setSnapshots(snapshots);
        var logger=(Logger)LoggerFactory.getLogger(TagoVehicleLocationService.class);
        var events=new ListAppender<ILoggingEvent>();events.start();logger.addAppender(events);
        try {
            assertEquals(0,service.pollNextAssignedRoute());
            assertEquals(0,budget.callCount());
            assertEquals("SKIPPED_NO_REGISTERED_VEHICLE",service.status().get("status"));
            assertEquals(1,events.list.stream().filter(e->e.getFormattedMessage().equals("SKIPPED_NO_REGISTERED_VEHICLE")).count());
            verifyNoInteractions(http,store,scheduler,snapshots);
            verify(jdbc,never()).update(anyString(),any(Object[].class));
        } finally {logger.detachAppender(events);events.stop();}
    }

    @Test void zeroRegisteredOverrideMakes105MockCallsButStoresNothing() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var http=mock(HttpClient.class);var service=service(jdbc,http);
        ReflectionTestUtils.setField(service,"alwaysCall",true);
        when(jdbc.queryForObject(contains("android_device_vehicle_assignments"),eq(Long.class))).thenReturn(0L);
        routesAndResponse(jdbc,http);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        var snapshots=mock(PositionSnapshots.class);service.setSnapshots(snapshots);
        try(var throttle=mockStatic(TagoApiRequestThrottle.class)) {
            for(int i=0;i<105;i++) assertEquals(0,service.pollNextAssignedRoute());
            throttle.verify(TagoApiRequestThrottle::awaitRequestSlot,times(105));
        }
        verify(http,times(105)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        verify(jdbc,times(105)).update(contains("JOIN android_device_vehicle_assignments a"),any(Object[].class));
        verifyNoInteractions(snapshots);
        assertEquals(0,service.status().get("lastSavedCount"));
    }

    @Test void eligibleRegisteredVehicleCallsAndStoresResponse() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var http=mock(HttpClient.class);var service=service(jdbc,http);
        when(jdbc.queryForObject(contains("android_device_vehicle_assignments"),eq(Long.class))).thenReturn(1L);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        routesAndResponse(jdbc,http);
        var snapshots=mock(PositionSnapshots.class);service.setSnapshots(snapshots);
        try(var throttle=mockStatic(TagoApiRequestThrottle.class)) {assertEquals(1,service.pollNextAssignedRoute());}
        verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        verify(snapshots,times(1)).observe(any(PositionSnapshots.Position.class),eq(false));
        verify(jdbc).queryForObject(argThat(s->s.contains("v.active = true")&&s.contains("a.vehicle_number = v.vehicle_number")),eq(Long.class));
    }

    @Test void assignmentWithoutVehicleDoesNotPassEligibilityGuard() throws Exception { assertIneligible(); }
    @Test void assignmentWithInactiveVehicleDoesNotPassEligibilityGuard() throws Exception { assertIneligible(); }
    private void assertIneligible() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var http=mock(HttpClient.class);
        when(jdbc.queryForObject(contains("android_device_vehicle_assignments"),eq(Long.class))).thenReturn(0L);
        assertEquals(0,service(jdbc,http).pollNextAssignedRoute());
        verifyNoInteractions(http);verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(jdbc).queryForObject(argThat(s->s.contains("v.active = true")&&s.contains("a.vehicle_number = v.vehicle_number")),eq(Long.class));
    }

    @Test void laneGuardDoesNotConsumeScheduledSlotOrObservationState() {
        var jdbc=mock(JdbcTemplate.class);var http=mock(HttpClient.class);var service=service(jdbc,http);
        when(jdbc.queryForObject(contains("android_device_vehicle_assignments"),eq(Long.class))).thenReturn(0L);
        var execution=mock(TagoLocationExecution.class);when(execution.locationTimeAllowed()).thenReturn(true);
        var scheduler=mock(RouteCallPlanScheduler.class);var snapshots=mock(PositionSnapshots.class);
        service.setOperationalExecution(execution);service.setOperationalScheduler(scheduler);service.setSnapshots(snapshots);
        service.pollLaneA();service.pollLaneB();service.pollLaneC();
        verifyNoInteractions(http,scheduler,snapshots);
        verify(execution,times(3)).locationTimeAllowed();verifyNoMoreInteractions(execution);
    }

    @Test void alwaysCallDefaultsToFalseInCodeAndConfiguration() throws Exception {
        assertEquals(false,ReflectionTestUtils.getField(service(mock(JdbcTemplate.class),mock(HttpClient.class)),"alwaysCall"));
        var properties=new Properties();
        try(var stream=getClass().getResourceAsStream("/application.properties")) {assertNotNull(stream);properties.load(stream);}
        assertEquals("${TAGO_LOCATION_ALWAYS_CALL:false}",properties.getProperty("tago.location.always-call"));
    }
}
