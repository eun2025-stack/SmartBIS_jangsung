#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
test -f .env || { echo '.env 파일이 없습니다.'; exit 1; }
read -r -s -p '새 TAGO 키 입력 (화면에 표시되지 않음): ' task_key
printf '\n'
[[ -n "$task_key" && "$task_key" != *'<'* ]] || { echo '실제 발급 키를 입력하십시오.'; exit 1; }
task_tmp=$(mktemp .env.tago.XXXXXX)
trap 'rm -f -- "$task_tmp"; unset task_key' EXIT
while IFS= read -r task_line || [[ -n "$task_line" ]]; do
  task_line=${task_line%$'\r'}
  case "$task_line" in
    TAGO_SERVICE_KEY=*) continue ;;
    TAGO_CITY_CODE=\<*) printf 'TAGO_CITY_CODE=\n' >> "$task_tmp" ;;
    *) printf '%s\n' "$task_line" >> "$task_tmp" ;;
  esac
done < .env
printf 'TAGO_SERVICE_KEY=%s\n' "$task_key" >> "$task_tmp"
mv -- "$task_tmp" .env
unset task_key
echo '키 저장 완료. 실서비스 자동 수집은 기존 설정대로 비활성 상태입니다.'
echo '컨테이너 반영: podman compose up -d --force-recreate egov-backend'
