#!/bin/bash
# usage: [ONLY=MAIN|HEAD] [OUT=path] [MAIN_APK=path] [HEAD_APK=path] measure-first-audio.sh
# Alternates MAIN and HEAD debug APKs on the S23U (RFCW11265XH): 3 install cycles per build,
# one unrecorded warm-up run per build, then 3 measured kiwi (non-router) prompt runs per build.
# Metric: lastToken epochMs (generation end, VoiceTiming) -> SpeakingStarted epochMs (KernelAI).
# MAIN must be a build that logs "lastToken epochMs" (timing-instrumented main).
# Debug build only (ai.jandal.assistant.debug). The harness token is read on-device via run-as and never leaves the phone.
# Device must be awake and unlocked with the screen kept on. This script sends no sleep/power keys and changes no svc power.
set -u
S=RFCW11265XH
PKG=ai.jandal.assistant.debug
MAIN_APK=${MAIN_APK:-/tmp/apk-main-timing.apk}
HEAD_APK=${HEAD_APK:-/tmp/apk-round6-head.apk}
OUT=${OUT:-/tmp/s23u-results-1892.txt}
ONLY=${ONLY:-}
LOCK=/tmp/jandal-s23u.lock
Q="Tell me three short facts about kiwi birds, one sentence each."

if [ -e "$LOCK" ]; then echo "device lock held: $LOCK" >&2; exit 1; fi
echo $$ > "$LOCK"
trap 'rm -f "$LOCK"' EXIT
: > "$OUT"

run() {  # $1 label
  adb -s $S logcat -c
  adb -s $S shell "am start -n $PKG/com.kernel.ai.MainActivity --es quick_action_input '$Q' --ez quick_action_is_voice true --es adb_harness_token \$(run-as $PKG cat files/adb_harness_token)" >/dev/null
  ss=""
  for n in $(seq 1 150); do
    ss=$(adb -s $S logcat -d -s KernelAI 2>/dev/null | grep SpeakingStarted | head -1 | grep -o 'epochMs=[0-9]*' | cut -d= -f2)
    [ -n "$ss" ] && break
    sleep 1
  done
  sleep 15
  last=$(adb -s $S logcat -d 2>/dev/null | grep 'lastToken epochMs' | head -1 | grep -o 'epochMs=[0-9]*' | cut -d= -f2)
  if [ -z "$ss" ]; then echo "$1 TIMEOUT no SpeakingStarted" | tee -a "$OUT"; return; fi
  d=NA; [ -n "$last" ] && d=$((ss - last))
  echo "$1 lastToken=$last speakingStarted=$ss lastToken_to_speaking_ms=$d" | tee -a "$OUT"
}

prep() {  # $1 label  $2 apk  $3 cycle  (install, then idle 60 s with the app foregrounded)
  adb -s $S install -r "$2" | tail -1 | tee -a "$OUT"
  adb -s $S shell am start -n $PKG/com.kernel.ai.MainActivity >/dev/null
  echo "$1 cycle$3 idle 60s after install" >> "$OUT"
  sleep 60
}

adb -s $S shell "run-as $PKG sh -c 'cat /proc/sys/kernel/random/uuid | tr -d - > files/adb_harness_token'"
for c in 1 2 3; do
  if [ -z "$ONLY" ] || [ "$ONLY" = MAIN ]; then
    prep MAIN "$MAIN_APK" $c
    if [ $c -eq 1 ]; then run "MAIN warmup"; sleep 20; fi
    run "MAIN cycle$c"; sleep 20
  fi
  if [ -z "$ONLY" ] || [ "$ONLY" = HEAD ]; then
    prep HEAD "$HEAD_APK" $c
    if [ $c -eq 1 ]; then run "HEAD warmup"; sleep 20; fi
    run "HEAD cycle$c"; sleep 20
  fi
done
echo DONE >> "$OUT"
