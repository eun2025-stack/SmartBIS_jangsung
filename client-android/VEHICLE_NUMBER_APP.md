# SmartBIS 차량번호 설정 앱

## 역할

- Headwind MDM에 배포할 별도 앱 `SmartBIS 차량번호 설정` (`com.smartbis.vehiclenumber`)입니다.
- 차량번호 일부를 입력해 서버의 TAGO 차량 목록을 검색하고, 목록에서 선택한 번호를 SmartBIS Client에 전달합니다.
- 차량번호는 BIS 단말 ID(`BIS_JS_NNN`)와 별도입니다.
- 검색은 공개 API `GET /api/v1/vehicles?q={검색어}`를 사용합니다. 서버 DB의 `bus_vehicles.active = true` 행만 표시하므로, 동기화된 활성 차량이 없으면 목록도 비어 있습니다.

## 빌드 및 Android Root CA 준비

프로젝트 루트에서 PowerShell로 아래 스크립트를 실행합니다. 기본값은 시험 서버, 앱 버전 `1.6 (code 7)`입니다. ADB online 기기가 한 대면 APK를 빌드한 뒤 `server/nginx/certs/rootCA.crt`를 해당 기기의 Download 폴더에 복사합니다.

```powershell
.\client-android\build-vehicle-number-apk.ps1
```

기기가 여러 대 연결된 경우 대상을 명시합니다.

```powershell
.\client-android\build-vehicle-number-apk.ps1 -DeviceSerial 192.168.1.11:33057
```

운영 서버 전환 시 API 주소를 지정합니다. CA가 바뀌면 앱 번들 인증서도 새 Root CA로 교체한 뒤 빌드해야 합니다.

```powershell
.\client-android\build-vehicle-number-apk.ps1 -ApiBaseUrl 'https://운영서버주소:8443/api' -DeviceSerial 192.168.1.11:33057
```

스크립트는 APK를 `client-android/releases`에 버전·시각이 포함된 이름으로 보관하고, 서버 CA와 앱 내 번들 CA의 인증서 지문이 일치하는지 검사합니다. Android에 CA 파일을 복사하는 것은 **Download 폴더에 파일을 전달하는 것**이며 시스템 인증서 저장소에 자동 설치하는 동작은 아닙니다. 이 앱의 HTTPS 통신은 APK 안에 포함된 동일 Root CA를 사용합니다.

ADB 기기가 연결되지 않은 상태에서도 APK 빌드는 완료되며, CA 복사는 건너뛰었다는 경고를 출력합니다. ADB online 기기가 여러 대이면 `-DeviceSerial`을 반드시 지정합니다.

## APK 경로와 버전

- 차량번호 설정 앱: `vehicle-number/build/outputs/apk/debug/vehicle-number-debug.apk`
- 버전 관리된 차량번호 앱: `releases/vehicle-number-<version>-code<code>-<timestamp>-debug.apk`
- SmartBIS Client: `app/build/outputs/apk/debug/app-debug.apk`
- 차량번호 설정 앱이 SmartBIS Client의 새 차량 선택 전달 기능을 호출하므로 **두 APK 모두 같은 빌드에서 만든 파일을 Headwind Applications에 등록**해야 합니다.
- 시험 빌드의 앱 서명은 디버그 서명입니다. 운영 배포에는 조직이 보관하는 정식 서명 키로 두 앱을 함께 서명해야 합니다.

## Headwind 배포 및 사용

1. 두 APK를 Headwind **Applications**에 각각 등록합니다.
2. `Common - Minimal` 구성에 두 앱을 배정해 저장합니다.
3. 시험 단말이 체크인한 뒤 두 앱이 설치되었는지 확인합니다.
4. `SmartBIS 차량번호 설정`을 열고 차량번호 일부(두 글자 이상)를 입력해 검색합니다.
5. 목록에서 차량을 선택하고 **차량 배정 저장**을 누릅니다. 기존 배정을 해제하려면 **현재 차량 배정 취소**를 눌러 확인합니다.
6. 취소는 현재 단말의 차량 매핑만 해제합니다. 단말 ID/물리 식별 등록과 과거 운행 데이터는 삭제하지 않습니다. 해제된 차량은 다른 단말에 배정할 수 있습니다.
7. 배정 후 **SmartBIS 표출 실행**을 눌러 표출을 시작합니다.

앱은 MDM 단말 ID를 차량번호로 변경하지 않으며, 기기 등록이나 Device Owner 설정을 대신하지 않습니다.
