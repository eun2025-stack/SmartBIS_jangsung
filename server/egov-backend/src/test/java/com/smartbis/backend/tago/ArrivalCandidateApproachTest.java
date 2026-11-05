package com.smartbis.backend.tago;
import com.smartbis.backend.arrival.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ArrivalCandidateApproachTest {
    private static final Instant NOW=Instant.parse("2026-11-04T01:00:00Z");
    static TagoArrivalEtaService.ArrivalTarget target(int order,int current) {
        return new TagoArrivalEtaService.ArrivalTarget("R","S"+order,"V","GLOBAL",order,current,0);
    }
    static class ActiveVisits extends ArrivalRetryTest.MemoryVisits {
        String stop; int transitions;
        @Override public String activeStop(String v,String r,String d) { return stop; }
        @Override public void ensure(String v,String r,String s,String d,Instant now) {
            if(!Objects.equals(stop,s)) { stop=s;value=null;transitions++; }
        }
    }
    @Test void approachWindowDeterminesCandidateBoundThree() {
        assertEquals(2,TagoArrivalEtaService.APPROACH_WINDOW_STOPS);
        assertEquals(3,TagoArrivalEtaService.APPROACH_WINDOW_STOPS+1);
    }
    @Test void approachIncludesZeroThroughTwoAndRejectsNegativeThreeAndOverflow() {
        for(int remaining=0;remaining<=2;remaining++) assertTrue(TagoArrivalEtaService.withinApproachWindow(target(4+remaining,4)));
        assertFalse(TagoArrivalEtaService.withinApproachWindow(target(3,4)));
        assertFalse(TagoArrivalEtaService.withinApproachWindow(target(7,4)));
        assertFalse(TagoArrivalEtaService.withinApproachWindow(target(Integer.MAX_VALUE,Integer.MIN_VALUE)));
    }
    @Test void firstTripOddAllowed() { assertTrue(ArrivalVisitPolicy.shouldQuery(1,0,0,0,true,false,2)); }
    @Test void overdueOddAllowed() { assertTrue(ArrivalVisitPolicy.shouldQuery(3,0,1,0,false,true,2)); }
    @Test void ordinaryOddRejected() { assertFalse(ArrivalVisitPolicy.shouldQuery(3,0,1,0,false,false,2)); }
    @Test void evenAllowedAndClaimedVisitRejectedIncludingExceptions() {
        assertTrue(ArrivalVisitPolicy.shouldQuery(2,0,1,0,false,false,2));
        assertFalse(ArrivalVisitPolicy.shouldQuery(2,0,1,1,false,false,2));
        assertFalse(ArrivalVisitPolicy.shouldQuery(1,0,0,1,true,true,2));
        assertFalse(ArrivalVisitPolicy.shouldQuery(1,2,0,0,true,true,2));
    }
    @Test void qualifiedOddBeforeEvenWinsByOrderNotParity() {
        var selected=TagoArrivalEtaService.selectTarget(List.of(target(2,1),target(1,1),target(3,1)),null,false,c->c.nodeOrder()==1,2);
        assertEquals(1,selected.nodeOrder());
    }
    @Test void activeQualifiedTargetWinsEvenAfterClaimAndLeavesOnlyWhenUnqualified() {
        var candidates=List.of(target(1,1),target(2,1),target(3,1));
        assertEquals(3,TagoArrivalEtaService.selectTarget(candidates,"S3",false,c->true,2).nodeOrder());
        assertEquals(2,TagoArrivalEtaService.selectTarget(candidates,"S3",false,c->false,2).nodeOrder());
        assertEquals(4,TagoArrivalEtaService.selectTarget(List.of(target(4,4),target(5,4)),"S3",false,c->true,2).nodeOrder());
    }
    @SuppressWarnings("unchecked")
    private TagoArrivalEtaService service(List<TagoArrivalEtaService.ArrivalTarget> candidates,HttpClient http,ActiveVisits visits,ArrivalService predictions) {
        var jdbc=mock(JdbcTemplate.class);
        doReturn(candidates).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        var service=new TagoArrivalEtaService(jdbc,predictions,"fixture","AUTO","fixture","http://mock.invalid",true,false,true,false,http,Clock.fixed(NOW,ZoneOffset.UTC));
        ReflectionTestUtils.setField(service,"visits",visits);ArrivalSeparationTest.allowBudget(service);return service;
    }
    @Test void outsideWindowMakesNoHttpOrVisitClaimAndDoesNotQueue() {
        var http=mock(HttpClient.class);var visits=new ActiveVisits();
        var service=service(List.of(target(4,1)),http,visits,mock(ArrivalService.class));
        List<?> eligible=ReflectionTestUtils.invokeMethod(service,"loadAssignedArrivalTargets");assertEquals(List.of(),eligible);
        service.pollNextAssignedArrival();verifyNoInteractions(http);assertEquals(0,visits.transitions);
    }
    @Test void insideWindowEvenCallsOnceAndRetainsAlreadyClaimedActiveTarget() throws Exception {
        var http=mock(HttpClient.class);var visits=new ActiveVisits();visits.stop="S2";
        var response=ArrivalRetryTest.response(200);when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var service=service(List.of(target(0,0),target(1,0),target(2,0)),http,visits,mock(ArrivalService.class));
        service.pollNextAssignedArrival();service.pollNextAssignedArrival();
        verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        assertEquals("S2",visits.stop);assertEquals(0,visits.transitions);assertEquals(1,visits.completions);
    }
    @Test void overdueAtExactArrivalTimeReachesRealSelectorAndHttp() throws Exception {
        var http=mock(HttpClient.class);var visits=new ActiveVisits();var predictions=mock(ArrivalService.class);
        when(predictions.savedEta("R","S1")).thenReturn(new ArrivalService.EtaState(NOW.minusSeconds(10),NOW));
        var response=ArrivalRetryTest.response(200);when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        service(List.of(target(1,1),target(2,1)),http,visits,predictions).pollNextAssignedArrival();
        assertEquals("S1",visits.stop);verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @Test void firstTripOddReachesRealSelectorAndHttpWhenZeroAbsent() throws Exception {
        var http=mock(HttpClient.class);var visits=new ActiveVisits();var response=ArrivalRetryTest.response(200);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        service(List.of(target(1,0),target(2,0)),http,visits,mock(ArrivalService.class)).pollNextAssignedArrival();
        assertEquals("S1",visits.stop);verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @Test void currentThreeOnlyDispatchesFourNotSix() {
        var service=service(List.of(target(3,3),target(4,3),target(5,3)),mock(HttpClient.class),new ActiveVisits(),mock(ArrivalService.class));
        List<TagoArrivalEtaService.ArrivalTarget> eligible=ReflectionTestUtils.invokeMethod(service,"loadAssignedArrivalTargets");
        assertEquals(List.of(4),eligible.stream().map(TagoArrivalEtaService.ArrivalTarget::nodeOrder).toList());
    }
}
