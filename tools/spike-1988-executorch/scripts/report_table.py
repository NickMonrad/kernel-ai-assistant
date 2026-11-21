"""Markdown comparison tables from `score.py` JSON outputs (aggregates only).

usage: python report_table.py <device label>=<score.json> [...]
"""

import json
import sys


def f(v, fmt="{:.1f}"):
    return "–" if v is None else fmt.format(v)


main_rows, long_rows = [], []
for arg in sys.argv[1:]:
    label, path = arg.split("=", 1)
    lanes = {(r["lane"], r["set"]): r for r in json.load(open(path))}
    for (lane, s), r in sorted(lanes.items()):
        if s == "long":
            long_rows.append(
                f"| {label} | {lane} | {f(r['rtfx'])} | {f(r['lag_p95_ms'], '{:.0f}')} / {f(r['lag_max_ms'], '{:.0f}')} "
                f"| {f(r['lag_first_fifth_mean_ms'], '{:.0f}')} → {f(r['lag_last_fifth_mean_ms'], '{:.0f}')} "
                f"| {r['max_queued_chunks']} ({r['chunks_with_queue']}/{r['chunks']}) "
                f"| {f(r['final_wall_after_audio_end_ms'], '{:.0f}')} | {f(r['pss_peak_delta_mib'], '{:.0f}')} |")
        if s != "owner":
            continue
        pub = lanes.get((lane, "public"), {})
        br = lanes.get((lane, "breadth"), {})
        main_rows.append(
            f"| {label} | {lane} | **{f(r.get('wer_pcec'), '{:.2f}')}** | {f(r.get('wer_raw'), '{:.2f}')} "
            f"| {f(br.get('wer_pcec'), '{:.2f}')} | {f(r['load_ms'], '{:.0f}')} | {f(r['pss_peak_delta_mib'], '{:.0f}')} "
            f"| {f(r['vm_hwm_mib'], '{:.0f}')} | {f(r['rtfx'])} | {f(br.get('rtfx'))} "
            f"| {f(r['first_raw_partial_median_ms'], '{:.0f}')} ({r['first_raw_partial_n']}/{r['clips']}) "
            f"| {f(pub.get('first_corr_partial_median_ms'), '{:.0f}')} ({pub.get('first_corr_partial_n', 0)}/{pub.get('clips', 0)}) "
            f"| {f(r['lag_p95_ms'], '{:.0f}')} / {f(r['lag_max_ms'], '{:.0f}')} | {r['max_queued_chunks']} "
            f"| {r['nz_terms_hit']} ({r['nz_terms_hit_macron_insensitive']}) / {r['nz_terms_total']} "
            f"| {r['section_a_edits']}/{r['section_a_words']} |")
print("| Device | Lane | Owner WER PCEC % | Owner WER raw % | Breadth WER PCEC % | Load ms | Peak PSS Δ MiB "
      "| VmHWM MiB | Owner RTFx | Breadth RTFx | First raw partial ms (n) | First corrected partial ms, public (n) "
      "| Owner lag p95 / max ms | Max queued chunks | NZ/Māori terms exact (macron-insensitive) / total "
      "| Section A edits / words |")
print("|" + "---|" * 16)
print("\n".join(main_rows))
if long_rows:
    print()
    print("| Device | Lane | RTFx | Lag p95 / max ms | Mean lag first → last fifth ms | Max queued chunks (chunks ≥100 ms) "
          "| Final after audio end ms | Peak PSS Δ MiB |")
    print("|" + "---|" * 8)
    print("\n".join(long_rows))
