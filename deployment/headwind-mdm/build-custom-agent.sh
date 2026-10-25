#!/usr/bin/env bash
set -Eeuo pipefail

# Reproducibly build the Headwind 6.39 opensource debug APK for one explicit server URL.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR/hmdm-android-v6.39"
SERVER_URL="${1:-https://192.168.1.5:8443}"
if [[ -z "${HMDM_ENROLLMENT_CONFIG_KEY:-}" && -f "$SCRIPT_DIR/.env" ]]; then
  HMDM_ENROLLMENT_CONFIG_KEY="$(sed -n 's/^HMDM_ENROLLMENT_CONFIG_KEY=//p' "$SCRIPT_DIR/.env" | tail -n 1 | tr -d '\r' | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")"
fi

[[ "$SERVER_URL" =~ ^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]] || {
  echo 'Usage: bash deployment/headwind-mdm/build-custom-agent.sh https://HOST[:PORT]' >&2
  exit 2
}
[[ -s "$ANDROID_DIR/app/src/main/res/raw/smartbis_root_ca.crt" ]] || {
  echo 'Bundled CA is missing: app/src/main/res/raw/smartbis_root_ca.crt' >&2
  exit 1
}
[[ -n "${HMDM_ENROLLMENT_CONFIG_KEY:-}" ]] || {
  echo 'Set HMDM_ENROLLMENT_CONFIG_KEY in deployment/headwind-mdm/.env to the target Headwind configuration QR key.' >&2
  exit 1
}
JAVA_BIN="$(command -v java 2>/dev/null || true)"
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_HOME_FOR_BASH="$JAVA_HOME"
  command -v cygpath >/dev/null 2>&1 && JAVA_HOME_FOR_BASH="$(cygpath -u "$JAVA_HOME")"
  JAVA_BIN="$JAVA_HOME_FOR_BASH/bin/java"
fi
[[ -x "$JAVA_BIN" ]] || { echo 'JDK is unavailable. Install JDK 17 and set JAVA_HOME/PATH.' >&2; exit 1; }
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
[[ "$JAVA_MAJOR" == 17 ]] || {
  echo "This Headwind Gradle 8.13 build requires JDK 17; selected Java major=${JAVA_MAJOR:-unknown}. Set JAVA_HOME to JDK 17." >&2
  exit 1
}

cd "$ANDROID_DIR"
APK="$ANDROID_DIR/app/build/outputs/apk/opensource/debug/app-opensource-debug.apk"
RELEASE_DIR="$SCRIPT_DIR/releases"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RELEASE_DIR"
if [[ -s "$APK" ]]; then
  cp -p "$APK" "$RELEASE_DIR/hmdm-agent-previous-$STAMP-6.39-debug.apk"
  echo "Previous APK preserved: $RELEASE_DIR/hmdm-agent-previous-$STAMP-6.39-debug.apk"
fi
./gradlew assembleOpensourceDebug --no-daemon "-PsmartbisServerUrl=$SERVER_URL" "-PhmdmEnrollmentConfigKey=$HMDM_ENROLLMENT_CONFIG_KEY"

[[ -s "$APK" ]] || { echo 'Build reported success but APK is missing.' >&2; exit 1; }
cp -p "$APK" "$RELEASE_DIR/hmdm-agent-6.39-$STAMP-debug.apk"
printf '\nBuild artifact: %s\n' "$APK"
printf 'Rollback copy: %s\n' "$RELEASE_DIR/hmdm-agent-previous-$STAMP-6.39-debug.apk"
printf 'Versioned build: %s\n' "$RELEASE_DIR/hmdm-agent-6.39-$STAMP-debug.apk"
printf 'Configured server: %s\n' "$SERVER_URL"
sha256sum "$APK"
echo '주의: debug 서명 APK입니다. 이미 공식 서명 Agent가 설치된 기기의 인플레이스 업데이트용이 아닙니다.'
