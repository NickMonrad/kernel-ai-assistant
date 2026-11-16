#!/bin/bash
# usage: overlap.sh SERIAL LABEL RUNS MODE [PROMPT]
# MODE=probe: as soon as the turn logs generate_called, the debug SpeechProbeReceiver speaks probe.txt through
# the production chunked path, so Sherpa synthesis and playback overlap the whole generation.
# MODE=none: same voice prompt, no probe (speech only after generation, finalize path).
# The app must already be installed, foregrounded and warm. The caller must hold the phone's lock
# (.docs/agents/risk-based-evidence-policy.md); the script exits otherwise.
# Full logcat (-v epoch) per run goes to $OUTDIR (default /tmp/1923/logs; it includes other apps, do not commit
# it; trim with trim.py first).
set -u
S=$1; LABEL=$2; RUNS=$3; MODE=$4
case $S in
  RFCW11265XH) LOCK=/tmp/jandal-s23u.lock ;;
  R5CR605B71K) LOCK=/tmp/jandal-s21.lock ;;
  *) echo "unknown serial $S: only the S23U and S21 test phones are allowed" >&2; exit 1 ;;
esac
[ -f "$LOCK/owner" ] || { echo "take $LOCK first (mkdir + owner file); not running" >&2; exit 1; }
Q=${5:-"Tell me three short facts about kiwi birds, one sentence each."}
PKG=ai.jandal.assistant.debug
OUTDIR=${OUTDIR:-/tmp/1923/logs}; mkdir -p $OUTDIR
HERE=$(cd "$(dirname "$0")" && pwd)
adb -s $S shell input keyevent KEYCODE_WAKEUP
adb -s $S shell wm dismiss-keyguard
adb -s $S shell "run-as $PKG sh -c 'cat /proc/sys/kernel/random/uuid | tr -d - > files/adb_harness_token'"
adb -s $S push "$HERE/probe.txt" /data/local/tmp/probe1923.txt >/dev/null
adb -s $S shell "cat /data/local/tmp/probe1923.txt | run-as $PKG sh -c 'cat > files/probe1923.txt'"
PROBE_PATH=/data/data/$PKG/files/probe1923.txt
# Thermal status 0-6 from dumpsys; 9 (and a warning) when it cannot be read, so the gate below does not spin silently.
thermal() {
  local t
  t=$(adb -s $S shell dumpsys thermalservice | grep -m1 'Thermal Status' | grep -o '[0-9]*')
  if [ -z "$t" ]; then echo "warning: could not read thermal status on $S; treating it as 9" >&2; t=9; fi
  echo "$t"
}
for r in $(seq 1 $RUNS); do
  name=$LABEL-$MODE-run$r
  # Start each run below thermal status 3 (severe): at 3 decode and TTS are both throttled. At rest on USB power
  # with the screen on, both phones sat at status 2 during this work, so a stricter gate never opened.
  # Waits at most 10 minutes. The status is written into the run's log.
  for n in $(seq 1 60); do [ "$(thermal)" -le 2 ] && break; sleep 10; done
  adb -s $S logcat -c
  adb -s $S shell log -t Evidence1923 "thermal_status_at_start=$(thermal)"
  adb -s $S shell "am start -n $PKG/com.kernel.ai.MainActivity --es quick_action_input '$Q' --ez quick_action_is_voice true --es adb_harness_token \$(run-as $PKG cat files/adb_harness_token)" >/dev/null
  if [ "$MODE" = probe ]; then
    for n in $(seq 1 300); do
      adb -s $S logcat -d -s KernelAI 2>/dev/null | grep -q 'stage=generate_called' && break
      sleep 0.2
    done
    adb -s $S shell am broadcast -n $PKG/com.kernel.ai.debug.speech.SpeechProbeReceiver --es path $PROBE_PATH >/dev/null
  fi
  ok=""
  for n in $(seq 1 240); do
    ok=$(adb -s $S logcat -d 2>/dev/null | grep 'lastToken epochMs' | head -1)
    [ -n "$ok" ] && break
    sleep 1
  done
  sleep 30
  adb -s $S logcat -d -v epoch > $OUTDIR/$name.log
  echo "$name complete=$([ -n "$ok" ] && echo yes || echo TIMEOUT) $(grep -o 'decode=[0-9]* tokens @ [0-9.]*' $OUTDIR/$name.log | head -1)"
  sleep 10
done
adb -s $S shell "run-as $PKG rm -f files/adb_harness_token files/probe1923.txt"
adb -s $S shell rm -f /data/local/tmp/probe1923.txt
