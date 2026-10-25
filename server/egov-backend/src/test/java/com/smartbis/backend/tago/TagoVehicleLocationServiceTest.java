package com.smartbis.backend.tago;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.w3c.dom.Document;
import com.sun.net.httpserver.HttpServer;
import org.springframework.jdbc.core.RowMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TagoVehicleLocationServiceTest {
    @Test
    void parsesVehicleGpsAndRouteStopFieldsFromDocumentShape() throws Exception {
        String xml = """
                <response>
                  <header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>
                  <body><items><item>
                    <gpslati>35.3001</gpslati><gpslong>126.7802</gpslong>
                    <nodeid>TSB342000660</nodeid><nodenm>사거리버스터미널</nodenm>
                    <nodeord>1</nodeord><routenm>20</routenm><routetp>농어촌버스</routetp>
                    <vehicleno>전남75자1234</vehicleno>
                  </item></items><numOfRows>10</numOfRows><pageNo>1</pageNo><totalCount>1</totalCount></body>
                </response>
                """;

        Document document = TagoVehicleLocationService.parseXml(xml.getBytes(StandardCharsets.UTF_8));

        assertEquals("00", document.getElementsByTagName("resultCode").item(0).getTextContent());
        assertEquals("전남75자1234", document.getElementsByTagName("vehicleno").item(0).getTextContent());
        assertEquals("35.3001", document.getElementsByTagName("gpslati").item(0).getTextContent());
        assertEquals("TSB342000660", document.getElementsByTagName("nodeid").item(0).getTextContent());
        assertEquals("1", document.getElementsByTagName("nodeord").item(0).getTextContent());
    }

    @Test
    void rejectsXmlExternalEntityPayloads() {
        String xml = """
                <!DOCTYPE response [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <response><header><resultCode>00</resultCode></header><body>&xxe;</body></response>
                """;
        assertThrows(Exception.class,
                () -> TagoVehicleLocationService.parseXml(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void locationPollingRemainsDisabledUnlessLiveFlagsAreExplicitlyEnabled() {
        var service = new TagoVehicleLocationService(
                org.mockito.Mockito.mock(JdbcTemplate.class),
                "test-key", "AUTO", "36450", "http://localhost", false, false, 10000, true, false);
        assertEquals("DISABLED", service.status().get("status"));
        assertEquals(false, service.status().get("enabled"));
    }

    @Test
    void reportsTenSecondRequestBudget() {
        var service = new TagoVehicleLocationService(
                org.mockito.Mockito.mock(JdbcTemplate.class),
                "test-key", "AUTO", "36450", "http://localhost", true, true, 10000, false, false);

        assertEquals(10_000L, service.status().get("minRequestGapMs"));
        assertEquals(8_640L, service.status().get("maxLocationRequestsPerDayAtConfiguredGap"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void stopsTheCurrentRouteRequestAfterRateLimitAndLeavesOtherRoutesForLaterTicks() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/1613000/BusLcInfoInqireService/getRouteAcctoBusLcList", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();

        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.doAnswer(invocation -> List.of("route-1", "route-2"))
                .when(jdbc).query(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(RowMapper.class),
                        org.mockito.ArgumentMatchers.<Object[]>any());
        var service = new TagoVehicleLocationService(
                jdbc,
                "test-key", "AUTO", "36450", "http://127.0.0.1:" + server.getAddress().getPort(),
                true, true, 10000, false, false, HttpClient.newHttpClient());

        try {
            assertEquals(0, service.pollNextAssignedRoute());
            assertEquals(1, requests.get());
            assertEquals(1, service.status().get("failedRouteCount"));
            assertEquals(0, service.status().get("unattemptedRouteCount"));
            assertEquals("route-1", service.status().get("lastPolledRouteId"));
            assertEquals("TAGO_HTTP_429", service.status().get("lastFailureReason"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void observationModeCyclesAll105RoutesAndKeepsEveryVehicleIncludingZeroOrderAndUnknownDirection() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<String> routes = IntStream.rangeClosed(1, 105).mapToObj(i -> "route-" + i).toList();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertTrue(sql.contains("FROM bus_routes"));
            assertTrue(!sql.contains("android_device_vehicle_assignments"));
            assertTrue(!sql.contains("bus_route_stops"));
            return routes;
        }).when(jdbc).query(anyString(), any(RowMapper.class), org.mockito.ArgumentMatchers.<Object[]>any());

        String xml = """
                <response><header><resultCode>00</resultCode></header><body><items>
                  <item><vehicleno>외부차량-1</vehicleno><nodeid></nodeid><nodeord>0</nodeord>
                    <updowncd>UNKNOWN-RAW</updowncd><gpslati>invalid</gpslati></item>
                  <item><vehicleno>외부차량-2</vehicleno><nodeid>not-in-catalog</nodeid><nodeord>7</nodeord>
                    <updowncd>2</updowncd><gpslati>35.1</gpslati><gpslong>126.8</gpslong></item>
                  <item><vehicleno>외부차량-3</vehicleno><nodeord>unparsed</nodeord></item>
                </items><totalCount>3</totalCount></body></response>
                """;
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(xml.getBytes(StandardCharsets.UTF_8));
        doReturn(response).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        var service = new TagoVehicleLocationService(jdbc, "key", "AUTO", "36450", "http://localhost",
                true, true, 10000, false, true, http);

        try (MockedStatic<TagoApiRequestThrottle> throttle = mockStatic(TagoApiRequestThrottle.class)) {
            for (int i = 0; i < 106; i++) assertEquals(3, service.pollNextAssignedRoute());
            throttle.verify(TagoApiRequestThrottle::awaitRequestSlot, times(106));
        }

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(106)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        for (int i = 0; i < 106; i++) {
            String query = requests.getAllValues().get(i).uri().getQuery();
            assertTrue(query.contains("routeId=" + routes.get(i % 105) + "&"));
            assertTrue(query.contains("numOfRows=100"));
        }
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(318)).update(contains("'TAGO_OBSERVATION'"),
                any(), any(), any(), any(), any(), any(), payloads.capture());
        verify(jdbc, times(106)).update(contains("'TAGO_OBSERVATION'"),
                eq("외부차량-1"), anyString(), eq(""), eq(0), isNull(), isNull(), anyString());
        var raw = new ObjectMapper().readTree(payloads.getAllValues().get(0));
        assertEquals("0", raw.get("nodeord").asText());
        assertEquals("UNKNOWN-RAW", raw.get("updowncd").asText());
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), org.mockito.ArgumentMatchers.<Object[]>any());
        assertEquals(105, service.status().get("routeCount"));
        assertEquals("ROUND_ROBIN_ALL_ROUTES", service.status().get("pollMode"));
    }
}
