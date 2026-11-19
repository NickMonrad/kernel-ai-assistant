#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
  echo "usage: $0 OUTSIDE_REPOSITORY_DIRECTORY" >&2
  exit 2
fi

out_dir=$(realpath -m -- "$1")
repo_root=$(git rev-parse --show-toplevel)
case "$out_dir/" in
  "$repo_root/"*)
    echo "refusing to write the WAV inside the repository" >&2
    exit 2
    ;;
esac
mkdir -p -- "$out_dir"

espeak_version=$(espeak-ng --version | head -n 1)
ffmpeg_version=$(ffmpeg -version | head -n 1)
[[ "$espeak_version" == "eSpeak NG text-to-speech: 1.52.0"* ]] || {
  echo "expected eSpeak NG 1.52.0; got: $espeak_version" >&2
  exit 1
}
[[ "$ffmpeg_version" == "ffmpeg version n9.0.2"* ]] || {
  echo "expected FFmpeg n9.0.2; got: $ffmpeg_version" >&2
  exit 1
}

text='A small green notebook is beside the open window. This is a synthetic speech fixture for offline recognition parity, not a real recording.'
if [[ -e "$out_dir/clip.wav" || -L "$out_dir/clip.wav" ]]; then
  echo "refusing to overwrite existing $out_dir/clip.wav" >&2
  exit 2
fi

tmp_dir=$(mktemp -d "$out_dir/.asr4all-parity.XXXXXXXX")
trap 'rm -rf -- "$tmp_dir"' EXIT
tmp_wav="$tmp_dir/espeak-source.wav"
tmp_final="$tmp_dir/clip.wav"

espeak-ng -v en-us -s 150 -p 50 -a 100 -w "$tmp_wav" "$text"
ffmpeg -nostdin -hide_banner -loglevel error -y -i "$tmp_wav" \
  -ar 16000 -ac 1 -c:a pcm_s16le -map_metadata -1 -fflags +bitexact -flags:a +bitexact \
  "$tmp_final"

expected=5b8e2fd35bf3d63db1e106eea1f2d860210572850c4d147f499506e761cf21f2
actual=$(sha256sum "$tmp_final" | cut -d ' ' -f 1)
[[ "$actual" == "$expected" ]] || {
  echo "fixture digest mismatch: expected $expected, got $actual" >&2
  exit 1
}
ln -T -- "$tmp_final" "$out_dir/clip.wav"
printf 'fixture=%s\nsha256=%s\neSpeak=%s\nFFmpeg=%s\n' \
  "$out_dir/clip.wav" "$actual" "$espeak_version" "$ffmpeg_version"
