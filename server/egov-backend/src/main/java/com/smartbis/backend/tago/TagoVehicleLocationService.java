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
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polls assigned routes normally, or preserves every returned item in observation mode. */
@Service
public class TagoVehicleLocationService {
    private static final Logger log = LoggerFactory.getLogger(TagoVehicleLocationService.class);
    private static final String PATH = "/1613000/BusLcInfoInqireService/getRouteAcctoBusLcList";
    private static final int PAGE_SIZE = 100;
    private static final ObjectMapper JSON = new ObjectMapper();
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
    private final boolean observationMode;
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
    private TagoLocationExecution operationalExecution;
    private RouteCallPlanScheduler operationalScheduler;
    private PositionSnapshots snapshots=new PositionSnapshots();
    private record LaneCall(String route,RouteCallSchedule.Role lane,long gapMs) {}
    private final ThreadLocal<LaneCall> laneCall=new ThreadLocal<>();
    @Autowired(required=false)
    void setSnapshots(PositionSnapshots value) { snapshots=value; }

    @Autowired(required = false)
    void setOperationalExecution(TagoLocationExecution execution) {
        this.operationalExecution = execution;
    }
    @Autowired(required = false)
    void setOperationalScheduler(RouteCallPlanScheduler scheduler) { this.operationalScheduler = scheduler; }

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
            @Value("${tago.mock-mode:true}") boolean mockMode,
            @Value("${tago.location.observation-mode:false}") boolean observationMode) {
        this(jdbc, serviceKey, keyMode, cityCode, baseUrl, tagoEnabled,
                enabled, pollIntervalMs, mockMode, observationMode,
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
            boolean observationMode,
            HttpClient http) {
        this.jdbc = jdbc;
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.keyMode = keyMode;
        this.cityCode = cityCode == null ? "" : cityCode.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.enabled = tagoEnabled && enabled;
        this.pollIntervalMs = pollIntervalMs;
        this.mockMode = mockMode;
        this.observationMode = observationMode;
        this.http = http;
    }

    @Scheduled(fixedDelayString = "#{${tago.location.operations.enabled:false} ? 1000 : ${tago.vehicle-location.poll-interval-ms:10000}}", initialDelay = 15000)
    public void pollScheduled() {
        if (!enabled || mockMode) {
            lastResult = mockMode ? "MOCK_MODE" : "DISABLED";
            return;
        }
        // Legacy 105-route / 10-second polling is no longer a runtime collection path.
        if(operationalScheduler!=null) return;
        lastResult="NO_ENABLED_LANE_SCHEDULER";
        return;
    }

    @Scheduled(fixedDelay=1000,initialDelay=15000,scheduler="laneAScheduler")
    public void pollLaneA() { pollLane(RouteCallSchedule.Role.HUNDRED); }
    @Scheduled(fixedDelay=1000,initialDelay=15000,scheduler="laneBScheduler")
    public void pollLaneB() { pollLane(RouteCallSchedule.Role.LOCAL_WINDOW); }
    @Scheduled(fixedDelay=1000,initialDelay=15000,scheduler="laneCScheduler")
    public void pollLaneC() { pollLane(RouteCallSchedule.Role.BACKUP); }
    void pollLane(RouteCallSchedule.Role lane) {
        if(!enabled||mockMode||operationalExecution==null||operationalScheduler==null) return;
        if(!operationalExecution.locationTimeAllowed()) return;
        long gap=operationalScheduler.gapMillis(lane);
        if(!operationalExecution.readyLane(lane,gap)) return;
        if(serviceKey.isBlank()||cityCode.isBlank()) { lastResult="MISSING_CREDENTIALS"; return; }
        try {
            var route=operationalScheduler.next(lane);
            if(route.isEmpty()) return;
            laneCall.set(new LaneCall(route.get(),lane,gap));
            lastPolledRouteId=route.get();
            int saved=pollRoute(route.get());
            lastSavedCount=Math.max(0,saved);lastCompletedAt=OffsetDateTime.now();
            lastResult=saved>=0?"UP":"DEGRADED";lastRouteCount=RouteTimetable.DEFAULT.all().size();
            lastSucceededRouteCount=saved>=0?1:0;lastFailedRouteCount=saved<0?1:0;
            if(saved>=0 && lane==RouteCallSchedule.Role.BACKUP) operationalScheduler.laneCSucceeded(route.get());
        } finally { laneCall.remove(); }
    }

    /** Kept as a direct legacy API; not registered as a scheduled collector. */
    void pollLegacyDirect() {
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
            if (!lastResult.startsWith("NO_")) {
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
        result.put("observationMode", observationMode);
        result.put("pollIntervalMs", pollIntervalMs);
        result.put("minRequestGapMs", MIN_REQUEST_GAP_MILLIS);
        result.put("maxLocationRequestsPerDayAtConfiguredGap", 86_400_000L / MIN_REQUEST_GAP_MILLIS);
        result.put("lastCompletedAt", lastCompletedAt == null ? "" : lastCompletedAt.toString());
        result.put("lastSavedCount", lastSavedCount);
        result.put("routeCount", lastRouteCount);
        result.put("lastPolledRouteId", lastPolledRouteId);
        result.put("pollMode", observationMode ? "ROUND_ROBIN_ALL_ROUTES" : "ROUND_ROBIN_ASSIGNED_ROUTES");
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
                    "SELECT MAX(observed_at)::text FROM bus_positions WHERE source = ?", String.class,
                    observationMode ? "TAGO_OBSERVATION" : "TAGO");
            result.put("latestPositionAt", latestPositionAt == null ? "" : latestPositionAt);
        } catch (RuntimeException ignored) {
            result.put("latestPositionAt", "");
        }
        return result;
    }

    int pollNextAssignedRoute() throws Exception {
        if (operationalScheduler != null) {
            if (operationalExecution == null) throw new IllegalStateException("LOCATION_GUARD_MISSING");
            // Check only, without spending quota or consuming a scheduled slot.
            if (!operationalExecution.ready()) { lastResult = "NO_LOCATION_GUARD_SLOT"; return 0; }
            var route = operationalScheduler.next();
            lastSavedCount = 0; lastSucceededRouteCount = 0; lastFailedRouteCount = 0;
            if (route.isEmpty()) { lastResult = "NO_DUE_ENABLED_PLANS"; lastPolledRouteId = ""; return 0; }
            if (serviceKey.isBlank() || cityCode.isBlank()) throw new IllegalStateException("Location plan requires API credentials");
            lastPolledRouteId = route.get();
            int saved = pollRoute(route.get());
            lastFailedRouteCount = saved < 0 ? 1 : 0;
            lastSucceededRouteCount = saved < 0 ? 0 : 1;
            return Math.max(0, saved);
        }
        if (serviceKey.isBlank() || cityCode.isBlank()) {
            throw new IllegalStateException("TAGO location polling requires service key and city code");
        }
        List<String> routeIds = observationMode ? allActiveRouteIds() : assignedActiveRouteIds();
        lastRouteCount = routeIds.size();
        lastSavedCount = 0;
        lastSucceededRouteCount = 0;
        lastFailedRouteCount = 0;
        lastFailedRouteId = "";
        lastFailureReason = "";
        lastUnattemptedRouteCount = 0;
        if (routeIds.isEmpty()) {
            lastResult = observationMode ? "NO_ACTIVE_ROUTES" : "NO_ASSIGNED_ROUTES";
            lastPolledRouteId = "";
            log.info("TAGO vehicle-location poll skipped: no routes for observationMode={}", observationMode);
            return 0;
        }

        int selectedIndex = Math.floorMod(nextRouteIndex, routeIds.size());
        String routeId = routeIds.get(selectedIndex);
        nextRouteIndex = (selectedIndex + 1) % routeIds.size();
        lastPolledRouteId = routeId;
        int saved = pollRoute(routeId);
        if (saved < 0) {
            lastFailedRouteCount = 1;
            log.info("TAGO vehicle-location route poll finished: routes={} observationMode={} routeId={} result=FAILED saved=0",
                    routeIds.size(), observationMode, routeId);
            return 0;
        }
        lastSucceededRouteCount = 1;
        log.info("TAGO vehicle-location route poll finished: routes={} observationMode={} routeId={} result=SUCCESS saved={}",
                routeIds.size(), observationMode, routeId, saved);
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

    private List<String> allActiveRouteIds() {
        return jdbc.query("""
                SELECT route_id FROM bus_routes
                 WHERE city_code = ? AND active = true
                 ORDER BY route_id
                """, (rs, rowNum) -> rs.getString(1), cityCode);
    }

    private int pollRoute(String routeId) {
        try {
            int saved = 0;
            for (VehiclePosition position : fetchRoutePositions(routeId)) {
                snapshots.observe(new PositionSnapshots.Position(position.vehicleNumber(),position.routeId(),position.nodeId(),position.nodeOrder(),
                    position.latitude(),position.longitude(),java.time.Instant.now()),false);
                if(operationalScheduler!=null) operationalScheduler.observed(position.routeId(),position.nodeOrder(),java.time.Instant.now());
                if(persist(position)) saved++;
                // Arrival collection runs separately; the app endpoint only reads saved values.
            }
            return saved;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String reason = TagoLocationExecution.failureReason(e);
            if (operationalScheduler != null) operationalScheduler.failure(routeId, reason);
            log.warn("TAGO vehicle-location route query failed: routeId={} exceptionType={} failureReason={}",
                    routeId, e.getClass().getSimpleName(), reason);
            lastFailedRouteId = routeId;
            lastFailureReason = reason;
            long backoff = "TAGO_HTTP_429".equals(reason)
                    ? RATE_LIMIT_COOLDOWN_MILLIS : Duration.ofMinutes(2).toMillis();
            if ("TAGO_HTTP_429".equals(reason) && operationalScheduler != null
                    && laneCall.get() != null && laneCall.get().lane() == RouteCallSchedule.Role.BACKUP) {
                operationalScheduler.laneCRateLimited();
            }
            if(laneCall.get()==null) nextAllowedPollAtMillis = System.currentTimeMillis() + backoff;
            return -2;
        }
    }

    private List<VehiclePosition> fetchRoutePositions(String routeId) throws Exception {
        routeId=RouteIdNormalizer.normalize(routeId);
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
                VehiclePosition position = observationMode
                        ? observationFrom(routeId, item) : positionFrom(routeId, item);
                if (position != null) results.add(position);
            }
            page++;
        } while ((page - 1) * PAGE_SIZE < totalCount);
        return results;
    }

    private HttpResponse<byte[]> sendRateLimitedRequest(HttpRequest request) throws Exception {
        var lane=laneCall.get();
        if(lane!=null) return operationalExecution.executeLane(lane.route(),lane.lane(),lane.gapMs(),()->sendDirectRequest(request));
        if (operationalExecution != null) {
            // Only the quota guard controls the operational gap, not the legacy shared throttle.
            return operationalExecution.execute(lastPolledRouteId, () -> sendDirectRequest(request));
        }
        return sendOriginalRateLimitedRequest(request);
    }

    private HttpResponse<byte[]> sendOriginalRateLimitedRequest(HttpRequest request) throws Exception {
        TagoApiRequestThrottle.awaitRequestSlot();
        return sendDirectRequest(request);
    }
    private HttpResponse<byte[]> sendDirectRequest(HttpRequest request) throws Exception {
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
        if (vehicleNumber.isBlank() || nodeId.isBlank() || nodeOrder < 0
                || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;
        List<String> directions = jdbc.query("""
                SELECT DISTINCT direction FROM bus_route_stops
                 WHERE route_id = ? AND node_id = ? AND node_order = ?
                   AND direction IN ('UP', 'DOWN')
                """, (rs, rowNum) -> rs.getString(1), routeId, nodeId, nodeOrder);
        String direction = directions.size() == 1 ? directions.get(0) : null;
        return new VehiclePosition(vehicleNumber, routeId, nodeId, nodeOrder,
                latitude, longitude, direction, null);
    }

    private VehiclePosition observationFrom(String routeId, Element item) throws Exception {
        var raw = new LinkedHashMap<String, String>();
        for (Node node = item.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                raw.put(element.getTagName(), element.getTextContent());
            }
        }
        Integer nodeOrder;
        try {
            nodeOrder = Integer.valueOf(text(item, "nodeord"));
        } catch (NumberFormatException ignored) {
            nodeOrder = null;
        }
        double latitude = decimal(text(item, "gpslati"), Double.NaN);
        double longitude = decimal(text(item, "gpslong"), Double.NaN);
        return new VehiclePosition(text(item, "vehicleno"), routeId, text(item, "nodeid"), nodeOrder,
                Double.isFinite(latitude) && latitude >= -90 && latitude <= 90 ? latitude : null,
                Double.isFinite(longitude) && longitude >= -180 && longitude <= 180 ? longitude : null,
                null, JSON.writeValueAsString(raw));
    }

    private boolean persist(VehiclePosition position) {
        if(!observationMode) return false;
        // Keep raw updowncd/nodeord without assignment or direction filtering.
        jdbc.update("""
                INSERT INTO bus_positions(vehicle_number, route_id, node_id, node_order,
                                          latitude, longitude, observed_at, source, raw_payload)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'TAGO_OBSERVATION', CAST(? AS jsonb))
                """, position.vehicleNumber(), position.routeId(), position.nodeId(), position.nodeOrder(),
                position.latitude(), position.longitude(), position.rawPayload());
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

    record VehiclePosition(String vehicleNumber, String routeId, String nodeId, Integer nodeOrder,
                           Double latitude, Double longitude, String direction, String rawPayload) { }
}
