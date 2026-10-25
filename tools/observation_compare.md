# TAGO 관측 / 배차 패턴 비교

Python 3.10 이상 표준 라이브러리만 사용한다. TAGO HTTP 호출 및 DB 쓰기는 하지 않는다.
DB 모드는 psql의 `BEGIN TRANSACTION READ ONLY` 안에서 SELECT 결과를 CSV로 출력하고 ROLLBACK한다.
파일은 `--output-dir`에만 생성한다. 원본 CSV, Java 수집기, DB 배차를 변경하지 않는다.

## 실행 (Git Bash, 프로젝트 루트)

```bash
cd /d/PROJECT/SmartBIS_jangsung
python -X utf8 tools/observation_compare.py \
  --patterns '군민운수 운행관리시스템/관내 운행 패턴.csv' '군민운수 운행관리시스템/광주 운행 패턴.csv' \
  --mapping '군민운수 운행관리시스템/routeid&kecode_matching_ts.csv' \
  --dispatch '군민운수 운행관리시스템/관내배차.csv' '군민운수 운행관리시스템/광주배차.csv' \
  --start-date 2026-10-16 --end-date 2026-10-22 \
  --podman-container smartbis_jangsung-postgres-db-1 \
  --output-dir reports/observation/2026-10-16_22
```

배차표는 **2026년 8월 예시이며 10월 실배차가 아니다**. `--assignment-month` 기본값은 `2026-08`이다.
기본 실행은 10월 배정 일치율을 계산하지 않으며 `배차 미확정`으로 표시한다.
관측 기반 후보·추천만 생성하고, 배정 기반 expected_results/mismatches는 헤더만 출력한다.
실제 10월 배차 파일을 확보한 경우에만 해당 파일로 `--dispatch`를 바꾸고
`--assignment-month 2026-10 --dispatch-confirmed`를 함께 지정한다. 한 옵션만으로는 계산하지 않는다.

불완전한 시각/노선 짝이 있으면 기본 실행은 중단한다. 현재 원본에서 `ud5 20:00` 공란 항목은 제거됐고,
`js9 01:10`도 `11:10`으로 수정돼 있다. 스크립트는 원본을 수정하지 않고 재확인 내용을 summary에 기록한다.
나머지 항목만 진단하려면 `--skip-incomplete`를 추가하고 출력 경로를
`reports/observation/2026-10-16_22-diagnostic`으로 지정한다. 제외 목록에 기록되므로 정식 판정과 구분한다.
현재 원본의 진단 실행에는 공란 항목이 없으므로 ud5 제외 기록도 없다. 공란을 임의로 복원하지 않는다.
비어 있지 않은 keycode의 매핑 실패·모호한 대응은 진단 모드에서도 모두 출력하고 중단한다.

DB 없이 CSV 내보내기를 비교하려면 `--podman-container ...` 대신
`--observations-csv /path/to/observations.csv`를 사용한다.
필수 헤더는 `vehicle_number,route_id,observed_at,node_order`이다. node_order는 빈 값 또는 0도 허용한다.
source 컬럼이 있으면 TAGO_OBSERVATION만 사용하며, 없으면 내보낸 파일 전체를 관측 데이터로 간주한다.
`--include-tago`를 지정해야 TAGO 행도 포함하며 summary에 `참고용`으로 표시한다.
DB 모드도 기본 TAGO 제외 조건을 SQL에 적용한다. 10월 14~15일 TAGO 행을 기본 관측으로 섞지 않는다.
로컬 psql은 `--psql --db-host HOST --db-port 5432 --db-user USER --db-name DB`로 선택한다.
비밀번호는 명령 인자로 받지 않으며 psql의 PGPASSFILE/PGPASSWORD 설정을 사용한다.

## 입력과 판정 기준

- 패턴 CSV: `patt,time,route,...` 또는 `div,patt,time,route,...`. 관내/100번 모두 동일 파서로 처리한다.
- route 칸은 OBE_NM(keycode) 또는 대응표에 존재하는 ROUTE_ID를 허용한다. TBS 접두사는 TSB로 정규화한다.
- 배차표는 차량 4자리와 날짜 열. 반복 구획 헤더, `7521[터]` 같은 차량 표기도 처리한다.
- 날짜가 1~31인 헤더는 `--assignment-month YYYY-MM`에 속한다. 기본값은 `2026-08`이다.
  ISO 날짜(YYYY-MM-DD) 헤더도 허용한다. 여러 달 비교는 ISO 날짜 열이나 월별 실행을 사용한다.
- `hjs`만 적힌 셀은 휴차, `hjs1~8`은 실제 패턴이다. 휴차는 일치율 판정 대상이 아니다.
- 같은 차량/날짜의 상충하는 배차는 중단한다. 다른 전체 차량번호가 끝 4자리를 공유해도 중단한다.
- 100번 계열은 대응표 ROUTE_NM이 `100`으로 끝나는 모든 ROUTE_ID를 TBS→TSB 정규화 후 집계한다.
  17개가 아니면 집계 목록을 출력하고 중단한다. 노선 번호 목록을 코드에 하드코딩하지 않는다.
- 관내 노선 범위는 대응표 **전체 집합에서 100번 계열 17개를 뺀 집합**이다. 접두사 필터는 없다.
  차량번호나 어느 배차표에 들어 있는지는 관측의 노선 분류 기준으로 사용하지 않는다.
- js7/hjs7의 20:30 TSB342000117도 100번 패턴에서 판정한다.
  관내 패턴에 들어간 100번 항목만 `범위 밖 노선(관내 패턴의 100번 계열 항목)`으로 기록한다.
- 시각 오기는 추정 보정하지 않는다. js9의 01:10은 그대로 판정하고 시간 역전 경고를 출력한다.
- 시간대는 KST. 오프셋 없는 관측 시각은 KST로 해석한다. ±15분 경계를 포함하며 자정도 처리한다.
- 같은 시간대에 기대 노선과 다른 노선이 함께 있으면 일치가 우선한다.
  다른 노선만 있으면 불일치, 해당 패턴 계열의 관측이 없으면 미관측이다.
- 실배차 확정 입력에서 같은 차량에 같은 패턴이 연속 날짜에 배정되고 같은 기대 항목이 양일 모두 미관측이면
  두 번째 날부터 불일치로 승격한다. 전날 또는 당일 전체 관측 데이터가 없으면 승격하지 않는다.
  첫날/관측 공백을 자동으로 운행 불일치로 간주하지 않기 위한 보수적인 조건이다.
  DB 모드는 전날 및 자정 경계 자료도 함께 읽는다. CSV 모드도 전날 관측을 포함해야 한다.
- 일치율은 일치/(일치+불일치), 보류 미관측은 분모에서 제외한다. 판정 대상 0이면 공란이다.
  `matched_expected_pct`(전체 기대 대비 일치율)와 `evidence_coverage_pct`도 함께 제공한다.
- 관측이 전혀 없는 날짜는 판정·추천을 하지 않고 `관측 없음`으로 표시한다. 일치율은 공란이다.
- 평일에는 관내 + js/gj, 휴일에는 관내 + hjs/hgj만 후보·날짜별 기대표에 들어간다.
  관내 ud/ld는 항상 후보다. 실제 차량의 소속으로 후보를 제한하지 않는다.
- 추천은 전체 기대 대비 일치율, 일치 건수,
  불일치 적은 순, 패턴 코드 사전순으로 결정한다. 동점 후보와 근거 부족도 표시하며 자동 배정하지 않는다.

## 결과 (CSV는 모두 UTF-8 BOM)

| 파일 | 내용 |
|---|---|
| vehicle_daily.csv | 날짜별 배차 미확정·관측 없음 / 확정 입력인 경우에만 배정 일치율 |
| pattern_candidates.csv | 모든 후보 패턴별 점수 |
| recommendations.csv | 차량·날짜별 추천 1개와 동점·근거 여부 |
| unassigned.csv | 미배정 또는 실배차 미확정·예시표 미등재를 상태로 구분 |
| mismatches.csv | 배정된 패턴의 불일치 기대 항목 |
| expected_results.csv | 배정된 패턴의 모든 시각별 판정 |
| expected_schedule.csv | 날짜별 해당 평일/휴일 후보 기대표 (배정 확정과 별개) |
| excluded_expectations.csv | 날짜별 범위 밖·진단 모드 불완전 항목 |
| day_summary.csv | 날짜별 평일/휴일, 관측 상태·행 수·차량 수·source별 건수 |
| hundred_routes.csv | 대응표에서 자동 집계한 100번 계열 목록 |
| calendar_assignment_conflicts.csv | 배차표의 날짜/패턴 상충 목록 |
| summary.txt | 전체 요약과 차량별 일치율, 입력 경고 |

단위 테스트:

```bash
python -X utf8 -m unittest discover -s tools -p test_observation_compare.py -v
```

주의: 노선 출발 시각과 ±15분의 노선 관측을 비교하는 방식이지, 기점 출발을 검증하는 방식은 아니다.
node_order가 빈 값/0이어도 보존하며 출발·방향 판정에 사용하지 않는다.

## 관측 누락 보간

`--interpolation-rules tools/config/interpolation_rules.json`에서 누락 순번 최대
`max_gap_stops=5`, 관측 사이 시간 상한 `max_gap_minutes=30`을 설정한다.
같은 차량의 시간순으로 인접한 실제 관측만 비교한다. 같은 노선에서 순번이 증가하고
차이가 2 이상이면 사이 순번을 선형 시각으로 생성한다. 3→7이면 4·5·6을 생성한다.
최대 개수는 끝점 차이가 아니라 생성할 중간 순번 수이며, 상한과 같은 값은 허용한다.
즉 node_order 차이 - 1이 기준이다. 1→7은 누락 5개로 허용, 1→8은 6개로 미보간,
1→11은 9개로 미보간한다. 여러 구간의 누락 개수를 합산해 제한하지 않는다.
노선/source 변경, 순번 감소·공란, 동일 시각, 상한 초과는 보간하지 않고
`unverified_segments.csv`에 `구간 미확인`과 사유를 기록한다.

`observations_with_interpolation.csv`는 원본 관측 객체의 값을 보존한 실제 행
(`interpolated=false`)과 추정 통과 행(`true`)을 함께 출력한다. 입력 CSV/DB는 수정하지 않는다.
출력의 vehicle은 기존 비교 도구와 동일하게 차량번호 끝 4자리다.
이 파일은 진단용 파생 결과이며, 원본 입력 파일을 대체하지 않는다.
일치·불일치·미관측, 추천 점수, 관측일, 행 수는 실제 행만 사용한다.
보간 행을 다시 끝점으로 사용하지 않는다. 이 기능은 순번 통과를 추정할 뿐 실제 정차를 입증하지 않는다.

### 노선·운행 구간 수집률

출발 기준은 TAGO 노선 정류장 순번 최소값 또는 운수사 시간표의 출발 기점이다.
관측된 순번 중 최소값을 노선 기점으로 추정하지 않는다.
장성터미널 등 특정 정류장 관측 유무는 창 생성 조건이 아니다.
실제 기점 최소 순번 관측에 node_id/nodeid가 없으면 summary에
`기점 좌표 검토 필요`와 날짜·노선·차량·순번·행 수를 기록한다.
이는 좌표 검토용 진단이며 창/후보를 제외하거나 운행 판정을 바꾸지 않는다.

`--route-stops`로 로컬 정류소 CSV(route_id,nodeord_raw)를 지정한다. 기본 자료는
장성교통 노선도/TAGO_장성군_노선별_경유정류소_20261002.csv이며 API를 호출하지 않는다.
`route_collection_rates.csv`와 summary에 노선/일자 및 차량/노선/운행 구간별 수집률을 기록한다.
분자는 실제 관측된 서로 다른 유효 순번 수, 분모는 해당 노선 카탈로그의 서로 다른 순번 수다.
중복·보간은 분자에 포함하지 않으며, 카탈로그에 없는 순번 수를 따로 기록한다.
분모가 없는 노선도 제외하지 않고 수집률 공란과 미확인 상태를 기록한다.
운행 구간은 날짜/노선/source 변경 또는 순번 감소로 분리한 관측 기반 추정 구간이다.
운행 ID 원본이 없어 실제 배차 운행을 확정하는 값은 아니다. 수집률로 노선이나 후보를 제외하지 않는다.

## 휴일 달력과 8월 검사 모드

기본 `tools/config/holidays_2026.csv`는 UTF-8 BOM, `date,name,kind` 형식이며 제공된 20개 공휴일을 담는다.
토·일은 datetime.weekday로, 법정·연휴·대체·선거 등은 CSV의 date 객체 집합 조회로 휴일 판정한다.
종류별 날짜를 코드에 넣지 않는다. 대체공휴일·선거일을 변경하려면 CSV 행을 수정한다.
다른 달력은 `--holiday-calendar PATH`로 받는다.

```bash
python -X utf8 tools/observation_compare.py \
  --schedule-check-only --assignment-month 2026-08 \
  --dispatch '군민운수 운행관리시스템/관내배차.csv' '군민운수 운행관리시스템/광주배차.csv' \
  --output-dir reports/observation/2026-08-schedule-check
```

검사 모드는 DB/관측을 읽지 않고 8월 1~31일의 배차/요일 상충만 검사한다.
평일 hjs/hgj 또는 휴일 js/gj 셀이 있으면 `배차 상충`으로 기록하고 종료 코드 2로 중단한다.
같은 차량/날짜의 서로 다른 배차도 항상 중단한다. 검사 결과는 summary와 상충 CSV만 생성한다.
8월 예시표를 10월 실배차로 치환하거나 주간 순환을 추정하지 않는다.
