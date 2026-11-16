#!/bin/bash
# usage: [ONLY=BEFORE|AFTER] [OUT=path] [BEFORE_APK=path] [AFTER_APK=path] measure-first-token.sh
# Issue #1920: time to first answer token for a chat-path voice command on the S23U (E-4B, GPU).
# Alternates the BEFORE (origin/main base) and AFTER (this PR) debug APKs: 3 install cycles per build,
# one unrecorded warm-up run per build, then 3 measured runs per build per cycle.
# Metric: TTFT_STAGE stage=first_token t_ms (ms from command accept in ChatViewModel to first token).
# Also recorded: engine "TTFT (Time to First Token)" and CONV_REBUILD lines (AFTER build only; it has the counter).
# Debug package only (ai.jandal.assistant.debug). The harness token is generated on the phone via run-as and never printed.
# The phone must be awake and unlocked; this script sends no sleep/power keys and changes no svc power.
set -u
S=RFCW11265XH
PKG=ai.jandal.assistant.debug
BEFORE_APK=${BEFORE_APK:-/tmp/apk-1920-before.apk}
AFTER_APK=${AFTER_APK:-/tmp/apk-1920-after.apk}
OUT=${OUT:-docs/evidence/1920/s23u-ttft-runs.txt}
ONLY=${ONLY:-}
LOCK=/tmp/jandal-s23u.lock
Q="Tell me a short story about a dog."

if [ -e "$LOCK" ]; then echo "device lock held: $LOCK" >&2; exit 1; fi
echo $$ > "$LOCK"
trap 'rm -f "$LOCK"' EXIT
: > "$OUT"

run() {  # $1 label
  adb -s $S logcat -c
  adb -s $S shell "am start -n $PKG/com.kernel.ai.MainActivity --es quick_action_input '$Q' --ez quick_action_is_voice true --es adb_harness_token \$(run-as $PKG cat files/adb_harness_token)" >/dev/null
  for n in $(seq 1 180); do
    adb -s $S logcat -d -s KernelAI:D | grep -q 'stage=first_token' && break
    sleep 1
  done
  log=$(adb -s $S logcat -d -s KernelAI:D LiteRtInferenceEngine:I 2>/dev/null)
  ttft=$(printf '%s\n' "$log" | grep 'stage=first_token' | tail -1 | grep -o 't_ms=[0-9]*' | cut -d= -f2)
  engine=$(printf '%s\n' "$log" | grep 'Time to First Token' | tail -1 | grep -o '[0-9]*ms' | head -1)
  rebuilds=$(printf '%s\n' "$log" | grep -c 'CONV_REBUILD count')
  reasons=$(printf '%s\n' "$log" | grep -o 'CONV_REBUILD count=[0-9]* reason=[A-Za-z]*' | sed 's/CONV_REBUILD //' | tr '\n' ' ')
  if [ -z "$ttft" ]; then echo "$1 TIMEOUT no first_token" | tee -a "$OUT"; sleep 25; return; fi
  echo "$1 first_token_ms=$ttft engine_ttft=${engine:-NA} rebuilds=$rebuilds reasons=[${reasons% }]" | tee -a "$OUT"
  sleep 25
}

prep() {  # $1 label  $2 apk  $3 cycle
  adb -s $S install -r "$2" | tail -1 | tee -a "$OUT"
  adb -s $S shell am start -n $PKG/com.kernel.ai.MainActivity >/dev/null
  echo "$1 cycle$3 idle 60s after install" >> "$OUT"
  sleep 60
}

adb -s $S shell "run-as $PKG sh -c 'cat /proc/sys/kernel/random/uuid | tr -d - > files/adb_harness_token'"
for c in 1 2 3; do
  if [ -z "$ONLY" ] || [ "$ONLY" = BEFORE ]; then
    prep BEFORE "$BEFORE_APK" $c
    if [ $c -eq 1 ]; then run "BEFORE warmup"; fi
    for r in 1 2 3; do run "BEFORE cycle$c run$r"; done
  fi
  if [ -z "$ONLY" ] || [ "$ONLY" = AFTER ]; then
    prep AFTER "$AFTER_APK" $c
    if [ $c -eq 1 ]; then run "AFTER warmup"; fi
    for r in 1 2 3; do run "AFTER cycle$c run$r"; done
  fi
done
echo DONE >> "$OUT"
