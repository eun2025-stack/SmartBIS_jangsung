#!/usr/bin/env bash
set -Eeuo pipefail

# Build one SmartBIS client APK for a declared backend API base URL.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
API_BASE_URL="${1:-https://192.168.100.50:8443/api}"
VERSION_CODE="${2:-2}"
VERSION_NAME="${3:-1.1}"

[[ "$API_BASE_URL" =~ ^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/api$ ]] || {
  echo 'Usage: bash client-android/build-smartbis-apk.sh https://HOST[:PORT]/api' >&2
  exit 2
}
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || {
  echo 'Version code must be a positive integer greater than the installed app version.' >&2
  exit 2
}
[[ -s "$SCRIPT_DIR/app/src/main/res/raw/smartbis_root_ca.crt" ]] || {
  echo 'Bundled CA is missing: app/src/main/res/raw/smartbis_root_ca.crt' >&2
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
  echo "SmartBIS builds are standardized on JDK 17; selected Java major=${JAVA_MAJOR:-unknown}. Set JAVA_HOME to JDK 17." >&2
  exit 1
}

cd "$SCRIPT_DIR"
APK="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_DIR="$SCRIPT_DIR/releases"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RELEASE_DIR"
if [[ -s "$APK" ]]; then
  cp -p "$APK" "$RELEASE_DIR/smartbis-client-previous-$STAMP-debug.apk"
  echo "Previous APK preserved: $RELEASE_DIR/smartbis-client-previous-$STAMP-debug.apk"
fi
./gradlew assembleDebug --no-daemon \
  "-PsmartbisApiBaseUrl=$API_BASE_URL" \
  "-PsmartbisVersionCode=$VERSION_CODE" \
  "-PsmartbisVersionName=$VERSION_NAME"
[[ -s "$APK" ]] || { echo 'Build reported success but APK is missing.' >&2; exit 1; }
cp -p "$APK" "$RELEASE_DIR/smartbis-client-$VERSION_NAME-code$VERSION_CODE-$STAMP-debug.apk"
printf '\nBuild artifact: %s\n' "$APK"
printf 'Rollback copy: %s\n' "$RELEASE_DIR/smartbis-client-previous-$STAMP-debug.apk"
printf 'Versioned build: %s\n' "$RELEASE_DIR/smartbis-client-$VERSION_NAME-code$VERSION_CODE-$STAMP-debug.apk"
printf 'Configured API: %s\n' "$API_BASE_URL"
sha256sum "$APK"
echo '주의: debug 서명 APK입니다. 동일 signing key를 유지해야 기존 설치본을 인플레이스 업데이트/롤백할 수 있습니다.'
