# Headwind MDM 웹 패널 서버 커스텀 소스

## 확보한 소스

- upstream: https://github.com/h-mdm/hmdm-server
- 버전 태그: `v5.41.1`
- 고정 커밋: `efd260edddef7a674014f4b9cf5b4fd9fc7276d3`
- 위치: `deployment/headwind-mdm/hmdm-server-v5.41.1/`
- 커스텀 브랜치: `smartbis/device-identity-guard`
- 라이선스: upstream 저장소의 `LICENSE`와 `NOTICE`를 따릅니다. 수정·재배포 전 해당 파일의 조건과 고지 의무를 확인하세요.

이 소스는 Headwind MDM **서버 웹 패널**입니다. Android 에이전트 소스인 `hmdm-android-v6.39/`와 별개입니다.

## SmartBIS 커스텀 변경

- 에이전트가 HMDM 등록 전에 Android ID의 SHA-256 지문을 서명해 `/rest/public/sync/identity/{deviceId}`로 전송합니다.
- 서버는 `deviceIdentityLocks`에 최초 지문을 기록합니다. 같은 단말의 재요청은 허용하고, 같은 차량 ID에 다른 지문이 오면 HTTP 409로 거부합니다.
- 지문 원문(Android ID)은 저장하지 않습니다. 서버에는 SHA-256 값만 저장하며 로그에도 출력하지 않습니다.
- 서버 소스에 충돌·최초 바인딩·기존 레거시 단말 케이스 테스트를 추가했습니다.
- 이 흐름은 SmartBIS 설정이 반영된 커스텀 에이전트와 함께 사용해야 작동합니다. 서버 변경만 배포하고 기존 에이전트를 쓰면 사전 ID 선점 요청이 없습니다.
- 최초 등록 때 등록이 실패해도 ID 선점은 유지됩니다. 다른 기기가 사용하지 못하도록 한 정책입니다. ID 재배정은 DB에서 운영자 확인 후 `deviceIdentityLocks`의 해당 차량 ID 행을 삭제하는 별도 복구 절차가 필요합니다.
- 2026-10-11 자동 단말 ID 배정 추가: 설치 식별값마다 `BIS_JS_001`부터 순차 ID를 서버에서 배정하며, HMDM의 기존 번호·과거 번호·기기 잠금에 이미 사용 중인 번호는 건너뜁니다. 재시도 시 같은 설치 식별값의 기존 ID를 재사용합니다. 차량번호는 별도 SmartBIS 데이터입니다.
- 자동 배정의 DB 테이블/시퀀스는 WAR의 Liquibase 마이그레이션으로 추가됩니다. 신규 Agent와 이 WAR를 함께 사용해야 합니다.

## 빌드

Windows/Podman 개발 환경에서는 프로젝트 루트에서 아래 스크립트를 실행합니다. 스크립트는 `volumes/hmdm-config/ROOT.xml`의 현재 런타임 설정을 사용해 임시 `server/build.properties`를 만들고, 빌드 후 이를 삭제합니다. Maven 컨테이너에서 WAR를 만든 뒤 `releases/`에 버전·시간이 붙은 파일로 복사합니다.

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deployment\headwind-mdm\build-custom-server.ps1
```

빌드 산출물은 `deployment/headwind-mdm/releases/hmdm-server-5.41.1-smartbis-<시각>.war`입니다. 이 WAR에는 DB 접속 및 서버 설정이 포함되므로 비밀 자료처럼 보호하고 Git에 추가하거나 외부로 공유하지 마세요. 배포 스크립트는 기존 `ROOT.war`를 `backups/`에 복사하고, HMDM 컨테이너 내부 HTTPS 응답을 확인하며 실패 시 이전 WAR로 복구합니다.

### 자동 ID WAR의 실제 배포 순서

프로젝트 루트의 **Git Bash 한 창**에서 먼저 두 DB를 백업하고, 같은 창에서 PowerShell 배포 스크립트를 호출합니다. DB 백업은 읽기 전용 `pg_dump`이며 서비스/데이터를 변경하지 않습니다. 배포 스크립트는 현재 `ROOT.war`를 별도 백업한 뒤 교체하고 HMDM 컨테이너만 재시작합니다. `down -v`는 실행하지 않습니다.

```bash
cd /d/PROJECT/SmartBIS_jangsung
bash deployment/headwind-mdm/backup-provisioning-test-dbs.sh
MSYS_NO_PATHCONV=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'D:\PROJECT\SmartBIS_jangsung\deployment\headwind-mdm\deploy-custom-server.ps1' -WarPath 'D:\PROJECT\SmartBIS_jangsung\deployment\headwind-mdm\releases\hmdm-server-5.41.1-smartbis-20261011-143508.war'
```

현재 빌드의 SHA-256은 `2237fec4c54960ab862d193da4e04b167a60808ae555503a88dd3eb16ae09dcd`입니다. 첫 명령이 두 DB dump 검증 완료를 출력하지 않으면 두 번째 명령은 실행하지 않습니다. 배포 후 HMDM 시작/마이그레이션 로그와 로그인 화면을 확인하고, 기존 단말 체크인을 확인합니다. 배포 스크립트의 자동 롤백은 HMDM HTTP 시작 확인 실패 시에만 동작합니다. HTTP가 뜨더라도 로그인이나 신규 등록이 실패하면 추가 시험을 멈추고 WAR 백업·DB dump를 보존한 채 원인을 확인합니다. 새 마이그레이션은 additive table/sequence 생성이며 기존 단말 행을 재번호화하거나 삭제하지 않습니다.

APK는 서버 WAR 배포와 별도입니다. 신규 단말에서는 이 debug APK가 Android Enterprise의 DPC/Device Owner 프로비저닝을 통해 설치·활성화된 다음, 첫 실행 때 자동 ID를 요청하고 Headwind 체크인을 합니다. Headwind의 **Applications**에 APK를 등록하는 것은 이미 관리 중인 기기의 원격 앱 배포에 해당하며, 초기 상태 단말의 DPC 프로비저닝을 대체하지 않습니다. 이 APK는 debug 서명이므로 공식 서명 Agent를 인플레이스 업데이트하는 용도로 쓰지 말고 격리된 시험 기기에서 사용하세요.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deployment\headwind-mdm\deploy-custom-server.ps1 -WarPath .\deployment\headwind-mdm\releases\hmdm-server-5.41.1-smartbis-<시각>.war
```

서버를 바꾸기 전에 새 identity-claim을 지원하는 커스텀 Android 에이전트가 준비되어야 합니다. 배포 후에는 관리자 로그인, 기존 단말 체크인, 새 단말 등록, 같은 ID를 다른 단말이 재사용할 때 HTTP 409 거부, 앱 배포를 시험 단말 한 대에서 확인한 뒤 확대하세요. 실패 시 백업 WAR를 복원하거나, 스크립트가 자동 복구합니다.

## 다른 체크아웃에서 소스 받기

이 디렉터리는 upstream 태그를 체크아웃한 독립 Git 저장소입니다. 이 프로젝트 저장소를 새로 복제한 경우 다음 명령으로 동일 버전을 다시 받습니다.

```bash
git clone --branch v5.41.1 --depth 1 https://github.com/h-mdm/hmdm-server.git deployment/headwind-mdm/hmdm-server-v5.41.1
```

수정본은 이 저장소의 `smartbis/device-identity-guard` 브랜치에서 관리합니다. upstream 업데이트 시에는 운영 중인 웹 패널 버전과 맞는 태그를 먼저 선택한 뒤, 기존 수정사항을 비교·이식하고 테스트합니다.
