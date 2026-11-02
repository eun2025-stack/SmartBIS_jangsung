package com.smartbis.backend.tago;

import com.smartbis.backend.arrival.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ArrivalRetryTest {
    static class MemoryVisits extends ArrivalVisitStore {
        Visit value;
        int completions;
        String failure;
        Integer seconds;
        MemoryVisits() { super(mock(JdbcTemplate.class)); }
        @Override public void ensure(String v,String r,String s,String d,Instant at) { }
        @Override public Visit read(String v) { return value; }
        @Override public Visit claim(String v,String r,String s,String d,Instant at) {
            if(value!=null) return null;
            return value=new Visit(1,at,null);
        }
        @Override public void complete(String v,Visit claim,Integer eta,ArrivalRulePolicy policy,String reason,Instant at) {
            completions++;failure=reason;seconds=eta;
            value=new Visit(1,at,reason.isEmpty()&&eta!=null?at.plusSeconds(eta):null);
        }
    }
    static class Store implements GuardUsageStore {
        final Map<String,Integer> counts=new HashMap<>();
        public int read(String api,LocalDate date) { return counts.getOrDefault(api,0); }
        public int reserve(String api,LocalDate date,int limit,Instant at) {
            int old=read(api,date);if(old>=limit) return -1;
            counts.put(api,old+1);return old+1;
        }
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    static HttpResponse<byte[]> response(int status) {
        var response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(("<response><header><resultCode>00</resultCode></header><body><items>"
            +"<item><routeid>R</routeid><arrtime>120</arrtime><arrprevstationcnt>1</arrprevstationcnt></item>"
            +"</items></body></response>").getBytes(StandardCharsets.UTF_8));
        return response;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    static TagoArrivalEtaService service(HttpClient http,ArrivalService predictions,MemoryVisits visits,
            LocationCollectionOperationsTest.MutableClock clock,Store store,List<Long> sleeps) {
        var jdbc=mock(JdbcTemplate.class);
        doReturn(List.of(new TagoArrivalEtaService.ArrivalTarget("R","S","V"))).when(jdbc)
            .query(anyString(),any(RowMapper.class),any(Object[].class));
        var service=new TagoArrivalEtaService(jdbc,predictions,"fixture","AUTO","36450","http://mock.invalid",
            true,false,true,true,http,clock);
        ReflectionTestUtils.setField(service,"visits",visits);
        ReflectionTestUtils.setField(service,"budget",new ArrivalApiBudget(true,9000,store,clock,2,10000,
            ms->{sleeps.add(ms);clock.advance(ms);}));
        var travel=mock(ArrivalTravelEstimator.class);
        when(travel.estimateSeconds(anyString(),anyInt(),anyInt(),any())).thenReturn(72);
        ReflectionTestUtils.setField(service,"travel",travel);
        return service;
    }
    @Test void t11FirstFailureSecondSuccessProducesOneFinalVisitResult() throws Exception {
        var clock=new LocationCollectionOperationsTest.MutableClock();
        var start=clock.instant();var store=new Store();var sleeps=new ArrayList<Long>();
        var http=mock(HttpClient.class);var predictions=mock(ArrivalService.class);var visits=new MemoryVisits();
        var failed=response(503);var recovered=response(200);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(failed,recovered);
        var service=service(http,predictions,visits,clock,store,sleeps);
        service.pollNextAssignedArrival();service.pollNextAssignedArrival();
        verify(http,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        assertEquals(List.of(10000L),sleeps);assertEquals(1,visits.completions);
        assertEquals("",visits.failure);assertEquals(120,visits.seconds);
        assertEquals(start.plusSeconds(130),visits.value.arrivalAt());
        verify(predictions,times(1)).collectPrediction("V","R","S",start.plusSeconds(10),start.plusSeconds(130));
        assertEquals(2,store.read("TAGO_ARRIVAL",null));assertEquals(0,store.read("TAGO_LOCATION",null));
    }
    @Test void t11ThreeFailuresFinishVisitAndNeverStartFourthAttempt() throws Exception {
        var clock=new LocationCollectionOperationsTest.MutableClock();var store=new Store();var sleeps=new ArrayList<Long>();
        var http=mock(HttpClient.class);var predictions=mock(ArrivalService.class);var visits=new MemoryVisits();
        var failed=response(503);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(failed);
        var service=service(http,predictions,visits,clock,store,sleeps);
        service.pollNextAssignedArrival();service.pollNextAssignedArrival();
        verify(http,times(3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        assertEquals(List.of(10000L,10000L),sleeps);assertEquals(1,visits.completions);
        assertEquals("TAGO_HTTP_503",visits.failure);assertNull(visits.seconds);
        assertEquals(1,visits.value.attempts());
        verify(predictions,times(1)).collectPrediction(anyString(),anyString(),anyString(),any(),any());
        assertEquals(3,store.read("TAGO_ARRIVAL",null));assertEquals(0,store.read("TAGO_LOCATION",null));
    }
    @Test void t14RetryStopsAtArrivalBudgetWithoutConsumingLocationBudget() throws Exception {
        var store=new Store();store.counts.put("TAGO_ARRIVAL",8999);
        var clock=new LocationCollectionOperationsTest.MutableClock();var attempts=new ArrayList<Integer>();
        var arrival=new ArrivalApiBudget(true,9000,store,clock,2,10000,clock::advance);
        assertThrows(IllegalStateException.class,()->arrival.execute(()->{attempts.add(1);throw new IllegalStateException("TAGO_HTTP_503");}));
        assertEquals(1,attempts.size());assertEquals(9000,arrival.count());
        var settings=new LocationCollectionProperties();settings.setEnabled(true);
        var location=new TagoQuotaGuard(settings,store,clock);
        assertEquals(TagoQuotaGuard.Decision.ALLOWED,location.acquire());
        assertEquals(1,location.callCount());assertEquals(9000,store.read("TAGO_ARRIVAL",null));
    }
    @Test void t11FinalResultSqlUsesRecoveryTimeAndOriginalClaimGuard() {
        var jdbc=mock(JdbcTemplate.class);var at=Instant.parse("2026-11-03T01:00:00Z");
        new ArrivalVisitStore(jdbc).complete("V",new ArrivalVisitStore.Visit(1,at,null),120,
            ArrivalRulePolicy.defaults(),"",at.plusSeconds(10));
        var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc,times(1)).update(anyString(),args.capture());
        assertEquals(Timestamp.from(at.plusSeconds(130)),args.getValue()[0]);
        assertEquals(Timestamp.from(at.plusSeconds(10)),args.getValue()[3]);
        assertEquals(Timestamp.from(at),args.getValue()[6]);
    }
    @Test void t11LearningUsesOnlyRealIncreasingRowsWithinThirtyMinutes() {
        var jdbc=mock(JdbcTemplate.class);
        assertEquals(72,new ArrivalTravelEstimator(jdbc).estimateSeconds("R",1,2,Instant.parse("2026-11-03T01:00:00Z")));
        var sql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(),eq(Double.class),any(Object[].class));
        assertTrue(sql.getValue().contains("COALESCE(raw_payload->>'interpolated','false')<>'true'"));
        assertTrue(sql.getValue().contains("previous_order>=0 AND node_order>previous_order"));
        assertTrue(sql.getValue().contains("observed_at-previous_at<=INTERVAL '30 minutes'"));
        assertTrue(sql.getValue().contains("percentile_cont(0.5)"));
    }
}
