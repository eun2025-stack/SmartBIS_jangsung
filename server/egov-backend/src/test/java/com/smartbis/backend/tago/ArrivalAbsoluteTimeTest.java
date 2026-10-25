package com.smartbis.backend.tago;

import com.smartbis.backend.arrival.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.sql.ResultSet;
import java.sql.Timestamp;
import org.springframework.jdbc.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ArrivalAbsoluteTimeTest {
    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void serverStatusAtThirtyThirtyOneFiftyNineAndSixtyNeverSaysZeroMinutes() throws Exception {
        Instant now=Instant.parse("2026-10-24T00:00:00Z");
        for (int seconds:new int[]{30,31,59,60}) {
            JdbcTemplate jdbc=mock(JdbcTemplate.class); ResultSet rs=mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getTimestamp("database_now")).thenReturn(Timestamp.from(now));
            when(rs.getTimestamp("estimated_arrival_at")).thenReturn(Timestamp.from(now.plusSeconds(seconds)));
            doAnswer(i->((ResultSetExtractor<?>)i.getArgument(1)).extractData(rs))
                .when(jdbc).query(anyString(),any(ResultSetExtractor.class),any(Object[].class));
            var saved=new ArrivalService(jdbc).predict("V");
            assertEquals(seconds==30?"곧 도착":"도착 예정",saved.arrivalStatusText());
            assertFalse(saved.arrivalStatusText().contains("0분"));
            verify(jdbc).query(anyString(),any(ResultSetExtractor.class),any(Object[].class));
            verifyNoMoreInteractions(jdbc);
        }
    }
    @Test void addsAbsoluteDbTimestampWithoutRenamingExistingFields() throws Exception {
        Instant expected=Instant.parse("2026-10-24T00:00:00Z");
        var value=new ArrivalPrediction("V","R","A","장성군청","S0","S1",null,false,null,2,90L,"도착 예정",expected);
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var json=mapper.readTree(mapper.writeValueAsBytes(value));
        assertEquals(expected.toString(),json.required("estimated_arrival_at").asText());
        assertEquals(2,json.required("estimatedArrivalMinutes").asInt());
        assertEquals(90,json.required("estimatedArrivalSeconds").asLong());
        assertTrue(json.has("vehicleNumber")); assertTrue(json.has("arrivalStatusText"));
        assertFalse(json.has("estimatedArrivalAt"));
    }
    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void mapsAbsoluteTimeFromDbRatherThanRelativeEta() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); ResultSet rs=mock(ResultSet.class);
        Instant expected=Instant.parse("2026-10-24T00:00:00Z");
        when(rs.next()).thenReturn(true);
        when(rs.getTimestamp("estimated_arrival_at")).thenReturn(Timestamp.from(expected));
        when(rs.getTimestamp("database_now")).thenReturn(Timestamp.from(expected.minusSeconds(90)));
        doAnswer(i->((ResultSetExtractor<?>)i.getArgument(1)).extractData(rs))
            .when(jdbc).query(anyString(),any(ResultSetExtractor.class),any(Object[].class));
        assertEquals(expected,new ArrivalService(jdbc).predict("V").estimatedArrivalAt());
        verify(jdbc).query(anyString(),any(ResultSetExtractor.class),any(Object[].class)); verifyNoMoreInteractions(jdbc);
    }
    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void nextStopCursorIsParameterizedAndReadOnly() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new ArrivalService(jdbc).predict("V","S1"));
        verify(jdbc).query(argThat((String s)->s.contains("next_node_id IS DISTINCT FROM CAST(? AS varchar)")),
            any(ResultSetExtractor.class),eq("V"),eq("S1"),eq("S1"),eq(60L));
    }
}
