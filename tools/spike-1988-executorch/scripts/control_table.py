"""ORT control table (#1988 review): ET vs #1987-config ONNX vs ONNX with 4 intra-op threads + arena/prepacking,
all three re-run in one device session. Input: `score.py` JSON per device. Aggregates only.

usage: python control_table.py <device label>=<score.json> [...]
"""

import json
import sys

RT = {"et": "ET INT8 (4 thr)", "onnx": "ONNX #1987 config", "onnxt4": "ONNX 4 thr + arena/prepack"}

print("| Device | Size/tier | Runtime | Owner WER PCEC % | Breadth WER PCEC % | Load ms | Peak PSS Δ MiB | Owner RTFx "
      "| Breadth RTFx | 67 s RTFx | 67 s lag p95 / max ms | 67 s max queued chunks | 67 s final after end ms |")
print("|" + "---|" * 13)
for arg in sys.argv[1:]:
    label, path = arg.split("=", 1)
    d = {(r["lane"], r["set"]): r for r in json.load(open(path))}
    for lane in ("m c16r4", "s c32r8"):
        for rt in ("et", "onnx", "onnxt4"):
            o, b, lg = (d.get((f"{rt} {lane}", s)) for s in ("owner", "breadth", "long"))
            if not (o and b and lg):
                continue
            print(f"| {label} | {lane.upper().replace(' ', '/')} | {RT[rt]} | {o['wer_pcec']:.2f} | {b['wer_pcec']:.2f} "
                  f"| {o['load_ms']:.0f} | {max(o['pss_peak_delta_mib'], b['pss_peak_delta_mib'], lg['pss_peak_delta_mib']):.0f} "
                  f"| {o['rtfx']:.1f} | {b['rtfx']:.1f} | {lg['rtfx']:.1f} | {lg['lag_p95_ms']:.0f} / {lg['lag_max_ms']:.0f} "
                  f"| {lg['max_queued_chunks']} | {lg['final_wall_after_audio_end_ms']:.0f} |")
