"""Aggregate per-run lane reports into transcript-free metrics (publishable) and markdown tables.

usage: python aggregate.py <owner phrases.json> <breadth phrases.json> <out dir> <device>=<private results dir> ...

Writes <out dir>/per-run-metrics.json (one row per device x lane x set x repeat; no transcript text, only
counts/timings and per-clip edit counts keyed by the public #1984 phrase IDs) and <out dir>/summary.json
(mean / sd / min / max / n across repeats). Prints markdown tables to stdout.

WER: summed word edits / summed reference words over the set, #1978 tokenisation (score.py). "norm" = after
Jandal's shipping TranscriptNormaliser (applied on device, with each engine's shipping pre-processing).
"""

import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from score import NZ_TERMS, edits, phrases, tokens  # noqa: E402

CHUNK_MS = 100


def pct(values, q):
    v = sorted(values)
    return v[min(len(v) - 1, int(round(q * (len(v) - 1))))]


def lane_key(r):
    if r["runtime"] == "et":
        name = f"ET {r['size'].upper()} {r['tier'].replace('stream_', '')}"
    else:
        name = {"zipformer": "Zipformer (shipping)", "native": "Android native"}[r["runtime"]]
    if r.get("cpus"):
        name += f" [low-end proxy: cpus 0x{r['cpus']}, {r['variant']}]"
    return name


def wer(clips, refs, field):
    e = n = 0
    per = {}
    for c in clips:
        ref = tokens(refs[c["id"]])
        d = edits(ref, tokens(c[field]))
        per[c["id"]] = d
        e += d
        n += len(ref)
    return 100.0 * e / n, e, n, per


def metrics(rep, refs):
    clips = rep["clips"]
    audio_s = sum(c["samples"] for c in clips) / 16000
    m = {
        "load_ms": rep["load_ms"] if rep["in_process"] else None,
        "pss_peak_delta_mib": (rep["pss_peak_sampled_kb"] - rep["pss_baseline_kb"]) / 1024 if rep["in_process"] else None,
        "vm_hwm_mib": rep["vm_hwm_kb"] / 1024 if rep["in_process"] else None,
        # Out-of-process engines (Android native): feed time is only the pipe write, so no RTFx or lag.
        "rtfx": audio_s / (sum(c["compute_ms"] for c in clips) / 1000) if rep["in_process"] else None,
        "audio_s": audio_s,
        "clips": len(clips),
        "cpus_observed": rep.get("observed_cpus_allowed", []),
    }
    if refs:
        for field, key in (("pcec_final", "wer"), ("pcec_final_normalised", "wer_norm"),
                           ("raw_final", "wer_raw"), ("raw_final_normalised", "wer_raw_norm")):
            w, e, n, per = wer(clips, refs, field)
            m[key], m[key + "_edits"] = w, e
            m["ref_words"] = n
            if key == "wer_norm":
                m["per_clip_edits_norm"] = per
        if rep["set"] == "owner":
            hit = tot = 0
            for term in NZ_TERMS:
                tt = " ".join(tokens(term))
                for c in clips:
                    if f" {tt} " in f" {' '.join(tokens(refs[c['id']]))} ":
                        tot += 1
                        hit += f" {tt} " in f" {' '.join(tokens(c['pcec_final_normalised']))} "
            m["nz_terms_hit_norm"], m["nz_terms_total"] = hit, tot
    if rep["mode"] == "paced" and rep["in_process"]:
        lags = [x for c in clips for x in c["chunk_lag_ms"]]
        m.update({
            "lag_p50_ms": statistics.median(lags), "lag_p95_ms": pct(lags, 0.95), "lag_max_ms": max(lags),
            "max_queued_chunks": int(max(lags) // CHUNK_MS),
            "chunks_with_queue": sum(x >= CHUNK_MS for x in lags), "chunks": len(lags),
            "final_after_audio_end_max_ms": max(c["final_wall_after_audio_end_ms"] for c in clips),
        })
    if rep["mode"] == "paced":
        fr = [c["first_raw_partial_wall_ms"] for c in clips if c["first_raw_partial_wall_ms"] is not None]
        fc = [c["first_corrected_partial_wall_ms"] for c in clips if c["first_corrected_partial_wall_ms"] is not None]
        if rep["runtime"] != "et":
            fc = []  # only asr4all has a separate correction (PCEC) pass
        m.update({
            "final_after_audio_end_median_ms": statistics.median(c["final_wall_after_audio_end_ms"] for c in clips),
            "partial_coverage": len(fr) / len(clips),
            "first_partial_median_ms": statistics.median(fr) if fr else None,
            "corrected_partial_coverage": len(fc) / len(clips),
            "first_corrected_partial_median_ms": statistics.median(fc) if fc else None,
            "partial_updates_per_clip": statistics.mean(c["raw_partial_updates"] for c in clips),
            # Partial coverage of the audio: share of each clip's duration during which some partial text was on
            # screen (from first partial to clip end), averaged over clips; 0 for clips with no partial.
            "partial_time_coverage": statistics.mean(
                max(0.0, 1 - c["first_raw_partial_wall_ms"] / (c["samples"] / 16)) if c["first_raw_partial_wall_ms"] is not None else 0.0
                for c in clips),
        })
    if rep["mode"] == "paced" and rep["in_process"] and len(clips) == 1:
        n5 = len(lags) // 5
        m["lag_first_fifth_mean_ms"] = statistics.mean(lags[:n5])
        m["lag_last_fifth_mean_ms"] = statistics.mean(lags[-n5:])
    if rep["runtime"] == "zipformer":
        m["clips_with_endpoint_before_eof"] = sum(c.get("endpoints_before_eof", 0) > 0 for c in clips)
    if rep["runtime"] == "native":
        m["native_errors"] = sum(c.get("native_error") is not None for c in clips)
        m["native_ended_before_eof"] = sum(bool(c.get("native_ended_before_eof")) for c in clips)
    # WER over repeats in which the recogniser reported no error (differs from wer_norm only for native).
    if "wer_norm" in m:
        m["wer_norm_error_free"] = m["wer_norm"] if not m.get("native_errors") else None
    return m


def summarise(values):
    v = [x for x in values if x is not None]
    if not v:
        return None
    return {"mean": statistics.mean(v), "sd": statistics.stdev(v) if len(v) > 1 else 0.0,
            "min": min(v), "max": max(v), "n": len(v)}


def main():
    owner, breadth = phrases(sys.argv[1]), phrases(sys.argv[2])
    out = Path(sys.argv[3])
    out.mkdir(parents=True, exist_ok=True)
    runs = []
    for arg in sys.argv[4:]:
        device, d = arg.split("=", 1)
        for f in sorted(Path(d).glob("*-r*.json")):
            rep = json.load(open(f))
            if "clips" not in rep:
                continue
            refs = owner if rep["set"] == "owner" else breadth if rep["set"] == "breadth" else None
            runs.append({"device": device, "lane": lane_key(rep), "set": rep["set"], "mode": rep["mode"],
                         "repeat": rep["repeat"], "engine": rep["lane"],
                         # Traceability (reports from the first measurement predate these fields; see README).
                         "app_apk_sha256": rep.get("app_apk_sha256"), "test_apk_sha256": rep.get("test_apk_sha256"),
                         "private_et_driver_sha256": rep.get("private_et_driver_sha256"),
                         **metrics(rep, refs)})
    (out / "per-run-metrics.json").write_text(json.dumps(runs, indent=1, ensure_ascii=False))
    groups = defaultdict(list)
    for r in runs:
        groups[(r["device"], r["lane"], r["set"])].append(r)
    summary = []
    for (device, lane, set_), rs in sorted(groups.items()):
        keys = [k for k, v in rs[0].items() if isinstance(v, (int, float)) and not isinstance(v, bool)]
        row = {"device": device, "lane": lane, "set": set_, "repeats": len(rs)}
        for k in keys:
            row[k] = summarise([r.get(k) for r in rs])
        summary.append(row)
    (out / "summary.json").write_text(json.dumps(summary, indent=1, ensure_ascii=False))
    print(f"{len(runs)} runs, {len(summary)} device x lane x set groups")


if __name__ == "__main__":
    main()
