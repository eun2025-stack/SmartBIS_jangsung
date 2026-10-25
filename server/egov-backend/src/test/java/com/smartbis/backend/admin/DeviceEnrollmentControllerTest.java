package com.smartbis.backend.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;

class DeviceEnrollmentControllerTest {
    private static final String SECRET = "test-hmdm-secret";
    private static final String VEHICLE = "BIS_JS_001";

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private DeviceEnrollmentController controller;

    @BeforeEach
    void setUp() throws Exception {
        Path rootXml = tempDir.resolve("ROOT.xml");
        Files.writeString(rootXml, "<Context><Parameter name=\"hash.secret\" value=\"" + SECRET + "\"/></Context>");
        jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        controller = new DeviceEnrollmentController(jdbc, rootXml.toString());
    }

    @Test
    void firstDeviceClaimsIdAndSameDeviceCanCheckInAgain() throws Exception {
        String fingerprint = fingerprint("android-id-device-one");
        when(jdbc.queryForObject(any(String.class), eq(String.class), eq(VEHICLE))).thenReturn(fingerprint);

        ResponseEntity<DeviceEnrollmentController.ClaimResponse> response = controller.claim(request(fingerprint));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("CLAIMED", response.getBody().code());
        verify(jdbc).update(eq("""
                INSERT INTO android_device_identity_lock(vehicle_number, identity_sha256)
                VALUES (?, ?)
                ON CONFLICT (vehicle_number) DO NOTHING
                """), eq(VEHICLE), eq(fingerprint));
        verify(jdbc).update("UPDATE android_device_identity_lock SET last_seen_at = CURRENT_TIMESTAMP WHERE vehicle_number = ?", VEHICLE);
    }

    @Test
    void differentDeviceCannotReuseClaimedVehicleId() throws Exception {
        String registeredFingerprint = fingerprint("android-id-device-one");
        String secondFingerprint = fingerprint("android-id-device-two");
        when(jdbc.queryForObject(any(String.class), eq(String.class), eq(VEHICLE))).thenReturn(registeredFingerprint);

        ResponseEntity<DeviceEnrollmentController.ClaimResponse> response = controller.claim(request(secondFingerprint));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("DEVICE_ID_ALREADY_BOUND_TO_ANOTHER_DEVICE", response.getBody().code());
    }

    @Test
    void invalidSignatureIsRejectedBeforeDatabaseClaim() throws Exception {
        String fingerprint = fingerprint("android-id-device-one");
        ResponseEntity<DeviceEnrollmentController.ClaimResponse> response = controller.claim(
                new DeviceEnrollmentController.ClaimRequest(VEHICLE, fingerprint, "bad-signature"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(jdbc, never()).update(eq("""
                INSERT INTO android_device_identity_lock(vehicle_number, identity_sha256)
                VALUES (?, ?)
                ON CONFLICT (vehicle_number) DO NOTHING
                """), any(), any());
    }

    private static DeviceEnrollmentController.ClaimRequest request(String fingerprint) throws Exception {
        String signature = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest((SECRET + VEHICLE + ":" + fingerprint).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new DeviceEnrollmentController.ClaimRequest(VEHICLE, fingerprint, signature);
    }

    private static String fingerprint(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
