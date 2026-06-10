"""
ADB Skill Harness — test runners.

run_tests, run_llm_tools, run_profile_tests, and supporting helpers.
"""

from __future__ import annotations

import os
import re
import shlex
import sys
import time
from datetime import datetime, timezone
from itertools import groupby
from operator import itemgetter

from adb_harness.cases import PHASES, TEST_CASES, PROFILE_TEST_CASES, LLM_TOOLS_CASES
from adb_harness.config import (
    ACTIVITY,
    ADB,
    PACKAGE,
    LLM_TOOLS_ROUTE_PATTERN,
    LLM_TOOLS_NATIVE_TOOL_PATTERN,
    LLM_TOOLS_LEGACY_TOOL_PATTERN,
    LLM_TOOLS_SKILL_RESULT_PATTERN,
    LLM_TOOLS_MESSAGE_SAVED_PATTERN,
    LLM_TOOLS_RETRY_PATTERN,
    LLM_TOOLS_SLOT_FILL_PATTERN,
    INTENT_MATCH_PATTERN,
    MARKER_TIMEOUT_PATTERN,
    SLOT_FILL_MARKERS,
    WAIT_SECONDS,
    PROFILE_WAIT_SECONDS,
    REPORTS_DIR,
)
from adb_harness.device import (
    cleanup_side_effects,
    clear_logcat,
    dismiss_notifications,
    extract_intent,
    extract_reply,
    check_params,
    logcat_start,
    logcat_wait,
    read_logcat,
    read_logcat_all,
    run_adb,
    send_quick_action,
    send_slot_reply,
    send_text,
    send_profile,
    setup_contact_alias_fixture,
    start_keepalive,
    stop_keepalive,
    teardown_contact_alias_fixture,
)
from adb_harness.models import LLMToolsResult, LLMToolsTestCase, ProfileTestCase, TestCase, TestResult
from adb_harness.selectors import _select_tests
from adb_harness.reporting import (
    analyse_results,
    check_oom_sanity,
    post_pr_comment,
    save_report,
    save_llm_tools_report,
)

def _parse_tool_marker(marker: str | None) -> dict[str, str]:
    """Parse a key=value-style marker string into a dict.
    Also handles request=<json> by parsing the JSON and merging its
    top-level string keys into the result dict, so field assertions
    (e.g. expected_fields={"query": "Battle of Hastings"}) work
    against the native tool marker's JSON request payload.
    For legacy raw-text markers (raw=<|tool_call>call:<tool>{...}):
    extracts the tool name and merges JSON fields from the raw text
    so the same assertion logic works for both paths.
    """
    if not marker:
        return {}
    result: dict[str, str] = {}
    for kv in re.finditer(r"(\w+)=((?:(?!\s+\w+=).)+)", marker):
        result[kv.group(1)] = kv.group(2)
    # If there's a request=<json> field, merge its top-level string values
    if "request" in result:
        try:
            parsed = json.loads(result["request"])
            if isinstance(parsed, dict):
                for k, v in parsed.items():
                    if isinstance(v, str):
                        result[k] = v
        except (json.JSONDecodeError, TypeError):
            pass
    # Legacy raw-text marker: extract tool name and merge JSON from
    # the raw content (format: <|tool_call>call:<tool>{<json>})
    if "raw" in result and "tool" not in result:
        raw = result["raw"]
        # Extract tool name: call:<toolName>{
        tool_m = re.search(r"call:(\w+)\{", raw)
        if tool_m:
            result["tool"] = tool_m.group(1)
        # Extract key=value fields from Gemma-4 tool call format:
        #   <key>:<|"|><value><|"|>
        # JSON parsing won't work because keys are unquoted.
        for fv in re.finditer(r"(\w+):<\\?\|\\?\"\\?\|>(.+?)<\\?\|\\?\"\\?\|>", raw):
            result[fv.group(1)] = fv.group(2)
    return result

def _poll_for_all_markers(
    patterns: dict[str, re.Pattern[str]],
    timeout: float = 120,
    poll_interval: float = 2.0,
) -> tuple[dict[str, str | None], str]:
    """Poll logcat for multiple marker patterns simultaneously.
    Accumulates a single log buffer across all poll iterations and searches
    all patterns against it. Also taps the screen periodically to keep the
    app foregrounded (required by Android 15+ foreground service constraint).
    Returns (results, accumulated_log) where results maps each pattern key
    to the first match content (or None), and accumulated_log is the full
    accumulated snapshot for additional searches (retry, slot-fill, etc.).
    """
    deadline = time.time() + timeout
    accumulated = ""
    results: dict[str, str | None] = {k: None for k in patterns}
    while time.time() < deadline:
        time.sleep(poll_interval)
        accumulated += "\n" + read_logcat_all()
        # Search all unfound patterns against the full accumulated log
        for key, pat in patterns.items():
            if results[key] is not None:
                continue
            m = pat.search(accumulated)
            if m:
                results[key] = m.group(1).strip() if m.lastindex else m.group(0).strip()
        # Keep screen on and app foregrounded (Android 15+ foreground service)
        run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(0.1)
        run_adb("shell", "input", "tap", "500", "1000")
        # Early exit if all markers found
        if all(v is not None for v in results.values()):
            break
    return results, accumulated
def _clear_conversation() -> None:
    """Force-stop the app to clear conversation state and model caches."""
    run_adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(1)



def _poll_for_all_markers(
    patterns: dict[str, re.Pattern[str]],
    timeout: float = 120,
    poll_interval: float = 2.0,
) -> tuple[dict[str, str | None], str]:
    """Poll logcat for multiple marker patterns simultaneously.
    Accumulates a single log buffer across all poll iterations and searches
    all patterns against it. Also taps the screen periodically to keep the
    app foregrounded (required by Android 15+ foreground service constraint).
    Returns (results, accumulated_log) where results maps each pattern key
    to the first match content (or None), and accumulated_log is the full
    accumulated snapshot for additional searches (retry, slot-fill, etc.).
    """
    deadline = time.time() + timeout
    accumulated = ""
    results: dict[str, str | None] = {k: None for k in patterns}
    while time.time() < deadline:
        time.sleep(poll_interval)
        accumulated += "\n" + read_logcat_all()
        # Search all unfound patterns against the full accumulated log
        for key, pat in patterns.items():
            if results[key] is not None:
                continue
            m = pat.search(accumulated)
            if m:
                results[key] = m.group(1).strip() if m.lastindex else m.group(0).strip()
        # Keep screen on and app foregrounded (Android 15+ foreground service)
        run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(0.1)
        run_adb("shell", "input", "tap", "500", "1000")
        # Early exit if all markers found
        if all(v is not None for v in results.values()):
            break
    return results, accumulated

def _clear_conversation() -> None:
    """Force-stop the app to clear conversation state and model caches."""
    run_adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(1)



def run_llm_tools(dry_run: bool = False) -> int:
    """Execute the llm_tools harness phase. Returns non-zero on failures.

    Requires runtime marker emission in the app code (ChatViewModel,
    NativeIntentHandler, and the tool-call path must log
    ``llm_tools_route``, ``llm_tools_native_tool``, ``llm_tools_legacy_tool``,
    ``llm_tools_skill_result``, and ``llm_tools_message_toolcall_saved``).
    Without these markers the harness will fail every case.

    This runner is separate from run_tests() because it has different data models,
    observability requirements, and state management (conversation isolation per case).
    """
    if dry_run:
        print("=" * 70)
        print("  LLM TOOLS E2E — DRY RUN (no device interaction)")
        print("=" * 70)
        print()
        for i, tc in enumerate(LLM_TOOLS_CASES, 1):
            print(f"  [{i:2d}] {tc.name}: \"{tc.message}\"")
            print(f"       expected → {tc.expected_top_level_tool}"
                  f"{f' (nested: {tc.expected_nested_intent})' if tc.expected_nested_intent else ''}")
            if tc.expected_fields:
                print(f"       fields   → {tc.expected_fields}")
            print(f"       expect: no_regex_match={tc.expect_no_regex_match}"
                  f" no_classifier={tc.expect_no_classifier_match}"
                  f" no_slot_fill={tc.expect_no_slot_fill}"
                  f" no_retry={tc.expect_no_retry}")
        print()
        print(f"  Total: {len(LLM_TOOLS_CASES)} test cases")
        print("=" * 70)
        return 0

    if not os.path.isfile(ADB):
        print(f"ERROR: ADB not found at {ADB}", file=sys.stderr)
        return 1

    print("=" * 70)
    print("  LLM TOOLS E2E TEST")
    print("=" * 70)
    # Start host-side logcat streaming (required for all read_logcat_all() calls below)
    logcat_start()

    # Preflight: prove model stack ready and MiniLM ready.
    # Dismiss any notification overlays first (Samsung Calendar, etc.)
    dismiss_notifications()
    # Force-stop to ensure clean process state before warmup
    run_adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(1)
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run_adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(3)
    clear_logcat()

    # Warmup probe: send a deterministic query so the model stack initializes.
    # Keep app in foreground until routing completes (Android 15+ constraint).
    # _keep_foreground_until_inference_starts() already proves the model stack is ready
    # by detecting OrchTest: or InferenceGenerationService markers.
    run_adb("shell", "am", "start", "-n", ACTIVITY, "--es", "chat_input", shlex.quote("what time is it"))
    _keep_foreground_until_inference_starts()

    # MiniLM readiness check: send a prompt that exercises MiniLM, wait for classifier result.
    # _keep_foreground_until_inference_starts() already proves routing works.
    print("  [preflight] Proving MiniLM ready ...", end=" ", flush=True)
    clear_logcat()
    run_adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(1)
    run_adb("shell", "am", "start", "-n", ACTIVITY, "--es", "chat_input", shlex.quote("what time is it"))
    _keep_foreground_until_inference_starts()
    print("ready")
    # Pre-run cleanup
    print("  [preflight] Cleaning up timers/alarms ...", end=" ", flush=True)
    for pkg in ("com.sec.android.app.clockpackage", "com.android.deskclock", "com.google.android.deskclock"):
        run_adb("shell", "am", "force-stop", pkg)
    cleanup_side_effects()
    print("done")
    clear_logcat()
    time.sleep(WAIT_SECONDS)
    clear_logcat()
    time.sleep(1)
    print()

    # Run each golden prompt in isolation
    results: list[LLMToolsResult] = []
    total = len(LLM_TOOLS_CASES)
    failures = 0

    for idx, tc in enumerate(LLM_TOOLS_CASES, 1):
        print(f"  [{idx:2d}/{total}] {tc.name}: \"{tc.message}\" ...", end=" ", flush=True)

        # Isolate: force-stop, dismiss overlays, then send prompt
        _clear_conversation()
        # Dismiss any notification overlays (Samsung Calendar, etc.)
        run_adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.3)
        run_adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.3)
        clear_logcat()
        time.sleep(0.5)

        # Send the prompt (foreground keepalive handled by _poll_for_all_markers)
        send_text(tc.message, wait_for_inference=False)
        # Poll for all markers simultaneously from a single accumulated buffer,
        # avoiding the log-draining bug where sequential _poll_for_marker calls
        # consume markers that arrived together.
        patterns = {
            "route": LLM_TOOLS_ROUTE_PATTERN,
            "native_tool": LLM_TOOLS_NATIVE_TOOL_PATTERN,
            "legacy_tool": LLM_TOOLS_LEGACY_TOOL_PATTERN,
            "skill_result": LLM_TOOLS_SKILL_RESULT_PATTERN,
            "message_saved": LLM_TOOLS_MESSAGE_SAVED_PATTERN,
        }
        markers, final_log = _poll_for_all_markers(patterns, timeout=120)
        route_marker = markers["route"]
        native_tool = markers["native_tool"]
        legacy_tool = markers["legacy_tool"]
        skill_result = markers["skill_result"]
        message_saved = markers["message_saved"]
        # Check for slot-fill and retry markers (from the same accumulated log)
        retry_seen = bool(LLM_TOOLS_RETRY_PATTERN.search(final_log))
        slot_fill_seen = bool(LLM_TOOLS_SLOT_FILL_PATTERN.search(final_log))
        # Extract tool info from markers
        native_data = _parse_tool_marker(native_tool)
        legacy_data = _parse_tool_marker(legacy_tool)
        actual_top_level = native_data.get("tool") or legacy_data.get("tool")
        actual_nested = native_data.get("nested_intent") or legacy_data.get("nested_intent")

        # Extract chip text from logcat (stable diagnostic logging from ChatViewModel)
        chip_match = re.search(r"tool_chip_visible:\s*(\S+)", final_log)
        chip_text = chip_match.group(1) if chip_match else None

        # Extract reply
        reply_text = extract_reply(final_log)

        # Build assertion failures
        failures_list: list[str] = []

        if not chip_text:
            failures_list.append("No tool_chip_visible marker found")

        # Tool name check
        if actual_top_level != tc.expected_top_level_tool:
            failures_list.append(
                f"tool name: expected {tc.expected_top_level_tool!r}, got {actual_top_level!r}"
            )

        # Nested intent check
        if tc.expected_nested_intent and actual_nested != tc.expected_nested_intent:
            failures_list.append(
                f"nested intent: expected {tc.expected_nested_intent!r}, got {actual_nested!r}"
            )

        # Field checks
        if tc.expected_fields:
            merged_data = {**native_data, **legacy_data}
            for k, v in tc.expected_fields.items():
                actual_v = merged_data.get(k)
                if actual_v is None:
                    failures_list.append(f"field {k!r}: missing")
                elif v.lower() not in actual_v.lower() and actual_v.lower() not in v.lower():
                    failures_list.append(f"field {k!r}: expected {v!r}, got {actual_v!r}")

        # Negative checks
        if tc.expect_no_regex_match and "NativeIntentHandler.handle" in final_log:
            # Check if it appeared before the tool-call marker
            regex_pos = final_log.find("NativeIntentHandler.handle")
            tool_positions = [p for p in (
                final_log.find("llm_tools_native_tool"),
                final_log.find("llm_tools_legacy_tool"),
            ) if p != -1]
            tool_pos = min(tool_positions) if tool_positions else -1
            if tool_pos == -1 or regex_pos < tool_pos:
                failures_list.append("QIR regex matched before Gemma tool-call")

        if tc.expect_no_classifier_match and "ClassifierMatch" in final_log:
            failures_list.append("ClassifierMatch before Gemma generation")

        if tc.expect_no_slot_fill and slot_fill_seen:
            failures_list.append("Slot-fill path triggered before Gemma")

        if tc.expect_no_retry and retry_seen:
            failures_list.append("Model retry observed (raw_tool_call_retry_succeeded / hallucination_retry_succeeded)")

        # Positive checks
        if not route_marker:
            failures_list.append("No route-decision marker found")

        if not (native_tool or legacy_tool):
            failures_list.append("No native-tool or legacy-tool marker found")

        if not message_saved:
            failures_list.append("No ChatMessage.toolCall persistence marker found")
        if actual_top_level and not skill_result:
            failures_list.append("No skill_result marker found")

        # Result mode check
        if tc.expected_result_mode != "unknown":
            skill_data = _parse_tool_marker(skill_result)
            mode = skill_data.get("mode", "unknown")
            if mode != tc.expected_result_mode:
                failures_list.append(f"result mode: expected {tc.expected_result_mode!r}, got {mode!r}")

        passed = len(failures_list) == 0
        result = LLMToolsResult(
            index=idx,
            name=tc.name,
            message=tc.message,
            expected_top_level_tool=tc.expected_top_level_tool,
            actual_top_level_tool=actual_top_level,
            actual_nested_intent=actual_nested,
            route_marker=route_marker,
            native_tool_marker=native_tool,
            legacy_tool_marker=legacy_tool,
            skill_result_marker=skill_result,
            message_saved_marker=message_saved,
            retry_seen=retry_seen,
            slot_fill_seen=slot_fill_seen,
            chip_text=chip_text,
            reply_text=reply_text,
            passed=passed,
            failures=failures_list,
        )
        results.append(result)

        if passed:
            print("✓")
        else:
            failures += 1
            print(f"✗ ({'; '.join(failures_list)})")

        # Brief pause between cases
        time.sleep(2)

    # Summary
    print()
    print("-" * 70)
    print(f"  {'#':>3}  {'RESULT':>6}  {'EXPECTED':<24}  {'ACTUAL':<24}  {'NAME':<30}")
    print("-" * 70)
    for r in results:
        icon = "  ✓" if r.passed else "  ✗"
        actual = r.actual_top_level_tool or "NO_MATCH"
        nested = f" (nested: {r.actual_nested_intent})" if r.actual_nested_intent else ""
        print(f"  {r.index:3d}  {icon:>6}  {r.expected_top_level_tool:<24}  {actual + nested:<24}  \"{r.message}\"")
    print("-" * 70)
    print(f"  PASSED: {total - failures}/{total}  FAILED: {failures}/{total}")
    print("=" * 70)

    # Save report
    run_ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    report_path = save_llm_tools_report(results, elapsed=0, partial=False, run_ts=run_ts)
    print(f"  Report saved → {report_path}")

    return 1 if failures > 0 else 0
def run_adb(*args: str) -> str:
    """Run an ADB command and return stdout. Prints stderr on non-zero exit."""
    result = subprocess.run(
        [ADB, *args],
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode != 0 and result.stderr:
        print(f"\n  [adb warn] {result.stderr.strip()}", file=sys.stderr)
    return result.stdout


# ===================================================================
# Host-side logcat streaming (#1102)
#
# Long-running logcat process on the host side avoids the S21's 5MB logcat
# buffer rotation problem. Instead of polling `adb logcat -d` (which dumps
# the ring buffer and loses early entries in long runs), we keep a persistent
# `adb logcat` subprocess whose stdout is continuously buffered on the host.
#
# Callers use `logcat_snapshot()` to atomically drain the accumulated output
# since the last snapshot (or since the stream started).
# ===================================================================

import subprocess
import atexit
import threading

_logcat_proc: subprocess.Popen | None = None
_logcat_buffer: list[str] = []
_logcat_lock = threading.Lock()


def _logcat_reader() -> None:
    """Read lines from the persistent logcat subprocess and buffer them."""
    global _logcat_proc, _logcat_buffer
    assert _logcat_proc is not None
    assert _logcat_proc.stdout is not None
    for line in _logcat_proc.stdout:
        with _logcat_lock:
            _logcat_buffer.append(line.rstrip("\n"))


def logcat_start() -> None:
    """Start the persistent logcat stream. Safe to call multiple times (idempotent).
    Filters to KernelAI:D and LiteRtInferenceEngine:I so profile warmup and
    orchestration tests both work from the same stream."""
    global _logcat_proc
    if _logcat_proc is not None:
        return
    # Clear device-side buffer first, then start streaming
    run_adb("logcat", "-c")
    _logcat_proc = subprocess.Popen(
        [ADB, "logcat", "-s", f"{LOGCAT_TAG}:D", "LiteRtInferenceEngine:I", "MiniLMIntentClassifier:I", "-v", "brief"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        universal_newlines=True, bufsize=1,
    )
    reader = threading.Thread(target=_logcat_reader, daemon=True)
    reader.start()
    # Brief pause to let the stream establish, then drain initial noise from buffer
    time.sleep(0.5)
    logcat_snapshot()  # Discard initial burst without blocking device clear on ADB-over-TCP


def logcat_stop() -> None:
    """Stop the persistent logcat stream and drain remaining output."""
    global _logcat_proc, _logcat_buffer
    if _logcat_proc is not None:
        _logcat_proc.terminate()
        _logcat_proc.wait(timeout=5)
        _logcat_proc = None
    with _logcat_lock:
        _logcat_buffer.clear()


def logcat_snapshot() -> str:
    """Atomically drain the accumulated logcat buffer and return it as a string."""
    with _logcat_lock:
        result = "\n".join(_logcat_buffer)
        _logcat_buffer.clear()
    return result


def logcat_wait(expected: str, timeout: float = WAIT_SECONDS) -> str:
    """Poll the logcat buffer until [expected] appears, or timeout.
    Returns accumulated snapshot — evidence isn't lost on timeout."""
    deadline = time.time() + timeout
    seen = set()
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


atexit.register(logcat_stop)


# ---------------------------------------------------------------------------
# Backward-compatible wrappers — existing callers continue to work unchanged.
# Uses streaming buffer instead of `adb logcat -d` to avoid S21's 5MB logcat
# buffer rotation issue in long runs (#1102).
# ---------------------------------------------------------------------------

def clear_logcat() -> None:
    """Clear the logcat buffer. Drains the streaming buffer only (skips device
    ring buffer clear which blocks on ADB-over-TCP while streaming is active)."""
    logcat_snapshot()  # Drain any accumulated output


def read_logcat() -> str:
    """Return accumulated KernelAI logcat output since last clear."""
    return logcat_snapshot()


def read_logcat_all() -> str:
    """Return accumulated logcat (KernelAI + LiteRtInferenceEngine) since last clear.
    Note: with streaming, this returns the same snapshot as read_logcat() since
    the stream is filtered by tag. For LiteRtInferenceEngine logs, callers that
    specifically need a wider tag filter should use logcat_snapshot() directly
    after starting a stream with the appropriate filter."""
    return logcat_snapshot()


# ---------------------------------------------------------------------------
# Screen keepalive
# ---------------------------------------------------------------------------

_keepalive_stop = threading.Event()


def _keepalive_worker() -> None:
    """Send KEYCODE_WAKEUP every 25 s to prevent screen sleep during test runs."""
    while not _keepalive_stop.wait(25):
        run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")


def start_keepalive() -> threading.Thread:
    _keepalive_stop.clear()
    t = threading.Thread(target=_keepalive_worker, daemon=True, name="screen-keepalive")
    t.start()
    return t


def stop_keepalive() -> None:
    _keepalive_stop.set()


def _keep_foreground_until_inference_starts() -> None:
    """Keep the app in the foreground by tapping the screen periodically.
    On Android 15+, InferenceGenerationService.startForegroundService() must be
    called within ~5 seconds of the app becoming foreground. This function keeps
    the activity visible until the inference service starts (detected via
    InferenceGenerationService log or NativeIntentHandler route marker).
    Taps every 2 seconds for up to 30 seconds.
    """
    deadline = time.time() + 30
    while time.time() < deadline:
        log = read_logcat_all()
        if "InferenceGenerationService" in log or "InferenceLoadingService" in log or "initEngineWhenReady" in log or "llm_tools_route:" in log or "OrchTest:" in log:
            break
        run_adb("shell", "input", "tap", "500", "1000")
        time.sleep(2)


def send_text(text: str, wait_for_inference: bool = True) -> None:
    """Deliver chat_input extra via onNewIntent — navigates to chat from any screen.

    On Android 15+, InferenceGenerationService.startForegroundService() must be called
    within ~5 seconds of the app becoming foreground. After sending the prompt we keep
    the activity visible (touch screen periodically) so the service start remains valid
    until inference completes (typically 30-60s for Gemma-4 E-4B).
    """
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    # --activity-clear-top ensures our activity is at the top of its task so
    # onNewIntent always fires, even when external apps (Calendar, Clock, Maps)
    # were opened by previous tests and are covering the screen.
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
    """Deliver quick_action_input extra — navigates to Actions tab and calls executeAction().

    Used to drive slot-fill tests: bare queries (e.g. "set an alarm") route through
    ActionsViewModel → QIR → NeedsSlot → navigate to Chat with slot prompt.
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
    """Deliver slot_reply_input extra via onNewIntent → ActionsViewModel.onSlotReply().

    Used for the second turn of a slot-fill test: after send_quick_action triggers NeedsSlot
    and the ModalBottomSheet is shown, this delivers the user's answer directly to the
    ActionsViewModel without navigating away from the Actions tab.
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



ALIAS_TEST_NAME = "zippy"       # test alias → resolves to Voicemail contact
ALIAS_DISPLAY_NAME = "Voicemail"  # must match a real contact on the device

DB_PATH = f"/data/data/{PACKAGE}/databases/kernel_ai.db"


VOICEMAIL_NUMBER = "121"         # provider voicemail shortcode
def setup_contact_alias_fixture() -> bool:
    """Insert test alias 'zippy' → Voicemail into Room contact_aliases table."""
    try:
        run_adb(
            "shell", "sqlite3", DB_PATH,
            f"INSERT OR REPLACE INTO contact_aliases (alias, displayName, contactId, phoneNumber) "
            f"VALUES ('{ALIAS_TEST_NAME}', '{ALIAS_DISPLAY_NAME}', '0', '{VOICEMAIL_NUMBER}');",
        )
        return True
    except Exception:
        return False


def teardown_contact_alias_fixture() -> None:
    """Remove test alias inserted during setup."""
    run_adb(
        "shell", "sqlite3", DB_PATH,
        f"DELETE FROM contact_aliases WHERE alias='{ALIAS_TEST_NAME}';",
    )


def dismiss_notifications() -> None:
    """Dismiss any notification popups or alerts that may block the app from being in the foreground.
    On Android 15+ Samsung devices, notification popups (e.g. Calendar alerts) can cover
    the activity and prevent startForegroundService() from succeeding. This function
    presses the back button to dismiss overlays, then brings the app to the foreground.
    """
    # Press back to dismiss any overlay/notification popup
    run_adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.5)
    run_adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.5)
    # Bring app to foreground
    run_adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(1)


def cleanup_side_effects() -> None:
    """Cancel any timers and alarms set during testing to avoid them firing on the device."""
    for msg in ("cancel the timer", "cancel all alarms"):
        send_text(msg, wait_for_inference=False)
        time.sleep(3)  # Brief pause — just enough for the intent to dispatch
    # Force-stop all clock apps to silence any ringing timers/alarms that have
    # already fired (send_text cancels pending ones; force-stop kills active alerts).
    for pkg in (
        "com.sec.android.app.clockpackage",  # Samsung Clock
        "com.android.deskclock",             # AOSP Clock
        "com.google.android.deskclock",      # Google Clock
    ):
        run_adb("shell", "am", "force-stop", pkg)


def extract_intent(logcat_output: str) -> tuple[str | None, dict[str, str]]:
    """Extract the intent name and params from logcat output.

    Returns (intent_name, params_dict). params_dict is empty if not found.
    The log line format is:
        NativeIntentHandler.handle: intent=<name> params={key=value, ...}
    """
    m = INTENT_PATTERN.search(logcat_output)
    if m:
        intent_name = m.group(1)
        raw_params = m.group(2)
        # Kotlin's Map.toString() produces {key1=value1, key2=value2}
        params: dict[str, str] = {}
        for kv in re.finditer(r"(\w+)=([^,}]+)", raw_params):
            params[kv.group(1)] = kv.group(2).strip()
        return intent_name, params
    # Fallback: intent name only (older log format without params)
    m2 = INTENT_NAME_PATTERN.search(logcat_output)
    return (m2.group(1) if m2 else None), {}


def extract_reply(logcat_output: str) -> str | None:
    """Extract the first DirectReply content from logcat output."""
    m = DIRECTREPLY_PATTERN.search(logcat_output)
    return m.group(1).strip() if m else None


def check_params(
    expect: dict[str, str] | None,
    actual: dict[str, str],
) -> tuple[bool, list[str]]:
    """Check expected params against actual. Returns (passed, failure_descriptions)."""
    if not expect:
        return True, []
    failures = []
    for k, v in expect.items():
        actual_v = actual.get(k)
        # Partial match: expected value just needs to appear in actual (handles list_name="shopping" vs "shopping list")
        if actual_v is None:
            failures.append(f"{k}: expected {v!r} but key missing")
        elif v.lower() not in actual_v.lower() and actual_v.lower() not in v.lower():
            failures.append(f"{k}: expected {v!r} got {actual_v!r}")
    return len(failures) == 0, failures


def save_llm_tools_report(
    results: list[LLMToolsResult],
    elapsed: float = 0.0,
    partial: bool = False,
    run_ts: str | None = None,
) -> Path:
    """Serialise llm_tools results to a JSON report.
    This is separate from save_report() because LLMToolsResult has a
    different schema (no intent_passed/params_passed/xfail fields).
    """
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    if partial and run_ts:
        report_path = REPORTS_DIR / f"{run_ts}_llm_tools_partial.json"
        status = "in_progress"
    else:
        report_path = REPORTS_DIR / f"{ts}_llm_tools.json"
        status = "complete"
        if run_ts:
            partial_file = REPORTS_DIR / f"{run_ts}_llm_tools_partial.json"
            partial_file.unlink(missing_ok=True)
    total = len(results)
    passed = sum(1 for r in results if r.passed)
    failed = total - passed
    report = {
        "suite": "llm_tools",
        "status": status,
        "timestamp": ts,
        "elapsed_seconds": round(elapsed, 1),
        "summary": {
            "total": total,
            "passed": passed,
            "failed": failed,
        },
        "results": [
            {
                "index": r.index,
                "name": r.name,
                "message": r.message,
                "expected_top_level_tool": r.expected_top_level_tool,
                "actual_top_level_tool": r.actual_top_level_tool,
                "actual_nested_intent": r.actual_nested_intent,
                "route_marker": r.route_marker,
                "native_tool_marker": r.native_tool_marker,
                "legacy_tool_marker": r.legacy_tool_marker,
                "skill_result_marker": r.skill_result_marker,
                "message_saved_marker": r.message_saved_marker,
                "retry_seen": r.retry_seen,
                "slot_fill_seen": r.slot_fill_seen,
                "chip_text": r.chip_text,
                "reply_text": r.reply_text,
                "passed": r.passed,
                "failures": r.failures,
            }
            for r in results
        ],
    }
    report_path.write_text(json.dumps(report, indent=2))
    return report_path
def save_report(
    results: list[TestResult],
    suite: str = "skills",
    elapsed: float = 0.0,
    partial: bool = False,
    run_ts: str | None = None,
) -> Path:
    """Serialise results to a JSON file in scripts/test-reports/ and return the path.

    When partial=True, writes/overwrites a fixed-name in-progress snapshot so that
    results are never lost if the run is aborted mid-way.  When partial=False (the
    final save), writes the completed timestamped report and deletes any partial file
    that was written during the same run.
    """
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")

    if partial and run_ts:
        report_path = REPORTS_DIR / f"{run_ts}_{suite}_partial.json"
        status = "in_progress"
    else:
        report_path = REPORTS_DIR / f"{ts}_{suite}.json"
        status = "complete"
        # Remove the in-progress snapshot now that the full report is being written
        if run_ts:
            partial_file = REPORTS_DIR / f"{run_ts}_{suite}_partial.json"
            partial_file.unlink(missing_ok=True)

    total = len(results)
    passed = sum(1 for r in results if r.intent_passed and r.params_passed and not r.xfail and not r.log_check_warn)
    xfails = sum(1 for r in results if r.xfail and not r.intent_passed)
    failures = total - passed - xfails

    report = {
        "suite": suite,
        "status": status,
        "timestamp": ts,
        "elapsed_seconds": round(elapsed, 1),
        "summary": {
            "total": total,
            "passed": passed,
            "xfail": xfails,
            "failed": failures,
        },
        "results": [
            {
                "index": r.index,
                "message": r.message,
                "expect_intent": r.expect_intent,
                "actual_intent": r.actual_intent,
                "expect_params": r.expect_params,
                "actual_params": r.actual_params,
                "intent_passed": r.intent_passed,
                "params_passed": r.params_passed,
                "param_failures": r.param_failures,
                "xfail": r.xfail,
                "reply_warn": r.reply_warn,
                "log_check_warn": r.log_check_warn,
                "first_turn_warn": r.first_turn_warn,
                "phase": r.phase,
                "status": (
                    "xfail" if r.xfail and not r.intent_passed
                    else "pass" if r.intent_passed and r.params_passed and not r.log_check_warn
                    else "fail"
                ),
            }
            for r in results
        ],
    }
    report_path.write_text(json.dumps(report, indent=2))

    # Auto-generate HTML report if generator script is present
    gen_script = Path(__file__).parent / "generate_report.py"
    if gen_script.exists():
        subprocess.run([sys.executable, str(gen_script), str(report_path)], check=False)

    return report_path


def analyse_results(results: list[TestResult]) -> None:
    """Print a pattern analysis section after the summary table."""
    failures = [r for r in results if not r.xfail and (not r.intent_passed or not r.params_passed)]
    if not failures:
        print("\n  ✅ No failures to analyse.")
        return

    print("\n  FAILURE ANALYSIS")
    print("  " + "-" * 68)

    # Group intent routing failures by actual (mis-routed) intent
    intent_failures = [r for r in failures if not r.intent_passed]
    param_failures  = [r for r in failures if r.intent_passed and not r.params_passed]

    if intent_failures:
        by_actual: dict[str, list[TestResult]] = {}
        for r in intent_failures:
            key = r.actual_intent or "NO_MATCH"
            by_actual.setdefault(key, []).append(r)
        print(f"\n  Intent routing failures ({len(intent_failures)}):")
        for actual, group in sorted(by_actual.items(), key=lambda x: -len(x[1])):
            expected_intents = sorted({r.expect_intent for r in group})
            print(f"    → routed as {actual!r} instead of {expected_intents}:")
            for r in group:
                print(f"       [{r.index:3d}] \"{r.message}\"")

    if param_failures:
        print(f"\n  Param extraction failures ({len(param_failures)}):")
        for r in param_failures:
            print(f"    [{r.index:3d}] \"{r.message}\"  (intent={r.expect_intent})")
            for pf in r.param_failures:
                print(f"           ✗ {pf}")

    # Highlight intents with high failure rates
    by_intent: dict[str, list[TestResult]] = {}
    for r in results:
        by_intent.setdefault(r.expect_intent, []).append(r)
    hot = [
        (intent, grp)
        for intent, grp in by_intent.items()
        if len(grp) >= 2 and sum(1 for r in grp if not r.intent_passed and not r.xfail) / len(grp) >= 0.5
    ]
    if hot:
        print(f"\n  ⚠️  High-failure-rate intents (≥50% of cases failing):")
        for intent, grp in sorted(hot, key=lambda x: -len(x[1])):
            n_fail = sum(1 for r in grp if not r.intent_passed and not r.xfail)
            print(f"    {intent}: {n_fail}/{len(grp)} failing")

    print()


# Minimum consecutive same-actual-intent results required to trigger the OOM warning.
_OOM_RUN_THRESHOLD = 5




# Minimum consecutive same-actual-intent results required to trigger the OOM warning.
_OOM_RUN_THRESHOLD = 5


def check_oom_sanity(results: list[TestResult]) -> None:
    """Warn if a long consecutive run of tests all return the same actual intent while their
    *expected* intents differ — a strong signal that the model has hung or OOM'd and is
    returning a stuck response.

    Deliberately does NOT warn when the expected intents within the run are all the same
    (e.g. the weather phase where every test correctly maps to get_weather), because that
    is valid behaviour, not a stuck model.  Closes #563.
    """
    i = 0
    warned = False
    while i < len(results):
        run_actual = results[i].actual_intent
        j = i
        while j < len(results) and results[j].actual_intent == run_actual:
            j += 1
        run = results[i:j]
        if len(run) >= _OOM_RUN_THRESHOLD:
            expected_in_run = {r.expect_intent for r in run}
            # Only suspicious when expected intents VARY but actual is stuck on one value.
            if len(expected_in_run) > 1:
                if not warned:
                    print("\n  OOM / MODEL-HANG SANITY CHECK")
                    print("  " + "-" * 68)
                    warned = True
                label = run_actual if run_actual else "NO_MATCH"
                print(
                    f"\n  ⚠️  Possible OOM/hang: tests {run[0].index}–{run[-1].index} "
                    f"({len(run)} consecutive) all returned {label!r} "
                    f"but expected {len(expected_in_run)} distinct intents "
                    f"({', '.join(sorted(expected_in_run))})."
                )
                print("     Consider restarting the app and re-running this range.")
        i = j
    if warned:
        print()



def run_tests(dry_run: bool = False, post_pr: bool = False, start_phase: str | None = None,
              phases: list[str] | None = None, categories: list[str] | None = None,
              tags: list[str] | None = None, exclude_tags: list[str] | None = None,
              case_ids: list[str] | None = None) -> int:
    """Execute all test cases. Returns non-zero on failures."""

    if dry_run:
        print("=" * 80)
        print("  ADB SKILL TEST — DRY RUN (no device interaction)")
        print("=" * 80)
        print()

        # Resolve phase names for display — preserve existing --phases behaviour
        phase_names_dr = [name for name, _ in PHASES]
        selected_phase_names: list[str] | None = None
        if phases is not None:
            selected_phases_set: set[int] = set()
            for token in phases:
                token = token.strip()
                if token.isdigit():
                    n = int(token)
                    if not (1 <= n <= len(PHASES)):
                        print(f"ERROR: --phases {token!r} out of range (1–{len(PHASES)}).", file=sys.stderr)
                        return 1
                    selected_phases_set.add(n - 1)
                else:
                    if token not in phase_names_dr:
                        print(f"ERROR: --phases {token!r} not recognised. Valid: {', '.join(phase_names_dr)}", file=sys.stderr)
                        return 1
                    selected_phases_set.add(phase_names_dr.index(token))
            selected_phase_names = [phase_names_dr[i] for i in sorted(selected_phases_set)]
        else:
            selected_phase_names = phase_names_dr

        # Use _select_tests for composable filtering
        selected_tests = _select_tests(
            phases=PHASES,
            phase_filter=selected_phase_names,
            categories=categories,
            tags=tags,
            exclude_tags=exclude_tags,
            case_ids=case_ids,
        )

        # Print filter summary
        filter_parts: list[str] = []
        if selected_phase_names:
            filter_parts.append(f"phases={','.join(selected_phase_names)}")
        if categories:
            filter_parts.append(f"categories={','.join(categories)}")
        if tags:
            filter_parts.append(f"tags={','.join(tags)}")
        if exclude_tags:
            filter_parts.append(f"exclude_tags={','.join(exclude_tags)}")
        if case_ids:
            filter_parts.append(f"case_ids={','.join(case_ids)}")
        if filter_parts:
            print(f"  Filters: {' | '.join(filter_parts)}")
            print()

        # Print selected tests with metadata
        xfail_count = 0
        for i, (phase_idx, case_idx, tc) in enumerate(selected_tests, 1):
            phase_name = PHASES[phase_idx][0]
            xfail_marker = "~" if tc.xfail else " "
            print(f"  [{xfail_marker}{i:2d}] {phase_name:22s} [{tc.category:14s}] id:{tc.id}")
            print(f"       \"{tc.message}\"", end="")
            if tc.expect_intent:
                print(f" → {tc.expect_intent}", end="")
            if tc.xfail:
                print(f"  (xfail: {tc.xfail_reason})", end="")
            print()
            # Extra metadata on third line if non-empty
            extra: list[str] = []
            if tc.tags:
                extra.append(f"tags=[{','.join(tc.tags)}]")
            if tc.fixture:
                extra.append(f"fixture={tc.fixture}")
            if tc.slot_reply:
                extra.append(f"slot_reply={tc.slot_reply!r}")
            if tc.confirm_reply:
                extra.append(f"confirm_reply={tc.confirm_reply!r}")
            if tc.expect_initial_log_contains:
                extra.append(f"init_log={tc.expect_initial_log_contains!r}")
            if tc.expect_log_contains:
                extra.append(f"log_contains={tc.expect_log_contains!r}")
            if tc.expect_reply_contains:
                extra.append(f"reply_contains={tc.expect_reply_contains!r}")
            if extra:
                print(f"       {' | '.join(extra)}")
            if tc.xfail:
                xfail_count += 1
        print()
        print(f"  Total: {len(selected_tests)} test cases"
              + (f" ({xfail_count} xfail)" if xfail_count else ""))
        print("=" * 80)
        return 0

    if not os.path.isfile(ADB):
        print(f"ERROR: ADB not found at {ADB}", file=sys.stderr)
        return 1

    # Start host-side logcat streaming (#1102) — avoids S21 buffer rotation failures.
    logcat_start()

    print("=" * 70)
    print("  ADB SKILL REGRESSION TEST")
    print("=" * 70)
    print()

    # Keep screen awake for the duration of the test run (restored on exit).
    # svc stayon usb only works when actively charging; background keepalive thread
    # sends KEYCODE_WAKEUP every 25 s as the primary mechanism, with max timeout as fallback.
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run_adb("shell", "svc", "power", "stayon", "usb")
    run_adb("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
    start_keepalive()

    # Warm up: send a dummy query to trigger model load, wait for NativeIntentHandler to fire.
    # Cold starts (or post-OOM reloads) can take 90-120s; poll for 120s before giving up.
    print("  [init] Warming up model (this takes ~30s on first run) ...", end=" ", flush=True)
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run_adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(3)
    clear_logcat()
    run_adb("shell", "am", "start", "-n", ACTIVITY, "--es", "chat_input", shlex.quote("what time is it"))
    # Poll logcat until NativeIntentHandler fires (model loaded + QIR dispatched) or 120s timeout
    deadline = time.time() + 120
    warmed = False
    while time.time() < deadline:
        time.sleep(2)
        log = read_logcat()
        if "NativeIntentHandler.handle" in log:
            warmed = True
            break
    if not warmed:
        # Model may still be loading (e.g. post-OOM reload). Send a second probe and
        # wait an additional 30s before giving up entirely.
        print("no response yet — sending second warmup probe ...", end=" ", flush=True)
        clear_logcat()
        run_adb("shell", "am", "start", "-n", ACTIVITY, "--es", "chat_input", shlex.quote("what time is it"))
        deadline2 = time.time() + 30
        while time.time() < deadline2:
            time.sleep(2)
            log = read_logcat()
            if "NativeIntentHandler.handle" in log:
                warmed = True
                break
    print("ready" if warmed else "timeout (proceeding anyway)")
    print()

    # Pre-run cleanup: silence any already-fired timers first, then cancel pending ones
    print("  [init] Cleaning up timers/alarms ...", end=" ", flush=True)
    for pkg in (
        "com.sec.android.app.clockpackage",
        "com.android.deskclock",
        "com.google.android.deskclock",
    ):
        run_adb("shell", "am", "force-stop", pkg)
    cleanup_side_effects()
    print("done")

    # Insert contact alias fixture for alias resolution tests
    print("  [init] Setting up contact alias fixture ...", end=" ", flush=True)
    setup_contact_alias_fixture()
    print("done")

    # ── Orchestrator warmup: wait for MiniLM phrase vectors to be fully built ──
    # Clear logcat first so we don't match a stale "Ready:" from a previous app run.
    clear_logcat()
    print("  [init] Warming up MiniLM classifier (up to 120s) ...", end=" ", flush=True)
    deadline = time.time() + 120
    warmed_ml = False
    while time.time() < deadline:
        time.sleep(3)
        log = read_logcat()
        if "Ready:" in log:
            warmed_ml = True
            break
    print("ready" if warmed_ml else "timeout (proceeding anyway)")

    # Flush any logcat residue from the cleanup intents before starting tests.
    time.sleep(WAIT_SECONDS)
    clear_logcat()
    time.sleep(1)
    print()

    phase_names = [name for name, _ in PHASES]

    # Resolve --phases: comma-separated list of phase names or 1-based numbers.
    # Builds a set of 0-based indices to include.
    selected_phase_indices: set[int] | None = None  # None = all phases
    if phases is not None:
        selected_phase_indices = set()
        for token in phases:
            token = token.strip()
            if token.isdigit():
                n = int(token)
                if not (1 <= n <= len(PHASES)):
                    print(
                        f"ERROR: --phases {token!r} out of range "
                        f"(1–{len(PHASES)}). Valid phases: {', '.join(f'{i+1}={n}' for i, (n, _) in enumerate(PHASES))}",
                        file=sys.stderr,
                    )
                    return 1
                selected_phase_indices.add(n - 1)
            else:
                if token not in phase_names:
                    print(
                        f"ERROR: --phases {token!r} not recognised. "
                        f"Valid phases: {', '.join(phase_names)}",
                        file=sys.stderr,
                    )
                    return 1
                selected_phase_indices.add(phase_names.index(token))
        selected_names = [phase_names[i] for i in sorted(selected_phase_indices)]
        print(f"  ── Running selected phases: {', '.join(selected_names)} ──")
        print()

    # Resolve --start-phase: accept a phase name or 1-based number.
    start_phase_idx = 0  # 0 = run all phases (0-based offset into PHASES)
    if start_phase is not None:
        if start_phase.isdigit():
            n = int(start_phase)
            if not (1 <= n <= len(PHASES)):
                print(
                    f"ERROR: --start-phase {start_phase!r} out of range "
                    f"(1–{len(PHASES)}; valid names: {', '.join(phase_names)})",
                    file=sys.stderr,
                )
                return 1
            start_phase_idx = n - 1
        else:
            if start_phase not in phase_names:
                print(
                    f"ERROR: --start-phase {start_phase!r} not found. "
                    f"Valid phases: {', '.join(phase_names)}",
                    file=sys.stderr,
                )
                return 1
            start_phase_idx = phase_names.index(start_phase)
        skipped = sum(len(cases) for _, cases in PHASES[:start_phase_idx])
        print(f"  ── Resuming from phase {start_phase_idx + 1}/{len(PHASES)}"
              f" ({PHASES[start_phase_idx][0]}) — skipping first {skipped} tests ──")
        print()

    # Build the filtered test list using _select_tests for composable selectors.
    # The phase filter is built from --phases (if given) or --start-phase (if given).
    run_phase_filter: list[str] | None = None
    if phases is not None:
        run_phase_filter = [phase_names[i] for i in sorted(selected_phase_indices)]  # type: ignore[union-attr]
    elif start_phase is not None:
        run_phase_filter = phase_names[start_phase_idx:]

    selected_tests = _select_tests(
        phases=PHASES,
        phase_filter=run_phase_filter,
        categories=categories,
        tags=tags,
        exclude_tags=exclude_tags,
        case_ids=case_ids,
    )

    # Print filter summary if any non-phase filter is active
    filter_parts: list[str] = []
    if categories:   filter_parts.append(f"categories={','.join(categories)}")
    if tags:         filter_parts.append(f"tags={','.join(tags)}")
    if exclude_tags: filter_parts.append(f"exclude_tags={','.join(exclude_tags)}")
    if case_ids:     filter_parts.append(f"case_ids={','.join(case_ids)}")
    if filter_parts:
        print(f"  Filters: {' | '.join(filter_parts)}")
        print()

    run_ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    suite_start = time.time()
    results: list[TestResult] = []

    total_tests = len(selected_tests)

    # Group selected tests by phase for the phase-results loop
    from itertools import groupby
    from operator import itemgetter
    for phase_idx, phase_group_iter in groupby(selected_tests, key=itemgetter(0)):
        phase_name = PHASES[phase_idx][0]
        phase_group = list(phase_group_iter)
        phase_start = time.time()
        phase_results: list[TestResult] = []

        for _phase_idx, _case_idx, tc in phase_group:
            global_index = len(results) + 1  # 1-based, runs only over selected tests
            print(f"  [{global_index:3d}/{total_tests}] \"{tc.message}\" ...", end=" ", flush=True)

            clear_logcat()
            time.sleep(0.5)  # Brief pause to ensure logcat clear is flushed before sending
            first_turn_warn: str | None = None
            if tc.slot_reply is not None:
                # Slot-fill test: two-turn flow
                # Turn 1: bare query via quick_action_input → NeedsSlot → ModalBottomSheet
                send_quick_action(tc.message)
                time.sleep(WAIT_SECONDS)
                # Capture first-turn logcat BEFORE clearing — check for NeedsSlot
                if tc.expect_initial_log_contains is not None:
                    logcat1 = read_logcat()
                    first_turn_ok = tc.expect_initial_log_contains in logcat1
                    if not first_turn_ok:
                        first_turn_warn = (
                            f"initial slot prompt '{tc.expect_initial_log_contains}' "
                            f"not found in first-turn logcat"
                        )
                # Turn 2: slot reply via slot_reply_input → ActionsViewModel.onSlotReply() → intent fires
                clear_logcat()
                time.sleep(0.5)
                send_slot_reply(tc.slot_reply)
            elif tc.confirm_reply is not None:
                # Confirmation test: two-turn flow (orchestrator AskConfirmation → user confirms)
                # Turn 1: query via chat_input → ChatViewModel → OrchTest override → orchestrator AskConfirmation
                clear_logcat()
                time.sleep(0.5)
                send_text(tc.message)
                time.sleep(WAIT_SECONDS)
                if tc.expect_log_contains is not None:
                    logcat1 = read_logcat()
                    log1_found = tc.expect_log_contains in logcat1
                    if not log1_found:
                        first_turn_warn = f"AskConfirmation not found (expected {tc.expect_log_contains!r})"
                # Turn 2: confirmation reply via chat_input → pending confirmation → skill executes
                clear_logcat()
                time.sleep(0.5)
                send_text(tc.confirm_reply)
            else:
                send_text(tc.message)

            # Early-exit wait: poll for expected signal instead of fixed WAIT_SECONDS (#1102)
            signal = tc.expect_log_contains or tc.expect_intent
            logcat = logcat_wait(signal, WAIT_SECONDS) if signal else (time.sleep(WAIT_SECONDS) or read_logcat())
            actual_intent, actual_params = extract_intent(logcat)
            intent_passed = (actual_intent or "") == tc.expect_intent
            params_ok, param_failures = check_params(tc.expect_params, actual_params)


            # DirectReply verification — best-effort, warn but don't fail the test
            reply_warn: str | None = None
            if intent_passed and tc.expect_reply_contains is not None:
                reply_text = extract_reply(logcat)
                if reply_text is None:
                    reply_warn = "no DirectReply logged"
                elif not re.search(tc.expect_reply_contains, reply_text):
                    reply_warn = f"reply {reply_text!r} didn't match {tc.expect_reply_contains!r}"
            # Logcat content check (for orchestrator paths that don't fire NativeIntentHandler)
            log_check_warn: str | None = None
            if tc.expect_log_contains is not None:
                if tc.expect_log_contains not in logcat:
                    log_check_warn = f"expected log '{tc.expect_log_contains}' not found"
            # Merge first-turn warning (e.g. AskConfirmation not found before confirm_reply)
            # into the final result so phase_results has exactly one entry per test.
            if first_turn_warn is not None:
                if log_check_warn is not None:
                    log_check_warn = first_turn_warn + "; " + log_check_warn
                else:
                    log_check_warn = first_turn_warn
            result = TestResult(
                index=global_index,
                message=tc.message,
                expect_intent=tc.expect_intent,
                actual_intent=actual_intent,
                expect_params=tc.expect_params,
                actual_params=actual_params,
                intent_passed=intent_passed,
                params_passed=params_ok,
                param_failures=param_failures,
                xfail=tc.xfail,
                reply_warn=reply_warn,
                log_check_warn=log_check_warn,
                first_turn_warn=first_turn_warn,
                phase=phase_name,
            )
            phase_results.append(result)
            results.append(result)
            global_index += 1

            # Determine pass/fail display
            warnings = []
            if reply_warn: warnings.append(f"reply warn: {reply_warn}")
            if log_check_warn: warnings.append(log_check_warn)
            warn_suffix = f" [{'; '.join(warnings)}]" if warnings else ""

            if intent_passed and not log_check_warn:
                print("✓" + warn_suffix)
            elif tc.xfail:
                print("✗ (xfail — not yet implemented)")
            elif not intent_passed:
                print(f"✗ (got {actual_intent or 'NO_MATCH'})" + warn_suffix)
            else:
                print(f"✗ (params: {'; '.join(param_failures)})")

            # Hang up after call tests so they don't stay open
            if tc.expect_intent == "make_call":
                time.sleep(2)
                run_adb("shell", "input", "keyevent", "KEYCODE_ENDCALL")

        # ── Phase summary ──────────────────────────────────────────────────
        phase_elapsed = time.time() - phase_start
        n_pass  = sum(1 for r in phase_results if r.intent_passed and r.params_passed and not r.xfail and not r.log_check_warn)
        n_xfail = sum(1 for r in phase_results if r.xfail and not r.intent_passed)
        n_fail  = sum(1 for r in phase_results if not r.xfail and (not r.intent_passed or not r.params_passed or r.log_check_warn is not None))
        print(
            f"  → {phase_name}: {n_pass} pass  {n_fail} fail  {n_xfail} xfail"
            f"  ({phase_elapsed:.1f}s)"
        )

        # OOM / model-reset sanity check (#554): warn if every test returned the same intent
        actual_intents = [r.actual_intent for r in phase_results if r.actual_intent]
        if len(actual_intents) > 1 and len(set(actual_intents)) == 1:
            print(
                f"  ⚠ WARNING: all {len(actual_intents)} tests in {phase_name}"
                f" returned '{actual_intents[0]}' — possible OOM/model reset"
            )

        # Incremental report save so results are never lost on abort
        save_report(
            results,
            suite="skills",
            elapsed=time.time() - suite_start,
            partial=True,
            run_ts=run_ts,
        )
        print()

    # Summary table
    print()
    print("-" * 70)
    print(f"  {'#':>3}  {'RESULT':>6}  {'EXPECTED':<24}  {'ACTUAL':<24}")
    print("-" * 70)

    failures = 0
    xfails = 0
    for r in results:
        if r.intent_passed and r.params_passed:
            icon = "  ✓"
        elif r.xfail:
            icon = "  ✗"
        else:
            icon = "  ✗"
        actual_str = r.actual_intent or "NO_MATCH"
        suffix = " (xfail)" if not r.intent_passed and r.xfail else ""
        if not r.intent_passed and not r.xfail:
            detail = actual_str
        elif not r.params_passed and not r.xfail:
            detail = f"{actual_str} [param fail]"
        else:
            detail = actual_str
        print(f"  {r.index:3d}  {icon:>6}  {r.expect_intent:<24}  {detail:<24}  \"{r.message}\"{suffix}")
        if not r.xfail and (not r.intent_passed or not r.params_passed or r.log_check_warn is not None):
            failures += 1
        elif r.xfail and not r.intent_passed:
            xfails += 1

    print("-" * 70)
    total = len(results)
    passed_count = total - failures - xfails
    print(f"  PASSED: {passed_count}/{total}  XFAIL: {xfails}/{total}  FAILED: {failures}/{total}")
    print("=" * 70)

    analyse_results(results)
    check_oom_sanity(results)
    report_path = save_report(
        results,
        suite="skills",
        elapsed=time.time() - suite_start,
        partial=False,
        run_ts=run_ts,
    )
    print(f"  Report saved → {report_path}")
    print()

    if post_pr:
        post_pr_comment(results, time.time() - suite_start, report_path)

    # Post-run cleanup: cancel any timers/alarms set during testing
    print()
    print("  [cleanup] Cancelling timers/alarms ...", end=" ", flush=True)
    cleanup_side_effects()
    print("done")
    print("  [cleanup] Removing contact alias fixture ...", end=" ", flush=True)
    teardown_contact_alias_fixture()
    print("done")
    print("  [cleanup] Restoring screen-timeout behaviour ...", end=" ", flush=True)
    stop_keepalive()
    run_adb("shell", "svc", "power", "stayon", "false")
    run_adb("shell", "settings", "put", "system", "screen_off_timeout", "60000")  # restore 60s
    print("done")

    return 1 if failures > 0 else 0




def run_profile_tests(dry_run: bool = False) -> int:
    """Execute all profile extraction test cases. Returns non-zero on failures."""
    if dry_run:
        print("=" * 70)
        print("  PROFILE EXTRACTION TEST — DRY RUN (no device interaction)")
        print("=" * 70)
        print()
        for i, tc in enumerate(PROFILE_TEST_CASES, 1):
            print(f"  [{i}] {tc.name}")
            print(f"       profile: {tc.profile_text[:60].replace(chr(10), ' ')}...")
            print(f"       expect_name={tc.expect_name}, "
                  f"role_contains={tc.expect_role_contains}, "
                  f"location_contains={tc.expect_location_contains}")
        print()
        print(f"  Total: {len(PROFILE_TEST_CASES)} profile test cases")
        print("=" * 70)
        return 0

    if not os.path.isfile(ADB):
        print(f"ERROR: ADB not found at {ADB}", file=sys.stderr)
        return 2

    logcat_start()

    print("=" * 70)
    print("  PROFILE EXTRACTION TEST")
    print("=" * 70)
    print()

    # Warm up: send a chat message and wait for the inference engine to load
    print("  Warming up engine ...", end=" ", flush=True)
    clear_logcat()
    send_text("hello")
    warm_start = time.time()
    warmed = False
    while time.time() - warm_start < 60:
        time.sleep(2)
        log = read_logcat_all()
        if "Generation complete" in log:
            warmed = True
            break
    print("ready" if warmed else "timeout (proceeding anyway)")
    print()

    results: list[TestResult] = []

    for i, tc in enumerate(PROFILE_TEST_CASES, 1):
        print(f"  [{i}/{len(PROFILE_TEST_CASES)}] {tc.name} ...", end=" ", flush=True)
        clear_logcat()
        send_profile(tc.profile_text)
        time.sleep(PROFILE_WAIT_SECONDS)
        logcat = read_logcat_all()
        extracted = extract_profile_result(logcat)

        passed = True
        if tc.expect_name and extracted["name"] != tc.expect_name:
            passed = False
        if tc.expect_role_contains and (
            not extracted["role"] or tc.expect_role_contains.lower() not in extracted["role"].lower()
        ):
            passed = False
        if tc.expect_location_contains and (
            not extracted["location"]
            or tc.expect_location_contains.lower() not in extracted["location"].lower()
        ):
            passed = False

        results.append(TestResult(
            index=i,
            message=tc.name,
            expect_intent=tc.name,
            actual_intent=extracted["method"],
            expect_params=None,
            actual_params={k: v for k, v in extracted.items() if v is not None},
            intent_passed=passed,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn=None,
        ))
        method_tag = f"[{extracted['method'] or 'NO_LOG'}]"
        print("✓" if passed else "✗", method_tag)

    # Summary
    print()
    print("-" * 70)
    failures = 0
    for r in results:
        icon = "  ✓" if r.intent_passed else "  ✗"
        print(f"  {icon}  {r.message}")
        print(f"       method={r.actual_intent}, "
              f"name={r.actual_params.get('name')!r}, "
              f"role={r.actual_params.get('role')!r}, "
              f"location={r.actual_params.get('location')!r}")
        if not r.intent_passed:
            failures += 1

    print("-" * 70)
    total = len(results)
    passed_count = total - failures
    print(f"  PASSED: {passed_count}/{total}  FAILED: {failures}/{total}")
    print("=" * 70)

    analyse_results(results)
    report_path = save_report(results, suite="profile")
    print(f"  Report saved → {report_path}")
    print()

    return 1 if failures > 0 else 0


def _fmt_elapsed(seconds: float) -> str:
    m, s = int(seconds) // 60, int(seconds) % 60
    return f"{m}m {s:02d}s" if m else f"{s}s"


def build_comment_markdown(
    results: list[TestResult],
    elapsed: float,
    report_path: Path,
) -> str:
    """Build a GitHub-flavoured markdown PR comment summarising the run."""
    total = len(results)
    passed = sum(1 for r in results if r.intent_passed and r.params_passed and not r.xfail)
    xfails = sum(1 for r in results if r.xfail and not r.intent_passed)
    failed = total - passed - xfails
    pass_rate = passed / max(total - xfails, 1) * 100

    lines: list[str] = [
        "## 🧪 Jandal Skill Regression Results",
        "",
        "| | Count |",
        "|---|---|",
        f"| ✅ Passed | {passed} |",
        f"| ❌ Failed | {failed} |",
        f"| ⚠️ Expected failures | {xfails} |",
        f"| **Total** | **{total}** |",
        "",
        f"**Pass rate: {pass_rate:.1f}%** • Run time: {_fmt_elapsed(elapsed)}",
    ]

    # Failed tests table (max 10 rows)
    failures = [
        r for r in results
        if not r.xfail and (not r.intent_passed or not r.params_passed)
    ]
    if failures:
        lines += [
            "",
            "### Failed tests",
            "| # | Input | Expected | Actual |",
            "|---|---|---|---|",
        ]
        for r in failures[:10]:
            actual = r.actual_intent or "NO_MATCH"
            lines.append(f"| {r.index} | {r.message} | `{r.expect_intent}` | `{actual}` |")
        if len(failures) > 10:
            lines.append(f"| … | *and {len(failures) - 10} more* | | |")

    # Phase breakdown (only when phase info is populated)
    phases_present = [r.phase for r in results if r.phase]
    if phases_present:
        from collections import defaultdict
        phase_data: dict[str, dict[str, float]] = defaultdict(
            lambda: {"pass": 0, "fail": 0, "xfail": 0, "time": 0.0}
        )
        for r in results:
            key = r.phase or "unknown"
            if r.xfail and not r.intent_passed:
                phase_data[key]["xfail"] += 1
            elif r.intent_passed and r.params_passed:
                phase_data[key]["pass"] += 1
            else:
                phase_data[key]["fail"] += 1

        lines += [
            "",
            "<details>",
            "<summary>Phase breakdown</summary>",
            "",
            "| Phase | Pass | Fail | XFail |",
            "|---|---|---|---|",
        ]
        for phase_name in dict.fromkeys(r.phase for r in results if r.phase):
            d = phase_data[phase_name]
            lines.append(
                f"| {phase_name} | {int(d['pass'])} | {int(d['fail'])} | {int(d['xfail'])} |"
            )
        lines += ["", "</details>"]

    dt = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    lines += [
        "",
        "---",
        f"*Report generated by [adb_skill_test.py](scripts/adb_skill_test.py) on {dt}*",
        f"*Full HTML report: `{report_path.name}`*",
    ]
    return "\n".join(lines)


def post_pr_comment(results: list[TestResult], elapsed: float, report_path: Path) -> None:
    """Post a markdown test summary to the open PR for the current branch."""
    body = build_comment_markdown(results, elapsed, report_path)
    result = subprocess.run(
        ["gh", "pr", "comment", "--body", body],
        capture_output=True, text=True, cwd=Path(__file__).parent.parent,
    )
    if result.returncode == 0:
        print("  [report] PR comment posted ✓")
    else:
        print(f"  [report] PR comment failed: {result.stderr.strip()}", file=sys.stderr)


