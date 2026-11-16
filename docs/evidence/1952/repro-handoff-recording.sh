#!/bin/bash
# Usage: repro-handoff-recording.sh <out-name> [light|dark]
# Cold-starts Jandal debug, runs a chat turn (chat in back stack), then fires the widget-mic/ADB
# voice handoff to Actions while recording the S23U screen. Pulls the mp4 to $PULL_DIR.
# Takes /tmp/jandal-s23u.lock for the run and releases it on exit (only if we own it).
# With a mode argument, sets `cmd uimode night` for the run and restores the original value on exit.
set -u
S=RFCW11265XH
P=ai.jandal.assistant.debug
OUT=$1
MODE=${2:-}
PULL_DIR=${PULL_DIR:-/tmp/r1952}
LOCK=/tmp/jandal-s23u.lock
OWNER=issue-1952-agent

if ! mkdir "$LOCK" 2>/dev/null; then
  echo "S23U lock held by: $(cat "$LOCK/owner" 2>/dev/null)" >&2
  exit 1
fi
echo "$OWNER" > "$LOCK/owner"
mkdir -p "$PULL_DIR"

ORIG_NIGHT=""
cleanup() {
  adb -s $S shell rm -f /sdcard/$OUT.mp4 >/dev/null 2>&1
  if [ -n "$ORIG_NIGHT" ]; then
    adb -s $S shell cmd uimode night "$ORIG_NIGHT" >/dev/null 2>&1
  fi
  if [ "$(cat "$LOCK/owner" 2>/dev/null)" = "$OWNER" ]; then
    rm -rf "$LOCK"
  fi
}
trap cleanup EXIT

if [ -n "$MODE" ]; then
  ORIG_NIGHT=$(adb -s $S shell cmd uimode night | awk '{print $NF}' | tr -d '\r')
  case "$MODE" in
    light) adb -s $S shell cmd uimode night no >/dev/null ;;
    dark) adb -s $S shell cmd uimode night yes >/dev/null ;;
    *) echo "unknown mode $MODE" >&2; exit 2 ;;
  esac
fi

TOKEN=$(openssl rand -hex 16)
adb -s $S shell "run-as $P sh -c 'mkdir -p files && printf %s $TOKEN > files/adb_harness_token'"
adb -s $S shell am force-stop $P
adb -s $S shell "am start -n $P/com.kernel.ai.MainActivity --es adb_harness_token $TOKEN --es chat_input 'Remind me about the team lunch'" >/dev/null
sleep 30
adb -s $S shell rm -f /sdcard/$OUT.mp4
adb -s $S shell "screenrecord --time-limit 10 --bit-rate 6000000 /sdcard/$OUT.mp4" &
REC=$!
sleep 3
adb -s $S shell "am start -n $P/com.kernel.ai.MainActivity --es adb_harness_token $TOKEN --es quick_action_input 'Add milk to my shopping list' --ez quick_action_is_voice true" >/dev/null
wait $REC
adb -s $S pull /sdcard/$OUT.mp4 "$PULL_DIR/$OUT.mp4"
echo done
