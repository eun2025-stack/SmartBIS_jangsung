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

이 누적 로그의 앞선 설명은 정정한다(2026-10-02). `contents.source_path`는 상대경로가 아니라 Node watcher가 DB에 기록한 컨테이너 절대경로다. 실제 DB 조회에서 아래 형식을 확인했다.

```text
/app/content-store/2026-09-25/BATCH_20260925_0001/파일명.mp4
```

백엔드와 watcher는 같은 `/app/content-store` 경로를 mount/configuration으로 사용한다. 백엔드는 설정된 root 아래인지 검사한 뒤 스트리밍한다. 이 계약을 바꿀 때는 기존 DB 행과 백엔드 해석 규칙을 함께 마이그레이션해야 한다.

## 최종 API 정상 판정

```text
HTTP/1.1 200
Content-Type: video/mp4
Content-Length: 원본 파일 크기
Accept-Ranges: bytes
```

## 대시보드 로그인 리다이렉트에서 외부 포트 보존 (2026-10-02)

### 증상

브라우저에서 다음 대시보드 주소를 열면 로그인 페이지로 이동하지만 주소에서 `:8443`이 빠졌다.

```text
입력: https://localhost:8443/api/dashboard/index.html
잘못된 이동: https://localhost/api/dashboard/login.html
```

### 원인과 수정

`nginx.conf`가 `proxy_set_header Host $host;`를 사용하면 `$host`에는 `localhost`만 전달되고 클라이언트가 접속한 포트 `8443`은 전달되지 않는다. 외부에서 받은 Host 헤더 전체를 보존하고 Forwarded Host를 명시하도록 수정했다.

```nginx
proxy_set_header Host $http_host;
proxy_set_header X-Forwarded-Host $http_host;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
```

Spring Boot 설정의 `server.forward-headers-strategy=framework`와 함께 사용한다. `X-Forwarded-Port`에는 컨테이너 내부 포트 443을 외부 포트인 것처럼 지정하지 않는다. 현재 외부 포트는 Host 헤더(`localhost:8443`)로 전달한다.

### 재현 및 검증

Nginx 설정을 저장한 뒤 컨테이너를 재생성한다.

```bash
podman compose up -d --force-recreate nginx
```

2026-10-02 백엔드 보안 설정도 함께 빌드·재생성한 뒤 비로그인 대시보드 요청을 확인했다. 결과는 `302`이고 `Location` 헤더는 다음과 같이 외부 포트를 유지했다.

```text
Location: https://localhost:8443/api/dashboard/login.html
```

첫 요청은 백엔드가 재기동 중일 때 실행되어 일시적으로 `502`를 반환했다. 몇 초 후 재요청에서 위의 `302`를 확인했다. 배포 직후 실패를 최종 장애로 판단하지 말고 컨테이너 기동 로그와 health endpoint를 확인한 다음 다시 시험한다.

이 검증은 비로그인 리다이렉트와 포트 보존까지 확인한 것이다. 로그인 성공 후 브라우저의 전체 대시보드 기능, 로그아웃 버튼 및 세션 종료까지 모두 통과했다는 뜻은 아니다. 관련 백엔드 변경과 남은 검증은 [egov-backend 변경 이력](SmartBIS_egov-backend_Build_Log_v1.0_updated.md)을 참고한다.

## 백엔드 교체 뒤 stale upstream으로 발생한 502 (2026-10-02)

지역 선택 코드가 포함된 백엔드 컨테이너를 재생성한 뒤 외부 `https://localhost:8443/api/actuator/health`가 502를 반환했다. Spring Boot 로그에서는 애플리케이션 시작 완료였고, Nginx 컨테이너 내부에서 `wget http://egov-backend:8080/api/actuator/health`는 200이었다. 반면 Nginx error log는 이전 upstream `10.89.0.20:8080`에 `Host is unreachable`이라고 기록했고 새 백엔드 주소는 `10.89.0.26`이었다.

Podman 네트워크에서 컨테이너 교체로 IP가 바뀌었지만 기존 Nginx worker가 이미 해석한 주소를 계속 사용한 것이다. Nginx 설정이나 DB 문제가 아니므로 인증서·Compose 네트워크·DB를 초기화하지 않았다. Nginx만 다시 생성해 서비스 DNS를 새로 해석하도록 한 뒤 HTTPS health `UP`을 확인했다.

따라서 백엔드를 재생성한 직후 502가 나면 (1) backend 시작 로그/내부 health, (2) Nginx error log의 upstream IP, (3) 새 backend IP, (4) Nginx 내부 서비스명 요청 순으로 비교한다. IP가 다르고 내부 서비스명 요청은 200이면 Nginx 재생성 후 재시험한다. 고정 IP로 설정하지 않는다.

### 자동 DNS 재조회 설정 (2026-10-02)

백엔드 재생성 때마다 Nginx도 수동 재생성해야 하는 문제를 줄이기 위해 `http` 블록에 Podman 내장 DNS resolver `10.89.0.1 valid=10s ipv6=off`를 지정하고 `/api/` location을 변수형 `proxy_pass`로 바꿨다. Podman machine의 현재 네트워크에서 Nginx 컨테이너 `/etc/resolv.conf`의 nameserver가 `10.89.0.1`임을 확인한 값이다. Nginx 설정 테스트 성공, Nginx 단독 재생성 후 backend 서비스명 health 200 및 외부 TLS health 200을 확인했다.

이제 backend 컨테이너 IP가 바뀌어도 DNS 캐시 유효기간 뒤 새 IP를 찾아야 한다. Podman 네트워크 구성이 바뀌어 DNS gateway가 달라지면 resolver 값을 다시 확인해야 한다. 동기화 POST 403 자체의 원인은 이 Nginx DNS 수정으로 해결되었다고 간주하지 않는다. 재로그인 뒤 요청을 재시험해 Spring Security 로그와 외부 TAGO 응답을 구분한다.

## 루트 URL의 404와 외부 포트가 빠지는 리다이렉트 수정 (2026-10-02)

사용자가 Nginx 404 화면을 보고해 로그를 확인한 결과, 브라우저 요청은 `/`였다. 기존 설정은 모든 `/` 요청을 `return 404`로 처리했으며 대시보드 경로(`/api/dashboard/index.html`)로의 진입점이 없었다. 이를 대시보드 접근 오류로 오인하기 쉬웠다.

`location = /`에서 `/api/dashboard/index.html`로 302 이동시키고, Nginx의 `absolute_redirect off`를 설정해 내부 listen 포트 `443`을 외부 포트처럼 노출하지 않게 했다. 첫 설정만 적용했을 때 Nginx가 `Location: https://localhost/api/dashboard/index.html`을 만들어 8443을 잃고, curl이 localhost:443 연결 실패하는 문제도 확인해 함께 수정했다. 현재는 상대 Location `/api/dashboard/index.html`을 보내므로 브라우저가 원래의 `https://localhost:8443` origin을 유지한다. Spring Security의 로그인 이동도 `https://localhost:8443/api/dashboard/login.html`로 포트를 보존한다.

검증 결과:

- Nginx `nginx -t`: syntax OK, test successful.
- `GET https://localhost:8443/` 응답: 302, `Location: /api/dashboard/index.html`.
- 이어지는 대시보드 비로그인 응답: 302, `Location: https://localhost:8443/api/dashboard/login.html`.
- 로그인 페이지 응답: HTTP 200.
- `GET https://localhost:8443/api/actuator/health`: HTTP 200, `{"status":"UP"}`.

따라서 루트 주소 접속 시 더 이상 Nginx 기본 404 페이지가 나오지 않고 로그인 화면으로 이어진다. 존재하지 않는 다른 루트 경로는 계속 404이며, 이는 의도된 동작이다.
