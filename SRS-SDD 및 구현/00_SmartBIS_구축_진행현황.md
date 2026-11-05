# SmartBIS 전체 구축 진행 현황

Headwind MDM 서버 구성부터 Android 기기 Device Owner 등록·앱 배포까지의 시험 및 운영 매뉴얼 근거는 [Headwind 기기 등록·운영 절차서](../deployment/headwind-mdm/DEVICE_PROVISIONING_RUNBOOK.md)에 누적한다.

2026-10-09 추가: 시험 Android는 유선 LAN에서 `192.168.100.52`, 개발 서버는 `192.168.100.50`으로 통신하며 브라우저 HTTPS 접속 및 Headwind 체크인이 사용자에 의해 확인되었다. Device Owner도 시험 단말에서 성공 확인됐다. 이제 커스텀 Agent/SmartBIS APK를 주소별 재현 가능하게 빌드하고, 설치 실패 롤백·SmartBIS 원격 배포·실 차량번호와 실 콘텐츠의 end-to-end 수용시험이 남아 있다. 상세 절차와 상태는 [Headwind 기기 등록·운영 절차서](../deployment/headwind-mdm/DEVICE_PROVISIONING_RUNBOOK.md)를 참조한다.

- 기준일: 2026-10-05
- 프로젝트: `D:/PROJECT/SmartBIS_jangsung`
- 목적: 구현 위치, 검증 근거, 미완료 범위와 다음 작업을 한 곳에서 찾는다. 기능별 상세 과정은 이 폴더의 기존 기록 문서를 유지한다.

## 문서 사용 원칙

1. 구현을 마칠 때마다 이 문서의 상태와 다음 작업을 갱신한다.
2. 상세한 코드·설정·오류 해결·테스트 과정은 아래 기능별 기록 문서에 추가한다. 기존 기록을 덮어쓰거나 같은 상세 설명을 여러 파일에 복사하지 않는다.
3. 상태는 `구현됨`, `검증됨`, `부분 검증`, `미구현`, `외부 승인 대기`를 구분한다. 사용자가 전달한 테스트 결과와 Codex가 직접 확인한 결과도 구분해 적는다.
4. 요구사항이나 합의가 바뀌면 SRS와 UI 협의사항을 먼저 갱신하고, 그 뒤 구현 기록과 이 표를 맞춘다.
5. `.env`, 개인 키·비밀번호, 사설 CA 개인키 등 비밀값은 문서·명령 출력에 기록하지 않는다.

## 전체 시스템 구성

```text
Android 차량 화면
    │ HTTPS :8443
    ▼
Nginx :443 ── Spring Boot 내장 Tomcat :8080 ── PostgreSQL :5432
                         ▲
                         │ 콘텐츠 메타데이터·배치 적재
                   Node watcher
                         ▲
                         │ .ready가 있는 날짜/배치 폴더
                    upload-store

server/content-store: watcher가 확정한 동영상·이미지 파일을 보관하고 백엔드는 읽기 전용 제공
```

Windows 호스트에서 공개하는 서비스 포트는 Nginx의 `8443`이다. Spring Boot와 PostgreSQL은 Compose 네트워크 내부에서만 접근한다. Android Emulator에서는 호스트 별칭 `10.0.2.2`를 사용한다.

## 구현 및 확인 현황

| 영역 | 현재 상태 | 확인 근거와 경계 |
|---|---|---|
| PostgreSQL·Compose 기반 | 검증됨 | PostgreSQL 기동, 접속, 콘텐츠·운영 테이블 조회 기록이 있다. 데이터 볼륨은 유지한다. |
| SQL migration 파일 순서 | 파일 V001~V012; V012 현 DB 적용 | V010에서 콘텐츠 분류·다중 자산, V011에서 TAGO 동기화 이력, V012에서 Android 단말 상태 테이블을 추가했다. V012는 2026-10-05 DB에 추가 적용했으며 기존 자료를 변경하지 않았다. Flyway/migration ledger가 없어 V001~V011의 과거 적용 이력은 별도 대조 대상이다. |
| Spring Boot 내장 WAS | 검증됨 | Maven 컨테이너 빌드와 Spring 기동, DB health 확인을 기록했다. 외부 Tomcat은 사용하지 않는다. |
| Nginx·사설 CA·HTTPS | 검증됨 | `localhost:8443` 및 CA 검증 성공 기록이 있다. 루트(`/`) 404 진입점을 대시보드 로그인 흐름으로 연결했다. 2026-10-02 backend 재생성 뒤 컨테이너 IP가 달라져 502가 발생한 재현을 확인하고 Podman DNS resolver 기반 10초 동적 upstream 재조회로 수정했다. Nginx 설정 검사, 컨테이너 내부 backend health, 외부 HTTPS actuator health 200을 확인했다. |
| 대시보드 포트 포함 로그인 이동 | 검증됨 | 2026-10-02 비로그인 요청에서 `302` 및 `Location: https://localhost:8443/api/dashboard/login.html`을 직접 확인했다. 로그인 후 브라우저 전 기능을 재검증한 것은 아니다. |
| Node watcher 수신·DB 적재 | v1 호환/v2 계약 통합 검증됨; 오류 화면·장애 복구는 별도 | Node `node --check`, 내장 테스트 11개 통과. 실제 v2 배치 5종 처리와 DB 저장/첨부 순번·크기·SHA-256 확인. 장기 watcher를 새 이미지로 재생성하고 상태 API DB UP/RUNNING 확인. 오류 행 화면 표출, DB 장애 fallback 및 파일 orphan 자동정리는 미검증이다. |
| 콘텐츠 데이터 계약 v2 | 구현·DB 교체·표출 API 검증됨 | `schema_version=2.0`, NOTICE/EVENT와 POSTER_TEXT/TEXT/CARD_NEWS/IMAGE/VIDEO, 역할·순번 자산. V010 적용, v1 호환. 2026-10-04 축제 포스터 5·동영상 5·카드뉴스 2(총 12)를 한 배치로 적재, 기존 콘텐츠 13행·기존 스케줄 제거, 신규 12건 활성 10초 스케줄 등록. `SAMPLE-001` 표출 API에서 CARD_NEWS와 4장 순서 확인. DB 변경 전 dump를 사용자 TEMP에 보관. 원본 upload-store와 이전 content-store 미디어 파일은 삭제하지 않음. 실제 Android 24인치 장시간 전환 시험은 별도. |
| 미디어 제공 API | 검증됨 | DB 경로를 이용한 미디어 GET, `video/mp4`, 크기 헤더와 다운로드 동작 기록이 있다. 서버는 파일 전체를 byte 배열로 메모리에 올리지 않는 방식이다. |
| Android 24인치 운영 화면(MVC) | 구현·단위 테스트·APK 빌드·1920×1080 emulator 동작 확인; 실제 장치 인수시험 대기 | 콘텐츠별 화면과 서버 순환을 구현했다. 2026-10-05 추가로 미디어 파일의 실제 크기/SHA-256 검증, 앱 전용 영구 캐시, 7일 초과 자산·불완전 `.part` 정리, 네트워크 I/O 장애 시 최근 6시간 이내 마지막 정상 홍보 콘텐츠 fallback을 적용했다. ARRIVAL/DISASTER는 오래된 화면을 오프라인 재생하지 않는다. Android 단위 테스트 및 `assembleDebug` 성공. 이번 실행 환경에서 `adb.exe` 실행이 Windows Access Denied로 막혀 새 APK 설치·단말 종단 검증은 미실시. |
| 표출 선택·정책·도착정보 API | 우선순위 적용 구현·배포, 정책 시간/반복 운영 검증 대기 | `display_policies.priority`가 도착정보·재난·예약 콘텐츠 사이의 선택에 적용된다. 예약 콘텐츠는 유형 정책 우선순위로 정렬하고 `force_display` 콘텐츠는 일반 후보보다 먼저 선택한다. 동순위는 도착정보→재난→일반 콘텐츠 순이다. API 응답의 표출시간과 Android 타이머/동영상 종료 전환이 연동되어 있다. Maven 25개 테스트 통과, backend 재배포 후 health `UP`. 실제 운영 priority 변경 시나리오와 모든 유형의 반복 간격 의미/운영 DB 토글은 추가 통합 검증 대상이다. |
| 관리자 인증·데이터 관리 | 대부분 구현, 최신 로그아웃 검증 대기 | 단일 관리자 계정, 게시상태 목록/중지/재개/제외/복구, CSRF 보호가 있다. 2026-10-02 비인증 redirect의 `:8443` 보존은 확인했으며 logout 204 반영 뒤 실제 브라우저 로그아웃/세션 무효화는 재확인 대상이다. |
| 표출 정책 대시보드 | UI/API 및 숫자 priority 적용 구현됨; 운영 시나리오 검증 대기 | 정책 시간·반복 간격·사용 여부·숫자 우선순위를 UI에서 저장하며 서버가 활성 후보 선택에 적용한다. 동순위는 도착→재난→일반 콘텐츠로 결정한다. 운영자가 바꾼 priority·반복 간격의 장시간 종단 시나리오만 추가 확인한다. |
| TAGO 노선·정류소 카탈로그 | 장성군 교차검증 및 타 지역 비교 완료; DB 미변경 | 장성군 105노선·5,339건, 상세/경유 API 실패 0, 원본 `updowncd` 0=2,062/1=2,029/2=1,248. 대전·계룡 9,570건, 나주 5,022건, 함평 5,308건을 같은 API로 전수 조회했고 19,900건에서 코드 2는 0건(노선 조회 실패 0). TAGO 가이드는 0/1만 정의한다. 이는 비교한 3개 도시 결과이지 전국 전수 조사는 아니며, 코드 2 의미는 미확정. 장성군의 순번 중복 및 현 DB 키의 방문 표현 문제도 남아 있어 운수사 노선도 대조 후 저장 구조/도착 판정을 결정한다. |
| 정류소 행정지역 자동분류 | 구현·DB 적용·검증 완료 | 확정 운영 규칙: TAGO 정류소가 장성군 읍·면 경계에 있으면 해당 지역으로 분류하고, 그 외(광주 구·동 경계, 겹침, 좌표 누락 포함)는 콘텐츠 그룹핑 용도로 `광주광역시`에 지정한다. 기존 미분류 44개를 광주 그룹에 배정해 장성군 951개 중 최종 미분류 0개를 확인했다. Maven 테스트 23개 통과, 이미지 재빌드·backend 재기동 완료, health `UP`. 노선·경유 순번·좌표는 변경하지 않았다. |
| TAGO 차량 위치 자동수집·100m 판정 | 수집기 코드·스케줄 구현, 실서비스 요청 실패 | 2026-10-05 재배포 후 105개 route별 조회가 시도됐으나 최신 로그에서 105개 모두 `IllegalStateException`, 저장 0건이다. 실제 차량 위치 적재·vehicleNumber 매칭·100m 판정은 동작 확인되지 않았다. 원인 분석이 남아 있다. |
| 행정안전부 재난문자 외부 수집 | 미완료 / 서비스 승인 대기 | DB 샘플을 이용한 내부 표출 흐름과 운영 API는 분리해서 취급한다. 외부 서비스 호출·승인은 확인되지 않았다. |
| 단말 상태/현재 표출 모니터 | 구현·DB 적용·API 기록 검증; Android 설치 후 보고 확인 대기 | V012 `android_device_status`, 익명 단말 상태 POST, 관리자 전용 조회 및 대시보드 모니터 화면을 연결했다. 임시 차량으로 POST 204와 DB upsert를 확인한 뒤 테스트 행을 제거했다. 새 Android APK의 실제 heartbeat와 로그인 관리자 화면의 표 렌더링은 adb 접근 제한으로 아직 종단 확인하지 못했다. |
| 지역 제한 콘텐츠 표출 | 규칙 구현 및 합성 DB 통합 검증됨; TAGO 실자료 대기 | `ArrivalService`가 예측의 current/next 정류소 ID를 저장하고, `/display/next`가 100m 이내 다음 정류소의 `bus_stops.region_name` 또는 연결된 `regions.region_name`으로 `target_regions` 후보를 필터링한다. 위치·정류소·지역을 모르면 전역 콘텐츠만 허용한다. 고유 시험자료로 지역 차량은 ARRIVAL 후 지역 콘텐츠, 지역 없는 차량은 전역 콘텐츠를 선택함을 확인하고 자료를 삭제했다. |
| Android 운영 미디어 안정성 | 체크섬·제한 오프라인 fallback 구현, 기기 장애시험 대기 | 선언된 파일 크기와 SHA-256을 다운로드 후 및 캐시 재사용 전에 검증한다. 파일은 `filesDir/playback-cache`에 보존하고 7일 지난 파일과 `.part`를 정리한다. 연결 I/O 오류 때 저장 시각 6시간 이내의 마지막 정상 일반 콘텐츠만 사용하며 서버 HTTP 오류/204와 ARRIVAL/DISASTER는 fallback하지 않는다. 실제 네트워크 차단·복구, 저장공간 부족, 손상파일 재다운로드는 단말 시험 대상이다. |

## 콘텐츠 저장소 경로 결정 및 검증 (2026-10-04)

- 외부 관리업체 전달 위치는 프로젝트 루트 `upload-store/YYYY-MM-DD/BATCH_ID/`로 유지한다. 업체 계약은 `contents.json` + 미디어 첨부 + 전송 완료 후 `.ready` 생성만 요구하도록 단순화한다.
- 시스템 내부의 확정 미디어 저장소는 `server/content-store/YYYY-MM-DD/BATCH_ID/`로 둔다. Compose 컨테이너 경로는 호환성 유지를 위해 `/app/content-store`로 고정한다. Node watcher는 읽기/쓰기를 하고 Spring Boot는 읽기 전용으로 연결한다.
- 기존 루트 `content-store`의 7개 파일(총 91,580,923바이트)을 새 위치에 복사했으며 SHA-256이 모두 일치함을 확인했다. watcher/backend를 재생성하고 새 호스트 mount 확인, backend health `UP`, `CNT_BATCH_0001` 동영상 미디어 API HTTP 200을 검증했다. 이전 위치는 롤백 사본으로 보존하며 삭제하지 않는다.
- `.gitignore`는 운영 미디어를 추적하지 않고 빈 `server/content-store`만 `.gitkeep`으로 유지한다. 시스템 이전은 `server/content-store`와 DB 백업을 함께 복사·복원해야 한다.
- 현 DB `contents.source_path`는 `/app/content-store/...` 컨테이너 절대경로이므로 Compose 내부 경로는 변경하지 않는다. 따라서 이번 호스트 디렉터리 이동에는 DB 데이터 수정이 필요 없다.
- 다음 구현 단위는 업무 분류(공지/행사/재난)와 표현 형식(포스터+텍스트/텍스트/카드뉴스/단일 이미지/동영상)을 분리하고, 첨부파일 목록과 역할·순서를 JSON 및 DB에 반영하는 데이터 계약이다. 이 계약과 기존 CARD/IMAGE 행의 호환성 테스트 전에는 watcher/API/Android 스키마를 임의 변경하지 않는다.

## 우선 처리할 미완료 사항

1. **관리자 운영 화면 확인**: 실제 운영 계정으로 단말 표출 모니터, 오류 행 목록, 데이터 관리와 첨부 미리보기를 확인한다. DB 장애 시 watcher 파일 보존/fallback 및 미디어 orphan 처리 규칙은 별도 구현·시험한다.
2. **migration 운영 이력**: V012까지 현 DB 적용 확인. Flyway ledger가 없어 V001~V011 과거 적용 이력을 스키마/기록과 대조하고 수동 이력표로 남긴다. DB volume은 삭제하지 않는다.
3. **지역 데이터 운영화**: GeoJSON 미리보기의 경계 수·정류소 일치/미일치/중복을 검토하고 관리자 적용 후 실제 노선에서 100m 경계, 지역명 정합성 및 구역 밖 fallback을 검증한다.
4. **TAGO 연동**: 관리자 화면에서 카탈로그 수동 동기화를 실행하고 실제 노선·정류소 DB 건수를 확인한다. 읍·면별 정류소 지역 분류 자료를 확보·등록하고, 장성군 실운행 위치 응답에서 차량번호·routeId·정류소 순번을 대조한 뒤 자동 polling을 켜고 100m 실측을 검증한다. **운영 이전 체크리스트**: 차량-단말 배정은 차량번호만 연결하며 route_id를 수동 입력값으로 요구하지 않는다. 배정 차량의 현재 노선은 TAGO `getRouteAcctoBusLcList` 응답의 `vehicleno`·`routeid`로 판별되는지, 선행 route_id가 없어도 조회가 시작되는지, 응답 route_id와 최신 관측 시각이 DB에 반영되는지 실차량으로 확인한다. 현 위치 수집기는 기존 `bus_vehicles.route_id`에 의존하므로 이 불일치는 운영 이전 전 해결·재시험 대상이다.
5. **재난문자 외부 연동**: 승인 뒤 공식 API 문서·응답에 맞게 collector를 구현한다. 기존 DB 샘플 시험과 분리한다.
6. **콘텐츠 계약 v2 후속 검수**: 축제 12건 DB·활성 스케줄 및 SAMPLE-001 표출 API 확인 완료. 서로 다른 카드뉴스 이미지/포스터 실자료를 에뮬레이터 또는 24인치에서 순서·가독성 확인하고 대시보드 첨부 미리보기를 검수한다. 재난 외부 수집은 승인 대기와 분리한다.
7. **운영 검증**: 새 Android APK를 기기에 설치해 heartbeat가 모니터에 보이는지 확인한다. 설정 저장·재실행 유지, 실제 TLS 주소, Wi-Fi 끊김/복구와 offline fallback, 손상 자산 재다운로드, 24인치 화면 가독성·장시간 순환, 최신 logout/세션 무효화, DB backup/restore와 TLS 갱신을 확인한다.

## 기능별 상세 기록

| 주제 | 기준 문서 |
|---|---|
| 최초 서버 기반·Spring Boot 빌드 | [SmartBIS_egov-backend_Build_Log_v1.0.md](SmartBIS_egov-backend_Build_Log_v1.0.md) |
| 최신 백엔드·API·관리자 인증 변경 | [SmartBIS_egov-backend_Build_Log_v1.0_updated.md](SmartBIS_egov-backend_Build_Log_v1.0_updated.md) |
| Nginx·사설 CA·HTTPS·포트 전달 장애 | [SmartBIS_Nginx_Private_CA_Test_Log_v1.0.md](SmartBIS_Nginx_Private_CA_Test_Log_v1.0.md), [누적 변경 기록](SmartBIS_Nginx_Private_CA_Test_Log_v1.0_updated.md) |
| Node watcher·배치 수신·폴링·미디어 보관 | [SmartBIS_Node_Watcher_Build_Log_v1.0.md](SmartBIS_Node_Watcher_Build_Log_v1.0.md), [누적 변경 기록](SmartBIS_Node_Watcher_Build_Log_v1.0_updated.md) |
| UI 합의·구현 경계 | [SmartBIS_UI_협의사항_20261001.md](SmartBIS_UI_협의사항_20261001.md) |
| 시스템 구성·요구사항 | [개발 환경 설정](1.%20Development_Environment_Setup_v1.0_revised.md), [시스템 요구사항](2.%20System%20Requirement%20Specification_v1.0_revised.md) |

## Headwind MDM·Android 운영 시험 현황 (2026-10-09)

- 개발 서버/Headwind: 사용자가 `192.168.100.50:8443` HTTPS 접속과 Headwind 체크인을 확인했다. Android 유선 IP는 `192.168.100.52`; 두 주소는 같은 `/24` 대역이다.
- Headwind Agent: 6.39 opensourceDebug 커스텀 APK 산출물이 있다. 서버 URL은 빌드 프로퍼티로 지정한다. 새 빌드 시도는 PC의 Java 25와 Gradle 8.13 호환 오류(major 69)로 실패했으며, 두 빌드 스크립트에 JDK 17 사전 검사를 추가했다.
- SmartBIS Android: 기존 APK는 있으나 기본값이 에뮬레이터 주소 `10.0.2.2`여서 실기기용 빌드 설정이 부적합했다. 기본 API 주소를 `https://192.168.100.50:8443/api`로 바꾸고 빌드별 주소 지정 및 CA 신뢰를 보완했으며, 수정 후 빌드는 아직 검증되지 않았다.
- 등록 자동화: serial을 명시·재확인하는 Agent 설치/Device Owner 스크립트를 추가했다. 기기 초기화는 하지 않는다. 새 기기의 무인 Device Owner 등록은 APK로 자동화할 수 없으며, 제조사 완전관리형 프로비저닝 지원 또는 기술자 사전 등록이 필요하다.
- 롤백: 빌드 스크립트가 빌드 전 APK와 새 버전 산출물을 날짜별 보관하도록 추가했다. Headwind 1대 canary 배포 후 이전 동일 서명 APK를 지정하는 앱 롤백 절차를 기록했다.
- 남은 종단간 검증: 수정 APK 재빌드 → 단말 1대 원격 설치 → 재부팅/네트워크 복구 → TAGO 차량번호 선택 → 실제 버스 위치·도착 ETA 및 공지/행사/재난/이미지/카드뉴스/영상 순환 → 롤백 리허설. 결과는 [Headwind 기기 등록·운영 절차서](../deployment/headwind-mdm/DEVICE_PROVISIONING_RUNBOOK.md)에 기록한다.

## 다음 기록 갱신

다음 작업이 완료되면 해당 기능별 로그의 관련 절에 **목표 → 변경 파일/설정 → 실행·검증 절차 → 실제 결과 → 오류와 해결 → 남은 위험/다음 단계** 순서로 기록하고, 이 현황표의 상태와 기준일을 함께 갱신한다. 구축 순서를 바꾸거나 대기 사유가 해소되면 그 근거도 남긴다.

### 2026-10-05 다음 정류소 ETA 오버레이

Android 중앙 상단에 다음 정류소와 TAGO의 분 단위 도착예정값을 표시하도록 구현했다. `getRouteAcctoSpcifySttnAccesBusLcInfo`는 GPS 위치만 제공하고 도착시간은 제공하지 않아, 실제 ETA는 TAGO `ArvlInfoInqireService/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList`의 `arrtime`으로 가져온다. `/v1/arrival`은 `estimatedArrivalMinutes`를 반환하고 Android는 값이 없을 때 시간을 만들어내지 않는다. Maven 35개 테스트와 Android `testDebugUnitTest assembleDebug` 성공. TAGO 도착 API 실응답 및 Android 설치 화면 검증은 아직 남아 있다. 자세한 설계·제약은 백엔드 빌드 로그와 시스템 요구사항을 참조한다.
