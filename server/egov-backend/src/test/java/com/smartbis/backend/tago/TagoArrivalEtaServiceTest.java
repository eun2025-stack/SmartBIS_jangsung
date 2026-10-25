package com.smartbis.backend.tago;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TagoArrivalEtaServiceTest {
    @Test
    void observationModeAndArrivalSettingEachPreventArrivalCollection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var observation = new TagoArrivalEtaService(jdbc, "key", "AUTO", "36450", "http://localhost",
                true, false, true, true);
        var disabled = new TagoArrivalEtaService(jdbc, "key", "AUTO", "36450", "http://localhost",
                true, false, false, false);
        observation.pollNextAssignedArrival();
        disabled.pollNextAssignedArrival();
        assertNull(observation.estimateMinutes("route", "stop"));
        assertNull(disabled.estimateMinutes("route", "stop"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void parsesRouteSpecificArrivalTimeAndRoundsUpToMinutes() throws Exception {
        String xml = """
                <response><header><resultCode>00</resultCode></header><body><items>
                  <item><routeid>R1</routeid><arrtime>121</arrtime></item>
                  <item><routeid>R1</routeid><arrtime>61</arrtime></item>
                  <item><routeid>R2</routeid><arrtime>10</arrtime></item>
                </items></body></response>
                """;

        assertEquals(2, TagoArrivalEtaService.earliestArrivalMinutes(
                xml.getBytes(StandardCharsets.UTF_8), "R1"));
    }

    @Test
    void returnsNoEstimateWhenRouteHasNoArrivalItem() throws Exception {
        String xml = """
                <response><header><resultCode>00</resultCode></header><body><items>
                  <item><routeid>OTHER</routeid><arrtime>60</arrtime></item>
                </items></body></response>
                """;

        assertNull(TagoArrivalEtaService.earliestArrivalMinutes(
                xml.getBytes(StandardCharsets.UTF_8), "R1"));
    }

    @Test
    void rejectsTagoErrorResponsesAndUnsafeXml() {
        String error = "<response><header><resultCode>30</resultCode></header></response>";
        assertThrows(IllegalStateException.class, () -> TagoArrivalEtaService.earliestArrivalMinutes(
                error.getBytes(StandardCharsets.UTF_8), "R1"));

        String xxe = "<!DOCTYPE response [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                + "<response><header><resultCode>00</resultCode></header><body>&xxe;</body></response>";
        assertThrows(Exception.class, () -> TagoArrivalEtaService.earliestArrivalMinutes(
                xxe.getBytes(StandardCharsets.UTF_8), "R1"));
    }
}
