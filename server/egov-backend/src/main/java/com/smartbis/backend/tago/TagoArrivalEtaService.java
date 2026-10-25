package com.smartbis.backend.tago;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.PropertySource;
import com.smartbis.backend.arrival.ArrivalService;
import com.smartbis.backend.arrival.ArrivalRulePolicy;
import com.smartbis.backend.arrival.ArrivalVisitStore;
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
import java.time.Instant;
import java.time.Clock;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads route-and-stop ETA from TAGO's bus-arrival API; no speed-based ETA is invented. */
@Service
@PropertySource("classpath:tago-arrival-read-policy.properties")
public class TagoArrivalEtaService {
    private static final Logger log = LoggerFactory.getLogger(TagoArrivalEtaService.class);
    private static final String PATH = "/1613000/ArvlInfoInqireService/"
            + "getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList";
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final String serviceKey;
    private final String keyMode;
    private final String cityCode;
    private final String baseUrl;
    private final boolean enabled;
    private final boolean mockMode;
    private final HttpClient http;
    private final JdbcTemplate jdbc;
    private final ArrivalService predictions;
    private final Clock clock;
    private ArrivalVisitStore visits;
    @Value("${tago.arrival.half-lower-seconds:60}") private long halfLowerSeconds=60;
    @Value("${tago.arrival.soon-seconds:30}") private long soonSeconds=30;
    @Value("${tago.arrival.half-ratio:0.5}") private double halfRatio=.5;
    @Value("${tago.arrival.half-minimum-seconds:30}") private long halfMinimumSeconds=30;
    @Value("${tago.arrival.passage-grace-seconds:60}") private long passageGraceSeconds=60;
    @Value("${tago.arrival.requery-interval-ms:60000}")
    private long requeryIntervalMs = 60000;
    @Value("${tago.arrival.refresh-threshold-minutes:3}")
    private int refreshThresholdMinutes = 3;
    private final AtomicBoolean batchPolling = new AtomicBoolean();

    private volatile String lastBatchResult = "NOT_RUN";
    private volatile String lastBatchRouteId = "";
    private volatile String lastBatchStopId = "";
    private volatile int lastBatchTargetCount;
    private int nextTargetIndex;

    @Autowired
    public TagoArrivalEtaService(
            JdbcTemplate jdbc,
            ArrivalService predictions,
            @Value("${tago.service-key:}") String serviceKey,
            @Value("${tago.service-key-mode:AUTO}") String keyMode,
            @Value("${tago.city-code:}") String cityCode,
            @Value("${tago.base-url:http://apis.data.go.kr}") String baseUrl,
            @Value("${tago.enabled:false}") boolean enabled,
            @Value("${tago.mock-mode:true}") boolean mockMode,
            @Value("${tago.arrival.enabled:true}") boolean arrivalEnabled,
            @Value("${tago.location.observation-mode:false}") boolean observationMode) {
        this(jdbc,predictions,serviceKey,keyMode,cityCode,baseUrl,enabled,mockMode,arrivalEnabled,observationMode,
                HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),Clock.systemUTC());
    }

    /** Compatibility for existing direct tests; Spring uses the injected collector constructor. */
    public TagoArrivalEtaService(JdbcTemplate jdbc,String serviceKey,String keyMode,String cityCode,String baseUrl,
            boolean enabled,boolean mockMode,boolean arrivalEnabled,boolean observationMode) {
        this(jdbc,new ArrivalService(jdbc),serviceKey,keyMode,cityCode,baseUrl,enabled,mockMode,arrivalEnabled,observationMode,
                HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),Clock.systemUTC());
    }

    TagoArrivalEtaService(JdbcTemplate jdbc,ArrivalService predictions,String serviceKey,String keyMode,String cityCode,
            String baseUrl,boolean enabled,boolean mockMode,boolean arrivalEnabled,boolean observationMode,HttpClient http,Clock clock) {
        this.jdbc = jdbc;
        this.predictions = predictions;
        this.clock = clock;
        this.visits = new ArrivalVisitStore(jdbc);
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.keyMode = keyMode == null ? "AUTO" : keyMode.trim().toUpperCase();
        this.cityCode = cityCode == null ? "" : cityCode.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.enabled = enabled && arrivalEnabled && !observationMode;
        this.mockMode = mockMode;
        this.http = http;
    }

    /** Compatibility DB-only accessor. App API does not inject this TAGO collector. */
    public Integer estimateMinutes(String routeId, String stopId) {
        if (!enabled || mockMode || serviceKey.isBlank() || cityCode.isBlank()
                || routeId == null || routeId.isBlank()
                || stopId == null || stopId.isBlank()) {
            return null;
        }

        ArrivalService.EtaState saved = predictions.savedEta(routeId,stopId);
        if (saved == null || saved.arrivalAt() == null || saved.arrivalAt().isBefore(clock.instant())) return null;
        return (int) Math.ceil(Duration.between(clock.instant(),saved.arrivalAt()).toMillis()/60000.0);
    }

    static boolean shouldRefresh(ArrivalService.EtaState saved,Instant now,long intervalMs,int thresholdMinutes) {
        if (intervalMs<=0 || thresholdMinutes<=0) throw new IllegalArgumentException("Invalid arrival refresh policy");
        if (saved==null) return true;
        var policy=new ArrivalRulePolicy(intervalMs/1000,thresholdMinutes*60L,60,30,.5,30,60);
        Integer eta=saved.arrivalAt()==null?null:(int)Duration.between(saved.queriedAt(),saved.arrivalAt()).getSeconds();
        return !policy.nextQuery(saved.queriedAt(),eta,1).isAfter(now);
    }

    @Scheduled(
            fixedDelayString = "${tago.arrival.poll-interval-ms:10000}",
            initialDelay = 20000
    )
    public void pollNextAssignedArrival() {
        if (!enabled || mockMode || serviceKey.isBlank() || cityCode.isBlank()) {
            lastBatchResult = mockMode ? "MOCK_MODE" : "DISABLED";
            return;
        }
        if (!batchPolling.compareAndSet(false, true)) {
            return;
        }

        try {
            List<ArrivalTarget> targets = loadAssignedArrivalTargets();
            lastBatchTargetCount = targets.size();

            if (targets.isEmpty()) {
                lastBatchResult = "NO_FRESH_ASSIGNED_VEHICLES";
                lastBatchRouteId = "";
                lastBatchStopId = "";
                log.info("TAGO arrival batch skipped: no fresh assigned vehicle positions");
                return;
            }

            int index = Math.floorMod(nextTargetIndex, targets.size());
            ArrivalTarget target = targets.get(index);
            nextTargetIndex = (index + 1) % targets.size();

            lastBatchRouteId = target.routeId();
            lastBatchStopId = target.stopId();

            Instant now = clock.instant();
            ArrivalRulePolicy policy=new ArrivalRulePolicy(requeryIntervalMs/1000,refreshThresholdMinutes*60L,
                halfLowerSeconds,soonSeconds,halfRatio,halfMinimumSeconds,passageGraceSeconds);
            var reservations=new java.util.LinkedHashMap<ArrivalTarget,ArrivalVisitStore.Visit>();
            for (ArrivalTarget assigned:targets) {
                if (!assigned.routeId().equals(target.routeId()) || !assigned.stopId().equals(target.stopId())
                        || !assigned.direction().equals(target.direction())) continue;
                visits.ensure(assigned.vehicleNumber(),assigned.routeId(),assigned.stopId(),assigned.direction(),now);
                var reservation=visits.claim(assigned.vehicleNumber(),assigned.routeId(),assigned.stopId(),assigned.direction(),now);
                if (reservation!=null) reservations.put(assigned,reservation);
            }
            Integer seconds=null;
            if (!reservations.isEmpty()) {
                try { seconds=requestSeconds(target.routeId(),target.stopId()); }
                catch (Exception failure) {
                    for (var entry:reservations.entrySet()) visits.complete(entry.getKey().vehicleNumber(),entry.getValue(),null,policy,failure.getClass().getSimpleName());
                    throw failure;
                }
                for (var entry:reservations.entrySet()) visits.complete(entry.getKey().vehicleNumber(),entry.getValue(),seconds,policy,"");
            }
            // Every assigned vehicle of this route/next stop gets its own geometry snapshot.
            // Other vehicles on the same route never cause duplicate HTTP in this tick.
            for (ArrivalTarget assigned : targets) {
                if (!assigned.routeId().equals(target.routeId()) || !assigned.stopId().equals(target.stopId())
                        || !assigned.direction().equals(target.direction())) continue;
                var saved=visits.read(assigned.vehicleNumber());
                if (saved==null || saved.queriedAt()==null) continue;
                try {
                    predictions.collectPrediction(assigned.vehicleNumber(),target.routeId(),target.stopId(),saved.queriedAt(),saved.arrivalAt());
                } catch (org.springframework.web.server.ResponseStatusException unavailable) {
                    if (unavailable.getStatusCode().value()!=404) throw unavailable;
                    log.info("Arrival geometry unavailable: vehicle={}",assigned.vehicleNumber());
                }
            }

            lastBatchResult = "UP";
            log.info(
                    "TAGO arrival batch completed: targets={} routeId={} stopId={} etaMinutes={}",
                    targets.size(), target.routeId(), target.stopId(), seconds==null?null:(seconds+59)/60
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastBatchResult = "INTERRUPTED";
            log.warn("TAGO arrival batch interrupted");
        } catch (Exception e) {
            lastBatchResult = "FAILED_" + e.getClass().getSimpleName();
            log.warn(
                    "TAGO arrival batch failed: routeId={} stopId={} exceptionType={}",
                    lastBatchRouteId, lastBatchStopId, e.getClass().getSimpleName()
            );
        } finally {
            batchPolling.set(false);
        }
    }

    private List<ArrivalTarget> loadAssignedArrivalTargets() {
        return jdbc.query("""
                SELECT DISTINCT position.route_id, next_stop.node_id, assignment.vehicle_number,position.direction
                FROM android_device_vehicle_assignments assignment
                JOIN bus_vehicles vehicle
                    ON vehicle.vehicle_number = assignment.vehicle_number
                AND vehicle.active = true
                JOIN bus_routes route
                    ON route.route_id = vehicle.route_id
                AND route.city_code = vehicle.city_code
                AND route.active = true
                JOIN LATERAL (
                        SELECT p.route_id, p.direction, p.node_id, p.node_order
                        FROM bus_positions p
                        WHERE p.vehicle_number = assignment.vehicle_number
                        AND p.observed_at >= CURRENT_TIMESTAMP - INTERVAL '2 minutes'
                        AND p.direction IN ('UP', 'DOWN')
                        ORDER BY p.observed_at DESC
                        LIMIT 1
                ) position ON position.route_id = route.route_id
                JOIN bus_route_stops current_stop
                    ON current_stop.route_id = position.route_id
                AND current_stop.direction = position.direction
                AND current_stop.node_id = position.node_id
                AND current_stop.node_order = position.node_order
                JOIN bus_route_stops next_stop
                    ON next_stop.route_id = current_stop.route_id
                AND next_stop.direction = current_stop.direction
                AND next_stop.node_order = current_stop.node_order + 1
                WHERE route.city_code = ?
                ORDER BY position.route_id, next_stop.node_id, assignment.vehicle_number,position.direction
                """,
                (rs, rowNum) -> new ArrivalTarget(rs.getString(1), rs.getString(2),rs.getString(3),rs.getString(4)),
                cityCode
        );
    }

    record ArrivalTarget(String routeId, String stopId, String vehicleNumber,String direction) {
        ArrivalTarget(String routeId,String stopId,String vehicleNumber) { this(routeId,stopId,vehicleNumber,"UP"); }
    }    

    private Integer requestSeconds(String routeId, String stopId) throws Exception {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + PATH)
                .queryParam("serviceKey", normalizedServiceKey())
                .queryParam("cityCode", cityCode)
                .queryParam("routeId", routeId)
                .queryParam("nodeId", stopId)
                .queryParam("numOfRows", 20)
                .queryParam("pageNo", 1)
                .queryParam("_type", "xml")
                .build().encode().toUri();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/xml").GET().build();
        TagoApiRequestThrottle.awaitRequestSlot();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IllegalStateException("TAGO_HTTP_" + response.statusCode());

        return earliestArrivalSeconds(response.body(), routeId);
    }

    static Integer earliestArrivalMinutes(byte[] xml, String routeId) throws Exception {
        Integer seconds=earliestArrivalSeconds(xml,routeId);
        return seconds==null?null:(seconds+59)/60;
    }
    static Integer earliestArrivalSeconds(byte[] xml, String routeId) throws Exception {
        Document document = parseXml(xml);
        Element root = document.getDocumentElement();
        Element header = first(root, "header");
        if (header == null || !"00".equals(text(header, "resultCode"))) {
            throw new IllegalStateException("TAGO_RESULT_" + (header == null ? "MISSING" : safeCode(text(header, "resultCode"))));
        }

        Element body = first(root, "body");
        Element items = body == null ? null : first(body, "items");
        Integer earliestSeconds = null;
        for (Element item : children(items, "item")) {
            if (!routeId.equals(text(item, "routeid"))) continue;
            int seconds = integer(text(item, "arrtime"), -1);
            if (seconds >= 0 && (earliestSeconds == null || seconds < earliestSeconds)) {
                earliestSeconds = seconds;
            }
        }
        return earliestSeconds;
    }

    private String normalizedServiceKey() {
        if ("DECODED".equals(keyMode)) return serviceKey;
        if ("ENCODED".equals(keyMode)
                || ("AUTO".equals(keyMode) && PERCENT_ESCAPE.matcher(serviceKey).find())) {
            return URLDecoder.decode(serviceKey.replace("+", "%2B"), StandardCharsets.UTF_8);
        }
        if (!"AUTO".equals(keyMode)) throw new IllegalArgumentException("Unsupported TAGO service key mode");
        return serviceKey;
    }

    static Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static Element first(Element parent, String name) {
        if (parent == null) return null;
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static java.util.List<Element> children(Element parent, String name) {
        java.util.List<Element> result = new java.util.ArrayList<>();
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
        try { return Integer.parseInt(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String safeCode(String value) {
        return value == null || value.isBlank() ? "MISSING" : value.replaceAll("[^A-Za-z0-9_-]", "");
    }

}
