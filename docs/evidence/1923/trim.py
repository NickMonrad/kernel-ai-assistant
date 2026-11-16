#!/usr/bin/env python3
"""Trims a full `adb logcat -v epoch` capture to the app's timing lines for docs/evidence/1923 (#1923).

usage: trim.py IN OUT
Keeps only timing/voice lines from the Jandal process. Thinking text is replaced by its length (it can
quote the system prompt); visible reply text is kept. Other apps' lines are dropped.
"""
import re
import sys

KEEP = re.compile(
    r"TTFT_STAGE|TTFT \(Time to First Token\)|LiteRT benchmark|Generation complete|VoiceTiming|"
    r"SpeakingStarted engine=|thinking_save|llm_tools_assistant_reply: len|llm_tools_tool_sequence|"
    r"speech probe (start|result)|event_seq: \S+ seq=\d+ type=(callback|complete)|generation_diagnostic|"
    r"external_prompt_trust|llm_tools_route"
)
LINE = re.compile(r"^\s*(\d+\.\d+)\s+(\d+)\s+")
RAW = re.compile(r'thought="(?:[^"\\]|\\.)*" raw="((?:[^"\\]|\\.)*)"')


def main(src, dst):
    lines = open(src, errors="replace").read().splitlines()
    pid = None
    for line in lines:
        if "TTFT_STAGE" in line:
            pid = LINE.match(line).group(2)
            break
    in_thinking = False
    out = []
    for line in lines:
        m = LINE.match(line)
        if not m:
            continue
        # overlap.sh writes the run's thermal status from adb shell (another pid).
        if "Evidence1923" not in line and (m.group(2) != pid or not KEEP.search(line)):
            continue
        r = RAW.search(line)
        if r:
            raw = r.group(1)
            opens = "<|channel>" in raw
            if opens:
                in_thinking = True
            if in_thinking:
                closes = "<channel|>" in raw
                visible = raw.split("<channel|>", 1)[1] if closes else ""
                # Channel markers stay so timeline.py can still find where the visible reply starts.
                shown = ("<|channel>" if opens else "") + f"[thinking {len(raw) - len(visible)} chars]"
                if closes:
                    shown += "<channel|>" + visible
                    in_thinking = False
            else:
                shown = raw
            line = line[: r.start()] + f'raw="{shown}"' + line[r.end():]
        out.append(line)
    open(dst, "w").write("\n".join(out) + "\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
