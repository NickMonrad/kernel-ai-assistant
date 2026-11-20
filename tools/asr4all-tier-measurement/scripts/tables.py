"""Markdown tables from aggregate.py's summary.json (transcript-free).

usage: python tables.py <summary.json>
Cells are mean ± sd over repeats (n shown in the lane table); "max" columns are the worst repeat.
"""

import json
import sys
from collections import defaultdict


def ms(s, key="mean", fmt="{:.0f}"):
    return "–" if s is None else fmt.format(s[key])


def msd(s, fmt="{:.0f}"):
    if s is None:
        return "–"
    return fmt.format(s["mean"]) + ("" if s["n"] < 2 else " ± " + fmt.format(s["sd"]))


def order(lane):
    rank = {"ET S": 0, "ET M": 1, "ET L": 2, "Zipf": 3, "Andr": 4}
    return (("low-end" in lane), rank.get(lane[:4], 9), lane)


summary = json.load(open(sys.argv[1]))
by = defaultdict(dict)
for r in summary:
    by[(r["device"], r["lane"])][r["set"]] = r
devices = sorted({d for d, _ in by}, key=lambda d: {"S23U": 0, "S21": 1}.get(d, 2))
lanes = lambda dev: sorted((l for d, l in by if d == dev), key=order)

print("### Accuracy (owner = 20 decision clips, 146 words; breadth = 100 voice-cloned clips, 638 words)\n")
print("| Device | Lane | Owner WER % (normalised) | Owner WER % (as emitted) | Owner raw WER % | Breadth WER % (normalised) | NZ/Māori terms (normalised) |")
print("|---|---|---|---|---|---|---|")
for dev in devices:
    for lane in lanes(dev):
        o, b = by[(dev, lane)].get("owner"), by[(dev, lane)].get("breadth")
        if not o:
            continue
        def w(r, k):
            if not r or not r.get(k):
                return "–"
            s = r[k]
            return f"{s['mean']:.2f}" + ("" if s["min"] == s["max"] else f" ({s['min']:.2f}–{s['max']:.2f})")
        nz = f"{o['nz_terms_hit_norm']['mean']:.0f}/{o['nz_terms_total']['mean']:.0f}" if o.get("nz_terms_hit_norm") else "–"
        print(f"| {dev} | {lane} | **{w(o, 'wer_norm')}** | {w(o, 'wer')} | {w(o, 'wer_raw')} | {w(b, 'wer_norm')} | {nz} |")

print("\n### Real-time behaviour (paced 100 ms feed; mean ± sd over repeats, worst repeat for max columns)\n")
print("| Device | Lane | n | Owner RTFx | Owner lag p95 ms | Owner max queued chunks (worst) | 67 s RTFx | 67 s lag p95 ms | 67 s max queued (worst) | 67 s mean lag first → last fifth ms | 67 s final after audio end ms |")
print("|---|---|---|---|---|---|---|---|---|---|---|")
for dev in devices:
    for lane in lanes(dev):
        o, lg = by[(dev, lane)].get("owner"), by[(dev, lane)].get("long")
        if not o:
            continue
        if not o.get("rtfx"):
            continue  # out-of-process engine: no attributable compute or lag
        print(f"| {dev} | {lane} | {o['repeats']} | {msd(o['rtfx'], '{:.2f}')} | {msd(o.get('lag_p95_ms'))} "
              f"| {ms(o.get('max_queued_chunks'), 'max')} | {msd(lg['rtfx'], '{:.2f}') if lg else '–'} "
              f"| {msd(lg.get('lag_p95_ms')) if lg else '–'} | {ms(lg.get('max_queued_chunks'), 'max') if lg else '–'} "
              f"| {(ms(lg.get('lag_first_fifth_mean_ms')) + ' → ' + ms(lg.get('lag_last_fifth_mean_ms'))) if lg else '–'} "
              f"| {msd(lg.get('final_after_audio_end_median_ms')) if lg else '–'} |")

print("\n### Live partials (owner clips, paced)\n")
print("| Device | Lane | First partial ms (median over clips) | Clips with a partial | Clips with a corrected partial | Share of clip time showing partial text | Partial updates / clip | Final after audio end ms (median) |")
print("|---|---|---|---|---|---|---|---|")
for dev in devices:
    for lane in lanes(dev):
        o = by[(dev, lane)].get("owner")
        if not o:
            continue
        cov = lambda k: "–" if not o.get(k) else f"{o[k]['mean'] * 20:.0f}/20"
        print(f"| {dev} | {lane} | {msd(o.get('first_partial_median_ms'))} | {cov('partial_coverage')} "
              f"| {cov('corrected_partial_coverage') if lane.startswith('ET') else 'n/a'} | {msd(o.get('partial_time_coverage'), '{:.2f}')} "
              f"| {msd(o.get('partial_updates_per_clip'), '{:.1f}')} | {msd(o.get('final_after_audio_end_median_ms'))} |")

print("\n### Load and memory (in-process engines; fresh process per lane and repeat)\n")
print("| Device | Lane | Load ms | Peak PSS Δ MiB | VmHWM MiB |")
print("|---|---|---|---|---|")
for dev in devices:
    for lane in lanes(dev):
        rows = [r for r in by[(dev, lane)].values() if r.get("load_ms")]
        if not rows:
            continue
        o = by[(dev, lane)].get("owner") or rows[0]
        peak = max((r["pss_peak_delta_mib"]["max"] for r in rows), default=None)
        print(f"| {dev} | {lane} | {msd(o['load_ms'])} | {msd(o['pss_peak_delta_mib'])} (max {peak:.0f}) "
              f"| {msd(o['vm_hwm_mib'])} |")
