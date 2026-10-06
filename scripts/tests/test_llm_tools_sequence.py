#!/usr/bin/env python3
"""Host-side contract tests for ordered llm_tools discovery evidence (#1593)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from adb_harness.cases import LLM_TOOLS_CASES
from adb_harness.device import (
    extract_llm_tool_events,
    extract_llm_tool_sequence,
    validate_llm_tool_sequence,
)
from adb_harness.runners import (
    _contains_raw_tool_content,
    _extract_nested_intent,
    _extract_tool_chip_name,
    _parse_tool_marker,
)


def _discovery_log(
    *,
    load_result_type: str = "Success",
    returned_to_gemma: str = "true",
    terminal_result_type: str = "DirectReply",
) -> str:
    return "\n".join(
        (
            "event_seq: tool_call name=load_skill args={\"skill_name\":\"run_intent\"}",
            "event_seq: tool_result name=load_skill "
            f"resultType={load_result_type} directReply=false "
            f"returnedToGemma={returned_to_gemma} "
            "content=Available model-callable intents: private instruction text",
            "event_seq: tool_call name=run_intent args={\"intent_name\":\"get_list_items\"}",
            "event_seq: tool_result name=run_intent "
            f"resultType={terminal_result_type} directReply=true "
            "returnedToGemma=false content=Shopping list is empty",
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
        self.assertEqual([], validate_llm_tool_sequence(("load_skill", "run_intent"), sequence, events))
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
            "event", "name", "result_type", "direct_reply", "returned_to_gemma",
        }) for event in events))
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
            'request={"intent_name":"get_date_diff","parameters":{}}'
        )
        native_data = _parse_tool_marker(marker)

        self.assertEqual(
            "get_date_diff",
            _extract_nested_intent(native_data, {}),
        )
        self.assertEqual("legacy_action", _extract_nested_intent({}, {"nested_intent": "legacy_action"}))

    def test_tool_chip_parser_extracts_value_from_chatviewmodel_marker(self) -> None:
        log_line = (
            "10-06 10:00:00.000 1234 5678 D KernelAI: "
            "tool_chip_visible: tool=run_intent"
        )

        self.assertEqual("run_intent", _extract_tool_chip_name(log_line))

    def test_user_reply_guard_blocks_protocol_but_allows_plain_language(self) -> None:
        self.assertFalse(_contains_raw_tool_content("Your stopwatch is not running."))
        self.assertTrue(_contains_raw_tool_content("<|tool_call|>call:run_intent{}"))
        self.assertTrue(_contains_raw_tool_content("Available model-callable intents: set_timer"))
        self.assertTrue(_contains_raw_tool_content('load_skill("run_intent")'))

    def test_llm_cases_cover_direct_discovery_chain_and_dedicated_tool(self) -> None:
        cases = {case.name: case for case in LLM_TOOLS_CASES}

        self.assertEqual(
            ("run_intent",),
            cases["run_intent_date_diff_direct"].expected_tool_sequence,
        )
        self.assertEqual("get_date_diff", cases["run_intent_date_diff_direct"].expected_nested_intent)
        self.assertEqual("run_intent", cases["run_intent_date_diff_direct"].expected_top_level_tool)
        self.assertTrue(cases["run_intent_date_diff_direct"].expect_no_regex_match)
        self.assertTrue(cases["run_intent_date_diff_direct"].expect_no_classifier_match)
        self.assertEqual("direct_reply", cases["run_intent_date_diff_direct"].expected_result_mode)
        self.assertTrue(cases["run_intent_date_diff_direct"].expect_no_slot_fill)
        self.assertTrue(cases["run_intent_date_diff_direct"].expect_no_retry)
        self.assertEqual(
            ("load_skill", "run_intent"),
            cases["run_intent_get_list_items_after_skill_load"].expected_tool_sequence,
        )
        self.assertEqual(
            "get_list_items",
            cases["run_intent_get_list_items_after_skill_load"].expected_nested_intent,
        )
        self.assertEqual("run_intent", cases["run_intent_get_list_items_after_skill_load"].expected_top_level_tool)
        discovery = cases["run_intent_get_list_items_after_skill_load"]
        self.assertTrue(discovery.expect_no_regex_match)
        self.assertTrue(discovery.expect_no_classifier_match)
        self.assertTrue(discovery.expect_no_slot_fill)
        self.assertTrue(discovery.expect_no_retry)
        self.assertEqual("direct_reply", discovery.expected_result_mode)
        self.assertEqual(
            ("get_system_info",),
            cases["get_system_info_natural"].expected_tool_sequence,
        )
        self.assertEqual("get_system_info", cases["get_system_info_natural"].expected_top_level_tool)
        self.assertEqual("direct_reply", cases["get_system_info_natural"].expected_result_mode)

if __name__ == "__main__":
    unittest.main()
