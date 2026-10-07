"""Local-only full transcripts for real llm_tools harness cases.

These files contain prompts, thinking, tool arguments, and results. Keep them under the
ignored test-reports tree; never add them to public JSON or Markdown reporting.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import re
from typing import Any, Mapping

from adb_harness.config import PACKAGE, REPORTS_DIR
from adb_harness.device import run_adb_checked


TRANSCRIPT_PROVIDER_URI = "content://com.kernel.ai.debug.local-llm-tools-transcript"
TRANSCRIPT_CACHE_FILE = "cache/llm_tools_local_transcript.json"
LOCAL_TRANSCRIPTS_DIR = REPORTS_DIR / "local_llm_tools_diagnostics"
_METHOD_BEGIN = "begin_local_transcript"
_METHOD_EXPORT = "export_local_transcript"
_METHOD_CLEAR = "clear_local_transcript"


def build_local_llm_tools_transcript(
    android_transcript: Mapping[str, Any],
    *,
    case_index: int,
    case_name: str,
    harness_prompt: str,
    outcome: Mapping[str, Any],
) -> dict[str, Any]:
    """Wrap the unredacted debug-provider snapshot without truncating its contents."""
    if not isinstance(android_transcript.get("messages"), list):
        raise ValueError("debug transcript is missing persisted messages")
    if not isinstance(android_transcript.get("tool_calls"), list):
        raise ValueError("debug transcript is missing tool calls")
    return {
        "schema_version": 1,
        "privacy": "local_only",
        "case": {
            "index": case_index,
            "name": case_name,
            "harness_prompt": harness_prompt,
        },
        "outcome": dict(outcome),
        "conversation": dict(android_transcript),
    }


def begin_local_llm_tools_capture() -> bool:
    """Arm the debug-only provider; failure never changes case pass/fail semantics."""
    return _device_provider_call(_METHOD_BEGIN)


def save_local_llm_tools_transcript(
    *,
    case_index: int,
    case_name: str,
    harness_prompt: str,
    outcome: Mapping[str, Any],
) -> Path | None:
    """Export one full local transcript and clear the app-private temporary copy."""
    try:
        if not _device_provider_call(_METHOD_EXPORT):
            return None
        success, payload = run_adb_checked(
            "shell",
            "run-as",
            PACKAGE,
            "cat",
            TRANSCRIPT_CACHE_FILE,
            timeout=30.0,
        )
        if not success:
            return None
        android_transcript = json.loads(payload)
        transcript = build_local_llm_tools_transcript(
            android_transcript,
            case_index=case_index,
            case_name=case_name,
            harness_prompt=harness_prompt,
            outcome=outcome,
        )
        LOCAL_TRANSCRIPTS_DIR.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
        safe_case_name = re.sub(r"[^A-Za-z0-9_-]+", "-", case_name).strip("-").lower()[:80]
        filename = f"local_llm_tools_{timestamp}_{case_index:02d}_{safe_case_name or 'case'}.json"
        path = LOCAL_TRANSCRIPTS_DIR / filename
        path.write_text(json.dumps(transcript, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return path
    except (OSError, TypeError, ValueError, json.JSONDecodeError):
        return None
    finally:
        _device_provider_call(_METHOD_CLEAR)


def _device_provider_call(method: str) -> bool:
    success, output = run_adb_checked(
        "shell",
        "content",
        "call",
        "--uri",
        TRANSCRIPT_PROVIDER_URI,
        "--method",
        method,
        timeout=30.0,
    )
    return success and re.search(r"\bok=true\b", output) is not None
