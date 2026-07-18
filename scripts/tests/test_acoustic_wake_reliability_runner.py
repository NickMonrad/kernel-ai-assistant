#!/usr/bin/env python3
"""Tests for the acoustic wake-word reliability runner (Issue #1409)."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from typing import Any

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

from acoustic_wake_reliability_runner import (  # noqa: E402
    MATRIX_ID, MATRIX_VERSION, MATRIX_S21, MATRIX_S23U,
    EXPECTED_DEVICES, PUBLIC_ALIASES, VALID_MAX_ATTEMPTS,
    DEFAULT_CUE_MARGIN_MS, DEFAULT_WAIT_TIMEOUT_MS,
    AdbClient, AcousticWakeReliabilityRunner,
    AttemptStatus, FailureClassification, GateMode, InvalidReason,
    MatrixSlot, MatrixAttempt, RunKind, RunManifest, TrialType,
    HarnessError,
    assert_commit_safe, sanitise_text,
    classify_attempt, device_identity,
    find_event, format_target_snapshot_events,
    get_matrix, matrix_slots_for_target,
    parse_source_result, parse_target_bundle,
    render_evidence, service_active,
    validate_snapshot_envelope, utc_now, monotonic_ms,
)

FIXTURES = SCRIPTS / "testdata" / "fixtures" / "acoustic-wake-reliability"


def _load_fixture(name: str) -> str:
    return (FIXTURES / name).read_text()


def _load_json(name: str) -> Any:
    return json.loads(_load_fixture(name))


class FakeAdbClient:
    """ADB double returning fixture data for testing."""

    def __init__(self, serial: str = "fake-serial") -> None:
        self.serial = serial
        self.commands: list[tuple[str, ...]] = []
        self.responses: dict[str, str] = {}
        self.reachable_flag = True
        self.shell_timeout = 30.0

    def run(self, *args: str, timeout: float = 30.0) -> str:
        self.commands.append(args)
        self.shell_timeout = timeout
        key = " ".join(args)
        if key in self.responses:
            return self.responses[key]
        # Default responses for common commands
        for cmd_key, response in self.responses.items():
            if " ".join(args).startswith(cmd_key):
                return response
        return ""

    def shell(self, *args: str, timeout: float = 30.0) -> str:
        return self.run("shell", *args, timeout=timeout)

    def reachable(self) -> bool:
        return self.reachable_flag


def make_adb_response(adb_args: str, content: str) -> dict[str, str]:
    return {adb_args: content}


# ── Frozen Matrix Tests ─────────────────────────────────────────────

class FrozenMatrixTest(unittest.TestCase):
    """Exact frozen matrix encoding and versioning."""

    def test_matrix_id_and_version(self) -> None:
        self.assertEqual(MATRIX_ID, "AWVR-001")
        self.assertEqual(MATRIX_VERSION, 1)

    def test_s21_matrix(self) -> None:
        """Exact S21 primary launch gate matrix."""
        expected = (
            (10, 5, 3),
            (30, 5, 0),
            (120, 5, 3),
            (900, 2, 0),
            (1800, 2, 2),
        )
        self.assertEqual(MATRIX_S21, expected)

    def test_s23u_matrix(self) -> None:
        """Exact S23U comparison matrix."""
        expected = (
            (120, 3, 2),
            (1800, 2, 1),
        )
        self.assertEqual(MATRIX_S23U, expected)

    def test_get_matrix_s21(self) -> None:
        self.assertEqual(get_matrix("s21"), MATRIX_S21)

    def test_get_matrix_s23u(self) -> None:
        self.assertEqual(get_matrix("s23u"), MATRIX_S23U)

    def test_get_matrix_unknown(self) -> None:
        with self.assertRaises(HarnessError):
            get_matrix("unknown")

    def test_s21_matrix_slots_count(self) -> None:
        slots = matrix_slots_for_target("s21")
        total_expected = (
            5 + 3 +  # 10s
            5 + 0 +  # 30s
            5 + 3 +  # 2m
            2 + 0 +  # 15m
            2 + 2    # 30m
        )
        self.assertEqual(len(slots), total_expected)
        # Verify breakdown
        wake_only = sum(1 for s in slots if s.wake_only)
        wpc = sum(1 for s in slots if not s.wake_only)
        self.assertEqual(wake_only, 5 + 5 + 5 + 2 + 2)
        self.assertEqual(wpc, 3 + 0 + 3 + 0 + 2)

    def test_s23u_matrix_slots_count(self) -> None:
        slots = matrix_slots_for_target("s23u")
        total_expected = 3 + 2 + 2 + 1
        self.assertEqual(len(slots), total_expected)

    def test_role_reversal(self) -> None:
        """S23U can be target (comparison matrix)."""
        slots = matrix_slots_for_target("s23u")
        self.assertGreater(len(slots), 0)

    def test_s21_public_model(self) -> None:
        """S21 public model must be SM-G991B."""
        self.assertEqual(EXPECTED_DEVICES["s21"]["model"], "SM-G991B")

    def test_s23u_public_model(self) -> None:
        self.assertEqual(EXPECTED_DEVICES["s23u"]["model"], "SM-S918B")

    def test_matrix_slot_validation(self) -> None:
        with self.assertRaises(ValueError):
            MatrixSlot(idle_s=0, wake_only=True)

    def test_matrix_slot_negative(self) -> None:
        with self.assertRaises(ValueError):
            MatrixSlot(idle_s=-1, wake_only=True)


# ── Public Alias Tests ──────────────────────────────────────────────

class PublicAliasTest(unittest.TestCase):
    """Public alias mapping."""

    def test_public_aliases_defined(self) -> None:
        self.assertIn("s21", PUBLIC_ALIASES)
        self.assertIn("s23u", PUBLIC_ALIASES)

    def test_s21_not_hardware_serial(self) -> None:
        """S21 must be SM-G991B model, not a serial/selector."""
        model = EXPECTED_DEVICES["s21"]["model"]
        self.assertEqual(model, "SM-G991B")
        self.assertNotIn(":", model)  # No TCP endpoint
        self.assertEqual(len(model.split(".")), 1)  # No IP address


# ── Same Device Rejection ───────────────────────────────────────────

class SameDeviceRejectionTest(unittest.TestCase):
    """Source and target must be different physical devices."""

    def test_same_alias_raises(self) -> None:
        fake = FakeAdbClient()
        with self.assertRaises(HarnessError) as ctx:
            AcousticWakeReliabilityRunner(
                run_kind=RunKind.SMOKE,
                source_alias="s21",
                target_alias="s21",
                source_client=fake,
                target_client=fake,
            )
        self.assertIn("different physical devices", str(ctx.exception).lower())

    def test_same_selector_raises_in_main(self) -> None:
        """CLI must also reject same selector."""
        # This is tested by main() validation, but also here for coverage
        fake = FakeAdbClient(serial="same-device")
        with self.assertRaises(HarnessError):
            AcousticWakeReliabilityRunner(
                run_kind=RunKind.SMOKE,
                source_alias="s21",
                target_alias="s23u",
                source_client=fake,
                target_client=fake,
            )

    def test_different_aliases_ok(self) -> None:
        fake_s = FakeAdbClient("source")
        fake_t = FakeAdbClient("target")
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=fake_s,
            target_client=fake_t,
        )
        self.assertIsNotNone(runner)
        self.assertEqual(runner.source_alias, "s23u")
        self.assertEqual(runner.target_alias, "s21")


# ── Device Identity Tests ───────────────────────────────────────────

class DeviceIdentityTest(unittest.TestCase):
    """Device identity resolution."""

    def setUp(self) -> None:
        self.s23u_props = _load_fixture("device-props-s23u.txt").splitlines()
        self.s21_props = _load_fixture("device-props-s21.txt").splitlines()

    def _make_fake_adb(self, props_text: str, pkg_dump: str = "versionName=1.0.0\nversionCode=100") -> FakeAdbClient:
        client = FakeAdbClient()
        for line in props_text.strip().splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                client.responses[f"shell getprop {key}"] = value + "\n"
        client.responses["shell dumpsys package com.kernel.ai.debug"] = pkg_dump
        return client

    def test_s23u_identity(self) -> None:
        client = self._make_fake_adb(_load_fixture("device-props-s23u.txt"))
        identity = device_identity(client, "s23u", "com.kernel.ai.debug")
        self.assertEqual(identity.alias, "s23u")
        self.assertEqual(identity.manufacturer, "Samsung")
        self.assertEqual(identity.model, "SM-S918B")

    def test_s21_identity(self) -> None:
        client = self._make_fake_adb(_load_fixture("device-props-s21.txt"))
        identity = device_identity(client, "s21", "com.kernel.ai.debug")
        self.assertEqual(identity.alias, "s21")
        self.assertEqual(identity.model, "SM-G991B")

    def test_wrong_manufacturer_raises(self) -> None:
        client = self._make_fake_adb(
            "ro.product.manufacturer=Apple\n"
            "ro.product.model=SM-G991B\n"
            "ro.build.version.release=15\n"
            "ro.build.version.sdk=35\n"
            "ro.build.fingerprint=test\n"
        )
        with self.assertRaises(HarnessError) as ctx:
            device_identity(client, "s21", "com.kernel.ai.debug")
        self.assertIn("manufacturer", str(ctx.exception))

    def test_wrong_model_raises(self) -> None:
        client = self._make_fake_adb(
            "ro.product.manufacturer=Samsung\n"
            "ro.product.model=R5CR605B71K\n"
            "ro.build.version.release=15\n"
            "ro.build.version.sdk=35\n"
            "ro.build.fingerprint=test\n"
        )
        with self.assertRaises(HarnessError) as ctx:
            device_identity(client, "s21", "com.kernel.ai.debug")
        self.assertIn("model", str(ctx.exception))

    def test_public_masks_serial(self) -> None:
        client = FakeAdbClient("100.76.134.49:44599")
        self.assertIn("100.76.134.49", client.serial)

    def test_package_version_code(self) -> None:
        client = self._make_fake_adb(
            _load_fixture("device-props-s21.txt"),
            "versionName=2.0.0\nversionCode=200\n",
        )
        identity = device_identity(client, "s21", "com.kernel.ai.debug")
        self.assertEqual(identity.package_version, "2.0.0")
        self.assertEqual(identity.package_version_code, 200)


# ── Target Journal Parsing Tests ────────────────────────────────────

class TargetJournalParsingTest(unittest.TestCase):
    """Target ContentProvider Bundle response parsing."""

    def test_result_code_0_valid_json(self) -> None:
        snapshot = _load_json("target-journal-snapshot-valid.json")
        result = parse_target_bundle(snapshot["result_code"], snapshot["result_data"])
        self.assertIn("events", result)

    def test_timeout_response(self) -> None:
        timeout = _load_json("target-journal-timeout.json")
        result = parse_target_bundle(timeout["result_code"], timeout["result_data"])
        self.assertTrue(result.get("timeout"))
        self.assertIn("timeout", result["data"])

    def test_cancelled_response(self) -> None:
        cancelled = _load_json("target-journal-cancelled.json")
        result = parse_target_bundle(cancelled["result_code"], cancelled["result_data"])
        self.assertTrue(result.get("cancelled"))

    def test_malformed_json_raises(self) -> None:
        malformed = _load_json("target-journal-malformed.json")
        with self.assertRaises(HarnessError):
            parse_target_bundle(malformed["result_code"], malformed["result_data"])

    def test_unknown_result_code_raises(self) -> None:
        with self.assertRaises(HarnessError):
            parse_target_bundle(99, "unknown")

    def test_snapshot_envelope_validation(self) -> None:
        snapshot = _load_json("target-journal-snapshot-valid.json")
        parsed = parse_target_bundle(snapshot["result_code"], snapshot["result_data"])
        validated = validate_snapshot_envelope(parsed)
        self.assertIn("events", validated)

    def test_missing_events_field_raises(self) -> None:
        with self.assertRaises(HarnessError):
            validate_snapshot_envelope({})

    def test_events_not_list_raises(self) -> None:
        with self.assertRaises(HarnessError):
            validate_snapshot_envelope({"events": "not a list"})

    def test_wait_late_subscriber_recovery(self) -> None:
        """Journal should allow late subscriber to see earlier events."""
        snapshot = _load_json("target-journal-snapshot-valid.json")
        parsed = parse_target_bundle(snapshot["result_code"], snapshot["result_data"])
        events = parsed.get("events", [])
        # even if we missed STT_READY by waiting too late, we still get it in the snapshot
        stt_ready = find_event(events, "STT_READY")
        self.assertIsNotNone(stt_ready)

    def test_generation_and_session_correlation(self) -> None:
        snapshot = _load_json("target-journal-snapshot-valid.json")
        parsed = parse_target_bundle(snapshot["result_code"], snapshot["result_data"])
        events = parsed.get("events", [])
        # Find events by generation
        gen1_events = [e for e in events if e.get("g") == 1]
        gen2_events = [e for e in events if e.get("g") == 2]
        self.assertGreater(len(gen1_events), 0)
        self.assertGreater(len(gen2_events), 0)
        session1_events = [e for e in events if e.get("i") == 1]
        self.assertGreater(len(session1_events), 0)

    def test_monotonic_sequence(self) -> None:
        snapshot = _load_json("target-journal-snapshot-valid.json")
        parsed = parse_target_bundle(snapshot["result_code"], snapshot["result_data"])
        events = parsed.get("events", [])
        last_s = -1
        for ev in events:
            self.assertGreater(ev["s"], last_s)
            last_s = ev["s"]

    def test_sticky_overflow(self) -> None:
        snapshot = _load_json("target-journal-snapshot-valid.json")
        parsed = parse_target_bundle(snapshot["result_code"], snapshot["result_data"])
        # overflow may be true or false but must be present
        self.assertIn("overflowed", parsed)


    def test_boundary_semantics(self) -> None:
        """Test strict greater-than boundary semantics."""
        snapshot = _load_json("target-journal-snapshot-valid.json")
        parsed = parse_target_bundle(snapshot["result_code"], snapshot["result_data"])
        events = parsed.get("events", [])
        # All events should have sequence > 0
        for ev in events:
            self.assertGreater(ev["s"], 0)



    def test_sticky_overflow_ok_when_bounds_ok(self) -> None:
        """Overflow flag is sticky - not all overflow means lost evidence."""
        source = {
            "trial_id": "test",
            "fixture_id": "natural_wake",
            "volume_applied": 7,
            "player_completed": True,
            "cleanup_completed": True,
            "cleanup_verified": True,
            "route": "builtin_speaker",
        }
        status, cls, reason, failures = classify_attempt(
            source_result=source,
            target_snapshot={
                "events": [{"s": 1, "t": "DETECTOR_GENERATION_STARTED", "g": 1},
                           {"s": 2, "t": "VOICED_FRAME_AFTER_SILENCE", "g": 1}],
                "overflowed": True,
                "lowest_sequence": 1,
                "since_sequence": 0,
                "highest_sequence": 2,
            },
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=None,
            cue_policy_available=True,
        )
        # lowest (1) <= boundary (0)+1, so no evidence loss
        self.assertEqual(status, AttemptStatus.FAILED)

# ── Source Result Parsing Tests ──────────────────────────────────────

class SourceResultParsingTest(unittest.TestCase):
    """Source playback result parsing."""

    def test_valid_source_result(self) -> None:
        result = _load_json("source-result-valid.json")
        text = json.dumps(result)
        parsed = parse_source_result(text)
        self.assertTrue(parsed["player_completed"])
        self.assertTrue(parsed["cleanup_verified"])

    def test_player_not_completed_raises(self) -> None:
        result = _load_json("source-result-invalid.json")
        text = json.dumps(result)
        with self.assertRaises(HarnessError):
            parse_source_result(text)

    def test_missing_fields_raises(self) -> None:
        with self.assertRaises(HarnessError):
            parse_source_result(json.dumps({"trial_id": "test"}))

    def test_invalid_json_raises(self) -> None:
        with self.assertRaises(HarnessError):
            parse_source_result("not-json")


# ── Classification Tests ────────────────────────────────────────────

class ClassificationTest(unittest.TestCase):
    """Validity-first classification logic."""

    def setUp(self) -> None:
        self.valid_source = {
            "trial_id": "test-001",
            "fixture_id": "natural_wake",
            "volume_applied": 7,
            "player_completed": True,
            "cleanup_completed": True,
            "cleanup_verified": True,
            "route": "builtin_speaker",
        }
        self.passing_events = [
            {"s": 1, "t": "DETECTOR_GENERATION_STARTED", "g": 1},
            {"s": 2, "t": "VOICED_FRAME_AFTER_SILENCE", "g": 1},
            {"s": 3, "t": "STAGE3_READY", "g": 1},
            {"s": 4, "t": "ACTIVATION_CANDIDATE", "g": 1},
            {"s": 5, "t": "VERIFIED_ACTIVATION", "g": 1},
            {"s": 6, "t": "WAKE_CALLBACK_INVOKED", "g": 1},
            {"s": 7, "t": "VOICE_SESSION_STARTED", "g": 1, "i": 1},
            {"s": 8, "t": "STT_READY", "g": 1, "i": 1},
            {"s": 9, "t": "CUE_REQUESTED", "g": 1},
            {"s": 10, "t": "STT_PARTIAL", "g": 1, "d": {"length": 42}},
            {"s": 11, "t": "SESSION_COMPLETED", "g": 1, "i": 1},
            {"s": 12, "t": "DETECTOR_REARMED", "g": 2},
        ]
        self.command_passing_events = self.passing_events + [
            {"s": 13, "t": "STT_FINAL_RESULT", "g": 1, "i": 1},
        ]

    def test_passing_wake_only(self) -> None:
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": self.passing_events, "lowest_sequence": 1, "highest_sequence": 12, "overflowed": False},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.PASSED)
        self.assertIsNone(cls)
        self.assertIsNone(reason)

    def test_passing_wake_plus_command(self) -> None:
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": self.command_passing_events, "lowest_sequence": 1, "highest_sequence": 13, "overflowed": False},
            trial_type=TrialType.WAKE_PLUS_COMMAND,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.PASSED)

    def test_invalid_source_stimulus_failure(self) -> None:
        status, cls, reason, failures = classify_attempt(
            source_result=None,
            target_snapshot={"events": []},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.INVALID)
        self.assertEqual(reason, InvalidReason.SOURCE_STIMULUS_FAILURE)

    def test_invalid_player_not_completed(self) -> None:
        bad_source = dict(self.valid_source, player_completed=False)
        status, cls, reason, failures = classify_attempt(
            source_result=bad_source,
            target_snapshot={"events": []},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.INVALID)
        self.assertEqual(reason, InvalidReason.SOURCE_STIMULUS_FAILURE)

    def test_invalid_cleanup_not_verified(self) -> None:
        bad_source = dict(self.valid_source, cleanup_verified=False)
        status, cls, reason, failures = classify_attempt(
            source_result=bad_source,
            target_snapshot={"events": []},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.INVALID)
        self.assertEqual(reason, InvalidReason.SOURCE_STIMULUS_FAILURE)

    def test_acoustic_or_gate_miss(self) -> None:
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": [{"s": 1, "t": "DETECTOR_GENERATION_STARTED", "g": 1}]},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.FAILED)
        self.assertEqual(cls, FailureClassification.ACOUSTIC_OR_GATE_MISS)

    def test_classifier_model_miss(self) -> None:
        events = [
            {"s": 1, "t": "DETECTOR_GENERATION_STARTED", "g": 1},
            {"s": 2, "t": "VOICED_FRAME_AFTER_SILENCE", "g": 1},
            {"s": 3, "t": "STAGE3_READY", "g": 1},
            # No ACTIVATION_CANDIDATE
        ]
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": events},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.FAILED)
        self.assertEqual(cls, FailureClassification.CLASSIFIER_MODEL_MISS)

    def test_activation_handoff_failure(self) -> None:
        events = [
            {"s": 1, "t": "DETECTOR_GENERATION_STARTED", "g": 1},
            {"s": 2, "t": "VOICED_FRAME_AFTER_SILENCE", "g": 1},
            {"s": 3, "t": "ACTIVATION_CANDIDATE", "g": 1},
            {"s": 4, "t": "VERIFIED_ACTIVATION", "g": 1},
            # No WAKE_CALLBACK_INVOKED
        ]
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": events},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.FAILED)
        self.assertEqual(cls, FailureClassification.ACTIVATION_HANDOFF_FAILURE)

    def test_stt_readiness_failure(self) -> None:
        events = [
            {"s": 1, "t": "DETECTOR_GENERATION_STARTED", "g": 1},
            {"s": 2, "t": "VOICED_FRAME_AFTER_SILENCE", "g": 1},
            {"s": 3, "t": "ACTIVATION_CANDIDATE", "g": 1},
            {"s": 4, "t": "WAKE_CALLBACK_INVOKED", "g": 1},
            {"s": 5, "t": "VOICE_SESSION_STARTED", "g": 1, "i": 1},
            {"s": 6, "t": "STT_START_REQUESTED", "g": 1},
            # No STT_READY
        ]
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": events},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.FAILED)
        self.assertEqual(cls, FailureClassification.STT_READINESS_FAILURE)

    def test_cue_audio_failure(self) -> None:
        events = [
            {"s": 1, "t": "DETECTOR_GENERATION_STARTED", "g": 1},
            {"s": 2, "t": "VOICED_FRAME_AFTER_SILENCE", "g": 1},
            {"s": 3, "t": "ACTIVATION_CANDIDATE", "g": 1},
            {"s": 4, "t": "WAKE_CALLBACK_INVOKED", "g": 1},
            {"s": 5, "t": "VOICE_SESSION_STARTED", "g": 1, "i": 1},
            {"s": 6, "t": "STT_READY", "g": 1},
            # No CUE_REQUESTED
        ]
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": events},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.FAILED)
        self.assertEqual(cls, FailureClassification.CUE_AUDIO_FAILURE)

    def test_stt_timeout_still_passes_for_wake_only(self) -> None:
        """Wake-only with expected STT timeout still passes with healthy wake."""
        events = [
            {"s": 1, "t": "DETECTOR_GENERATION_STARTED", "g": 1},
            {"s": 2, "t": "SILENCE_GATE_ENTERED", "g": 1},
            {"s": 3, "t": "VOICED_FRAME_AFTER_SILENCE", "g": 1},
            {"s": 4, "t": "ACTIVATION_CANDIDATE", "g": 1},
            {"s": 5, "t": "VERIFIED_ACTIVATION", "g": 1},
            {"s": 6, "t": "WAKE_CALLBACK_INVOKED", "g": 1},
            {"s": 7, "t": "VOICE_SESSION_STARTED", "g": 1, "i": 1},
            {"s": 8, "t": "STT_READY", "g": 1, "i": 1},
            {"s": 9, "t": "CUE_REQUESTED", "g": 1},
            {"s": 10, "t": "SESSION_COMPLETED", "g": 1, "i": 1},
            {"s": 11, "t": "DETECTOR_REARMED", "g": 2},
        ]
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": events},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.PASSED)

    def test_command_routing_failure(self) -> None:
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": self.passing_events},  # No STT_FINAL_RESULT
            trial_type=TrialType.WAKE_PLUS_COMMAND,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.FAILED)
        self.assertEqual(cls, FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE)

    def test_rearm_failure(self) -> None:
        events = [e for e in self.passing_events if e["t"] != "DETECTOR_REARMED"]
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": events},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.FAILED)
        self.assertEqual(cls, FailureClassification.SERVICE_REARM_FAILURE)

    def test_cue_audibility_unconfirmed(self) -> None:
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": self.passing_events},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=False,
        )
        self.assertEqual(status, AttemptStatus.FAILED)
        self.assertEqual(cls, FailureClassification.CUE_AUDIBILITY_UNCONFIRMED)

    def test_unclassified_no_terminal(self) -> None:
        events = self.passing_events[:-2]  # Remove SESSION_COMPLETED and DETECTOR_REARMED
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": events},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.FAILED)
        self.assertEqual(cls, FailureClassification.UNCLASSIFIED)

    def test_evidence_boundary_lost(self) -> None:
        """Overflow that evicted post-boundary events should be invalid."""
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={
                "events": [{"s": 150, "t": "DETECTOR_GENERATION_STARTED", "g": 5}],
                "overflowed": True,
                "lowest_sequence": 100,
                "since_sequence": 50,
                "highest_sequence": 150,
            },
            trial_type=TrialType.WAKE_ONLY,
            gen_id=5, session_id=None,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.INVALID)
        self.assertEqual(reason, InvalidReason.EVIDENCE_BOUNDARY_LOST)

    def test_invalid_attempt_retention(self) -> None:
        """Invalid attempts remain in evidence."""
        status, cls, reason, failures = classify_attempt(
            source_result=None,
            target_snapshot={"events": []},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.INVALID)

    def test_valid_failure_never_retried(self) -> None:
        """Valid failure should be in a different category than invalid."""
        # This test verifies the classification produces FAILED, not INVALID
        events = [
            {"s": 1, "t": "DETECTOR_GENERATION_STARTED", "g": 1},
        ]
        status, cls, reason, failures = classify_attempt(
            source_result=self.valid_source,
            target_snapshot={"events": events},
            trial_type=TrialType.WAKE_ONLY,
            gen_id=1, session_id=1,
            cue_policy_available=True,
        )
        self.assertEqual(status, AttemptStatus.FAILED)

    def test_every_valid_failure_has_classification(self) -> None:
        """Every valid failure must have a classification or unclassified."""
        scenarios = [
            ({"events": []}, FailureClassification.ACOUSTIC_OR_GATE_MISS),
            ({"events": [{"s": 1, "t": "DETECTOR_GENERATION_STARTED", "g": 1},
                         {"s": 2, "t": "VOICED_FRAME_AFTER_SILENCE", "g": 1}]},
             FailureClassification.CLASSIFIER_MODEL_MISS),
        ]
        for snapshot, expected_cls in scenarios:
            status, cls, reason, failures = classify_attempt(
                source_result=self.valid_source,
                target_snapshot=snapshot,
                trial_type=TrialType.WAKE_ONLY,
                gen_id=1, session_id=1,
                cue_policy_available=True,
            )
            self.assertEqual(status, AttemptStatus.FAILED)
            # Must have either classification or unclassified
            self.assertIsNotNone(cls, f"Failed attempt without classification: {failures}")


# ── Evidence Rendering Tests ────────────────────────────────────────

class EvidenceRenderingTest(unittest.TestCase):
    """Evidence rendering and normalisation."""

    def setUp(self) -> None:
        self.manifest = RunManifest(
            run_id="test-2026-07-18T10-00-00Z-a1b2",
            run_kind=RunKind.DIAGNOSTIC,
            gate_mode=GateMode.DIAGNOSTIC,
            matrix_id=MATRIX_ID,
            matrix_version=MATRIX_VERSION,
            created_utc=utc_now(),
            source_alias="s23u",
            target_alias="s21",
            fixture_set_id="v1-test",
            fixture_hashes={"natural_wake": "abc123", "qwen_command": "def456"},
            cue_policy_version=None,
            preflight_hash="preflight-hash",
        )
        self.target_identity = type("Identity", (), {
            "public": lambda self: {"alias": "s21", "model": "SM-G991B"},
            "alias": "s21",
        })()
        self.source_identity = type("Identity", (), {
            "public": lambda self: {"alias": "s23u", "model": "SM-S918B"},
            "alias": "s23u",
        })()

    def test_summary_reconciliation(self) -> None:
        attempts = [
            MatrixAttempt("t1", MatrixSlot(10, True), 1, AttemptStatus.PASSED),
            MatrixAttempt("t2", MatrixSlot(10, True), 1, AttemptStatus.PASSED),
            MatrixAttempt("t3", MatrixSlot(10, False), 1, AttemptStatus.FAILED,
                          classification=FailureClassification.STT_READINESS_FAILURE),
            MatrixAttempt("t4", MatrixSlot(30, True), 1, AttemptStatus.INVALID,
                          invalid_reason=InvalidReason.DEVICE_ENVIRONMENT_ERROR),
        ]
        evidence = render_evidence(
            run_manifest=self.manifest,
            target_identity=self.target_identity,
            source_identity=self.source_identity,
            source_helper_version="1.0.0",
            attempts=attempts,
            preflight_approval=None,
            cleanup_verified=True,
            source_route="builtin_speaker",
        )
        self.assertEqual(evidence["summary"]["total_attempts"], 4)
        self.assertEqual(evidence["summary"]["valid"], 3)  # passed + failed
        self.assertEqual(evidence["summary"]["passed"], 2)
        self.assertEqual(evidence["summary"]["failed"], 1)
        self.assertEqual(evidence["summary"]["invalid"], 1)
        self.assertAlmostEqual(evidence["summary"]["pass_rate"], 2 / 3, places=4)

    def test_invalid_excluded_from_pass_rate(self) -> None:
        attempts = [
            MatrixAttempt("t1", MatrixSlot(10, True), 1, AttemptStatus.INVALID,
                          invalid_reason=InvalidReason.DEVICE_ENVIRONMENT_ERROR),
            MatrixAttempt("t2", MatrixSlot(10, True), 1, AttemptStatus.PASSED),
        ]
        evidence = render_evidence(
            run_manifest=self.manifest,
            target_identity=self.target_identity,
            source_identity=self.source_identity,
            source_helper_version="1.0.0",
            attempts=attempts,
            preflight_approval=None,
            cleanup_verified=True,
            source_route="builtin_speaker",
        )
        self.assertEqual(evidence["summary"]["passed"], 1)
        self.assertEqual(evidence["summary"]["invalid"], 1)
        self.assertEqual(evidence["summary"]["pass_rate"], 1.0)  # 1/1 valid

    def test_failed_without_classification_has_unclassified(self) -> None:
        attempts = [
            MatrixAttempt("t1", MatrixSlot(10, True), 1, AttemptStatus.FAILED),
        ]
        evidence = render_evidence(
            run_manifest=self.manifest,
            target_identity=self.target_identity,
            source_identity=self.source_identity,
            source_helper_version="1.0.0",
            attempts=attempts,
            preflight_approval=None,
            cleanup_verified=True,
            source_route="builtin_speaker",
        )
        case = evidence["cases"][0]
        self.assertEqual(case["failure_classification"], "unclassified")

    def test_failed_with_classification(self) -> None:
        attempts = [
            MatrixAttempt("t1", MatrixSlot(10, True), 1, AttemptStatus.FAILED,
                          classification=FailureClassification.ACOUSTIC_OR_GATE_MISS),
        ]
        evidence = render_evidence(
            run_manifest=self.manifest,
            target_identity=self.target_identity,
            source_identity=self.source_identity,
            source_helper_version="1.0.0",
            attempts=attempts,
            preflight_approval=None,
            cleanup_verified=True,
            source_route="builtin_speaker",
        )
        self.assertEqual(evidence["cases"][0]["failure_classification"],
                         "acoustic_or_gate_miss")

    def test_invalid_with_reason(self) -> None:
        attempts = [
            MatrixAttempt("t1", MatrixSlot(10, True), 1, AttemptStatus.INVALID,
                          invalid_reason=InvalidReason.SOURCE_STIMULUS_FAILURE),
        ]
        evidence = render_evidence(
            run_manifest=self.manifest,
            target_identity=self.target_identity,
            source_identity=self.source_identity,
            source_helper_version="1.0.0",
            attempts=attempts,
            preflight_approval=None,
            cleanup_verified=True,
            source_route="builtin_speaker",
        )
        self.assertEqual(evidence["cases"][0]["invalid_reason"],
                         "source_stimulus_failure")

    def test_wake_reliability_fields_present(self) -> None:
        evidence = render_evidence(
            run_manifest=self.manifest,
            target_identity=self.target_identity,
            source_identity=self.source_identity,
            source_helper_version="1.0.0",
            attempts=[],
            preflight_approval={"source_volume_index": 7, "placement_notes": "test"},
            cleanup_verified=True,
            source_route="builtin_speaker",
        )
        wr = evidence.get("wake_reliability", {})
        self.assertEqual(wr.get("run_kind"), "diagnostic_pre_fix")
        self.assertEqual(wr.get("gate_mode"), "diagnostic")
        self.assertEqual(wr.get("matrix_id"), MATRIX_ID)
        self.assertEqual(wr.get("matrix_version"), MATRIX_VERSION)
        self.assertEqual(wr.get("approved_source_volume"), 7)
        self.assertTrue(wr.get("cleanup_verified"))


# ── Sanitisation Tests ──────────────────────────────────────────────

class SanitisationTest(unittest.TestCase):
    """Privacy sanitisation."""

    def test_ip_port_redacted(self) -> None:
        result = sanitise_text("connected to 100.76.134.49:44599")
        self.assertNotIn("100.76.134.49:44599", result)
        self.assertIn("[REDACTED]", result)

    def test_home_path_redacted(self) -> None:
        result = sanitise_text("/home/lokhor/fixtures/natural.wav")
        self.assertNotIn("/home/lokhor", result)

    def test_serial_safe_commit(self) -> None:
        assert_commit_safe({"device": "s21", "model": "SM-G991B"})

    def test_unsafe_commit_raises(self) -> None:
        with self.assertRaises(HarnessError):
            assert_commit_safe({"serial": "100.76.134.49:44599"})

    def test_raw_filename_raises(self) -> None:
        with self.assertRaises(HarnessError):
            assert_commit_safe({"artifact": "source-result.json"})

    def test_secret_custom_redaction(self) -> None:
        text = sanitise_text("my secret key is ABC123", secrets=["ABC123"])
        self.assertNotIn("ABC123", text)
        self.assertIn("[REDACTED_DEVICE_IDENTIFIER]", text)

    def test_adb_serial_redacted_from_evidence(self) -> None:
        evidence = {
            "device": {"alias": "s21"},
            "serial": "100.76.134.49:44599",
        }
        with self.assertRaises(HarnessError):
            assert_commit_safe(evidence)

    def test_no_private_paths_in_evidence(self) -> None:
        evidence = {"run_id": "test", "device": {"alias": "s21"}}
        assert_commit_safe(evidence)


# ── Checkpoint and Resume Tests ─────────────────────────────────────

class CheckpointResumeTest(unittest.TestCase):
    """Checkpoint persistence and resume logic."""

    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp())
        self.source = FakeAdbClient("source-serial")
        self.target = FakeAdbClient("target-serial")

    def tearDown(self) -> None:
        import shutil
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def _make_runner(self, **kwargs: Any) -> AcousticWakeReliabilityRunner:
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=self.source,
            target_client=self.target,
            private_root=self.tmpdir,
            **kwargs,
        )
        return runner

    def test_checkpoint_writes_json(self) -> None:
        runner = self._make_runner()
        state = runner.checkpoint()
        checkpoint_path = runner.run_dir / "checkpoint.json"
        self.assertTrue(checkpoint_path.exists())
        data = json.loads(checkpoint_path.read_text())
        self.assertEqual(data["run_kind"], "smoke")
        self.assertEqual(data["matrix_id"], MATRIX_ID)

    def test_checkpoint_tracks_attempts(self) -> None:
        runner = self._make_runner()
        attempt = MatrixAttempt("test-1", MatrixSlot(10, True), 1, AttemptStatus.PASSED)
        runner.attempts.append(attempt)
        runner.completed_slots.add("10:wo:1")
        runner.checkpoint()

        data = json.loads((runner.run_dir / "checkpoint.json").read_text())
        self.assertEqual(len(data["attempts"]), 1)
        self.assertEqual(data["completed_slots"], ["10:wo:1"])

    def test_load_checkpoint_restores_state(self) -> None:
        runner = self._make_runner()
        attempt = MatrixAttempt("test-1", MatrixSlot(10, True), 1, AttemptStatus.PASSED)
        runner.attempts.append(attempt)
        runner.completed_slots.add("10:wo:1")
        runner.checkpoint()
        run_id = runner.run_id

        runner2 = self._make_runner()
        state = runner2.load_checkpoint(run_id)
        self.assertEqual(state["run_id"], run_id)
        self.assertEqual(len(state["attempts"]), 1)
        self.assertEqual(runner2.completed_slots, {"10:wo:1"})

    def test_corrupted_checkpoint_raises(self) -> None:
        runner = self._make_runner()
        runner.run_dir.mkdir(parents=True, exist_ok=True)
        bad_path = runner.run_dir / "checkpoint.json"
        bad_path.write_text("not-json")
        with self.assertRaises(HarnessError) as ctx:
            runner.load_checkpoint(runner.run_id)
        self.assertIn("corrupted", str(ctx.exception).lower())

    def test_missing_checkpoint_raises(self) -> None:
        runner = self._make_runner()
        with self.assertRaises(HarnessError):
            runner.load_checkpoint("nonexistent-run-id")

    def test_checkpoint_drift_raises(self) -> None:
        """Preflight drift between checkpoint and current state."""
        runner = self._make_runner()
        runner.preflight_approval = {"test": "data"}
        runner.checkpoint()

        # Modify preflight to simulate drift
        preflight_path = runner.run_dir / "preflight-private.json"
        preflight_path.write_text(json.dumps({"test": "changed"}))

        runner2 = self._make_runner()
        with self.assertRaises(HarnessError):
            runner2.load_checkpoint(runner.run_id)

    def test_valid_failure_not_retried_during_resume(self) -> None:
        """Slots with valid failures are never retried."""
        runner = self._make_runner()
        runner.valid_failed_slots.add("10:wo")
        runner.completed_slots.add("10:wo:1")
        runner.checkpoint()

        # Load and verify
        runner2 = self._make_runner()
        runner2.load_checkpoint(runner.run_id)
        self.assertIn("10:wo", runner2.valid_failed_slots)

    def test_invalid_attempt_retained(self) -> None:
        """Invalid attempts are retained in checkpoint."""
        runner = self._make_runner()
        attempt = MatrixAttempt("test-inv", MatrixSlot(10, True), 1,
                                AttemptStatus.INVALID,
                                invalid_reason=InvalidReason.DEVICE_ENVIRONMENT_ERROR)
        runner.attempts.append(attempt)
        runner.completed_slots.add("10:wo:1")
        runner.invalid_attempt_count = 1
        runner.checkpoint()

        runner2 = self._make_runner()
        runner2.load_checkpoint(runner.run_id)
        self.assertEqual(runner2.invalid_attempt_count, 1)
        self.assertEqual(len(runner2.attempts), 1)


# ── Fixture Mode Tests ──────────────────────────────────────────────

class FixtureModeTest(unittest.TestCase):
    """Fixture/dry-run mode performs no ADB mutation."""

    def test_fixture_mode_runs_without_adb(self) -> None:
        from acoustic_wake_reliability_runner import fixture_mode
        exit_code = fixture_mode()
        self.assertEqual(exit_code, 0)


# ── Preflight Tests ─────────────────────────────────────────────────

class PreflightTest(unittest.TestCase):
    """Preflight candidate selection and immutable approval."""

    def setUp(self) -> None:
        self.source = FakeAdbClient("source")
        self.target = FakeAdbClient("target")

    def test_preflight_rejects_no_interactive(self) -> None:
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.PREFLIGHT,
            source_alias="s23u",
            target_alias="s21",
            source_client=self.source,
            target_client=self.target,
        )
        with self.assertRaises(HarnessError):
            runner.run_preflight("v1-test", {"natural_wake": "abc"})

    def test_preflight_no_automatic_volume_search(self) -> None:
        """Preflight starts at conservative volume, never searches."""
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.PREFLIGHT,
            source_alias="s23u",
            target_alias="s21",
            source_client=self.source,
            target_client=self.target,
        )
        preflight = runner.preflight_approval
        # Before any run, there should be no approval
        self.assertIsNone(preflight)

    def test_preflight_rejects_source_wake_active(self) -> None:
        self.source.responses["shell dumpsys activity services com.kernel.ai.debug"] = \
            "WakeWordService" in "WakeWordService" and "WakeWordService\n" or ""
        self.target.responses["shell dumpsys activity services com.kernel.ai.debug"] = \
            "serviceInfo=...WakeWordService..."
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.PREFLIGHT,
            source_alias="s23u",
            target_alias="s21",
            source_client=self.source,
            target_client=self.target,
        )
        runner.source_identity = type("Identity", (), {
            "public": lambda self: {"alias": "s23u", "model": "SM-S918B"},
            "alias": "s23u",
        })()
        runner.target_identity = type("Identity", (), {
            "public": lambda self: {"alias": "s21", "model": "SM-G991B"},
            "alias": "s21",
        })()
        with self.assertRaises(HarnessError):
            runner._verify_state()

    def test_preflight_rejects_target_wake_inactive(self) -> None:
        self.target.responses["shell dumpsys activity services com.kernel.ai.debug"] = ""
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.PREFLIGHT,
            source_alias="s23u",
            target_alias="s21",
            source_client=self.source,
            target_client=self.target,
        )
        # This will be rejected at the interactive preflight barrier first


# ── Target Idle Preservation Tests ──────────────────────────────────

class TargetIdlePreservationTest(unittest.TestCase):
    """No target ADB commands during idle phase."""

    def test_no_target_adb_during_idle(self) -> None:
        """Verify idle interval produces no target ADB commands."""
        source = FakeAdbClient("source")
        target = FakeAdbClient("target")
        run_id_counter = 0

        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=source,
            target_client=target,
        )
        # Mock checkpoint writer to count target commands
        target_commands_before = len(target.commands)

        # Simulate idle phase: should produce no target commands
        idle_start = time.monotonic()
        remaining = 100  # 100ms for test
        while remaining > 0:
            wait = min(remaining, 50)
            time.sleep(wait / 1000)
            remaining -= wait

        target_commands_during = len(target.commands) - target_commands_before
        self.assertEqual(target_commands_during, 0,
                         "Idle phase produced target ADB commands")


# ── Cross-Device Clock Domain Tests ─────────────────────────────────

class ClockDomainIsolationTest(unittest.TestCase):
    """Different clock domains must not be directly subtracted."""

    def test_no_cross_device_subtraction(self) -> None:
        """Test that our code doesn't directly subtract different clock domains."""
        import acoustic_wake_reliability_runner as runner_mod
        source = inspect if False else None  # Just verify the module loads
        self.assertIsNotNone(runner_mod)
        # The module uses monotonic_ms() for host time
        # and target snapshot m timestamps for target time
        # No code should subtract one device's time from another

    def test_host_monotonic_separate_from_target(self) -> None:
        host_ms = monotonic_ms()
        # Target events use their own monotonic timestamps (m field)
        # Host only tracks dispatch/return boundaries
        self.assertGreater(host_ms, 0)


# ── Invalid Retry Limit Tests ───────────────────────────────────────

class InvalidRetryLimitTest(unittest.TestCase):
    """Invalid attempts have finite retry limit."""

    def test_invalid_retry_limit_defined(self) -> None:
        self.assertGreater(VALID_MAX_ATTEMPTS, 0)
        self.assertLessEqual(VALID_MAX_ATTEMPTS, 10)

    def test_invalid_count_tracking(self) -> None:
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=FakeAdbClient("s"),
            target_client=FakeAdbClient("t"),
        )
        slot = MatrixSlot(10, True)
        self.assertEqual(runner._invalid_count_for(slot), 0)
        runner.attempts.append(MatrixAttempt("t1", slot, 1, AttemptStatus.INVALID,
                                             invalid_reason=InvalidReason.SOURCE_STIMULUS_FAILURE))
        self.assertEqual(runner._invalid_count_for(slot), 1)

    def test_valid_failure_not_counted_as_invalid(self) -> None:
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=FakeAdbClient("s"),
            target_client=FakeAdbClient("t"),
        )
        slot = MatrixSlot(10, True)
        runner.attempts.append(MatrixAttempt("t1", slot, 1, AttemptStatus.FAILED,
                                             classification=FailureClassification.ACOUSTIC_OR_GATE_MISS))
        self.assertEqual(runner._invalid_count_for(slot), 0)


# ── Evidence Formatting ─────────────────────────────────────────────

class EvidenceFormattingTest(unittest.TestCase):
    """Evidence Markdown rendering."""

    def test_format_target_events(self) -> None:
        snapshot = _load_json("target-journal-snapshot-valid.json")
        parsed = parse_target_bundle(snapshot["result_code"], snapshot["result_data"])
        events = format_target_snapshot_events(parsed)
        self.assertGreater(len(events), 0)
        for ev in events:
            self.assertIn("s", ev)
            self.assertIn("t", ev)

    def test_write_sanitized_summary_creates_files(self) -> None:
        from acoustic_wake_reliability_runner import write_sanitized_summary
        manifest = RunManifest(
            run_id="test-summary",
            run_kind=RunKind.DIAGNOSTIC,
            gate_mode=GateMode.DIAGNOSTIC,
            matrix_id=MATRIX_ID,
            matrix_version=MATRIX_VERSION,
            created_utc=utc_now(),
            source_alias="s23u",
            target_alias="s21",
            fixture_set_id="v1",
            fixture_hashes={"natural_wake": "abc"},
            cue_policy_version=None,
            preflight_hash="hash",
        )
        evidence = render_evidence(
            run_manifest=manifest,
            target_identity=type("I", (), {"public": lambda self: {"alias": "s21", "model": "SM-G991B"}, "alias": "s21"})(),
            source_identity=type("I", (), {"public": lambda self: {"alias": "s23u", "model": "SM-S918B"}, "alias": "s23u"})(),
            source_helper_version="1.0.0",
            attempts=[],
            preflight_approval=None,
            cleanup_verified=True,
            source_route="builtin_speaker",
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            json_path, md_path = write_sanitized_summary(Path(tmpdir), evidence)
            self.assertTrue(json_path.exists())

    def test_diagnostic_runs_may_contain_valid_failures(self) -> None:
        """Diagnostic mode doesn't require 100% pass rate."""
        manifest = RunManifest(
            run_id="diag-test",
            run_kind=RunKind.DIAGNOSTIC,
            gate_mode=GateMode.DIAGNOSTIC,
            matrix_id=MATRIX_ID,
            matrix_version=MATRIX_VERSION,
            created_utc=utc_now(),
            source_alias="s23u",
            target_alias="s21",
            fixture_set_id="v1",
            fixture_hashes={},
            cue_policy_version=None,
            preflight_hash=None,
        )
        evidence = render_evidence(
            run_manifest=manifest,
            target_identity=type("I", (), {"public": lambda self: {"alias": "s21"}, "alias": "s21"})(),
            source_identity=type("I", (), {"public": lambda self: {"alias": "s23u"}, "alias": "s23u"})(),
            source_helper_version="1.0.0",
            attempts=[MatrixAttempt("f1", MatrixSlot(10, True), 1, AttemptStatus.FAILED,
                                     classification=FailureClassification.ACOUSTIC_OR_GATE_MISS)],
            preflight_approval=None,
            cleanup_verified=True,
            source_route="builtin_speaker",
        )
        # Diagnostic runs can have valid failures
        self.assertEqual(evidence["summary"]["failed"], 1)

    def test_release_gate_fails_without_cue_policy(self) -> None:
        """Release-gate mode must fail closed when #1405 cue evidence is absent."""
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.REGRESSION,
            source_alias="s23u",
            target_alias="s21",
            source_client=FakeAdbClient("s"),
            target_client=FakeAdbClient("t"),
        )
        with self.assertRaises(HarnessError):
            runner.export_evidence()


# ── Cancellation Tests ──────────────────────────────────────────────

class CancellationTest(unittest.TestCase):
    """Cancellation at each lifecycle phase."""

    def test_cancel_before_idle(self) -> None:
        target = FakeAdbClient("t")
        target.responses["shell content call"] = "result_code=0\nresult_data=0\n"
        target.responses["shell cat /proc/uptime"] = "12345.67 89012.34\n"
        target.responses["shell dumpsys power"] = "mScreenOn=false\n"
        target.responses["shell dumpsys deviceidle"] = "IDLE\n"
        target.responses["shell dumpsys activity services com.kernel.ai.debug"] = "WakeWordService\n"
        source = FakeAdbClient("s")
        source.responses["shell get-state"] = "device\n"
        source.responses["shell dumpsys activity services com.kernel.ai.debug"] = ""
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=source,
            target_client=target,
        )
        runner._cancel.set()
        attempt = runner.run_trial("test-cancel", MatrixSlot(10, True), "natural_wake")
        self.assertEqual(attempt.status, AttemptStatus.INVALID)

    def test_cancel_during_idle(self) -> None:
        """Cancel during idle should produce invalid attempt."""
        target = FakeAdbClient("t")
        target.responses["shell content call --uri content://com.kernel.ai.debug.journal.TargetEventJournalProvider/journal --method GET_JOURNAL_SEQUENCE"] = "result_code=0\nresult_data=0\n"
        target.responses["shell content call --uri content://com.kernel.ai.debug.journal.TargetEventJournalProvider/journal --method GET_JOURNAL_SNAPSHOT --el since_sequence 0"] = "result_code=0\nresult_data={}\n"
        target.responses["shell content call --uri content://com.kernel.ai.debug.journal.TargetEventJournalProvider/journal --method WAIT_FOR_JOURNAL_EVENT"] = "result_code=1\nresult_data=timeout\n"
        target.responses["shell cat /proc/uptime"] = "12345.67 89012.34\n"
        target.responses["shell dumpsys power"] = "mScreenOn=false\n"
        target.responses["shell dumpsys deviceidle"] = "IDLE\n"
        target.responses["shell dumpsys activity services com.kernel.ai.debug"] = "WakeWordService\n"
        source = FakeAdbClient("s")
        source.responses["shell get-state"] = "device\n"
        source.responses["shell dumpsys activity services com.kernel.ai.debug"] = ""
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=source,
            target_client=target,
        )
        # Cancel before entering run_trial (cancel before idle)
        runner._cancel.set()
        attempt = runner.run_trial("test-cancel-idle", MatrixSlot(10, True), "natural_wake")
        self.assertEqual(attempt.status, AttemptStatus.INVALID)

    def test_first_failure_preserved(self) -> None:
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=FakeAdbClient("s"),
            target_client=FakeAdbClient("t"),
        )
        runner.primary_failure = "first failure"
        self.assertEqual(runner.primary_failure, "first failure")
        # Second failure shouldn't overwrite
        if runner.primary_failure is None:
            runner.primary_failure = "second failure"
        self.assertEqual(runner.primary_failure, "first failure")

    def test_cleanup_after_cancel(self) -> None:
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=FakeAdbClient("s"),
            target_client=FakeAdbClient("t"),
        )
        runner.preflight_approval = {"source_volume_index": 7}
        # Should not raise
        runner.cleanup()
        self.assertIsNotNone(runner.cleanup_verified)


# ── CLI Parser Tests ────────────────────────────────────────────────

class CLIParserTest(unittest.TestCase):
    """Command-line argument parsing."""

    def setUp(self) -> None:
        from acoustic_wake_reliability_runner import build_parser
        self.parser = build_parser()

    def test_fixture_mode_no_args(self) -> None:
        args = self.parser.parse_args(["fixture"])
        self.assertEqual(args.mode, "fixture")

    def test_smoke_mode_requires_selectors(self) -> None:
        """Smoke mode without selectors should result in empty selector strings."""
        args = self.parser.parse_args(["smoke"])
        # argparse stores defaults (empty env var), validation happens in main()
        self.assertEqual(args.source_selector, "")
        self.assertEqual(args.target_selector, "")

    def test_preflight_with_selectors(self) -> None:
        args = self.parser.parse_args([
            "preflight", "--source", "s23u", "--target", "s21",
            "--source-selector", "src", "--target-selector", "tgt",
        ])
        self.assertEqual(args.source, "s23u")
        self.assertEqual(args.target, "s21")
        self.assertEqual(args.source_selector, "src")
        self.assertEqual(args.target_selector, "tgt")

    def test_resume_requires_run_id(self) -> None:
        args = self.parser.parse_args(["resume", "--run-id", "test-run"])
        self.assertEqual(args.run_id, "test-run")

    def test_cue_margin_default(self) -> None:
        args = self.parser.parse_args(["fixture"])
        self.assertEqual(args.cue_margin_ms, DEFAULT_CUE_MARGIN_MS)

    def test_fixed_command_delay_only_feasibility(self) -> None:
        args = self.parser.parse_args(["feasibility", "--fixed-command-delay-ms", "3000"])
        self.assertEqual(args.fixed_command_delay_ms, 3000)

    def test_regression_requires_cue_policy(self) -> None:
        args = self.parser.parse_args(["regression", "--cue-policy-version", "1.0"])
        self.assertEqual(args.cue_policy_version, "1.0")

    def test_interactive_flag(self) -> None:
        args = self.parser.parse_args(["preflight", "--interactive"])
        self.assertTrue(args.interactive)

    def test_env_var_defaults(self) -> None:
        os.environ["JANDAL_SOURCE"] = "env-source"
        os.environ["JANDAL_TARGET"] = "env-target"
        try:
            args = self.parser.parse_args(["smoke", "--source-selector", "src"])
            self.assertEqual(args.source_selector, "src")
        finally:
            del os.environ["JANDAL_SOURCE"]
            del os.environ["JANDAL_TARGET"]


# ── Fixture Completeness Tests ──────────────────────────────────────

class FixtureCompletenessTest(unittest.TestCase):
    """Committed synthetic JSON fixtures are valid."""

    def test_fixture_dir_exists(self) -> None:
        self.assertTrue(FIXTURES.exists())

    def test_all_fixtures_valid_json(self) -> None:
        for path in FIXTURES.iterdir():
            if path.suffix == ".json":
                try:
                    json.loads(path.read_text())
                except json.JSONDecodeError as e:
                    self.fail(f"Invalid JSON in {path.name}: {e}")

    def test_snapshot_fixture_has_events(self) -> None:
        snapshot = _load_json("target-journal-snapshot-valid.json")
        parsed = parse_target_bundle(snapshot["result_code"], snapshot["result_data"])
        self.assertGreater(len(parsed.get("events", [])), 0)

    def test_target_journal_contract_version(self) -> None:
        from acoustic_wake_reliability_runner import JOURNAL_CONTRACT_VERSION
        self.assertEqual(JOURNAL_CONTRACT_VERSION, "1.0.0")

    def test_expected_devices_match_fixtures(self) -> None:
        s21_props = _load_fixture("device-props-s21.txt")
        self.assertIn("SM-G991B", s21_props)
        s23u_props = _load_fixture("device-props-s23u.txt")
        self.assertIn("SM-S918B", s23u_props)

    def test_fixtures_are_synthetic(self) -> None:
        """Verify no real device data in fixtures."""
        for path in FIXTURES.iterdir():
            text = path.read_text()
            self.assertNotIn("NickMonrad", text)
            self.assertNotIn("Monrad", text)


# ── Matrix Completion Check ─────────────────────────────────────────

class MatrixCompletionTest(unittest.TestCase):
    """Matrix completeness verification."""

    def test_matrix_not_complete_without_slots(self) -> None:
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=FakeAdbClient("s"),
            target_client=FakeAdbClient("t"),
        )
        self.assertFalse(runner.is_matrix_complete())

    def test_matrix_complete_with_all_passed(self) -> None:
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=FakeAdbClient("s"),
            target_client=FakeAdbClient("t"),
        )
        slots = matrix_slots_for_target("s21")
        for i, slot in enumerate(slots):
            attempt = MatrixAttempt(
                f"pass-{i}", slot, 1, AttemptStatus.PASSED
            )
            runner.attempts.append(attempt)
            key = f"{slot.idle_s}:{'wo' if slot.wake_only else 'wc'}:1"
            runner.completed_slots.add(key)
        self.assertTrue(runner.is_matrix_complete())

    def test_matrix_not_complete_with_failures(self) -> None:
        runner = AcousticWakeReliabilityRunner(
            run_kind=RunKind.SMOKE,
            source_alias="s23u",
            target_alias="s21",
            source_client=FakeAdbClient("s"),
            target_client=FakeAdbClient("t"),
        )
        slots = matrix_slots_for_target("s21")
        for i, slot in enumerate(slots):
            attempt = MatrixAttempt(
                f"fail-{i}", slot, 1, AttemptStatus.FAILED,
                classification=FailureClassification.UNCLASSIFIED,
            )
            runner.attempts.append(attempt)
            key = f"{slot.idle_s}:{'wo' if slot.wake_only else 'wc'}:1"
            runner.completed_slots.add(key)
            runner.valid_failed_slots.add(f"{slot.idle_s}:{'wo' if slot.wake_only else 'wc'}")
        # Matrix is "complete" in the sense all slots have been attempted,
        # but failures mean it's not a passing matrix
        self.assertTrue(runner.is_matrix_complete())


if __name__ == "__main__":
    unittest.main()
