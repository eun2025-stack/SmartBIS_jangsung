package com.smartbis.backend.tago;

import com.smartbis.backend.arrival.*;
import java.time.*;
import java.util.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class Rev17SpecificationTest {
    static final ZoneId KST=ZoneId.of("Asia/Seoul");
    static final LocalDate DAY=LocalDate.of(2026,11,3);
    static final Instant NOW=DAY.atTime(10,0).atZone(KST).toInstant();
    static LocationCollectionProperties settings() {
        var p=new LocationCollectionProperties(); p.setEnabled(true);
        p.getLocal().setEnabled(true);p.getHundred().setEnabled(true);
        p.getLocal().setRouteIds(new ArrayList<>(RouteTimetable.DEFAULT.regular()));
        p.getHundred().setRouteIds(new ArrayList<>(RouteTimetable.DEFAULT.laneA()));
        p.setVariantRouteIds(new ArrayList<>(RouteTimetable.DEFAULT.variant()));
        return p;
    }
    static List<RouteCallSchedule.Plan> plans() {
        var d=RouteTimetable.DEFAULT;
        return d.all().stream().sorted().map(r->new RouteCallSchedule.Plan(r,
            d.regular().contains(r)||(!d.laneA().contains(r)&&!d.variant().contains(r))?"관내":"100번",
            true,d.windows(r,DAY,settings()),null,new RouteCallSchedule.Window(21600,75600,1800),null)).toList();
    }
    @Test void t2NightIsRejectedByActualExecutionGateAndLogged() {
        var logger=(Logger)LoggerFactory.getLogger(TagoLocationExecution.class);
        var events=new ListAppender<ILoggingEvent>();events.start();logger.addAppender(events);
        try {
            var store=mock(GuardUsageStore.class);var calls=new AtomicInteger();
            var executor=new TagoLocationExecution(settings(),new TagoQuotaGuard(settings(),store,
                Clock.fixed(DAY.atTime(21,15).atZone(KST).toInstant(),KST)),mock(LocationCollectorLock.class),ms->{});
            assertThrows(IllegalStateException.class,()->executor.executeLane("R",RouteCallSchedule.Role.BACKUP,2000,()->calls.incrementAndGet()));
            assertEquals(0,calls.get());verifyNoInteractions(store);
            assertTrue(events.list.stream().anyMatch(e->e.getFormattedMessage().contains("outside fixed")));
        } finally {logger.detachAppender(events);events.stop();}
    }
    @Test void t4RealLaneCScheduleHasThirtySequential105RouteRounds() {
        var slots=RouteCallSchedule.compileLanes(plans(),DAY,KST,settings()).slots().stream()
            .filter(s->s.roles().contains(RouteCallSchedule.Role.BACKUP)).toList();
        assertEquals(3150,slots.size());
        for(int i=1;i<105;i++) assertEquals(2,Duration.between(slots.get(i-1).at(),slots.get(i).at()).getSeconds());
        assertEquals(1800,Duration.between(slots.get(0).at(),slots.get(105).at()).getSeconds());
    }
    @Test void t5RateLimitStepsAreLoggedByRuntimeScheduler() {
        var logger=(Logger)LoggerFactory.getLogger(RouteCallPlanScheduler.class);
        var events=new ListAppender<ILoggingEvent>();events.start();logger.addAppender(events);
        try {
            var scheduler=new RouteCallPlanScheduler(mock(JdbcRouteCallPlanStore.class),Clock.fixed(NOW,KST),KST,settings());
            assertEquals(2000,scheduler.gapMillis(RouteCallSchedule.Role.BACKUP));
            scheduler.laneCRateLimited();assertEquals(3000,scheduler.gapMillis(RouteCallSchedule.Role.BACKUP));
            scheduler.laneCRateLimited();assertEquals(5000,scheduler.gapMillis(RouteCallSchedule.Role.BACKUP));
            assertEquals(2,events.list.stream().filter(e->e.getFormattedMessage().contains("backoff increased")).count());
            RouteTimetable.DEFAULT.all().forEach(scheduler::laneCSucceeded);
            assertEquals(3000,scheduler.gapMillis(RouteCallSchedule.Role.BACKUP));
            RouteTimetable.DEFAULT.all().forEach(scheduler::laneCSucceeded);
            assertEquals(2000,scheduler.gapMillis(RouteCallSchedule.Role.BACKUP));
        } finally {logger.detachAppender(events);events.stop();}
    }
    @Test void t10WindowClosesFiveMinutesAfterFirstTerminalAndSixtyMinutesWithoutOne() {
        var window=LocationLaneSchedule.window(7*3600+15*60,15,60,600);
        var normal=LocationLaneSchedule.close(window,7*3600+20*60,5);
        assertEquals(7*3600+25*60,normal.end());
        assertFalse(LocationLaneSchedule.locationCallsAllowed(LocalTime.of(7,25),normal.start(),normal.end()));
        var missing=LocationLaneSchedule.close(window,null,5);
        assertEquals(8*3600,missing.end());
        assertFalse(LocationLaneSchedule.locationCallsAllowed(LocalTime.of(8,0),missing.start(),missing.end()));
        assertEquals(window,LocationLaneSchedule.close(window,6*3600,5));
    }
    @Test void t10RuntimeScheduleDropsCallsAfterTerminalClose() {
        var p=settings();
        var initial=RouteCallSchedule.compileLanes(plans(),DAY,KST,p);
        var terminal=DAY.atTime(7,20).atZone(KST).toInstant();
        var closed=RouteCallSchedule.compileLanes(plans(),DAY,KST,p,
            (route,w)->w.start()<=7*3600+20*60 && w.end()>7*3600+20*60?terminal:null);
        assertTrue(closed.summary().local()<initial.summary().local());
        for(String route:RouteTimetable.DEFAULT.laneB()) {
            for(var window:RouteTimetable.DEFAULT.windows(route,DAY,p)) {
                if(window.start()>7*3600+20*60 || window.end()<=7*3600+20*60) continue;
                assertTrue(closed.slots().stream().noneMatch(s->s.routeId().equals(route)
                    && s.roles().contains(RouteCallSchedule.Role.LOCAL_WINDOW)
                    && !s.at().isBefore(terminal.plusSeconds(300))
                    && s.at().isBefore(DAY.atStartOfDay(KST).toInstant().plusSeconds(window.end()))));
            }
        }
    }
    @Test void t11OddExceptionsAndAbsoluteEvenStopKeepOneVisitCap() {
        for(int eta:new int[]{20,90,179,180,360}) {
            assertTrue(ArrivalVisitPolicy.shouldQuery(2,1,1,0,false,false,2));
            assertFalse(ArrivalVisitPolicy.shouldQuery(2,1,1,1,false,false,2));
            assertNull(ArrivalRulePolicy.defaults().nextQuery(NOW,eta,1));
        }
        assertTrue(ArrivalVisitPolicy.shouldQuery(1,0,0,0,true,false,2));
        assertTrue(ArrivalVisitPolicy.shouldQuery(3,0,1,0,false,true,2));
        assertFalse(ArrivalVisitPolicy.shouldQuery(3,0,1,0,false,false,2));
        assertFalse(ArrivalVisitPolicy.shouldQuery(1,0,0,1,true,false,2));
        assertFalse(ArrivalVisitPolicy.shouldQuery(7,7,7,0,false,false,2));
        assertTrue(ArrivalVisitPolicy.shouldQuery(8,7,7,0,false,false,2));
        assertTrue(ArrivalVisitPolicy.shouldQuery(10,7,8,0,false,false,2));
        assertFalse(ArrivalVisitPolicy.shouldQuery(10,7,11,0,false,false,2));
        assertThrows(IllegalArgumentException.class,()->ArrivalVisitPolicy.shouldQuery(9,7,8,0,false,false,3));
    }
    @Test void t11NoResponseUsesHourlyLearningThenPerStopPlaceholder() {
        var jdbc=mock(JdbcTemplate.class);
        var estimator=new ArrivalTravelEstimator(jdbc);
        assertEquals(216,estimator.estimateSeconds("R",3,6,NOW));
        when(jdbc.queryForObject(anyString(),eq(Double.class),any(Object[].class))).thenReturn(150.0);
        assertEquals(150,estimator.estimateSeconds("R",3,6,NOW));
    }
    @Test void t12CanonicalIdMatchingAndUnknownFlag() {
        assertEquals(new RouteIdNormalizer.Match("TSB342000033",true),
            RouteIdNormalizer.match("TBS342000033",RouteTimetable.DEFAULT.all()));
        assertFalse(RouteIdNormalizer.match("TBS342999999",RouteTimetable.DEFAULT.all()).known());
    }
    @Test void t13ObservationSwitchControlsPositionHistoryAndSyntheticRowsCannotChangeState() throws Exception {
        for(boolean enabled:new boolean[]{false,true}) {
            var jdbc=mock(JdbcTemplate.class);
            var service=new TagoVehicleLocationService(jdbc,"","","","",false,false,10000,true,enabled,mock(HttpClient.class));
            if(enabled) when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
            var trace=new TagoVehicleLocationService.PositionTrace(java.util.UUID.randomUUID(),NOW,NOW,"C");
            var position=new TagoVehicleLocationService.VehiclePosition("V","R","S",0,null,null,null,"{}",trace);
            var method=TagoVehicleLocationService.class.getDeclaredMethod("persist",TagoVehicleLocationService.VehiclePosition.class);
            method.setAccessible(true);assertEquals(enabled,method.invoke(service,position));
            if(enabled) verify(jdbc).update(contains("TAGO_OBSERVATION"),any(Object[].class));
            else verifyNoInteractions(jdbc);
        }
        var snapshots=new PositionSnapshots();
        snapshots.observe(new PositionSnapshots.Position("V","R","S",3,null,null,NOW),false);
        snapshots.observe(new PositionSnapshots.Position("V","R","T",4,null,null,NOW.plusSeconds(20)),true);
        assertEquals(3,snapshots.all().get(0).order());
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void t13ArrivalResponsesAreSavedOnlyWithObservationMode() throws Exception {
        for(boolean observation:new boolean[]{false,true}) {
            var jdbc=mock(JdbcTemplate.class);var predictions=mock(ArrivalService.class);var http=mock(HttpClient.class);
            var response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("<response><header><resultCode>00</resultCode></header><body><items><item><routeid>R</routeid><arrtime>120</arrtime></item></items></body></response>".getBytes(StandardCharsets.UTF_8));
            when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
            doReturn(List.of(new TagoArrivalEtaService.ArrivalTarget("R","S","V"))).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
            var service=new TagoArrivalEtaService(jdbc,predictions,"mock","AUTO","36450","http://mock.invalid",true,false,true,observation,http,Clock.fixed(NOW,KST));
            ArrivalSeparationTest.allowBudget(service);
            var visits=mock(ArrivalVisitStore.class);
            when(visits.claim(anyString(),anyString(),anyString(),anyString(),any())).thenReturn(new ArrivalVisitStore.Visit(1,NOW,null));
            when(visits.read("V")).thenReturn(new ArrivalVisitStore.Visit(0,null,null),new ArrivalVisitStore.Visit(1,NOW,NOW.plusSeconds(120)));
            ReflectionTestUtils.setField(service,"visits",visits);
            service.pollNextAssignedArrival();
            verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
            if(observation) verify(predictions).collectPrediction("V","R","S",NOW,NOW.plusSeconds(120));
            else verify(predictions,never()).collectPrediction(anyString(),anyString(),anyString(),any(),any());
        }
    }
    @Test void t14IndependentPersistedBudgetsStopAt9500And9000AndLog() throws Exception {
        var logger=(Logger)LoggerFactory.getLogger(TagoQuotaGuard.class);
        var events=new ListAppender<ILoggingEvent>();events.start();logger.addAppender(events);
        try {
            var store=mock(GuardUsageStore.class);
            when(store.read("TAGO_LOCATION",DAY)).thenReturn(9500);
            when(store.read("TAGO_ARRIVAL",DAY)).thenReturn(9000);
            var location=new TagoQuotaGuard(settings(),store,Clock.fixed(NOW,KST));
            var arrival=new ArrivalApiBudget(true,9000,store,Clock.fixed(NOW,KST));
            assertFalse(location.ready());assertFalse(arrival.ready());
            var calls=new AtomicInteger();
            when(store.reserve(anyString(),eq(DAY),anyInt(),eq(NOW))).thenReturn(-1);
            assertThrows(IllegalStateException.class,()->arrival.execute(()->calls.incrementAndGet()));
            assertEquals(0,calls.get());
            assertEquals(9500,location.callCount());assertEquals(9000,arrival.count());
            var restarted=new TagoQuotaGuard(settings(),store,Clock.fixed(NOW,KST));restarted.initialize();
            assertEquals(9500,restarted.callCount());
            assertTrue(events.list.stream().anyMatch(e->e.getFormattedMessage().contains("TAGO_LOCATION")&&e.getFormattedMessage().contains("budget reached")));
            assertTrue(events.list.stream().anyMatch(e->e.getFormattedMessage().contains("TAGO_ARRIVAL")&&e.getFormattedMessage().contains("budget reached")));
        } finally {logger.detachAppender(events);events.stop();}
    }
    @org.junit.jupiter.api.Tag("source-material")
    @Test void t15ExactDisjointTargetsAndArrivalCountFormula() throws Exception {
        var data=RouteTimetable.DEFAULT;
        assertEquals(3,data.laneA().size());assertEquals(100,data.laneB().size());assertEquals(105,data.all().size());
        assertTrue(Collections.disjoint(data.laneA(),data.laneB()));
        assertFalse(data.laneB().contains("TSB342000006"));assertFalse(data.laneB().contains("TSB342000074"));
        assertEquals(12,RouteTimetable.arrivalUpperBound(3,7));
        var counts=new HashMap<String,Integer>();
        var stopFile=java.nio.file.Path.of("../../노선 및 운행 관련 자료/군민운수 제공 노선 및 운행 관련 자료/TAGO_장성군_노선별_경유정류소_20261002.csv");
        for(String line:java.nio.file.Files.readAllLines(stopFile)) {
            var cells=line.split(",",-1);
            if(cells.length>1 && cells[1].startsWith("TSB")) counts.merge(cells[1],1,Integer::sum);
        }
        long weekday=0,holiday=0;
        for(String route:data.all()) {
            for(var departure:data.departures(route,DAY,Set.of())) weekday+=RouteTimetable.arrivalUpperBound(1,counts.get(route));
            for(var departure:data.departures(route,LocalDate.of(2026,11,7),Set.of())) holiday+=RouteTimetable.arrivalUpperBound(1,counts.get(route));
        }
        assertEquals(7337,weekday);assertEquals(2567,holiday);
        var day=RouteCallSchedule.compileLanes(plans(),DAY,KST,settings());
        assertEquals(288,day.summary().hundred());assertEquals(3150,day.summary().backup());
        assertTrue(day.summary().uniqueCalls()<=5692);
    }
}
