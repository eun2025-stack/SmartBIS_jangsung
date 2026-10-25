package com.smartbis.backend.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fetches Jangseong and Gwangju administrative boundaries for point-in-polygon stop grouping. */
@Service
public class BoundaryRegionService {
    private static final String VWORLD_DATA_URL = "https://api.vworld.kr/req/data";
    private static final String LAYER = "LT_C_ADEMD_INFO";
    // Covers Jangseong-gun and Gwangju, including routes that cross the county boundary.
    private static final String JANGSEONG_BBOX = "BOX(126.45,35.0,127.1,35.7)";
    private static final Set<String> GWANGJU_DISTRICTS = Set.of("광산구", "북구", "동구", "남구", "서구");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final String serviceKey;
    private final String domain;
    private final String tagoCityCode;

    public BoundaryRegionService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${moct.wfs.service-key:}") String serviceKey,
            @Value("${moct.wfs.domain:https://localhost:8443}") String domain,
            @Value("${tago.city-code:}") String tagoCityCode
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.domain = domain == null ? "" : domain.trim();
        this.tagoCityCode = tagoCityCode == null ? "" : tagoCityCode.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Transactional
    public Map<String, Object> classifyUnassigned() {
        List<Boundary> boundaries = fetchBoundaries();
        List<Stop> stops = loadStops();
        List<Assignment> assignments = assign(stops, boundaries);
        List<Assignment> toApply = assignments.stream()
                .filter(a -> a.candidates().size() == 1)
                .filter(a -> a.stop().regionId() == null && a.stop().regionName() == null)
                .toList();

        int added = 0;
        int assignedToBoundary = 0;
        for (Boundary boundary : boundaries) {
            List<String> codeMatches = jdbc.query("SELECT region_name FROM regions WHERE region_code = ?",
                    (rs, rowNum) -> rs.getString(1), boundary.code());
            if (!codeMatches.isEmpty() && !boundary.name().equals(codeMatches.get(0))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "경계 코드가 다른 지역명으로 이미 등록되어 있습니다: " + boundary.code());
            }
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM regions WHERE region_name = ?",
                    Integer.class, boundary.name());
            if (count == null || count == 0) {
                jdbc.update("""
                        INSERT INTO regions(region_code, region_name, parent_region_name,
                                            active, created_at, updated_at)
                        VALUES (?, ?, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, boundary.code(), boundary.name(), boundary.parentName());
                added++;
            }
        }

        for (Assignment assignment : toApply) {
            String regionName = assignment.candidates().get(0).name();
            assignedToBoundary += jdbc.update("""
                    UPDATE bus_stops
                       SET region_id = (SELECT region_id FROM regions
                                         WHERE region_name = ? AND active = true),
                           region_name = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE node_id = ? AND city_code = ?
                       AND region_id IS NULL AND region_name IS NULL
                    """, regionName, regionName, assignment.stop().nodeId(), assignment.stop().cityCode());
        }

        // Business rule for the Jangseong route catalog: a stop inside a Jangseong
        // boundary gets its 읍·면; every remaining stop is grouped under Gwangju.
        // This is an operational grouping fallback, not a claim that TAGO supplied
        // a Gwangju administrative code or that missing coordinates are invalid.
        Long gwangjuRegionId = jdbc.queryForObject("""
                INSERT INTO regions(region_name, parent_region_name, active, created_at, updated_at)
                VALUES ('광주광역시', '광주광역시', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (region_name) DO UPDATE
                    SET parent_region_name = EXCLUDED.parent_region_name,
                        active = true,
                        updated_at = CURRENT_TIMESTAMP
                RETURNING region_id
                """, Long.class);
        int fallbackAssigned = jdbc.update("""
                UPDATE bus_stops
                   SET region_id = ?, region_name = '광주광역시', updated_at = CURRENT_TIMESTAMP
                 WHERE city_code = ? AND active = true
                   AND region_id IS NULL AND region_name IS NULL
                """, gwangjuRegionId, tagoCityCode);
        int unmatchedCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bus_stops
                 WHERE city_code = ? AND active = true
                   AND region_id IS NULL AND region_name IS NULL
                """, Integer.class, tagoCityCode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("boundaryCount", boundaries.size());
        result.put("regionsCreated", added);
        result.put("stopsAssigned", assignedToBoundary + fallbackAssigned);
        result.put("gwangjuFallbackAssigned", fallbackAssigned);
        result.put("ambiguousToGwangjuCount", assignments.stream().filter(BoundaryRegionService::isUnassigned)
                .filter(a -> a.candidates().size() > 1).count());
        result.put("boundaryUnmatchedToGwangjuCount", assignments.stream().filter(BoundaryRegionService::isUnassigned)
                .filter(a -> a.candidates().isEmpty()).count());
        result.put("unmatchedCount", unmatchedCount);
        result.put("gwangjuFallbackApplied", true);
        result.put("existingAssignmentsPreserved", true);
        return result;
    }

    private List<Boundary> fetchBoundaries() {
        if (serviceKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MOCT_WFS_SERVICE_KEY가 설정되지 않았습니다. 서버 .env와 Compose 전달 설정을 확인하세요.");
        }
        if (tagoCityCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "TAGO_CITY_CODE가 설정되지 않았습니다.");
        }

        URI uri = UriComponentsBuilder.fromUriString(VWORLD_DATA_URL)
                .queryParam("service", "data")
                .queryParam("version", "2.0")
                .queryParam("request", "GetFeature")
                .queryParam("format", "json")
                .queryParam("key", serviceKey)
                .queryParam("domain", domain)
                .queryParam("data", LAYER)
                .queryParam("size", "1000")
                .queryParam("page", "1")
                .queryParam("geometry", "true")
                .queryParam("attribute", "true")
                .queryParam("crs", "EPSG:4326")
                .queryParam("geomFilter", JANGSEONG_BBOX)
                .build().encode().toUri();

        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45))
                .header("Accept", "application/json")
                .GET().build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "행정구역 API HTTP " + response.statusCode() + " 응답");
            }
            JsonNode root = objectMapper.readTree(response.body());
            String status = root.path("response").path("status").asText();
            if (!"OK".equalsIgnoreCase(status)) {
                String message = root.path("response").path("error").path("text").asText("응답 상태 오류");
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "행정구역 API 오류: " + message);
            }
            JsonNode features = root.path("response").path("result").path("featureCollection").path("features");
            if (!features.isArray() || features.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "행정구역 경계 결과가 없습니다. API 키·허용 도메인·경계 조회 범위를 확인하세요.");
            }

            List<Boundary> boundaries = new ArrayList<>();
            for (JsonNode feature : features) {
                JsonNode properties = feature.path("properties");
                String code = properties.path("emd_cd").asText("");
                String sourceName = properties.path("emd_kor_nm").asText("");
                String fullName = properties.path("full_nm").asText("");
                // Select both sides of Jangseong routes; do not rely on changeable area codes.
                if (!isTargetArea(fullName)) continue;
                if (code.isBlank() || sourceName.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "행정구역 경계 응답에 emd_cd 또는 emd_kor_nm 속성이 없습니다.");
                }
                JsonNode geometry = feature.path("geometry");
                String type = geometry.path("type").asText();
                JsonNode coordinates = geometry.path("coordinates");
                if ((!"Polygon".equals(type) && !"MultiPolygon".equals(type)) || !coordinates.isArray()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "지원하지 않는 경계 Geometry 형식: " + type);
                }
                boundaries.add(toBoundary(code, sourceName, fullName, type, coordinates));
            }
            if (boundaries.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "조회 범위에서 장성군 또는 광주광역시 읍·면·동 경계를 찾지 못했습니다.");
            }
            return boundaries;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "행정구역 API 요청이 중단되었습니다.");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "행정구역 API 응답을 읽지 못했습니다: " + exception.getClass().getSimpleName());
        }
    }

    private List<Stop> loadStops() {
        return jdbc.query("""
                SELECT node_id, city_code, node_name, latitude, longitude, region_id, region_name
                  FROM bus_stops
                 WHERE city_code = ? AND active = true
                 ORDER BY node_id
                """, (rs, rowNum) -> new Stop(rs.getString("node_id"), rs.getString("city_code"),
                rs.getString("node_name"), rs.getBigDecimal("latitude") == null ? null
                : rs.getBigDecimal("latitude").doubleValue(), rs.getBigDecimal("longitude") == null ? null
                : rs.getBigDecimal("longitude").doubleValue(), (Long) rs.getObject("region_id"),
                rs.getString("region_name")), tagoCityCode);
    }

    private static List<Assignment> assign(List<Stop> stops, List<Boundary> boundaries) {
        List<Assignment> result = new ArrayList<>();
        for (Stop stop : stops) {
            List<Boundary> candidates = new ArrayList<>();
            if (stop.latitude() != null && stop.longitude() != null) {
                for (Boundary boundary : boundaries) {
                    if (contains(boundary, stop.longitude(), stop.latitude())) candidates.add(boundary);
                }
            }
            result.add(new Assignment(stop, candidates));
        }
        return result;
    }

    private static boolean isUnassigned(Assignment assignment) {
        return assignment.stop().regionId() == null && assignment.stop().regionName() == null;
    }

    static boolean contains(Boundary boundary, double longitude, double latitude) {
        return "Polygon".equals(boundary.geometryType())
                ? polygonContains(boundary.coordinates(), longitude, latitude)
                : multiPolygonContains(boundary.coordinates(), longitude, latitude);
    }

    private static boolean multiPolygonContains(JsonNode polygons, double x, double y) {
        for (JsonNode polygon : polygons) if (polygonContains(polygon, x, y)) return true;
        return false;
    }

    /** GeoJSON polygon coordinates are [rings], with first ring exterior and remaining rings holes. */
    private static boolean polygonContains(JsonNode rings, double x, double y) {
        if (!rings.isArray() || rings.isEmpty() || !pointInRing(rings.get(0), x, y)) return false;
        for (int i = 1; i < rings.size(); i++) if (pointInRing(rings.get(i), x, y)) return false;
        return true;
    }

    private static boolean pointInRing(JsonNode ring, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            JsonNode pi = ring.get(i);
            JsonNode pj = ring.get(j);
            if (pi == null || pj == null || pi.size() < 2 || pj.size() < 2) continue;
            double xi = pi.get(0).asDouble();
            double yi = pi.get(1).asDouble();
            double xj = pj.get(0).asDouble();
            double yj = pj.get(1).asDouble();
            if (onSegment(x, y, xi, yi, xj, yj)) return true;
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
        }
        return inside;
    }

    private static boolean onSegment(double x, double y, double x1, double y1, double x2, double y2) {
        double cross = (x - x1) * (y2 - y1) - (y - y1) * (x2 - x1);
        if (Math.abs(cross) > 1e-10) return false;
        return x >= Math.min(x1, x2) - 1e-10 && x <= Math.max(x1, x2) + 1e-10
                && y >= Math.min(y1, y2) - 1e-10 && y <= Math.max(y1, y2) + 1e-10;
    }

    static boolean isTargetArea(String fullName) {
        if (fullName == null) return false;
        String[] parts = fullName.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("장성군")) return true;
            if (GWANGJU_DISTRICTS.contains(parts[i]) && i > 0
                    && (parts[i - 1].equals("광주광역시")
                    || parts[i - 1].equals("전남광주통합특별시"))) return true;
        }
        return false;
    }

    static Boundary toBoundary(String code, String sourceName, String fullName,
                               String geometryType, JsonNode coordinates) {
        if (fullName.contains("장성군")) {
            return new Boundary(code, sourceName, "장성군", geometryType, coordinates);
        }

        String[] parts = fullName.trim().split("\\s+");
        int districtIndex = -1;
        for (int i = 0; i < parts.length; i++) {
            if (GWANGJU_DISTRICTS.contains(parts[i]) && i > 0
                    && (parts[i - 1].equals("광주광역시")
                    || parts[i - 1].equals("전남광주통합특별시"))) {
                districtIndex = i;
                break;
            }
        }
        if (districtIndex < 0 || districtIndex + 1 >= parts.length) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "광주 행정경계의 full_nm에서 광역시·구·읍면동을 확인할 수 없습니다: " + fullName);
        }
        String parent = "광주광역시 " + parts[districtIndex];
        String name = parent + " " + parts[districtIndex + 1];
        return new Boundary(code, name, parent, geometryType, coordinates);
    }

    record Boundary(String code, String name, String parentName,
                    String geometryType, JsonNode coordinates) {
    }

    private record Stop(String nodeId, String cityCode, String nodeName, Double latitude,
                        Double longitude, Long regionId, String regionName) { }

    private record Assignment(Stop stop, List<Boundary> candidates) { }
}
