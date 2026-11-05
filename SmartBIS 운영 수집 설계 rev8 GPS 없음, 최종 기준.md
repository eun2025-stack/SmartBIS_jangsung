# SmartBIS 운영 수집 설계 rev8 (GPS 없음, 최종 기준)

작성일: 2026-11-05 (Asia/Seoul)
상태: **최신 기준.** rev7 이하는 참고용이다. 충돌하면 이 문서가 우선한다.

---

## 0. 목표와 원칙

- **목표**: TAGO 위치·도착 데이터로 제공 자료(운행패턴·순번·창 규칙)와 실제 운행의 일치 정도를 검증하고, 규칙의 적정성을 판단한다.
- **무인 운영**: 사람이 매번 메모하거나 결정하는 방식은 쓰지 않는다.
- **호출 주체**: TAGO는 백엔드만 호출한다. 클라이언트 앱, 웹 대시보드, HMDM은 TAGO를 호출하지 않고 DB를 읽는다. (2026-11-04 전수 조사: 직접 호출 위치는 백엔드 3곳뿐)
- **검증되지 않은 것은 확정하지 않는다.** 승인 없이 DB 쓰기, TAGO 호출, 컨테이너 기동, 커밋, push를 하지 않는다.

### 정정
- rev5·rev6의 "운영 DB" 정의는 폐기한다. 서버(web1·web2)에는 DB가 없다.
- 현재 대상 DB는 **로컬 Podman의 postgres-db 안 `smartbis_db`**다. 2026-11-04 교체 후 Flyway V001~V019가 19/19 적용되었다.
- 원자료 CSV 6개 파일은 로직과 테스트가 읽지 않는다. 판정 근거로도 쓰지 않는다. 특히 `nodeord_raw`는 가공값이므로 원본 증거가 아니다.

## 0-1. 입력 자료 (rev6 복원, 파일 확인 2026-11-05)

| 파일 | 내용 | 확인 |
|---|---|---|
| 차량구분.csv | 33대. div = 운행지역 (ud 10, ld 8, js 10, gj 2, rb 3). rb = reserve bus | 확인 |
| 배차.csv | 29대. 열: 차량번호, div, this week 1~7, next week 1~7. 예시 표로 규칙을 설명한다 | 확인 |
| 운행패턴.csv | 39행, 56열. 행 1개 = 패턴 1개. 회차마다 seq/time/route 3열 묶음, 최대 18회. 차량번호 열 없음 | 확인 |

- 수집 대상 차량: **29대** = 배차.csv 기준 (ud 10, ld 8, js 9, gj 2). 차량구분 33대에서 7536과 rb 3대를 뺀 수와 일치한다.
- 7536: 첨단100 투입, TAGO route_id 없음, 수집 대상 제외. route_id가 확보되면 별도 요청으로 추가한다.
- rb 3대 (7503, 7510, 7516): 예비 차량, 배차 없음, 수집 대상 제외.
- 패턴 39개 = 평일 29 (ud1~10, ld1~8, js1~9, gj1~2) + 휴일 10 (hjs1~8, hgj1~2). 평일 패턴 29개가 차량 29대와 1:1로 대응한다. 휴일 패턴은 같은 차량이 휴일에 쓰는 패턴이다.
- 배정 규칙 (배차 예시에서 확인):
  - ud/ld: 일요일마다 번호가 한 칸씩 넘어간다 (주 단위).
  - js/gj: 평일 근무일마다 순서대로 넘어간다 (날짜 무관).
  - hjs/hgj: 휴일 근무일마다 순서대로 넘어간다. 빈칸은 휴차다.
  - 달력: 1일 = 일, 7일 = 토, 8일 = 일, 14일 = 토. 14일 표는 예시이며 반복 규칙이 아니다.
  - 배차 예시의 8·14일 번호 중복은 예시 실수다.
- 미결: gi1·gi2 (gj 오타 의심), 7513의 ld2 (gj 차량이 ld 패턴 운행).
- 11일 배정은 5~9일 수집 관측으로 정한다. 배차 표로 계산하지 않는다.
- 운행패턴 time은 기준 출발 시각으로 해석한다 (rev6). 수집 데이터로 검증한다.

---

## 1. 현재 상태 (2026-11-04 검증)

| 항목 | 상태 | 근거 |
|---|---|---|
| DB 스키마 | Flyway V001~V019 19/19 | 교체 후 적용 |
| bus_stops | 951행 | SAMPLE_NODE_001·002 2행 삭제 커밋 (953→951) |
| bus_route_stops | 5,339행, 105개 노선, 노선별 최소 순번 0 | 원본 CSV와 노선별 행 수·순번 집합·정류장 ID 일치 |
| 순번 이상 | 중복 0, 음수 0, NULL 0, 연속 끊김 0 | DB 집계 |
| bus_routes | 105행 | 카탈로그 동기화 결과 |
| regions | 214행 | 덤프 복원 |
| route_call_plan | 105행, enabled 0건 | 옛 DB CSV 보관. **복원 미완료** |
| vehicle_route_pattern_daily | 248행 | **복원 미완료** |
| bus_vehicles | 29행 | **복원 미완료** (29는 행 수이며 운행 차량 수가 아님) |
| 백업 | `D:\DB_BACKUP\smartbis_db_20261104.dump` 외 | 교체 전 옛 DB 기준. 복구 기준 |
| 백엔드 이미지 | `localhost/smartbis-backend:odd-allowed-20261104` (ID 90e7c781…13a9) | jar SHA256 일치, APPROACH_WINDOW_STOPS 포함, ARRIVAL_TARGET_CANDIDATES 미포함 |
| 실행 컨테이너 | `smartbis_jangsung-egov-backend-1` exited, 이전 이미지(f2f74cdc…) | 교체 보류 결정 |
| 수집 | 스위치 OFF, 호출 0건 | 위치·도착 스위치 4개 모두 OFF |
| compose 백엔드 | `build:`만 있고 `image:` 고정 없음 | 교체 방식 미정 (§9) |

---

## 2. 순번 (node_order)

1. **TAGO 원본 값을 그대로 저장한다.** 변환, 추론, 빈 번호 재배정을 하지 않는다.
2. **기준은 0부터다.**
   - 노선 TSB342000106: 공공데이터포털 직접 수신 원문 56건, nodeord 0~55 연속, 중복·결측 없음.
   - 노선 TSB342000142: 2페이지 합계 113건, nodeord 0~112 연속. 기존 CSV와 차이 0건.
   - 노선 105개 전부 시작값 0 (2026-11-04 조회 CSV 기준, HTTP 200·resultCode 00 107회 호출).
   - 원자료 CSV의 `nodeord_raw`는 이미 −1 처리된 가공값이므로 근거로 쓰지 않는다. "1부터" 기준은 근거가 없어 철회한다.
3. **거부 규칙**
   - 음수: `STOP_ORDER_INVALID`
   - 결측: `STOP_ORDER_MISSING`
   - 비숫자: `STOP_ORDER_INVALID`
   - 응답 내 같은 노선·같은 순번 중복: `DUPLICATE_RAW_STOP_ORDER`
   - 거부된 항목이 하나라도 있으면 카탈로그 저장 트랜잭션 전체를 롤백한다. 기존 행은 그대로 둔다.
4. **위치 응답 파서**도 같은 기준(0 허용, 음수 거부)을 따른다. 현재 위치 파서는 0을 허용하고, 음수 거부 적용 여부는 다음 수정 때 확인한다.

---

## 3. 카탈로그 동기화

### 3-1. 저장 방식 (혼합, 소스 확인 기준)

| 대상 | 방식 | 기존 행 처리 |
|---|---|---|
| bus_route_stops | 노선 단위 교체 | 응답에 포함된 노선의 기존 행 전체 DELETE 후 검증된 항목 INSERT. ON CONFLICT 없음. 0 행 포함 |
| bus_routes | 병합 | INSERT, ON CONFLICT(route_id) DO UPDATE |
| bus_stops | 병합 | INSERT, ON CONFLICT(node_id) DO UPDATE. 지역 필드 보존 |
| 응답에 없는 노선의 bus_route_stops | 보존 | 연쇄 삭제하지 않는다 |
| 응답에 없는 노선의 bus_routes | 비활성화 | 같은 도시의 활성 TAGO 노선 중 응답에 없는 노선 |
| 사용하지 않는 TAGO 정류장 | 비활성화 | 활성 노선에서 쓰지 않는 정류장 |

- TRUNCATE, saveAll, 별도 upsert 메서드는 없다.
- 전체 노선 목록이나 전체 정류장 목록이 비면 저장 전에 실패한다.
- 응답 노선에 0이 있어도 허용한다(§2). 검증 실패 시 저장 전 중단이므로 기존 행은 유지된다.
- 카탈로그 저장은 한 트랜잭션, 지역 분류는 별도 트랜잭션이다.
- 수동 실행만 가능하다: `POST /api/v1/admin/tago/catalog/sync` (ADMIN 로그인, CSRF). 기동 시 자동 동기화는 없다.

### 3-2. 정류장 마스터
- SAMPLE_NODE_001(장성터미널), SAMPLE_NODE_002(장성읍사무소) 2행은 삭제했다. 생성 경위는 미확인이다.
- 삭제 후 마스터 없는 경유 참조는 0건이다.

### 3-3. 미결
- 응답에서 빠진 노선의 기존 경유 행을 보존할지 정리할지 결정해야 한다(§12).
- `catalog_changed`가 NULL이라 자료 변경 여부는 확인되지 않았다.
- `regionClassification.status` 값은 응답에서 확인되지 않았다.

---

## 4. 도착 호출 대상 규칙

### 4-1. 대상 판정 (정류장 s, 차량 현재 순번 c, 정류장 순번 t)

| 단계 | 조건 | 결과 |
|---|---|---|
| 접근 창 | 0 ≤ t − c ≤ 2 | 창 밖이면 방문 claim과 HTTP 호출 없음 |
| 짝수 | t mod stopStep = 0 (stopStep 기본 2, 절대 기준) | 항상 허용 |
| 홀수 | firstTripVisit(c == 0) **또는** overdue | 둘 중 하나일 때만 허용 |
| overdue | 저장된 도착 예정 시각이 있고 now ≥ arrivalAt | HEAD 정의 유지 |
| 하한 | t ≥ minimumOrder, t ≥ c | 하한 검사에만 사용 |
| 방문 상한 | attempts == 0 **그리고** query_count < 1 | 정류장별 1회 |

- **최소값(minimum_order)은 짝수 판정에 쓰지 않는다.** 짝수 판정은 절대 순번 기준이다. 최소값이 0인 현재 데이터에서는 두 기준이 같지만, 기준을 하나로 고정한다.

### 4-2. 후보 조회와 선택

1. SQL은 노선 내 `node_order ≥ c AND node_order ≤ c + 2`를 `ORDER BY node_order LIMIT 3`으로 조회한다. 홀짝은 SQL에 두지 않고 Java에서 판정한다.
2. Java는 차량마다 다음 순서로 **한 개만** 선택한다.
   1. 활성 방문 대상이 창 안에 있고 자격이 있으면 그 대상을 유지한다.
   2. 없으면 자격 있는 후보 중 순번이 가장 작은 1개를 고른다.
   3. 자격 있는 후보가 없으면 호출하지 않는다.
3. 선택된 대상에 대해서만 방문 상한을 확인하고 호출한다. **차량당 회차마다 최대 1회 호출**한다.
4. 후보 목록은 저장하지 않는다. 미선택 후보는 다음 관측에서 다시 판정한다.

### 4-3. 재시도와 응답 없음
- 재시도는 최대 2회, 간격 10초다. 최종 결과는 방문당 1건이다.
- 응답이 없으면 학습 중앙값을 쓰고, 중앙값도 없으면 정류장 수 × 1.2분을 쓴다(placeholder, 관측 후 조정).

### 4-4. 알려진 한계
- **자격 상실 후 복귀 시 재조회 가능.** 현재 방문 상태는 차량당 활성 1행이다. 대상이 바뀌면 query_count가 0으로 초기화된다. 대상이 자격을 잃었다가 되찾으면 같은 정류장을 다시 호출할 수 있다.
- 유효한 활성 대상 사이의 불필요한 전환은 막는다.
- 근본 해결은 §10의 방문 저장 구조 확장이다. 승인 전까지 다중 호출은 하지 않는다.

---

## 5. 호출 예산

| 항목 | 값 | 비고 |
|---|---|---|
| 위치 호출 | 일 9,500회 | 카운터 분리 |
| 도착 호출 | 일 9,000회 | 재시도 포함 |
| 재시도 | 2회, 간격 10초 | 설정값 유지 |
| 차량당 회차 상한 | 3회 (1 + 재시도 2) | 대상 1개 기준 |
| 수집 대상 29대 기준 회차 상한 | 87회 | 배차.csv 29대 (7536, rb 3대 제외). bus_vehicles 행 수와 무관 |
| 일일 최악 회차 수 | 약 103회차 | 9,000 ÷ 87. 실제 호출은 방문당 1회라 훨씬 적음 |
| 예산 도달 시 | 해당 호출만 중지하고 로그 | 전체 중지 아님 |

- 예산 상수(위치 9,500, 도착 9,000, 재시도 2, 간격 10초)는 바꾸지 않는다.
- 일일 실측 검증은 수집 ON 이후에 한다.

---

## 6. 수집 스케줄 (rev6 승계, 재확인 필요)

| lane | 대상 | 주기 | 시간 | 비고 |
|---|---|---|---|---|
| A | 102·103·155 (3개) | 10분 | 05:15~21:15 | |
| B | 관내 86 + 변형 100번 14 = 100 | 관측 기반 | 창 시작: 기준 출발 −15분(관내), −10분(변형) | 종료: 마지막 node_order 최초 관측 +5분, 미관측 시 창 시작 +60분(placeholder) |
| C | 105개 노선 | 30분 | 06:00~21:00 | 2초 간격. 429 시 백오프 2→3→5초 |

- 야간(21:15~05:15)에는 위치 호출을 하지 않는다.
- 수집 종료는 일요일 21:00 기준이다. 종료 장치는 코드 존재만 확인했고 동작은 미확인이다.
- lane B 대상 수는 문서마다 달라서(100, 88+100, 장성 100 중 3개 제외) 최종 확정이 필요하다.

---

## 7. 관측 기록과 실행 ID

- **collection_run_id**: 수집 시작마다 부여한다. 이전 행은 삭제하지 않고 pre_run으로 분리한다.
- **관측 모드**: 응답에 있는 모든 차량을 저장한다. 기록 항목은 호출 ID, 호출 시각, route_id, 차량번호, node_order, 호출 종류(위치·도착), 방문 ID, 재시도 횟수, 최종 결과 여부다.
- **정지 구간**: 중간 정지는 collection_pause_intervals에 기록한다.
- **프로세스 기록**: collection_runs, collection_process_runs에 남긴다.
- **스위치**: 기본 OFF이며 호출 직전에 차단된다. ON 명령은 `enable --run-id <실행 ID> --confirm-g6-g8`이다.

---

## 8. 도착-차량 대응과 판정

- 남은 순번 = 정류장 node_order − 차량 node_order
- 0 이상인 최솟값 차량이 arrprevstationcnt와 같으면 대응 성공
- 동률, 후보 없음은 별도 분류
- 조회 대상 외 정류장(홀수 중 자격 없는 정류장)은 일치율 분모에서 제외하고 따로 센다

### 판정 지표
일치율, 대응 성공률, 동률 비율, 미관측 비율, 정지 시간, 실패 원인 분류.

### 운행 시각 판정 (확정, 2026-11-05)
- 기준: seq, time, route 세 값을 같은 중요도로 비교한다. 승객에게는 time이 가장 중요하지만, 운전원 관리 기준에서는 세 값이 동일한 비중이다.
- **일치**: 회차마다 seq·route 동일, |Δtime| ≤ 5분
- **시각 편차**: seq·route 동일, |Δtime| > 5분
- **불일치**: seq 또는 route 다름, 또는 하루 회차 수 다름
- 허용 오차 ±5분은 현장 관찰 기준이다.

### 기타 허용 기준 (placeholder, 관측 후 조정)
- 순번 누락 5개 이하
- 시차 10분

---

## 9. 운영 환경과 수집 전 조건

- **PC 상시 가동**: 수집 시간 동안 PC를 켜 둔다. 22~05시 꺼짐은 호출 금지 시간과 겹치므로 허용한다. 중간 정지는 정지 구간으로 기록한다.
- **G1(무로그인 자동 기동)**: 필수 조건에서 제외했다.
- **G6~G8 확인(수집 ON 전 필수)**: 절전·최대 절전 해제, 업데이트 재시작 차단, VPN 해제.
- **컨테이너 교체**: 수집 ON 직전에 한다. compose 백엔드가 build-only라 교체 방식(§12-8)을 먼저 정한다. 교체 후 60초 관찰로 TAGO 호출 0건을 확인해야 한다.
- **공개 주소**: PUBLIC_BASE_URL = `https://jangseong.go.kr:8443` (설정값).
- **서버 배치 (2026-11-05 결정)**: SmartBIS는 web1로 이전한다. web2는 홈페이지 시스템 이전용이다. NAS는 백업용이며 보관 기간은 1개월이다. rev7 §9의 web1 보류는 해제되었다.
- **접근 경로 (저장소 기준, 2026-11-05 확인)**: 클라이언트 → 호스트 8443 → nginx 컨테이너 443 → egov-backend:8080/api → Spring Boot 내장 Tomcat. 근거: client-android build.gradle.kts:6, compose.yaml:77·79·81, server/nginx/nginx.conf:13·23, application.properties:3~4.
- **MDM 기본 서버**: deployment/headwind-mdm/nginx/mdm.conf.template도 443 default_server에서 /api를 프록시한다. compose.yaml:81로 같은 nginx 컨테이너에 마운트되는지, 두 server 블록의 우선순위는 web1 배포 전에 확인한다.
- **/data/smartbis**: 저장소 텍스트 7,347개에서 0건. 읽지 못한 파일 5개는 미확인이다. nginx 경로가 아니다.
- **인증서**: nginx.conf는 /etc/nginx/certs/, MDM은 /etc/nginx/mdm-certs/ 경로를 쓴다. web1에 파일이 있는지 확인한다. 내용은 읽지 않는다.
- **네트워크**: 기본 프로젝트 네트워크(smartbis_jangsung_default)로 추정. 실제 실행 네트워크는 미확인.
- **실제 서버 설정·TLS 동작**: 미확인 (저장소 기준 확인만 완료).

---

## 10. 방문 저장 구조 (한계와 후속 과제)

- **현재**: 차량당 활성 방문 1행. 대상이 바뀌면 query_count를 0으로 초기화한다.
- **위험**: 자격을 잃었다가 복귀하는 정류장을 재조회할 수 있다(§4-4).
- **확장안(승인 필요, 미구현)**: (차량, 노선, 정류장 순번) 단위로 방문 상태를 저장하고, query_count를 지우지 않고 유지한다. 보관 기간과 정리 기준을 정하고, 예산을 다시 계산한다.
- **그 전까지**: 단일 호출을 유지한다. 다중 호출 전환(K 후보 호출)은 보류한다.

---

## 11. 테스트와 검증 기준

| 검증 | 결과 | 범위 |
|---|---|---|
| 관련 단위 테스트 | 44건 통과 | 정책, 접근 창, 활성 방문 유지 |
| PostgreSQL 임시 DB | 14건 통과 | 메모리 스키마 사본(가드만 치환), 종료 시 삭제 |
| 전체 Java | 213건 통과 | CollectionRunSchemaTest 제외(DB 쓰기) |
| 원문 대조 | TSB342000106(56건), TSB342000142(113건, 2페이지) | 공공데이터포털 직접 수신 또는 호출 1회 |
| 실제 TAGO 호출 | 0건 | 수집 OFF. 교체 후 관찰은 미실행 |

### 미검증 항목
- CollectionRunSchemaTest(DB 쓰기 테스트)는 실행하지 않았다.
- SQL 테스트는 Java의 실제 JDBC 전송 경로를 거치지 않았다. 바인딩을 맞춘 동등 SQL로 검증했다.
- 실제 운행 데이터 기반 일치율은 아직 없다.

---

## 12. 미결 항목 (사람 결정 필요)

| # | 항목 | 현재 상태 | 필요한 결정 |
|---|---|---|---|
| 1 | 비응답 노선의 잔존 경유 행 | 보존 | 보존할지 정리할지 |
| 2 | 복원 3개 테이블 | DB 0행 (2026-11-05 SELECT 확인). 원천 CSV: 배차 29대, 운행패턴 39행, 차량구분 33대. 문서상 248행·105행은 옛 DB CSV 기준 | 복원 원천과 적재 방식 확정, 적재 승인 |
| 3 | route_call_plan 활성화 | enabled 0건 | 활성화 기준 |
| 4 | bus_vehicles의 route_id 출처 | 배차 라벨에는 route_id가 없다. 패턴 라벨은 운행패턴.csv 회차의 route 목록으로 연결할 수 있다. bus_vehicles는 차량당 한 칸이라 표현이 맞지 않는다 | 차량·날짜별 패턴 구조로 바꿀지 결정 |
| 5 | 광주 구·동 라벨 69개 | 잔존 | "광주광역시" 한 묶음 적용 여부 (bus_stops.region_id 쓰기 승인 필요) |
| 6 | regionClassification.status, catalog_changed | NULL, 미확인 | 자료 변경 여부 판단 근거 |
| 7 | 콘텐츠·기기·표시 정책 테이블 | 복원 미결정 | 복원 여부 |
| 8 | 컨테이너 교체 방식 | compose build-only, 교체 보류 | 옵션 A(수집 ON 직전 교체) 확정 여부, 이후 B(image 고정) 또는 C(직접 생성) |
| 9 | 홀수 예외의 firstTripVisit 의미 | HEAD 정의(currentOrder == 0) 유지 | 기점 대기를 첫 방문으로 볼지 재검토 |
| 10 | 방문 저장 구조 확장 | 승인 대기 | 확장 착수 여부 |
| 11 | 서버 이전 범위 | web1 이전 결정 (2026-11-05). web2는 홈페이지 전용 | 이전 범위 (백엔드, HMDM, nginx), 방화벽 목적지, PUBLIC_BASE_URL 확정 |
| 12 | 원자료 커밋 방식, git 정리 | 미추적 파일 65개, 자동 생성 Office 잠금 파일 포함 | 커밋 범위와 제외 목록 |
| 13 | MDM 제외 폴더 추적 파일 24개 | 미확인 | 처리 방법 |
| 14 | 미확인 파일 | [Help, powershell 코드.txt, SmartBIS-Review.bat, AGENTS.md 와 AGENTS.txt 차이 | 용도 확인 |
| 15 | placeholder 값 | 60분 종료, 허용 기준, 시차 10분, 1.2분 추정 | 관측 후 조정 |
| 16 | 6번째 원자료 파일 | pattern&time_routeid&kecode_matching_ts.csv | 설계 2장 추가 여부 |
| 17 | 하위 폴더 경로 참조 23건 | 미정리 | 루트 기준 정리 |
| 18 | 클라이언트 앱·웹 DB 조회 점검 | client-android 일부만 확인 | 나머지 점검 범위 |
| 19 | lane B 대상 수 | 100 (문서별 차이) | 확정 |
| 20 | 21:00 종료 장치 | 코드 존재만 확인 | 실제 동작 확인 |
| 21 | 7536 route_id | 미확보. 첨단100 투입 | route_id 확보 시 별도 추가 |
| 22 | 클라이언트 3종 점검 | 3단계 결과 미수신. client-android 일부만 확인 | vehicle-number, 커스텀 MDM agent 위치와 결과 확인 |
| 23 | 배차 예시 라벨 | gi1·gi2 (gj 오타 의심), 7513 ld2 | 원본 의도 확인. 11일 배정은 관측으로 결정 |

---

## 13. 변경 이력 (rev6 → rev7)

1. **운영 DB 정의 재작성**: 서버에 DB가 없다는 사실을 반영해 0장과 12장을 고쳤다.
2. **순번**: 0 기준 확정, 음수 거부, 결측·비숫자·중복 거부. "1부터" 가정은 철회했다.
3. **도착 대상**: 최소값 기준에서 절대 짝수 기준으로 바꿨다. 홀수는 첫 방문 또는 예정 초과일 때만 허용한다(B안).
4. **접근 창**: 현재부터 현재+2까지. SQL 후보 3개, 차량당 1회 호출. K=2 다중 호출은 폐기했다.
5. **카탈로그 저장**: bus_route_stops 노선 단위 교체, 비응답 노선 보존으로 명시했다.
6. **정류장 마스터**: SAMPLE 2행 삭제(953→951).
7. **방문 구조 한계**: 자격 상실 후 재조회 문제를 명시했다.
8. **테스트 기준**: 2절의 검증 결과를 반영했다.

## 13-2. 변경 이력 (rev7 → rev8)

1. **입력 자료 절 복원**: rev6의 입력 자료(차량구분 33, 배차 29, 운행패턴 39, 7536·rb 제외)를 0-1절로 되살렸다. rev7에서 빠졌던 내용이다.
2. **차량 수 기준 정정**: 수집 대상 29대는 배차.csv 기준이다. bus_vehicles 행 수 29를 운행 차량 수로 쓴 기록을 정정했다. 87회 상한은 유지.
3. **판정 규칙**: 시각 허용 오차 ±5분 확정. seq·time·route 동등 비교.
4. **서버 배치**: web1 이전 결정 반영 (web2 홈페이지 전용, NAS 백업 1개월). rev7 §9 보류 해제.
5. **접근 경로**: 8443 → nginx → egov-backend:8080/api 일치 (저장소 기준). 실제 서버 설정은 미확인.
6. **미확정 유지**: DB 0행 상태, 클라이언트 3종 점검 결과(미수신), 배차 예시 라벨 오타 여부.

---

## 14. 서버 주소와 환경 변수 (2026-11-05 00:54 결정, rev8 추가)

### 14-1. 배경
- 2026-11-05 오늘 SmartBIS를 web1로 이전한다. 이전 시점의 외부 주소는 `https://jangseong.go.kr:8443`이다. 이 값은 지자체 요청과 이전 일정에 따라 정한 것이다.
- 주소는 나중에 IP로 바뀔 수 있다. 그래서 코드와 앱 곳곳에 주소를 박지 않고 `.env` 한 곳에서 관리한다.
- Codex는 지금까지 `192.168.1.5`만 알고 있었다. 이 값이 web1인지 개발 PC인지 확인되지 않았다. **이 값을 서버 주소로 가정하지 않는다.**
- web2는 홈페이지 시스템 전용이므로 SmartBIS 대상이 아니다.
- `/data/smartbis`는 저장소 텍스트 7,347개에서 0건이다. 서버 경로라는 근거가 없으므로 설정에 넣지 않는다.

### 14-2. 환경 변수 (키)

| 키 | 확정 값 | 용도 | 적용 위치 |
|---|---|---|---|
| `SMARTBIS_HOST` | `jangseong.go.kr` (오늘 이전 기준) | 서버 주소. IP 변경 시 이 값만 바꾼다 | compose.yaml, 백엔드 |
| `SMARTBIS_HTTPS_PORT` | `8443` | 외부 HTTPS 포트 | compose.yaml 포트 매핑 `8443:443` |
| `SMARTBIS_API_PATH` | `/api` | 백엔드 경로 | 클라이언트 기본 경로 |
| `PUBLIC_BASE_URL` | `https://${SMARTBIS_HOST}:${SMARTBIS_HTTPS_PORT}` | 파생 값 | 백엔드 환경 변수 |

- `.env`는 Git 추적에서 제외한다. 키 이름만 적은 `.env.example`은 커밋 대상이다.
- `.env` 값(실제 주소 포함)은 보고서에 쓰지 않는다. 키 이름만 기록한다.
- 백엔드(`egov-backend`)는 `PUBLIC_BASE_URL`을 환경 변수에서 읽는다. `application.properties`에는 기본값을 두지 않는다.
- Android 앱(`client-android/app/build.gradle.kts` 6행 부근)은 `gradle property` 또는 `local.properties`에서 주소를 읽어 `BuildConfig`에 넣는다.

### 14-3. 주소를 바꿀 때 함께 바뀌는 것
1. **Android 앱**: 주소가 APK 빌드 시점에 고정된다. IP로 바꾸면 앱을 다시 빌드해 배포해야 한다. 현장 기기 수가 많으면 부담이 크다. 그래서 앱에는 도메인을 넣고, 서버 IP가 바뀌면 DNS만 고치는 방식을 기본으로 한다.
2. **인증서**: 인증서는 주소(도메인 또는 IP)와 맞아야 한다. 주소가 바뀌면 인증서를 다시 발급받아야 할 수 있다. 현재 nginx 인증서 경로는 `/etc/nginx/certs/server.crt`, `server.key`다.
3. **nginx `server_name`**: 현재 `server/nginx/nginx.conf` 14행이 `localhost`이고, MDM 템플릿 5행은 `_`(default_server)다. 도메인 요청을 받도록 맞춰야 한다. 두 server 블록의 우선순위도 확인해야 한다.
4. **방화벽**: 목적지 IP는 web1의 실제 IP가 확정된 뒤 정한다.

### 14-4. 오늘 이전 전 확인 항목 (미확인)
| # | 항목 | 현재 상태 | 필요한 확인 |
|---|---|---|---|
| 1 | `jangseong.go.kr` DNS가 web1을 가리키는지 | 미확인 | 웹에서 조회 또는 DNS 설정 확인 |
| 2 | web1 IP | 미확인 | 실제 IP 확인 후 방화벽 목적지에 기록 |
| 3 | `jangseong.go.kr` 인증서 존재 | 미확인 | `/etc/nginx/certs/`에 파일이 있는지 (내용은 읽지 않음) |
| 4 | nginx `server_name` 도메인 반영 | 미수정 (localhost) | 수정 여부는 Codex 보고 후 결정 |
| 5 | MDM `default_server`와 충돌 | 미확인 | 두 server 블록 우선순위 확인 |
| 6 | 수집 스위치 4개 | OFF (2026-11-04 기준) | 이전 완료 전까지 OFF 유지. lane A가 05:15에 시작하므로 주의 |
| 7 | 192.168.1.5 용도 | 미확인 | 웹 장비 또는 개발 PC 여부 확인 |

### 14-5. 작업 순서 (설계서 확인 후 실행)
1. 이 절(14장)을 확인한다.
2. Codex 요청문을 보낸다. 범위는 **저장소 설정 파일 수정까지**다. 배포, 컨테이너 기동, nginx reload, 빌드, 커밋, DB 접속, TAGO 호출은 금지다.
3. Codex 결과로 `SMARTBIS_HOST`와 환경 변수 적용 범위를 확인한다.
4. web1 배포는 별도 승인으로 진행한다. 배포 전에 14-4의 1~3번을 확인한다.
5. 수집 ON은 G6~G8 확인과 실행 ID 승인 뒤에만 진행한다.

### 14-6. 이 절에서 바뀐 기존 항목
- §9 `PUBLIC_BASE_URL = https://jangseong.go.kr:8443 (설정값)`은 **환경 변수 파생 값**으로 바뀐다.
- §9 `서버 배치` 결정은 유지한다. web1 이전, web2 홈페이지 전용, NAS 백업 1개월.
- §12 #11(서버 이전 범위), #24(방화벽 목적지·PUBLIC_BASE_URL)는 14-4 확인 후 닫는다.


### 14-3 정정 (1번 항목 대체, 2026-11-05)
1. **Android 앱 재빌드**: 재빌드 비용은 MDM으로 새 APK를 배포해 처리한다. 다만 기기가 주소를 찾아 MDM 서버에 닿아야 새 APK를 받을 수 있다. 주소가 바뀐 뒤에도 기기가 MDM에 다시 닿는 경로는 도메인(DNS 레코드 변경)이 IP 변경보다 안전하다.

위 정정은 원래 14-3의 1번 항목을 대체한다.

### 14-7 Codex 정적 점검 결과 (2026-11-05, 별도 실행 없음)
- **변경 파일 7개**: .env(비운영 이름 키만, 값 비공개), .env.example, compose.yaml(포트 매핑 `${SMARTBIS_HTTPS_PORT}:443`, backend 환경 변수 2개), client-android/app/build.gradle.kts(gradle property → local.properties → 없으면 GradleException, 도메인 기본값 없음), application.properties 53행, application-web2-observation.properties 20행(기본값 제거), PublicBaseUrlConfiguration.java 22행(`@Value` 기본값 제거)
- **정적 검사만 수행**: Gradle/Kotlin 컴파일, 테스트, compose config, Spring 기동, placeholder 해석은 미실행
- **nginx 미변경**: server/nginx/nginx.conf:14 `server_name localhost` 유지. 도메인 반영은 결정 대기. MDM 기본 서버(`default_server`)와의 선택 정책 확인 필요. 현재 인증서 내용·SAN·만료일은 미확인
- **잔존 IP 하드코딩 (역할 미확정, 변경 안 함)**: client-android/vehicle-number/build.gradle.kts:6, client-android/build-vehicle-number-apk.ps1:2, deployment/headwind-mdm/build-custom-agent.sh:7, deployment/headwind-mdm/deploy-d-project-apks.ps1:3, deployment/headwind-mdm/ensure-wifi-portproxy.ps1:6. vehicle-number와 커스텀 agent는 14-5의 배포 전에 주소 처리 방식을 정해야 한다
- **테스트 기대값**: server/egov-backend/src/test/.../PublicBaseUrlConfigurationTest.java:8~9 이 새 환경 변수 규칙에 맞는지 재실행해서 확인해야 한다
- **git**: .env는 미추적, .gitignore:2로 무시됨. .env.example은 추적 대상. 미추적 Office 잠금 파일(~$API활용가이드...docx)이 새로 관측됨, 생성 주체 미확인
- **남은 확인 항목**: web1 실제 IP, jangseong.go.kr DNS, 인증서 도메인 일치, 실행 중 프로파일, 수집 스위치 OFF 상태 (14-4 표와 연결)


### 14-8 주소별 검증 순서 (2026-11-05 결정)
1. **1단계 로컬 테스트**: `SMARTBIS_HOST=192.168.1.5`, `SMARTBIS_HTTPS_PORT=8443`으로 빌드·기동·`/api` 응답을 확인한다. 이 주소가 어느 장비인지 응답 장비로 확인하고 기록한다. 수집 스위치 OFF 유지, TAGO 호출 0건.
2. **2단계 이전**: 1단계 성공 후 `SMARTBIS_HOST=jangseong.go.kr`로 바꾸고 백엔드·앱을 다시 빌드한다. web1 이전은 별도 승인.
3. **주소 3종 전환 검토**: `192.168.1.5`, `jangseong.go.kr`, `<서버 IP>` 중 어느 값이든 코드·설정에 주소를 박지 않고 `SMARTBIS_HOST` 교체만으로 전환할 수 있는지 점검. 항목: 백엔드 `PUBLIC_BASE_URL`, compose 포트, nginx `server_name`(현재 localhost 고정, 환경 변수 치환 방식 필요), 인증서 이름(IP SAN 또는 도메인), Android BuildConfig(빌드 시 주입, 재빌드 필요), MDM 기본 서버.
4. **설계서 대조**: rev8 전체(§0~§12, 14장)와 실제 코드의 차이를 파일:줄 단위로 표로 정리한다. 주요 대상: 순번 0기준, 도착 대상 규칙(짝수·홈수·접근 창), 호출 예산·예산 상한, 스케줄, 판정(±5분, seq·time·route 동등 중요도), 패턴·배차 적용 규칙, route_id NULL 처리, bus_vehicles 0행일 때 위치 저장 여부.


## 15. 클라이언트·MDM 로직 검토 범위 (누락 정정, 2026-11-05)

### 15-1. 정정
- 14-8까지의 Codex 요청문은 백엔드·설정 위주였고 클라이언트·MDM의 데이터 접근을 비웠다.
- §0의 "직접 호출 위치 백엔드 3곳뿐"는 2026-11-04 조사 기록이다. §12 #18(클라이언트 DB 조회 점검 일부만)와 #22(3종 점검 결과 미수신)와 서로 맞지 않으므로, 이 기록만으로 클라이언트의 TAGO 호출 없음을 확정하지 않는다.

### 15-2. 검토 대상
| 대상 | 경로 | 확인할 것 |
|---|---|---|
| client-android | `client-android/` | TAGO 호출, DB 접근(JDBC/Room/SQLite), 읽는 테이블, 서버 API 경로 |
| vehicle-number | `client-android/vehicle-number/` | 위와 동일 + 주소 하드코딩 |
| 커스텀 MDM agent | 위치 미확인 (`deployment/headwind-mdm/hmdm-android-v6.39` 여부 포함) | 기기 식별자·차량번호 매핑, 전송 데이터 |
| HMDM 서버 | `deployment/headwind-mdm/hmdm-server-v5.41.1` | 사용 DB명, SmartBIS DB와 연결 여부 |
| HMDM SQL | `deployment/headwind-mdm/sql` | smartbis 테이블 참조, 교차 스키마 |
| 백엔드 전체 | `server/egov-backend` | §14 주소, §8 판정, §3 카탈로그 동기화 |

### 15-3. 확인 항목
1. **TAGO 직접 호출**: TAGO 도메인, `apis.data.go.kr`, `serviceKey`를 클라이언트·MDM 전체에서 검색. 건수와 파일:줄
2. **이전 DB 참조**: `smartbis_db_old`, `smartbis_verify_*`, `copy2`, 이전 스키마 이름이 코드·설정·SQL에 있는지
3. **DB 간 상호 참조**: HMDM DB와 smartbis_db가 같은 인스턴스인지, dblink·FDW·다른 DB명 참조가 있는지, MDM이 SmartBIS 테이블(bus_*, arrival_*, route_*)을 읽고 쓰는지
4. **테이블·컨럼 참조**: 클라이언트가 읽는 테이블과 컨럼이 스키마 V001~V019에 있는지
5. **데이터 가정**: route_id NULL 처리, node_order 기준(0 또는 1), 시각 허용 오차 ±5분, seq·time·route 비교 방식
6. **수집 스위치와 무관한 호출**: 클라이언트가 백엔드를 거치치지 않고 직접 외부 API를 호출하는지
7. **주소**: 하드코딩 주소(192.168.1.5, jangseong.go.kr, 서버 IP)의 위치와 역할

### 15-4. 원칙
- 확인하지 못한 항목은 통과로 쓰지 않고 "미확인"으로 남긴다.
- 검색 패턴·검토 파일 수를 보고한다. 전체 로직 확인이라는 표현은 해당 범위를 읽었을 때만 쓴다.

## 14-9 수집 스위치 실효값 (2026-11-05, 정적 분석)

- **정정**: §1과 14장에서 "스위치 4개 OFF"라고 쓴 것은 틀렸다. 로컬 `.env`의 `TAGO_ENABLED`는 ON이고 compose.yaml:33에서 그 값이 전달된다. 위치·도착 개별 스위치 3개만 OFF다.
- **스위치 실효값**

| 키 | 읽는 위치 | 누락 시 | 현재 | 판정 |
|---|---|---|---|---|
| TAGO_ENABLED | application.properties:25 → tago.enabled | OFF | ON | 전역 스위치. 카탈로그 수동 경로는 이 값을 검사하지 않음 |
| TAGO_VEHICLE_LOCATION_ENABLED | application.properties:34 | OFF | OFF | 위치 수집 차단 |
| TAGO_ARRIVAL_ENABLED | application.properties:52 | OFF | OFF | 도착 수집 차단 |
| TAGO_LOCATION_OPERATIONS_ENABLED | tago-location-operations.properties:2 | OFF | 미설정 | 기본 lane 실행기·계획 스케줄러 미생성 |

- **compose 기본값 주의**: compose.yaml:43에서 도착 스위치의 미설정 기본값이 ON이다. .env에서 해당 치 값이 삭제되거나 비면 도착이 ON이 된다.
- **호출 차단 근거**
  - 위치: TagoVehicleLocationService.java:122 (전역 AND 위치 스위치), :150 (OFF·mock·실행기 부재 차단)
  - 도착: TagoArrivalEtaService.java:100, :121 (전역 AND 위치 AND 도착), :153 (차단)
  - 카탈로그 수동 경로: TagoCatalogController.java:30 (POST 수동 호출). TagoCatalogService.java:578 전송 직전에 TAGO_ENABLED를 검사하지 않음. **전역 스위치로는 이 경로를 막지 못 한다.**
- **미확인**: 활성 프로파일(별도 프로파일에 ON 재정의 있음), 셸 환경 변수, 실행 중 컨테이너. 따라서 실행 상태 전체에서 TAGO 호출 0건이라고 보장하지 않는다.
- **결론**: 기본 경로에서 위치·도착은 OFF이다. 전체 TAGO 호출은 정적 분석으로 막히지 않는다. 컨테이너 기동 전 활성 프로파일과 셸 환경을 확인한다.
- **로컬 .env**: SMARTBIS_HOST(.env:26)만 192.168.1.5로 변경. 나머지 키와 스위치는 변경 없음.