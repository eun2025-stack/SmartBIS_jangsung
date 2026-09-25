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
    └── content-store
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
content-store/YYYY-MM-DD/BATCH_ID/file_name
```

DB의 `source_path`는 `content-store` 기준 상대경로다.

```text
2026-09-25/BATCH_20260925_0001/장성 관광 홍보 영상.mp4
```

Spring Boot는 컨테이너의 다음 경로를 읽기 전용으로 사용한다.

```text
/app/content-store
```

경로에 `../`가 포함되어 content-store 외부로 벗어나는 요청은 차단한다.

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
- [ ] DB `source_path`가 상대경로인가
- [ ] Spring Boot가 `/app/content-store`를 읽을 수 있는가
- [ ] Nginx 재생성 후에도 API가 동작하는가
