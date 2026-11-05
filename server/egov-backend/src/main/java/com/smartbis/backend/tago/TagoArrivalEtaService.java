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
    static final int APPROACH_WINDOW_STOPS = 2;

    private final String serviceKey;
    private final String keyMode;
    private final String cityCode;
    private final String baseUrl;
    private final boolean enabled;
    private final boolean mockMode;
    private final boolean observationMode;
    private final HttpClient http;
    private final JdbcTemplate jdbc;
    private final ArrivalService predictions;
    private final Clock clock;
    private ArrivalVisitStore visits;
    @Value("${tago.arrival.soon-seconds:30}") private long soonSeconds=30;
    @Value("${tago.arrival.passage-grace-seconds:60}") private long passageGraceSeconds=60;
    @Value("${tago.arrival.daily-limit:9000}") private int dailyLimit=9000;
    @Value("${tago.arrival.stop-step:2}") private int stopStep=2;
    @Value("${budget.retry_max:2}") private int retryMax=2;
    @Value("${budget.retry_gap_sec:10}") private long retryGapSeconds=10;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CollectionDeadline deadline=CollectionDeadline.unlimited();
    @Value("${collection.capture-metadata:false}") private boolean captureMetadata;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private LocalCollectionControl localControl;
    record AttemptTrace(Instant requested,Integer remaining,int retries) {}
    private final ThreadLocal<AttemptTrace> attemptTrace=new ThreadLocal<>();
    private ArrivalApiBudget budget;
    private PositionSnapshots snapshots=new PositionSnapshots();
    private com.smartbis.backend.arrival.ArrivalTravelEstimator travel;
    @Autowired(required=false)
    void setSnapshots(PositionSnapshots value) { snapshots=value; }
    @Autowired(required=false)
    void setTravel(com.smartbis.backend.arrival.ArrivalTravelEstimator value) { travel=value; }
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
            @Value("${tago.arrival.enabled:false}") boolean arrivalEnabled,
            @Value("${tago.vehicle-location.enabled:false}") boolean locationEnabled,
            @Value("${tago.location.observation-mode:false}") boolean observationMode) {
        this(jdbc,predictions,serviceKey,keyMode,cityCode,baseUrl,enabled&&locationEnabled,mockMode,arrivalEnabled,observationMode,
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
        this.enabled = enabled && arrivalEnabled;
        this.observationMode=observationMode;
        this.mockMode = mockMode;
        this.http = http;
        this.travel=new com.smartbis.backend.arrival.ArrivalTravelEstimator(jdbc);
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
        return saved==null; // Compatibility accessor; ETA-band requery policy is retired.
    }

    @Scheduled(
            fixedDelayString = "${tago.arrival.poll-interval-ms:10000}",
            initialDelay = 20000, scheduler="arrivalScheduler"
    )
    public void pollNextAssignedArrival() {
        if(localControl!=null&&!localControl.callsAllowed()) { lastBatchResult="LOCAL_COLLECTION_SWITCH_OFF";return; }
        if(!deadline.open()) { lastBatchResult="COLLECTION_ENDED";return; }
        if (!enabled || mockMode || serviceKey.isBlank() || cityCode.isBlank()) {
            lastBatchResult = mockMode ? "MOCK_MODE" : "DISABLED";
            return;
        }
        if (!batchPolling.compareAndSet(false, true)) {
            return;
        }

        try {
            visits.setMetadataEnabled(captureMetadata);
            attemptTrace.remove();
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
            ArrivalRulePolicy policy=ArrivalRulePolicy.defaults();
            var reservations=new java.util.LinkedHashMap<ArrivalTarget,ArrivalVisitStore.Visit>();
            for (ArrivalTarget assigned:targets) {
                if (!assigned.routeId().equals(target.routeId()) || !assigned.stopId().equals(target.stopId())
                        ) continue;
                visits.ensure(assigned.vehicleNumber(),assigned.routeId(),assigned.stopId(),assigned.direction(),now);
                var prior=visits.read(assigned.vehicleNumber());
                if(!com.smartbis.backend.arrival.ArrivalVisitPolicy.shouldQuery(assigned.nodeOrder(),
                    assigned.minimumOrder(),assigned.currentOrder(),prior==null?0:prior.attempts(),
                    assigned.currentOrder()==0,isOverdue(assigned,now),stopStep)) continue;
                var reservation=visits.claim(assigned.vehicleNumber(),assigned.routeId(),assigned.stopId(),assigned.direction(),now);
                if (reservation!=null) reservations.put(assigned,reservation);
            }
            Integer seconds=null;
            String failureReason="";
            if (!reservations.isEmpty()) {
                try { seconds=requestSeconds(target.routeId(),target.stopId()); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();throw interrupted;
                }
                catch (Exception failure) {
                    failureReason=TagoLocationExecution.failureReason(failure);
                    log.warn("Arrival attempt failed: failureReason={}",TagoLocationExecution.failureReason(failure));
                }
                Instant completedAt=clock.instant();
                for (var entry:reservations.entrySet()) visits.complete(entry.getKey().vehicleNumber(),entry.getValue(),seconds,policy,failureReason,completedAt);
            }
            // Every assigned vehicle of this route/next stop gets its own geometry snapshot.
            // Other vehicles on the same route never cause duplicate HTTP in this tick.
            for (ArrivalTarget assigned : targets) {
                if (!assigned.routeId().equals(target.routeId()) || !assigned.stopId().equals(target.stopId())
                        ) continue;
                if(!reservations.containsKey(assigned)
                        && !predictions.needsInitialQuery(assigned.vehicleNumber(),assigned.routeId(),assigned.stopId())) continue;
                var saved=visits.read(assigned.vehicleNumber());
                Instant queried=saved==null||saved.queriedAt()==null?now:saved.queriedAt();
                Instant arrival=saved==null?null:saved.arrivalAt();
                if(arrival==null) arrival=queried.plusSeconds(travel.estimateSeconds(assigned.routeId(),assigned.currentOrder(),assigned.nodeOrder(),now));
                if(!observationMode) continue;
                try {
                    var trace=attemptTrace.get();
                    var reservation=reservations.get(assigned);
                    if(localControl!=null) {
                        if(!captureMetadata)throw new IllegalStateException("LOCAL_RUN_METADATA_REQUIRED");
                        // A cached visit is not a new final response in this run.
                        if(reservation==null||trace==null)continue;
                    }
                    if(captureMetadata && reservation!=null && trace!=null) {
                        if(reservation.id()==null) throw new IllegalStateException("ARRIVAL_VISIT_ID_REQUIRED");
                        predictions.collectPrediction(assigned.vehicleNumber(),target.routeId(),target.stopId(),queried,arrival,
                            new ArrivalService.Observation(trace.requested(),trace.remaining(),reservation.id(),trace.retries(),true,false,failureReason,
                                localControl==null?null:localControl.runId()));
                    } else predictions.collectPrediction(assigned.vehicleNumber(),target.routeId(),target.stopId(),queried,arrival);
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
        var byVehicle=new java.util.LinkedHashMap<String,List<ArrivalTarget>>();
        for(var candidate:loadArrivalCandidates()) {
            byVehicle.computeIfAbsent(candidate.vehicleNumber(),key->new ArrayList<>()).add(candidate);
        }
        var result=new ArrayList<ArrivalTarget>();
        Instant now=clock.instant();
        for(var candidates:byVehicle.values()) {
            var first=candidates.get(0);
            String active=visits.activeStop(first.vehicleNumber(),first.routeId(),first.direction());
            var selected=selectTarget(candidates,active,first.currentOrder()==0,c->isOverdue(c,now),stopStep);
            if(selected!=null) result.add(selected);
        }
        return result;
    }

    private boolean isOverdue(ArrivalTarget target,Instant now) {
        var savedEta=predictions.savedEta(target.routeId(),target.stopId());
        return savedEta!=null && savedEta.arrivalAt()!=null && !now.isBefore(savedEta.arrivalAt());
    }

    static ArrivalTarget selectTarget(List<ArrivalTarget> candidates,String activeStop,boolean firstTripVisit,
            java.util.function.Predicate<ArrivalTarget> overdue,int stopStep) {
        var eligible=candidates.stream().filter(TagoArrivalEtaService::withinApproachWindow)
            .filter(c->com.smartbis.backend.arrival.ArrivalVisitPolicy.shouldQuery(c.nodeOrder(),c.minimumOrder(),
                c.currentOrder(),0,firstTripVisit,overdue.test(c),stopStep))
            .sorted(java.util.Comparator.comparingInt(ArrivalTarget::nodeOrder)).toList();
        // Qualification deliberately ignores attempts: a completed active visit must not rotate away.
        return eligible.stream().filter(c->c.stopId().equals(activeStop)).findFirst()
            .orElse(eligible.isEmpty()?null:eligible.get(0));
    }

    static boolean withinApproachWindow(ArrivalTarget candidate) {
        long remaining=(long)candidate.nodeOrder()-candidate.currentOrder();
        return remaining>=0 && remaining<=APPROACH_WINDOW_STOPS;
    }

    private List<ArrivalTarget> loadArrivalCandidates() {
        // rev6 10.3: continuity is an analysis criterion, not a collection gate.
        // Record the defect without renumbering rows or excluding the route.
        for(var issue:jdbc.queryForList("""
            SELECT route_id,count(*) stop_count,count(DISTINCT node_order) distinct_count,
                   MIN(node_order) first_order,MAX(node_order) last_order
            FROM bus_route_stops GROUP BY route_id
            HAVING count(*)<>count(DISTINCT node_order)
                OR MAX(node_order)::bigint-MIN(node_order)+1<>count(DISTINCT node_order)
            """)) {
            log.warn("CATALOG_ORDER_CONTINUITY: route={} count={} distinct={} first={} last={}",
                issue.get("route_id"),issue.get("stop_count"),issue.get("distinct_count"),
                issue.get("first_order"),issue.get("last_order"));
        }
        if(!snapshots.all().isEmpty()) {
            var result=new ArrayList<ArrivalTarget>();
            for(var position:snapshots.all()) {
                if(position.order()==null) continue;
                result.addAll(jdbc.query("""
                    WITH bounds AS (
                      SELECT MIN(node_order) minimum_order FROM bus_route_stops WHERE route_id=?
                    )
                    SELECT s.node_id,s.node_order,b.minimum_order FROM bus_route_stops s CROSS JOIN bounds b
                    WHERE s.route_id=? AND s.node_order>=? AND s.node_order::bigint<=?::bigint+?
                    ORDER BY s.node_order LIMIT ?
                    """,(rs,n)->new ArrivalTarget(position.route(),rs.getString(1),position.vehicle(),"GLOBAL",
                        rs.getInt(2),position.order(),rs.getInt(3)),position.route(),position.route(),position.order(),
                        position.order(),APPROACH_WINDOW_STOPS,APPROACH_WINDOW_STOPS+1));
            }
            return result;
        }
        return jdbc.query("""
                WITH position AS (
                  SELECT DISTINCT ON (p.vehicle_number) p.*
                  FROM bus_positions p
                  WHERE p.source IN ('TAGO','TAGO_OBSERVATION') AND p.node_order IS NOT NULL
                    AND COALESCE(p.raw_payload->>'interpolated','false') NOT IN ('true','1')
                  ORDER BY p.vehicle_number,p.observed_at DESC,p.position_id DESC
                ), bounds AS (
                  SELECT route_id,MIN(node_order) minimum_order FROM bus_route_stops GROUP BY route_id
                )
                SELECT DISTINCT position.route_id,next_stop.node_id,position.vehicle_number,
                       'GLOBAL',next_stop.node_order,position.node_order,b.minimum_order
                FROM position JOIN bus_routes route ON route.route_id=position.route_id AND route.active=true
                JOIN bounds b ON b.route_id=position.route_id
                JOIN LATERAL (
                  SELECT s.node_id,s.node_order FROM bus_route_stops s
                  WHERE s.route_id=position.route_id AND s.node_order>=position.node_order
                    AND s.node_order::bigint<=position.node_order::bigint+?
                  ORDER BY s.node_order LIMIT ?
                ) next_stop ON true
                WHERE route.city_code = ?
                ORDER BY position.route_id,position.vehicle_number,next_stop.node_order
                """,
                (rs, rowNum) -> new ArrivalTarget(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getInt(6),rs.getInt(7)),
                APPROACH_WINDOW_STOPS,APPROACH_WINDOW_STOPS+1,cityCode
        );
    }

    record ArrivalTarget(String routeId, String stopId, String vehicleNumber,String direction,int nodeOrder,int currentOrder,int minimumOrder) {
        ArrivalTarget(String routeId,String stopId,String vehicleNumber) { this(routeId,stopId,vehicleNumber,"GLOBAL",2,1,2); }
        ArrivalTarget(String routeId,String stopId,String vehicleNumber,String direction) { this(routeId,stopId,vehicleNumber,direction,2,1,2); }
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
        if(budget==null) {
            budget=new ArrivalApiBudget(enabled&&!mockMode,dailyLimit,new JdbcGuardUsageStore(jdbc),clock,
                retryMax,Math.multiplyExact(retryGapSeconds,1000),Thread::sleep);
            budget.setDeadline(deadline);
        }
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        return budget.execute(()->{
            if(localControl!=null)localControl.requireEnabled();
            Instant requested=clock.instant();
            int retries=attempts.getAndIncrement();
            attemptTrace.set(new AttemptTrace(requested,null,retries));
            HttpResponse<byte[]> response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200) throw new IllegalStateException("TAGO_HTTP_"+response.statusCode());
            var parsed=parseArrival(response.body(),routeId,null);
            attemptTrace.set(new AttemptTrace(requested,parsed.remaining(),retries));
            return parsed.seconds();
        });
    }

    static Integer earliestArrivalMinutes(byte[] xml, String routeId) throws Exception {
        Integer seconds=earliestArrivalSeconds(xml,routeId);
        return seconds==null?null:(seconds+59)/60;
    }
    static Integer earliestArrivalSeconds(byte[] xml, String routeId) throws Exception {
        return earliestArrivalSeconds(xml,routeId,null);
    }
    static Integer earliestArrivalSeconds(byte[] xml,String routeId,Integer remainingStops) throws Exception {
        return parseArrival(xml,routeId,remainingStops).seconds();
    }
    record ParsedArrival(Integer seconds,Integer remaining) {}
    static ParsedArrival parseArrival(byte[] xml,String routeId,Integer remainingStops) throws Exception {
        Document document = parseXml(xml);
        Element root = document.getDocumentElement();
        Element header = first(root, "header");
        if (header == null || !"00".equals(text(header, "resultCode"))) {
            throw new IllegalStateException("TAGO_RESULT_" + (header == null ? "MISSING" : safeCode(text(header, "resultCode"))));
        }

        Element body = first(root, "body");
        Element items = body == null ? null : first(body, "items");
        Integer earliestSeconds = null;
        Integer selectedRemaining=null;
        for (Element item : children(items, "item")) {
            if (!RouteIdNormalizer.normalize(routeId).equals(RouteIdNormalizer.normalize(text(item, "routeid")))) continue;
            String remaining=text(item,"arrprevstationcnt");
            if(remainingStops!=null && !remaining.isBlank() && integer(remaining,-1)!=remainingStops) continue;
            int seconds = integer(text(item, "arrtime"), -1);
            if (seconds >= 0 && (earliestSeconds == null || seconds < earliestSeconds)) {
                earliestSeconds = seconds;
                int parsed=integer(remaining,-1);selectedRemaining=parsed<0?null:parsed;
            }
        }
        return new ParsedArrival(earliestSeconds,selectedRemaining);
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
