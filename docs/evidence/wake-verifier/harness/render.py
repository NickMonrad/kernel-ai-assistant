#!/usr/bin/env python3
"""Render the published aggregate JSON into the markdown tables used in the README."""
from __future__ import annotations

import json
import sys
from pathlib import Path

CONDS = ("clean", "att12", "reverb", "music10", "babble10")


def p(r: dict | None) -> str:
    if not r or r.get("of", 0) == 0:
        return "–"
    return f"{r['pct']:.1f}% ({r['n']}/{r['of']})"


def host_tables(h: dict, labels: list[str]) -> str:
    out = ["| Option | Genuine TA (first crossing) | Genuine TA (peak ring) | Owner natural (first / peak) | Owner-voice clone #1736 (first / peak) | Piper voices (first) | Near-miss FA | Background-speech windows FA | Non-speech FA |",
           "|---|---|---|---|---|---|---|---|---|"]
    for lb in labels:
        e = h["engine_level"][lb]
        g, gp = e["genuine_by_subset"], e["genuine_at_peak_by_subset"]
        out.append(f"| `{lb}` | {p(e['genuine'])} | {p(e['genuine_at_peak'])} | "
                   f"{p(g.get('owner-natural'))} / {p(gp.get('owner-natural'))} | "
                   f"{p(g.get('cloned-1736'))} / {p(gp.get('cloned-1736'))} | {p(g.get('piper-multi'))} | "
                   f"{p(e['near_miss'])} | {p(e['background_windows'])} | {p(e['non_speech'])} |")
    out += ["", "| Option | " + " | ".join(CONDS) + " |", "|---|" + "---|" * len(CONDS)]
    for lb in labels:
        e = h["engine_level"][lb]["genuine_by_condition"]
        out.append(f"| `{lb}` | " + " | ".join(p(e.get(c)) for c in CONDS) + " |")
    out += ["", "| Option | Genuine accepted | Gain over detector-only (of 12 low-band) | Near-miss accepted | Near-miss gain (of 11 low-band) | Background accepted |",
            "|---|---|---|---|---|---|"]
    for lb in labels:
        s = h["system"][lb]
        out.append(f"| `{lb}` | {p(s['genuine'])} | {s['genuine']['gain_over_detector_only']} | "
                   f"{p(s['near_miss'])} | {s['near_miss']['gain_over_detector_only']} | {p(s['background_speech'])} |")
    out += ["", "| Option | Genuine accepted | Near-miss accepted | Background accepted |", "|---|---|---|---|"]
    for lb in labels:
        s = h["system_verify_all"][lb]
        out.append(f"| `{lb}` | {p(s['genuine'])} | {p(s['near_miss'])} | {p(s['background_speech'])} |")
    return "\n".join(out)


def engine_table(h: dict) -> str:
    out = ["| Engine | Threads | Load ms | First call ms | Quiet p50 ms | Quiet p95 ms | RSS Δ after load MiB | Peak RSS Δ MiB |",
           "|---|---|---|---|---|---|---|---|"]
    for n, m in h["engines"].items():
        q = m.get("quiet_timing", {})
        out.append(f"| `{n}` | {m['threads']} | {q.get('load_ms', m['load_ms'])} | {q.get('first_call_ms', '–')} | "
                   f"{q.get('warm_p50_ms', '–')} | {q.get('warm_p95_ms', '–')} | {m['rss_after_load_delta_mib']} | "
                   f"{m['peak_rss_delta_mib']} |")
    return "\n".join(out)


def matching_table(h: dict) -> str:
    out = ["| Engine output | prod match | split/spacing | name within edit 1 | name within edit 2 | no wake name | empty | Extra name forms seen ≥2× |",
           "|---|---|---|---|---|---|---|---|"]
    for n, m in h["matching"].items():
        c = m["genuine_miss_classes"]
        out.append(f"| `{n}` | {c.get('prod_match', 0)} | {c.get('split_or_spacing', 0)} | {c.get('name_ed1', 0)} | "
                   f"{c.get('name_ed2', 0)} | {c.get('no_wake_name', 0)} | {c.get('empty', 0)} | "
                   f"{', '.join(m['extra_name_forms_seen_twice']) or '–'} |")
    return "\n".join(out)


def detector_table(h: dict) -> str:
    d = h["detector_only"]
    ts = list(d["genuine"].keys())
    out = ["| Single threshold | " + " | ".join(ts) + " |", "|---|" + "---|" * len(ts)]
    for c in ("genuine", "near_miss", "background_speech", "non_speech"):
        out.append(f"| {c} | " + " | ".join(f"{d[c][t]['pct']:.1f}" for t in ts) + " |")
    return "\n".join(out)


def device_table(d: dict) -> str:
    out = ["| Lane | Genuine TA | Owner natural | Near-miss FA | Background FA | Non-speech FA | Host verdict agreement | Verify p50 / p95 ms | Load / cold ms | Memory |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    for lb, l in d["lanes"].items():
        if "verify_warm_p50_ms" in l:
            lat = f"{l['verify_warm_p50_ms']} / {l['verify_warm_p95_ms']}"
            load = f"cold first verify ≈ {l['cold_first_verify_est_ms']}"
            mem = f"PSS Δ {l['pss_delta_mib']} MiB"
        else:
            lat = f"{l['verify_p50_ms']} / {l['verify_p95_ms']}"
            load = f"load {l['load_ms']}"
            mem = f"PSS Δ load {l['pss_load_delta_mib']} / peak {l['pss_peak_delta_mib']} MiB"
        out.append(f"| `{lb}` | {p(l['genuine'])} | {p(l['genuine_owner_natural'])} | {p(l['near_miss'])} | "
                   f"{p(l['background_speech'])} | {p(l['non_speech'])} | {p(l['host_verdict_agreement'])} | "
                   f"{lat} | {load} | {mem} |")
    return "\n".join(out)


if __name__ == "__main__":
    h = json.loads(Path(sys.argv[1]).read_text())
    labels = sys.argv[3:] if len(sys.argv) > 3 else list(h["engine_level"])
    print(engine_table(h), "", matching_table(h), "", host_tables(h, labels), "", detector_table(h), sep="\n")
    if len(sys.argv) > 2 and sys.argv[2] != "-":
        for f in sys.argv[2].split(","):
            print("\n" + device_table(json.loads(Path(f).read_text())))
