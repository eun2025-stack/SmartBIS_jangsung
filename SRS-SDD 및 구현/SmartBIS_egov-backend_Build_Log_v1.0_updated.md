# SmartBIS egov-backend 구축 및 변경 이력

## 1. 실행 구조

`egov-backend`는 외부 Tomcat 설치 없이 Spring Boot 내장 Tomcat으로 실행한다.

```text
Nginx HTTPS :443
        ↓
Spring Boot embedded Tomcat :8080
        ↓
PostgreSQL :5432
```

Spring Boot 컨테이너의 8080 포트는 Compose 네트워크에만 노출하며 Windows 호스트에 직접 publish하지 않는다.

## 2. 빌드 환경

호스트에 Maven이 설치되어 있지 않아도 Maven 컨테이너로 빌드할 수 있다.

```bash
cd /d/PROJECT/SmartBIS_jangsung/server/egov-backend

MSYS_NO_PATHCONV=1 podman run --rm \
  -v "D:/PROJECT/SmartBIS_jangsung/server/egov-backend:/app" \
  -w /app \
  docker.io/library/maven:3.9-eclipse-temurin-17 \
  mvn clean package -DskipTests
```

Git Bash에서 `/app`이 `C:/Program Files/Git/app`으로 변환되면 `MSYS_NO_PATHCONV=1`을 반드시 사용한다.

## 3. 현재 애플리케이션 설정

```properties
server.port=8080
server.servlet.context-path=/api
spring.datasource.url=jdbc:postgresql://postgres-db:5432/${POSTGRES_DB}
spring.datasource.username=${POSTGRES_USER}
spring.datasource.password=${POSTGRES_PASSWORD}
content.root=${CONTENT_ROOT:/app/content-store}
```

정상 기동 로그:

```text
Tomcat started on port 8080 with context path '/api'
Started SmartBisApplication
```

## 4. DB 연동 확인

컨테이너 내부 health endpoint:

```bash
podman run --rm --network smartbis_jangsung_default \
  docker.io/curlimages/curl:8.10.1 \
  http://egov-backend:8080/api/actuator/health
```

정상 결과는 `status: UP`, `db.status: UP`이다.

## 5. 다중 콘텐츠 DB 구조

Node watcher가 하나의 배치 JSON을 여러 콘텐츠로 분리하여 `contents` 테이블에 콘텐츠별 행으로 저장한다.

```text
content_id       batch_id              content_type
CNT_BATCH_0001   BATCH_20260925_0001   VIDEO
CNT_BATCH_0002   BATCH_20260925_0001   IMAGE
```

동일한 `content_id`가 재수신되면 UPSERT되므로 수정 콘텐츠는 기존 `content_id`를 유지한다.

## 6. content-store 연결

`node-watcher`는 `content-store`에 쓰기 권한을 갖고, `egov-backend`는 읽기 전용으로 연결한다.

```yaml
egov-backend:
  environment:
    CONTENT_ROOT: /app/content-store
  volumes:
    - ./content-store:/app/content-store:ro
```

초기 설계 기록에는 `source_path`를 `content-store` 기준 상대경로로 두는 안이 적혀 있으나, 현재 적용된 watcher 구현과 DB 값은 컨테이너 절대경로다. 실제 저장 형식은 본 문서 말미의 2026-10-02 정정을 기준으로 한다.

```text
2026-09-25/BATCH_20260925_0001/장성 관광 홍보 영상.mp4
```

## 7. 미디어 다운로드 API

다음 API를 추가한다.

```text
GET /api/v1/media/{content_id}
```

예:

```bash
curl --cacert server/nginx/certs/rootCA.crt \
  --ssl-no-revoke \
  -o test-video.mp4 \
  https://localhost:8443/api/v1/media/CNT_BATCH_0001
```

구현 원칙:

- `JdbcTemplate`으로 `source_path`를 조회한다.
- DB 경로를 `content.root` 기준으로 해석하고 정규화한다. 현재 DB 값은 컨테이너 절대경로이며 Java `Path.resolve`는 절대 입력이면 그 절대경로를 유지한다.
- `normalize()` 후 content root 하위인지 검사한다.
- `FileSystemResource`로 응답한다.
- `byte[]`로 전체 동영상을 메모리에 적재하지 않는다.
- 존재하지 않는 콘텐츠와 파일은 HTTP 404로 응답한다.
- 잘못된 경로 조작은 차단한다.

## 8. 미디어 API의 현재 범위와 향후 범위

현재 구현은 파일 다운로드와 순차 스트리밍에 필요한 기능이다. Android가 파일을 먼저 내려받아 재생하는 구조라면 이것으로 충분하다.

다음 조건이면 HTTP Range 지원을 추가한다.

- 다운로드 중 동영상 재생이 필요할 때
- 재생 위치 이동(seek)이 필요할 때
- 네트워크 중단 후 이어받기가 필요할 때
- ExoPlayer가 API를 직접 재생할 때

Range는 현재 필수 기능으로 확정하지 않고 Android 재생 방식 결정 후 추가한다.

## 9. 빌드 및 재기동

```bash
cd /d/PROJECT/SmartBIS_jangsung/server/egov-backend

MSYS_NO_PATHCONV=1 podman run --rm \
  -v "D:/PROJECT/SmartBIS_jangsung/server/egov-backend:/app" \
  -w /app \
  docker.io/library/maven:3.9-eclipse-temurin-17 \
  mvn clean package -DskipTests

cd /d/PROJECT/SmartBIS_jangsung
podman compose build egov-backend
podman compose up -d egov-backend nginx
```

## 10. 최종 점검

```bash
podman compose ps
podman compose logs --tail=100 egov-backend
curl --cacert server/nginx/certs/rootCA.crt \
  --ssl-no-revoke \
  https://localhost:8443/api/actuator/health
curl --cacert server/nginx/certs/rootCA.crt \
  --ssl-no-revoke \
  -o test-image.jpg \
  https://localhost:8443/api/v1/media/CNT_BATCH_0002
```

## 11. 결론

`egov-backend`는 외부 WAS에 의존하지 않고 Spring Boot 내장 WAS로 실행한다. Nginx가 유일한 외부 HTTPS 진입점이며, 미디어 파일은 Node watcher가 저장하고 Spring Boot가 읽기 전용 스트리밍 API로 제공한다.

## 12. 운영 대시보드 관리자 인증 및 상태 관리 (2026-10-02)

### 적용 범위

- 대시보드는 `src/main/resources/static/dashboard/` 아래에 제공한다.
- `/api/dashboard/login.html`에서 폼 로그인을 하고 `/api/dashboard/index.html`은 `ADMIN` 역할 사용자만 접근한다.
- 관리자 계정은 환경변수 `DASHBOARD_ADMIN_USERNAME` 및 `DASHBOARD_ADMIN_PASSWORD`에서 읽는다. `.env` 파일과 실제 비밀번호는 문서에 기록하지 않는다.
- 세션 기반 인증을 사용한다. CSRF 토큰은 `GET /api/csrf`에서 받아 상태 변경 요청에 포함한다.
- `/api/v1/admin/**`은 관리자 인증 대상이고, Android가 사용하는 공개 콘텐츠·미디어·표출 API는 로그인으로 막지 않는다.

### 관리 API

```text
GET /api/v1/admin/contents?status=ACTIVE|PAUSED|REMOVED|ALL
PUT /api/v1/admin/contents/{contentId}/publication-state
GET /api/v1/admin/display-policies
PUT /api/v1/admin/display-policies/{policyKey}
GET /api/v1/admin/ingest-errors/summary
GET /api/v1/admin/ingest-errors
```

게시 중지·재개·게시 제외·복구는 콘텐츠 원본 행과 미디어 파일을 지우지 않고 운영 상태를 별도 저장한다. 관리 API 변경은 로그인 사용자로 기록한다. 구체적인 화면 합의와 버튼 동작은 [UI 협의 및 구현 경계](SmartBIS_UI_협의사항_20261001.md)를 참조한다.

### 인증 흐름에서 발견한 포트 누락

사용자가 `https://localhost:8443/api/dashboard/index.html`을 입력했을 때 로그인 경로로 이동하면서 주소가 `https://localhost/api/dashboard/login.html`로 바뀌는 문제가 있었다. Nginx의 기존 `proxy_set_header Host $host;`가 외부 `:8443` 포트를 제거한 채 Spring Boot로 전달한 것이 원인이었다.

Nginx `location /api/`에서 외부 Host를 유지하도록 수정했다.

```nginx
proxy_set_header Host $http_host;
proxy_set_header X-Forwarded-Host $http_host;
proxy_set_header X-Forwarded-Proto $scheme;
```

Spring Security에는 `/dashboard/**`의 비인증 요청을 `/dashboard/login.html`로 보내는 진입점을 명시하고, `/v1/admin/**` 비인증 요청은 API 응답으로 `401`을 반환하도록 분리했다. 로그아웃은 브라우저 fetch가 잘못된 절대 URL 리다이렉트를 따라가지 않도록 `204 No Content`를 반환하도록 했다. 대시보드 JavaScript가 성공 응답을 확인한 뒤 같은 출처의 로그인 페이지로 이동한다.

### 2026-10-02 검증 결과

- Maven 컨테이너 `mvn clean package -DskipTests`: `BUILD SUCCESS`.
- `egov-backend` 이미지를 다시 빌드하고 `egov-backend`, `nginx`를 재생성했다. PostgreSQL과 Node watcher는 재기동하지 않았다.
- 로그인하지 않은 요청 `GET https://localhost:8443/api/dashboard/index.html`에서 `302`를 받았고, 응답 `Location`은 `https://localhost:8443/api/dashboard/login.html`이었다. 공개 포트가 유지됨을 확인했다.
- 바로 직후의 첫 요청은 백엔드가 시작 중이라 `502`였고, 잠시 기다린 재요청에서 위의 `302`를 확인했다. 재배포 직후에는 로그와 health 상태가 준비된 뒤 테스트해야 한다.
- 로그인 후 실제 브라우저 로그아웃 버튼을 눌러 `204` 및 로그인 화면 복귀를 확인하는 종단 테스트는 아직 완료되지 않았다.

### 남은 확인

1. 브라우저에서 관리자 로그인 후 데이터 관리 조회와 CSRF 보호 상태 변경을 확인한다.
2. 로그아웃 버튼을 눌러 세션 쿠키가 무효화되고 로그인 화면으로 돌아오는지 확인한다.
3. 로그인하지 않은 관리자 API가 `401`, Android 공개 콘텐츠 API가 정상 응답하는지 함께 확인한다.
4. migration 파일의 `V005` 번호 충돌은 2026-10-02 `display_states` 파일명을 `V006__display_states.sql`로 변경해 해결했다. 실제 DB 반영 여부는 migration runner/이력 테이블이 없어 별도 read-only 스키마 확인이 필요하다. DB 볼륨 초기화는 하지 않는다.
# SmartBIS 테스트 문제 및 해결 보강 기록 v1.1

기존 egov-backend, Nginx, Node watcher 문서에 공통으로 추가할 실제 장애 대응 기록이다.

## Maven 빌드

호스트에 Maven이 없으면 Maven 컨테이너로 빌드한다.

```bash
MSYS_NO_PATHCONV=1 podman run --rm \
  -v "D:/PROJECT/SmartBIS_jangsung/server/egov-backend:/app" \
  -w /app \
  docker.io/library/maven:3.9-eclipse-temurin-17 \
  mvn clean package -DskipTests
```

Git Bash의 경로 변환을 막지 않으면 `/app`이 `C:/Program Files/Git/app`으로 바뀌어 workdir 오류가 발생한다.

## Nginx 502 Bad Gateway

실제 원인은 백엔드 컨테이너 재생성 후 IP가 바뀌었지만 Nginx가 이전 IP를 사용한 것이었다.

```text
connect() failed (113: Host is unreachable) while connecting to upstream
upstream: http://10.89.0.4:8080/...
```

해결:

```bash
podman compose restart nginx
```

또는:

```bash
podman compose up -d --force-recreate egov-backend
podman compose up -d --force-recreate nginx
```

연결 확인:

```bash
MSYS_NO_PATHCONV=1 podman exec smartbis_jangsung-nginx-1 \
wget -S -O /dev/null \
http://egov-backend:8080/api/actuator/health
```

`HTTP/1.1 200`이면 Nginx와 백엔드 연결은 정상이다.

## Git Bash에 Nginx 설정을 입력한 문제

다음은 Bash 명령이 아니라 `server/nginx/nginx.conf` 파일 내부 설정이다.

```nginx
location /api/ {
    proxy_pass http://egov-backend:8080;
}
```

반영 후:

```bash
podman exec smartbis_jangsung-nginx-1 nginx -t
```

## 동영상이 터미널에 출력된 문제

동영상 API에 `-o`가 없으면 바이너리가 터미널에 출력되어 화면이 깨진다. `Ctrl+C`로 중단한다.

Header만 확인:

```bash
curl -sS -D - -o /dev/null URL
```

파일 저장:

```bash
curl -sS -o test-video.mp4 URL
```

## Podman 포트 전달 문제

컨테이너 내부 호출은 성공하지만 Windows의 `localhost:8443` 연결이 거부될 수 있다.

```bash
podman machine stop
podman machine set --user-mode-networking=true podman-machine-default
podman machine set --rootful=false podman-machine-default
podman machine start
```

## Node watcher의 .ready 문제

`.ready`가 없으면 배치가 있어도 처리하지 않는다. 상위 폴더를 먼저 만든다.

```bash
mkdir -p upload-store/2026-09-25/BATCH_20260925_0001
touch upload-store/2026-09-25/BATCH_20260925_0001/.ready
```

모든 파일 전송 후 마지막에 `.ready`를 생성해야 한다.

## watcher 테스트가 종료되지 않는 문제

watcher는 운영 중 계속 감시하므로 처리 완료 후에도 종료되지 않는 것이 정상이다. 즉시 처리 테스트 후 `Ctrl+C`로 종료한다.

## JSON 주석 문제

실제 JSON에는 `// 필수` 같은 주석을 넣으면 안 된다. 주석은 JSON 파싱 오류를 만든다.

## 정정: `source_path`의 실제 저장 형식 (2026-10-02)

이 누적 로그의 앞선 설명 중 DB `source_path`가 `content-store` 기준 상대경로라고 한 내용은 현재 구현과 맞지 않아 정정한다. Node watcher는 파일을 `content-store`로 복사한 뒤 컨테이너 기준 절대경로를 DB에 저장한다.

```text
/app/content-store/2026-09-25/BATCH_20260925_0001/파일명.mp4
```

이 형식은 실제 조회 결과(`/app/content-store/2026-09-25/BATCH_20260925_0001/...`)로 확인됐다. Backend는 `content.root` 아래인지 정규화해 검사한 후 `FileSystemResource`로 제공한다. 개발·운영 환경에서 동일한 컨테이너 mount 경로를 유지해야 하며, 향후 상대경로로 이관하려면 기존 DB 값 변환과 API 호환 계획을 별도로 수립해야 한다.

위 정정은 이 문서에서 반복된 동일한 “미디어 경로 문제” 설명에도 적용한다.

## 최종 API 정상 판정

```text
HTTP/1.1 200
Content-Type: video/mp4
Content-Length: 원본 파일 크기
Accept-Ranges: bytes
```

## DB migration 파일 번호 중복 해소 (2026-10-02)

### 확인된 문제

저장소에 `V005__content_publication_controls.sql` 및 `V005__display_states.sql`이 함께 있어 정렬 순서가 파일명에 의존하고 새로 구축할 때 적용 이력이 모호했다. 백엔드 Maven 설정에는 Flyway/Liquibase가 없고 migration 이력 테이블도 확인되지 않아, 파일명 변경만으로 기존 DB 적용 상태를 추정할 수 없다.

### 안전한 파일 변경

`V005__display_states.sql`을 `V006__display_states.sql`로 변경했다. 기존 DB에 해당 테이블이 이미 수동 생성되어 있을 수 있어 SQL은 `CREATE TABLE IF NOT EXISTS`를 사용한다. 이번 작업은 migration 파일만 수정했으며 실행 중인 PostgreSQL에 접속하거나 SQL을 적용하지 않았고, 볼륨·테이블·데이터도 수정하지 않았다.

당시 파일 순서는 `V001` contents, `V002` operations, `V003` batch_id, `V004` display policies, `V005` publication controls, `V006` display states였다. 이후 2026-10-02 `V007__arrival_display_state.sql`을 추가·적용했다. 기존 V005 파일 두 개 중 어느 것을 어떤 순서로 실행했는지는 수동 실행 기록과 실제 DB 스키마를 확인해야 한다. migration ledger는 여전히 없어 이후 SQL도 적용 전후를 문서에 기록한다. 재초기화나 `podman compose down -v`는 해결책이 아니다.

## 지역 제한 콘텐츠 선택과 도착정보 반복 방지 (2026-10-02)

### 동작 변경

- `ArrivalService`가 거리 계산에 사용한 current/next 정류소 ID를 `arrival_predictions.current_node_id` 및 `next_node_id`에 함께 저장한다. 해당 컬럼은 기존 운영 스키마에 이미 있으며 새 migration은 추가하지 않았다.
- `/api/v1/display/next`는 동일한 노선/다음 정류소 arrival를 매 요청마다 반복하지 않고 한 번 표출한 뒤 다음 우선순위로 진행한다. 다음 정류소 ID가 바뀌면 arrival를 다시 우선한다.
- 콘텐츠 후보는 최신 유효 arrival 예측이 100m 안에 있을 때 next stop의 `bus_stops.region_name`, 없으면 연결된 `regions.region_name`으로 `target_regions`를 필터링한다. 차량 위치 또는 지역을 알 수 없을 때는 지역 제한 콘텐츠를 제외하고 전역 콘텐츠만 허용한다.

### 시험 및 적용

새 이미지를 운영 백엔드에 덮어쓰기 전에 임시 검증 컨테이너로 실행해 PostgreSQL의 고유 합성 데이터로 확인했다. 지역 차량은 첫 요청에서 `ARRIVAL`, 같은 정류소에 대한 다음 요청에서 지역 전용 콘텐츠를 받았고, 지역 예측이 없는 별도 차량은 전역 콘텐츠를 받았다. 합성 DB 행을 모두 제거한 뒤 임시 컨테이너를 삭제했다.

검증된 이미지를 `egov-backend`에 반영한 직후 Nginx가 이전 백엔드 컨테이너 IP를 계속 사용해 일시적으로 502가 발생했다. Nginx 로그의 이전 upstream 주소와 새 컨테이너 IP 차이를 확인한 후 Nginx만 재생성해 HTTPS health `UP`을 확인했다. DB 볼륨과 콘텐츠 데이터는 초기화하거나 삭제하지 않았다.

이는 합성 정류소/지역의 통합시험이며 실제 TAGO 응답, 실제 지역명 매핑, 100m 현장 정확도를 검증한 것은 아니다. 도착 우선순위 값은 여전히 코드에서 1000이며 모든 정책 DB 값과의 통합 및 장시간 차량 운행 시험도 남아 있다.

후속 점검에서 도착정보 중복 억제 뒤에도 콘텐츠 cursor가 유지되어야 함을 확인해 V007을 추가했다. arrival key와 마지막 표출된 콘텐츠 key를 분리 보존하고, 동일 정류소 arrival는 정책 반복 간격 전에는 억제하며 활성 예측이 끝나면 억제 상태를 초기화한다. 고유 합성 자료를 사용해 `ARRIVAL → 지역 콘텐츠 1 → 지역 콘텐츠 2`, 지역 미상 차량은 전역 콘텐츠 선택을 다시 확인했다. V007의 두 컬럼을 현 DB에 적용하고 새 백엔드를 배포했으며 Nginx stale upstream 문제를 해결한 뒤 HTTPS health `UP`을 확인했다. 모든 시험 자료와 임시 컨테이너를 제거했다.

## TAGO 위치조회·장성군 도시코드·노선목록 검증 (2026-10-02)

### 사용자의 포털 미리보기

사용자는 `BusLcInfoInqireService/getRouteAcctoBusLcList`에 `cityCode=25`, `routeId=DJB30300052`를 넣어 미리보기 응답이 정상 표출된다고 확인했다. 공공데이터포털 [버스위치정보 명세](https://www.data.go.kr/data/15098533/openapi.do)에도 이 값들은 요청 예시로 기재되어 있다. 따라서 이 사례는 장성군 실노선 확인이 아니라 위치조회 형식과 예시 호출이 가능한지 보여주는 참고 결과다.

### `.env` 서버 키로 직접 실행한 읽기 전용 확인

서비스 키는 요청에만 사용했고 터미널 출력이나 문서에는 남기지 않았다.

| 호출 | 결과 | 해석 |
|---|---|---|
| `BusLcInfoInqireService/getRouteAcctoBusLcList?cityCode=25&routeId=DJB30300052` | HTTP 200, `resultCode=00`, `NORMAL SERVICE.`, `totalCount=7` | 현재 `.env` 키는 위치조회 오퍼레이션에 유효. 다만 문서 예시 노선임 |
| `BusSttnInfoInqireService/getCtyCodeList` | HTTP 200, `resultCode=00`; 장성군 `cityCode=36450` 확인 | 실제 장성군 도시코드를 서버 키로 조회함 |
| `BusRouteInfoInqireService/getRouteNoList?cityCode=36450` | HTTP 403, `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`, reason 30 | 노선목록 오퍼레이션에서 현재 서버 키가 등록되지 않은 것으로 응답함. 장성군 노선 ID는 아직 조회하지 못함 |

프로젝트 첨부 [버스노선정보 활용가이드](오픈API활용가이드_국토교통부(TAGO)_버스노선정보v1.0.docx)는 노선번호목록 오퍼레이션을 `getRouteNoList`로 정의한다. 앞선 진단에서는 `getCtyCodeList`를 `BusRouteInfoInqireService` 경로로 호출했으나, 사용자가 지적한 대로 장성군 도시코드 조회에 사용한 올바른 경로는 `BusSttnInfoInqireService/getCtyCodeList`다. 따라서 잘못된 BusRouteInfo의 `getCtyCodeList` 403은 노선목록 API의 유효한 시험 결과가 아니며, 위 표의 `getRouteNoList` 직접 결과로 대체한다.

**승인 전 당시 기록:** 공공데이터포털 [버스정류소정보](https://www.data.go.kr/data/15098534/openapi.do)와 [버스노선정보](https://www.data.go.kr/data/15098529/openapi.do)는 각각 별도 API 서비스로 명세된다. 당시 서버 키는 정류소 도시코드 및 위치조회에서는 성공했지만 노선번호목록 API에서는 실패했다. 이후 사용자가 버스노선정보 활용 신청·승인을 완료했고, 바로 아래의 승인 후 결과로 재검증했다. TAGO collector는 여전히 미구현이며 `TAGO_ENABLED=false`, `TAGO_MOCK_MODE=true`이므로 자동 호출·DB 적재는 하지 않는다.

수집 상태 대시보드도 이 구분(장성군 `36450` 확인, 노선목록 API 키 미등록, 위치조회 샘플 성공, 자동 수집 미구현)으로 수정했다. Maven 컨테이너로 JAR를 다시 패키징하고 backend 이미지를 빌드·재기동했다. 첫 health 호출은 백엔드 컨테이너 IP 변경 뒤 Nginx stale upstream으로 502였으며 Nginx만 재생성한 뒤 HTTPS health `{"status":"UP"}`을 확인했다. PostgreSQL volume/데이터와 Node watcher는 건드리지 않았다.

### 버스노선정보 API 승인 후 재검증 (2026-10-02)

사용자가 국토교통부(TAGO) 버스노선정보 API 활용 신청·승인이 완료되었다고 알렸다. 앞 절의 HTTP 403은 승인 전 시점의 결과로 보존하고, 같은 서버 `.env` 키로 승인 후 read-only 재검증을 수행했다. 서비스키는 요청에만 사용했으며 출력/문서에 기록하지 않았다.

| 호출 | 승인 후 결과 | 의미 |
|---|---|---|
| `BusRouteInfoInqireService/getCtyCodeList` | HTTP 200, `resultCode=00`, `NORMAL SERVICE.`; 장성군 `cityCode=36450` | 노선정보 서비스의 도시코드 API 접근 승인 확인 |
| `BusRouteInfoInqireService/getRouteNoList?cityCode=36450&pageNo=1&numOfRows=100` | HTTP 200, `resultCode=00`, 전체 `totalCount=105` | 장성군 노선 ID/번호 목록 조회 성공 |

정류소정보 API의 도시코드 응답도 장성군 `36450`으로 기존 확인과 일치했다. 다만 노선목록 105개를 조회한 것만으로 현재 운행 중인 특정 버스의 routeId나 차량번호 일치가 확인된 것은 아니다. 위치정보 예시 `cityCode=25`, `routeId=DJB30300052`의 7건 응답은 별도 예시 데이터이며 장성군 실노선 자료로 취급하지 않는다.

**남은 구현/검증:** 실제 운행 노선번호 또는 차량번호를 기준으로 장성군 노선목록에서 routeId를 식별하고, 그 routeId로 노선 경유 정류소 및 위치정보를 조회해 `vehicleno`, 정류소 순서·좌표를 대조한다. 이후 TAGO collector, 응답 검증·재시도, PostgreSQL upsert와 지역 매핑을 구현해야 한다. 현 시점 `TAGO_ENABLED=false`, `TAGO_MOCK_MODE=true`; API 키 연결 성공은 자동 수집 구현 완료를 뜻하지 않는다.

대시보드 수집 상태를 승인 후 사실(도시코드 36450, 노선목록 105개 조회 성공)과 남은 사항(실차량 매칭·collector 미구현)으로 갱신했다. 변경 화면 반영을 위해 backend 재빌드·재기동 후 HTTPS health와 대시보드 수집 상태를 확인한다. DB volume, PostgreSQL 데이터, Node watcher는 변경하지 않는다.

## Node watcher 실시간 상태 API 및 관리자 화면 연결 (2026-10-02)

대시보드 수집 상태에 Node watcher를 “실시간 상태 API 미연결”로만 표기하던 부분을 실제 연동했다.

### 구현 내용

- Node watcher에 내부 전용 `GET /health`를 추가했다. PostgreSQL `SELECT 1`, 파일 감시 초기화 상태, 배치 처리 여부, 프로세스 uptime을 반환한다.
- DB 연결 실패는 HTTP 503과 `status=DOWN`으로 반환하며 예외 메시지·접속정보는 노출하지 않는다. GET 이외 메서드는 405, 다른 경로는 404다.
- Spring Boot 관리자 인증 API `GET /api/v1/admin/system-status`가 watcher 상태를 프록시한다. 브라우저가 watcher 포트에 직접 접속하지 않으며 API는 기존 `/v1/admin/**` ADMIN 보안 규칙을 따른다.
- 대시보드의 수집 상태 메뉴는 watcher 상태를 표시하고 화면이 열린 동안 30초마다 자동 갱신한다. 수동 새로고침도 제공한다.
- Compose에서는 watcher `3000`을 내부 `expose`만 하고 호스트 포트로 publish하지 않는다.

### 검증

`server/node-watcher`에서 `npm test`: 기존 테스트와 신규 상태 API 테스트 포함 **7개 통과**. 신규 테스트는 정상 응답, DB 장애 응답에서 오류정보 비노출, 잘못된 URL/메서드를 확인했다. Maven 컨테이너 빌드도 성공했다.

배포 후 Compose 네트워크 안에서 `http://node-watcher:3000/health`는 다음 상태를 반환했다: `status=UP`, `database=UP`, `watcher=RUNNING`, `processing=false`. 인증 없이 관리자 프록시 `GET /api/v1/admin/system-status` 호출 시 HTTP 401을 확인해 보호 규칙도 확인했다. HTTPS actuator health도 `{"status":"UP"}`이며 PostgreSQL 데이터/볼륨은 재생성하지 않았다.

### 남은 검증과 다음 단계

- 실제 관리자 로그인 브라우저 화면에서 자동 갱신 값이 렌더링되는지 확인한다.
- TAGO 노선/정류소 카탈로그 동기화와 실제 운행 차량 위치 polling은 별도 미구현이다. API 승인 및 노선목록 호출 성공만으로 자동 수집이 되지는 않는다.
- Android heartbeat/현재 표출 상태 API도 미구현이다.

## TAGO 노선·정류소 카탈로그 동기화 기능 (2026-10-02)

### 범위와 의도

TAGO 승인이 끝나 장성군 `cityCode=36450` 및 노선 105개 조회가 확인되어, 운행 위치 polling 전에 필요한 노선/정류소 기준정보 적재를 먼저 구현했다. 이는 자동 위치 수집이 아니다. 오퍼레이터가 대시보드 `수집 상태` 메뉴에서 실행하는 수동 동기화로 범위를 제한했다. 105개 노선을 주기적으로 무차별 polling하면 API 호출량이 불필요하게 커지므로 `TAGO_ENABLED=false`는 그대로 두었다.

### 구현한 흐름

1. `POST /api/v1/admin/tago/catalog/sync`가 설정된 service key와 city code를 사용한다. 관리자 권한과 CSRF 보호가 적용된다.
2. `BusRouteInfoInqireService/getRouteNoList`에서 페이지별 노선 ID/번호/유형을 가져온다.
3. 각 route ID에 대해 `getRouteAcctoThrghSttnList`의 페이지를 읽고 stop ID/name/order, 위경도, `updowncd` 방향을 검증한다.
4. 외부 호출을 모두 끝내고 응답이 유효할 때만 DB transaction 하나에서 `bus_routes`, `bus_stops`, `bus_route_stops`에 upsert한다. 실패한 경우 부분 카탈로그를 남기지 않는다. 기존 정류소 지역 컬럼은 덮어쓰지 않는다.
5. `GET /api/v1/admin/tago/catalog`는 현재 DB 건수와 마지막 동기화 시각을 제공하고, 대시보드는 실행·새로고침 버튼과 결과 메시지를 표시한다.

XML DTD/외부 엔티티는 차단한다. URL 인코딩/디코딩 어느 형태의 키도 설정 모드 `AUTO`로 처리하며, literal `+` 문자가 공백으로 변환되지 않도록 한다. 키를 URL·예외·응답에 출력하지 않는다. 요청 사이에 최소 150ms 간격을 두어 문서의 30 TPS 한계보다 낮은 속도로 순차 호출한다.

`.env`의 빈 `TAGO_CITY_CODE`를 이미 실조회로 확인된 `36450`으로 설정했다. 자동 위치 수집 및 mock-mode 값은 변경하지 않았다.

### 검증과 배포 결과

- TAGO key normalization, 노선/정류소 XML 파싱, XXE 차단 JUnit **3건 통과**.
- Maven `package -DskipTests` 성공. 신규 JAR가 Docker 이미지 COPY에 실제 반영됨을 확인하고 backend만 재생성했다.
- Spring Boot 기동 로그 정상, 내부 health HTTP 200. 비로그인 카탈로그 GET 및 동기화 POST는 HTTP 401로 차단됨을 확인했다.
- 백엔드 재생성 직후 Nginx가 이전 컨테이너 IP를 캐시해 CSRF GET이 502였다. 기존과 같은 stale-upstream 현상이어서 Nginx만 재생성해 복구했다. PostgreSQL/Node watcher 및 DB volume은 재생성하지 않았다.
- 로컬 관리자 세션을 사용한 별도 비브라우저 POST 시험은 로그인/ADMIN 확인 뒤에도 HTTP 403으로 끝났다. 이 시도는 TAGO 호출 전 인증 단계에서 막혀 카탈로그 DB를 변경하지 않았다. 따라서 기능의 실제 동기화 성공을 아직 주장하지 않는다. 브라우저 대시보드 버튼으로 재검증이 필요하다.

### 2026-10-02 후속 장애 확인

대시보드에서 카탈로그가 `아직 동기화 전 · 노선 0개 · 정류소 0개`로 보이고 동기화 버튼이 HTTP 403을 표시했다. 동시에 Node watcher 상태 행도 비어 있다는 보고가 있었다. 운영 로그와 컨테이너 상태를 분리해서 확인한 결과, 서로 다른 문제가 겹쳐 있었다.

- 기존 Nginx는 `egov-backend`를 시작 시점에 한 번 해석한 IP로 프록시하고 있었다. 백엔드 재생성 후 IP가 `10.89.0.41`에서 `10.89.0.43`으로 바뀌었지만 Nginx는 구 IP를 계속 사용했고, 이 동안 관리자 API와 대시보드 요청은 502/401이 났다. Nginx에 Podman DNS resolver(`10.89.0.1`)와 10초 유효기간을 설정하고 변수형 `proxy_pass`를 사용하도록 수정했다. Nginx만 재생성한 뒤 현재 backend IP로 내부 health 요청 200, 외부 `https://localhost:8443/api/actuator/health` 200을 확인했다. 이 수정으로 다음 backend 재생성 뒤에도 DNS를 주기적으로 새로 확인한다.
- 백엔드 재생성은 세션을 무효화하므로 브라우저에서 새로 로그인해야 한다. 현재 자동 브라우저 확인에서 관리자 API의 401을 확인해, 대시보드 수집 상태 행은 로그인 전에는 신뢰할 수 있는 값을 보여줄 수 없다. Node watcher 컨테이너 자체는 계속 `Up`이며, 직전 직접 확인에서도 `/health`는 `status=UP`, `database=UP`, `watcher=RUNNING`이었다. 따라서 빈 UI 표시는 watcher 컨테이너가 꺼졌다는 증거가 아니다.
- 재로그인 후 실제 브라우저에서 관리자 session, TAGO catalog GET, Node watcher status GET, `/csrf` GET이 모두 HTTP 200인데 sync POST는 다시 HTTP 403이었다. 로그아웃 POST는 HTTP 204로 통과했다. Spring Security 경고는 sync URI가 아니라 `/api/error`의 `AccessDeniedException`만 남겼다. 이는 오류 dispatch가 원래 403 원인을 가렸다는 증거이며 CSRF/권한 중 무엇이 최초 원인인지는 아직 확정할 수 없다. 서비스 코드가 TAGO 외부 HTTP 실패를 502로 변환하므로, sync API 컨트롤러에 도달하지 못했을 가능성이 높지만 로그만으로 단정하지 않는다.
- 최초 진단 빌드는 Maven Central 연결 시간초과로 막혔으나 2026-10-02 네트워크 복구 후 DNS/TCP 443을 재확인하고 실제 프로젝트 `mvn clean package`를 수행했다. TAGO service test 3건 통과, `BUILD SUCCESS`.
- CSRF 필터 DEBUG 로그(토큰값 비기록), ERROR dispatcher 허용, TAGO sync controller 진입 로그를 넣어 403을 구분하도록 했다. 대시보드 오류 문구도 403(관리자 세션·CSRF·권한 단계)과 502(TAGO 외부 호출 단계)를 구별하도록 수정했다. backend 이미지 재빌드·재생성 후 외부 HTTPS health 200 확인.
- TAGO 외부 호출 실패는 `TagoCatalogService`가 502로 변환하도록 구현되어 있다. 따라서 관리자가 새 로그인 후 다시 sync해도 **외부 TAGO의 서비스키/승인 오류라면 403이 아니라 502**가 기대된다. 재시험 전까지 DB 카탈로그는 기존대로 미동기화(0건)이며 변경하지 않았다.

**다음 검증:** backend 재생성으로 관리자 브라우저 세션이 초기화됐으므로 새로 로그인한 뒤 TAGO sync를 한 번 실행한다. `Invalid CSRF token` 로그가 나타나면 CSRF 전달을 수정하고, controller 진입 로그 뒤 HTTP 502가 나오면 TAGO 외부 응답/키 승인을 확인한다. 로그·HTTP 성공과 DB 건수 확인 전까지 동기화 완료로 표시하지 않는다.

### 2026-10-02 TAGO 동기화 502 후속 진단

관리자 화면에서 재시도한 동기화는 HTTP 502였다. 같은 시간대 Nginx access log는 `/api/v1/admin/tago/catalog/sync` 502를 기록했고, backend에는 `TAGO catalog synchronization request reached controller` 로그가 있어 인증·CSRF 필터를 통과해 컨트롤러에 도달한 것은 확인됐다. 직전 403보다 진행 단계가 달라졌으며, 502는 TAGO API 요청 또는 응답 검증 과정의 실패다. 그러나 기존 서비스는 실패 원인을 사용자 응답에 숨기고 로그에도 상세를 남기지 않아, 당시에는 어느 TAGO endpoint에서 실패했는지 알 수 없었다.

이를 위해 `TagoCatalogService`의 안전 로그를 보강했다. 외부 HTTP 실패는 **HTTP 상태와 고정 API 경로만**, TAGO XML 오류는 **표준 resultCode와 고정 API 경로만**, 그 외 예외는 **예외 클래스명만** 기록한다. URL, query parameter, 노선 ID, 서비스키, 외부 응답 메시지는 출력하지 않는다. 따라서 다음 실패 한 번으로 노선목록 endpoint인지 노선별 경유정류소 endpoint인지, HTTP 오류인지 TAGO resultCode 오류인지 구분할 수 있다.

변경 후 Maven `clean package`: `BUILD SUCCESS`, JUnit 3건 통과. backend 이미지 재빌드·재생성 및 외부 HTTPS health 200을 확인했다. backend 재생성으로 관리자 세션이 초기화되었으므로, 재로그인 후 동기화 재시도가 필요하다. 재시도 전후 카탈로그는 미동기화(0개)이며, 검증 완료 전 부분 저장은 수행되지 않는다.

### 현재 남은 항목

- 관리자 브라우저에서 `수집 상태 → TAGO 노선·정류소 동기화`를 눌러 HTTP 성공 및 DB 건수(105 노선, 경유 정류소, 고유 정류소)를 확인한다.
- 정류소 TAGO 응답은 읍·면 경계를 제공하지 않는다. 별도 행정구역 자료를 확보해 region 매핑 UI/DB 작업을 해야 한다.
- 실제 장성군 운행 노선·차량번호와 위치 응답을 대조한 뒤 active route만 제한적으로 polling하는 collector를 구현하고, 그 전까지 자동 수집은 계속 비활성이다.

## 대시보드 수집 상태의 접속 URL 오류 수정 (2026-10-02)

### 사용자 관찰 및 원인

수집 상태 화면에 `TAGO: 카탈로그 상태 조회 실패: Failed to fetch`, `Node watcher: 대시보드 접속 주소 오류`가 함께 표시되었다. Node watcher의 실제 컨테이너 장애로 단정할 수 없는 화면 측 문제였다. 대시보드 JavaScript가 watcher 상태 조회 전에 `https`와 외부 포트 `8443`을 정확히 요구했지만, TAGO 조회는 현재 페이지 URL을 기준으로 상대 경로를 요청하고 있어 브라우저 주소가 `https://localhost/…`처럼 포트 없이 열리면 두 동작이 서로 다른 실패 메시지를 냈다. 이 상황에서는 브라우저가 접근할 수 없는 기본 포트로 API fetch를 보내 `Failed to fetch`가 발생할 수 있다.

### 수정 내용

- localhost, `127.0.0.1`, IPv6 loopback으로 대시보드를 열 때 프로토콜이 HTTPS가 아니거나 포트가 `8443`이 아니면 현재 경로·해시를 유지한 채 `https://<현재 로컬 호스트>:8443/...`로 자동 이동하도록 했다.
- 이동 중에는 관리자 세션 조회, 메뉴별 API 호출, 주기 갱신을 시작하지 않아 잘못된 origin으로 요청하지 않도록 했다.
- watcher 상태 조회의 `location.port === '8443'` 차단을 제거했다. 외부 도메인 배포는 임의로 localhost 포트에 강제하지 않으며 동일 출처 상대 경로를 사용한다.
- 변경 파일은 `server/egov-backend/src/main/resources/static/dashboard/index.html` 하나다. DB, 사용자 계정, 컨테이너 데이터 볼륨은 수정하지 않았다.

### 빌드·실행 검증

- 추출한 dashboard JavaScript를 Node.js `new Function`으로 구문 검사: 통과.
- Maven `clean package -DskipTests`: `BUILD SUCCESS`.
- 백엔드와 Nginx만 재빌드·재생성했다. PostgreSQL과 Node watcher는 그대로 유지했다.
- `GET /api/actuator/health`: `{"status":"UP"}`.
- 비로그인 dashboard 진입: HTTP 302, `Location: https://localhost:8443/api/dashboard/login.html`로 포트 보존 확인.
- Node watcher 컨테이너 내부 `GET /health`: HTTP 200, `status=UP`, `database=UP`, `watcher=RUNNING`, `processing=false` 확인. 따라서 당시 `대시보드 접속 주소 오류` 표시는 watcher 프로세스 다운을 뜻하지 않았다.

### 아직 확인되지 않은 항목

실제 관리자 브라우저 세션에서 수집 상태 메뉴를 열어 TAGO 카탈로그 GET 응답과 화면 렌더링을 재확인해야 한다. `Failed to fetch`가 계속되면 주소 표시줄의 최종 URL과 브라우저 개발자 도구 Network에서 `/api/v1/admin/tago/catalog`의 요청 URL/상태를 확인한다. 이 배포로 카탈로그를 동기화하거나 TAGO 외부 API를 호출한 것은 아니다.
# 2026-10-02 TAGO 일시 오류 재시도 보완

## 확인된 현상

- 관리자 화면의 카탈로그 동기화가 HTTP 502로 실패했고 백엔드 로그에는 `getRouteNoList` 응답 `resultCode=99`가 기록되었다.
- Node watcher health는 DB UP / watcher RUNNING으로 별도 정상 상태였다. 두 컴포넌트는 독립된 상태 확인이며, 한쪽이 정상이라고 다른 쪽이 실패하는 종속 관계는 아니다.
- 서버의 `.env` 및 Spring 설정에 `TAGO_RETRY_COUNT=3`, `TAGO_RETRY_DELAY_SECONDS=10`이 있었지만 기존 `TagoCatalogService`에서 읽거나 사용하지 않았다.
- 프로젝트 `.env` 값을 출력하지 않는 직접 읽기 전용 GET 요청에서 `cityCode=36450`, `numOfRows=500` 요청이 HTTP 200, `resultCode=00`, 총 105개로 응답하는 것을 확인했다. 이는 직전 `resultCode=99`가 영구 키 거부가 아니라 해당 시점의 일시적 응답일 가능성을 뒷받침한다.

## 코드 변경

- `TagoCatalogService`가 retry-count와 retry-delay를 주입받도록 변경했다.
- HTTP 429/5xx, TAGO resultCode 99, I/O 전송 오류만 재시도한다.
- 총 시도 횟수는 설정값(현재 3회), 대기 간격은 설정값(현재 10초)이다. HTTP 4xx 중 429가 아닌 응답과 resultCode 00 이외의 인증/요청 오류는 재시도하지 않는다.
- 재시도 로그는 고정 API 경로와 시도 횟수만 포함한다. 서비스키, query parameter, 외부 오류 원문은 로그에 쓰지 않는다.
- 재시도 가능 오류 분류, 최대 시도 횟수, 영구 오류 즉시 종료 테스트를 추가했다.

## 검증 상태 및 다음 조치

- `git diff --check` 통과.
- **Maven 테스트 및 컨테이너 배포는 미검증**: 이 Codex 실행 환경에서 Podman 프로세스 실행이 Windows `Access denied`로 차단되었다. 수정 코드는 아직 실행 컨테이너에 반영되지 않았다.
- 다음으로 사용자 Git Bash에서 backend Maven 컨테이너 빌드/테스트를 수행하고 성공한 뒤 backend 이미지를 재빌드·재생성해야 한다. 재생성으로 관리자 세션이 초기화될 수 있으므로 로그인 후 카탈로그 동기화를 한 번 시험한다.
- 동기화 성공 전까지 DB 카탈로그는 미동기화로 취급하고 자동 위치 수집은 계속 비활성 상태로 둔다.
# 2026-10-02 TAGO `nodeord` 검증 실패 처리

## 재현 로그와 원인

사용자가 새 진단 빌드를 배포한 뒤 TAGO 동기화를 눌렀고 로그에서 다음을 확인했다.

```text
TAGO catalog synchronization failed; exceptionType=CatalogValidationException failureCode=STOP_ORDER_INVALID origin=...TagoCatalogService.stopFrom:332
```

즉, 컨트롤러 및 TAGO 경유 정류소 요청까지 도달한 뒤 일부 응답 항목의 `nodeord`가 1 미만이었다. TAGO 자체 통신 거부나 Node watcher 장애가 원인이 아니다. 기존 구현은 이 한 항목 때문에 모든 노선·정류소 적재를 중단했다.

## 보완

- 경유 정류소의 `nodeid`와 `nodenm`은 계속 필수로 검증한다. 누락 시에는 불완전 데이터 방지를 위해 전체 동기화를 중단한다.
- `nodeord`가 숫자가 아니거나 1 미만인 경우, 해당 노선의 TAGO 응답에서 항목이 나온 순번을 대체 순번으로 사용한다. `numOfRows=500`으로 노선 경유 정류소 페이지를 읽으며 페이지 간에도 전체 응답 위치를 계산한다.
- 동기화 결과에 `inferredOrderCount`를 포함하고, 대체 순번 건수를 로그 및 관리자 화면 성공 메시지에 표시한다. 해당 수가 0이면 기존 성공 문구를 유지한다.
- 이 처리는 TAGO의 경유정류소 목록 순서가 노선 진행 순서를 반영한다는 전제다. 실제 성공 후 DB에서 대표 노선의 node_order 단조 증가를 확인해야 한다. TAGO 응답의 순서 자체가 불안정하면 이 대체는 신뢰할 수 없으므로 그 경우에는 동기화를 중단하고 원천기관 확인이 필요하다.

## 검증 단계

- `nodeord=0`일 때 응답 순번을 적용하고 `orderInferred=true`를 반환하는 단위시험을 추가했다.
- 변경 코드에 대해 사용자가 Maven 컨테이너 빌드를 다시 실행하고 `BUILD SUCCESS`를 확인해야 한다. 이어 backend 이미지를 재빌드·재생성한 다음 관리자 로그인을 새로 하고 TAGO 동기화를 실행한다.
- 성공 메시지에 보완 건수가 표시되는지 확인하고, `bus_route_stops`에서 노선별 `node_order`가 중복·역전 없이 들어갔는지 검증한다. 카탈로그가 commit되기 전에는 실제 DB 상태를 동기화 완료로 간주하지 않는다.
# 2026-10-02 `bus_route_stops` 순번 고유키 충돌 대응

## 실패 원인

사용자 로그에서 경유 정류소 요청 및 `nodeord` fallback 이후 PostgreSQL 저장 시 `DuplicateKeyException`이 발생했다. 스키마 `server/database/SmartBIS_operations_schema_v1.0.sql`의 `bus_route_stops`에는 다음 두 제약이 있다.

- 기본키: `(route_id, node_id, direction)`
- 추가 고유키: `(route_id, direction, node_order)`

앞선 fallback은 잘못된 `nodeord`에 전역 응답 위치를 대입했으므로, 정상 `nodeord`와 같은 방향에서 숫자가 겹칠 수 있었다. API 요청이 실패한 것이 아니라 DB 제약과 순번 정규화 규칙이 맞지 않은 문제다.

## 구현 보완

- 각 노선의 반환 항목 순서를 유지하고, 노선·방향별로 순번을 1부터 연속 정규화하도록 변경했다. 이로써 방향별 `node_order` unique 충돌을 예방한다.
- TAGO 원본 `nodeord`가 결측·0 이하이거나 정규화된 위치와 다르면 `orderInferred`로 집계한다. 대시보드 성공 문구와 backend 경고 로그에 조정 건수를 표시한다.
- 정류소 ID/이름 누락은 계속 전체 트랜잭션을 거부한다. 데이터 일부만 저장하거나 실패한 동기화를 성공으로 표시하지 않는다.
- 방향별 순번 고유키 충돌 예시와 재정렬 결과를 확인하는 단위시험을 추가했다.

## 검증 대기

- 이 수정은 직전 Maven `BUILD SUCCESS` 이후 적용되었으므로 새 빌드가 필요하다.
- 빌드 후 백엔드만 재생성하고 카탈로그 동기화를 실행한다. 성공 시 대시보드에서 노선·경유 정류소·고유 정류소 수와 조정 건수를 확인한다.
- 다음 DB 조회에서 같은 `(route_id,direction)` 내 `node_order`가 1부터 유일하게 이어지는지 검증한다. 실제 성공 전에는 카탈로그 적재 완료로 기록하지 않는다.
# 2026-10-02 TAGO 카탈로그 적재 성공 및 순번 집계 오류 교정

## 실동기화 결과

사용자가 대시보드에서 동기화 성공을 확인했다.

- 노선 105개
- 경유 정류소 응답 5,339건
- 고유 정류소 951개
- DB의 마지막 동기화 시각 표시 확인
- Node watcher는 DB UP / RUNNING 상태

다만 화면의 `응답 순서로 순번 보완 5339건`은 의미상 비정상이다. 당시 `normalizeStopOrders`가 유효한 `nodeord`와 direction별 응답 위치가 다르다는 이유만으로 정상 순번도 전부 재부여하고 fallback으로 세었다. 따라서 데이터 적재는 성공했으나 노선 순번이 원본 TAGO 값과 다를 수 있어 정확한 완료로 간주하지 않는다.

## 교정 내용

- 유효하고 고유한 원본 `nodeord`는 보존한다.
- 결측·0 이하 순번과 같은 route/direction 안에서 중복된 순번만 응답 순서상 가능한 비어 있는 값으로 보완한다.
- 현재 적재된 값은 같은 route/stop/direction PK를 upsert하더라도 대체 순번 고유 제약과 일시 충돌할 수 있다. 따라서 동기화 트랜잭션 내에서 해당 동기화 대상 노선들의 `bus_route_stops` 관계를 삭제한 뒤 정규화 완료 관계를 삽입한다. 트랜잭션 전체가 실패하면 이전 관계는 롤백되어 유지된다.
- `inferredOrderCount`는 실제 보완 항목만 세도록 단위시험을 보강한다.

## 남은 검증

- 최신 변경에 대해 Maven 테스트/build를 실행하고 backend 컨테이너를 재생성한다.
- TAGO 동기화를 재실행해 노선 105/정류소 951 상태를 확인한다. 보완 건수는 5,339가 아니라 원본에 이상/중복이 있는 소수 또는 0이어야 한다.
- DB에서 대표 노선 몇 개의 `node_order`와 TAGO 순번을 대조하고 direction별 고유성·증가 순서를 검사한다. 이 비교 전까지 자동 위치/100m 처리는 켜지 않는다.

# 2026-10-02 TAGO 순환노선 중복 진단 추가

## 새로 확인된 DB 오류

사용자가 로그에서 다음을 확인했다.

```text
exceptionType=DuplicateKeyException failureCode=DB_UNIQUE_bus_route_stops_pkey
```

이 제약은 현재 `(route_id, node_id, direction)`을 기본키로 사용한다. 농촌 순환·회차 노선은 같은 정류장을 같은 방향으로 두 번 이상 통과할 수 있으므로, 해당 조합이 반복됐다는 사실만으로 TAGO 응답 오류라고 할 수 없다. TAGO가 돌려준 서로 다른 `nodeord`가 같은 `(노선, 정류장, 방향)`으로 반복된다면 현재 DB 기본키가 실제 경유 방문을 표현하지 못하는 것일 수 있다. 같은 순번까지 중복되거나 방향이 `UNKNOWN`으로 뭉쳐지는 경우는 별도 데이터/매핑 이상 후보다.

## 읽기 전용 교차검증 기능

관리자 수집 상태 화면에 **TAGO API 교차 검증 (DB 저장 없음)** 기능을 추가했다. 백엔드의 `POST /api/v1/admin/tago/catalog/validate`는 쓰기 쿼리를 호출하지 않고 다음 응답들을 대조한다.

1. 노선정보 서비스의 도시코드 목록에서 설정된 장성군 코드 확인
2. 정류소정보 서비스의 도시코드 목록에서 동일 코드 확인
3. 노선 목록에서 노선 ID·노선명 읽기
4. 노선별 상세 API의 ID·노선명·시작/종점 정류장명 확인
5. 노선별 경유 정류소 API에서 ID·이름·방향·`nodeord` 읽기

결과에는 각 도시코드 확인 여부, 상세 응답 누락/ID·이름 불일치, 상세 시작·종점명이 경유목록에 존재하는지, 미상 방향 수, 같은 노선·정류장·방향 반복 그룹, 노선·방향별 순번 중복 그룹을 포함한다. 반복 그룹은 서로 다른 순번의 재방문 후보와 같은 순번 반복을 구별하고, 화면에는 소수의 예시만 표시한다. 순환노선에서 다른 순번으로 같은 정류장이 다시 나오는 것은 정상일 수 있으며 자동으로 실패 판정하지 않는다.

모든 장성군 노선을 자세히 대조하므로 외부 API 요청 수가 많고 1분 이상 걸릴 수 있다. Nginx API upstream timeout은 300초로 설정되어 있다. TAGO 응답이나 연결이 실패하면 보고서가 완성되지 않으며 DB는 변경되지 않는다. 진단이 성공한 뒤 실제 반복 사례와 순번을 검토하고, 방문 occurrence를 보존할 수 있도록 `bus_route_stops` 키를 별도 migration으로 변경할지 결정한다. 이 결과 확인 전에는 제약을 임의로 바꾸거나 동기화를 반복하지 않는다.

## 단위검증 및 배포 상태

- 중복 PK 후보 탐지, 서로 다른 순번의 재방문, 같은 순번 반복, route/direction 단위 순번 충돌에 대한 단위시험을 추가했다.
- 결과는 아직 로컬 소스 변경 상태다. Maven 빌드·테스트와 컨테이너 배포는 다음 실행이 필요하며 실제 API 진단은 아직 실행되지 않았다.
- 성공 후 관리자 로그인을 하고 교차검증 버튼을 한 번 눌러 결과를 확인한다. 동기화 버튼은 현재 DB 기본키 오류를 내므로 교차검증 및 schema 결정 전에는 누르지 않는다.

# 2026-10-02 TAGO 교차검증의 부분 실패 수집 보완

## 실행 결과

사용자가 교차검증 버튼을 실행한 뒤 첨부한 backend 로그에서 확인했다.

- `POST /tago/catalog/validate` 요청은 Spring controller까지 도달했다.
- 노선별 `getRouteInfoIem` 및 `getRouteAcctoThrghSttnList` 호출에서 간헐적인 `resultCode=99`가 발생했고, 재시도가 기록되었다.
- 이후 한 경유목록 응답은 `header.resultCode`가 비어 있어 `requestOnce`에서 `IllegalStateException`으로 처리되었다.
- 따라서 이 HTTP 502는 DB 중복키 오류가 아니라, 교차검증 중 외부 TAGO 응답 실패 하나가 전체 요청을 중단시킨 결과다. DB 쓰기 함수에는 도달하지 않았으므로 DB 변경은 없다.
- 이 로그만으로 응답 실패가 TAGO 일시 장애인지, 개별 routeId 응답 특성인지, 호출 빈도 영향인지는 아직 구별되지 않는다. 순환노선 가설도 아직 확인되지 않았다.

## 진단 동작 수정

- 도시코드 목록과 전체 노선목록처럼 기준 집합을 만들 수 없는 오류는 계속 전체 요청 실패로 처리한다.
- 노선별 상세 또는 경유목록 호출 실패는 해당 노선의 실패로 집계하고 다음 노선으로 계속한다. 결과 상태는 `PARTIAL`이며, 성공한 노선에서 계산한 지표와 상세/경유목록 별 실패 건수, 최대 20개 사례를 제공한다.
- 읽기 전용 검증에서는 API 일시 오류 재시도를 최대 2회, 1초 간격으로 제한해 105개 노선 전체 조사 중 재시도 지연이 과도하게 누적되지 않게 했다. 일반 catalog 동기화 재시도 설정은 그대로 유지한다.
- 화면에는 정상 재방문 후보와 같은 순번 중복, 노선별 API 요청 실패를 서로 다른 상태로 표시한다. 재방문은 오류로 단정하지 않는다.

## 검증 및 다음 단계

- 사용자가 직전 소스 버전의 Maven `BUILD SUCCESS`를 확인했다. 위 부분 실패 수집 보완은 그 이후 변경이므로 재빌드가 필요하다.
- 최신 backend 이미지 재빌드·재생성 후 대시보드의 읽기 전용 교차검증을 실행한다. 전체 응답 대신 `PARTIAL`이라도 반환되면 DB 변경 없이 실패 routeId/stage를 확인할 수 있다.
- 결과에서 서로 다른 `nodeord`를 가진 동일 정류장 재방문이 나타나면 이를 정상 노선 패턴으로 보고 스키마가 방문 occurrence를 표현하도록 migration을 설계한다. 같은 순번 중복과 방향 미상은 별도 원인 조사 후 다룬다.
- 교차검증 결과 확인 전 카탈로그 동기화를 다시 누르거나 운영 DB 기본키를 바꾸지 않는다.

# 2026-10-02 장성군 노선 교차검증 부분 결과

## 사용자가 대시보드에서 확인한 결과

- 도시코드 두 서비스 모두 확인, 노선 105개.
- 노선상세 조회 실패 1개, 경유목록 조회 실패 2개. 따라서 계산은 경유 응답 5,277건 기준이며 전체 노선 검증 완료가 아니다.
- 상세 ID/노선명/시작·종점 명칭 불일치는 모두 0.
- 같은 노선·정류장·방향 키 충돌 후보 9그룹. 예시 중 서로 다른 `nodeord`로 재등장한 정류장들이 있어 농촌 순환·회차 노선의 정상 반복일 가능성과 일치한다. 다만 이 중 다수는 방향이 `UNKNOWN`이라 실제 상·하행/회차 구조를 아직 확정하지 못한다.
- 같은 노선·방향·순번에 여러 정류장 ID가 들어간 그룹 103개, 방향 미상 1,248건.
- 결과적으로 기존 `(route_id,node_id,direction)` PK뿐 아니라 `(route_id,direction,node_order)` 고유 제약과 unknown 방향 처리도 고려해야 한다. 이 출력만 보고 어느 제약만 바꿀지 결정하지 않는다.

## 문서 대조 및 다음 진단 보강

프로젝트 내 TAGO 노선정보 가이드의 경유 정류소 응답 명세는 `updowncd`를 상하행 코드로 정의하고 `0=상행`, `1=하행` 예시를 제공한다. 기존 파서는 이 매핑을 적용하고 있으나 이번 교차검증 결과의 `UNKNOWN`이 빈 문자열인지, 이외 값인지 집계하지 않았다. 이에 교차검증 응답에 원본 `updowncd` 값별 건수(민감정보 아닌 코드 집계)를 추가하고, 부분 실패 사례를 화면 상단에 먼저 보여주도록 보완했다.

최신 변경은 직전 교차검증용 Maven 성공 뒤 추가되어 아직 다시 빌드·배포되지 않았다. 다음은 raw code 분포와 실패 routeId/stage를 확인하는 읽기 전용 교차검증이다. 해당 결과 전에는 unknown 방향의 `nodeord`를 자동 재정렬해 적재하거나 운영 스키마 제약을 변경하지 않는다. `ArrivalService`는 같은 방향에서 `node_order + 1`로 다음 정류장을 찾으므로 단순히 저장을 성공시키는 키 변경은 도착정보 판단을 오염시킬 수 있다.

# 2026-10-02 운수사 노선도 대조용 TAGO CSV 생성

## 목적과 안전 경계

운수사에서 받은 노선도 CSV가 시간표와 경로를 혼합한 표현이어서 TAGO의 원본 경유 정류소 응답과 직접 대조할 수 있도록 프로젝트 `.env`의 TAGO 설정을 이용해 읽기 전용으로 API를 조회하고 CSV 산출물을 만들었다. 대시보드에서 받는 API endpoint 방식도 시험 구현했으나 사용자의 요청에 따라 복잡한 서버 다운로드 절차는 제거했다. 최종 산출물은 프로젝트의 `장성교통 노선도` 폴더에 두며, 기존 PostgreSQL 카탈로그를 조회·변경하지 않고 카탈로그 동기화나 순번 보정도 수행하지 않는다.

## 내보내는 필드와 원본 보존

UTF-8 BOM이 포함된 CSV로 내려받아 Windows Excel에서 한글이 깨지지 않도록 한다. 각 행은 TAGO 경유 정류소 응답 한 건이며 다음 열을 제공한다.

`city_code`, `route_id`, `route_name`, `api_response_sequence`, `nodeord_raw`, `updowncd_raw`, `direction_interpreted`, `node_id`, `node_name`, `gpslati`, `gpslong`

`nodeord_raw`와 `updowncd_raw`는 응답 원문을 보존한다. `api_response_sequence`는 API의 페이지 응답 순서를 재구성하기 위한 보조값이며, 서버의 임의 순번 보정값이 아니다. 해석 방향은 TAGO 가이드가 정의한 0→UP, 1→DOWN만 적용하고 나머지는 UNKNOWN으로 표기하며 원본 코드는 별도 열에 그대로 남긴다. CSV 셀은 따옴표·쉼표·줄바꿈을 이스케이프하고, 스프레드시트 수식 실행 위험이 있는 텍스트는 보호한다.

## 검증 상태 및 사용자 절차

- 조회에는 `.env`의 실제 TAGO 서비스키를 사용했고, 키 값은 로그나 문서에 출력하지 않았다. 첫 시도에서 일시 응답코드 99로 중단되어 파일을 만들지 않았고, 재시도 후 전체 결과를 받았을 때만 파일을 썼다.
- 생성 파일: `장성교통 노선도/TAGO_장성군_노선별_경유정류소_20261002.csv`.
- 확인 결과: HTTP 200 / resultCode 00, 장성군 도시코드 36450, 노선 105개, 경유 정류소 5,339건. UTF-8 BOM, CSV 11개 열, 헤더 포함 5,340개 레코드, 열 수 불일치 0건을 로컬 파서로 확인했다.
- 열은 `city_code`, `route_id`, `route_name`, `api_response_sequence`, `nodeord_raw`, `updowncd_raw`, `direction_interpreted`, `node_id`, `node_name`, `gpslati`, `gpslong`이다. TAGO 원본 `nodeord`와 `updowncd`를 보존하고, 해석 방향은 문서 정의값 0→UP, 1→DOWN만 적용한다. 그 외 코드는 UNKNOWN으로 둔다.
- 다음은 사용자가 CSV와 운수사 노선표를 직접 대조하는 단계다. 두 자료의 정류장 명칭이나 순서를 자동으로 동일시하지 말고 확인 결과를 확정/불일치/확인필요로 구분한다. CSV 생성만으로 `updowncd=2`의 의미나 운영 DB 키 변경을 확정하지 않는다.

# 2026-10-02 타 지역 TAGO updowncd 비교

장성군 경유정류소 API 결과의 `updowncd=2`가 TAGO 여러 지역에서 공통으로 쓰이는지 확인하기 위해 같은 서비스키와 동일한 `getRouteNoList` / `getRouteAcctoThrghSttnList` 호출로 대전·계룡, 나주, 함평의 전체 노선을 읽기 전용 조사했다. 각 도시의 노선 목록 및 모든 노선 경유 목록 요청이 resultCode 00으로 끝났으며 실패 노선은 없었다.

| 지역 | 도시코드 | 노선 | 경유 항목 | 원본 `updowncd` 분포 |
|---|---:|---:|---:|---|
| 대전광역시/계룡시 | 25 | 123 | 9,570 | 0=4,907, 1=4,663 |
| 나주시 | 36040 | 63 | 5,022 | 0=2,932, 1=2,090 |
| 함평군 | 36430 | 83 | 5,308 | 0=3,828, 1=1,480 |

합계 19,900개 경유 항목에서 코드 2는 한 건도 관찰되지 않았다. 이는 이번에 조사한 세 지역의 전체 결과이며 TAGO 전국 모든 도시의 전수 결과는 아니다. 따라서 현재 증거로는 코드 2가 모든 지역의 일반적인 값이라고 볼 수 없고 장성군 데이터 또는 특정 노선 응답에 한정된 현상일 가능성이 커졌지만, 이 비교만으로 코드 2의 공식 의미나 원인을 확정할 수는 없다. 제공된 노선 가이드가 설명하는 값은 계속 0=상행, 1=하행뿐이다. 운영 DB 저장·도착 판정은 운수사 CSV 및 TAGO 원본 행을 노선별로 대조하고 필요하면 제공기관에 코드 의미를 확인한 뒤 결정한다.

# 2026-10-03 정류소 행정지역 분류 관리자 API

## 배경

TAGO 정류소 동기화로 정류소 ID·이름·좌표는 가져올 수 있지만 읍·면 행정 구분은 제공되지 않는다. 좌표 기반 추정은 행정 경계와 일치한다고 보장할 수 없으므로 서버에서 자동 분류하지 않는다. 기존 DB의 `regions`, `bus_stops.region_id`, `bus_stops.region_name`을 사용해 담당자가 확정된 기준표를 반영할 관리자 API를 추가했다.

## 추가 API와 보안

- `GET /api/v1/admin/regions`: 지역 그룹 및 지역별 장성군 정류소 수 조회
- `POST /api/v1/admin/regions`: 지역 그룹 생성. 지역명 필수, 중복 코드/이름은 HTTP 409
- `GET /api/v1/admin/tago/stops`: 설정된 장성군 도시코드 범위에서 ID/이름 검색, 지역 필터, 미분류 필터 및 페이지 조회
- `PUT /api/v1/admin/tago/stops/{nodeId}/region`: 활성 지역 지정 또는 명시적 `{"regionId": null}`로 분류 해제

기존 Spring Security의 관리자 전용 `/v1/admin/**` 및 CSRF 보호가 그대로 적용된다. `TAGO_CITY_CODE`가 비어 있으면 503으로 중단한다. 지역 지정 때 활성 지역과 장성군 정류소 존재를 확인하며 `region_id`와 `region_name`을 동기화해 기존 지역 콘텐츠 필터가 읽을 수 있게 한다. 동기화 SQL은 지역 분류 컬럼을 변경하지 않는다.

## 데이터 안전과 검증

- 추가 테이블/migration을 만들거나 실제 운영 DB를 수정하지 않았다. 임의 읍·면 분류도 입력하지 않았다.
- 지역명 필수 검증, 동시 사용 불가한 필터 조합, `TAGO_CITY_CODE` 누락을 단위시험으로 확인한다.
- Maven 전체 테스트 **16개 통과, 실패 0, 오류 0 (`BUILD SUCCESS`)**: 기존 display 1개, TAGO 12개, 지역 API 3개.
- 컨테이너 배포 후 실제 브라우저 관리자 세션/CSRF 요청 및 시험 정류소 지정·해제는 아직 확인하지 않았다. 운영 UI에 정류소 검색·지역 선택을 연결하고 지자체 확정 기준표로 검증하는 후속 작업이 필요하다.

## 수집 상태 대시보드 UI 연결 및 배포

수집 상태 메뉴에 **정류소 행정지역 분류** 카드를 추가했다. 관리자는 지역명·선택적 코드·상위지역을 등록하고, 정류소명 또는 TAGO ID 검색, 지역별 필터, 미분류만 보기, 50개 단위 페이지 이동을 사용할 수 있다. 정류소별 지역을 지정하거나 분류를 해제할 수 있으며 성공 후 지역별 건수와 정류소 목록을 다시 조회한다. TAGO가 읍·면 구분을 제공하지 않으므로 UI도 위치 좌표만으로 지역을 추정하지 않는다.

- 대시보드 inline JavaScript 구문 검사 통과.
- 이전 단계의 Maven 전체 테스트 16개 통과 상태에서 대시보드 리소스를 포함해 패키징하고 `egov-backend` 컨테이너를 재생성했다.
- 외부 HTTPS actuator health `UP`; 비인증 지역 API는 HTTP 401로 관리자 인증이 작동함을 확인했다.
- 실제 로그인 관리자 세션/CSRF로 지역 생성 및 정류소 지정·해제하는 브라우저 종단 시나리오는 아직 실행하지 않았다. 읍·면 정류소 분류 데이터도 입력하지 않았다.

### 2026-10-03 대시보드 `Failed to fetch` 진입 경로 보정

사용자가 Windows 탐색기 경로 `D:/PROJECT/.../dashboard/index.html`을 브라우저에 직접 입력하면 페이지 출처가 HTTPS 서버가 아니라 `file://`이 된다. 페이지 안의 상대 API URL(`../v1/admin/...`)도 `file://` 기준으로 해석되어 브라우저 보안상 요청을 보낼 수 없으므로 여러 데이터가 한꺼번에 `Failed to fetch`가 된다. 이는 관리자 API 하나의 장애가 아니라 대시보드가 서버 외부 출처에서 실행된 문제다.

`normalizeLocalDashboardUrl()`에서 `file:` 진입을 감지하면 `https://localhost:8443/api/dashboard/index.html`로 이동시키고 기존 메뉴 hash를 보존하도록 했다. Node VM에서 실제 `file:///D:/.../index.html#collect` 입력을 모의 실행하여 기대한 HTTPS URL로 이동하는 테스트를 통과했다. 변경은 다음 Maven 패키지/컨테이너 재배포에 포함되어야 하며, 서버가 켜져 있고 인증서 신뢰가 되어야 로그인 후 관리자 API 조회가 가능하다.

### 2026-10-03 GeoJSON 기반 정류소 행정지역 분류

TAGO 정류소 카탈로그에는 좌표가 있지만 읍·면 행정지역 필드가 없고, 기존에는 관리자가 하나씩 지정해야 했다. 장성군 정류소를 좌표로 자동 분류하도록 국토부 읍면동 경계 레이어 `LT_C_ADEMD_INFO`를 서버에서 요청하고, WGS84 GeoJSON 폴리곤과 TAGO 좌표(longitude, latitude)를 내부에서 point-in-polygon 비교하도록 했다. 지도 UI는 추가하지 않는다.

- `.env`의 `MOCT_WFS_SERVICE_KEY`는 Compose를 통해 **egov-backend에만** 전달하고 Android·브라우저 응답에는 노출하지 않는다. `MOCT_WFS_DOMAIN`은 경계 API 키에 등록한 허용 도메인과 일치해야 한다. 기존 예상값 `46880`으로 `emd_cd` 필터를 시험했으나 결과가 0건이었다. 현재 API 결과의 장성군 읍·면 코드는 `1284…` 형태이고 `full_nm`에는 `전남광주통합특별시 장성군 …`으로 나타났다. 이에 특정 법정코드를 가정하지 않고 넓은 장성군 공간 범위에서 조회한 뒤 `full_nm`에 `장성군`이 포함된 경계만 선택하도록 수정했다. TAGO `TAGO_CITY_CODE=36450`은 이 두 코드와 별개다.
- 관리자 `POST /api/v1/admin/tago/stops/regions/preview`는 경계 API에서 장성군 경계를 읽어 분류 예상 수, 미일치·중복 사례를 반환하며 DB를 변경하지 않는다.
- 관리자 `POST /api/v1/admin/tago/stops/regions/apply`는 외부 경계를 다시 조회한 뒤 단 하나의 읍·면 폴리곤에 들어가는 정류소만 적용한다. 경계가 겹쳐 후보가 여러 개인 정류소 및 바깥/좌표 누락은 보류한다. `region_id` 또는 `region_name`이 이미 있는 수동 분류도 덮어쓰지 않는다. 모든 쓰기는 DB 트랜잭션 안에서 처리한다.
- 수집 상태 화면에는 미리보기(DB 변경 없음)와 별도 적용 버튼을 추가했다. 적용 전 결과와 보존 규칙을 확인하고 관리자가 명시적으로 동의해야 한다.
- 폴리곤 내부/외부, 경계선, hole 제외 및 MultiPolygon 처리를 위한 단위시험을 추가했다. Maven 전체 19개 테스트 통과, JAR 패키징과 백엔드 컨테이너 재생성, health HTTP 200을 확인했다. 실제 `.env` 키로 읽기 전용 WGS84 경계조회도 HTTP 200/`OK`; 좁은 공간 범위에 포함된 370 features에서 `장성군` 명칭 경계 11개(장성읍 및 10개 면), 필요한 속성(`emd_cd`, `emd_kor_nm`, `full_nm`)과 MultiPolygon 형식을 확인했다. API 키 자체는 유효하다. 다만 로그인 관리자 브라우저에서 실제 미리보기 결과와 정류소 매칭 수를 확인하기 전까지 DB 반영 및 운영 분류 완료로 간주하지 않는다.

검증 결과: `.env`의 키와 현재 `https://localhost:8443` 도메인으로 VWorld 경계 조회를 직접 읽기 전용 호출해 HTTP 200, API `OK`를 확인했다. 키는 백엔드 컨테이너에 전달되며 검사 출력에서 값은 숨겼다. 외부 경계 API 확인과 서버 저장 API 적용은 서로 다른 단계다. 실제 관리자 브라우저에서 미리보기 결과를 보고, 결과가 합리적일 때만 적용 버튼을 눌러야 한다.

### 2026-10-03 장성군 노선의 광주 운행 구간 반영

운수사 노선은 장성군 행정경계를 넘어 광주까지 연결될 수 있다. 기존 분류기는 행정경계 응답의 `full_nm`에 `장성군`이 들어간 경계만 선택했기 때문에, 장성군 TAGO 노선 카탈로그에 포함된 광주 정류소가 좌표상 정상이어도 미분류로 남을 수 있었다.

- 경계 응답은 장성군과 광주광역시 범위를 모두 선택한다. 현재 넓은 WGS84 조회 박스는 두 지역을 포함하며, 장성군/광주광역시 이름 기준 필터로 담양 등 인접 지역 경계를 제외한다. 행정구역 코드를 하드코딩하지 않는다.
- 장성군은 기존처럼 `장성읍`, `남면` 등 읍·면 이름을 그대로 사용하고 상위 지역은 `장성군`으로 둔다. 광주는 동명이인 행정동 가능성과 `regions.region_name`의 유일성 제약을 고려해 `광주광역시 광산구 신가동`처럼 시·구·동 전체를 지역명으로 만들며, 상위 지역은 `광주광역시 광산구`로 둔다.
- point-in-polygon 분류 방식, 중복 후보 보류, 미분류 정류소만 적용, 기존 수동 분류 보존은 그대로다. TAGO 장성군 도시코드(36450) 카탈로그에 실제 들어온 정류소 좌표만 분류 대상으로 삼기 때문에 광주 전체 정류소를 임의로 DB에 추가하지 않는다.
- 대시보드 설명과 미리보기 결과에 장성군·광주광역시 범위를 표시하도록 했다. 새로운 외부 키나 별도 지도/WMS/WFS 서비스는 필요하지 않으며 기존 `MOCT_WFS_SERVICE_KEY`와 허용 도메인을 사용한다.
- `BoundaryRegionServiceTest`에 타 지역 경계 제외, 장성군 지역명 보존, 광주 지역명 구 단위 정규화 사례를 추가했다. 전체 Maven 테스트 **21개 통과, 실패 0, 오류 0 (`BUILD SUCCESS`)**.

이 시점에는 단위시험까지만 완료했다. 코드 변경은 아직 실행 중 컨테이너에 재배포되지 않았고, 로그인 후 관리자 미리보기에서 광주 경계가 포함되는지 및 장성군 노선 카탈로그 정류소 중 광주 좌표가 실제 매칭되는지 확인해야 한다. 이 미리보기만으로 DB는 바뀌지 않는다. 결과를 검토한 뒤 별도의 적용 버튼을 눌러야 실제 `regions`/`bus_stops`에 반영된다.

### 2026-10-03 수동 정류소 분류 제거 및 주기 자동 처리

운영자는 미분류 113건을 수동으로 처리하지 않고, TAGO 노선 변경을 주기 확인해 정류소 지역정보를 자동 보정하는 방식으로 결정했다. 따라서 화면에서 미분류 목록을 검토·수정하는 운영 절차는 제거한다.

- 대시보드의 “정류소 행정지역 분류” 패널, 경계 미리보기/적용, 지역 생성, 정류소별 지역 지정·해제 UI를 제거했다. 이에 연결된 관리자 수동 분류 컨트롤러/API도 제거했다. 미분류 건수는 대시보드의 TAGO 카탈로그 상태에 숫자로만 표시한다.
- TAGO 노선·경유 정류소 카탈로그는 기본 매일 03:00(Asia/Seoul)에 자동 동기화한다. `TAGO_CATALOG_SYNC_CRON`으로 실행 시각을 바꿀 수 있다. 이는 차량 위치 자동수집 설정 `TAGO_ENABLED`와 별개의 작업이다. TAGO 관리자가 버튼으로 즉시 카탈로그 동기화를 실행하는 기능은 유지한다.
- 카탈로그가 검증·저장된 다음 장성군 읍·면 및 광주광역시 구·동 행정경계와 좌표로 새로 들어온 미분류 정류소를 분류한다. 중복 경계, 경계 밖, 좌표 누락은 임의 추정하지 않고 미분류 상태로 둬 다음 주기 실행에서 다시 확인한다. 수동으로 이미 정해진 값은 덮어쓰지 않는다.
- 경계 API 실패는 TAGO 카탈로그 트랜잭션 이후 별도 처리되며, 지역분류 실패가 성공한 노선 카탈로그 저장을 되돌리지 않는다. 동기화 응답에는 자동 분류 성공/실패와 분류·미분류·중복 후보 수를 반환하고, 스케줄 로그에는 민감정보 없이 결과 요약만 기록한다.
- 실제 자동실행 시점·TAGO 요청 성공 여부·실제 113건이 줄었는지는 배포 후 수동 동기화 또는 다음 스케줄 실행 결과에서 확인한다. 배포와 health만으로 실제 정류소 분류 완료라고 간주하지 않는다.

이번 반영 검증: Maven `clean package` **BUILD SUCCESS**, 전체 테스트 **18개 통과/실패 0/오류 0**. Compose 설정 출력에서 기본 스케줄 `0 0 3 * * *` 및 `TAGO_ENABLED=false`(차량 위치 수집 비활성 유지)를 확인했다. 백엔드를 재빌드·재생성했고 `https://localhost:8443/api/actuator/health`는 `{"status":"UP"}`을 반환했다. 첫 정기 실행 전이므로 TAGO 동기화 및 정류소 자동분류가 실제 실행된 것은 아니다. 기존 미분류 113건도 아직 재분류되지 않았으며, 다음 03:00 실행 또는 수동 카탈로그 갱신에서 TAGO·VWorld 호출 결과와 분류/미분류/중복 수를 확인한다.

### 2026-10-03 TAGO `updowncd=2` 장성군 노선 방향 추론 적용

#### 적용 근거와 규칙

TAGO 문서의 원본 정의는 `updowncd=0`/`1`이며, 장성군 경유정보에는 `2`가 포함되어 API 원본값만으로는 0/1 방향을 직접 알 수 없었다. 사용자가 확인한 운수사 노선 검토 규칙에 따라 서버 내부 운영방향을 보완한다. 이 보완은 TAGO가 `2`의 공식 뜻을 제공했다고 간주하는 것이 아니며, API 원본 `directionCode`는 메모리상의 레코드와 교차검증 결과에 유지한다.

1. 장성터미널에서 출발하는 경로는 `UP`(사용자 규칙의 0, 장성읍 중심 외곽 방향), 장성터미널로 끝나는 경로는 `DOWN`(사용자 규칙의 1, 장성읍 방향)으로 판정한다.
2. 외곽 경로는 기준 노선 `TSB342000042`에서 `UP` 첫 정류장과 `DOWN` 마지막 정류장이 같은 사거리버스터미널임을 확인해, 그 정류장에서 시작하면 `UP`, 끝나면 `DOWN`으로 판정한다.
3. 위 두 기준으로 바로 분류되지 않는 경로는 같은 `route_name`의 이미 판정된 경로와 기점·종점을 비교한다. 같은 방향의 끝점은 같은 방향, 서로 반대 끝점은 반대 방향을 뜻한다. 정류장 ID가 변형 노선에서 다를 수 있으므로 정류장 ID 또는 공백 정규화 후 같은 정류장명을 비교한다. 증거가 충돌하거나 유일한 방향으로 수렴하지 않으면 `UNKNOWN`을 유지한다.
4. 운영 DB의 `bus_route_stops.direction`만 보완하며 행 순서·정류장 관계는 건드리지 않는다. 향후 매일 TAGO 카탈로그 동기화에도 동일한 추론을 적용한다.

#### 실제 DB 적용 결과

DB 적용 전 장성군(city_code `36450`)에는 105개 노선 중 31개 노선, 1,242개 경유 행이 `UNKNOWN`이었다. 읽기 전용 사전 집계에서 방향 충돌/판정 보류는 0개로 확인한 뒤, 명시적 트랜잭션으로 그 1,242행만 갱신했다.

| 판정 근거 | 노선 수 | 경유 행 | 판정 |
| --- | ---: | ---: | --- |
| 장성터미널 도착 | 14 | 582 | `DOWN` |
| 장성터미널 출발 | 12 | 509 | `UP` |
| route 042 기준 사거리버스터미널 도착 | 1 | 13 | `DOWN` |
| route 042 기준 사거리버스터미널 출발 | 2 | 27 | `UP` |
| 같은 노선명 변형의 끝점 정합 | 2 | 111 | 한 노선 54행 `UP`, 다른 노선 57행 `DOWN` |
| **기준 충족 합계** | **31** | **1,242** | **전부 분류** |
| **방향 기준 불일치·모호·미분류** | **0** | **0** | 없음 |

갱신 뒤 장성군 DB 방향별 경유 행은 `UP=2,649`, `DOWN=2,681`, `UNKNOWN=0`이며 노선 수는 105개다. 전체 시스템 DB에는 테스트 도시의 별도 2행이 있어 전체 테이블 행 수와 장성군 수를 혼동하지 않는다.

#### 검증과 남은 데이터 정합성 확인

- `TagoCatalogService`에 위 판정 규칙을 넣었고, 장성터미널 기준, route 042 기준, 동일 노선명의 같은/반대 끝점, 정류장 ID가 다르나 이름이 같은 경우, 근거가 없는 미분류 유지 및 원본 코드 보존을 단위시험으로 확인했다.
- Maven 전체 테스트: **19개 통과, 실패 0, 오류 0**. XML 외부 엔터티 거부 테스트는 의도된 보안 동작으로 parser의 DOCTYPE 거부 메시지가 표준출력에 나오지만 테스트는 통과한다.
- 기존 TAGO 동기화 요약에는 원본 API 경유 목록 5,339건으로 기록되어 있으나, 현재 장성군 `bus_route_stops`에는 5,330행이 저장되어 있다(차이 9행). 동기화 재시도 로그의 `DB_UNIQUE_bus_route_stops_pkey`를 통해 기존 기본키 `(route_id, node_id, direction)`가 동일 노선·방향 안의 정류장 재방문을 막는 것이 실패 원인임을 확인했다. V008에서 기본키를 `(route_id, direction, node_order)`로 변경해 각 정류장 방문 순서를 보존하고, 동일 순번만 중복 금지하도록 수정했다. V008을 운영 DB에 데이터 삭제 없이 적용 완료했으며, 현재 기존 5,330행과 방향 분류는 그대로 보존된다. 새 키로 TAGO 전체 재동기화한 뒤 5,339건 차이가 해소되는지 확인해야 하며, 동기화 성공 전까지 방향 0건 미분류를 “원본 경유 경로 전체 일치”로 확대 해석하지 않는다.
- Maven 전체 테스트 **19개 통과, 실패 0, 오류 0** 후 JAR 생성, egov-backend 이미지 재빌드 및 컨테이너 재생성을 완료했다. 로그에서 Spring Boot 기동 완료를 확인했고, nginx 컨테이너에서 `http://egov-backend:8080/api/actuator/health` 응답 `{"status":"UP"}`을 확인했다. Windows 호스트 `curl.exe`는 Schannel 자격 오류를 냈으므로 그 출력은 서버 실패로 보지 않았고, 컨테이너 내부 health 요청으로 검증했다.
- 현재 DB에도 1,242행 추론 업데이트를 이미 적용했다. 다음 정기/수동 TAGO 동기화부터 새 코드가 같은 판정을 재계산해 저장한다. 원본 upstream `updowncd=2`는 계속 공급기관에서 확인해야 할 의미이며 본 규칙은 사용자 승인 운영기준을 구현한 것이다.

### 2026-10-03 TAGO 재동기화 502 및 재방문 기본키 수정

백엔드 로그에서 대시보드 동기화 요청 수신 후 TAGO `resultCode=99` 재시도 1회, 최종 `DB_UNIQUE_bus_route_stops_pkey` 실패를 확인했다. 따라서 화면의 “TAGO 승인·키·도시코드” 안내는 이 실패 원인과 맞지 않았다. TAGO 외부 키 오류가 아니라 루프·분기에서 같은 정류장을 다시 지나는 유효 경로를 기존 기본키가 막은 문제다. 실패한 저장은 트랜잭션 롤백되었고 부분 카탈로그 반영은 없었다.

- `V008__route_stop_sequence_primary_key.sql`을 추가하고 운영 DB에 적용했다. 사전 검사에서 `(route_id, direction, node_order)` 중복이 있으면 마이그레이션을 중단하도록 했다. 기존 5,330행을 보존한 채 기본키를 `(route_id, direction, node_order)`로 바꿔 정류장 재방문을 허용하고, 같은 방향·순번 중복은 계속 막는다. 초기 스키마 정의도 같은 키로 맞췄다.
- 새 키 적용 후 기본키 정의와 장성군 방향 행(`UP=2,649`, `DOWN=2,681`, `UNKNOWN=0`)을 확인했고 백엔드 health는 `UP`이다. TAGO 재동기화는 마이그레이션 이후 아직 성공으로 검증되지 않았다. 관리자가 대시보드에서 **TAGO 노선·정류소 동기화**를 다시 실행한 뒤 로그에서 오류가 사라지는지, DB 경유 행 수가 원본 5,339건과 맞는지 확인한다.
- 502 당시 대시보드가 승인·키·도시코드 문제로 단정하던 안내를 TAGO 응답·카탈로그 검증·DB 저장 로그를 확인하라는 일반 안내로 수정했다. `mvn clean package` 성공 후 이미지 재빌드·백엔드 재생성을 마쳤고 health `UP`, PK `(route_id, direction, node_order)`, `UNKNOWN=0`을 재확인했다. 브라우저 탭/관리자 세션이 현재 작업공간에서 열려 있지 않아 인증된 **재동기화 버튼 실행은 아직 하지 않았다**.

### 2026-10-03 재동기화 후 UNKNOWN 705행 확인 — 이전 0행 보고 정정

이후 관리자가 대시보드에서 TAGO 동기화를 실행했고 성공 결과는 노선 105개, 경유 5,339건, 고유 정류소 951개였다. 성공 동기화 뒤 DB 집계는 `UP=2,062`, `DOWN=2,572`, `UNKNOWN=705`이다. 따라서 앞 절의 `UNKNOWN=0`은 재동기화 전 5,330행 스냅샷에 단회 적용한 SQL 보완 결과일 뿐, TAGO 재동기화 후 상태가 아니다. 당시 “현재 UNKNOWN 0”을 완료로 보고한 내용은 최신 결과와 다르므로 정정한다.

현재 705행은 18개 노선 경로의 원본 방향코드가 `2`/미상으로 들어와 자동 보완에서 분류되지 않은 경유 행이다. 노선별 행 수: TSB342000109 녹진100 62, TSB342000112 진원100 58, TSB342000111 진원100 57, TSB342000106 마령100 56, TSB342000110 진원100 55, TSB342000155 장성100 47, TSB342000038 21번 46, TSB342000103 장성100 45, TSB342000035·036 각 40, TSB342000049 31번 38, TSB342000047 30번 35, TSB342000030 20번 31, TSB342000087 85번 30, TSB342000032 20번 25, TSB342000041 23번 14, TSB342000039·040 각 13.

원인 검토: 기존 추론기는 장성터미널 기·종점 직접 비교와, 직접 추론된 같은 노선명 경로의 끝점 비교만 사용했다. TAGO가 이미 `UP`/`DOWN`으로 제공한 같은 노선명 기준 경로를 참조 후보로 포함하지 않아 85·100번 계열 등에서 판단 기회를 놓친다. 또한 구현 당시 route 042의 `UP` 첫 정류장과 `DOWN` 마지막 정류장이 동일한 사거리터미널일 것이라고 가정했지만, 현재 동기화 DB에서는 UP 첫 정류장이 신창마을이고 DOWN 마지막 정류장이 사거리버스터미널로 달라 특수 기준점이 만들어지지 않았다. 그러므로 자동 추론을 아직 완성으로 볼 수 없다.

705는 서로 독립된 705개 노선 오류가 아니라 18개 경로의 모든 미상 방향 경유 행이다. 다음 개선은 실제 route 042 방향별 경로와 같은 노선명의 기확정 경로를 대상으로 정류장 ID/명칭 및 정류 순서 일치도를 비교하고, 유일하고 충분히 강한 방향 증거가 있는 경로만 추론하도록 해야 한다. 증거가 부족하거나 상충하면 `UNKNOWN`으로 남겨야 한다. 재동기화 후 현재 5,339행 전체에서 회귀 검증하기 전까지 0건 처리 완료라고 보고하지 않는다. 자동 지역분류 838개·미분류 113개는 별도 행정지역 분류 상태이며 UNKNOWN 방향 수와 무관하다.

# 2026-10-03 표출 정책 활성화(enabled) 서버 적용

## 문제와 변경 범위

표출 정책 DB에 `enabled` 값이 있었지만 `/api/v1/display/next`의 선택 로직이 모든 경로에서 이를 일관되게 반영하지 않았다. 그 결과 운영자가 정책 화면에서 유형을 비활성화해도 도착정보/재난정보 또는 해당 유형의 일반 예약 콘텐츠가 선택될 수 있었다.

- ARRIVAL 및 DISASTER는 각 정책이 유효기간 내 존재하고 `enabled=true`일 때만 선택 후보로 사용한다.
- 일반 예약 콘텐츠는 해당 콘텐츠 유형의 유효한 `display_policies` 행이 활성화된 경우에만 조회한다. 비활성 정책 유형은 콘텐츠 목록에서 제외되며 다른 활성 콘텐츠 후보는 계속 처리된다.
- 정책 조회는 비활성 행도 읽어 `enabled` 상태를 판단하도록 변경했다. 정책 행이 없으면 안전하게 비활성으로 취급한다.
- 이번 변경은 `enabled` 토글의 서버 효력에 한정한다. ARRIVAL → 정책상 반복 시점의 DISASTER → 일반 콘텐츠라는 현재 선택 순서와 숫자형 `priority`를 이용한 전역 재정렬은 바꾸지 않았다. 표출·반복 간격의 모든 타입별 의미가 완전히 일치한다고도 간주하지 않는다.

## 검증 및 반영 절차

- `DisplayServiceTest`에서 정책 활성/비활성 및 정책 누락 시 비활성 처리 단위시험을 추가했다.
- Maven 전체 테스트: **13개 통과, 실패 0, 오류 0** (`BUILD SUCCESS`). 기존 TAGO 단위시험도 함께 통과했다.
- DB migration은 추가하지 않았고 운영 DB를 수정하지 않았다. 소스 변경만 반영된 상태이므로 실행 중인 Compose 서비스에는 아직 반영되지 않았다.
- 운영 반영 시 프로젝트 루트에서 `podman compose up -d --build --force-recreate egov-backend`를 실행하고, 대시보드에서 한 유형씩 비활성/재활성하여 `/display/next` 결과를 확인한다. 테스트 중 DB 볼륨 삭제 옵션 `-v`는 사용하지 않는다.

### 2026-10-03 UNKNOWN 31개 노선 방향 DB 정리 및 재동기화 고정

앞 절의 TAGO 동기화 후 `UNKNOWN=705`는 18개 노선이라는 집계는 CSV 원본을 전체 경로로 다시 묶어 본 결과와 달랐다. `TAGO_장성군_노선별_경유정류소_20261002.csv`에서 `direction_interpreted=UNKNOWN`인 데이터는 **31개 노선, 경유 행 1,248건**이었다. 그중 DB에는 기존 자동 추론으로 이미 UP/DOWN이 들어간 행도 있었기 때문에 DB의 UNKNOWN 수만 보고 판단하지 않고, CSV의 route id별 전체 경로와 사용자가 확인한 운행 방향 규칙을 대조했다.

#### 방향 판정값

| 방향 | 사용자 규칙 값 | 노선 ID |
| --- | ---: | --- |
| `UP` | 0 · 장성읍 중심에서 외곽으로 출발 | TSB342000029, TSB342000030, TSB342000031, TSB342000033, TSB342000034, TSB342000037, TSB342000039, TSB342000041, TSB342000046, TSB342000048, TSB342000102, TSB342000104, TSB342000105, TSB342000107, TSB342000108, TSB342000110 |
| `DOWN` | 1 · 외곽에서 장성읍 중심 방향 | TSB342000032, TSB342000035, TSB342000036, TSB342000038, TSB342000040, TSB342000047, TSB342000049, TSB342000087, TSB342000103, TSB342000106, TSB342000109, TSB342000111, TSB342000112, TSB342000134, TSB342000155 |

직접 장성터미널을 기점/종점으로 갖는 경로는 사용자 규칙 2를 적용했다. 외곽 경로는 같은 노선명의 정방향·역방향, 방향이 확인된 042번 기준 경로, 또는 장성터미널 종점의 짝 경로를 이용했다. `TSB342000030`은 사용자가 확인한 실제 방향(장성터미널 → 사거리버스터미널)을 우선해 `UP`으로 두었다. 정류장 ID, 이름, 원본 응답 순서, `node_order`는 수정하지 않았다.

#### DB 반영 및 백엔드 재동기화 방지

- 대상 31개 route id의 `bus_route_stops.direction` 컬럼만 갱신했다. 기존 값과 달라 실제 UPDATE된 행은 1,214건이며, 나머지 34건은 이미 최종 방향값과 일치했다. 적용 뒤 전체 1,248행이 `UP` 또는 `DOWN`이고 대상 노선별 행 수와 distinct `node_order` 수가 일치하는 것을 확인했다.
- 장성군 `city_code=36450` 전체 방향 집계: `UP=2,684`, `DOWN=2,655`, `UNKNOWN=0` (총 5,339행). 개별 확인: `TSB342000030=UP` 31행, `TSB342000032=DOWN` 25행.
- 향후 TAGO 재동기화가 방향을 다시 `UNKNOWN`으로 저장하지 않도록 `TagoCatalogService`에 이번에 검토한 31개 노선의 운영 방향 맵을 명시했다. 이 매핑은 TAGO 원본 updowncd의 의미를 바꾸지 않고 저장 직전 운영용 `direction`만 보완한다. 원본 정류장 행은 보존한다.
- 검토 노선 매핑 회귀시험을 추가했다. 특히 030번의 명시 방향이 단순한 기·종점 자동 추론에 의해 덮어써지지 않고, 정류장 ID와 순번이 유지되는지 확인한다.
- Maven 전체 테스트 **20개 통과, 실패 0, 오류 0**. Maven `-DskipTests package` **BUILD SUCCESS**, egov-backend 이미지 재빌드 및 컨테이너 재생성 완료. `/api/actuator/health` 응답 `{"status":"UP"}`을 확인했다.

이번 작업은 운영 DB의 방향 컬럼 정리와 재동기화 시 동일 판정을 유지하는 코드 반영까지 완료했다. 다음 단계는 TAGO 전체 카탈로그를 다시 동기화해 노선 105개·경유 5,339건이 보존되고, 위 31개 노선의 방향이 유지되는지 확인하는 것이다.

### 2026-10-03 장성·광주 미분류 정류소 경계 필터 수정

#### 확인된 원인

대시보드 카탈로그에서 지역 미분류 113개가 남아 있던 원인은 정류소 좌표 자체의 결함으로 단정할 수 없었다. 현재 VWorld 행정경계 API의 `full_nm`은 광주 지역을 `전남광주통합특별시 광산구 신가동`처럼 반환하지만, 기존 필터는 예전 명칭인 `광주광역시`만 대상으로 인식했다. 그 결과 장성군 경계는 조회해도 광주 구·동 경계가 후보에서 빠져, 장성에서 광주까지 가는 노선의 광주 정류소가 미분류로 남을 수 있었다.

#### 수정 내용

- 대상 판정을 현 API 형식인 `전남광주통합특별시`로 확장했다. 장성군은 계속 포함하고, 통합특별시 전체를 무차별 포함하지 않도록 광주 5개 자치구(광산·북·동·남·서구)만 허용한다. 나주·담양 등 인접 시·군은 포함하지 않는다.
- 신규 경계의 DB 표기는 기존 시스템과 호환되도록 `광주광역시 <구> <동>` 및 부모 `광주광역시 <구>`로 표준화한다. 구 코드가 바뀌어 같은 구역 이름의 중복 지역이 생기지 않게 했다.
- 이미 저장된 노선/경유정류소를 다시 TAGO에서 가져오지 않고, 현재 DB에서 지역이 비어 있는 정류소만 다시 판정하는 관리자 API `POST /api/v1/admin/boundary-regions/classify-unassigned`와 수집 상태 화면의 **미분류 정류소 다시 분류** 버튼을 추가했다. 이 API는 기존 지역 분류값이 있는 정류소와 노선·방향·순번을 수정하지 않는다. 경계 겹침이나 좌표 불일치는 추정하지 않고 미분류로 유지한다.
- 대시보드 실행 결과에 조회한 행정경계 수, 새 지역 수, 이번에 새로 분류된 정류소 수, 겹침 및 미매칭 수가 표시되도록 했다. 화면의 카탈로그 상태를 다시 불러와 실제 미분류 수를 함께 확인한다.

#### 테스트·반영 및 운영 확인

- 테스트는 구 API 응답 형식(`전남광주통합특별시 광산구 신가동`, `북구 용봉동`)이 포함되고 나주 등 인접 지역은 제외되는지, 기존 `광주광역시` 표기와 장성군 필터는 계속 지원되는지 검증한다. Maven 테스트 **20개 통과, 실패 0**.
- `mvn -DskipTests package` 패키징, egov-backend 이미지 빌드 및 컨테이너 재생성을 완료했다. 컨테이너 내부 `/api/actuator/health`는 `{"status":"UP"}`.
- 분류 API는 관리자 세션과 CSRF 검증이 적용되는 대시보드 전용 작업이다. 운영 DB의 실제 113개 결과는 관리자가 대시보드의 수집 상태 메뉴에서 버튼을 실행한 뒤 응답과 갱신된 카탈로그 숫자로 확인해야 한다. 이 로그 작성 시점에는 인증 세션에서 버튼을 실행하지 않았으므로 113개가 모두 해소됐다고 단정하지 않는다.

### 2026-10-03 TAGO 실시간 차량 위치→방향별 도착 예측 연결

#### 확정된 데이터 흐름

TAGO 버스 위치정보 API는 요청-응답 방식이며, 노선별 조회(`getRouteAcctoBusLcList`) 응답에 차량번호(`vehicleno`), GPS 위·경도(`gpslati`, `gpslong`), 위치에 대응하는 정류소 ID/순번(`nodeid`, `nodeord`)을 제공한다. API가 DB나 Android에 데이터를 자동 전송하는 구조가 아니므로 서버가 장성군 노선 카탈로그를 기준으로 API를 주기 요청하고, 응답을 저장해 기존 표출·도착 판정이 사용하도록 연결했다. 차량번호나 노선 방향을 임의 생성하지 않는다.

`TAGO 위치 API → bus_positions(관측 GPS·노선·정류소 순번·UP/DOWN) → ArrivalService(해당 방향 다음 정류소까지 거리 계산) → arrival_predictions(100m 판정/만료시각) → DisplayService(/display/next 우선순위)` 순서다. 정류소 순번이 해당 노선에서 UP 또는 DOWN 하나로 유일하게 대응되는 위치만 방향을 확정한다. 모호하면 GPS 관측은 보관하되 방향별 도착 예측은 만들지 않는다. 2분보다 오래된 GPS는 `/arrival`이 사용하지 않는다.

#### 구현 및 설정

- `TagoVehicleLocationService`가 활성 장성군 노선 각각에 대해 위치 API를 페이지 조회한다. 동시 요청 수를 8개로 제한하고 요청 시작 간격을 최소 150ms로 두며, 이전 전체 순회가 끝나지 않았으면 중복 실행하지 않는다. 차량이 운행하지 않는 노선의 정상 응답 0건은 오류로 취급하지 않는다. 실패한 노선 수는 상태 API에 `DEGRADED`로 구분한다.
- 차량 위치 테이블에 `direction`을 추가하는 `V009__bus_position_direction.sql`을 작성하고 개발 DB에 비파괴적으로 적용했다. 초기 schema와 `V002`도 같은 컬럼을 포함하도록 맞췄다.
- `.env`의 TAGO 활성·실서비스 모드·차량 위치 수집을 켜고, `TAGO_VEHICLE_LOCATION_POLL_INTERVAL_MS=30000` 설정으로 실행했다. 서비스키 값은 문서와 로그에 기록하지 않는다.
- 대시보드 수집 상태에 차량위치 수집기의 활성/모의 상태, 마지막 완료 시각, 저장 건수와 실패 노선 수를 표시한다. 관리자 `GET /api/v1/admin/system-status` 응답의 `tagoVehicleLocation` 항목으로 확인한다.

#### 실제 API·DB 검증

- 장성군 노선 `TSB342000030`을 대상으로 실서비스 키를 이용한 1회 읽기 요청: HTTP 200, TAGO `resultCode=00`, `NORMAL SERVICE.`를 확인했다. 요청 시점 해당 노선 운행차량 응답은 0건이었다. 차량번호와 GPS 원값은 로그에 출력하지 않았다.
- 전체 백엔드 테스트 및 패키징 성공. XML 외부 엔티티 차단과 차량위치 응답 필드 형식, 기본 비활성 상태 테스트를 포함한다.
- 운영 개발환경 컨테이너에서 첫 전체 순회: `routes=105`, `failedRoutes=0`, `observationsSaved=15`; backend health `UP`.
- 최근 3분 DB 집계: `bus_positions` 16건, 방향 미상 0건, `arrival_predictions` 16건, `within_100m=true` 2건. 따라서 TAGO 위치 수집에서 도착 예측 생성까지 실제 응답으로 연동됨을 확인했다. 해당 시점 운행 위치 건수이므로 이후 수집 주기에 따라 값은 달라진다.

#### 범위

이번 반영은 TAGO 실시간 위치를 서버 DB와 기존 방향·100m 도착 예측에 연결했다. 행정구역 미분류 건을 해결 과제로 확대하지 않았고, 정류소 순서·노선 방향 판정 데이터도 수정하지 않았다. API에 실제 차량이 없는 응답은 0건으로 기록하며 임의 위치 샘플로 대체하지 않는다.

### 2026-10-03 운영 규칙 변경: 장성 외 정류소를 광주 그룹으로 분류

#### 배경과 확정 기준

기존에는 VWorld 경계와 점 좌표가 단일 읍·면·동으로 일치하지 않으면 미분류로 남겼다. 이 때문에 대시보드의 “미분류 44개”가 대한민국 밖 좌표라는 뜻처럼 오해되었으나, 그 수치는 단지 기존 행정경계 분류기가 지역명을 부여하지 못한 DB 행 수다. TAGO 카탈로그에 수록된 정류소이며, 미분류만으로 좌표의 국내외 여부를 판단할 수 없다.

운영 목적은 행정통계가 아니라 장성군 노선의 콘텐츠 지역 그룹핑이다. 확정 규칙은 **장성군 읍·면 경계 안이면 해당 읍·면, 장성군으로 분류되지 않는 나머지는 `광주광역시` 그룹**이다. 따라서 좌표 누락·경계 중복·광주 구·동 경계 일치도 이 업무 규칙에 따라 광주 그룹에 포함한다. 이는 행정주소 확정이 아니라 콘텐츠 선택을 위한 fallback 그룹이다.

#### 코드 변경

- `BoundaryRegionService.classifyUnassigned()`는 먼저 기존과 같이 장성군 및 광주 행정경계와 좌표를 대조한다. 단일 경계가 확인된 장성 읍·면 및 광주 구·동 분류를 적용한다.
- 그 뒤에도 `region_id`와 `region_name`이 모두 비어 있는 활성 장성군 도시코드 정류소는 공통 `광주광역시` region에 할당한다. 이미 분류된 정류소는 보존한다.
- 노선, 원본 TAGO 응답, 방향, 경유 순번과 GPS 좌표는 수정하지 않는다. 적용 범위는 `bus_stops.region_id/region_name`뿐이다.
- 응답은 전체 분류 수, 광주 fallback 배정 수, 경계 미일치/중복 참고 수 및 최종 미분류 수를 구분한다. 최종 미분류는 실제로 지역이 비어 있는 행만 뜻한다.
- 대시보드의 안내·확인 문구도 이 기준으로 수정했다. 기존 “좌표 누락/경계 밖은 미분류로 보존” 설명은 폐기한다.

#### 검증 및 적용 상태

백엔드 테스트 **23개 통과**, `mvn clean package -DskipTests` **BUILD SUCCESS**, Podman backend 이미지 재빌드·컨테이너 재생성을 완료했다. HTTPS actuator health는 `{"status":"UP"}`이다. DB 적용 전 집계는 장성군 정류소 951개 중 미분류 44개였고, 해당 44개만 공통 `광주광역시` 그룹으로 배정했다. 적용 후 `광주광역시` 기본 그룹 44개, 기존 장성 읍·면 및 광주 구·동 지역 분류는 유지, 최종 미분류 0개를 SQL로 확인했다. 노선·경유 순번·좌표는 변경하지 않았다. 관리자 수동 분류 API도 같은 기준을 사용하므로 다음 TAGO 자동 동기화에서 들어오는 미분류 정류소에도 동일 규칙이 적용된다.

### 2026-10-03 타입 정책 priority의 실제 표출 선택 적용

기존 `/api/v1/display/next`는 도착정보를 먼저 반환하고, 그 다음 재난정보, 마지막에 예약 콘텐츠를 반환하는 고정 분기였다. 따라서 대시보드에서 `display_policies.priority`를 바꿔도 후보 간 실제 표출 우선순위에는 반영되지 않았다.

- 활성 도착정보·재난정보·일반 콘텐츠 후보를 모아 유효 정책의 priority가 가장 높은 항목을 반환하도록 변경했다. priority가 같으면 도착정보, 재난정보, 일반 콘텐츠 순으로 결정한다.
- 예약 콘텐츠 조회도 정책 priority로 정렬하고, `force_display=true` 콘텐츠는 일반 후보보다 먼저 선택한다. 표출/반복 간격은 기존 API 응답값과 단말 전환 흐름을 유지한다.
- DB 노선·정류소 원본은 변경하지 않았다. `DisplayServiceTest`에 서로 다른 우선순위와 동순위의 선택 회귀시험을 추가했다.

검증: Maven 전체 테스트 **25개 통과**, `mvn clean package -DskipTests` **BUILD SUCCESS**, backend 이미지 재빌드 및 컨테이너 재생성 완료. 외부 HTTPS actuator health `{"status":"UP"}` 및 PostgreSQL/Node watcher/Nginx/backend 컨테이너 실행 상태를 확인했다. 정책을 실제 대시보드에서 바꾼 뒤 도착정보·재난·콘텐츠가 바뀐 순서로 연속 표출되는 브라우저/Android 통합시험은 아직 별도 확인 대상이다.

### 2026-10-04 콘텐츠 계약 v2 및 다중 미디어 API

#### 스키마/API 반영

`V010__content_data_contract_v2.sql`을 현재 PostgreSQL에 적용했다. `contents`에 `category`(NOTICE/EVENT), `presentation_type`(POSTER_TEXT/TEXT/CARD_NEWS/IMAGE/VIDEO)를 추가하고 기존 8개 행을 보존·backfill했다. 하위 `content_assets` 테이블은 역할, sequence, 파일명, content root 기준 상대경로, MIME, 바이트 크기, SHA-256을 보유한다. 기존 `content_type`, `target_file_name`, `source_path`, 단일 미디어 URL은 이전 클라이언트를 위해 유지한다.

콘텐츠 목록과 관리자 데이터 응답에는 업무 분류/표현 형식/첨부 배열을 포함한다. `/api/v1/media/{contentId}/assets`는 메타데이터 목록, `/api/v1/media/{contentId}/assets/{sequence}`는 특정 자산을 스트리밍한다. 절대·상대 경로 양쪽을 안전하게 content root 아래에서 해석하고, 기존 `/api/v1/media/{contentId}`는 하위호환 경로로 남긴다. Display API는 자산 배열의 순서를 보존해 Android가 카드뉴스를 진행할 수 있게 한다.

#### 빌드 및 통합 확인

- Maven `mvn test`: **29 tests, BUILD SUCCESS**.
- Maven `mvn package`: **BUILD SUCCESS**, 패키징된 JAR로 backend 이미지 재빌드 및 컨테이너 재생성.
- HTTPS actuator health: `{"status":"UP"}`.
- 실제 v2 배치 5개 콘텐츠 모두 `/api/v1/contents`에서 `category`, `presentationType`, `assets`로 확인.
- 카드뉴스 두 번째 자산 다운로드: HTTP 200, `image/jpeg`, 6,436,410 bytes.
- 기존 업로드 JSON source_path가 `/app/upload-store/...`였던 `TEST_20260924_0001`은 content root 밖이므로 새 assets에 허위 매핑하지 않았다. 원 contents 행은 보존되며 과거 media URL은 파일이 서버 저장소에 없으면 실패할 수 있다. 이 레거시 데이터의 파일 연결은 원본 파일 대조가 필요하다.

Dashboard의 데이터 API는 신규 분류 및 자산 필드를 반환하지만, 실제 브라우저에서 자산 미리보기·관리 조작을 검증한 것은 아니다. Android의 서버 자산 전달 API는 확인됐고 APK 테스트/빌드도 성공했으나 24인치 실제 화면에서 서로 다른 CARD_NEWS 파일이 순서대로 전환되는 시각 인수시험은 후속 작업이다.

### 2026-10-04 기존 콘텐츠 교체 및 실제 다음 표출 확인

사용자 요청에 따라 변경 전 PostgreSQL custom-format dump를 `%TEMP%\smartbis-before-festival-20261004-151820.dump`에 생성했다. 그 뒤 기존 DB 콘텐츠 13건과 이 콘텐츠를 참조하던 표출 스케줄 4건을 transaction으로 제거하고, 새 축제 배치의 콘텐츠 12건만 남겼다. 새 12건에 활성 `content_schedules`를 각각 생성하고 `min_display_seconds=10`을 설정했다. 최종 DB 조회는 `contents=12`, 활성 스케줄 12건이다. 과거 미디어 파일과 원본 업로드는 디스크에서 삭제하지 않았다.

실제 `/api/v1/display/next?vehicleNumber=SAMPLE-001` 응답은 `FESTIVAL_CARDNEWS_20261004_001`을 반환했으며 `presentationType=CARD_NEWS`, `displaySeconds=10`, 1~4 순서의 카드 자산 URL을 포함했다. 영상 및 포스터/카드 자산 다운로드도 HTTP 200을 확인했다. 이는 API 선택과 파일 제공 검증이며 Android 24인치 단말에서 전체 콘텐츠가 계속 순환하는 장시간 화면 시험은 별도다.

### 2026-10-04 TAGO 카탈로그 DB 점검 및 변경 감지·동기화 이력

#### 현재 카탈로그 DB 기준선

운영 PostgreSQL을 읽기 전용으로 점검한 결과 장성군 `city_code=36450`은 활성 노선 105개, 활성 정류소 951개, 노선-경유 연결 5,339건이다. 105개 노선 모두 경유 행을 가지며, 정류소 마스터가 없거나 노선이 없는 고아 경유 행, 비활성 노선의 잔존 경유 행, 활성 노선에 연결되지 않은 활성 정류소, 노선·방향·순번 중복은 각각 0건이다. 운영 방향은 `UP=2,684`, `DOWN=2,655`, `UNKNOWN=0`으로 확인했다. 방향 정리는 표시 방향 열에만 반영했고, 정류장 순서와 노선-정류소 연결 데이터는 이 정리 작업에서 건드리지 않았다. 전체 DB에는 기존 `SAMPLE_CITY` 테스트용 노선 1개와 정류소 2개가 별도 존재하며 장성군 카탈로그와 구분해 보존한다.

#### 이후 TAGO 업데이트 운영 흐름

- 기존 자동 전체 동기화(매일 03:00, `Asia/Seoul`)와 대시보드의 **TAGO 노선·정류소 동기화** 수동 실행을 유지한다. 어느 실행 경로든 동일 서비스가 TAGO 전체 노선·노선별 경유 정류소를 조회하고, 기존 운영 방향 보완 규칙을 거쳐 검증된 스냅샷을 트랜잭션으로 반영한다. TAGO 차량 위치 자동 수집 설정과는 별개다.
- `V011__tago_catalog_sync_runs.sql`을 추가·운영 DB에 적용해 자동/수동 구분, 시작·완료 시각, 성공/경고/실패, 노선·경유·정류소 수, 미분류 방향 수, 응답 순번 보완 수, 실패 코드, 스냅샷 SHA-256을 기록한다. 최대 최근 10회 이력을 관리자 수집 상태 화면에서 확인한다.
- 성공 스냅샷의 SHA-256을 직전 성공 스냅샷과 비교해 다음 실행부터 “변경 감지/변경 없음”을 보여준다. 마이그레이션 이후 첫 성공 실행은 비교 기준을 저장하는 회차로 표시되며, 그 전 이력은 없다. 실패 코드는 상태 화면에 남고 기존 카탈로그 자료는 TAGO 전체 응답과 검증이 성공하기 전까지 바뀌지 않는다.
- 다음 완전 스냅샷에서 사라진 TAGO 노선은 `active=false`로 비활성화하고, 활성 노선 어디에도 연결되지 않는 TAGO 정류소 마스터도 비활성화한다. 기존 노선·경유 이력을 삭제하지 않으며, 들어온 노선의 경유 연결은 새 완전 스냅샷으로 교체한다. 운영 방향 `UNKNOWN` 또는 TAGO 순번 누락에 따른 응답 순서 보완이 있으면 실행 자체를 숨기지 않고 `SUCCESS_WITH_WARNINGS`로 기록하며 두 품질 지표는 따로 표시한다.

#### 적용 및 검증

- `V011`은 별도 이력 테이블과 인덱스를 추가하며 기존 노선/정류소 데이터 삭제를 포함하지 않는다. 운영 DB에서 재실행 가능한 방식으로 적용하고 컬럼·제약조건을 확인했다.
- Maven `clean verify`: 테스트 **29개 통과**, 실패/오류 0. 새 JAR을 만든 뒤 backend 이미지를 캐시 없이 재빌드하고 egov-backend만 재생성했다. Actuator health `{"status":"UP"}`.
- 배포 후 실제 DB 기준선 재조회: 활성 노선 105, 고유 정류소 951, 경유 5,339, 방향 미분류 0. 대시보드 관리자 세션으로 전체 수동 동기화를 실행해 `SUCCESS_WITH_WARNINGS`, 노선 105, 경유 5,339, 정류소 951, 방향 미분류 0, 응답 순번 보완 105건을 확인했다. 활성 DB 행 수도 동기화 전후 105/951/5,339로 확인했고, 비활성 노선 0·고아 관계 0이다. 첫 동기화이므로 `catalogChanged=null`(직전 스냅샷 없음)이며 SHA-256 기준값을 저장했다. 다음 수동/자동 전체 실행에서 변경/변경 없음이 비교된다.
- 최종 Maven 전체 테스트 **29개 통과**, backend 이미지 캐시 없는 재빌드와 컨테이너 재생성 후 health `UP`. 인증 관리자 API에서도 활성 노선·정류소·경유 행 수와 마지막 실행 `MANUAL / SUCCESS_WITH_WARNINGS / 순번 보완 105 / 변경 기준 설정`을 읽어 검증했다.

### 2026-10-04 수집 상태 화면 운영용 정리

수집 상태 메뉴에서 일상 운영자가 직접 사용할 필요가 없는 TAGO API 교차검증(DB 미저장 진단)과 미분류 정류소 수동 재분류 버튼을 제거했다. 해당 기능의 화면 호출 코드도 제거하여 일반 수집 상태 화면에는 진단·일회성 정비 조작이 남지 않도록 했다. 기존 백엔드 관리 API는 삭제하지 않았다.

일반 운영 화면에는 실제 카탈로그·차량 위치·재난문자·Node watcher 상태, 최근 자동/수동 TAGO 동기화 이력, 상태 새로고침, 필요 시 전체 TAGO 카탈로그를 갱신하는 “지금 동기화”만 유지한다. 카탈로그는 매일 03:00(Asia/Seoul)에 자동 동기화되고, 수동 동기화도 전체 조회와 검증이 성공한 뒤 DB에 반영된다는 안내를 추가했다. 홈의 과거 “실노선·차량 매칭 및 자동 수집기 구현 필요” 상태 문구도 현재 운영 상태에 맞게 수정했다.

이 변경은 Dashboard 정적 HTML/JavaScript만 대상으로 한다. Maven 검증과 backend 정적 리소스가 포함된 이미지 재빌드·컨테이너 재생성 후, 실제 브라우저에서 수집 상태의 버튼 구성과 상태 API 표시를 확인한다.

### 2026-10-05 Android heartbeat 및 운영 모니터 API

대시보드의 표출 모니터가 정적 안내 문구만 표시하던 부분을 실제 단말 상태 흐름으로 연결했다.

- `V012__android_device_status.sql`이 차량별 최신 상태 테이블과 last-seen 인덱스를 추가한다. 기존 콘텐츠·운행 테이블은 수정하지 않는다. 운영 PostgreSQL 적용 후 컬럼·기본키·상태 제약조건을 확인했다.
- `POST /api/v1/device-status`는 Android가 표출상태 변경 때와 30초마다 보내며 `204`를 반환한다. 차량번호/상태 열거값/필드 길이를 검증하고 서버 수신시각으로 upsert한다. 이 단말 POST는 인증/CSRF 없는 제한된 상태 쓰기이며, 현재 요청은 내부 HTTPS 배포 환경을 전제로 한다.
- `GET /api/v1/admin/device-status`는 관리자 인증으로 상태 목록을 제공한다. 이 조회는 `display/next`를 호출하지 않아 서버의 콘텐츠 순환 위치를 바꾸지 않는다. 대시보드는 최근 90초 이내 보고를 online, 그 이상은 offline으로 표시하고 현재 유형·제목·ID·오류를 보여준다.
- 단위/API 검증용 고유 임시 차량으로 POST 204와 PostgreSQL upsert를 확인한 다음 그 테스트 행만 제거했다. Maven `mvn -o test package`: **32 tests, 0 failures/errors**. 새 이미지로 `egov-backend`만 재생성했다.
- 브라우저 관리자 로그인 상태에서 모니터 표 렌더링은 아직 직접 검증하지 않았다. Android APK 설치 및 실 단말 heartbeat도 adb 프로세스 Access Denied 때문에 이번 실행에서 확인할 수 없었다.

### 2026-10-05 다음 정류소 TAGO ETA API 연결

Android 24인치 공통 상단 중앙 오버레이에서 다음 정류소와 “약 N분 후 도착”을 표시하도록 도착 API를 확장했다.

- `/v1/arrival`은 기존 실시간 GPS/노선 방향/순번 로직으로 다음 정류소를 찾고, TAGO `ArvlInfoInqireService/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList`에 장성군 cityCode, 해당 routeId, 다음 nodeId로 요청한다. TAGO `arrtime` 초를 분 단위 올림하여 `estimatedArrivalMinutes`로 반환한다. 분 값은 거리/고정속도로 추정하지 않는다.
- 사용자가 지목한 `BusLcInfoInqireService/getRouteAcctoSpcifySttnAccesBusLcInfo`는 정류소 접근 차량의 GPS 위치를 반환하지만 도착 초/분을 제공하지 않는다. 따라서 분 값의 데이터 원천은 도착정보 서비스이며, 기존 차량별 GPS 수집·다음 정류소 계산과 조합한다. 도착정보 응답에는 차량번호가 없어 ETA는 routeId+nodeId 기준이며 현재 표출 차량 한 대만의 ETA라고 보장하지 않는다.
- routeId+nodeId 기준으로 15초 메모리 캐시를 두어 차량이 같은 노선·다음 정류소를 공유할 때 중복 외부 호출을 줄인다. TAGO 비활성/mock 모드, 오류, 유효한 응답 없음은 `null`로 처리한다. Android 중앙 오버레이는 `다음 정류장 → 정류소명 · 약 N분 후 도착`을 보이고, ETA가 없으면 정류소명만 표시한다.
- XML 외부 엔티티 차단, 경로(routeId) 일치, 최소 도착초 선택, 분 올림, 무응답/오류 응답 테스트 추가.
- Maven `mvn test`: **35 tests, 0 failures/errors, BUILD SUCCESS**. 실제 TAGO 도착정보 서비스의 접근승인 및 live ETA 응답은 이번 테스트에서 호출하지 않았으므로 운영 키로의 종단 확인이 남아 있다.
