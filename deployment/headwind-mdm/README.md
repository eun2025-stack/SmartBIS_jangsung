# SmartBIS용 Headwind MDM 별도 구성

단말의 최초 등록·Device Owner 설정·Agent 연결·SmartBIS 자동 배포 및 지자체 인수인계 절차는 [기기 등록·운영 절차서](DEVICE_PROVISIONING_RUNBOOK.md)를 참조한다. 2026-10-11 현재 개발용 커스텀 Agent 6.39와 SmartBIS Client APK를 Android 11 시험기 한 대에 설치했고 Device Owner 설정 및 Client 실행까지 확인했다. 다만 해당 기기의 Wi-Fi 대역에서 Headwind/SmartBIS 서버 사설 Ethernet IP로 라우팅되지 않아 서버 체크인, 원격 설치·콘텐츠 표출 시험은 남아 있다. 두 APK는 debug 서명 시험본이며 운영 서명 빌드가 아니다. 상세 결과와 다음 네트워크 검증 단계는 [기기 등록·운영 절차서](DEVICE_PROVISIONING_RUNBOOK.md)에 기록했다. 빌드 설정·제약은 [커스텀 Agent 빌드 기록](CUSTOM_AGENT_BUILD.md)에 있다.

개발용 APK는 Git Bash 프로젝트 루트에서 각각 빌드한다. 빌드 스크립트는 기존 APK를 보존하고 새 산출물의 SHA-256을 출력한다.

두 빌드 모두 JDK 17로 표준화했다. 현재 PC의 Java 25는 Headwind의 Gradle 8.13과 호환되지 않아 `Unsupported class file major version 69`로 실패한다. [Gradle Java 호환표](https://docs.gradle.org/current/userguide/compatibility.html)에서 Java 25 실행 지원은 Gradle 9.1부터다. JDK 17 설치 후 Git Bash를 다시 열고 `java -version`을 확인한다.

```bash
bash deployment/headwind-mdm/build-custom-agent.sh https://192.168.100.50:8443
bash client-android/build-smartbis-apk.sh https://192.168.100.50:8443/api 2 1.1
```

이 빌드는 디버그 서명 시험본이다. 기존 Agent를 새 서명으로 덮어쓰지 말고, 실기기에서는 우선 SmartBIS 앱만 한 대에 배포한다. Agent 교체는 기기 초기화/재등록 복구가 가능한 시험 단말로 한정한다.

이 구성은 현재 SmartBIS와 **같은 클라우드 홈페이지 서버·같은 호스트**에서 실행하되, 별도 Compose 프로젝트, PostgreSQL, 데이터 디렉터리를 사용하는 Headwind MDM 스택입니다. 별도 VM이나 새로운 웹 포트를 추가하지 않습니다. 현장에 설치할 Android BIT에 앱을 사전 배포하고 이후 원격 설치·업데이트하는 것이 목적입니다.

## 연결 구조와 포트

```text
BIT / 관리자 브라우저
  ├─ https://<서버 IP>:8443/* ───────────────────────────> 기존 SmartBIS Nginx ─> Headwind HTTPS:8443
  ├─ https://<서버 IP>:8443/api/* ───────────────────────> 기존 SmartBIS Nginx ─> SmartBIS API
  ├─ TCP 31000 (MQTT 푸시) ───────────────────────────────> Headwind:31000
```

- 기존 호스트 TCP `8443`은 계속 SmartBIS Nginx가 소유합니다. Headwind 컨테이너에 `8443`을 직접 공개하지 않습니다.
- 새로 네트워크/방화벽에 요청할 포트는 **TCP 31000 한 개**입니다. 이 포트는 Headwind의 MQTT 푸시 전달용입니다. HTTPS와 APK 다운로드는 기존 `8443`을 통해 전달합니다. Headwind의 기본 포트 역할은 [공식 방화벽 안내](https://qa.h-mdm.com/1824/install-headwind-mdm-behind-a-firewall)에 설명되어 있습니다.
- 개발 환경은 DNS 없이 현재 PC의 사설 IP(`192.168.100.50`)로 접속합니다. IP 접속을 위해 인증서의 IP SAN에 해당 주소가 있어야 하며, 이 구성은 기존 SmartBIS Root CA로 발급합니다. 운영 이전 때는 실제 운영 서버 IP로 인증서를 다시 발급하고 MDM 설정을 갱신합니다.
- TLS는 외부 구간에서 기존 Nginx가 종료하고, 내부 Nginx→Headwind 구간은 Headwind의 HTTPS `8443`을 사용합니다. Nginx는 기존 `rootCA.crt`로 내부 인증서를 검증하도록 템플릿을 준비했습니다.
- Nginx MDM 가상호스트는 인증서 발급 및 설정 검사가 끝날 때까지 **비활성**입니다. IP 주소로 들어오는 연결은 기본 가상호스트가 받아 `/api/`만 SmartBIS로 보내고 나머지는 Headwind로 전달합니다.

## 파일 구성

- `compose.yaml`: MDM 전용 DB 및 Headwind 서비스. SmartBIS의 기존 Podman 네트워크에는 Headwind만 연결합니다.
- `.env.example`: MDM DB 비밀번호, 호스트명, Headwind 공유 비밀값의 예시.
- `nginx/mdm.conf.template`: MDM DNS 이름으로 들어온 요청을 Headwind 내부 HTTPS로 전달하는 비활성 Nginx 설정.
- `certs/`: 배포 때 발급받은 MDM leaf 인증서와 개인키를 둘 위치. 비밀 인증서 파일은 Git에 포함되지 않습니다.
- Podman named volume `hmdm-db-data`: MDM 전용 PostgreSQL 데이터입니다. Windows bind mount의 권한 문제를 피하기 위해 DB만 Podman 볼륨을 사용합니다.
- `volumes/work`, `volumes/hmdm-config`, `volumes/webapps`: Headwind 앱/설정 영속 데이터입니다. SmartBIS DB 및 `content-store`와 별개입니다.

## 실제 기동 전 준비

1. 클라우드 서버의 자원 한도 내에서 MDM 전용 데이터 공간을 확보하고 TCP `31000` 인바운드 허용을 요청합니다. `8443`은 기존과 동일하게 사용합니다.
2. 개발 IP는 Android 단말에서 실제로 도달 가능한 호스트 IP를 `.env`의 `MDM_HOST`로 사용합니다. 유선과 Wi-Fi가 다른 서브넷이면 단말이 접근하는 인터페이스의 IP를 선택해야 합니다. 설정 스크립트는 기존 사설 Root CA로 해당 IP SAN 인증서를 발급하고, `base.url`과 Agent가 접속할 `mqtt.server.uri=<MDM_HOST>:31000`을 함께 갱신한 뒤 Nginx와 Headwind를 재생성합니다. `0.0.0.0`은 서버 bind 주소로만 쓰며 Agent에 광고하는 MQTT 주소로 사용하면 안 됩니다. Git Bash에서 프로젝트 루트 또는 어느 폴더에서든 다음을 실행합니다.

   ```bash
   cd /d/PROJECT/SmartBIS_jangsung
   bash deployment/headwind-mdm/configure-dev-ip.sh
   ```

   필요하면 IP를 인자로 전달할 수 있습니다. 인증서 개인키와 비밀값은 출력되지 않습니다.

   ```bash
   bash deployment/headwind-mdm/configure-dev-ip.sh 192.168.100.50
   ```

   이 스크립트는 이미 초기화된 MDM DB의 계정 비밀번호도 새 값으로 변경합니다. DB 볼륨은 삭제하지 않습니다. 인증서 검증 또는 Nginx 설정 검사에 실패하면 Nginx를 재생성하지 않습니다.

3. 인증서 파일은 아래 경로에 생성됩니다. Root CA 개인키는 이 폴더로 복사하지 않습니다.

   ```text
   deployment/headwind-mdm/certs/cert.pem
   deployment/headwind-mdm/certs/fullchain.pem
   deployment/headwind-mdm/certs/privkey.pem
   ```

   `cert.pem` 및 `fullchain.pem`은 이 구성에서 Root CA가 직접 서명한 leaf 인증서이며, `privkey.pem`은 이에 대응하는 개인키입니다. Nginx와 단말은 기존 `server/nginx/certs/rootCA.crt`를 신뢰해야 합니다.

4. MDM 전용 `.env`를 만들고 아직 없다면 예시 파일을 복사해 기본값을 준비합니다.

   ```bash
   cd /d/PROJECT/SmartBIS_jangsung/deployment/headwind-mdm
   test -f .env || cp .env.example .env
   chmod 600 .env
   ```

   `.env`의 `MDM_HOST`와 `ADMIN_EMAIL`을 확인합니다. `configure-dev-ip.sh`가 `SQL_PASS`와 `SHARED_SECRET`을 새 임의 값으로 설정합니다. `SMARTBIS_NETWORK`은 기본 프로젝트 네트워크명과 일치해야 합니다. `.env`를 공유하거나 커밋하지 마세요.

5. 기존 SmartBIS 스택을 먼저 올려 외부 네트워크를 만듭니다. 이후 MDM 스택만 별도 폴더에서 기동합니다.

   ```bash
   cd /d/PROJECT/SmartBIS_jangsung
   podman compose up -d
   cd deployment/headwind-mdm
   podman compose config
   podman compose up -d postgresql hmdm
   podman compose ps
   podman compose logs --tail=100 hmdm
   ```

6. 위 설정 스크립트가 Nginx 설정 검사를 통과한 경우에만 Nginx의 MDM 주소 경로를 활성화하고 재생성합니다. 수동 복구가 필요한 경우 프로젝트 `compose.yaml`의 Nginx 볼륨은 다음 대상 경로를 사용합니다.

   ```yaml
   ./deployment/headwind-mdm/nginx/mdm.conf.template:/etc/nginx/conf.d/headwind-mdm.conf:ro
   ```

   프로젝트 루트에서 Nginx 설정을 먼저 검사한 뒤 재생성합니다.

   ```bash
   cd /d/PROJECT/SmartBIS_jangsung
   podman compose run --rm --no-deps nginx nginx -t
   podman compose up -d --force-recreate nginx
   ```

   인증서 파일이 없거나 인증서 SAN/CA가 맞지 않으면 검사가 실패합니다. 이때 기존 Nginx를 재생성하지 말고 인증서부터 바로잡습니다. MDM IP 가상호스트는 `nginx.conf`가 포함하는 `/etc/nginx/conf.d/*.conf`에 적용됩니다.

7. 같은 LAN의 관리자 PC에서 `https://<MDM_HOST>:8443`에 접속해 Headwind 화면과 에이전트 다운로드를 확인하고, SmartBIS API는 같은 IP의 `/api/` 경로로 확인합니다. BIT에서 MQTT TCP `31000` 연결도 별도로 확인합니다. 현재 개발용 사설 IP는 같은 LAN 또는 이를 라우팅하는 VPN에서만 접근 가능합니다.

## 버스 설치 전에 반드시 할 단말 등록

서버가 올라왔다고 순정 Android가 스스로 MDM 서버를 찾아 등록되는 것은 아닙니다. 무인 앱 설치·업데이트를 하려면 BIT가 먼저 Headwind에 Device Owner(완전 관리형 기기)로 등록되어야 합니다. 일반 앱처럼 에이전트를 설치하는 것만으로는 무인 설치 권한이 생기지 않습니다.

따라서 실제 버스에 장착하기 전에 예비 장비 또는 설치 업체 작업 단계에서 다음을 수행합니다.

1. 해당 Android 11/RK3566 기기의 초기 프로비저닝/초기화 절차와 QR 등록 지원 여부를 확인합니다.
2. Headwind에 장비를 Device Owner로 등록하고 SmartBIS 앱을 필수 앱 정책으로 지정합니다.
3. Wi-Fi/이더넷 재연결 및 재부팅 후 앱 자동 설치·업데이트·자동 실행을 시험합니다.
4. 성공한 설정을 실제 차량 장비에 적용하고 버스에 장착합니다.

이미 초기 설정을 끝낸 장비는 모델/펌웨어에 따라 초기화가 필요할 수 있습니다. Headwind의 등록 안내도 초기 프로비저닝 절차를 전제로 합니다: [Headwind 빠른 시작](https://dev.h-mdm.com/quick-start/). 이 등록을 생략한 채 “버스에서 처음 전원을 켜면 자동 설치”를 보장할 수는 없으므로, 설치업체와 초기 등록 책임을 먼저 정해야 합니다.

## 운영 서버로 이미지/데이터 이관

이 프로젝트는 개발·검증 후 컨테이너 이미지 스냅샷을 운영 호스트로 옮기는 구조입니다. Headwind는 SmartBIS와 별도 앱이므로 Headwind와 PostgreSQL 이미지를 별도로 반입해야 하며, 이미지 파일만 복사해서는 관리 상태가 이전되지 않습니다. 다음도 함께 백업·이관해야 합니다.

- Podman named volume `hmdm-db-data`의 PostgreSQL dump
- `volumes/work`, `volumes/hmdm-config`, `volumes/webapps`
- 운영용 `.env`와 MDM 인증서/개인키(안전한 별도 전달)
- 운영 서버 IP에 맞춘 `.env`와 사설 인증서, 8443 Nginx 라우팅, TCP 31000 연결 설정

운영 서버에서 이미지 아키텍처/버전을 확인하고 복원 테스트를 한 뒤 전환합니다. 특히 운영 DB와 파일 데이터는 이미지 스냅샷에 포함되지 않으므로 별도 복구 절차가 필수입니다.

## 보존 및 중단 주의

- 이 Compose 프로젝트의 named volume `hmdm-db-data`와 파일 폴더는 MDM 기기 등록/정책/앱 배포 상태를 보유합니다. 삭제하지 마세요.
- 중지할 때는 이 디렉터리에서 `podman compose stop`을 사용합니다. `down -v`나 `volumes/` 삭제는 등록 장비와 정책 정보를 잃을 수 있습니다.
- 비밀 `.env`, 인증서 개인키, DB 백업은 Git에 포함하지 않습니다.
- Headwind 공식 컨테이너의 데이터베이스 및 HTTPS 설정은 [공식 Docker 문서](https://github.com/h-mdm/hmdm-docker)를 따릅니다. 현재 구성은 운영 환경에서 아직 기동/인증서 검증되지 않은 배포 초안입니다.
