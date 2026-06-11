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
    """Start the persistent host-buffered logcat stream.

    NOTE: ``adb logcat -c`` (clear device ring buffer) is deliberately
    NOT called here.  On ADB-over-TLS (adb-tls-connect) the ``-c`` flag
    exits 0 but leaves the ring buffer in a state where subsequent reads
    produce no output, causing all tests to return NO_MATCH.

    Per-test isolation is handled entirely via the host-side buffer:
    ``clear_logcat()`` drains ``_STREAM_BUFFER`` before each case without
    touching the device ring.
    """
    global _STREAM_PROC, _STREAM_READER
    if _STREAM_PROC is not None and _STREAM_PROC.poll() is None:
        return
    if _STREAM_PROC is not None:
        logcat_stop()
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
# Oracle preflight check
# ═══════════════════════════════════════════════════════════════════════

# Known-good oracle probes: non-destructive prompts the harness can
# reliably detect to confirm the observability pipeline is healthy.
_ORACLE_PROBES: list[tuple[str, str, str, str]] = [
    # (label, prompt, expected_intent, expected_log_marker)
    ("get_time", "what time is it", "get_time", "NativeIntentHandler.handle: intent=get_time"),
]


def check_oracle(
    timeout: float = 30.0,
    probe_idx: int = 0,
) -> bool:
    """Run a single oracle probe to confirm logcat observability is healthy.

    Sends a known-good prompt, waits for the expected NativeIntentHandler
    marker in the host-side logcat buffer, and returns True iff the marker
    is observed within *timeout* seconds.

    On failure, prints diagnostic guidance and returns False.  Callers
    should abort the test suite when this returns False.
    """
    label, prompt, expected_intent, expected_marker = _ORACLE_PROBES[probe_idx]

    clear_logcat()
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run_adb("shell", "input", "swipe", "540", "2000", "540", "500", "200")
    time.sleep(1)
    run_adb(
        "shell", "am", "start", "-n", f"{ACTIVITY}",
        "--activity-clear-top", "--activity-single-top",
    )
    time.sleep(2)

    clear_logcat()
    print(f"  [oracle] probe=\"{prompt}\"  expect={expected_marker!r}  timeout={timeout:.0f}s")

    run_adb(
        "shell", "am", "start", "-n", f"{ACTIVITY}",
        "--activity-clear-top", "--activity-single-top",
        "--es", "chat_input", shlex.quote(prompt),
    )

    accumulated = logcat_wait(expected_marker, timeout)
    found = expected_marker in accumulated

    if found:
        print(f"  [oracle] \u2705  marker found \u2014 observability pipeline healthy")
        return True

    lines = accumulated.strip().split("\n") if accumulated.strip() else []
    print(f"  [oracle] \u274c  marker NOT found in {len(lines)} logcat line(s) within {timeout:.0f}s")
    print(f"  [oracle]     Last 10 logcat lines:")
    for l in lines[-10:]:
        print(f"              {l}")
    print(f"  [oracle]     Possible causes (check oracle lines above to distinguish):")
    print(f"              [LOG STREAM]: No logcat lines at all — streaming subprocess broken")
    print(f"              [LOG STREAM]:   • `adb logcat -c` was called (removed in host-buffer fix)")
    print(f"              [LOG STREAM]:   • ADB-TLS transport failure")
    print(f"              [APP CRASH]:  Log lines present but no NativeIntentHandler — app or model issue")
    print(f"              [APP CRASH]:   • Model not loaded (warmup timed out)")
    print(f"              [APP CRASH]:   • Inference too slow for timeout window")
    print(f"              [ROUTING]:    Log lines show fallthrough — QIR/MiniLM didn't match")
    print(f"              [ROUTING]:    • Intent not in QIR regex set")
    print(f"              [ROUTING]:    • MiniLM classifier below threshold")
    print(f"              [TIMEOUT]:    • Timeout too short for this device")
    print(f"  [oracle]     ABORTING \u2014 all further test results would be untrustworthy")
    return False


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


def _keep_foreground_until_inference_starts(
    timeout: float = 30.0,
    poll_interval: float = 2.0,
) -> None:
    """Keep the app foregrounded until inference actually starts."""
    deadline = time.time() + timeout
    accumulated = ""
    while time.time() < deadline:
        time.sleep(poll_interval)
        accumulated += "\n" + read_logcat_all()
        if "OrchTest:" in accumulated or "InferenceGenerationService" in accumulated:
            return
        run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(0.1)
        run_adb("shell", "input", "tap", "500", "1000")
def send_text(text: str, wait_for_inference: bool = True) -> None:
    """Deliver *text* via ``chat_input`` extra → ChatViewModel.

    Uses ``shlex.quote()`` so multi-word text survives re-interpretation
    by the device shell through ``adb shell am start ...``.
    """
    run_adb(
        "shell", "am", "start", "-n", f"{ACTIVITY}",
        "--activity-clear-top", "--activity-single-top",
        "--es", "chat_input", shlex.quote(text),
    )
    if wait_for_inference:
        _keep_foreground_until_inference_starts()


def send_quick_action(text: str) -> None:
    """Deliver *text* via ``quick_action_input`` extra → ActionsViewModel.

    Uses ``shlex.quote()`` so multi-word text survives re-interpretation
    by the device shell through ``adb shell am start ...``.
    """
    run_adb(
        "shell", "am", "start", "-n", f"{ACTIVITY}",
        "--activity-clear-top", "--activity-single-top",
        "--es", "quick_action_input", shlex.quote(text),
    )


def send_slot_reply(text: str) -> None:
    """Deliver a slot reply via ``slot_reply_input`` extra → ActionsViewModel.onSlotReply().

    Uses ``shlex.quote()`` so multi-word text survives re-interpretation
    by the device shell through ``adb shell am start ...``.
    """
    run_adb(
        "shell", "am", "start", "-n", f"{ACTIVITY}",
        "--activity-clear-top", "--activity-single-top",
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
            continue
        expected_text = str(val).lower()
        actual_text = str(actual_val).lower()
        if expected_text not in actual_text and actual_text not in expected_text:
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
