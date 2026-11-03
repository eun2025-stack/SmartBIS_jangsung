package com.smartbis.backend.tago;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.net.http.HttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.smartbis.backend.arrival.ArrivalService;

class LocalCollectionControlTest {
    @TempDir Path directory;
    private final UUID id=UUID.randomUUID();
    private LocalCollectionControl control;
    private LocalCollectionControl.Store store;
    @BeforeEach void prepareRun() throws Exception {
        Files.writeString(directory.resolve("run.json"),"""
            {"collection_run_id":"%s","prepared_at":"2026-11-03T00:00:00Z","commit_hash":"%s","config_hash":"%s"}
            """.formatted(id,"a".repeat(40),"b".repeat(64)));
        store=mock(LocalCollectionControl.Store.class);
        control=new LocalCollectionControl(store,directory,Clock.fixed(Instant.parse("2026-11-03T01:00:00Z"),ZoneOffset.UTC));
        control.prepare();
    }
    @Test void runExistsBeforeApprovalAndDefaultSwitchIsOff() throws Exception {
        assertEquals(id,control.runId());assertTrue(Files.exists(directory.resolve("ready.json")));
        assertFalse(control.callsAllowed());verify(store).prepare(any());verify(store,never()).activate(any(),any());
    }
    @Test void wrongRunCannotEnableAndRemovingSwitchBlocksAgain() throws Exception {
        Files.writeString(directory.resolve("enabled"),UUID.randomUUID().toString());assertFalse(control.callsAllowed());
        Files.writeString(directory.resolve("enabled"),id.toString());assertTrue(control.callsAllowed());
        Files.writeString(directory.resolve("enabled"),"off");assertFalse(control.callsAllowed());
    }
    @Test void disabledSchedulersAndDirectHttpMakeZeroCalls() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var http=mock(HttpClient.class);
        var location=new TagoVehicleLocationService(jdbc,"fixture","","fixture","https://invalid.test",true,true,10000,false,true,http);
        ReflectionTestUtils.setField(location,"localControl",control);
        location.pollLaneA();location.pollLaneB();location.pollLaneC();location.pollScheduled();
        var send=TagoVehicleLocationService.class.getDeclaredMethod("sendDirectRequest",java.net.http.HttpRequest.class);send.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->send.invoke(location,new Object[]{null}));
        var arrival=new TagoArrivalEtaService(jdbc,"fixture","","fixture","https://invalid.test",true,false,true,true);
        ReflectionTestUtils.setField(arrival,"localControl",control);ReflectionTestUtils.setField(arrival,"http",http);arrival.pollNextAssignedArrival();
        verifyNoInteractions(http,jdbc);
    }
    @Test void everyPositionReceivesPreparedRunId() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        var service=new TagoVehicleLocationService(jdbc,"","","","",false,false,10000,true,true,mock(HttpClient.class));
        ReflectionTestUtils.setField(service,"captureMetadata",true);ReflectionTestUtils.setField(service,"localControl",control);
        var persist=TagoVehicleLocationService.class.getDeclaredMethod("persist",TagoVehicleLocationService.VehiclePosition.class);persist.setAccessible(true);
        var trace=new TagoVehicleLocationService.PositionTrace(UUID.randomUUID(),Instant.now(),Instant.now(),"A");
        persist.invoke(service,new TagoVehicleLocationService.VehiclePosition("fixture","R","S",0,null,null,null,"{}",trace));
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);verify(jdbc).update(contains("collection_run_id"),args.capture());
        assertEquals(id,args.getValue()[args.getValue().length-1]);
    }
    @Test void finalArrivalReceivesSameRunEvenWithoutGeometry() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(1L);
        var meta=new ArrivalService.Observation(Instant.now(),2,UUID.randomUUID(),0,true,false,"",id);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new ArrivalService(jdbc).collectPrediction("fixture","R","S",Instant.now(),Instant.now(),meta));
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(contains("collection_run_id"),eq(Long.class),args.capture());
        assertEquals(id,args.getValue()[args.getValue().length-1]);
    }
    @Test void legacySentinelCannotBecomeLiveRun() {
        assertThrows(IllegalArgumentException.class,()->new LocalCollectionControl.Run(new UUID(0,0),Instant.now(),"a".repeat(40),"b".repeat(64)));
    }
    @Test void pauseAndProcessIdentityAreSeparatedFromCollectionRun() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var ds=mock(javax.sql.DataSource.class);var connection=mock(java.sql.Connection.class);
        when(jdbc.getDataSource()).thenReturn(ds);when(ds.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        when(jdbc.update(startsWith("UPDATE collection_process_runs"),any(Object[].class))).thenReturn(1);
        var persistence=new LocalCollectionLifecycle.JdbcSessionStore(jdbc,()->id);
        persistence.stop(UUID.randomUUID(),Instant.now(),"시스템 적용");
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(startsWith("INSERT INTO collection_pause_intervals"),args.capture());
        assertEquals(id,args.getValue()[2]);verify(connection).commit();
    }
    @Test void sameRunCanBePreparedAgainWithoutAuthorizingHttp() throws Exception {
        control.prepare();verify(store,times(1)).prepare(any());
        assertEquals(id,control.runId());assertFalse(control.callsAllowed());
    }
    @Test void localCatalogCannotCallEvenAfterApproval() throws Exception {
        Files.writeString(directory.resolve("enabled"),id.toString());
        // The catalog request guard is before all HTTP request construction/send.
        String code=Files.readString(Path.of("src/main/java/com/smartbis/backend/tago/TagoCatalogService.java"));
        int request=code.indexOf("private Document requestOnce");
        assertTrue(code.indexOf("LOCAL_CATALOG_DISABLED",request)<code.indexOf("http.send",request));
    }
}
