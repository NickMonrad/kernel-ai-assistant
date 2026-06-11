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
from collections.abc import Callable

from adb_harness.config import (
    ADB,
    ACTIVITY,
    ANDROID_SERIAL,
    DIRECT_REPLY_PATTERN,
    LOGCAT_TAG,
    NATIVE_INTENT_NAME_PATTERN,
    NATIVE_INTENT_PATTERN,
    PARAM_EXTRACT_PATTERN,
    PROFILE_FALLBACK_PATTERN,
    PROFILE_LLM_PATTERN,
    WAIT_SECONDS,
)


# ═══════════════════════════════════════════════════════════════════════
# Host-buffered logcat
# ═══════════════════════════════════════════════════════════════════════

_STREAM_PROC: subprocess.Popen[str] | None = None
_STREAM_READER: threading.Thread | None = None
_STREAM_LOCK = threading.Lock()
_STREAM_BUFFER: list[str] = []

_LOGCAT_STREAM_ARGS = [
    "logcat",
    "-v",
    "brief",
    "-s",
    f"{LOGCAT_TAG}:D",
    "LiteRtInferenceEngine:I",
    "MiniLMIntentClassifier:I",
]


def build_adb_cmd(*args: str) -> list[str]:
    """Build an adb command with the selected serial applied consistently."""
    cmd = [ADB]
    if ANDROID_SERIAL:
        cmd.extend(["-s", ANDROID_SERIAL])
    cmd.extend(args)
    return cmd


def run_adb(*args: str) -> str:
    """Run ``adb <args>`` and return stdout. Stderr is discarded."""
    try:
        result = subprocess.run(
            build_adb_cmd(*args),
            capture_output=True,
            text=True,
            timeout=30,
        )
    except subprocess.TimeoutExpired:
        return ""
    return result.stdout.strip()


def _tap_keepalive() -> None:
    """Keep the screen awake and the app in the foreground."""
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.1)
    run_adb("shell", "input", "tap", "500", "1000")


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

    NOTE: ``adb logcat -c`` is deliberately NOT called here. On ADB-over-TLS
    the ``-c`` flag exits 0 but can leave the ring buffer unreadable, causing
    false ``NO_MATCH`` results.

    Per-test isolation for the persistent stream is handled entirely via the
    host-side buffer: ``clear_logcat()`` drains ``_STREAM_BUFFER`` before each
    case without touching the device ring.
    """
    global _STREAM_PROC, _STREAM_READER
    if _STREAM_PROC is not None and _STREAM_PROC.poll() is None:
        return
    if _STREAM_PROC is not None:
        logcat_stop()
    try:
        _STREAM_PROC = subprocess.Popen(
            build_adb_cmd(*_LOGCAT_STREAM_ARGS),
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
    """Poll the buffered logcat stream until *expected* appears or timeout elapses."""
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


def check_logcat_stream(timeout: float = 5.0) -> bool:
    """Best-effort health check for the persistent host-side logcat stream."""
    if _STREAM_PROC is None or _STREAM_PROC.poll() is not None:
        return False
    deadline = time.time() + timeout
    while time.time() < deadline:
        if logcat_snapshot():
            return True
        time.sleep(0.25)
    return _STREAM_PROC.poll() is None


def clear_logcat() -> None:
    """Clear the host-side buffer without touching the device ring buffer."""
    logcat_snapshot()


def read_logcat() -> str:
    """Return accumulated KernelAI logcat output since the last drain."""
    return logcat_snapshot()


def read_logcat_all() -> str:
    """Return accumulated KernelAI + inference logcat output since the last drain."""
    return logcat_snapshot()


def capture_fresh_logcat(
    action: Callable[[], None],
    timeout: float = WAIT_SECONDS,
    expected: str | None = None,
    poll_interval: float = 0.5,
    keep_foreground: bool = False,
) -> str:
    """Capture only log lines produced after *action* starts.

    Starts a fresh bounded logcat subprocess, runs *action*, then accumulates
    only lines emitted during the current observation window. This avoids stale
    ring-buffer matches and avoids dependence on the long-running stream.
    """
    proc = subprocess.Popen(
        build_adb_cmd(*_LOGCAT_STREAM_ARGS),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        bufsize=1,
    )
    assert proc.stdout is not None

    lock = threading.Lock()
    buffer: list[str] = []

    def reader() -> None:
        try:
            for line in proc.stdout:
                with lock:
                    buffer.append(line.rstrip("\n"))
        except ValueError:
            pass

    reader_thread = threading.Thread(target=reader, daemon=True)
    reader_thread.start()
    time.sleep(0.2)

    accumulated: list[str] = []
    seen: set[str] = set()

    def drain() -> None:
        with lock:
            snapshot = list(buffer)
            buffer.clear()
        for line in snapshot:
            line = line.strip()
            if line and line not in seen:
                seen.add(line)
                accumulated.append(line)

    try:
        action()
        deadline = time.time() + timeout
        while time.time() < deadline:
            time.sleep(poll_interval)
            drain()
            combined = "\n".join(accumulated)
            if expected and expected in combined:
                break
            if keep_foreground:
                _tap_keepalive()
        drain()
        return "\n".join(accumulated)
    finally:
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
            proc.wait(timeout=5)

def check_logcat_stream(timeout: float = 5.0) -> bool:
    """Verify the host-side streaming logcat buffer is receiving new lines.

    Writes a unique marker to logcat via ``adb shell log`` and waits for
    it to appear in ``_STREAM_BUFFER`` via ``logcat_snapshot()``.
    Returns True if the marker appears within *timeout* seconds.
    """
    import uuid
    marker = f"HARNESS_STREAM_CHECK_{uuid.uuid4().hex[:12]}"
    clear_logcat()
    run_adb("shell", "log", "-t", LOGCAT_TAG, marker)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if marker in logcat_snapshot():
            return True
        time.sleep(0.5)
    return False



# ═══════════════════════════════════════════════════════════════════════
# Oracle preflight check
# ═══════════════════════════════════════════════════════════════════════

_ORACLE_PROBES: list[tuple[str, str, str, str]] = [
    ("get_time", "what time is it", "get_time", "NativeIntentHandler.handle: intent=get_time"),
]


def check_oracle(
    timeout: float = 30.0,
    probe_idx: int = 0,
) -> bool:
    """Run a single current-probe oracle to confirm observability is healthy."""
    _label, prompt, _expected_intent, expected_marker = _ORACLE_PROBES[probe_idx]

    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run_adb("shell", "input", "swipe", "540", "2000", "540", "500", "200")
    time.sleep(1)
    run_adb(
        "shell",
        "am",
        "start",
        "-n",
        ACTIVITY,
        "--activity-clear-top",
        "--activity-single-top",
    )
    time.sleep(2)

    print(f"  [oracle] probe=\"{prompt}\"  expect={expected_marker!r}  timeout={timeout:.0f}s")
    logcat = capture_fresh_logcat(
        lambda: run_adb(
            "shell",
            "am",
            "start",
            "-n",
            ACTIVITY,
            "--activity-clear-top",
            "--activity-single-top",
            "--es",
            "chat_input",
            shlex.quote(prompt),
        ),
        timeout=timeout,
        expected=expected_marker,
        keep_foreground=True,
    )

    if expected_marker in logcat:
        print("  [oracle] ✅  marker found — observability pipeline healthy")
        return True

    lines = logcat.strip().split("\n") if logcat.strip() else []
    print(f"  [oracle] ❌  marker NOT found in {len(lines)} logcat line(s) within {timeout:.0f}s")
    print("  [oracle]     Last 10 logcat lines:")
    for line in lines[-10:]:
        print(f"              {line}")
    print("  [oracle]     Possible causes (check oracle lines above to distinguish):")
    print("              [LOG STREAM]: No logcat lines at all -- fresh capture produced no app logs")
    print("              [APP CRASH]:  Log lines present but no NativeIntentHandler -- app or model issue")
    print("              [APP CRASH]:   - Model not loaded (warmup timed out)")
    print("              [APP CRASH]:   - Inference too slow for timeout window")
    print("              [ROUTING]:    Log lines show fallthrough -- QIR/MiniLM didn't match")
    print("              [ROUTING]:    - Intent not in QIR regex set")
    print("              [ROUTING]:    - MiniLM classifier below threshold")
    print("              [TIMEOUT]:    - Timeout too short for this device")
    print("  [oracle]     ABORTING - all further test results would be untrustworthy")
    return False


atexit.register(logcat_stop)


# ═══════════════════════════════════════════════════════════════════════
# Screen keepalive
# ═══════════════════════════════════════════════════════════════════════

_KEEPALIVE_STOP: threading.Event | None = None
_KEEPALIVE_THREAD: threading.Thread | None = None


def _keepalive_worker(stop_event: threading.Event) -> None:
    """Background thread: press KEYCODE_WAKEUP every ~25 s to keep screen on."""
    while not stop_event.is_set():
        time.sleep(25)
        if not stop_event.is_set():
            run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")


def start_keepalive() -> None:
    """Start the keepalive background thread."""
    global _KEEPALIVE_STOP, _KEEPALIVE_THREAD
    if _KEEPALIVE_THREAD is not None:
        return
    _KEEPALIVE_STOP = threading.Event()
    _KEEPALIVE_THREAD = threading.Thread(
        target=_keepalive_worker,
        args=(_KEEPALIVE_STOP,),
        daemon=True,
    )
    _KEEPALIVE_THREAD.start()


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
        _tap_keepalive()


def send_text(text: str, wait_for_inference: bool = True) -> None:
    """Deliver *text* via ``chat_input`` extra to ChatViewModel."""
    run_adb(
        "shell",
        "am",
        "start",
        "-n",
        ACTIVITY,
        "--activity-clear-top",
        "--activity-single-top",
        "--es",
        "chat_input",
        shlex.quote(text),
    )
    if wait_for_inference:
        _keep_foreground_until_inference_starts()


def send_quick_action(text: str) -> None:
    """Deliver *text* via ``quick_action_input`` extra to ActionsViewModel."""
    run_adb(
        "shell",
        "am",
        "start",
        "-n",
        ACTIVITY,
        "--activity-clear-top",
        "--activity-single-top",
        "--es",
        "quick_action_input",
        shlex.quote(text),
    )


def send_slot_reply(text: str) -> None:
    """Deliver a slot reply via ``slot_reply_input`` extra to ActionsViewModel."""
    run_adb(
        "shell",
        "am",
        "start",
        "-n",
        ACTIVITY,
        "--activity-clear-top",
        "--activity-single-top",
        "--es",
        "slot_reply_input",
        shlex.quote(text),
    )


# ═══════════════════════════════════════════════════════════════════════
# Fixture helpers
# ═══════════════════════════════════════════════════════════════════════


def setup_contact_alias_fixture() -> bool:
    """Insert an alias entry for contact-name resolution tests."""
    result = run_adb(
        "shell",
        "content",
        "insert",
        "--uri",
        "content://com.android.contacts/raw_contacts",
        "--bind",
        "display_name:STRIP:zippy",
    )
    return "Added" in result or "Uri" in result or result != ""


def teardown_contact_alias_fixture() -> None:
    """Remove the zippy alias fixture inserted by setup."""
    run_adb(
        "shell",
        "content",
        "delete",
        "--uri",
        "content://com.android.contacts/raw_contacts",
        "--bind",
        "display_name:STRIP:zippy",
    )


def dismiss_notifications() -> None:
    """Dismiss all system notifications via ADB."""
    run_adb("shell", "cmd", "notification", "dismiss", "--all")


def cleanup_side_effects(wait_for_inference: bool = False) -> None:
    """Run a no-op weather query to flush pending state."""
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
        params = {
            kv.group(1): kv.group(2).strip()
            for kv in PARAM_EXTRACT_PATTERN.finditer(raw_params)
        }
        return intent_name, params

    fallback = list(NATIVE_INTENT_NAME_PATTERN.finditer(logcat_output))
    if not fallback:
        return None, {}
    return fallback[-1].group(1), {}


def extract_reply(logcat_output: str) -> str | None:
    """Extract the latest DirectReply text from logcat, if any."""
    matches = DIRECT_REPLY_PATTERN.findall(logcat_output)
    return matches[-1] if matches else None


def compare_params(
    expected: dict[str, str] | None,
    actual: dict[str, str],
    ignored_params: frozenset[str] | None = None,
) -> tuple[bool, list[str]]:
    """Compare expected vs actual parameter dicts with tolerant matching."""
    if not expected:
        return True, []
    if actual is None:
        return False, ["Actual params missing"]
    failures: list[str] = []
    ignored = ignored_params or frozenset()
    for key, val in expected.items():
        if key in ignored:
            continue
        actual_val = actual.get(key)
        if actual_val is None:
            failures.append(f"Missing param {key}")
            continue
        expected_text = str(val).lower()
        actual_text = str(actual_val).lower()
        if expected_text not in actual_text and actual_text not in expected_text:
            failures.append(f"Param '{key}': expected {val!r}, got {actual_val!r}")
    return len(failures) == 0, failures


check_params = compare_params


# ═══════════════════════════════════════════════════════════════════════
# Profile helper
# ═══════════════════════════════════════════════════════════════════════


def send_profile(profile_text: str) -> None:
    """Deliver ``profile_text`` via onNewIntent to trigger profile extraction."""
    single_line = profile_text.replace("\n", "\\n")
    run_adb(
        "shell",
        "am",
        "start",
        "-n",
        ACTIVITY,
        "--activity-clear-top",
        "--activity-single-top",
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
