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

DB의 `source_path`는 컨테이너 절대경로가 아닌 `content-store` 기준 상대경로를 저장한다.

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
- `content.root`와 상대경로를 조합한다.
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

## 미디어 경로 문제

DB `source_path`는 컨테이너 절대경로가 아닌 content-store 기준 상대경로다.

```text
2026-09-25/BATCH_20260925_0001/파일명.mp4
```

## 최종 API 정상 판정

```text
HTTP/1.1 200
Content-Type: video/mp4
Content-Length: 원본 파일 크기
Accept-Ranges: bytes
```

