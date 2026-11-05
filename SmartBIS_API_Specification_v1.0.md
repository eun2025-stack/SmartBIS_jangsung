# SmartBIS Server API Specification v1.0

## 1. 문서 정보

| 항목 | 내용 |
|---|---|
| 시스템 | SmartBIS |
| API 서버 | Spring Boot embedded Tomcat |
| 외부 진입점 | Nginx HTTPS |
| 기본 개발 주소 | `https://localhost:8443` |
| API Context Path | `/api` |
| 문자 인코딩 | UTF-8 |
| 시간대 | Asia/Seoul |

## 2. 통신 구조

```text
Android Client
    │ HTTPS :8443
    ▼
Nginx
    │ HTTP 내부 통신
    ▼
Spring Boot :8080
    │
    ├── PostgreSQL
    └── server/content-store
```

Android 클라이언트는 Spring Boot의 8080 포트로 직접 접속하지 않고 반드시 Nginx HTTPS 주소를 사용한다.

## 3. 공통 규칙

### 3.1 Base URL

개발 환경:

```text
https://localhost:8443/api
```

예를 들어 Controller가 `/v1/media/{contentId}`로 선언되어 있으면 실제 호출 주소는 다음과 같다.

```text
https://localhost:8443/api/v1/media/{contentId}
```

### 3.2 TLS 인증서

개발 환경은 사설 Root CA를 사용한다. Android 테스트 단말 또는 에뮬레이터에는 다음 CA를 신뢰하도록 설치해야 한다.

```text
server/nginx/certs/rootCA.crt
```

서버 인증서의 SAN에는 `localhost`, `127.0.0.1`, `10.0.2.2`가 포함되어 있다. Android Emulator에서 호스트 PC를 접근할 때는 일반적으로 `10.0.2.2`를 사용하지만, 인증서 검증과 실제 접속 주소가 일치해야 한다.

### 3.3 HTTP 상태 코드

| 상태 코드 | 의미 |
|---:|---|
| 200 | 요청 성공 및 미디어 응답 |
| 400 | 잘못된 경로 또는 요청 형식 |
| 404 | 콘텐츠 또는 미디어 파일 없음 |
| 500 | 서버 내부 처리 오류 |
| 502 | Nginx가 Spring Boot에 연결하지 못함 |

## 4. Health API

서버와 DB 연결 상태를 확인한다.

### Request

```http
GET /api/actuator/health HTTP/1.1
Host: localhost:8443
```

### cURL

```bash
curl --cacert server/nginx/certs/rootCA.crt \
  --ssl-no-revoke \
  https://localhost:8443/api/actuator/health
```

### Response 200

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

### 4.1 단말 차량번호 검색 API

운영자 설정 화면에서 차량번호 일부를 입력해 실제 TAGO 차량목록 중 일치 항목을 선택한다. 최소 2자 이상 검색하며, 활성 차량 최대 50건을 반환한다. 응답이 비어 있으면 서버가 아직 해당 차량을 TAGO 수집으로 확인하지 못했거나 검색어가 일치하지 않는 것이다.

### Request

```http
GET /api/v1/vehicles?q=12 HTTP/1.1
Host: localhost:8443
```

### Response 200

```json
["전남 72자 1234", "전남 72자 1256"]
```

검색 결과에서 한 항목을 선택해야 앱에 차량번호를 저장할 수 있다. 이 API는 차량 단말용이며 프로젝트 내부망에서만 사용한다.

## 5. 미디어 다운로드 API

DB에 저장된 `content_id`를 기준으로 동영상 또는 이미지 파일을 반환한다.

### Request

```http
GET /api/v1/media/{content_id} HTTP/1.1
Host: localhost:8443
```

예:

```text
GET /api/v1/media/CNT_BATCH_0001
```

### Path Parameter

| 이름 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `content_id` | String | 예 | `contents.content_id` 값 |

### 성공 응답

동영상 예:

```http
HTTP/1.1 200 OK
Content-Type: video/mp4
Content-Length: 43768018
Accept-Ranges: bytes
```

이미지 예:

```http
HTTP/1.1 200 OK
Content-Type: image/jpeg
```

응답 본문은 JSON이 아니라 실제 파일의 바이너리 데이터다.

### cURL: 동영상 저장

```bash
curl -sS \
  --cacert server/nginx/certs/rootCA.crt \
  --ssl-no-revoke \
  -o test-video.mp4 \
  https://localhost:8443/api/v1/media/CNT_BATCH_0001
```

### cURL: 이미지 저장

```bash
curl -sS \
  --cacert server/nginx/certs/rootCA.crt \
  --ssl-no-revoke \
  -o test-image.jpg \
  https://localhost:8443/api/v1/media/CNT_BATCH_0002
```

### Header 확인

바이너리 파일을 터미널에 출력하지 않도록 본문을 버리고 Header만 확인한다.

```bash
curl -sS -D - -o /dev/null \
  --cacert server/nginx/certs/rootCA.crt \
  --ssl-no-revoke \
  https://localhost:8443/api/v1/media/CNT_BATCH_0001
```

### 404 응답

콘텐츠가 없거나 DB의 `source_path`가 비어 있거나 실제 파일이 없으면 404가 반환된다.

```bash
curl -sS -D - -o /dev/null \
  --cacert server/nginx/certs/rootCA.crt \
  --ssl-no-revoke \
  https://localhost:8443/api/v1/media/NOT_FOUND
```

## 6. 미디어 저장 규칙

Node watcher는 미디어를 다음 위치에 저장한다.

```text
호스트: server/content-store/YYYY-MM-DD/BATCH_ID/file_name
컨테이너: /app/content-store/YYYY-MM-DD/BATCH_ID/file_name
```

현재 DB의 `source_path`는 다음 컨테이너 기준 절대경로다. 호스트 저장 위치를 옮겨도 컨테이너 경로를 고정하면 기존 DB 값은 바꾸지 않아도 된다.

```text
/app/content-store/2026-09-25/BATCH_20260925_0001/장성 관광 홍보 영상.mp4
```

Spring Boot는 컨테이너의 다음 경로를 읽기 전용으로 사용한다.

```text
/app/content-store
```

경로에 `../`가 포함되어 content-store 외부로 벗어나는 요청은 차단한다.

제공업체의 인계 위치는 프로젝트 루트 `upload-store/YYYY-MM-DD/BATCH_ID`이며 내부 영구 보관소(`server/content-store`)와 목적이 다르다. 업체는 upload-store에 `contents.json`과 첨부를 전송하고 모든 전송이 끝난 뒤 `.ready`를 만든다. 처리 성공 후 watcher가 내부 보관소로 파일을 복사하고 원본 배치를 archive로 이동한다.

## 7. Android 구현 권장 흐름

현재 서버 API는 파일 다운로드 방식이므로 Android는 다음 흐름을 권장한다.

```text
1. content_id 확인
2. GET /api/v1/media/{content_id} 호출
3. 응답을 임시 파일로 저장
4. 다운로드 완료 확인
5. 파일을 로컬 콘텐츠 경로로 이동
6. 동영상 또는 이미지 표시
```

대용량 동영상은 응답 전체를 메모리에 저장하지 말고 파일 스트림으로 저장한다.

Android 의사 코드:

```text
response.body().byteStream()
    → FileOutputStream
    → 임시 파일
    → 완료 후 최종 파일명으로 이동
```

다운로드 중인 파일을 바로 표출하지 않고 임시 파일에 저장해야 앱 중단이나 네트워크 끊김으로 불완전한 파일이 표출되는 것을 막을 수 있다.

## 8. 동영상 Range 처리

현재 응답에는 다음 Header가 확인되었다.

```text
Accept-Ranges: bytes
```

Android가 파일을 먼저 다운로드한 뒤 재생하는 경우에는 별도 Range 요청 없이 사용할 수 있다.

다음 기능이 필요해지면 Range 처리 검증을 추가한다.

- 다운로드 중 재생
- 재생 위치 이동
- 중단 후 이어받기
- ExoPlayer의 URL 직접 재생

## 9. 장애 확인 순서

### 9.1 502 Bad Gateway

Nginx가 백엔드에 연결하지 못한 경우다.

```bash
podman compose ps
podman compose logs --tail=100 nginx
podman compose restart nginx
```

컨테이너 내부 연결 확인:

```bash
MSYS_NO_PATHCONV=1 podman exec smartbis_jangsung-nginx-1 \
wget -S -O /dev/null \
http://egov-backend:8080/api/actuator/health
```

### 9.2 404 Not Found

다음을 확인한다.

```bash
MSYS_NO_PATHCONV=1 podman exec smartbis_jangsung-postgres-db-1 \
psql -U smartbis_user -d smartbis_db \
-c "SELECT content_id, source_path FROM contents WHERE content_id = 'CNT_BATCH_0001';"
```

그리고 백엔드 컨테이너에서 파일을 확인한다.

```bash
MSYS_NO_PATHCONV=1 podman exec smartbis_jangsung-egov-backend-1 \
ls -l /app/content-store/2026-09-25/BATCH_20260925_0001
```

### 9.3 바이너리가 터미널에 출력되는 경우

`curl`에 `-o` 옵션이 없으면 동영상 데이터가 터미널에 출력되어 화면이 깨진 것처럼 보일 수 있다.

```bash
curl -sS -D - -o /dev/null URL
```

## 10. 현재 구현 범위

| 기능 | 상태 |
|---|---|
| HTTPS 8443 외부 진입 | 완료 |
| 사설 CA 인증서 | 완료 |
| Health API | 완료 |
| PostgreSQL 연동 | 완료 |
| 다중 콘텐츠 배치 저장 | 완료 |
| 동영상 다운로드 | 완료 |
| 이미지 다운로드 | API 구현 완료, 파일별 확인 필요 |
| 대용량 파일 Resource 스트리밍 | 완료 |
| 경로 조작 방지 | 완료 |
| HTTP Range 고급 시나리오 | Android 재생 방식 확정 후 검토 |
| 인증·인가 | 별도 요구사항 확정 후 구현 |

## 11. API 테스트 완료 기준

- [ ] `/api/actuator/health`가 200이고 DB가 UP인가
- [ ] 동영상 API가 `200`과 `video/mp4`를 반환하는가
- [ ] 이미지 API가 `200`과 올바른 이미지 MIME Type을 반환하는가
- [ ] 저장 파일 크기가 원본과 일치하는가
- [ ] 존재하지 않는 `content_id`가 404인가
- [ ] DB `source_path`가 현재 계약대로 `/app/content-store/...` 절대경로인가
- [ ] Spring Boot가 `/app/content-store`를 읽을 수 있는가
- [ ] Nginx 재생성 후에도 API가 동작하는가

## 부록 A. 관리자 시스템 상태 API

### Node watcher 상태 조회

관리자 세션이 필요하다. 대시보드의 `수집 상태` 화면에서 30초마다 호출하며, 요청 시 표출 순서를 진행시키거나 데이터를 변경하지 않는다.

```http
GET /api/v1/admin/system-status HTTP/1.1
Host: localhost:8443
Accept: application/json
```

예시 응답:

```json
{
  "status": "UP",
  "checkedAt": "2026-10-02T12:00:00+09:00",
  "nodeWatcher": {
    "status": "UP",
    "database": "UP",
    "watcher": "RUNNING",
    "processing": false,
    "uptimeSeconds": 120
  }
}
```

Node watcher의 내부 `GET /health`는 Compose 네트워크에만 노출되며 호스트 포트로 공개하지 않는다. 백엔드 프록시 API는 관리자 인증을 요구한다. watcher 또는 DB 상태를 확인하지 못하면 대시보드 상태는 `DEGRADED`로 표시한다.

### Android 단말 heartbeat 및 현재 표출

단말은 30초마다, 그리고 표출 상태가 바뀔 때 현재 상태를 전송한다. 이 POST는 화면 조회/표출 순서를 진행시키지 않는다. 관리자 대시보드 조회는 로그인 세션이 필요하다.

```http
POST /api/v1/device-status HTTP/1.1
Content-Type: application/json

{
  "vehicleNumber": "SAMPLE-001",
  "playbackStatus": "PLAYING",
  "displayType": "CARD_NEWS",
  "contentId": "CNT-001",
  "contentTitle": "장성 축제 안내",
  "appVersion": "1.0",
  "errorMessage": null
}
```

성공 시 `204 No Content`. `playbackStatus` 허용값은 `CONNECTING`, `PLAYING`, `WAITING`, `ERROR`, `OFFLINE_PLAYBACK`이다. 서버는 차량별 최신 상태만 upsert하고 서버 수신 시각을 기록한다. POST는 Android 앱의 관리자 세션이 없으므로 인증/CSRF 없이 허용하되, 필드 길이와 차량번호·상태 형식을 검증한다. 화면의 상태 요약 외 별도 개인정보는 수집하지 않는다.

관리자 조회:

```http
GET /api/v1/admin/device-status HTTP/1.1
```

모니터는 마지막 보고 시각이 90초 이내이면 온라인, 그보다 오래되면 오프라인으로 표시한다. 조회 전용이며 `/display/next`를 호출하지 않는다.

## 부록 B. TAGO 노선·정류소 카탈로그 관리자 API

두 API 모두 관리자 로그인과 CSRF 토큰이 필요하다. 브라우저는 `GET /api/csrf` 응답의 `headerName`/`token`을 변경 요청 헤더로 전달한다.

### 현재 DB 카탈로그 상태

```http
GET /api/v1/admin/tago/catalog
```

응답에는 설정된 도시코드, 노선 수, 고유 정류소 수, 마지막 노선 동기화 시각 및 `SYNCED`/`NOT_SYNCED` 상태가 포함된다. 데이터가 아직 동기화되지 않았다면 0건으로 응답한다.

### 장성군 카탈로그 수동 동기화

```http
POST /api/v1/admin/tago/catalog/sync
```

서버의 `.env`에서 `TAGO_SERVICE_KEY`, `TAGO_SERVICE_KEY_MODE`, `TAGO_CITY_CODE`를 읽는다. 서비스키는 요청 query에만 사용하며 로그와 응답에 남기지 않는다. `AUTO`, `ENCODED`, `DECODED` 키 형식을 지원한다. 노선번호목록을 페이지 단위로 가져온 후 각 노선의 경유 정류소 목록과 좌표/순번을 조회한다. 외부 응답이 모두 검증된 뒤 PostgreSQL transaction 하나로 다음 테이블에 upsert한다.

- `bus_routes`: route ID, 노선번호, 도시코드, 노선유형
- `bus_stops`: 정류소 ID/이름, 도시코드, 위도/경도. 기존 행정 지역 매핑 컬럼은 유지한다.
- `bus_route_stops`: 노선별 정류소 순번 및 TAGO `updowncd` 기반 방향

성공 응답 예:

```json
{
  "status": "SUCCESS",
  "cityCode": "36450",
  "routeCount": 105,
  "routeStopCount": 1234,
  "uniqueStopCount": 456,
  "syncedAt": "2026-10-02T12:00:00+09:00"
}
```

숫자는 응답 형식 예시이며 현재 운영 DB 적재를 뜻하지 않는다. TAGO 오류, 잘못된 XML/필수 필드, DB 오류가 발생하면 HTTP 오류를 반환하고 해당 실행에서 가져온 노선 카탈로그 데이터는 DB에 반영하지 않는다. 노선 카탈로그는 매일 03:00에 갱신한다. 차량위치 수집은 별도 TAGO 위치 API를 호출해 30초 주기로 실행한다. 두 기능은 각각 `TAGO_ENABLED=true`, `TAGO_MOCK_MODE=false`, `TAGO_VEHICLE_LOCATION_ENABLED=true` 설정이 필요하며 서비스키는 서버에서만 사용한다.

## 부록 C. TAGO 실시간 차량 위치 및 도착 예측

### 외부 TAGO 위치 API

```http
GET http://apis.data.go.kr/1613000/BusLcInfoInqireService/getRouteAcctoBusLcList
    ?serviceKey={server-side-key}
    &cityCode=36450
    &routeId={bus_routes.route_id}
    &numOfRows=10
    &pageNo=1
    &_type=xml
```

TAGO 위치 API는 요청-응답 방식이다. 서버 수집기가 활성 노선별로 페이지 조회하고 `resultCode=00`인 정상 응답의 `vehicleno`, `gpslati`, `gpslong`, `nodeid`, `nodeord`를 읽는다. API 키는 Android 앱에 배포하지 않는다.

### 서버 처리

- `bus_vehicles`: TAGO 응답의 차량번호와 관측 노선을 upsert한다.
- `bus_positions`: GPS, TAGO 정류소 ID·순번, 관측 시각과 방향을 기록한다. 방향은 같은 route/node/order에 UP 또는 DOWN 하나만 매칭되는 경우에만 확정한다.
- `GET /api/v1/arrival?vehicleNumber=...`: 최근 2분 이내 위치를 사용해 저장된 동일 방향 경로의 다음 정류소까지 직선거리를 계산하고 `arrival_predictions`에 기록한다. 응답에는 TAGO 도착정보의 route+next-stop ETA를 초 단위에서 올림한 `estimatedArrivalMinutes`(정수 또는 `null`)가 포함된다. 차량번호를 포함하지 않는 노선·정류소 기준 ETA이므로 개별 버스의 ETA라고 보장하지 않는다.
- Android가 `/api/v1/display/next?vehicleNumber=...`를 호출하면 기존 DisplayService가 도착 예측의 100m 여부와 정책 우선순위로 표출 항목을 선택한다.

표출 가능한 도착정보·재난정보·콘텐츠가 없으면 이 API는 `204 No Content`를 반환한다. 이는 정상적인 대기 상태이며 클라이언트는 오류 화면 대신 대기 문구를 표시하고 다음 자동 조회에서 다시 확인한다. 경로 자체가 존재하지 않는 경우는 계속 `404 Not Found`이다.

### 관리자 상태

```http
GET /api/v1/admin/system-status
```

응답의 `tagoVehicleLocation`은 `status`(DISABLED, MOCK_MODE, UP, DEGRADED 등), `enabled`, `mockMode`, `pollIntervalMs`, `lastCompletedAt`, `lastSavedCount`, `failedRouteCount`를 제공한다. 실차 응답 0건은 정상일 수 있으며, 위치를 임의 생성하지 않는다. 차량 위치 API의 실제 장성군 응답은 승인된 키로 읽기 전용 확인 후 서버에 연결한다.

## 부록 D. 정류소 행정지역 분류 관리자 API

아래 API는 `/v1/admin/**`에 속하므로 관리자 로그인 세션이 필요하다. `POST`와 `PUT`에는 `GET /api/csrf`의 `headerName`/`token`을 변경 요청 헤더로 전달한다. 조회·변경 대상은 서버의 `TAGO_CITY_CODE`(현재 장성군 `36450`) 범위다. TAGO가 제공하지 않는 읍·면 정보를 시스템이 좌표만으로 추정하지 않는다.

### 지역 그룹 조회 및 생성

```http
GET /api/v1/admin/regions
POST /api/v1/admin/regions
Content-Type: application/json
```

생성 요청:

```json
{
  "regionCode": "JANGSEONG_EUP",
  "regionName": "장성읍",
  "parentRegionName": "장성군"
}
```

목록은 지역 코드·이름·상위 지역명·활성 여부와 연결된 장성군 정류소 수를 포함한다. `regionName`은 필수 1~100자, `regionCode`는 선택 최대 50자다. 이름 또는 코드가 중복되면 HTTP 409다.

### 정류소 조회·검색

```http
GET /api/v1/admin/tago/stops?q=장성&unassignedOnly=true&limit=50&offset=0
GET /api/v1/admin/tago/stops?regionId=1&limit=50&offset=0
```

정류소 ID/이름 부분 검색, 특정 지역 조회, 미분류 필터 및 페이지 조회를 제공한다. `regionId`와 `unassignedOnly=true`는 동시에 사용할 수 없고, 페이지 크기는 1~200이다. 응답은 `{ "items": [...], "total": 123, "limit": 50, "offset": 0, "cityCode": "36450" }` 형태다.

### 정류소 지역 지정 또는 해제

```http
PUT /api/v1/admin/tago/stops/{nodeId}/region
Content-Type: application/json
```

```json
{"regionId": 1}
```

`regionId`는 활성 지역 ID여야 한다. 분류 해제는 필드를 생략하는 대신 `{"regionId": null}`로 명시한다. 성공 응답에는 정류소 ID/이름과 저장 지역을 반환한다. 해당 장성군 정류소 또는 활성 지역이 없으면 HTTP 404다. DB의 `region_id`와 `region_name`을 함께 저장해 콘텐츠 지역 필터에 반영한다.

이 API는 서버 구현 범위다. 수집 상태 화면에서 지역 등록, 정류소 검색·페이지 이동, 지역 지정/해제를 제공한다. 실제 읍·면 기준표 입력 및 로그인 관리자 세션/CSRF를 이용한 저장 종단 검증은 별도 완료 전까지 미검증이다.

## 부록 E. 콘텐츠 데이터 계약 v2 (2026-10-04 적용)

### 배치 전달

```text
upload-store/YYYY-MM-DD/BATCH_ID/
├── contents.json
├── 첨부파일...
└── .ready                 # 전송을 모두 마친 뒤 마지막에 생성
```

신규 제공자는 `schema_version: "2.0"`을 보내고, 배치 `batch_id`는 폴더명과 같아야 한다. 하나의 manifest 안에 여러 콘텐츠를 넣을 수 있으며 DB에서는 `content_id` 하나당 한 행을 저장한다. 수정 재전송은 같은 ID를 사용해 UPSERT하고 새 자료는 새 ID를 발급한다. 기존 schema_version 생략/`1.0` 단일 `content_type` + `target_file_name` 형식도 수집기가 호환 처리한다.

### 분류와 첨부 계약

- `category`: `NOTICE` 또는 `EVENT` (업무 목적)
- `presentation_type`: `POSTER_TEXT`, `TEXT`, `CARD_NEWS`, `IMAGE`, `VIDEO` (화면 구성)
- `title`, `content`, `template`: 문자열 필수. `content`는 TEXT/POSTER_TEXT에서 비어 있을 수 없으며 매체 전용 형식은 빈 문자열 허용.
- `display_start_date`, `display_end_date`, `target_regions`: 선택 및 null 허용. 날짜는 `YYYY-MM-DD`, 지역 제한 없음은 null.
- `assets`: 각 항목은 `role`, `file_name`, 양의 정수 `sequence` 포함. 경로 구분자, `..`, 중복 순번을 거부한다. 모든 파일은 배치 폴더 안에 있어야 한다.

| `presentation_type` | 본문/첨부 규칙 | legacy `content_type` 매핑 |
|---|---|---|
| `POSTER_TEXT` | 본문 + POSTER 한 개 | CARD |
| `TEXT` | 본문, 첨부 없음 | CARD |
| `CARD_NEWS` | IMAGE 두 개 이상, sequence 순서 | CARD |
| `IMAGE` | IMAGE 한 개 | IMAGE |
| `VIDEO` | VIDEO 한 개 | VIDEO |

`category`와 표현형식은 서로 독립이다. 재난정보는 이 외부 업체의 NOTICE/EVENT 배치로 위장하지 않고 재난 API/시스템 데이터로 관리한다. Node watcher는 `.ready`, manifest 및 첨부 안정화를 확인하고 SHA-256/크기/MIME을 계산한다. 파일은 호스트 `server/content-store/YYYY-MM-DD/BATCH_ID/`에 보관한다. DB `content_assets.file_path`는 이 저장소 기준 상대경로이며 `/app/content-store`는 컨테이너 mount 경로다.

### 응답·미디어 API

콘텐츠 목록과 `/api/v1/display/next` 응답에 `category`, `presentationType`, 정렬된 `assets`를 추가했다. 기존 `contentType`, `targetFileName`, `mediaUrl`은 호환을 위해 유지한다. 개별 자산은 다음 경로로 조회한다.

```http
GET /api/v1/media/{contentId}/assets
GET /api/v1/media/{contentId}/assets/{sequence}
```

자산 목록에는 `role`, `fileName`, `sequence`, `mimeType`, `sizeBytes`, `sha256`, `mediaUrl`이 들어간다. 백엔드는 content root 밖으로 나가는 경로를 거부하고 파일을 스트리밍한다. 적용 SQL은 `server/database/migrations/V010__content_data_contract_v2.sql`이며 기존 `contents` 행과 legacy 컬럼을 삭제하지 않는 additive 변경이다.

### 구현 검증 기록

2026-10-04 한 배치로 포스터+본문, 텍스트 전용, 이미지 2개 카드뉴스, 단일 이미지, 동영상 5건을 실제 watcher에 투입했다. DB의 업무 분류/표현 타입/첨부 역할·순번 및 새 파일 SHA-256을 확인했고, 목록 API에서 전체 첨부 메타데이터를 확인했다. 카드뉴스 두 번째 이미지 URL은 HTTP 200, `image/jpeg`, 6,436,410 bytes를 반환했다. Node watcher 테스트 11개, Maven 테스트 29개, Android `testDebugUnitTest assembleDebug` 빌드가 통과했다. v2 시험 배치에는 활성 `content_schedules`를 만들지 않아 실제 차량 표출 순환에 끼어들지 않는다. 24인치 기기에서 서로 다른 이미지의 시각 전환을 확인하는 것은 후속 화면 인수시험이다.
