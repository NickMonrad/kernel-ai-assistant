#!/usr/bin/env bash
# Host-side run of every verifier option over the prepared rings (#1045 spike).
# Accuracy passes run in two parallel waves; timing passes then run one engine at a time.
# usage: run_host.sh <python> <private-root> <work-dir> <out-dir>
set -euo pipefail
py=$1 private=$2 work=$3 out=$4
here=$(cd "$(dirname "$0")" && pwd)
ev() { "$py" "$here/wake_eval.py" engine "$1" --private "$private" --work "$work" "${@:2}"; }
ASR4ALL=(asr4all-m-stream_c16r4 asr4all-m-stream_c32r8 asr4all-l-stream_c16r4 asr4all-l-stream_c32r8)
OTHERS=(whisper-tiny-en zipformer kws-t0.25-s1.0 kws-t0.15-s1.5 kws-t0.08-s2.0)
for wave in "${ASR4ALL[*]}" "${OTHERS[*]}"; do
  pids=()
  for e in $wave; do ev "$e" > "$work/log-$e.txt" 2>&1 & pids+=($!); done
  for p in "${pids[@]}"; do wait "$p"; done
done
for e in "${ASR4ALL[@]}" "${OTHERS[@]}"; do ev "$e" --latency 200 > "$work/log-latency-$e.txt" 2>&1; done
"$py" "$here/wake_eval.py" score --work "$work" --out "$out"
