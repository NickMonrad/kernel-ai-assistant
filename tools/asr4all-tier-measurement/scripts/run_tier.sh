#!/usr/bin/env bash
# One locked device session of the asr4all per-device-tier measurement.
#
# usage: run_tier.sh s23u|s21 <agent-id> <private-out-dir> <repeat> [lanes-file]
#   Runs every lane in <lanes-file> (default: lanes-<phone>.txt beside this script) once, rotated by <repeat>
#   so lane order differs per repeat, then removes staged owner audio and outputs, restores stay-awake and
#   releases the lock. One repeat per session keeps each lock hold to ~1 h so other agents can use the phone.
# env:   CACHE  model/public-clip cache (see ../README.md), OWNER / BREADTH  16 kHz WAV dirs (private),
#        UNINSTALL=1 uninstall the probe at the end, SMOKE=1 owner set only and no breadth.
#
# Lane line format: <runtime> <size> <tier> <variant> <cpus|all> <sets>
#   sets: comma list of owner (paced), long (paced 67 s stream), breadth (compute, only on repeat 1)
set -euo pipefail
phone=$1 agent=$2 out=$3 repeat=$4
here=$(cd "$(dirname "$0")" && pwd)
lanes_file=${5:-$here/lanes-$phone.txt}
case $phone in s23u) S=RFCW11265XH ;; s21) S=R5CR605B71K ;; *) echo "phone?" >&2; exit 2 ;; esac
CACHE=${CACHE:-$HOME/.cache/jandal-1988}
OWNER=${OWNER:-$HOME/private-acoustic-fixtures/stt-eval-2026-11/owner-decision/16k}
BREADTH=${BREADTH:-$HOME/private-acoustic-fixtures/stt-eval-breadth/16k}
A=ai.jandal.spike.asr4alltier
RUNNER=$A.test/androidx.test.runner.AndroidJUnitRunner
APK=$here/../probe/build/outputs/apk
HF=$HOME/.cache/huggingface/hub
declare -A REV=([s]=87a6b5d093723fe37e9f69ad26307a3b95a31af0 [m]=7d6e9145109a5bece4f05831d046e93d27e38a99 [l]=79d1c23bc8f1174cee576b40bbaa0118bd95abed)
mkdir -p "$out"; chmod 700 "$out"

"$here/device-lock.sh" acquire "$phone" "$agent"   # exits non-zero (and we stop) if held
stay=$(adb -s $S shell settings get global stay_on_while_plugged_in | tr -d '\r')
cleanup() {
  adb -s $S shell "run-as $A rm -rf files/tier/owner files/tier/breadth files/tier-out" || true
  adb -s $S shell rm -rf /data/local/tmp/tier || true
  adb -s $S shell settings put global stay_on_while_plugged_in "$stay" || true
  if [[ ${UNINSTALL:-0} == 1 ]]; then
    adb -s $S uninstall $A.test >/dev/null || true
    adb -s $S uninstall $A >/dev/null || true
  fi
  "$here/device-lock.sh" release "$phone" "$agent"
}
trap cleanup EXIT
adb -s $S shell svc power stayon usb

adb -s $S install -r "$APK/debug/probe-debug.apk" >/dev/null
adb -s $S install -r "$APK/androidTest/debug/probe-debug-androidTest.apk" >/dev/null
# Own isolated package only; the native lane needs the permission to open a session (mic is never used).
adb -s $S shell pm grant $A android.permission.RECORD_AUDIO
sha256sum "$APK/debug/probe-debug.apk" "$APK/androidTest/debug/probe-debug-androidTest.apk" > "$out/apk-r$repeat.sha256"

adb -s $S shell mkdir -p /data/local/tmp/tier
have() { adb -s $S shell "run-as $A sha256sum files/$1 2>/dev/null" | cut -c1-64; }
stage() { # stage <host file> <app-relative dir>; skipped when the staged copy already matches
  [[ $(have "$2/$(basename "$1")") == $(sha256sum "$1" | cut -c1-64) ]] && return 0
  adb -s $S push "$1" /data/local/tmp/tier/stage.bin >/dev/null
  adb -s $S shell "run-as $A mkdir -p files/$2 && run-as $A cp /data/local/tmp/tier/stage.bin files/$2/$(basename "$1")"
}
for sz in s m l; do
  et=$HF/models--futo-org--asr4all-$sz/snapshots/${REV[$sz]}/executorch/xnnpack_int8
  for f in asr_encoder.pte metadata.json; do stage "$et/$f" tier/$sz; done
done
for f in "$CACHE"/zipformer/sherpa-stt-*; do stage "$f" tier/zipformer; done
stage "$CACHE/public/2.wav" tier/public
stage "$CACHE/public/long-public.wav" tier/long
for f in "$OWNER"/*.wav; do stage "$f" tier/owner; done
[[ $repeat == 1 && ${SMOKE:-0} != 1 ]] && for f in "$BREADTH"/*.wav; do stage "$f" tier/breadth; done
adb -s $S shell "run-as $A sh -c 'cd files/tier && sha256sum */*.pte */*.json */*.onnx */*.txt long/*.wav'" > "$out/staged-r$repeat.sha256"

bat() { adb -s $S shell dumpsys battery | tr -d '\r' | awk '$1 == "temperature:" {print $2 / 10; exit}'; }
instr() { adb -s $S shell am instrument -w "$@" "$RUNNER" | tr -d '\r'; }
pull() { adb -s $S exec-out run-as $A cat "files/tier-out/$1" > "$out/$1" || echo "PULL FAILED $1" | tee -a "$out/lanes.log"; }

mapfile -t lanes < <(grep -vE '^\s*(#|$)' "$lanes_file")
n=${#lanes[@]}
for ((k = 0; k < n; k++)); do
  # Rotate the lane order by repeat so no lane always runs first (cold) or last (hot).
  read -r rt sz tier variant cpus sets <<< "${lanes[$(( (k + (repeat - 1) * 5) % n ))]}"
  IFS=, read -ra setlist <<< "$sets"
  for set_ in "${setlist[@]}"; do
    mode=paced
    if [[ $set_ == breadth ]]; then
      [[ $repeat == 1 && ${SMOKE:-0} != 1 ]] || continue
      mode=compute
    fi
    [[ ${SMOKE:-0} == 1 && $set_ != owner ]] && continue
    cpuarg=(); [[ $cpus != all ]] && cpuarg=(-e cpus "$cpus")
    echo "$(date +%T) r$repeat lane $rt $sz $tier $variant cpus=$cpus $set_ temp=$(bat)C" | tee -a "$out/lanes.log"
    instr -e class ai.jandal.asr4alltier.BenchmarkTest -e runtime $rt -e size $sz -e tier $tier \
      -e variant $variant "${cpuarg[@]}" -e mode $mode -e set $set_ -e clips tier/$set_ -e repeat $repeat \
      | tail -1 | tee -a "$out/lanes.log"
    pull "$set_-$mode-$rt-$sz-$tier-$variant-$cpus-r$repeat.json"
  done
  sleep 10
done
echo DONE
