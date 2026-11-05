# SmartBIS 장성 서버 이전 체크리스트

> 기준: 2026-10-26 (rev 38). 이전 전 반드시 위에서부터 순서대로 확인한다. 완료 칸은 ☐ → ☑로 바꾼다.
> 현재 상태: 운영 DB 테이블 28개, 운영 스택 Up. 마이그레이션 V001~V019는 빈 DB 순차 적용 시뮬레이션 통과(2026-10-26, 커밋 b48e7bb). backend는 Flyway 미사용. 수집 기능 비활성(TAGO 호출 OFF). DB route_call_plan 105행, enabled=true 0행.

## A. 이전 전 준비

| # | 항목 | 확인 방법 | 완료 |
|---|---|---|---|
| A1 | 현재 Git 상태 보관 (V005 삭제, V006~V014 untracked, 군민운수 폴더 등 기존 dirty 목록 포함) | `git status --short > ~/status_before_move.txt` | ☐ |
| A2 | 운영 DB 전체 백업 (pg_dump) | 백업 파일 생성 후 용량·행 수 확인 | ☐ |
| A3 | `.env`, `compose.yaml`, `tago-location-operations.properties`, `tago-arrival-read-policy.properties`, 운영 키 보관 | 키는 저장소 밖 보관, 이전 후 권한 확인 | ☐ |
| A4 | 현재 DB 기준값 기록: route_call_plan 105행, enabled=true 0행, guard_usage_daily 0행, vehicle_route_pattern_daily 248행 | SELECT 결과를 파일로 저장 | ☐ |
| A5 | 보호 파일 해시 기록 (원본 CSV/XLSX, 운영 설정 등 68개) | 해시 목록 저장 | ☐ |

## B. DB 마이그레이션

| # | 항목 | 확인 방법 | 완료 |
|---|---|---|---|
| B1 | **Flyway 이력 처리 방식**: 미사용으로 확정 | backend에 flyway 의존성·설정 없음(pom.xml, application*). `server/database/migrations`를 파일명 버전 순서로 psql 적용. `flyway_schema_history` 없음 | ☑ |
| B2 | 이전 대상 DB에 V001~V014 적용 상태 확인 | 빈 DB 시뮬레이션에서 V001~V014 순차 적용 통과. 실제 대상 DB는 이전 때 테이블 존재 확인 | ☐ |
| B3 | V015 적용 (3개 테이블 생성) | 시뮬레이션 통과(2026-10-26). 이전 때 테이블 존재 확인 | ☑ |
| B4 | V016 적용 (route_call_plan 창·서비스·백업 컬럼) | 시뮬레이션 통과(2026-10-26) | ☑ |
| B5 | V017 적용 (판정 이력 확장, 패턴 계열 NULL 허용) | 시뮬레이션 통과(2026-10-26) | ☑ |
| B6 | V018 적용 (arrival_predictions 3개 컬럼 추가) | 시뮬레이션 통과(2026-10-26) | ☑ |
| B7 | V019 적용 (arrival_query_visits, arrival_direction, query_count CHECK 0~2) | 시뮬레이션 통과(2026-10-26) | ☑ |
| B8 | 순서 준수: V015 → V016 → V017 → V018 → V019 | 버전 순 루프, 실패 시 중단. 빈 DB 전체 통과 | ☑ |
| B9 | 계획 행 재적재 후 105행, enabled=true 0행 확인 | 이전 후 실행 필요. 시뮬레이션 미실행 | ☐ |

## C. 설정 이전 (수집 비활성 유지)

| # | 항목 | 기본값 | 완료 |
|---|---|---|---|
| C1 | `tago.location.operations.enabled` | **false 유지** (일요일 분석 전 변경 금지) | ☐ |
| C2 | observation mode | `.env`의 `TAGO_LOCATION_OBSERVATION_MODE=true`와 compose 기본 `false`가 다름. 활성화 때 두 값을 함께 맞춘다 | ☐ |
| C3 | 위치 API 일일 예산 | 9,500 (개발계정 10,000과 별도) | ☐ |
| C4 | 위치 API 재시도 | 최대 2회, 간격 10초, 실패 시도 포함 집계 | ☐ |
| C5 | 도착 조회 정책 `tago-arrival-read-policy.properties` | 재조회 60,000ms, 기준 3분, 중간 비율 0.5, 최소 30초, 곧 도착 30초, 통과 유예 60초 | ☐ |
| C6 | 도착 조회 2회 상한 | 정책과 DB CHECK로 고정, 설정으로 변경 불가 | ☐ |
| C7 | 운영 API 키 | 운영용과 테스트 PC용 분리 | ☐ |

## D. 서버 코드 배포

| # | 항목 | 확인 방법 | 완료 |
|---|---|---|---|
| D1 | 서버 소스 반영 (`server/egov-backend`) | 커밋 c07eeaa (push 완료, 2026-10-26). node-watcher·nginx·compose 포함 | ☑ |
| D2 | Maven 빌드 `mvn verify` | BUILD SUCCESS, Java 119개 통과, 실패 0 (2026-10-24 실측) | ☑ |
| D3 | 컨테이너 빌드·기동 (podman compose) | `podman compose up -d --build` 후 `--force-recreate egov-backend`. 2026-10-24 16:41:17 StartedAt, running, 이미지 aeea8cbe8abd. MDM 컨테이너 Uptime 6h 유지 (실측) | ☑ |
| D4 | 기동 설정 확인 (기준 변경: 기동 로그에 값이 없어 환경 변수·`.env`·application.properties로 확인) | `TAGO_ARRIVAL_ENABLED=false` 적용 후 재기동. 재기동 후 로그 27줄, 기동 완료 1, TAGO 줄 0, 예외 0. 컨테이너 환경 변수 확인: TAGO_ARRIVAL_ENABLED=false, TAGO_VEHICLE_LOCATION_ENABLED=false, TAGO_LOCATION_OBSERVATION_MODE=false, TAGO_ENABLED=true (2026-10-24) | ☑ |
| D5 | podman machine PC 재부팅 후 자동 시작 | 재부팅 후 컨테이너 상태 확인. **완료(2026-10-24)**: Windows 부팅 18:52:17, 재부팅 후 수동 기동 없이 프로젝트 컨테이너 4개 Up(자동 시작). nginx 로그 18:57:58(부팅 후 프로세스) 근거. 조건: 재부팅 후 podman start·compose up 미실행 | ☑ |
| D6 | 도착 API 응답에 arrivalStatusText, 초 단위 ETA 포함 확인 | `/v1/arrival` 응답 확인 | ☐ |

## E. 클라이언트 (Android)

| # | 항목 | 확인 방법 | 완료 |
|---|---|---|---|
| E1 | APK 빌드 및 버전 번호 상향 | 소스: versionCode 15, versionName 1.13, arrival 경로 `/v1/arrival`(중복 해소). Android 테스트 23건 통과(2026-10-24, ExampleUnitTest 1 + ArrivalDisplayWindowTest 2 + ArrivalHeaderTest 16 + DisplayContentTest 4), 경고 1건(ArrivalHeaderTest.kt:104). **APK 빌드·설치 미실행**: 이전 APK(versionCode 14, SHA-256 41842AC7...)는 폐기 대상 | ☐ |
| E2 | 상단 바 "다음 정류장 → 장성군청 N분 후 도착" 구현 여부 | 미구현이면 Codex 요청 후 확인 | ☐ |
| E3 | 상단 바 상태별 문구 (정상·곧 도착·통과·통과 추정·정보 없음) | 단위 테스트 + 실기기 확인 | ☐ |
| E4 | 전체 화면 "이번 정류장 OOO 다음 정류장 OOO" 표시 | 실기기 확인 | ☐ |
| E5 | 단말 설치 후 실제 주행에서 곧 도착·통과 전환 시점 확인 | 테스트 운행 로그 | ☐ |
| E6 | MDM 에이전트(커스텀 빌드, vehicle-number 앱과 별도) | 소스: `deployment\headwind-mdm\hmdm-android-v6.39`. `ServerService.java:102` polling 호출, `Initializer.java:115` pushOptions=polling일 때 실행. 단말 logcat에 polling 실패 없음(08:11~08:20 단말 시각). **서버 측 원인(H11)**: MQTT 바인드 주소를 `0.0.0.0:31000`으로 변경(13:19 재시작) 후 `Cannot assign` 사라짐. 단 polling은 500에서 504(nginx upstream timeout, 업스트림 10.89.0.7:8443)로 변함 | ☐ |

## F. 이전 후 검증 (활성화 전)

| # | 항목 | 확인 방법 | 완료 |
|---|---|---|---|
| F1 | 전체 테스트 재실행 | Java 119 재확인(2026-10-26, mvn verify BUILD SUCCESS, Failures 0). Python 102·Android 23은 미재확인 | ☐ |
| F2 | 보호 파일 해시 비교 | A5 기록과 일치 | ☐ |
| F3 | DB 계획 행 수 비교 | 105행, enabled=true 0행 | ☐ |
| F4 | 수집 비활성 상태에서 외부 호출 0회 | 도착 배치 비활성화 후 재기동 90초 로그 TAGO 0줄, 예외 0. 최근 10분 로그 TAGO|tago|api.data.go.kr|ConnectException 0줄 (2026-10-24, 재기동 후 10분 이상 경과 기준) | ☑ |
| F5 | 일요일 분석 후 활성화 여부 결정 기록 | 결정 문서 저장 | ☐ |
| F6 | 빈 DB 이전 시뮬레이션 | 임시 DB(운영 이미지)에 V001→V019 순차 적용 후 backend 기동 | ☑ 2026-10-26: 테이블 28개, 운영과 목록 동일(컬럼 순서만 상이), backend Started, APPLICATION FAILED 없음. 제외: HMDM·nginx·Android·실제 TAGO 호출 |

## G. 활성화 (일요일 분석 이후에만)

| # | 항목 | 확인 방법 | 완료 |
|---|---|---|---|
| G1 | 판정 배치 결과 검토 (`route_call_plan_proposal.json`) | 사람이 검토 후 승인 | ☐ |
| G2 | route_call_plan 반영 (enabled=true) | 별도 SQL, 행 수 확인 | ☐ |
| G3 | `location.operations.enabled=true`, observation mode false 함께 변경 | 재기동 후 로그 확인 | ☐ |
| G4 | 첫 1일 호출량 확인 | `guard_usage_daily` 위치 9,500 이하, 정류장당 도착 조회 2회 이하 | ☐ |
| G5 | 위치 API 실패율 점검 (관측 기준 12~33%) | 실패 사유 집계 | ☐ |
| G6 | `TAGO_RESULT_MISSING` 원응답 1건 저장·확인 | 로그 또는 샘플 저장 | ☐ |

## H. 미해결 항목 (이전 전 결정)

| # | 항목 | 현재 상태 |
|---|---|---|
| H1 | Flyway 이력 처리 방식 | 결정: Flyway 미사용, psql 순차 적용 (B1) |
| H2 | `TAGO_RESULT_MISSING` 실제 응답 유형 | 미확인 (G6) |
| H3 | 10-23 수집 공백(20:47 이후) 영향 | 엄격 기준 채택, 144번 2건은 백업 전환 |
| H4 | 100m ↔ 30초 환산 | 추정값. 실제 주행 로그로 보정 필요 |
| H5 | 도착 API 일일 호출 실측 | 시뮬레이션 126회/일은 가정값 |
| H6 | 운행 시간 기준 (06:15~21:00 vs 06:30~20:30) | 운수사 확인 필요 |
| H7 | 87번 TAGO 미존재 | 단말 설치 대상 여부 확인 |
| H8 | 설계 문서 rev 7 4·5장 갱신 | rev 8에서 반영 예정. V018·V019는 마이그레이션에 반영 완료 |
| H9 | 상단 바 표시 구현 | Codex 요청 미실행 |
| H11 | 단말 요청 경로 중복 및 polling 500 | **완료(2026-10-25)**. arrival: `/v1/arrival` 수정(rev 26), 테스트 23건 통과. polling 500 원인: `LongPollingServlet.onTimeout`이 응답을 완료하지 않아 타임아웃 시 컨테이너가 500 반환. `event.getAsyncContext().complete()`로 수정. 검증: 기동 시 `ROOT.war`에서 재배포되므로 WAR와 `ROOT/` 안 jar 모두 수정본(`e7bb85ce…`) 반영. 재기동(07:14) 후 polling 200(`data:[]`), 07:14 이후 500 0건(마지막 500은 07:10:31). 메시지 즉시 전달 확인(08:00 서버 로그). 원격 재부팅 명령 BIS_JS_003 도달, 복귀 확인(08:08), BIS_JS_001 polling 200(08:12). 남은 항목: 푸시 본문 한글 깨짐, MQTT 31000 중복 바인드(별도), 백업 정리·소스 커밋(사용자 결정) |
| H12 | 원격 이력 노출 DB 비밀번호 | 커밋 01ff968 이력의 `.env.backup-tago-*`에 POSTGRES_PASSWORD(현재 값과 동일), TAGO 옛 키 2건 존재. 이전 때 DB 비밀번호 교체, TAGO 옛 키 포털 폐기 확인 |
| H13 | 이전 절차 누락 항목 | `volumes/`(HMDM 설정), `server/content-store/`, `certs/`는 .gitignore 대상이라 저장소에 없음. 운영에서 복사 |
## I. 판단 기준 요약

- 이전 완료 조건: A~C 전부, D1~D5, F1~F4 완료
- 활성화 조건: F5 결정 + G1 승인. 이 둘 없이 G2 이후를 실행하지 않는다
- 중단 조건: B 단계에서 마이그레이션 실패, F2 해시 불일치, F4에서 외부 호출 발생