#!/usr/bin/env python3
"""Host-side contract tests for ordered llm_tools discovery evidence (#1593)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from adb_harness.cases import LLM_TOOLS_CASES
from adb_harness.device import (
    extract_llm_tool_events,
    extract_llm_tool_sequence,
    validate_llm_tool_sequence,
)
from adb_harness.config import LLM_TOOLS_ROUTE_PATTERN
from adb_harness.runners import (
    _contains_raw_tool_content,
    _extract_nested_intent,
    _extract_tool_chip_name,
    _parse_tool_marker,
    _poll_for_all_markers,
    _route_marker_is_fallthrough,
    _case_input_failures,
    _message_persistence_failure,
    _missing_reply_terms,
)


def _discovery_log(
    *,
    load_skill_name: str = "run_intent",
    run_intent_name: str = "get_date",
    load_result_type: str = "Success",
    returned_to_gemma: str = "true",
    terminal_result_type: str = "DirectReply",
) -> str:
    return "\n".join(
        (
            f'event_seq: tool_call name=load_skill args={{"skill_name":"{load_skill_name}"}}',
            "event_seq: tool_result name=load_skill "
            f"resultType={load_result_type} directReply=false "
            f"returnedToGemma={returned_to_gemma} "
            "content=Available model-callable intents: private instruction text",
            f'event_seq: tool_call name=run_intent args={{"intent_name":"{run_intent_name}"}}',
            "event_seq: tool_result name=run_intent "
            f"resultType={terminal_result_type} directReply=true "
            "returnedToGemma=false content=It's 12:00 on Monday, 1 June 2026.",
            "llm_tools_tool_sequence: attempt=load_skill turn=load_skill terminal=load_skill",
            "llm_tools_tool_sequence: attempt=run_intent "
            "turn=load_skill>run_intent terminal=run_intent",
        )
    )


class LLMToolsSequenceEvidenceTest(unittest.TestCase):
    def test_discovery_chain_uses_last_turn_marker_and_validates(self) -> None:
        log = _discovery_log()
        sequence, marker = extract_llm_tool_sequence(log)
        events = extract_llm_tool_events(log)

        self.assertEqual(["load_skill", "run_intent"], sequence)
        self.assertIn("turn=load_skill>run_intent", marker or "")
        self.assertEqual(
            [],
            validate_llm_tool_sequence(
                ("load_skill", "run_intent"),
                sequence,
                events,
                expected_load_skill_name="run_intent",
                expected_nested_intent="get_date",
            ),
        )
        latest_malformed = log + "\nllm_tools_tool_sequence: attempt=run_intent terminal=run_intent"
        malformed_sequence, malformed_marker = extract_llm_tool_sequence(latest_malformed)
        self.assertEqual([], malformed_sequence)
        self.assertIn("attempt=run_intent", malformed_marker or "")
        self.assertEqual(
            ["load_skill", "run_intent"],
            [event["name"] for event in events if event["event"] == "tool_call"],
        )

    def test_event_evidence_excludes_arguments_and_instruction_content(self) -> None:
        events = extract_llm_tool_events(_discovery_log())

        self.assertEqual(4, len(events))
        self.assertTrue(all(set(event).issubset({
            "event", "name", "skill_name", "intent_name", "result_type", "direct_reply", "returned_to_gemma",
        }) for event in events))
        self.assertEqual("run_intent", events[0]["skill_name"])
        self.assertEqual("get_date", events[2]["intent_name"])
        self.assertNotIn("content", repr(events))
        self.assertNotIn("args", repr(events))
        self.assertTrue(events[1]["returned_to_gemma"])

    def test_failed_or_non_returning_skill_load_fails_contract(self) -> None:
        events = extract_llm_tool_events(
            _discovery_log(load_result_type="Failure", returned_to_gemma="false")
        )
        failures = validate_llm_tool_sequence(
            ("load_skill", "run_intent"), ["load_skill", "run_intent"], events
        )

        self.assertIn("load_skill result was not successful", failures)
        self.assertIn("load_skill result did not return to Gemma", failures)

    def test_wrong_loaded_skill_fails_contract(self) -> None:
        log = _discovery_log(load_skill_name="meal_planner")
        sequence, _ = extract_llm_tool_sequence(log)
        events = extract_llm_tool_events(log)

        failures = validate_llm_tool_sequence(
            ("load_skill", "run_intent"),
            sequence,
            events,
            expected_load_skill_name="run_intent",
        )

        self.assertIn(
            "load_skill target: expected 'run_intent', got 'meal_planner'",
            failures,
        )

    def test_wrong_run_intent_action_fails_contract(self) -> None:
        log = _discovery_log(run_intent_name="get_date_diff")
        sequence, _ = extract_llm_tool_sequence(log)
        events = extract_llm_tool_events(log)

        failures = validate_llm_tool_sequence(
            ("load_skill", "run_intent"),
            sequence,
            events,
            expected_load_skill_name="run_intent",
            expected_nested_intent="get_date",
        )

        self.assertIn(
            "run_intent action: expected 'get_date', got 'get_date_diff'",
            failures,
        )

    def test_all_required_reply_terms_are_checked(self) -> None:
        self.assertEqual(
            [],
            _missing_reply_terms("It's 12:00 on Monday, 1 June 2026.", ["It's", "on"]),
        )
        self.assertEqual(
            ["on"],
            _missing_reply_terms("It's 12:00", ["It's", "on"]),
        )

    def test_reordered_calls_fail_contract(self) -> None:
        events = [
            {"event": "tool_call", "name": "run_intent"},
            {"event": "tool_result", "name": "run_intent", "result_type": "Failure"},
            {"event": "tool_call", "name": "load_skill"},
            {
                "event": "tool_result",
                "name": "load_skill",
                "result_type": "Success",
                "returned_to_gemma": True,
            },
        ]
        failures = validate_llm_tool_sequence(
            ("load_skill", "run_intent"), ["run_intent", "load_skill"], events
        )

        self.assertTrue(any("turn tool sequence" in failure for failure in failures))
        self.assertTrue(any("tool_call order" in failure for failure in failures))

    def test_failed_terminal_result_fails_contract(self) -> None:
        events = [
            {"event": "tool_call", "name": "load_skill"},
            {
                "event": "tool_result",
                "name": "load_skill",
                "result_type": "Success",
                "returned_to_gemma": True,
            },
            {"event": "tool_call", "name": "run_intent"},
            {"event": "tool_result", "name": "run_intent", "result_type": "Failure"},
        ]
        failures = validate_llm_tool_sequence(
            ("load_skill", "run_intent"), ["load_skill", "run_intent"], events
        )

        self.assertIn("terminal tool run_intent did not succeed", failures)

    def test_missing_terminal_result_fails_contract(self) -> None:
        events = [
            {"event": "tool_call", "name": "load_skill"},
            {
                "event": "tool_result",
                "name": "load_skill",
                "result_type": "Success",
                "returned_to_gemma": True,
            },
            {"event": "tool_call", "name": "run_intent"},
        ]
        failures = validate_llm_tool_sequence(
            ("load_skill", "run_intent"), ["load_skill", "run_intent"], events
        )

        self.assertIn("missing tool_result for run_intent", failures)
        self.assertTrue(any("tool_result order" in failure for failure in failures))

    def test_direct_reply_requires_direct_reply_flag(self) -> None:
        events = [
            {"event": "tool_call", "name": "run_intent"},
            {
                "event": "tool_result",
                "name": "run_intent",
                "result_type": "DirectReply",
                "direct_reply": False,
            },
        ]
        failures = validate_llm_tool_sequence(
            ("run_intent",), ["run_intent"], events
        )

        self.assertIn("terminal tool run_intent did not report a direct reply", failures)

    def test_direct_run_intent_reads_intent_name_from_native_request(self) -> None:
        marker = (
            'tool=run_intent '
            'request={"intent_name":"get_date","parameters":{}}'
        )
        native_data = _parse_tool_marker(marker)

        self.assertEqual(
            "get_date",
            _extract_nested_intent(native_data, {}),
        )
        self.assertEqual("legacy_action", _extract_nested_intent({}, {"nested_intent": "legacy_action"}))

    def test_tool_chip_parser_extracts_value_from_chatviewmodel_marker(self) -> None:
        log_line = (
            "10-06 10:00:00.000 1234 5678 D KernelAI: "
            "tool_chip_visible: tool=run_intent"
        )

        self.assertEqual("run_intent", _extract_tool_chip_name(log_line))

    def test_safe_run_intent_probe_requires_ephemeral_persistence_policy(self) -> None:
        safe_case = next(case for case in LLM_TOOLS_CASES if case.safe_run_intent_test)
        normal_case = next(case for case in LLM_TOOLS_CASES if not case.safe_run_intent_test)

        self.assertIsNone(_message_persistence_failure(safe_case, None))
        self.assertEqual(
            "Safe run-intent test unexpectedly persisted a chat message",
            _message_persistence_failure(safe_case, "id=synthetic tool=run_intent"),
        )
        self.assertIsNone(_message_persistence_failure(normal_case, "id=synthetic tool=query_wikipedia"))
        self.assertEqual(
            "No ChatMessage.toolCall persistence marker found",
            _message_persistence_failure(normal_case, None),
        )

    def test_user_reply_guard_blocks_protocol_but_allows_plain_language(self) -> None:
        self.assertFalse(_contains_raw_tool_content("Your stopwatch is not running."))
        self.assertTrue(_contains_raw_tool_content("<|tool_call|>call:run_intent{}"))
        self.assertTrue(_contains_raw_tool_content("Available model-callable intents: set_timer"))
        self.assertTrue(_contains_raw_tool_content('load_skill("run_intent")'))

    def test_native_handler_log_is_not_mistaken_for_qir_route(self) -> None:
        log = "\n".join((
            "llm_tools_route: result=fallthrough best_guess=null confidence=0.0",
            "NativeIntentHandler.handle: intent=get_date",
            "llm_tools_native_tool: tool=run_intent",
        ))
        route_match = LLM_TOOLS_ROUTE_PATTERN.search(log)
        route_marker = route_match.group(1) if route_match else None

        self.assertTrue(_route_marker_is_fallthrough(route_marker))
        self.assertFalse(
            _route_marker_is_fallthrough(
                "result=classified best_guess=get_date confidence=0.9"
            )
        )
        self.assertFalse(_route_marker_is_fallthrough(None))

    def test_case_input_guard_accepts_exact_current_prompt(self) -> None:
        prompt = next(
            case.message
            for case in LLM_TOOLS_CASES
            if case.name == "run_intent_get_date_direct"
        )
        self.assertGreater(len(prompt), 120)

        log = (
            f"ADB_INTENT_TRACE commandId=case-1 input={prompt[:120]} "
            "submitMode=Text"
        )

        self.assertEqual([], _case_input_failures(log, prompt))

    def test_case_input_guard_rejects_foreign_concurrent_prompt(self) -> None:
        prompt = next(
            case.message
            for case in LLM_TOOLS_CASES
            if case.name == "run_intent_get_date_direct"
        )
        log = "\n".join((
            f"ADB_INTENT_TRACE commandId=case-1 input={prompt} submitMode=Text",
            "ADB_INTENT_TRACE commandId=other-1 input=what time is it submitMode=Text",
        ))

        self.assertIn(
            "Multiple ADB_INTENT_TRACE prompt markers found after case boundary; "
            "another device input may have contaminated this case",
            _case_input_failures(log, prompt),
        )

    def test_case_input_guard_rejects_missing_or_mismatched_prompt(self) -> None:
        self.assertEqual(
            ["No ADB_INTENT_TRACE prompt marker found after case boundary"],
            _case_input_failures("", "expected"),
        )
        self.assertEqual(
            ["ADB_INTENT_TRACE prompt does not match the selected case"],
            _case_input_failures(
                "ADB_INTENT_TRACE commandId=other-1 input=other prompt submitMode=Text",
                "expected prompt",
            ),
        )

    def test_llm_cases_cover_direct_discovery_chain_and_dedicated_tool(self) -> None:
        cases = {case.name: case for case in LLM_TOOLS_CASES}

        direct = cases["run_intent_get_date_direct"]
        self.assertEqual(("run_intent",), direct.expected_tool_sequence)
        self.assertEqual("get_date", direct.expected_nested_intent)
        self.assertEqual("run_intent", direct.expected_top_level_tool)
        self.assertTrue(direct.safe_run_intent_test)
        self.assertTrue(direct.expect_no_regex_match)
        self.assertTrue(direct.expect_no_classifier_match)
        self.assertTrue(direct.expect_no_slot_fill)
        self.assertTrue(direct.expect_no_retry)
        self.assertEqual("direct_reply", direct.expected_result_mode)
        self.assertEqual(["It's", "on"], direct.expected_reply_contains_all)

        discovery = cases["run_intent_get_date_after_skill_load"]
        self.assertEqual(("load_skill", "run_intent"), discovery.expected_tool_sequence)
        self.assertEqual("run_intent", discovery.expected_load_skill_name)
        self.assertEqual("get_date", discovery.expected_nested_intent)
        self.assertEqual("run_intent", discovery.expected_top_level_tool)
        self.assertTrue(discovery.safe_run_intent_test)
        self.assertTrue(discovery.expect_no_regex_match)
        self.assertTrue(discovery.expect_no_classifier_match)
        self.assertTrue(discovery.expect_no_slot_fill)
        self.assertTrue(discovery.expect_no_retry)
        self.assertEqual("direct_reply", discovery.expected_result_mode)
        self.assertEqual(["It's", "on"], discovery.expected_reply_contains_all)

        dedicated = cases["get_system_info_natural"]
        self.assertEqual(("get_system_info",), dedicated.expected_tool_sequence)
        self.assertEqual("get_system_info", dedicated.expected_top_level_tool)
        self.assertEqual("direct_reply", dedicated.expected_result_mode)

        self.assertTrue(all(
            case.expect_no_regex_match and case.expect_no_classifier_match
            for case in (direct, discovery)
        ))
        self.assertIn("get_date", direct.message)
        self.assertIn("read the run_intent instructions", discovery.message)
        self.assertNotIn("bulk_add_to_list", repr((direct.message, discovery.message)).lower())
        self.assertNotIn("shopping list", direct.message.lower())
        self.assertFalse(any(token in repr((direct.message, discovery.message)).lower()
                             for token in ("<|tool_call|>", 'load_skill("run_intent")',
                                           "available model-callable intents")))

    def test_marker_poll_uses_synchronized_boundary_prefix(self) -> None:
        boundary = "__LLM_TOOLS_CASE_BOUNDARY_123__"
        boundary_logcat = "\n".join(
            (
                "llm_tools_route: result=classified intent=get_time",
                "llm_tools_assistant_reply: It's 4:40 pm",
                boundary,
            )
        )
        current_logcat = "\n".join(
            (
                "llm_tools_route: result=fallthrough best_guess=null confidence=0.0",
                "llm_tools_native_tool: tool=run_intent",
            )
        )
        with patch("adb_harness.runners.time.sleep"), patch(
            "adb_harness.runners.read_logcat_all", return_value=current_logcat
        ), patch("adb_harness.runners.run_adb"):
            markers, fresh_log = _poll_for_all_markers(
                {"route": LLM_TOOLS_ROUTE_PATTERN},
                timeout=1,
                poll_interval=0,
                boundary=boundary,
                initial_logcat=boundary_logcat,
            )

        self.assertIn("fallthrough", markers["route"] or "")
        self.assertNotIn("get_time", fresh_log)
        self.assertNotIn("4:40 pm", fresh_log)
        self.assertIn("llm_tools_native_tool: tool=run_intent", fresh_log)

    def test_marker_poll_stops_on_foreign_prompt_after_boundary(self) -> None:
        boundary = "__LLM_TOOLS_CASE_BOUNDARY_123__"
        foreign_prompt = (
            "ADB_INTENT_TRACE commandId=other-1 input=what time is it submitMode=Text"
        )

        with patch(
            "adb_harness.runners.time.time",
            side_effect=[100.0, 100.0, 111.0],
        ) as clock, patch("adb_harness.runners.time.sleep"), patch(
            "adb_harness.runners.read_logcat_all",
            return_value=foreign_prompt,
        ) as read_logcat, patch("adb_harness.runners.run_adb") as run_adb:
            markers, fresh_log = _poll_for_all_markers(
                {"route": LLM_TOOLS_ROUTE_PATTERN},
                timeout=10,
                poll_interval=0,
                boundary=boundary,
                initial_logcat=boundary,
                expected_message="the selected prompt",
            )

        self.assertIsNone(markers["route"])
        self.assertIn("input=what time is it", fresh_log)
        self.assertEqual(1, read_logcat.call_count)
        self.assertEqual(2, clock.call_count)
        run_adb.assert_not_called()

    def test_marker_poll_fails_closed_without_boundary(self) -> None:
        log = "llm_tools_route: result=classified intent=get_time"
        with patch("adb_harness.runners.time.sleep"), patch(
            "adb_harness.runners.read_logcat_all", return_value=log
        ), patch("adb_harness.runners.run_adb"):
            markers, fresh_log = _poll_for_all_markers(
                {"route": LLM_TOOLS_ROUTE_PATTERN},
                timeout=1,
                poll_interval=0,
                boundary="__LLM_TOOLS_CASE_BOUNDARY_missing__",
            )

        self.assertIsNone(markers["route"])
        self.assertEqual("__BOUNDARY_NOT_FOUND__", fresh_log)

if __name__ == "__main__":
    unittest.main()
