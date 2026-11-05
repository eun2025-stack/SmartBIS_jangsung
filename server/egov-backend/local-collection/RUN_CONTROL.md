# 로컬 수집 기동과 실행 ID

이 절차는 로컬 복사본 `smartbis_db_copy2` 전용이다. 아래 명령은 구성 검토 후 별도 승인으로 실행한다. 스키마 적용·backend 기동·수집 승인은 각각 별도 단계이며 실제 실행 증거는 작업별 보고서를 따른다.

## 1. 실행 준비 — 파일만 생성

저장소 루트에서 실행한다. 운영 `.env`는 사용하지 않는다. 이미 실행 ID가 있으면 덮어쓰지 않으며, 새 실행은 명시적 `--new-run`으로 준비한다.

```powershell
& 'C:\Program Files\Git\usr\bin\timeout.exe' 30 python -B -X utf8 tools/local_collection_control.py prepare
```

`.tmp/local-collection-control/run.json`에 UUID, 준비 시각, 커밋 SHA, 비밀값을 제외한 설정 SHA-256을 기록한다. `enabled`는 기본 `off`다. 기존 실행 설명은 새 실행 준비 시 `history/`에 보존한다. 실제 수집 시작 시각은 승인 스위치를 backend가 처음 확인할 때 `collection_runs.started_at`에 기록한다.

## 2. 복사본 백업 및 별도 스키마 적용

먼저 복사본의 pg_dump 백업을 만들고 PGDMP 서명·pg_restore 목차·파일 크기·SHA-256을 검증한다. 검증 영수증 JSON은 database, host, backup, size, sha256, pg_restore_list_ok 필드를 가진다. 운영 DB 백업으로 대체하지 않는다.

```powershell
& 'C:\Program Files\Git\usr\bin\timeout.exe' 30 python -B -X utf8 tools/local_collection_control.py migrate --list
& 'C:\Program Files\Git\usr\bin\timeout.exe' 110 python -B -X utf8 tools/local_collection_control.py migrate --backup-receipt .tmp/offstart-backup-result.json
```

대상은 `local-collection/run-schema.sql` 한 개다. V020과 임의 SQL은 거부한다. 백업 무결성·복사본·OFF 스위치를 확인한 뒤 단일 트랜잭션으로 적용하고 사후 스키마를 확인한다. 원자료를 읽거나 적재하지 않는다. 적용 이력은 control 폴더 schema-history.jsonl에 기록한다.

## 3. backend만 기동 — 별도 승인 후

```powershell
& 'C:\Program Files\Git\usr\bin\timeout.exe' 1250 python -B -X utf8 tools/local_collection_control.py up
```

이 명령은 먼저 스위치를 끄고 복사본의 스키마를 SELECT로만 확인한다. 스키마가 없으면 별도 migrate를 요구하며 기동을 중단한다. 스키마가 준비된 경우 JAR를 빌드하고 **한 번의 compose up**으로 전용 backend만 기동한다. 운영용 backend·DB 컨테이너는 변경하지 않는다. 기존 로컬 PostgreSQL 네트워크 namespace를 공유하므로 별도 호스트 DB 포트 공개가 필요 없다. PostgreSQL이 먼저 실행 중이어야 한다.

내부 compose 인자:

```text
podman compose --env-file .tmp/local-collection.env -p smartbis-local-collection -f .tmp/local-collection-control/compose.local.yaml --profile local-collection up -d --no-deps --force-recreate --build local-collection-backend
```

명령 전체에는 timeout 600초를 적용한다. Maven 빌드에도 별도로 600초를 적용한다. 운영 `.env` 자동 로드를 피하려고 `--env-file`을 반드시 명시한다. 이미지 태그는 `local-run-control`이며 운영 이미지 태그를 사용하지 않는다.

Compose는 비활성 서비스의 변수도 보간하므로 원본 전체를 직접 전달하지 않는다. `up`은 compose.yaml의 local-collection-backend 블록만 추출하고 상대 경로를 절대 경로로 바꿔 compose.local.yaml을 만든다. 다른 서비스나 운영 필수 변수를 전달하지 않는다. 이 파일은 별도 서비스 정의를 수동 유지하는 대신 원본의 로컬 블록에서 생성한다.

backend 자체의 관리자 인증 초기화에 필요한 값은 전용 backend-auth.env에 무작위로 생성한다. 운영 계정·설정을 읽거나 인증을 해제하지 않는다. 해당 파일은 .tmp 아래 git 무시 대상이며 값은 로그에 출력하지 않는다. 기동 로그의 비공개 값과 전체 주소도 출력 전에 가린다.

backend는 로컬 프로필의 `LocalCollectionControl`을 준비한 뒤 `ready.json`을 쓴다. 스위치가 꺼져 있으면 위치·도착 스케줄러가 조기 반환하며 HTTP 직전에도 차단한다. 로컬 카탈로그 HTTP는 스위치와 무관하게 차단한다. 파일/실행 ID/DB 메타데이터가 잘못되면 호출 허용 상태가 되지 않는다.

## 4. 사용자 승인 — G6~G8 확인 후에만

실행하지 않은 명령 예시다. `<실행_UUID>`는 준비 결과의 UUID로 바꾼다.

```powershell
& 'C:\Program Files\Git\usr\bin\timeout.exe' 30 python -B -X utf8 tools/local_collection_control.py enable --run-id '<실행_UUID>' --confirm-g6-g8
```

backend 준비 마커의 실행 ID·커밋·설정 해시가 모두 맞아야 스위치를 켠다. 커밋·설정이 바뀌면 새 실행을 준비해야 한다. 이 명령은 승인된 수집 시작 명령이므로 준비 단계에서 실행하지 않는다.

호출 중지 파일 명령:

```powershell
& 'C:\Program Files\Git\usr\bin\timeout.exe' 30 python -B -X utf8 tools/local_collection_control.py disable
```

이미 전송된 요청의 취소를 의미하지는 않는다. 일요일 21시 종료와 야간 위치 호출 차단은 기존 코드가 별도로 유지한다.

## 5. 저장·분석 범위

- `collection_runs`는 실행 메타데이터를 보관한다. 프로세스가 재시작돼도 같은 실행 UUID를 유지하며 `collection_process_runs.run_id`는 프로세스마다 별도로 생성한다.
- 위치·도착 최종 응답·정지 구간·프로세스 행에 `collection_run_id`를 저장한다.
- 기존 행은 삭제하지 않고 예약 UUID `00000000-0000-0000-0000-000000000000`으로 표시한다. 이 UUID의 메타데이터는 `pre_run`, `archived=true`다. 기존 행을 현재 실행 데이터로 재사용하지 않는다.
- 스키마는 Flyway/V020이 아닌 로컬 전용 SQL이다. 적용은 백업 검증 후 별도 migrate 명령으로만 수행한다.
- `tools/collection_run_analysis.py --collection-run-id UUID --output .tmp/run-summary.json`은 해당 실행만 읽고 보간·정지 구간을 제외한 기본 건수를 계산한다. 읽기 전용이며 실제 차량번호를 출력하지 않는다.
- 기존 패턴 판정 도구 `tools/observation_compare.py`에도 `--collection-run-id UUID`를 추가했다. 지정 시 복사본 DB·지정 로컬 컨테이너만 허용하며 해당 실행/실제 관측만 읽고 정지 구간을 제외한다. CSV도 이 컬럼이 있어야 한다. 배차 예시를 실제 배차로 확정하는 규칙은 바꾸지 않았다.

## 6. 검증 한계

mock/인메모리 테스트는 실제 PostgreSQL 마이그레이션·Podman 기동 검증을 대체하지 않는다. H2에서 PostgreSQL 타입 별칭과 ON CONFLICT 문법 일부를 호환 처리해 보관 표시·행 보존을 확인했다. 실제 기동/DB 적용과 호출 0건 관측은 승인 후 확인할 항목이다.
