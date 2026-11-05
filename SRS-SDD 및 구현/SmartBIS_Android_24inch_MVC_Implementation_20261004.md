# SmartBIS Android 24인치 운영 화면 구현 기록

- 작업일: 2026-10-04
- UI 기준: `UI Prototype/Claud/장성 자연친화형_v3.0.html`
- 대상: Android 11 이상, 24인치 1920×1080 landscape 화면
- 앱 모듈: `client-android/app`

## 목표 및 설계 적용

기존 시험 화면은 스크롤 화면과 수동 표출 버튼이 노출되어 운행 중인 버스의 상시 표출 화면으로 적합하지 않았다. 참조 HTML의 크림색 바탕, 숲 녹색과 테라코타 강조색, 산 능선 배경, 굵고 큰 글씨, 장면별 정보 구조를 Android 네이티브 화면에 옮겼다. HTML의 파일 선택/옵션 전환/클릭해 다음 슬라이드 기능은 데모용이므로 운영 앱에는 옮기지 않았다. 실제 콘텐츠 순서와 우선순위는 서버 `/api/v1/display/next`가 계속 결정한다.

## MVC 코드 구성

| MVC 역할 | 파일 | 책임 |
|---|---|---|
| Model | `client-android/app/src/main/java/com/smartbis/client/model/DisplayContent.kt` | 서버 JSON을 화면 유형, 콘텐츠 메타데이터, 정책 표출시간, 정렬된 다중 자산으로 변환 |
| Model | `.../model/SmartBisRepository.kt` | 차량별 다음 표출 API 호출, 이미지/영상 다운로드, `.part` 임시 저장 후 파일 rename, SHA-256 식별 캐시 |
| Controller | `.../controller/DisplayController.kt` | 주기 조회, 표시 유형 분기, 표출 타이머, 카드뉴스 장별 전환, 영상 완료 후 다음 API 요청, 연결 오류 재시도 |
| View | `.../view/NaturalDisplayView.kt` | XML 화면에 데이터를 넣고 필요한 장면만 노출, VideoView 준비/완료/오류 이벤트 전달 |
| View | `.../view/JangseongBackdropView.kt` | 참조안 분위기의 크림·녹색 산 능선 배경 그리기 |
| Activity | `.../MainActivity.kt` | Android Activity 수명주기, 가로·몰입형 화면 설정, Model/Controller/View 연결, 단말 설정 입력 |
| View asset renderer | `client-android/app/src/main/java/com/smartbis/client/view/SvgAssetView.kt` | 참조 HTML의 브랜드 SVG를 Android 캔버스에 렌더링 |
| Layout | `client-android/app/src/main/res/layout/activity_main.xml` | 실제 브랜드 이미지 공통 프레임, 시계, 우측 하단 제목 오버레이와 콘텐츠별 화면 구성 |

Activity는 화면 호스트와 수명주기 어댑터 역할만 하도록 줄이고, API·파일 작업과 순환 정책은 Activity 밖으로 분리했다.

## 콘텐츠 화면 및 동작

| API의 표출 유형 | 운영 화면 |
|---|---|
| `ARRIVAL` | 현재 정류장·다음 정류장·거리·간단한 노선 흐름을 크게 표시 |
| `DISASTER` | 어두운 적색 긴급 배경, 경고 아이콘, 재난 제목과 안내문 |
| `POSTER_TEXT` | 좌측 포스터 이미지, 우측 행사/공지 제목과 본문. category에 따라 행사 테라코타/공지 녹색 강조 |
| `TEXT` | 포스터 없이 전체 영역에 제목과 본문을 크게 배치 |
| `CARD_NEWS` | 서버 `assets` 순서대로 이미지 전환, 공통 우측 하단 제목 및 좌측 하단 장 번호 표시 |
| `IMAGE` | 콘텐츠 이미지 전체 화면 표출 |
| `VIDEO` | 전체 화면 VideoView, 우측 하단에 영상 제목만 반투명 글자로 표시(배경 상자 없음). 반복 재생을 끄고 재생 완료 이벤트에서 다음 표출을 요청 |

### 공통 이미지 오버레이 및 제목

2026-10-05 보완: 앞선 구현은 브랜드를 텍스트/기호로 대체했고 제목도 배경 상자 안에 좌측 하단으로 표시해 승인된 HTML의 의도와 달랐다. 이를 수정하여 HTML과 같은 반투명 코랄 상단 프레임을 두고, `UI Prototype/Claud/charackter.svg`, `BI_h100.svg`, `CI_h100.svg`를 앱 `assets/brand/`에 포함했다. SVG는 AndroidSVG를 통해 기기에서 렌더링하므로 서버/인터넷에서 내려받지 않는다. 시간과 연결 상태는 프레임 오른쪽에 유지한다.

모든 실제 콘텐츠 유형(도착정보, 재난, 포스터+텍스트, 텍스트, 카드뉴스, 단일 이미지, 동영상)에 대해 콘텐츠 제목을 우측 하단에 흰색 반투명 글자와 그림자만으로 표시한다. 제목 배경 박스는 두지 않는다. 카드뉴스 장수 표시는 좌측 하단으로 이동해 제목과 겹치지 않게 한다. 대기/오류 안내 화면에는 콘텐츠가 없으므로 제목 오버레이를 숨긴다.

표출 API의 순서와 서버 정책값을 유지한다. 정책 `displaySeconds`로 텍스트·이미지·카드뉴스 화면 시간을 제어하며, 카드는 콘텐츠 전체 표출시간을 장수로 나눠 순차 전환한다. 영상은 정책 타이머로 중간 종료하지 않고 실제 재생 완료 이벤트에서 다음 항목으로 넘어간다. 표출 항목이 없으면 5초 후 다시 확인하고 통신/파일 오류면 10초 후 재시도한다.

## 24인치 운영 모드와 단말 설정

- 가로 화면을 고정하고 Android 시스템 바를 숨기는 immersive 모드로 실행한다. 화면 꺼짐을 막는다.
- 일반 승객에게 설정 버튼을 보이지 않도록 상단 장성 브랜드 마크를 2초간 누르면 운영자 연결 설정 창이 열린다.
- 차량번호는 자유 입력 저장 대신 두 글자 이상 부분검색 후 서버의 활성 TAGO 차량 목록에서 항목을 선택하는 방식으로 개선했다. 예를 들어 `12`를 검색해 `전남 72자 1234` 후보를 선택한다. 목록 API는 `/api/v1/vehicles?q=12`이며, 활성 `bus_vehicles`의 번호 중 일치하는 항목을 최대 50건 반환한다. 실제 차량 목록이 서버 DB에 수집되어 있어야 검색된다. 테스트용 `SAMPLE-001`은 차량 목록에 실제 등록된 경우에만 선택된다.
- HTTPS API 주소(`/api`로 끝나는 주소)와 선택한 차량번호를 SharedPreferences에 저장한다. 서버 주소 기본값은 `https://10.0.2.2:8443/api`이다.
- 기존 사설 CA `network_security_config`를 유지한다.

### 단말 앱 업데이트 운영 제약

일반 Android 앱은 앱이 설치되어 있지 않은 기기에 스스로 설치할 수 없으며, 설치된 앱도 OS의 설치 확인 없이 다른 APK로 교체할 수 없다. 백그라운드 업데이트 확인·APK 다운로드는 앱 기능으로 구현할 수 있지만, 무인 설치를 원하면 기기를 Android Enterprise/MDM에 등록하거나 앱을 Device Owner로 사전 프로비저닝해야 한다. 이는 최초 설치·기기 초기 설정을 대체하지 않는다. 일반 단말에서 `PackageInstaller` 설치 확인을 우회하는 구현은 Android 보안 모델상 지원하지 않는다.

현재 차량목록 검색과 선택 UI/API는 구현했다. 무승인 업데이트는 미구현이며, 단말을 관리형 키오스크(Device Owner)로 운영할지와 APK 배포 주소·릴리스 서명키가 확정된 뒤 구현해야 한다. Debug APK와 Release APK는 서명 인증서가 다를 수 있으므로 같은 패키지의 자동 업데이트에는 운영용 서명키 관리가 선행되어야 한다.

## 파일 캐시 및 제한

다운로드는 앱 전용 `filesDir/playback-cache`에 `.part`로 쓰고 완료 후 rename한다. 다운로드 완료 및 캐시 재사용 전에 선언된 `sizeBytes`와 SHA-256을 실제 파일과 대조한다. `.part` 파일과 7일 지난 미디어 캐시를 정리한다. 네트워크 I/O 오류에 한해 마지막 정상 일반 콘텐츠 응답을 로컬 저장본에서 복구하며 저장 후 6시간까지만 허용한다. 도착정보·재난정보·알 수 없는 유형은 오프라인 fallback 대상에서 제외한다. HTTP 오류 응답이나 정상 `204 No Content`를 네트워크 단절로 간주하지 않는다. 실제 네트워크 차단·복구 및 디스크 부족/대용량 손상 복구는 기기 인수시험이 남아 있다.

## 검증 결과

- `DisplayContentTest`: ARRIVAL/DISASTER 우선 분기, 콘텐츠 5종 매핑, 이전 VIDEO 응답 하위호환을 검사하는 3개 테스트 추가.
- Gradle `clean testDebugUnitTest assembleDebug`: **BUILD SUCCESSFUL**. 다음 경고 수정 빌드에서도 `testDebugUnitTest assembleDebug`: **BUILD SUCCESSFUL**.
- APK: `client-android/app/build/outputs/apk/debug/app-debug.apk`.
- APK를 `emulator-5554`에 설치·실행했다. 에뮬레이터는 1920×1080, density 120이며 MainActivity가 전면 표시되고 치명 오류가 없음을 확인했다. 화면 캡처에서 축제 포스터+본문의 좌우 배치 및 자연 배경도 확인했다. 운영 화면 시계는 에뮬레이터 기본 시각대 대신 `Asia/Seoul`을 사용하도록 했다.
- 최종 APK에서 동영상이 재생된 뒤 영상 종료 이벤트를 거쳐 다른 서버 콘텐츠(행사 포스터+텍스트)가 표시되는 순차 전환을 확인했다. 즉 서버 `display/next` 재호출·VideoView 완료 callback 연결이 emulator에서 동작했다.
- 2026-10-05 공통 오버레이 보완 빌드: `testDebugUnitTest assembleDebug` **BUILD SUCCESSFUL**, APK를 `emulator-5554`에 재설치했다. 화면 캡처에서 실제 캐릭터·BI·CI SVG가 상단 프레임에 보이고, 카드뉴스 제목이 우측 하단에 배경 없이 표시되는 것을 확인했다. 모든 콘텐츠 유형의 코드 경로에 같은 제목 오버레이를 연결했으며 실제 24인치 장치 확인은 아직 남아 있다.
- 2026-10-05 안정성/상태보고 보완: Android repository가 실제 크기·SHA-256을 검사하고, 마지막 정상 콘텐츠를 제한 시간 동안 오프라인 복구하도록 했다. `DisplayController`가 시작·상태 전환·30초 heartbeat를 서버에 보고하며, 대시보드 표출 모니터는 최신 보고를 조회한다. `testDebugUnitTest assembleDebug` **BUILD SUCCESSFUL**이며 체크섬 일치·크기 불일치·해시 불일치 단위시험을 포함한다. 새 APK 파일 생성은 성공했으나 이 실행에서 adb 접근이 Windows Access Denied로 거부되어 설치/heartbeat 종단시험은 하지 못했다.
- 2026-10-05 중앙 도착 안내 ETA 보완: `/v1/arrival` 응답의 `estimatedArrivalMinutes`를 읽어 공통 상단 중앙에 `다음 정류장 → 정류소명 · 약 N분 후 도착`으로 표시한다. TAGO ETA 미제공/오류일 때는 정류소명만 표시하고 시간을 임의 생성하지 않는다. Gradle `testDebugUnitTest assembleDebug`: **BUILD SUCCESSFUL**. 실 Android 설치와 실제 TAGO 도착정보 응답 표출은 별도 인수시험이다.
- 2026-10-05 운영자 차량 선택 개선: `/v1/vehicles?q={부분문자열}` 검색 API를 추가하고, 운영자 설정에서 차량번호 일부(최소 2자)를 입력하면 서버 차량 목록을 검색해 리스트에서 선택하도록 했다. 검색 결과를 선택하지 않으면 차량번호를 저장할 수 없다. Android 단위 테스트와 `assembleDebug`가 통과했고 새 APK를 에뮬레이터에 설치했다. 실제 설정창에서 `12`를 입력해 서버 검색 결과 차량이 목록에 나타나는 것까지 확인했다. 백엔드 Maven 테스트 37개 통과, Maven JAR·Podman 이미지를 재생성하고 `egov-backend`를 재기동했다. HTTPS `/api/actuator/health`와 `/api/v1/vehicles?q=12` 모두 HTTP 200을 확인했다. 실제 운영 차량에 대한 선택·저장은 번호가 확정되지 않아 수행하지 않았다.
- 2026-10-05 무승인 앱 업데이트 검토: 앱 내 백그라운드 코드는 앱이 설치되지 않은 장치에 앱을 설치할 수 없다. Android 11 일반 앱의 패키지 설치 확인을 우회할 수도 없다. 앱 미설치 상태까지 자동 배포하려면 MDM/Android Enterprise의 Device Owner 정책으로 최초 등록·앱 배포·업데이트를 맡겨야 한다. 단말 MDM/Device Owner 운영 결정과 운영 APK 서명·배포 경로가 확정되기 전까지 자동 업데이트 설치 코드는 추가하지 않는다.
- 실제 24인치 Android 장치에서 글자 크기, 안전 여백, 이미지 크롭, 재난 문구 줄바꿈, 카드뉴스 전체 순환 및 장시간 연속 운행을 확인한 것은 아직 아니다. APK 빌드와 해상도 일치 에뮬레이터 확인은 실제 24인치 기기 인수시험 완료를 뜻하지 않는다.

## 다음 검증

1. 24인치 emulator/device에 APK 설치 후 화면 해상도·밀도에서 6개 화면의 잘림과 큰 글자 가독성을 확인한다.
2. 운영 배치의 포스터, 텍스트, 4장 카드뉴스, 이미지, 영상과 재난/도착 샘플이 서버 우선순위대로 연속 순환되는지 확인한다.
3. Wi-Fi/서버를 잠시 끊고 복구해 마지막 정상 화면 유지, 재시도, 영상 캐시 재사용 동작을 결정·시험한다.
4. 기기 호스트명이 `localhost`/에뮬레이터 주소가 아닐 때 사설 CA 및 인증서 SAN을 검증한다.
