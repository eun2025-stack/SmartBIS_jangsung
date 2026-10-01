#!/usr/bin/env bash
set -euo pipefail

FILE="client-android/app/src/main/java/com/smartbis/client/MainActivity.kt"
BACKUP="${FILE}.backup-$(date +%Y%m%d-%H%M%S)"

test -f "$FILE" || {
  echo "파일을 찾을 수 없습니다: $FILE"
  exit 1
}

cp "$FILE" "$BACKUP"
echo "백업 생성: $BACKUP"

grep -q 'import android.os.Handler' "$FILE" || \
sed -i '/^import android.os.Bundle/a import android.os.Handler\nimport android.os.Looper' "$FILE"

grep -q 'private val autoRefreshHandler' "$FILE" || \
sed -i '/^class MainActivity/a\
\
    private val autoRefreshHandler = Handler(Looper.getMainLooper())\
    private var autoRefreshEnabled = false\
    private var displayRequestRunning = false\
\
    private val autoRefreshRunnable = object : Runnable {\
        override fun run() {\
            if (!autoRefreshEnabled) return\
            if (!displayRequestRunning) loadDisplay()\
            autoRefreshHandler.postDelayed(this, 30_000L)\
        }\
    }\
' "$FILE"

grep -q 'override fun onStart()' "$FILE" || \
sed -i '/^[[:space:]]*override fun onCreate/i\
    override fun onStart() {\
        super.onStart()\
        autoRefreshEnabled = true\
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)\
        autoRefreshHandler.post(autoRefreshRunnable)\
    }\
\
    override fun onStop() {\
        autoRefreshEnabled = false\
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)\
        super.onStop()\
    }\
' "$FILE"

grep -q 'displayRequestRunning = true' "$FILE" || \
sed -i '/^[[:space:]]*private fun loadDisplay()/a\
        if (displayRequestRunning) return\
        displayRequestRunning = true\
' "$FILE"

grep -q 'displayRequestRunning = false' "$FILE" || \
sed -i '/^[[:space:]]*private fun loadDisplay()/,/^[[:space:]]*}/ s/^[[:space:]]*}[[:space:]]*$/        displayRequestRunning = false\
    }/' "$FILE"

echo
echo "자동 전환 코드 적용 완료"
echo "백업 파일: $BACKUP"
echo
grep -n -A8 -B3 'autoRefreshRunnable' "$FILE"
