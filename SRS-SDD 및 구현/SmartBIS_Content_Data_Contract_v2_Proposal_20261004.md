# SmartBIS 콘텐츠 데이터 계약 v2 확정 및 구현 기록

- 작성일: 2026-10-04
- 목적: 콘텐츠 제공업체가 전달할 JSON/파일 규칙과 서버 내부 저장·표출 모델을 구분해, 콘텐츠 유형이 늘어도 임시 필드가 누적되지 않도록 한다.
- 상태: **v2 계약 확정·구현됨**. 기존 v1 JSON도 호환 처리한다. 2026-10-04 V010 additive migration 적용, Node watcher/API/Android 반영 및 실제 v2 배치 통합검증 완료.

## 1. 저장 위치와 책임 경계

```text
관리업체 → 프로젝트 루트 upload-store/YYYY-MM-DD/BATCH_ID
          contents.json + 첨부파일 전송, 완료 후 .ready
                    ↓ Node watcher 검증·DB 적재
시스템 내부 → server/content-store/YYYY-MM-DD/BATCH_ID
              정규화한 영구 첨부파일 보관
```

- `upload-store`는 외부 관리업체와 합의할 단순 인계 위치다. 업체는 내부 보관소 구조를 알 필요가 없다.
- `server/content-store`는 시스템 소유의 영구 미디어 저장소다. watcher는 쓰기, Spring Boot는 읽기 전용이다. 컨테이너 경로 `/app/content-store`는 기존 DB 경로 호환을 위해 유지한다.
- `.ready`는 전송 완료 신호다. watcher는 폴링 간격으로 완료 여부를 추측하지 않는다.
- 시스템 이전/복구는 미디어 디렉터리만이 아니라 `server/content-store`와 PostgreSQL 백업을 함께 보관·복원한다.

## 2. 확정된 계약

업무상 무엇을 알리는지와 화면에서 어떻게 보여주는지를 분리한다. 하나의 `content_type`에 두 의미를 섞으면 포스터 공지, 텍스트 행사, 카드뉴스의 구분이 불명확해진다.

### 배치 필드

| 필드 | 규칙 | 의미 |
|---|---|---|
| `schema_version` | 필수, 신규 계약 `2.0` | 계약 버전 |
| `batch_id` | 필수, 배치 폴더명과 일치 | 인입 배치 식별자 |
| `contents` | 필수, 1개 이상 | 한 배치 안의 콘텐츠 목록 |

### 콘텐츠 필드

| 필드 | 규칙 | 의미 |
|---|---|---|
| `content_id` | 필수, 전역 고유 | DB UPSERT 키. 수정 재전송은 같은 ID, 새로운 자료는 새 ID |
| `category` | 필수: `NOTICE` 또는 `EVENT` | 업무 분류. 재난은 승인된 재난 API/시스템 출처로 별도 취급 |
| `presentation_type` | 필수: `POSTER_TEXT`, `TEXT`, `CARD_NEWS`, `IMAGE`, `VIDEO` | 화면에 표현하는 형식 |
| `title` | 필수 | 운영 목록과 화면 제목 |
| `content` | 텍스트 형식에서 필수 | 본문 전문. 매체 전용 형식은 빈 문자열 허용 |
| `display_start_date`, `display_end_date` | 선택/null 가능 | 게시 기간. 둘 다 있으면 종료일은 시작일보다 빠를 수 없음 |
| `target_regions` | 선택/null 가능 | 제한 없음은 `null`; 제한 시 시스템 지역명 배열(현행 필터 계약) |
| `template` | 필수 | 화면 템플릿 식별자. 허용 목록으로 관리 |
| `assets` | 형식별 개수 규칙 | 첨부파일 목록 및 화면 내 역할·순서 |

`category`와 `presentation_type`을 조합한다. 예를 들어 행사 포스터는 `EVENT` + `POSTER_TEXT`다. 재난문자는 업체가 일반 게시물처럼 만들어 보내지 않으며, 실서비스 승인 뒤 재난 API/운영 수명주기로 별도 수집한다. 단, 최종 표출 엔진과 디자인 정책은 일반 콘텐츠와 공통으로 활용할 수 있다.

### 첨부파일 규칙

각 `assets` 항목은 `role`, `file_name`, `sequence`를 갖는다. 역할은 `POSTER`, `IMAGE`, `VIDEO`다. 파일명은 해당 배치 디렉터리 안에서 유일한 basename이어야 하고 경로 구분자, 절대경로, `..`는 허용하지 않는다. 업체에 체크섬 계산을 의무화하지 않고 watcher가 안정화 이후 크기와 SHA-256을 계산해 감사/재처리 기록에 남긴다.

| `presentation_type` | 첨부/본문 검증 |
|---|---|
| `POSTER_TEXT` | 본문 필수, `POSTER` 1개 |
| `TEXT` | 본문 필수, 첨부 없음 |
| `CARD_NEWS` | `IMAGE` 2개 이상, `sequence` 순서대로 전체화면 순차 표출 |
| `IMAGE` | `IMAGE` 1개 |
| `VIDEO` | `VIDEO` 1개, 재생 종료 이벤트가 다음 표출을 시작 |

JSON이 참조하는 모든 파일이 배치 안에 있어야 하고 안정화되어야 한다. 선언하지 않은 파일은 자동 게시하지 않는다. 부분 성공을 피하기 위해 배치의 검증 실패 시 해당 배치는 DB에 부분 반영하지 않고 원본과 오류 이력을 보존한다.

### 예시

```json
{
  "schema_version": "2.0",
  "batch_id": "BATCH_20261004_0001",
  "contents": [
    {
      "content_id": "NOTICE_20261004_001",
      "category": "NOTICE",
      "presentation_type": "POSTER_TEXT",
      "title": "장성 가을 축제 안내",
      "content": "축제 일정과 이용 안내 본문",
      "display_start_date": "2026-10-10",
      "display_end_date": "2026-10-20",
      "target_regions": ["JANGSEONG_EUP"],
      "template": "EVENT_POSTER_SPLIT",
      "assets": [
        {"role": "POSTER", "file_name": "autumn-festival.jpg", "sequence": 1}
      ]
    },
    {
      "content_id": "EVENT_20261004_002",
      "category": "EVENT",
      "presentation_type": "CARD_NEWS",
      "title": "행사 이용 방법",
      "content": "",
      "display_start_date": null,
      "display_end_date": null,
      "target_regions": null,
      "template": "FULLSCREEN_CARD",
      "assets": [
        {"role": "IMAGE", "file_name": "guide-01.jpg", "sequence": 1},
        {"role": "IMAGE", "file_name": "guide-02.jpg", "sequence": 2}
      ]
    }
  ]
}
```

업체는 JSON과 참조 파일 전송을 마친 뒤 `.ready`를 마지막에 생성한다. JSON에는 주석을 넣지 않는다.

## 3. DB/API 구현 내용

1. 기존 `contents` 행과 `content_id`를 보존했다. 업무 분류/표현 형식을 추가하고 기존 `content_type`은 호환 필드로 유지한다. 기존 템플릿 값에 따른 category backfill은 `행사`/`event`면 EVENT, 그 외 NOTICE로 초기 분류했으므로 실제 운영 데이터는 대시보드에서 검토할 수 있다.
2. `content_assets` 하위 테이블을 추가했다: `content_id` FK, 역할, 순번, 원본 파일명, content root 기준 상대 저장경로, MIME, 크기, SHA-256. 콘텐츠별 순번 유일성을 제약한다. 기존 유효 미디어의 크기/해시는 임의 계산값으로 채우지 않고 NULL로 보존한다.
3. 신규 `content_assets.file_path`는 content root 기준 상대경로로 저장한다. API는 `CONTENT_ROOT` 아래로 안전하게 resolve한다. 기존 `contents.source_path` 절대경로는 호환 필드로 유지한다.
4. 콘텐츠 목록 API는 기존 필드를 유지하며 `category`, `presentationType`, 순서가 보장된 `assets`를 추가했다. 기존 단일 `mediaUrl`도 제공한다. 개별 첨부는 `/api/v1/media/{contentId}/assets/{sequence}`로 내려받는다.
5. 대시보드 데이터 관리 API의 콘텐츠 행에 업무 분류·표현 형식·첨부 수량이 제공되며 게시 중지/제외 상태 override는 콘텐츠와 파일을 보존한다. 정책 테이블의 기존 `CARD` 정책과 연결하기 위해 `CARD_NEWS`, `POSTER_TEXT`, `TEXT`는 legacy `content_type=CARD`로 매핑한다.
6. Android는 API의 `presentationType`과 `assets` 순서를 기준으로 포스터+본문, 텍스트, 카드 슬라이드, 단일 이미지, 동영상 및 재난을 처리한다. 텍스트/이미지는 서버 정책 시간, 동영상은 재생 종료를 기준으로 전환한다.

## 4. 적용 및 통합검증 결과

1. **저장 위치 분리**: `server/content-store/YYYY-MM-DD/BATCH_ID`로 이동 완료. 구/신 위치 파일 해시 일치, 기존 API HTTP 200, backend health UP 확인.
2. **계약 고정**: 본 문서의 필드·허용값·첨부 규칙을 v2로 확정했다. v1 입력은 호환 유지.
3. **DB additive migration**: `V010__content_data_contract_v2.sql`을 실제 DB에 트랜잭션 적용했다. `contents`에 category/presentation_type 추가, `content_assets` 생성, 기존 유효 레거시 파일 참조 이관. 기존 8개 콘텐츠 ID/행은 보존.
4. **Watcher v1/v2**: schema_version 1.0/2.0 검증, batch_id/폴더 일치, 첨부 basename/순번/역할 검증, 파일 안정화·SHA-256·크기·MIME 계산, content-store 복사, contents UPSERT와 assets 교체를 DB transaction으로 적용. 새 입력의 파일 경로는 상대경로.
5. **Backend/API**: 목록·데이터 관리·표출 API에 category/presentationType/assets 순서를 추가하고, 자산 목록 및 순번별 다운로드를 제공. 기존 단일 mediaUrl과 경로도 지원.
6. **Android**: `presentationType`/`assets`를 읽어 CARD_NEWS 순서와 파일별 전환을 사용. 영상은 재생 종료 이벤트로 넘어가며 텍스트/이미지는 정책 시간에 따라 전환.
7. **실제 배치시험**: `BATCH_V2_CONTRACT_20261004`로 POSTER_TEXT, TEXT, CARD_NEWS(2개 이미지), IMAGE, VIDEO 총 5개 행을 한 배치로 처리했다. PostgreSQL에서 category/type/첨부 역할·순번·SHA-256 기록 확인, backend 목록 API에서 전체 자산 목록 반환, 카드뉴스 2번 자산 HTTP 200/image/jpeg/6,436,410 bytes 확인. watcher health는 DB UP/RUNNING, 처리 중 아님.

### 검증 한계와 운영상 주의

- 카드뉴스의 두 시험 파일은 배관과 순번을 확인하기 위해 같은 JPEG를 복사해 사용했다. 서로 다른 슬라이드의 시각적 교체/실제 24인치 단말 표출은 별도 기기 확인 대상이다.
- 테스트 콘텐츠는 `content_schedules`에 활성 예약을 만들지 않아 `/display/next`의 표출 후보가 아니다. 운영 콘텐츠 순환에 끼어들지 않도록 했다. 테스트 콘텐츠 행과 저장 파일은 재현 가능한 검증자료로 유지한다.
- 과거 DB의 단일 파일 참조 중 `/app/content-store/...`로 안전하게 해석되는 것은 `content_assets`에 연결했다. upload-store JSON 경로처럼 content root 밖을 가리키는 레거시 행은 첨부로 등록하지 않았고 원본 `contents` 행을 유지했다. 해당 행은 과거 미디어 API에서 400이 날 수 있으므로 실제 파일 매핑 확인 후 별도 정리 대상이다.
- 파일시스템 복사와 PostgreSQL transaction은 단일 원자 작업이 아니다. DB 실패 시 content-store에 orphan이 남을 가능성은 기록했으며 자동 orphan 정리 기능은 아직 없다.

### 2026-10-04 축제 콘텐츠 교체 및 실제 표출 설정

프로젝트의 업로드 폴더에 있던 포스터 이미지 5개, 동영상 5개, 카드뉴스 폴더 2개(4장/3장)를 새 v2 배치 `BATCH_FESTIVAL_20261004_001`로 모아 전송했다. 파일은 수집기 계약상 배치 폴더 바로 아래에 두고, 중복되지 않는 파일명과 자산 sequence로 원래 슬라이드 순서를 표현했다. 행사 날짜·장소 등 원본에 확인되지 않는 사실은 만들지 않고, 포스터에서 세부 내용을 확인하도록 짧은 안내문을 사용했다.

- 실제 DB에 새 콘텐츠 12행과 자산 20개를 저장했다. (포스터 5 + 영상 5 + 카드뉴스 이미지 7)
- 변경 전 PostgreSQL 전체 dump를 `%TEMP%\smartbis-before-festival-20261004-151820.dump`에 보관했다.
- 기존 DB의 13개 콘텐츠 행을 교체하고 연결된 기존 표출 스케줄 4건을 제거했다. `content_assets`는 FK cascade로 기존 콘텐츠와 함께 제거됐다.
- 신규 12건 모두 `content_schedules.active=true`, `min_display_seconds=10`으로 설정했다. DB 최종 콘텐츠 및 활성 스케줄은 각각 12건이다.
- `SAMPLE-001`의 `/api/v1/display/next` 실제 호출에서 `FESTIVAL_CARDNEWS_20261004_001`, `presentationType=CARD_NEWS`, `displaySeconds=10`, 순서 1~4 자산이 반환됐다. 포스터/영상/카드뉴스 대표 자산 URL은 HTTP 200.
- 기존 upload-store 입력 원본과 이전 미디어 파일은 삭제하지 않았다. DB에서 더 이상 참조하지 않는 과거 파일은 백업 및 디스크 회수 정책 확정 전까지 보존한다.

Android 장치에서 12개 콘텐츠가 장시간 순환하고 실제 화면에서 가독성·전환 간격을 확인하는 시험은 별도다. 현재 검증은 DB, 표출 API 및 미디어 다운로드까지다.

## 5. 확정 계약 중 후속 운영 결정 사항

`target_regions`는 현재 표출 필터와 호환되도록 지역명 문자열을 사용한다. 향후 코드 체계 전환은 별도 마이그레이션 과제다. 기존 `CARD` 행을 실제 이미지 슬라이드로 간주하지 않고 `TEXT`로 분류해 데이터 내용을 보존했다. 재난 원본의 DB 수명주기/보존 정책과 실제 외부 제공 연동은 재난문자 API 승인 후 확정한다.

2026-10-04 Compose mount 변경 당시 기존 프로젝트 루트 `content-store`와 `server/content-store`의 파일 7개(91,580,923바이트)는 SHA-256이 모두 일치했다. watcher와 backend를 재생성한 뒤 두 서비스가 `/mnt/d/PROJECT/SmartBIS_jangsung/server/content-store`를 `/app/content-store`로 mount함을 확인했다. Backend health는 `UP`, 기존 `CNT_BATCH_0001` 동영상 API는 HTTP 200과 `video/mp4`를 반환했다. 이전 경로는 검증을 마쳤지만 즉시 삭제하지 않고 롤백 사본으로 보존한다.
