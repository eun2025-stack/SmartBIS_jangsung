# SmartBIS Nginx 사설 CA 및 HTTPS 테스트 누적 로그

## 1. 목적

이 문서는 기존 Nginx 사설 인증서 구축 로그 이후 확인된 문제와 해결 방법을 정리한다. SmartBIS의 외부 진입점은 Nginx이며, Windows Podman Machine 환경에서 `localhost:8443`으로 HTTPS를 제공한다.

## 2. 최종 네트워크 구조

```text
Android Client
      │ HTTPS :8443
      ▼
Nginx :443  ── proxy_pass ──>  egov-backend :8080
                                      │
                                      └── PostgreSQL :5432
```

호스트 포트와 컨테이너 포트의 관계:

```text
Windows localhost:8443 → Nginx container:443
Nginx container → egov-backend:8080
egov-backend → postgres-db:5432
```

Spring Boot와 PostgreSQL은 외부에 직접 공개하지 않고 Podman 네트워크 내부에서만 통신한다.

## 3. 사설 인증서

서버 인증서에는 다음 SAN이 포함되어야 한다.

```text
DNS:localhost
IP:127.0.0.1
IP:10.0.2.2
```

인증서 확인:

```bash
openssl x509 -in server/nginx/certs/server.crt -noout -subject -issuer -dates
openssl x509 -in server/nginx/certs/server.crt -noout -text | \
grep -A2 "Subject Alternative Name"
```

운영 또는 Android 단말에서는 `rootCA.crt`를 신뢰할 수 있는 CA로 설치해야 한다. `server.key`와 `rootCA.key`는 외부에 배포하지 않는다.

## 4. Windows Podman 포트 문제

초기 Rootful 설정에서는 컨테이너 내부 Nginx가 정상 동작해도 Windows 호스트의 `localhost:8443` 연결이 거부될 수 있었다. `podman port`에 매핑이 표시되더라도 Windows 호스트 전달이 실제로 동작하지 않는 경우가 있었다.

해결한 설정:

```bash
podman machine stop
podman machine set --user-mode-networking=true podman-machine-default
podman machine set --rootful=false podman-machine-default
podman machine start
```

확인:

```bash
podman machine inspect | grep -i UserModeNetworking
```

정상 결과:

```text
"UserModeNetworking": true
```

그 후 Compose 서비스를 재생성한다.

```bash
podman compose up -d
podman port smartbis_jangsung-nginx-1
```

## 5. 테스트 결과

호스트에서 다음 테스트가 성공했다.

```bash
curl -vk https://localhost:8443/api/actuator/health
```

응답:

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" }
  }
}
```

Windows curl의 Schannel은 사설 인증서의 폐기 상태를 확인하지 못해 다음 오류를 표시할 수 있다.

```text
schannel: revocation status is unknown
```

개발 환경 검증에서는 다음처럼 폐기 상태 확인만 끈다.

```bash
curl --cacert server/nginx/certs/rootCA.crt \
  --ssl-no-revoke \
  https://localhost:8443/api/actuator/health
```

`-k`는 인증서 검증 자체를 끄는 옵션이므로 단순 연결 확인용으로만 사용한다. CA 검증 테스트에는 `--cacert` 방식을 사용한다.

## 6. 대용량 미디어 프록시 설정

동영상·이미지 응답을 Spring Boot에서 스트리밍하기 위해 Nginx `/api/` location에 다음을 적용한다.

```nginx
proxy_buffering off;
proxy_request_buffering off;
proxy_read_timeout 300s;
```

이 설정은 Nginx가 응답 전체를 임시 버퍼에 모으지 않고 백엔드 응답을 클라이언트로 전달하도록 한다.

## 7. 장애 진단 순서

```bash
podman compose ps -a
podman compose logs --tail=100 nginx
podman exec smartbis_jangsung-nginx-1 nginx -t
podman port smartbis_jangsung-nginx-1
curl -vk https://localhost:8443/api/actuator/health
```

컨테이너 내부에서는 성공하지만 호스트에서 실패하면 Nginx 설정이 아니라 Podman Machine의 포트 전달 상태를 먼저 확인한다.

## 8. 결론

현재 외부 인터페이스는 `https://localhost:8443`을 기준으로 정상화되었다. Android 클라이언트는 운영 시 IP 주소 대신 인증서 SAN과 일치하는 접속 이름을 사용해야 하며, 사설 Root CA 신뢰 설정이 필요하다.
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

