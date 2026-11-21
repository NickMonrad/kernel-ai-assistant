#!/usr/bin/env bash
# Wake-verifier device confirmation (#1045 spike). Benchmark-only; follows the device rules in
# .docs/agents/risk-based-evidence-policy.md (explicit serial, lock, stay-awake restore, cleanup).
#
#   whisper  production path: the debug WakeVerifierProbeReceiver (verifyWakeWindow ->
#            transcribeBlocking -> Whisper tiny.en -> containsWakePhrase) on every exported ring,
#            two runs each (run 2 - run 1 = warm verification latency).
#   et       asr4all M via ExecuTorch, using the isolated #1988 probe (ai.jandal.spike1988.etprobe,
#            BenchmarkTest mode=compute) on the same rings, one process per tier.
#
#   kws      sherpa-onnx open-vocabulary keyword spotter (KwsProbe under app_process, device-kws/).
#
# usage: device_run.sh s21|s23u <agent-id> <rings-dir> <private-out-dir> [whisper,et,kws]
# env:   APK (app debug APK), PROBE_APK_DIR (#1988 probe build/outputs/apk), ET_TIERS, ET_SIZES,
#        KWS_DIR (device-kws/build.sh output + keywords.txt), KWS_MODEL, KWS_CFG ("threshold:score ...")
# None of the phases needs the screen; the script never wakes, unlocks or turns off a phone.
# Private outputs (transcripts, per-ring verdicts) stay in <private-out-dir>, outside Git.
set -euo pipefail
phone=$1 agent=$2 rings=$3 out=$4 phases=${5:-whisper,et}
case $phone in s23u) S=RFCW11265XH ;; s21) S=R5CR605B71K ;; *) echo "phone?" >&2; exit 2 ;; esac
here=$(cd "$(dirname "$0")" && pwd)
APP=ai.jandal.assistant.debug
APK=${APK:-$here/../../../../app/build/outputs/apk/debug/app-debug.apk}
PROBE=ai.jandal.spike1988.etprobe
PROBE_APK_DIR=${PROBE_APK_DIR:-}
ET_TIERS=${ET_TIERS:-stream_c16r4 stream_c32r8}
ET_SIZES=${ET_SIZES:-m}
HF=$HOME/.cache/huggingface/hub
declare -A REV=([m]=7d6e9145109a5bece4f05831d046e93d27e38a99 [l]=79d1c23bc8f1174cee576b40bbaa0118bd95abed)
WHISPER_SRC=${WHISPER_SRC:-$HOME/private-acoustic-fixtures/verifier-models}
WHISPER_FILES=(sherpa-whisper-tiny.en-encoder.int8.onnx sherpa-whisper-tiny.en-decoder.int8.onnx sherpa-whisper-tiny.en-tokens.txt)
MODELS=/sdcard/Android/data/$APP/files/models
TMP=/data/local/tmp/wake-verifier
a() { adb -s "$S" "$@"; }
mkdir -p "$out" && chmod 700 "$out"

lock=/tmp/jandal-$phone.lock
if ! mkdir "$lock" 2>/dev/null; then echo "LOCK HELD: $(tr '\n' ' ' < "$lock/owner" 2>/dev/null)" >&2; exit 1; fi
printf '%s\n%s\n' "$agent" "$(date +%s)" > "$lock/owner"

stay=$(a shell settings get global stay_on_while_plugged_in | tr -d '\r')
pushed_models=()
fixtures_dir_existed=
orig_apk=
probe_installed=
cleanup() {
  set +e
  a shell "run-as $APP sh -c 'rm -f files/acoustic-fixtures/wv_*.wav'" 2>/dev/null
  [[ $fixtures_dir_existed == no ]] && a shell "run-as $APP rmdir files/acoustic-fixtures" 2>/dev/null
  for f in "${pushed_models[@]}"; do a shell rm -f "$MODELS/$f"; done
  if [[ -n $orig_apk ]]; then a install -r -d "$orig_apk" >/dev/null && echo "restored original $APP APK" >> "$out/run.log"; fi
  if [[ -n $probe_installed ]]; then a uninstall "$PROBE.test" >/dev/null; a uninstall "$PROBE" >/dev/null; fi
  a shell rm -rf "$TMP"
  [[ -n ${logcat_pid:-} ]] && kill "$logcat_pid" 2>/dev/null
  a shell settings put global stay_on_while_plugged_in "$stay"
  [[ $(head -n1 "$lock/owner") == "$agent" ]] && rm -rf "$lock"
  echo "cleanup done $(date +%T)" >> "$out/run.log"
}
trap cleanup EXIT
a shell svc power stayon usb
a shell mkdir -p "$TMP"
tar -C "$rings" -cf "$out/rings.tar" $(cd "$rings" && ls wv_*.wav)
a push "$out/rings.tar" "$TMP/rings.tar" >/dev/null
a shell chmod 644 "$TMP/rings.tar"
mapfile -t names < <(cd "$rings" && ls wv_*.wav)
{ echo "device $S $(a shell getprop ro.product.model | tr -d '\r') $(a shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "rings ${#names[@]} sha256 $(sha256sum "$out/rings.tar" | cut -c1-16)"; } > "$out/run.log"

if [[ $phases == *whisper* ]]; then
  # Keep the build that was installed so it can be put back exactly.
  orig_path=$(a shell pm path $APP | tr -d '\r' | sed -n '1s/^package://p')
  a pull "$orig_path" "$out/orig-base.apk" >/dev/null && orig_apk=$out/orig-base.apk
  echo "orig apk $(sha256sum "$orig_apk" | cut -c1-64)" >> "$out/run.log"
  a install -r -d "$APK" >/dev/null
  echo "test apk $(sha256sum "$APK" | cut -c1-64) $(a shell dumpsys package $APP | grep -m1 lastUpdateTime | tr -d '\r ')" >> "$out/run.log"
  for f in "${WHISPER_FILES[@]}"; do
    if ! a shell ls "$MODELS/$f" >/dev/null 2>&1; then a push "$WHISPER_SRC/$f" "$MODELS/$f" >/dev/null; pushed_models+=("$f"); fi
    echo "model $f $(a shell sha256sum "$MODELS/$f" | cut -c1-64) pushed=$([[ " ${pushed_models[*]} " == *" $f "* ]] && echo yes || echo no)" >> "$out/run.log"
  done
  if a shell "run-as $APP ls files/acoustic-fixtures" >/dev/null 2>&1; then fixtures_dir_existed=yes; else fixtures_dir_existed=no; fi
  a shell "run-as $APP sh -c 'mkdir -p files/acoustic-fixtures && cd files/acoustic-fixtures && tar xf $TMP/rings.tar'"
  rcv=$APP/com.kernel.ai.debug.verifier.WakeVerifierProbeReceiver
  act=com.kernel.ai.debug.action.VERIFY_WAKE_WINDOW
  # Start the process without loading a verifier (rejected name), then baseline memory.
  a shell am broadcast -a $act -n $rcv --es fixture_file x/y >/dev/null
  sleep 3
  pid=$(a shell pidof $APP | tr -d '\r' || true)
  pss0=$(a shell dumpsys meminfo $APP | tr -d '\r' | awk '/TOTAL PSS:/ && !x {print $3; x=1}')
  start=$(a shell date +%s)
  # Stream the probe's log lines while it runs (the ring buffer could rotate on a long run).
  adb -s "$S" logcat -v epoch -s WakeVerifierProbe:I -T "$start.000" > "$out/whisper-logcat.txt" &
  logcat_pid=$!
  printf '%s\n' "${names[@]}" > "$out/names.txt"
  a push "$out/names.txt" "$TMP/names.txt" >/dev/null
  a shell "while read f; do echo \"\$f \$(date +%s%3N) \$(am broadcast -a $act -n $rcv --es fixture_file \$f --ei runs 2 | tail -1)\"; done < $TMP/names.txt" \
    | tr -d '\r' > "$out/whisper-broadcasts.txt"
  pss1=$(a shell dumpsys meminfo $APP | tr -d '\r' | awk '/TOTAL PSS:/ && !x {print $3; x=1}')
  pid1=$(a shell pidof $APP | tr -d '\r' || true)
  hwm=$(a shell "run-as $APP cat /proc/$pid1/status" | tr -d '\r' | awk '/VmHWM/ {print $2}')
  sleep 2; kill "$logcat_pid"; wait "$logcat_pid" 2>/dev/null || true
  sed -i 's/\r$//' "$out/whisper-logcat.txt"
  printf '{"pid_before": %s, "pid_after": %s, "pss_before_kb": %s, "pss_after_kb": %s, "vm_hwm_kb": %s}\n' \
    "$pid" "$pid1" "$pss0" "$pss1" "${hwm:-null}" > "$out/whisper-memory.json"
  echo "whisper done $(date +%T)" >> "$out/run.log"
fi

if [[ $phases == *et* ]]; then
  [[ -n $PROBE_APK_DIR ]] || { echo "PROBE_APK_DIR required" >&2; exit 2; }
  a install -r "$PROBE_APK_DIR/debug/probe-debug.apk" >/dev/null
  a install -r "$PROBE_APK_DIR/androidTest/debug/probe-debug-androidTest.apk" >/dev/null
  probe_installed=1
  echo "probe apks $(sha256sum "$PROBE_APK_DIR/debug/probe-debug.apk" "$PROBE_APK_DIR/androidTest/debug/probe-debug-androidTest.apk" | cut -c1-64 | tr '\n' ' ')" >> "$out/run.log"
  for sz in $ET_SIZES; do
    et=$HF/models--futo-org--asr4all-$sz/snapshots/${REV[$sz]}/executorch/xnnpack_int8
    for f in asr_encoder.pte metadata.json; do
      a push "$et/$f" "$TMP/$f" >/dev/null
      a shell "run-as $PROBE sh -c 'mkdir -p files/spike1988/$sz && cp $TMP/$f files/spike1988/$sz/$f'"
      a shell rm -f "$TMP/$f"
    done
  done
  a shell "run-as $PROBE sh -c 'mkdir -p files/spike1988/wake && cd files/spike1988/wake && tar xf $TMP/rings.tar'"
  a shell "run-as $PROBE sh -c 'cd files/spike1988 && sha256sum */asr_encoder.pte'" | tr -d '\r' >> "$out/run.log"
  for sz in $ET_SIZES; do for tier in $ET_TIERS; do
    temp=$(a shell dumpsys battery | tr -d '\r' | awk '$1 == "temperature:" && !x {print $2 / 10; x=1}')
    echo "$(date +%T) et $sz $tier temp=${temp}C" >> "$out/run.log"
    a shell am instrument -w -e class ai.jandal.spike1988.BenchmarkTest -e runtime et -e size "$sz" -e tier "$tier" \
      -e mode compute -e set wake -e clips spike1988/wake -e warmup spike1988/wake/${names[0]} \
      $PROBE.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tail -2 >> "$out/run.log"
    a exec-out run-as $PROBE cat "files/spike1988-out/bench/wake-compute-et-$sz-$tier-default.json" \
      > "$out/wake-compute-et-$sz-$tier-default.json"
    sleep 10
  done; done
  echo "et done $(date +%T)" >> "$out/run.log"
fi

if [[ $phases == *kws* ]]; then
  # sherpa-onnx KWS lane under app_process (device-kws/build.sh output + the pinned KWS model).
  [[ -n ${KWS_DIR:-} && -n ${KWS_MODEL:-} ]] || { echo "KWS_DIR and KWS_MODEL required" >&2; exit 2; }
  a shell mkdir -p "$TMP/kws/lib" "$TMP/kws/model" "$TMP/kws/rings"
  a push "$KWS_DIR/classes.dex" "$TMP/kws/" >/dev/null
  a push "$KWS_DIR/keywords.txt" "$TMP/kws/" >/dev/null
  for f in "$KWS_DIR"/lib/*.so; do a push "$f" "$TMP/kws/lib/" >/dev/null; done
  for f in encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx \
           joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx tokens.txt; do a push "$KWS_MODEL/$f" "$TMP/kws/model/" >/dev/null; done
  a shell "cd $TMP/kws/rings && tar xf $TMP/rings.tar"
  for c in ${KWS_CFG:-0.25:1.0 0.08:2.0}; do
    t=${c%%:*} sc=${c##*:}
    echo "$(date +%T) kws t=$t s=$sc" >> "$out/run.log"
    a shell "cd $TMP/kws && LD_LIBRARY_PATH=$TMP/kws/lib CLASSPATH=$TMP/kws/classes.dex app_process \
      -Djava.library.path=$TMP/kws/lib $TMP/kws KwsProbe $TMP/kws/model $TMP/kws/keywords.txt $TMP/kws/rings \
      $TMP/kws/out.tsv $t $sc 2" 2>&1 | tr -d '\r' | tail -3 >> "$out/run.log"
    a exec-out cat "$TMP/kws/out.tsv" > "$out/kws-t$t-s$sc.tsv"
  done
  echo "kws done $(date +%T)" >> "$out/run.log"
fi
echo DONE
