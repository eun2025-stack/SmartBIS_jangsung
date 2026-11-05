package com.smartbis.backend.tago;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Reads actual PostgreSQL results; never opens a DB or performs HTTP. */
@Tag("database-write")
@EnabledIfEnvironmentVariable(named="SMARTBIS_SQL_FIXTURE",matches=".+")
class ArrivalCandidateSqlFixtureTest {
    @TestFactory Stream<DynamicTest> postgresRowsPassActualJavaSelector() throws Exception {
        var input=new ObjectMapper().readTree(Files.readString(Path.of(System.getenv("SMARTBIS_SQL_FIXTURE"))));
        var tests=new ArrayList<DynamicTest>();
        for(var fixture:input) tests.add(DynamicTest.dynamicTest(fixture.get("name").asText(),()->{
            var rows=new ArrayList<TagoArrivalEtaService.ArrivalTarget>();
            for(var row:fixture.get("rows")) rows.add(new TagoArrivalEtaService.ArrivalTarget("R",row.get("stop").asText(),"V","GLOBAL",row.get("order").asInt(),fixture.get("current").asInt(),fixture.get("minimum").asInt()));
            var selected=TagoArrivalEtaService.selectTarget(rows,null,fixture.get("first_trip").asBoolean(),c->fixture.get("overdue_orders").toString().equals("["+c.nodeOrder()+"]"),2);
            assertNotNull(selected);assertEquals(fixture.get("expected").asInt(),selected.nodeOrder());
        }));
        return tests.stream();
    }
}
