package com.smartbis.backend.tago;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polls TAGO route-based vehicle GPS and persists only valid, fresh observations. */
@Service
public class TagoVehicleLocationService {
    private static final Logger log = LoggerFactory.getLogger(TagoVehicleLocationService.class);
    private static final String PATH = "/1613000/BusLcInfoInqireService/getRouteAcctoBusLcList";
    private static final int PAGE_SIZE = 10;
    // One TAGO key is shared by all route calls; 10 seconds caps usage below 10,000/day.
    private static final long MIN_REQUEST_GAP_MILLIS = TagoApiRequestThrottle.MIN_REQUEST_GAP_MILLIS;
    private static final long RATE_LIMIT_COOLDOWN_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9a-fA-F]{2}");

    private final JdbcTemplate jdbc;
    private final HttpClient http;
    private final String serviceKey;
    private final String keyMode;
    private final String cityCode;
    private final String baseUrl;
    private final boolean enabled;
    private final boolean mockMode;
    private final long pollIntervalMs;
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile long nextAllowedPollAtMillis;
    private volatile OffsetDateTime lastCompletedAt;
    private volatile int lastSavedCount;
    private volatile int lastFailedRouteCount;
    private volatile int lastRouteCount;
    private volatile int lastSucceededRouteCount;
    private volatile int lastUnattemptedRouteCount;
    private volatile String lastFailedRouteId = "";
    private volatile String lastPolledRouteId = "";
    private volatile String lastFailureReason = "";
    private volatile String lastResult = "NOT_RUN";
    private int nextRouteIndex;

    @Autowired
    public TagoVehicleLocationService(
            JdbcTemplate jdbc,
            @Value("${tago.service-key:}") String serviceKey,
            @Value("${tago.service-key-mode:AUTO}") String keyMode,
            @Value("${tago.city-code:}") String cityCode,
            @Value("${tago.base-url:http://apis.data.go.kr}") String baseUrl,
            @Value("${tago.enabled:false}") boolean tagoEnabled,
            @Value("${tago.vehicle-location.enabled:false}") boolean enabled,
            @Value("${tago.vehicle-location.poll-interval-ms:10000}") long pollIntervalMs,
            @Value("${tago.mock-mode:true}") boolean mockMode) {
        this(jdbc, serviceKey, keyMode, cityCode, baseUrl, tagoEnabled,
                enabled, pollIntervalMs, mockMode,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    TagoVehicleLocationService(
            JdbcTemplate jdbc,
            String serviceKey,
            String keyMode,
            String cityCode,
            String baseUrl,
            boolean tagoEnabled,
            boolean enabled,
            long pollIntervalMs,
            boolean mockMode,
            HttpClient http) {
        this.jdbc = jdbc;
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.keyMode = keyMode;
        this.cityCode = cityCode == null ? "" : cityCode.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.enabled = tagoEnabled && enabled;
        this.pollIntervalMs = pollIntervalMs;
        this.mockMode = mockMode;
        this.http = http;
    }

    @Scheduled(fixedDelayString = "${tago.vehicle-location.poll-interval-ms:10000}", initialDelay = 15000)
    public void pollScheduled() {
        if (!enabled || mockMode) {
            lastResult = mockMode ? "MOCK_MODE" : "DISABLED";
            return;
        }
        if (System.currentTimeMillis() < nextAllowedPollAtMillis) {
            lastResult = "RETRY_BACKOFF";
            return;
        }
        if (!polling.compareAndSet(false, true)) {
            log.warn("TAGO vehicle-location poll skipped because previous poll is still running");
            return;
        }
        try {
            int saved = pollNextAssignedRoute();
            lastSavedCount = saved;
            lastCompletedAt = OffsetDateTime.now();
            if (!"NO_ASSIGNED_ROUTES".equals(lastResult)) {
                lastResult = lastFailedRouteCount == 0 ? "UP" : "DEGRADED";
            }
            log.info("TAGO vehicle-location poll completed: observationsSaved={}", saved);
        } catch (Exception e) {
            lastCompletedAt = OffsetDateTime.now();
            lastResult = "FAILED_" + e.getClass().getSimpleName();
            log.error("TAGO vehicle-location poll failed: exceptionType={}", e.getClass().getSimpleName());
        } finally {
            polling.set(false);
        }
    }

    public java.util.Map<String, Object> status() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", !enabled ? "DISABLED" : mockMode ? "MOCK_MODE" : lastResult);
        result.put("enabled", enabled);
        result.put("mockMode", mockMode);
        result.put("pollIntervalMs", pollIntervalMs);
        result.put("minRequestGapMs", MIN_REQUEST_GAP_MILLIS);
        result.put("maxLocationRequestsPerDayAtConfiguredGap", 86_400_000L / MIN_REQUEST_GAP_MILLIS);
        result.put("lastCompletedAt", lastCompletedAt == null ? "" : lastCompletedAt.toString());
        result.put("lastSavedCount", lastSavedCount);
        result.put("routeCount", lastRouteCount);
        result.put("lastPolledRouteId", lastPolledRouteId);
        result.put("pollMode", "ROUND_ROBIN_ASSIGNED_ROUTES");
        result.put("estimatedRouteRefreshSeconds", lastRouteCount == 0 ? 0
                : (lastRouteCount * Math.max(MIN_REQUEST_GAP_MILLIS, pollIntervalMs)) / 1_000);
        result.put("succeededRouteCount", lastSucceededRouteCount);
        result.put("failedRouteCount", lastFailedRouteCount);
        result.put("unattemptedRouteCount", lastUnattemptedRouteCount);
        result.put("lastFailedRouteId", lastFailedRouteId);
        result.put("lastFailureReason", lastFailureReason);
        result.put("nextAllowedPollAt", nextAllowedPollAtMillis <= System.currentTimeMillis()
                ? "" : OffsetDateTime.now().plusNanos((nextAllowedPollAtMillis - System.currentTimeMillis()) * 1_000_000).toString());
        try {
            String latestPositionAt = jdbc.queryForObject(
                    "SELECT MAX(observed_at)::text FROM bus_positions WHERE source = 'TAGO'", String.class);
            result.put("latestPositionAt", latestPositionAt == null ? "" : latestPositionAt);
        } catch (RuntimeException ignored) {
            result.put("latestPositionAt", "");
        }
        return result;
    }

    int pollNextAssignedRoute() throws Exception {
        if (serviceKey.isBlank() || cityCode.isBlank()) {
            throw new IllegalStateException("TAGO location polling requires service key and city code");
        }
        List<String> routeIds = assignedActiveRouteIds();
        lastRouteCount = routeIds.size();
        lastSavedCount = 0;
        lastSucceededRouteCount = 0;
        lastFailedRouteCount = 0;
        lastFailedRouteId = "";
        lastFailureReason = "";
        lastUnattemptedRouteCount = 0;
        if (routeIds.isEmpty()) {
            lastResult = "NO_ASSIGNED_ROUTES";
            lastPolledRouteId = "";
            log.info("TAGO vehicle-location poll skipped: no active vehicle assignments");
            return 0;
        }

        int selectedIndex = Math.floorMod(nextRouteIndex, routeIds.size());
        String routeId = routeIds.get(selectedIndex);
        nextRouteIndex = (selectedIndex + 1) % routeIds.size();
        lastPolledRouteId = routeId;
        int saved = pollRoute(routeId);
        if (saved < 0) {
            lastFailedRouteCount = 1;
            log.info("TAGO vehicle-location route poll finished: assignedRoutes={} routeId={} result=FAILED saved=0",
                    routeIds.size(), routeId);
            return 0;
        }
        lastSucceededRouteCount = 1;
        log.info("TAGO vehicle-location route poll finished: assignedRoutes={} routeId={} result=SUCCESS saved={}",
                routeIds.size(), routeId, saved);
        return saved;
    }

    private List<String> assignedActiveRouteIds() {
        return jdbc.query("""
                SELECT DISTINCT r.route_id
                  FROM bus_routes r
                  JOIN bus_vehicles v
                    ON v.route_id = r.route_id AND v.city_code = r.city_code AND v.active = true
                  JOIN android_device_vehicle_assignments a
                    ON a.vehicle_number = v.vehicle_number
                 WHERE r.city_code = ? AND r.active = true
                 ORDER BY r.route_id
                """, (rs, rowNum) -> rs.getString(1), cityCode);
    }

    private int pollRoute(String routeId) {
        try {
            int saved = 0;
            for (VehiclePosition position : fetchRoutePositions(routeId)) {
                persist(position);
                saved++;
                // Keep the location-ingest job limited to route-position calls. ETA is fetched
                // separately on demand by the arrival endpoint and cached by route + next stop.
            }
            return saved;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String reason = e.getMessage();
            if (reason == null || !reason.matches("TAGO_(HTTP_[0-9]{3}|RESULT_[A-Za-z0-9_-]{1,30}|BODY_MISSING)")) {
                reason = e.getClass().getSimpleName().matches("[A-Za-z][A-Za-z0-9]{0,79}")
                        ? e.getClass().getSimpleName() : "DETAIL_UNAVAILABLE";
            }
            log.warn("TAGO vehicle-location route query failed: routeId={} exceptionType={} failureReason={}",
                    routeId, e.getClass().getSimpleName(), reason);
            lastFailedRouteId = routeId;
            lastFailureReason = reason;
            long backoff = "TAGO_HTTP_429".equals(reason)
                    ? RATE_LIMIT_COOLDOWN_MILLIS : Duration.ofMinutes(2).toMillis();
            nextAllowedPollAtMillis = System.currentTimeMillis() + backoff;
            return -2;
        }
    }

    private List<VehiclePosition> fetchRoutePositions(String routeId) throws Exception {
        List<VehiclePosition> results = new ArrayList<>();
        int page = 1;
        int totalCount;
        do {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl + PATH)
                    .queryParam("serviceKey", normalizedServiceKey())
                    .queryParam("cityCode", cityCode)
                    .queryParam("routeId", routeId)
                    .queryParam("numOfRows", PAGE_SIZE)
                    .queryParam("pageNo", page)
                    .queryParam("_type", "xml")
                    .build().encode().toUri();
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/xml").GET().build();
            HttpResponse<byte[]> response = sendRateLimitedRequest(request);
            Document document = parseXml(response.body());
            Element root = document.getDocumentElement();
            Element header = first(root, "header");
            if (header == null || !"00".equals(text(header, "resultCode"))) {
                throw new IllegalStateException("TAGO_RESULT_" + (header == null ? "MISSING" : safeCode(text(header, "resultCode"))));
            }
            Element body = first(root, "body");
            if (body == null) throw new IllegalStateException("TAGO_BODY_MISSING");
            totalCount = integer(text(body, "totalCount"), 0);
            Element items = first(body, "items");
            for (Element item : children(items, "item")) {
                VehiclePosition position = positionFrom(routeId, item);
                if (position != null) results.add(position);
            }
            page++;
        } while ((page - 1) * PAGE_SIZE < totalCount);
        return results;
    }

    private HttpResponse<byte[]> sendRateLimitedRequest(HttpRequest request) throws Exception {
        TagoApiRequestThrottle.awaitRequestSlot();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 429) {
            throw new IllegalStateException("TAGO_HTTP_429");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("TAGO_HTTP_" + response.statusCode());
        }
        return response;
    }

    private String normalizedServiceKey() {
        String value = serviceKey;
        String mode = keyMode == null ? "AUTO" : keyMode.trim().toUpperCase();
        if ("DECODED".equals(mode)) return value;
        if ("ENCODED".equals(mode) || ("AUTO".equals(mode) && PERCENT_ESCAPE.matcher(value).find())) {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        }
        if (!"AUTO".equals(mode)) throw new IllegalArgumentException("Unsupported TAGO service key mode");
        return value;
    }

    private VehiclePosition positionFrom(String routeId, Element item) {
        String vehicleNumber = text(item, "vehicleno");
        String nodeId = text(item, "nodeid");
        int nodeOrder = integer(text(item, "nodeord"), -1);
        double latitude = decimal(text(item, "gpslati"), Double.NaN);
        double longitude = decimal(text(item, "gpslong"), Double.NaN);
        if (vehicleNumber.isBlank() || nodeId.isBlank() || nodeOrder < 1
                || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;
        List<String> directions = jdbc.query("""
                SELECT DISTINCT direction FROM bus_route_stops
                 WHERE route_id = ? AND node_id = ? AND node_order = ?
                   AND direction IN ('UP', 'DOWN')
                """, (rs, rowNum) -> rs.getString(1), routeId, nodeId, nodeOrder);
        String direction = directions.size() == 1 ? directions.get(0) : null;
        return new VehiclePosition(vehicleNumber, routeId, nodeId, nodeOrder,
                latitude, longitude, direction);
    }

    private boolean persist(VehiclePosition position) {
        Long vehicleId = jdbc.queryForObject("""
                INSERT INTO bus_vehicles(vehicle_number, city_code, route_id, active,
                                         last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (vehicle_number) DO UPDATE SET
                    city_code = EXCLUDED.city_code,
                    route_id = EXCLUDED.route_id,
                    active = true,
                    last_seen_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING vehicle_id
                """, Long.class, position.vehicleNumber(), cityCode, position.routeId());
        jdbc.update("""
                INSERT INTO bus_positions(vehicle_id, vehicle_number, route_id, direction,
                                          node_id, node_order, latitude, longitude,
                                          observed_at, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'TAGO')
                """, vehicleId, position.vehicleNumber(), position.routeId(), position.direction(),
                position.nodeId(), position.nodeOrder(), position.latitude(), position.longitude());
        return true;
    }

    static Document parseXml(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return builder.parse(new ByteArrayInputStream(body));
    }

    private static Element first(Element parent, String name) {
        if (parent == null) return null;
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) result.add(element);
        }
        return result;
    }

    private static String text(Element parent, String name) {
        Element element = first(parent, name);
        return element == null ? "" : element.getTextContent().trim();
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return fallback; }
    }

    private static double decimal(String value, double fallback) {
        try { return Double.parseDouble(value); } catch (RuntimeException ignored) { return fallback; }
    }

    private static String safeCode(String code) { return code.matches("[A-Za-z0-9_-]{1,30}") ? code : "INVALID"; }

    record VehiclePosition(String vehicleNumber, String routeId, String nodeId, int nodeOrder,
                           double latitude, double longitude, String direction) { }
}
