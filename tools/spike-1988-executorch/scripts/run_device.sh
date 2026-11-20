#!/usr/bin/env bash
# #1988 device session: stage pinned models + clips into the isolated probe's app-private storage,
# run public parity and every benchmark lane (one process per lane), pull results to private host
# storage, remove staged audio, restore stay-awake and release the device lock.
#
# usage: run_device.sh s23u|s21 <agent-id> <private-out-dir> [phases: parity,bench,breadth]
# env:   CACHE (model/public cache, default ~/.cache/jandal-1988)
#        OWNER (owner 16k dir), BREADTH (breadth 16k dir)
set -euo pipefail
phone=$1 agent=$2 out=$3 phases=${4:-parity,bench,breadth}
case $phone in s23u) S=RFCW11265XH ;; s21) S=R5CR605B71K ;; *) echo "phone?" >&2; exit 2 ;; esac
here=$(cd "$(dirname "$0")" && pwd)
CACHE=${CACHE:-$HOME/.cache/jandal-1988}
OWNER=${OWNER:-$HOME/private-acoustic-fixtures/stt-eval-2026-11/owner-decision/16k}
BREADTH=${BREADTH:-$HOME/private-acoustic-fixtures/stt-eval-breadth/16k}
A=ai.jandal.spike1988.etprobe
RUNNER=$A.test/androidx.test.runner.AndroidJUnitRunner
APK=$here/../probe/build/outputs/apk
HF=$HOME/.cache/huggingface/hub
declare -A REV=([s]=87a6b5d093723fe37e9f69ad26307a3b95a31af0 [m]=7d6e9145109a5bece4f05831d046e93d27e38a99 [l]=79d1c23bc8f1174cee576b40bbaa0118bd95abed)
mkdir -p "$out"

"$here/device-lock.sh" acquire "$phone" "$agent"   # exits non-zero (and we stop) if held
stay=$(adb -s $S shell settings get global stay_on_while_plugged_in | tr -d '\r')
cleanup() {
  adb -s $S shell "run-as $A rm -rf files/spike1988/owner files/spike1988/breadth files/spike1988-out" || true
  adb -s $S shell rm -rf /data/local/tmp/spike1988 || true
  adb -s $S shell settings put global stay_on_while_plugged_in "$stay" || true
  "$here/device-lock.sh" release "$phone" "$agent"
}
trap cleanup EXIT
adb -s $S shell svc power stayon usb

adb -s $S install -r "$APK/debug/probe-debug.apk" >/dev/null
adb -s $S install -r "$APK/androidTest/debug/probe-debug-androidTest.apk" >/dev/null
sha256sum "$APK/debug/probe-debug.apk" "$APK/androidTest/debug/probe-debug-androidTest.apk" > "$out/apk.sha256"

stage() { # stage <host file> <app-relative dir>
  adb -s $S push "$1" /data/local/tmp/spike1988/stage.bin >/dev/null
  adb -s $S shell "run-as $A mkdir -p files/$2 && run-as $A cp /data/local/tmp/spike1988/stage.bin files/$2/$(basename "$1")"
}
adb -s $S shell mkdir -p /data/local/tmp/spike1988
have() { adb -s $S shell "run-as $A sha256sum files/$1 2>/dev/null" | cut -c1-64; }
for sz in s m l; do
  et=$HF/models--futo-org--asr4all-$sz/snapshots/${REV[$sz]}/executorch/xnnpack_int8
  for f in asr_encoder.pte metadata.json; do
    [[ $(have spike1988/$sz/$f) == $(sha256sum "$et/$f" | cut -c1-64) ]] || stage "$et/$f" spike1988/$sz
  done
  for f in metadata.json stream_c16r4.onnx stream_c32r8.onnx pcec_c16.onnx pcec_c16_flush.onnx asr4all.onnx.data; do
    [[ $(have spike1988/onnx-$sz/$f) == $(sha256sum "$CACHE/onnx-$sz/$f" | cut -c1-64) ]] || stage "$CACHE/onnx-$sz/$f" spike1988/onnx-$sz
  done
done
for f in 1 2 3 4 mlk; do stage "$CACHE/public/$f.wav" spike1988/public; done
[[ $phases == *bench* ]] && for f in "$OWNER"/*.wav; do stage "$f" spike1988/owner; done
[[ $phases == *breadth* ]] && for f in "$BREADTH"/*.wav; do stage "$f" spike1988/breadth; done
[[ $phases == *long* ]] && stage "$CACHE/public/long-public.wav" spike1988/long
adb -s $S shell "run-as $A sh -c 'cd files/spike1988 && sha256sum */*.pte */*.onnx */*.data */*.json public/*.wav'" > "$out/staged.sha256"

instr() { adb -s $S shell am instrument -w "$@" "$RUNNER" | tr -d '\r'; }
pull() { adb -s $S exec-out run-as $A cat "files/spike1988-out/$1" > "$out/$(basename "$1")" || echo "PULL FAILED $1" | tee -a "$out/lanes.log"; }

if [[ $phases == *parity* ]]; then
  for sz in s m l; do
    instr -e class ai.jandal.spike1988.ParityTest -e size $sz -e clips 1.wav,2.wav,3.wav,4.wav,mlk.wav | tail -1
    pull parity-$sz-default.json
  done
fi

bat() { adb -s $S shell dumpsys battery | tr -d '\r' | awk '$1 == "temperature:" {print $2 / 10; exit}'; }
if [[ $phases == *smoke* ]]; then
  for rt in et onnx; do
    instr -e class ai.jandal.spike1988.BenchmarkTest -e runtime $rt -e size s -e tier stream_c16r4 \
      -e mode paced -e set public -e clips spike1988/public | tail -1
    pull bench/public-paced-$rt-s-stream_c16r4-default.json
  done
fi
if [[ $phases == *bench* ]]; then
  for sz in s m l; do for tier in stream_c16r4 stream_c32r8; do for rt in et onnx; do
    for set in owner public; do
      echo "$(date +%T) lane $rt $sz $tier $set temp=$(bat)C" | tee -a "$out/lanes.log"
      instr -e class ai.jandal.spike1988.BenchmarkTest -e runtime $rt -e size $sz -e tier $tier \
        -e mode paced -e set $set -e clips spike1988/$set | tail -1 | tee -a "$out/lanes.log"
      pull bench/$set-paced-$rt-$sz-$tier-default.json
    done
    sleep 10
  done; done; done
fi
if [[ $phases == *breadth* ]]; then
  for sz in s m l; do for tier in stream_c16r4 stream_c32r8; do for rt in et onnx; do
    echo "$(date +%T) lane $rt $sz $tier breadth temp=$(bat)C" | tee -a "$out/lanes.log"
    instr -e class ai.jandal.spike1988.BenchmarkTest -e runtime $rt -e size $sz -e tier $tier \
      -e mode compute -e set breadth -e clips spike1988/breadth | tail -1 | tee -a "$out/lanes.log"
    pull bench/breadth-compute-$rt-$sz-$tier-default.json
  done; done; done
fi
if [[ $phases == *long* ]]; then
  # One 66.7 s public stream (clips 1,2,3,mlk twice): sustained paced behaviour.
  for sz in s m l; do for tier in stream_c16r4 stream_c32r8; do for rt in et onnx; do
    echo "$(date +%T) lane $rt $sz $tier long temp=$(bat)C" | tee -a "$out/lanes.log"
    instr -e class ai.jandal.spike1988.BenchmarkTest -e runtime $rt -e size $sz -e tier $tier \
      -e mode paced -e set long -e clips spike1988/long | tail -1 | tee -a "$out/lanes.log"
    pull bench/long-paced-$rt-$sz-$tier-default.json
    sleep 10
  done; done; done
fi
echo DONE
