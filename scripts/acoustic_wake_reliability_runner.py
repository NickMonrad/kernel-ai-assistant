#!/usr/bin/env python3
"""Unattended paired acoustic wake-word reliability runner (Issue #1409).

Coordinates a source Android device and a target Android device through the
frozen acoustic wake-word reliability matrix. Preserves target idle,
classifies every attempt, and emits privacy-safe normalised evidence.

Device roles (default):
  S23 Ultra = acoustic source, S21 = launch-blocking target.

Usage examples:
  # Dry-run / fixture mode (no ADB)
  python3 scripts/acoustic_wake_reliability_runner.py fixture

  # Human-monitored preflight
  python3 scripts/acoustic_wake_reliability_runner.py preflight \
    --source s23u --target s21 \
    --source-selector "$JANDAL_S23U_SOURCE" \
    --target-selector "$JANDAL_S21_TARGET" \
    --fixture-dir data/local/tmp/acoustic-fixtures

  # Short physical smoke
  python3 scripts/acoustic_wake_reliability_runner.py smoke \
    --source s23u --target s21

  # Diagnostic pre-fix matrix
  python3 scripts/acoustic_wake_reliability_runner.py diagnostic \
    --source s23u --target s21

  # Post-fix release-gate regression
  python3 scripts/acoustic_wake_reliability_runner.py regression \
    --source s23u --target s21

  # Feasibility fixed-delay mode
  python3 scripts/acoustic_wake_reliability_runner.py feasibility \
    --source s23u --target s21 \
    --cue-margin-ms 3000 --fixed-command-delay-ms 5000

  # Resume interrupted run
  python3 scripts/acoustic_wake_reliability_runner.py resume \
    --run-id smoke-2026-07-18T10-00-00Z-a1b2c3d4
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = REPO_ROOT / "scripts"
DEFAULT_PRIVATE_ROOT = SCRIPTS_DIR / "private-acoustic-runs"
DEFAULT_PACKAGE = "com.kernel.ai.debug"

SOURCE_RECEIVER_CLS = "com.kernel.ai.debug.acoustic.AcousticStimulusReceiver"
SOURCE_ACTION = "com.kernel.ai.debug.action.PLAY_ACOUSTIC_STIMULUS"

# Target journal broadcast actions (TargetEventJournalContract)
TARGET_PACKAGE = "com.kernel.ai.debug"
TARGET_RECEIVER_CLS = "com.kernel.ai.debug.journal.TargetEventJournalReceiver"
TARGET_ACTION_GET_SEQUENCE = "com.kernel.ai.debug.action.GET_JOURNAL_SEQUENCE"
TARGET_ACTION_WAIT_FOR_EVENT = "com.kernel.ai.debug.action.WAIT_FOR_JOURNAL_EVENT"
TARGET_ACTION_GET_SNAPSHOT = "com.kernel.ai.debug.action.GET_JOURNAL_SNAPSHOT"
TARGET_WAIT_DEFAULT_TIMEOUT_MS = 15_000
TARGET_WAIT_MIN_TIMEOUT_MS = 500
# Public aliases that may appear in sanitised output
PUBLIC_ALIASES = ("s21", "s23u")

# Frozen matrix identifiers
MATRIX_ID = "AWVR-001"
MATRIX_VERSION = 1

# Frozen valid-trial matrix: [(idle_s, wake_only, wake_plus_command), ...]
MATRIX_S21: tuple[tuple[int, int, int], ...] = (
    (10, 5, 3),
    (30, 5, 0),
    (120, 5, 3),
    (900, 2, 0),
    (1800, 2, 2),
)
MATRIX_S23U: tuple[tuple[int, int, int], ...] = (
    (120, 3, 2),
    (1800, 2, 1),
)

EXPECTED_DEVICES: dict[str, dict[str, str]] = {
    "s21": {"manufacturer": "samsung", "model": "SM-G991B"},
    "s23u": {"manufacturer": "samsung", "model": "SM-S918B"},
}

# Private-identifier patterns for sanitisation
PRIVATE_PATTERNS: tuple[re.Pattern, ...] = (
    re.compile(r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d{2,5}\b"),  # IP:port
    re.compile(r"\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b"),  # MAC
    re.compile(r"(?i)\bhome\b.*\b(?:lokhor|nick|monrad)\b.*", re.IGNORECASE),  # home paths
    re.compile(r"/home/[^/\s]+"),  # /home/... paths
    re.compile(r"(?i)(serial|sn|imei)\s*[=:]\s*\S+"),  # serial-like
)
RAW_EVIDENCE_FILENAMES = {
    "source-result.json", "target-snapshot.json", "checkpoint.json",
    "manifest-private.json", "preflight-private.json",
}

DEFAULT_CUE_MARGIN_MS = 1000
DEFAULT_WAIT_TIMEOUT_MS = 30000
DEFAULT_SOURCE_TIMEOUT_S = 20
VALID_MAX_ATTEMPTS = 5  # max invalid attempts per matrix slot


# ── Exceptions ──────────────────────────────────────────────────────────

class HarnessError(RuntimeError):
    """A precondition or runtime failure that invalidates a run or attempt."""


# ── Enums and data classes ──────────────────────────────────────────────

class RunKind(str, Enum):
    FIXTURE = "fixture"
    PREFLIGHT = "preflight"
    SMOKE = "smoke"
    DIAGNOSTIC = "diagnostic_pre_fix"
    REGRESSION = "regression_post_fix"
    FEASIBILITY = "feasibility"

class GateMode(str, Enum):
    DIAGNOSTIC = "diagnostic"
    RELEASE = "release_gate"

class TrialType(str, Enum):
    WAKE_ONLY = "wake_only"
    WAKE_PLUS_COMMAND = "wake_plus_command"

class AttemptStatus(str, Enum):
    PASSED = "passed"
    FAILED = "failed"
    INVALID = "invalid"

class FailureClassification(str, Enum):
    ACOUSTIC_OR_GATE_MISS = "acoustic_or_gate_miss"
    CLASSIFIER_MODEL_MISS = "classifier_model_miss"
    ACTIVATION_HANDOFF_FAILURE = "activation_handoff_failure"
    STT_READINESS_FAILURE = "stt_readiness_failure"
    CUE_AUDIO_FAILURE = "cue_audio_failure"
    CUE_AUDIBILITY_UNCONFIRMED = "cue_audibility_unconfirmed"
    COMMAND_CAPTURE_OR_ROUTING_FAILURE = "command_capture_or_routing_failure"
    SERVICE_REARM_FAILURE = "service_rearm_failure"
    UNCLASSIFIED = "unclassified"

class InvalidReason(str, Enum):
    SAME_DEVICE = "same_source_and_target_device"
    SOURCE_STIMULUS_FAILURE = "source_stimulus_failure"
    DEVICE_ENVIRONMENT_ERROR = "device_environment_error"
    SOURCE_WAKE_ACTIVE = "source_wake_service_active"
    TARGET_WAKE_INACTIVE = "target_wake_service_inactive"
    WRONG_MANUFACTURER = "unexpected_manufacturer"
    WRONG_MODEL = "unexpected_model"
    ADB_LOST = "adb_connection_lost"
    EVIDENCE_BOUNDARY_LOST = "required_post_boundary_events_evicted"
    CLEANUP_UNVERIFIED = "cleanup_or_restoration_unverified"
    OPERATOR_CANCELLED = "operator_cancellation"
    MATRIX_INCOMPLETE = "matrix_incomplete"
    MISSING_CUE_POLICY = "missing_cue_policy_evidence"
    BUILD_PROVENANCE_FAILURE = "build_provenance_failure"
    UNKNOWN = "unknown"


@dataclasses.dataclass(frozen=True)
class MatrixSlot:
    """One position in the frozen matrix."""
    idle_s: int
    wake_only: bool  # True = wake_only, False = wake_plus_command

    def __post_init__(self) -> None:
        if self.idle_s <= 0:
            raise ValueError(f"idle_s must be positive: {self.idle_s}")


@dataclasses.dataclass
class MatrixAttempt:
    """One attempt at a matrix slot."""
    trial_id: str
    matrix_slot: MatrixSlot
    attempt: int  # 1-based attempt number
    status: AttemptStatus
    classification: FailureClassification | None = None
    invalid_reason: InvalidReason | None = None
    host_start_ms: int = 0
    host_duration_ms: int = 0
    failures: list[str] = dataclasses.field(default_factory=list)


@dataclasses.dataclass
class RunManifest:
    """Persistent run metadata."""
    run_id: str
    run_kind: RunKind
    gate_mode: GateMode
    matrix_id: str
    matrix_version: int
    created_utc: str
    source_alias: str
    target_alias: str
    fixture_set_id: str | None
    fixture_hashes: dict[str, str]
    cue_policy_version: str | None
    preflight_hash: str | None


# ── Sanitisation (reuses patterns from battery_telemetry_harness.py) ────

def sanitise_text(text: str, secrets: Iterable[str] = ()) -> str:
    """Redact known device secrets and private identifiers from public output."""
    sanitized = text
    for secret in sorted({v for v in secrets if v}, key=len, reverse=True):
        sanitized = sanitized.replace(secret, "[REDACTED_DEVICE_IDENTIFIER]")
    for pattern in PRIVATE_PATTERNS:
        sanitized = pattern.sub("[REDACTED]", sanitized)
    return sanitized


def assert_commit_safe(value: Any, secrets: Iterable[str] = ()) -> None:
    """Fail closed if JSON-safe output still contains private artifact indicators."""
    text = json.dumps(value, sort_keys=True)
    redacted = sanitise_text(text, secrets)
    if redacted != text:
        raise HarnessError("sanitized output contains a private identifier or path")
    if any(name in text.lower() for name in RAW_EVIDENCE_FILENAMES):
        raise HarnessError("sanitized output must not name raw artifact files")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def monotonic_ms() -> int:
    return time.monotonic_ns() // 1_000_000


# ── ADB transport ───────────────────────────────────────────────────────

class AdbClient:
    """Thin ADB wrapper with injectable runner for testing."""

    def __init__(
        self, serial: str,
        runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    ) -> None:
        self.serial = serial
        self._runner = runner

    def run(self, *args: str, timeout: float = 30.0) -> str:
        result = self._runner(
            ["adb", "-s", self.serial, *args],
            text=True, capture_output=True, timeout=timeout,
            stdin=subprocess.DEVNULL,
        )
        if result.returncode != 0:
            raise HarnessError(f"ADB command failed ({result.returncode}): {args[0] if args else ''}")
        return result.stdout

    def shell(self, *args: str, timeout: float = 30.0) -> str:
        return self.run("shell", *args, timeout=timeout)

    def reachable(self) -> bool:
        try:
            return self.run("get-state").strip() == "device"
        except (HarnessError, OSError):
            return False


# ── Device identity ─────────────────────────────────────────────────────

@dataclasses.dataclass(frozen=True)
class DeviceIdentity:
    alias: str
    manufacturer: str
    model: str
    android_release: str
    android_api: str
    build_fingerprint: str
    package_version: str | None
    package_version_code: int | None

    def public(self) -> dict[str, Any]:
        return {
            "alias": self.alias,
            "manufacturer": self.manufacturer,
            "model": self.model,
            "android_release": self.android_release,
            "android_api": self.android_api,
            "package_version": self.package_version,
            "package_version_code": self.package_version_code,
        }

    @property
    def public_model(self) -> str:
        return self.model


def device_identity(client: AdbClient, alias: str, package: str) -> DeviceIdentity:
    """Resolve device identity and package version for an ADB-connected device."""
    if not client.reachable():
        raise HarnessError(f"{alias}: ADB is not reachable")

    props: dict[str, str] = {}
    for key in (
        "ro.product.manufacturer", "ro.product.model",
        "ro.build.version.release", "ro.build.version.sdk",
        "ro.build.fingerprint",
    ):
        props[key] = client.shell("getprop", key).strip()

    expected = EXPECTED_DEVICES.get(alias)
    if expected:
        if props["ro.product.manufacturer"].lower() != expected["manufacturer"]:
            raise HarnessError(
                f"{alias}: expected manufacturer '{expected['manufacturer']}', "
                f"got '{props['ro.product.manufacturer']}'"
            )
        if props["ro.product.model"] != expected["model"]:
            raise HarnessError(
                f"{alias}: expected model '{expected['model']}', "
                f"got '{props['ro.product.model']}'"
            )

    # Package version
    dumpsys = client.shell("dumpsys", "package", package)
    pkg_ver = re.search(r"versionName=([^\s]+)", dumpsys)
    pkg_code = re.search(r"versionCode=(\d+)", dumpsys)
    version = pkg_ver.group(1) if pkg_ver else None
    code = int(pkg_code.group(1)) if pkg_code else None

    return DeviceIdentity(
        alias=alias,
        manufacturer=props["ro.product.manufacturer"],
        model=props["ro.product.model"],
        android_release=props["ro.build.version.release"],
        android_api=props["ro.build.version.sdk"],
        build_fingerprint=props["ro.build.fingerprint"],
        package_version=version,
        package_version_code=code,
    )


def service_active(client: AdbClient, package: str) -> bool:
    """Check whether WakeWordService is running on the device."""
    return "WakeWordService" in client.shell("dumpsys", "activity", "services", package)


# ── Frozen matrix ───────────────────────────────────────────────────────

def get_matrix(target_alias: str) -> tuple[tuple[int, int, int], ...]:
    """Return the frozen matrix for the given target alias."""
    if target_alias == "s21":
        return MATRIX_S21
    elif target_alias == "s23u":
        return MATRIX_S23U
    else:
        raise HarnessError(f"unknown target alias: {target_alias}")


def matrix_slots_for_target(target_alias: str) -> list[MatrixSlot]:
    """Expand frozen matrix into individual slots."""
    slots: list[MatrixSlot] = []
    for idle_s, wake_only, wpc in get_matrix(target_alias):
        for _ in range(wake_only):
            slots.append(MatrixSlot(idle_s=idle_s, wake_only=True))
        for _ in range(wpc):
            slots.append(MatrixSlot(idle_s=idle_s, wake_only=False))
    return slots


# ── Source contract parsing ────────────────────────────────────────────

def parse_source_result(text: str) -> dict[str, Any]:
    """Parse a source playback result JSON (AcousticStimulusResult.toJson())."""
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise HarnessError(f"source result is not valid JSON: {e}")

    if not isinstance(data, dict):
        raise HarnessError("source result is not a JSON object")

    # The source helper always writes a schema_version.
    # Validate playback completed cleanly.
    status = data.get("completion_status")
    if status != "completed":
        err = data.get("error_category") or data.get("playback_error_category") or "unknown"
        raise HarnessError(f"source playback did not complete: status={status} error={err}")

    if data.get("timeout", False):
        raise HarnessError("source playback timed out")

    if data.get("overlap_rejected", False):
        raise HarnessError("source playback rejected due to overlap")

    if not data.get("cleanup_success", False):
        raise HarnessError("source cleanup did not succeed")

    if not data.get("exact_restoration_verified", False):
        raise HarnessError("source volume restoration not verified")

    route = data.get("output_route_during")
    if route and route != "BUILT_IN_SPEAKER":
        raise HarnessError(f"source output route is not BUILT_IN_SPEAKER: {route}")

    # Must have trial and fixture identity
    if not data.get("trial_id"):
        raise HarnessError("source result missing trial_id")

    if not data.get("fixture_id"):
        raise HarnessError("source result missing fixture_id")

    return data


# ── Target journal parsing ──────────────────────────────────────────────

JOURNAL_CONTRACT_VERSION = "1.0.0"
VALID_EVENT_TYPES: set[str] = {
    "DETECTOR_GENERATION_STARTED", "SILENCE_GATE_ENTERED",
    "VOICED_FRAME_AFTER_SILENCE", "STAGE3_READY",
    "ACTIVATION_CANDIDATE", "VERIFIED_ACTIVATION",
    "WAKE_CALLBACK_INVOKED", "VOICE_SESSION_STARTED",
    "STT_START_REQUESTED", "STT_READY", "CUE_REQUESTED",
    "STT_SPEECH_DETECTED", "STT_PARTIAL", "STT_ERROR",
    "SESSION_COMPLETED", "SESSION_CANCELLED",
    "DETECTOR_REARMED", "DETECTOR_ERROR", "SERVICE_ERROR",
}


def parse_journal_snapshot(result_data: str) -> dict[str, Any]:
    """Parse a GET_JOURNAL_SNAPSHOT result_data (JSON array, one event per line).

    The receiver serialises events as compact JSON, one object per line between
    ``[`` and ``]``. Each event uses short keys:
      s = sequence, m = monotonicMs, w = wallClockMs,
      t = type, g = generationId, i = sessionId, d = metadata dict.

    We canonicalise to long keys and add an empty metadata dict when absent.
    """
    if not result_data:
        return {"events": []}

    raw = result_data.strip()
    if raw == "[]":
        return {"events": []}

    if not (raw.startswith("[") and raw.endswith("]")):
        # Not a JSON array — likely a timeout/error message
        raise HarnessError(f"snapshot is not a JSON array: {result_data[:200]}")

    inner = raw[1:-1].strip()
    if not inner:
        return {"events": []}

    # Split on newlines between event objects. Each line is one event.
    # Some shells collapse the newlines into a single line; fall back to
    # full-JSON-array parsing.
    lines = [ln.strip() for ln in inner.split("\n") if ln.strip()]
    if lines:
        events_raw = lines
    else:
        # Single-line case — parse as JSON array
        try:
            arr = json.loads(raw)
            return {"events": [_canonicalise_event(e) for e in arr]}
        except (json.JSONDecodeError, TypeError) as e:
            raise HarnessError(f"snapshot is not valid JSON: {e}")

    events = []
    for ln in events_raw:
        try:
            ev = json.loads(ln.rstrip(","))
        except json.JSONDecodeError as e:
            raise HarnessError(f"snapshot event is not valid JSON: {e} — line: {ln[:200]}")
        events.append(_canonicalise_event(ev))

    return {"events": events}


def _canonicalise_event(ev: dict[str, Any]) -> dict[str, Any]:
    """Canonicalise compact event keys to consistent dict."""
    canon: dict[str, Any] = {}
    canon["s"] = ev.get("s", 0)
    canon["m"] = ev.get("m", 0)
    canon["w"] = ev.get("w", 0)
    canon["t"] = ev.get("t", "")
    canon["g"] = ev.get("g", 0)
    canon["i"] = ev.get("i", 0)
    canon["d"] = ev.get("d") or {}
    return canon


def parse_journal_wait_result(result_code: int, result_data: str) -> dict[str, Any] | None:
    """Parse a WAIT_FOR_JOURNAL_EVENT result.

    Returns the event dict if found (code 0), None on timeout (code 1),
    or raises HarnessError on error (code 2).
    """
    if result_code == 1:
        return None  # Timeout
    elif result_code == 2:
        raise HarnessError(f"target wait error: {result_data}")
    elif result_code != 0:
        raise HarnessError(f"unknown target result code: {result_code}")

    # Code 0 — event found. result_data is a JSON event object.
    try:
        ev = json.loads(result_data)
    except json.JSONDecodeError as e:
        raise HarnessError(f"wait event not valid JSON: {e}")

    if not isinstance(ev, dict) or "t" not in ev:
        raise HarnessError(f"wait event missing type field: {result_data[:200]}")

    return _canonicalise_event(ev)


def parse_journal_sequence(result_data: str) -> int:
    """Parse a GET_JOURNAL_SEQUENCE result_data (plain integer string)."""
    try:
        return int(result_data.strip())
    except (ValueError, AttributeError) as e:
        raise HarnessError(f"sequence is not a valid integer: {result_data[:100]}")


def validate_snapshot_envelope(envelope: dict[str, Any]) -> dict[str, Any]:
    """Validate a target snapshot envelope and return normalised events."""
    if "events" not in envelope:
        raise HarnessError("snapshot envelope missing 'events'")

    events = envelope["events"]
    if not isinstance(events, list):
        raise HarnessError("snapshot 'events' is not a list")

    last_s = -1
    for i, ev in enumerate(events):
        if not isinstance(ev, dict):
            raise HarnessError(f"event {i} is not a dict")
        if "s" not in ev:
            raise HarnessError(f"event {i} missing sequence 's'")
        if "t" not in ev:
            raise HarnessError(f"event {i} missing type 't'")
        if ev["t"] not in VALID_EVENT_TYPES:
            raise HarnessError(f"event {i}: unknown type '{ev['t']}'")
        if not isinstance(ev["s"], int) or ev["s"] <= last_s:
            raise HarnessError(
                f"event {i}: sequence not monotonic ({ev['s']} <= {last_s})"
            )
        last_s = ev["s"]

    return envelope


def find_event(events: list[dict[str, Any]], event_type: str,
               generation: int | None = None,
               session: int | None = None) -> dict[str, Any] | None:
    """Find the first event of the given type, optionally filtered by gen/session."""
    for ev in events:
        if ev["t"] != event_type:
            continue
        if generation is not None and ev.get("g") != generation:
            continue
        if session is not None and ev.get("i") != session:
            continue
        return ev
    return None


# ── Classification ──────────────────────────────────────────────────────

def classify_attempt(source_result: dict[str, Any] | None,
                      target_snapshot: dict[str, Any] | None,
                      trial_type: TrialType,
                      gen_id: int | None,
                      session_id: int | None,
                      cue_policy_available: bool,
                      ) -> tuple[AttemptStatus, FailureClassification | None, InvalidReason | None, list[str]]:
    """Validity-first classification of a trial attempt.

    Returns (status, classification, invalid_reason, failures).
    """
    failures: list[str] = []
    # 1. Invalid — source stimulus (check before anything else)
    if source_result is None:
        return (AttemptStatus.INVALID, None, InvalidReason.SOURCE_STIMULUS_FAILURE, ["no source result available"])

    if source_result.get("completion_status") != "completed":
        err = source_result.get("error_category") or source_result.get("playback_error_category") or "unknown"
        failures.append(f"source playback not completed: status={source_result.get('completion_status')} error={err}")
        return (AttemptStatus.INVALID, None, InvalidReason.SOURCE_STIMULUS_FAILURE, failures)

    if source_result.get("timeout", False):
        failures.append("source playback timed out")
        return (AttemptStatus.INVALID, None, InvalidReason.SOURCE_STIMULUS_FAILURE, failures)

    if source_result.get("overlap_rejected", False):
        failures.append("source playback rejected due to overlap")
        return (AttemptStatus.INVALID, None, InvalidReason.SOURCE_STIMULUS_FAILURE, failures)

    if not source_result.get("cleanup_success", False):
        failures.append("source cleanup did not succeed")
        return (AttemptStatus.INVALID, None, InvalidReason.SOURCE_STIMULUS_FAILURE, failures)

    if not source_result.get("exact_restoration_verified", False):
        failures.append("source volume restoration not verified")
        return (AttemptStatus.INVALID, None, InvalidReason.SOURCE_STIMULUS_FAILURE, failures)

    route = source_result.get("output_route_during")
    if route and route != "BUILT_IN_SPEAKER":
        failures.append(f"source route: {route}")
        return (AttemptStatus.INVALID, None, InvalidReason.SOURCE_STIMULUS_FAILURE, failures)

    # 2. Invalid — device/environment/evidence
    if target_snapshot is None:
        failures.append("no target snapshot available")
        return (AttemptStatus.INVALID, None, InvalidReason.DEVICE_ENVIRONMENT_ERROR, failures)

    events = target_snapshot.get("events", [])

    # Empty events after valid source stimulus = valid acoustic/gate failure
    if not events:
        failures.append("no target events recorded after valid stimulus")
        return (AttemptStatus.FAILED, FailureClassification.ACOUSTIC_OR_GATE_MISS, None, failures)

    if "overflowed" in target_snapshot and target_snapshot.get("overflowed"):
        # Check whether events after the trial boundary may have been evicted
        lowest = target_snapshot.get("lowest_sequence", 0)
        boundary_seq = target_snapshot.get("since_sequence", 0)
        if isinstance(lowest, int) and isinstance(boundary_seq, int) and lowest > boundary_seq + 1:
            failures.append(f"post-boundary events may be evicted (lowest={lowest}, boundary={boundary_seq})")
            return (AttemptStatus.INVALID, None, InvalidReason.EVIDENCE_BOUNDARY_LOST, failures)
    has_gate_activity = (
        find_event(events, "SILENCE_GATE_ENTERED") is not None
        or find_event(events, "VOICED_FRAME_AFTER_SILENCE") is not None
    )
    has_activation = (
        find_event(events, "ACTIVATION_CANDIDATE") is not None
        or find_event(events, "VERIFIED_ACTIVATION") is not None
    )
    has_wake_callback = find_event(events, "WAKE_CALLBACK_INVOKED", generation=gen_id) is not None
    has_session = find_event(events, "VOICE_SESSION_STARTED", generation=gen_id, session=session_id) is not None
    has_stt_ready = find_event(events, "STT_READY") is not None
    has_cue_requested = find_event(events, "CUE_REQUESTED") is not None
    has_terminal = (
        find_event(events, "SESSION_COMPLETED", session=session_id) is not None
        or find_event(events, "SESSION_CANCELLED", session=session_id) is not None
    )
    has_rearm = find_event(events, "DETECTOR_REARMED") is not None
    has_command_result = find_event(events, "STT_SPEECH_DETECTED") is not None

    if not has_gate_activity:
        return (AttemptStatus.FAILED, FailureClassification.ACOUSTIC_OR_GATE_MISS, None, ["no gate or voiced activity after stimulus"])

    if not has_activation:
        return (AttemptStatus.FAILED, FailureClassification.CLASSIFIER_MODEL_MISS, None, ["gate active but no activation candidate"])

    if not has_wake_callback:
        return (AttemptStatus.FAILED, FailureClassification.ACTIVATION_HANDOFF_FAILURE, None, ["activation but no wake callback"])

    if not has_stt_ready:
        return (AttemptStatus.FAILED, FailureClassification.STT_READINESS_FAILURE, None, ["session started but no STT_READY"])

    # Cue check
    if not has_cue_requested:
        return (AttemptStatus.FAILED, FailureClassification.CUE_AUDIO_FAILURE, None, ["no CUE_REQUESTED after STT_READY"])

    if not cue_policy_available:
        return (AttemptStatus.FAILED, FailureClassification.CUE_AUDIBILITY_UNCONFIRMED, None, ["cue policy not available; CUE_REQUESTED alone not proof of audibility"])

    # Command-only checks
    if trial_type == TrialType.WAKE_PLUS_COMMAND:
        if not has_command_result:
            return (AttemptStatus.FAILED, FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE, None, ["no STT_SPEECH_DETECTED for command trial"])

    if not has_terminal:
        return (AttemptStatus.FAILED, FailureClassification.UNCLASSIFIED, None, ["no terminal session event"])

    if not has_rearm:
        return (AttemptStatus.FAILED, FailureClassification.SERVICE_REARM_FAILURE, None, ["session ended but no DETECTOR_REARMED"])

    return (AttemptStatus.PASSED, None, None, [])
# ── Evidence formatting ─────────────────────────────────────────────────

def format_target_snapshot_events(envelope: dict[str, Any]) -> list[dict[str, Any]]:
    """Extract sanitised event sequence from a validated snapshot."""
    events = envelope.get("events", [])
    return [
        {
            "s": ev["s"],
            "t": ev["t"],
            "g": ev.get("g"),
            "i": ev.get("i"),
            "m": ev.get("m"),
            "d": ev.get("d", {}),
        }
        for ev in events
    ]


def render_evidence(
    run_manifest: RunManifest,
    target_identity: DeviceIdentity,
    source_identity: DeviceIdentity,
    source_helper_version: str | None,
    attempts: list[MatrixAttempt],
    preflight_approval: dict[str, Any] | None,
    cleanup_verified: bool,
    source_route: str | None,
    secrets: Iterable[str] = (),
) -> dict[str, Any]:
    """Build the normalised evidence record per target device."""
    total = len(attempts)
    passed = sum(1 for a in attempts if a.status == AttemptStatus.PASSED)
    failed = sum(1 for a in attempts if a.status == AttemptStatus.FAILED)
    invalid = sum(1 for a in attempts if a.status == AttemptStatus.INVALID)
    valid = passed + failed

    evidence: dict[str, Any] = {
        "schema_version": "1.0",
        "source": "on_device",
        "suite": "wake_word_acoustic_reliability",
        "timestamp": run_manifest.created_utc,
        "repo": "NickMonrad/kernel-ai-assistant",
        "branch": os.environ.get("GIT_BRANCH", None),
        "commit": os.environ.get("GIT_COMMIT", None),
        "pr": int(os.environ["GIT_PR"]) if "GIT_PR" in os.environ else None,
        "release": None,
        "run_id": run_manifest.run_id,
        "device": target_identity.public(),
        "model": {"name": "wake_word", "runtime": "ONNX", "backend": "NPU"},
        "summary": {
            "total_attempts": total,
            "valid": valid,
            "passed": passed,
            "failed": failed,
            "invalid": invalid,
            "pass_rate": round(passed / valid, 4) if valid > 0 else 0.0,
        },
        "cases": [],
        "wake_reliability": {
            "run_kind": run_manifest.run_kind.value,
            "gate_mode": run_manifest.gate_mode.value,
            "matrix_id": run_manifest.matrix_id,
            "matrix_version": run_manifest.matrix_version,
            "source": source_identity.public(),
            "source_helper_version": source_helper_version,
            "fixture_set_id": run_manifest.fixture_set_id,
            "fixture_hashes": run_manifest.fixture_hashes,
            "preflight_approval": preflight_approval or {},
            "approved_source_volume": preflight_approval.get("source_volume_index") if preflight_approval else None,
            "approved_source_volume_max": preflight_approval.get("source_volume_max") if preflight_approval else None,
            "approved_source_route": source_route,
            "placement_notes": (preflight_approval or {}).get("placement_notes"),
            "cue_policy_version": run_manifest.cue_policy_version,
            "monitored_acoustic_check": run_manifest.cue_policy_version is not None,
            "expected_valid_counts": {
                str(s.idle_s) + ("_wo" if s.wake_only else "_wc"): 0
                for s in [MatrixSlot(idle_s=10, wake_only=True)]
            },
            "cleanup_verified": cleanup_verified,
        },
    }

    for attempt in attempts:
        case: dict[str, Any] = {
            "name": attempt.trial_id,
            "passed": attempt.status == AttemptStatus.PASSED,
            "status": attempt.status.value,
            "idle_seconds": attempt.matrix_slot.idle_s,
            "trial_type": "wake_only" if attempt.matrix_slot.wake_only else "wake_plus_command",
            "attempt": attempt.attempt,
        }
        if attempt.status == AttemptStatus.FAILED and attempt.classification:
            case["failure_classification"] = attempt.classification.value
        elif attempt.status == AttemptStatus.FAILED:
            case["failure_classification"] = "unclassified"
        if attempt.status == AttemptStatus.INVALID and attempt.invalid_reason:
            case["invalid_reason"] = attempt.invalid_reason.value
        if attempt.failures:
            case["failures"] = attempt.failures
        evidence["cases"].append(case)

    assert_commit_safe(evidence, secrets)
    return evidence


def write_sanitized_summary(output_dir: Path, evidence: dict[str, Any],
                             secrets: Iterable[str] = ()) -> tuple[Path, Path]:
    """Write JSON evidence and Markdown summary, returning (json_path, md_path)."""
    assert_commit_safe(evidence, secrets)

    json_path = output_dir / "evidence.json"
    json_path.write_text(json.dumps(evidence, indent=2) + "\n")

    md_lines = [
        "# Acoustic Wake Reliability Report",
        "",
        f"**Run ID:** {evidence.get('run_id', 'unknown')}",
        f"**Kind:** {evidence.get('wake_reliability', {}).get('run_kind', 'unknown')}",
        f"**Gate:** {evidence.get('wake_reliability', {}).get('gate_mode', 'unknown')}",
        f"**Matrix:** {evidence.get('wake_reliability', {}).get('matrix_id', 'unknown')} v{evidence.get('wake_reliability', {}).get('matrix_version', '?')}",
        f"**Target:** {evidence.get('device', {}).get('alias', 'unknown')}",
        f"**Source:** {evidence.get('wake_reliability', {}).get('source', {}).get('alias', 'unknown')}",
        "",
        "## Summary",
        f"- Total attempts: {evidence.get('summary', {}).get('total_attempts', 0)}",
        f"- Valid: {evidence.get('summary', {}).get('valid', 0)}",
        f"- Passed: {evidence.get('summary', {}).get('passed', 0)}",
        f"- Failed: {evidence.get('summary', {}).get('failed', 0)}",
        f"- Invalid: {evidence.get('summary', {}).get('invalid', 0)}",
        f"- Pass rate: {evidence.get('summary', {}).get('pass_rate', 0.0):.1%}",
        "",
        "## Cleanup",
        f"Verified: {evidence.get('wake_reliability', {}).get('cleanup_verified', False)}",
    ]

    if evidence.get("cases"):
        md_lines.extend(["", "## Attempts"])
        for case in evidence["cases"]:
            status_mark = "PASS" if case.get("passed") else "FAIL" if case.get("status") == "failed" else "INV"
            cls = case.get("failure_classification", case.get("invalid_reason", ""))
            md_lines.append(
                f"- {case.get('trial_id', '?')}: {status_mark} "
                f"idle={case.get('idle_seconds', '?')}s "
                f"{case.get('trial_type', '?')} "
                f"(attempt {case.get('attempt', 1)})"
                + (f" [{cls}]" if cls else "")
            )

    md_path = output_dir / "run-summary.md"
    md_path.write_text("\n".join(md_lines) + "\n")

    return json_path, md_path


# ── Core orchestration ──────────────────────────────────────────────────

class AcousticWakeReliabilityRunner:
    """Unattended paired acoustic wake-word reliability runner."""

    def __init__(
        self,
        run_kind: RunKind,
        source_alias: str,
        target_alias: str,
        source_client: AdbClient,
        target_client: AdbClient,
        fixture_dir: Path | None = None,
        private_root: Path = DEFAULT_PRIVATE_ROOT,
        package: str = DEFAULT_PACKAGE,
        cue_margin_ms: int = DEFAULT_CUE_MARGIN_MS,
        fixed_command_delay_ms: int | None = None,
        interactive: bool = False,
    ) -> None:
        if source_alias == target_alias:
            raise HarnessError("source and target must resolve to different physical devices")
        if source_client.serial == target_client.serial:
            raise HarnessError("source and target must resolve to different physical devices (same ADB serial)")
        self.run_kind = run_kind
        self.gate_mode = (
            GateMode.RELEASE if run_kind == RunKind.REGRESSION
            else GateMode.DIAGNOSTIC
        )
        self.source_alias = source_alias
        self.target_alias = target_alias
        self.source = source_client
        self.target = target_client
        self.fixture_dir = fixture_dir
        self.private_root = private_root
        self.package = package
        self.cue_margin_ms = cue_margin_ms
        self.fixed_command_delay_ms = fixed_command_delay_ms
        self.interactive = interactive
        self.is_feasibility = run_kind == RunKind.FEASIBILITY

        self.run_id = (
            f"{run_kind.value}-{datetime.now(timezone.utc):%Y-%m-%dT%H-%M-%SZ}"
            f"-{uuid.uuid4().hex[:8]}"
        )
        self.run_dir = private_root / self.run_id
        self.trials_dir = self.run_dir / "trials"
        self.sanitized_dir = self.run_dir / "sanitized"

        self.manifest: RunManifest | None = None
        self.target_identity: DeviceIdentity | None = None
        self.source_identity: DeviceIdentity | None = None
        self.preflight_approval: dict[str, Any] | None = None
        self.attempts: list[MatrixAttempt] = []
        self.completed_slots: set[str] = set()  # "idle_s:wo/wc:attempt"
        self.valid_failed_slots: set[str] = set()  # slots that had a valid failure
        self.invalid_attempt_count: int = 0
        self.abort_reason: str | None = None
        self.cleanup_verified: bool = False
        self.primary_failure: str | None = None
        self.secrets: list[str] = []
        self._cancel = threading.Event()

    # ── Private file helpers ─────────────────────────────────────────

    def private_write(self, subdir: str, name: str, content: str) -> Path:
        path = self.run_dir / subdir / name
        path.parent.mkdir(parents=True, exist_ok=True)
        # Atomic write via temp + rename
        tmp = path.with_suffix(f".tmp.{uuid.uuid4().hex[:8]}")
        tmp.write_text(content)
        tmp.rename(path)
        return path

    def checkpoint(self, name: str = "checkpoint") -> dict[str, Any]:
        state = {
            "schema_version": 1,
            "run_id": self.run_id,
            "run_kind": self.run_kind.value,
            "gate_mode": self.gate_mode.value,
            "matrix_id": MATRIX_ID,
            "matrix_version": MATRIX_VERSION,
            "source_alias": self.source_alias,
            "target_alias": self.target_alias,
            "preflight_hash": (
                hashlib.sha256(
                    json.dumps(self.preflight_approval, sort_keys=True).encode()
                ).hexdigest()
                if self.preflight_approval else None
            ),
            "completed_slots": sorted(self.completed_slots),
            "valid_failed_slots": sorted(self.valid_failed_slots),
            "invalid_attempt_count": self.invalid_attempt_count,
            "abort_reason": self.abort_reason,
            "cleanup_verified": self.cleanup_verified,
            "primary_failure": self.primary_failure,
            "attempts": [
                {
                    "trial_id": a.trial_id,
                    "matrix_slot": {
                        "idle_s": a.matrix_slot.idle_s,
                        "wake_only": a.matrix_slot.wake_only,
                    },
                    "attempt": a.attempt,
                    "status": a.status.value,
                    "classification": a.classification.value if a.classification else None,
                    "invalid_reason": a.invalid_reason.value if a.invalid_reason else None,
                }
                for a in self.attempts
            ],
            "created_utc": utc_now(),
        }
        self.private_write("", f"{name}.json", json.dumps(state, indent=2))
        return state

    def load_checkpoint(self, run_id: str) -> dict[str, Any]:
        """Load a prior checkpoint by run_id. Returns the state dict."""
        path = self.private_root / run_id / "checkpoint.json"
        if not path.exists():
            raise HarnessError(f"no checkpoint found at {path}")
        try:
            state = json.loads(path.read_text())
        except (json.JSONDecodeError, OSError) as e:
            raise HarnessError(f"corrupted checkpoint: {e}")

        required = {"run_id", "run_kind", "matrix_id", "source_alias", "target_alias",
                    "completed_slots", "valid_failed_slots", "attempts"}
        missing = required - set(state)
        if missing:
            raise HarnessError(f"checkpoint missing required fields: {missing}")

        if state["matrix_id"] != MATRIX_ID or state.get("matrix_version", 0) != MATRIX_VERSION:
            raise HarnessError(
                f"checkpoint matrix mismatch: expected {MATRIX_ID} v{MATRIX_VERSION}, "
                f"got {state['matrix_id']} v{state.get('matrix_version', '?')}"
            )

        # Restore state
        self.run_id = state["run_id"]
        self.run_dir = self.private_root / self.run_id
        self.trials_dir = self.run_dir / "trials"
        self.sanitized_dir = self.run_dir / "sanitized"
        self.completed_slots = set(state.get("completed_slots", []))
        self.valid_failed_slots = set(state.get("valid_failed_slots", []))
        self.invalid_attempt_count = state.get("invalid_attempt_count", 0)
        self.abort_reason = state.get("abort_reason")
        self.cleanup_verified = state.get("cleanup_verified", False)
        self.primary_failure = state.get("primary_failure")

        # Restore attempt history
        self.attempts = []
        for a in state.get("attempts", []):
            slot = MatrixSlot(
                idle_s=a["matrix_slot"]["idle_s"],
                wake_only=a["matrix_slot"]["wake_only"],
            )
            attempt = MatrixAttempt(
                trial_id=a["trial_id"],
                matrix_slot=slot,
                attempt=a["attempt"],
                status=AttemptStatus(a["status"]),
                classification=FailureClassification(a["classification"]) if a.get("classification") else None,
                invalid_reason=InvalidReason(a["invalid_reason"]) if a.get("invalid_reason") else None,
            )
            self.attempts.append(attempt)

        # Verify preflight hash
        preflight_path = self.run_dir / "preflight-private.json"
        if preflight_path.exists():
            preflight = json.loads(preflight_path.read_text())
            current_hash = hashlib.sha256(json.dumps(preflight, sort_keys=True).encode()).hexdigest()
            if state.get("preflight_hash") and state["preflight_hash"] != current_hash:
                raise HarnessError("preflight manifest has changed since checkpoint was created")

        return state

    # ── Preflight ────────────────────────────────────────────────────

    def run_preflight(self, fixture_set_id: str, fixture_hashes: dict[str, str]) -> dict[str, Any]:
        """Run the human-monitored audibility preflight and return approved manifest."""
        print(f"\n=== Audibility Preflight: {fixture_set_id} ===\n")

        # Verify device identities and roles
        if not self.source.reachable():
            raise HarnessError(f"{self.source_alias}: source not reachable")
        if not self.target.reachable():
            raise HarnessError(f"{self.target_alias}: target not reachable")

        self.source_identity = device_identity(self.source, self.source_alias, self.package)
        self.target_identity = device_identity(self.target, self.target_alias, self.package)
        print(f"Source: {self.source_identity.public()}")
        print(f"Target: {self.target_identity.public()}")

        # Verify source wake disabled
        if service_active(self.source, self.package):
            raise HarnessError(f"{self.source_alias}: wake service must be disabled on the source device")

        # Verify target wake enabled
        if not service_active(self.target, self.package):
            raise HarnessError(f"{self.target_alias}: wake service must be active on the target device")

        # Snapshot audio state
        source_audio = self._snapshot_audio_state(self.source, self.source_alias)
        target_audio = self._snapshot_audio_state(self.target, self.target_alias)

        # Check no active external Bluetooth audio
        if self._has_active_bluetooth_route(self.source):
            raise HarnessError(f"{self.source_alias}: active external Bluetooth audio route detected")
        if self._has_active_bluetooth_route(self.target):
            raise HarnessError(f"{self.target_alias}: active external Bluetooth audio route detected")

        # Read fixture manifest
        fixture_manifest = self._read_fixture_manifest()
        print(f"Fixture manifest: {len(fixture_manifest.get('fixtures', []))} fixtures")

        # Verify natural_wake fixture exists
        wake_fixture = None
        for f in fixture_manifest.get("fixtures", []):
            if f["fixture_id"] == "natural_wake":
                wake_fixture = f
                break
        if not wake_fixture:
            raise HarnessError("required fixture 'natural_wake' not found in fixture manifest")

        # Start at conservative volume (60% of max)
        conservative_volume = max(1, int(self._get_media_max_volume(self.source) * 0.6))
        print(f"Starting at conservative volume index: {conservative_volume}")

        preflight_data = {
            "schema_version": 1,
            "fixture_set_id": fixture_set_id,
            "fixture_hashes": fixture_hashes or {},
            "approved_at": utc_now(),
            "source_role": self.source_alias,
            "target_role": self.target_alias,
            "source_identity": self.source_identity.public(),
            "target_identity": self.target_identity.public(),
            "source_audio_state": source_audio,
            "target_audio_state": target_audio,
            "source_volume_index": conservative_volume,
            "source_volume_max": self._get_media_max_volume(self.source),
            "source_route": "builtin_speaker",
            "placement_notes": "",
            "operator_approved": False,
            "attempts": [],
            "uptime_source": self.source.shell("cat", "/proc/uptime").strip().split()[0] if self.source.reachable() else "unknown",
            "uptime_target": self.target.shell("cat", "/proc/uptime").strip().split()[0] if self.target.reachable() else "unknown",
        }

        # Store preflight attempts
        preflight_data["attempts"].append({
            "attempt": len(preflight_data["attempts"]) + 1,
            "volume_index": conservative_volume,
            "fixture_id": "natural_wake",
            "source_completed": None,
            "target_gate_evidence": None,
        })

        # Write preliminary preflight
        self.private_write("", "preflight-private.json", json.dumps(preflight_data, indent=2))

        if not self.interactive:
            raise HarnessError(
                "preflight requires --interactive mode for operator confirmation. "
                "Run again with --interactive after reviewing device state."
            )

        # Interactive preflight loop (bounded adjustments only)
        approved = False
        current_volume = conservative_volume
        for adj_attempt in range(3):  # At most 3 adjustment opportunities
            operator_prompt = (
                f"\n=== Operator Confirmation Required ===\n"
                f"Source: {self.source_alias} at volume {current_volume}\n"
                f"Target: {self.target_alias}\n"
                f"Enter:\n"
                f"  APPROVE - freeze current setup and continue\n"
                f"  UP <N>  - increase volume by N (max 3)\n"
                f"  DOWN <N> - decrease volume by N (max 3)\n"
                f"  ABORT   - cancel preflight\n"
                f"> "
            )
            choice = input(operator_prompt).strip().lower()

            if choice == "approve":
                approved = True
                break
            elif choice.startswith("up"):
                try:
                    delta = int(choice.split()[1])
                    delta = max(1, min(3, delta))
                    current_volume = min(self._get_media_max_volume(self.source), current_volume + delta)
                except (ValueError, IndexError):
                    print("Invalid adjustment. Use UP <N> where N is 1-3.")
                    continue
            elif choice.startswith("down"):
                try:
                    delta = int(choice.split()[1])
                    delta = max(1, min(3, delta))
                    current_volume = max(1, current_volume - delta)
                except (ValueError, IndexError):
                    print("Invalid adjustment. Use DOWN <N> where N is 1-3.")
                    continue
            elif choice == "abort":
                raise HarnessError("preflight cancelled by operator")
            else:
                print("Unknown command. Use APPROVE, UP <N>, DOWN <N>, or ABORT.")
                continue

            # Record adjustment attempt
            preflight_data["attempts"].append({
                "attempt": len(preflight_data["attempts"]) + 1,
                "volume_index": current_volume,
                "fixture_id": "natural_wake",
                "source_completed": None,
                "target_gate_evidence": None,
            })
            self.private_write("", "preflight-private.json", json.dumps(preflight_data, indent=2))

        if not approved:
            raise HarnessError("operator did not approve preflight setup")

        # Apply final volume
        self.source.shell(
            "settings", "put", "global", "media_volume",
            str(current_volume),
        )

        # Play test wake fixture
        print(f"Playing natural_wake at volume {current_volume} for preflight check...")
        result = self._invoke_source(
            trial_id="preflight-001",
            fixture_id="natural_wake",
            volume_index=current_volume,
        )
        preflight_data["attempts"][-1]["source_completed"] = result.get("completion_status") == "completed"

        # Check for target gate evidence
        code, snap_data = self._call_target_broadcast(
            TARGET_ACTION_GET_SNAPSHOT,
            extras={"since_sequence": 0},
        )
        try:
            if code == 0 and snap_data:
                snap_parsed = parse_journal_snapshot(snap_data)
                events = snap_parsed.get("events", [])
            else:
                events = []
            has_gate = any(
                e.get("t") in ("SILENCE_GATE_ENTERED", "VOICED_FRAME_AFTER_SILENCE", "STAGE3_READY")
                for e in events
            )
            preflight_data["attempts"][-1]["target_gate_evidence"] = has_gate
        except (json.JSONDecodeError, HarnessError):
            preflight_data["attempts"][-1]["target_gate_evidence"] = None

        preflight_data["source_volume_index"] = current_volume
        preflight_data["operator_approved"] = True
        preflight_data["placement_notes"] = input("Enter placement notes (distance, orientation): ").strip()

        self.preflight_approval = preflight_data
        self.private_write("", "preflight-private.json", json.dumps(preflight_data, indent=2))

        approval_hash = hashlib.sha256(json.dumps(preflight_data, sort_keys=True).encode()).hexdigest()
        print(f"\nPreflight approved. Hash: {approval_hash}")
        return preflight_data

    # ── Trial lifecycle ──────────────────────────────────────────────

    def run_trial(self, trial_id: str, matrix_slot: MatrixSlot,
                  fixture_id: str, command_fixture_id: str | None = None) -> MatrixAttempt:
        attempt = MatrixAttempt(
            trial_id=trial_id,
            matrix_slot=matrix_slot,
            attempt=self._attempt_number_for(matrix_slot),
            status=AttemptStatus.INVALID,
        )
        host_start = monotonic_ms()
        attempt.host_start_ms = host_start
        try:
            # 2. Target boundary snapshot and sequence
            seq_code, seq_data = self._call_target_broadcast(
                TARGET_ACTION_GET_SEQUENCE,
            )
            boundary_sequence = parse_journal_sequence(seq_data) if seq_code == 0 and seq_data else 0
            snap_code, snap_data_before = self._call_target_broadcast(
                TARGET_ACTION_GET_SNAPSHOT,
                extras={"since_sequence": 0},
            )
            self.checkpoint("pre-idle")

            # 3. Verify target service, uptime, screen, charging
            target_state = self._snapshot_target_state()
            pre_uptime = float(target_state.get("uptime_seconds", 0))

            # 4. Idle interval — no target ADB during this period
            self.checkpoint("idle-start")
            idle_start_ms = monotonic_ms()
            if matrix_slot.idle_s > 0:
                remaining = matrix_slot.idle_s * 1000
                while remaining > 0 and not self._cancel.is_set():
                    wait = min(remaining, 100)
                    time.sleep(wait / 1000)
                    remaining -= wait
            if self._cancel.is_set():
                raise HarnessError("cancelled during idle")
            self.checkpoint("post-idle")

            # 5. Play wake fixture
            source_result = self._invoke_source(
                trial_id=trial_id,
                fixture_id=fixture_id,
                volume_index=(
                    self.preflight_approval["source_volume_index"]
                    if self.preflight_approval else 7
                ),
            )
            self.checkpoint("post-source")

            # 6. Parse source result
            parsed_source = parse_source_result(json.dumps(source_result))

            # 7. Wait for target event (wake-only: STT_READY; wake+command also)
            events, envelope = self._wait_for_target_events(
                since_sequence=boundary_sequence,
                event_type="STT_READY",
                timeout_ms=DEFAULT_WAIT_TIMEOUT_MS,
            )

            gen_id = 1
            session_id = 1
            # Extract gen/session from first WAKE_CALLBACK_INVOKED or VOICE_SESSION_STARTED
            for ev in envelope.get("events", events):
                if ev.get("t") == "WAKE_CALLBACK_INVOKED":
                    gen_id = ev.get("g", 1)
                if ev.get("t") == "VOICE_SESSION_STARTED":
                    session_id = ev.get("i", 1)
                    gen_id = ev.get("g", gen_id)
                    break

            # 8. Command playback for wake+command trials
            if not matrix_slot.wake_only and command_fixture_id:
                if self.is_feasibility and self.fixed_command_delay_ms:
                    time.sleep(self.fixed_command_delay_ms / 1000)
                else:
                    # Event-driven: wait cue clearance
                    time.sleep(self.cue_margin_ms / 1000)

                cmd_source_result = self._invoke_source(
                    trial_id=f"{trial_id}-cmd",
                    fixture_id=command_fixture_id,
                    volume_index=(
                        self.preflight_approval["source_volume_index"]
                        if self.preflight_approval else 7
                    ),
                )
                parsed_source["command_result"] = cmd_source_result

            # 9. Final target snapshot
            self.checkpoint("post-playback")
            final_code, final_data = self._call_target_broadcast(
                TARGET_ACTION_GET_SNAPSHOT,
                extras={"since_sequence": boundary_sequence},
            )
            self.checkpoint("post-snapshot")

            # 10. Parse and validate
            snapshot: dict[str, Any] = {"events": events}
            if final_code == 0 and final_data:
                try:
                    snapshot = parse_journal_snapshot(final_data)
                except HarnessError:
                    pass  # Use pre-wait events as fallback

            # 11. Verify target post-state
            post_state = self._snapshot_target_state()
            post_uptime = float(post_state.get("uptime_seconds", 0))
            if abs(post_uptime - pre_uptime) > 2:
                attempt.failures.append("target uptime reset detected")
                attempt.status = AttemptStatus.INVALID
                attempt.invalid_reason = InvalidReason.DEVICE_ENVIRONMENT_ERROR

            # 12. Verify cleanup
            self._verify_source_restoration()

            # 13. Classify
            cue_policy_ok = (
                self.manifest is not None
                and self.manifest.cue_policy_version is not None
            )
            status, classification, invalid_reason, failures = classify_attempt(
                source_result=parsed_source,
                target_snapshot=snapshot,
                trial_type=TrialType.WAKE_PLUS_COMMAND if not matrix_slot.wake_only else TrialType.WAKE_ONLY,
                gen_id=gen_id,
                session_id=session_id,
                cue_policy_available=cue_policy_ok,
            )

            attempt.status = status
            attempt.classification = classification
            attempt.invalid_reason = invalid_reason
            attempt.failures.extend(failures)

        except HarnessError as e:
            attempt.status = AttemptStatus.INVALID
            attempt.invalid_reason = InvalidReason.UNKNOWN
            attempt.failures.append(str(e))
            if self.primary_failure is None:
                self.primary_failure = str(e)

        finally:
            attempt.host_duration_ms = monotonic_ms() - host_start
            self.attempts.append(attempt)

            # Track slot completion
            slot_key = f"{matrix_slot.idle_s}:{'wo' if matrix_slot.wake_only else 'wc'}"
            attempt_key = f"{slot_key}:{attempt.attempt}"
            self.completed_slots.add(attempt_key)

            if attempt.status == AttemptStatus.FAILED:
                self.valid_failed_slots.add(slot_key)
            elif attempt.status == AttemptStatus.INVALID:
                self.invalid_attempt_count += 1

            self.checkpoint()

        return attempt

    # ── Matrix scheduler ─────────────────────────────────────────────

    def run_matrix(self, fixture_id: str = "natural_wake",
                   command_fixture_id: str | None = "qwen_command") -> None:
        """Run through the frozen matrix."""
        slots = matrix_slots_for_target(self.target_alias)

        # Group slots by idle_s for prioritised scheduling
        trial_index = 1
        for slot in slots:
            if self._cancel.is_set():
                break

            slot_key = f"{slot.idle_s}:{'wo' if slot.wake_only else 'wc'}"

            # Skip if this slot already has a valid pass
            if self._slot_has_pass(slot):
                print(f"Skipping completed slot {slot_key} (already passed)")
                continue

            # Check invalid retry limit
            if self._invalid_count_for(slot) >= VALID_MAX_ATTEMPTS:
                print(f"Skipping slot {slot_key}: exceeded invalid retry limit")
                continue

            # Check if a valid failure exists for this slot
            if slot_key in self.valid_failed_slots:
                print(f"Skipping slot {slot_key}: valid failure exists, not retrying")
                continue

            trial_id = f"trial-{self.run_id}-{trial_index:03d}"
            trial_index += 1

            command_fx = command_fixture_id if not slot.wake_only else None
            attempt = self.run_trial(
                trial_id=trial_id,
                matrix_slot=slot,
                fixture_id=fixture_id,
                command_fixture_id=command_fx,
            )

            status_str = attempt.status.value
            cls_str = (
                attempt.classification.value if attempt.classification
                else attempt.invalid_reason.value if attempt.invalid_reason
                else ""
            )
            print(
                f"Trial {trial_id}: idle={slot.idle_s}s "
                f"{'wake-only' if slot.wake_only else 'wake+cmd'} "
                f"→ {status_str}"
                + (f" [{cls_str}]" if cls_str else "")
            )

    def _slot_has_pass(self, slot: MatrixSlot) -> bool:
        """Check if this matrix slot already has a valid pass attempt."""
        key = f"{slot.idle_s}:{'wo' if slot.wake_only else 'wc'}"
        for a in self.attempts:
            if a.matrix_slot.idle_s != slot.idle_s:
                continue
            if a.matrix_slot.wake_only != slot.wake_only:
                continue
            if a.status == AttemptStatus.PASSED:
                return True
        return False

    def _invalid_count_for(self, slot: MatrixSlot) -> int:
        key = f"{slot.idle_s}:{'wo' if slot.wake_only else 'wc'}"
        return sum(
            1 for a in self.attempts
            if a.matrix_slot.idle_s == slot.idle_s
            and a.matrix_slot.wake_only == slot.wake_only
            and a.status == AttemptStatus.INVALID
        )

    def _attempt_number_for(self, slot: MatrixSlot) -> int:
        key = f"{slot.idle_s}:{'wo' if slot.wake_only else 'wc'}"
        return sum(
            1 for a in self.attempts
            if a.matrix_slot.idle_s == slot.idle_s
            and a.matrix_slot.wake_only == slot.wake_only
        ) + 1

    def _verify_state(self) -> None:
        """Verify device roles before preflight or trial execution."""
        if service_active(self.source, self.package):
            raise HarnessError(f"{self.source_alias}: wake service must be disabled on source device")
        if not service_active(self.target, self.package):
            raise HarnessError(f"{self.target_alias}: wake service must be active on target device")

    def verify_preflight_approval(self) -> None:
        """Verify current state matches approved preflight manifest."""
        if not self.preflight_approval:
            raise HarnessError("no preflight approval available")

        if not self.target.reachable():
            raise HarnessError("target not reachable; preflight conditions may have changed")

        current_uptime = self.target.shell("cat", "/proc/uptime").strip().split()[0] if self.target.reachable() else "0"
        approved_uptime = self.preflight_approval.get("uptime_target", current_uptime)
        try:
            if abs(float(current_uptime) - float(approved_uptime)) < 2:
                raise ValueError  # uptime reset
        except (ValueError, TypeError):
            raise HarnessError("target has rebooted since preflight approval")

        if not service_active(self.target, self.package):
            raise HarnessError("target wake service is no longer active")

    # ── ADB helpers ──────────────────────────────────────────────────

    def _snapshot_audio_state(self, client: AdbClient, alias: str) -> dict[str, Any]:
        """Capture current audio state for a device."""
        volume = client.shell("settings", "get", "global", "media_volume").strip()
        ringer = client.shell("settings", "get", "global", "mode_ringer").strip()
        dnd = client.shell("settings", "get", "global", "zen_mode").strip()
        return {
            "alias": alias,
            "media_volume": volume,
            "ringer_mode": ringer,
            "dnd_mode": dnd,
            "monotonic_ms": monotonic_ms(),
        }

    def _get_media_max_volume(self, client: AdbClient) -> int:
        try:
            return int(client.shell("settings", "get", "global", "media_volume_max").strip())
        except (HarnessError, ValueError):
            return 25  # conservative default

    def _has_active_bluetooth_route(self, client: AdbClient) -> bool:
        """Check for active Bluetooth audio route."""
        try:
            routes = client.shell("dumpsys", "audio").lower()
            return "a2dp" in routes or "bluetooth_sco" in routes
        except HarnessError:
            return False

    def _snapshot_target_state(self) -> dict[str, Any]:
        if not self.target.reachable():
            return {"uptime_seconds": 0, "reachable": False}
        uptime_text = self.target.shell("cat", "/proc/uptime").strip()
        uptime = float(uptime_text.split()[0]) if uptime_text else 0.0
        deviceidle = self.target.shell("dumpsys", "deviceidle")
        screen_off = "mScreenOn=false" in self.target.shell("dumpsys", "power")
        return {
            "uptime_seconds": uptime,
            "screen_off": screen_off,
            "service_active": service_active(self.target, self.package),
            "reachable": True,
        }

    def _read_fixture_manifest(self) -> dict[str, Any]:
        if not self.fixture_dir:
            return {"fixtures": []}
        try:
            manifest_text = self.source.shell(
                "cat", f"{self.fixture_dir}/manifest.json"
            )
            return json.loads(manifest_text)
        except (HarnessError, json.JSONDecodeError, FileNotFoundError):
            return {"fixtures": []}

    def _invoke_source(self, trial_id: str, fixture_id: str,
                       volume_index: int) -> dict[str, Any]:
        """Send play broadcast to source device and collect result."""
        if not self.source.reachable():
            raise HarnessError("source ADB not reachable")

        # Build broadcast args
        broadcast_args = [
            "shell", "am", "broadcast",
            "-n", f"{self.package}/{SOURCE_RECEIVER_CLS}",
            "-a", SOURCE_ACTION,
            "--es", "trial_id", trial_id,
            "--es", "fixture_id", fixture_id,
            "--ei", "volume_index", str(volume_index),
        ]

        # Send broadcast and wait for result
        result_text = self.source.run(*broadcast_args, timeout=DEFAULT_SOURCE_TIMEOUT_S)

        # Read private source result from app storage
        try:
            result_file_text = self.source.shell(
                "run-as", self.package,
                "cat", f"files/acoustic-stimulus-results/{trial_id}.json",
            )
            result = json.loads(result_file_text)
        except (HarnessError, json.JSONDecodeError, FileNotFoundError) as e:
            raise HarnessError(f"failed to read source result: {e}")

        return result

    def _call_target_broadcast(self, action: str, extras: dict[str, Any] | None = None,
                                timeout: float = 15.0) -> tuple[int, str]:
        """Send an ordered broadcast to the target journal receiver.

        Returns (result_code, result_data). The broadcast blocks up to
        *timeout* seconds. For WAIT_FOR_JOURNAL_EVENT the receiver waits
        the requested duration internally so *timeout* should account for
        that.
        """
        args = [
            "shell", "am", "broadcast",
            "-n", f"{self.package}/{TARGET_RECEIVER_CLS}",
            "-a", action,
        ]
        if extras:
            for key, value in extras.items():
                if isinstance(value, bool):
                    args.extend(["--ez", key, str(value).lower()])
                elif isinstance(value, int):
                    args.extend(["--el", key, str(value)])
                else:
                    args.extend(["--es", key, str(value)])

        raw = self.target.run(*args, timeout=timeout)
        return self._parse_broadcast_result(raw)

    @staticmethod
    def _parse_broadcast_result(output: str) -> tuple[int, str]:
        """Parse result_code and result_data from ``am broadcast`` output.

        Handles both ``result=0, data="..."`` (older am) and
        ``result_code=0, result_data="..."`` formats.  The result_data may
        contain characters that interact with shell quoting — we take
        everything after the first ``"`` following the ``=`` to the end,
        then strip trailing ``"`` and whitespace.
        """
        code_match = re.search(r"(?:result_code|result)\s*=\s*(\d+)", output)
        code = int(code_match.group(1)) if code_match else 2

        # Find data= or result_data= and grab the quoted value
        data_match = re.search(
            r"(?:result_data|data)\s*=\s*\"(.+)$",
            output, re.DOTALL,
        )
        if data_match:
            raw_data = data_match.group(1)
            # Strip trailing " that closes the shell-quoted value
            if raw_data.endswith('"'):
                raw_data = raw_data[:-1]
            data = raw_data.strip()
        else:
            data = ""

        return code, data

    def _wait_for_target_events(
        self, since_sequence: int, event_type: str, timeout_ms: int,
    ) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        """Wait for event via WAIT_FOR_JOURNAL_EVENT, then get full snapshot."""
        broadcast_timeout = max(timeout_ms / 1000 + 2.0, 20.0)
        code, data = self._call_target_broadcast(
            TARGET_ACTION_WAIT_FOR_EVENT,
            extras={"since_sequence": since_sequence,
                    "event_type": event_type,
                    "timeout_ms": timeout_ms},
            timeout=broadcast_timeout,
        )

        try:
            event = parse_journal_wait_result(code, data)
        except HarnessError:
            event = None

        if event is None and code != 0:
            # Timeout or non-recoverable error — no event
            pass

        # Get full snapshot
        snap_code, snap_data = self._call_target_broadcast(
            TARGET_ACTION_GET_SNAPSHOT,
            extras={"since_sequence": since_sequence},
        )

        snapshot_events: list[dict[str, Any]] = []
        if snap_code == 0 and snap_data:
            try:
                parsed = parse_journal_snapshot(snap_data)
                snapshot_events = parsed.get("events", [])
            except HarnessError:
                pass

        if event is not None and event not in snapshot_events:
            snapshot_events.insert(0, event)

        envelope: dict[str, Any] = {"events": snapshot_events}
        return snapshot_events, envelope

    def _verify_source_restoration(self) -> None:
        """Verify source media volume was restored to pre-playback level."""
        if not self.preflight_approval or not self.source.reachable():
            return
        try:
            current = int(
                self.source.shell("settings", "get", "global", "media_volume").strip()
            )
            expected = self.preflight_approval.get("source_volume_index")
            if expected is not None and current != expected:
                raise HarnessError(
                    f"source volume not restored: expected {expected}, got {current}"
                )
        except (HarnessError, ValueError):
            pass

    # ── Cleanup ──────────────────────────────────────────────────────
    def cleanup(self) -> None:
        """Clean up after run completion or failure."""

        cleanup_failures: list[str] = []

        # The target journal has no active-wait cancellation mechanism.
        # An in-flight WAIT_FOR_JOURNAL_EVENT broadcast will complete
        # naturally with its timeout; there is no separate cancel action.

        # Restore source volume

        if self.source and self.source.reachable():
            try:
                if self.preflight_approval:
                    vol = self.preflight_approval.get("source_volume_index")
                    if vol is not None:
                        self.source.shell("settings", "put", "global", "media_volume", str(vol))
            except Exception as e:
                cleanup_failures.append(f"volume restoration: {e}")

        if cleanup_failures:
            self.cleanup_verified = False
            if self.primary_failure is None:
                self.primary_failure = "; ".join(cleanup_failures)
        else:
            self.cleanup_verified = True

    def is_matrix_complete(self) -> bool:
        """Check whether all required valid trials are present."""
        slots = matrix_slots_for_target(self.target_alias)
        for slot in slots:
            if not self._slot_has_pass(slot):
                # Check if slot has a valid failure (not retried)
                key = f"{slot.idle_s}:{'wo' if slot.wake_only else 'wc'}"
                if key in self.valid_failed_slots:
                    continue  # Valid failures are not retried
                return False
        return True

    def export_evidence(self) -> dict[str, Any]:
        """Build, sanitise, and write evidence."""
        if not self.target_identity or not self.source_identity:
            raise HarnessError("device identities not available")

        preflight = self.preflight_approval

        # Build cue policy version
        cue_version = None
        if self.manifest and self.manifest.cue_policy_version:
            cue_version = self.manifest.cue_policy_version
        elif self.gate_mode == GateMode.RELEASE:
            raise HarnessError("release-gate mode requires cue policy evidence (#1405)")

        fixture_hashes: dict[str, str] = (preflight or {}).get("fixture_hashes", {})
        fixture_set_id: str | None = (preflight or {}).get("fixture_set_id")

        run_manifest = RunManifest(
            run_id=self.run_id,
            run_kind=self.run_kind,
            gate_mode=self.gate_mode,
            matrix_id=MATRIX_ID,
            matrix_version=MATRIX_VERSION,
            created_utc=utc_now(),
            source_alias=self.source_alias,
            target_alias=self.target_alias,
            fixture_set_id=fixture_set_id,
            fixture_hashes=fixture_hashes,
            cue_policy_version=cue_version,
            preflight_hash=(
                hashlib.sha256(json.dumps(preflight, sort_keys=True).encode()).hexdigest()
                if preflight else None
            ),
        )

        evidence = render_evidence(
            run_manifest=run_manifest,
            target_identity=self.target_identity,
            source_identity=self.source_identity,
            source_helper_version="1.0.0",
            attempts=self.attempts,
            preflight_approval=preflight,
            cleanup_verified=self.cleanup_verified,
            source_route=(preflight or {}).get("source_route"),
            secrets=self.secrets,
        )

        self.sanitized_dir.mkdir(parents=True, exist_ok=True)
        write_sanitized_summary(self.sanitized_dir, evidence, self.secrets)

        # Check completeness
        if self.gate_mode == GateMode.RELEASE and not self.is_matrix_complete():
            evidence["wake_reliability"]["matrix_incomplete"] = True
            evidence["summary"]["note"] = "MATRIX INCOMPLETE — not publishable as release evidence"

        return evidence

    # ─── Cancellation ────────────────────────────────────────────────

    def cancel(self) -> None:
        """Request cancellation."""
        self._cancel.set()


# ── CLI and modes ─────────────────────────────────────────────────────

def fixture_mode() -> int:
    """Dry-run/fixture mode: validate configuration without ADB."""
    print("=== Acoustic Wake Reliability Runner — Fixture Mode ===\n")
    print("No ADB devices required. Validating matrix and contracts...\n")

    # Validate frozen matrix
    for alias in ("s21", "s23u"):
        matrix = get_matrix(alias)
        total = sum(wake + wpc for _, wake, wpc in matrix)
        print(f"  {alias}: {len(matrix)} intervals, {total} valid trials required")
        for idle_s, wake, wpc in matrix:
            print(f"    {idle_s:>5}s: {wake} wake-only, {wpc} wake+command")

    # Validate expected devices
    for alias, expected in EXPECTED_DEVICES.items():
        print(f"  Device '{alias}': {expected['manufacturer']} {expected['model']}")

    # Validate contract versions
    print(f"\n  Source helper contract: 1.0.0")
    print(f"  Target journal contract: {JOURNAL_CONTRACT_VERSION}")
    print(f"  Matrix ID: {MATRIX_ID} v{MATRIX_VERSION}")

    # Test classification
    sample_source = {
        "trial_id": "fixture-test",
        "fixture_id": "natural_wake",
        "volume_applied": 7,
        "player_completed": True,
        "cleanup_completed": True,
        "cleanup_verified": True,
        "route": "builtin_speaker",
    }
    sample_events = [
        {"s": 1, "m": 100, "t": "DETECTOR_GENERATION_STARTED", "g": 1},
        {"s": 2, "m": 200, "t": "VOICED_FRAME_AFTER_SILENCE", "g": 1},
        {"s": 3, "m": 300, "t": "ACTIVATION_CANDIDATE", "g": 1},
        {"s": 4, "m": 400, "t": "VERIFIED_ACTIVATION", "g": 1},
        {"s": 5, "m": 500, "t": "WAKE_CALLBACK_INVOKED", "g": 1},
        {"s": 6, "m": 600, "t": "VOICE_SESSION_STARTED", "g": 1, "i": 1},
        {"s": 7, "m": 700, "t": "STT_READY", "g": 1, "i": 1},
        {"s": 8, "m": 800, "t": "CUE_REQUESTED", "g": 1},
        {"s": 9, "m": 900, "t": "STT_PARTIAL", "g": 1},
        {"s": 10, "m": 1000, "t": "SESSION_COMPLETED", "g": 1, "i": 1},
        {"s": 11, "m": 1100, "t": "DETECTOR_REARMED", "g": 2},
    ]
    sample_snapshot = {
        "lowest_sequence": 1,
        "highest_sequence": 11,
        "overflowed": False,
        "events": sample_events,
    }

    status, cls, reason, failures = classify_attempt(
        source_result=sample_source,
        target_snapshot=sample_snapshot,
        trial_type=TrialType.WAKE_ONLY,
        gen_id=1,
        session_id=1,
        cue_policy_available=False,
    )
    print(f"\n  Sample classification: {status.value}")
    if cls:
        print(f"    Would classify as: {cls.value}")
    if reason:
        print(f"    Invalid: {reason.value}")

    # Verify matrix slots expansion
    slots_s21 = matrix_slots_for_target("s21")
    slots_s23u = matrix_slots_for_target("s23u")
    print(f"\n  S21 expanded slots: {len(slots_s21)}")
    print(f"  S23U expanded slots: {len(slots_s23u)}")

    # Verify same-device rejection
    try:
        _ = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s21",
            target_alias="s21",
            source_client=AdbClient("fake"),
            target_client=AdbClient("fake"),
        )
        print("\n  ⚠ SAME-DEVICE CHECK FAILED: no error raised")
    except HarnessError:
        print("  Same-device check: PASS")

    print("\nFixture mode complete. No ADB commands executed.")
    return 0


def smoke_mode(args: argparse.Namespace) -> int:
    """Short physical smoke test."""
    print("=== Short Physical Smoke ===\n")

    source_client = AdbClient(args.source_selector)
    target_client = AdbClient(args.target_selector)

    runner = AcousticWakeReliabilityRunner(
        run_kind=RunKind.SMOKE,
        source_alias=args.source,
        target_alias=args.target,
        source_client=source_client,
        target_client=target_client,
        fixture_dir=Path(args.fixture_dir) if args.fixture_dir else None,
        interactive=True,
    )

    # Wire secrets for sanitisation
    runner.secrets = [args.source_selector, args.target_selector]

    try:
        runner.run_preflight(
            fixture_set_id=args.fixture_set_id or "v1-smoke",
            fixture_hashes=args.fixture_hashes or {},
        )
        print("\nPreflight complete. Running smoke trials...\n")

        # Run a few smoke trials
        runner.run_matrix(
            fixture_id=args.fixture_id or "natural_wake",
            command_fixture_id=args.command_fixture_id,
        )
    except HarnessError as e:
        runner.primary_failure = str(e)
        print(f"\nSmoke error: {e}")
    except KeyboardInterrupt:
        print("\nInterrupted")
        runner.cancel()
    finally:
        runner.cleanup()
        evidence = runner.export_evidence()

    print(f"\nSmoke complete. Evidence: {runner.sanitized_dir}")
    return 0 if runner.is_matrix_complete() else 1


def preflight_mode(args: argparse.Namespace) -> int:
    """Human-monitored preflight mode."""
    source_client = AdbClient(args.source_selector)
    target_client = AdbClient(args.target_selector)

    runner = AcousticWakeReliabilityRunner(
        run_kind=RunKind.PREFLIGHT,
        source_alias=args.source,
        target_alias=args.target,
        source_client=source_client,
        target_client=target_client,
        fixture_dir=Path(args.fixture_dir) if args.fixture_dir else None,
        interactive=True,
    )
    runner.secrets = [args.source_selector, args.target_selector]

    try:
        runner.run_preflight(
            fixture_set_id=args.fixture_set_id or "v1-preflight",
            fixture_hashes=args.fixture_hashes or {},
        )
    except HarnessError as e:
        print(f"Preflight error: {e}")
        return 1

    print(f"\nPreflight manifest: {runner.run_dir / 'preflight-private.json'}")
    return 0


def diagnostic_mode(args: argparse.Namespace) -> int:
    """Diagnostic pre-fix matrix mode."""
    source_client = AdbClient(args.source_selector)
    target_client = AdbClient(args.target_selector)

    runner = AcousticWakeReliabilityRunner(
        run_kind=RunKind.DIAGNOSTIC,
        source_alias=args.source,
        target_alias=args.target,
        source_client=source_client,
        target_client=target_client,
        fixture_dir=Path(args.fixture_dir) if args.fixture_dir else None,
        cue_margin_ms=args.cue_margin_ms,
        interactive=args.interactive,
    )
    runner.secrets = [args.source_selector, args.target_selector]

    try:
        runner.run_preflight(
            fixture_set_id=args.fixture_set_id or "v1-diagnostic",
            fixture_hashes=args.fixture_hashes or {},
        )
        print("\nPreflight approved. Running diagnostic matrix...\n")
        runner.run_matrix(
            fixture_id=args.fixture_id or "natural_wake",
            command_fixture_id=args.command_fixture_id,
        )
    except HarnessError as e:
        runner.primary_failure = str(e)
        print(f"Error: {e}")
    except KeyboardInterrupt:
        print("\nInterrupted")
        runner.cancel()
    finally:
        runner.cleanup()
        evidence = runner.export_evidence()

    print(f"\nDiagnostic complete. Evidence: {runner.sanitized_dir}")
    return 0


def regression_mode(args: argparse.Namespace) -> int:
    """Post-fix release-gate regression matrix."""
    source_client = AdbClient(args.source_selector)
    target_client = AdbClient(args.target_selector)

    runner = AcousticWakeReliabilityRunner(
        run_kind=RunKind.REGRESSION,
        source_alias=args.source,
        target_alias=args.target,
        source_client=source_client,
        target_client=target_client,
        fixture_dir=Path(args.fixture_dir) if args.fixture_dir else None,
        cue_margin_ms=args.cue_margin_ms,
        interactive=args.interactive,
    )
    runner.secrets = [args.source_selector, args.target_selector]

    if not args.cue_policy_version:
        raise HarnessError("release-gate mode requires --cue-policy-version from #1405")

    try:
        runner.run_preflight(
            fixture_set_id=args.fixture_set_id or "v1-regression",
            fixture_hashes=args.fixture_hashes or {},
        )
        runner.manifest = RunManifest(
            run_id=runner.run_id,
            run_kind=RunKind.REGRESSION,
            gate_mode=GateMode.RELEASE,
            matrix_id=MATRIX_ID,
            matrix_version=MATRIX_VERSION,
            created_utc=utc_now(),
            source_alias=args.source,
            target_alias=args.target,
            fixture_set_id=args.fixture_set_id or "v1-regression",
            fixture_hashes=args.fixture_hashes or {},
            cue_policy_version=args.cue_policy_version,
            preflight_hash=None,
        )
        print("\nPreflight approved. Running regression matrix...\n")
        runner.run_matrix(
            fixture_id=args.fixture_id or "natural_wake",
            command_fixture_id=args.command_fixture_id,
        )

        if not runner.is_matrix_complete():
            print("WARNING: Matrix incomplete. Not publishable as release evidence.")
    except HarnessError as e:
        runner.primary_failure = str(e)
        print(f"Error: {e}")
    except KeyboardInterrupt:
        print("\nInterrupted")
        runner.cancel()
    finally:
        runner.cleanup()
        evidence = runner.export_evidence()

    print(f"\nRegression complete. Evidence: {runner.sanitized_dir}")
    return 0 if (runner.is_matrix_complete() and args.cue_policy_version) else 1


def feasibility_mode(args: argparse.Namespace) -> int:
    """Explicitly labelled feasibility fixed-delay mode."""
    source_client = AdbClient(args.source_selector)
    target_client = AdbClient(args.target_selector)

    runner = AcousticWakeReliabilityRunner(
        run_kind=RunKind.FEASIBILITY,
        source_alias=args.source,
        target_alias=args.target,
        source_client=source_client,
        target_client=target_client,
        fixture_dir=Path(args.fixture_dir) if args.fixture_dir else None,
        fixed_command_delay_ms=args.fixed_command_delay_ms or 5000,
        interactive=True,
    )
    runner.secrets = [args.source_selector, args.target_selector]

    print("=== FEASIBILITY MODE === Command timing uses fixed delays, not event-driven.")
    print("This mode is NON-EVIDENTIARY for final release-gate classification.\n")

    try:
        runner.run_preflight(
            fixture_set_id=args.fixture_set_id or "v1-feasibility",
            fixture_hashes=args.fixture_hashes or {},
        )
        runner.run_matrix(
            fixture_id=args.fixture_id or "natural_wake",
            command_fixture_id=args.command_fixture_id,
        )
    except HarnessError as e:
        runner.primary_failure = str(e)
        print(f"Error: {e}")
    except KeyboardInterrupt:
        print("\nInterrupted")
        runner.cancel()
    finally:
        runner.cleanup()
        evidence = runner.export_evidence()
        # Mark evidence as non-evidentiary
        evidence["wake_reliability"]["feasibility_mode"] = True
        evidence["summary"]["note"] = (
            "NON-EVIDENTIARY — feasibility fixed-delay mode. "
            "Command timing does not satisfy final release-gate requirements."
        )

    print(f"\nFeasibility complete. Evidence: {runner.sanitized_dir}")
    return 0


def resume_mode(args: argparse.Namespace) -> int:
    """Resume an interrupted run."""
    source_client = AdbClient(args.source_selector)
    target_client = AdbClient(args.target_selector)

    runner = AcousticWakeReliabilityRunner(
        run_kind=RunKind.DIAGNOSTIC,  # Will be overwritten by checkpoint
        source_alias=args.source,
        target_alias=args.target,
        source_client=source_client,
        target_client=target_client,
        interactive=args.interactive,
    )
    runner.secrets = [args.source_selector, args.target_selector]

    try:
        state = runner.load_checkpoint(args.run_id)
        # Override run kind from checkpoint
        runner.run_kind = RunKind(state.get("run_kind", "diagnostic_pre_fix"))
        runner.gate_mode = GateMode(state.get("gate_mode", "diagnostic"))
        runner.source_alias = state.get("source_alias", args.source)
        runner.target_alias = state.get("target_alias", args.target)
        runner.preflight_approval = json.loads(
            (runner.run_dir / "preflight-private.json").read_text()
        ) if (runner.run_dir / "preflight-private.json").exists() else None

        print(f"Resuming run: {args.run_id}")
        print(f"Completed: {len(runner.completed_slots)} slots")
        print(f"Attempts: {len(runner.attempts)}")

        # Check preflight drift
        if runner.preflight_approval:
            runner.verify_preflight_approval()

        # Continue matrix
        runner.run_matrix(
            fixture_id=args.fixture_id or "natural_wake",
            command_fixture_id=args.command_fixture_id,
        )

    except HarnessError as e:
        runner.primary_failure = str(e)
        print(f"Resume error: {e}")
    except KeyboardInterrupt:
        print("\nInterrupted")
        runner.cancel()
    finally:
        runner.cleanup()
        evidence = runner.export_evidence()

    if runner.primary_failure:
        print(f"Primary failure: {runner.primary_failure}")
    print(f"Evidence: {runner.sanitized_dir}")
    return 0


# ── Argument parser ───────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Unattended paired acoustic wake-word reliability runner (#1409)",
    )
    parser.add_argument(
        "mode",
        choices=["fixture", "preflight", "smoke", "diagnostic", "regression",
                 "feasibility", "resume"],
        help="Operating mode",
    )

    # Device selection
    parser.add_argument("--source", default="s23u",
                        help="Source device alias (default: s23u)")
    parser.add_argument("--target", default="s21",
                        help="Target device alias (default: s21)")
    parser.add_argument("--source-selector",
                        default=os.environ.get("JANDAL_SOURCE", ""),
                        help="Source ADB selector (or JANDAL_SOURCE env)")
    parser.add_argument("--target-selector",
                        default=os.environ.get("JANDAL_TARGET", ""),
                        help="Target ADB selector (or JANDAL_TARGET env)")

    # Fixture configuration
    parser.add_argument("--fixture-dir",
                        default=os.environ.get("ACOUSTIC_FIXTURE_DIR", ""),
                        help="Source app-private fixture directory path")
    parser.add_argument("--fixture-set-id", default="",
                        help="Fixture set identifier")
    parser.add_argument("--fixture-id", default="natural_wake",
                        help="Primary wake fixture ID")
    parser.add_argument("--command-fixture-id", default="qwen_command",
                        help="Command fixture ID for wake+command trials")
    parser.add_argument("--fixture-hashes", type=json.loads, default={},
                        help="JSON dict of fixture_id->sha256")

    # Timing
    parser.add_argument("--cue-margin-ms", type=int, default=DEFAULT_CUE_MARGIN_MS,
                        help="Cue-clearance margin in ms (default: 1000)")
    parser.add_argument("--fixed-command-delay-ms", type=int, default=None,
                        help="Fixed command delay in ms (feasibility mode only)")

    # Policy
    parser.add_argument("--cue-policy-version", default=None,
                        help="Cue policy version (#1405) — required for regression gate")

    # Resume
    parser.add_argument("--run-id", default="",
                        help="Run ID to resume (resume mode)")

    # Interaction
    parser.add_argument("--interactive", action="store_true",
                        help="Enable interactive operator prompts")

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    # Validate selectors
    if args.mode != "fixture":
        if not args.source_selector:
            raise HarnessError("--source-selector is required (or set JANDAL_SOURCE)")
        if not args.target_selector:
            raise HarnessError("--target-selector is required (or set JANDAL_TARGET)")
        if args.source_selector == args.target_selector:
            raise HarnessError("source and target must be different devices")

    # Route to mode handler
    handlers = {
        "fixture": fixture_mode,
        "preflight": preflight_mode,
        "smoke": smoke_mode,
        "diagnostic": diagnostic_mode,
        "regression": regression_mode,
        "feasibility": feasibility_mode,
        "resume": resume_mode,
    }
    handler = handlers[args.mode]
    return handler(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except HarnessError as e:
        print(f"FATAL: {e}", file=sys.stderr)
        raise SystemExit(2) from e
