package com.smartbis.backend.tago;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class TagoCatalogServiceTest {
    @Test
    void supportsEncodedAndDecodedServiceKeysWithoutTreatingPlusAsSpace() {
        String decoded = "AbC+12/==";
        String encoded = "AbC%2B12%2F%3D%3D";
        assertEquals(decoded, TagoCatalogService.normalizeServiceKey(decoded, "DECODED"));
        assertEquals(decoded, TagoCatalogService.normalizeServiceKey(encoded, "ENCODED"));
        assertEquals(decoded, TagoCatalogService.normalizeServiceKey(encoded, "AUTO"));
    }

    @Test
    void parsesRouteAndOrderedStopFromTagoXml() throws Exception {
        Document doc = TagoCatalogService.parseXml("""
                <response><header><resultCode>00</resultCode></header><body><items>
                  <item><routeid>JSE0001</routeid><routeno>1-1</routeno><routetp>농어촌버스</routetp></item>
                </items><totalCount>1</totalCount></body></response>
                """);
        var route = TagoCatalogService.routeFrom((org.w3c.dom.Element) doc.getElementsByTagName("item").item(0));
        assertEquals("JSE0001", route.id());
        assertEquals("1-1", route.name());
        assertEquals("농어촌버스", route.type());

        Document stopDoc = TagoCatalogService.parseXml("""
                <response><body><items><item><nodeid>STOP1</nodeid><nodenm>장성터미널</nodenm>
                  <nodeord>7</nodeord><updowncd>1</updowncd><gpslati>35.3012</gpslati><gpslong>126.7845</gpslong>
                </item></items></body></response>
                """);
        var stop = TagoCatalogService.stopFrom(
                (org.w3c.dom.Element) stopDoc.getElementsByTagName("item").item(0), route.id(), 1);
        assertEquals("JSE0001", stop.routeId());
        assertEquals(7, stop.order());
        assertEquals("DOWN", stop.direction());
        assertEquals("1", stop.directionCode());
        assertEquals(new BigDecimal("35.3012"), stop.latitude());
        assertEquals(new BigDecimal("126.7845"), stop.longitude());
    }

    @Test
    void preservesUnexpectedRawDirectionCodeForDiagnosis() throws Exception {
        Document doc = TagoCatalogService.parseXml("""
                <response><item><nodeid>STOP-X</nodeid><nodenm>진단 정류장</nodenm>
                  <nodeord>1</nodeord><updowncd>2</updowncd>
                </item></response>
                """);

        var stop = TagoCatalogService.stopFrom(
                (org.w3c.dom.Element) doc.getElementsByTagName("item").item(0), "ROUTE-X", 1);

        assertEquals("UNKNOWN", stop.direction());
        assertEquals("2", stop.directionCode());
    }

    @Test
    void infersUnknownDirectionsFromJangseongHubReferenceHubAndSameNameEndpoints() {
        var routes = java.util.List.of(
                new TagoCatalogService.Route("TSB342000042", "24", null),
                new TagoCatalogService.Route("R-OUT", "23", null),
                new TagoCatalogService.Route("R-IN", "23", null),
                new TagoCatalogService.Route("R-FAMILY-BASE", "진원100", null),
                new TagoCatalogService.Route("R-FAMILY-SAME", "진원100", null),
                new TagoCatalogService.Route("R-FAMILY-REVERSE", "진원100", null),
                new TagoCatalogService.Route("R-UNRESOLVED", "other", null),
                new TagoCatalogService.Route("R-LOOP", "loop", null));
        var stops = java.util.List.of(
                stop("TSB342000042", "SA", "사거리버스터미널", 1, "UP"),
                stop("TSB342000042", "SB", "외곽 종점", 8, "UP"),
                stop("TSB342000042", "SB", "외곽 종점", 1, "DOWN"),
                stop("TSB342000042", "SA", "사거리버스터미널", 8, "DOWN"),
                stop("R-OUT", "SA", "사거리버스터미널", 1, "UNKNOWN"),
                stop("R-OUT", "X", "마을 정류장", 2, "UNKNOWN"),
                stop("R-IN", "Y", "마을 정류장", 1, "UNKNOWN"),
                stop("R-IN", "SA", "사거리버스터미널", 2, "UNKNOWN"),
                stop("R-FAMILY-BASE", "LOTTE", "롯데백화점", 1, "UNKNOWN"),
                stop("R-FAMILY-BASE", "JANG", "장성터미널", 2, "UNKNOWN"),
                stop("R-FAMILY-SAME", "LOTTE-ALT", "롯데백화점", 1, "UNKNOWN"),
                stop("R-FAMILY-SAME", "CHODONG", "초동", 2, "UNKNOWN"),
                stop("R-FAMILY-REVERSE", "CHODONG", "초동", 1, "UNKNOWN"),
                stop("R-FAMILY-REVERSE", "LOTTE-ALT", "롯데백화점", 2, "UNKNOWN"),
                stop("R-UNRESOLVED", "A", "마을 A", 1, "UNKNOWN"),
                stop("R-UNRESOLVED", "B", "마을 B", 2, "UNKNOWN"),
                stop("R-LOOP", "JANG", "장성터미널", 1, "UNKNOWN"),
                stop("R-LOOP", "JANG", "장성터미널", 3, "UNKNOWN"));

        var result = TagoCatalogService.inferUnknownDirections(routes, stops);
        var inferred = result.stops().stream().collect(java.util.stream.Collectors.toMap(
                s -> s.routeId() + ":" + s.id(), TagoCatalogService.RouteStop::direction, (a, b) -> a));

        assertEquals("UP", inferred.get("R-OUT:SA"));
        assertEquals("DOWN", inferred.get("R-IN:SA"));
        assertEquals("DOWN", inferred.get("R-FAMILY-BASE:JANG"));
        assertEquals("DOWN", inferred.get("R-FAMILY-SAME:CHODONG"));
        assertEquals("UP", inferred.get("R-FAMILY-REVERSE:CHODONG"));
        assertEquals("UNKNOWN", inferred.get("R-UNRESOLVED:A"));
        assertEquals(10, result.inferredStopCount());
        assertEquals(4, result.unresolvedStopCount());
        assertEquals("2", result.stops().stream().filter(s -> s.routeId().equals("R-OUT"))
                .findFirst().orElseThrow().directionCode());
    }

    @Test
    void reviewedRouteDirectionOverridesEndpointInferenceWithoutChangingStopRows() {
        var routes = java.util.List.of(new TagoCatalogService.Route("TSB342000030", "20", null));
        var stops = java.util.List.of(
                stop("TSB342000030", "SA", "사거리버스터미널", 1, "UNKNOWN"),
                stop("TSB342000030", "JANG", "장성터미널", 2, "UNKNOWN"));

        var result = TagoCatalogService.inferUnknownDirections(routes, stops);

        assertEquals(java.util.List.of("UP", "UP"), result.stops().stream()
                .map(TagoCatalogService.RouteStop::direction).toList());
        assertEquals(java.util.List.of("SA", "JANG"), result.stops().stream()
                .map(TagoCatalogService.RouteStop::id).toList());
        assertEquals(java.util.List.of(1, 2), result.stops().stream()
                .map(TagoCatalogService.RouteStop::order).toList());
        assertEquals(2, result.inferredStopCount());
    }

    private static TagoCatalogService.RouteStop stop(String routeId, String nodeId, String name,
                                                       int order, String direction) {
        return new TagoCatalogService.RouteStop(routeId, nodeId, name, order, direction, "2",
                null, null, order, false);
    }

    @Test
    void rejectsExternalEntityXml() {
        String xml = "<!DOCTYPE response [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><response>&xxe;</response>";
        assertThrows(Exception.class, () -> TagoCatalogService.parseXml(xml));
    }

    @Test
    void retriesOnlyTransientTagoFailures() {
        assertTrue(TagoCatalogService.isRetryableHttpStatus(429));
        assertTrue(TagoCatalogService.isRetryableHttpStatus(502));
        assertFalse(TagoCatalogService.isRetryableHttpStatus(403));
        assertFalse(TagoCatalogService.isRetryableHttpStatus(400));
        assertTrue(TagoCatalogService.isRetryableResultCode("99"));
        assertFalse(TagoCatalogService.isRetryableResultCode("30"));
        assertFalse(TagoCatalogService.isRetryableResultCode("00"));
    }

    @Test
    void retriesTransientFailuresUpToConfiguredAttemptCount() throws Exception {
        int[] calls = {0};
        int[] waits = {0};
        String value = TagoCatalogService.retry(3, attempt -> {
            calls[0]++;
            if (attempt < 3) throw new TagoCatalogService.RetryOperationException(new java.io.IOException());
            return "ok";
        }, failedAttempt -> waits[0]++);

        assertEquals("ok", value);
        assertEquals(3, calls[0]);
        assertEquals(2, waits[0]);
    }

    @Test
    void fallsBackToResponseSequenceWhenStopOrderIsMissingOrInvalid() throws Exception {
        Document doc = TagoCatalogService.parseXml("""
                <response><item><nodeid>STOP2</nodeid><nodenm>장성역</nodenm><nodeord>0</nodeord></item></response>
                """);
        var stop = TagoCatalogService.stopFrom(
                (org.w3c.dom.Element) doc.getElementsByTagName("item").item(0), "ROUTE1", 12);

        assertEquals(12, stop.order());
        assertTrue(stop.orderInferred());

        var normalized = TagoCatalogService.normalizeStopOrders(java.util.List.of(stop));
        assertEquals(1, normalized.get(0).order());
    }

    @Test
    void normalizesStopOrdersPerRouteAndDirectionToAvoidUniqueKeyCollisions() {
        var stops = java.util.List.of(
                new TagoCatalogService.RouteStop("R1", "A", "A", 1, "UP", null, null, 1, false),
                new TagoCatalogService.RouteStop("R1", "B", "B", 12, "UP", null, null, 2, true),
                new TagoCatalogService.RouteStop("R1", "C", "C", 12, "DOWN", null, null, 3, false),
                new TagoCatalogService.RouteStop("R1", "D", "D", 4, "UP", null, null, 4, false));

        var normalized = TagoCatalogService.normalizeStopOrders(stops);

        assertEquals(java.util.List.of(1, 2, 12, 4), normalized.stream().map(TagoCatalogService.RouteStop::order).toList());
        assertTrue(normalized.get(1).orderInferred());
        assertFalse(normalized.get(3).orderInferred());
    }

    @Test
    void reportsRepeatedStopPrimaryKeysSeparatelyFromRepeatedRouteVisits() {
        var stops = java.util.List.of(
                new TagoCatalogService.RouteStop("R1", "STOP-A", "정류장 A", 3, "UP", null, null, 3, false),
                new TagoCatalogService.RouteStop("R1", "STOP-A", "정류장 A", 8, "UP", null, null, 8, false),
                new TagoCatalogService.RouteStop("R1", "STOP-A", "정류장 A", 3, "DOWN", null, null, 3, false));

        var duplicates = TagoCatalogService.duplicateStopKeys(stops);

        assertEquals(1, duplicates.size());
        assertEquals("R1", duplicates.get(0).get("routeId"));
        assertEquals("STOP-A", duplicates.get(0).get("nodeId"));
        assertEquals("UP", duplicates.get(0).get("direction"));
        assertEquals(java.util.List.of(3, 8), duplicates.get(0).get("nodeOrders"));
    }

    @Test
    void reportsDuplicateOrderKeysPerRouteAndDirection() {
        var stops = java.util.List.of(
                new TagoCatalogService.RouteStop("R1", "STOP-A", "정류장 A", 4, "UP", null, null, 1, false),
                new TagoCatalogService.RouteStop("R1", "STOP-B", "정류장 B", 4, "UP", null, null, 2, false),
                new TagoCatalogService.RouteStop("R1", "STOP-C", "정류장 C", 4, "DOWN", null, null, 3, false));

        var duplicates = TagoCatalogService.duplicateStopOrders(stops);

        assertEquals(1, duplicates.size());
        assertEquals("R1", duplicates.get(0).get("routeId"));
        assertEquals("UP", duplicates.get(0).get("direction"));
        assertEquals(4, duplicates.get(0).get("nodeOrder"));
        assertEquals(java.util.List.of("STOP-A", "STOP-B"), duplicates.get(0).get("nodeIds"));
    }

    @Test
    void distinguishesSameStopRepeatedAtDifferentOrdersFromSameOrderDuplicate() {
        var stops = java.util.List.of(
                new TagoCatalogService.RouteStop("R1", "STOP-A", "정류장 A", 2, "UP", null, null, 1, false),
                new TagoCatalogService.RouteStop("R1", "STOP-A", "정류장 A", 7, "UP", null, null, 2, false),
                new TagoCatalogService.RouteStop("R1", "STOP-B", "정류장 B", 5, "UP", null, null, 3, false),
                new TagoCatalogService.RouteStop("R1", "STOP-B", "정류장 B", 5, "UP", null, null, 4, false));

        var groups = TagoCatalogService.duplicateStopKeys(stops);
        var differentOrderVisit = groups.stream()
                .filter(group -> "STOP-A".equals(group.get("nodeId"))).findFirst().orElseThrow();
        var sameOrderDuplicate = groups.stream()
                .filter(group -> "STOP-B".equals(group.get("nodeId"))).findFirst().orElseThrow();

        assertEquals(java.util.List.of(2, 7), differentOrderVisit.get("nodeOrders"));
        assertEquals(java.util.List.of(5, 5), sameOrderDuplicate.get("nodeOrders"));
    }

    @Test
    void doesNotRetryPermanentFailures() {
        int[] calls = {0};
        assertThrows(IllegalArgumentException.class, () -> TagoCatalogService.retry(3, attempt -> {
            calls[0]++;
            throw new TagoCatalogService.OperationException(new IllegalArgumentException("permanent"));
        }, failedAttempt -> fail("Permanent failure must not retry")));
        assertEquals(1, calls[0]);
    }
}
