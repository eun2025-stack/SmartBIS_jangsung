#!/usr/bin/env bash
set -euo pipefail

ENV_FILE=".env"
BACKUP=".env.backup-tago-$(date +%Y%m%d-%H%M%S)"

cp "$ENV_FILE" "$BACKUP"

python - "$ENV_FILE" <<'PY'
from pathlib import Path
import sys

file = Path(sys.argv[1])
lines = file.read_text(encoding="utf-8").splitlines()
seen = set()
result = []

for line in lines:
    if "=" in line and not line.lstrip().startswith("#"):
        key = line.split("=", 1)[0].strip()

        if key.startswith("TAGO_"):
            if key in seen:
                continue
            seen.add(key)

    result.append(line)

file.write_text("\n".join(result) + "\n", encoding="utf-8")
PY

echo ".env 중복 TAGO 설정 정리 완료"
echo "백업 파일: $BACKUP"
