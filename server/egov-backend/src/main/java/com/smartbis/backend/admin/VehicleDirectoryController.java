package com.smartbis.backend.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Provides registered TAGO vehicle numbers for the on-device operator selector. */
@RestController
@RequestMapping("/v1/vehicles")
public class VehicleDirectoryController {
    private static final Pattern FINGERPRINT = Pattern.compile("[a-fA-F0-9]{64}");
    private final JdbcTemplate jdbc;

    public VehicleDirectoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<String> search(@RequestParam(value = "q", required = false, defaultValue = "") String query) {
        String value = query == null ? "" : query.trim();
        if (value.length() > 50) {
            throw new ResponseStatusException(BAD_REQUEST, "차량번호 검색어는 50자 이하여야 합니다.");
        }
        String escaped = value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return jdbc.queryForList("""
                SELECT vehicle_number
                  FROM bus_vehicles
                 WHERE active = TRUE
                   AND vehicle_number ILIKE ? ESCAPE '\\'
                 ORDER BY vehicle_number
                 LIMIT 1000
                """, String.class, "%" + escaped + "%");
    }

    /** Availability view for the assignment app; another device's identity is not disclosed. */
    @GetMapping("/availability")
    public List<VehicleAvailability> availability(
            @RequestParam(value = "q", required = false, defaultValue = "") String query,
            @RequestParam String identitySha256) {
        String value = query == null ? "" : query.trim();
        if (value.length() > 50) {
            throw new ResponseStatusException(BAD_REQUEST, "차량번호 검색어는 50자 이하여야 합니다.");
        }
        if (identitySha256 == null || !FINGERPRINT.matcher(identitySha256).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "단말 식별값 형식이 올바르지 않습니다.");
        }

        List<String> deviceNumbers = jdbc.queryForList("""
                SELECT vehicle_number
                  FROM android_device_identity_lock
                 WHERE identity_sha256 = ?
                 ORDER BY vehicle_number
                 LIMIT 2
                """, String.class, identitySha256.toLowerCase(java.util.Locale.ROOT));
        if (deviceNumbers.size() != 1) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    deviceNumbers.isEmpty() ? "DEVICE_NOT_ENROLLED" : "DEVICE_IDENTITY_AMBIGUOUS");
        }

        String escaped = value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT v.vehicle_number,
                       CASE
                           WHEN a.device_number IS NULL THEN 'UNASSIGNED'
                           WHEN a.device_number = ? THEN 'ASSIGNED_TO_THIS_DEVICE'
                           ELSE 'ASSIGNED_TO_OTHER_DEVICE'
                       END AS assignment_status
                  FROM bus_vehicles v
                  LEFT JOIN android_device_vehicle_assignments a
                    ON a.vehicle_number = v.vehicle_number
                 WHERE v.active = TRUE
                   AND v.vehicle_number ILIKE ? ESCAPE '\\'
                 ORDER BY v.vehicle_number
                 LIMIT 1000
                """, deviceNumbers.get(0), "%" + escaped + "%");
        return rows.stream().map(row -> new VehicleAvailability(
                String.valueOf(row.get("vehicle_number")),
                String.valueOf(row.get("assignment_status")))).toList();
    }

    public record VehicleAvailability(String vehicleNumber, String assignmentStatus) { }
}
