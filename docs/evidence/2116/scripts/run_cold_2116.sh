#!/usr/bin/env bash
# One cold run of a Kokoro ExecuTorch lane on the S23U (#2116).
# usage: run_cold_2116.sh <lane: kokoro_et|kokoro_et_int8> <label>
# Precondition: caller holds /tmp/jandal-s23u.lock and sets E4B state (alone: fresh foreground launch;
# with-e4b: chat warm-up done). Output JSON and logcat go to ~/.cache/kokoro-et-2116/results (not in git).
set -u
LANE="$1"
LABEL="$2"
D=RFCW11265XH
PKG=ai.jandal.assistant.debug
RCV="$PKG/com.kernel.ai.debug.tts.DebugTtsBaselineReceiver"
OUT="$HOME/.cache/kokoro-et-2116/results"
mkdir -p "$OUT"

adb -s $D shell am force-stop $PKG
adb -s $D logcat -c
# Receiver needs a foreground app (startForegroundService is refused from background).
adb -s $D shell am start -n $PKG/com.kernel.ai.MainActivity >/dev/null
sleep 15
PID=$(adb -s $D shell pidof $PKG | tr -d '\r')
echo "pid=$PID rss_before=$(adb -s $D shell grep VmRSS /proc/$PID/status | tr -d '\r')"

RESULT=$(adb -s $D shell am broadcast -n "$RCV" \
  --es lane "$LANE" \
  --es corpus_path /data/local/tmp/corpus-kokoro-1557.tsv \
  --es label "$LABEL" \
  --ei repeats 3 | tr -d '\r')
echo "$RESULT" | grep -q "result=-1" || { echo "broadcast failed: $RESULT"; exit 2; }
echo "$RESULT" | grep -q "baseline_failed" && { echo "baseline failed: $RESULT"; exit 2; }

# Wait for the output JSON, up to 40 min (checked every 5 s).
found=0
for i in $(seq 1 480); do
  if adb -s $D shell run-as $PKG test -f "files/tts_baseline/$LANE/$LABEL.json" 2>/dev/null; then
    found=1
    break
  fi
  sleep 5
done
echo "json_found=$found"
[ $found = 1 ] || exit 3
adb -s $D shell run-as $PKG cat "files/tts_baseline/$LANE/$LABEL.json" > "$OUT/$LABEL.json"
adb -s $D shell grep VmRSS /proc/$PID/status | tr -d '\r'
adb -s $D logcat -d -s KokoroEt:* DebugTtsBaseline:* KernelAI:W > "$OUT/$LABEL.logcat.txt"
echo "done $LABEL"
