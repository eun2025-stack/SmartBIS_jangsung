package com.smartbis.backend.tago;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.w3c.dom.Element;

class RawNodeOrderPersistenceTest {
    private static Element item(String node,String order)throws Exception {
        return TagoCatalogService.parseXml("<item><nodeid>"+node+"</nodeid><nodenm>fixture</nodenm>"+
            (order==null?"":"<nodeord>"+order+"</nodeord>")+"<updowncd>0</updowncd></item>").getDocumentElement();
    }
    @Test void rawIntegerZeroOneAndObservedEighteenNeverUseResponseIndex()throws Exception {
        for(int raw:new int[]{0,1,18,55,56,76}) {
            var stop=TagoCatalogService.stopFrom(item("N"+raw,String.valueOf(raw)),"TSB342000106",900);
            assertEquals(raw,stop.order());assertFalse(stop.orderInferred());
            assertEquals(raw,TagoCatalogService.normalizeStopOrders(List.of(stop)).get(0).order());
        }
        var negative=assertThrows(TagoCatalogService.CatalogValidationException.class,
            ()->TagoCatalogService.stopFrom(item("NEGATIVE","-1"),"TSB342000106",900));
        assertEquals("STOP_ORDER_INVALID",negative.failureCode());
    }
    @Test void missingBlankAndInvalidOrdersCannotCreateSyntheticZero()throws Exception {
        for(String raw:new String[]{null,"","abc"}) {
            Element input=item("N",raw);
            assertThrows(TagoCatalogService.CatalogValidationException.class,()->TagoCatalogService.stopFrom(input,"R",1));
        }
    }
    @Test void duplicateZeroIsRejectedRatherThanReassigned()throws Exception {
        var a=TagoCatalogService.stopFrom(item("A","0"),"R",1);
        var b=TagoCatalogService.stopFrom(item("B","0"),"R",2);
        var duplicate=assertThrows(TagoCatalogService.CatalogValidationException.class,
            ()->TagoCatalogService.normalizeStopOrders(List.of(a,b)));
        assertEquals("DUPLICATE_RAW_STOP_ORDER",duplicate.failureCode());
        assertEquals(0,a.order());assertEquals(0,b.order());
    }
    @Test void sameOriginAndTerminalNodeKeepsBothRawOrders()throws Exception {
        var origin=TagoCatalogService.stopFrom(item("SAME","0"),"R",1);
        var terminal=TagoCatalogService.stopFrom(item("SAME","55"),"R",56);
        var rows=TagoCatalogService.normalizeStopOrders(List.of(origin,terminal));
        assertEquals(List.of(0,55),rows.stream().map(TagoCatalogService.RouteStop::order).toList());
        assertEquals(2,rows.size());
    }
    @Test void catalogSqlReceivesRawOrderExactly()throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        var catalog=new TagoCatalogService(jdbc,mock(org.springframework.transaction.PlatformTransactionManager.class),
            mock(com.smartbis.backend.admin.BoundaryRegionService.class),"","AUTO","fixture","",1,0);
        var method=TagoCatalogService.class.getDeclaredMethod("persistCatalog",List.class,List.class);method.setAccessible(true);
        var stops=new ArrayList<TagoCatalogService.RouteStop>();
        for(int raw:new int[]{0,1,18,55})stops.add(TagoCatalogService.stopFrom(
            item(raw==0?"TSB175000010":"N"+raw,String.valueOf(raw)),"TSB342000106",999));
        method.invoke(catalog,List.of(new TagoCatalogService.Route("TSB342000106","fixture",null)),TagoCatalogService.normalizeStopOrders(stops));
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc,times(4)).update(contains("INSERT INTO bus_route_stops"),args.capture());
        assertEquals(List.of(0,1,18,55),args.getAllValues().stream().map(x->x[2]).toList());
        assertEquals("TSB175000010",args.getAllValues().get(0)[1]);
    }
    @Test void positionSqlKeepsRawOrdersAndMissingRemainsNull()throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        var service=new TagoVehicleLocationService(jdbc,"","AUTO","fixture","",false,false,10000,true,true,mock(HttpClient.class));
        ReflectionTestUtils.setField(service,"captureMetadata",true);
        var control=mock(LocalCollectionControl.class);when(control.runId()).thenReturn(UUID.randomUUID());
        ReflectionTestUtils.setField(service,"localControl",control);
        var parse=TagoVehicleLocationService.class.getDeclaredMethod("observationFrom",String.class,Element.class);parse.setAccessible(true);
        var persist=TagoVehicleLocationService.class.getDeclaredMethod("persist",TagoVehicleLocationService.VehiclePosition.class);persist.setAccessible(true);
        var trace=new TagoVehicleLocationService.PositionTrace(UUID.randomUUID(),Instant.now(),Instant.now(),"B");
        ReflectionTestUtils.setField(service,"positionTrace",new ThreadLocal<TagoVehicleLocationService.PositionTrace>(){{set(trace);}});
        for(String raw:new String[]{"0","1","18",null,"abc"}) {
            var element=TagoVehicleLocationService.parseXml(("<item><vehicleno>fixture</vehicleno><nodeid>N</nodeid>"+
                (raw==null?"":"<nodeord>"+raw+"</nodeord>")+"</item>").getBytes(java.nio.charset.StandardCharsets.UTF_8)).getDocumentElement();
            var position=(TagoVehicleLocationService.VehiclePosition)parse.invoke(service,"R",element);
            persist.invoke(service,position);
        }
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc,times(5)).update(contains("INSERT INTO bus_positions"),args.capture());
        assertEquals(Arrays.asList(0,1,18,null,null),args.getAllValues().stream().map(x->x[3]).toList());
    }
    @Test void jdbcNullOrderCannotTurnIntoOriginZero()throws Exception {
        var jdbc=mock(JdbcTemplate.class);var rs=mock(java.sql.ResultSet.class);
        when(rs.next()).thenReturn(true);when(rs.getInt("node_order")).thenReturn(0);when(rs.wasNull()).thenReturn(true);
        when(rs.getString("vehicle_number")).thenReturn("fixture");when(rs.getString("route_id")).thenReturn("R");
        when(jdbc.query(anyString(),any(org.springframework.jdbc.core.ResultSetExtractor.class),any(Object[].class)))
            .thenAnswer(call->((org.springframework.jdbc.core.ResultSetExtractor<?>)call.getArgument(1)).extractData(rs));
        var service=new com.smartbis.backend.arrival.ArrivalService(jdbc);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            ()->service.collectPrediction("fixture","R","S",Instant.now(),Instant.now()));
        verify(jdbc,times(1)).query(anyString(),any(org.springframework.jdbc.core.ResultSetExtractor.class),any(Object[].class));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
