#!/bin/bash
# usage: measure-first-audio.sh <label>   (device-side harness token; never leaves the phone)
S=R5CR605B71K
PKG=ai.jandal.assistant.debug
Q="What is the capital of Samoa? Answer in two short sentences."
run() {
  adb -s $S shell input keyevent KEYCODE_WAKEUP; adb -s $S shell wm dismiss-keyguard; adb -s $S logcat -c
  t0=$(adb -s $S shell date +%s%3N | tr -d '\r')
  adb -s $S shell "am start -n $PKG/com.kernel.ai.MainActivity --es quick_action_input '$Q' --ez quick_action_is_voice true --es adb_harness_token \$(run-as $PKG cat files/adb_harness_token)" >/dev/null
  for n in $(seq 1 120); do
    line=$(adb -s $S logcat -d -s KernelAI 2>/dev/null | grep SpeakingStarted | head -1)
    if [ -n "$line" ]; then
      ep=$(echo "$line" | grep -o 'epochMs=[0-9]*' | cut -d= -f2)
      echo "$1 t0=$t0 speakingStarted=$ep delta_ms=$((ep - t0))"
      return
    fi
    sleep 1
  done
  echo "$1 TIMEOUT no SpeakingStarted"
}
adb -s $S shell am start -n $PKG/com.kernel.ai.MainActivity >/dev/null
sleep 45
run warmup
sleep 25
run run1; sleep 25
run run2; sleep 25
run run3
