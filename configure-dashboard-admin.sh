#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${1:-.env}"
if [[ ! -f "$ENV_FILE" ]]; then
  printf '환경 파일을 찾을 수 없습니다: %s\n' "$ENV_FILE" >&2
  exit 1
fi

read -r -p '관리자 계정명 (3~64자, 영문/숫자/._-): ' admin_username
if [[ ! "$admin_username" =~ ^[A-Za-z0-9._-]{3,64}$ ]]; then
  printf '계정명 형식이 올바르지 않습니다.\n' >&2; exit 1
fi
read -r -s -p '관리자 비밀번호 (영문/숫자/_/- 10~72자): ' admin_password; printf '\n'
read -r -s -p '비밀번호 확인: ' admin_password_confirm; printf '\n'
if [[ ! "$admin_password" =~ ^[A-Za-z0-9_-]{10,72}$ ]]; then
  printf '비밀번호는 허용 문자로 10~72자여야 합니다.\n' >&2; exit 1
fi
if [[ "$admin_password" != "$admin_password_confirm" ]]; then
  printf '비밀번호가 일치하지 않습니다. .env는 변경하지 않았습니다.\n' >&2; exit 1
fi

tmp_file="$(mktemp "${ENV_FILE}.tmp.XXXXXX")"
trap 'rm -f "$tmp_file"' EXIT
{
  printf '%s\n' "$admin_username" "$admin_password"
  awk '!/^DASHBOARD_ADMIN_USERNAME=/ && !/^DASHBOARD_ADMIN_PASSWORD=/' "$ENV_FILE"
} | awk 'NR==1 {u=$0; next} NR==2 {p=$0; next} {print} END {print ""; print "# SmartBIS dashboard administrator"; printf "DASHBOARD_ADMIN_USERNAME=%s\nDASHBOARD_ADMIN_PASSWORD=%s\n", u, p}' > "$tmp_file"
chmod 600 "$tmp_file" 2>/dev/null || true
mv "$tmp_file" "$ENV_FILE"
trap - EXIT
unset admin_password admin_password_confirm
printf '.env에 관리자 계정을 저장했습니다. 비밀번호는 출력하지 않았습니다.\n'
