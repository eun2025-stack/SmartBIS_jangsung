# SmartBIS Headwind Agent custom build (development)

## Build result

- Upstream source: `h-mdm/hmdm-android`, tag `v6.39`, commit `7a26a16e913ef7976dfc5c991dbc9253fe3bce12`
- Package/version: `com.hmdm.launcher` / `6.39` (version code `15390`)
- Development server default: `https://192.168.1.5:8443` (current Wi-Fi test endpoint)
- SmartBIS development Root CA is bundled as an app trust anchor. The agent does not require a manual CA import for its own HTTPS connections.
- Build variant: `opensourceDebug`; it is debug-signed and is for the isolated test device only.
- Enrollment ID policy: `BIS_JS_` followed by at least three digits. Changing an ID clears the previous cached MDM configuration; if the new ID is unknown, the agent sends the enrollment request rather than repeatedly querying the old registration.
- Automatic first-registration ID: when no device ID is configured, the agent obtains `BIS_JS_001`, `BIS_JS_002`, … from the custom Headwind server using the Android installation identity. The server returns the same ID on retry/reinstall for the same identity. Before assigning a new number it skips IDs already present in HMDM devices (`number`/`oldNumber`), existing physical-identity locks, and prior automatic assignments. Existing identity locks take precedence so the same Android installation keeps its already-claimed ID.
- The auto-assignment endpoint is authenticated with the existing HMDM `hash.secret` request-signature scheme. The SHA-256 Android identity is not logged; the allocation mapping is stored in the HMDM database. This number identifies the managed terminal only; the vehicle number is assigned separately in SmartBIS after enrollment.
- The custom WAR adds Liquibase changeset `smartbis-device-number-allocation-v1` (sequence plus assignment table). It must be deployed before the new APK can auto-assign IDs. On first startup Liquibase creates these objects in the HMDM database; the existing HMDM records are not deleted or renumbered.
- New enrollments receive the `Common - Minimal` configuration. Headwind's enrollment API expects the configuration's QR enrollment key, not its display name. The current key is kept only in `deployment/headwind-mdm/.env` as `HMDM_ENROLLMENT_CONFIG_KEY`; the build injects it into the APK without printing it. `build-custom-agent.sh` now requires this value. Enable Headwind's `Create new devices` setting using `sql/enable-smartbis-auto-enrollment.sql` after backing up the HMDM database. Nginx blocks enrollment POSTs whose ID does not match the policy.
- Before each Headwind configuration GET/POST, the agent claims the vehicle ID against the Android `ANDROID_ID` fingerprint in the SmartBIS database. First device wins atomically; another physical device reusing the ID receives HTTP 409 and does not reach HMDM enrollment. The backend reads the existing HMDM `hash.secret` from the read-only mounted `ROOT.xml` and verifies the claim signature; the secret and raw Android ID are not stored or logged.
- Build command: `bash deployment/headwind-mdm/build-custom-agent.sh https://192.168.1.5:8443`
- Result: `hmdm-android-v6.39/app/build/outputs/apk/opensource/debug/app-opensource-debug.apk`
- Build result on 2026-10-09: `BUILD SUCCESSFUL`.
- Auto-ID enrollment fix build (2026-10-12): `releases/20261012-server-192.168.1.5/hmdm-agent-6.39-auto-id-enrollment-20261012-debug.apk`; SHA-256 `d6d0429e222a89c4e5e0d9d92e3fb295a8e368b27aee7232d4a960f18386cf7b`. Gradle `assembleOpensourceDebug` succeeded with JDK 17. The build fixes both the first-start flow (run server ID allocation instead of showing a manual ID prompt) and the enrollment configuration value (QR key, not display name).
- Matching custom server build (2026-10-11): `releases/hmdm-server-5.41.1-smartbis-20261011-143508.war`; SHA-256 `2237fec4c54960ab862d193da4e04b167a60808ae555503a88dd3eb16ae09dcd`. Maven reactor `BUILD SUCCESS`; unit tests were skipped by the existing build script. The WAR contains deployment configuration and must be treated as secret.

## Important limits

This custom APK preconfigures the server URL and trusts the bundled CA. It does **not** by itself enroll a device or grant Android Device Owner. The explicit provisioning helper is `provision-test-device.sh`; it requires the exact ADB serial to be typed twice, never wipes a device, installs the APK, attempts Device Owner once, and verifies the resulting policy state. It cannot bypass Android's fresh-device/managed-provisioning requirement. For repeated factory enrollment without technician ADB, the OEM must support Android Enterprise zero-touch/QR or pre-provision the DPC.

The APK uses a debug signing key, so Android will reject it as an in-place update over the official Headwind-signed APK. Install it only on a factory-reset test device. Do not assign this debug APK as an update to other enrolled devices.

Automatic enrollment and duplicate-physical-device protection are server-and-client features: building the APK alone does not enable them. Deploy the matching custom WAR, keep Headwind's `Create new devices` setting enabled with `Common - Minimal` as the default configuration, set `HMDM_ENROLLMENT_CONFIG_KEY` to that configuration's QR key in the local `.env`, ensure the SmartBIS backend's `ROOT.xml` read-only mount exists, rebuild/recreate the backend, and recreate/reload Nginx so the enrollment filter is active. On the first successful claim of an ID, the physical Android installation becomes its owner; replacing or factory-resetting that device requires an administrator-controlled identity-lock reset/reassignment. Do not start both devices with the same ID during initial rollout: whichever valid device claims first is pinned.

### Auto-ID enrollment test on 2026-10-12

The connected test device already had Device Owner set. The first custom build successfully allocated `BIS_JS_003`, but Headwind rejected device creation because the APK sent the visible configuration name `Common - Minimal` where the API requires its QR enrollment key. The launcher also showed a manual ID dialog instead of entering the auto-allocation flow when the ID was blank. Both issues are fixed in the release above.

The corrected APK was installed in place without clearing app data. Headwind logged `New device BIS_JS_003 added`; the Agent logged `Configuration updated` and `Device owner: true`. Thus ID allocation and enrollment into `Common - Minimal` are confirmed on this physical test device. The QR key is stored in the local `.env` and must not be copied into public documentation or logs.

Remaining fleet test: factory-reset/provision the second physical test device through the agreed Device Owner method, install the same corrected Agent, and confirm it receives a different next-free ID. Reboot both and verify their IDs remain stable; assign vehicle numbers separately in SmartBIS.

Do not install the debug APK over an official-signed Agent on the current test device; Android signature mismatch may prevent in-place replacement. The first end-to-end test should use a wiped test unit and the normal provisioning/deployment route, not manual APK sideloading as proof of fleet automation.

For production, rebuild against the production HTTPS hostname/IP and CA, sign with a persistent protected release key, and register that exact APK/version in Headwind. Keep the signing key backed up securely: later updates must use the same key. Do not use `TRUST_ANY_CERTIFICATE` as a substitute for a production CA.

## Rebuilding

Use JDK 17, Gradle Wrapper 8.13, and the Android SDK. This PC currently resolves Java 25; Gradle 8.13 cannot run on Java 25 (the error is `Unsupported class file major version 69`). Set `JAVA_HOME` and `PATH` to JDK 17 before building. The script now checks the Java major version and stops before Gradle if it is not 17. From the project root run:

```bash
bash deployment/headwind-mdm/build-custom-agent.sh https://192.168.100.50:8443
```

Windows install option: `winget install --id EclipseAdoptium.Temurin.17.JDK -e`. After installation, restart Git Bash and confirm `java -version` reports 17. If another Java remains first on PATH, set `JAVA_HOME` to the installed JDK 17 directory and prepend its `bin` directory to PATH in Git Bash.

For production, pass its exact HTTPS endpoint. Replace `app/src/main/res/raw/smartbis_root_ca.crt` with the production trust chain before building; never rely on `TRUST_ANY_CERTIFICATE`. The script prints the artifact path and SHA-256 for the release record.
