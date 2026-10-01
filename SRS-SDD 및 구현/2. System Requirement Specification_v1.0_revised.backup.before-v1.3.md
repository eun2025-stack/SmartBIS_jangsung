# System Requirement Specification v1.0

## 1. 시스템 범위

본 시스템은 서버와 차량용 Android 클라이언트 사이의 콘텐츠/BIS 데이터 통신 시스템이다. 브라우저 기반 관리자 화면, 외부 Tomcat, 별도 WAS 서버, `/sht_webapp` URL은 범위에 포함하지 않는다.

## 2. 아키텍처

```text
Android Client
      │ HTTPS 8443
      ▼
Nginx TLS Gateway :443 (host port 8443)
      │ internal HTTP
      ▼
Spring Boot Embedded WAS Container :8080
      ├─ PostgreSQL 15
      └─ Node.js watcher / upload-store
```

외부에서 허용하는 포트는 `8443` 하나다. `8080`, `3000`, `5432`는 외부 클라이언트에 공개하지 않는다. Spring Boot, Node watcher, PostgreSQL, Nginx는 모두 Compose 컨테이너로 실행한다.

## 3. 기능 요구사항

### SR-API

- Spring Boot는 차량 식별자와 노선/지역 정보를 받아 콘텐츠 동기화 API를 제공한다.
- 응답에는 유효기간 내 콘텐츠, 미디어 파일 식별자, 템플릿, 자막 텍스트가 포함된다.
- 모든 외부 API는 `/api/v1` 하위에 둔다.
- 미지원 경로는 404 JSON 응답을 반환한다.

### SR-CONTENT

- Node watcher는 `upload-store/YYYY-MM-DD`를 감시한다.
- JSON과 미디어 파일의 업로드 완료 여부를 검증한다.
- JSON 스키마, 파일 확장자, MIME/Magic Number, 유효기간을 검증한다.
- 검증 성공 데이터만 PostgreSQL에 적재한다.

### CR-ANDROID

- Android 앱은 `https://10.0.2.2:8443`을 통해서만 서버에 접근한다.
- Room DB에 동기화 결과를 저장하고 네트워크 단절 시 캐시를 사용한다.
- 미디어는 임시 파일로 저장한 뒤 검증 완료 후 원자적으로 교체한다.
- ExoPlayer는 VIDEO를 재생하고 자막 오버레이는 VIDEO에만 적용한다.
- BIS 위치정보와 콘텐츠 표출 상태는 앱 내부에서 관리한다.

## 4. 보안 요구사항

- Nginx만 외부에 공개한다.
- TLS 1.2 이상을 사용한다.
- Android에는 사설 CA 또는 검증된 공개키를 내장한다.
- DB 계정과 인증서는 `.env` 및 비밀 저장소로 관리한다.
- 업로드 파일명은 서버 내부 저장 시 UUID로 변환한다.
- SQL은 파라미터 바인딩을 사용한다.
- `server/` 내부 소스와 인증서 개인키는 업로드 계정에 노출하지 않는다.

## 5. 데이터 계약

VIDEO 콘텐츠는 `summary`를 필수로 한다. IMAGE/CARD 콘텐츠의 `summary`는 `null`이다.

```json
{
  "content_id": "CNT_20260921_0001",
  "content_type": "VIDEO",
  "title": "장성 황룡강 가을 노란꽃 잔치 홍보 영상",
  "content": "장성군 황룡강 일원에서 펼쳐지는 노란꽃 잔치!",
  "summary": "황룡강 노란꽃 잔치 홍보 영상입니다.",
  "target_file_name": "hwangryong_flower_2026.mp4",
  "display_start_date": "2026-10-01",
  "display_end_date": "2026-10-15",
  "target_regions": ["장성읍", "남면"],
  "template": "행사"
}
```

## 6. 오류 및 상태 기준

- TLS/인증서 오류: 인증서 SAN 및 Android 신뢰 설정 점검
- 404: API 경로 또는 Spring Controller 매핑 오류
- 502: Nginx와 Spring Boot `8080` 연결 오류
- 400: JSON 스키마/필수 필드 오류
- 401/403: 차량 인증 실패
- 500: 서버 또는 DB 처리 오류

## 7. 검증 시나리오

1. Spring Boot 단독 실행 후 내부 health 확인
2. Nginx를 통한 `8443` TLS health 확인
3. 잘못된 `/sht_webapp/` 요청이 지원되지 않는 경로로 거부되는지 확인
4. Android Emulator에서 `10.0.2.2:8443` API 호출
5. 정상 VIDEO JSON 적재 및 동기화 확인
6. `summary` 누락 VIDEO 거부 확인
7. 만료 콘텐츠가 응답에서 제외되는지 확인
8. PostgreSQL 중지 시 적절한 오류 응답 확인
9. 네트워크 중단 후 Android Room 캐시 재생 확인

## 8. 기술 스택

- Podman Compose
- Nginx Alpine
- Spring Boot 3.x 내장 WAS, 컨테이너 실행
- Java 17 이상
- PostgreSQL 15 이상
- Node.js, Chokidar, node-cron, pg
- Android Kotlin, Retrofit/OkHttp, Room, ExoPlayer

본 문서에서 `8443:443`은 유일한 외부 통신 진입점이며, 시스템 완성 여부는 Android 클라이언트의 HTTPS API 통신과 오프라인 동작으로 판단한다.
