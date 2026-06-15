"""
ADB Harness — Model Readiness Preflight.

Deterministic S21 model-readiness check for ADB/device test runs after reinstall.
Runs after a fresh install or clean reinstall and ensures the required conversation
model is downloaded and the inference engine is ready before test execution begins.

Logcat markers monitored:
  - ``"Auto-queuing required model: {model}"``   → download auto-triggered
  - ``"Enqueuing download for {model}"``          → download enqueued in WorkManager
  - ``"Refreshed state for {model}: Downloaded(`` → model ready on disk
  - ``"Download succeeded: {path}"``               → file verified on disk
  - ``"Engine ready — backend:"``                  → LiteRT engine fully initialised
  - ``"{model} is gated but no HF token"``         → HuggingFace sign-in required

Failure buckets:
  - ``MODEL_NOT_READY`` — download never started and model not present
  - ``MODEL_DOWNLOAD_FAILED`` — download worker errored
  - ``MODEL_DOWNLOAD_TIMEOUT`` — download started but did not finish within timeout
  - ``ENGINE_NOT_READY`` — engine did not initialise after download completed
  - ``HF_SIGNIN_FAILED`` — HuggingFace sign-in UI shown but could not be completed
"""

from __future__ import annotations

import json
import os
import re
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import ClassVar

from adb_harness.config import ADB, ACTIVITY
from adb_harness.device import (
    clear_logcat,
    logcat_snapshot,
    logcat_start,
    logcat_stop,
    run_adb,
    send_text,
)


# ═══════════════════════════════════════════════════════════════════════
# Constants
# ═══════════════════════════════════════════════════════════════════════

S21_REQUIRED_MODEL = "Gemma 4 E-2B"
S21_MODEL_FILE = "gemma-4-E2B-it.litertlm"

# ── Exit codes ──
EXIT_SUCCESS = 0
EXIT_MODEL_NOT_READY = 44  # matches MODEL_NOT_READY bucket
EXIT_PREFLIGHT_CRASHED = 45

# ── Poll defaults ──
_POLL_INTERVAL: float = 2.0

# ═══════════════════════════════════════════════════════════════════════
# Evidence record
# ═══════════════════════════════════════════════════════════════════════


@dataclass
class ModelReadinessEvidence:
    """Structured evidence record for model readiness preflight.

    Serialised to JSON and included in ADB test evidence to distinguish
    model-bootstrap failures from product test failures.
    """

    device_serial: str
    required_model: str = S21_REQUIRED_MODEL
    model_file: str = S21_MODEL_FILE
    initial_state: str | None = None
    hf_signin_shown: bool = False
    hf_signin_clicked: bool = False
    download_triggered: bool = False
    readiness_wait_seconds: float = 0.0
    final_state: str | None = None
    failure_bucket: str | None = None
    logcat_markers: dict[str, bool] = field(default_factory=dict)

    def to_dict(self) -> dict[str, object]:
        return {
            "device_serial": self.device_serial,
            "required_model": self.required_model,
            "model_file": self.model_file,
            "initial_state": self.initial_state,
            "hf_signin_shown": self.hf_signin_shown,
            "hf_signin_clicked": self.hf_signin_clicked,
            "download_triggered": self.download_triggered,
            "readiness_wait_seconds": round(self.readiness_wait_seconds, 1),
            "final_state": self.final_state,
            "failure_bucket": self.failure_bucket,
            "logcat_markers": self.logcat_markers,
        }


# ═══════════════════════════════════════════════════════════════════════
# Marker definitions
# ═══════════════════════════════════════════════════════════════════════


class _Marker:
    """A named logcat marker with a compiled regex."""

    def __init__(self, name: str, pattern: str) -> None:
        self.name = name
        self.pattern = re.compile(pattern)


# Ordered list of markers the preflight tracks.
# The order defines priority: first match wins for initial-state detection.
_MARKERS: ClassVar[list[_Marker]] = [
    _Marker("model_already_downloaded", r"Refreshed state for (.+): Downloaded\("),
    _Marker("engine_already_ready", r"Engine ready — backend:"),
    _Marker("auto_queue_seen", r"Auto-queuing required model: (.+)"),
    _Marker("enqueue_seen", r"Enqueuing download for (.+)"),
    _Marker("hf_token_missing", r"(.+) is gated but no HF token"),
    _Marker("download_completed", r"Refreshed state for (.+): Downloaded\("),
    _Marker("download_succeeded", r"Download succeeded: (.+)"),
    _Marker("download_failed", r"Download failed for (.+): (.+)"),
    _Marker("hf_signin_approved", r"Set (.+) → APPROVED"),
    _Marker("engine_ready", r"Engine ready — backend:"),
]

# Map marker name → initial state string for Phase 1 detection.
# ``engine_already_ready`` is checked first — if the engine is already
# initialised, the preflight can return immediately. ``model_already_downloaded``
# means the model file exists but the engine may not be ready yet, so the
# preflight continues to an engine readiness check.
_INITIAL_STATE_MAP: dict[str, str] = {
    "engine_already_ready": "Ready",                    # engine running → true Ready
    "model_already_downloaded": "Downloaded",            # model on disk → needs engine check
    "auto_queue_seen": "Preparing",
    "enqueue_seen": "Preparing",
    "hf_token_missing": "ActionRequired(SignInRequired)",
}


# ═══════════════════════════════════════════════════════════════════════
# Logcat polling (non-destructive)
# ═══════════════════════════════════════════════════════════════════════


def _drain_logcat() -> str:
    """Read and drain the streaming logcat buffer (destructive)."""
    return logcat_snapshot()


def _read_fresh_logcat() -> str:
    """Read a fresh ``adb logcat -d`` dump without touching the stream buffer.

    Returns all logcat lines since boot, filtered to relevant tags.
    """
    raw = run_adb(
        "logcat",
        "-d",
        "-s",
        "ModelDownloadManager",
        "LiteRtInferenceEngine",
        "GatedModelStatusRepository",
        "HardwareProfileDetector",
        "KernelAI",
    )
    return raw or ""


def _find_markers(text: str, markers: list[_Marker]) -> dict[str, bool]:
    """Search *text* for *markers*. Returns {marker_name: found}."""
    result: dict[str, bool] = {}
    for m in markers:
        result[m.name] = m.pattern.search(text) is not None
    return result


def _poll_adb_logcat(
    timeout: float,
    poll_interval: float = _POLL_INTERVAL,
) -> dict[str, bool]:
    """Poll ``adb logcat -d`` repeatedly for markers.

    Uses fresh ``logcat -d`` each cycle (non-destructive to the stream buffer),
    so markers accumulate across cycles.
    """
    deadline = time.time() + timeout
    seen: dict[str, bool] = {m.name: False for m in _MARKERS}

    while time.time() < deadline:
        snapshot = _read_fresh_logcat()
        found = _find_markers(snapshot, _MARKERS)
        for k, v in found.items():
            if v:
                seen[k] = True
        # Return early when all relevant markers found
        # (no specific all-markers check — let caller decide)
        time.sleep(poll_interval)

    return seen


def _poll_logcat_until(
    target_marker_names: list[str],
    timeout: float,
    poll_interval: float = _POLL_INTERVAL,
) -> dict[str, bool]:
    """Poll adb logcat until one of *target_marker_names* is found or timeout."""
    deadline = time.time() + timeout
    seen: dict[str, bool] = {m.name: False for m in _MARKERS}
    target_set = set(target_marker_names)

    while time.time() < deadline:
        snapshot = _read_fresh_logcat()
        found = _find_markers(snapshot, _MARKERS)
        for k, v in found.items():
            if v:
                seen[k] = True
        # Check if any target marker found
        for t in target_set:
            if seen.get(t):
                return seen
        time.sleep(poll_interval)

    return seen


# ═══════════════════════════════════════════════════════════════════════
# UI helpers
# ═══════════════════════════════════════════════════════════════════════


def _check_model_on_disk(model_file: str = S21_MODEL_FILE) -> bool:
    """Check if the model file exists on the device's external storage.

    Works around logcat-clear edge cases where the model was already
    downloaded in a prior session but the ring buffer was emptied.
    """
    pkg = "com.kernel.ai.debug"
    path = f"/storage/emulated/0/Android/data/{pkg}/files/models/{model_file}"
    result = run_adb("shell", "ls", path)
    return result == path or path in result
# ── Permission handling ───────────────────────────────────────────


# Dangerous permissions that trigger runtime dialogs on Android 15
_DANGEROUS_PERMISSIONS: list[str] = [
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_CALENDAR",
    "android.permission.RECORD_AUDIO",
    "android.permission.CALL_PHONE",
    "android.permission.ACCESS_NOTIFICATION_POLICY",
]


def _grant_dangerous_permissions(pkg: str = "com.kernel.ai.debug") -> None:
    """Pre-grant dangerous permissions via ``pm grant``.

    Runs before the app requests the permission so dialogs never appear.
    On Android 15, ``pm grant`` works pre-emptively for most dangerous
    permissions. Failures are logged but non-fatal — fallback UI handling
    covers any remaining dialogs.
    """
    for perm in _DANGEROUS_PERMISSIONS:
        try:
            run_adb("shell", "pm", "grant", pkg, perm)
        except Exception as exc:
            # Some permissions cannot be pre-granted; that's OK.
            pass


def _handle_permission_dialogs(timeout: float = 30.0) -> bool:
    """Wait for and handle runtime permission dialogs one at a time.

    Polls ``dumpsys activity`` for ``GrantPermissionsActivity`` in the
    foreground. When a dialog is found, uses UIAutomator to locate and
    tap the "Allow" or "While using the app" button.

    Returns ``True`` once no more permission dialogs are visible.
    Returns ``False`` if dialogs persist after *timeout* seconds.
    """
    start = time.time()
    while time.time() - start < timeout:
        dumpsys = run_adb("shell", "dumpsys", "activity")
        if "GrantPermissionsActivity" not in dumpsys:
            return True

        _uiautomator_tap_text("Allow")
        time.sleep(2.0)

        # Some dialogs use "While using the app" instead of "Allow"
        dumpsys = run_adb("shell", "dumpsys", "activity")
        if "GrantPermissionsActivity" in dumpsys:
            _uiautomator_tap_text("While using the app")
            time.sleep(2.0)

    return False
_BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def _uiautomator_has_text(target_text: str) -> bool:
    """Check if *target_text* appears in the current UI hierarchy."""
    try:
        tmp = tempfile.mktemp(suffix=".xml")
        run_adb("shell", "uiautomator", "dump", tmp)
        time.sleep(1.0)
        raw = run_adb("shell", "cat", tmp) or ""
        if not raw.strip():
            return False
        # Search for text in any attribute
        return target_text in raw
    except Exception:
        return False


def _uiautomator_tap_text(target_text: str) -> bool:
    """Find and tap an element containing *target_text* via bound center.

    Returns True if found and tapped.
    """
    try:
        tmp = tempfile.mktemp(suffix=".xml")
        run_adb("shell", "uiautomator", "dump", tmp)
        time.sleep(1.0)
        raw = run_adb("shell", "cat", tmp) or ""
        if not raw.strip():
            return False
        root = ET.fromstring(raw)
        for node in root.iter():
            txt = node.attrib.get("text", "") or ""
            desc = node.attrib.get("content-desc", "") or ""
            if target_text in txt or target_text in desc:
                b = node.attrib.get("bounds", "")
                m = _BOUNDS_RE.match(b)
                if m:
                    l, t, r, b_ = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))
                    cx, cy = (l + r) // 2, (t + b_) // 2
                    run_adb("shell", "input", "tap", str(cx), str(cy))
                    return True
        return False
    except Exception:
        return False


# ═══════════════════════════════════════════════════════════════════════
# Preflight (state-machine continuous poll)
# ═══════════════════════════════════════════════════════════════════════


def preflight_model_readiness(
    serial: str | None = None,
    timeout_download: float = 600.0,
    timeout_engine: float = 120.0,
    hf_signin_timeout: float = 60.0,
    verbose: bool = True,
    unlock_pin: str | None = None,
) -> ModelReadinessEvidence:
    """Run model-readiness preflight on the target device.

    Expects the app to already be installed (e.g. after a fresh ``adb install``).
    Launches the app and waits for the required conversation model to be
    downloaded and the inference engine to initialise.

    Uses a single continuous polling loop that tracks all markers
    simultaneously, so there is no logcat drain gap between phases.

    Returns a ``ModelReadinessEvidence`` dataclass summarising the run.

    # ── Phase 1: Detect initial model state ───────────────────────────
    The caller should check ``evidence.failure_bucket`` to determine outcome.

    Parameters
    ----------
    serial : str or None
        Device serial. Falls back to ``ANDROID_SERIAL`` / ``ADB_SERIAL`` env vars.
    timeout_download : float
        Max seconds to wait for the model to finish downloading (default 600).
    timeout_engine : float
        Max seconds to wait for the inference engine to init after download (default 120).
    hf_signin_timeout : float
        Max seconds to wait for HuggingFace sign-in flow to complete (default 60).
    verbose : bool
        Print progress output.

    Returns
    -------
    ModelReadinessEvidence
    """
    evidence = ModelReadinessEvidence(
        device_serial=(serial
                       or os.environ.get("ANDROID_SERIAL")
                       or os.environ.get("ADB_SERIAL")
                       or "unknown"),
        required_model=S21_REQUIRED_MODEL,
        model_file=S21_MODEL_FILE,
    )
    _print = print if verbose else lambda *a, **kw: None
    start_ts = time.time()

    # ── Logcat preamble ───────────────────────────────────────────────
    logcat_start()
    clear_logcat()
    # Clear the device ring buffer so we only see fresh entries
    # (safe on USB ADB, not on ADB-over-TLS)
    run_adb("logcat", "-c")
    time.sleep(1)

    # ── Permissions: pre-grant BEFORE app launch ──────────────────────
    # Granting POST_NOTIFICATIONS before the app launches ensures the
    # ModelDownloadWorker can call setForeground() successfully, which
    # prevents WorkManager from falling back to non-expedited (which
    # ── Unlock device (dismiss keyguard if possible) ─────────────────
    if unlock_pin:
        _print("  [readiness] Unlocking device with PIN …")
        run_adb("shell", "input", "keyevent", "KEYCODE_POWER")
        time.sleep(1)
        run_adb("shell", "input", "swipe", "500", "1500", "500", "500")
        time.sleep(1)
        run_adb("shell", "input", "text", unlock_pin)
        time.sleep(0.3)
        run_adb("shell", "input", "keyevent", "KEYCODE_ENTER")
        time.sleep(1)
        # Verify unlock succeeded
        keyguard = run_adb("shell", "dumpsys", "window")
        if "isKeyguardShowing=true" in keyguard:
            _print("  [readiness] ⚠️  PIN unlock may have failed — keyguard still showing")
        else:
            _print("  [readiness] ✅ Device unlocked")
    else:
        # Swipe attempt for swipe-to-unlock devices
        run_adb("shell", "input", "keyevent", "KEYCODE_MENU")
        run_adb("shell", "input", "swipe", "500", "1500", "500", "500")
        time.sleep(2)
    _print("  [readiness] Granting dangerous permissions before launch …")
    _grant_dangerous_permissions()
    time.sleep(1)

    # ── Launch the app ────────────────────────────────────────────────
    _print("  [readiness] Launching app …")
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run_adb(
        "shell", "am", "start", "-n", ACTIVITY,
        "--activity-clear-top", "--activity-single-top",
    )
    time.sleep(5)
    clear_logcat()
    time.sleep(1)

    # ── Handle permission dialogs (system UI layer) ─────────────────
    # Some permissions like notification controls or overlay permissions
    # may still show system dialogs regardless of pm grant.
    _print("  [readiness] Handling permission dialogs …")
    perms_ok = _handle_permission_dialogs(timeout=30.0)
    if perms_ok:
        _print("  [readiness] ✅ Permissions granted")
    else:
        _print("  [readiness] ⚠ Permission dialog may still be visible — proceeding anyway")
    clear_logcat()
    time.sleep(1)

    # ── Trigger: send a message to activate model-readiness flow ────
    _print("  [readiness] Sending trigger message to activate model readiness check …")
    send_text("hello", wait_for_inference=False)
    time.sleep(3)
    clear_logcat()
    # ── Phase 1: Detect initial model state ───────────────────────────
    _print("  [readiness] Detecting initial model state …")

    evidence.initial_state = "Unknown"
    initial_seen: dict[str, bool] = {}  # used to gate Phase 2 below

    if _check_model_on_disk():
        evidence.initial_state = "Downloaded"
        evidence.logcat_markers["model_on_disk"] = True

    if evidence.initial_state != "Downloaded":
        # Quick 30s poll to establish initial state from logcat markers
        initial_seen = _poll_logcat_until(
            list(_INITIAL_STATE_MAP.keys()),
            timeout=30.0,
        )
        for marker_name, state_name in _INITIAL_STATE_MAP.items():
            if initial_seen.get(marker_name):
                evidence.initial_state = state_name
                evidence.logcat_markers[marker_name] = True
                break

    # Only return early when the ENGINE is confirmed ready (not just model-on-disk)
    if evidence.initial_state == "Ready":
        _print(f"  [readiness] Initial state: {evidence.initial_state} — engine already ready")
        evidence.final_state = "Ready"
        evidence.readiness_wait_seconds = time.time() - start_ts
        return evidence

    _print(f"  [readiness] Initial state: {evidence.initial_state}")

    # If initial state is "Preparing" the download was auto-queued
    if evidence.initial_state == "Preparing":
        evidence.download_triggered = True

    # ── Phase 2: Handle HF sign-in dialog ────────────────────────────
    # Gate: only run when the initial state indicates sign-in is needed.
    # Avoids false-positive taps when UIAutomator finds "Sign in" text from
    # other parts of the UI during normal model-download flows.
    if (evidence.initial_state == "ActionRequired(SignInRequired)"
            or initial_seen.get("hf_token_missing")):
        _print("  [readiness] HF sign-in required — tapping sign-in button …")
        signin_tapped = False
        if _uiautomator_has_text("Sign in to Hugging Face"):
            signin_tapped = _uiautomator_tap_text("Sign in")
            _print("  [readiness] Tapped HF sign-in button (dialog)")
        elif _uiautomator_has_text("Sign in"):
            signin_tapped = _uiautomator_tap_text("Sign in")
            _print("  [readiness] Tapped HF sign-in button")
        if signin_tapped:
            evidence.hf_signin_clicked = True
            _print("  [readiness] Waiting for HF sign-in approval …")
            signin_seen = _poll_logcat_until(
                ["hf_signin_approved"],
                timeout=hf_signin_timeout,
            )
            if signin_seen.get("hf_signin_approved"):
                evidence.logcat_markers["hf_signin_approved"] = True
                _print("  [readiness] ✅ HF sign-in approved — awaiting gated model auto-queues")
                gated_seen = _poll_logcat_until(
                    ["auto_queue_seen", "enqueue_seen"],
                    timeout=30.0,
                )
                if gated_seen.get("auto_queue_seen") or gated_seen.get("enqueue_seen"):
                    evidence.download_triggered = True
                    _print("  [readiness] Gated model downloads enqueued after sign-in")
            else:
                _print("  [readiness] ⚠️  HF sign-in approval NOT detected within timeout")
        else:
            _print("  [readiness] ⚠️  Could not find sign-in button to tap")
    else:
        _print("  [readiness] No HF sign-in dialog detected")

    # ── Phase 3+4 combined: Continuous poll for download + engine ──
    # Deadline starts NOW (after Phase 1+2), not from start_ts — avoids
    # the combined window being consumed by Phase 1's 30s initial detection.
    phase34_start = time.time()
    # If the model is already on disk, skip the download wait
    download_matched = (evidence.initial_state == "Downloaded")
    if download_matched:
        _print("  [readiness] Model already on disk — checking engine readiness")
        engine_deadline = phase34_start + timeout_engine
    else:
        engine_deadline = phase34_start + timeout_download + timeout_engine
    engine_matched = False

    _print(f"  [readiness] Waiting for download + engine (download timeout: {timeout_download:.0f}s, engine: {timeout_engine:.0f}s)…")

    while time.time() < engine_deadline:
        snapshot = _read_fresh_logcat()
        markers = _find_markers(snapshot, _MARKERS)

        # Track downloads
        if not download_matched and (markers.get("download_completed") or markers.get("download_succeeded")):
            evidence.download_triggered = True
            evidence.logcat_markers["download_completed"] = True
            _print(f"  [readiness] ✅ Model download completed  (t={time.time() - start_ts:.0f}s)")
            download_matched = True
            # When download just completed, engine deadline extends by timeout_engine
            engine_deadline = time.time() + timeout_engine
            # Ensure the app is in the foreground for engine init
            _print("  [readiness] Bringing app to foreground for engine initialisation …")
            run_adb("shell", "am", "start", "-n", ACTIVITY,
                    "--activity-clear-top", "--activity-single-top")
            time.sleep(3)

        if markers.get("download_failed"):
            m = next(
                (p.search(snapshot) for p in [re.compile(r"Download failed for (.+): (.+)")] if p.search(snapshot)),
                None,
            )
            error_msg = m.group(2) if m and m.lastindex and m.lastindex >= 2 else "unknown"
            evidence.failure_bucket = "MODEL_DOWNLOAD_FAILED"
            evidence.logcat_markers["download_failed"] = True
            _print(f"  [readiness] ❌ Download failed: {error_msg}")
            break

        if markers.get("engine_ready"):
            if not engine_matched:
                evidence.logcat_markers["engine_ready"] = True
                evidence.final_state = "Ready"
                _print(f"  [readiness] ✅ Inference engine ready  (t={time.time() - start_ts:.0f}s)")
                engine_matched = True

        # Both done → success
        if engine_matched:
            break

        # ── Keep device awake during long downloads ─────────────────
        # The poll interval is ~3s; every 15 cycles (~45s) send a small
        # interaction to prevent the device from dozing or locking.
        if not engine_matched:
            elapsed_since_poll_start = time.time() - phase34_start
            cycles = int(elapsed_since_poll_start // _POLL_INTERVAL) if _POLL_INTERVAL > 0 else 0
            if cycles > 0 and cycles % 15 == 0:
                run_adb("shell", "input", "touchscreen", "swipe",
                        "540", "1000", "540", "1004", "50")

        # Timeout checks
        phase34_elapsed = time.time() - phase34_start
        if not download_matched and phase34_elapsed > timeout_download:
            # Download timeout
            if not evidence.download_triggered:
                evidence.failure_bucket = "MODEL_NOT_READY"
                _print(f"  [readiness] ❌ Download never started within {timeout_download:.0f}s")
            else:
                evidence.failure_bucket = "MODEL_DOWNLOAD_TIMEOUT"
                _print(f"  [readiness] ❌ Download did not complete within {timeout_download:.0f}s")
            break

        # Engine-timeout check (separate deadline for each case)
        if download_matched and not engine_matched:
            if time.time() >= engine_deadline:
                # Check if lock screen is blocking engine init
                keyguard = run_adb("shell", "dumpsys", "window")
                if "isKeyguardShowing=true" in keyguard:
                    evidence.failure_bucket = "ENGINE_BLOCKED_BY_KEYGUARD"
                    _print("  [readiness] ❌ Lock screen / keyguard blocking engine init — unlock device first")
                else:
                    evidence.failure_bucket = "ENGINE_NOT_READY"
                    _print(f"  [readiness] ❌ Engine did not initialise within {timeout_engine:.0f}s")
                break

        time.sleep(_POLL_INTERVAL)

    # ── Wrap up ───────────────────────────────────────────────────────
    if evidence.final_state is None and evidence.failure_bucket is None:
        evidence.failure_bucket = "ENGINE_NOT_READY"

    evidence.readiness_wait_seconds = time.time() - start_ts

    if evidence.failure_bucket:
        _print(f"  [readiness] ❌ FAILED: {evidence.failure_bucket}")
    else:
        _print("  [readiness] ✅ Model readiness preflight passed")

    _print(f"  [readiness] Elapsed: {evidence.readiness_wait_seconds:.0f}s")

    # Drain preflight residue so caller doesn't see it
    clear_logcat()

    return evidence


def cli_main() -> int:
    """Direct CLI entry point for ``python -m adb_harness.model_readiness``."""
    import argparse
    parser = argparse.ArgumentParser(description="Model readiness preflight for S21 ADB tests")
    parser.add_argument("--serial", help="Device serial (falls back to ANDROID_SERIAL / ADB_SERIAL env)")
    parser.add_argument("--timeout-download", type=float, default=360.0,
                        help="Max seconds to wait for model download (default 360)")
    parser.add_argument("--timeout-engine", type=float, default=120.0,
                        help="Max seconds to wait for engine initialisation (default 120)")
    parser.add_argument("--unlock-pin", type=str, default=None,
                        help="Device unlock PIN to dismiss keyguard before initialisation")
    parser.add_argument("--json", action="store_true",
                        help="Output evidence as JSON to stdout")
    args = parser.parse_args()

    if args.serial:
        os.environ["ANDROID_SERIAL"] = args.serial

    try:
        evidence = preflight_model_readiness(
            serial=args.serial,
            timeout_download=args.timeout_download,
            timeout_engine=args.timeout_engine,
            verbose=not args.json,
            unlock_pin=args.unlock_pin,
        )
    except Exception as e:
        print(f"ERROR: Model readiness preflight crashed: {e}", file=sys.stderr)
        return EXIT_PREFLIGHT_CRASHED

    if args.json:
        json.dump(evidence.to_dict(), sys.stdout, indent=2)
        print()

    if evidence.failure_bucket:
        print(f"\nPreflight FAILED: {evidence.failure_bucket}", file=sys.stderr)
        return EXIT_MODEL_NOT_READY

    return EXIT_SUCCESS


if __name__ == "__main__":
    sys.exit(cli_main())
