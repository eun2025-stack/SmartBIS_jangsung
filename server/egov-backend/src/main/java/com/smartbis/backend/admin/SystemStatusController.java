package com.smartbis.backend.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.smartbis.backend.tago.TagoVehicleLocationService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authenticated, read-only proxy for internal service health. */
@RestController
@RequestMapping("/v1/admin/system-status")
public class SystemStatusController {
    private final ObjectMapper objectMapper;
    private final URI watcherHealthUri;
    private final HttpClient httpClient;
    private final TagoVehicleLocationService tagoVehicleLocationService;

    public SystemStatusController(
            ObjectMapper objectMapper,
            TagoVehicleLocationService tagoVehicleLocationService,
            @Value("${smartbis.node-watcher-status-url:http://node-watcher:3000/health}") String watcherHealthUrl) {
        this.objectMapper = objectMapper;
        this.tagoVehicleLocationService = tagoVehicleLocationService;
        this.watcherHealthUri = URI.create(watcherHealthUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkedAt", OffsetDateTime.now());
        result.put("tagoVehicleLocation", tagoVehicleLocationService.status());

        try {
            HttpRequest request = HttpRequest.newBuilder(watcherHealthUri)
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                result.put("nodeWatcher", objectMapper.readValue(response.body(), Map.class));
                result.put("status", "UP");
            } else {
                result.put("nodeWatcher", Map.of("status", "DOWN"));
                result.put("status", "DEGRADED");
            }
        } catch (Exception ignored) {
            result.put("nodeWatcher", Map.of("status", "UNAVAILABLE"));
            result.put("status", "DEGRADED");
        }

        return result;
    }
}
