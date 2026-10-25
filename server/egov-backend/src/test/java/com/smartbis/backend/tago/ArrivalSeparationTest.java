package com.smartbis.backend.tago;

import com.smartbis.backend.arrival.*;
import com.smartbis.backend.display.DisplayService;
import java.math.BigDecimal;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ArrivalSeparationTest {
    static final Instant NOW=Instant.parse("2026-10-24T01:00:00Z");
    static ArrivalService.EtaState state(long queriedAgo,long remaining) {
        return new ArrivalService.EtaState(NOW.minusSeconds(queriedAgo),NOW.plusSeconds(remaining));
    }
    static boolean refresh(ArrivalService.EtaState saved) {
        return TagoArrivalEtaService.shouldRefresh(saved,NOW,60000,3);
    }

    @Test void firstQuery() { assertTrue(refresh(null)); }
    @Test void threeMinutesMustWaitForRequeryInterval() {
        assertFalse(refresh(state(59,121))); assertTrue(refresh(state(60,120)));
        assertTrue(refresh(state(60,121)));
    }
    @Test void middleEtaWaitsUntilHalfThenAllowsSingleFollowup() {
        assertFalse(refresh(state(60,119))); assertTrue(refresh(state(178,1)));
    }
    @Test void elapsedTimeStillUnpassedRequeries() {
        assertTrue(refresh(state(60,0))); assertTrue(refresh(state(60,-1)));
    }
    @Test void noEtaDoesNotInventAnArrivalAndUsesSixtySecondBackoff() {
        assertFalse(refresh(new ArrivalService.EtaState(NOW.minusSeconds(59),null)));
        assertTrue(refresh(new ArrivalService.EtaState(NOW.minusSeconds(60),null)));
    }
    @Test void policyValuesAreConfigurableAndValidated() {
        assertFalse(TagoArrivalEtaService.shouldRefresh(state(60,240),NOW,120000,3));
        assertFalse(TagoArrivalEtaService.shouldRefresh(state(60,239),NOW,60000,5));
        assertThrows(IllegalArgumentException.class,()->TagoArrivalEtaService.shouldRefresh(null,NOW,0,3));
    }
    @Test void defaultPolicyLoadsWithoutEnablingCollector() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        try (var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class,()->jdbc);
            context.register(ArrivalService.class,TagoArrivalEtaService.class);
            context.refresh();
            assertEquals("60000",context.getEnvironment().getProperty("tago.arrival.requery-interval-ms"));
            assertEquals("3",context.getEnvironment().getProperty("tago.arrival.refresh-threshold-minutes"));
            verify(jdbc).afterPropertiesSet();
            verifyNoMoreInteractions(jdbc);
            clearInvocations(jdbc); // Spring's bean lifecycle is not a SQL/HTTP collection attempt.
            context.getBean(TagoArrivalEtaService.class).pollNextAssignedArrival();
            verifyNoInteractions(jdbc);
        }
    }
    @Test void migrationOnlyAddsArrivalPersistenceColumnsAndIndex() throws Exception {
        String sql=Files.readString(Path.of("../database/migrations/V018__persist_arrival_eta.sql"));
        assertTrue(sql.contains("ALTER TABLE public.arrival_predictions"));
        assertTrue(sql.contains("estimated_arrival_at TIMESTAMPTZ"));
        assertFalse(sql.matches("(?s).*\\b(UPDATE|INSERT|DELETE|TRUNCATE|DROP)\\b.*"));
        assertFalse(sql.contains("route_call_plan"));
    }
    @Test void apiAndDisplayDoNotInjectTagoOrHttp() {
        for (Class<?> type:List.of(ArrivalService.class,ArrivalController.class,DisplayService.class)) {
            for (var field:type.getDeclaredFields()) {
                assertFalse(field.getType().getName().contains(".tago."));
                assertFalse(HttpClient.class.isAssignableFrom(field.getType()));
            }
        }
        assertArrayEquals(new Class<?>[]{JdbcTemplate.class},ArrivalService.class.getConstructors()[0].getParameterTypes());
    }
    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void missingDbValueReturnsExplicitNoInformationWithoutWrite() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.query(anyString(),any(ResultSetExtractor.class),any(Object[].class))).thenReturn(null);
        var mvc=MockMvcBuilders.standaloneSetup(new ArrivalController(new ArrivalService(jdbc))).build();
        mvc.perform(get("/v1/arrival").param("vehicleNumber","전남76아7500"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("정보 없음"));
        verify(jdbc).query(contains("FROM arrival_predictions"),any(ResultSetExtractor.class),any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }
    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void savedDbValueIsReturnedAndNoPredictionIsInserted() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); ResultSet rs=mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString("vehicle_number")).thenReturn("전남76아7500");
        when(rs.getString("next_stop_text")).thenReturn("장성터미널");
        when(rs.getObject("eta_minutes")).thenReturn(2);
        doAnswer(i->((ResultSetExtractor<?>)i.getArgument(1)).extractData(rs))
            .when(jdbc).query(anyString(),any(ResultSetExtractor.class),any(Object[].class));
        var value=new ArrivalService(jdbc).predict("전남76아7500");
        assertEquals(2,value.estimatedArrivalMinutes()); assertEquals("장성터미널",value.nextStop());
        verify(jdbc).query(contains("expires_at>CURRENT_TIMESTAMP"),any(ResultSetExtractor.class),any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    TagoArrivalEtaService collector(JdbcTemplate jdbc,ArrivalService predictions,HttpClient http) {
        doAnswer(i->List.of(new TagoArrivalEtaService.ArrivalTarget("R1","S2","전남76아7500"),
                           new TagoArrivalEtaService.ArrivalTarget("R1","S2","전남76아7501")))
            .when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        var service=new TagoArrivalEtaService(jdbc,predictions,"mock-key","AUTO","36450","http://mock.invalid",
            true,false,true,false,http,Clock.fixed(NOW,ZoneOffset.UTC));
        ArrivalVisitStore visits=mock(ArrivalVisitStore.class);
        Map<String,ArrivalVisitStore.Visit> states=new HashMap<>();
        var saved=predictions.savedEta("R1","S2");
        if (saved!=null) for (String vehicle:List.of("전남76아7500","전남76아7501"))
            states.put(vehicle,new ArrivalVisitStore.Visit(1,saved.queriedAt(),saved.arrivalAt()));
        when(visits.read(anyString())).thenAnswer(i->states.get(i.getArgument(0)));
        when(visits.claim(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(i->{
            String vehicle=i.getArgument(0); var prior=states.get(vehicle);
            if (prior!=null && prior.attempts()>=2) return null;
            if (!predictions.needsInitialQuery(vehicle,"R1","S2") && prior!=null
                    && !refresh(new ArrivalService.EtaState(prior.queriedAt(),prior.arrivalAt()))) return null;
            return new ArrivalVisitStore.Visit(prior==null?1:prior.attempts()+1,NOW,prior==null?null:prior.arrivalAt());
        });
        doAnswer(i->{
            ArrivalVisitStore.Visit reserved=i.getArgument(1); Integer seconds=i.getArgument(2);
            states.put(i.getArgument(0),new ArrivalVisitStore.Visit(reserved.attempts(),reserved.queriedAt(),
                seconds==null?null:reserved.queriedAt().plusSeconds(seconds))); return null;
        }).when(visits).complete(anyString(),any(),nullable(Integer.class),any(),anyString());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"visits",visits);
        return service;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    HttpClient mockHttp() throws Exception {
        HttpClient http=mock(HttpClient.class); HttpResponse response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(("<response><header><resultCode>00</resultCode></header><body><items>"
            +"<item><routeid>R1</routeid><arrtime>120</arrtime></item></items></body></response>").getBytes(StandardCharsets.UTF_8));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return http;
    }
    @Test void changedNextStopForcesQueryEvenWhenAnotherVehicleHasShortEta() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); ArrivalService predictions=mock(ArrivalService.class);
        HttpClient http=mockHttp(); when(predictions.savedEta("R1","S2")).thenReturn(state(10,60));
        when(predictions.needsInitialQuery("전남76아7500","R1","S2")).thenReturn(true);
        var collector=collector(jdbc,predictions,http);
        try (MockedStatic<TagoApiRequestThrottle> throttle=mockStatic(TagoApiRequestThrottle.class)) {
            collector.pollNextAssignedArrival();
        }
        verify(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        verify(predictions).collectPrediction("전남76아7500","R1","S2",NOW,NOW.plusSeconds(120));
        // This vehicle's follow-up was not due: another vehicle cannot renew its visit budget/ETA.
        verify(predictions).collectPrediction("전남76아7501","R1","S2",NOW.minusSeconds(10),NOW.plusSeconds(60));
    }
    @Test void shortEtaUsesDbAndWritesGeometryWithoutHttp() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); ArrivalService predictions=mock(ArrivalService.class);
        HttpClient http=mock(HttpClient.class); var saved=state(60,119);
        when(predictions.savedEta("R1","S2")).thenReturn(saved);
        collector(jdbc,predictions,http).pollNextAssignedArrival();
        verifyNoInteractions(http);
        verify(predictions).collectPrediction("전남76아7500","R1","S2",saved.queriedAt(),saved.arrivalAt());
    }
    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void collectorRetainsOriginalArrivalPredictionInsert() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        Map<String,Object> position=Map.of("routeId","R1","direction","UP","nodeId","S1","nodeOrder",1,
            "latitude",BigDecimal.valueOf(35),"longitude",BigDecimal.valueOf(126));
        Map<String,Object> stop=Map.of("currentNodeId","S1","nextNodeId","S2","currentName","현재",
            "nextName","다음","currentLatitude",BigDecimal.valueOf(35),"currentLongitude",BigDecimal.valueOf(126),
            "latitude",BigDecimal.valueOf(35.001),"longitude",BigDecimal.valueOf(126));
        when(jdbc.query(anyString(),any(ResultSetExtractor.class),any(Object[].class))).thenReturn(position,stop);
        new ArrivalService(jdbc).collectPrediction("전남76아7500","R1","S2",NOW,NOW.plusSeconds(120));
        verify(jdbc).update(contains("INSERT INTO arrival_predictions"),any(Object[].class));
    }
    @Test void clientCodeHasOnlyInternalArrivalEndpoint() throws Exception {
        Path root=Path.of("../..");
        String repository=Files.readString(root.resolve("client-android/app/src/main/java/com/smartbis/client/model/SmartBisRepository.kt"));
        assertTrue(repository.contains("/v1/arrival?vehicleNumber="));
        for (String name:List.of("model/SmartBisRepository.kt","controller/DisplayController.kt","view/NaturalDisplayView.kt")) {
            String code=Files.readString(root.resolve("client-android/app/src/main/java/com/smartbis/client/"+name));
            assertFalse(code.contains("apis.data.go.kr")); assertFalse(code.contains("ArvlInfoInqireService"));
            assertFalse(code.contains("serviceKey="));
        }
    }

    static int simulate(int legs,int tripSeconds) {
        int calls=0;
        for (int leg=0;leg<legs;leg++) {
            Instant origin=NOW.plusSeconds((long)leg*tripSeconds/legs);
            int seconds=tripSeconds/legs;
            Instant next=origin;
            int attempts=0;
            for (int tick=0;tick<seconds;tick+=10) {
                Instant now=origin.plusSeconds(tick);
                if (next!=null && !now.isBefore(next)) {
                    calls++;
                    attempts++;
                    // Current collector preserves raw seconds and persists this due instant.
                    next=ArrivalRulePolicy.defaults().nextQuery(now,seconds-tick,attempts);
                }
            }
        }
        return calls;
    }
    @Test void equalDailyCountsDoNotMeanIdenticalRequeryTiming() {
        var policy=ArrivalRulePolicy.defaults();
        assertEquals(NOW.plusSeconds(45),policy.nextQuery(NOW,90,1));
        assertEquals(NOW.plusSeconds(60),policy.nextQuery(NOW,360,1));
        assertEquals(2,simulate(1,90));
        assertEquals(2,simulate(1,360));
        // Visits shorter than the half-ETA band pass before another 10-second tick is due.
        assertEquals(1,simulate(1,50));
        assertNull(policy.nextQuery(NOW,90,2));
    }
    @Test void offlineSingleLocalRouteDailySimulation() throws Exception {
        Path root=Path.of("../.."); String route="TSB342000001";
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(
            root.resolve("reports/route-plan/2026-10-23/route_plan_manifest.json")));
        int up=0,down=0;
        for (var d:json.required("confirmed_departures")) if (route.equals(d.required("route_id").asText())) {
            if ("기점".equals(d.required("direction").asText())) up++; else down++;
        }
        int upStops=0,downStops=0;
        for (String line:Files.readAllLines(root.resolve("장성교통 노선도/TAGO_장성군_노선별_경유정류소_20261002.csv"))) {
            String[] cells=line.split(",",-1);
            if (cells.length>6 && route.equals(cells[1])) {
                if ("UP".equals(cells[6])) upStops++; else if ("DOWN".equals(cells[6])) downStops++;
            }
        }
        assertEquals(3,up); assertEquals(3,down); assertEquals(11,upStops); assertEquals(12,downStops);
        int shortTrips=up*simulate(upStops-1,900)+down*simulate(downStops-1,900);
        int hourTrips=up*simulate(upStops-1,3600)+down*simulate(downStops-1,3600);
        assertEquals(126,shortTrips); assertEquals(126,hourTrips);
        System.out.println("ARRIVAL_MOCK_SIMULATION route="+route+" departures=6 stops=23 trip15min="+shortTrips+" trip60min="+hourTrips);
    }
}
