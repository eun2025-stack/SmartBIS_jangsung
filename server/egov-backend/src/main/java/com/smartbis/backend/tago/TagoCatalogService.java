package com.smartbis.backend.tago;

import com.smartbis.backend.admin.BoundaryRegionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.function.IntFunction;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/** Administrator-triggered, validate-then-persist import of the TAGO Jangseong route catalog. */
@Service
public class TagoCatalogService {
    private static final Logger log = LoggerFactory.getLogger(TagoCatalogService.class);
    /** Operator-reviewed directions for the 2026-10 Jangseong UNKNOWN batch.
     * These label-only overrides preserve TAGO stop rows and stop order. */
    private static final Map<String, String> REVIEWED_ROUTE_DIRECTIONS = Map.ofEntries(
            Map.entry("TSB342000029", "UP"), Map.entry("TSB342000030", "UP"),
            Map.entry("TSB342000031", "UP"), Map.entry("TSB342000032", "DOWN"),
            Map.entry("TSB342000033", "UP"), Map.entry("TSB342000034", "UP"),
            Map.entry("TSB342000035", "DOWN"), Map.entry("TSB342000036", "DOWN"),
            Map.entry("TSB342000037", "UP"), Map.entry("TSB342000038", "DOWN"),
            Map.entry("TSB342000039", "UP"), Map.entry("TSB342000040", "DOWN"),
            Map.entry("TSB342000041", "UP"), Map.entry("TSB342000046", "UP"),
            Map.entry("TSB342000047", "DOWN"), Map.entry("TSB342000048", "UP"),
            Map.entry("TSB342000049", "DOWN"), Map.entry("TSB342000087", "DOWN"),
            Map.entry("TSB342000102", "UP"), Map.entry("TSB342000103", "DOWN"),
            Map.entry("TSB342000104", "UP"), Map.entry("TSB342000105", "UP"),
            Map.entry("TSB342000106", "DOWN"), Map.entry("TSB342000107", "UP"),
            Map.entry("TSB342000108", "UP"), Map.entry("TSB342000109", "DOWN"),
            Map.entry("TSB342000110", "UP"), Map.entry("TSB342000111", "DOWN"),
            Map.entry("TSB342000112", "DOWN"), Map.entry("TSB342000134", "DOWN"),
            Map.entry("TSB342000155", "DOWN"));
    private static final int PAGE_SIZE = 500;
    private static final long MIN_REQUEST_GAP_MILLIS = 150;
    private static final int VALIDATION_RETRY_COUNT = 2;
    private static final long VALIDATION_RETRY_DELAY_MILLIS = 1_000;
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final String ROUTE_LIST_PATH = "/1613000/BusRouteInfoInqireService/getRouteNoList";
    private static final String ROUTE_STOPS_PATH = "/1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList";
    private static final String ROUTE_INFO_PATH = "/1613000/BusRouteInfoInqireService/getRouteInfoIem";
    private static final String CITY_LIST_PATH = "/1613000/BusRouteInfoInqireService/getCtyCodeList";
    private static final String STOP_CITY_LIST_PATH = "/1613000/BusSttnInfoInqireService/getCtyCodeList";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final BoundaryRegionService boundaryRegionService;
    private final HttpClient http;
    private final String serviceKey;
    private final String keyMode;
    private final String cityCode;
    private final String baseUrl;
    private final int retryCount;
    private final long retryDelayMillis;
    private volatile long lastRequestAt;

    public TagoCatalogService(
            JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            BoundaryRegionService boundaryRegionService,
            @Value("${tago.service-key:}") String serviceKey,
            @Value("${tago.service-key-mode:AUTO}") String keyMode,
            @Value("${tago.city-code:}") String cityCode,
            @Value("${tago.base-url:http://apis.data.go.kr}") String baseUrl,
            @Value("${tago.retry-count:3}") int retryCount,
            @Value("${tago.retry-delay-seconds:10}") long retryDelaySeconds) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.boundaryRegionService = boundaryRegionService;
        this.serviceKey = serviceKey;
        this.keyMode = keyMode;
        this.cityCode = cityCode;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.retryCount = Math.max(1, retryCount);
        this.retryDelayMillis = Math.max(0, retryDelaySeconds) * 1000L;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public synchronized Map<String, Object> synchronizeCatalog() {
        return synchronizeCatalog("MANUAL");
    }

    public synchronized Map<String, Object> synchronizeCatalog(String triggerSource) {
        if (serviceKey == null || serviceKey.isBlank() || cityCode == null || cityCode.isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "TAGO 서비스 키 또는 도시코드가 설정되지 않았습니다.");
        }

        long syncRunId = jdbc.queryForObject("""
                INSERT INTO tago_catalog_sync_runs(city_code, trigger_source, status)
                VALUES (?, ?, 'RUNNING') RETURNING sync_run_id
                """, Long.class, cityCode, "AUTOMATIC".equals(triggerSource) ? "AUTOMATIC" : "MANUAL");
        try {
            List<Route> routes = fetchPages(ROUTE_LIST_PATH, Map.of("cityCode", cityCode),
                    (item, sequence) -> routeFrom(item));
            if (routes.isEmpty()) {
                throw new IllegalStateException("TAGO가 노선 목록을 반환하지 않았습니다.");
            }

            List<RouteStop> stops = new ArrayList<>();
            for (Route route : routes) {
                stops.addAll(fetchPages(ROUTE_STOPS_PATH,
                        Map.of("cityCode", cityCode, "routeId", route.id()),
                        (item, sequence) -> stopFrom(item, route.id(), sequence)));
            }
            if (stops.isEmpty()) {
                throw new IllegalStateException("TAGO가 경유 정류소 목록을 반환하지 않았습니다.");
            }
            List<RouteStop> normalizedStops = normalizeStopOrders(stops);
            DirectionInference directionInference = inferUnknownDirections(routes, normalizedStops);
            List<RouteStop> inferredStops = directionInference.stops();
            String snapshotHash = snapshotHash(routes, inferredStops);
            String previousSnapshotHash = jdbc.query("""
                    SELECT snapshot_sha256 FROM tago_catalog_sync_runs
                     WHERE city_code = ? AND status IN ('SUCCESS', 'SUCCESS_WITH_WARNINGS')
                       AND snapshot_sha256 IS NOT NULL
                     ORDER BY completed_at DESC LIMIT 1
                    """, rs -> rs.next() ? rs.getString(1) : null, cityCode);
            Boolean catalogChanged = previousSnapshotHash == null ? null : !previousSnapshotHash.equals(snapshotHash);

            int staleRouteCount;
            try {
                staleRouteCount = transactionTemplate.execute(status -> persistCatalog(routes, inferredStops));
            } catch (org.springframework.dao.DuplicateKeyException e) {
                String constraint = postgresConstraintName(e);
                throw new CatalogValidationException("DB_UNIQUE_" + constraint);
            }
            Set<String> uniqueStops = new HashSet<>();
            inferredStops.forEach(stop -> uniqueStops.add(stop.id()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("cityCode", cityCode);
            result.put("routeCount", routes.size());
            result.put("routeStopCount", inferredStops.size());
            result.put("uniqueStopCount", uniqueStops.size());
            result.put("inferredDirectionCount", directionInference.inferredStopCount());
            result.put("unresolvedDirectionCount", directionInference.unresolvedStopCount());
            result.put("directionInferenceByRule", directionInference.inferredByRule());
            result.put("staleRouteCount", staleRouteCount);
            result.put("catalogChanged", catalogChanged);
            if (directionInference.inferredStopCount() > 0) {
                log.info("TAGO direction inference completed: inferredStops={} unresolvedStops={} rules={}",
                        directionInference.inferredStopCount(), directionInference.unresolvedStopCount(),
                        directionInference.inferredByRule());
            }
            long inferredOrderCount = inferredStops.stream().filter(RouteStop::orderInferred).count();
            result.put("inferredOrderCount", inferredOrderCount);
            if (inferredOrderCount > 0) {
                log.warn("TAGO catalog used response-order fallback for invalid nodeord values: count={}", inferredOrderCount);
            }
            result.put("syncedAt", OffsetDateTime.now());
            String syncStatus = directionInference.unresolvedStopCount() == 0 && inferredOrderCount == 0
                    ? "SUCCESS" : "SUCCESS_WITH_WARNINGS";
            result.put("status", syncStatus);
            jdbc.update("""
                    UPDATE tago_catalog_sync_runs
                       SET status = ?, completed_at = CURRENT_TIMESTAMP,
                           route_count = ?, route_stop_count = ?, unique_stop_count = ?,
                           inferred_direction_count = ?, unresolved_direction_count = ?,
                           inferred_order_count = ?, stale_route_count = ?,
                           catalog_changed = ?, snapshot_sha256 = ?
                     WHERE sync_run_id = ?
                    """, syncStatus, routes.size(), inferredStops.size(), uniqueStops.size(),
                    directionInference.inferredStopCount(), directionInference.unresolvedStopCount(),
                    inferredOrderCount, staleRouteCount, catalogChanged, snapshotHash, syncRunId);
            try {
                Map<String, Object> classification = boundaryRegionService.classifyUnassigned();
                classification.put("status", "SUCCESS");
                result.put("regionClassification", classification);
            } catch (Exception classificationFailure) {
                log.warn("TAGO catalog saved, automatic stop-region classification failed; exceptionType={}",
                        classificationFailure.getClass().getSimpleName());
                result.put("regionClassification", Map.of(
                        "status", "FAILED",
                        "exceptionType", classificationFailure.getClass().getSimpleName()));
            }
            return result;
        } catch (ResponseStatusException e) {
            jdbc.update("""
                    UPDATE tago_catalog_sync_runs
                       SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP, failure_code = ?
                     WHERE sync_run_id = ?
                    """, "HTTP_" + e.getStatusCode().value(), syncRunId);
            throw e;
        } catch (Exception e) {
            // Log only the source location, never exception messages, URLs, parameters, or credentials.
            StackTraceElement origin = e.getStackTrace().length == 0 ? null : e.getStackTrace()[0];
            String failureCode = e instanceof CatalogValidationException validation
                    ? validation.failureCode() : "UNCLASSIFIED";
            jdbc.update("""
                    UPDATE tago_catalog_sync_runs
                       SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP, failure_code = ?
                     WHERE sync_run_id = ?
                    """, failureCode, syncRunId);
            log.error("TAGO catalog synchronization failed; exceptionType={} failureCode={} origin={}.{}:{}",
                    e.getClass().getSimpleName(),
                    failureCode,
                    origin == null ? "unknown" : origin.getClassName(),
                    origin == null ? "unknown" : origin.getMethodName(),
                    origin == null ? -1 : origin.getLineNumber());
            throw new ResponseStatusException(BAD_GATEWAY,
                    "TAGO 카탈로그 조회 또는 검증에 실패했습니다. 키 승인·도시코드·응답 상태를 확인하세요.");
        }
    }

    /** Read-only cross-check across city, route list, per-route detail, and ordered stop APIs. */
    public synchronized Map<String, Object> validateCatalog() {
        if (serviceKey == null || serviceKey.isBlank() || cityCode == null || cityCode.isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "TAGO 서비스 키 또는 도시코드가 설정되지 않았습니다.");
        }
        try {
            List<String> routeCityCodes = fetchPages(CITY_LIST_PATH, Map.of(),
                    (item, sequence) -> childText(item, "citycode"),
                    VALIDATION_RETRY_COUNT, VALIDATION_RETRY_DELAY_MILLIS);
            List<String> stopCityCodes = fetchPages(STOP_CITY_LIST_PATH, Map.of(),
                    (item, sequence) -> childText(item, "citycode"),
                    VALIDATION_RETRY_COUNT, VALIDATION_RETRY_DELAY_MILLIS);
            boolean routeCityFound = routeCityCodes.contains(cityCode);
            boolean stopCityFound = stopCityCodes.contains(cityCode);
            if (!routeCityFound || !stopCityFound) {
                throw new CatalogValidationException(!routeCityFound
                        ? "ROUTE_CITY_CODE_NOT_RETURNED" : "STOP_CITY_CODE_NOT_RETURNED");
            }

            List<Route> routes = fetchPages(ROUTE_LIST_PATH, Map.of("cityCode", cityCode),
                    (item, sequence) -> routeFrom(item),
                    VALIDATION_RETRY_COUNT, VALIDATION_RETRY_DELAY_MILLIS);
            if (routes.isEmpty()) throw new CatalogValidationException("ROUTE_LIST_EMPTY");

            List<RouteStop> stops = new ArrayList<>();
            int routeDetailIdMismatchCount = 0;
            int routeDetailNameMismatchCount = 0;
            int routeDetailMissingCount = 0;
            int routeDetailMatchedCount = 0;
            int routeDetailQueryFailureCount = 0;
            int routeStopQueryFailureCount = 0;
            int routeEndpointNameMismatchCount = 0;
            List<Map<String, Object>> routeDetailIssues = new ArrayList<>();
            for (Route route : routes) {
                List<RouteDetail> details = List.of();
                boolean detailQuerySucceeded = false;
                try {
                    details = fetchPages(ROUTE_INFO_PATH,
                            Map.of("cityCode", cityCode, "routeId", route.id()),
                            (item, sequence) -> routeDetailFrom(item),
                            VALIDATION_RETRY_COUNT, VALIDATION_RETRY_DELAY_MILLIS);
                    detailQuerySucceeded = true;
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    routeDetailQueryFailureCount++;
                    addRouteIssue(routeDetailIssues, route.id(), "DETAIL_QUERY_FAILED_" + safeFailureCode(e));
                    log.warn("TAGO cross-validation request failed: stage=ROUTE_DETAIL routeId={} failureCode={}",
                            route.id(), safeFailureCode(e));
                }
                if (details.isEmpty()) {
                    if (detailQuerySucceeded) {
                        routeDetailMissingCount++;
                        addRouteIssue(routeDetailIssues, route.id(), "DETAIL_MISSING");
                    }
                } else {
                    RouteDetail detail = details.get(0);
                    if (!route.id().equals(detail.id())) {
                        routeDetailIdMismatchCount++;
                        addRouteIssue(routeDetailIssues, route.id(), "DETAIL_ROUTE_ID_MISMATCH");
                    } else {
                        routeDetailMatchedCount++;
                    }
                    if (!canonicalText(route.name()).equals(canonicalText(detail.name()))) {
                        routeDetailNameMismatchCount++;
                        addRouteIssue(routeDetailIssues, route.id(), "ROUTE_NAME_MISMATCH");
                    }
                }
                List<RouteStop> routeStops = List.of();
                boolean stopQuerySucceeded = false;
                try {
                    routeStops = fetchPages(ROUTE_STOPS_PATH,
                            Map.of("cityCode", cityCode, "routeId", route.id()),
                            (item, sequence) -> stopFrom(item, route.id(), sequence),
                            VALIDATION_RETRY_COUNT, VALIDATION_RETRY_DELAY_MILLIS);
                    stopQuerySucceeded = true;
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    routeStopQueryFailureCount++;
                    addRouteIssue(routeDetailIssues, route.id(), "STOP_LIST_QUERY_FAILED_" + safeFailureCode(e));
                    log.warn("TAGO cross-validation request failed: stage=ROUTE_STOPS routeId={} failureCode={}",
                            route.id(), safeFailureCode(e));
                }
                stops.addAll(routeStops);
                if (detailQuerySucceeded && stopQuerySucceeded && !details.isEmpty()) {
                    Set<String> stopNames = new HashSet<>();
                    routeStops.forEach(stop -> stopNames.add(stop.name()));
                    RouteDetail detail = details.get(0);
                    boolean endpointsMatch = (detail.startStopName().isBlank()
                            || stopNames.stream().anyMatch(name -> canonicalText(name).equals(canonicalText(detail.startStopName()))))
                            && (detail.endStopName().isBlank()
                            || stopNames.stream().anyMatch(name -> canonicalText(name).equals(canonicalText(detail.endStopName()))));
                    if (!endpointsMatch) {
                        routeEndpointNameMismatchCount++;
                        addRouteIssue(routeDetailIssues, route.id(), "ENDPOINT_STOP_NAME_NOT_IN_ROUTE_STOPS");
                    }
                }
            }

            List<Map<String, Object>> duplicateGroups = duplicateStopKeys(stops);
            List<Map<String, Object>> duplicateOrderGroups = duplicateStopOrders(stops);
            long repeatedVisits = duplicateGroups.stream().filter(group -> {
                @SuppressWarnings("unchecked")
                List<Integer> orders = (List<Integer>) group.get("nodeOrders");
                return orders.stream().distinct().count() > 1;
            }).count();
            long sameOrderDuplicates = duplicateGroups.size() - repeatedVisits;
            long duplicateExtraRows = duplicateGroups.stream()
                    .mapToLong(group -> ((Number) group.get("occurrences")).longValue() - 1).sum();
            Map<String, Long> directionCodeCounts = new LinkedHashMap<>();
            for (RouteStop stop : stops) {
                String rawCode = stop.directionCode().isBlank() ? "(blank)"
                        : stop.directionCode().matches("[A-Za-z0-9_-]{1,20}") ? stop.directionCode() : "(other)";
                directionCodeCounts.merge(rawCode, 1L, Long::sum);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("cityCode", cityCode);
            result.put("routeCityCodeVerified", routeCityFound);
            result.put("stopCityCodeVerified", stopCityFound);
            result.put("routeCount", routes.size());
            result.put("routeDetailMatchedCount", routeDetailMatchedCount);
            result.put("routeDetailIdMismatchCount", routeDetailIdMismatchCount);
            result.put("routeDetailNameMismatchCount", routeDetailNameMismatchCount);
            result.put("routeDetailMissingCount", routeDetailMissingCount);
            result.put("routeDetailQueryFailureCount", routeDetailQueryFailureCount);
            result.put("routeStopQueryFailureCount", routeStopQueryFailureCount);
            result.put("routeEndpointNameMismatchCount", routeEndpointNameMismatchCount);
            result.put("routeStopCount", stops.size());
            result.put("uniqueStopCount", stops.stream().map(RouteStop::id).distinct().count());
            result.put("unknownDirectionCount", stops.stream().filter(stop -> "UNKNOWN".equals(stop.direction())).count());
            result.put("directionCodeCounts", directionCodeCounts);
            result.put("duplicateRouteStopKeyGroupCount", duplicateGroups.size());
            result.put("duplicateRouteStopExtraRowCount", duplicateExtraRows);
            result.put("repeatedVisitCandidateGroupCount", repeatedVisits);
            result.put("sameOrderDuplicateGroupCount", sameOrderDuplicates);
            result.put("duplicateRouteStopSamples", duplicateGroups.stream().limit(20).toList());
            result.put("duplicateRouteDirectionOrderGroupCount", duplicateOrderGroups.size());
            result.put("duplicateRouteDirectionOrderSamples", duplicateOrderGroups.stream().limit(20).toList());
            result.put("routeDetailIssueSamples", routeDetailIssues);
            result.put("databaseWritten", false);
            result.put("status", routeDetailQueryFailureCount + routeStopQueryFailureCount == 0
                    ? "VALIDATED" : "PARTIAL");
            return result;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            StackTraceElement origin = e.getStackTrace().length == 0 ? null : e.getStackTrace()[0];
            String failureCode = e instanceof CatalogValidationException validation
                    ? validation.failureCode() : "UNCLASSIFIED";
            log.error("TAGO read-only cross-validation failed; exceptionType={} failureCode={} origin={}.{}:{}",
                    e.getClass().getSimpleName(), failureCode,
                    origin == null ? "unknown" : origin.getClassName(),
                    origin == null ? "unknown" : origin.getMethodName(),
                    origin == null ? -1 : origin.getLineNumber());
            throw new ResponseStatusException(BAD_GATEWAY,
                    "TAGO 읽기 전용 교차 검증에 실패했습니다. 백엔드 로그를 확인하세요.");
        }
    }

    private static void addRouteIssue(List<Map<String, Object>> issues, String routeId, String code) {
        if (issues.size() >= 20) return;
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("routeId", routeId);
        issue.put("code", code);
        issues.add(issue);
    }

    private static String safeFailureCode(Exception error) {
        if (error instanceof CatalogValidationException validation) return validation.failureCode();
        if (error instanceof RetryableTagoException retryable) return retryable.reason();
        return error.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "");
    }

    static List<Map<String, Object>> duplicateStopKeys(List<RouteStop> stops) {
        Map<StopKey, List<Integer>> groups = new LinkedHashMap<>();
        for (RouteStop stop : stops) {
            StopKey key = new StopKey(stop.routeId(), stop.id(), stop.direction());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(stop.order());
        }
        List<Map<String, Object>> duplicates = new ArrayList<>();
        for (Map.Entry<StopKey, List<Integer>> entry : groups.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("routeId", entry.getKey().routeId());
            item.put("nodeId", entry.getKey().nodeId());
            item.put("direction", entry.getKey().direction());
            item.put("occurrences", entry.getValue().size());
            item.put("nodeOrders", entry.getValue());
            duplicates.add(item);
        }
        return duplicates;
    }

    static List<Map<String, Object>> duplicateStopOrders(List<RouteStop> stops) {
        Map<StopOrderKey, List<String>> groups = new LinkedHashMap<>();
        for (RouteStop stop : stops) {
            StopOrderKey key = new StopOrderKey(stop.routeId(), stop.direction(), stop.order());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(stop.id());
        }
        List<Map<String, Object>> duplicates = new ArrayList<>();
        for (Map.Entry<StopOrderKey, List<String>> entry : groups.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("routeId", entry.getKey().routeId());
            item.put("direction", entry.getKey().direction());
            item.put("nodeOrder", entry.getKey().order());
            item.put("nodeIds", entry.getValue());
            duplicates.add(item);
        }
        return duplicates;
    }

    private static String canonicalText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    public Map<String, Object> catalogStatus() {
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT (SELECT COUNT(*) FROM bus_routes WHERE city_code = ? AND active = true) AS route_count,
                       (SELECT COUNT(*) FROM bus_stops WHERE city_code = ? AND active = true) AS stop_count,
                       (SELECT COUNT(*) FROM bus_route_stops s JOIN bus_routes r USING (route_id)
                         WHERE r.city_code = ? AND r.active = true) AS route_stop_count,
                       (SELECT COUNT(*) FROM bus_route_stops s JOIN bus_routes r USING (route_id)
                         WHERE r.city_code = ? AND r.active = true AND s.direction = 'UNKNOWN') AS unknown_direction_count,
                       (SELECT COUNT(*) FROM bus_stops WHERE city_code = ?
                         AND (region_id IS NOT NULL OR region_name IS NOT NULL)) AS region_assigned_count,
                       (SELECT COUNT(*) FROM bus_stops WHERE city_code = ?
                         AND region_id IS NULL AND region_name IS NULL) AS region_unassigned_count,
                       (SELECT MAX(last_synced_at) FROM bus_routes WHERE city_code = ?) AS last_synced_at
                """, cityCode, cityCode, cityCode, cityCode, cityCode, cityCode, cityCode);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cityCode", cityCode);
        result.put("routeCount", counts.get("route_count"));
        result.put("stopCount", counts.get("stop_count"));
        result.put("routeStopCount", counts.get("route_stop_count"));
        result.put("unknownDirectionCount", counts.get("unknown_direction_count"));
        result.put("regionAssignedStopCount", counts.get("region_assigned_count"));
        result.put("regionUnassignedStopCount", counts.get("region_unassigned_count"));
        result.put("lastSyncedAt", counts.get("last_synced_at"));
        result.put("status", ((Number) counts.get("route_count")).longValue() > 0 ? "SYNCED" : "NOT_SYNCED");
        List<Map<String, Object>> recentRuns = jdbc.queryForList("""
                SELECT trigger_source AS "triggerSource", status,
                       started_at AS "startedAt", completed_at AS "completedAt",
                       route_count AS "routeCount", route_stop_count AS "routeStopCount",
                       unique_stop_count AS "uniqueStopCount",
                       inferred_direction_count AS "inferredDirectionCount",
                       unresolved_direction_count AS "unresolvedDirectionCount",
                       inferred_order_count AS "inferredOrderCount",
                       stale_route_count AS "staleRouteCount", catalog_changed AS "catalogChanged",
                       failure_code AS "failureCode"
                  FROM tago_catalog_sync_runs
                 WHERE city_code = ?
                 ORDER BY started_at DESC
                 LIMIT 10
                """, cityCode);
        result.put("recentSyncRuns", recentRuns);
        if (!recentRuns.isEmpty()) result.put("latestSyncRun", recentRuns.get(0));
        return result;
    }

    private <T> List<T> fetchPages(String path, Map<String, String> parameters, ItemMapper<T> mapper) throws Exception {
        return fetchPages(path, parameters, mapper, retryCount, retryDelayMillis);
    }

    private <T> List<T> fetchPages(String path, Map<String, String> parameters, ItemMapper<T> mapper,
                                   int maxAttempts, long retryDelay) throws Exception {
        List<T> result = new ArrayList<>();
        int page = 1;
        int total = -1;
        do {
            Map<String, String> query = new LinkedHashMap<>(parameters);
            query.put("numOfRows", Integer.toString(PAGE_SIZE));
            query.put("pageNo", Integer.toString(page));
            query.put("_type", "xml");
            Document document = request(path, query, maxAttempts, retryDelay);
            Element body = first(document.getDocumentElement(), "body");
            if (body == null) throw new IllegalStateException("TAGO response has no body");
            String count = childText(body, "totalCount");
            total = count.isBlank() ? 0 : Integer.parseInt(count);
            Element items = first(body, "items");
            List<Element> pageItems = children(items, "item");
            int pageOffset = result.size();
            for (int index = 0; index < pageItems.size(); index++) {
                result.add(mapper.map(pageItems.get(index), pageOffset + index + 1));
            }
            if (pageItems.isEmpty() && result.size() < total) {
                throw new IllegalStateException("TAGO page contained no items before totalCount");
            }
            page++;
        } while (result.size() < total);
        return result;
    }

    private Document request(String path, Map<String, String> query,
                             int maxAttempts, long retryDelay) throws Exception {
        return retry(maxAttempts, attempt -> {
            try {
                return requestOnce(path, query, attempt);
            } catch (RetryableTagoException | IOException e) {
                throw new RetryOperationException(e);
            } catch (Exception e) {
                throw new OperationException(e);
            }
        }, attempt -> {
            log.warn("Retrying TAGO request: path={} attempt={}/{}", path, attempt, maxAttempts);
            if (retryDelay > 0) Thread.sleep(retryDelay);
        });
    }

    static <T> T retry(int maxAttempts, IntFunction<T> operation, RetryWait wait) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.apply(attempt);
            } catch (RetryOperationException e) {
                if (attempt >= maxAttempts) throw e.getCause();
                wait.beforeRetry(attempt);
            } catch (OperationException e) {
                throw e.getCause();
            }
        }
    }

    @FunctionalInterface interface RetryWait { void beforeRetry(int failedAttempt) throws Exception; }
    static final class RetryOperationException extends RuntimeException {
        RetryOperationException(Exception cause) { super(cause); }
        @Override public synchronized Exception getCause() { return (Exception) super.getCause(); }
    }
    static final class OperationException extends RuntimeException {
        OperationException(Exception cause) { super(cause); }
        @Override public synchronized Exception getCause() { return (Exception) super.getCause(); }
    }

    private Document requestOnce(String path, Map<String, String> query, int attempt) throws Exception {
        long wait = MIN_REQUEST_GAP_MILLIS - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) Thread.sleep(wait);
        StringBuilder url = new StringBuilder(baseUrl).append(path).append("?serviceKey=")
                .append(encode(normalizeServiceKey(serviceKey, keyMode)));
        query.forEach((name, value) -> url.append('&').append(encode(name)).append('=').append(encode(value)));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/xml")
                .GET().build();
        lastRequestAt = System.currentTimeMillis();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            log.warn("TAGO API returned HTTP {}: path={} attempt={}/{}", status, path, attempt, retryCount);
            if (isRetryableHttpStatus(status)) throw new RetryableTagoException("HTTP_" + status);
            throw new IllegalStateException("TAGO returned non-retryable HTTP status");
        }
        Document document = parseXml(response.body());
        Element root = document.getDocumentElement();
        Element header = first(root, "header");
        String code = header == null ? "" : childText(header, "resultCode");
        if (!"00".equals(code)) {
            log.warn("TAGO API returned resultCode={} path={} attempt={}/{}", code, path, attempt, retryCount);
            if (isRetryableResultCode(code)) throw new RetryableTagoException("RESULT_99");
            throw new IllegalStateException("TAGO returned non-retryable result code");
        }
        return document;
    }

    static final class RetryableTagoException extends Exception {
        private final String reason;
        RetryableTagoException(String reason) { super("Transient TAGO failure"); this.reason = reason; }
        String reason() { return reason; }
    }

    static boolean isRetryableHttpStatus(int status) {
        return status == 429 || status >= 500;
    }

    static boolean isRetryableResultCode(String code) {
        return "99".equals(code);
    }

    private int persistCatalog(List<Route> routes, List<RouteStop> stops) {
        for (Route route : routes) {
            jdbc.update("""
                    INSERT INTO bus_routes(route_id, route_name, city_code, route_type, source, active, last_synced_at, updated_at)
                    VALUES (?, ?, ?, ?, 'TAGO', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (route_id) DO UPDATE SET route_name = EXCLUDED.route_name,
                        city_code = EXCLUDED.city_code, route_type = EXCLUDED.route_type,
                        source = 'TAGO', active = true, last_synced_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    """, route.id(), route.name(), cityCode, route.type());
        }
        for (RouteStop stop : stops) {
            jdbc.update("""
                    INSERT INTO bus_stops(node_id, node_name, city_code, latitude, longitude, source, active, last_synced_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'TAGO', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (node_id) DO UPDATE SET node_name = EXCLUDED.node_name,
                        city_code = EXCLUDED.city_code,
                        latitude = COALESCE(EXCLUDED.latitude, bus_stops.latitude),
                        longitude = COALESCE(EXCLUDED.longitude, bus_stops.longitude),
                        source = 'TAGO', active = true, last_synced_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    """, stop.id(), stop.name(), cityCode, stop.latitude(), stop.longitude());
        }
        for (Route route : routes) {
            jdbc.update("DELETE FROM bus_route_stops WHERE route_id = ?", route.id());
        }
        for (RouteStop stop : stops) {
            jdbc.update("""
                    INSERT INTO bus_route_stops(route_id, node_id, node_order, direction)
                    VALUES (?, ?, ?, ?)
                    """, stop.routeId(), stop.id(), stop.order(), stop.direction());
        }
        String routePlaceholders = String.join(",", java.util.Collections.nCopies(routes.size(), "?"));
        List<Object> routeParameters = new ArrayList<>();
        routeParameters.add(cityCode);
        routes.forEach(route -> routeParameters.add(route.id()));
        int staleRoutes = jdbc.update("""
                UPDATE bus_routes
                   SET active = false, updated_at = CURRENT_TIMESTAMP
                 WHERE city_code = ? AND source = 'TAGO' AND active = true
                   AND route_id NOT IN (""" + routePlaceholders + ")", routeParameters.toArray());
        // Keep historical stop/route-stop rows. Only mark stop masters inactive when
        // they are no longer used by any active route after a complete snapshot.
        jdbc.update("""
                UPDATE bus_stops b
                   SET active = false, updated_at = CURRENT_TIMESTAMP
                 WHERE b.city_code = ? AND b.source = 'TAGO' AND b.active = true
                   AND NOT EXISTS (
                       SELECT 1 FROM bus_route_stops s
                       JOIN bus_routes r ON r.route_id = s.route_id AND r.active = true
                       WHERE s.node_id = b.node_id
                   )
                """, cityCode);
        return staleRoutes;
    }

    private static String snapshotHash(List<Route> routes, List<RouteStop> stops) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            routes.stream().sorted(java.util.Comparator.comparing(Route::id))
                    .forEach(route -> updateDigest(digest, route.id(), route.name(), route.type()));
            stops.stream().sorted(java.util.Comparator.comparing(RouteStop::routeId)
                    .thenComparingInt(RouteStop::order).thenComparingInt(RouteStop::responseSequence))
                    .forEach(stop -> updateDigest(digest, stop.routeId(), stop.id(), stop.name(),
                            Integer.toString(stop.order()), stop.direction(), stop.directionCode(),
                            java.util.Objects.toString(stop.latitude(), ""),
                            java.util.Objects.toString(stop.longitude(), "")));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateDigest(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }
    }

    static String normalizeServiceKey(String value, String mode) {
        String trimmed = value == null ? "" : value.trim();
        String normalizedMode = mode == null ? "AUTO" : mode.trim().toUpperCase();
        if ("DECODED".equals(normalizedMode)) return trimmed;
        if ("ENCODED".equals(normalizedMode) || ("AUTO".equals(normalizedMode) && PERCENT_ESCAPE.matcher(trimmed).find())) {
            // URLDecoder treats '+' as space; preserve literal plus characters in an encoded key.
            return URLDecoder.decode(trimmed.replace("+", "%2B"), StandardCharsets.UTF_8);
        }
        if (!"AUTO".equals(normalizedMode)) throw new IllegalArgumentException("Unsupported TAGO key mode");
        return trimmed;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    static Route routeFrom(Element item) {
        String id = childText(item, "routeid");
        if (id.isBlank()) throw new IllegalArgumentException("TAGO route has no routeid");
        String name = childText(item, "routeno");
        if (name.isBlank()) name = id;
        return new Route(id, name, nullable(childText(item, "routetp")));
    }

    static RouteDetail routeDetailFrom(Element item) {
        return new RouteDetail(childText(item, "routeid"), childText(item, "routeno"),
                childText(item, "startnodenm"), childText(item, "endnodenm"));
    }

    static RouteStop stopFrom(Element item, String routeId, int responseSequence) {
        String id = childText(item, "nodeid");
        String name = childText(item, "nodenm");
        if (id.isBlank()) throw new CatalogValidationException("STOP_ID_MISSING");
        if (name.isBlank()) throw new CatalogValidationException("STOP_NAME_MISSING");
        String rawOrder = childText(item, "nodeord");
        int parsedOrder;
        try {
            parsedOrder = Integer.parseInt(rawOrder);
        } catch (NumberFormatException e) {
            parsedOrder = 0;
        }
        boolean orderInferred = parsedOrder < 1;
        int order = orderInferred ? responseSequence : parsedOrder;
        if (order < 1) throw new CatalogValidationException("STOP_ORDER_INVALID");
        String directionCode = childText(item, "updowncd");
        String direction = "0".equals(directionCode) ? "UP" : "1".equals(directionCode) ? "DOWN" : "UNKNOWN";
        return new RouteStop(routeId, id, name, order, direction, directionCode,
                decimalOrNull(childText(item, "gpslati")), decimalOrNull(childText(item, "gpslong")),
                responseSequence, orderInferred);
    }

    static List<RouteStop> normalizeStopOrders(List<RouteStop> input) {
        Map<String, List<Integer>> indexesByRouteDirection = new LinkedHashMap<>();
        for (int index = 0; index < input.size(); index++) {
            RouteStop stop = input.get(index);
            String key = stop.routeId() + "\u0000" + stop.direction();
            indexesByRouteDirection.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
        }

        RouteStop[] normalized = new RouteStop[input.size()];
        for (List<Integer> indexes : indexesByRouteDirection.values()) {
            Set<Integer> usedOrders = new HashSet<>();
            boolean[] fixed = new boolean[indexes.size()];
            for (int position = 0; position < indexes.size(); position++) {
                RouteStop stop = input.get(indexes.get(position));
                fixed[position] = !stop.orderInferred() && stop.order() > 0 && usedOrders.add(stop.order());
            }

            for (int position = 0; position < indexes.size(); position++) {
                int sourceIndex = indexes.get(position);
                RouteStop stop = input.get(sourceIndex);
                if (fixed[position]) {
                    normalized[sourceIndex] = stop;
                    continue;
                }

                int lowerBound = 1;
                for (int previous = position - 1; previous >= 0; previous--) {
                    if (fixed[previous]) {
                        lowerBound = input.get(indexes.get(previous)).order() + 1;
                        break;
                    }
                }
                int upperBound = Integer.MAX_VALUE;
                for (int next = position + 1; next < indexes.size(); next++) {
                    if (fixed[next]) {
                        upperBound = input.get(indexes.get(next)).order() - 1;
                        break;
                    }
                }

                int fallbackOrder = firstAvailableOrder(lowerBound, upperBound, usedOrders);
                if (fallbackOrder < 1) {
                    fallbackOrder = firstAvailableOrder(1, Integer.MAX_VALUE, usedOrders);
                }
                if (fallbackOrder < 1) throw new CatalogValidationException("STOP_ORDER_EXHAUSTED");
                usedOrders.add(fallbackOrder);
                normalized[sourceIndex] = new RouteStop(stop.routeId(), stop.id(), stop.name(), fallbackOrder,
                        stop.direction(), stop.directionCode(), stop.latitude(), stop.longitude(), stop.responseSequence(), true);
            }
        }
        List<RouteStop> result = new ArrayList<>(normalized.length);
        java.util.Collections.addAll(result, normalized);
        return result;
    }

    /**
     * Derives local direction labels only when the configured Jangseong hub or an
     * already-oriented same-route-name variant provides an unambiguous endpoint.
     * The original TAGO updowncd is retained on each stop for audit.
     */
    static DirectionInference inferUnknownDirections(List<Route> routes, List<RouteStop> stops) {
        final String jangseongTerminal = "장성터미널";
        final String referenceRouteId = "TSB342000042";
        final String referenceHubName = "사거리버스터미널";

        Map<String, Route> routesById = new LinkedHashMap<>();
        routes.forEach(route -> routesById.put(route.id(), route));
        Map<String, List<RouteStop>> rowsByRoute = new LinkedHashMap<>();
        stops.forEach(stop -> rowsByRoute.computeIfAbsent(stop.routeId(), ignored -> new ArrayList<>()).add(stop));
        rowsByRoute.values().forEach(rows -> rows.sort(java.util.Comparator.comparingInt(RouteStop::order)
                .thenComparingInt(RouteStop::responseSequence)));

        String referenceHubId = null;
        List<RouteStop> referenceRows = rowsByRoute.get(referenceRouteId);
        if (referenceRows != null) {
            RouteStop upFirst = referenceRows.stream().filter(stop -> "UP".equals(stop.direction()))
                    .min(java.util.Comparator.comparingInt(RouteStop::order)).orElse(null);
            RouteStop downLast = referenceRows.stream().filter(stop -> "DOWN".equals(stop.direction()))
                    .max(java.util.Comparator.comparingInt(RouteStop::order)).orElse(null);
            if (upFirst != null && downLast != null && upFirst.id().equals(downLast.id())
                    && canonicalText(upFirst.name()).equals(canonicalText(referenceHubName))) {
                referenceHubId = upFirst.id();
            }
        }

        Map<String, String> inferredRouteDirections = new LinkedHashMap<>();
        Map<String, String> inferenceRuleByRoute = new LinkedHashMap<>();
        for (Map.Entry<String, List<RouteStop>> entry : rowsByRoute.entrySet()) {
            String reviewedDirection = REVIEWED_ROUTE_DIRECTIONS.get(entry.getKey());
            if (reviewedDirection != null && !entry.getValue().isEmpty()
                    && entry.getValue().stream().allMatch(stop -> "UNKNOWN".equals(stop.direction()))) {
                inferredRouteDirections.put(entry.getKey(), reviewedDirection);
                inferenceRuleByRoute.put(entry.getKey(), "REVIEWED_ROUTE_DIRECTION");
            }
        }
        for (Map.Entry<String, List<RouteStop>> entry : rowsByRoute.entrySet()) {
            List<RouteStop> rows = entry.getValue();
            if (rows.isEmpty() || inferredRouteDirections.containsKey(entry.getKey())
                    || rows.stream().anyMatch(stop -> !"UNKNOWN".equals(stop.direction()))) continue;
            RouteStop first = rows.get(0);
            RouteStop last = rows.get(rows.size() - 1);
            boolean startsAtJangseong = canonicalText(first.name()).equals(canonicalText(jangseongTerminal));
            boolean endsAtJangseong = canonicalText(last.name()).equals(canonicalText(jangseongTerminal));
            boolean startsAtReferenceHub = referenceHubId != null && first.id().equals(referenceHubId);
            boolean endsAtReferenceHub = referenceHubId != null && last.id().equals(referenceHubId);
            if (startsAtJangseong != endsAtJangseong && startsAtJangseong) {
                inferredRouteDirections.put(entry.getKey(), "UP");
                inferenceRuleByRoute.put(entry.getKey(), "JANGSEONG_OUTBOUND");
            } else if (startsAtJangseong != endsAtJangseong) {
                inferredRouteDirections.put(entry.getKey(), "DOWN");
                inferenceRuleByRoute.put(entry.getKey(), "JANGSEONG_INBOUND");
            } else if (startsAtReferenceHub != endsAtReferenceHub && startsAtReferenceHub) {
                inferredRouteDirections.put(entry.getKey(), "UP");
                inferenceRuleByRoute.put(entry.getKey(), "REFERENCE_042_OUTBOUND");
            } else if (startsAtReferenceHub != endsAtReferenceHub) {
                inferredRouteDirections.put(entry.getKey(), "DOWN");
                inferenceRuleByRoute.put(entry.getKey(), "REFERENCE_042_INBOUND");
            }
        }

        // A same-name variant can differ in intermediate stops. Align it to
        // classified variants by matching an endpoint; conflicting evidence stays UNKNOWN.
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, List<RouteStop>> entry : rowsByRoute.entrySet()) {
                String routeId = entry.getKey();
                if (inferredRouteDirections.containsKey(routeId)
                        || entry.getValue().isEmpty()
                        || entry.getValue().stream().anyMatch(stop -> !"UNKNOWN".equals(stop.direction()))) continue;
                Route route = routesById.get(routeId);
                if (route == null || route.name() == null || route.name().isBlank()) continue;
                RouteStop first = entry.getValue().get(0);
                RouteStop last = entry.getValue().get(entry.getValue().size() - 1);
                Set<String> votes = new HashSet<>();
                for (Map.Entry<String, String> anchor : inferredRouteDirections.entrySet()) {
                    Route anchorRoute = routesById.get(anchor.getKey());
                    if (anchorRoute == null || !canonicalText(route.name()).equals(canonicalText(anchorRoute.name()))) continue;
                    List<RouteStop> anchorRows = rowsByRoute.get(anchor.getKey());
                    if (anchorRows == null || anchorRows.isEmpty()) continue;
                    RouteStop anchorFirst = anchorRows.get(0);
                    RouteStop anchorLast = anchorRows.get(anchorRows.size() - 1);
                    boolean same = sameEndpoint(first, anchorFirst) || sameEndpoint(last, anchorLast);
                    boolean reverse = sameEndpoint(first, anchorLast) || sameEndpoint(last, anchorFirst);
                    if (same != reverse) {
                        String direction = same ? anchor.getValue() : oppositeDirection(anchor.getValue());
                        votes.add(direction);
                    }
                }
                if (votes.size() == 1) {
                    inferredRouteDirections.put(routeId, votes.iterator().next());
                    inferenceRuleByRoute.put(routeId, "SAME_ROUTE_NAME_ENDPOINT");
                    changed = true;
                }
            }
        } while (changed);

        List<RouteStop> result = new ArrayList<>(stops.size());
        Map<String, Long> inferredByRule = new LinkedHashMap<>();
        long inferredStopCount = 0;
        long unresolvedStopCount = 0;
        for (RouteStop stop : stops) {
            String inferredDirection = "UNKNOWN".equals(stop.direction()) ? inferredRouteDirections.get(stop.routeId()) : null;
            if (inferredDirection != null) {
                result.add(new RouteStop(stop.routeId(), stop.id(), stop.name(), stop.order(), inferredDirection,
                        stop.directionCode(), stop.latitude(), stop.longitude(), stop.responseSequence(), stop.orderInferred()));
                inferredStopCount++;
                inferredByRule.merge(inferenceRuleByRoute.get(stop.routeId()), 1L, Long::sum);
            } else {
                result.add(stop);
                if ("UNKNOWN".equals(stop.direction())) unresolvedStopCount++;
            }
        }
        return new DirectionInference(List.copyOf(result), inferredStopCount, unresolvedStopCount, Map.copyOf(inferredByRule));
    }

    private static String oppositeDirection(String direction) {
        return "UP".equals(direction) ? "DOWN" : "UP";
    }

    private static boolean sameEndpoint(RouteStop left, RouteStop right) {
        return left.id().equals(right.id())
                || canonicalText(left.name()).equals(canonicalText(right.name()));
    }

    record DirectionInference(List<RouteStop> stops, long inferredStopCount, long unresolvedStopCount,
                              Map<String, Long> inferredByRule) { }

    private static int firstAvailableOrder(int lowerBound, int upperBound, Set<Integer> usedOrders) {
        if (lowerBound > upperBound) return -1;
        for (int candidate = Math.max(1, lowerBound); candidate > 0 && candidate <= upperBound; candidate++) {
            if (!usedOrders.contains(candidate)) return candidate;
        }
        return -1;
    }

    private static String postgresConstraintName(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            try {
                Object serverError = cause.getClass().getMethod("getServerErrorMessage").invoke(cause);
                if (serverError == null) continue;
                Object value = serverError.getClass().getMethod("getConstraint").invoke(serverError);
                if (value instanceof String name && name.matches("[A-Za-z0-9_]{1,120}")) return name;
            } catch (ReflectiveOperationException | SecurityException ignored) {
                // The JDBC driver may not expose PostgreSQL-specific diagnostics.
            }
        }
        return "UNKNOWN";
    }

    static final class CatalogValidationException extends IllegalArgumentException {
        private final String failureCode;
        CatalogValidationException(String failureCode) {
            super("TAGO catalog item failed validation");
            this.failureCode = failureCode;
        }
        String failureCode() { return failureCode; }
    }

    private static BigDecimal decimalOrNull(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
    }

    private static String nullable(String value) { return value == null || value.isBlank() ? null : value; }

    private static Element first(Element parent, String tag) {
        if (parent == null) return null;
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static String childText(Element parent, String tag) {
        Element child = first(parent, tag);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && tag.equals(element.getTagName())) result.add(element);
        }
        return result;
    }

    @FunctionalInterface private interface ItemMapper<T> { T map(Element item, int responseSequence); }
    public record Route(String id, String name, String type) { }
    public record RouteDetail(String id, String name, String startStopName, String endStopName) { }
    private record StopKey(String routeId, String nodeId, String direction) { }
    private record StopOrderKey(String routeId, String direction, int order) { }
    public record RouteStop(String routeId, String id, String name, int order, String direction, String directionCode,
                            BigDecimal latitude, BigDecimal longitude, int responseSequence,
                            boolean orderInferred) {
        public RouteStop(String routeId, String id, String name, int order, String direction,
                         BigDecimal latitude, BigDecimal longitude, int responseSequence,
                         boolean orderInferred) {
            this(routeId, id, name, order, direction,
                    "UP".equals(direction) ? "0" : "DOWN".equals(direction) ? "1" : "",
                    latitude, longitude, responseSequence, orderInferred);
        }
    }
}
