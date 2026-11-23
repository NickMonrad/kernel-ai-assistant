#!/usr/bin/env bash
# One warm run of the Kokoro ExecuTorch lane on the S23U (process kept; no force-stop).
# usage: run_warm.sh <label>
# Precondition: E4B state is set by the caller (alone: fresh foreground launch; with-e4b: chat warm-up done).
set -u
LABEL="$1"
D=RFCW11265XH
PKG=ai.jandal.assistant.debug
RCV="$PKG/com.kernel.ai.debug.tts.DebugTtsBaselineReceiver"
OUT="$HOME/.cache/kokoro-et-1557/results"
mkdir -p "$OUT"

# E4B stays resident: no force-stop (process kept from the chat warm-up).
# Receiver needs a foreground app (startForegroundService is refused from background).
adb -s $D shell am start -n $PKG/com.kernel.ai.MainActivity >/dev/null
sleep 15
PID=$(adb -s $D shell pidof $PKG | tr -d '\r')
echo "pid=$PID rss_before=$(adb -s $D shell grep VmRSS /proc/$PID/status | tr -d '\r')"

RESULT=$(adb -s $D shell am broadcast -n "$RCV" \
  --es lane kokoro_et \
  --es corpus_path /data/local/tmp/corpus-kokoro-1557.tsv \
  --es label "$LABEL" \
  --ei repeats 3 | tr -d '\r')
echo "$RESULT" | grep -q "result=-1" || { echo "broadcast failed: $RESULT"; exit 2; }
echo "$RESULT" | grep -q "baseline_failed" && { echo "baseline failed: $RESULT"; exit 2; }

# Wait for the output JSON, up to 40 min (checked every 5 s).
found=0
for i in $(seq 1 480); do
  if adb -s $D shell run-as $PKG test -f "files/tts_baseline/kokoro_et/$LABEL.json" 2>/dev/null; then
    found=1
    break
  fi
  sleep 5
done
echo "json_found=$found"
[ $found = 1 ] || exit 3
adb -s $D shell run-as $PKG cat "files/tts_baseline/kokoro_et/$LABEL.json" > "$OUT/$LABEL.json"
adb -s $D shell grep VmRSS /proc/$PID/status | tr -d '\r'
adb -s $D logcat -d -s KokoroEt:* DebugTtsBaseline:* KernelAI:W > "$OUT/$LABEL.logcat.txt"
echo "done $LABEL"
