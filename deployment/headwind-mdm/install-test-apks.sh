#!/usr/bin/env bash
set -Eeuo pipefail

# Canary-only helper: install the custom Headwind Agent, confirm Device Owner,
# then install the SmartBIS client. Never wipes data or uninstalls packages.
if [[ $# -ne 3 ]]; then
  echo 'Usage: bash deployment/headwind-mdm/install-test-apks.sh <adb-serial> <agent.apk> <smartbis-client.apk>' >&2
  exit 2
fi

SERIAL="$1"
AGENT_APK="$2"
CLIENT_APK="$3"
for apk in "$AGENT_APK" "$CLIENT_APK"; do
  [[ -s "$apk" ]] || { echo "APK missing or empty: $apk" >&2; exit 1; }
done
command -v adb >/dev/null 2>&1 || { echo 'adb is not available on PATH.' >&2; exit 1; }

STATE="$(adb -s "$SERIAL" get-state 2>/dev/null || true)"
[[ "$STATE" == device ]] || { echo "Target $SERIAL is not connected/authorized (state: ${STATE:-missing}). No changes made." >&2; exit 1; }
MODEL="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
ANDROID_VERSION="$(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
printf 'Test target: %s | model=%s | Android=%s\n' "$SERIAL" "$MODEL" "$ANDROID_VERSION"
read -r -p 'Canary device serial을 다시 입력하세요 (다른 기기는 변경하지 않음): ' CONFIRM
[[ "$CONFIRM" == "$SERIAL" ]] || { echo 'Serial confirmation mismatch. No changes made.' >&2; exit 1; }

POLICY="$(adb -s "$SERIAL" shell dumpsys device_policy)"
if grep -q 'Device Owner:' <<< "$POLICY" && ! grep -q 'com\.hmdm\.launcher/com\.hmdm\.launcher\.AdminReceiver' <<< "$POLICY"; then
  echo 'A different Device Owner already exists. Refusing to replace it.' >&2
  exit 1
fi

echo '[1/4] Install/update custom Headwind Agent (signature conflicts are not auto-uninstalled).'
adb -s "$SERIAL" install -r "$AGENT_APK"

if ! grep -q 'com\.hmdm\.launcher/com\.hmdm\.launcher\.AdminReceiver' <<< "$POLICY"; then
  echo '[2/4] Set Headwind Agent as Device Owner.'
  adb -s "$SERIAL" shell dpm set-device-owner com.hmdm.launcher/.AdminReceiver
else
  echo '[2/4] Headwind is already Device Owner.'
fi

POLICY="$(adb -s "$SERIAL" shell dumpsys device_policy)"
grep -q 'com\.hmdm\.launcher/com\.hmdm\.launcher\.AdminReceiver' <<< "$POLICY" || {
  echo 'Device Owner verification failed. No factory reset or uninstall was attempted.' >&2
  exit 1
}
echo '[3/4] Device Owner verified.'

echo '[4/4] Install/update SmartBIS client.'
adb -s "$SERIAL" install -r "$CLIENT_APK"

echo 'Local install complete. Next verify on device: launch SmartBIS, API connection, content playback, reboot auto-start.'
echo 'Separately verify Headwind device check-in and remote push; local APK install does not prove MDM delivery.'
