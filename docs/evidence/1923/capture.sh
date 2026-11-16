#!/bin/bash
# usage: capture.sh SERIAL APK LABEL RUNS [PROMPT]
# Installs APK (unless APK=-), idles 60 s, then runs RUNS voice prompts through the debug harness route
# (first is a warm-up), saving the full logcat (-v epoch) per run under $OUTDIR (default /tmp/1923/logs; it
# includes other apps, do not commit it; trim with trim.py first).
# The caller must hold the phone's lock (.docs/agents/risk-based-evidence-policy.md); the script exits otherwise.
set -u
S=$1; APK=$2; LABEL=$3; RUNS=$4
case $S in
  RFCW11265XH) LOCK=/tmp/jandal-s23u.lock ;;
  R5CR605B71K) LOCK=/tmp/jandal-s21.lock ;;
  *) echo "unknown serial $S: only the S23U and S21 test phones are allowed" >&2; exit 1 ;;
esac
[ -f "$LOCK/owner" ] || { echo "take $LOCK first (mkdir + owner file); not running" >&2; exit 1; }
Q=${5:-"Tell me three short facts about kiwi birds, one sentence each."}
PKG=ai.jandal.assistant.debug
OUTDIR=${OUTDIR:-/tmp/1923/logs}; mkdir -p $OUTDIR
if [ "$APK" != "-" ]; then
  adb -s $S install -r "$APK" | tail -1
  adb -s $S shell dumpsys package $PKG | grep lastUpdateTime
fi
adb -s $S shell input keyevent KEYCODE_WAKEUP
adb -s $S shell wm dismiss-keyguard
adb -s $S shell "run-as $PKG sh -c 'cat /proc/sys/kernel/random/uuid | tr -d - > files/adb_harness_token'"
adb -s $S shell am start -n $PKG/com.kernel.ai.MainActivity >/dev/null
sleep 60
for r in $(seq 0 $((RUNS-1))); do
  name=$LABEL-run$r; [ $r -eq 0 ] && name=$LABEL-warmup
  adb -s $S logcat -c
  adb -s $S shell "am start -n $PKG/com.kernel.ai.MainActivity --es quick_action_input '$Q' --ez quick_action_is_voice true --es adb_harness_token \$(run-as $PKG cat files/adb_harness_token)" >/dev/null
  ss=""
  for n in $(seq 1 180); do
    ss=$(adb -s $S logcat -d -s KernelAI 2>/dev/null | grep SpeakingStarted | head -1)
    [ -n "$ss" ] && break
    sleep 1
  done
  sleep 25
  adb -s $S logcat -d -v epoch > $OUTDIR/$name.log
  echo "$name speaking=$([ -n "$ss" ] && echo yes || echo TIMEOUT) lines=$(wc -l < $OUTDIR/$name.log)"
  sleep 10
done
adb -s $S shell "run-as $PKG rm -f files/adb_harness_token"
