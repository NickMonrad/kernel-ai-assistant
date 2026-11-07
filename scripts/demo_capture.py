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
ROOT_TABS = ("Chats", "Actions", "Tools")
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
        first = shot["steps"][0]["do"] if shot["steps"] else None
        if first not in ("launch", "restart"):
            # The launch frame is the anchor that syncs the clip to the runner clock.
            raise CaptureError(f"{shot['id']}: first step must be launch or restart (sync anchor)")
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


def count_matches(xml_text: str, text: str) -> int:
    """Nodes whose text contains *text* (case-insensitive)."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return 0
    want = text.lower()
    return sum(1 for node in root.iter("node") if want in node.get("text", "").lower())


def field_text(xml_text: str) -> str:
    """Text in the first on-screen EditText ('' if none)."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return ""
    for node in root.iter("node"):
        if node.get("class") == "android.widget.EditText":
            return node.get("text", "")
    return ""


def shade_open(xml_text: str) -> bool:
    """True when the screen read is dominated by System UI (the notification shade)."""
    pkgs = re.findall(r'package="([^"]*)"', xml_text)
    return bool(pkgs) and sum(p == "com.android.systemui" for p in pkgs) > len(pkgs) / 2


def root_size(xml_text: str) -> tuple[int, int] | None:
    """Screen size in uiautomator pixels, from the root node's bounds."""
    try:
        node = next(ET.fromstring(xml_text).iter("node"))
    except (ET.ParseError, StopIteration):
        return None
    m = BOUNDS_RE.fullmatch(node.get("bounds", ""))
    return (int(m.group(3)), int(m.group(4))) if m else None


GENERIC_HOST_LABELS = {"nextcloud", "cloud", "duckdns", "dyndns", "local", "localhost", "files", "drive"}


def redact_needles(values: list[str]) -> list[str]:
    """The values to blur plus the forms the app may show them in (no scheme, no trailing slash)."""
    out: list[str] = []
    for value in values:
        v = value.strip()
        bare = re.sub(r"^[a-z]+://", "", v, flags=re.I)
        forms = [v, bare, bare.rstrip("/")]
        if "://" in v:
            # A long host can be shown truncated ("nextcloud.example-home.duck…"), so also
            # match its distinctive labels; generic ones would blur UI like "Sync with Nextcloud".
            host = bare.split("/", 1)[0]
            forms += [label for label in host.split(".")
                      if len(label) >= 5 and label.lower() not in GENERIC_HOST_LABELS]
        for form in forms:
            if form and form not in out:
                out.append(form)
    return out


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
                if box[2] <= box[0] or box[3] <= box[1]:
                    continue  # zero-size (off-screen) node: nothing visible to blur
                if box not in found:
                    found.append(box)
    return found


def airplane_commands(on: bool) -> list[str]:
    """Device shell commands that put the radio into the shot's network mode.

    Offline is literal (shot-script.md rule 5): airplane mode on and Wi-Fi off, even if
    the device remembers Wi-Fi as on in airplane mode.
    """
    # Checked on the S21 (Android 15) and S23U (Android 16); `svc wifi` is the older form.
    if on:
        return ["cmd connectivity airplane-mode enable", "cmd wifi set-wifi-enabled disabled"]
    return ["cmd connectivity airplane-mode disable", "cmd wifi set-wifi-enabled enabled"]


def has_validated_network(dumpsys_connectivity: str) -> bool:
    """True when the *active default* network is currently validated.

    Other networks (e.g. an IMS-only mobile network) and the ``EVER_VALIDATED`` policy
    do not count.
    """
    m = re.search(r"Active default network: (\d+)", dumpsys_connectivity)
    if not m:
        return False
    tag = f"NetworkAgentInfo{{network{{{m.group(1)}}}"
    return any(tag in line and "IS_VALIDATED" in line for line in dumpsys_connectivity.splitlines())


def scene_changes(showinfo_log: str) -> list[float]:
    """Frame times picked by ffmpeg's ``select='gt(scene,X)',showinfo``."""
    return [float(t) for t in re.findall(r"pts_time:([0-9.]+)", showinfo_log)]


def lead_in_from_anchor(changes: list[float], anchor: float, max_lead: float = 6.0) -> float | None:
    """Clip time minus runner time, from the first big screen change.

    Before ``t0`` the runner leaves the device on the home screen, so the first scene
    change in the clip is the app appearing. ``anchor`` is the runner time at which
    ``am start -W`` returned, i.e. the app's first frame was drawn. Measured on the S23U
    this gave 1.38-1.45 s and predicted a later event to within ~0.2 s; the old fixed
    2.0 s was out by about 0.6 s.
    """
    for c in changes:
        lead = c - anchor
        if 0.0 <= lead <= max_lead:
            return round(lead, 3)
    return None


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

    def dump_ui(self, attempts: int = 4) -> str:
        """Fresh uiautomator dump; never returns a previous screen.

        ``uiautomator dump`` exits 0 even when it fails (e.g. "could not get idle state"
        while a reply streams), so the old file is removed first and the output checked.
        """
        last = ""
        for _ in range(attempts):
            self.run("shell", "rm", "-f", UI_DUMP)
            last = self.run("shell", "uiautomator", "dump", UI_DUMP, timeout=20)
            if "dumped to" in last:
                return self.run("shell", "cat", UI_DUMP)
            time.sleep(0.5)
        raise CaptureError(f"uiautomator dump failed {attempts}x: {last.strip()}")

    def unlock(self, pin: str) -> None:
        """Unlock a PIN-locked device. The PIN is passed to adb only, never logged."""
        self.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self.run("shell", "input", "swipe", "540", "1900", "540", "600", "250")
        time.sleep(1.0)
        try:
            self.run("shell", "input", "text", pin)
        except CaptureError:
            # run() quotes the command in its error, which would put the PIN in logs.
            raise CaptureError(f"{self.serial}: entering the unlock PIN failed") from None
        self.run("shell", "input", "keyevent", "KEYCODE_ENTER")
        time.sleep(1.5)

    def prepare_screen(self) -> None:
        """Awake, unlocked, shade closed, on the home screen (the sync anchor needs a change)."""
        self.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        if "isKeyguardShowing=true" in self.shell("dumpsys window | grep -m1 isKeyguardShowing"):
            pin = unlock_pin()
            if pin:
                self.unlock(pin)
            if "isKeyguardShowing=true" in self.shell("dumpsys window | grep -m1 isKeyguardShowing"):
                raise CaptureError(f"{self.serial} is locked; unlock it (or set DEMO_UNLOCK_PIN_FILE) before recording")
        self.shell("cmd statusbar collapse")
        self.run("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(1.2)

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

    def zen_mode(self) -> str:
        return self.shell("settings get global zen_mode").strip()

    def set_dnd(self, mode: str) -> None:
        """mode: 'alarms' (only alarms break through), 'priority', 'none' or 'off'."""
        self.shell(f"cmd notification set_dnd {mode}")

    def provenance(self) -> dict:
        def safe(fn: Callable[[], str]) -> str:
            try:
                return fn().strip()
            except CaptureError as exc:
                return f"unavailable: {exc}"

        pkg = safe(lambda: self.shell(f"dumpsys package {self.package}"))
        version_name = re.search(r"versionName=(\S+)", pkg)
        version_code = re.search(r"versionCode=(\d+)", pkg)
        # --user 0: the S23U has a second profile that the shell may not query.
        apk_path = safe(lambda: self.shell(f"pm path --user 0 {self.package}")).splitlines()
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
        self.redact = redact_needles(redact or [])
        self.last_xml = ""
        self.baseline_xml = ""
        self.anchor: float | None = None

    def dump(self) -> str:
        """Every screen read goes through here, so redaction sees every screen we look at."""
        xml = self.d.dump_ui()
        if shade_open(xml):
            # The shade shows real notifications (names, locations); never keep recording it.
            raise CaptureError("notification shade or system UI in front; take discarded")
        self.last_xml = xml
        if self.redact:
            self.log.add_redact(nodes_containing(xml, self.redact))
        return xml

    def now(self) -> float:
        return round(time.monotonic() - self.t0, 2)

    def tap(self, *, text: str | None = None, desc: str | None = None,
            cls: str | None = None, timeout: float = 15) -> None:
        deadline = time.monotonic() + timeout
        while True:
            pos = find_node(self.dump(), text=text, desc=desc, cls=cls)
            if pos:
                self.d.tap_xy(*pos)
                time.sleep(0.6)
                return
            if time.monotonic() > deadline:
                raise CaptureError(f"no on-screen node for text={text!r} desc={desc!r} cls={cls!r}")
            time.sleep(self.poll)

    def to_root_tabs(self, attempts: int = 3) -> None:
        """Leave any open conversation or sub-screen: the app reopens where it was left,
        and shots start from the bottom tabs (Chats / Actions / Tools)."""
        for _ in range(attempts):
            xml = self.dump()
            if all(find_node(xml, text=t) for t in ROOT_TABS):
                # Always start from Actions: on the S23U the Chats tab ignores taps while
                # Tools is selected (#1743), and both other tabs work from Actions.
                if not find_node(xml, desc="Run quick action"):
                    self.d.tap_xy(*find_node(xml, text="Actions"))
                    time.sleep(0.8)
                return
            self.d.run("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.8)
        raise CaptureError(f"could not get back to the {'/'.join(ROOT_TABS)} tabs")

    def type_and_send(self, text: str, retries: int = 3) -> None:
        self.tap(cls="android.widget.EditText")
        time.sleep(0.6)  # let the keyboard bind to the field before the first key
        # Type in short chunks so the recording shows the text being entered.
        words = text.split(" ")
        for i, word in enumerate(words):
            chunk = word + (" " if i < len(words) - 1 else "")
            self.d.run("shell", "input", "text", escape_input_text(chunk))
            time.sleep(0.12)
        time.sleep(0.4)
        # Read back before sending. Fast chunks can be reordered by the keyboard (seen on
        # the S23U: "Give me tree ideas for a rainy Sundayh"), and a mangled prompt must
        # never be sent. This read also finds Send after the keyboard has moved it.
        # Seen in the chat composer right after the model loads ("tat" for "that"),
        # intermittently and even for a single-call retype, so retry a few times.
        xml = self.dump()
        for _ in range(retries):
            if field_text(xml) == text:
                break
            self.d.run("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            self.d.run("shell", "input", "keyevent", *(["KEYCODE_DEL"] * (len(field_text(xml)) + 5)))
            time.sleep(1.0)
            self.d.run("shell", "input", "text", escape_input_text(text))
            time.sleep(0.8)
            xml = self.dump()
        if field_text(xml) != text:
            raise CaptureError(f"text field reads {field_text(xml)!r}, expected {text!r}")
        pos = find_node(xml, desc="Send")
        if not pos:
            raise CaptureError("no Send button after typing")
        self.d.tap_xy(*pos)
        time.sleep(0.6)
        # The chat composer keeps the keyboard open after Send, covering the reply. Back only
        # when it is actually shown, so it can never navigate away instead.
        if "mInputShown=true" in self.d.shell("dumpsys input_method | grep -m1 mInputShown"):
            self.d.run("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.4)

    def wait_new(self, text: str, timeout: float) -> None:
        """Wait until more nodes contain *text* than before the last typed request.

        Actions history keeps earlier confirmations ("Timer set for ..."), so a plain wait
        would pass on a previous take's result.
        """
        before = count_matches(self.baseline_xml, text)
        deadline = time.monotonic() + timeout
        while count_matches(self.dump(), text) <= before:
            if time.monotonic() > deadline:
                raise CaptureError(f"no new {text!r} on screen within {timeout}s (had {before})")
            time.sleep(self.poll)

    def wait_for(self, *, text: str | None, desc: str | None, gone: bool, timeout: float,
                 appear_timeout: float = 20) -> None:
        if gone:
            # The node may not exist yet when the step starts; wait for it first.
            deadline = time.monotonic() + appear_timeout
            while not ui_contains(self.dump(), text=text, desc=desc):
                if time.monotonic() > deadline:
                    return  # finished before we saw it (e.g. instant reply)
                time.sleep(self.poll)
        deadline = time.monotonic() + timeout
        while True:
            present = ui_contains(self.dump(), text=text, desc=desc)
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
            if self.anchor is None:
                self.anchor = self.now()  # -W returns once the first frame is drawn
            time.sleep(1.5)
            self.to_root_tabs()
        elif kind == "tap":
            self.tap(text=step.get("text"), desc=step.get("desc"))
        elif kind == "type":
            self.baseline_xml = self.last_xml
            self.type_and_send(step["text"])
        elif kind == "wait" and step.get("new"):
            self.wait_new(step["text"], float(step.get("timeout", 30)))
        elif kind in ("wait", "wait_gone"):
            self.wait_for(text=step.get("text"), desc=step.get("desc"), gone=kind == "wait_gone",
                          timeout=float(step.get("timeout", 30)),
                          appear_timeout=float(step.get("appear_timeout", 20)))
        elif kind == "sleep":
            until = time.monotonic() + float(step["s"])
            # A screen read takes ~2.5 s; short holds after a wait (which just read the
            # screen) skip it, longer holds keep scanning for values to blur.
            while self.redact and float(step["s"]) > 2.5 and time.monotonic() < until - 0.5:
                self.dump()  # keep scanning for values to blur while holding on a screen
            time.sleep(max(0.0, until - time.monotonic()))
        elif kind == "open_url":
            # The server address comes from the env file, never from shots.json.
            base = os.environ.get(step["env"], "").rstrip("/")
            if not base.startswith("https://"):
                raise CaptureError(f"open_url needs {step['env']} set to an https:// address")
            path = os.environ.get(step["path_env"], "") if step.get("path_env") else ""
            path = path or step.get("path", "/")
            self.d.run("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
                       "-d", base + path)
            time.sleep(1.5)
        elif kind == "key":
            self.d.run("shell", "input", "keyevent", step["code"])
        elif kind == "shell":
            self.d.shell(step["cmd"])
        elif kind == "host":
            self.baseline_xml = self.last_xml
            cmd = os.environ.get(step["env"], "")
            if not cmd:
                raise CaptureError(f"step needs {step['env']} (see the shot's note)")
            try:
                res = subprocess.run(shlex.split(cmd), cwd=REPO, timeout=float(step.get("timeout", 180)))
            except (subprocess.TimeoutExpired, OSError) as exc:
                raise CaptureError(f"host step {step['env']} failed: {exc}") from exc
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
            span = {"start": start, "end": end}
            if "compress_to" in step:
                span["max_s"] = float(step["compress_to"])
            self.log.compress.append(span)
        # A wait's last screen read already shows the final screen; re-reading only adds a
        # ~2 s static hold to the clip. Every other step changes the screen, so scan again.
        short_hold = kind == "sleep" and float(step["s"]) <= 2.5
        if self.redact and kind not in ("wait", "wait_gone") and not short_hold:
            self.dump()


# ── recording ──────────────────────────────────────────────────────────────


def scrcpy_cmd(serial: str, out_file: Path, scrcpy: str = "scrcpy") -> list[str]:
    return [
        scrcpy, "-s", serial,
        "--record", str(out_file),
        "--no-playback",          # scrcpy >= 2.2
        "--no-window",            # scrcpy >= 3.0; tested with 5.0.1
        "--no-audio",             # device audio is not used in the cut (see shot-script.md)
        "--max-fps", "30",
        "--video-bit-rate", "16M",
        "--stay-awake",
    ]


DEFAULT_ENV_FILE = Path("~/.config/jandal-demo/demo.env")


def load_env_file(path: Path, environ: dict | None = None) -> list[str]:
    """Load ``KEY=VALUE`` lines into the environment without overriding what is already set.

    Keeps the test Nextcloud server/username and the wake command out of the repo and out
    of shell history. Returns the keys it set.
    """
    environ = os.environ if environ is None else environ
    path = path.expanduser()
    if not path.exists():
        return []
    loaded = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key, value = key.strip().removeprefix("export ").strip(), value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        if key and key not in environ:
            environ[key] = value
            loaded.append(key)
    return loaded


def unlock_pin(environ: dict | None = None) -> str | None:
    """The device PIN from the file named by DEMO_UNLOCK_PIN_FILE (variable DEMO_UNLOCK_PIN_VAR).

    Read into a private dict, not os.environ, so it never reaches child processes or logs.
    """
    environ = os.environ if environ is None else environ
    path = environ.get("DEMO_UNLOCK_PIN_FILE")
    if not path:
        return None
    values: dict[str, str] = {}
    load_env_file(Path(path), values)
    pin = values.get(environ.get("DEMO_UNLOCK_PIN_VAR", "S23U_PIN"), "")
    return pin if pin.isdigit() else None


def redact_values(shot: dict) -> list[str]:
    """Values to blur for *shot*; every named env var must be set."""
    names = shot.get("redact_env", [])
    missing = [n for n in names if not os.environ.get(n)]
    if missing:
        raise CaptureError(f"{shot['id']} needs {', '.join(missing)} set so the server and username can be blurred")
    # Optional extras, e.g. the account's display name shown by the Nextcloud web UI.
    optional = [os.environ[n] for n in shot.get("redact_env_optional", []) if os.environ.get(n)]
    return [os.environ[n] for n in names] + optional


def probe(clip: Path, ffmpeg: str = "ffmpeg") -> tuple[tuple[int, int] | None, list[float]]:
    """Clip frame size and scene-change times (for the sync anchor)."""
    ffprobe = shutil.which("ffprobe") or "ffprobe"
    size = None
    res = subprocess.run([ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries",
                          "stream=width,height", "-of", "csv=p=0", str(clip)], capture_output=True, text=True)
    m = re.match(r"(\d+),(\d+)", res.stdout.strip())
    if m:
        size = (int(m.group(1)), int(m.group(2)))
    res = subprocess.run([ffmpeg, "-v", "info", "-i", str(clip), "-vf", "select='gt(scene,0.1)',showinfo",
                          "-f", "null", "-"], capture_output=True, text=True)
    return size, scene_changes(res.stderr)


def record_shot(device: Device, shot: dict, out_dir: Path, take: int, scrcpy: str, build: dict) -> dict:
    redact = redact_values(shot)
    airplane = device.set_offline(bool(shot.get("offline")))
    device.prepare_screen()
    clip = out_dir / f"{shot['id']}_take{take}.mp4"
    if clip.exists():
        clip.unlink()
    scrcpy_log = (out_dir / f"{shot['id']}_take{take}.scrcpy.log").open("w")
    # Own log file, not a pipe: an unread pipe can fill and stall scrcpy mid-shot.
    proc = subprocess.Popen(scrcpy_cmd(device.serial, clip, scrcpy), stdout=scrcpy_log, stderr=subprocess.STDOUT)
    time.sleep(2.0)  # let scrcpy start encoding before t0; the real offset is measured below
    if proc.poll() is not None:
        scrcpy_log.close()
        raise CaptureError(f"scrcpy exited early; see {scrcpy_log.name}")
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
        # SIGTERM, not SIGINT: a runner started from a non-interactive shell can inherit
        # SIGINT as ignored, and scrcpy then never finalises the MP4.
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
            status, error = "failed", error or "scrcpy did not stop; clip is not finalised"
        scrcpy_log.close()
    clip_size, changes = probe(clip) if clip.exists() else (None, [])
    lead = lead_in_from_anchor(changes, runner.anchor) if runner.anchor is not None else None
    if status == "ok" and lead is None:
        status, error = "failed", "could not sync the clip to the runner clock (no launch frame found)"
    screen = root_size(runner.last_xml)
    return {
        "id": shot["id"],
        "take": take,
        "clip": clip.name,
        "serial": device.serial,
        "airplane_mode": airplane,
        "status": status,
        "error": error,
        "lead_in_s": lead,
        "lead_in_source": "launch frame (am start -W) vs first scene change",
        "duration_s": runner.now(),
        "screen_size": list(screen) if screen else None,
        "clip_size": list(clip_size) if clip_size else None,
        "events": log.events,
        "compress": log.compress,
        "redact": log.redact,
        "build": build,
        "recorded_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
    }


def update_manifest(path: Path, header: dict, result: dict) -> dict:
    """Append one take. Build provenance lives on each take, so a retake on a newer
    build never relabels older takes."""
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
    ap.add_argument("--env-file", type=Path, default=DEFAULT_ENV_FILE,
                    help="KEY=VALUE file for DEMO_NC_SERVER, DEMO_NC_USER, DEMO_WAKE_CMD (default %(default)s)")
    args = ap.parse_args(argv)
    loaded = load_env_file(args.env_file)
    if loaded:
        print(f"loaded {', '.join(loaded)} from {args.env_file}")

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
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            ap.error(f"{tool} is needed to sync each clip to the runner clock")
    shots_sha = hashlib.sha256(SHOTS_FILE.read_bytes()).hexdigest()
    builds = {}
    for dev in (device, second):
        if dev is None:
            continue
        prov = dev.provenance()
        if prov["version_name"] is None:
            print(f"error: {dev.package} is not installed on {dev.serial}", file=sys.stderr)
            return 2
        builds[dev.serial] = {"commit": args.commit or None, "device": prov, "shots_sha256": shots_sha}
    header = {"issue": 1736}

    # Do Not Disturb (alarms only) for the run, so no heads-up notification lands in a clip;
    # the previous setting is restored afterwards.
    zen_names = {"0": "off", "1": "priority", "2": "none", "3": "alarms"}
    previous_zen = {dev.serial: dev.zen_mode() for dev in (device, second) if dev}
    for dev in (device, second):
        if dev:
            dev.set_dnd("alarms")
    try:
        return record_all(shots, device, second, out, args, builds, header)
    finally:
        for dev in (device, second):
            if dev:
                dev.set_dnd(zen_names.get(previous_zen.get(dev.serial, "0"), "off"))


def record_all(shots, device, second, out, args, builds, header) -> int:
    failed = 0
    for shot in shots:
        target = second if shot.get("device") == "second" else device
        mode = "offline" if shot.get("offline") else "online"
        print(f"recording {shot['id']} take {args.take} on {target.serial} ({mode}) …", flush=True)
        try:
            result = record_shot(target, shot, out, args.take, args.scrcpy, builds[target.serial])
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
