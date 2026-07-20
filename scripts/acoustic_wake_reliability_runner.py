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
import copy
import dataclasses
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import uuid
from collections import defaultdict
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Iterable
from summarise_test_report import schema_validation_errors

REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = REPO_ROOT / "scripts"
DEFAULT_PRIVATE_ROOT = SCRIPTS_DIR / "private-acoustic-runs"
DEFAULT_PACKAGE = "com.kernel.ai.debug"
SOURCE_FIXTURE_MANIFEST = "files/acoustic-fixtures/manifest.json"
CHECKPOINT_SCHEMA_VERSION = 2
WAIT_CANCEL_JOIN_TIMEOUT_S = 2.0
SOURCE_RESULT_RECOVERY_TIMEOUT_S = 5.0
PREFLIGHT_SCHEMA_VERSION = 5
SOURCE_HELPER_CONTRACT_VERSION = "1.0.0"


SOURCE_RECEIVER_CLS = "com.kernel.ai.debug.acoustic.AcousticStimulusReceiver"
SOURCE_ACTION = "com.kernel.ai.debug.action.PLAY_ACOUSTIC_STIMULUS"
SOURCE_CANCEL_ACTION = "com.kernel.ai.debug.action.CANCEL_ACOUSTIC_STIMULUS"

# Target journal interfaces (TargetEventJournalContract).  The provider is
# authoritative for every operation, including bounded waits and cancellation.
TARGET_PACKAGE = "com.kernel.ai.debug"
TARGET_PROVIDER_URI = "content://com.kernel.ai.debug.target-event-journal"
TARGET_METHOD_GET_SEQUENCE = "GET_JOURNAL_SEQUENCE"
TARGET_METHOD_WAIT_FOR_EVENT = "WAIT_FOR_JOURNAL_EVENT"
TARGET_METHOD_GET_WAIT_STATUS = "GET_JOURNAL_WAIT_STATUS"
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
TARGET_ERROR_UNKNOWN_REQUEST_ID = "argument_error:unknown_request_id"
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

EVIDENCE_DEVICE_IDS = {
    "s21": "s21-exynos",
    "s23u": "s23-ultra",
}

# Private-identifier patterns for sanitisation
PRIVATE_PATTERNS: tuple[re.Pattern, ...] = (
    re.compile(r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d{2,5}\b"),  # IP:port
    re.compile(r"\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b"),  # MAC
    re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\b"),
    re.compile(r"(?i)\bhome\b.*\b(?:lokhor|nick|monrad)\b.*", re.IGNORECASE),
    re.compile(r"/home/[^/\s]+"),
    re.compile(r"(?i)(serial|sn|imei|boot[_ -]?id)\s*[=:]\s*\S+"),
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
    host_timing: dict[str, Any] = dataclasses.field(default_factory=dict)
    source_timing: dict[str, Any] = dataclasses.field(default_factory=dict)
    target_timing: dict[str, Any] = dataclasses.field(default_factory=dict)
    environment_before: dict[str, Any] = dataclasses.field(default_factory=dict)
    environment_after: dict[str, Any] = dataclasses.field(default_factory=dict)
    environment_summary: dict[str, Any] = dataclasses.field(default_factory=dict)
    operational_failure: str | None = None
    fixture_id: str | None = None
    fixture_sha256: str | None = None
    command_fixture_id: str | None = None
    command_fixture_sha256: str | None = None
    source_outcome: dict[str, Any] = dataclasses.field(default_factory=dict)
    command_source_outcome: dict[str, Any] = dataclasses.field(default_factory=dict)
    artifact_refs: list[str] = dataclasses.field(default_factory=list)
    source_environment_before: dict[str, Any] = dataclasses.field(default_factory=dict)
    source_environment_after: dict[str, Any] = dataclasses.field(default_factory=dict)


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
    cue_policy_evidence_verified: bool = False
    cue_audibility_evidence_verified: bool = False



# ── Sanitisation (reuses patterns from battery_telemetry_harness.py) ────

def sanitise_text(text: str, secrets: Iterable[str] = ()) -> str:
    """Redact known device secrets and private identifiers from public output."""
    sanitized = text
    for secret in sorted({v for v in secrets if v}, key=len, reverse=True):
        sanitized = sanitized.replace(secret, "[REDACTED_DEVICE_IDENTIFIER]")
    for pattern in PRIVATE_PATTERNS:
        sanitized = pattern.sub("[REDACTED]", sanitized)
    return sanitized

def sanitise_evidence(value: Any, secrets: Iterable[str] = ()) -> Any:
    """Recursively redact private strings without changing evidence structure."""
    if isinstance(value, dict):
        return {
            sanitise_text(str(key), secrets): sanitise_evidence(item, secrets)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [sanitise_evidence(item, secrets) for item in value]
    if isinstance(value, tuple):
        return tuple(sanitise_evidence(item, secrets) for item in value)
    if isinstance(value, str):
        return sanitise_text(value, secrets)
    return value


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

def git_metadata() -> tuple[str | None, str | None]:
    """Resolve branch and full commit, with explicit and CI environment overrides."""
    branch = (
        os.environ.get("GIT_BRANCH")
        or os.environ.get("GITHUB_HEAD_REF")
        or os.environ.get("GITHUB_REF_NAME")
    )
    commit = os.environ.get("GIT_COMMIT") or os.environ.get("GITHUB_SHA")
    if branch is None:
        result = subprocess.run(
            ["git", "branch", "--show-current"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        branch = (result.stdout.strip() if result.returncode == 0 else "") or "detached"
    if commit is None:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        commit = (result.stdout.strip() if result.returncode == 0 else "") or None
    return branch, commit


def monotonic_ms() -> int:
    return time.monotonic_ns() // 1_000_000


@dataclasses.dataclass(frozen=True)
class BoundaryContext:
    sequence: int
    generation: int | None

# ── ADB transport ───────────────────────────────────────────────────────

class AdbClient:
    """Thin ADB wrapper with injectable runner for testing."""

    def __init__(
        self, serial: str,
        runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    ) -> None:
        self.serial = serial
        self._runner = runner

    def run(self, *args: str, timeout: float = 30.0, check: bool = True) -> str:
        result = self._runner(
            ["adb", "-s", self.serial, *args],
            text=True, capture_output=True, timeout=timeout,
            stdin=subprocess.DEVNULL,
        )
        if check and result.returncode != 0:
            raise HarnessError(
                f"ADB command failed ({result.returncode}): {args[0] if args else ''}"
            )
        return result.stdout

    def shell(
        self, *args: str, timeout: float = 30.0, check: bool = True,
    ) -> str:
        return self.run("shell", *args, timeout=timeout, check=check)
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


def parse_ordered_broadcast_result(output: str) -> tuple[int, str]:
    """Parse the terminal result from an explicit ordered ``am broadcast``."""
    if not isinstance(output, str):
        raise HarnessError("broadcast output is not text")
    matches = re.findall(
        r'^Broadcast completed: result=(-?\d+)(?:, data="([^"]*)")?$',
        output,
        flags=re.MULTILINE,
    )
    if len(matches) != 1:
        raise HarnessError("broadcast output missing unique terminal result")
    code, data = matches[0]
    return int(code), data



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
    device_id_sha256: str = ""

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

    def approval(self) -> dict[str, Any]:
        value = self.public()
        value["build_fingerprint_sha256"] = hashlib.sha256(
            self.build_fingerprint.encode("utf-8")
        ).hexdigest()
        value["device_id_sha256"] = self.device_id_sha256
        return value

    @property
    def public_model(self) -> str:
        return self.model


@dataclasses.dataclass(frozen=True)
class InstalledBuildIdentity:
    package_name: str
    version_name: str
    version_code: int
    base_apk_sha256: str

    def public(self) -> dict[str, Any]:
        return dataclasses.asdict(self)



def device_identity(client: AdbClient, alias: str, package: str) -> DeviceIdentity:
    """Resolve a stable, privacy-preserving device identity."""
    if not client.reachable():
        raise HarnessError(f"{alias}: ADB is not reachable")

    props: dict[str, str] = {}
    for key in (
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.build.fingerprint",
        "ro.serialno",
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
    stable_device_id = props["ro.serialno"] or client.serial
    return DeviceIdentity(
        alias=alias,
        manufacturer=props["ro.product.manufacturer"],
        model=props["ro.product.model"],
        android_release=props["ro.build.version.release"],
        android_api=props["ro.build.version.sdk"],
        build_fingerprint=props["ro.build.fingerprint"],
        package_version=pkg_ver.group(1) if pkg_ver else None,
        package_version_code=int(pkg_code.group(1)) if pkg_code else None,
        device_id_sha256=hashlib.sha256(stable_device_id.encode("utf-8")).hexdigest(),
    )

def assert_distinct_device_identities(source: DeviceIdentity, target: DeviceIdentity) -> None:
    """Reject aliases or ADB endpoints that resolve to the same physical device."""
    if source.device_id_sha256 == target.device_id_sha256:
        raise HarnessError("source and target resolve to the same physical device identity")



def installed_build_identity(client: AdbClient, package: str) -> InstalledBuildIdentity:
    """Read the installed package version and hash the exact base APK bytes."""
    dumpsys = client.shell("dumpsys", "package", package)
    version_match = re.search(r"versionName=([^\s]+)", dumpsys)
    code_match = re.search(r"versionCode=(\d+)", dumpsys)
    if version_match is None or code_match is None:
        raise HarnessError("installed package version identity is unavailable")
    paths = client.shell("pm", "path", package).splitlines()
    base_paths = [
        line.removeprefix("package:").strip()
        for line in paths
        if line.startswith("package:") and line.strip().endswith("/base.apk")
    ]
    if len(base_paths) != 1:
        raise HarnessError("installed base APK path is unavailable or ambiguous")
    hash_output = client.shell("sha256sum", base_paths[0]).strip()
    hash_match = re.fullmatch(r"([0-9a-fA-F]{64})\s+\S+", hash_output)
    if hash_match is None:
        raise HarnessError("installed base APK SHA-256 is unavailable")
    return InstalledBuildIdentity(
        package_name=package,
        version_name=version_match.group(1),
        version_code=int(code_match.group(1)),
        base_apk_sha256=hash_match.group(1).lower(),
    )


def parse_fixture_manifest_contract(text: str) -> tuple[dict[str, str], dict[str, str]]:
    """Validate the installed fixture manifest and retain command expectations."""
    try:
        manifest = json.loads(text)
    except json.JSONDecodeError as exc:
        raise HarnessError(f"source fixture manifest is not valid JSON: {exc}") from exc
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 1:
        raise HarnessError("unsupported source fixture manifest")
    fixtures = manifest.get("fixtures")
    if not isinstance(fixtures, list) or not fixtures:
        raise HarnessError("source fixture manifest has no fixtures")
    hashes: dict[str, str] = {}
    transcript_hashes: dict[str, str] = {}
    for index, entry in enumerate(fixtures):
        if not isinstance(entry, dict):
            raise HarnessError(f"source fixture manifest entry {index} is not an object")
        fixture_id = entry.get("fixture_id")
        digest = entry.get("sha256")
        if not isinstance(fixture_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", fixture_id):
            raise HarnessError(f"source fixture manifest entry {index} has invalid fixture_id")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise HarnessError(f"source fixture manifest entry {index} has invalid SHA-256")
        if fixture_id in hashes:
            raise HarnessError(f"source fixture manifest repeats fixture_id {fixture_id}")
        expected_transcript = entry.get("expected_transcript_sha256")
        if expected_transcript is not None:
            if not isinstance(expected_transcript, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_transcript):
                raise HarnessError(
                    f"source fixture manifest entry {index} has invalid expected transcript SHA-256"
                )
            transcript_hashes[fixture_id] = expected_transcript
        hashes[fixture_id] = digest
    return hashes, transcript_hashes


def parse_fixture_manifest(text: str) -> dict[str, str]:
    """Validate the installed source fixture manifest and return ID-to-hash."""
    return parse_fixture_manifest_contract(text)[0]


def parse_source_cleanup_result(
    text: str,
    expected_trial_id: str | None = None,
    expected_fixture_id: str | None = None,
) -> dict[str, Any]:
    """Validate persisted cleanup evidence for every helper terminal status."""
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise HarnessError(f"source result is not valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise HarnessError("source result is not a JSON object")
    required_fields = {
        "schema_version", "trial_id", "fixture_id", "request_wall_clock_ms",
        "request_monotonic_ms", "focus_result", "completion_status",
        "evidence_persistence_failed", "timeout", "overlap_rejected",
        "cleanup_success", "exact_restoration_verified", "events",
    }
    missing = sorted(required_fields - set(data))
    if missing:
        raise HarnessError(f"source result missing fields: {', '.join(missing)}")
    if data.get("schema_version") != 1:
        raise HarnessError("unsupported source result schema_version")

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
    for field in ("request_wall_clock_ms", "request_monotonic_ms"):
        value = data.get(field)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise HarnessError(f"source result {field} must be a non-negative integer")
    if not isinstance(data.get("focus_result"), str) or not data["focus_result"]:
        raise HarnessError("source result focus_result must be a non-empty string")
    for field in (
        "evidence_persistence_failed", "timeout", "overlap_rejected",
        "cleanup_success", "exact_restoration_verified",
    ):
        if not isinstance(data.get(field), bool):
            raise HarnessError(f"source result {field} must be a boolean")

    status = data.get("completion_status")
    supported_statuses = {"completed", "cancelled", "timeout", "rejected", "failed", "invalid"}
    if status not in supported_statuses:
        raise HarnessError(f"source cleanup result has unsupported status: {status}")
    error_category = data.get("error_category")
    if error_category is not None and (
        not isinstance(error_category, str) or not error_category
    ):
        raise HarnessError("source result error_category must be a non-empty string when present")
    playback_error_category = data.get("playback_error_category")
    if playback_error_category is not None and (
        not isinstance(playback_error_category, str) or not playback_error_category
    ):
        raise HarnessError(
            "source result playback_error_category must be a non-empty string when present"
        )

    if data["evidence_persistence_failed"] is not False:
        raise HarnessError("source evidence persistence failure flag is not false")
    if data["cleanup_success"] is not True:
        raise HarnessError("source cleanup did not succeed")
    if data["exact_restoration_verified"] is not True:
        raise HarnessError("source volume restoration was not verified")

    timed_out = data["timeout"]
    overlap_rejected = data["overlap_rejected"]
    if status == "completed":
        if error_category is not None:
            raise HarnessError("completed source result must not contain an error category")
    elif status == "cancelled":
        if error_category != "operator_cancelled":
            raise HarnessError("cancelled source result is missing operator_cancelled evidence")
    elif status == "timeout":
        if not timed_out or error_category != "playback_timeout":
            raise HarnessError("timeout source result has inconsistent timeout evidence")
    elif not isinstance(error_category, str):
        raise HarnessError(f"{status} source result is missing an error category")
    if status != "timeout" and timed_out:
        raise HarnessError("non-timeout source result must not claim timeout")
    if overlap_rejected != (status == "rejected" and error_category == "overlap_rejected"):
        raise HarnessError("source result has inconsistent overlap rejection evidence")
    if playback_error_category is not None:
        raise HarnessError(
            f"{status} source result must not claim a playback error category "
            "after successful cleanup"
        )


    events = data.get("events")
    if not isinstance(events, list):
        raise HarnessError("source result events must be a list")
    allowed_event_fields = {
        "name", "monotonic_ms", "wall_clock_ms", "cleanup_success",
        "exact_restoration_verified", "error_category",
    }
    previous_monotonic_ms = -1
    for index, event in enumerate(events):
        if not isinstance(event, dict):
            raise HarnessError(f"source result event {index} must be an object")
        unexpected = sorted(set(event) - allowed_event_fields)
        if unexpected:
            raise HarnessError(
                f"source result event {index} has unexpected fields: {', '.join(unexpected)}"
            )
        if not isinstance(event.get("name"), str) or not event["name"]:
            raise HarnessError(f"source result event {index} has an invalid name")
        for field in ("monotonic_ms", "wall_clock_ms"):
            value = event.get(field)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise HarnessError(
                    f"source result event {index} {field} must be a non-negative integer"
                )
        if event["monotonic_ms"] < previous_monotonic_ms:
            raise HarnessError("source result event monotonic timestamps regressed")
        previous_monotonic_ms = event["monotonic_ms"]
        for field in ("cleanup_success", "exact_restoration_verified"):
            if field in event and not isinstance(event[field], bool):
                raise HarnessError(f"source result event {index} {field} must be a boolean")
        if "error_category" in event and (
            not isinstance(event["error_category"], str) or not event["error_category"]
        ):
            raise HarnessError(
                f"source result event {index} error_category must be a non-empty string"
            )
    if events:
        cleanup_event = events[-1]
        if cleanup_event["name"] != "cleanup_completed":
            raise HarnessError("source result events do not end with cleanup_completed")
        if cleanup_event.get("cleanup_success") is not data["cleanup_success"]:
            raise HarnessError("source cleanup event disagrees with cleanup_success")
        if cleanup_event.get("exact_restoration_verified") is not data["exact_restoration_verified"]:
            raise HarnessError("source cleanup event disagrees with exact restoration evidence")
    elif status not in {"rejected", "invalid"}:
        raise HarnessError("source result must contain cleanup events")
    return data


def parse_source_result(
    text: str,
    expected_trial_id: str | None = None,
    expected_fixture_id: str | None = None,
    expected_fixture_sha256: str | None = None,
) -> dict[str, Any]:
    """Parse and validate a successfully completed source playback result."""
    data = parse_source_cleanup_result(
        text,
        expected_trial_id=expected_trial_id,
        expected_fixture_id=expected_fixture_id,
    )
    completed_fields = {
        "fixture_sha256", "fixture_duration_ms", "prepare_monotonic_ms",
        "playback_start_monotonic_ms", "completion_monotonic_ms",
        "cleanup_monotonic_ms", "volume_before", "requested_volume",
        "applied_volume", "maximum_volume", "restored_volume",
        "output_route_before", "output_route_during",
    }
    missing = sorted(completed_fields - set(data))
    if missing:
        raise HarnessError(f"completed source result missing fields: {', '.join(missing)}")
    if data.get("completion_status") != "completed":
        raise HarnessError(
            f"source playback did not complete: "
            f"status={data.get('completion_status')} "
            f"error={data.get('error_category') or data.get('playback_error_category') or 'unknown'}"
        )
    if data.get("output_route_during") != "BUILT_IN_SPEAKER":
        raise HarnessError("source output route was not BUILT_IN_SPEAKER")
    if data.get("focus_result") != "granted":
        raise HarnessError("source audio focus was not granted")
    fixture_sha256 = data.get("fixture_sha256")
    if not isinstance(fixture_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", fixture_sha256):
        raise HarnessError("source result fixture_sha256 is invalid")
    if expected_fixture_sha256 is not None and fixture_sha256 != expected_fixture_sha256:
        raise HarnessError("source result fixture_sha256 does not match approved fixture")
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

def boundary_generation_from_snapshot(
    envelope: dict[str, Any], boundary_sequence: int,
) -> int | None:
    """Resolve the detector generation active at an exact sequence boundary."""
    validated = validate_snapshot_envelope(envelope)
    prior = [
        event for event in validated["events"]
        if event["s"] <= boundary_sequence and event["g"] > 0
    ]
    return prior[-1]["g"] if prior else None


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


def require_correlated_event(
    events: list[dict[str, Any]],
    event_type: str,
    generation: int,
    session: int,
) -> dict[str, Any]:
    """Require an event from the trial's detector generation and voice session."""
    correlated = find_event(events, event_type, generation, session)
    if correlated is None:
        raise HarnessError(
            f"{event_type} did not match detector generation {generation} "
            f"and voice session {session}"
        )
    return correlated


# ── Classification ──────────────────────────────────────────────────────

def _event_for_session(
    events: list[dict[str, Any]],
    event_type: str,
    generation: int,
    session: int,
    after_sequence: int = -1,
) -> dict[str, Any] | None:
    return next(
        (
            event for event in events
            if event["s"] > after_sequence
            and event["t"] == event_type
            and event["g"] == generation
            and event["i"] == session
        ),
        None,
    )


MEANINGFUL_WAKE_EVENTS = {
    "SILENCE_GATE_ENTERED",
    "VOICED_FRAME_AFTER_SILENCE",
    "STAGE2_RESUMED",
    "STAGE3_READY",
    "ACTIVATION_CANDIDATE",
}


def correlate_event_path(
    events: list[dict[str, Any]],
    trial_type: TrialType,
    boundary_generation: int | None = None,
    require_terminal: bool = True,
) -> tuple[int | None, int | None, dict[str, Any], list[str]]:
    """Correlate one post-boundary generation/session in strict sequence order."""
    ordered = sorted(events, key=lambda event: event["s"])
    anchor = next(
        (
            event for event in ordered
            if event["t"] in MEANINGFUL_WAKE_EVENTS and event["g"] > 0
        ),
        None,
    )
    generation = boundary_generation or (anchor["g"] if anchor else None)
    if generation is None:
        return None, None, {}, ["no meaningful post-boundary detector activity"]

    generated = [event for event in ordered if event["g"] == generation]
    path: dict[str, Any] = {
        "gate_activity": [
            event for event in generated if event["t"] in MEANINGFUL_WAKE_EVENTS
        ]
    }
    failures: list[str] = []

    def generation_event(event_type: str, after: int = -1) -> dict[str, Any] | None:
        return next(
            (
                event for event in generated
                if event["t"] == event_type and event["s"] > after
            ),
            None,
        )

    candidate = generation_event("ACTIVATION_CANDIDATE")
    if candidate is None:
        failures.append("missing correlated ACTIVATION_CANDIDATE")
    else:
        path["ACTIVATION_CANDIDATE"] = candidate
    verified = generation_event(
        "VERIFIED_ACTIVATION", candidate["s"] if candidate else -1
    )
    if verified is None:
        failures.append("missing correlated VERIFIED_ACTIVATION")
    else:
        path["VERIFIED_ACTIVATION"] = verified
    callback = generation_event(
        "WAKE_CALLBACK_INVOKED", verified["s"] if verified else -1
    )
    if callback is None or callback["i"] <= 0:
        failures.append("missing correlated WAKE_CALLBACK_INVOKED")
        callback = None
    else:
        path["WAKE_CALLBACK_INVOKED"] = callback

    session = callback["i"] if callback else None
    session_start = None
    if session is not None:
        session_start = _event_for_session(
            ordered, "VOICE_SESSION_STARTED", generation, session, callback["s"]
        )
    if session_start is None:
        failures.append("missing correlated VOICE_SESSION_STARTED")
    else:
        path["VOICE_SESSION_STARTED"] = session_start

    previous = session_start["s"] if session_start else -1
    if session is not None:
        for event_type in ("STT_START_REQUESTED", "STT_READY", "CUE_REQUESTED"):
            current = _event_for_session(ordered, event_type, generation, session, previous)
            if current is None:
                failures.append(f"missing correlated {event_type}")
                continue
            path[event_type] = current
            previous = current["s"]

        if trial_type == TrialType.WAKE_PLUS_COMMAND:
            for event_type in (
                "STT_SPEECH_DETECTED",
                "STT_FINAL",
                "COMMAND_ROUTING_RESULT",
            ):
                current = _event_for_session(ordered, event_type, generation, session, previous)
                if current is None:
                    failures.append(f"missing correlated {event_type}")
                    continue
                path[event_type] = current
                previous = current["s"]

        terminal_floor = max(
            (event["s"] for key, event in path.items() if key != "gate_activity"),
            default=-1,
        )
        terminal = next(
            (
                event for event in ordered
                if event["t"] in {"SESSION_COMPLETED", "SESSION_CANCELLED"}
                and event["g"] == generation
                and event["i"] == session
                and event["s"] > terminal_floor
            ),
            None,
        )
    else:
        terminal = None
    if terminal is None:
        if require_terminal:
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
        if rearm is None and require_terminal:
            failures.append("missing detector re-arm after correlated terminal")
        else:
            path["DETECTOR_REARMED"] = rearm
    return generation, session, path, failures





def classify_attempt(
    source_result: dict[str, Any] | None,
    target_snapshot: dict[str, Any] | None,
    trial_type: TrialType,
    boundary_generation: int | None,
    session_id: int | None,
    cue_audibility_verified: bool,
    expected_command_transcript_sha256: str | None = None,
) -> tuple[AttemptStatus, FailureClassification | None, InvalidReason | None, list[str]]:
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
        return AttemptStatus.INVALID, None, InvalidReason.EVIDENCE_BOUNDARY_LOST, [
            "required post-boundary evidence was evicted"
        ]
    events = envelope["events"]
    if not events:
        return AttemptStatus.FAILED, FailureClassification.ACOUSTIC_OR_GATE_MISS, None, ["no target events"]

    _, session, path, correlation_failures = correlate_event_path(
        events, trial_type, boundary_generation
    )
    if not path.get("gate_activity"):
        return AttemptStatus.FAILED, FailureClassification.ACOUSTIC_OR_GATE_MISS, None, [
            "no meaningful post-boundary gate or activation activity"
        ]
    if "ACTIVATION_CANDIDATE" not in path or "VERIFIED_ACTIVATION" not in path:
        return AttemptStatus.FAILED, FailureClassification.CLASSIFIER_MODEL_MISS, None, correlation_failures
    if "WAKE_CALLBACK_INVOKED" not in path or "VOICE_SESSION_STARTED" not in path:
        return AttemptStatus.FAILED, FailureClassification.ACTIVATION_HANDOFF_FAILURE, None, correlation_failures
    if session_id is not None and session != session_id:
        return AttemptStatus.INVALID, None, InvalidReason.UNKNOWN, [
            "waited session does not match correlated session"
        ]
    if "STT_START_REQUESTED" not in path or "STT_READY" not in path:
        return AttemptStatus.FAILED, FailureClassification.STT_READINESS_FAILURE, None, correlation_failures
    if "CUE_REQUESTED" not in path:
        return AttemptStatus.FAILED, FailureClassification.CUE_AUDIO_FAILURE, None, correlation_failures
    if trial_type == TrialType.WAKE_PLUS_COMMAND:
        command_keys = {"STT_SPEECH_DETECTED", "STT_FINAL", "COMMAND_ROUTING_RESULT"}
        if not command_keys.issubset(path):
            return AttemptStatus.FAILED, FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE, None, correlation_failures
        if path["COMMAND_ROUTING_RESULT"]["d"].get("outcome") != "handed_off":
            return AttemptStatus.FAILED, FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE, None, [
                "command routing did not hand off successfully"
            ]
        observed_transcript = path["STT_FINAL"]["d"].get("normalized_transcript_sha256")
        if expected_command_transcript_sha256 is None:
            return AttemptStatus.INVALID, None, InvalidReason.SOURCE_STIMULUS_FAILURE, [
                "command fixture has no expected transcript SHA-256"
            ]
        if observed_transcript != expected_command_transcript_sha256:
            return AttemptStatus.FAILED, FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE, None, [
                "captured command transcript does not match the approved fixture expectation"
            ]
    if "terminal" not in path:
        return AttemptStatus.FAILED, FailureClassification.UNCLASSIFIED, None, correlation_failures
    terminal = path["terminal"]
    if terminal["t"] == "SESSION_CANCELLED":
        category = terminal["d"].get("category")
        expected = {"stt_recognition_failed", "stt_stopped_without_result"}
        if trial_type != TrialType.WAKE_ONLY or category not in expected:
            return AttemptStatus.FAILED, FailureClassification.UNCLASSIFIED, None, [
                f"unexpected session cancellation category: {category or 'missing'}"
            ]
    if "DETECTOR_REARMED" not in path:
        return AttemptStatus.FAILED, FailureClassification.SERVICE_REARM_FAILURE, None, correlation_failures
    if not cue_audibility_verified:
        return AttemptStatus.FAILED, FailureClassification.CUE_AUDIBILITY_UNCONFIRMED, None, [
            "independent cue audibility evidence is unavailable"
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
            "w": ev.get("w"),
            "d": ev.get("d", {}),
        }
        for ev in events
    ]


def public_preflight_approval(approval: dict[str, Any] | None) -> dict[str, Any]:
    """Project approval data without stable device identifiers or private paths."""
    if not approval:
        return {}
    allowed = (
        "manifest_schema_version",
        "fixture_set_id",
        "fixture_hashes",
        "source_build",
        "target_build",
        "source_volume_index",
        "source_volume_max",
        "source_route",
        "placement_notes",
        "route_policy_approved",
        "operator_approved",
        "cue_audibility_evidence_verified",
        "cue_audibility_evidence",
        "approved_at",
        "manifest_sha256",
    )
    return {key: approval[key] for key in allowed if key in approval}


def public_environment_snapshot(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Expose stable environment facts while keeping boot identifiers private."""
    allowed = {
        "reachable", "uptime_seconds", "screen_off", "charging",
        "service_active", "media_volume", "ringer_mode", "dnd_mode",
        "bluetooth_route_active",
    }
    return {key: value for key, value in snapshot.items() if key in allowed}


def public_run_environment(
    environment: dict[str, dict[str, Any]] | None,
) -> dict[str, dict[str, Any]] | None:
    """Project paired device state without private identifiers or raw errors."""
    if environment is None:
        return None
    return {
        role: public_environment_snapshot(snapshot)
        for role, snapshot in environment.items()
        if role in {"source", "target"} and isinstance(snapshot, dict)
    }


def evidence_device_profile(alias: str, identity: DeviceIdentity) -> dict[str, Any]:
    """Resolve and verify a canonical dashboard profile from the device registry."""
    canonical_id = EVIDENCE_DEVICE_IDS.get(alias)
    if canonical_id is None:
        raise HarnessError(f"unsupported evidence device alias: {alias}")

    from summarise_test_report import build_device_obj, load_devices

    profile = build_device_obj(canonical_id, load_devices())
    if identity.model != profile["model"]:
        raise HarnessError(
            f"target alias {alias!r} expected model {profile['model']!r}, got {identity.model!r}"
        )
    return profile


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
    invalid = sum(1 for a in attempts if a.status == AttemptStatus.INVALID)
    passed = sum(1 for a in attempts if a.status == AttemptStatus.PASSED)
    failed = sum(1 for a in attempts if a.status == AttemptStatus.FAILED)
    total = len(attempts)
    valid = passed + failed
    branch, commit = git_metadata()
    required_positions = {
        slot.position_id for slot in matrix_slots_for_target(run_manifest.target_alias)
    }
    valid_counts = {
        position: sum(
            1
            for attempt in attempts
            if attempt.required_position_id == position
            and attempt.status in {AttemptStatus.PASSED, AttemptStatus.FAILED}
        )
        for position in required_positions
    }
    complete_valid_matrix = all(count == 1 for count in valid_counts.values())
    all_required_passed = complete_valid_matrix and all(
        any(
            attempt.required_position_id == position
            and attempt.status == AttemptStatus.PASSED
            for attempt in attempts
        )
        for position in required_positions
    )

    evidence: dict[str, Any] = {
        "schema_version": "1.1",
        "source": "on_device",
        "suite": "wake_word_acoustic_reliability",
        "timestamp": run_manifest.created_utc,
        "repo": "NickMonrad/kernel-ai-assistant",
        "branch": branch,
        "commit": commit,
        "pr": int(os.environ["GIT_PR"]) if "GIT_PR" in os.environ else None,
        "release": None,
        "run_id": run_manifest.run_id,
        "device": evidence_device_profile(run_manifest.target_alias, target_identity),
        # The current runner cannot prove the exact accelerator used by the
        # wake-word runtime. Do not publish an inferred NPU claim.
        "model": {"name": "wake_word", "runtime": "ONNX", "backend": None},
        "summary": {
            "total": valid,
            "total_attempts": total,
            "valid": valid,
            "passed": passed,
            "failed": failed,
            "invalid": invalid,
            "pass_rate": round(passed / valid, 4) if valid > 0 else 0.0,
        },
        "cases": [],
        "artifact_refs": [],
        "wake_reliability": {
            "run_kind": run_manifest.run_kind.value,
            "gate_mode": run_manifest.gate_mode.value,
            "matrix_id": run_manifest.matrix_id,
            "matrix_version": run_manifest.matrix_version,
            "source": source_identity.public(),
            "source_helper_version": source_helper_version,
            "fixture_set_id": run_manifest.fixture_set_id,
            "fixture_hashes": run_manifest.fixture_hashes,
            "preflight_approval": public_preflight_approval(preflight_approval),
            "approved_source_volume": preflight_approval.get("source_volume_index") if preflight_approval else None,
            "approved_source_volume_max": preflight_approval.get("source_volume_max") if preflight_approval else None,
            "approved_source_route": source_route,
            "placement_notes": (preflight_approval or {}).get("placement_notes"),
            "cue_policy_version": run_manifest.cue_policy_version,
            "monitored_acoustic_check": run_manifest.cue_policy_version is not None,
            "cue_policy_evidence_verified": run_manifest.cue_policy_evidence_verified,
            "cue_audibility_evidence_verified": run_manifest.cue_audibility_evidence_verified,
            "preflight_manifest_sha256": run_manifest.preflight_hash,
            "expected_valid_counts": {
                slot.position_id: 1
                for slot in matrix_slots_for_target(run_manifest.target_alias)
            },
            "cleanup_verified": cleanup_verified,
            "release_gate_success": False,
            "complete": False,
            "complete_valid_matrix": complete_valid_matrix,
            "all_required_passed": all_required_passed,
            "release_provenance_verified": False,
            "feasibility_only": run_manifest.run_kind == RunKind.FEASIBILITY,
            "run_environment_before": None,
            "run_environment_after": None,
            "cleanup_failures": [],
            "abort_reason": None,
            "completion": {"status": "rendered"},
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
            "environment_before": public_environment_snapshot(attempt.environment_before),
            "environment_after": public_environment_snapshot(attempt.environment_after),
            "source_environment_before": public_environment_snapshot(
                attempt.source_environment_before
            ),
            "source_environment_after": public_environment_snapshot(
                attempt.source_environment_after
            ),
            "environment_summary": attempt.environment_summary,
            "fixture": {"id": attempt.fixture_id, "sha256": attempt.fixture_sha256},
            "source_outcome": attempt.source_outcome,
            "artifact_refs": attempt.artifact_refs,
            **(
                {
                    "command_fixture": {
                        "id": attempt.command_fixture_id,
                        "sha256": attempt.command_fixture_sha256,
                    },
                    "command_source_outcome": attempt.command_source_outcome,
                }
                if attempt.command_fixture_id is not None
                else {}
            ),
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

    evidence["artifact_refs"] = sorted({
        path
        for case in evidence["cases"]
        for path in case.get("artifact_refs", [])
    })
    assert_commit_safe(evidence, secrets)
    return evidence


def _referenced_artifact_paths(evidence: dict[str, Any]) -> list[str]:
    """Return the deduplicated allow-list of artifacts named by evidence."""
    references: set[str] = set()
    for owner in [evidence, *evidence.get("cases", [])]:
        if not isinstance(owner, dict):
            continue
        values = owner.get("artifact_refs", [])
        if not isinstance(values, list):
            raise HarnessError("artifact_refs must be a list")
        for value in values:
            if not isinstance(value, str) or not value:
                raise HarnessError("artifact_refs entries must be non-empty strings")
            references.add(value)
    return sorted(references)


def _copy_sanitised_artifacts(
    output_dir: Path,
    evidence: dict[str, Any],
    private_run_dir: Path,
    secrets: Iterable[str],
) -> None:
    """Copy only explicitly referenced, text-safe artifacts into the public tree."""
    private_root = private_run_dir.resolve()
    allowed_suffixes = {".csv", ".json", ".log", ".md", ".txt"}
    for reference in _referenced_artifact_paths(evidence):
        relative = Path(reference)
        if relative.is_absolute() or ".." in relative.parts or relative == Path("."):
            raise HarnessError(f"unsafe artifact reference: {reference}")
        if relative.suffix.lower() not in allowed_suffixes:
            raise HarnessError(f"unsupported public artifact type: {reference}")

        source = private_run_dir / relative
        if not source.is_file() or source.is_symlink():
            raise HarnessError(f"referenced artifact is missing or unsafe: {reference}")
        resolved_source = source.resolve()
        try:
            resolved_source.relative_to(private_root)
        except ValueError as exc:
            raise HarnessError(f"artifact escapes private run root: {reference}") from exc
        current = source.parent
        while current != private_run_dir:
            if current.is_symlink():
                raise HarnessError(f"artifact traverses a symlink: {reference}")
            current = current.parent

        destination = output_dir / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        if relative.suffix.lower() == ".json":
            try:
                payload = json.loads(source.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError) as exc:
                raise HarnessError(f"invalid referenced JSON artifact {reference}: {exc}") from exc
            content = json.dumps(sanitise_evidence(payload, secrets), indent=2) + "\n"
        else:
            try:
                content = sanitise_text(source.read_text(encoding="utf-8"), secrets)
            except OSError as exc:
                raise HarnessError(f"cannot read referenced artifact {reference}: {exc}") from exc
        destination.write_text(content, encoding="utf-8")


def write_sanitized_summary(
    output_dir: Path,
    evidence: dict[str, Any],
    secrets: Iterable[str] = (),
    private_run_dir: Path | None = None,
) -> tuple[Path, Path]:
    assert_commit_safe(evidence, secrets)
    schema_errors = schema_validation_errors(evidence)
    if schema_errors:
        raise HarnessError("Schema validation failed: " + "; ".join(schema_errors))

    safe = sanitise_evidence(evidence, secrets)
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / "evidence.json"
    json_path.write_text(json.dumps(safe, indent=2) + "\n", encoding="utf-8")
    if private_run_dir is not None:
        _copy_sanitised_artifacts(output_dir, safe, private_run_dir, secrets)

    md_lines = [
        "# Acoustic Wake Reliability Report",
        "",
        f"**Kind:** {safe.get('wake_reliability', {}).get('run_kind', 'unknown')}",
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
        if fixed_command_delay_ms is not None and run_kind != RunKind.FEASIBILITY:
            raise HarnessError("fixed command delay is allowed only in feasibility mode")
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
        self.source_build: InstalledBuildIdentity | None = None
        self.target_build: InstalledBuildIdentity | None = None
        self.installed_fixture_hashes: dict[str, str] = {}
        self.installed_command_transcript_hashes: dict[str, str] = {}

        self.cue_audibility_evidence_verified: bool = False
        self.cue_audibility_evidence: dict[str, Any] | None = None

        self.attempts: list[MatrixAttempt] = []
        self.source_results: list[dict[str, Any]] = []
        self.completed_slots: set[str] = set()
        self.valid_failed_slots: set[str] = set()
        self.invalid_attempt_count: int = 0
        self.abort_reason: str | None = None
        self.cleanup_verified: bool = False
        self.cleanup_failures: list[str] = []
        self.primary_failure: str | None = None
        self.run_environment_before: dict[str, dict[str, Any]] | None = None
        self.run_environment_after: dict[str, dict[str, Any]] | None = None
        self.secrets: list[str] = []
        self._cancel = threading.Event()
        self._wait_lock = threading.Lock()
        self._active_wait_request_id: str | None = None
        self._active_source_trial_id: str | None = None
        self._active_source_fixture_id: str | None = None

        self._last_boundary_sequence: int | None = None
        self._last_target_snapshot: dict[str, Any] | None = None
        self.preflight_manifest_hash: str | None = None
        self.cue_policy_evidence_verified: bool = False

    # ── Private file helpers ─────────────────────────────────────────

    @staticmethod
    def _preflight_manifest_hash(manifest: dict[str, Any]) -> str:
        unsigned = dict(manifest)
        unsigned.pop("manifest_sha256", None)
        canonical = json.dumps(unsigned, sort_keys=True, separators=(",", ":")).encode()
        return hashlib.sha256(canonical).hexdigest()

    def private_write(self, subdir: str, name: str, content: str) -> Path:
        path = self.run_dir / subdir / name
        path.parent.mkdir(parents=True, exist_ok=True)
        # Atomic write via temp + rename
        tmp = path.with_suffix(f".tmp.{uuid.uuid4().hex[:8]}")
        tmp.write_text(content)
        tmp.rename(path)
        return path

    def checkpoint(self, name: str = "checkpoint") -> dict[str, Any]:
        attempts = []
        for attempt in self.attempts:
            value = dataclasses.asdict(attempt)
            value["status"] = attempt.status.value
            value["classification"] = (
                attempt.classification.value if attempt.classification else None
            )
            value["invalid_reason"] = (
                attempt.invalid_reason.value if attempt.invalid_reason else None
            )
            value["matrix_slot"].pop("position_id", None)
            attempts.append(value)

        manifest = dataclasses.asdict(self.manifest) if self.manifest else None
        if manifest:
            manifest["run_kind"] = self.manifest.run_kind.value
            manifest["gate_mode"] = self.manifest.gate_mode.value
        state = {
            "schema_version": CHECKPOINT_SCHEMA_VERSION,
            "run_id": self.run_id,
            "run_kind": self.run_kind.value,
            "gate_mode": self.gate_mode.value,
            "matrix_id": MATRIX_ID,
            "matrix_version": MATRIX_VERSION,
            "source_alias": self.source_alias,
            "target_alias": self.target_alias,
            "preflight_approval": self.preflight_approval,
            "preflight_manifest_hash": self.preflight_manifest_hash,
            "source_build": dataclasses.asdict(self.source_build) if self.source_build else None,
            "target_build": dataclasses.asdict(self.target_build) if self.target_build else None,
            "installed_fixture_hashes": dict(self.installed_fixture_hashes),
            "installed_command_transcript_hashes": dict(self.installed_command_transcript_hashes),

            "cue_audibility_evidence_verified": self.cue_audibility_evidence_verified,
            "cue_policy_evidence_verified": self.cue_policy_evidence_verified,
            "cue_audibility_evidence": self.cue_audibility_evidence,
            "completed_slots": sorted(self.completed_slots),
            "valid_failed_slots": sorted(self.valid_failed_slots),
            "invalid_attempt_count": self.invalid_attempt_count,
            "abort_reason": self.abort_reason,
            "cleanup_verified": self.cleanup_verified,
            "cleanup_failures": list(self.cleanup_failures),
            "primary_failure": self.primary_failure,
            "run_environment_before": self.run_environment_before,
            "run_environment_after": self.run_environment_after,
            "attempts": attempts,
            "source_results": list(self.source_results),
            "manifest": manifest,
            "updated_utc": utc_now(),
        }
        self.private_write("", f"{name}.json", json.dumps(state, indent=2))
        return state

    def load_checkpoint(self, run_id: str) -> dict[str, Any]:
        """Load, validate, and restore a prior checkpoint."""
        self.run_id = run_id
        self.run_dir = self.private_root / run_id
        self.trials_dir = self.run_dir / "trials"
        self.sanitized_dir = self.run_dir / "sanitized"
        path = self.run_dir / "checkpoint.json"
        if not path.exists():
            raise HarnessError(f"no checkpoint found at {path}")
        try:
            state = json.loads(path.read_text())
        except (json.JSONDecodeError, OSError) as exc:
            raise HarnessError(f"corrupted checkpoint: {exc}") from exc
        if state.get("schema_version") != CHECKPOINT_SCHEMA_VERSION:
            raise HarnessError("checkpoint schema version mismatch")
        if state.get("matrix_id") != MATRIX_ID or state.get("matrix_version") != MATRIX_VERSION:
            raise HarnessError("checkpoint matrix contract mismatch")

        self.run_kind = RunKind(state["run_kind"])
        self.gate_mode = GateMode(state["gate_mode"])
        self.source_alias = state["source_alias"]
        self.target_alias = state["target_alias"]
        self.attempts = []
        for item in state.get("attempts", []):
            slot_data = dict(item["matrix_slot"])
            slot_data.pop("position_id", None)
            self.attempts.append(MatrixAttempt(
                trial_id=item["trial_id"],
                matrix_slot=MatrixSlot(**slot_data),
                attempt=int(item["attempt"]),
                status=AttemptStatus(item["status"]),
                classification=(
                    FailureClassification(item["classification"])
                    if item.get("classification") else None
                ),
                invalid_reason=(
                    InvalidReason(item["invalid_reason"])
                    if item.get("invalid_reason") else None
                ),
                host_start_ms=int(item.get("host_start_ms", 0)),
                host_duration_ms=int(item.get("host_duration_ms", 0)),
                failures=list(item.get("failures", [])),
                invalid_details=dict(item.get("invalid_details", {})),
                source_timing=dict(item.get("source_timing", {})),
                target_timing=dict(item.get("target_timing", {})),
                environment_before=dict(item.get("environment_before", {})),
                environment_after=dict(item.get("environment_after", {})),
                environment_summary=dict(item.get("environment_summary", {})),
                operational_failure=item.get("operational_failure"),
                fixture_id=item.get("fixture_id"),
                fixture_sha256=item.get("fixture_sha256"),
                command_fixture_id=item.get("command_fixture_id"),
                command_fixture_sha256=item.get("command_fixture_sha256"),
                source_outcome=dict(item.get("source_outcome", {})),
                command_source_outcome=dict(item.get("command_source_outcome", {})),
                artifact_refs=list(item.get("artifact_refs", [])),
                source_environment_before=dict(
                    item.get("source_environment_before", {})
                ),
                source_environment_after=dict(
                    item.get("source_environment_after", {})
                ),
            ))
        self.source_results = list(state.get("source_results", []))
        self.completed_slots = set(state.get("completed_slots", []))
        self.valid_failed_slots = set(state.get("valid_failed_slots", []))
        self.invalid_attempt_count = int(state.get("invalid_attempt_count", 0))
        self.abort_reason = state.get("abort_reason")
        self.cleanup_verified = bool(state.get("cleanup_verified", False))
        self.cleanup_failures = list(state.get("cleanup_failures", []))
        self.primary_failure = state.get("primary_failure")
        self.run_environment_before = state.get("run_environment_before")
        self.run_environment_after = state.get("run_environment_after")
        self.preflight_approval = state.get("preflight_approval")
        self.preflight_manifest_hash = state.get("preflight_manifest_hash")
        self.cue_policy_evidence_verified = bool(
            state.get("cue_policy_evidence_verified", False)
        )
        if state.get("source_build"):
            self.source_build = InstalledBuildIdentity(**state["source_build"])
        if state.get("target_build"):
            self.target_build = InstalledBuildIdentity(**state["target_build"])
        self.installed_fixture_hashes = dict(state.get("installed_fixture_hashes", {}))
        self.installed_command_transcript_hashes = dict(
            state.get("installed_command_transcript_hashes", {})
        )
        self.cue_audibility_evidence_verified = bool(
            state.get("cue_audibility_evidence_verified", False)
        )
        self.cue_audibility_evidence = state.get("cue_audibility_evidence")
        manifest = state.get("manifest")
        if manifest:
            manifest = dict(manifest)
            manifest["run_kind"] = RunKind(manifest["run_kind"])
            manifest["gate_mode"] = GateMode(manifest["gate_mode"])
            self.manifest = RunManifest(**manifest)

        preflight_path = self.run_dir / "preflight-private.json"
        if self.preflight_approval is not None:
            if not preflight_path.exists():
                raise HarnessError("checkpoint references missing preflight manifest")
            try:
                actual_preflight = json.loads(preflight_path.read_text())
            except (OSError, json.JSONDecodeError) as exc:
                raise HarnessError(f"cannot read checkpoint preflight manifest: {exc}") from exc
            actual_hash = self._preflight_manifest_hash(actual_preflight)
            if actual_hash != self.preflight_manifest_hash:
                raise HarnessError("checkpoint preflight manifest hash mismatch")
            if actual_preflight != self.preflight_approval:
                raise HarnessError("checkpoint preflight manifest content mismatch")
        return state
    def load_preflight_manifest(self, path: Path) -> dict[str, Any]:
        """Load and strictly verify a previously approved immutable manifest."""
        try:
            manifest = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError) as exc:
            raise HarnessError(f"cannot read preflight manifest: {exc}") from exc
        if not isinstance(manifest, dict):
            raise HarnessError("preflight manifest must be a JSON object")
        expected_contract = {
            "schema_version": PREFLIGHT_SCHEMA_VERSION,
            "matrix_id": MATRIX_ID,
            "matrix_version": MATRIX_VERSION,
            "source_helper_contract_version": SOURCE_HELPER_CONTRACT_VERSION,
            "target_journal_contract_version": JOURNAL_CONTRACT_VERSION,
        }
        for field, expected in expected_contract.items():
            if manifest.get(field) != expected:
                raise HarnessError(
                    f"preflight manifest {field} mismatch: "
                    f"expected {expected!r}, got {manifest.get(field)!r}"
                )
        if not manifest.get("operator_approved"):
            raise HarnessError("preflight manifest is not operator-approved")
        if not manifest.get("cue_audibility_evidence_verified"):
            raise HarnessError("preflight manifest lacks cue audibility evidence")
        if not isinstance(manifest.get("fixture_hashes"), dict):
            raise HarnessError("preflight manifest fixture_hashes must be an object")
        if not isinstance(manifest.get("command_transcript_hashes"), dict):
            raise HarnessError("preflight manifest command_transcript_hashes must be an object")
        for field in ("source_environment_state", "target_environment_state"):
            state = manifest.get(field)
            if not isinstance(state, dict) or not state:
                raise HarnessError(f"preflight manifest {field} must be a non-empty object")
            if state.get("capture_errors"):
                raise HarnessError(f"preflight manifest {field} contains capture errors")
        if not isinstance(manifest.get("attempts"), list):
            raise HarnessError("preflight manifest attempts must be an array")
        expected_hash = manifest.get("manifest_sha256")
        actual_hash = self._preflight_manifest_hash(manifest)
        if expected_hash != actual_hash:
            raise HarnessError("preflight manifest hash mismatch")
        self.preflight_approval = manifest
        self.preflight_manifest_hash = expected_hash
        self.cue_policy_evidence_verified = bool(manifest.get("cue_policy_evidence_verified"))
        self.cue_audibility_evidence_verified = True
        self.private_write("", "preflight-private.json", json.dumps(manifest, indent=2))
        return manifest


    # ── Preflight ────────────────────────────────────────────────────

    def run_preflight(
        self,
        fixture_set_id: str,
        fixture_hashes: dict[str, str],
        cue_policy_version: str | None = None,
    ) -> dict[str, Any]:
        """Play monitored audio before accepting explicit audibility approval."""
        if not self.interactive:
            raise HarnessError("preflight requires --interactive")
        self.source_identity = device_identity(self.source, self.source_alias, self.package)
        self.target_identity = device_identity(self.target, self.target_alias, self.package)
        assert_distinct_device_identities(self.source_identity, self.target_identity)
        self.source_build = installed_build_identity(self.source, self.package)
        self.target_build = installed_build_identity(self.target, self.package)
        if service_active(self.source, self.package):
            raise HarnessError("source wake service must be disabled")
        if not service_active(self.target, self.package):
            raise HarnessError("target wake service must be active")
        if self._has_active_bluetooth_route(self.source) or self._has_active_bluetooth_route(self.target):
            raise HarnessError("external Bluetooth audio route detected")

        installed_hashes = self._read_fixture_manifest()
        self.installed_fixture_hashes = installed_hashes
        if "natural_wake" not in installed_hashes:
            raise HarnessError("required installed fixture 'natural_wake' not found")
        if fixture_hashes and fixture_hashes != installed_hashes:
            raise HarnessError("requested fixture hashes do not match the installed source manifest")
        resolved_set_id = fixture_set_id or hashlib.sha256(
            json.dumps(installed_hashes, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()[:16]

        placement_notes = input(
            "Enter fixed placement notes (distance and orientation) before playback: "
        ).strip()
        if not placement_notes:
            raise HarnessError("preflight requires non-empty placement notes")
        source_environment = self._snapshot_source_state()
        target_environment = self._snapshot_target_state()
        source_audio = {
            key: source_environment.get(key)
            for key in ("alias", "media_volume", "media_volume_max", "ringer_mode", "dnd_mode")
        }
        target_audio = {
            key: target_environment.get(key)
            for key in ("alias", "media_volume", "media_volume_max", "ringer_mode", "dnd_mode")
        }
        initial_capture_errors = [
            *(f"source: {item}" for item in source_environment.get("capture_errors", [])),
            *(f"target: {item}" for item in target_environment.get("capture_errors", [])),
        ]
        if initial_capture_errors:
            raise HarnessError("preflight environment capture failed: " + "; ".join(initial_capture_errors))
        if target_environment.get("screen_off") is not True:
            raise HarnessError("preflight requires the target screen to be off")
        if target_environment.get("sensor_privacy", {}).get("microphone") != "disabled":
            raise HarnessError("preflight requires target microphone sensor privacy to be disabled")
        if source_environment.get("sensor_privacy", {}).get("microphone") != "disabled":
            raise HarnessError("preflight requires source microphone sensor privacy to be disabled")
        self.run_environment_before = {
            "source": copy.deepcopy(source_environment),
            "target": copy.deepcopy(target_environment),
        }
        initial_target = target_environment
        current_volume = max(1, int(source_environment["media_volume_max"] * 0.6))
        approved = False
        attempts: list[dict[str, Any]] = []

        for preflight_attempt in range(1, 4):
            sequence_code, sequence_data = self._call_target_provider(TARGET_METHOD_GET_SEQUENCE)
            if sequence_code != TARGET_RESULT_OK:
                raise HarnessError(f"preflight target sequence failed: {sequence_data}")
            boundary = parse_journal_sequence(sequence_data)
            trial_id = f"preflight-{preflight_attempt:02d}"
            result = self._invoke_source(
                trial_id,
                "natural_wake",
                current_volume,
            )
            self._wait_for_target_events(
                since_sequence=boundary,
                event_type="CUE_REQUESTED",
                timeout_ms=TARGET_WAIT_DEFAULT_TIMEOUT_MS,
            )
            snapshot_code, snapshot_data = self._call_target_provider(
                TARGET_METHOD_GET_SNAPSHOT,
                extras={TARGET_EXTRA_SINCE_SEQUENCE: boundary},
            )
            if snapshot_code != TARGET_RESULT_OK:
                raise HarnessError(f"preflight target snapshot failed: {snapshot_data}")
            envelope = parse_journal_snapshot(snapshot_data)
            events = envelope["events"]
            parsed = parse_source_result(
                json.dumps(result),
                expected_trial_id=trial_id,
                expected_fixture_id="natural_wake",
                expected_fixture_sha256=installed_hashes["natural_wake"],
            )
            boundary_generation = boundary_generation_from_snapshot(envelope, boundary)
            generation, session, event_path, correlation_failures = correlate_event_path(
                envelope["events"],
                TrialType.WAKE_ONLY,
                boundary_generation,
                require_terminal=False,
            )
            gate_evidence = "VERIFIED_ACTIVATION" in event_path
            cue_event = event_path.get("CUE_REQUESTED")
            source_environment_after = self._snapshot_source_state()
            target_environment_after = self._snapshot_target_state()
            preflight_environment_failures = self._environment_failures(
                target_environment,
                target_environment_after,
                target_environment,
            )
            preflight_environment_failures.extend(self._source_environment_failures(
                source_environment,
                source_environment_after,
                source_environment,
            ))
            attempts.append(
                {
                    "attempt": preflight_attempt,
                    "volume_index": current_volume,
                    "fixture_id": "natural_wake",
                    "fixture_sha256": installed_hashes["natural_wake"],
                    "boundary_sequence": boundary,
                    "correlated_generation": generation,
                    "correlated_session": session,
                    "correlation_failures": correlation_failures,
                    "source_completed": True,
                    "target_gate_evidence": gate_evidence,
                    "target_cue_requested": cue_event is not None,
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
                    "source_environment_before": source_environment,
                    "source_environment_after": source_environment_after,
                    "target_environment_before": target_environment,
                    "target_environment_after": target_environment_after,
                    "environment_failures": preflight_environment_failures,
                }
            )
            if preflight_environment_failures:
                raise HarnessError(
                    "preflight environment changed during monitored playback: "
                    + "; ".join(preflight_environment_failures)
                )
            choice = input(
                f"Playback completed at volume {current_volume}. Only enter APPROVE if "
                "the source wake phrase AND target acknowledgement cue were audibly heard. "
                "Enter APPROVE, UP <N>, DOWN <N>, or ABORT: "
            ).strip().lower()
            if choice == "approve":
                if correlation_failures or not gate_evidence or cue_event is None:
                    detail = "; ".join(correlation_failures) or "gate/cue evidence is missing"
                    raise HarnessError(
                        f"operator approval rejected: correlated target path is incomplete: {detail}"
                    )
                approved = True
                self.cue_audibility_evidence_verified = True
                self.cue_audibility_evidence = {
                    "kind": "human_monitored_preflight",
                    "attempt": preflight_attempt,
                    "cue_event_sequence": cue_event["s"],
                    "generation": generation,
                    "session": session,
                    "approved_at": utc_now(),
                }
                break
            if choice == "abort":
                raise HarnessError("preflight cancelled by operator")
            parts = choice.split()
            if len(parts) == 2 and parts[0] in {"up", "down"}:
                try:
                    delta = max(1, min(3, int(parts[1])))
                except ValueError as exc:
                    raise HarnessError("preflight adjustment must use UP/DOWN <1-3>") from exc
                current_volume = (
                    min(self._get_media_max_volume(self.source), current_volume + delta)
                    if parts[0] == "up"
                    else max(1, current_volume - delta)
                )
                continue
            raise HarnessError("preflight requires APPROVE, UP <N>, DOWN <N>, or ABORT")

        if not approved:
            raise HarnessError("preflight did not receive approval within three attempts")
        manifest = {
            "schema_version": PREFLIGHT_SCHEMA_VERSION,
            "matrix_id": MATRIX_ID,
            "matrix_version": MATRIX_VERSION,
            "source_helper_contract_version": SOURCE_HELPER_CONTRACT_VERSION,
            "target_journal_contract_version": JOURNAL_CONTRACT_VERSION,
            "cue_policy_version": cue_policy_version,
            "cue_policy_evidence_verified": cue_policy_version is not None,
            "fixture_set_id": resolved_set_id,
            "fixture_hashes": installed_hashes,
            "command_transcript_hashes": dict(self.installed_command_transcript_hashes),
            "source_role": self.source_alias,
            "target_role": self.target_alias,
            "source_identity": self.source_identity.approval(),
            "target_identity": self.target_identity.approval(),
            "source_build": self.source_build.public(),
            "target_build": self.target_build.public(),
            "source_audio_state": source_audio,
            "target_audio_state": target_audio,
            "source_environment_state": source_environment,
            "target_environment_state": target_environment,
            "source_volume_index": current_volume,
            "source_volume_max": self._get_media_max_volume(self.source),
            "source_route": "BUILT_IN_SPEAKER",
            "target_boot_id": target_environment.get("boot_id"),
            "target_service_active": target_environment.get("service_active"),
            "target_screen_off": target_environment.get("screen_off"),
            "target_charging": target_environment.get("charging"),
            "placement_notes": placement_notes,
            "operator_approved": True,
            "cue_audibility_evidence_verified": True,
            "cue_audibility_evidence": self.cue_audibility_evidence,
            "attempts": attempts,
            "approved_at": utc_now(),
        }
        manifest["manifest_sha256"] = self._preflight_manifest_hash(manifest)
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
        attempt.host_timing = {
            "clock_domain": "host_monotonic",
            "trial_start_monotonic_ms": host_start,
        }
        boundary_sequence = 0
        boundary_generation: int | None = None
        parsed_source: dict[str, Any] | None = None
        command_source: dict[str, Any] | None = None
        attempt.fixture_id = fixture_id
        attempt.command_fixture_id = command_fixture_id
        attempt.artifact_refs = []
        try:
            attempt.invalid_reason = InvalidReason.DEVICE_ENVIRONMENT_ERROR
            attempt.environment_before = self._snapshot_target_state()
            attempt.source_environment_before = self._snapshot_source_state()
            if not attempt.environment_before.get("reachable", False):
                raise HarnessError("target became unreachable before trial")

            # Capture the exact journal boundary before idle. From this point until
            # wake playback completes, the runner sends no command to the target.
            sequence_code, sequence_data = self._call_target_provider(
                TARGET_METHOD_GET_SEQUENCE,
            )
            if sequence_code != TARGET_RESULT_OK:
                raise HarnessError(f"target boundary sequence failed: {sequence_data}")
            boundary_sequence = parse_journal_sequence(sequence_data)
            boundary_code, boundary_data = self._call_target_provider(
                TARGET_METHOD_GET_SNAPSHOT,
                extras={TARGET_EXTRA_SINCE_SEQUENCE: 0},
            )
            if boundary_code != TARGET_RESULT_OK:
                raise HarnessError(f"target boundary snapshot failed: {boundary_data}")
            boundary_snapshot = parse_journal_snapshot(boundary_data)
            boundary_generation = boundary_generation_from_snapshot(
                boundary_snapshot,
                boundary_sequence,
            )
            self.private_write(
                f"trials/{trial_id}/target",
                "boundary-snapshot.json",
                json.dumps(boundary_snapshot, indent=2),
            )
            attempt.artifact_refs.append(
                f"trials/{trial_id}/target/boundary-snapshot.json"
            )
            if boundary_generation is None:
                raise HarnessError("target detector generation is unavailable at trial boundary")
            self._last_boundary_sequence = boundary_sequence
            attempt.target_timing["boundary_sequence"] = boundary_sequence
            attempt.target_timing["boundary_generation"] = boundary_generation
            self.checkpoint("pre-idle")

            idle_start_ms = monotonic_ms()
            remaining_ms = matrix_slot.idle_s * 1000
            while remaining_ms > 0 and not self._cancel.is_set():
                sleep_ms = min(remaining_ms, 100)
                time.sleep(sleep_ms / 1000.0)
                remaining_ms -= sleep_ms
            if self._cancel.is_set():
                attempt.invalid_reason = InvalidReason.OPERATOR_CANCELLED
                raise HarnessError("cancelled during idle")
            attempt.host_timing["idle_start_monotonic_ms"] = idle_start_ms
            attempt.host_timing["idle_end_monotonic_ms"] = monotonic_ms()

            volume = int((self.preflight_approval or {}).get("source_volume_index", 7))
            expected_wake_hash = self.installed_fixture_hashes.get(fixture_id)
            if expected_wake_hash is None:
                raise HarnessError(f"fixture {fixture_id!r} is absent from approved manifest")
            attempt.fixture_sha256 = expected_wake_hash
            attempt.invalid_reason = InvalidReason.SOURCE_STIMULUS_FAILURE
            source_result = self._invoke_source(
                trial_id,
                fixture_id,
                volume,
            )
            attempt.artifact_refs.append(f"trials/{trial_id}/source/result.json")
            events, wake_envelope = self._wait_for_target_events(
                since_sequence=boundary_sequence,
                event_type="STT_READY",
                timeout_ms=TARGET_WAIT_DEFAULT_TIMEOUT_MS,
            )
            self._last_target_snapshot = wake_envelope
            parsed_source = parse_source_result(
                json.dumps(source_result),
                expected_trial_id=trial_id,
                expected_fixture_id=fixture_id,
                expected_fixture_sha256=expected_wake_hash,
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
            attempt.source_timing["clock_domain"] = "source_device_elapsed_realtime"
            attempt.source_outcome = {
                key: parsed_source.get(key)
                for key in (
                    "completion_status", "cleanup_success",
                    "exact_restoration_verified", "output_route_during",
                    "focus_result", "timeout", "overlap_rejected",
                )
            }
            self.checkpoint("post-source")

            attempt.invalid_reason = InvalidReason.EVIDENCE_BOUNDARY_LOST
            stt_ready = find_event(
                events,
                "STT_READY",
                generation=boundary_generation,
            )
            correlated_session = stt_ready.get("i") if stt_ready is not None else None

            if not matrix_slot.wake_only and command_fixture_id is not None:
                expected_command_hash = self.installed_fixture_hashes.get(command_fixture_id)
                if expected_command_hash is None:
                    raise HarnessError(
                        f"fixture {command_fixture_id!r} is absent from approved manifest"
                    )
                attempt.command_fixture_sha256 = expected_command_hash
                cue_ready = True
                if self.is_feasibility and self.fixed_command_delay_ms:
                    time.sleep(self.fixed_command_delay_ms / 1000.0)
                else:
                    cue_events, cue_envelope = self._wait_for_target_events(
                        since_sequence=boundary_sequence,
                        event_type="CUE_REQUESTED",
                        timeout_ms=TARGET_WAIT_DEFAULT_TIMEOUT_MS,
                    )
                    self._last_target_snapshot = cue_envelope
                    cue = find_event(
                        cue_events,
                        "CUE_REQUESTED",
                        generation=boundary_generation,
                        session=correlated_session,
                    )
                    cue_ready = cue is not None
                if cue_ready and not (
                    self.is_feasibility and self.fixed_command_delay_ms
                ):
                    time.sleep(self.cue_margin_ms / 1000.0)
                if cue_ready:
                    command_trial_id = f"{trial_id}-cmd"
                    attempt.invalid_reason = InvalidReason.SOURCE_STIMULUS_FAILURE
                    command_source, command_events, command_envelope = (
                        self._invoke_command_source_with_armed_wait(
                            trial_id=command_trial_id,
                            fixture_id=command_fixture_id,
                            volume_index=volume,
                            since_sequence=boundary_sequence,
                            timeout_ms=TARGET_WAIT_DEFAULT_TIMEOUT_MS,
                        )
                    )
                    attempt.artifact_refs.append(
                        f"trials/{command_trial_id}/source/result.json"
                    )
                    require_correlated_event(
                        command_events,
                        "COMMAND_ROUTING_RESULT",
                        boundary_generation,
                        correlated_session,
                    )
                    self._last_target_snapshot = command_envelope
                    parsed_command_source = parse_source_result(
                        json.dumps(command_source),
                        expected_trial_id=command_trial_id,
                        expected_fixture_id=command_fixture_id,
                        expected_fixture_sha256=expected_command_hash,
                    )
                    attempt.command_source_outcome = {
                        key: parsed_command_source.get(key)
                        for key in (
                            "completion_status", "cleanup_success",
                            "exact_restoration_verified", "output_route_during",
                            "focus_result", "timeout", "overlap_rejected",
                        )
                    }
                    self.checkpoint("post-command")

            attempt.invalid_reason = InvalidReason.EVIDENCE_BOUNDARY_LOST
            self._wait_for_target_events(
                since_sequence=boundary_sequence,
                event_type="DETECTOR_REARMED",
                timeout_ms=TARGET_WAIT_DEFAULT_TIMEOUT_MS,
            )
            final_code, final_data = self._call_target_provider(
                TARGET_METHOD_GET_SNAPSHOT,
                extras={TARGET_EXTRA_SINCE_SEQUENCE: boundary_sequence},
            )
            if final_code != TARGET_RESULT_OK:
                raise HarnessError(f"target final snapshot failed: {final_data}")
            snapshot = parse_journal_snapshot(final_data)
            snapshot["_boundary_sequence"] = boundary_sequence
            self.private_write(
                f"trials/{trial_id}/target",
                "final-snapshot.json",
                json.dumps(snapshot, indent=2),
            )
            attempt.artifact_refs.append(
                f"trials/{trial_id}/target/final-snapshot.json"
            )
            self._last_target_snapshot = snapshot
            final_generation, final_session, final_path, correlation_failures = correlate_event_path(
                snapshot["events"],
                matrix_slot.trial_type,
                boundary_generation,
            )
            if final_generation is not None:
                attempt.target_timing["generation_id"] = final_generation
            if final_session is not None:
                correlated_session = final_session
                attempt.target_timing["session_id"] = final_session
            correlated_events: list[dict[str, Any]] = []
            for value in final_path.values():
                if isinstance(value, list):
                    correlated_events.extend(value)
                else:
                    correlated_events.append(value)
            correlated_events.sort(key=lambda event: event["s"])
            attempt.target_timing["events"] = format_target_snapshot_events(
                {"events": correlated_events}
            )
            if correlation_failures:
                attempt.target_timing["correlation_failures"] = correlation_failures
            self.checkpoint("post-snapshot")

            attempt.invalid_reason = InvalidReason.DEVICE_ENVIRONMENT_ERROR
            attempt.environment_after = self._snapshot_target_state()
            attempt.source_environment_after = self._snapshot_source_state()
            environment_failures = self._environment_failures(
                attempt.environment_before,
                attempt.environment_after,
                dict((self.preflight_approval or {}).get("target_environment_state", {})),
            )
            environment_failures.extend(self._source_environment_failures(
                attempt.source_environment_before,
                attempt.source_environment_after,
                dict((self.preflight_approval or {}).get("source_environment_state", {})),
            ))
            attempt.environment_summary = {
                "stable": not environment_failures,
                "failures": list(environment_failures),
            }
            attempt.target_timing["clock_domain"] = "target_device_elapsed_realtime"
            cue_audibility_ok = bool(
                self.cue_audibility_evidence_verified
                and (self.preflight_approval or {}).get("cue_audibility_evidence_verified") is True
            )
            status, classification, invalid_reason, failures = classify_attempt(
                source_result=parsed_source,
                target_snapshot=snapshot,
                trial_type=matrix_slot.trial_type,
                boundary_generation=boundary_generation,
                session_id=correlated_session,
                cue_audibility_verified=cue_audibility_ok,
                expected_command_transcript_sha256=(
                    self.installed_command_transcript_hashes.get(command_fixture_id)
                    if command_fixture_id is not None
                    else None
                ),
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
        except KeyboardInterrupt:
            attempt.status = AttemptStatus.INVALID
            attempt.classification = None
            attempt.invalid_reason = InvalidReason.OPERATOR_CANCELLED
            attempt.failures.append("operator interrupted trial")
            attempt.operational_failure = "operator interrupted trial"
            attempt.invalid_details["operational_failure"] = "operator interrupted trial"
            raise
        except HarnessError as exc:
            attempt.status = AttemptStatus.INVALID
            if attempt.invalid_reason is None:
                attempt.invalid_reason = InvalidReason.UNKNOWN
            attempt.failures.append(str(exc))
            attempt.operational_failure = str(exc)
            attempt.invalid_details["operational_failure"] = str(exc)
        finally:
            attempt.host_duration_ms = monotonic_ms() - host_start
            attempt.host_timing["trial_end_monotonic_ms"] = host_start + attempt.host_duration_ms
            attempt.host_timing["trial_duration_ms"] = attempt.host_duration_ms
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
        if command_fixture_id not in self.installed_command_transcript_hashes:
            raise HarnessError(
                f"command fixture {command_fixture_id!r} lacks expected_transcript_sha256"
            )
        trial_index = len(self.attempts) + 1
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
        """Re-capture and compare every approved identity/environment input."""
        approval = self.preflight_approval
        if not approval:
            raise HarnessError("no preflight approval available")
        expected_hash = approval.get("manifest_sha256")
        actual_hash = self._preflight_manifest_hash(approval)
        if expected_hash != actual_hash:
            raise HarnessError("preflight manifest hash is invalid")
        if approval.get("source_helper_contract_version") != SOURCE_HELPER_CONTRACT_VERSION:
            raise HarnessError("source helper contract version differs from approval")
        if approval.get("target_journal_contract_version") != JOURNAL_CONTRACT_VERSION:
            raise HarnessError("target journal contract version differs from approval")
        if approval.get("matrix_id") != MATRIX_ID or approval.get("matrix_version") != MATRIX_VERSION:
            raise HarnessError("matrix contract differs from approval")
        if not self.source.reachable() or not self.target.reachable():
            raise HarnessError("source or target is unreachable since preflight approval")

        live_source = device_identity(self.source, self.source_alias, self.package)
        live_target = device_identity(self.target, self.target_alias, self.package)
        assert_distinct_device_identities(live_source, live_target)
        live_source_build = installed_build_identity(self.source, self.package)
        live_target_build = installed_build_identity(self.target, self.package)
        live_fixture_hashes = self._read_fixture_manifest()
        live_source_audio = self._snapshot_audio_state(self.source, self.source_alias)
        live_target_audio = self._snapshot_audio_state(self.target, self.target_alias)
        current_source = self._snapshot_source_state()
        current_target = self._snapshot_target_state()

        if approval.get("source_identity") != live_source.approval():
            raise HarnessError("source identity changed since preflight approval")
        if approval.get("target_identity") != live_target.approval():
            raise HarnessError("target identity changed since preflight approval")
        if approval.get("source_build") != live_source_build.public():
            raise HarnessError("source installed build changed since preflight approval")
        if approval.get("target_build") != live_target_build.public():
            raise HarnessError("target installed build changed since preflight approval")
        if approval.get("fixture_hashes") != live_fixture_hashes:
            raise HarnessError("installed source fixtures changed since preflight approval")
        if approval.get("command_transcript_hashes") != self.installed_command_transcript_hashes:
            raise HarnessError("installed command transcript expectations changed since preflight approval")
        if approval.get("source_audio_state") != live_source_audio:
            raise HarnessError("source audio state changed since preflight approval")
        if approval.get("target_audio_state") != live_target_audio:
            raise HarnessError("target audio state changed since preflight approval")
        approved_source_environment = dict(approval.get("source_environment_state", {}))
        approved_target_environment = dict(approval.get("target_environment_state", {}))
        if not approved_source_environment or not approved_target_environment:
            raise HarnessError("preflight approval is missing exact Android environment state")
        environment_failures = self._environment_failures(
            approved_target_environment,
            current_target,
            approved_target_environment,
        )
        environment_failures.extend(self._source_environment_failures(
            approved_source_environment,
            current_source,
            approved_source_environment,
        ))
        if environment_failures:
            raise HarnessError(
                "environment changed since preflight approval: " + "; ".join(environment_failures)
            )
        approved_boot_id = approval.get("target_boot_id")
        if not approved_boot_id or current_target.get("boot_id") != approved_boot_id:
            raise HarnessError("target boot ID changed since preflight approval")
        if current_target.get("screen_off") is not True:
            raise HarnessError("target screen is not off since preflight approval")
        if current_target.get("screen_off") != approval.get("target_screen_off"):
            raise HarnessError("target screen state changed since preflight approval")
        if current_target.get("charging") != approval.get("target_charging"):
            raise HarnessError("target charging state changed since preflight approval")
        if service_active(self.source, self.package):
            raise HarnessError("source wake service is active after preflight approval")
        if not current_target.get("service_active", False):
            raise HarnessError("target wake service is no longer active")
        if self._has_active_bluetooth_route(self.source) or self._has_active_bluetooth_route(self.target):
            raise HarnessError("external Bluetooth audio route detected after preflight approval")
        self.source_identity = live_source
        self.target_identity = live_target
        self.source_build = live_source_build
        self.target_build = live_target_build
        self.installed_fixture_hashes = live_fixture_hashes
        self.run_environment_before = {
            "source": current_source,
            "target": current_target,
        }
    # ── ADB helpers ──────────────────────────────────────────────────

    @staticmethod
    def _parse_media_volume(raw: str) -> tuple[int, int]:
        """Parse Android's authoritative media-volume command response."""
        match = re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]", raw)
        if match is None:
            raise HarnessError(f"unrecognised media volume response: {raw!r}")
        current, minimum, maximum = (int(value) for value in match.groups())
        if not minimum <= current <= maximum:
            raise HarnessError(f"media volume {current} is outside [{minimum}..{maximum}]")
        return current, maximum

    @staticmethod
    def _parse_sensor_privacy_dump(raw: str, user_id: int) -> dict[str, str]:
        """Parse effective microphone/camera privacy from SensorPrivacyService."""
        if not raw.startswith("SENSOR PRIVACY MANAGER STATE (dumpsys sensor_privacy)"):
            raise HarnessError("dumpsys sensor_privacy omitted sensor privacy manager state")

        current_user: int | None = None
        current_sensor: int | None = None
        states: dict[int, list[int]] = {1: [], 2: []}
        for raw_line in raw.splitlines():
            line = raw_line.strip()
            match = re.fullmatch(r"user_id=(\d+)", line)
            if match:
                current_user = int(match.group(1))
                current_sensor = None
                continue
            match = re.fullmatch(r"sensor=(\d+)", line)
            if match:
                current_sensor = int(match.group(1))
                continue
            match = re.fullmatch(r"state_type=(\d+)", line)
            if match and current_user == user_id and current_sensor in states:
                state_type = int(match.group(1))
                if state_type not in {1, 2}:
                    raise HarnessError(
                        f"unrecognised sensor privacy state type: {state_type}"
                    )
                states[current_sensor].append(state_type)

        return {
            "microphone": "enabled" if 1 in states[1] else "disabled",
            "camera": "enabled" if 1 in states[2] else "disabled",
        }

    @staticmethod
    def _parse_screen_off(raw: str) -> bool:
        """Parse screen state from modern PowerManagerService output."""
        match = re.search(r"(?m)^\s*mWakefulness=(\w+)\s*$", raw)
        if match is None:
            raise HarnessError("dumpsys power omitted mWakefulness")
        wakefulness = match.group(1)
        if wakefulness == "Awake":
            return False
        if wakefulness in {"Asleep", "Dozing"}:
            return True
        raise HarnessError(f"unrecognised Android wakefulness: {wakefulness!r}")

    def _snapshot_audio_state(self, client: AdbClient, alias: str) -> dict[str, Any]:
        """Capture exact Android audio values used by pre/post comparisons."""
        volume_raw = client.shell(
            "cmd", "media_session", "volume", "--get", "--stream", "3"
        ).strip()
        volume, volume_max = self._parse_media_volume(volume_raw)
        ringer = client.shell("settings", "get", "global", "mode_ringer").strip()
        dnd = client.shell("settings", "get", "global", "zen_mode").strip()
        if ringer not in {"0", "1", "2"}:
            raise HarnessError(f"unrecognised Android ringer mode: {ringer!r}")
        if not re.fullmatch(r"\d+", dnd):
            raise HarnessError(f"unrecognised Android zen mode: {dnd!r}")
        return {
            "alias": alias,
            "media_volume": volume,
            "media_volume_max": volume_max,
            "ringer_mode": int(ringer),
            "dnd_mode": int(dnd),
        }

    def _get_media_max_volume(self, client: AdbClient) -> int:
        return self._snapshot_audio_state(client, "volume-probe")["media_volume_max"]

    def _has_active_bluetooth_route(self, client: AdbClient) -> bool:
        """Read the current AudioRoutesInfo Bluetooth route, ignoring dumpsys history."""
        lines = client.shell("dumpsys", "audio").splitlines()
        try:
            routes_start = next(
                index for index, line in enumerate(lines)
                if line.strip() == "Audio routes:"
            )
        except StopIteration as exc:
            raise HarnessError("dumpsys audio omitted current Audio routes state") from exc

        bluetooth_name = None
        for line in lines[routes_start + 1:routes_start + 8]:
            match = re.fullmatch(r"\s*mBluetoothName=(.*)\s*", line)
            if match:
                bluetooth_name = match.group(1).strip()
                break
        if bluetooth_name is None:
            raise HarnessError("dumpsys audio omitted current Bluetooth route state")
        return bluetooth_name.lower() != "null"

    def _snapshot_android_state(
        self,
        client: AdbClient,
        alias: str,
        *,
        target: bool,
    ) -> dict[str, Any]:
        """Capture exact privacy, app, power, and audio state without hiding lookup failures."""
        if not client.reachable():
            return {"alias": alias, "reachable": False, "capture_errors": ["ADB unreachable"]}

        errors: list[str] = []

        def capture(label: str, command: tuple[str, ...]) -> str | None:
            try:
                value = client.shell(*command).strip()
            except HarnessError as exc:
                errors.append(f"{label}: {exc}")
                return None
            if not value:
                errors.append(f"{label}: empty response")
                return None
            return value

        current_user = capture("current user", ("am", "get-current-user"))
        if current_user is not None and not current_user.isdigit():
            errors.append(f"current user: unrecognised response {current_user!r}")
            current_user = None

        privacy: dict[str, str | None] = {"microphone": None, "camera": None}
        if current_user is not None:
            privacy_dump = capture(
                "sensor privacy",
                ("dumpsys", "sensor_privacy"),
            )
            if privacy_dump is not None:
                try:
                    privacy.update(
                        self._parse_sensor_privacy_dump(privacy_dump, int(current_user))
                    )
                except HarnessError as exc:
                    errors.append(f"sensor privacy: {exc}")

        package_uid_raw = capture(
            "package UID",
            ("cmd", "package", "list", "packages", "-U", self.package),
        )
        package_uid: str | None = None
        if package_uid_raw is not None:
            uid_match = re.search(r"\buid:(\d+)\b", package_uid_raw)
            if uid_match is None:
                errors.append(f"package UID: unrecognised response {package_uid_raw!r}")
            else:
                package_uid = uid_match.group(1)

        uid_state = (
            capture("package UID state", ("am", "get-uid-state", package_uid))
            if package_uid is not None
            else None
        )
        standby_bucket = capture(
            "package standby bucket",
            ("am", "get-standby-bucket", self.package),
        )
        package_pid = client.shell("pidof", self.package, check=False).strip() or None

        audio: dict[str, Any] = {}
        try:
            audio = self._snapshot_audio_state(client, alias)
        except HarnessError as exc:
            errors.append(f"audio state: {exc}")

        battery = capture("battery state", ("dumpsys", "battery")) if target else None
        power = capture("power state", ("dumpsys", "power")) if target else None
        uptime_text = capture("uptime", ("cat", "/proc/uptime")) if target else None
        boot_id = capture("boot ID", ("cat", "/proc/sys/kernel/random/boot_id")) if target else None
        uptime: float | None = None
        if uptime_text is not None:
            try:
                uptime = float(uptime_text.split()[0])
            except (IndexError, ValueError):
                errors.append(f"uptime: unrecognised response {uptime_text!r}")

        snapshot: dict[str, Any] = {
            "alias": alias,
            "reachable": True,
            "current_user": int(current_user) if current_user is not None else None,
            "sensor_privacy": privacy,
            "package_uid": int(package_uid) if package_uid is not None else None,
            "package_pid": package_pid,
            "package_uid_state": uid_state,
            "package_standby_bucket": standby_bucket,
            "service_active": None,
            "bluetooth_route_active": None,
            "capture_errors": errors,
            **audio,
        }
        try:
            snapshot["service_active"] = service_active(client, self.package)
        except HarnessError as exc:
            errors.append(f"wake service state: {exc}")
        try:
            snapshot["bluetooth_route_active"] = self._has_active_bluetooth_route(client)
        except HarnessError as exc:
            errors.append(f"Bluetooth route state: {exc}")
        if target:
            screen_off: bool | None = None
            if power is not None:
                try:
                    screen_off = self._parse_screen_off(power)
                except HarnessError as exc:
                    errors.append(f"power state: {exc}")
            snapshot.update({
                "uptime_seconds": uptime,
                "boot_id": boot_id,
                "screen_off": screen_off,
                "charging": battery is not None and (
                    "AC powered: true" in battery or "USB powered: true" in battery
                ),
            })
        return snapshot

    def _snapshot_target_state(self) -> dict[str, Any]:
        return self._snapshot_android_state(self.target, self.target_alias, target=True)

    def _snapshot_source_state(self) -> dict[str, Any]:
        return self._snapshot_android_state(self.source, self.source_alias, target=False)

    @staticmethod
    def _environment_failures(
        before: dict[str, Any],
        after: dict[str, Any],
        approved: dict[str, Any],
    ) -> list[str]:
        failures: list[str] = []
        failures.extend(f"target pre-capture failed: {item}" for item in before.get("capture_errors", []))
        failures.extend(f"target post-capture failed: {item}" for item in after.get("capture_errors", []))
        if before.get("screen_off") is not True:
            failures.append("target screen was not off before trial")
        if not before.get("reachable") or not after.get("reachable"):
            failures.append("target ADB reachability changed during trial")
        if before.get("boot_id") and after.get("boot_id") != before.get("boot_id"):
            failures.append("target boot ID changed during trial")
        try:
            if float(after.get("uptime_seconds", 0.0)) < float(before.get("uptime_seconds", 0.0)):
                failures.append("target uptime regressed during trial")
        except (TypeError, ValueError):
            failures.append("target uptime was not numeric")
        if after.get("screen_off") != before.get("screen_off"):
            failures.append("target screen state changed during trial")
        if after.get("charging") != before.get("charging"):
            failures.append("target charging state changed during trial")
        if not after.get("service_active", False):
            failures.append("target wake service is inactive after trial")
        for field, label in (
            ("current_user", "Android user"),
            ("sensor_privacy", "sensor privacy toggles"),
            ("package_uid", "package UID"),
            ("package_standby_bucket", "package standby bucket"),
            ("media_volume", "media volume"),
            ("media_volume_max", "media volume range"),
            ("ringer_mode", "ringer mode"),
            ("dnd_mode", "DND mode"),
            ("bluetooth_route_active", "Bluetooth route"),
        ):
            if before.get(field) != approved.get(field):
                failures.append(f"target {label} differed from approved preflight")
            if after.get(field) != before.get(field):
                failures.append(f"target {label} changed during trial")
        if before.get("bluetooth_route_active") is True:
            failures.append("target Bluetooth route was active before trial")
        return failures
    @staticmethod
    def _source_environment_failures(
        before: dict[str, Any],
        after: dict[str, Any],
        approved: dict[str, Any],
    ) -> list[str]:
        failures: list[str] = []
        failures.extend(f"source pre-capture failed: {item}" for item in before.get("capture_errors", []))
        failures.extend(f"source post-capture failed: {item}" for item in after.get("capture_errors", []))
        if not before.get("reachable") or not after.get("reachable"):
            failures.append("source ADB reachability changed during trial")
        if before.get("service_active") or after.get("service_active"):
            failures.append("source wake service was active during trial")
        for field, label in (
            ("current_user", "Android user"),
            ("sensor_privacy", "sensor privacy toggles"),
            ("package_uid", "package UID"),
            ("package_standby_bucket", "package standby bucket"),
            ("media_volume", "media volume"),
            ("media_volume_max", "media volume range"),
            ("ringer_mode", "ringer mode"),
            ("dnd_mode", "DND mode"),
        ):
            if before.get(field) != approved.get(field):
                failures.append(f"source {label} differed from approved preflight")
            if after.get(field) != before.get(field):
                failures.append(f"source {label} changed during trial")
        if before.get("bluetooth_route_active") or after.get("bluetooth_route_active"):
            failures.append("source Bluetooth route was active during trial")
        return failures

    def _read_fixture_manifest(self) -> dict[str, str]:
        """Read and strictly validate the source app-private fixture manifest."""
        fixture_dir = str(self.fixture_dir or "files/acoustic-fixtures")
        manifest_text = self.source.shell(
            "run-as", self.package,
            "cat", f"{fixture_dir.rstrip('/')}/manifest.json",
        )
        fixture_hashes, transcript_hashes = parse_fixture_manifest_contract(manifest_text)
        self.installed_command_transcript_hashes = transcript_hashes
        return fixture_hashes

    def _invoke_source(self, trial_id: str, fixture_id: str,
                       volume_index: int) -> dict[str, Any]:
        """Send play broadcast to source device and collect its persisted result."""
        if not self.source.reachable():
            raise HarnessError("source ADB not reachable")

        broadcast_args = [
            "shell", "am", "broadcast",
            "-n", f"{self.package}/{SOURCE_RECEIVER_CLS}",
            "-a", SOURCE_ACTION,
            "--es", "trial_id", trial_id,
            "--es", "fixture_id", fixture_id,
            "--ei", "volume_index", str(volume_index),
        ]
        self._active_source_trial_id = trial_id
        self._active_source_fixture_id = fixture_id
        broadcast_output = self.source.run(
            *broadcast_args,
            timeout=DEFAULT_SOURCE_TIMEOUT_S,
            check=False,
        )
        if broadcast_output.strip():
            try:
                result_code, result_data = parse_ordered_broadcast_result(broadcast_output)
            except HarnessError:
                # ADB may exit non-zero after the receiver has already persisted its
                # authoritative result. Continue to the app-private result recovery.
                pass
            else:
                if result_code not in (0, 1, 2):
                    raise HarnessError(
                        f"source broadcast returned unsupported result "
                        f"{result_code}: {result_data}"
                    )
        result = self._read_source_result(trial_id, fixture_id)
        self.source_results.append(result)
        self._active_source_trial_id = None
        self._active_source_fixture_id = None
        return result

    def _read_source_result(
        self,
        trial_id: str,
        fixture_id: str,
        *,
        require_completed: bool = True,
    ) -> dict[str, Any]:
        """Read, validate, and retain one private source result artifact."""
        try:
            result_file_text = self.source.shell(
                "run-as", self.package,
                "cat", f"files/acoustic-stimulus-results/{trial_id}.json",
            )
        except HarnessError as exc:
            raise HarnessError(f"failed to read source result: {exc}") from exc
        parser = parse_source_result if require_completed else parse_source_cleanup_result
        result = parser(
            result_file_text,
            expected_trial_id=trial_id,
            expected_fixture_id=fixture_id,
        )
        self.private_write(
            f"trials/{trial_id}/source",
            "result.json",
            json.dumps(result, indent=2),
        )
        return result

    def _cancel_active_source_playback(self) -> list[str]:
        """Request immediate helper cleanup for an interrupted source trial."""
        trial_id = self._active_source_trial_id
        if trial_id is None:
            return []
        try:
            output = self.source.run(
                "shell", "am", "broadcast",
                "-n", f"{self.package}/{SOURCE_RECEIVER_CLS}",
                "-a", SOURCE_CANCEL_ACTION,
                "--es", "trial_id", trial_id,
                timeout=5.0,
            )
            code, data = parse_ordered_broadcast_result(output)
        except HarnessError as exc:
            return [f"active source trial {trial_id} cancellation failed: {exc}"]
        if code == 0 or (code == 1 and data == "no_matching_active_trial"):
            return []
        return [f"active source trial {trial_id} cancellation failed: {data or code}"]

    def _recover_active_source_result(self) -> list[str]:
        """Boundedly recover helper cleanup evidence after host interruption."""
        trial_id = self._active_source_trial_id
        fixture_id = self._active_source_fixture_id
        if trial_id is None or fixture_id is None:
            return []
        deadline = time.monotonic() + SOURCE_RESULT_RECOVERY_TIMEOUT_S
        last_error = "result unavailable"
        while time.monotonic() < deadline:
            try:
                result = self._read_source_result(
                    trial_id,
                    fixture_id,
                    require_completed=False,
                )
            except HarnessError as exc:
                last_error = str(exc)
                time.sleep(0.25)
                continue
            self.source_results.append(result)
            self._active_source_trial_id = None
            self._active_source_fixture_id = None
            return []
        return [f"active source trial {trial_id} cleanup evidence unavailable: {last_error}"]

    def _invoke_command_source_with_armed_wait(
        self,
        trial_id: str,
        fixture_id: str,
        volume_index: int,
        since_sequence: int,
        timeout_ms: int,
    ) -> tuple[dict[str, Any], list[dict[str, Any]], dict[str, Any]]:
        """Arm only the post-wake command-result wait before command playback."""
        source_result: dict[str, Any] | None = None

        def invoke_source() -> None:
            nonlocal source_result
            source_result = self._invoke_source(trial_id, fixture_id, volume_index)

        events, envelope = self._wait_for_target_events(
            since_sequence=since_sequence,
            event_type="COMMAND_ROUTING_RESULT",
            timeout_ms=timeout_ms,
            on_armed=invoke_source,
        )
        if source_result is None:
            raise HarnessError("target journal wait completed before source playback was armed")
        return source_result, events, envelope

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
            self._active_wait_request_id = None
        if request_id is None:
            return
        code, data = self._call_target_provider(
            TARGET_METHOD_CANCEL_WAIT,
            extras={TARGET_EXTRA_REQUEST_ID: request_id},
            timeout=5.0,
        )
        completed_before_cancel = (
            code == TARGET_RESULT_ERROR
            and data == TARGET_ERROR_UNKNOWN_REQUEST_ID
        )
        if code not in (TARGET_RESULT_OK, TARGET_RESULT_CANCELLED) and not completed_before_cancel:
            raise HarnessError(f"target wait cancellation failed: {data}")

    def _wait_for_target_events(
        self,
        since_sequence: int,
        event_type: str,
        timeout_ms: int,
        wait_started: threading.Event | None = None,
        on_armed: Callable[[], None] | None = None,
    ) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        """Arm one provider wait, run source work, then require its exact snapshot."""
        if not TARGET_WAIT_MIN_TIMEOUT_MS <= timeout_ms <= TARGET_WAIT_MAX_TIMEOUT_MS:
            raise HarnessError(
                f"target wait timeout must be {TARGET_WAIT_MIN_TIMEOUT_MS}.."
                f"{TARGET_WAIT_MAX_TIMEOUT_MS} ms"
            )
        request_id = f"wait-{uuid.uuid4().hex}"
        wait_result: dict[str, Any] = {}
        wait_complete = threading.Event()

        def run_wait() -> None:
            try:
                wait_result["value"] = self._call_target_provider(
                    TARGET_METHOD_WAIT_FOR_EVENT,
                    extras={
                        TARGET_EXTRA_REQUEST_ID: request_id,
                        TARGET_EXTRA_SINCE_SEQUENCE: since_sequence,
                        TARGET_EXTRA_EVENT_TYPE: event_type,
                        TARGET_EXTRA_TIMEOUT_MS: timeout_ms,
                    },
                    timeout=timeout_ms / 1000.0 + 5.0,
                )
            except BaseException as exc:  # Propagated on the runner thread below.
                wait_result["error"] = exc
            finally:
                wait_complete.set()

        with self._wait_lock:
            self._active_wait_request_id = request_id
        wait_thread = threading.Thread(
            target=run_wait,
            name=f"target-journal-{request_id[-8:]}",
            daemon=True,
        )
        wait_thread.start()
        try:
            arm_deadline = time.monotonic() + 5.0
            while not wait_complete.is_set():
                status_code, status_data = self._call_target_provider(
                    TARGET_METHOD_GET_WAIT_STATUS,
                    extras={TARGET_EXTRA_REQUEST_ID: request_id},
                    timeout=5.0,
                )
                if status_code == TARGET_RESULT_OK and status_data == f"active:{request_id}":
                    if wait_started is not None:
                        wait_started.set()
                    if on_armed is not None:
                        on_armed()
                    break
                if not (
                    status_code == TARGET_RESULT_ERROR
                    and status_data == TARGET_ERROR_UNKNOWN_REQUEST_ID
                ):
                    self._cancel_active_wait()
                    raise HarnessError(f"target wait status failed: {status_data}")
                if time.monotonic() >= arm_deadline:
                    self._cancel_active_wait()
                    raise HarnessError("target journal wait did not arm within 5 seconds")
                time.sleep(0.05)

            if not wait_complete.wait(timeout_ms / 1000.0 + 5.0):
                self._cancel_active_wait()
                raise HarnessError("target journal wait worker did not complete")
            error = wait_result.get("error")
            if error is not None:
                raise error
            code, data = wait_result["value"]
            event = parse_journal_wait_result(code, data)
            if self._cancel.is_set():
                raise HarnessError("cancelled while waiting for target journal event")
        except BaseException:
            if not wait_complete.is_set():
                self._cancel_active_wait()
                wait_complete.wait(2.0)
            raise
        finally:
            with self._wait_lock:
                if self._active_wait_request_id == request_id:
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
                parse_source_cleanup_result(json.dumps(result))
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
        failures.extend(self._cancel_active_source_playback())
        failures.extend(self._recover_active_source_result())
        failures.extend(self._verify_source_restoration())
        if self.run_environment_before is not None:
            self.run_environment_after = {
                "source": self._snapshot_source_state(),
                "target": self._snapshot_target_state(),
            }
            failures.extend(self._environment_failures(
                self.run_environment_before["target"],
                self.run_environment_after["target"],
                self.run_environment_before["target"],
            ))
            failures.extend(self._source_environment_failures(
                self.run_environment_before["source"],
                self.run_environment_after["source"],
                self.run_environment_before["source"],
            ))
        self.cleanup_failures = failures
        self.cleanup_verified = not failures

    def is_matrix_complete(self) -> bool:
        """Return true only when each required position has one valid outcome."""
        required = {
            slot.position_id for slot in matrix_slots_for_target(self.target_alias)
        }
        valid_attempts = [
            attempt
            for attempt in self.attempts
            if attempt.status in {AttemptStatus.PASSED, AttemptStatus.FAILED}
        ]
        return (
            all(
                sum(
                    attempt.required_position_id == position
                    for attempt in valid_attempts
                ) == 1
                for position in required
            )
            and all(
                attempt.required_position_id in required
                for attempt in valid_attempts
            )
        )

    def all_required_passed(self) -> bool:
        """Return true when the complete valid matrix contains only passes."""
        return self.is_matrix_complete() and all(
            attempt.status != AttemptStatus.FAILED
            for attempt in self.attempts
            if attempt.status in {AttemptStatus.PASSED, AttemptStatus.FAILED}
        )

    def _release_provenance_verified(self) -> bool:
        _, commit = git_metadata()
        source = self.source_identity
        target = self.target_identity
        expected = EXPECTED_DEVICES["s21"]
        return (
            commit is not None
            and re.fullmatch(r"[0-9a-f]{40}", commit) is not None
            and self.target_alias == "s21"
            and source is not None
            and source.package_version is not None
            and source.package_version_code is not None
            and target is not None
            and target.manufacturer.lower() == expected["manufacturer"]
            and target.model == expected["model"]
            and target.package_version is not None
            and target.package_version_code is not None
        )

    def release_gate_success(self) -> bool:
        """Return true only for the S21 all-passed regression launch gate."""
        preflight = self.preflight_approval or {}
        return (
            self.gate_mode == GateMode.RELEASE
            and self.run_kind == RunKind.REGRESSION
            and self._release_provenance_verified()
            and self.all_required_passed()
            and self.cleanup_verified
            and preflight.get("operator_approved") is True
            and preflight.get("cue_audibility_evidence_verified") is True
            and self.cue_audibility_evidence_verified
            and self.cue_policy_evidence_verified
            and self.manifest is not None
            and self.manifest.cue_policy_version is not None
            and isinstance(self.preflight_manifest_hash, str)
            and re.fullmatch(r"[0-9a-f]{64}", self.preflight_manifest_hash) is not None
            and isinstance(preflight.get("fixture_set_id"), str)
            and bool(preflight["fixture_set_id"])
            and isinstance(preflight.get("fixture_hashes"), dict)
            and bool(preflight["fixture_hashes"])
            and all(
                isinstance(value, str)
                and re.fullmatch(r"[0-9a-f]{64}", value) is not None
                for value in preflight["fixture_hashes"].values()
            )
            and not self.primary_failure
            and self.abort_reason is None
        )

    def _completion_counts(self) -> dict[str, int]:
        """Derive authoritative run-level completion counts from frozen matrix and attempts."""
        required = {
            slot.position_id for slot in matrix_slots_for_target(self.target_alias)
        }
        valid_attempts = [
            a for a in self.attempts
            if a.status in {AttemptStatus.PASSED, AttemptStatus.FAILED}
            and a.required_position_id in required
        ]
        invalid_attempts = [
            a for a in self.attempts
            if a.status == AttemptStatus.INVALID
            and a.required_position_id in required
        ]
        positions: dict[str, list[AttemptStatus]] = defaultdict(list)
        for attempt in valid_attempts:
            positions[attempt.required_position_id].append(attempt.status)

        total_required = len(required)
        completed = sum(1 for outcomes in positions.values() if len(outcomes) == 1)
        duplicate_count = sum(max(0, len(outcomes) - 1) for outcomes in positions.values())
        passed_count = sum(
            1 for outcomes in positions.values()
            if len(outcomes) == 1 and outcomes[0] == AttemptStatus.PASSED
        )
        failed_count = sum(
            1 for outcomes in positions.values()
            if len(outcomes) == 1 and outcomes[0] == AttemptStatus.FAILED
        )

        return {
            "total_required": total_required,
            "completed": completed,
            "missing": total_required - completed,
            "passed": passed_count,
            "failed": failed_count,
            "invalid": len(invalid_attempts),
            "duplicate_valid_positions": duplicate_count,
        }


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
            cue_policy_evidence_verified=self.cue_policy_evidence_verified,
            cue_audibility_evidence_verified=self.cue_audibility_evidence_verified,
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
        complete_valid_matrix = self.is_matrix_complete()
        all_required_passed = self.all_required_passed()
        release_provenance_verified = self._release_provenance_verified()
        release_gate_success = self.release_gate_success()
        complete = self.abort_reason is None and self.primary_failure is None
        abort_reason = (
            sanitise_text(self.abort_reason, self.secrets)
            if self.abort_reason else None
        )
        primary_failure = (
            sanitise_text(self.primary_failure, self.secrets)
            if self.primary_failure else None
        )
        completion_counts = self._completion_counts()
        reliability = evidence["wake_reliability"]
        reliability.update(
            {
                "release_gate_success": release_gate_success,
                "complete": complete,
                "complete_valid_matrix": complete_valid_matrix,
                "all_required_passed": all_required_passed,
                "release_provenance_verified": release_provenance_verified,
                "feasibility_only": self.is_feasibility,
                "preflight_manifest_sha256": self.preflight_manifest_hash,
                "cue_policy_evidence_verified": self.cue_policy_evidence_verified,
                "cue_audibility_evidence_verified": self.cue_audibility_evidence_verified,
                "run_environment_before": public_run_environment(
                    self.run_environment_before
                ),
                "run_environment_after": public_run_environment(
                    self.run_environment_after
                ),
                "cleanup_failures": [
                    sanitise_text(failure, self.secrets)
                    for failure in self.cleanup_failures
                ],
                "abort_reason": abort_reason,
                "completion": {
                    "status": "completed" if complete else "aborted",
                    "primary_failure": primary_failure,
                    "cleanup_verified": self.cleanup_verified,
                    **completion_counts,
                },
            }
        )
        assert_commit_safe(evidence, self.secrets)
        self.sanitized_dir.mkdir(parents=True, exist_ok=True)
        write_sanitized_summary(
            self.sanitized_dir,
            evidence,
            self.secrets,
            private_run_dir=self.run_dir,
        )
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
        """Request cancellation and cancel active target/source operations."""
        self._cancel.set()
        if self.abort_reason is None:
            self.abort_reason = "operator_interrupt"
        try:
            self._cancel_active_wait()
        except HarnessError as exc:
            self.cleanup_failures.append(str(exc))
        self.cleanup_failures.extend(self._cancel_active_source_playback())

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
        boundary_generation=None,
        session_id=None,
        cue_audibility_verified=True,
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
    approved_cue_policy = runner.preflight_approval.get("cue_policy_version")
    requested_cue_policy = getattr(args, "cue_policy_version", None)
    if requested_cue_policy is not None and approved_cue_policy != requested_cue_policy:
        raise HarnessError(
            "requested cue policy version does not match monitored preflight approval"
        )

def finalize_evidence(runner: AcousticWakeReliabilityRunner) -> dict[str, Any]:
    """Capture final cleanup state before serialising public evidence."""
    runner.cleanup()
    return runner.export_evidence()


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
        load_later_run_preflight(runner, args)
        print("\nApproved preflight loaded. Running one bounded smoke trial...\n")

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
        evidence = finalize_evidence(runner)

    print(f"\nSmoke complete. Evidence: {runner.sanitized_dir}")
    return 0 if (
        runner.attempts
        and runner.attempts[-1].status in {AttemptStatus.PASSED, AttemptStatus.FAILED}
        and runner.cleanup_verified
    ) else 1


def preflight_mode(args: argparse.Namespace) -> int:
    """Human-monitored preflight mode with fail-closed helper cleanup."""
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
    completed = False
    try:
        runner.run_preflight(
            fixture_set_id=args.fixture_set_id or "v1-preflight",
            fixture_hashes=args.fixture_hashes or {},
            cue_policy_version=args.cue_policy_version,
        )
        completed = True
    except HarnessError as exc:
        runner.primary_failure = str(exc)
        print(f"Preflight error: {exc}")
    except KeyboardInterrupt:
        runner.primary_failure = "preflight interrupted by operator"
        print("\nInterrupted")
        runner.cancel()
    finally:
        finalize_evidence(runner)

    if completed and runner.cleanup_verified:
        print(f"\nPreflight manifest: {runner.run_dir / 'preflight-private.json'}")
        return 0
    return 1


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
        evidence = finalize_evidence(runner)

    print(f"\nDiagnostic complete. Evidence: {runner.sanitized_dir}")
    return 0 if (
        not runner.primary_failure
        and not runner.abort_reason
        and runner.is_matrix_complete()
        and runner.cleanup_verified
    ) else 1


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
            fixture_set_id=runner.preflight_approval["fixture_set_id"],
            fixture_hashes=dict(runner.preflight_approval["fixture_hashes"]),
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
        evidence = finalize_evidence(runner)

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
        evidence = finalize_evidence(runner)

    print(f"\nFeasibility complete. Evidence: {runner.sanitized_dir}")
    return 0 if (
        not runner.primary_failure
        and not runner.abort_reason
        and runner.is_matrix_complete()
        and runner.cleanup_verified
    ) else 1


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
        evidence = finalize_evidence(runner)

    if runner.primary_failure:
        print(f"Primary failure: {runner.primary_failure}")
    print(f"Evidence: {runner.sanitized_dir}")
    if runner.gate_mode == GateMode.RELEASE:
        return 0 if runner.release_gate_success() else 1
    return 0 if runner.is_matrix_complete() and not runner.primary_failure else 1


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
                        default=os.environ.get("ACOUSTIC_FIXTURE_DIR", "files/acoustic-fixtures"),
                        help="Source run-as-relative app-private fixture directory")
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
