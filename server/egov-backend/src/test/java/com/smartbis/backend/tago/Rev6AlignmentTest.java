package com.smartbis.backend.tago;

import com.smartbis.backend.arrival.ArrivalService;
import com.smartbis.backend.arrival.ArrivalVisitPolicy;
import java.nio.file.*;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** rev6 clauses 3, 6.2, 8.2, 10.3 and 12.5; no DB, HTTP or source-material input. */
class Rev6AlignmentTest {
    private static final String RUN="11111111-1111-4111-8111-111111111111";
    private String runSchema() throws Exception { return Files.readString(Path.of("local-collection/run-schema.sql")); }

    @Test void s3FinalPrimaryKeyRejectsSameOrderAcrossDirections() throws Exception {
        assertTrue(runSchema().contains("PRIMARY KEY (route_id, node_order)"));
        var up=new TagoCatalogService.RouteStop("R","A","A",1,"UP","0",null,null,1,false);
        var down=new TagoCatalogService.RouteStop("R","B","B",1,"DOWN","1",null,null,2,false);
        assertThrows(TagoCatalogService.CatalogValidationException.class,
            ()->TagoCatalogService.normalizeStopOrders(List.of(up,down)));
    }

    @Test void s4FinalCheckAndApplicationClaimBothAllowOnlyOneVisitResult() throws Exception {
        assertTrue(runSchema().contains("CHECK (query_count BETWEEN 0 AND 1)"));
        String store=Files.readString(Path.of("src/main/java/com/smartbis/backend/arrival/ArrivalVisitStore.java"));
        assertTrue(store.contains("query_count<1"));
        assertFalse(ArrivalVisitPolicy.shouldQuery(2,1,1,1,false,false,2));
    }

    @Test void s1RunSchemaHasNoDatabaseNameRestriction() throws Exception {
        assertFalse(runSchema().contains("current_database()"));
        assertFalse(runSchema().contains("smartbis_db_copy2"));
    }

    @Test void l1LocationMetadataIsMandatoryByDefault() throws Exception {
        var field=TagoVehicleLocationService.class.getDeclaredField("captureMetadata");
        assertEquals("${collection.capture-metadata:true}",field.getAnnotation(Value.class).value());
    }

    private AnnotationConfigApplicationContext context(JdbcTemplate jdbc, String run) {
        var context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of("collection.read-run-id",run)));
        context.registerBean(JdbcTemplate.class,()->jdbc);
        context.register(ArrivalService.class);
        context.refresh();
        clearInvocations(jdbc); // Spring's InitializingBean callback is not a query.
        return context;
    }

    @SuppressWarnings("unchecked")
    @Test void q4ClientQueryUsesOnlyExplicitRunAndSameRunPassage() {
        var jdbc=mock(JdbcTemplate.class);
        doAnswer(i->{
            String sql=i.getArgument(0);
            assertTrue(sql.contains("collection_run_id=?"));
            assertTrue(sql.contains("p.collection_run_id=a.collection_run_id"));
            assertTrue(sql.contains("NOT r.archived"));
            assertTrue(Arrays.asList(i.getArguments()).contains(UUID.fromString(RUN)));
            return null;
        }).when(jdbc).query(anyString(),any(ResultSetExtractor.class),any(Object[].class));
        try(var context=context(jdbc,RUN)) {
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->context.getBean(ArrivalService.class).predict("fixture"));
        }
        verify(jdbc).query(anyString(),any(ResultSetExtractor.class),any(Object[].class));
    }

    @Test void q4UnselectedOrPreRunCannotReadAnotherRunsRows() {
        for(String run:List.of("","00000000-0000-0000-0000-000000000000")) {
            var jdbc=mock(JdbcTemplate.class);
            try(var context=context(jdbc,run)) {
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    ()->context.getBean(ArrivalService.class).predict("fixture"));
            }
            verifyNoInteractions(jdbc);
        }
    }

    @SuppressWarnings("unchecked")
    @Test void q1ContinuityDoesNotExcludeTargetsFromCollection() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        var service=new TagoArrivalEtaService(jdbc,"","AUTO","fixture","http://localhost",false,true,false,true);
        var snapshots=new PositionSnapshots();
        snapshots.observe(new PositionSnapshots.Position("V","R","S2",2,null,null,Instant.EPOCH),false);
        service.setSnapshots(snapshots);
        doAnswer(i->{
            String sql=i.getArgument(0);
            assertFalse(sql.contains("HAVING"),"Continuity belongs to judgment/diagnostics, not target exclusion");
            var rs=mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("S4");when(rs.getInt(2)).thenReturn(4);when(rs.getInt(3)).thenReturn(1);
            return List.of(((RowMapper<TagoArrivalEtaService.ArrivalTarget>)i.getArgument(1)).mapRow(rs,0));
        }).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        List<TagoArrivalEtaService.ArrivalTarget> targets=ReflectionTestUtils.invokeMethod(service,"loadAssignedArrivalTargets");
        assertEquals(1,targets.size());
        assertEquals(4,targets.get(0).nodeOrder());
    }

    @Test void rawOneThroughNAndOriginalZeroArePreservedWithoutFilling() {
        var stops=new ArrayList<TagoCatalogService.RouteStop>();
        for(int order=1;order<=4;order++) stops.add(new TagoCatalogService.RouteStop("R","S"+order,"S",order,"UP","0",null,null,order,false));
        assertEquals(List.of(1,2,3,4),TagoCatalogService.normalizeStopOrders(stops).stream().map(TagoCatalogService.RouteStop::order).toList());
        var zero=new TagoCatalogService.RouteStop("R","S0","S",0,"UP","0",null,null,1,false);
        assertEquals(0,TagoCatalogService.normalizeStopOrders(List.of(zero)).get(0).order());
    }

    @Test void q1ContinuityDefectsAreLoggedWithoutMutatingCatalog() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("HAVING"))).thenReturn(List.of(Map.of(
            "route_id","R","stop_count",2,"distinct_count",2,"first_order",1,"last_order",3)));
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(TagoArrivalEtaService.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();logger.addAppender(appender);
        try {
            var service=new TagoArrivalEtaService(jdbc,"","AUTO","fixture","http://localhost",false,true,false,true);
            ReflectionTestUtils.invokeMethod(service,"loadAssignedArrivalTargets");
            assertTrue(appender.list.stream().anyMatch(e->e.getFormattedMessage().contains("CATALOG_ORDER_CONTINUITY: route=R")));
            verify(jdbc,never()).update(anyString(),any(Object[].class));
        } finally { logger.detachAppender(appender);appender.stop(); }
    }

    @Test void oneStopIntervalIsRejectedAndAbsoluteEvenTwoStopRuleRemains() {
        assertThrows(IllegalArgumentException.class,()->ArrivalVisitPolicy.shouldQuery(2,1,1,0,false,false,1));
        assertFalse(ArrivalVisitPolicy.shouldQuery(1,1,1,0,false,false,2));
        assertTrue(ArrivalVisitPolicy.shouldQuery(2,1,1,0,false,false,2));
        assertFalse(ArrivalVisitPolicy.shouldQuery(3,1,1,0,false,false,2));
    }
}
