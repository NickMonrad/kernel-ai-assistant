#!/usr/bin/env python3
"""
Post-process batch whisper --output_format txt output to filter TTS positives.
Run AFTER: uvx --from openai-whisper whisper tts_*.wav --model tiny --language en
           --output_format txt --output_dir /tmp/whisper_tts_out
"""
import re, shutil
from pathlib import Path

JANDAL = re.compile(
    r"\b(jand|jando|jandle|jandal|jandel|jandl|"
    r"gand|gendo|gendle|gendel|"
    r"chandl|chando|chandle|chandel|"
    r"hamdo|hando|hichando|"
    r"jamdo|jamble|jambel|jambal|jandof|jandu|jandon|"
    r"handel)\w*",
    re.I,
)
HARD_REJECT = re.compile(
    r"\b(handle|handles|handled|handler|handoff|handover|"
    r"channel|channels|candle|scandal|gentle|general|"
    r"jungle|gandalf|randall|kendall)\b",
    re.I,
)

def is_good(transcript: str) -> bool:
    t = transcript.strip()
    if not t or len(t.split()) > 8:
        return False
    if HARD_REJECT.search(t):
        return False
    return bool(JANDAL.search(t))

pos_dir  = Path("training/wakeword/data/positives")
quar_dir = Path("training/wakeword/data/quarantine")
txt_dir  = Path("/tmp/whisper_tts_out")
quar_dir.mkdir(parents=True, exist_ok=True)

kept = quarantined = missing = 0
log = []

for wav in sorted(pos_dir.glob("tts_*.wav")):
    txt = txt_dir / (wav.stem + ".txt")
    if not txt.exists():
        missing += 1
        log.append(f"MISS\t{wav.name}\t(no transcript)")
        continue
    transcript = txt.read_text().strip()
    good = is_good(transcript)
    status = "KEEP" if good else "QUAR"
    log.append(f"{status}\t{wav.name}\t{transcript!r}")
    if not good:
        shutil.move(str(wav), quar_dir / wav.name)
        quarantined += 1
    else:
        kept += 1

Path("training/wakeword/data/filter_log.tsv").write_text("\n".join(log))
total = kept + quarantined
pct = 100 * quarantined // total if total else 0
print(f"Kept: {kept}  Quarantined: {quarantined} ({pct}%)  Missing transcripts: {missing}")
print(f"Remaining in positives/: {kept + missing}")
