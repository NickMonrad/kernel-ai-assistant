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
    DIRECT_REPLY_PATTERN,
    LOGCAT_TAG,
    NATIVE_INTENT_NAME_PATTERN,
    NATIVE_INTENT_PATTERN,
    PROFILE_FALLBACK_PATTERN,
    PROFILE_LLM_PATTERN,
    WAIT_SECONDS,
    PACKAGE,
)


# ── Module-level logcat streaming state ──
_STREAM_PROC: subprocess.Popen | None = None
_STREAM_READER: threading.Thread | None = None
_STREAM_BUFFER: list[str] = []
_STREAM_LOCK = threading.Lock()

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
# Logcat streaming (#1102 / #1162)
# ═══════════════════════════════════════════════════════════════════════


def _logcat_reader() -> None:
    """Read lines from the persistent logcat subprocess and buffer them."""
    global _STREAM_PROC
    assert _STREAM_PROC is not None
    assert _STREAM_PROC.stdout is not None
    try:
        for line in _STREAM_PROC.stdout:
            with _STREAM_LOCK:
                _STREAM_BUFFER.append(line.rstrip("\n"))
    except ValueError:
        pass


def logcat_start() -> None:
    """Start the persistent host-buffered logcat stream."""
    global _STREAM_PROC, _STREAM_READER
    if _STREAM_PROC is not None and _STREAM_PROC.poll() is None:
        return
    if _STREAM_PROC is not None:
        logcat_stop()
    run_adb("logcat", "-c")
    try:
        _STREAM_PROC = subprocess.Popen(
            [ADB, "logcat", "-s", f"{LOGCAT_TAG}:D", "LiteRtInferenceEngine:I", "MiniLMIntentClassifier:I", "-v", "brief"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            bufsize=1,
        )
    except FileNotFoundError:
        print(f"ERROR: ADB binary not found at {ADB}", file=sys.stderr)
        _STREAM_PROC = None
        return
    _STREAM_READER = threading.Thread(target=_logcat_reader, daemon=True)
    _STREAM_READER.start()
    time.sleep(0.5)
    logcat_snapshot()


def logcat_stop() -> None:
    """Stop the persistent logcat stream and clear buffered output."""
    global _STREAM_PROC, _STREAM_READER
    if _STREAM_PROC is not None:
        try:
            _STREAM_PROC.terminate()
            _STREAM_PROC.wait(timeout=5)
        except Exception:
            _STREAM_PROC.kill()
            _STREAM_PROC.wait(timeout=5)
        _STREAM_PROC = None
    _STREAM_READER = None
    with _STREAM_LOCK:
        _STREAM_BUFFER.clear()


def logcat_snapshot() -> str:
    """Atomically drain the accumulated host-side logcat buffer."""
    with _STREAM_LOCK:
        snapshot = "\n".join(_STREAM_BUFFER)
        _STREAM_BUFFER.clear()
    return snapshot


def logcat_wait(expected: str, timeout: float = WAIT_SECONDS) -> str:
    """Poll the buffered logcat stream until *expected* appears or *timeout* elapses."""
    deadline = time.time() + timeout
    seen: set[str] = set()
    accumulated: list[str] = []
    while time.time() < deadline:
        snapshot = logcat_snapshot()
        if not snapshot:
            time.sleep(0.5)
            continue
        for line in snapshot.split("\n"):
            line = line.strip()
            if line and line not in seen:
                seen.add(line)
                accumulated.append(line)
        combined = "\n".join(accumulated)
        if expected in combined:
            return combined
        time.sleep(0.5)
    return "\n".join(accumulated)


def clear_logcat() -> None:
    """Clear the host-side buffer without touching the device ring buffer."""
    logcat_snapshot()


def read_logcat() -> str:
    """Return accumulated KernelAI logcat output since the last drain."""
    return logcat_snapshot()


def read_logcat_all() -> str:
    """Return accumulated KernelAI + inference logcat output since the last drain."""
    return logcat_snapshot()


atexit.register(logcat_stop)


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
        "shell",
        "am",
        "start",
        "--activity-clear-top",
        "--activity-single-top",
        "-n",
        ACTIVITY,
        "--es",
        "chat_input",
        shlex.quote(text),
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
        "shell",
        "am",
        "start",
        "--activity-clear-top",
        "--activity-single-top",
        "-n",
        ACTIVITY,
        "--es",
        "quick_action_input",
        shlex.quote(text),
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
        "shell",
        "am",
        "start",
        "--activity-clear-top",
        "--activity-single-top",
        "-n",
        ACTIVITY,
        "--es",
        "slot_reply_input",
        shlex.quote(text),
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
    """Extract the latest native intent name + params from logcat output."""
    matches = list(NATIVE_INTENT_PATTERN.finditer(logcat_output))
    if matches:
        match = matches[-1]
        intent_name = match.group(1)
        raw_params = match.group(2)
        params = {kv.group(1): kv.group(2).strip() for kv in re.finditer(r"(\w+)=([^,}]+)", raw_params)}
        return intent_name, params

    fallback = list(NATIVE_INTENT_NAME_PATTERN.finditer(logcat_output))
    if not fallback:
        return None, {}
    return fallback[-1].group(1), {}


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
    """Deliver ``profile_text`` via onNewIntent to trigger profile extraction."""
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    single_line = profile_text.replace("\n", "\\n")
    run_adb(
        "shell",
        "am",
        "start",
        "-n",
        ACTIVITY,
        "--es",
        "profile_text",
        shlex.quote(single_line),
    )


def extract_profile_result(logcat_output: str) -> dict[str, str | None]:
    """Parse profile extraction method and structured fields from logcat."""
    used_llm = bool(PROFILE_LLM_PATTERN.search(logcat_output))
    used_fallback = bool(PROFILE_FALLBACK_PATTERN.search(logcat_output))
    name_match = re.search(r"KernelAI: name:\s*(.+)", logcat_output)
    role_match = re.search(r"KernelAI: role:\s*(.+)", logcat_output)
    location_match = re.search(r"KernelAI: location:\s*(.+)", logcat_output)
    return {
        "method": "llm" if used_llm else ("regex" if used_fallback else None),
        "name": name_match.group(1).strip() if name_match else None,
        "role": role_match.group(1).strip() if role_match else None,
        "location": location_match.group(1).strip() if location_match else None,
    }
