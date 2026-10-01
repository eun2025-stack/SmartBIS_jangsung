#!/usr/bin/env bash
set -euo pipefail

FILE="client-android/app/src/main/java/com/smartbis/client/MainActivity.kt"
BACKUP="${FILE}.backup-playback-$(date +%Y%m%d-%H%M%S)"

test -f "$FILE" || {
  echo "파일을 찾을 수 없습니다: $FILE"
  exit 1
}

cp "$FILE" "$BACKUP"
echo "백업 생성: $BACKUP"

python - "$FILE" <<'PY'
from pathlib import Path
import re
import sys

file = Path(sys.argv[1])
text = file.read_text(encoding="utf-8")

# 중복 삽입 방지
text = text.replace(
    "player.isLooping = true\n                videoView.start()",
    """player.isLooping = false

                videoView.setOnCompletionListener {
                    videoView.stopPlayback()
                    loadDisplay()
                }

                videoView.start()"""
)

text = text.replace(
    "player.isLooping = true\r\n                videoView.start()",
    """player.isLooping = false

                videoView.setOnCompletionListener {
                    videoView.stopPlayback()
                    loadDisplay()
                }

                videoView.start()"""
)

# loadDisplay() 내부에 finally가 없을 경우 실행 상태 해제 추가
if "finally {\n                displayRequestRunning = false" not in text:
    pattern = re.compile(
        r"(private fun loadDisplay\(\)\s*\{.*?displayRequestRunning\s*=\s*true.*?)(\n\s*\}\s*\n)",
        re.DOTALL
    )

    match = pattern.search(text)
    if match:
        block = match.group(1)

        if "displayRequestRunning = false" not in block:
            replacement = (
                block
                + "\n        } finally {\n"
                + "            displayRequestRunning = false\n"
                + "        }"
                + match.group(2)
            )
            text = text[:match.start()] + replacement + text[match.end():]

file.write_text(text, encoding="utf-8")
PY

echo
echo "자동 수정 완료"
echo "백업 파일: $BACKUP"
echo

grep -n -A12 -B4 \
-e "setOnCompletionListener" \
-e "displayRequestRunning" \
server-placeholder 2>/dev/null || true

grep -n -A12 -B4 \
-e "setOnCompletionListener" \
-e "displayRequestRunning" \
"$FILE"
