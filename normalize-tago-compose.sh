#!/usr/bin/env bash
set -euo pipefail

COMPOSE="compose.yaml"
BACKUP="compose.yaml.backup-tago-$(date +%Y%m%d-%H%M%S)"

cp "$COMPOSE" "$BACKUP"

python - "$COMPOSE" <<'PY'
from pathlib import Path
import re
import sys

file = Path(sys.argv[1])
lines = file.read_text(encoding="utf-8").splitlines(keepends=True)

tago_names = {
    "TAGO_CITY_CODE",
    "TAGO_ENABLED",
    "TAGO_MOCK_MODE",
    "TAGO_SERVICE_KEY",
    "TAGO_SERVICE_KEY_MODE",
    "TAGO_POLL_INTERVAL_SECONDS",
    "TAGO_RETRY_COUNT",
    "TAGO_RETRY_DELAY_SECONDS",
}

current_service = None
output = []

for line in lines:
    service_match = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)

    if service_match:
        current_service = service_match.group(1)
        output.append(line)
        continue

    tago_match = re.match(
        r"^(\s+)(TAGO_[A-Z0-9_]+):\s*(.*?)(\r?\n)?$",
        line
    )

    if tago_match:
        indent, name, value, newline = tago_match.groups()

        if name not in tago_names:
            output.append(line)
            continue

        # TAGO 설정은 egov-backend에만 유지
        if current_service != "egov-backend":
            continue

        # 실제 값이나 placeholder를 compose.yaml에 두지 않고 .env에서 읽음
        output.append(
            f"{indent}{name}: ${{{name}}}{newline or ''}"
        )
        continue

    output.append(line)

file.write_text("".join(output), encoding="utf-8")
PY

echo "compose.yaml 정리 완료"
echo "백업 파일: $BACKUP"
