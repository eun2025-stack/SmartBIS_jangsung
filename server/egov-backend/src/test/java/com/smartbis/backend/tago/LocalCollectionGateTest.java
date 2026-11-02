package com.smartbis.backend.tago;

import com.smartbis.backend.arrival.ArrivalService;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.net.http.*;
import javax.sql.DataSource;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LocalCollectionGateTest {
    static final Instant END=Instant.parse("2026-11-08T12:00:00Z");
    @Test void wrongDatabaseIsRejectedBeforeAnyBeanOrConnection() {
        var environment=new org.springframework.mock.env.MockEnvironment()
            .withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:5432/smartbis_db");
        var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerBeanDefinition("neverCreated",new org.springframework.beans.factory.support.RootBeanDefinition(JdbcTemplate.class));
        assertThrows(IllegalStateException.class,()->LocalCollectionSafetyConfiguration.localDatabaseGuard(environment).postProcessBeanFactory(factory));
        assertEquals(0,factory.getSingletonCount());
    }
    @Test void sundayDeadlineStopsBothApisAtExactBoundary() throws Exception {
        var clock=new LocationCollectionOperationsTest.MutableClock();clock.now=END.minusSeconds(1);
        var deadline=new CollectionDeadline(clock,END);
        var guard=mock(TagoQuotaGuard.class);
        when(guard.now()).thenAnswer(i->clock.instant());
        when(guard.acquire(anyString(),anyLong())).thenReturn(TagoQuotaGuard.Decision.ALLOWED);
        var settings=new LocationCollectionProperties();settings.setEnabled(true);
        var location=new TagoLocationExecution(settings,guard,mock(LocationCollectorLock.class),clock::advance);
        location.setDeadline(deadline);
        var arrival=new ArrivalApiBudget(true,9000,new ArrivalRetryTest.Store(),clock,2,10000,clock::advance);
        arrival.setDeadline(deadline);
        assertEquals(1,location.executeLane("R",RouteCallSchedule.Role.HUNDRED,10000,()->1));
        assertEquals(1,arrival.execute(()->1));
        clock.advance(1000);
        assertThrows(IllegalStateException.class,()->location.executeLane("R",RouteCallSchedule.Role.HUNDRED,10000,()->fail("location HTTP after deadline")));
        assertThrows(IllegalStateException.class,()->arrival.execute(()->fail("arrival HTTP after deadline")));
        verify(guard,times(1)).acquire(anyString(),anyLong());
        assertEquals(1,arrival.count());
    }
    @Test void retryCannotCrossSundayDeadline() {
        var clock=new LocationCollectionOperationsTest.MutableClock();clock.now=END.minusSeconds(5);
        var budget=new ArrivalApiBudget(true,9000,new ArrivalRetryTest.Store(),clock,2,10000,clock::advance);
        budget.setDeadline(new CollectionDeadline(clock,END));var calls=new ArrayList<Integer>();
        var error=assertThrows(IllegalStateException.class,()->budget.execute(()->{calls.add(1);throw new IllegalStateException("TAGO_HTTP_503");}));
        assertEquals("COLLECTION_DEADLINE_REACHED",error.getMessage());assertEquals(1,calls.size());
    }
    @Test void normalStopAndDeadlineRecordOnePausePerRun() {
        var clock=new LocationCollectionOperationsTest.MutableClock();clock.now=END.minusSeconds(2);
        var store=mock(CollectionSession.Store.class);
        var session=new CollectionSession(store,clock,new CollectionDeadline(clock,END));
        session.start();session.start();session.tick();clock.advance(2000);session.tick();session.tick();session.stop("기타");
        verify(store,times(1)).start(any(),eq(END.minusSeconds(2)));
        verify(store,times(1)).heartbeat(any(),eq(END.minusSeconds(2)));
        verify(store,times(1)).stop(any(),eq(END),eq("시스템 적용"));
    }
    @Test void unfinishedRunRecoveryRecordsUnknownGapAndClosesOpenPause() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var ds=mock(DataSource.class);var connection=mock(Connection.class);
        when(jdbc.getDataSource()).thenReturn(ds);when(ds.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        when(jdbc.queryForObject("SELECT current_database()",String.class)).thenReturn("smartbis_db_copy2");
        new LocalCollectionLifecycle.JdbcSessionStore(jdbc).start(UUID.randomUUID(),END.minusSeconds(10));
        verify(jdbc).update(contains("MIN(last_heartbeat_at)"),any(Object[].class));
        verify(jdbc).update(contains("stop_reason='기타'"),any(Object[].class));
        verify(jdbc).update(contains("UPDATE collection_pause_intervals SET ended_at"),any(Object[].class));
        verify(connection).commit();
    }
    @Test void localProfileHasCompleteExplicitSafetySettings() throws Exception {
        var properties=new Properties();
        try(var in=getClass().getResourceAsStream("/application-local-collection.properties")) { properties.load(in); }
        assertEquals("true",properties.getProperty("collection.capture-metadata"));
        assertEquals("${LOCAL_COLLECTION_STOP_AT}",properties.getProperty("collection.stop-at"));
        assertEquals("-",properties.getProperty("tago.catalog.sync-cron"));
        assertEquals("9500",properties.getProperty("tago.location.operations.daily-limit"));
        assertEquals("9000",properties.getProperty("tago.arrival.daily-limit"));
        assertEquals("false",properties.getProperty("spring.flyway.enabled"));
    }
    @Test void positionsFromOneCallKeepSameIdentityAndExactTimestamps() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        var service=new TagoVehicleLocationService(jdbc,"","","","",false,false,10000,true,true,mock(HttpClient.class));
        ReflectionTestUtils.setField(service,"captureMetadata",true);
        var trace=new TagoVehicleLocationService.PositionTrace(UUID.randomUUID(),END.minusSeconds(30),END.minusSeconds(29),"B");
        var persist=TagoVehicleLocationService.class.getDeclaredMethod("persist",TagoVehicleLocationService.VehiclePosition.class);persist.setAccessible(true);
        for(String vehicle:List.of("fixture-1","fixture-2")) persist.invoke(service,new TagoVehicleLocationService.VehiclePosition(vehicle,"R","S",0,null,null,null,"{}",trace));
        var args=ArgumentCaptor.forClass(Object[].class);verify(jdbc,times(2)).update(contains("call_id"),args.capture());
        for(Object[] row:args.getAllValues()) {
            assertEquals(Timestamp.from(trace.requested()),row[7]);assertEquals(Timestamp.from(trace.responded()),row[8]);
            assertEquals(trace.id(),row[9]);assertEquals("B",row[10]);
        }
    }
    @Test void recoveredArrivalPreservesActualRemainingStopsAndFinalVisitMetadata() throws Exception {
        var clock=new LocationCollectionOperationsTest.MutableClock();var store=new ArrivalRetryTest.Store();
        var http=mock(HttpClient.class);var predictions=mock(ArrivalService.class);var visits=new ArrivalRetryTest.MemoryVisits();
        var failed=ArrivalRetryTest.response(503);var success=ArrivalRetryTest.response(200);
        when(success.body()).thenReturn("<response><header><resultCode>00</resultCode></header><body><items><item><routeid>R</routeid><arrtime>120</arrtime><arrprevstationcnt>3</arrprevstationcnt></item></items></body></response>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(failed,success);
        var service=ArrivalRetryTest.service(http,predictions,visits,clock,store,new ArrayList<>());
        ReflectionTestUtils.setField(service,"captureMetadata",true);
        service.pollNextAssignedArrival();service.pollNextAssignedArrival();
        var metadata=ArgumentCaptor.forClass(ArrivalService.Observation.class);
        verify(predictions,times(1)).collectPrediction(eq("V"),eq("R"),eq("S"),any(),any(),metadata.capture());
        assertEquals(3,metadata.getValue().remainingStops());assertEquals(1,metadata.getValue().retryCount());
        assertTrue(metadata.getValue().finalResult());assertFalse(metadata.getValue().interpolated());assertNotNull(metadata.getValue().visitId());
    }
    @Test void failedFinalResponseIsSavedEvenWhenGeometryIsUnavailable() {
        var jdbc=mock(JdbcTemplate.class);var at=END.minusSeconds(30);var id=UUID.randomUUID();
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(42L);
        var observation=new ArrivalService.Observation(at,null,id,2,true,false,"TAGO_HTTP_503");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            ()->new ArrivalService(jdbc).collectPrediction("fixture","R","S",at,at.plusSeconds(72),observation));
        var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc,times(1)).queryForObject(contains("RETURNING prediction_id"),eq(Long.class),args.capture());
        assertEquals(id,args.getValue()[7]);assertEquals(2,args.getValue()[8]);
        assertEquals(true,args.getValue()[9]);assertEquals(false,args.getValue()[10]);assertEquals("TAGO_HTTP_503",args.getValue()[11]);
    }
}
