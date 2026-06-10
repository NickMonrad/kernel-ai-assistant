"""
ADB Skill Harness — device interaction layer.

ADB shell commands, logcat streaming, text/action/slot input, fixture setup,
keepalive, and result extraction helpers.
"""

from __future__ import annotations

import atexit
import os
import re
import shlex
import subprocess
import sys
import threading
import time
from adb_harness.config import (
    ACTIVITY,
    ADB,
    NATIVE_INTENT_PATTERN,
    PARAM_EXTRACT_PATTERN,
    DIRECT_REPLY_PATTERN,
    WAIT_SECONDS,
    PACKAGE,
)


# ── Module-level logcat streaming state ──
_STREAM_PROC: subprocess.Popen | None = None
_STREAM_PID: int | None = None

# ── Keepalive state ──
_KEEPALIVE_THREAD: threading.Thread | None = None
_KEEPALIVE_STOP: threading.Event | None = None


# ═══════════════════════════════════════════════════════════════════════
# ADB primitives
# ═══════════════════════════════════════════════════════════════════════


def run_adb(*args: str) -> str:
    """Run ``adb <args>`` and return stdout.  Stderr is discarded."""
    cmd = [ADB, *args]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    return result.stdout.strip()


# ═══════════════════════════════════════════════════════════════════════
# Logcat streaming (#1102)
# ═══════════════════════════════════════════════════════════════════════


def _logcat_reader() -> None:
    """Background thread: continuously drains adb logcat stdout into a buffer."""
    global _STREAM_PROC, _STREAM_PID
    try:
        _STREAM_PROC = subprocess.Popen(
            [ADB, "logcat", "-s", "KernelAI:D", "MiniLMIntentClassifier:I", "-v", "brief"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            bufsize=1,
        )
    except FileNotFoundError:
        print(f"ERROR: ADB binary not found at {ADB}", file=sys.stderr)
        return
    _STREAM_PID = _STREAM_PROC.pid
    # Read and discard — the subprocess's stdout pipe keeps the ADB
    # transport alive so that logcat_snapshot()/read_logcat() work.
    # If the pipe breaks (device disconnect), the loop exits naturally.
    try:
        while _STREAM_PROC.poll() is None:
            _STREAM_PROC.stdout.readline()  # type: ignore[union-attr]
    except ValueError:
        pass  # pipe closed during shutdown


def logcat_start() -> None:
    """Start host-side logcat streaming subprocess (#1102).

    The streaming subprocess keeps an open ADB transport so that
    logcat_snapshot() via ``adb logcat -d`` returns fresh output
    without needing to reopen a connection each time.
    """
    t = threading.Thread(target=_logcat_reader, daemon=True)
    t.start()
    time.sleep(2)  # Give the subprocess time to open the transport


def logcat_stop() -> None:
    """Kill the background logcat subprocess."""
    global _STREAM_PROC, _STREAM_PID
    if _STREAM_PROC is not None:
        try:
            _STREAM_PROC.terminate()
            _STREAM_PROC.wait(timeout=5)
        except Exception:
            _STREAM_PROC.kill()
        _STREAM_PROC = None
        _STREAM_PID = None


def logcat_snapshot() -> str:
    """Return current device logcat buffer (``adb logcat -d``)."""
    return run_adb("logcat", "-d", "-s", "KernelAI:D", "MiniLMIntentClassifier:I", "-v", "brief")


def logcat_wait(expected: str, timeout: float = WAIT_SECONDS) -> str:
    """Poll ``logcat_snapshot()`` until *expected* substring appears.

    Returns the full logcat output once found, or after *timeout* seconds
    even if not found.
    """
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        last = logcat_snapshot()
        if expected in last:
            return last
        time.sleep(0.5)
    return last


def clear_logcat() -> None:
    """Clear the logcat buffer by draining the streaming buffer only.

    Skips device ring-buffer clear (``adb logcat -c``) which blocks on
    ADB-over-TCP while streaming is active (#1162).
    """
    logcat_snapshot()  # Drain


def read_logcat() -> str:
    """Return current logcat snapshot (shortcut)."""
    return logcat_snapshot()


def read_logcat_all() -> str:
    """Return full buffered logcat content (same as snapshot)."""
    return logcat_snapshot()


# ═══════════════════════════════════════════════════════════════════════
# Screen keepalive
# ═══════════════════════════════════════════════════════════════════════


def _keepalive_worker(stop_event: threading.Event) -> None:
    """Background thread: press KEYCODE_WAKEUP every ~25 s to keep screen on."""
    while not stop_event.is_set():
        time.sleep(25)
        if not stop_event.is_set():
            run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")


def start_keepalive() -> threading.Thread:
    """Start the keepalive background thread."""
    global _KEEPALIVE_THREAD, _KEEPALIVE_STOP
    _KEEPALIVE_STOP = threading.Event()
    _KEEPALIVE_THREAD = threading.Thread(target=_keepalive_worker, args=(_KEEPALIVE_STOP,), daemon=True)
    _KEEPALIVE_THREAD.start()
    return _KEEPALIVE_THREAD


def stop_keepalive() -> None:
    """Stop the keepalive background thread."""
    global _KEEPALIVE_THREAD, _KEEPALIVE_STOP
    if _KEEPALIVE_STOP is not None:
        _KEEPALIVE_STOP.set()
    if _KEEPALIVE_THREAD is not None:
        _KEEPALIVE_THREAD.join(timeout=3)
    _KEEPALIVE_THREAD = None
    _KEEPALIVE_STOP = None


def _keep_foreground_until_inference_starts() -> None:
    """Send KEYCODE_WAKEUP to ensure the screen stays on during warmup."""
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")


# ═══════════════════════════════════════════════════════════════════════
# Input helpers
# ═══════════════════════════════════════════════════════════════════════


def send_text(text: str, wait_for_inference: bool = True) -> None:
    """Deliver *text* via ``chat_input`` extra → ChatViewModel.

    This is the main input path for most tests (non-slot-fill).
    """
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    run_adb(
        "shell", "am", "start", "-n", ACTIVITY,
        "--es", "chat_input", shlex.quote(text),
    )
    if wait_for_inference:
        _keep_foreground_until_inference_starts()


def send_quick_action(text: str) -> None:
    """Deliver *text* via ``quick_action_input`` extra → ActionsViewModel.

    Used to drive slot-fill tests: bare queries (e.g. "set an alarm") route
    through ActionsViewModel → QIR → NeedsSlot → navigate to Chat with slot prompt.
    """
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    run_adb(
        "shell", "am", "start", "-n", ACTIVITY,
        "--es", "quick_action_input", shlex.quote(text),
    )


def send_slot_reply(text: str) -> None:
    """Deliver a slot reply via ``slot_reply_input`` extra → ActionsViewModel.onSlotReply().

    Used for the second turn of a slot-fill test: after send_quick_action triggers
    NeedsSlot and the ModalBottomSheet is shown, this delivers the user's answer
    directly to the ActionsViewModel without navigating away from the Actions tab.
    """
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    run_adb(
        "shell", "am", "start", "-n", ACTIVITY,
        "--es", "slot_reply_input", shlex.quote(text),
    )


# ═══════════════════════════════════════════════════════════════════════
# Fixture helpers
# ═══════════════════════════════════════════════════════════════════════


def setup_contact_alias_fixture() -> bool:
    """Insert an alias entry for contact-name resolution tests.

    Runs an ``adb shell content insert`` command targeting the ContactsContract
    database to insert a stable-name contact entry that can be used to verify
    contact-name-to-phone-number resolution.

    Returns True if the alias was created.
    """
    # Use ``zippy`` as the alias name for Voicemail/121 to keep tests stable
    # across different SIM provisioning statuses.
    result = run_adb(
        "shell", "content", "insert",
        "--uri", "content://com.android.contacts/raw_contacts",
        "--bind", "display_name:STRIP:zippy",
    )
    return "Added" in result or "Uri" in result or result != ""


def teardown_contact_alias_fixture() -> None:
    """Remove the zippy alias fixture inserted by setup."""
    run_adb(
        "shell", "content", "delete",
        "--uri", "content://com.android.contacts/raw_contacts",
        "--bind", "display_name:STRIP:zippy",
    )


def dismiss_notifications() -> None:
    """Dismiss all system notifications via ADB."""
    run_adb("shell", "cmd", "notification", "dismiss_all")


def cleanup_side_effects(wait_for_inference: bool = False) -> None:
    """Run a no-op weather query to flush any pending state.

    This sends a benign query via chat_input to flush pending side effects
    (timers, alarms, active calls).
    """
    send_text("what time is it", wait_for_inference=wait_for_inference)


# ═══════════════════════════════════════════════════════════════════════
# Result extraction helpers
# ═══════════════════════════════════════════════════════════════════════


def extract_intent(logcat_output: str) -> tuple[str | None, dict[str, str]]:
    """Extract the latest NativeIntentHandler intent + params from logcat.

    Returns ``(intent_name, param_dict)``.
    """
    intents = NATIVE_INTENT_PATTERN.findall(logcat_output)
    if not intents:
        return None, {}
    actual_intent = intents[-1]
    param_matches = PARAM_EXTRACT_PATTERN.findall(logcat_output)
    params = {k: v.strip("[]") for k, v in param_matches}
    return actual_intent, params


def extract_reply(logcat_output: str) -> str | None:
    """Extract the latest DirectReply text from logcat, if any."""
    matches = DIRECT_REPLY_PATTERN.findall(logcat_output)
    return matches[-1] if matches else None


def check_params(
    expected: dict[str, str] | None,
    actual: dict[str, str],
) -> tuple[bool, list[str]]:
    """Compare expected vs actual parameter dicts.

    Returns ``(passed, failure_messages)``.
    """
    if not expected:
        return True, []
    failures: list[str] = []
    for key, val in expected.items():
        actual_val = actual.get(key)
        if actual_val is None:
            failures.append(f"Missing param {key}")
        elif actual_val != val:
            failures.append(f"Param '{key}': expected {val!r}, got {actual_val!r}")
    return len(failures) == 0, failures


# ═══════════════════════════════════════════════════════════════════════
# Profile helper
# ═══════════════════════════════════════════════════════════════════════


def send_profile(profile_text: str) -> None:
    """Deliver a profile query (llm_tools skill with profile mode)."""
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    run_adb(
        "shell", "am", "start", "-n", ACTIVITY,
        "--es", "chat_input", shlex.quote(profile_text),
    )


def extract_profile_result(logcat_output: str) -> dict[str, str | None]:
    """Extract structured profile fields from logcat."""
    from adb_harness.config import PROFILE_RESULT_PATTERN
    match = PROFILE_RESULT_PATTERN.search(logcat_output)
    if not match:
        return {}
    try:
        import json
        return json.loads(match.group(1))
    except (json.JSONDecodeError, KeyError):
        return {}
