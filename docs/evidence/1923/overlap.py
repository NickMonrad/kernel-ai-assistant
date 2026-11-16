#!/usr/bin/env python3
"""Decode and TTS speed with and without TTS synthesis overlapping generation (#1923).

usage: overlap.py LOG [LOG...]   (logs from overlap.sh, named <device>-ab<N>-<none|probe>-run1.log)
Per run: LiteRT's decode tok/s, thermal status at the start (when overlap.sh logged it), and how much of the
decode window (first engine callback -> lastToken) had Sherpa synthesis running and audio playing.
Per pair abN (same device, back to back): probe vs none decode change. Runs whose turn called a tool are
excluded: their benchmark line covers only the post-tool pass.
TTS side: real-time factor (synth ms / audio ms) of probe chunks synthesised entirely during decode vs the
reply chunks of the `none` runs, all synthesised after lastToken with no decode running.
"""
import re
import statistics
import sys

LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+(.*)$")
DECODE = re.compile(r"decode=(\d+) tokens @ ([\d.]+) tokens/s")
CALLBACK = re.compile(r"event_seq: \S+ seq=\d+ type=callback")
LAST = re.compile(r"VoiceTiming lastToken epochMs=(\d+)")
SYNTH = re.compile(r"synth token=(\d+) chunk=\d+ .*startEpochMs=(\d+) endEpochMs=(\d+) synthMs=(\d+) audioMs=(\d+)")
PLAY_START = re.compile(r"playStart token=(\d+) chunk=(\d+) epochMs=(\d+)")
PLAY_END = re.compile(r"playEnd token=(\d+) chunk=(\d+) epochMs=(\d+)")
THERMAL = re.compile(r"thermal_status_at_start=(\d+)")
TOOL = re.compile(r"event=tool_call_received")
NAME = re.compile(r"(?P<device>s23u|s21)-ab(?P<pair>\d+)-(?P<mode>none|probe)-")
MIN_DECODE_TOKENS = 40  # shorter replies give no usable rate (one S21 reply was 17 tokens)


def covered(intervals, lo, hi):
    return sum(max(0, min(b, hi) - max(a, lo)) for a, b in intervals)


def parse(path):
    run = {"decode": None, "first_cb": None, "last": None, "thermal": None, "tool": False}
    synth, plays, open_plays = [], [], {}
    for line in open(path, errors="replace"):
        m = LINE.match(line)
        if not m:
            continue
        ts, msg = round(float(m.group(1)) * 1000), m.group(2)
        if run["decode"] is None and (d := DECODE.search(msg)):
            run["decode"] = (int(d.group(1)), float(d.group(2)))
        if run["first_cb"] is None and CALLBACK.search(msg):
            run["first_cb"] = ts
        if run["last"] is None and (l := LAST.search(msg)):
            run["last"] = int(l.group(1))
        if run["thermal"] is None and (t := THERMAL.search(msg)):
            run["thermal"] = int(t.group(1))
        if TOOL.search(msg):
            run["tool"] = True
        if s := SYNTH.search(msg):
            synth.append(tuple(int(x) for x in s.groups()))  # token, start, end, synthMs, audioMs
        if p := PLAY_START.search(msg):
            open_plays[(p.group(1), p.group(2))] = int(p.group(3))
        if p := PLAY_END.search(msg):
            start = open_plays.pop((p.group(1), p.group(2)), None)
            if start:
                plays.append((start, int(p.group(3))))
    lo, hi = run["first_cb"], run["last"]
    if lo and hi:
        run["synth_pct"] = 100 * covered([(s[1], s[2]) for s in synth], lo, hi) / (hi - lo)
        run["play_pct"] = 100 * covered(plays, lo, hi) / (hi - lo)
        # A chunk counts as "during decode" when at least half its synthesis ran inside the decode window.
        run["rtf_during"] = [
            s[3] / s[4] for s in synth if s[4] > 0 and covered([(s[1], s[2])], lo, hi) * 2 >= (s[2] - s[1])
        ]
        run["rtf_after"] = [s[3] / s[4] for s in synth if s[1] >= hi and s[4] > 0]
    return run


def main():
    pairs, rtf_during, rtf_after = {}, {}, {}
    for path in sys.argv[1:]:
        name = path.rsplit("/", 1)[-1]
        key = NAME.match(name)
        run = parse(path)
        if not key or not run["decode"] or "synth_pct" not in run:
            print(f"{name}: incomplete")
            continue
        thermal = "?" if run["thermal"] is None else run["thermal"]
        excluded = run["tool"] or run["decode"][0] < MIN_DECODE_TOKENS
        note = " TOOL CALL (excluded)" if run["tool"] else " TOO SHORT (excluded)" if excluded else ""
        print(f"{name}: decode {run['decode'][0]} tok @ {run['decode'][1]:.2f} tok/s; thermal {thermal}; "
              f"synth during decode {run['synth_pct']:.0f}%, playback {run['play_pct']:.0f}%{note}")
        if excluded:
            continue
        pairs.setdefault((key["device"], int(key["pair"])), {})[key["mode"]] = run["decode"][1]
        if key["mode"] == "probe":
            rtf_during.setdefault(key["device"], []).extend(run["rtf_during"])
        else:
            rtf_after.setdefault(key["device"], []).extend(run["rtf_after"])
    by_device = {}
    for (device, pair), modes in sorted(pairs.items()):
        if "none" in modes and "probe" in modes:
            change = 100 * (modes["probe"] - modes["none"]) / modes["none"]
            by_device.setdefault(device, []).append(change)
            print(f"{device} ab{pair}: none {modes['none']:.2f} -> probe {modes['probe']:.2f} tok/s ({change:+.1f}%)")
    for device, changes in sorted(by_device.items()):
        print(f"{device}: {len(changes)} pairs, decode median change {statistics.median(changes):+.1f}% "
              f"(range {min(changes):+.1f}% to {max(changes):+.1f}%)")
    for device in sorted(rtf_during):
        d, a = rtf_during[device], rtf_after.get(device, [])
        if d and a:
            print(f"{device}: TTS real-time factor during decode median {statistics.median(d):.2f} (n={len(d)}); "
                  f"after generation, no decode {statistics.median(a):.2f} (n={len(a)})")


if __name__ == "__main__":
    main()
