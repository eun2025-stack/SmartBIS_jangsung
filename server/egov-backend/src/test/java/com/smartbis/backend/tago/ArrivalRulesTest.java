package com.smartbis.backend.tago;

import com.smartbis.backend.arrival.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ArrivalRulesTest {
    final Instant start=Instant.parse("2026-10-24T00:00:00Z");
    final ArrivalRulePolicy policy=ArrivalRulePolicy.defaults();
    @Test void requestedScheduleTable() {
        int[] eta={200,150,90,50,20}; long[] due={60,75,45,50,20};
        for (int i=0;i<eta.length;i++) {
            assertEquals(start.plusSeconds(due[i]),policy.nextQuery(start,eta[i],1));
            assertNull(policy.nextQuery(start,eta[i],2));
            String text=policy.display(start.plusSeconds(eta[i]),null,start);
            assertEquals(eta[i]<=30?"곧 도착":"도착 예정",text);
            System.out.println("ARRIVAL_RULE_TABLE eta="+eta[i]+" next=+"+due[i]+"s state="+text);
        }
    }
    @Test void lowerAndUpperBoundaryAndMinimumHalfDelay() {
        assertEquals(start.plusSeconds(30),policy.nextQuery(start,60,1));
        assertEquals(start.plusSeconds(90),policy.nextQuery(start,179,1));
        assertEquals(start.plusSeconds(60),policy.nextQuery(start,180,1));
        var configured=new ArrivalRulePolicy(60,180,60,30,.2,30,60);
        assertEquals(start.plusSeconds(30),configured.nextQuery(start,90,1));
    }
    @Test void secondResponseCannotCreateThirdCallAtAnyEta() {
        for (int first:new int[]{20,50,60,90,150,180,200,600})
            for (int second:new int[]{0,20,50,90,150,200,600}) {
                Instant due=policy.nextQuery(start,first,1);
                assertNotNull(due); assertNull(policy.nextQuery(due,second,2));
            }
    }
    @Test void simulationIsCappedAtTwoEvenWhenNeverPassingStop() {
        for (int eta:new int[]{0,20,50,90,150,200,600}) {
            int count=0; Instant next=start;
            for (int second=0;second<86400;second++) {
                Instant now=start.plusSeconds(second);
                if (next!=null && !next.isAfter(now)) { count++; next=policy.nextQuery(now,eta,count); }
            }
            assertEquals(2,count);
        }
    }
    @Test void imminentIncludesExactlyThirtyAndNegativeBeforeGrace() {
        assertEquals("도착 예정",policy.display(start.plusSeconds(31),null,start));
        assertEquals("곧 도착",policy.display(start.plusSeconds(30),null,start));
        assertEquals("곧 도착",policy.display(start,null,start.plusSeconds(60)));
    }
    @Test void passConfirmedInsideSixtySecondsVersusEstimatedAfter() {
        assertEquals("통과",policy.display(start,start.plusSeconds(59),start.plusSeconds(59)));
        assertEquals("통과",policy.display(start,start.plusSeconds(60),start.plusSeconds(90)));
        assertEquals("통과 추정",policy.display(start,null,start.plusSeconds(61)));
        assertEquals("통과 추정",policy.display(start,start.plusSeconds(61),start.plusSeconds(61)));
    }
    @Test void missingEtaIsNotInvented() { assertEquals("정보 없음",policy.display(null,null,start)); }
    @Test void rawSecondsArePreservedInsteadOfRoundedToMinutes() throws Exception {
        String xml="<response><header><resultCode>00</resultCode></header><body><items><item><routeid>R</routeid><arrtime>90</arrtime></item></items></body></response>";
        assertEquals(90,TagoArrivalEtaService.earliestArrivalSeconds(xml.getBytes(StandardCharsets.UTF_8),"R"));
        assertEquals(2,TagoArrivalEtaService.earliestArrivalMinutes(xml.getBytes(StandardCharsets.UTF_8),"R"));
    }
    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void atomicDbClaimIncludesCapAndVisitIdentityBeforeAnyHttp() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); ArrivalVisitStore store=new ArrivalVisitStore(jdbc);
        store.claim("V","R","S","UP",start);
        verify(jdbc).query(argThat((String sql)->sql.contains("query_count<2") && sql.contains("next_query_at<=?")
            && sql.contains("direction=?") && sql.contains("RETURNING query_count")),any(ResultSetExtractor.class),any(Object[].class));
    }
    @Test void migrationEnforcesPersistedCapAndNeverEnablesCollection() throws Exception {
        String sql=Files.readString(Path.of("../database/migrations/V019__arrival_visit_query_limits.sql"));
        assertTrue(sql.contains("CHECK(query_count BETWEEN 0 AND 2)"));
        assertFalse(sql.contains("enabled")); assertFalse(sql.contains("route_call_plan"));
    }
    @Test void restartUsesPersistedCounterWithoutAgeReset() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var first=new ArrivalVisitStore(jdbc); var restarted=new ArrivalVisitStore(jdbc);
        first.ensure("V","R","S","UP",start); restarted.ensure("V","R","S","UP",start.plusSeconds(86400));
        verify(jdbc,times(2)).update(argThat((String sql)->sql.contains("IS DISTINCT FROM")
            && !sql.contains("INTERVAL") && sql.contains("arrival_query_visits.direction")),any(Object[].class));
    }
    @Test @SuppressWarnings({"unchecked","rawtypes"})
    void collectorHttpIsAtMostTwoAcrossRepeatedTicksAndRestart() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); ArrivalService predictions=mock(ArrivalService.class);
        HttpClient http=new ArrivalSeparationTest().mockHttp(); ArrivalVisitStore visits=mock(ArrivalVisitStore.class);
        AtomicInteger attempts=new AtomicInteger();
        doAnswer(i->List.of(new TagoArrivalEtaService.ArrivalTarget("R1","S2","V")))
            .when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        when(visits.claim(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(i->{
            if (attempts.get()>=2) return null;
            return new ArrivalVisitStore.Visit(attempts.incrementAndGet(),start,start.plusSeconds(120));
        });
        when(visits.read("V")).thenAnswer(i->new ArrivalVisitStore.Visit(attempts.get(),start,start.plusSeconds(120)));
        try (MockedStatic<TagoApiRequestThrottle> throttle=mockStatic(TagoApiRequestThrottle.class)) {
            for (int restart=0;restart<2;restart++) {
                var service=new TagoArrivalEtaService(jdbc,predictions,"mock","AUTO","36450","http://mock.invalid",
                    true,false,true,false,http,Clock.fixed(start,ZoneOffset.UTC));
                org.springframework.test.util.ReflectionTestUtils.setField(service,"visits",visits);
                for (int tick=0;tick<20;tick++) service.pollNextAssignedArrival();
            }
        }
        verify(http,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        assertEquals(2,attempts.get());
    }
    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void apiReturnsPresentationFromDatabaseWithoutTago() throws Exception {
        for (String expected:List.of("곧 도착","통과","통과 추정")) {
            JdbcTemplate jdbc=mock(JdbcTemplate.class); java.sql.ResultSet rs=mock(java.sql.ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getTimestamp("estimated_arrival_at")).thenReturn(java.sql.Timestamp.from(start));
            Instant now=expected.equals("통과 추정")?start.plusSeconds(61):expected.equals("통과")?start.plusSeconds(30):start;
            when(rs.getTimestamp("database_now")).thenReturn(java.sql.Timestamp.from(now));
            if (expected.equals("통과")) when(rs.getTimestamp("passed_at")).thenReturn(java.sql.Timestamp.from(start.plusSeconds(30)));
            doAnswer(i->((ResultSetExtractor<?>)i.getArgument(1)).extractData(rs))
                .when(jdbc).query(anyString(),any(ResultSetExtractor.class),any(Object[].class));
            assertEquals(expected,new ArrivalService(jdbc).predict("V").arrivalStatusText());
            verify(jdbc).query(anyString(),any(ResultSetExtractor.class),any(Object[].class)); verifyNoMoreInteractions(jdbc);
        }
    }
    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void recentPassageIsNotHiddenByCollectorSavingTheFollowingStop() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new ArrivalService(jdbc).predict("V"));
        verify(jdbc).query(argThat((String sql)->sql.contains("FROM enriched")
            && sql.contains("ORDER BY CASE WHEN passed_at>=CURRENT_TIMESTAMP")
            && sql.contains("p.node_order>s.node_order") && sql.contains("interpolated")),
            any(ResultSetExtractor.class),any(Object[].class));
    }
}
