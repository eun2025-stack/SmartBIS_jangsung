#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
task_key=''
while IFS= read -r task_line || [[ -n "$task_line" ]]; do
  task_line=${task_line%$'\r'}
  case "$task_line" in TAGO_SERVICE_KEY=*) task_key=${task_line#*=} ;; esac
done < .env
[[ -n "$task_key" && "$task_key" != *'<'* ]] || { echo '먼저 bash configure-tago-key.sh 를 실행하십시오.'; exit 1; }
task_encoded=''
for ((task_i=0; task_i<${#task_key}; task_i++)); do
  task_char=${task_key:task_i:1}
  if [[ "$task_char" == '%' && ${task_key:task_i+1:2} =~ ^[[:xdigit:]]{2}$ ]]; then
    task_encoded+="${task_key:task_i:3}"
    task_i=$((task_i+2))
  else
    case "$task_char" in
      [a-zA-Z0-9.~_-]) task_encoded+="$task_char" ;;
      *) printf -v task_hex '%02X' "'$task_char"; task_encoded+="%$task_hex" ;;
    esac
  fi
done
# curl 설정을 표준입력으로 전달하여 키를 프로세스 인수에 남기지 않습니다.
printf 'url = "http://apis.data.go.kr/1613000/BusRouteInfoInqireService/getCtyCodeList?serviceKey=%s&_type=xml"\n' "$task_encoded" |
  curl --config - --silent --show-error --connect-timeout 10 --max-time 30 --write-out '\nHTTP_STATUS=%{http_code}\n'
