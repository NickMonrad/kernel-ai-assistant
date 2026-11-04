#!/usr/bin/env python3
"""Render the #1736 intro video from clips recorded by ``scripts/demo_capture.py``.

One command, no hand editing::

    python3 scripts/demo_edit.py --clips ~/jandal-demo/rc1 --out ~/jandal-demo/rc1/intro.mp4

What it does, per intro shot (``intro_order`` in docs/demo/shots.json):

- picks the newest successful take, or the one given with ``--pick S04=2``;
- cuts the app launch and navigation before the shot's first input;
- speeds up every model wait the capture runner marked for compression to at most
  ``--max-wait`` seconds and shows a "sped up N×" marker for that whole span, so
  replies never look instant (shot-script.md rule 3);
- burns in the shot caption and, for offline shots, an airplane-mode badge;
- fits the portrait recording onto a 1080 × 1920 canvas.

It then appends the end card, joins everything, optionally mixes a voiceover file,
and writes ``<out>.edl.json`` listing every cut and speed-up for review.
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import subprocess
import sys
import tempfile
import textwrap
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SHOTS_FILE = REPO / "docs" / "demo" / "shots.json"
W, H, FPS = 1080, 1920, 30
BG = "0x101418"
FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",  # Arch / CachyOS
    "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
]
INPUT_STEPS = {"type", "host"}


class EditError(RuntimeError):
    pass


@dataclass(frozen=True)
class Segment:
    src_start: float
    src_end: float
    speed: float  # 1.0 = real time

    @property
    def out_len(self) -> float:
        return (self.src_end - self.src_start) / self.speed


# ── planning (pure, unit-tested) ───────────────────────────────────────────


def plan_segments(take: dict, max_wait: float = 2.5, pre_roll: float = 0.6) -> list[Segment]:
    """Turn one manifest take into real-time and sped-up segments in clip time.

    Event times in the manifest are relative to t0, which is ``lead_in_s`` into the clip.
    """
    lead = float(take.get("lead_in_s", 0.0))
    end = lead + float(take["duration_s"])
    first_input = next((e["start"] for e in take["events"] if e["do"] in INPUT_STEPS), 0.0)
    start = max(0.0, lead + first_input - pre_roll)

    spans = sorted(
        (max(start, lead + c["start"]), min(end, lead + c["end"])) for c in take.get("compress", [])
    )
    segments: list[Segment] = []
    cursor = start
    for s, e in spans:
        if e <= cursor:
            continue
        s = max(s, cursor)
        if s > cursor:
            segments.append(Segment(cursor, s, 1.0))
        length = e - s
        speed = max(1.0, length / max_wait)
        segments.append(Segment(s, e, round(speed, 3)))
        cursor = e
    if end > cursor:
        segments.append(Segment(cursor, end, 1.0))
    return [seg for seg in segments if seg.src_end - seg.src_start > 0.05]


def fit_text(text: str, base_size: int, max_chars: int = 26) -> tuple[str, int]:
    """Wrap *text* to at most two lines and pick a font size that fits 90% of the width.

    0.62 em is a safe average advance for DejaVu Sans Bold.
    """
    head, sep, tail = text.partition(". ")
    if sep and tail and len(text) > max_chars and max(len(head) + 1, len(tail)) <= max_chars + 6:
        lines = [head + ".", tail]  # prefer a sentence break
    else:
        lines = textwrap.wrap(text, width=max_chars) or [""]
    if len(lines) > 2:
        lines = textwrap.wrap(text, width=math.ceil(len(text) / 2) + 1)
    longest = max(len(line) for line in lines)
    size = min(base_size, int(0.9 * W / (0.62 * max(1, longest))))
    return "\n".join(lines), size


def marker_windows(segments: list[Segment]) -> list[tuple[float, float, int]]:
    """Output-time windows (start, end, rounded factor) that need the sped-up marker."""
    out, t = [], 0.0
    for seg in segments:
        if seg.speed > 1.0:
            out.append((round(t, 3), round(t + seg.out_len, 3), max(2, math.ceil(seg.speed))))
        t += seg.out_len
    return out


def pick_take(manifest: dict, shot_id: str, picks: dict[str, int]) -> dict:
    takes = [t for t in manifest.get("takes", []) if t["id"] == shot_id]
    if shot_id in picks:
        chosen = [t for t in takes if t["take"] == picks[shot_id]]
        if not chosen:
            raise EditError(f"{shot_id}: take {picks[shot_id]} not in manifest")
        if chosen[-1]["status"] != "ok":
            raise EditError(f"{shot_id}: take {picks[shot_id]} did not complete")
        return chosen[-1]
    ok = [t for t in takes if t["status"] == "ok"]
    if not ok:
        raise EditError(f"{shot_id}: no successful take in manifest")
    return max(ok, key=lambda t: (t["take"], t["recorded_at"]))


# ── ffmpeg ─────────────────────────────────────────────────────────────────


def _concat_q(path: Path) -> str:
    """Quote a path for an ffmpeg concat-demuxer ``file`` line."""
    return "'" + str(path).replace("'", "'\\''") + "'"


def _q(path: Path) -> str:
    """Quote a path for use inside an ffmpeg filter argument."""
    return "'" + str(path).replace("\\", "\\\\").replace("'", "'\\''").replace(":", "\\:") + "'"


def overlay_filter(caption_file: Path, caption_size: int,
                   marker_files: dict[tuple[float, float, int], Path],
                   font: Path, offline: bool, badge_file: Path) -> str:
    """drawtext chain for one shot: caption, optional airplane badge, sped-up markers."""
    common = f"fontfile={_q(font)}:fontcolor=white:box=1:boxcolor=black@0.6:boxborderw=24"
    chain = [f"drawtext={common}:textfile={_q(caption_file)}:fontsize={caption_size}:"
             f"line_spacing=12:text_align=C:x=(w-tw)/2:y=h-th-150"]
    if offline:
        chain.append(f"drawtext={common}:textfile={_q(badge_file)}:fontsize=40:x=w-tw-48:y=72")
    for (start, end, _factor), path in marker_files.items():
        chain.append(
            f"drawtext={common}:textfile={_q(path)}:fontsize=48:x=48:y=72:"
            f"enable='between(t,{start},{end})'"
        )
    return ",".join(chain)


def segment_filter(speed: float) -> str:
    """Retime one segment and fit it onto the 1080 x 1920 canvas."""
    return (
        f"setpts=(PTS-STARTPTS)/{speed},fps={FPS},"
        f"scale={W}:{H}:force_original_aspect_ratio=decrease,"
        f"pad={W}:{H}:(ow-iw)/2:(oh-ih)/2:color={BG},setsar=1"
    )


def run(cmd: list[str]) -> None:
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise EditError(f"ffmpeg failed:\n{' '.join(cmd)}\n{res.stderr[-3000:]}")


def encode_args() -> list[str]:
    return ["-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-r", str(FPS)]


def render_shot(clip: Path, shot: dict, take: dict, work: Path, font: Path, max_wait: float) -> tuple[Path, dict]:
    segments = plan_segments(take, max_wait=max_wait)
    if not segments:
        raise EditError(f"{shot['id']}: nothing to render")
    caption = work / f"{shot['id']}_caption.txt"
    caption_text, caption_size = fit_text(shot["caption"], 54)
    caption.write_text(caption_text, encoding="utf-8")
    badge = work / "badge.txt"
    badge.write_text("✈ Airplane mode", encoding="utf-8")
    markers: dict[tuple[float, float, int], Path] = {}
    for i, window in enumerate(marker_windows(segments)):
        p = work / f"{shot['id']}_marker{i}.txt"
        p.write_text(f"▶▶ sped up {window[2]}×", encoding="utf-8")
        markers[window] = p
    # Cut each segment on its own (bounded memory), join, then overlay text in one pass.
    seg_files = []
    for i, seg in enumerate(segments):
        seg_file = work / f"{shot['id']}_seg{i}.mp4"
        run(["ffmpeg", "-y", "-v", "error", "-ss", f"{seg.src_start:.3f}", "-to", f"{seg.src_end:.3f}",
             "-i", str(clip), "-vf", segment_filter(seg.speed), "-an", *encode_args(), str(seg_file)])
        seg_files.append(seg_file)
    listing = work / f"{shot['id']}_segs.txt"
    listing.write_text("".join(f"file {_concat_q(p)}\n" for p in seg_files), encoding="utf-8")
    joined = work / f"{shot['id']}_joined.mp4"
    run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", str(joined)])
    out = work / f"{shot['id']}.mp4"
    run(["ffmpeg", "-y", "-v", "error", "-i", str(joined),
         "-vf", overlay_filter(caption, caption_size, markers, font, bool(shot.get("offline")), badge),
         "-an", *encode_args(), str(out)])
    edl = {
        "id": shot["id"],
        "take": take["take"],
        "clip": take["clip"],
        "segments": [{"src_start": s.src_start, "src_end": s.src_end, "speed": s.speed} for s in segments],
        "sped_up_windows": [{"start": a, "end": b, "factor": f} for a, b, f in markers],
        "seconds": round(sum(s.out_len for s in segments), 2),
    }
    return out, edl


def render_end_card(card: dict, work: Path, font: Path, icon: Path | None) -> Path:
    out = work / "end_card.mp4"
    title = work / "end_title.txt"
    title.write_text(card["title"], encoding="utf-8")
    filt = f"[0:v]drawtext=fontfile={_q(font)}:fontcolor=white:textfile={_q(title)}:fontsize=96:x=(w-tw)/2:y=h*0.52"
    for i, line in enumerate(card["lines"]):
        p = work / f"end_line{i}.txt"
        _, size = fit_text(line, 44, max_chars=60)
        p.write_text(line, encoding="utf-8")
        filt += f",drawtext=fontfile={_q(font)}:fontcolor=white@0.85:textfile={_q(p)}:fontsize={size}:x=(w-tw)/2:y=h*0.52+150+{i}*80"
    inputs = ["-f", "lavfi", "-i", f"color=c={BG}:s={W}x{H}:r={FPS}:d={card['seconds']}"]
    if icon:
        inputs += ["-i", str(icon)]
        filt += "[bg];[1:v]scale=360:-1[icon];[bg][icon]overlay=(W-w)/2:H*0.30"
    filt += "[v]"
    run(["ffmpeg", "-y", "-v", "error", *inputs, "-filter_complex", filt, "-map", "[v]",
         "-t", str(card["seconds"]), *encode_args(), str(out)])
    return out


def join(parts: list[Path], out: Path, voiceover: Path | None, work: Path) -> None:
    listing = work / "concat.txt"
    listing.write_text("".join(f"file {_concat_q(p)}\n" for p in parts), encoding="utf-8")
    joined = work / "joined.mp4"
    run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", str(listing),
         "-c", "copy", str(joined)])
    audio = ["-i", str(voiceover)] if voiceover else ["-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"]
    run(["ffmpeg", "-y", "-v", "error", "-i", str(joined), *audio, "-map", "0:v", "-map", "1:a",
         "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", str(out)])


def find_font(explicit: Path | None) -> Path:
    for cand in ([explicit] if explicit else []) + [Path(p) for p in FONT_CANDIDATES]:
        if cand and cand.exists():
            return cand
    raise EditError("no DejaVu Sans Bold font found; pass --font")


def parse_picks(values: list[str]) -> dict[str, int]:
    picks = {}
    for v in values:
        sid, _, take = v.partition("=")
        if not take.isdigit():
            raise EditError(f"bad --pick {v!r}; use SHOT=TAKE, e.g. S04=2")
        picks[sid] = int(take)
    return picks


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--clips", type=Path, required=True, help="Directory with manifest.json and clips")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--pick", action="append", default=[], help="Choose a take, e.g. S04=2")
    ap.add_argument("--max-wait", type=float, default=2.5, help="Seconds a model wait is compressed to")
    ap.add_argument("--voiceover", type=Path, help="Audio file mixed under the video")
    ap.add_argument("--icon", type=Path, help="PNG for the end card")
    ap.add_argument("--font", type=Path)
    args = ap.parse_args(argv)

    if not shutil.which("ffmpeg"):
        print("error: ffmpeg not found", file=sys.stderr)
        return 2
    try:
        shots_data = json.loads(SHOTS_FILE.read_text(encoding="utf-8"))
        by_id = {s["id"]: s for s in shots_data["shots"]}
        clips = args.clips.expanduser().resolve()
        manifest = json.loads((clips / "manifest.json").read_text())
        picks = parse_picks(args.pick)
        font = find_font(args.font)
        out = args.out.expanduser().resolve()
        if out == REPO or REPO in out.parents:
            raise EditError("--out must be outside the repository")
        out.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="jandal-demo-") as tmp:
            work = Path(tmp)
            parts, edl = [], {"commit": manifest.get("commit"), "device": manifest.get("device"), "shots": []}
            for sid in shots_data["intro_order"]:
                take = pick_take(manifest, sid, picks)
                part, shot_edl = render_shot(clips / take["clip"], by_id[sid], take, work, font, args.max_wait)
                parts.append(part)
                edl["shots"].append(shot_edl)
            parts.append(render_end_card(shots_data["end_card"], work, font, args.icon))
            join(parts, out, args.voiceover, work)
        edl["seconds"] = round(sum(s["seconds"] for s in edl["shots"]) + shots_data["end_card"]["seconds"], 2)
        out.with_suffix(".edl.json").write_text(json.dumps(edl, indent=2, ensure_ascii=False) + "\n")
    except EditError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(f"wrote {out} ({edl['seconds']} s); cut list in {out.with_suffix('.edl.json').name}")
    if edl["seconds"] > 60:
        print("warning: longer than the 60 s cap in shot-script.md", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
