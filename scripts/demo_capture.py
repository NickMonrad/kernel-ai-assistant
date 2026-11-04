#!/usr/bin/env python3
"""Record the #1736 showcase shots on a device, one clip per shot.

Reads docs/demo/shots.json, drives the app through the UI (uiautomator + ``input``)
so it works on release builds as well as debug builds, and records each shot with
``scrcpy --record``. Writes one MP4 per shot plus ``manifest.json`` with build
provenance and per-step timestamps. ``scripts/demo_edit.py`` consumes the manifest.

Video files are never written into the repo: ``--out`` must be outside it.

Examples::

    # Print the plan without touching a device
    python3 scripts/demo_capture.py --dry-run --shots S02,S03

    # Record the intro shots on the S23U from the RC build
    python3 scripts/demo_capture.py --serial RFCW11265XH --package com.kernel.ai \\
        --commit <rc-sha> --out ~/jandal-demo/rc1 --shots intro

S01 also needs ``DEMO_WAKE_CMD``: the host command that plays the Hey Jandal and
command fixtures from the second device (the #1329 J10 path). It is run with
``shlex.split`` while recording.

Network per shot: the runner turns airplane mode on (and Wi-Fi off) before every
``offline`` shot and off again before every online shot, so the whole intro records
in one run. That needs USB adb, because airplane mode drops wireless adb.

``device: "second"`` shots (S07B) record on ``--second-serial``, the second Nextcloud
client. Shots with ``redact_env`` (S07A/S07B) need those env vars set to the test
Nextcloud server host and username; every on-screen node containing either value is
recorded in the manifest and blurred by ``scripts/demo_edit.py``.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

REPO = Path(__file__).resolve().parent.parent
SHOTS_FILE = REPO / "docs" / "demo" / "shots.json"
DEFAULT_PACKAGE = "com.kernel.ai.debug"
ACTIVITY = "com.kernel.ai.MainActivity"
UI_DUMP = "/sdcard/jandal_demo_ui.xml"
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class CaptureError(RuntimeError):
    pass


# ── shot list ──────────────────────────────────────────────────────────────


def load_shots(path: Path = SHOTS_FILE) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    ids = [s["id"] for s in data["shots"]]
    if len(ids) != len(set(ids)):
        raise CaptureError("duplicate shot ids in shots.json")
    for sid in data.get("intro_order", []):
        if sid not in ids:
            raise CaptureError(f"intro_order names unknown shot {sid}")
    for shot in data["shots"]:
        if shot.get("device", "primary") not in ("primary", "second"):
            raise CaptureError(f"{shot['id']}: device must be 'primary' or 'second'")
        if shot.get("device") == "second" and shot.get("offline"):
            # The second client only exists to show a synced change; it is never offline.
            raise CaptureError(f"{shot['id']}: second-device shots cannot be offline")
    return data


def select_shots(data: dict, selector: str) -> list[dict]:
    by_id = {s["id"]: s for s in data["shots"]}
    if selector == "intro":
        return [by_id[i] for i in data["intro_order"]]
    if selector == "all":
        return list(data["shots"])
    wanted = [x.strip() for x in selector.split(",") if x.strip()]
    missing = [w for w in wanted if w not in by_id]
    if missing:
        raise CaptureError(f"unknown shot ids: {', '.join(missing)}")
    return [by_id[w] for w in wanted]


# ── ui helpers (pure, unit-tested) ─────────────────────────────────────────


def escape_input_text(text: str) -> str:
    """Escape *text* for ``adb shell input text``.

    ``input text`` treats ``%s`` as a space, and the device shell re-parses the
    argument, so shell metacharacters are backslash-escaped.
    """
    out = []
    for ch in text:
        if ch == " ":
            out.append("%s")
        elif ch in "\\'\"`$&|;<>()[]{}*?!#~":
            out.append("\\" + ch)
        else:
            out.append(ch)
    return "".join(out)


def find_node(xml_text: str, *, text: str | None = None, desc: str | None = None,
              cls: str | None = None) -> tuple[int, int] | None:
    """Return the centre of the first node matching all given attributes.

    ``text`` and ``desc`` match exactly first, then case-insensitively as a substring.
    """
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return None
    nodes = list(root.iter("node"))

    def centre(node: ET.Element) -> tuple[int, int] | None:
        m = BOUNDS_RE.fullmatch(node.get("bounds", ""))
        if not m:
            return None
        x1, y1, x2, y2 = map(int, m.groups())
        return (x1 + x2) // 2, (y1 + y2) // 2

    def matches(node: ET.Element, exact: bool) -> bool:
        for attr, want in (("text", text), ("content-desc", desc)):
            if want is None:
                continue
            have = node.get(attr, "")
            if exact and have != want:
                return False
            if not exact and want.lower() not in have.lower():
                return False
        if cls is not None and node.get("class") != cls:
            return False
        return True

    for exact in (True, False):
        for node in nodes:
            if matches(node, exact):
                c = centre(node)
                if c:
                    return c
    return None


def ui_contains(xml_text: str, *, text: str | None = None, desc: str | None = None) -> bool:
    return find_node(xml_text, text=text, desc=desc) is not None


def nodes_containing(xml_text: str, needles: list[str]) -> list[tuple[int, int, int, int]]:
    """Bounds ``(x1, y1, x2, y2)`` of every node whose text or desc contains a needle.

    Case-insensitive. Used to locate the Nextcloud server URL and username for blurring.
    """
    wanted = [n.lower() for n in needles if n]
    if not wanted:
        return []
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []
    found = []
    for node in root.iter("node"):
        have = (node.get("text", "") + "\n" + node.get("content-desc", "")).lower()
        if any(w in have for w in wanted):
            m = BOUNDS_RE.fullmatch(node.get("bounds", ""))
            if m:
                box = tuple(map(int, m.groups()))
                if box not in found:
                    found.append(box)
    return found


def airplane_commands(on: bool) -> list[str]:
    """Device shell commands that put the radio into the shot's network mode.

    Offline is literal (shot-script.md rule 5): airplane mode on and Wi-Fi off, even if
    the device remembers Wi-Fi as on in airplane mode.
    """
    if on:
        return ["cmd connectivity airplane-mode enable", "svc wifi disable"]
    return ["cmd connectivity airplane-mode disable", "svc wifi enable"]


def has_validated_network(dumpsys_connectivity: str) -> bool:
    return any("NetworkAgentInfo" in line and "VALIDATED" in line
               for line in dumpsys_connectivity.splitlines())


# ── device ─────────────────────────────────────────────────────────────────


@dataclass
class Device:
    serial: str
    package: str
    adb: str = field(default_factory=lambda: os.environ.get("ADB_PATH") or shutil.which("adb") or "adb")

    def run(self, *args: str, timeout: float = 30) -> str:
        cmd = [self.adb, "-s", self.serial, *args]
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            raise CaptureError(f"adb timed out: {' '.join(args)}") from exc
        if res.returncode != 0:
            raise CaptureError(f"adb failed ({' '.join(args)}): {res.stderr.strip() or res.stdout.strip()}")
        return res.stdout

    def shell(self, cmd: str, timeout: float = 30) -> str:
        return self.run("shell", cmd, timeout=timeout)

    def dump_ui(self) -> str:
        self.run("shell", "uiautomator", "dump", UI_DUMP, timeout=20)
        return self.run("shell", "cat", UI_DUMP)

    def tap_xy(self, x: int, y: int) -> None:
        self.run("shell", "input", "tap", str(x), str(y))

    def set_offline(self, offline: bool, timeout: float = 45) -> str:
        """Switch airplane mode for the next shot; returns ``airplane_mode_on`` afterwards."""
        for cmd in airplane_commands(offline):
            self.shell(cmd)
        deadline = time.monotonic() + timeout
        while True:
            state = self.shell("settings get global airplane_mode_on").strip()
            ready = state == ("1" if offline else "0")
            if ready and not offline:
                ready = has_validated_network(self.shell("dumpsys connectivity", timeout=30))
            if ready:
                return state
            if time.monotonic() > deadline:
                mode = "offline" if offline else "online with a validated network"
                raise CaptureError(f"{self.serial} did not go {mode} within {timeout:.0f}s")
            time.sleep(1.0)

    def provenance(self) -> dict:
        def safe(fn: Callable[[], str]) -> str:
            try:
                return fn().strip()
            except CaptureError as exc:
                return f"unavailable: {exc}"

        pkg = safe(lambda: self.shell(f"dumpsys package {self.package}"))
        version_name = re.search(r"versionName=(\S+)", pkg)
        version_code = re.search(r"versionCode=(\d+)", pkg)
        apk_path = safe(lambda: self.shell(f"pm path {self.package}")).splitlines()
        base = next((p.split(":", 1)[1] for p in apk_path if p.endswith("base.apk")), None)
        apk_sha = safe(lambda: self.shell(f"sha256sum {base}")).split()[0] if base else None
        return {
            "serial": self.serial,
            "model": safe(lambda: self.shell("getprop ro.product.model")),
            "android": safe(lambda: self.shell("getprop ro.build.version.release")),
            "build": safe(lambda: self.shell("getprop ro.build.display.id")),
            "package": self.package,
            "version_name": version_name.group(1) if version_name else None,
            "version_code": version_code.group(1) if version_code else None,
            "apk_sha256": apk_sha,
            "airplane_mode": safe(lambda: self.shell("settings get global airplane_mode_on")),
        }


# ── step execution ─────────────────────────────────────────────────────────


@dataclass
class StepLog:
    events: list[dict] = field(default_factory=list)
    compress: list[dict] = field(default_factory=list)
    redact: list[list[int]] = field(default_factory=list)

    def add_redact(self, boxes: list[tuple[int, int, int, int]]) -> None:
        for box in boxes:
            if list(box) not in self.redact:
                self.redact.append(list(box))


class ShotRunner:
    def __init__(self, device: Device, t0: float, log: StepLog, poll: float = 1.0,
                 redact: list[str] | None = None):
        self.d = device
        self.t0 = t0
        self.log = log
        self.poll = poll
        self.redact = [r for r in (redact or []) if r]

    def now(self) -> float:
        return round(time.monotonic() - self.t0, 2)

    def tap(self, *, text: str | None = None, desc: str | None = None,
            cls: str | None = None, timeout: float = 15) -> None:
        deadline = time.monotonic() + timeout
        while True:
            pos = find_node(self.d.dump_ui(), text=text, desc=desc, cls=cls)
            if pos:
                self.d.tap_xy(*pos)
                time.sleep(0.6)
                return
            if time.monotonic() > deadline:
                raise CaptureError(f"no on-screen node for text={text!r} desc={desc!r} cls={cls!r}")
            time.sleep(self.poll)

    def type_and_send(self, text: str) -> None:
        self.tap(cls="android.widget.EditText")
        # Type in short chunks so the recording shows the text being entered.
        words = text.split(" ")
        for i, word in enumerate(words):
            chunk = word + (" " if i < len(words) - 1 else "")
            self.d.run("shell", "input", "text", escape_input_text(chunk))
            time.sleep(0.12)
        time.sleep(0.5)
        self.tap(desc="Send")

    def wait_for(self, *, text: str | None, desc: str | None, gone: bool, timeout: float,
                 appear_timeout: float = 20) -> None:
        if gone:
            # The node may not exist yet when the step starts; wait for it first.
            deadline = time.monotonic() + appear_timeout
            while not ui_contains(self.d.dump_ui(), text=text, desc=desc):
                if time.monotonic() > deadline:
                    return  # finished before we saw it (e.g. instant reply)
                time.sleep(self.poll)
        deadline = time.monotonic() + timeout
        while True:
            present = ui_contains(self.d.dump_ui(), text=text, desc=desc)
            if present != gone:
                return
            if time.monotonic() > deadline:
                state = "still visible" if gone else "never appeared"
                raise CaptureError(f"wait timed out ({timeout}s): text={text!r} desc={desc!r} {state}")
            time.sleep(self.poll)

    def run_step(self, step: dict) -> None:
        kind = step["do"]
        start = self.now()
        if kind in ("launch", "restart"):
            if kind == "restart":
                self.d.run("shell", "am", "force-stop", self.d.package)
            self.d.run("shell", "am", "start", "-W", "--activity-clear-top",
                       "-n", f"{self.d.package}/{ACTIVITY}")
            time.sleep(1.5)
        elif kind == "tap":
            self.tap(text=step.get("text"), desc=step.get("desc"))
        elif kind == "type":
            self.type_and_send(step["text"])
        elif kind in ("wait", "wait_gone"):
            self.wait_for(text=step.get("text"), desc=step.get("desc"), gone=kind == "wait_gone",
                          timeout=float(step.get("timeout", 30)))
        elif kind == "sleep":
            time.sleep(float(step["s"]))
        elif kind == "key":
            self.d.run("shell", "input", "keyevent", step["code"])
        elif kind == "shell":
            self.d.shell(step["cmd"])
        elif kind == "host":
            cmd = os.environ.get(step["env"], "")
            if not cmd:
                raise CaptureError(f"step needs {step['env']} (see the shot's note)")
            res = subprocess.run(shlex.split(cmd), cwd=REPO, timeout=float(step.get("timeout", 180)))
            if res.returncode != 0:
                raise CaptureError(f"host step exited {res.returncode}")
        else:
            raise CaptureError(f"unknown step kind {kind!r}")
        end = self.now()
        event = {"do": kind, "start": start, "end": end}
        for key in ("text", "desc", "env", "start_here"):
            if key in step:
                event[key] = step[key]
        self.log.events.append(event)
        if step.get("compress"):
            self.log.compress.append({"start": start, "end": end})
        if self.redact:
            self.log.add_redact(nodes_containing(self.d.dump_ui(), self.redact))


# ── recording ──────────────────────────────────────────────────────────────


def scrcpy_cmd(serial: str, out_file: Path, scrcpy: str = "scrcpy") -> list[str]:
    return [
        scrcpy, "-s", serial,
        "--record", str(out_file),
        "--no-playback",          # scrcpy >= 2.2; no window needed
        "--no-audio",             # device audio is not used in the cut (see shot-script.md)
        "--max-fps", "30",
        "--video-bit-rate", "16M",
        "--stay-awake",
    ]


def redact_values(shot: dict) -> list[str]:
    """Values to blur for *shot*; every named env var must be set."""
    names = shot.get("redact_env", [])
    missing = [n for n in names if not os.environ.get(n)]
    if missing:
        raise CaptureError(f"{shot['id']} needs {', '.join(missing)} set so the server and username can be blurred")
    return [os.environ[n] for n in names]


def record_shot(device: Device, shot: dict, out_dir: Path, take: int, scrcpy: str) -> dict:
    redact = redact_values(shot)
    airplane = device.set_offline(bool(shot.get("offline")))
    clip = out_dir / f"{shot['id']}_take{take}.mp4"
    proc = subprocess.Popen(scrcpy_cmd(device.serial, clip, scrcpy),
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    time.sleep(2.0)  # let scrcpy start encoding before t0
    if proc.poll() is not None:
        raise CaptureError(f"scrcpy exited early: {proc.stderr.read() if proc.stderr else ''}")
    t0 = time.monotonic()
    log = StepLog()
    runner = ShotRunner(device, t0, log, redact=redact)
    status, error = "ok", None
    try:
        for step in shot["steps"]:
            runner.run_step(step)
    except CaptureError as exc:
        status, error = "failed", str(exc)
    finally:
        time.sleep(0.5)
        proc.send_signal(signal.SIGINT)
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
    return {
        "id": shot["id"],
        "take": take,
        "clip": clip.name,
        "serial": device.serial,
        "airplane_mode": airplane,
        "status": status,
        "error": error,
        "lead_in_s": 2.0,
        "duration_s": runner.now(),
        "events": log.events,
        "compress": log.compress,
        "redact": log.redact,
        "recorded_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
    }


def update_manifest(path: Path, header: dict, result: dict) -> dict:
    manifest = json.loads(path.read_text()) if path.exists() else {"takes": []}
    manifest.update(header)
    manifest["takes"].append(result)
    path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    return manifest


def describe(shot: dict) -> str:
    lines = [f"{shot['id']}  {shot['feature']}  (device={shot.get('device', 'primary')}, "
             f"offline={shot.get('offline', False)}, model={shot.get('model', False)})"]
    if shot.get("redact_env"):
        lines.append(f"    blur      values of {', '.join(shot['redact_env'])}")
    for step in shot["steps"]:
        detail = ", ".join(f"{k}={v!r}" for k, v in step.items() if k not in ("do", "note"))
        lines.append(f"    {step['do']:<9} {detail}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--shots", default="intro", help="'intro', 'all', or comma-separated ids")
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", ""))
    ap.add_argument("--package", default=DEFAULT_PACKAGE)
    ap.add_argument("--second-serial", default="", help="Second Nextcloud client for device='second' shots (S07B)")
    ap.add_argument("--second-package", default="", help="Package on the second device (default: --package)")
    ap.add_argument("--commit", default="", help="RC commit SHA the installed APK was built from")
    ap.add_argument("--out", type=Path, help="Output directory (must be outside the repo)")
    ap.add_argument("--take", type=int, default=1, help="Take number to record (max 3 for model shots)")
    ap.add_argument("--scrcpy", default=shutil.which("scrcpy") or "scrcpy")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)

    data = load_shots()
    shots = select_shots(data, args.shots)

    if args.dry_run:
        for shot in shots:
            print(describe(shot))
        return 0

    if not args.serial or not args.out:
        ap.error("--serial and --out are required unless --dry-run")
    out = args.out.expanduser().resolve()
    if out == REPO or REPO in out.parents:
        ap.error("--out must be outside the repository; video files are never committed")
    if args.take > 3 and any(s.get("model") for s in shots):
        ap.error("model shots allow at most 3 takes (shot-script.md)")
    out.mkdir(parents=True, exist_ok=True)

    needs_second = any(s.get("device") == "second" for s in shots)
    if needs_second and not args.second_serial:
        ap.error("device='second' shots (S07B) need --second-serial")
    if needs_second and args.second_serial == args.serial:
        ap.error("--second-serial must be a different device from --serial")
    # Airplane mode is switched per shot on the primary device, which drops wireless adb.
    if ":" in args.serial and any(s.get("device", "primary") == "primary" for s in shots):
        ap.error("the primary device needs a USB adb connection: the runner toggles airplane mode")

    device = Device(args.serial, args.package)
    second = Device(args.second_serial, args.second_package or args.package) if needs_second else None
    header = {
        "issue": 1736,
        "commit": args.commit or None,
        "device": device.provenance(),
        "shots_sha256": hashlib.sha256(SHOTS_FILE.read_bytes()).hexdigest(),
    }
    if second:
        header["second_device"] = second.provenance()
    for key, dev in (("device", device), ("second_device", second)):
        if dev and header[key]["version_name"] is None:
            print(f"error: {dev.package} is not installed on {dev.serial}", file=sys.stderr)
            return 2

    failed = 0
    for shot in shots:
        target = second if shot.get("device") == "second" else device
        mode = "offline" if shot.get("offline") else "online"
        print(f"recording {shot['id']} take {args.take} on {target.serial} ({mode}) …", flush=True)
        try:
            result = record_shot(target, shot, out, args.take, args.scrcpy)
        except CaptureError as exc:
            print(f"  not recorded: {exc}", file=sys.stderr)
            failed += 1
            continue
        update_manifest(out / "manifest.json", header, result)
        print(f"  {result['status']} ({result['duration_s']} s){': ' + result['error'] if result['error'] else ''}")
        failed += result["status"] != "ok"
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
