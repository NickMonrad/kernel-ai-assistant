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
import os
import re
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
CAP_SECONDS = 150  # Lokhor, 8 Nov 2026: fine at ~140 s now that speech plays at real speed
BG = "0x101418"
FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",  # Arch / CachyOS
    "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
]
INPUT_STEPS = {"type", "host", "voice"}
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


def subtract(span: tuple[float, float], holes: list[tuple[float, float]]) -> list[tuple[float, float]]:
    """``span`` minus every interval in ``holes``."""
    parts = [span]
    for hs, he in sorted(holes):
        nxt = []
        for s, e in parts:
            if he <= s or hs >= e:
                nxt.append((s, e))
                continue
            if hs > s:
                nxt.append((s, hs))
            if he < e:
                nxt.append((he, e))
        parts = nxt
    return parts


def plan_segments(take: dict, max_wait: float = 2.0, pre_roll: float = 0.6,
                  keep_real: list[tuple[float, float]] | None = None) -> list[Segment]:
    """Turn one manifest take into real-time and sped-up segments in clip time.

    Event times in the manifest are relative to t0, which is ``lead_in_s`` into the clip.
    ``keep_real`` holds runner-time intervals that must play at real speed (speech), even
    inside a span marked for compression.
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
    holes = [(lead + a, lead + b) for a, b in (keep_real or [])]
    spans = sorted(
        (ps, pe, float(c.get("max_s", max_wait)))
        for c in take.get("compress", [])
        for ps, pe in subtract((max(start, lead + c["start"]), min(end, lead + c["end"])), holes)
        if pe - ps > 0.05
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


def out_time(segments: list[Segment], clip_t: float, lengths: list[float] | None = None) -> float | None:
    """Where clip time ``clip_t`` lands in the rendered shot (None if it was cut).

    ``lengths`` are the segments' real rendered lengths (each rounds to whole frames, which
    adds up over a long shot); without them the planned lengths are used.
    """
    lengths = lengths or [seg.out_len for seg in segments]
    t = 0.0
    for seg, length in zip(segments, lengths):
        if clip_t < seg.src_start:
            return None if t == 0.0 else round(t, 3)
        if clip_t <= seg.src_end:
            return round(t + min((clip_t - seg.src_start) / seg.speed, length), 3)
        t += length
    return round(t + (clip_t - segments[-1].src_end), 3) if segments else None


@dataclass(frozen=True)
class Sound:
    path: Path
    t: float  # runner time it started on the device
    seconds: float
    kind: str  # "input" (cloned voice fixture) or "jandal" (Cori reply)


def place_sounds(sounds: list[Sound], segments: list[Segment], lead: float,
                 lengths: list[float] | None = None) -> list[tuple[Sound, float]]:
    """Output start times for each sound. Jandal's chunks never overlap each other: a chunk
    whose regenerated audio runs long pushes the next one back."""
    placed, jandal_end = [], 0.0
    for snd in sorted(sounds, key=lambda x: x.t):
        at = out_time(segments, lead + snd.t, lengths)
        if at is None:
            continue
        if snd.kind == "jandal":
            at = max(at, jandal_end)
            jandal_end = at + snd.seconds + 0.05
        placed.append((snd, round(at, 3)))
    return placed


def hold_for_sound(placed: list[tuple[Sound, float]], shot_seconds: float, tail: float = 0.4) -> float:
    """Extra seconds to hold the last frame so the last sound finishes inside the shot."""
    if not placed:
        return 0.0
    need = max(at + snd.seconds for snd, at in placed) + tail
    return round(max(0.0, need - shot_seconds), 3)


def speech_cmd(template: str, text: str, out: Path) -> list[str]:
    """``DEMO_CORI_CMD`` with ``{text}`` and ``{out}`` filled in, one argument each."""
    import shlex
    args = [a.replace("{text}", text).replace("{out}", str(out)) for a in shlex.split(template)]
    if not any("{out}" in a for a in shlex.split(template)):
        raise EditError("DEMO_CORI_CMD must contain {out} (and {text})")
    return [str(Path(a).expanduser()) if a.startswith("~") else a for a in args]


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


# ── caption style B (Lokhor, 7 Nov 2026): dark glass pill + Jandal-green accent bar ──

GREEN = (79, 121, 66)          # launcher icon background #4F7942
GLASS = (30, 36, 42, 215)       # a touch lighter than the app's dark UI so the pill reads
OUTLINE = (255, 255, 255, 46)  # faint hairline so the pill's shape shows on dark screens
CAPTION_FONTS = [
    "/usr/share/fonts/TTF/OpenSans-ExtraBold.ttf",
    "/usr/share/fonts/truetype/open-sans/OpenSans-ExtraBold.ttf",
    "/usr/share/fonts/open-sans/OpenSans-ExtraBold.ttf",
]
FADE_IN = 0.25


def _font(paths: list[str], size: int, fallback: Path):
    from PIL import ImageFont
    for path in paths:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.truetype(str(fallback), size)


def _pill(size: tuple[int, int], box: list[float], radius: int, shadow: bool = True):
    from PIL import Image, ImageDraw, ImageFilter
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    if shadow:
        sh = Image.new("RGBA", size, (0, 0, 0, 0))
        ImageDraw.Draw(sh).rounded_rectangle([box[0] + 4, box[1] + 10, box[2] + 4, box[3] + 10], radius, fill=(0, 0, 0, 120))
        layer = Image.alpha_composite(layer, sh.filter(ImageFilter.GaussianBlur(12)))
    ImageDraw.Draw(layer).rounded_rectangle(box, radius, fill=GLASS, outline=OUTLINE, width=2)
    return layer


def caption_layer(text: str, symbol_font: Path, out: Path) -> Path:
    """Full-frame transparent PNG: the caption in a glass pill with a green accent bar."""
    from PIL import ImageDraw
    wrapped, size = fit_text(text, 56)
    lines = wrapped.split("\n")
    font = _font(CAPTION_FONTS, size, symbol_font)
    probe = ImageDraw.Draw(_pill((W, H), [0, 0, 1, 1], 1, shadow=False))
    while max(probe.textlength(ln, font=font) for ln in lines) > W * 0.80 and font.size > 30:
        font = _font(CAPTION_FONTS, font.size - 2, symbol_font)
    spacing = 6
    tw = max(probe.textlength(ln, font=font) for ln in lines)
    th = len(lines) * (font.size + spacing) - spacing
    bottom = H - 200
    box = [(W - tw) / 2 - 52, bottom - th - 34, (W + tw) / 2 + 40, bottom + 30]
    img = _pill((W, H), box, 36)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([box[0] + 18, box[1] + 22, box[0] + 28, box[3] - 22], 5, fill=GREEN)
    y = bottom - th
    for ln in lines:
        lw = d.textlength(ln, font=font)
        d.text(((W - lw) / 2 + 6, y - font.size * 0.12), ln, font=font, fill=(255, 255, 255))
        y += font.size + spacing
    img.save(out)
    return out


def corner_layer(text: str, symbol: str, symbol_font: Path, out: Path, right: bool, tint=(255, 255, 255)) -> Path:
    """A small glass pill in a top corner: the airplane badge (right) or a sped-up marker (left)."""
    from PIL import ImageDraw
    font = _font(CAPTION_FONTS, 34, symbol_font)
    sym = _font([str(symbol_font)], 34, symbol_font)  # DejaVu has ✈ and ▶
    probe = ImageDraw.Draw(_pill((W, H), [0, 0, 1, 1], 1, shadow=False))
    sw, tw = probe.textlength(symbol, font=sym), probe.textlength(text, font=font)
    width = sw + 14 + tw + 56
    x0 = W - 40 - width if right else 40
    box = [x0, 64, x0 + width, 64 + 64]
    img = _pill((W, H), box, 32, shadow=False)
    d = ImageDraw.Draw(img)
    d.text((x0 + 28, 74), symbol, font=sym, fill=GREEN if not right else tint)
    d.text((x0 + 28 + sw + 14, 72), text, font=font, fill=tint)
    img.save(out)
    return out


def styled_overlay_args(joined: Path, caption_png: Path, badge_png: Path | None,
                        markers: dict[tuple[float, float, int], Path], seconds: float | None = None) -> list[str]:
    """ffmpeg inputs + filter_complex placing the PNG layers over one shot.

    The caption fades in at the start and, when the shot length is known, out at the end.
    """
    inputs = ["-i", str(joined), "-loop", "1", "-framerate", str(FPS), "-i", str(caption_png)]
    fade_out = (f",fade=out:st={max(0.0, seconds - FADE_IN):.3f}:d={FADE_IN}:alpha=1"
                if seconds and seconds > 2 * FADE_IN else "")
    graph = [f"[1:v]format=rgba,fade=in:st=0:d={FADE_IN}:alpha=1{fade_out}[cap]",
             "[0:v][cap]overlay=0:0:shortest=1[v1]"]
    last, idx = "v1", 2
    if badge_png:
        inputs += ["-loop", "1", "-framerate", str(FPS), "-i", str(badge_png)]
        graph.append(f"[{last}][{idx}:v]overlay=0:0:shortest=1[v{idx}]")
        last, idx = f"v{idx}", idx + 1
    for (start, end, _factor), png in markers.items():
        inputs += ["-loop", "1", "-framerate", str(FPS), "-i", str(png)]
        graph.append(f"[{last}][{idx}:v]overlay=0:0:shortest=1:enable='between(t,{start},{end})'[v{idx}]")
        last, idx = f"v{idx}", idx + 1
    return [*inputs, "-filter_complex", ";".join(graph), "-map", f"[{last}]"]


def pillow_available() -> bool:
    try:
        import PIL  # noqa: F401
        return True
    except ImportError:
        return False


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


def render_shot(clip: Path, shot: dict, take: dict, work: Path, font: Path, max_wait: float,
                sounds: list[Sound] | None = None) -> tuple[Path, dict]:
    sounds = sounds or []
    # Speech plays at real speed, so the screen is never sped up under it.
    keep_real = [(snd.t, snd.t + snd.seconds) for snd in sounds]
    segments = plan_segments(take, max_wait=max_wait, keep_real=keep_real)
    if not segments:
        raise EditError(f"{shot['id']}: nothing to render")
    boxes = blur_boxes(scale_redact(take))
    if shot.get("redact_required") and not boxes:
        # Fail closed: this shot always shows the test server (S07B's address bar). No box
        # means the capture runner never read it on screen, not that there is nothing to hide.
        raise EditError(f"{shot['id']}: no regions to blur were recorded, but the shot always shows "
                        "the test server; re-take it")
    clip_end = clip_duration(clip)
    # A tail pad only fills frames the clip is missing inside a segment's planned length.
    pads = [tail_pad(seg, clip_end) for seg in segments]
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
             "-i", str(clip), "-filter_complex", f"{blur_graph(boxes)};[pre]{segment_filter(seg.speed, pads[i])}[v]",
             "-map", "[v]", "-an", *encode_args(), str(seg_file)])
        seg_files.append(seg_file)
    # Place the sounds against what was actually rendered, then hold the last frame until
    # the last reply has finished (the cut may run longer for speech).
    lengths = [clip_duration(f) or seg.out_len for f, seg in zip(seg_files, segments)]
    placed = place_sounds(sounds, segments, float(take["lead_in_s"]), lengths)
    extra_hold = hold_for_sound(placed, sum(lengths))
    # A shot can ask for a longer end hold, e.g. so a reply can be read (S05K).
    extra_hold = max(extra_hold, float(shot.get("extra_hold_s", 0)))
    if extra_hold > 0:
        still = work / f"{shot['id']}_last.png"
        run(["ffmpeg", "-y", "-v", "error", "-sseof", "-0.2", "-i", str(seg_files[-1]), "-update", "1", "-frames:v", "1", str(still)])
        hold = work / f"{shot['id']}_hold.mp4"
        run(["ffmpeg", "-y", "-v", "error", "-loop", "1", "-framerate", str(FPS), "-i", str(still),
             "-t", f"{extra_hold:.3f}", "-vf", "setsar=1", *encode_args(), str(hold)])
        seg_files.append(hold)
        lengths.append(clip_duration(hold) or extra_hold)
    shot_seconds = round(sum(lengths), 3)
    listing = work / f"{shot['id']}_segs.txt"
    listing.write_text("".join(f"file {_concat_q(p)}\n" for p in seg_files), encoding="utf-8")
    joined = work / f"{shot['id']}_joined.mp4"
    run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", str(joined)])
    out = work / f"{shot['id']}.mp4"
    if pillow_available():
        cap_png = caption_layer(shot["caption"], font, work / f"{shot['id']}_caption.png")
        badge_png = (corner_layer("Airplane mode", "✈", font, work / "badge.png", right=True, tint=(200, 235, 190))
                     if shot.get("offline") else None)
        marker_pngs = {w: corner_layer(f"sped up {w[2]}×", "▶▶", font, work / f"{shot['id']}_marker{i}.png", right=False)
                       for i, w in enumerate(markers)}
        run(["ffmpeg", "-y", "-v", "error", *styled_overlay_args(joined, cap_png, badge_png, marker_pngs, shot_seconds),
             "-an", *encode_args(), str(out)])
    else:  # plain drawtext captions
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
        "seconds": round(shot_seconds, 2),
        "held_for_speech_s": extra_hold,
        "sounds": [{"kind": snd.kind, "file": snd.path.name, "at": at, "seconds": snd.seconds} for snd, at in placed],
    }
    shot_len = clip_duration(out) or edl["seconds"]
    if abs(shot_len - shot_seconds) > 0.15:
        # Sounds are placed against the planned length; a different real length would cut
        # the end of a reply or put it over the next shot.
        raise EditError(f"{shot['id']}: rendered {shot_len:.2f} s but its parts add up to {shot_seconds:.2f} s")
    edl["audio"] = str(render_shot_audio(placed, shot_len, work / f"{shot['id']}_audio.wav"))
    return out, edl


ICON_DRAWABLES = REPO / "app" / "src" / "main" / "res" / "drawable"


def icon_svg(drawables: Path = ICON_DRAWABLES) -> str | None:
    """The launcher icon (background + foreground VectorDrawables) as a rounded-square SVG.

    The whole 108-unit canvas is shown: the jandal is drawn larger than the adaptive-icon
    safe zone, so the usual 72-unit crop would cut it off.
    """
    import xml.etree.ElementTree as ET
    android = "{http://schemas.android.com/apk/res/android}"
    paths = []
    for name in ("ic_launcher_background.xml", "ic_launcher_foreground.xml"):
        f = drawables / name
        if not f.exists():
            return None
        for node in ET.parse(f).getroot().iter("path"):
            attrs = [f'd="{" ".join(node.get(android + "pathData", "").split())}"',
                     f'fill="{node.get(android + "fillColor") or "none"}"']
            if node.get(android + "fillType", "").lower() == "evenodd":
                attrs.append('fill-rule="evenodd"')
            if node.get(android + "strokeColor"):
                attrs.append(f'stroke="{node.get(android + "strokeColor")}" '
                             f'stroke-width="{node.get(android + "strokeWidth", "1")}"')
            paths.append("<path " + " ".join(attrs) + "/>")
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">'
            '<defs><clipPath id="m"><rect width="108" height="108" rx="24" ry="24"/></clipPath></defs>'
            f'<g clip-path="url(#m)">{"".join(paths)}</g></svg>')


def default_icon(work: Path) -> Path | None:
    """Render the app's own launcher icon to PNG (needs rsvg-convert); None if unavailable."""
    svg = icon_svg()
    if not svg or not shutil.which("rsvg-convert"):
        return None
    src, png = work / "icon.svg", work / "icon.png"
    src.write_text(svg, encoding="utf-8")
    res = subprocess.run(["rsvg-convert", "-w", "512", "-h", "512", str(src), "-o", str(png)], capture_output=True)
    return png if res.returncode == 0 else None


def render_card(card: dict, work: Path, font: Path, icon: Path | None, name: str) -> Path:
    """A title or end card: icon, title and lines on the dark background."""
    out = work / f"{name}.mp4"
    title = work / f"{name}_title.txt"
    title.write_text(card["title"], encoding="utf-8")
    filt = f"[0:v]drawtext=fontfile={_q(font)}:fontcolor=white:textfile={_q(title)}:fontsize=96:x=(w-tw)/2:y=h*0.52"
    for i, line in enumerate(card["lines"]):
        p = work / f"{name}_line{i}.txt"
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


AUDIO_FMT = ["-ar", "48000", "-ac", "2", "-c:a", "pcm_s16le"]
SPEECH_LUFS = -16.0  # each voice item is levelled to this before mixing
MUSIC_LUFS = -24.0   # music bed, before ducking: ~8 LU under speech, so music-only shots still carry
VOICE_TARGET_LUFS = -13.5  # the master gain puts the voices here; the music follows


def loudness(path: Path) -> float | None:
    """Integrated loudness (LUFS) of a file, from ffmpeg's loudnorm analysis."""
    res = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", str(path), "-af",
                          "loudnorm=print_format=json", "-f", "null", "-"], capture_output=True, text=True)
    # ffmpeg prints the JSON block followed by its usual summary lines.
    m = re.search(r'"input_i"\s*:\s*"(-?(?:\d+(?:\.\d+)?|inf))"', res.stderr)
    if not m:
        return None
    value = float(m.group(1))
    return value if math.isfinite(value) else None


def silence(seconds: float, out: Path) -> Path:
    run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
         "-t", f"{seconds:.3f}", *AUDIO_FMT, str(out)])
    return out


def render_shot_audio(placed: list[tuple[Sound, float]], seconds: float, out: Path) -> Path:
    """One shot's voice track: each sound levelled and placed at its output time."""
    if not placed:
        return silence(seconds, out)
    inputs, graph = ["-f", "lavfi", "-t", f"{seconds:.3f}", "-i", "anullsrc=r=48000:cl=stereo"], []
    for i, (snd, at) in enumerate(placed, start=1):
        inputs += ["-i", str(snd.path)]
        lufs = loudness(snd.path)
        gain = 0.0 if lufs is None else SPEECH_LUFS - lufs
        ms = int(round(at * 1000))
        graph.append(f"[{i}:a]aresample=48000,aformat=channel_layouts=stereo,volume={gain:.2f}dB,"
                     f"adelay={ms}|{ms}[a{i}]")
    mix = "".join(f"[a{i}]" for i in range(1, len(placed) + 1))
    graph.append(f"[0:a]{mix}amix=inputs={len(placed) + 1}:normalize=0:duration=first[out]")
    run(["ffmpeg", "-y", "-v", "error", *inputs, "-filter_complex", ";".join(graph), "-map", "[out]",
         "-t", f"{seconds:.3f}", *AUDIO_FMT, str(out)])
    return out


def final_mix(voice: Path, music: Path | None, seconds: float, out: Path) -> Path:
    """Voice plus music ducked under it, with a -1.5 dBFS ceiling.

    The master gain is set from the voice track alone. Set from the whole mix, long
    music-only stretches pulled the average down, the gain went up, and the limiter then
    flattened the speech while the music rose to the same level.
    """
    pre = out.with_name("premix.wav")
    if music:
        m_lufs = loudness(music)
        m_gain = 0.0 if m_lufs is None else MUSIC_LUFS - m_lufs
        fade_out = max(0.0, seconds - 2.5)
        graph = (f"[1:a]aresample=48000,aformat=channel_layouts=stereo,volume={m_gain:.2f}dB,"
                 f"atrim=0:{seconds:.3f},afade=in:d=0.6,afade=out:st={fade_out:.3f}:d=2.5[m];"
                 "[0:a]asplit[v][key];"
                 "[m][key]sidechaincompress=threshold=0.015:ratio=10:attack=15:release=500[duck];"
                 "[v][duck]amix=inputs=2:normalize=0:duration=first[out]")
        run(["ffmpeg", "-y", "-v", "error", "-i", str(voice), "-stream_loop", "-1", "-i", str(music),
             "-filter_complex", graph, "-map", "[out]", "-t", f"{seconds:.3f}", *AUDIO_FMT, str(pre)])
    else:
        shutil.copy(voice, pre)
    lufs = loudness(voice)
    gain = 0.0 if lufs is None else VOICE_TARGET_LUFS - lufs
    run(["ffmpeg", "-y", "-v", "error", "-i", str(pre), "-af",
         f"volume={gain:.2f}dB,alimiter=limit=0.84:level=disabled", *AUDIO_FMT, str(out)])
    return out


def concat_audio(parts: list[Path], out: Path) -> Path:
    listing = out.with_suffix(".txt")
    listing.write_text("".join(f"file {_concat_q(p)}\n" for p in parts), encoding="utf-8")
    run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", str(listing), *AUDIO_FMT, str(out)])
    return out


def shot_sounds(take: dict, voice_dir: Path | None, cori_cmd: str | None, cache: Path) -> list[Sound]:
    """The spoken input fixtures and Jandal's re-rendered Cori replies for one take."""
    sounds = []
    for ev in take.get("audio", []):
        if voice_dir is None:
            raise EditError(f"{take['id']}: take has spoken input; pass --voice-dir (or set DEMO_VOICE_DIR)")
        wav = voice_dir / f"{ev['fixture']}.wav"
        if not wav.exists():
            raise EditError(f"{take['id']}: missing fixture {wav}")
        sounds.append(Sound(wav, float(ev["t"]), clip_duration(wav) or float(ev.get("seconds") or 0), "input"))
    for ev in take.get("speech", []):
        if ev.get("text_incomplete"):
            raise EditError(f"{take['id']}: a spoken reply was cut off in logcat; re-take the shot")
        if not ev["text"].strip():
            continue
        if not cori_cmd:
            raise EditError(f"{take['id']}: take has Jandal speech; set DEMO_CORI_CMD (see shot-script.md)")
        import hashlib
        wav = cache / f"cori_{hashlib.sha256(ev['text'].encode()).hexdigest()[:16]}.wav"
        if not wav.exists():
            cache.mkdir(parents=True, exist_ok=True)
            res = subprocess.run(speech_cmd(cori_cmd, ev["text"], wav), capture_output=True, text=True)
            if res.returncode != 0 or not wav.exists():
                raise EditError(f"DEMO_CORI_CMD failed for {ev['text'][:40]!r}: {res.stderr[-500:]}")
        sounds.append(Sound(wav, float(ev["t"]), clip_duration(wav) or 0.0, "jandal"))
    return sounds


def join(parts: list[Path], out: Path, voiceover: Path | None, work: Path,
         audio_parts: list[Path] | None = None, music: Path | None = None) -> None:
    listing = work / "concat.txt"
    listing.write_text("".join(f"file {_concat_q(p)}\n" for p in parts), encoding="utf-8")
    joined = work / "joined.mp4"
    run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", str(listing),
         "-c", "copy", str(joined)])
    if audio_parts:
        voice = concat_audio(audio_parts, work / "voice.wav")
        voiceover = final_mix(voice, music, clip_duration(joined) or 0.0, work / "mix.wav")
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
    ap.add_argument("--voiceover", type=Path, help="Audio file used as the whole soundtrack (skips the voice mix)")
    ap.add_argument("--music", type=Path, help="Music bed, looped/trimmed to the cut and ducked under speech")
    ap.add_argument("--voice-dir", type=Path, help="Directory with the demo_*.wav input fixtures (or DEMO_VOICE_DIR)")
    ap.add_argument("--silent", action="store_true", help="No voice mix (as before spoken input)")
    ap.add_argument("--env-file", type=Path, default=Path("~/.config/jandal-demo/demo.env"),
                    help="KEY=VALUE file for DEMO_CORI_CMD and DEMO_VOICE_DIR")
    ap.add_argument("--icon", type=Path, help="PNG for the title and end cards (default: the app's launcher icon)")
    ap.add_argument("--font", type=Path)
    ap.add_argument("--variant", help="Use intro_variants[NAME] from shots.json as the order, e.g. 'kiwi'")
    ap.add_argument("--shots", default="intro",
                    help="'intro' (full cut with end card) or comma-separated ids for a preview without one")
    ap.add_argument("--allow-over-cap", action="store_true", help="Write a cut longer than the 150 s cap anyway")
    args = ap.parse_args(argv)

    if not shutil.which("ffmpeg"):
        print("error: ffmpeg not found", file=sys.stderr)
        return 2
    from demo_capture import load_env_file
    load_env_file(args.env_file)
    voice_dir = args.voice_dir or (Path(os.environ["DEMO_VOICE_DIR"]) if os.environ.get("DEMO_VOICE_DIR") else None)
    voice_dir = voice_dir.expanduser() if voice_dir else None
    cori_cmd = os.environ.get("DEMO_CORI_CMD")
    mix = not (args.silent or args.voiceover)
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
            audio_parts: list[Path] = []
            cache = clips / "speech_cache"  # Cori renders, keyed by text, reused across edits
            preview = args.shots != "intro"
            full_order = shots_data["intro_order"]
            if args.variant:
                variants = shots_data.get("intro_variants", {})
                if args.variant not in variants:
                    raise EditError(f"unknown --variant {args.variant!r}; shots.json has: {', '.join(variants) or 'none'}")
                full_order = variants[args.variant]
            order = full_order if not preview else [x.strip() for x in args.shots.split(",") if x.strip()]
            unknown = [sid for sid in order if sid not in by_id]
            if unknown:
                raise EditError(f"unknown shot ids: {', '.join(unknown)}")
            icon = args.icon or default_icon(work)
            title_card = shots_data.get("title_card")
            if title_card and not preview:
                parts.append(render_card(title_card, work, font, icon, "title_card"))
                audio_parts.append(silence(clip_duration(parts[-1]) or title_card["seconds"], work / "title.wav"))
                edl["title_card"] = {"seconds": title_card["seconds"]}
            for sid in order:
                take = pick_take(manifest, sid, picks)
                sounds = shot_sounds(take, voice_dir, cori_cmd, cache) if mix else []
                part, shot_edl = render_shot(clips / take["clip"], by_id[sid], take, work, font, args.max_wait, sounds)
                parts.append(part)
                audio_parts.append(Path(shot_edl.pop("audio")))
                shot_edl["build"] = take.get("build")
                edl["shots"].append(shot_edl)
                if take.get("build") and take["build"] not in edl["builds"]:
                    edl["builds"].append(take["build"])
            title_seconds = title_card["seconds"] if (title_card and not preview) else 0
            planned = sum(s["seconds"] for s in edl["shots"]) + card["seconds"] + title_seconds
            if planned > CAP_SECONDS and not args.allow_over_cap and not preview:
                longest = sorted(edl["shots"], key=lambda s: -s["seconds"])[:3]
                raise EditError(f"cut is {planned:.1f} s, over the {CAP_SECONDS} s cap; longest shots: "
                                + ", ".join(f"{s['id']} {s['seconds']} s" for s in longest)
                                + " (or pass --allow-over-cap)")
            if not preview:
                parts.append(render_card(card, work, font, icon, "end_card"))
                audio_parts.append(silence(clip_duration(parts[-1]) or card["seconds"], work / "end.wav"))
            join(parts, out, args.voiceover, work, audio_parts if mix else None, args.music)
            if args.music:
                edl["music"] = args.music.name
        edl["seconds"] = round(sum(s["seconds"] for s in edl["shots"])
                               + (0 if preview else card["seconds"] + edl.get("title_card", {}).get("seconds", 0)), 2)
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
