package com.smartbis.backend.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.List;
import java.util.Map;

/** Receives lightweight player heartbeats and exposes them only to dashboard administrators. */
@RestController
@RequestMapping("/v1")
public class DeviceStatusController {
    private final JdbcTemplate jdbc;

    public DeviceStatusController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/device-status")
    public ResponseEntity<Void> report(@RequestBody DeviceStatusRequest request) {
        validate(request);
        jdbc.update("""
                INSERT INTO android_device_status (
                    vehicle_number, playback_status, display_type, content_id,
                    content_title, app_version, error_message, last_seen_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (vehicle_number) DO UPDATE SET
                    playback_status = EXCLUDED.playback_status,
                    display_type = EXCLUDED.display_type,
                    content_id = EXCLUDED.content_id,
                    content_title = EXCLUDED.content_title,
                    app_version = EXCLUDED.app_version,
                    error_message = EXCLUDED.error_message,
                    last_seen_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                """,
                request.vehicleNumber().trim(), request.playbackStatus(),
                blankToNull(request.displayType()), blankToNull(request.contentId()),
                blankToNull(request.contentTitle()), blankToNull(request.appVersion()),
                blankToNull(request.errorMessage()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/device-status")
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT vehicle_number, playback_status, display_type, content_id,
                       content_title, app_version, error_message, last_seen_at
                  FROM android_device_status
                 ORDER BY last_seen_at DESC, vehicle_number
                """);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validate(DeviceStatusRequest request) {
        if (request == null || request.vehicleNumber() == null ||
                !request.vehicleNumber().trim().matches("[A-Za-z0-9가-힣._ -]{1,50}")) {
            throw new ResponseStatusException(BAD_REQUEST, "차량번호 형식이 올바르지 않습니다.");
        }
        if (request.playbackStatus() == null ||
                !request.playbackStatus().matches("CONNECTING|PLAYING|WAITING|ERROR|OFFLINE_PLAYBACK")) {
            throw new ResponseStatusException(BAD_REQUEST, "재생 상태가 올바르지 않습니다.");
        }
        if (!within(request.displayType(), 30) || !within(request.contentId(), 100) ||
                !within(request.contentTitle(), 300) || !within(request.appVersion(), 40) ||
                !within(request.errorMessage(), 1000)) {
            throw new ResponseStatusException(BAD_REQUEST, "상태 보고 필드 길이가 제한을 초과했습니다.");
        }
    }

    private static boolean within(String value, int maxLength) {
        return value == null || value.length() <= maxLength;
    }

    public record DeviceStatusRequest(
            String vehicleNumber,
            String playbackStatus,
            String displayType,
            String contentId,
            String contentTitle,
            String appVersion,
            String errorMessage
    ) { }
}
