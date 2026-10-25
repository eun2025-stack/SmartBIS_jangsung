package com.smartbis.backend.tago;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RouteCallPlanTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalDate DAY = LocalDate.of(2026,10,23);
    static final Instant NOW = DAY.atTime(10,0).atZone(KST).toInstant();
    RouteCallSchedule.Plan plan(boolean enabled, Instant last) {
        return new RouteCallSchedule.Plan("R", "관내", enabled,
            List.of(new RouteCallSchedule.Window(9*3600,11*3600,600)),null,
            new RouteCallSchedule.Window(0,86400,1800),last);
    }
    List<RouteCallSchedule.Plan> fixture() throws Exception {
        var json = new ObjectMapper().readTree(Files.readString(Path.of("../../reports/route-plan/2026-10-23/route_plan_manifest.json")));
        var result = new ArrayList<RouteCallSchedule.Plan>();
        for (var row : json.get("routes")) {
            var windows = new ArrayList<RouteCallSchedule.Window>();
            for (var w : row.get("windows")) windows.add(new RouteCallSchedule.Window(RouteCallSchedule.seconds(w.get("start").asText()),
                RouteCallSchedule.seconds(w.get("end").asText()),w.get("interval_seconds").asInt()));
            var s = row.get("hundred_service");
            var service = s.isNull() ? null : new RouteCallSchedule.Window(RouteCallSchedule.seconds(s.get("start").asText()),
                RouteCallSchedule.seconds(s.get("end").asText()),s.get("interval_seconds").asInt());
            var b = row.get("backup_sweep");
            result.add(new RouteCallSchedule.Plan(row.get("route_id").asText(),row.get("route_group").asText(),false,windows,service,
                new RouteCallSchedule.Window(RouteCallSchedule.seconds(b.get("start").asText()),RouteCallSchedule.seconds(b.get("end").asText()),b.get("interval_seconds").asInt()),null));
        }
        return result;
    }
    @Test void adoptedPlanPreviewHasAllThreeComponentsAndAtMost8679Calls() throws Exception {
        var plans = fixture();
        var schedule = RouteCallSchedule.compile(plans,DAY,KST,true);
        assertEquals(105,plans.size());
        assertEquals(17,plans.stream().filter(p->p.group().equals("100번")).count());
        assertEquals(2058,schedule.summary().local()); assertEquals(1581,schedule.summary().hundred());
        assertEquals(5040,schedule.summary().backup()); assertEquals(8679,schedule.summary().total());
        assertTrue(schedule.summary().uniqueCalls()<=8679);
        System.out.println("DB plan mock budget: "+schedule.summary());
        assertTrue(schedule.slots().stream().filter(s->s.roles().contains(RouteCallSchedule.Role.HUNDRED))
            .allMatch(s->{int sec=s.at().atZone(KST).toLocalTime().toSecondOfDay(); return sec>=19800 && sec<75600;}));
        assertTrue(RouteCallSchedule.compile(plans,DAY,KST,false).slots().isEmpty());
    }
    @Test void coincidentWindowAndSweepAreDeduplicated() {
        var result=RouteCallSchedule.compile(List.of(plan(true,null)),DAY,KST,false);
        var atTen=result.slots().stream().filter(s->s.at().equals(NOW)).toList();
        assertEquals(1,atTen.size()); assertEquals(2,atTen.get(0).roles().size());
    }
    @Test void endExclusiveMidnightAndInvalidWindow() {
        assertEquals(86400,RouteCallSchedule.seconds("24:00"));
        assertThrows(IllegalArgumentException.class,()->RouteCallSchedule.seconds("24:01"));
        var result=RouteCallSchedule.compile(List.of(plan(true,null)),DAY,KST,false);
        assertTrue(result.slots().stream().allMatch(s->s.at().isBefore(DAY.plusDays(1).atStartOfDay(KST).toInstant())));
        assertThrows(IllegalArgumentException.class,()->new RouteCallSchedule.Window(10,5,600));
        assertThrows(IllegalArgumentException.class,()->new RouteCallSchedule.Window(0,600,0));
    }
    @Test void disabledPlansNeverClaimOrProduceARequest() {
        var store=mock(JdbcRouteCallPlanStore.class); when(store.read()).thenReturn(List.of(plan(false,null)));
        assertTrue(new RouteCallPlanScheduler(store,Clock.fixed(NOW,KST),KST).next().isEmpty());
        verify(store,never()).claim(any(),any());
    }
    @Test void enabledDueSlotUsesDatabaseClaimAndDoesNotReplayPreviousAttempt() {
        var store=mock(JdbcRouteCallPlanStore.class); when(store.read()).thenReturn(List.of(plan(true,NOW.minusSeconds(600))));
        when(store.claim(any(),eq(NOW))).thenReturn(true);
        assertEquals("R",new RouteCallPlanScheduler(store,Clock.fixed(NOW,KST),KST).next().orElseThrow());
        when(store.read()).thenReturn(List.of(plan(true,NOW)));
        assertTrue(new RouteCallPlanScheduler(store,Clock.fixed(NOW,KST),KST).next().isEmpty());
    }
    @Test void lostClaimNeverSchedulesHttpAndExpiredSlotsAreNotReplayed() {
        var store=mock(JdbcRouteCallPlanStore.class); when(store.read()).thenReturn(List.of(plan(true,null)));
        when(store.claim(any(),any())).thenReturn(false);
        assertTrue(new RouteCallPlanScheduler(store,Clock.fixed(NOW,KST),KST).next().isEmpty());
        var captor=org.mockito.ArgumentCaptor.forClass(RouteCallSchedule.Slot.class);
        verify(store,atLeastOnce()).claim(captor.capture(),eq(NOW));
        assertTrue(captor.getAllValues().stream().allMatch(s->NOW.isBefore(s.expiresAt())));
    }
    @Test void invalidHundredPlanAndDuplicateRoutesFailClosed() {
        var bad=new RouteCallSchedule.Plan("H","100번",true,List.of(),null,new RouteCallSchedule.Window(0,86400,1800),null);
        assertThrows(IllegalArgumentException.class,()->RouteCallSchedule.compile(List.of(bad),DAY,KST,false));
        assertThrows(IllegalArgumentException.class,()->RouteCallSchedule.compile(List.of(plan(true,null),plan(true,null)),DAY,KST,false));
    }
    @Test void hardBudgetAndRetrySettingsCannotBeRaisedAbove9500OrTwo() {
        var p=new LocationCollectionProperties(); p.setDailyLimit(9501);
        assertThrows(IllegalArgumentException.class,p::validate);
        p.setDailyLimit(9500); p.setMaxRetries(3); assertThrows(IllegalArgumentException.class,p::validate);
        p.setMaxRetries(2); p.setRetryDelayMs(9999); assertThrows(IllegalArgumentException.class,p::validate);
    }
    @Test void failuresPersistReasonAndAllThreeAttemptsConsumeUsage() throws Exception {
        var p=new LocationCollectionProperties(); p.setEnabled(true);
        var store=new LocationCollectionOperationsTest.PersistedStore();
        var clock=new LocationCollectionOperationsTest.MutableClock();
        var recorder=mock(TagoLocationExecution.FailureRecorder.class);
        var guard=new TagoQuotaGuard(p,store,clock);
        var executor=new TagoLocationExecution(p,guard,mock(LocationCollectorLock.class),clock::advance,recorder);
        AtomicInteger calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->executor.execute("R",()->{calls.incrementAndGet();throw new IllegalStateException("TAGO_HTTP_503");}));
        assertEquals(3,calls.get()); assertEquals(3,guard.callCount());
        verify(recorder,times(3)).record("R","TAGO_HTTP_503");
        assertEquals(Instant.parse("2026-10-22T14:59:20Z"),clock.instant());
    }
    @Test void quotaBlocksBeforeAnyNextMockRequestAndRestartRestoresCount() {
        var p=new LocationCollectionProperties(); p.setEnabled(true);
        var store=new LocationCollectionOperationsTest.PersistedStore();
        var clock=new LocationCollectionOperationsTest.MutableClock();
        store.counts.put(LocalDate.of(2026,10,22),9500);
        var guard=new TagoQuotaGuard(p,store,clock); guard.initialize();
        var executor=new TagoLocationExecution(p,guard,mock(LocationCollectorLock.class),clock::advance);
        assertThrows(IllegalStateException.class,()->executor.execute("R",()->fail("No HTTP")));
        assertEquals(9500,guard.callCount());
        var restarted=new TagoQuotaGuard(p,store,clock); restarted.initialize();
        assertEquals(9500,restarted.callCount()); assertEquals(TagoQuotaGuard.Decision.DAILY_LIMIT,restarted.acquire());
    }
    @Test void failureRecorderNeverStoresUrlsOrCredentials() {
        assertEquals("IOException",TagoLocationExecution.failureReason(new IOException("https://secret/path?serviceKey=secret")));
        assertEquals("TAGO_RESULT_MISSING",TagoLocationExecution.failureReason(new IllegalStateException("TAGO_RESULT_MISSING")));
    }
    @Test void jdbcClaimsOnlyEnabledRowsAndRecordsFailureReason() {
        var jdbc=mock(JdbcTemplate.class); var store=new JdbcRouteCallPlanStore(jdbc);
        var slot=new RouteCallSchedule.Slot("R",NOW,NOW.plusSeconds(600),Set.of(RouteCallSchedule.Role.BACKUP));
        when(jdbc.update(anyString(),any(),any(),any())).thenReturn(1);
        assertTrue(store.claim(slot,NOW));
        verify(jdbc).update(contains("enabled=true"),eq(Timestamp.from(NOW)),eq("R"),eq(Timestamp.from(NOW)));
        store.failure("R","TAGO_HTTP_503");
        verify(jdbc).update(contains("failure_reason=?"),eq("TAGO_HTTP_503"),eq("R"));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    @Test void serviceUsesPlannedRouteAndGuardWithoutLegacyDoubleWait() throws Exception {
        var jdbc=mock(JdbcTemplate.class); var http=mock(HttpClient.class); var response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("<response><header><resultCode>00</resultCode></header><body><totalCount>0</totalCount><items/></body></response>".getBytes(StandardCharsets.UTF_8));
        doReturn(response).when(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        var repo=mock(JdbcRouteCallPlanStore.class); when(repo.read()).thenReturn(List.of(plan(true,null)));
        when(repo.claim(any(),any())).thenReturn(true);
        var scheduler=new RouteCallPlanScheduler(repo,Clock.fixed(NOW,KST),KST);
        var p=new LocationCollectionProperties(); p.setEnabled(true);
        var guard=new TagoQuotaGuard(p,new LocationCollectionOperationsTest.PersistedStore(),Clock.fixed(NOW,KST));
        var service=new TagoVehicleLocationService(jdbc,"mock-key","AUTO","36450","http://localhost",true,true,10000,false,false,http);
        service.setOperationalExecution(new TagoLocationExecution(p,guard,mock(LocationCollectorLock.class),ms->{}));
        service.setOperationalScheduler(scheduler);
        try(var throttle=mockStatic(TagoApiRequestThrottle.class)) {
            assertEquals(0,service.pollNextAssignedRoute());
            assertEquals(0,service.pollNextAssignedRoute());
            throttle.verifyNoInteractions();
        }
        verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        verifyNoInteractions(jdbc); assertEquals(1,guard.callCount());
        verify(repo,times(1)).read(); // TOO_SOON never consumes the next plan slot.
    }
    @Test void disabledServicePlanHasZeroMockHttpCalls() throws Exception {
        var http=mock(HttpClient.class); var jdbc=mock(JdbcTemplate.class); var repo=mock(JdbcRouteCallPlanStore.class);
        when(repo.read()).thenReturn(List.of(plan(false,null)));
        var service=new TagoVehicleLocationService(jdbc,"","AUTO","36450","http://localhost",true,true,10000,false,false,http);
        var p=new LocationCollectionProperties(); p.setEnabled(true);
        service.setOperationalExecution(new TagoLocationExecution(p,new TagoQuotaGuard(p,new LocationCollectionOperationsTest.PersistedStore(),Clock.fixed(NOW,KST)),mock(LocationCollectorLock.class),ms->{}));
        service.setOperationalScheduler(new RouteCallPlanScheduler(repo,Clock.fixed(NOW,KST),KST));
        assertEquals(0,service.pollNextAssignedRoute()); verifyNoInteractions(http); verifyNoInteractions(jdbc);
    }
    @Test void migrationOnlyAddsMetadataAndDoesNotEnableAnything() throws Exception {
        String sql=Files.readString(Path.of("../database/migrations/V016__route_call_plan_windows.sql")).replaceAll("(?m)--.*$","");
        assertTrue(sql.contains("ALTER TABLE public.route_call_plan"));
        assertFalse(sql.matches("(?s).*\\b(INSERT|UPDATE|DELETE|DROP|TRUNCATE)\\b.*"));
        assertFalse(sql.contains("enabled=true")); assertTrue(sql.contains("failure_reason"));
    }
}
