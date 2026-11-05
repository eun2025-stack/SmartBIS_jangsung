package com.smartbis.backend.tago;

import com.smartbis.backend.arrival.ArrivalVisitPolicy;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** DB-shaped fixtures only. No source-material files, real DB or HTTP calls. */
class Rev2RawOrderRulesTest {
    @Test void visitsUseAbsoluteEvenOrdersWithoutShiftingForMinimum() {
        for(int minimum:new int[]{-3,0,1,7,18}) {
            for(int offset=0;offset<8;offset++) {
                int target=minimum+offset;
                assertEquals(Math.floorMod(target,2)==0,ArrivalVisitPolicy.shouldQuery(target,minimum,minimum,0,false,false,2));
                assertFalse(ArrivalVisitPolicy.shouldQuery(target,minimum,minimum,1,false,false,2));
                assertFalse(ArrivalVisitPolicy.shouldQuery(target,minimum,target+1,0,false,false,2));
            }
        }
    }

    @Test void minimumZeroSelectsZeroTwoAndFourButNotOneOrThree() {
        for(int target:new int[]{0,1,2,3,4})
            assertEquals(target%2==0,ArrivalVisitPolicy.shouldQuery(target,0,0,0,false,false,2));
    }

    @Test void minimumOneStillSelectsAbsoluteEvenOrdersAndDiffersFromOldRule() {
        for(int target:new int[]{1,2,3,4}) {
            boolean actual=ArrivalVisitPolicy.shouldQuery(target,1,1,0,false,false,2);
            assertEquals(target%2==0,actual);
            assertNotEquals((target-1)%2==0,actual);
        }
        assertFalse(ArrivalVisitPolicy.shouldQuery(0,1,1,0,false,false,2));
    }

    @Test void duplicateRawOrderAcrossDirectionsIsAnImportError() {
        var up=new TagoCatalogService.RouteStop("R","A","A",7,"UP","0",null,null,1,false);
        var down=new TagoCatalogService.RouteStop("R","B","B",7,"DOWN","1",null,null,2,false);
        assertThrows(TagoCatalogService.CatalogValidationException.class,
            ()->TagoCatalogService.normalizeStopOrders(List.of(up,down)));
        assertEquals(7,up.order());assertEquals(7,down.order());
    }

    @SuppressWarnings("unchecked")
    @Test void targetSqlUsesApproachWindowWithoutExcludingDiscontinuousCatalog() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        var service=new TagoArrivalEtaService(jdbc,"","AUTO","fixture","http://localhost",false,true,false,true);
        var snapshots=new PositionSnapshots();
        snapshots.observe(new PositionSnapshots.Position("V","R","S8",8,null,null,Instant.EPOCH),false);
        service.setSnapshots(snapshots);
        doAnswer(i->{
            String sql=i.getArgument(0);
            assertTrue(sql.contains("MIN(node_order) minimum_order"));
            assertFalse(sql.contains("HAVING"));
            assertTrue(sql.contains("s.node_order>=?"));
            assertTrue(sql.contains("s.node_order::bigint<="));
            assertFalse(sql.contains("mod("));
            assertTrue(sql.contains("LIMIT ?"));
            assertFalse(sql.contains("s.node_order::bigint-b.minimum_order,2"));
            assertFalse(sql.contains("?+1"));
            var rs=mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("S8");when(rs.getInt(2)).thenReturn(8);when(rs.getInt(3)).thenReturn(7);
            return List.of(((RowMapper<TagoArrivalEtaService.ArrivalTarget>)i.getArgument(1)).mapRow(rs,0));
        }).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        List<TagoArrivalEtaService.ArrivalTarget> targets=ReflectionTestUtils.invokeMethod(service,"loadAssignedArrivalTargets");
        assertEquals(1,targets.size());var target=targets.get(0);
        assertEquals(7,target.minimumOrder());assertEquals(8,target.nodeOrder());assertEquals(8,target.currentOrder());
    }

    @SuppressWarnings("unchecked")
    @Test void databaseFallbackSqlAlsoIncludesOddWindowCandidates() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        var service=new TagoArrivalEtaService(jdbc,"","AUTO","fixture","http://localhost",false,true,false,true);
        doAnswer(i->{
            String sql=i.getArgument(0);
            assertTrue(sql.contains("FROM bus_positions p"));
            assertTrue(sql.contains("s.node_order::bigint<="));
            assertFalse(sql.contains("mod("));
            assertTrue(sql.contains("LIMIT ?"));
            assertFalse(sql.contains("s.node_order::bigint-b.minimum_order,2"));
            var rs=mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("R");when(rs.getString(2)).thenReturn("S2");
            when(rs.getString(3)).thenReturn("V");when(rs.getString(4)).thenReturn("GLOBAL");
            when(rs.getInt(5)).thenReturn(2);when(rs.getInt(6)).thenReturn(1);when(rs.getInt(7)).thenReturn(1);
            return List.of(((RowMapper<TagoArrivalEtaService.ArrivalTarget>)i.getArgument(1)).mapRow(rs,0));
        }).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        List<TagoArrivalEtaService.ArrivalTarget> targets=ReflectionTestUtils.invokeMethod(service,"loadAssignedArrivalTargets");
        assertEquals(1,targets.size());
        assertEquals(2,targets.get(0).nodeOrder());assertEquals(1,targets.get(0).minimumOrder());
    }

    @Test void runtimeUsesOnlyDbPlanWindowsAndExpiresAtTerminalClose() {
        var settings=Rev17SpecificationTest.settings();
        String route="TSB342000118";
        var plan=new RouteCallSchedule.Plan(route,"100번",true,
            List.of(new RouteCallSchedule.Window(25500,29100,600),new RouteCallSchedule.Window(26100,29700,600)),
            null,new RouteCallSchedule.Window(21600,75600,1800),null);
        var day=Rev17SpecificationTest.DAY;var zone=Rev17SpecificationTest.KST;
        var midnight=day.atStartOfDay(zone).toInstant();
        var schedule=RouteCallSchedule.compileLanes(List.of(plan),day,zone,settings,(r,w)->midnight.plusSeconds(26400));
        var local=schedule.slots().stream().filter(s->s.roles().contains(RouteCallSchedule.Role.LOCAL_WINDOW)).toList();
        assertEquals(2,local.size());
        assertEquals(midnight.plusSeconds(25500),local.get(0).at());
        assertEquals(midnight.plusSeconds(26700),local.get(1).expiresAt());
        assertTrue(local.stream().allMatch(s->!s.expiresAt().isAfter(midnight.plusSeconds(26700))));
        var empty=new RouteCallSchedule.Plan(route,"100번",true,List.of(),null,plan.backup(),null);
        assertEquals(0,RouteCallSchedule.compileLanes(List.of(empty),day,zone,settings).summary().local());
        for(String excluded:List.of("TSB342000102","TSB342000103","TSB342000155","TSB342000006","TSB342000074")) {
            settings.getLocal().getRouteIds().add(excluded);
            var forbidden=new RouteCallSchedule.Plan(excluded,"관내",true,plan.windows(),null,plan.backup(),null);
            assertEquals(0,RouteCallSchedule.compileLanes(List.of(forbidden),day,zone,settings).summary().local());
        }
    }

    @Test void predictionGeometryUsesQueriedTargetInsteadOfInventingNextOrder() {
        var jdbc=mock(JdbcTemplate.class);
        var position=new HashMap<String,Object>();
        position.put("routeId","R");position.put("nodeId","S8");position.put("nodeOrder",8);
        var stop=new HashMap<String,Object>();
        stop.put("currentNodeId","S8");stop.put("nextNodeId","S9");
        stop.put("currentName","현재");stop.put("nextName","조회 대상");
        doReturn(position,stop).when(jdbc).query(anyString(),any(org.springframework.jdbc.core.ResultSetExtractor.class),any(Object[].class));
        var now=Instant.EPOCH;
        new com.smartbis.backend.arrival.ArrivalService(jdbc).collectPrediction("V","R","S9",now,now.plusSeconds(120));
        verify(jdbc).query(argThat((String sql)->sql.contains("next_route_stop.node_id = ?")
            && sql.contains("ORDER BY next_route_stop.node_order") && !sql.contains("node_order + 1")),
            any(org.springframework.jdbc.core.ResultSetExtractor.class),eq("S9"),eq("R"),eq("S8"),eq(8));
        verify(jdbc).update(contains("INSERT INTO arrival_predictions"),any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    @Test void dbWindowsRespectWeekdayAndHolidayWithoutReadingTimetableFiles() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var store=new JdbcRouteCallPlanStore(jdbc);
        doAnswer(i->{
            var rs=mock(ResultSet.class);
            when(rs.getString("route_id")).thenReturn("TSB342000118");
            when(rs.getString("route_group")).thenReturn("100번");when(rs.getBoolean("enabled")).thenReturn(true);
            when(rs.getString("local_windows")).thenReturn("""
              [{"start":"07:05","end":"08:05","interval_seconds":600,"day_type":"WEEKDAY"},
               {"start":"09:05","end":"10:05","interval_seconds":600,"day_type":"HOLIDAY"}]
              """);
            when(rs.getInt("backup_start_seconds")).thenReturn(21600);
            when(rs.getInt("backup_end_seconds")).thenReturn(75600);
            when(rs.getInt("backup_interval_seconds")).thenReturn(1800);
            return List.of(((RowMapper<RouteCallSchedule.Plan>)i.getArgument(1)).mapRow(rs,0));
        }).when(jdbc).query(anyString(),any(RowMapper.class));
        var day=Rev17SpecificationTest.DAY;
        assertEquals(25500,store.read(day,Set.of()).get(0).windows().get(0).start());
        assertEquals(32700,store.read(day,Set.of(day)).get(0).windows().get(0).start());
    }
}
