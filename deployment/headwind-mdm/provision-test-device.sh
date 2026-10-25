#!/usr/bin/env bash
set -Eeuo pipefail

# Install the custom Agent and set Device Owner on one explicitly confirmed ADB target.
# Never wipes the device and never chooses a target automatically.
if [[ $# -ne 2 ]]; then
  echo 'Usage: bash deployment/headwind-mdm/provision-test-device.sh <adb-serial> <agent.apk>' >&2
  exit 2
fi
SERIAL="$1"
APK="$2"
[[ -s "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }
command -v adb >/dev/null 2>&1 || { echo 'adb is not available on PATH.' >&2; exit 1; }

STATE="$(adb -s "$SERIAL" get-state 2>/dev/null || true)"
[[ "$STATE" == device ]] || { echo "Target $SERIAL is not connected/authorized (state: ${STATE:-missing}). No changes made." >&2; exit 1; }
MODEL="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
echo "Target serial: $SERIAL"
echo "Target model:  $MODEL"
read -r -p '위 단말만 시험 대상으로 확인했으면 serial을 다시 입력하세요: ' CONFIRM
[[ "$CONFIRM" == "$SERIAL" ]] || { echo 'Serial confirmation mismatch. No changes made.' >&2; exit 1; }

echo '[1/3] Installing Agent APK...'
adb -s "$SERIAL" install -r "$APK"

echo '[2/3] Setting Headwind as Device Owner...'
if ! adb -s "$SERIAL" shell dpm set-device-owner com.hmdm.launcher/.AdminReceiver; then
  echo 'Device Owner 설정 실패. 기기를 초기화하지 않았습니다.' >&2
  echo 'Agent APK는 진단을 위해 남겨 두었습니다. 기기 상태를 확인한 뒤 별도 복구 절차를 따르세요.' >&2
  exit 1
fi

echo '[3/3] Verifying Device Owner...'
POLICY="$(adb -s "$SERIAL" shell dumpsys device_policy)"
if ! grep -q 'com\.hmdm\.launcher/com\.hmdm\.launcher\.AdminReceiver' <<< "$POLICY"; then
  echo 'Device Owner 확인 실패. 자동 초기화/제거는 수행하지 않았습니다.' >&2
  exit 1
fi
echo 'Device Owner confirmed. 이제 Headwind 웹에서 단말 체크인과 SmartBIS 배포를 확인하세요.'
echo '서버 체크인은 MDM_HOST:8443, MQTT는 MDM_HOST:31000에서 별도로 확인합니다.'
