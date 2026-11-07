#!/usr/bin/env python3
"""Play "Hey Jandal" then "what time is it" from the source phone for showcase shot S01 (#1736).

Uses the debug-only acoustic stimulus receiver (docs/testing/acoustic-stimulus-source.md)
with the approved fixtures already installed on the source device (``natural_wake``,
``qwen_command``) and the approved #1403/#1410 volume (9/15). The demo runner calls it as
``DEMO_WAKE_CMD``::

    DEMO_WAKE_CMD="python3 scripts/demo_wake.py --source R5CR605B71K"

Unlike the reliability runner, it does not wait for the target's listening cue: it waits a
fixed gap after the wake word (``--gap``). If the command misses the listening window, the
S01 take fails its result check and is retaken.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time

PACKAGE = "com.kernel.ai.debug"
RECEIVER = f"{PACKAGE}/com.kernel.ai.debug.acoustic.AcousticStimulusReceiver"
ACTION = "com.kernel.ai.debug.action.PLAY_ACOUSTIC_STIMULUS"
ACTIVITY = "com.kernel.ai.MainActivity"
RESULT_OK = 0  # AcousticStimulusContract.RESULT_OK


def adb(serial: str, *args: str) -> str:
    exe = os.environ.get("ADB_PATH") or shutil.which("adb") or "adb"
    res = subprocess.run([exe, "-s", serial, *args], capture_output=True, text=True, timeout=30)
    if res.returncode != 0:
        raise SystemExit(f"adb failed: {res.stderr.strip() or res.stdout.strip()}")
    return res.stdout


def fixture_durations(serial: str) -> dict[str, float]:
    raw = adb(serial, "shell", f"run-as {PACKAGE} cat files/acoustic-fixtures/manifest.json")
    try:
        manifest = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"no approved fixture manifest on {serial}: {raw.strip()[:200]}") from exc
    return {f["fixture_id"]: f["duration_ms"] / 1000.0 for f in manifest["fixtures"]}


def broadcast_outcome(am_output: str) -> tuple[int | None, str]:
    """``(result code, data)`` from ``am broadcast`` output; the receiver answers when done."""
    m = re.search(r'result=(-?\d+)(?:, data="?([^"\n]*))?', am_output)
    if not m:
        return None, am_output.strip()[:200]
    return int(m.group(1)), (m.group(2) or "").strip()


def prepare_source(serial: str) -> None:
    """Android 15+ refuses audio focus to a background app ("blocked by hardening", seen on
    the S21), so the debug app must be in front. Its own wake listener must be off, or it
    would answer the wake word it plays."""
    services = adb(serial, "shell", f"dumpsys activity services {PACKAGE}")
    if "WakeWordService" in services:
        raise SystemExit(f"Hey Jandal is listening on the source {serial}; turn it off there first")
    adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    if "isKeyguardShowing=true" in adb(serial, "shell", "dumpsys window | grep -m1 isKeyguardShowing"):
        raise SystemExit(f"source {serial} is locked; unlock it first")
    adb(serial, "shell", "am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}")
    time.sleep(1.0)


def play(serial: str, trial_id: str, fixture_id: str, volume: int) -> None:
    code, data = broadcast_outcome(adb(serial, *broadcast_args(trial_id, fixture_id, volume)))
    if code != RESULT_OK:
        raise SystemExit(f"{fixture_id} did not play on {serial}: result={code} {data}")


def broadcast_args(trial_id: str, fixture_id: str, volume: int) -> list[str]:
    return ["shell", "am", "broadcast", "-n", RECEIVER, "-a", ACTION,
            "--es", "trial_id", trial_id, "--es", "fixture_id", fixture_id,
            "--ei", "volume_index", str(volume), "--ef", "player_gain", "1.0"]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--source", default=os.environ.get("DEMO_WAKE_SOURCE", ""), help="Source device serial (the S21)")
    ap.add_argument("--volume", type=int, default=9, help="Media volume index (approved setup: 9/15)")
    ap.add_argument("--gap", type=float, default=1.5,
                    help="Seconds between the end of the wake word and the command (listening cue + margin)")
    ap.add_argument("--sequence", default="natural_wake,qwen_command",
                    help="Comma-separated fixture ids to play in order, e.g. a single push-to-talk command")
    ap.add_argument("--prepare-only", action="store_true",
                    help="Wake the source and bring the debug app to the front, then exit (before a push-to-talk tap)")
    ap.add_argument("--no-prepare", action="store_true",
                    help="Skip waking the source (already done with --prepare-only), so playback starts at once")
    args = ap.parse_args(argv)
    if not args.source:
        ap.error("--source (or DEMO_WAKE_SOURCE) is required")
    durations = fixture_durations(args.source)
    sequence = [f.strip() for f in args.sequence.split(",") if f.strip()]
    for needed in sequence:
        if needed not in durations:
            raise SystemExit(f"fixture {needed!r} is not installed on {args.source}")
    if not args.no_prepare:
        prepare_source(args.source)
    if args.prepare_only:
        return 0
    stamp = time.strftime("%Y%m%d%H%M%S")
    t0 = time.monotonic()
    for i, fixture in enumerate(sequence):
        start = time.monotonic()
        # One JSON line per fixture: when it started, so the capture runner can lay the same
        # audio into the video at the moment it was played. "mono" is CLOCK_MONOTONIC, which
        # the runner's clock shares; "t" is relative to this script's start.
        print(json.dumps({"fixture": fixture, "t": round(start - t0, 3), "mono": round(start, 3),
                          "seconds": durations[fixture]}), flush=True)
        play(args.source, f"demo-{stamp}-{i}-{fixture}", fixture, args.volume)
        # The receiver may answer before or after playback ends; wait out the clip either way.
        last = i == len(sequence) - 1
        time.sleep(max(0.0, durations[fixture] - (time.monotonic() - start)) + (0.5 if last else args.gap))
    return 0


if __name__ == "__main__":
    sys.exit(main())
