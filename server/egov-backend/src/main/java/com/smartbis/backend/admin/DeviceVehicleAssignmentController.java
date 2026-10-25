package com.smartbis.backend.admin;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Persists the separately changeable bus assignment for an enrolled physical MDM terminal. */
@RestController
@RequestMapping("/v1")
public class DeviceVehicleAssignmentController {
    private static final Pattern FINGERPRINT = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Pattern VEHICLE_NUMBER = Pattern.compile("[A-Za-z0-9가-힣._ -]{1,100}");

    private final JdbcTemplate jdbc;

    public DeviceVehicleAssignmentController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS android_device_identity_lock (
                    vehicle_number VARCHAR(50) PRIMARY KEY,
                    identity_sha256 CHAR(64) NOT NULL,
                    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS android_device_vehicle_assignments (
                    device_number VARCHAR(50) PRIMARY KEY
                        REFERENCES android_device_identity_lock(vehicle_number) ON DELETE RESTRICT,
                    vehicle_number VARCHAR(100) NOT NULL UNIQUE,
                    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    /** Returns only the terminal identity whose Android installation fingerprint is presented. */
    @GetMapping("/device-vehicle-assignment/current")
    public CurrentAssignment current(@RequestParam String identitySha256) {
        String fingerprint = validateFingerprint(identitySha256);
        List<String> deviceNumbers = jdbc.queryForList("""
                SELECT vehicle_number
                  FROM android_device_identity_lock
                 WHERE identity_sha256 = ?
                 ORDER BY vehicle_number
                 LIMIT 2
                """, String.class, fingerprint);
        if (deviceNumbers.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    deviceNumbers.isEmpty() ? "DEVICE_NOT_ENROLLED" : "DEVICE_IDENTITY_AMBIGUOUS");
        }
        String deviceNumber = deviceNumbers.get(0);
        List<String> assignedVehicles = jdbc.queryForList("""
                SELECT vehicle_number FROM android_device_vehicle_assignments WHERE device_number = ?
                """, String.class, deviceNumber);
        return new CurrentAssignment(deviceNumber, assignedVehicles.isEmpty() ? null : assignedVehicles.get(0));
    }

    /** Saves the bus selected on the physical terminal; one vehicle cannot be assigned twice. */
    @PostMapping("/device-vehicle-assignment")
    public ResponseEntity<CurrentAssignment> assign(@RequestBody AssignmentRequest request) {
        if (request == null || request.identitySha256() == null || request.vehicleNumber() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        String fingerprint = validateFingerprint(request.identitySha256());
        String vehicleNumber = request.vehicleNumber().trim();
        if (!VEHICLE_NUMBER.matcher(vehicleNumber).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_VEHICLE_NUMBER");
        }
        Integer vehicleExists = jdbc.queryForObject(
                "SELECT count(*) FROM bus_vehicles WHERE active = TRUE AND vehicle_number = ?",
                Integer.class, vehicleNumber);
        if (vehicleExists == null || vehicleExists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_IN_ACTIVE_CATALOG");
        }
        String deviceNumber = resolveDeviceNumber(fingerprint);
        try {
            jdbc.update("""
                    INSERT INTO android_device_vehicle_assignments(device_number, vehicle_number)
                    VALUES (?, ?)
                    ON CONFLICT (device_number) DO UPDATE SET
                        vehicle_number = EXCLUDED.vehicle_number,
                        updated_at = CURRENT_TIMESTAMP
                    """, deviceNumber, vehicleNumber);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "VEHICLE_ALREADY_ASSIGNED_TO_ANOTHER_DEVICE");
        }
        return ResponseEntity.ok(new CurrentAssignment(deviceNumber, vehicleNumber));
    }

    /** Removes only the selected terminal's current vehicle mapping; terminal identity/history remain intact. */
    @DeleteMapping("/device-vehicle-assignment")
    public CurrentAssignment unassign(@RequestParam String identitySha256) {
        String fingerprint = validateFingerprint(identitySha256);
        String deviceNumber = resolveDeviceNumber(fingerprint);
        jdbc.update("DELETE FROM android_device_vehicle_assignments WHERE device_number = ?", deviceNumber);
        return new CurrentAssignment(deviceNumber, null);
    }

    /** Administrator-only inventory for the dashboard; includes registered terminals not yet assigned. */
    @GetMapping("/admin/device-vehicle-assignments")
    public List<Map<String, Object>> listAssignments() {
        return jdbc.queryForList("""
                SELECT locks.vehicle_number AS device_number,
                       assignments.vehicle_number,
                       assignments.assigned_at,
                       assignments.updated_at
                  FROM android_device_identity_lock locks
                  LEFT JOIN android_device_vehicle_assignments assignments
                    ON assignments.device_number = locks.vehicle_number
                 ORDER BY locks.vehicle_number
                """);
    }

    private String resolveDeviceNumber(String fingerprint) {
        List<String> deviceNumbers = jdbc.queryForList("""
                SELECT vehicle_number
                  FROM android_device_identity_lock
                 WHERE identity_sha256 = ?
                 ORDER BY vehicle_number
                 LIMIT 2
                """, String.class, fingerprint);
        if (deviceNumbers.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    deviceNumbers.isEmpty() ? "DEVICE_NOT_ENROLLED" : "DEVICE_IDENTITY_AMBIGUOUS");
        }
        return deviceNumbers.get(0);
    }

    private static String validateFingerprint(String value) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_FINGERPRINT");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    public record AssignmentRequest(String identitySha256, String vehicleNumber) { }
    public record CurrentAssignment(String deviceNumber, String vehicleNumber) { }
}
