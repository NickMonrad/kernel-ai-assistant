#!/usr/bin/env python3
"""Focused contract tests for the acoustic wake-word reliability runner."""
from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

import acoustic_wake_reliability_runner as runner  # noqa: E402

FIXTURES = SCRIPTS / "testdata" / "fixtures" / "acoustic-wake-reliability"


def load_json(name: str):
    return json.loads((FIXTURES / name).read_text())


class FakeAdb:
    def __init__(self, serial: str) -> None:
        self.serial = serial
        self.commands: list[tuple[str, ...]] = []
        self.responses: dict[str, str] = {}
        self.reachable_flag = True

    def run(self, *args: str, timeout: float = 30.0) -> str:
        self.commands.append(args)
        key = " ".join(args)
        if key in self.responses:
            return self.responses[key]
        for prefix, response in self.responses.items():
            if key.startswith(prefix):
                return response
        return ""

    def shell(self, *args: str, timeout: float = 30.0) -> str:
        return self.run("shell", *args, timeout=timeout)

    def reachable(self) -> bool:
        return self.reachable_flag


def event(seq: int, event_type: str, generation: int = 4, session: int = 9,
          data: dict | None = None) -> dict:
    return {"s": seq, "m": seq * 10, "w": seq * 10, "t": event_type,
            "g": generation, "i": session, "d": data or {}}


def complete_events(trial_type: runner.TrialType) -> list[dict]:
    names = [
        "DETECTOR_GENERATION_STARTED", "SILENCE_GATE_ENTERED",
        "VOICED_FRAME_AFTER_SILENCE", "STAGE2_RESUMED", "STAGE3_READY",
        "ACTIVATION_CANDIDATE", "VERIFIED_ACTIVATION", "WAKE_CALLBACK_INVOKED",
        "VOICE_SESSION_STARTED", "STT_START_REQUESTED", "STT_READY", "CUE_REQUESTED",
    ]
    if trial_type == runner.TrialType.WAKE_PLUS_COMMAND:
        names += ["STT_SPEECH_DETECTED", "STT_FINAL", "COMMAND_ROUTING_RESULT"]
    names += ["SESSION_COMPLETED", "DETECTOR_REARMED"]
    events = [event(index, name) for index, name in enumerate(names, 1)]
    events[-1]["g"] = 5
    for item in events:
        if item["t"] == "COMMAND_ROUTING_RESULT":
            item["d"] = {"outcome": "handed_off"}
    return events


def envelope(events: list[dict], lowest: int = 1, overflowed: bool = False) -> dict:
    return {"lowestSequence": lowest, "highestSequence": events[-1]["s"] if events else lowest,
            "overflowed": overflowed, "events": events}


def source_result(trial_id: str = "trial-1", fixture_id: str = "natural_wake") -> dict:
    result = load_json("source-result-valid.json")
    result["trial_id"] = trial_id
    result["fixture_id"] = fixture_id
    return result


def make_runner(run_kind: runner.RunKind = runner.RunKind.SMOKE,
                *, fixed_command_delay_ms: int | None = None) -> runner.AcousticWakeReliabilityRunner:
    return runner.AcousticWakeReliabilityRunner(
        run_kind=run_kind,
        source_alias="s23u",
        target_alias="s21",
        source_client=FakeAdb("source"),
        target_client=FakeAdb("target"),
        private_root=Path(tempfile.mkdtemp()),
        fixed_command_delay_ms=fixed_command_delay_ms,
    )


class ProviderContractTests(unittest.TestCase):
    def test_content_call_uses_provider_and_typed_extras(self) -> None:
        args = runner.build_content_call_args(
            "WAIT_FOR_JOURNAL_EVENT",
            {"request_id": "wait-abc", "since_sequence": 12, "timeout_ms": 1500},
        )
        self.assertEqual(args[:6], [
            "shell", "content", "call", "--uri",
            runner.TARGET_PROVIDER_URI, "--method",
        ])
        self.assertEqual(args[6:], [
            "WAIT_FOR_JOURNAL_EVENT", "--extra", "request_id:s:wait-abc",
            "--extra", "since_sequence:l:12", "--extra", "timeout_ms:l:1500",
        ])
        self.assertNotIn("am", args)
        self.assertNotIn("broadcast", args)

    def test_bundle_result_parser_preserves_embedded_json(self) -> None:
        payload = json.dumps({"lowestSequence": 1, "highestSequence": 1,
                              "overflowed": False, "events": []}, separators=(",", ":"))
        code, data = runner.parse_content_call_result(
            f"Bundle[{{result_code=0, result_data=\"{payload.replace(chr(34), chr(92)+chr(34))}\"}}]"
        )
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(data)["highestSequence"], 1)

    def test_wait_result_codes_are_explicit(self) -> None:
        ev = event(3, "STT_READY")
        self.assertEqual(runner.parse_journal_wait_result(0, json.dumps(ev)), ev)
        self.assertIsNone(runner.parse_journal_wait_result(1, ""))
        with self.assertRaises(runner.HarnessError):
            runner.parse_journal_wait_result(2, "provider failure")
        with self.assertRaises(runner.HarnessError):
            runner.parse_journal_wait_result(3, "cancelled")

    def test_wait_uses_unique_request_and_final_snapshot(self) -> None:
        target = FakeAdb("target")
        wait_event = json.dumps(event(1, "STT_READY"), separators=(",", ":"))
        events = [event(1, "STT_READY")]
        snap = json.dumps(envelope([event(1, "STT_READY")]), separators=(",", ":"))
        target.responses["shell content call --uri content://com.kernel.ai.debug.target-event-journal --method WAIT_FOR_JOURNAL_EVENT"] = (
            "Bundle[{result_code=0, result_data=" + wait_event + "}]"
        )
        target.responses["shell content call --uri content://com.kernel.ai.debug.target-event-journal --method GET_JOURNAL_SNAPSHOT"] = (
            "Bundle[{result_code=0, result_data=" + snap + "}]"
        )
        harness = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.SMOKE, "s23u", "s21", FakeAdb("source"), target,
            private_root=Path(tempfile.mkdtemp()),
        )
        returned, final = harness._wait_for_target_events(0, "STT_READY", 500)
        self.assertEqual(returned, events)
        self.assertEqual(final["highestSequence"], 1)
        provider_calls = [call for call in target.commands if "content" in call]
        self.assertGreaterEqual(len(provider_calls), 2)
        wait_call = next(call for call in provider_calls if "WAIT_FOR_JOURNAL_EVENT" in call)
        request_ids = [value for value in wait_call if value.startswith("request_id:s:wait-")]
        self.assertEqual(len(request_ids), 1)


class SnapshotContractTests(unittest.TestCase):
    def test_exact_object_envelope_is_required(self) -> None:
        valid = envelope([event(1, "STT_READY")])
        self.assertEqual(runner.validate_snapshot_envelope(valid), valid)
        for invalid in ([], {}, {"events": []}, {**valid, "highestSequence": 0}):
            with self.subTest(invalid=invalid), self.assertRaises(runner.HarnessError):
                runner.validate_snapshot_envelope(invalid)

    def test_event_vocabulary_and_compact_shape_are_strict(self) -> None:
        valid = envelope([event(1, "STT_READY")])
        with self.assertRaises(runner.HarnessError):
            runner.validate_snapshot_envelope({**valid, "unexpected": True})
        with self.assertRaises(runner.HarnessError):
            runner.validate_snapshot_envelope(
                {**valid, "events": [{**valid["events"][0], "unexpected": True}]}
            )
        all_types = sorted(runner.VALID_EVENT_TYPES)
        self.assertIn("STAGE2_RESUMED", all_types)
        self.assertIn("STT_FINAL", all_types)
        self.assertIn("COMMAND_ROUTING_RESULT", all_types)
        self.assertEqual(len(all_types), 22)
        for index, event_type in enumerate(all_types, 1):
            self.assertEqual(runner.validate_snapshot_envelope(envelope([event(index, event_type)]))["events"][0]["t"], event_type)
        bad = event(1, "STT_READY")
        bad.pop("d")
        with self.assertRaises(runner.HarnessError):
            runner.validate_snapshot_envelope(envelope([bad]))
        with self.assertRaises(runner.HarnessError):
            runner.validate_snapshot_envelope(envelope([event(1, "NOT_AN_EVENT")]))

    def test_overflow_boundary_is_fail_closed(self) -> None:
        current = envelope([event(5, "STT_READY")], lowest=5, overflowed=True)
        self.assertFalse(runner.snapshot_boundary_evicted(current, 4))
        self.assertTrue(runner.snapshot_boundary_evicted(current, 3))
        self.assertFalse(runner.snapshot_boundary_evicted(envelope([event(5, "STT_READY")], lowest=5), 3))


class SourceAndCorrelationTests(unittest.TestCase):
    def test_source_contract_requires_restoration_and_route_evidence(self) -> None:
        parsed = runner.parse_source_result(json.dumps(source_result()))
        self.assertEqual(parsed["output_route_during"], "BUILT_IN_SPEAKER")
        for field, value in (("cleanup_success", False), ("exact_restoration_verified", False),
                             ("focus_result", "denied"), ("output_route_during", "BLUETOOTH")):
            invalid = source_result()
            invalid[field] = value
            with self.subTest(field=field), self.assertRaises(runner.HarnessError):
                runner.parse_source_result(json.dumps(invalid))

    def test_wake_and_command_paths_require_distinct_command_events(self) -> None:
        valid_source = source_result()
        wake = envelope(complete_events(runner.TrialType.WAKE_ONLY))
        status, classification, invalid, failures = runner.classify_attempt(
            valid_source, wake, runner.TrialType.WAKE_ONLY, None, None, True,
        )
        self.assertEqual((status, classification, invalid, failures),
                         (runner.AttemptStatus.PASSED, None, None, []))
        command = envelope(complete_events(runner.TrialType.WAKE_PLUS_COMMAND))
        status, *_ = runner.classify_attempt(
            valid_source, command, runner.TrialType.WAKE_PLUS_COMMAND, None, None, True,
        )
        self.assertEqual(status, runner.AttemptStatus.PASSED)
        missing_final = copy.deepcopy(command)
        missing_final["events"] = [e for e in missing_final["events"] if e["t"] != "STT_FINAL"]
        missing_final["highestSequence"] = missing_final["events"][-1]["s"]
        status, classification, _, _ = runner.classify_attempt(
            valid_source, missing_final, runner.TrialType.WAKE_PLUS_COMMAND, None, None, True,
        )
        self.assertEqual(classification, runner.FailureClassification.ACTIVATION_HANDOFF_FAILURE)

    def test_unrelated_generation_is_not_accepted(self) -> None:
        events = complete_events(runner.TrialType.WAKE_ONLY)
        events[10]["i"] = 10
        snap = envelope(events)
        status, classification, _, failures = runner.classify_attempt(
            source_result(), snap, runner.TrialType.WAKE_ONLY, None, None, True,
        )
        self.assertEqual(status, runner.AttemptStatus.FAILED)
        self.assertEqual(classification, runner.FailureClassification.STT_READINESS_FAILURE)
        self.assertTrue(failures)


class MatrixAndEnvironmentTests(unittest.TestCase):
    def test_positions_are_independent_with_ordinals(self) -> None:
        slots = runner.matrix_slots_for_target("s21")
        ten_second_wake = [s for s in slots if s.idle_s == 10 and s.wake_only]
        ten_second_command = [s for s in slots if s.idle_s == 10 and not s.wake_only]
        self.assertEqual([s.ordinal for s in ten_second_wake], [1, 2, 3, 4, 5])
        self.assertEqual([s.ordinal for s in ten_second_command], [1, 2, 3])
        self.assertEqual(len({s.position_id for s in slots}), len(slots))

    def test_completion_accepts_valid_failures_but_release_requires_passes(self) -> None:
        harness = make_runner(runner.RunKind.REGRESSION)
        slots = runner.matrix_slots_for_target("s21")
        for slot in slots:
            harness.attempts.append(runner.MatrixAttempt(
                trial_id=f"trial-{slot.position_id}", matrix_slot=slot, attempt=1,
                status=runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.ACOUSTIC_OR_GATE_MISS,
            ))
        self.assertTrue(harness.is_matrix_complete())
        self.assertFalse(harness.release_gate_success())

    def test_environment_boot_change_and_uptime_regression_invalidate(self) -> None:
        failures = runner.AcousticWakeReliabilityRunner._environment_failures(
            {"reachable": True, "boot_id": "a", "uptime_seconds": 20.0},
            {"reachable": True, "boot_id": "b", "uptime_seconds": 19.0},
        )
        self.assertIn("target boot ID changed during trial", failures)
        self.assertIn("target uptime regressed during trial", failures)
        self.assertNotIn("target uptime changed unexpectedly", failures)

    def test_cleanup_does_not_mutate_global_volume(self) -> None:
        harness = make_runner()
        harness.source_results = [source_result()]
        harness.cleanup()
        self.assertTrue(harness.cleanup_verified)
        commands = harness.source.commands
        self.assertFalse(any("settings" in command and "put" in command for command in commands))


class EvidenceAndModeTests(unittest.TestCase):
    def test_evidence_contains_every_required_position(self) -> None:
        manifest = runner.RunManifest(
            run_id="run-1", run_kind=runner.RunKind.FEASIBILITY, gate_mode=runner.GateMode.DIAGNOSTIC,
            matrix_id=runner.MATRIX_ID, matrix_version=runner.MATRIX_VERSION, created_utc="2026-01-01T00:00:00+00:00",
            source_alias="s23u", target_alias="s21", fixture_set_id="set-1", fixture_hashes={"natural_wake": "hash"},
            cue_policy_version=None, preflight_hash=None,
        )
        target = runner.DeviceIdentity("s21", "samsung", "SM-G991B", "15", "35", "fingerprint", "pkg", 1)
        source = runner.DeviceIdentity("s23u", "samsung", "SM-S918B", "15", "35", "fingerprint", "pkg", 1)
        evidence = runner.render_evidence(manifest, target, source, "1", [], None, False, "BUILT_IN_SPEAKER")
        expected = evidence["wake_reliability"]["expected_valid_counts"]
        self.assertEqual(set(expected), {slot.position_id for slot in runner.matrix_slots_for_target("s21")})

    def test_manifest_hash_detects_tampering(self) -> None:
        harness = make_runner()
        manifest = {"schema_version": 2, "fixture_set_id": "set-1", "fixture_hashes": {}, "cue_policy_version": "cue-v1"}
        canonical = json.dumps(manifest, sort_keys=True, separators=(",", ":"))
        manifest["manifest_sha256"] = hashlib.sha256(canonical.encode()).hexdigest()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "preflight.json"
            path.write_text(json.dumps(manifest))
            self.assertEqual(harness.load_preflight_manifest(path)["fixture_set_id"], "set-1")
            manifest["fixture_set_id"] = "tampered"
            path.write_text(json.dumps(manifest))
            with self.assertRaises(runner.HarnessError):
                harness.load_preflight_manifest(path)

    def test_sanitisation_rejects_private_paths_and_serials(self) -> None:
        with self.assertRaises(runner.HarnessError):
            runner.assert_commit_safe({"path": "/private/fixtures/source.wav"}, ["/private/fixtures/source.wav"])
        self.assertEqual(runner.sanitise_text("serial=ABC123", ["ABC123"]), "[REDACTED]")

    def test_smoke_plan_is_bounded_to_one_position(self) -> None:
        harness = make_runner()
        harness.target_identity = runner.DeviceIdentity("s21", "samsung", "SM-G991B", "15", "35", "fingerprint", "pkg", 1)
        harness.source_identity = runner.DeviceIdentity("s23u", "samsung", "SM-S918B", "15", "35", "fingerprint", "pkg", 1)
        harness.manifest = runner.RunManifest(
            "run", runner.RunKind.SMOKE, runner.GateMode.DIAGNOSTIC, runner.MATRIX_ID, runner.MATRIX_VERSION,
            runner.utc_now(), "s23u", "s21", "set", {}, None, None,
        )
        with patch.object(harness, "run_trial") as run_trial:
            run_trial.return_value = runner.MatrixAttempt(
                "smoke-1", runner.matrix_slots_for_target("s21")[0], 1, runner.AttemptStatus.PASSED,
            )
            harness.run_smoke()
            self.assertEqual(run_trial.call_count, 1)


if __name__ == "__main__":
    unittest.main()
