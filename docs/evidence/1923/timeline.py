#!/usr/bin/env python3
"""Per-run chat voice timeline from `adb logcat -v epoch` captures (#1923).

usage: timeline.py LOG [LOG...]
Times are ms relative to the turn's `generate_called` (TTFT_STAGE).

Rows:
- engine_first_visible_token: LiteRtInferenceEngine emits the first visible (non-thinking) token (TTFT line).
- vm_first_token: ChatViewModel collects that token (TTFT_STAGE first_token).
- first_sentence_ready: the visible reply first holds a sentence end at or after 40 chars
  (VOICE_FIRST_CHUNK_MIN_LENGTH), i.e. when a live first chunk could have been queued. Rebuilt from the
  engine's per-callback `event_seq` lines.
- engine_lastToken: VoiceTiming lastToken (generation complete, just before GenerationResult.Complete).
- vm_complete_handler: ChatViewModel handles Complete (thinking_save).
- firstChunkQueued: the finalize path queues the first speech chunk.
- sherpa_synth0_end / SpeakingStarted: first chunk synthesised / playback starts.
"""
import re
import sys

LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+(.*)$")
EPOCH = re.compile(r"\bepochMs=(\d+)")
EVENTS = [
    ("generate_called", re.compile(r"TTFT_STAGE .*stage=generate_called"), False),
    ("vm_first_thinking", re.compile(r"TTFT_STAGE .*stage=first_thinking_text "), False),
    ("engine_first_visible_token", re.compile(r"TTFT \(Time to First Token\)"), False),
    ("vm_first_token", re.compile(r"TTFT_STAGE .*stage=first_token "), False),
    ("engine_lastToken", re.compile(r"VoiceTiming lastToken epochMs="), True),
    ("vm_complete_handler", re.compile(r"thinking_save: thinkingLen="), False),
    ("firstChunkQueued", re.compile(r"firstChunkQueued epochMs="), True),
    ("SpeakingStarted", re.compile(r"SpeakingStarted engine="), True),
]
CALLBACK = re.compile(r'event_seq: \S+ seq=\d+ type=callback .* raw="((?:[^"\\]|\\.)*)" toolCalls=')
SYNTH0 = re.compile(r"synth token=\d+ chunk=0 chars=(\d+) sid=\d+ startEpochMs=(\d+) endEpochMs=(\d+) synthMs=(\d+) audioMs=(\d+)")
DECODE = re.compile(r"decode=(\d+) tokens @ ([\d.]+) tokens/s")
FIRST_CHUNK_MIN = 40  # VOICE_FIRST_CHUNK_MIN_LENGTH
# Sentence ends as the chunker sees them after normalizeChatTextForSpeech: . ! ? and non-numeric colons
# before whitespace, and line breaks. A list marker such as "1." is not an end.
SENTENCE_END = re.compile(r"(?<!\b\d)[.!?:](?=\s)|(?<=\S)\n")


def first_sentence_ready(callbacks):
    raw = ""
    for ts, delta in callbacks:
        raw += delta.replace("\\n", "\n").replace('\\"', '"').replace("\\\\", "\\")
        if "<|channel>" in raw and "<channel|>" not in raw:
            continue  # still thinking
        visible = (raw.split("<channel|>", 1)[1] if "<channel|>" in raw else raw).lstrip()
        for m in SENTENCE_END.finditer(visible):
            if m.start() + 1 >= FIRST_CHUNK_MIN:
                return ts, visible[: m.start() + 1]
    return None, None


def parse(path):
    times, callbacks, decode, synth0 = {}, [], None, None
    for line in open(path, errors="replace"):
        m = LINE.match(line)
        if not m:
            continue
        ts, msg = round(float(m.group(1)) * 1000), m.group(2)
        for name, rx, use_epoch in EVENTS:
            if name not in times and rx.search(msg):
                e = EPOCH.search(msg) if use_epoch else None
                times[name] = int(e.group(1)) if e else ts
        c = CALLBACK.search(msg)
        if c:
            callbacks.append((ts, c.group(1)))
        d = DECODE.search(msg)
        if d and decode is None:
            decode = (int(d.group(1)), float(d.group(2)))
        s = SYNTH0.search(msg)
        if s and synth0 is None:
            synth0 = tuple(int(x) for x in s.groups())  # chars, start, end, synthMs, audioMs
    return times, callbacks, decode, synth0


def main():
    for path in sys.argv[1:]:
        times, callbacks, decode, synth0 = parse(path)
        t0 = times.get("generate_called")
        print(f"== {path.rsplit('/', 1)[-1]}")
        if t0 is None:
            print("   no generate_called")
            continue
        fs_ts, fs_text = first_sentence_ready(callbacks)
        if fs_ts:
            times["first_sentence_ready"] = fs_ts
        if synth0:
            times["sherpa_synth0_end"] = synth0[2]
        order = sorted(times.items(), key=lambda kv: kv[1])
        for name, t in order:
            print(f"   {name:28s} {t - t0:+8d} ms")
        if fs_text:
            print(f"   first sentence ({len(fs_text)} chars): {fs_text!r}")
        if synth0:
            print(f"   first chunk synth: {synth0[0]} chars, {synth0[3]} ms for {synth0[4]} ms audio")
        if decode:
            print(f"   decode: {decode[0]} tokens @ {decode[1]:.2f} tok/s")
        lt, ss, fq = times.get("engine_lastToken"), times.get("SpeakingStarted"), times.get("firstChunkQueued")
        if lt and ss:
            print(f"   lastToken->firstChunkQueued {fq - lt if fq else 'NA'} ms; lastToken->SpeakingStarted {ss - lt:+d} ms")
        fv = times.get("engine_first_visible_token")
        if lt and fv:
            print(f"   visible-answer phase (first visible token -> lastToken): {lt - fv} ms")
        if lt and fs_ts and synth0:
            live = fs_ts + synth0[3]
            print(f"   projection (same synth time, no contention): live speech starts {live - lt:+d} ms vs lastToken")


if __name__ == "__main__":
    main()
