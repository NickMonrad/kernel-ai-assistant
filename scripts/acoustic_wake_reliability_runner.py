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

# Target journal interfaces (TargetEventJournalContract).  The provider is
# authoritative for every operation, including bounded waits and cancellation.
TARGET_PACKAGE = "com.kernel.ai.debug"
TARGET_PROVIDER_URI = "content://com.kernel.ai.debug.target-event-journal"
TARGET_METHOD_GET_SEQUENCE = "GET_JOURNAL_SEQUENCE"
TARGET_METHOD_WAIT_FOR_EVENT = "WAIT_FOR_JOURNAL_EVENT"
TARGET_METHOD_CANCEL_WAIT = "CANCEL_JOURNAL_WAIT"
TARGET_METHOD_GET_SNAPSHOT = "GET_JOURNAL_SNAPSHOT"
TARGET_EXTRA_REQUEST_ID = "request_id"
TARGET_EXTRA_SINCE_SEQUENCE = "since_sequence"
TARGET_EXTRA_EVENT_TYPE = "event_type"
TARGET_EXTRA_TIMEOUT_MS = "timeout_ms"
TARGET_RESULT_OK = 0
TARGET_RESULT_TIMEOUT = 1
TARGET_RESULT_ERROR = 2
TARGET_RESULT_CANCELLED = 3
TARGET_WAIT_DEFAULT_TIMEOUT_MS = 15_000
TARGET_WAIT_MIN_TIMEOUT_MS = 500
TARGET_WAIT_MAX_TIMEOUT_MS = 60_000
# Retained only for non-blocking legacy reads on older debug APKs.
TARGET_RECEIVER_CLS = "com.kernel.ai.debug.journal.TargetEventJournalReceiver"
TARGET_ACTION_GET_SEQUENCE = "com.kernel.ai.debug.action.GET_JOURNAL_SEQUENCE"
TARGET_ACTION_GET_SNAPSHOT = "com.kernel.ai.debug.action.GET_JOURNAL_SNAPSHOT"
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
    """One independently accountable position in the frozen matrix."""

    idle_s: int
    wake_only: bool
    ordinal: int = 1

    def __post_init__(self) -> None:
        if self.idle_s <= 0:
            raise ValueError(f"idle_s must be positive: {self.idle_s}")
        if self.ordinal <= 0:
            raise ValueError(f"ordinal must be positive: {self.ordinal}")

    @property
    def trial_type(self) -> TrialType:
        return TrialType.WAKE_ONLY if self.wake_only else TrialType.WAKE_PLUS_COMMAND

    @property
    def position_id(self) -> str:
        return f"{self.idle_s}:{self.trial_type.value}:{self.ordinal}"

    @property
    def required_position_id(self) -> str:
        return self.position_id

@dataclasses.dataclass
class MatrixAttempt:
    """One attempt attached to exactly one required matrix position."""

    trial_id: str
    matrix_slot: MatrixSlot
    attempt: int
    status: AttemptStatus
    classification: FailureClassification | None = None
    invalid_reason: InvalidReason | None = None
    host_start_ms: int = 0
    host_duration_ms: int = 0
    failures: list[str] = dataclasses.field(default_factory=list)
    invalid_details: dict[str, Any] = dataclasses.field(default_factory=dict)
    source_timing: dict[str, Any] = dataclasses.field(default_factory=dict)
    target_timing: dict[str, Any] = dataclasses.field(default_factory=dict)
    environment_before: dict[str, Any] = dataclasses.field(default_factory=dict)
    environment_after: dict[str, Any] = dataclasses.field(default_factory=dict)

    @property
    def required_position_id(self) -> str:
        return self.matrix_slot.position_id




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


def build_content_call_args(
    method: str, extras: dict[str, int | str] | None = None,
) -> list[str]:
    """Build the exact ``adb shell content call`` provider invocation."""
    args = [
        "shell", "content", "call",
        "--uri", TARGET_PROVIDER_URI,
        "--method", method,
    ]
    for key, value in (extras or {}).items():
        prefix = "l" if isinstance(value, int) and not isinstance(value, bool) else "s"
        args.extend(["--extra", f"{key}:{prefix}:{value}"])
    return args


def _extract_bundle_data(tail: str) -> str:
    """Extract Bundle result_data while preserving embedded JSON."""
    tail = tail.strip()
    if not tail:
        return ""
    if tail.startswith('"'):
        escaped = False
        for index in range(1, len(tail)):
            char = tail[index]
            if char == '"' and not escaped:
                try:
                    return str(json.loads(tail[: index + 1]))
                except json.JSONDecodeError as exc:
                    raise HarnessError(f"malformed provider result_data string: {exc}") from exc
            escaped = char == "\\" and not escaped
            if char != "\\":
                escaped = False
        raise HarnessError("unterminated provider result_data string")
    if tail[0] in "[{":
        try:
            value, _ = json.JSONDecoder().raw_decode(tail)
        except json.JSONDecodeError as exc:
            raise HarnessError(f"malformed provider result_data JSON: {exc}") from exc
        return json.dumps(value, separators=(",", ":"))
    return re.split(r",\s*[A-Za-z_][A-Za-z0-9_]*=", tail, maxsplit=1)[0].rstrip("}] ")


def parse_content_call_result(output: str) -> tuple[int, str]:
    """Parse ``content call``'s ``Bundle[{result_code=…, result_data=…}]``."""
    if not isinstance(output, str):
        raise HarnessError("provider output is not text")
    code_match = re.search(r"\bresult_code\s*=\s*(-?\d+)", output)
    if not code_match:
        raise HarnessError("provider output missing result_code")
    data_match = re.search(r"\bresult_data\s*=", output)
    if not data_match:
        raise HarnessError("provider output missing result_data")
    return int(code_match.group(1)), _extract_bundle_data(output[data_match.end():])



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
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.build.version.release",
        "ro.build.version.sdk",
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

def parse_source_result(
    text: str,
    expected_trial_id: str | None = None,
    expected_fixture_id: str | None = None,
) -> dict[str, Any]:
    """Parse and validate ``AcousticStimulusResult.toJson()`` output."""
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise HarnessError(f"source result is not valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise HarnessError("source result is not a JSON object")
    required_fields = {
        "schema_version", "trial_id", "fixture_id", "fixture_sha256",
        "fixture_duration_ms", "request_wall_clock_ms", "request_monotonic_ms",
        "prepare_monotonic_ms", "playback_start_monotonic_ms",
        "completion_monotonic_ms", "cleanup_monotonic_ms",
        "volume_before", "requested_volume", "applied_volume", "maximum_volume",
        "restored_volume", "output_route_before", "output_route_during",
        "focus_result", "completion_status", "error_category",
        "playback_error_category", "evidence_persistence_failed", "timeout",
        "overlap_rejected", "cleanup_success", "exact_restoration_verified",
        "events",
    }
    missing = sorted(required_fields - set(data))
    if missing:
        raise HarnessError(f"source result missing fields: {', '.join(missing)}")
    if data.get("schema_version") != 1:
        raise HarnessError("unsupported source result schema_version")
    if data.get("completion_status") != "completed":
        raise HarnessError(
            f"source playback did not complete: "
            f"status={data.get('completion_status')} "
            f"error={data.get('error_category') or data.get('playback_error_category') or 'unknown'}"
        )
    if data.get("timeout") is not False:
        raise HarnessError("source playback timeout flag is not false")
    if data.get("overlap_rejected") is not False:
        raise HarnessError("source playback overlap_rejected flag is not false")
    if data.get("cleanup_success") is not True:
        raise HarnessError("source cleanup did not succeed")
    if data.get("exact_restoration_verified") is not True:
        raise HarnessError("source volume restoration was not verified")
    if data.get("output_route_during") != "BUILT_IN_SPEAKER":
        raise HarnessError("source output route was not BUILT_IN_SPEAKER")
    if data.get("focus_result") != "granted":
        raise HarnessError("source audio focus was not granted")
    trial_id = data.get("trial_id")
    fixture_id = data.get("fixture_id")
    if not isinstance(trial_id, str) or not trial_id:
        raise HarnessError("source result missing trial_id")
    if not isinstance(fixture_id, str) or not fixture_id:
        raise HarnessError("source result missing fixture_id")
    if expected_trial_id is not None and trial_id != expected_trial_id:
        raise HarnessError("source result trial_id does not match request")
    if expected_fixture_id is not None and fixture_id != expected_fixture_id:
        raise HarnessError("source result fixture_id does not match request")
    if not isinstance(data.get("events"), list):
        raise HarnessError("source result events must be a list")
    return data



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
    """Expand the frozen counts into independently identified positions."""
    slots: list[MatrixSlot] = []
    for idle_s, wake_only_count, command_count in get_matrix(target_alias):
        for ordinal in range(1, wake_only_count + 1):
            slots.append(MatrixSlot(idle_s=idle_s, wake_only=True, ordinal=ordinal))
        for ordinal in range(1, command_count + 1):
            slots.append(MatrixSlot(idle_s=idle_s, wake_only=False, ordinal=ordinal))
    return slots


# ── Source contract parsing ────────────────────────────────────────────



# ── Target journal parsing ──────────────────────────────────────────────

JOURNAL_CONTRACT_VERSION = "1.0.0"
VALID_EVENT_TYPES: set[str] = {
    "DETECTOR_GENERATION_STARTED",
    "SILENCE_GATE_ENTERED",
    "VOICED_FRAME_AFTER_SILENCE",
    "STAGE2_RESUMED",
    "STAGE3_READY",
    "ACTIVATION_CANDIDATE",
    "VERIFIED_ACTIVATION",
    "WAKE_CALLBACK_INVOKED",
    "VOICE_SESSION_STARTED",
    "STT_START_REQUESTED",
    "STT_READY",
    "CUE_REQUESTED",
    "STT_SPEECH_DETECTED",
    "STT_PARTIAL",
    "STT_FINAL",
    "STT_ERROR",
    "COMMAND_ROUTING_RESULT",
    "SESSION_COMPLETED",
    "SESSION_CANCELLED",
    "DETECTOR_REARMED",
    "SERVICE_ERROR",
    "DETECTOR_ERROR",
}


def _require_int(value: Any, name: str, *, minimum: int | None = None) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise HarnessError(f"snapshot field {name} must be an integer")
    if minimum is not None and value < minimum:
        raise HarnessError(f"snapshot field {name} must be >= {minimum}")
    return value


def _canonicalise_event(ev: Any, index: int = 0) -> dict[str, Any]:
    """Validate and canonicalise one compact AcousticEvent object."""
    if not isinstance(ev, dict):
        raise HarnessError(f"event {index} is not an object")
    required = {"s", "m", "w", "t", "g", "i", "d"}
    missing = sorted(required - set(ev))
    if missing:
        raise HarnessError(f"event {index} missing fields: {', '.join(missing)}")
    unexpected = sorted(set(ev) - required)
    if unexpected:
        raise HarnessError(f"event {index} has unexpected fields: {', '.join(unexpected)}")
    event_type = ev["t"]
    if not isinstance(event_type, str) or event_type not in VALID_EVENT_TYPES:
        raise HarnessError(f"event {index} has unknown type: {event_type!r}")
    metadata = ev["d"]
    if not isinstance(metadata, dict):
        raise HarnessError(f"event {index} metadata d must be an object")
    if any(not isinstance(k, str) for k in metadata):
        raise HarnessError(f"event {index} metadata keys must be strings")
    return {
        "s": _require_int(ev["s"], f"events[{index}].s", minimum=0),
        "m": _require_int(ev["m"], f"events[{index}].m", minimum=0),
        "w": _require_int(ev["w"], f"events[{index}].w", minimum=0),
        "t": event_type,
        "g": _require_int(ev["g"], f"events[{index}].g", minimum=0),
        "i": _require_int(ev["i"], f"events[{index}].i", minimum=0),
        "d": dict(metadata),
    }


def validate_snapshot_envelope(envelope: Any) -> dict[str, Any]:
    """Validate and retain the exact target journal snapshot envelope."""
    if not isinstance(envelope, dict):
        raise HarnessError("snapshot result must be a JSON object envelope")
    required = {"lowestSequence", "highestSequence", "overflowed", "events"}
    missing = sorted(required - set(envelope))
    if missing:
        raise HarnessError(f"snapshot envelope missing fields: {', '.join(missing)}")
    unexpected = sorted(set(envelope) - required)
    if unexpected:
        raise HarnessError(f"snapshot envelope has unexpected fields: {', '.join(unexpected)}")
    lowest = _require_int(envelope["lowestSequence"], "lowestSequence", minimum=0)
    highest = _require_int(envelope["highestSequence"], "highestSequence", minimum=0)
    if highest < lowest:
        raise HarnessError("snapshot highestSequence precedes lowestSequence")
    if not isinstance(envelope["overflowed"], bool):
        raise HarnessError("snapshot field overflowed must be boolean")
    events_raw = envelope["events"]
    if not isinstance(events_raw, list):
        raise HarnessError("snapshot field events must be a list")
    events = [_canonicalise_event(event, index) for index, event in enumerate(events_raw)]
    previous = -1
    for index, event in enumerate(events):
        sequence = event["s"]
        if sequence <= previous:
            raise HarnessError(f"event {index} sequence is not strictly increasing")
        if highest and sequence > highest:
            raise HarnessError(f"event {index} sequence exceeds highestSequence")
        if lowest and sequence < lowest:
            raise HarnessError(f"event {index} sequence precedes lowestSequence")
        previous = sequence
    return {
        "lowestSequence": lowest,
        "highestSequence": highest,
        "overflowed": envelope["overflowed"],
        "events": events,
    }


def parse_journal_snapshot(result_data: str) -> dict[str, Any]:
    """Parse the provider's complete JSON snapshot envelope."""
    if not isinstance(result_data, str) or not result_data.strip():
        raise HarnessError("required journal snapshot is missing")
    try:
        raw = json.loads(result_data)
    except json.JSONDecodeError as exc:
        raise HarnessError(f"snapshot is not valid JSON: {exc}") from exc
    return validate_snapshot_envelope(raw)


def parse_journal_wait_result(
    result_code: int, result_data: str,
) -> dict[str, Any] | None:
    """Parse provider wait codes: found, timeout, endpoint error, cancelled."""
    if result_code == TARGET_RESULT_TIMEOUT:
        return None
    if result_code == TARGET_RESULT_ERROR:
        raise HarnessError(f"target wait error: {result_data}")
    if result_code == TARGET_RESULT_CANCELLED:
        raise HarnessError(f"target wait cancelled: {result_data}")
    if result_code != TARGET_RESULT_OK:
        raise HarnessError(f"unknown target result code: {result_code}")
    try:
        event = json.loads(result_data)
    except json.JSONDecodeError as exc:
        raise HarnessError(f"wait event is not valid JSON: {exc}") from exc
    return _canonicalise_event(event)


def parse_journal_sequence(result_data: str) -> int:
    """Parse the provider's decimal highest sequence result."""
    try:
        return _require_int(int(str(result_data).strip()), "sequence", minimum=0)
    except (ValueError, TypeError) as exc:
        raise HarnessError(f"sequence is not a valid integer: {result_data!r}") from exc


def snapshot_boundary_evicted(envelope: dict[str, Any], boundary_sequence: int) -> bool:
    """Return whether sticky overflow proves post-boundary evidence was evicted."""
    validated = validate_snapshot_envelope(envelope)
    return bool(
        validated["overflowed"]
        and validated["highestSequence"] > boundary_sequence
        and validated["lowestSequence"] > boundary_sequence + 1
    )


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

def _event_for_session(
    events: list[dict[str, Any]],
    event_type: str,
    generation: int,
    session: int,
    after_sequence: int = -1,
) -> dict[str, Any] | None:
    for event in events:
        if event["s"] <= after_sequence:
            continue
        if event["t"] != event_type:
            continue
        if event["g"] != generation or event["i"] != session:
            continue
        return event
    return None


def correlate_event_path(
    events: list[dict[str, Any]],
    trial_type: TrialType,
) -> tuple[int | None, int | None, dict[str, dict[str, Any]], list[str]]:
    """Correlate one ordered generation/session without synthetic IDs."""
    ordered = sorted(events, key=lambda event: event["s"])
    sessions = [
        event for event in ordered
        if event["t"] == "VOICE_SESSION_STARTED"
        and event["g"] > 0 and event["i"] > 0
    ]
    if not sessions:
        return None, None, {}, ["no correlated VOICE_SESSION_STARTED"]
    session_start = sessions[0]
    generation, session = session_start["g"], session_start["i"]
    path: dict[str, dict[str, Any]] = {"VOICE_SESSION_STARTED": session_start}
    failures: list[str] = []

    generation_start = next(
        (
            event for event in ordered
            if event["t"] == "DETECTOR_GENERATION_STARTED"
            and event["g"] == generation
            and event["s"] <= session_start["s"]
        ),
        None,
    )
    if generation_start is None:
        failures.append("session is not preceded by its detector generation")
    else:
        path["DETECTOR_GENERATION_STARTED"] = generation_start

    previous = generation_start["s"] if generation_start else -1
    pre_session_types = {
        "SILENCE_GATE_ENTERED",
        "VOICED_FRAME_AFTER_SILENCE",
        "STAGE2_RESUMED",
        "STAGE3_READY",
        "ACTIVATION_CANDIDATE",
        "VERIFIED_ACTIVATION",
        "WAKE_CALLBACK_INVOKED",
    }
    for event_type in (
        "SILENCE_GATE_ENTERED",
        "VOICED_FRAME_AFTER_SILENCE",
        "STAGE2_RESUMED",
        "STAGE3_READY",
        "ACTIVATION_CANDIDATE",
        "VERIFIED_ACTIVATION",
        "WAKE_CALLBACK_INVOKED",
        "STT_START_REQUESTED",
        "STT_READY",
        "CUE_REQUESTED",
    ):
        if event_type in pre_session_types:
            event = next(
                (
                    candidate for candidate in ordered
                    if candidate["t"] == event_type
                    and candidate["g"] == generation
                    and candidate["s"] > previous
                ),
                None,
            )
        else:
            event = _event_for_session(ordered, event_type, generation, session, previous)
        if event is None:
            failures.append(f"missing correlated {event_type}")
            continue
        path[event_type] = event
        previous = event["s"]

    if trial_type == TrialType.WAKE_PLUS_COMMAND:
        for event_type in ("STT_SPEECH_DETECTED", "STT_FINAL", "COMMAND_ROUTING_RESULT"):
            event = _event_for_session(ordered, event_type, generation, session, previous)
            if event is None:
                failures.append(f"missing correlated {event_type}")
            else:
                path[event_type] = event
                previous = event["s"]

    terminal = next(
        (
            event for event in ordered
            if event["t"] in {"SESSION_COMPLETED", "SESSION_CANCELLED"}
            and event["g"] == generation
            and event["i"] == session
            and event["s"] > previous
        ),
        None,
    )
    if terminal is None:
        failures.append("missing correlated session terminal")
    else:
        path["terminal"] = terminal
        rearm = next(
            (
                event for event in ordered
                if event["t"] == "DETECTOR_REARMED"
                and event["s"] > terminal["s"]
                and event["g"] > generation
            ),
            None,
        )
        if rearm is None:
            failures.append("missing detector re-arm after correlated terminal")
        else:
            path["DETECTOR_REARMED"] = rearm
    return generation, session, path, failures


def classify_attempt(
    source_result: dict[str, Any] | None,
    target_snapshot: dict[str, Any] | None,
    trial_type: TrialType,
    gen_id: int | None,
    session_id: int | None,
    cue_policy_available: bool,
) -> tuple[AttemptStatus, FailureClassification | None, InvalidReason | None, list[str]]:
    """Classify only a source-valid, non-evicted, correlated event path."""
    failures: list[str] = []
    if source_result is None:
        return AttemptStatus.INVALID, None, InvalidReason.SOURCE_STIMULUS_FAILURE, ["no source result"]
    try:
        parse_source_result(json.dumps(source_result))
    except HarnessError as exc:
        return AttemptStatus.INVALID, None, InvalidReason.SOURCE_STIMULUS_FAILURE, [str(exc)]
    if target_snapshot is None:
        return AttemptStatus.INVALID, None, InvalidReason.DEVICE_ENVIRONMENT_ERROR, ["no target snapshot"]
    boundary = target_snapshot.get("_boundary_sequence")
    snapshot_payload = {
        key: value for key, value in target_snapshot.items()
        if key != "_boundary_sequence"
    }
    try:
        envelope = validate_snapshot_envelope(snapshot_payload)
    except HarnessError as exc:
        return AttemptStatus.INVALID, None, InvalidReason.EVIDENCE_BOUNDARY_LOST, [str(exc)]
    if boundary is not None and snapshot_boundary_evicted(envelope, boundary):
        return (
            AttemptStatus.INVALID,
            None,
            InvalidReason.EVIDENCE_BOUNDARY_LOST,
            [f"post-boundary evidence evicted at boundary {boundary}"],
        )
    events = envelope["events"]
    if not events:
        return AttemptStatus.FAILED, FailureClassification.ACOUSTIC_OR_GATE_MISS, None, ["no target events"]

    actual_gen, actual_session, path, correlation_failures = correlate_event_path(events, trial_type)
    if actual_gen is None or actual_session is None:
        return AttemptStatus.FAILED, FailureClassification.ACTIVATION_HANDOFF_FAILURE, None, correlation_failures
    if gen_id is not None and gen_id != actual_gen:
        failures.append("caller generation does not match correlated generation")
    if session_id is not None and session_id != actual_session:
        failures.append("caller session does not match correlated session")
    if failures:
        return AttemptStatus.INVALID, None, InvalidReason.UNKNOWN, failures
    if correlation_failures:
        if any("gate" in failure or "STAGE" in failure for failure in correlation_failures):
            return AttemptStatus.FAILED, FailureClassification.ACOUSTIC_OR_GATE_MISS, None, correlation_failures
        if any("STT_READY" in failure for failure in correlation_failures):
            return AttemptStatus.FAILED, FailureClassification.STT_READINESS_FAILURE, None, correlation_failures
        return AttemptStatus.FAILED, FailureClassification.ACTIVATION_HANDOFF_FAILURE, None, correlation_failures
    if path["terminal"]["t"] == "SESSION_CANCELLED":
        return AttemptStatus.FAILED, FailureClassification.UNCLASSIFIED, None, ["session was cancelled"]
    if not cue_policy_available:
        return AttemptStatus.FAILED, FailureClassification.CUE_AUDIBILITY_UNCONFIRMED, None, [
            "cue policy evidence is unavailable"
        ]
    if trial_type == TrialType.WAKE_PLUS_COMMAND:
        if path["COMMAND_ROUTING_RESULT"]["d"].get("outcome") != "handed_off":
            return AttemptStatus.FAILED, FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE, None, [
                "command routing did not hand off successfully"
            ]
    return AttemptStatus.PASSED, None, None, []
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
                slot.position_id: 1
                for slot in matrix_slots_for_target(run_manifest.target_alias)
            },
            "cleanup_verified": cleanup_verified,
        },
    }

    for attempt in attempts:
        case: dict[str, Any] = {
            "name": attempt.trial_id,
            "trial_id": attempt.trial_id,
            "required_position_id": attempt.required_position_id,
            "matrix_slot": {
                "idle_s": attempt.matrix_slot.idle_s,
                "trial_type": attempt.matrix_slot.trial_type.value,
                "ordinal": attempt.matrix_slot.ordinal,
            },
            "passed": attempt.status == AttemptStatus.PASSED,
            "status": attempt.status.value,
            "idle_seconds": attempt.matrix_slot.idle_s,
            "trial_type": attempt.matrix_slot.trial_type.value,
            "attempt": attempt.attempt,
            "source_timing": attempt.source_timing,
            "target_timing": attempt.target_timing,
            "environment_before": attempt.environment_before,
            "environment_after": attempt.environment_after,
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
        self.source_results: list[dict[str, Any]] = []
        self.completed_slots: set[str] = set()
        self.valid_failed_slots: set[str] = set()
        self.invalid_attempt_count: int = 0
        self.abort_reason: str | None = None
        self.cleanup_verified: bool = False
        self.cleanup_failures: list[str] = []
        self.primary_failure: str | None = None
        self.secrets: list[str] = []
        self._cancel = threading.Event()
        self._wait_lock = threading.Lock()
        self._active_wait_request_id: str | None = None
        self._last_boundary_sequence: int | None = None
        self._last_target_snapshot: dict[str, Any] | None = None
        self.preflight_manifest_hash: str | None = None

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
                        "ordinal": a.matrix_slot.ordinal,
                        "position_id": a.matrix_slot.position_id,
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
            slot_data = a["matrix_slot"]
            slot = MatrixSlot(
                idle_s=slot_data["idle_s"],
                wake_only=slot_data["wake_only"],
                ordinal=slot_data.get("ordinal", 1),
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
    def load_preflight_manifest(self, path: Path) -> dict[str, Any]:
        """Load and verify a previously approved immutable manifest."""
        try:
            manifest = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError) as exc:
            raise HarnessError(f"cannot read preflight manifest: {exc}") from exc
        if not isinstance(manifest, dict):
            raise HarnessError("preflight manifest must be a JSON object")
        expected_hash = manifest.get("manifest_sha256")
        unsigned = dict(manifest)
        unsigned.pop("manifest_sha256", None)
        actual_hash = hashlib.sha256(
            json.dumps(unsigned, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
        if expected_hash != actual_hash:
            raise HarnessError("preflight manifest hash mismatch")
        self.preflight_approval = manifest
        self.preflight_manifest_hash = expected_hash
        return manifest


    # ── Preflight ────────────────────────────────────────────────────

    def run_preflight(self, fixture_set_id: str, fixture_hashes: dict[str, str]) -> dict[str, Any]:
        """Run monitored playback, collect target evidence, then ask approval."""
        if not self.interactive:
            raise HarnessError("preflight requires --interactive")
        self.source_identity = device_identity(self.source, self.source_alias, self.package)
        self.target_identity = device_identity(self.target, self.target_alias, self.package)
        if service_active(self.source, self.package):
            raise HarnessError("source wake service must be disabled")
        if not service_active(self.target, self.package):
            raise HarnessError("target wake service must be active")
        if self._has_active_bluetooth_route(self.source) or self._has_active_bluetooth_route(self.target):
            raise HarnessError("external Bluetooth audio route detected")

        fixture_manifest = self._read_fixture_manifest()
        fixture_records = fixture_manifest.get("fixtures", [])
        wake_fixture = next(
            (item for item in fixture_records if item.get("fixture_id") == "natural_wake"),
            None,
        )
        if wake_fixture is None:
            raise HarnessError("required fixture 'natural_wake' not found")
        resolved_hashes = fixture_hashes or {
            item["fixture_id"]: item.get("sha256")
            for item in fixture_records
            if item.get("fixture_id") and item.get("sha256")
        }
        source_audio = self._snapshot_audio_state(self.source, self.source_alias)
        target_audio = self._snapshot_audio_state(self.target, self.target_alias)
        initial_target = self._snapshot_target_state()
        current_volume = max(1, int(self._get_media_max_volume(self.source) * 0.6))
        approved = False
        attempts: list[dict[str, Any]] = []

        for preflight_attempt in range(1, 4):
            sequence_code, sequence_data = self._call_target_provider(TARGET_METHOD_GET_SEQUENCE)
            if sequence_code != TARGET_RESULT_OK:
                raise HarnessError(f"preflight target sequence failed: {sequence_data}")
            boundary = parse_journal_sequence(sequence_data)
            result = self._invoke_source(
                trial_id=f"preflight-{preflight_attempt:02d}",
                fixture_id="natural_wake",
                volume_index=current_volume,
            )
            parsed = parse_source_result(
                json.dumps(result),
                expected_trial_id=f"preflight-{preflight_attempt:02d}",
                expected_fixture_id="natural_wake",
            )
            events, envelope = self._wait_for_target_events(
                since_sequence=boundary,
                event_type="STT_READY",
                timeout_ms=TARGET_WAIT_DEFAULT_TIMEOUT_MS,
            )
            gate_evidence = any(
                event["t"] in {
                    "SILENCE_GATE_ENTERED",
                    "VOICED_FRAME_AFTER_SILENCE",
                    "STAGE2_RESUMED",
                    "STAGE3_READY",
                }
                for event in events
            )
            attempts.append(
                {
                    "attempt": preflight_attempt,
                    "volume_index": current_volume,
                    "fixture_id": "natural_wake",
                    "boundary_sequence": boundary,
                    "source_completed": True,
                    "target_gate_evidence": gate_evidence,
                    "source_result": {
                        "focus_result": parsed.get("focus_result"),
                        "output_route_during": parsed.get("output_route_during"),
                        "cleanup_success": parsed.get("cleanup_success"),
                        "exact_restoration_verified": parsed.get("exact_restoration_verified"),
                    },
                    "target_snapshot": {
                        "lowestSequence": envelope["lowestSequence"],
                        "highestSequence": envelope["highestSequence"],
                        "overflowed": envelope["overflowed"],
                        "event_count": len(envelope["events"]),
                    },
                }
            )
            choice = input(
                f"Preflight playback at source volume {current_volume}. "
                "Enter APPROVE, UP <N>, DOWN <N>, or ABORT: "
            ).strip().lower()
            if choice == "approve":
                approved = True
                break
            if choice == "abort":
                raise HarnessError("preflight cancelled by operator")
            parts = choice.split()
            if len(parts) == 2 and parts[0] in {"up", "down"}:
                try:
                    delta = max(1, min(3, int(parts[1])))
                except ValueError as exc:
                    raise HarnessError("preflight adjustment must use UP/DOWN <1-3>") from exc
                if parts[0] == "up":
                    current_volume = min(self._get_media_max_volume(self.source), current_volume + delta)
                else:
                    current_volume = max(1, current_volume - delta)
                continue
            raise HarnessError("preflight requires APPROVE, UP <N>, DOWN <N>, or ABORT")

        if not approved:
            raise HarnessError("preflight did not receive approval within three attempts")
        placement_notes = input("Enter placement notes (distance, orientation): ").strip()
        manifest = {
            "schema_version": 2,
            "fixture_set_id": fixture_set_id,
            "fixture_hashes": resolved_hashes,
            "source_role": self.source_alias,
            "target_role": self.target_alias,
            "source_identity": self.source_identity.public(),
            "target_identity": self.target_identity.public(),
            "source_audio_state": source_audio,
            "target_audio_state": target_audio,
            "source_volume_index": current_volume,
            "source_volume_max": self._get_media_max_volume(self.source),
            "source_route": "BUILT_IN_SPEAKER",
            "target_boot_id": initial_target.get("boot_id"),
            "target_service_active": initial_target.get("service_active"),
            "placement_notes": placement_notes,
            "operator_approved": True,
            "cue_audibility_confirmed": True,
            "attempts": attempts,
            "approved_at": utc_now(),
        }
        canonical = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
        manifest["manifest_sha256"] = hashlib.sha256(canonical).hexdigest()
        self.preflight_approval = manifest
        self.preflight_manifest_hash = manifest["manifest_sha256"]
        self.private_write("", "preflight-private.json", json.dumps(manifest, indent=2))
        print(f"\nPreflight approved. Hash: {manifest['manifest_sha256']}")
        return manifest

    # ── Trial lifecycle ──────────────────────────────────────────────

    def run_trial(
        self,
        trial_id: str,
        matrix_slot: MatrixSlot,
        fixture_id: str,
        command_fixture_id: str | None = None,
    ) -> MatrixAttempt:
        attempt = MatrixAttempt(
            trial_id=trial_id,
            matrix_slot=matrix_slot,
            attempt=self._attempt_number_for(matrix_slot),
            status=AttemptStatus.INVALID,
        )
        host_start = monotonic_ms()
        attempt.host_start_ms = host_start
        try:
            sequence_code, sequence_data = self._call_target_provider(TARGET_METHOD_GET_SEQUENCE)
            if sequence_code != TARGET_RESULT_OK:
                raise HarnessError(f"target sequence failed: {sequence_data}")
            boundary_sequence = parse_journal_sequence(sequence_data)
            self._last_boundary_sequence = boundary_sequence
            attempt.environment_before = self._snapshot_target_state()
            if not attempt.environment_before.get("reachable", False):
                raise HarnessError("target became unreachable before trial")
            self.checkpoint("pre-idle")

            idle_start_ms = monotonic_ms()
            remaining_ms = matrix_slot.idle_s * 1000
            while remaining_ms > 0 and not self._cancel.is_set():
                sleep_ms = min(remaining_ms, 100)
                time.sleep(sleep_ms / 1000.0)
                remaining_ms -= sleep_ms
            if self._cancel.is_set():
                raise HarnessError("cancelled during idle")
            attempt.target_timing["idle_start_monotonic_ms"] = idle_start_ms
            attempt.target_timing["idle_end_monotonic_ms"] = monotonic_ms()

            volume = int((self.preflight_approval or {}).get("source_volume_index", 7))
            source_result = self._invoke_source(trial_id, fixture_id, volume)
            parsed_source = parse_source_result(
                json.dumps(source_result),
                expected_trial_id=trial_id,
                expected_fixture_id=fixture_id,
            )
            attempt.source_timing = {
                key: parsed_source.get(key)
                for key in (
                    "request_wall_clock_ms",
                    "request_monotonic_ms",
                    "prepare_monotonic_ms",
                    "playback_start_monotonic_ms",
                    "completion_monotonic_ms",
                    "cleanup_monotonic_ms",
                )
            }
            self.checkpoint("post-source")

            events, envelope = self._wait_for_target_events(
                since_sequence=boundary_sequence,
                event_type="STT_READY",
                timeout_ms=TARGET_WAIT_DEFAULT_TIMEOUT_MS,
            )

            command_source: dict[str, Any] | None = None
            if not matrix_slot.wake_only and command_fixture_id:
                if self.is_feasibility and self.fixed_command_delay_ms:
                    time.sleep(self.fixed_command_delay_ms / 1000.0)
                else:
                    self._wait_for_target_events(
                        since_sequence=boundary_sequence,
                        event_type="CUE_REQUESTED",
                        timeout_ms=TARGET_WAIT_DEFAULT_TIMEOUT_MS,
                    )
                command_trial_id = f"{trial_id}-cmd"
                command_source = self._invoke_source(command_trial_id, command_fixture_id, volume)
                parse_source_result(
                    json.dumps(command_source),
                    expected_trial_id=command_trial_id,
                    expected_fixture_id=command_fixture_id,
                )

            final_code, final_data = self._call_target_provider(
                TARGET_METHOD_GET_SNAPSHOT,
                extras={TARGET_EXTRA_SINCE_SEQUENCE: boundary_sequence},
            )
            if final_code != TARGET_RESULT_OK:
                raise HarnessError(f"target final snapshot failed: {final_data}")
            snapshot = parse_journal_snapshot(final_data)
            snapshot["_boundary_sequence"] = boundary_sequence
            self.checkpoint("post-snapshot")

            attempt.environment_after = self._snapshot_target_state()
            environment_failures = self._environment_failures(
                attempt.environment_before,
                attempt.environment_after,
            )
            attempt.target_timing["source_clock_domain"] = "source_monotonic_ms"
            attempt.target_timing["target_clock_domain"] = "target_event_millis"
            cue_policy_ok = bool(
                self.manifest
                and self.manifest.cue_policy_version
                and (self.preflight_approval or {}).get("cue_audibility_confirmed") is True
            )
            status, classification, invalid_reason, failures = classify_attempt(
                source_result=parsed_source,
                target_snapshot=snapshot,
                trial_type=matrix_slot.trial_type,
                gen_id=None,
                session_id=None,
                cue_policy_available=cue_policy_ok,
            )
            attempt.status = status
            attempt.classification = classification
            attempt.invalid_reason = invalid_reason
            attempt.failures.extend(failures)
            if command_source is not None:
                attempt.source_timing["command"] = {
                    key: command_source.get(key)
                    for key in (
                        "request_wall_clock_ms",
                        "request_monotonic_ms",
                        "playback_start_monotonic_ms",
                        "completion_monotonic_ms",
                    )
                }
            if environment_failures:
                attempt.status = AttemptStatus.INVALID
                attempt.classification = None
                attempt.invalid_reason = InvalidReason.DEVICE_ENVIRONMENT_ERROR
                attempt.failures.extend(environment_failures)
                attempt.invalid_details["environment_failures"] = environment_failures

        except HarnessError as exc:
            attempt.status = AttemptStatus.INVALID
            attempt.invalid_reason = InvalidReason.UNKNOWN
            attempt.failures.append(str(exc))
            if self.primary_failure is None:
                self.primary_failure = str(exc)
        finally:
            attempt.host_duration_ms = monotonic_ms() - host_start
            self.attempts.append(attempt)
            slot_key = matrix_slot.position_id
            self.completed_slots.add(f"{slot_key}:{attempt.attempt}")
            if attempt.status == AttemptStatus.FAILED:
                self.valid_failed_slots.add(slot_key)
            elif attempt.status == AttemptStatus.INVALID:
                self.invalid_attempt_count += 1
            self.checkpoint()
        return attempt

    # ── Matrix scheduler ─────────────────────────────────────────────

    def run_matrix(
        self,
        fixture_id: str = "natural_wake",
        command_fixture_id: str | None = "qwen_command",
    ) -> None:
        """Run every required position independently with bounded retries."""
        trial_index = 1
        for slot in matrix_slots_for_target(self.target_alias):
            while not self._cancel.is_set():
                if any(
                    attempt.required_position_id == slot.position_id
                    and attempt.status in {AttemptStatus.PASSED, AttemptStatus.FAILED}
                    for attempt in self.attempts
                ):
                    break
                if self._invalid_count_for(slot) >= VALID_MAX_ATTEMPTS:
                    break
                trial_id = f"trial-{self.run_id}-{trial_index:03d}"
                trial_index += 1
                attempt = self.run_trial(
                    trial_id=trial_id,
                    matrix_slot=slot,
                    fixture_id=fixture_id,
                    command_fixture_id=None if slot.wake_only else command_fixture_id,
                )
                print(
                    f"Trial {trial_id}: position={slot.position_id} "
                    f"→ {attempt.status.value}"
                )

    def _slot_has_pass(self, slot: MatrixSlot) -> bool:
        return any(
            attempt.required_position_id == slot.position_id
            and attempt.status == AttemptStatus.PASSED
            for attempt in self.attempts
        )

    def _invalid_count_for(self, slot: MatrixSlot) -> int:
        return sum(
            1 for attempt in self.attempts
            if attempt.required_position_id == slot.position_id
            and attempt.status == AttemptStatus.INVALID
        )

    def _attempt_number_for(self, slot: MatrixSlot) -> int:
        return sum(
            1 for attempt in self.attempts
            if attempt.required_position_id == slot.position_id
        ) + 1

    def verify_preflight_approval(self) -> None:
        """Verify the immutable preflight manifest and live environment."""
        if not self.preflight_approval:
            raise HarnessError("no preflight approval available")
        expected_hash = self.preflight_approval.get("manifest_sha256")
        manifest_copy = dict(self.preflight_approval)
        manifest_copy.pop("manifest_sha256", None)
        actual_hash = hashlib.sha256(
            json.dumps(manifest_copy, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
        if expected_hash != actual_hash:
            raise HarnessError("preflight manifest hash is invalid")
        if not self.target.reachable():
            raise HarnessError("target not reachable; preflight conditions may have changed")
        current = self._snapshot_target_state()
        approved_boot_id = self.preflight_approval.get("target_boot_id")
        if approved_boot_id and current.get("boot_id") != approved_boot_id:
            raise HarnessError("target boot ID changed since preflight approval")
        expected_target = self.preflight_approval.get("target_identity")
        if self.target_identity and expected_target != self.target_identity.public():
            raise HarnessError("target identity changed since preflight approval")
        if not current.get("service_active", False):
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
            return {"uptime_seconds": 0.0, "reachable": False, "boot_id": None}
        uptime_text = self.target.shell("cat", "/proc/uptime").strip()
        uptime = float(uptime_text.split()[0]) if uptime_text else 0.0
        boot_id = self.target.shell("cat", "/proc/sys/kernel/random/boot_id").strip()
        battery = self.target.shell("dumpsys", "battery")
        return {
            "uptime_seconds": uptime,
            "boot_id": boot_id,
            "screen_off": "mScreenOn=false" in self.target.shell("dumpsys", "power"),
            "charging": "AC powered: true" in battery or "USB powered: true" in battery,
            "service_active": service_active(self.target, self.package),
            "reachable": True,
        }

    @staticmethod
    def _environment_failures(
        before: dict[str, Any],
        after: dict[str, Any],
    ) -> list[str]:
        failures: list[str] = []
        if not before.get("reachable") or not after.get("reachable"):
            failures.append("target ADB reachability changed during trial")
        if before.get("boot_id") and after.get("boot_id") != before.get("boot_id"):
            failures.append("target boot ID changed during trial")
        try:
            if float(after.get("uptime_seconds", 0.0)) < float(before.get("uptime_seconds", 0.0)):
                failures.append("target uptime regressed during trial")
        except (TypeError, ValueError):
            failures.append("target uptime was not numeric")
        if not after.get("service_active", False):
            failures.append("target wake service is inactive after trial")
        return failures

    def _read_fixture_manifest(self) -> dict[str, Any]:
        if not self.fixture_dir:
            return {"fixtures": []}
        try:
            manifest_text = self.source.shell(
                "run-as", self.package,
                "cat", f"{self.fixture_dir}/manifest.json",
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

        self.source_results.append(result)
        return result

    def _call_target_provider(
        self,
        method: str,
        extras: dict[str, int | str] | None = None,
        timeout: float = 15.0,
    ) -> tuple[int, str]:
        """Call the authoritative target-event ContentProvider."""
        raw = self.target.run(
            *build_content_call_args(method, extras),
            timeout=timeout,
        )
        return parse_content_call_result(raw)

    def _call_target_broadcast(
        self,
        action: str,
        extras: dict[str, Any] | None = None,
        timeout: float = 15.0,
    ) -> tuple[int, str]:
        """Compatibility name for provider-backed journal operations.

        No target journal operation is sent through ``am broadcast``.
        """
        method_by_action = {
            TARGET_ACTION_GET_SEQUENCE: TARGET_METHOD_GET_SEQUENCE,
            TARGET_ACTION_GET_SNAPSHOT: TARGET_METHOD_GET_SNAPSHOT,
        }
        try:
            method = method_by_action[action]
        except KeyError as exc:
            raise HarnessError(f"unsupported target journal action: {action}") from exc
        return self._call_target_provider(method, extras=extras, timeout=timeout)

    def _cancel_active_wait(self) -> None:
        with self._wait_lock:
            request_id = self._active_wait_request_id
        if request_id is None:
            return
        code, data = self._call_target_provider(
            TARGET_METHOD_CANCEL_WAIT,
            extras={TARGET_EXTRA_REQUEST_ID: request_id},
            timeout=5.0,
        )
        if code not in (TARGET_RESULT_OK, TARGET_RESULT_CANCELLED):
            raise HarnessError(f"target wait cancellation failed: {data}")

    def _wait_for_target_events(
        self, since_sequence: int, event_type: str, timeout_ms: int,
    ) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        """Wait with a unique provider request, then require its exact snapshot."""
        if not TARGET_WAIT_MIN_TIMEOUT_MS <= timeout_ms <= TARGET_WAIT_MAX_TIMEOUT_MS:
            raise HarnessError(
                f"target wait timeout must be {TARGET_WAIT_MIN_TIMEOUT_MS}.."
                f"{TARGET_WAIT_MAX_TIMEOUT_MS} ms"
            )
        request_id = f"wait-{uuid.uuid4().hex}"
        with self._wait_lock:
            self._active_wait_request_id = request_id
        try:
            code, data = self._call_target_provider(
                TARGET_METHOD_WAIT_FOR_EVENT,
                extras={
                    TARGET_EXTRA_REQUEST_ID: request_id,
                    TARGET_EXTRA_SINCE_SEQUENCE: since_sequence,
                    TARGET_EXTRA_EVENT_TYPE: event_type,
                    TARGET_EXTRA_TIMEOUT_MS: timeout_ms,
                },
                timeout=timeout_ms / 1000.0 + 5.0,
            )
            event = parse_journal_wait_result(code, data)
            if self._cancel.is_set():
                self._cancel_active_wait()
                raise HarnessError("cancelled while waiting for target journal event")
        finally:
            with self._wait_lock:
                self._active_wait_request_id = None

        snapshot_code, snapshot_data = self._call_target_provider(
            TARGET_METHOD_GET_SNAPSHOT,
            extras={TARGET_EXTRA_SINCE_SEQUENCE: since_sequence},
        )
        if snapshot_code != TARGET_RESULT_OK:
            raise HarnessError(f"target snapshot failed: {snapshot_data}")
        envelope = parse_journal_snapshot(snapshot_data)
        events = envelope["events"]
        if event is not None and not any(item["s"] == event["s"] for item in events):
            raise HarnessError("provider wait event is absent from its final snapshot")
        return events, envelope

    def _verify_source_restoration(self) -> list[str]:
        """Verify helper-owned cleanup for every source playback result."""
        failures: list[str] = []
        if not self.source.reachable():
            return ["source ADB unreachable during cleanup verification"]
        for index, result in enumerate(self.source_results):
            try:
                parse_source_result(json.dumps(result))
            except HarnessError as exc:
                failures.append(f"source result {index + 1}: {exc}")
        return failures

    # ── Cleanup ──────────────────────────────────────────────────────
    def cleanup(self) -> None:
        """Cancel provider waits and verify helper-owned restoration."""
        failures: list[str] = []
        try:
            self._cancel_active_wait()
        except HarnessError as exc:
            failures.append(str(exc))
        failures.extend(self._verify_source_restoration())
        self.cleanup_failures = failures
        self.cleanup_verified = not failures

    def is_matrix_complete(self) -> bool:
        """Return true when every required position has a valid outcome."""
        for slot in matrix_slots_for_target(self.target_alias):
            if not any(
                attempt.required_position_id == slot.position_id
                and attempt.status in {AttemptStatus.PASSED, AttemptStatus.FAILED}
                for attempt in self.attempts
            ):
                return False
        return True

    def release_gate_success(self) -> bool:
        """Return true only for a complete, all-passed, fully verified run."""
        required = {slot.position_id for slot in matrix_slots_for_target(self.target_alias)}
        passed = {
            attempt.required_position_id
            for attempt in self.attempts
            if attempt.status == AttemptStatus.PASSED
        }
        return (
            self.is_matrix_complete()
            and passed == required
            and self.cleanup_verified
            and self.preflight_approval is not None
            and self.preflight_approval.get("cue_audibility_confirmed") is True
            and self.manifest is not None
            and self.manifest.cue_policy_version is not None
            and not self.primary_failure
        )

    def export_evidence(self) -> dict[str, Any]:
        """Build the final evidence object before a single sanitised write."""
        if not self.target_identity or not self.source_identity:
            raise HarnessError("device identities not available")
        preflight = self.preflight_approval
        cue_version = self.manifest.cue_policy_version if self.manifest else None
        fixture_hashes = (preflight or {}).get("fixture_hashes", {})
        fixture_set_id = (preflight or {}).get("fixture_set_id")
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
            preflight_hash=self.preflight_manifest_hash,
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
        required_positions = [slot.position_id for slot in matrix_slots_for_target(self.target_alias)]
        reliability = evidence["wake_reliability"]
        reliability.update(
            {
                "required_matrix_positions": required_positions,
                "matrix_complete": self.is_matrix_complete(),
                "release_gate_success": self.release_gate_success(),
                "cleanup_failures": self.cleanup_failures,
                "fixture_provenance_verified": bool(fixture_set_id and fixture_hashes),
                "build_provenance_verified": bool(self.source_identity.package_version),
                "preflight_manifest_sha256": (preflight or {}).get("manifest_sha256"),
            }
        )
        reliability["expected_valid_counts"] = {position: 1 for position in required_positions}
        evidence["summary"]["matrix_complete"] = self.is_matrix_complete()
        evidence["summary"]["release_gate_success"] = self.release_gate_success()
        if self.is_feasibility:
            evidence["non_evidentiary"] = True
            evidence["summary"]["note"] = (
                "NON-EVIDENTIARY — feasibility fixed-delay mode"
            )
        elif self.gate_mode == GateMode.RELEASE and not self.release_gate_success():
            evidence["summary"]["note"] = "RELEASE GATE FAILED — evidence is not publishable"
        assert_commit_safe(evidence, self.secrets)
        self.sanitized_dir.mkdir(parents=True, exist_ok=True)
        write_sanitized_summary(self.sanitized_dir, evidence, self.secrets)
        return evidence

    # ─── Cancellation ────────────────────────────────────────────────

    def run_smoke(
        self,
        fixture_id: str = "natural_wake",
        command_fixture_id: str | None = None,
    ) -> None:
        """Run one bounded wake-only smoke position, never the full matrix."""
        slot = matrix_slots_for_target(self.target_alias)[0]
        smoke_slot = MatrixSlot(
            idle_s=slot.idle_s,
            wake_only=True,
            ordinal=slot.ordinal,
        )
        self.run_trial(
            trial_id=f"smoke-{self.run_id}-001",
            matrix_slot=smoke_slot,
            fixture_id=fixture_id,
            command_fixture_id=None,
        )

    def cancel(self) -> None:
        """Request cancellation and cancel any active provider wait."""
        self._cancel.set()
        try:
            self._cancel_active_wait()
        except HarnessError as exc:
            self.cleanup_failures.append(str(exc))

# ── CLI and modes ─────────────────────────────────────────────────────

def fixture_mode(args: argparse.Namespace | None = None) -> int:
    """Dry-run/fixture mode: validate configuration without ADB."""
    # Validate expected devices
    for alias, expected in EXPECTED_DEVICES.items():
        print(f"  Device '{alias}': {expected['manufacturer']} {expected['model']}")

    # Validate contract versions
    print(f"\n  Source helper contract: 1.0.0")
    print(f"  Target journal contract: {JOURNAL_CONTRACT_VERSION}")
    print(f"  Matrix ID: {MATRIX_ID} v{MATRIX_VERSION}")

    # Validate the checked-in strict source and target provider fixtures.
    fixture_root = Path(__file__).parent / "testdata" / "fixtures" / "acoustic-wake-reliability"
    sample_source = json.loads((fixture_root / "source-result-valid.json").read_text())
    snapshot_wrapper = json.loads(
        (fixture_root / "target-journal-snapshot-valid.json").read_text()
    )
    sample_snapshot = json.loads(snapshot_wrapper["result_data"])
    sample_snapshot["_boundary_sequence"] = 0

    status, cls, reason, failures = classify_attempt(
        source_result=sample_source,
        target_snapshot=sample_snapshot,
        trial_type=TrialType.WAKE_ONLY,
        gen_id=None,
        session_id=None,
        cue_policy_available=True,
    )
    print(f"\n  Sample classification: {status.value}")
    if cls:
        print(f"    Would classify as: {cls.value}")
    if reason:
        print(f"    Invalid: {reason.value}")
    if failures:
        print(f"    Failure details: {', '.join(failures)}")

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


def load_later_run_preflight(
    runner: AcousticWakeReliabilityRunner,
    args: argparse.Namespace,
) -> None:
    """Require an approved manifest for diagnostic/regression/feasibility runs."""
    if not args.preflight_manifest:
        raise HarnessError(
            "later runs require --preflight-manifest from monitored preflight"
        )
    runner.load_preflight_manifest(Path(args.preflight_manifest))
    runner.source_identity = device_identity(runner.source, runner.source_alias, runner.package)
    runner.target_identity = device_identity(runner.target, runner.target_alias, runner.package)
    runner.verify_preflight_approval()

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
        runner.run_smoke(
            fixture_id=args.fixture_id or "natural_wake",
            command_fixture_id=None,
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
    return 0 if (
        runner.attempts
        and runner.attempts[-1].status in {AttemptStatus.PASSED, AttemptStatus.FAILED}
        and runner.cleanup_verified
    ) else 1


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
        load_later_run_preflight(runner, args)
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
        load_later_run_preflight(runner, args)
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
            preflight_hash=runner.preflight_manifest_hash,
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
    return 0 if runner.release_gate_success() else 1


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
        load_later_run_preflight(runner, args)
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
    parser.add_argument(
        "--preflight-manifest",
        default=os.environ.get("ACOUSTIC_PREFLIGHT_MANIFEST", ""),
        help="Previously approved immutable preflight manifest for later runs",
    )

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
