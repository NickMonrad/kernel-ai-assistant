"""ASR intelligibility for #2116 WAVs: faster-whisper medium.en (CPU, int8, local cache only).

Run with a venv that has faster-whisper (this spike used faster-whisper 1.2.1 / ctranslate2 4.8.5).
  asr_2116.py --wav-root OUT --voices V ... --models NAME ... --out OUT/asr.json
Reference text per chunk comes from phonemes.json written by pc_eval_2116.py synth.
Word error rate is computed after lowercasing and stripping punctuation; numbers are already spelled out.
"""
import argparse
import json
import re
from pathlib import Path

from faster_whisper import WhisperModel


def norm_words(text: str) -> list[str]:
    text = text.lower().replace("-", " ")
    text = re.sub(r"[^a-z' ]+", " ", text)
    return text.split()


def wer(ref: list[str], hyp: list[str]) -> tuple[int, int]:
    """Returns (edit distance, len(ref))."""
    d = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, start=1):
        prev, d[0] = d[0], i
        for j, h in enumerate(hyp, start=1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r != h))
            prev, d[j] = d[j], cur
    return d[len(hyp)], len(ref)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--wav-root", required=True)
    p.add_argument("--models", nargs="+", required=True)
    p.add_argument("--voices", nargs="+", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--model-size", default="medium.en")
    args = p.parse_args()

    root = Path(args.wav_root)
    chunks = json.loads((root / "phonemes.json").read_text(encoding="utf-8"))["chunks"]
    refs = {f"s{c['sentence']:02d}-c{c['chunk']:02d}": c["text"] for c in chunks}
    asr = WhisperModel(args.model_size, device="cpu", compute_type="int8", local_files_only=True)

    results = []
    for model in args.models:
        for voice in args.voices:
            for key, ref_text in refs.items():
                wav = root / f"{model}/{voice}/{key}.wav"
                segments, _ = asr.transcribe(str(wav), language="en", beam_size=5, vad_filter=False)
                hyp_text = " ".join(s.text.strip() for s in segments)
                err, n = wer(norm_words(ref_text), norm_words(hyp_text))
                results.append({"model": model, "voice": voice, "chunk": key, "errors": err, "ref_words": n,
                                "hyp": hyp_text})
                print(model, voice, key, f"{err}/{n}", flush=True)
    Path(args.out).write_text(json.dumps(results, indent=1, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
