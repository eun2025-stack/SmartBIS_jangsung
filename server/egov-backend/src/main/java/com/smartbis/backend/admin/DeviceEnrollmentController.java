package com.smartbis.backend.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Element;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;

/** Claims a SmartBIS vehicle ID for one Android installation before HMDM enrollment. */
@RestController
@RequestMapping("/v1/device-enrollment")
public class DeviceEnrollmentController {
    private static final Pattern VEHICLE_ID = Pattern.compile("BIS_JS_[0-9]{3,}");
    private static final Pattern SHA256 = Pattern.compile("[a-fA-F0-9]{64}");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DeviceEnrollmentController.class);

    private final JdbcTemplate jdbc;
    private final String rootConfigPath;

    public DeviceEnrollmentController(
            JdbcTemplate jdbc,
            @Value("${smartbis.device-identity.hmdm-root-config:/run/hmdm/ROOT.xml}") String rootConfigPath) {
        this.jdbc = jdbc;
        this.rootConfigPath = rootConfigPath;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS android_device_identity_lock (
                    vehicle_number VARCHAR(50) PRIMARY KEY,
                    identity_sha256 CHAR(64) NOT NULL,
                    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    @PostMapping("/claim")
    public ResponseEntity<ClaimResponse> claim(@RequestBody ClaimRequest request) {
        if (request == null || request.deviceNumber() == null ||
                !VEHICLE_ID.matcher(request.deviceNumber()).matches() ||
                request.identitySha256() == null || !SHA256.matcher(request.identitySha256()).matches() ||
                request.signature() == null || request.signature().isBlank()) {
            return ResponseEntity.badRequest().body(new ClaimResponse(false, "INVALID_REQUEST"));
        }

        final String secret;
        try {
            secret = readHmdmHashSecret();
        } catch (Exception e) {
            log.error("Device identity verification unavailable: HMDM signing secret could not be loaded");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ClaimResponse(false, "IDENTITY_VERIFICATION_UNAVAILABLE"));
        }

        String expected = sha1(secret + request.deviceNumber() + ":" + request.identitySha256().toLowerCase());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                request.signature().toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ClaimResponse(false, "INVALID_SIGNATURE"));
        }

        String fingerprint = request.identitySha256().toLowerCase();
        jdbc.update("""
                INSERT INTO android_device_identity_lock(vehicle_number, identity_sha256)
                VALUES (?, ?)
                ON CONFLICT (vehicle_number) DO NOTHING
                """, request.deviceNumber(), fingerprint);

        String registered = jdbc.queryForObject(
                "SELECT identity_sha256 FROM android_device_identity_lock WHERE vehicle_number = ?",
                String.class, request.deviceNumber());
        if (!MessageDigest.isEqual(registered.trim().getBytes(StandardCharsets.US_ASCII),
                fingerprint.getBytes(StandardCharsets.US_ASCII))) {
            log.warn("Device ID reuse rejected: deviceNumber={} (fingerprints withheld)", request.deviceNumber());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ClaimResponse(false, "DEVICE_ID_ALREADY_BOUND_TO_ANOTHER_DEVICE"));
        }

        jdbc.update("UPDATE android_device_identity_lock SET last_seen_at = CURRENT_TIMESTAMP WHERE vehicle_number = ?",
                request.deviceNumber());
        return ResponseEntity.ok(new ClaimResponse(true, "CLAIMED"));
    }

    private String readHmdmHashSecret() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element root = factory.newDocumentBuilder().parse(new java.io.File(rootConfigPath)).getDocumentElement();
        var parameters = root.getElementsByTagName("Parameter");
        for (int i = 0; i < parameters.getLength(); i++) {
            Element parameter = (Element) parameters.item(i);
            if ("hash.secret".equals(parameter.getAttribute("name"))) {
                String value = parameter.getAttribute("value");
                if (!value.isBlank()) return value;
            }
        }
        throw new IllegalStateException("HMDM hash.secret missing");
    }

    private static String sha1(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    public record ClaimRequest(
            @JsonProperty("deviceNumber") @JsonAlias("vehicleNumber") String deviceNumber,
            @JsonProperty("identitySha256") String identitySha256,
            @JsonProperty("signature") String signature) {}

    public record ClaimResponse(boolean accepted, String code) {}
}
