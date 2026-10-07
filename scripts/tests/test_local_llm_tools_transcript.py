#!/usr/bin/env python3
"""Host tests for private full llm_tools transcripts and public evidence redaction."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import call, patch

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from adb_harness import local_transcripts  # noqa: E402
from summarise_test_report import normalise_case, write_markdown  # noqa: E402


class LocalTranscriptTest(unittest.TestCase):
    def test_saved_transcript_preserves_full_conversation_and_ordered_tool_chain(self) -> None:
        prompt = "LOCAL_USER_PROMPT_雪_" + ("p" * 420)
        thinking = "FULL_PERSISTED_THINKING_" + ("t" * 900)
        payload = '{"query":"RAW_ARGUMENT_雪_' + ("a" * 600) + '"}'
        tool_result = "FULL_TOOL_RESULT_" + ("r" * 950)
        final_response = "FINAL_VISIBLE_RESPONSE_" + ("f" * 500)
        persisted_tool_call = json.dumps(
            {"skillName": "query_wikipedia", "request": payload, "result": tool_result},
            ensure_ascii=False,
        )
        calls = [
            {
                "order": 0,
                "name": "load_skill",
                "arguments": {"skill_name": "query_wikipedia"},
                "terminal": False,
                "result_type": "Success",
                "result_content": tool_result,
                "tool_result": {"result": tool_result},
                "succeeded": True,
                "direct_reply": False,
                "returned_to_gemma": True,
            },
            {
                "order": 1,
                "name": "query_wikipedia",
                "arguments": {"query": payload},
                "terminal": True,
                "result_type": "DirectReply",
                "result_content": tool_result,
                "tool_result": {"result": tool_result},
                "succeeded": True,
                "direct_reply": True,
                "returned_to_gemma": False,
            },
        ]
        android_transcript = {
            "schema_version": 1,
            "conversation_id": "conversation-1",
            "user_prompt": prompt,
            "final_visible_response": final_response,
            "messages": [
                {
                    "id": "user-1",
                    "conversation_id": "conversation-1",
                    "role": "user",
                    "content": prompt,
                    "thinking_text": None,
                    "timestamp": 100,
                    "tool_call_json": None,
                },
                {
                    "id": "assistant-1",
                    "conversation_id": "conversation-1",
                    "role": "assistant",
                    "content": final_response,
                    "thinking_text": thinking,
                    "timestamp": 200,
                    "tool_call_json": persisted_tool_call,
                },
            ],
            "tool_calls": calls,
            "terminal_call": calls[-1],
        }
        outcome = {"passed": True, "harness_reply_text": final_response}

        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)
            with (
                patch.object(local_transcripts, "LOCAL_TRANSCRIPTS_DIR", output_dir),
                patch.object(
                    local_transcripts,
                    "_device_provider_call",
                    side_effect=[True, True],
                ) as provider_call,
                patch.object(
                    local_transcripts,
                    "run_adb_checked",
                    return_value=(True, json.dumps(android_transcript, ensure_ascii=False)),
                ),
            ):
                path = local_transcripts.save_local_llm_tools_transcript(
                    case_index=3,
                    case_name="query_wikipedia_natural",
                    harness_prompt=prompt,
                    outcome=outcome,
                )

            self.assertIsNotNone(path)
            assert path is not None
            self.assertEqual(output_dir, path.parent)
            self.assertFalse(path.name.endswith("_llm_tools.json"))
            self.assertEqual(
                [
                    call("export_local_transcript"),
                    call("clear_local_transcript"),
                ],
                provider_call.call_args_list,
            )
            saved = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual("local_only", saved["privacy"])
        self.assertEqual(prompt, saved["case"]["harness_prompt"])
        conversation = saved["conversation"]
        self.assertEqual(prompt, conversation["messages"][0]["content"])
        self.assertEqual(thinking, conversation["messages"][1]["thinking_text"])
        self.assertEqual(persisted_tool_call, conversation["messages"][1]["tool_call_json"])
        self.assertEqual(
            ["load_skill", "query_wikipedia"],
            [call["name"] for call in conversation["tool_calls"]],
        )
        self.assertEqual(payload, conversation["tool_calls"][1]["arguments"]["query"])
        self.assertEqual(tool_result, conversation["tool_calls"][1]["result_content"])
        self.assertEqual(tool_result, conversation["tool_calls"][1]["tool_result"]["result"])
        self.assertTrue(conversation["terminal_call"]["direct_reply"])
        self.assertFalse(conversation["terminal_call"]["returned_to_gemma"])
        self.assertEqual(final_response, conversation["final_visible_response"])


class SanitizedReportRedactionTest(unittest.TestCase):
    def test_private_transcript_fields_do_not_enter_public_json_or_markdown(self) -> None:
        private_values = (
            "PRIVATE_PROMPT_SENTINEL",
            "PRIVATE_THINKING_SENTINEL",
            "PRIVATE_ARGUMENT_SENTINEL",
            "PRIVATE_TOOL_RESULT_SENTINEL",
            "PRIVATE_FINAL_RESPONSE_SENTINEL",
        )
        raw_case = {
            "name": "query_wikipedia_natural",
            "passed": True,
            "message": private_values[0],
            "thinking_text": private_values[1],
            "raw_tool_call_arguments": private_values[2],
            "native_tool_marker": private_values[2],
            "legacy_tool_marker": private_values[2],
            "skill_result_marker": f"mode=success result={private_values[3]}",
            "message_saved_marker": private_values[3],
            "reply_text": private_values[4],
            "expected_top_level_tool": "query_wikipedia",
            "actual_top_level_tool": "query_wikipedia",
            "chip_text": "query_wikipedia",
            "retry_seen": False,
            "slot_fill_seen": False,
        }
        public_case = normalise_case(raw_case, "success")
        self.assertEqual(
            {
                "name",
                "passed",
                "expected_tool",
                "actual_tool",
                "expected_result_mode",
                "actual_result_mode",
                "chip_present",
                "skill_result_present",
                "message_saved",
                "retry_seen",
                "slot_fill_seen",
                "failure_category",
                "failures",
            },
            set(public_case),
        )
        public_json = json.dumps(public_case)
        for private_value in private_values:
            self.assertNotIn(private_value, public_json)

        normalised_report = {
            "source": "on_device",
            "commit": "a" * 40,
            "branch": "feature/1611-local-llm-transcripts",
            "suite": "llm_tools",
            "pr": None,
            "timestamp": "2026-09-01T00:00:00Z",
            "run_id": "run-1",
            "device": {"id": "test-device", "label": "Test device", "soc": "Test SoC", "android_api": 35, "tier": "tracked"},
            "model": {"name": "Gemma", "runtime": "LiteRT", "backend": "GPU"},
            "summary": {"total": 1, "passed": 1, "failed": 0, "pass_rate": 1.0},
            "cases": [public_case],
        }
        public_json = json.dumps(normalised_report)
        for private_value in private_values:
            self.assertNotIn(private_value, public_json)
        with tempfile.TemporaryDirectory() as temp_dir:
            markdown_path = Path(temp_dir) / "summary.md"
            write_markdown(normalised_report, markdown_path)
            public_markdown = markdown_path.read_text(encoding="utf-8")

        for private_value in private_values:
            self.assertNotIn(private_value, public_markdown)


if __name__ == "__main__":
    unittest.main()
