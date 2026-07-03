"""
ADB device interaction primitives — commands, logcat streaming, and keepalive.

Serial resolution is dynamic: every ADB command resolves the target device
from ``ANDROID_SERIAL`` / ``ADB_SERIAL`` at call time, not at import time.
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
    ADB,
    ACTIVITY,
    DIRECT_REPLY_PATTERN,
    LOGCAT_TAG,
    NATIVE_INTENT_NAME_PATTERN,
    NATIVE_INTENT_PATTERN,
    PARAM_EXTRACT_PATTERN,
    PACKAGE,
    PROFILE_FALLBACK_PATTERN,
    PROFILE_LLM_PATTERN,
    WAIT_SECONDS,
    LLM_TOOLS_ASSISTANT_REPLY_PATTERN,
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
    """Build an adb command with the selected serial applied consistently.

    Serial is resolved dynamically from ``ANDROID_SERIAL`` / ``ADB_SERIAL``
    at call time, so callers can set ``os.environ["ANDROID_SERIAL"]`` before
    invoking harness functions without worrying about import-time capture.
    """
    cmd = [ADB]
    serial = os.environ.get("ANDROID_SERIAL", "") or os.environ.get("ADB_SERIAL", "")
    if serial:
        cmd.extend(["-s", serial])
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


def run_adb_checked(*args: str, timeout: float = 10.0) -> tuple[bool, str]:
    """Run an ADB command and return (success, output_or_error).

    Captures both stdout and stderr, returns success based on the process
    return code.  On timeout the command is killed and ``(False, "timeout")``
    is returned.  Does not raise exceptions — the caller can trust a
    ``False`` first element without wrapping in try/except.

    Intended for cleanup/safety-critical commands where the harness must
    distinguish success from failure.
    """
    try:
        result = subprocess.run(
            build_adb_cmd(*args),
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return False, "timeout"
    except FileNotFoundError:
        return False, "adb not found"
    except OSError as exc:
        return False, str(exc)

    if result.returncode == 0:
        return True, result.stdout.strip()

    # Build error message from stderr (if any) or non-zero stdout tail
    err = (result.stderr or result.stdout or "").strip()
    return False, err or f"exit code {result.returncode}"


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
    """Keep the app foregrounded until inference actually starts.
    Uses device-side logcat reads to avoid draining the host-side buffer.
    """
    deadline = time.time() + timeout
    device_logcat_args = ["logcat", "-d", "-s", LOGCAT_TAG + ":D", "InferenceLoadingService:I"]
    while time.time() < deadline:
        time.sleep(poll_interval)
        device_log = run_adb(*device_logcat_args)
        if "InferenceGenerationService" in device_log or "OrchTest:" in device_log:
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




# ═══════════════════════════════════════════════════════════════════════
# Clock/timer alert cleanup
# ═══════════════════════════════════════════════════════════════════════

# Jandal ClockAlertService intent actions (from ClockAlertContract.kt)
_STOP_TIMER_ALERTS = "com.kernel.ai.alarm.action.STOP_TIMER_ALERTS"
_STOP_ALERT = "com.kernel.ai.alarm.action.STOP_ALERT"
_CLOCK_SERVICE = f"{PACKAGE}/.alarm.ClockAlertService"

_DISMISS_TIMEOUT = 10.0
_FORCE_STOP_TIMEOUT = 15.0
_INTENT_TIMEOUT = 5.0


# Third-party clock packages that may have inherited a Jandal timer via AlarmManager
_CLOCK_PKGS = (
    "com.sec.android.app.clockpackage",
    "com.android.deskclock",
    "com.google.android.deskclock",
)


def cleanup_clock_alerts(force_stop_last: bool = True) -> bool:
    """Attempt to cancel/stop any active Jandal timer or alarm alerts.

    Tries, in order:
      1. Send ``ACTION_STOP_TIMER_ALERTS`` to ClockAlertService (best-effort).
      2. Send ``ACTION_STOP_ALERT`` to dismiss any non-timer alert activity (best-effort).
      3. Dismiss all system notifications (best-effort — may not be available on all ROMs).
      4. Force-stop third-party clock packages that may hold leaked timers (best-effort).
      5. Force-stop the Jandal app itself as a last resort (critical — failure fails cleanup).

    Uses ``run_adb_checked()`` so each command is verified via its exit code.
    Stderr from failed commands is printed for debugging.

    Steps 1-3 are best-effort: the ClockAlertService may not be running (app force-stopped),
    and ``cmd notification dismiss`` is not available on all ROMs (e.g. Samsung OneUI).
    Only step 5 (force-stop Jandal) failing counts as a total cleanup failure.

    Returns ``True`` iff the critical force-stop step succeeded (or was skipped).
    """
    success = True
    attempts: list[str] = []

    # ── Best-effort service intents ──────────────────────────────────
    # These send stop signals to Jandal's ClockAlertService.  If the app
    # was force-stopped the service won't be running — that is benign
    # (the device is already clean).  We log the failure but do NOT fail
    # the overall result.
    ok, err = run_adb_checked(
        "shell", "am", "startservice", "-n", _CLOCK_SERVICE,
        "-a", _STOP_TIMER_ALERTS,
        timeout=_INTENT_TIMEOUT,
    )
    if ok:
        attempts.append("stop_timer_alerts")
    else:
        print(f"  [cleanup] stop_timer_alerts (best-effort): {err}", file=sys.stderr)

    ok, err = run_adb_checked(
        "shell", "am", "startservice", "-n", _CLOCK_SERVICE,
        "-a", _STOP_ALERT,
        timeout=_INTENT_TIMEOUT,
    )
    if ok:
        attempts.append("stop_alert")
    else:
        print(f"  [cleanup] stop_alert (best-effort): {err}", file=sys.stderr)

    # Brief settle time for service intents to be processed
    time.sleep(1)

    # ── Best-effort notification dismiss ────────────────────────────
    # ``cmd notification dismiss`` is not available on all ROMs
    # (notably Samsung OneUI).  The force-stop below handles leftovers.
    ok, err = run_adb_checked(
        "shell", "cmd", "notification", "dismiss", "--all",
        timeout=_DISMISS_TIMEOUT,
    )
    if ok:
        attempts.append("dismiss_notifications")
    else:
        print(f"  [cleanup] dismiss_notifications (best-effort): {err}", file=sys.stderr)

    # ── Best-effort third-party clock force-stops ────────────────────
    for pkg in _CLOCK_PKGS:
        ok, err = run_adb_checked(
            "shell", "am", "force-stop", pkg,
            timeout=_FORCE_STOP_TIMEOUT,
        )
        if ok:
            attempts.append(f"force-stop:{pkg}")
        else:
            print(f"  [cleanup] force-stop {pkg} (best-effort): {err}", file=sys.stderr)

    # ── Critical: force-stop Jandal ──────────────────────────────────
    # This is the last-resort action.  If this fails the device may
    # still be buzzing.
    if force_stop_last:
        ok, err = run_adb_checked(
            "shell", "am", "force-stop", PACKAGE,
            timeout=_FORCE_STOP_TIMEOUT,
        )
        if ok:
            attempts.append(f"force-stop:{PACKAGE}")
        else:
            print(f"  [cleanup] force-stop {PACKAGE} FAILED: {err}", file=sys.stderr)
            print(f"  [cleanup]   This is the last-resort action — device may still be buzzing!",
                  file=sys.stderr)
            success = False

    print(f"  [cleanup] clock alerts: {' | '.join(attempts)}"
          + ("  ✓" if success else "  ✗"))
    return success


def cleanup_side_effects(wait_for_inference: bool = False) -> None:
    """Send a harmless query to flush pending LLM state.

    This does **not** cancel timers, alarms, or other active device alerts.
    Use ``cleanup_clock_alerts()`` for that purpose.

    The query ("what time is it") is a get_time route — safe, fast, and
    produces no device side effects.  It is used to clear the model's
    current working state between test cases so inference starts from an
    idle context.
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
    """Extract the latest reply text from logcat, if any.
    Checks both DirectReply (native intent) and llm_tools_assistant_reply (LLM fallthrough) markers.
    """
    direct_matches = DIRECT_REPLY_PATTERN.findall(logcat_output)
    llm_matches = LLM_TOOLS_ASSISTANT_REPLY_PATTERN.findall(logcat_output)
    # Prefer the latest of whichever marker type fired, by return order
    if direct_matches and llm_matches:
        # Both present — take whichever came last by comparing last positions
        return direct_matches[-1] if logcat_output.rfind("DirectReply:") > logcat_output.rfind("llm_tools_assistant_reply:") else llm_matches[-1]
    if direct_matches:
        return direct_matches[-1]
    if llm_matches:
        return llm_matches[-1]
    return None


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
    # The app logs YAML field names (name:, role:, location:) as the log message
    # content via Log.d("KernelAI", ...), so we match the field name after any
    # logcat prefix content.  The prefix comes from logcat -v brief
    # ("D/KernelAI(PID): ") and is not part of the message.
    # Using $ with MULTILINE anchors to end-of-line; . doesn't cross \n.
    name_match = re.search(r"name:\s*(.+)$", logcat_output, flags=re.MULTILINE)
    role_match = re.search(r"role:\s*(.+)$", logcat_output, flags=re.MULTILINE)
    location_match = re.search(r"location:\s*(.+)$", logcat_output, flags=re.MULTILINE)
    return {
        "method": "llm" if used_llm else ("regex" if used_fallback else None),
        "name": name_match.group(1).strip() if name_match else None,
        "role": role_match.group(1).strip() if role_match else None,
        "location": location_match.group(1).strip() if location_match else None,
    }
