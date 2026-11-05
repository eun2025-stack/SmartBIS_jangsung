# SmartBIS Node Watcher 구축 및 변경 이력

## 1. 문서 목적

이 문서는 `SmartBIS_Node_Watcher_Build_Log_v1.0.md` 이후 적용된 변경사항을 누적 정리한 문서다. 다음 구축 시 단일 JSON 처리 방식과 배치 단위 처리 방식의 차이를 명확히 이해하고, 전송 완료 판정·다중 콘텐츠·미디어 파일 보관·DB 저장·재처리 절차를 반복해서 설명하지 않도록 하는 것이 목적이다.

## 2. 최종 처리 구조

외부 시스템은 폐쇄망에서 오픈망 방향으로 파일을 전송한다. 전송 중인 파일을 Node watcher가 즉시 처리하면 JSON 또는 동영상이 아직 완성되지 않은 상태일 수 있으므로, 파일의 생성 이벤트만으로 처리하지 않는다.

최종 구조는 다음과 같다.

```text
upload-store/YYYY-MM-DD/BATCH_ID/
├── contents.json
├── 동영상.mp4
├── 이미지.jpg
└── .ready
        │
        ├─ polling으로 폴더와 파일 확인
        ├─ .ready가 있어야 접수 완료
        ├─ 매일 04:00 Asia/Seoul에 일괄 처리
        ├─ JSON의 contents 배열을 content_id별로 검증
        ├─ 미디어 파일을 content-store/YYYY-MM-DD/BATCH_ID로 이동
        ├─ PostgreSQL에 콘텐츠별 1행 UPSERT
        └─ 성공 시 upload-store/archive로 배치 이동
```

## 3. 단일 JSON 방식에서 배치 방식으로 변경한 이유

기존에는 `upload-store`에 `contents.json` 하나가 생성되면 즉시 처리했다. 이 방식은 다음 문제가 있다.

- 동영상과 이미지가 아직 복사 중이어도 JSON만 먼저 감지할 수 있다.
- 동일한 파일명이 다시 전송되면 이전 파일을 덮어쓸 수 있다.
- JSON 하나에 여러 콘텐츠를 담을 수 없다.
- 처리 완료 파일과 미처리 파일의 구분이 어렵다.
- 폴링 간격이 길면 짧은 파일 이벤트를 놓칠 수 있다.

따라서 파일 단위가 아니라 `BATCH_ID` 폴더 단위로 수신을 확정한다. 전송 업체는 모든 파일 복사가 끝난 후 마지막 단계에서 빈 `.ready` 파일을 생성해야 한다.

## 4. 권장 수신 규칙

```text
upload-store/
└── 2026-09-25/
    └── BATCH_20260925_0001/
        ├── contents.json
        ├── 장성 관광 홍보 영상.mp4
        ├── 장성 행사 홍보 이미지.jpg
        └── .ready
```

`.ready`는 반드시 모든 파일 전송이 끝난 뒤 생성한다. `.ready`가 먼저 생성되면 watcher는 안정성 검사를 수행하더라도 전송 업체의 완료 의도를 신뢰할 수 없으므로, 전송 순서를 협의해야 한다.

## 5. JSON 형식

JSON 하나에 여러 콘텐츠를 넣을 수 있다.

```json
{
  "batch_id": "BATCH_20260925_0001",
  "contents": [
    {
      "content_id": "CNT_BATCH_0001",
      "content_type": "VIDEO",
      "title": "장성 관광 홍보 영상",
      "content": "장성 관광 홍보 영상입니다.",
      "target_file_name": "장성 관광 홍보 영상.mp4",
      "display_start_date": "2026-09-25",
      "display_end_date": "2026-10-01",
      "target_regions": ["장성읍"],
      "template": "행사"
    },
    {
      "content_id": "CNT_BATCH_0002",
      "content_type": "IMAGE",
      "title": "장성 행사 홍보 이미지",
      "content": "장성 행사 안내 이미지입니다.",
      "target_file_name": "장성 행사 홍보 이미지.jpg",
      "display_start_date": null,
      "display_end_date": null,
      "target_regions": null,
      "template": "공지"
    }
  ]
}
```

`content_id`는 콘텐츠별 고유값이다. `batch_id`는 한 번에 수신된 묶음의 고유값이다. 기간과 지역 제한이 없으면 해당 값은 `null`로 보낸다. JSON 주석(`//`)은 표준 JSON이 아니므로 실제 전송 파일에는 넣지 않는다.

## 6. PostgreSQL 저장 방식

JSON의 `contents` 배열 항목마다 DB에 한 행을 저장한다.

```text
content_id       batch_id              content_type
CNT_BATCH_0001   BATCH_20260925_0001   VIDEO
CNT_BATCH_0002   BATCH_20260925_0001   IMAGE
```

동일한 `content_id`가 다시 수신되면 새 행을 만들지 않고 UPSERT한다. 따라서 콘텐츠 수정 전송은 동일 `content_id`를 유지하고, 신규 콘텐츠만 새로운 `content_id`를 사용해야 한다.

현재 `source_path`는 `content-store` 기준 상대경로가 아니라 컨테이너 절대경로를 저장한다. 실제 DB 조회에서 `/app/content-store/YYYY-MM-DD/BATCH_ID/filename` 형태를 확인했다. (2026-10-02 정정)

```text
/app/content-store/2026-09-25/BATCH_20260925_0001/장성 관광 홍보 영상.mp4
```

따라서 모든 실행 환경에서 container mount path `/app/content-store`를 동일하게 유지해야 한다. 경로 규약을 변경한다면 기존 DB 값도 함께 이관해야 한다.

## 7. 미디어 파일 보관

성공한 배치의 미디어는 다음 위치로 이동한다.

```text
content-store/YYYY-MM-DD/BATCH_ID/파일명
```

예:

```text
content-store/2026-09-25/BATCH_20260925_0001/장성 관광 홍보 영상.mp4
content-store/2026-09-25/BATCH_20260925_0001/장성 행사 홍보 이미지.jpg
```

초기 설계 설명은 content-store 상대경로 저장을 제안했으나 현재 코드/DB 동작과 다르다. 실제 값은 `/app/content-store/...` 컨테이너 절대경로이며, Spring Boot에는 같은 root가 읽기 전용으로 연결되고 Node watcher만 파일을 쓴다. 자세한 정정은 이 문서 말미를 기준으로 한다.

## 8. 전송 완료 판정과 폴링

Windows의 Podman bind mount에서는 파일 변경 이벤트가 안정적으로 전달되지 않을 수 있어 `chokidar`를 polling 모드로 설정했다.

```js
usePolling: true,
interval: 500,
awaitWriteFinish: {
  stabilityThreshold: 2000,
  pollInterval: 100
}
```

다만 polling은 전송 완료를 보장하는 기능이 아니다. 따라서 최종 판정은 다음 순서로 한다.

1. 배치 폴더가 존재하는지 확인한다.
2. `contents.json`과 `.ready`가 존재하는지 확인한다.
3. JSON에 선언된 미디어 파일이 모두 존재하는지 확인한다.
4. 파일 크기가 연속 검사에서 변하지 않는지 확인한다.
5. 04:00 일괄 처리 시 검증·DB 저장·미디어 이동을 수행한다.

파일이 04:00 직전에 도착하면 해당 배치는 다음 처리 주기로 넘어갈 수 있다. 이것은 유실이 아니라 안전한 지연 처리다. 관리 업체와 “당일 처리 대상은 04:00 이전에 `.ready` 생성 완료” 규칙을 협의해야 한다.

## 9. 04:00 일괄 처리

시내버스가 04:00 이후 운행을 시작하고 Android 클라이언트가 운행 시작 시 다운로드하므로, Node watcher는 매일 Asia/Seoul 기준 04:00에 배치를 처리하도록 구성했다.

운영 컨테이너는 계속 실행되며 다음 작업을 수행한다.

- 감시 및 수신 상태 확인
- 04:00 cron 실행
- READY 배치 검색
- 안정성 검사
- JSON 다중 콘텐츠 검증
- PostgreSQL 트랜잭션 저장
- 미디어 이동
- 성공 배치 archive 이동

개발 테스트에서는 다음 환경변수로 즉시 처리한다.

```bash
podman compose run --rm \
  -e RUN_PROCESS_NOW=true \
  -e STABILITY_CHECK_INTERVAL_MS=1000 \
  -e STABILITY_CHECK_COUNT=1 \
  node-watcher
```

즉시 처리 테스트 컨테이너도 watcher와 동일하게 감시를 시작하므로, 처리 후 종료되지 않는 것이 정상이다. 테스트가 끝나면 `Ctrl+C`로 종료한다.

## 10. 성공·실패 보관

성공한 배치는 다음으로 이동한다.

```text
upload-store/archive/<timestamp>_BATCH_ID/
```

검증 실패 또는 DB 오류 배치는 다음으로 이동한다.

```text
upload-store/error/<timestamp>_BATCH_ID/
```

원본을 즉시 삭제하지 않는 이유는 재처리와 장애 원인 분석을 위해서다. archive와 error 보관 기간은 운영 정책으로 별도 정해야 한다.

## 11. 해시 및 대용량 파일 메모리 처리

동영상은 `fs.readFile()`로 전체를 메모리에 올리지 않고 `createReadStream()` 기반으로 SHA-256을 계산해야 한다. 현재 Node watcher는 이 방식으로 안정성 검사와 파일 검증을 수행한다.

반면 Android 클라이언트와 Spring Boot 미디어 API도 전체 파일을 메모리에 적재하면 안 된다. Spring Boot는 `Resource` 응답으로 스트리밍하고, Nginx는 다음 설정을 사용한다.

```nginx
proxy_buffering off;
proxy_request_buffering off;
proxy_read_timeout 300s;
```

현재 구현 범위는 파일 다운로드·스트리밍이다. Android에서 동영상 파일을 직접 재생하고 HTTP 재개 재생이 필요해지면 이후 `Range` 요청을 추가한다.

## 12. 실제 검증 결과

다중 콘텐츠 배치 처리 결과:

```text
[database] 배치 저장 성공: BATCH_20260925_0001 (2건)
[archive] 배치 이동 완료
```

DB 확인 결과:

```text
CNT_BATCH_0001 | VIDEO | 장성 관광 홍보 영상   | 장성 관광 홍보 영상.mp4
CNT_BATCH_0002 | IMAGE | 장성 행사 홍보 이미지 | 장성 행사 홍보 이미지.jpg
```

기간·지역 제한이 없는 콘텐츠는 다음과 같이 저장된다.

```text
display_start_date | null
display_end_date   | null
target_regions     | null
```

## 13. 다음 구축 시 체크리스트

- [ ] 배치 폴더를 날짜와 `BATCH_ID`로 생성했는가
- [ ] `contents.json`의 `contents` 배열에 모든 콘텐츠를 넣었는가
- [ ] 콘텐츠마다 고유 `content_id`가 있는가
- [ ] 동영상·이미지 파일명이 JSON의 `target_file_name`과 일치하는가
- [ ] 모든 파일 전송 후 마지막에 `.ready`를 생성했는가
- [ ] 실제 JSON에 `//` 주석을 넣지 않았는가
- [ ] 04:00 처리 규칙을 전송 업체와 협의했는가
- [ ] 동일 콘텐츠 수정 시 `content_id`를 유지했는가
- [ ] 신규 콘텐츠에 새로운 `content_id`를 부여했는가
- [ ] 성공 후 `content-store/YYYY-MM-DD/BATCH_ID`에 파일이 있는가
- [ ] DB의 `source_path`가 `/app/content-store/...` 형태의 컨테이너 절대경로이고, 백엔드 mount 경로와 일치하는가
- [ ] 실패 배치가 `upload-store/error`에 보존되는가
- [ ] Spring Boot가 `content-store`를 읽기 전용으로 연결했는가
- [ ] `/api/v1/media/{content_id}` 다운로드 API가 동작하는가

## 14. 운영상 결론

현재 Node watcher의 핵심 책임은 “파일 전송을 받는 것”이 아니라 “완료가 명시된 배치를 검증하고, 콘텐츠별 DB 행과 미디어 보관 구조를 원자적으로 확정하는 것”이다.

따라서 파일 전송 시스템과 다음 계약을 유지해야 한다.

```text
파일 전체 전송
    → contents.json 전송
    → 모든 파일 검증
    → 마지막에 .ready 생성
    → 다음 04:00 처리
```

이 규칙을 지키면 polling 간격 때문에 파일을 놓치는 문제를 줄이고, 동일 파일명 덮어쓰기와 전송 중인 대용량 파일의 조기 처리를 방지할 수 있다.
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

이 문서의 이전 상대경로 설명은 잘못되었으므로 정정한다. Node watcher가 성공적으로 이동한 파일 경로를 DB에 컨테이너 절대경로로 기록하며, 아래와 같은 실제 쿼리 결과가 확인되었다.

```text
/app/content-store/2026-09-25/BATCH_20260925_0001/파일명.mp4
```

컨테이너 간 경로 계약은 `CONTENT_ROOT=/app/content-store`를 동일하게 유지하는 것이다. 호스트 절대경로를 DB에 기록하는 것이 아니다.

## 운영상 오류기록 연동 미완료 (2026-10-02)

watcher가 검증 또는 배치 처리에 실패하면 오류를 로그에 출력하고 배치 폴더를 `upload-store/error`로 옮긴다. 현재 `src/index.js`에는 `content_ingest_batches`나 `content_validation_errors`에 실패 정보를 INSERT하는 코드가 없다. 해당 테이블과 관리 API/UI가 존재하더라도 watcher 실패가 자동으로 대시보드에 보이지 않는 이유다. 운영 전 오류 테이블 저장(원본 배치/오류 단계·메시지/발생시각/처리상태), 저장 실패 시 원본 보존, 재처리 및 중복 기록 정책을 구현하고 end-to-end로 확인해야 한다.

또한 미디어 파일 복사와 PostgreSQL transaction은 분산된 두 자원이라 SQL rollback이 이미 복사한 파일을 자동 삭제하지 않는다. 배치 도중 실패할 때 고아 파일이 남을 수 있으므로 실패 격리/정리 정책은 운영 안정화 항목이다.

## 최종 API 정상 판정

```text
HTTP/1.1 200
Content-Type: video/mp4
Content-Length: 원본 파일 크기
Accept-Ranges: bytes
```

## 실패 배치 DB 기록과 재처리 해소 (2026-10-02)

### 변경 목적

이전 구현은 실패 내용을 컨테이너 로그와 `upload-store/error` 폴더에만 남겼다. 대시보드에는 `content_ingest_batches` 및 `content_validation_errors` 조회 API가 이미 있었으나 watcher의 INSERT가 없어 자동 수집 오류가 목록에 나오지 않았다.

### 처리 동작

`src/ingest-history.js`를 추가하고 `index.js`의 배치 처리 흐름에서 사용한다.

1. 검증/적재 실패 시 원본 배치 이동을 먼저 시도해 `upload-store/error`에 보존한다.
2. DB transaction 하나에서 `content_ingest_batches`를 `FAILED`로 UPSERT하고 `retry_count`, `last_error`, 보존 경로를 갱신한다.
3. 같은 transaction에서 `content_validation_errors`에 오류 코드·메시지·경로 등 상세 JSON을 `OPEN`으로 추가한다. 오류 테이블 INSERT 실패 시 batch 행만 남지 않도록 transaction 전체를 rollback한다.
4. 실패 기록을 DB에 쓸 수 없으면 파일 보존 상태와 기록 실패를 로그에 남긴다. PostgreSQL 자체가 중단된 경우 DB 기록을 강제로 보장할 수 없으며, error 폴더가 fallback 증거다.
5. 원본을 같은 batch ID로 인입 경로에 복구해 재처리하고 성공하면 batch 상태를 `PROCESSED`로 변경하고 기존 미해결 오류를 `RESOLVED`로 갱신한다.
6. 콘텐츠·파일 처리는 기존과 같이 먼저 수행한다. 따라서 DB 처리 이력 쓰기 실패가 콘텐츠 적재 성공을 실패로 되돌리지는 않도록 분리했다.

오류 코드는 JSON 문법 오류, 입력 파일 누락, DB 연결 오류, DB 제약 오류, 일반 배치 오류를 구분한다. 개별 콘텐츠 ID를 확인할 수 없는 manifest/파일 오류는 error 행의 `content_id`를 null로 두고 batch 단위로 조회한다.

### 시험

Node 내장 `node:test`로 오류 분류, batch+error 원자 기록, 기록 실패 rollback/connection release, 재처리 성공 시 미해결 오류 해소를 시험했다. 총 4개 시험이 통과했고 `index.js`, helper, test 파일의 `node --check`도 통과했다.

2026-10-02 실제 PostgreSQL을 사용하는 통합 시험을 완료했다. 먼저 의도적으로 잘못된 JSON 배치를 투입해 `content_ingest_batches`의 `FAILED`/`retry_count=1`, `content_validation_errors`의 `INVALID_JSON`/`OPEN` 기록을 확인했다. 같은 `batch_id`의 JSON을 수정해 재투입하자 배치는 `PROCESSED`, 기존 오류는 `RESOLVED`로 바뀌었고 콘텐츠 행도 저장됐다. 시험용 DB 행과 `upload-store/error`, `upload-store/archive`의 시험 배치는 모두 제거했으며 확인 결과 시험 배치 행은 남아 있지 않다. 실행 컨테이너는 계속 기동 중이다.

이후 2026-10-02 관리자 계정으로 로그인하여 대시보드 HTML, 오류 요약 API, 오류 목록 API에 인증 세션으로 접근했고 모두 HTTP 200을 확인했다. 조회 건수는 0건으로, 앞선 통합시험 데이터를 정리한 결과와 일치한다. 따라서 인증 및 API 연결은 확인됐으나, 실제 오류 행이 브라우저 표에 렌더링되는 것까지는 아직 확인하지 않았다. DB 중단 시 파일/log fallback, 미디어 orphan 정리 및 장시간 운영도 추가 시험 대상이다. Node 내장 시험 4개, 소스/시험 파일의 `node --check`, 컨테이너 이미지 빌드와 watcher 재생성도 통과했다.

Podman 실행 환경 참고: 이 Windows 호스트에는 Podman 6.0.2가 설치되어 있고 WSL 머신도 실행 중이며, 설치 경로는 `C:\Users\eun\AppData\Local\Programs\Podman\podman.exe`다. 기본 제한 실행에서는 Windows가 프로세스 시작을 `액세스 거부`로 막았지만 승인된 권한 실행에서는 버전·머신 조회, 이미지 빌드, watcher 재생성, PostgreSQL 통합시험이 정상 동작했다. 따라서 PATH를 추가 변경할 문제가 아니며, Codex가 Podman을 실행해야 할 때 권한 승인 창이 나오면 해당 작업을 승인해야 한다. 승인/실행 허용은 현재 앱의 전역 샌드박스 정책 자체를 영구 변경했다는 뜻은 아니다.

### 2026-10-04 콘텐츠 데이터 계약 v2 및 다중 첨부 구현

#### 입력 계약 및 검증

새 `src/content-contract.js`에 v1 호환 어댑터와 v2 검증기를 분리했다. v2는 `schema_version=2.0`, 폴더명과 일치하는 `batch_id`, 1개 이상 콘텐츠를 요구한다. 업무 category(공지/행사)와 presentation type(포스터+본문/텍스트/카드뉴스/이미지/영상)을 구분하고, 첨부 역할·파일명·순번 및 형식별 첨부 수를 검사한다. media-only 콘텐츠는 빈 본문을 허용하지만 텍스트/포스터 본문은 필수다. 첨부 파일은 경로 traversal을 거부하고 파일 안정화 후 watcher가 크기·SHA-256·MIME을 산출한다.

검증된 자산은 `server/content-store/YYYY-MM-DD/BATCH_ID`에 복사한다. DB transaction 안에서 기존 `contents` UPSERT, 기존 자산행 교체 및 `content_assets` 저장을 수행한다. legacy 컬럼에는 종전 소비자와 정책이 작동하도록 단일 기본 파일과 `VIDEO`/`IMAGE`/`CARD` 매핑을 유지한다. 파일시스템 복사와 DB commit은 하나의 원자 transaction이 아니므로 DB 실패 시 orphan 파일 가능성은 남는다.

#### 실제 검증 결과

- `node --check src/index.js`, `node --test`: 11개 통과.
- `podman compose build node-watcher`: 성공. 새 이미지로 장기 실행 watcher 재생성 완료.
- `BATCH_V2_CONTRACT_20261004` 실제 한 배치에 POSTER_TEXT, TEXT, 2-image CARD_NEWS, IMAGE, VIDEO 총 5건을 넣고 `.ready`를 마지막에 생성했다. 모든 콘텐츠 저장 후 `upload-store/archive`로 이동했다.
- PostgreSQL에서 5개의 category/presentation type, 자산 수(1/0/2/1/1), 카드뉴스 sequence 1·2 및 신규 파일의 크기/SHA-256 확인.
- Backend 목록 API가 `assets`와 개별 media URL을 반환했고 CARD_NEWS sequence 2 다운로드는 HTTP 200, `image/jpeg`, 6,436,410 bytes.
- 장기 watcher 상태 API는 `status=UP`, `database=UP`, `watcher=RUNNING`, `processing=false`.
- 시험 레코드에 예약 schedule을 만들지 않아 현재 Android 차량 순환 후보로 활성화하지 않았다. 카드 이미지 두 장은 동일 JPEG 복사본이어서 파일 전달·순서 검증은 됐지만 실제 디자인 전환의 시각 검수는 아니다.

DB migration은 `server/database/migrations/V010__content_data_contract_v2.sql`에서 별도 적용했다. 이전 8개 콘텐츠 행은 유지했다. content root 밖의 예전 upload-store 경로는 `content_assets`로 오인 이관하지 않았다. 해당 legacy 경로는 원본 데이터 조사 후 별도 정리한다.

#### 같은 날 축제 콘텐츠 배치 적재 (2026-10-04)

실제 수신 배치 `BATCH_FESTIVAL_20261004_001`은 POSTER_TEXT 5건, VIDEO 5건, CARD_NEWS 2건(각각 4장/3장)으로 구성됐다. 카드뉴스 원본 폴더의 이미지들은 배치 내 basename으로 복사되고 sequence 값으로 원래 순서를 표현했다. watcher가 12개 콘텐츠 및 총 20개 첨부를 검사·해시 계산·복사하고 DB에 저장한 다음 archive로 이동했다. 이후 운영자가 기존 콘텐츠 13건을 모두 교체하기로 하여 그 행과 활성 스케줄을 제거하고 새 12건에 각각 10초 활성 schedule을 설정했다. 원본 upload-store와 content-store 파일은 보존했다.
