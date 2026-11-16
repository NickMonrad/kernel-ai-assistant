#!/bin/bash
# Issue #1920: per-command timing of chat-path voice commands, BEFORE vs AFTER, on one phone.
#
# usage: AGENT_ID=<id> BEFORE_APK=<apk> AFTER_APK=<apk> [SERIAL=RFCW11265XH] [CYCLES=3] [RUNS=2]
#        [KINDS=tool,chat] [STAYON=1 (S21 only)] [OUT=/tmp/jandal-1920/runs-<serial>]
#        docs/evidence/1920/measure-first-token.sh
#
# - Device lock protocol (.docs/agents/risk-based-evidence-policy.md): atomic mkdir of the phone's
#   lock with an owner file, bounded wait (25 min), released only if the owner file is ours. The
#   owner timestamp is refreshed before each command while the run holds the lock.
# - Builds alternate ABBA per cycle. After each install: launch, 60 s idle, one discarded warm-up
#   command per query, then RUNS measured commands per query.
# - Commands use the debug harness route: quick_action_input + quick_action_is_voice + a harness
#   token generated here and written with run-as (never printed). Actions falls through to chat.
# - Media volume is set to 1 for the run and restored. No sleep/power keys; `svc power` only with
#   STAYON=1 on the S21, restored afterwards; the S23U is never sent `svc power`.
# - Raw logcat stays under $OUT/raw (it can hold prompt and reply text); only parsed metric lines
#   go to $OUT/runs.txt (see parse_run.py).
set -u
SERIAL=${SERIAL:-RFCW11265XH}
case "$SERIAL" in
  RFCW11265XH) LOCK=/tmp/jandal-s23u.lock ;;
  R5CR605B71K) LOCK=/tmp/jandal-s21.lock ;;
  *) echo "unknown serial $SERIAL" >&2; exit 2 ;;
esac
AGENT_ID=${AGENT_ID:?set AGENT_ID}
BEFORE_APK=${BEFORE_APK:?set BEFORE_APK}
AFTER_APK=${AFTER_APK:?set AFTER_APK}
CYCLES=${CYCLES:-3}
RUNS=${RUNS:-2}
OUT=${OUT:-/tmp/jandal-1920/runs-$SERIAL}
PKG=ai.jandal.assistant.debug
HERE=$(cd "$(dirname "$0")" && pwd)
QUERIES=(
  "tool|Look up the weather in Apia this afternoon"
  "chat|In one sentence, why is the sky blue?"
)
# KINDS=chat runs only the chat query (the S21 E-2B tool query fails on both builds; see timing.md).
KINDS=${KINDS:-tool,chat}
SELECTED=()
for entry in "${QUERIES[@]}"; do [[ ",$KINDS," == *",${entry%%|*},"* ]] && SELECTED+=("$entry"); done
QUERIES=("${SELECTED[@]}")
A() { adb -s "$SERIAL" "$@"; }

write_owner() { printf '%s\n%s\n' "$AGENT_ID" "$(date +%s)" > "$LOCK/owner"; }
deadline=$((SECONDS + 1500))
until mkdir "$LOCK" 2>/dev/null; do
  if (( SECONDS >= deadline )); then echo "lock $LOCK still held after 25 min:" >&2; cat "$LOCK/owner" >&2; exit 1; fi
  sleep 30
done
write_owner
MEDIA_VOL=""
STAY_ON=""
cleanup() {
  if [ -n "$MEDIA_VOL" ]; then A shell cmd media_session volume --stream 3 --set "$MEDIA_VOL" >/dev/null 2>&1; fi
  if [ "$STAY_ON" = 0 ]; then A shell svc power stayon false; fi
  if [ "$(head -1 "$LOCK/owner" 2>/dev/null)" = "$AGENT_ID" ]; then rm -rf "$LOCK"; fi
}
trap cleanup EXIT

mkdir -p "$OUT/raw"
MEDIA_VOL=$(A shell cmd media_session volume --stream 3 --get | grep -o 'volume is [0-9]*' | grep -o '[0-9]*$')
A shell cmd media_session volume --stream 3 --set 1 >/dev/null
# STAYON=1 keeps the screen on for the run (S21 only; the S23U is never sent `svc power`).
if [ "${STAYON:-0}" = 1 ] && [ "$SERIAL" != RFCW11265XH ]; then
  STAY_ON=$(A shell settings get global stay_on_while_plugged_in | tr -d '\r')
  [ "$STAY_ON" = 0 ] && A shell svc power stayon usb
fi
A shell input keyevent KEYCODE_WAKEUP
A shell wm dismiss-keyguard
if A shell dumpsys window | grep -q 'mDreamingLockscreen=true\|isKeyguardShowing=true'; then
  echo "keyguard is showing; not unlocking with a PIN" >&2; exit 1
fi
echo "start $(date -Iseconds) serial=$SERIAL media_volume_was=$MEDIA_VOL" >> "$OUT/runs.txt"

TOKEN=""
set_token() {
  TOKEN=$(openssl rand -hex 16)
  A shell "run-as $PKG sh -c 'mkdir -p files && printf %s $TOKEN > files/adb_harness_token'"
}

run_one() {  # $1 build  $2 kind  $3 query  $4 label
  write_owner
  local thermal temp
  thermal=$(A shell dumpsys thermalservice | grep -m1 'Thermal Status' | grep -o '[0-9]*$')
  temp=$(A shell dumpsys battery | grep -m1 ' temperature' | grep -o '[0-9]*$')
  A logcat -c
  A shell "am start -n $PKG/com.kernel.ai.MainActivity --es quick_action_input '$3' --ez quick_action_is_voice true --es adb_harness_token $TOKEN" >/dev/null
  local done=0
  for _ in $(seq 1 120); do
    sleep 2
    if A logcat -d -s LiteRtInferenceEngine:I | grep -q 'Generation complete'; then done=1; break; fi
  done
  sleep 3
  local raw="$OUT/raw/$4.log"
  A logcat -d -v epoch -b main,system > "$raw"
  python3 "$HERE/parse_run.py" "$raw" "label=$4" "build=$1" "kind=$2" "complete=$done" \
    "thermal_status=${thermal:-NA}" "battery_temp_dC=${temp:-NA}" | tee -a "$OUT/runs.txt"
  sleep 20
}

prep() {  # $1 build  $2 apk  $3 cycle
  A install -r "$2" | tail -1
  A shell am force-stop $PKG
  set_token
  A shell am start -n $PKG/com.kernel.ai.MainActivity >/dev/null
  sleep 60
  local entry kind query
  for entry in "${QUERIES[@]}"; do
    kind=${entry%%|*}; query=${entry#*|}
    run_one "$1" "$kind" "$query" "$1-c$3-$kind-warmup" > /dev/null
  done
  for r in $(seq 1 "$RUNS"); do
    for entry in "${QUERIES[@]}"; do
      kind=${entry%%|*}; query=${entry#*|}
      run_one "$1" "$kind" "$query" "$1-c$3-$kind-r$r"
    done
  done
}

for c in $(seq 1 "$CYCLES"); do
  if (( c % 2 == 1 )); then
    prep BEFORE "$BEFORE_APK" "$c"; prep AFTER "$AFTER_APK" "$c"
  else
    prep AFTER "$AFTER_APK" "$c"; prep BEFORE "$BEFORE_APK" "$c"
  fi
done
echo "done $(date -Iseconds)" >> "$OUT/runs.txt"
