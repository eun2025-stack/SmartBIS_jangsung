# Headwind MDM 기기 등록·운영 절차 (시험 기록)

## 2026-10-12 자동 ID 등록 실기기 검증 (최신)

- 실기기에서 번호 자동 발급은 동작해 `BIS_JS_003`이 배정됐으나, 최초 등록은 설정 선택값 오류로 실패했다.
- 원인: APK가 Headwind API의 configuration 필드에 표시명 `Common - Minimal`을 전달했다. 서버는 이 필드에서 해당 설정의 QR 등록 키를 조회하므로 기기를 생성하지 못했다. Agent 시작 화면도 빈 ID일 때 서버 자동발급 대신 수동 ID 입력창을 표시하는 별도 분기 오류가 있었다.
- 수정: 빈 ID 시작 분기는 자동발급/등록 작업을 바로 시작한다. 빌드 설정은 QR 등록 키를 `deployment/headwind-mdm/.env`의 `HMDM_ENROLLMENT_CONFIG_KEY`에서 읽도록 변경했다. 비밀 키 자체는 문서에 기록하지 않는다.
- 수정 APK: `releases/20261012-server-192.168.1.5/hmdm-agent-6.39-auto-id-enrollment-20261012-debug.apk` (SHA-256 `d6d0429e222a89c4e5e0d9d92e3fb295a8e368b27aee7232d4a960f18386cf7b`).
- 실기기에는 앱 데이터와 Device Owner를 유지한 채 `adb install -r`로 업데이트했다. Headwind 서버에 `New device BIS_JS_003 added`, Agent 로그에 `Configuration updated`, `Device owner: true`가 남아 신규 등록까지 확인됐다.
- 다음 단계는 두 번째 물리 기기의 신규 ID 발급 및 등록, 재부팅 후 각 ID 유지 확인이다. 차량번호는 등록 이후 SmartBIS에서 별도로 설정한다.

## 2026-10-11 자동 단말 ID 배정 구현

- 요청: 신규 단말은 `BIS_JS_001`, `BIS_JS_002`, `BIS_JS_003` … 형태로 자동 명명하고 Headwind 등록까지 완료한다. 이 값은 차량번호가 아니라 MDM 단말 ID다. 차량번호는 등록 후 SmartBIS에서 별도 지정한다.
- 구현: 커스텀 Agent가 초기 설정 시 Android 설치 식별값의 SHA-256을 서명 요청에 담아 Headwind 서버의 `/rest/public/sync/allocate`에 요청한다. 서버는 기존 HMDM ID, 과거 ID, 물리기기 잠금 및 과거 자동배정 값을 건너뛰어 번호를 배정하고, Agent는 반환된 ID로 기존 체크인/등록 절차를 이어간다.
- 같은 설치 식별값의 재요청에는 기존 배정/잠금 ID를 반환해 재부팅·재시도에서 ID가 바뀌지 않도록 한다. ID 중복은 DB 제약으로 막는다. 신규 번호는 기존 기기 ID를 덮어쓰거나 기존 행을 삭제하지 않는다.
- 새 Headwind Liquibase 변경은 `smartbisDeviceNumberSequence`와 `smartbisDeviceNumberAssignments`를 생성한다. HMDM DB 마이그레이션은 커스텀 WAR 배포 후 Headwind 시작 시 실행된다.
- 차량번호와 MDM ID는 서로 다른 필드/목적이다. 설치 시 차량번호를 몰라도 단말을 먼저 등록할 수 있다.
- 2026-10-11 빌드 결과: Agent `releases/hmdm-agent-6.39-auto-id-20261011-debug.apk`, SHA-256 `47A32EF581B58D3193CE8F41262B357EEDA0109B3C406D57DDE526B3FE813069`. Headwind WAR `releases/hmdm-server-5.41.1-smartbis-20261011-143508.war`, SHA-256 `2237fec4c54960ab862d193da4e04b167a60808ae555503a88dd3eb16ae09dcd`. Agent Gradle 빌드 및 서버 Maven 빌드는 성공했다. 서버 WAR 빌드 스크립트에서 테스트를 생략했다.
- 미완료: 현재 구동 중인 Headwind 서버에는 아직 새 WAR가 배포되지 않았고, 실기기 초기화부터 자동 배정·체크인까지의 종단간 검증도 아직 하지 않았다. 기존 두 테스트 기기에는 같은 번호/물리기기 소유권 기록이 있으므로 운영 기록을 자동 재배정하지 않는다.
- 다음 시험: HMDM DB와 기존 WAR 백업 → 새 WAR 배포 및 Liquibase 완료 확인 → `Create new devices` 활성 및 기본 구성 `Common - Minimal` 확인 → 초기화한 시험기 1대의 정식 Device Owner 프로비저닝 → Agent 첫 실행에서 번호 자동 배정과 Headwind 등록 확인 → 두 번째 단말에서 다음 고유 번호 확인 → 재부팅 후 동일 ID 유지 확인 → SmartBIS에서 차량번호 별도 지정.
- 현재 산출물은 `opensourceDebug` 서명본이다. 기존 공식 서명본의 인플레이스 업데이트로 배포하면 서명 불일치가 날 수 있다. 또한 Android Enterprise Device Owner 프로비저닝을 우회하지 않는다. 실제 배포는 기존 단말에 파일을 수동 복사·설치하는 방식이 아니라, 초기화 시험기에서 계획한 프로비저닝 경로로 검증한다.
- 서버 WAR에는 배포 설정이 포함되므로 비밀자료로 취급하고 공개 저장소/외부 공유를 금지한다. 테스트는 기존 HMDM/SmartBIS DB 및 운영 기기를 삭제·초기화하지 않고 격리 시험기에서 수행한다.

- 기준일: 2026-10-08
- 목적: Headwind 서버 최초 구축부터 Android 11/RK3566 BIT의 등록, Device Owner 확인, SmartBIS 배포까지 재현 가능한 절차를 남기고 지자체 인수인계 매뉴얼의 근거로 사용한다.
- 원칙: 실제 사용자 실행 결과와 계획을 구분한다. 비밀번호, DB 비밀번호, 공유 비밀값, 개인키, QR 등록 토큰은 기록하지 않는다.

## 2026-10-10 단말 ID 충돌 원인 및 자동 등록 반영

- 원인 확인: 커스텀 Agent의 `DEVICE_ID_CHOICE`가 `user`여서 단말 ID는 기기별 하드웨어 키가 아니라 Agent 로컬 설정에 저장되는 입력값이다. 기존 서버 설정이 저장된 상태에서 ID만 바꾸면 Agent는 등록 `POST`가 아니라 설정 조회 `GET`을 보내므로, 서버에 없는 ID는 체크인되지 않았다.
- HMDM DB에서 `settings.createnewdevices=false`였고 `devices.number`는 고유 제약이었다. 따라서 당시 서버는 신규 ID를 자동 생성하지 않았다. 같은 ID가 두 기기에 입력되면 두 기기가 한 `devices.number` 레코드를 공유할 수 있으며, IP나 암호화 키가 두 기기를 묶은 것이 아니다.
- 커스텀 Agent 수정: 허용 형식은 `BIS_JS_` + 숫자 3자리 이상. 형식이 맞지 않으면 등록 전에 거부한다. 기기 ID를 변경하면 캐시 설정을 지워 새 등록 요청을 보내고, 기존 캐시 상태에서 미등록 ID 조회가 404여도 등록 요청으로 재시도한다. 신규 기기 기본 구성은 `Common - Minimal`이다.
- Nginx 수정: 잘못된 ID 형식의 신규 등록 POST는 HTTP 403으로 차단한다. 실행 중 컨테이너에서 `nginx -t` 성공 후 Nginx를 재생성해 반영했다.
- HMDM DB 변경: 고객 1의 `createnewdevices=true`, 기본 구성 ID 1 (`Common - Minimal`)로 설정했다. 기존 `BIS_JS_002` 레코드는 유지했다.
- SmartBIS ID 도용 방지: 에이전트가 HMDM 설정 조회/등록 전에 Android `ANDROID_ID`의 SHA-256 지문을 SmartBIS DB의 `android_device_identity_lock`에 원자적으로 선점한다. 다른 지문이 같은 차량 ID를 사용하면 HTTP 409로 차단한다. 원문 Android ID와 HMDM 서명 비밀값은 저장하지 않는다. 현재 HMDM에 이미 있는 기기를 최초 전환할 때는 승인할 물리 단말 하나만 먼저 새 에이전트로 체크인해야 한다. 기존 기록만으로 서버가 어느 물리 기기가 정당한 소유자인지 소급 판별할 수는 없으며 첫 선점 단말이 기준이 된다.
- 실시간 확인: 잘못된 ID의 enrollment POST는 HTTP 403, 형식이 맞는 미등록 ID 조회는 HMDM까지 전달됨(HTTP 200 응답). 이 조회는 DB에 기기를 만들지 않는 GET이었다.
- APK 빌드 및 `testOpensourceDebugUnitTest` 성공. 산출물: `releases/hmdm-agent-6.39-enrollment-20261010-083005-debug.apk`, SHA-256 `26A67830C0F8679FFED332698299382897537CE4B16C3D84290A6A063EC7CC22`.
- 물리 기기 ID 잠금 포함 APK도 별도 산출: `releases/hmdm-agent-6.39-identity-lock-20261010-debug.apk`, SHA-256 `329672858AA91B988D4231854D7D53B57F582739FDDC5395DD63DB90729F83D6`. 이전 APK는 그대로 보존했다.
- 최종 APK(응답 본문 정리 포함): `releases/hmdm-agent-6.39-identity-lock-final-20261010-debug.apk`, SHA-256 `01D5E9D7EDEE739647C235588D90B572F89234AB9B60F7F4BA996A38C8B546A3`, 크기 7,922,918 bytes.
- 남은 실기기 확인: 기기 1에 새 APK를 적용하고 `BIS_JS_001`로 체크인하여 서버에 새 행이 생기는지 확인한다. 기기 2의 `BIS_JS_002` 행은 변경하지 않는다. 이 APK는 debug 서명이므로 기존 동일 debug 서명의 시험 APK를 대체하는 용도이며 공식 서명 APK의 인플레이스 업데이트용이 아니다.
- APK 빌드 전용 테스트만 완료된 상태이며, ID-물리 단말 고정은 본 변경으로 처음 추가했다. 서버 테이블은 백엔드 기동 시 생성되며 기준 DDL은 `server/database/migrations/V013__device_identity_lock.sql`이다. 실제 동작 검증은 새 APK를 설치한 첫 단말이 ID를 선점하는지, 두 번째 단말이 같은 ID로 HTTP 409 차단되는지 확인해야 한다.
- ID 잠금은 첫 성공 요청의 단말이 소유자가 되는 방식이다. 현재 중복 ID를 가진 두 시험 기기 중 정당한 소유자를 시스템이 과거 데이터만으로 판별할 수는 없다. 시험 시 승인할 한 기기만 먼저 켜서 체크인한 뒤, 다른 기기에서 동일 ID가 거부되는지 확인한다. 기기 교체/초기화 시에는 관리자 승인 절차로 잠금 행을 재배정해야 한다.

## 2026-10-08 실기기 등록 결과 추가

- 시험 기기 `192.168.1.14:46595`에 `/d/PROJECT/hmdm-6.39-os.apk` 설치 완료.
- `adb -s 192.168.1.14:46595 shell dpm set-device-owner com.hmdm.launcher/.AdminReceiver` 성공.
- 후속 `dumpsys device_policy`에서 Device Owner package `com.hmdm.launcher`, admin `com.hmdm.launcher/.AdminReceiver`, `provisioningState: 3`을 확인함. 초기화 없이 등록 성공.
- `--name "SmartBIS HMDM"`을 포함한 첫 명령은 `Invalid component HMDM`으로 실패했고, 이름 옵션을 제거한 명령은 성공했다.
- 완료 범위는 APK 설치와 Device Owner 지정까지다. Headwind Devices 목록 등록, MQTT 연결, SmartBIS 원격 설치·업데이트는 아직 확인되지 않았다.
- 후속 작업은 시험 단말의 Agent 서버 연결 → Headwind 목록 확인 → MQTT → SmartBIS 배포·재부팅 시험이다. 기존 단말 `192.168.1.10`은 대상에서 제외한다.

## 1. 자동화 범위와 전제

Headwind 서버 기동만으로 Android 단말이 자동 등록되지는 않는다. 단말에 HMDM Agent가 설치되고 Android Device Owner 권한이 부여되어야 무인 앱 배포·업데이트를 기대할 수 있다. QR 기반 완전 관리형 등록은 Android의 Managed Provisioning과 제조사 펌웨어 지원이 전제다. [Headwind 빠른 시작](https://dev.h-mdm.com/quick-start/)

시험 대상 WTK-051D는 터치와 카메라가 없는 디스플레이형으로 보고되었다. QR 스캔을 당연한 절차로 전제하지 않고, ADB 기반 기술자 사전 등록 또는 제조사 프로비저닝 지원을 시험한다. ADB 자동화는 단말 접근과 디버깅 허용이 선행되는 사전 작업이며, 무인 최초 등록과 동일하지 않다.

## 2. 서버 구성 및 주소

- SmartBIS와 Headwind는 같은 호스트에서 별도 Compose 프로젝트·DB로 운영한다.
- 기존 Nginx가 HTTPS TCP 8443을 소유한다. `/api/`는 SmartBIS, 나머지 경로는 Headwind로 라우팅한다.
- Headwind MQTT는 TCP 31000이다. Agent에 광고하는 URI는 단말이 접근할 수 있는 실제 서버 IP여야 한다. `0.0.0.0`은 광고 주소로 쓰지 않는다.
- 2026-10-08 개발 Wi-Fi 시험에서 사용자 제공 정보상 PC는 `192.168.1.11`, 시험 단말은 `192.168.1.14`였다. 이전 유선 주소 `192.168.100.50`은 이번 단말과 다른 서브넷이다. IP·인증서 SAN·방화벽·portproxy는 네트워크 변경 때 재확인한다.
- 개발 접속 예: `https://192.168.1.11:8443`. 인증서 IP SAN과 Android의 CA 신뢰가 일치해야 한다.
- 2026-10-09 주소 혼선 복구 기준: 기존 서버 Ethernet 주소 `192.168.100.50`을 유지한다. Headwind `base.url`, MQTT 주소 및 커스텀 Agent 기본 URL은 이 주소를 사용하도록 정렬했다. PC Wi-Fi의 `192.168.1.x`/`192.168.100.x` 변경은 별도 네트워크 주소이며 서버 주소로 임의 사용하지 않는다. Android가 서버 Ethernet 주소에 도달하려면 라우팅 가능 여부와 인증서 SAN을 검증한다.

## 3. 서버 최초 기동 절차

세부 TLS/IP 설정은 이 디렉터리의 `README.md` 및 `configure-dev-ip.sh`를 기준으로 한다. Git Bash에서:

```bash
cd /d/PROJECT/SmartBIS_jangsung
podman compose up -d

cd /d/PROJECT/SmartBIS_jangsung/deployment/headwind-mdm
test -f .env || cp .env.example .env
chmod 600 .env
podman compose config
podman compose up -d postgresql hmdm
podman compose ps
podman compose logs --tail=100 hmdm
```

`.env`에서 `MDM_HOST`, `SMARTBIS_NETWORK`, 관리자 이메일을 확인하고 비밀값은 안전하게 생성·관리한다. `.env`는 커밋하거나 문서에 복사하지 않는다. 서버 초기화 완료 뒤 `https://<MDM_HOST>:8443/`에서 초기 관리자 로그인과 비밀번호 변경을 수행한다. 사용자는 개발 서버에서 로그인 및 비밀번호 변경 완료를 보고했다.

서버 확인:

```bash
cd /d/PROJECT/SmartBIS_jangsung
podman compose ps
curl -k -sS -D - -o /dev/null https://<MDM_HOST>:8443/
podman exec smartbis_jangsung-nginx-1 wget -S -O /dev/null --no-check-certificate https://headwind-mdm:8443/
```

기대 응답은 Headwind 페이지 HTTP 200이다. 개발 진단용 `-k`/`--no-check-certificate`는 인증서 검증 성공의 증거가 아니다. 인증서는 CA 검증을 별도로 한다. 404/502/504면 단말 등록보다 먼저 Nginx 라우팅, Headwind 컨테이너 상태 및 `volumes/webapps/ROOT` 전개 상태를 확인한다.

## 4. APK 식별 및 버전 확인

2026-10-08 시험 파일 `D:/PROJECT/hmdm-6.39-os.apk`를 `aapt`로 읽어 다음을 확인했다.

| 항목 | 값 |
|---|---|
| Agent 패키지 | `com.hmdm.launcher` |
| Device Admin receiver | `com.hmdm.launcher.AdminReceiver` |
| 버전 | 6.39 |
| 최소 SDK | 26 |

확인 명령(로컬 SDK 경로에 따라 조정):

```bash
/c/Users/eun/AppData/Local/Android/Sdk/build-tools/36.0.0/aapt.exe \
  dump badging /d/PROJECT/hmdm-6.39-os.apk
```

manifest의 패키지/receiver 정보만으로 APK와 Headwind 서버의 호환성이 증명되지는 않는다. 현재 Compose의 Headwind 이미지/WAR 및 `CLIENT_VERSION`과 Agent APK 간 호환성은 기기 등록 전 확인해야 한다.

## 5. 시험 단말과 사전 점검 결과

모든 ADB 명령은 정확한 serial을 지정한다.

| 역할 | 식별자 | 확인 결과 |
|---|---|---|
| 이번 시험 대상 | `192.168.1.14:46595` | `WTK-051D`, Android 11, ADB `device` |
| 기존 설정 기기 | 이전 주소 `192.168.1.10` | 이번 시험 대상 아님. 초기화/등록 명령 금지 |

시험 단말에 실행한 `dumpsys device_policy` 결과는 Device Owner 미표시, `Enabled Device Admins` 비어 있음, `Device provisioned: true`였다. `pm list packages | grep -iE 'hmdm|headwind'`에서는 패키지가 나오지 않았다. 따라서 Android 초기 설정은 완료됐지만 HMDM은 미설치이며 Device Owner도 없는 상태다. `Device provisioned: true`는 Owner 등록 여부를 말해주지 않는다.

읽기 전용 점검:

```bash
adb devices
adb -s 192.168.1.14:46595 shell getprop ro.product.model
adb -s 192.168.1.14:46595 shell getprop ro.build.version.release
adb -s 192.168.1.14:46595 shell dumpsys device_policy
adb -s 192.168.1.14:46595 shell pm list packages | grep -iE 'hmdm|headwind'
```

## 6. 기기 등록 시험 순서

기존 설정 기기와 혼동하지 않도록 `adb devices`에서 대상을 확인하고 모든 명령에 serial을 붙인다. 시험 기기는 사용자가 보존 자료가 없다고 확인했지만, 초기화는 대상 serial 재확인 후에만 한다.

### A. QR/Managed Provisioning 확인

Android의 초기 설정 QR 등록은 Headwind가 안내하는 기본 경로지만, Managed Provisioning이 장비 펌웨어에 있어야 한다. 터치·카메라가 없는 모델에는 바로 적용할 수 있다고 가정하지 않는다. 제조사에 WTK-051D Android 11의 QR enrollment/Managed Provisioning 지원 여부를 확인한다. 지원하지 않으면 이 경로를 사용하지 않는다.

### B. ADB 기술자 사전 등록 시험

아래 명령은 실제 기기 상태를 바꾸므로 실행 전에 목적·대상·영향을 사용자에게 제시한다. 사용자가 시험 대상임을 확인한 뒤 단계별로 실행한다. 이 문서에 기록된 명령은 절차 초안이며 아직 실행 완료가 아니다.

APK 설치:

```bash
adb -s 192.168.1.14:46595 install -r /d/PROJECT/hmdm-6.39-os.apk
```

Device Owner 지정 형식(APK manifest에서 확인):

```bash
adb -s 192.168.1.14:46595 shell dpm set-device-owner \
  --name "SmartBIS HMDM" \
  com.hmdm.launcher/.AdminReceiver
```

기기는 `Device provisioned: true` 상태여서 Android 정책상 지정이 거부될 수 있다. 오류가 나면 무작정 반복하지 말고 전문을 기록한다. 초기화가 필요하다는 근거가 확인된 경우에만 시험 기기 초기화 절차를 별도로 안내하고, 기존 기기 serial에는 적용하지 않는다.

성공 확인:

```bash
adb -s 192.168.1.14:46595 shell dumpsys device_policy
```

출력에 HMDM package/admin이 Device Owner로 표시되어야 성공이다. APK 설치나 Launcher 화면 표시만으로 성공 판정하지 않는다.

### C. 제조사 통합 경로

QR Managed Provisioning이 없고 반복적인 현장 자동화가 필요하면 제조사에 DPC 사전 탑재, Managed Provisioning/완전관리형 등록, 필요한 OS 서명·플랫폼 통합을 문의한다. 이 기능은 Podman 서버에서 대신 설정할 수 없다.

## 7. 등록 이후 수용시험

Device Owner 확인 후에만 다음 항목을 시험한다.

1. Headwind Devices 목록에 시험 단말이 등록되는지 확인한다.
2. Agent가 `https://<MDM_HOST>:8443` 및 `<MDM_HOST>:31000`에 연결되는지 확인한다. `0.0.0.0` 또는 `mdm.example.org`가 표시되면 실패다.
3. SmartBIS APK를 Headwind 구성에 할당하고 원격 설치되는지 확인한다.
4. 기기 재부팅 후 SmartBIS 자동 실행, 새 버전 원격 업데이트, Wi-Fi 끊김/복구 후 재연결을 확인한다.
5. Headwind에서 Installation Status가 실패면 Agent 로그와 해당 앱 설치 오류를 조사한다. 필요 없는 `com.hmdm.pager`가 `mdm.example.org`에서 다운로드되려는 경우 구성에서 제외한다. Pager 사용 시에는 접근 가능한 올바른 앱 URL을 설정한다.
6. 날짜, 서버/Agent/APK 버전, 시험 serial(배포 문서에는 마스킹), 명령 및 결과를 기록한다. 토큰·비밀번호·키는 기록하지 않는다.

## 8. 진행 상태 (2026-10-09 업데이트)

| 범위 | 상태 | 근거 |
|---|---|---|
| 별도 Headwind Podman 서버 | 부분 검증 | PostgreSQL/HMDM 기동, 관리자 로그인·비밀번호 변경 사용자 보고, Nginx 경유 HTTP 200 사용자 결과 |
| 커스텀 Agent 빌드 | 개발 APK 산출 | 6.39 `opensourceDebug`; 주소 기본값 `.100.50:8443`; CA 번들 포함. 운영 서명 아님 |
| Device Owner 등록 | 시험 단말 1대 성공 | 사용자가 `dpm set-device-owner` 성공과 `dumpsys device_policy` Owner 확인을 보고함 |
| Android ↔ 개발 서버 경로 | 확인 | Android 고정 IP `.100.52`에서 `https://192.168.100.50:8443/` 정상 접속을 보고함 |
| Headwind 체크인 | 확인 | 사용자가 `.100.50:8443`으로 체크인 정상이라고 보고함 |
| SmartBIS 원격 설치·실차량번호 표출 | 미완료/미확인 | 아래 수용시험과 차량번호 실데이터 확인 필요 |
| 공장초기화 상태에서 자동 등록 | 미완료 | 이 모델에 자동화 가능한 OEM/Android Enterprise 등록 경로 미확인 |
| 복구/롤백 리허설 | 미완료 | 아래 복구 기준에 따라 시험 단말에서 수행 필요 |

## 9. 두 시험기 Android Enterprise 재등록 시험 준비 (2026-10-10)

### 범위와 안전선

- 대상은 현재 시험 중인 물리 단말 2대뿐이다. 현재 로그에 나온 `BIS_JS_001`/`BIS_JS_002`를 물리기기와 바로 연결하지 않는다. 공장초기화 전 본체 라벨과 현재 단말 화면/관리자 목록을 대조해 시험기 A/B로 기록한다.
- Android 공장초기화는 해당 단말의 사용자 데이터, MDM Agent, Device Owner, 저장된 Ethernet/Wi-Fi 설정을 지운다. 이것은 Headwind DB나 SmartBIS DB를 초기화하지 않는다.
- 서버 DB 전체 삭제, Compose `down -v`, 데이터 볼륨 재생성은 금지한다. 두 DB 각각 백업이 만들어지고 검사된 뒤에만 기기 초기화 단계로 넘어간다.
- Ethernet 연결정보 제거 자체가 Android Enterprise 등록 수단은 아니다. 초기 설정 중 단말이 인터넷과 HMDM 서버에 닿는 네트워크가 필요하다. 개발 서버는 사설 주소 `192.168.100.50`이므로 시험 네트워크에서 그 주소로 라우팅되고 인증서가 신뢰되어야 한다. 운영 환경에서는 최종 HTTPS 주소/CA로 별도 재빌드가 필요하다.

### 먼저 확인된 Android Enterprise 경로 제약

Headwind의 기본 Android Enterprise 완전관리형 등록은 공장초기화 후 Setup Wizard에서 QR을 스캔하여 Managed Provisioning이 Agent를 설치하고 Device Owner를 부여하는 흐름이다. Headwind 안내도 QR 표시/스캔을 enrollment 단계로 요구한다 ([Headwind 빠른 시작](https://dev.h-mdm.com/quick-start/), [카메라 없는 기기 안내](https://dev.h-mdm.com/mdm-android-without-camera/)). Android 전용 표지판은 카메라·터치가 없다고 보고되었고, 현재 ADB도 연결되지 않는다. 그러므로 현재까지 확인된 상태만으로는 초기화 후 QR 또는 ADB 중 어느 경로도 실제 완료가 입증되지 않았다.

따라서 두 기기를 바로 초기화하면 안 된다. 먼저 제조사/펌웨어에서 아래 중 실제 지원하는 경로를 확인한다: Setup Wizard QR scanner가 내장되어 있고 다른 화면의 QR을 읽을 수 있음, NFC provisioning 지원, 또는 제조사/OEM 사전 Device Owner provisioning/zero-touch. 어느 것도 없으면 Android Enterprise 완전관리형 초기 등록은 현재 장비 조합으로 수행 불가이며, 터치 없는 화면에서 QR을 수동으로 찍는 식의 안내로 해결되지 않는다. Headwind APK를 일반 앱으로 설치하는 것은 Android Enterprise Device Owner 등록과 같지 않다.

### APK 빌드·서명 현황

- 기존 산출물은 `deployment/headwind-mdm/releases/`의 6.39 커스텀 Agent APK와 `client-android/app/build/outputs/apk/debug/app-debug.apk`의 SmartBIS debug APK다. 둘 다 운영 서명본으로 확인된 것은 아니다.
- Agent의 현재 구현은 ID를 `BIS_JS_NNN` 형식으로 선택하게 하고, 서버 설정 요청 전 SmartBIS 물리기기 identity claim을 수행한다. 사용자 로그에서는 이전 실행이 identity claim 없이 설정을 요청해 거부된 기록이 있으므로, 다음 시험에서는 반드시 새 Agent APK가 claim 요청을 성공시키는지 서버 로그/API 응답으로 확인한다.
- 이번 세션에서 JDK 17, Android SDK 및 Gradle Wrapper로 두 시험 APK를 다시 빌드했다. SmartBIS `testDebugUnitTest`와 `assembleDebug`는 성공했다. Headwind `assembleOpensourceDebug`는 성공했지만, 별도 단위 테스트 task는 Gradle daemon 접속 오류로 완료되지 않았다. 산출물은 둘 다 debug 서명 시험본이며 기존 산출물을 덮어쓰지 않고 다음 경로에 별도 보관했다:
  - Agent: `releases/hmdm-agent-6.39-smartbis-20261010-162917-debug.apk`, 7,922,918 bytes, SHA-256 `5EB6B42DB0D187F09C1AA11246B444BDF1A6B850EB4CAB38E83BF0F4E592AE69`.
  - Client: `../../client-android/releases/smartbis-client-1.2-code3-20261010-162917-debug.apk`, 14,957,884 bytes, SHA-256 `9C108317442750DDEDD691762E281BD30F34BCDCBCDBF59A6AF36B95BBE6CE8B`.
  - 두 APK 모두 `apksigner verify --print-certs` 통과, signer는 Android Debug certificate다. Agent manifest는 `com.hmdm.launcher` 6.39, Client는 `com.smartbis.client` 1.2 / code 3이다.
- 현재 Android 프로젝트에는 release keystore 설정이 확인되지 않았다. HMDM Agent는 debug-signed opensource APK로, 공식 서명 Agent에 업데이트 설치할 수 없다. SmartBIS 앱도 운영 서명 키를 마련하기 전에는 운영 릴리스 APK라고 부르지 않는다. 테스트 APK와 운영 APK를 구분하고, 운영 서명키는 저장소 밖에 보관하며 비밀번호를 채팅/소스에 넣지 않는다.

### DB 백업·업데이트 원칙

Headwind와 SmartBIS는 각각 별도 PostgreSQL DB/볼륨이다. 단말 공장초기화만으로 이 DB들은 바뀌지 않는다. 초기화 전에 Git Bash에서 아래를 실행하면 두 DB의 SQL dump를 같은 timestamp 디렉터리에 만들고, dump 완료 표식 및 SHA-256을 확인한다. 비밀값은 읽거나 출력하지 않는다.

```bash
cd /d/PROJECT/SmartBIS_jangsung
bash deployment/headwind-mdm/backup-provisioning-test-dbs.sh
```

백업에는 단말 목록/구성/Headwind identity lock, SmartBIS 콘텐츠·운행 자료와 Android identity lock이 포함될 수 있으므로 백업 폴더는 Git에서 제외하고 접근 제한한다. `backups/`는 복구 자료이며 운영 DB로 자동 복원하지 않는다.

- 2026-10-10 실제 사전 백업 완료: `deployment/headwind-mdm/backups/provisioning-test-20261010-163505/` (`hmdm.sql` 708,860 bytes, `smartbis.sql` 3,816,113 bytes). 두 dump의 완료 표식과 SHA-256 체크섬 파일을 확인했고 Git ignore 및 Windows ACL을 확인했다. ACL은 현재 사용자 `SAI\eun`, SYSTEM, Administrators에 한정했다. 이 백업은 재등록 전 기준점이며 DB를 수정하거나 복원하지 않았다.
- SHA-256: HMDM `d6c6038d9ea85674e13b8c6ab2f5782ee1bc7eafab4ab75be7c467dab2eafc24`; SmartBIS `7a786096efe64426246d7ac82ccab26a7cede422b8c3a7bd1f79542b255a2b53`.

초기화 후에는 **두 단말 모두 초기화 → 각 단말을 서로 다른 터미널 ID로 새로 등록 → 각 단말의 identity claim 성공 확인 → SmartBIS 연결** 순서로 한다. 기존 행 전체를 지우지 않는다. 동일 시험 ID의 기존 HMDM deviceIdentityLocks 및 SmartBIS android_device_identity_lock이 새 Android 설치의 지문과 충돌하면, 두 단말의 물리 라벨과 단말 ID를 확인하고 백업이 유효한 경우에만 해당 두 ID 행을 제한적으로 재바인딩한다. 실행 전 현재 값/행 수와 대상 `BIS_JS_001`, `BIS_JS_002`가 시험용임을 확인하는 트랜잭션 계획을 작성하고, 실제 production 데이터에는 적용하지 않는다.

현재 SmartBIS `android_device_identity_lock.vehicle_number` 컬럼은 이름과 달리 단말 ID(`BIS_JS_NNN`)의 identity claim 키로 쓰인다. 반면 재생상태 테이블의 `vehicle_number`는 실제 차량번호다. 이 두 개념은 운영 전 schema/API migration으로 `device_id`와 실제 `vehicle_number` 연결을 분리해야 한다. 현재 두 시험기 DB 재등록에서는 기존 스키마를 유지하고 테스트 ID만 재바인딩하며, 실제 차량번호를 단말 ID 자리에 넣지 않는다.

SmartBIS backend DB의 운영 콘텐츠·TAGO 노선/정류소·차량정보는 보존한다. schema 변경은 애플리케이션의 기존 DB 초기화/DDL 경로와 버전 migration을 확인한 뒤 순방향 migration으로만 적용하고, HMDM은 Liquibase가 해당 서버 WAR 기동 때 migration을 관리한다. DB 볼륨을 지우거나 빈 DB로 재생성하는 방식은 기기 재등록 테스트에 필요하지 않다.

### 두 기기 수용시험 순서

1. A/B 물리 라벨과 기존 MDM ID를 기록하고, 두 DB 백업 파일 및 체크섬을 확인한다. 각 단말의 현재 이더넷/Wi-Fi 프로필을 기록한 뒤 사용자 승인하에 공장초기화한다. ADB나 원격 명령으로 초기화를 시도하지 않는다.
2. 사전에 확인된 Android Enterprise 방법으로 Setup Wizard 등록을 진행한다. Device Owner를 시스템 정책 상태 또는 HMDM 관리 화면에서 확인한다.
3. Agent가 서버에 identity claim을 성공적으로 보내고, 각각의 고유 터미널 ID가 HMDM에 한 대씩 생성되는지 확인한다. 두 단말에 같은 ID를 사용하지 않는다. 차량번호/실차량 매핑은 별도 운영 UI에서 나중에 설정한다.
4. Headwind에서 SmartBIS 운영 앱을 원격 설치하고, 앱 버전·HTTPS API 연결·콘텐츠 재생·도착정보 표출·재부팅 후 자동실행을 확인한다.
5. 업데이트 실패 시 이전 APK를 같은 서명키로 되돌리는 복구 절차를 시험한다. 앱 복구와 DB 복구는 독립 절차로 기록한다.

**현재 진행 판정:** 두 debug APK의 빌드/서명 확인과 HMDM·SmartBIS DB 쌍의 실제 백업은 완료했다. 두 물리 단말의 공장초기화, Android Enterprise QR/OEM 등록 경로 확인, 새 APK의 실기기 설치 및 Device Owner 등록은 아직 하지 않았다. 두 단말의 이더넷 설정도 아직 변경하지 않았다. 이 문서가 위 장비·등록 경로 제약을 해소했다고 간주하지 않는다.
| QR 무인 등록 | 미확인 | 펌웨어 지원·터치/카메라 제약 확인 필요 |
| MQTT | 재확인 필요 | 과거 연결 실패 기록이 있어 현재 31000 체크인/푸시 수신 확인 필요 |

서버 기동, Agent 설치, Device Owner 등록, 서버 등록, SmartBIS 자동 배포는 서로 다른 완료 기준이다.

### 반복 가능한 단말 사전 등록

기기에서 USB 디버깅이 실제로 허용되고 ADB가 `device`로 보여야 한다. 프로젝트 루트의 Git Bash에서 아래처럼 **대상 serial과 APK를 명시**한다. serial 확인 대화가 나오며, 공장 초기화는 자동으로 수행하지 않는다.

```bash
adb devices -l
bash deployment/headwind-mdm/provision-test-device.sh <ADB_SERIAL> \
  deployment/headwind-mdm/hmdm-android-v6.39/app/build/outputs/apk/opensource/debug/app-opensource-debug.apk
```

이 절차는 현재 설정된 디버깅이 있는 시험 장비의 반복 작업을 줄이는 기술자 절차다. 입고 초기 상태에서는 USB debugging 승인/Managed Provisioning이 선행되어야 하므로 APK 하나만으로 설치 및 Device Owner를 무인 획득하지 못한다. 터치 없는 여러 대를 현장에서 처리하려면 공급사에 DPC 사전 탑재 또는 Android Enterprise 완전관리형 프로비저닝 지원을 확인한다.

### 실 차량번호·콘텐츠 수용시험

1. Android에서 서버 `https://192.168.100.50:8443` 접속, Headwind Devices의 최근 체크인, MQTT 푸시 도달을 각각 확인한다.
2. Headwind에서 검증된 SmartBIS APK를 해당 시험 단말 하나에만 할당한다. 앱 설치 상태가 성공으로 바뀌고 재부팅 후 SmartBIS가 자동 실행되는지 확인한다.
3. 앱 설정에서 차량번호 검색에 실제 TAGO 차량번호 일부를 입력하고, 검색 목록에서 해당 차량을 선택한다. SmartBIS APK를 빌드할 때 실기기 API 주소와 설치본보다 높은 versionCode를 지정한다. 현재 기본 설치본이 versionCode 1이라면 다음 시험 빌드 예시는 `bash client-android/build-smartbis-apk.sh https://192.168.100.50:8443/api 2 1.1`이다. 서버 차량 목록 API가 비거나 번호가 없으면 실제 운행 등록/차량 데이터가 서버 DB 및 TAGO 위치 수집기에 들어왔는지 먼저 확인한다. 임의 차량번호로 실차량 검증을 성공 처리하지 않는다.
4. 실제 운행 차량에서 도착정류소/다음 정류소 및 도착 예정 분이 바뀌는지, 지역 콘텐츠 제한과 재난·행사·공지·동영상 순환 우선순위, 영상 종료 후 다음 항목 전환을 확인한다. 화면 기록에는 차량번호를 마스킹한다.
5. 설치/업데이트/재부팅/네트워크 복구 결과와 앱·Agent·서버 버전, 시간, 오류 로그를 시험 기록에 남긴다.

### 실패 시 롤백 기준

- **SmartBIS 앱 업데이트 실패:** 시험 단말 한 대에만 배포한다. 빌드 스크립트가 빌드 전 APK와 새 APK의 버전별 사본 및 SHA-256을 보관한다. 동일 applicationId·서명키의 이전 APK를 우선 복구한다. Android가 낮은 versionCode 설치를 거부하면 이전 정상 소스로 새 versionCode를 올린 복구 빌드를 만들어 배포한다. 서명이 다르면 인플레이스 복구가 안 되므로 해당 APK는 배포 후보로 승인하지 않는다.
- **커스텀 Agent APK 실패:** 이 저장소의 6.39 debug APK는 공식 Agent 서명과 다르므로 공식 Agent 위에 업데이트 설치할 수 없다. 디버그 APK 적용은 초기화 가능한 시험 기기 한 대로 제한한다. 문제가 생기면 기기 화면/ADB로 현재 상태를 보존하고, Device Owner 제거 또는 초기화가 필요한지 확인 후 별도 절차를 밟는다. 자동 uninstall/reset은 실행하지 않는다.
- **Headwind 서버/WAR 업데이트 실패:** `update-release.sh`가 업데이트 전에 WAR, 설정 디렉터리 및 DB dump를 `deployment/headwind-mdm/backups/release-*`에 저장한다. 자동 복원 명령은 없으므로 해당 백업이 비어 있지 않은지 확인한 다음에만 업그레이드한다. 복구는 앱 중지 → 이전 `ROOT.war` 복원 → DB/설정 호환성 점검 → 기동 순으로 별도 승인·기록 아래 수행하며 `down -v`는 금지한다.
- 매 배포는 canary 단말 1대 → 체크인/앱/콘텐츠 확인 → 나머지 단말 순으로 진행한다. 새 버전이 확인될 때까지 직전 정상 APK와 서버 백업을 삭제하지 않는다.

## 9. 지자체 운영·관리 매뉴얼 산출 항목

사업 완료 시 실제 운영 서버값으로 별도 운영 매뉴얼을 확정한다.

- 구성도, 관리 URL, 8443 HTTPS/31000 MQTT 방화벽, 인증서 배포·갱신
- 관리자 로그인·비밀번호 변경 및 계정 복구(비밀값 제외)
- 신규 단말 사전 등록, Device Owner 확인, 그룹/정책 할당, 앱 설치·업데이트·롤백
- 단말 교체·초기화·재등록 및 데이터 영향/승인 책임
- MQTT/네트워크/설치 실패 진단, 로그 확인과 장애 연락 경로
- HMDM DB·영속 파일 백업 및 복원, 운영 서버 이전
- 검증된 단말 모델·OS·Agent/서버 버전 조합과 미지원 기능

## 10. 현재 확인된 주의사항

- `deployment/headwind-mdm/compose.yaml`의 서버/WAR 버전과 사용자가 가진 Agent 6.39의 호환성은 확인되지 않았다. 확인 전 광범위 배포하지 않는다.
- 이전 로그의 `mdm.example.org`는 예시 주소로 보이며, 현재 설정의 MQTT 주소가 아닌 이상 단말이 연결할 수 없다.
- Nginx 8443이 SmartBIS와 Headwind 양쪽의 단일 진입점이다. 독립적인 Headwind Nginx 컨테이너를 추가로 시작할 필요가 없다.
- Headwind DB named volume과 `volumes/` 데이터는 등록 기기·정책 정보를 보유할 수 있으므로 임의로 `down -v` 또는 삭제하지 않는다.
- 공장 초기화/Device Owner 해제는 단말 상태를 바꾸거나 데이터를 삭제할 수 있다. 이 문서는 자동으로 실행하지 않는다.

## 11. 시험 시작 패키지 (2026-10-11)

개발용 원격 배포 canary 시험에 사용할 APK다. 두 파일은 운영용이 아닌 개발 서버 `192.168.100.50` 전용 debug 서명본이다.

| 역할 | APK | 패키지/버전 | SHA-256 |
|---|---|---|---|
| Headwind Agent | `releases/hmdm-agent-6.39-auto-id-20261011-debug.apk` | `com.hmdm.launcher` / 6.39 | `47A32EF581B58D3193CE8F41262B357EEDA0109B3C406D57DDE526B3FE813069` |
| SmartBIS 화면 앱 | `../../client-android/releases/smartbis-client-1.2-code3-20261010-162917-debug.apk` | `com.smartbis.client` / 1.2 (3) | `9C108317442750DDEDD691762E281BD30F34BCDCBCDBF59A6AF36B95BBE6CE8B` |

### Headwind를 통한 원격 앱 배포 절차

1. 자동 ID 배정용 Headwind WAR를 먼저 배포하고 HMDM DB 백업 및 Liquibase 마이그레이션 완료를 확인한다. **Agent APK는 새 기기가 아직 Headwind에 등록되기 전에는 Headwind의 Applications에서 내려받을 수 없다.** 새 기기 등록 때 Android Enterprise/OEM 프로비저닝이 Agent를 DPC(Device Owner)로 설치·활성화해야 한다. 이 APK의 첫 실행이 서버에서 `BIS_JS_NNN`을 받아 체크인한다.
2. Headwind 관리자 화면에서 **Applications → Add application**을 열어 아래처럼 등록한다. 파일 업로드 후 패키지 ID와 버전은 APK에서 자동 입력되므로 직접 임의 입력하지 않는다.

| 화면 항목 | 입력/선택 |
|---|---|
| Type | Android application / `app` |
| APK file | `client-android/releases/smartbis-client-1.2-code3-20261010-162917-debug.apk`를 PC에서 선택 |
| Package ID | 자동 감지값 `com.smartbis.client` 확인 |
| Application Name | 자동 감지값 `SmartBIS Client` 확인 |
| Version | 자동 감지값 `1.2` 확인 (versionCode 3) |
| Architecture | `None (universal APK)` 선택 |
| Preinstalled app | 체크 해제 — Android 시스템에 원래 포함된 앱이 아님 |
| Run after installation | 체크 — 설치 직후 디스플레이 앱을 실행 |
| Run at boot | 체크 — 기기 재부팅 뒤 전면 실행 |
| Auto Update | 최초 canary 검증 동안은 해제. 한 대에서 업데이트 성공을 확인한 뒤 전체 적용 여부 결정 |
| URL | 업로드 후 생성된 기본 URL 유지; 임의 수정하지 않음 |

APK를 올리면 Headwind가 URL을 만들고 파일을 보관한다. 이후 `Common - Minimal` 구성 편집의 Applications에서 `SmartBIS Client`를 추가하고 저장한다. 신규 단말 기본 구성이 `Common - Minimal`인지도 확인한다.
3. 초기화 시험기 한 대를 Device Owner로 프로비저닝한다. Agent 체크인 후 `BIS_JS_NNN`이 발급되고 `Common - Minimal` 구성이 적용되면, Headwind가 해당 구성의 SmartBIS Client를 단말에 원격 설치한다. 단말에 USB/ADB로 Client APK를 복사 설치하지 않는다. 그래야 이번 원격 배포 시험이 된다.
4. Headwind Devices에서 새 단말 ID와 최근 체크인을 확인하고, 해당 기기의 앱 설치 상태에서 SmartBIS Client가 성공했는지 확인한다. 이후 SmartBIS 화면에서 콘텐츠 재생과 재부팅 후 자동 실행을 시험한다. 두 번째 단말은 첫 번째 결과 확인 후 진행한다.

두 APK의 역할은 다르다. Agent는 신규 단말의 DPC/Device Owner 프로비저닝 경로로 설치해야 하며, SmartBIS Client는 Agent가 체크인한 뒤 Headwind 구성 정책으로 원격 설치한다. 기존 공식 서명 Agent를 이 debug Agent로 인플레이스 교체할 수 있다고 가정하지 않는다. 로컬 ADB 설치 helper가 있더라도 이는 개발 진단용일 뿐, 원격 배포 성공 판정에 사용하지 않는다.

## 12. 시험기 1대 설치 실행 결과 (2026-10-11)

사용자가 지정한 개발 시험 범위에서 현재 ADB에 실제 연결된 단말 하나만 대상으로 수행했다. 단말 식별은 ADB serial `192.168.1.11:46075`, 모델 `WTK-051D`, Android 11이다. 다른 단말, HMDM DB, SmartBIS DB에는 변경을 하지 않았다.

| 단계 | 결과 | 검증 |
|---|---|---|
| PC ADB 데몬 | 정상 기동 | `adb devices -l`에서 지정 serial이 `device` |
| 커스텀 Headwind Agent 설치 | 성공 | `adb install -r` 성공 |
| Device Owner 설정 | 성공 | `dumpsys device_policy`에서 `com.hmdm.launcher/.AdminReceiver`, provisioningState 3 확인 |
| SmartBIS Client 설치 | 성공 | Android 11 `adb install -r` 성공 |
| SmartBIS Client 실행 | 성공 | launcher intent 실행 이벤트 주입 성공 |
| Headwind 서버 체크인 | 미완료 | 단말에서 `192.168.100.50:8443` TCP 연결 시험 timeout |
| Headwind 원격 앱 배포/푸시 | 미완료 | 서버 연결이 성립하지 않아 원격 설치 경로는 아직 실증되지 않음 |
| 콘텐츠 API/영상 재생 | 미완료 | 클라이언트가 서버에 도달할 네트워크 경로가 없어 확인 불가 |

현재 단말 `ip -4 addr show`에는 Wi-Fi `wlan0 = 192.168.1.11/24`만 나타났고 Ethernet IPv4 주소는 없었다. PC에서 `192.168.100.50:8443`은 HTTP 200으로 응답하며, SmartBIS APK 파일 URL도 HTTP 200 및 14,957,884 bytes를 반환한다. 즉 APK 빌드·서버 파일 등록 경로는 준비됐지만, 현재 단말 Wi-Fi에서 서버의 사설 Ethernet 대역으로 라우팅이 되지 않는 것이 체크인/콘텐츠 시험의 직접적인 차단 원인이다. 서버 IP를 임의 변경하지 않는다. 다음 시험은 단말 Ethernet에 기존 개발망 주소를 연결해 `192.168.100.50:8443`에 도달하는지 확인한 뒤, Agent 체크인 → identity claim → 고유 `BIS_JS_NNN` 등록 → Headwind 앱 배포 → 콘텐츠 재생 순으로 이어간다.

시험에 사용한 새 준비 스크립트 `install-test-apks.sh`도 Bash `-n` 구문 검사를 통과했다. 이 실행에서는 스크립트 대신 명령별 결과 확인을 위해 동일한 순서로 ADB 명령을 실행했다. 스크립트는 이후 canary 반복 시험용이며, serial 재확인·다른 Device Owner 거부·초기화/제거 금지 동작을 포함한다.
