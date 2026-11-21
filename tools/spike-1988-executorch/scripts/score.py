"""Score #1988 benchmark lane reports on host. Prints aggregates only (no transcripts).

usage: python score.py <results dir> <owner phrases.json> <breadth phrases.json> [--private-detail <file>]

WER tokenisation matches #1978: NFC, lowercase, NFC, strip apostrophes, other punctuation separates tokens,
Maori macrons preserved. WER = summed word edit distance / summed reference words over the set.
"""

import json
import re
import statistics
import sys
import unicodedata
from pathlib import Path

APOSTROPHES = "'\u2019\u2018\u02bc`\u00b4"
NZ_TERMS = ["kia ora", "jandal", "tauranga", "pūkekohe", "porirua", "petone", "whanganui", "matamata", "taupō",
            "paraparaumu", "te awamutu", "taihape", "waikato", "whakatāne", "jandals", "pavlova"]


def tokens(text: str) -> list[str]:
    t = unicodedata.normalize("NFC", unicodedata.normalize("NFC", text).lower())
    t = "".join(c for c in t if c not in APOSTROPHES)
    t = "".join(c if unicodedata.category(c)[0] in "LN" else " " for c in t)
    return t.split()


def fold(text: str) -> str:
    """Drop diacritics (macrons) for the macron-insensitive NZ/Maori term check only."""
    return "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")


def edits(ref: list[str], hyp: list[str]) -> int:
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[-1]


def phrases(path: str) -> dict[str, str]:
    return {p["id"]: p["text"] for p in json.load(open(path))["phrases"]}


def wer(rows, refs, field):
    e = n = 0
    for r in rows:
        ref = tokens(refs[r["id"]])
        e += edits(ref, tokens(r[field]))
        n += len(ref)
    return 100.0 * e / n, e, n


def pct(values, q):
    v = sorted(values)
    return v[min(len(v) - 1, int(round(q * (len(v) - 1))))]


def main():
    root = Path(sys.argv[1])
    owner, breadth = phrases(sys.argv[2]), phrases(sys.argv[3])
    detail_path = sys.argv[sys.argv.index("--private-detail") + 1] if "--private-detail" in sys.argv else None
    detail = {}
    out = []
    for f in sorted(root.glob("*.json")):
        if f.name.startswith(("parity-", "apk", "staged")):
            continue
        rep = json.load(open(f))
        if "clips" not in rep:
            continue
        lane = f"{rep['runtime']} {rep['size']} {rep['tier'].replace('stream_', '')}"
        row = {"lane": lane, "set": rep["set"], "mode": rep["mode"], "load_ms": rep["load_ms"],
               "first_feed_ms": rep["first_feed_ms"],
               "pss_load_mib": (rep["pss_after_load_kb"] - rep["pss_baseline_kb"]) / 1024,
               "pss_peak_mib": rep["pss_peak_sampled_kb"] / 1024,
               "pss_peak_delta_mib": (rep["pss_peak_sampled_kb"] - rep["pss_baseline_kb"]) / 1024,
               "vm_hwm_mib": rep["vm_hwm_kb"] / 1024}
        clips = rep["clips"]
        audio_s = sum(c["samples"] for c in clips) / 16000
        row["rtfx"] = audio_s / (sum(c["compute_ms"] for c in clips) / 1000)
        refs = owner if rep["set"] == "owner" else breadth if rep["set"] == "breadth" else None
        if refs:
            row["wer_pcec"], row["edits_pcec"], row["ref_words"] = wer(clips, refs, "pcec_final")
            row["wer_raw"], row["edits_raw"], _ = wer(clips, refs, "raw_final")
            if rep["set"] == "owner":
                hits, loose = {}, 0
                for term in NZ_TERMS:
                    tt = " ".join(tokens(term))
                    rel = [c for c in clips if f" {tt} " in f" {' '.join(tokens(refs[c['id']]))} "]
                    hits[term] = sum(f" {tt} " in f" {' '.join(tokens(c['pcec_final']))} " for c in rel), len(rel)
                    loose += sum(f" {fold(tt)} " in f" {fold(' '.join(tokens(c['pcec_final'])))} " for c in rel)
                row["nz_terms_hit"] = sum(h for h, _ in hits.values())
                row["nz_terms_hit_macron_insensitive"] = loose
                row["nz_terms_total"] = sum(t for _, t in hits.values())
                row["nz_term_detail"] = {k: f"{h}/{t}" for k, (h, t) in hits.items() if t}
                per = {c["id"]: edits(tokens(refs[c["id"]]), tokens(c["pcec_final"])) for c in clips}
                row["per_phrase_pcec_edits"] = per
                section_a = [c["id"] for c in clips if c["id"].startswith("A-")]
                row["section_a_edits"] = sum(per[i] for i in section_a)
                row["section_a_words"] = sum(len(tokens(refs[i])) for i in section_a)
        if rep["mode"] == "paced":
            lags = [x for c in clips for x in c["chunk_lag_ms"]]
            row["lag_p50_ms"] = statistics.median(lags)
            row["lag_p95_ms"] = pct(lags, 0.95)
            row["lag_max_ms"] = max(lags)
            # Chunks are released every 100 ms, so a decode finishing `lag` ms after its chunk's release
            # leaves floor(lag / 100) later chunks queued behind it.
            row["max_queued_chunks"] = int(max(lags) // 100)
            row["chunks_with_queue"] = sum(x >= 100 for x in lags)
            row["chunks"] = len(lags)
            if len(clips) == 1:  # one long stream: sustained behaviour = lag trend from first to last fifth
                n = len(lags) // 5
                row["lag_first_fifth_mean_ms"] = statistics.mean(lags[:n])
                row["lag_last_fifth_mean_ms"] = statistics.mean(lags[-n:])
                row["final_wall_after_audio_end_ms"] = clips[0]["final_wall_after_audio_end_ms"]
            row["proc_p95_ms"] = pct([x for c in clips for x in c["chunk_proc_ms"]], 0.95)
            fr = [c["first_raw_partial_wall_ms"] for c in clips if c["first_raw_partial_wall_ms"] is not None]
            fc = [c["first_corrected_partial_wall_ms"] for c in clips
                  if c["first_corrected_partial_wall_ms"] is not None]
            row["first_raw_partial_median_ms"] = statistics.median(fr) if fr else None
            row["first_raw_partial_n"] = len(fr)
            row["first_corr_partial_median_ms"] = statistics.median(fc) if fc else None
            row["first_corr_partial_n"] = len(fc)
            row["finalize_median_ms"] = statistics.median(c["finalize_ms"] for c in clips)
            row["clips"] = len(clips)
        out.append(row)
        if detail_path:
            detail[f.name] = {c["id"]: {"raw": c["raw_final"], "pcec": c["pcec_final"]} for c in clips}
    if detail_path:
        Path(detail_path).write_text(json.dumps(detail, indent=1, ensure_ascii=False))
    print(json.dumps(out, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
