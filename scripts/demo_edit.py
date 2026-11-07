#!/usr/bin/env python3
"""Render the #1736 intro video from clips recorded by ``scripts/demo_capture.py``.

One command, no hand editing::

    python3 scripts/demo_edit.py --clips ~/jandal-demo/rc1 --out ~/jandal-demo/rc1/intro.mp4 \\
        --end-card beta

What it does, per intro shot (``intro_order`` in docs/demo/shots.json):

- picks the newest successful take, or the one given with ``--pick S04=2``;
- cuts the app launch and navigation before the shot's first input (or the step
  marked ``start_here``);
- blurs every screen region the capture runner found holding a ``redact_env`` value
  (the test Nextcloud server and username in S07A/S07B);
- speeds up every span the capture runner marked for compression (model waits, and
  the typing in S03) to at most ``--max-wait`` seconds, or the step's ``compress_to``, and shows a "sped up N×" marker for that whole span, so
  replies never look instant (shot-script.md rule 3);
- burns in the shot caption and, for offline shots only, an airplane-mode badge;
- fits the portrait recording onto a 1080 × 1920 canvas.

It then appends the end card chosen with ``--end-card`` (``beta``: "Join the beta",
for recruitment before the Play listing is public; ``launch``: "Free on Google Play"),
joins everything, optionally mixes a voiceover file,
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
CAP_SECONDS = 70  # Nick, 6 Nov 2026: allow ~70 s (measured cut); only the first 30 s autoplay
BG = "0x101418"
FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",  # Arch / CachyOS
    "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
]
INPUT_STEPS = {"type", "host"}
# boxblur needs radius < half the chroma plane; the crop may be clamped at the frame edge,
# so the radius is worked out by ffmpeg from the cropped size (cw, ch).
BLUR_RADIUS = "min(20\\,min(cw\\,ch)/2-1)"


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


def plan_segments(take: dict, max_wait: float = 2.0, pre_roll: float = 0.6) -> list[Segment]:
    """Turn one manifest take into real-time and sped-up segments in clip time.

    Event times in the manifest are relative to t0, which is ``lead_in_s`` into the clip.
    """
    if take.get("lead_in_s") is None:
        raise EditError(f"{take.get('id')}: take has no measured lead_in_s; re-record it")
    lead = float(take["lead_in_s"])
    end = lead + float(take["duration_s"])
    first_input = next((e["start"] for e in take["events"] if e.get("start_here")), None)
    if first_input is None:
        first_input = next((e["start"] for e in take["events"] if e["do"] in INPUT_STEPS), 0.0)
    start = max(0.0, lead + first_input - pre_roll)

    # A span may carry its own ``max_s`` (step ``compress_to``), e.g. typing in S03.
    spans = sorted(
        (max(start, lead + c["start"]), min(end, lead + c["end"]), float(c.get("max_s", max_wait)))
        for c in take.get("compress", [])
    )
    segments: list[Segment] = []
    cursor = start
    for s, e, span_max in spans:
        if e <= cursor:
            continue
        s = max(s, cursor)
        if s > cursor:
            segments.append(Segment(cursor, s, 1.0))
        length = e - s
        speed = max(1.0, length / span_max)
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


def scale_redact(take: dict) -> list[list[int]]:
    """Redact bounds in clip pixels.

    The runner records them in uiautomator (screen) pixels; scrcpy may record smaller
    (an encoder fallback, or a WQHD display setting). A rotated or differently shaped clip
    cannot be mapped safely, so it is refused rather than blurred in the wrong place.
    """
    boxes = take.get("redact", [])
    screen, clip = take.get("screen_size"), take.get("clip_size")
    if not boxes:
        return []
    if not screen or not clip:
        raise EditError(f"{take['id']}: redact boxes but no screen/clip size in the manifest; re-record it")
    sx, sy = clip[0] / screen[0], clip[1] / screen[1]
    if abs(sx - sy) > 0.01:
        raise EditError(f"{take['id']}: clip {clip} and screen {screen} differ in shape (rotation?); cannot place blur")
    return [[int(x1 * sx), int(y1 * sy), int(x2 * sx + 0.999), int(y2 * sy + 0.999)] for x1, y1, x2, y2 in boxes]


def blur_boxes(redact: list[list[int]], margin: int = 12) -> list[tuple[int, int, int, int]]:
    """Turn manifest redact bounds into even-sized ``(x, y, w, h)`` crops with a margin.

    Even sizes keep yuv420p chroma aligned; the margin covers text antialiasing.
    """
    out = []
    for x1, y1, x2, y2 in redact:
        x, y = max(0, x1 - margin) // 2 * 2, max(0, y1 - margin) // 2 * 2
        w = (x2 + margin - x + 1) // 2 * 2
        h = (y2 + margin - y + 1) // 2 * 2
        if w >= 4 and h >= 4 and (x, y, w, h) not in out:
            out.append((x, y, w, h))
    return out


def blur_graph(boxes: list[tuple[int, int, int, int]]) -> str:
    """Filtergraph from ``[0:v]`` to ``[pre]`` that blurs each box in source pixels.

    Crops are clamped to the frame by ffmpeg's own iw/ih, so a box at the edge still works.
    """
    if not boxes:
        return "[0:v]null[pre]"
    parts = [f"[0:v]split={len(boxes) + 1}[base]" + "".join(f"[c{i}]" for i in range(len(boxes)))]
    last = "base"
    for i, (x, y, w, h) in enumerate(boxes):
        parts.append(f"[c{i}]crop=min({w}\\,iw-{x}):min({h}\\,ih-{y}):{x}:{y},"
                     f"boxblur=lr={BLUR_RADIUS}:lp=2:cr={BLUR_RADIUS}:cp=2[b{i}]")
        nxt = "pre" if i == len(boxes) - 1 else f"o{i}"
        parts.append(f"[{last}][b{i}]overlay={x}:{y}[{nxt}]")
        last = nxt
    return ";".join(parts)


def select_end_card(shots_data: dict, variant: str) -> dict:
    cards = shots_data.get("end_cards", {})
    if variant not in cards:
        raise EditError(f"unknown end card {variant!r}; choose from {', '.join(sorted(cards))}")
    return cards[variant]


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


def clip_duration(clip: Path) -> float | None:
    res = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(clip)],
                         capture_output=True, text=True)
    try:
        return float(res.stdout.strip())
    except ValueError:
        return None


def tail_pad(seg: "Segment", clip_end: float | None) -> float:
    """Output seconds missing when the clip ends before the segment's planned end.

    The screen is static by then (a hold), so the last frame is repeated for that time.
    """
    if clip_end is None or seg.src_end <= clip_end:
        return 0.0
    return round((seg.src_end - max(clip_end, seg.src_start)) / seg.speed, 3)


def segment_filter(speed: float, pad: float = 0.0) -> str:
    """Retime one segment, pad a short tail with its last frame, and fit it onto the canvas."""
    tpad = f"tpad=stop_mode=clone:stop_duration={pad}," if pad > 0 else ""
    return (
        f"setpts=(PTS-STARTPTS)/{speed},{tpad}fps={FPS},"
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
    boxes = blur_boxes(scale_redact(take))
    clip_end = clip_duration(clip)
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
             "-i", str(clip), "-filter_complex", f"{blur_graph(boxes)};[pre]{segment_filter(seg.speed, tail_pad(seg, clip_end))}[v]",
             "-map", "[v]", "-an", *encode_args(), str(seg_file)])
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
        "blurred": [{"x": x, "y": y, "w": w, "h": h} for x, y, w, h in boxes],
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
    ap.add_argument("--end-card", required=True, choices=("beta", "launch"),
                    help="beta: 'Join the beta' (pre-launch recruitment); launch: 'Free on Google Play'")
    ap.add_argument("--max-wait", type=float, default=2.0, help="Seconds a model wait is compressed to")
    ap.add_argument("--voiceover", type=Path, help="Audio file mixed under the video")
    ap.add_argument("--icon", type=Path, help="PNG for the end card")
    ap.add_argument("--font", type=Path)
    ap.add_argument("--shots", default="intro",
                    help="'intro' (full cut with end card) or comma-separated ids for a preview without one")
    ap.add_argument("--allow-over-cap", action="store_true", help="Write a cut longer than the 70 s cap anyway")
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
        card = select_end_card(shots_data, args.end_card)
        font = find_font(args.font)
        out = args.out.expanduser().resolve()
        if out == REPO or REPO in out.parents:
            raise EditError("--out must be outside the repository")
        out.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="jandal-demo-") as tmp:
            work = Path(tmp)
            parts, edl = [], {"end_card": args.end_card, "builds": [], "shots": []}
            preview = args.shots != "intro"
            order = shots_data["intro_order"] if not preview else [x.strip() for x in args.shots.split(",") if x.strip()]
            unknown = [sid for sid in order if sid not in by_id]
            if unknown:
                raise EditError(f"unknown shot ids: {', '.join(unknown)}")
            for sid in order:
                take = pick_take(manifest, sid, picks)
                part, shot_edl = render_shot(clips / take["clip"], by_id[sid], take, work, font, args.max_wait)
                parts.append(part)
                shot_edl["build"] = take.get("build")
                edl["shots"].append(shot_edl)
                if take.get("build") and take["build"] not in edl["builds"]:
                    edl["builds"].append(take["build"])
            planned = sum(s["seconds"] for s in edl["shots"]) + card["seconds"]
            if planned > CAP_SECONDS and not args.allow_over_cap and not preview:
                longest = sorted(edl["shots"], key=lambda s: -s["seconds"])[:3]
                raise EditError(f"cut is {planned:.1f} s, over the {CAP_SECONDS} s cap; longest shots: "
                                + ", ".join(f"{s['id']} {s['seconds']} s" for s in longest)
                                + " (or pass --allow-over-cap)")
            if not preview:
                parts.append(render_end_card(card, work, font, args.icon))
            join(parts, out, args.voiceover, work)
        edl["seconds"] = round(sum(s["seconds"] for s in edl["shots"]) + (0 if preview else card["seconds"]), 2)
        out.with_suffix(".edl.json").write_text(json.dumps(edl, indent=2, ensure_ascii=False) + "\n")
    except EditError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(f"wrote {out} ({edl['seconds']} s); cut list in {out.with_suffix('.edl.json').name}")
    if edl["seconds"] > CAP_SECONDS:
        print(f"warning: longer than the {CAP_SECONDS} s cap in shot-script.md", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
