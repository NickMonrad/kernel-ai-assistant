#!/bin/bash
# usage: measure-first-audio.sh <label>
# Debug build only (ai.jandal.assistant.debug). Harness token is read on-device via run-as and never leaves the phone.
# Needs the VoiceTiming debug lines (lastToken, firstChunkQueued, path=stream|final) from the branch/main debug build.
# Device is expected to be awake and unlocked with screen kept on; this script sends no sleep/power keys.
S=RFCW11265XH
PKG=ai.jandal.assistant.debug
Q="Tell me three short facts about kiwi birds, one sentence each."
run() {
  adb -s $S logcat -c
  t0=$(adb -s $S shell date +%s%3N | tr -d '\r')
  adb -s $S shell "am start -n $PKG/com.kernel.ai.MainActivity --es quick_action_input '$Q' --ez quick_action_is_voice true --es adb_harness_token \$(run-as $PKG cat files/adb_harness_token)" >/dev/null
  ss=""
  for n in $(seq 1 150); do
    line=$(adb -s $S logcat -d -s KernelAI 2>/dev/null | grep SpeakingStarted | head -1)
    if [ -n "$line" ]; then ss=$(echo "$line" | grep -o 'epochMs=[0-9]*' | cut -d= -f2); break; fi
    sleep 1
  done
  sleep 15
  logs=$(adb -s $S logcat -d 2>/dev/null)
  path=$(echo "$logs" | grep 'VoiceTiming.*path=' | head -1 | grep -o 'path=\(stream\|final\)' | cut -d= -f2)
  last=$(echo "$logs" | grep 'VoiceTiming lastToken' | head -1 | grep -o 'epochMs=[0-9]*' | cut -d= -f2)
  fq=$(echo "$logs" | grep 'firstChunkQueued epochMs' | head -1 | grep -o 'epochMs=[0-9]*' | cut -d= -f2)
  fqc=$(echo "$logs" | grep 'firstChunkQueued epochMs' | head -1 | grep -o 'chars=[0-9]*' | cut -d= -f2)
  dec=$(echo "$logs" | grep -o 'decode=[0-9]* tokens @ [0-9.]* tokens/s' | tail -1)
  if [ -z "$ss" ]; then echo "$1 TIMEOUT no SpeakingStarted"; return; fi
  d1=NA; d2=NA; [ -n "$last" ] && d1=$((ss - last)); [ -n "$fq" ] && d2=$((ss - fq))
  echo "$1 path=$path t0=$t0 speakingStarted=$ss lastToken=$last firstChunkQueued=$fq fqChars=$fqc ss_minus_lastToken=$d1 ss_minus_firstChunk=$d2 $dec"
}
adb -s $S shell am start -n $PKG/com.kernel.ai.MainActivity >/dev/null
sleep 45
run warmup
sleep 25
run run1; sleep 25
run run2; sleep 25
run run3
