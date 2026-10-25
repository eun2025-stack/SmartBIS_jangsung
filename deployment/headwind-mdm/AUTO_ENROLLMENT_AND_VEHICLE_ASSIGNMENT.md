# SmartBIS terminal auto-enrollment and vehicle assignment

Last updated: 2026-10-13

## Intended field flow

1. Install the matching custom Headwind Agent APK on a factory-reset test terminal.
2. Grant Device Owner to `com.hmdm.launcher/.AdminReceiver` using the supported Android Enterprise provisioning method (ADB is currently the test method on these units).
3. When Device Owner activates, the Agent opens its enrollment screen. It requests the next free terminal ID from the custom Headwind server (`BIS_JS_001`, `BIS_JS_002`, ...), binds that ID to the Android installation fingerprint, and enrolls into `Common - Minimal`.
4. Headwind distributes the two SmartBIS apps assigned to `Common - Minimal`: SmartBIS Client and Vehicle Number Selector. The packages and configuration assignment must exist in Headwind Applications before the test; building APKs does not upload them to Headwind.
5. In Vehicle Number Selector, search by part of the vehicle number (for example `12`), select the actual vehicle, and save. The SmartBIS backend binds that vehicle to the enrolled terminal ID. A vehicle already assigned to another terminal is rejected. If the wrong vehicle was selected, use **현재 차량 배정 취소** in this app; it removes the current mapping while retaining terminal identity and historical operations data.
6. SmartBIS Client reads the assignment from the backend whenever it starts. It displays only after the server returns both the enrolled terminal ID and its vehicle assignment. The local preference is not authoritative.

The MDM terminal ID identifies the physical Android installation; it is not the vehicle registration. Factory-reset/replacement handling must be an administrator operation because Android's installation ID changes after a reset and the identity lock deliberately prevents silent reassignment.

## Current development build

Release directory: `deployment/headwind-mdm/releases/20261012-auto-enrollment-vehicle-mapping/`

| Artifact | Package / version | Purpose |
|---|---|---|
| `hmdm-agent-6.39-auto-enrollment-debug.apk` | `com.hmdm.launcher` / 6.39 | Uses the current test endpoint, auto-allocates the next `BIS_JS_NNN`, claims the physical identity, registers in `Common - Minimal`, and launches the enrollment UI after Device Owner activation. |
| `smartbis-client-1.4-code5-debug.apk` | `com.smartbis.client` / 1.4 (code 5) | Resolves terminal-to-vehicle assignment from the backend at startup and uses that vehicle for display/arrival calls. |
| `vehicle-number-1.6-code7-debug.apk` | `com.smartbis.vehiclenumber` / 1.6 (code 7) | Loads active vehicle numbers, saves an assignment, and allows the current assignment to be explicitly canceled. |
| `smartbis-backend.jar` | SmartBIS backend 0.0.1-SNAPSHOT | Provides the terminal/vehicle assignment APIs and administrator inventory endpoint. |

The Android builds use `https://192.168.1.5:8443/api`; the Agent includes the development Root CA. These are debug-signed test builds, not production releases. A debug APK cannot update an official-signed app. Install on the intended test units only; use a consistent release signing key and the production server/CA for production.

The custom Headwind WAR was not changed by this build. The existing 5.41.1 custom WAR must remain deployed because it provides server-side ID allocation/enrollment. The SmartBIS backend image must be rebuilt/recreated for the new mapping API and dashboard table.

## Backend contract and data ownership

- `GET /api/v1/device-vehicle-assignment/current?identitySha256=<sha256>`: resolves the authenticated-by-possession installation fingerprint through the existing physical identity lock and returns `{deviceNumber, vehicleNumber}`; `vehicleNumber` is `null` until selected.
- `GET /api/v1/vehicles` (optionally `?q=<partial-number>`): lists up to 1,000 active entries in SmartBIS `bus_vehicles`; the app loads the list on launch and uses a partial number to narrow it. This table is populated by TAGO vehicle-location observations, not a separate carrier-maintained master roster.
- `POST /api/v1/device-vehicle-assignment` with JSON `{ "identitySha256": "<sha256>", "vehicleNumber": "<active TAGO vehicle number>" }`: saves or changes the assignment for that registered terminal. A vehicle can belong to only one terminal at a time.
- `DELETE /api/v1/device-vehicle-assignment?identitySha256=<sha256>`: cancels only the current terminal-to-vehicle mapping and returns `{ "deviceNumber": "BIS_JS_NNN", "vehicleNumber": null }`. The terminal identity lock and historical `bus_positions`/arrival data remain intact. The released vehicle may then be assigned to another terminal.
- `GET /api/v1/admin/device-vehicle-assignments`: administrator-only inventory used by the dashboard. Registered terminals without a vehicle are shown as unassigned.
- SmartBIS DB table `android_device_vehicle_assignments` stores the mapping. It references the existing `android_device_identity_lock`; the Headwind database and SmartBIS content/catalog data are not reset by this feature.
- Schema migration: `server/database/migrations/V014__android_device_vehicle_assignments.sql`. The backend also creates the table idempotently at startup for deployments that do not run the separate SQL migration runner.

## Test acceptance checklist

Use only one freshly reset test terminal for the first pass. Verify the complete sequence before enrolling the second unit:

1. Confirm the custom Headwind 5.41.1 WAR is live and HMDM's `Create new devices` / default `Common - Minimal` settings are enabled.
2. In Headwind, register the current Client and Vehicle Number APKs and assign both to `Common - Minimal` with Vehicle Number Selector run after install. (No automatic server-side upload is performed by this build.)
3. Install `hmdm-agent-6.39-auto-enrollment-debug.apk`, set Device Owner, and verify the first clean installation is `BIS_JS_001` in Headwind and in its logs.
4. Verify both SmartBIS packages arrive from Headwind without manually sideloading them. If they do not, inspect the Headwind Installation Status and application package/version/signing configuration.
5. Search/select the real vehicle number in Vehicle Number Selector. Confirm the success message names `BIS_JS_001` and the selected vehicle.
6. Confirm **현재 차량 배정 취소**, verify it reports the terminal as unassigned, and verify the released vehicle can be selected on another terminal. Reassign the intended vehicle for the first test.
7. In the authenticated SmartBIS dashboard's display-monitor section, verify the same ID/vehicle pair. Restart SmartBIS Client and confirm the display uses that vehicle from the backend mapping.
8. Only after step 7 passes, reset/enroll the second test unit. It must receive the next free ID and a distinct vehicle assignment.

The code and APKs are built; actual installation, Device Owner activation, Headwind distribution, and a live vehicle-number assignment still require the physical-terminal acceptance steps above. No production device or SmartBIS DB data was changed by the build.

