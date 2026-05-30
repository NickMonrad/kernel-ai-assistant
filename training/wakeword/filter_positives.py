#!/usr/bin/env python3
"""
Filter TTS positive clips using Whisper tiny.
Runs whisper via uvx subprocess per-file, quarantines bad clips.
"""
import re
import sys
import wave
import shutil
import subprocess
from pathlib import Path

GREETING = re.compile(r"\b(hey|hai|hi|hei|aye|ay)\b", re.I)
JANDAL = re.compile(
    r"\b(jand|jando|jandle|jandal|jandel|jandl|jandof|"
    r"gand|gendo|gendle|gendel|gandalf|"
    r"chandl|chando|chandle|chandel|"
    r"hamdo|hando|hichando|"
    r"jamdo|jamble|jambel|jambal|jamboo|jandu|jandon|"
    r"handel|hando)\w*",
    re.I,
)
HARD_REJECT = re.compile(
    r"\b(handle|handles|handled|handler|handoff|handover|"
    r"channel|channels|candle|scandal|gentle|general|"
    r"jungle|gandalf|randall|kendall)\b",
    re.I,
)


def get_duration(path: Path) -> float:
    with wave.open(str(path)) as w:
        return w.getnframes() / w.getframerate()


def transcribe(wav: Path) -> str:
    result = subprocess.run(
        ["uvx", "--from", "openai-whisper", "whisper",
         str(wav), "--model", "tiny", "--language", "en"],
        capture_output=True, text=True, timeout=30
    )
    # Parse the stdout line: [00:00.000 --> 00:01.000]  Hey Jandal.
    for line in result.stdout.splitlines():
        m = re.match(r"\[.*?\]\s+(.*)", line)
        if m:
            return m.group(1).strip()
    return ""


def is_good(transcript: str) -> bool:
    t = transcript.strip()
    if not t:
        return False
    # Reject hallucinations (too many words)
    if len(t.split()) > 8:
        return False
    if HARD_REJECT.search(t):
        return False
    if not JANDAL.search(t):
        return False
    return True


def main():
    pos_dir = Path("training/wakeword/data/positives")
    quar_dir = Path("training/wakeword/data/quarantine")
    quar_dir.mkdir(parents=True, exist_ok=True)

    tts_files = sorted(pos_dir.glob("tts_*.wav"))
    print(f"Filtering {len(tts_files)} TTS clips...", flush=True)

    kept = quarantined = 0
    log_lines = []

    for i, wav in enumerate(tts_files):
        dur = get_duration(wav)
        try:
            transcript = transcribe(wav)
        except Exception as e:
            transcript = f"ERROR: {e}"

        good = is_good(transcript)
        status = "KEEP" if good else "QUAR"
        log_lines.append(f"{status}\t{wav.name}\t{dur:.2f}s\t{transcript!r}")

        if not good:
            shutil.move(str(wav), quar_dir / wav.name)
            quarantined += 1
        else:
            kept += 1

        if (i + 1) % 25 == 0:
            print(f"  {i+1}/{len(tts_files)} — kept={kept} quarantined={quarantined}", flush=True)

    log_path = Path("training/wakeword/data/filter_log.tsv")
    log_path.write_text("\n".join(log_lines))
    pct = 100 * quarantined // (kept + quarantined) if (kept + quarantined) else 0
    print(f"\nDone: {kept} kept, {quarantined} quarantined ({pct}% removed)")
    print(f"Log: {log_path}")


if __name__ == "__main__":
    main()
