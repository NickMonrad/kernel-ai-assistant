"""
ADB Skill Harness — configuration constants.

All path, timeout, and pattern constants used across the harness modules.
"""

from __future__ import annotations

import os
import re
import shutil
from pathlib import Path


# ── Device / app paths ──
ADB = (
    os.environ.get("ADB_PATH")
    or shutil.which("adb")
    or os.path.expanduser("~/Android/Sdk/platform-tools/adb")
    or "/usr/bin/adb"
)
PACKAGE = "com.kernel.ai.debug"
ACTIVITY = f"{PACKAGE}/com.kernel.ai.MainActivity"

# ── Static patterns ──
LLM_TOOLS_ROUTE_PATTERN = re.compile(r"llm_tools_route:\s*(.+)")
LLM_TOOLS_NATIVE_TOOL_PATTERN = re.compile(r"llm_tools_native_tool:\s*(.+)")
LLM_TOOLS_LEGACY_TOOL_PATTERN = re.compile(r"llm_tools_legacy_tool:\s*(.+)")
LLM_TOOLS_SKILL_RESULT_PATTERN = re.compile(r"llm_tools_skill_result:\s*(.+)")
LLM_TOOLS_MESSAGE_SAVED_PATTERN = re.compile(r"llm_tools_message_toolcall_saved:\s*(.+)")
LLM_TOOLS_RETRY_PATTERN = re.compile(r"raw_tool_call_retry_succeeded|hallucination_retry_succeeded")
LLM_TOOLS_SLOT_FILL_PATTERN = re.compile(r"NeedsSlot|ConfirmationFastPath:")
LOGCAT_TAG = "KernelAI"
INTENT_MATCH_PATTERN = re.compile(r"llm_tools_route:\s*(\w+)")
NATIVE_INTENT_PATTERN = re.compile(r"NativeIntentHandler\.handle: intent=([^\s]+)\s+params=\{(.*?)\}")
NATIVE_INTENT_NAME_PATTERN = re.compile(r"NativeIntentHandler\.handle: intent=(\S+)")
PARAM_EXTRACT_PATTERN = re.compile(r"(\w+)=([^,}]+)")
DIRECT_REPLY_PATTERN = re.compile(r"DirectReply:\s*(.*)")
LLM_TOOLS_ASSISTANT_REPLY_PATTERN = re.compile(r"llm_tools_assistant_reply:\s*(.*)")
MARKER_TIMEOUT_PATTERN = re.compile(r"llm_tools_response_ready|llm_tools_native_handler_started")
SLOT_FILL_MARKERS = re.compile(r"NeedsSlot|ConfirmationFastPath:")
PROFILE_LLM_PATTERN = re.compile(r"ProfileExtraction\s+method=llm")
PROFILE_FALLBACK_PATTERN = re.compile(
    r"ProfileExtraction\s+method=regex|"
    r"ProfileExtraction\s+method=fallback"
)
PROFILE_RESULT_PATTERN = re.compile(r"llm_tools_skill_result:\s*({.*})")

# ── Timeouts (overridable via env vars) ──
WAIT_SECONDS = float(os.environ.get("ADB_WAIT_SECONDS", "15"))
PROFILE_WAIT_SECONDS = int(os.environ.get("ADB_PROFILE_WAIT_SECONDS", "20"))

# ── Device serial (overridable via env vars) ──
ANDROID_SERIAL = os.environ.get("ANDROID_SERIAL", "") or os.environ.get("ADB_SERIAL", "")

# ── Output directories ──
REPORTS_DIR = Path(__file__).parent.parent / "test-reports"

# ── OOM detection ──
OOM_RUN_THRESHOLD = 5
