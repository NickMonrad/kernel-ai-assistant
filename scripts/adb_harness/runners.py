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
import json
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
    _keep_foreground_until_inference_starts,
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


