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
from types import SimpleNamespace
from unittest.mock import Mock, patch

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

import acoustic_wake_reliability_runner as runner  # noqa: E402
import summarise_test_evidence_metrics as evidence_metrics  # noqa: E402

FIXTURES = SCRIPTS / "testdata" / "fixtures" / "acoustic-wake-reliability"


def load_json(name: str):
    return json.loads((FIXTURES / name).read_text())


class FakeAdb:
    def __init__(self, serial: str) -> None:
        self.serial = serial
        self.commands: list[tuple[str, ...]] = []
        self.checks: list[bool] = []
        self.responses: dict[str, str] = {}
        self.reachable_flag = True

    def run(self, *args: str, timeout: float = 30.0, check: bool = True) -> str:
        self.commands.append(args)
        self.checks.append(check)
        key = " ".join(args)
        if key in self.responses:
            return self.responses[key]
        for prefix, response in self.responses.items():
            if key.startswith(prefix):
                return response
        return ""

    def shell(self, *args: str, timeout: float = 30.0, check: bool = True) -> str:
        return self.run("shell", *args, timeout=timeout, check=check)

    def reachable(self) -> bool:
        return self.reachable_flag


def configure_environment(client: FakeAdb, *, target: bool) -> None:
    package = runner.DEFAULT_PACKAGE
    responses = {
        "shell am get-current-user": "0",
        "shell dumpsys sensor_privacy": "SENSOR PRIVACY MANAGER STATE (dumpsys sensor_privacy)\n",
        f"shell cmd package list packages -U {package}": f"package:{package} uid:10123",
        "shell am get-uid-state 10123": "PROCESS_STATE_CACHED_EMPTY",
        f"shell am get-standby-bucket {package}": "10",
        f"shell pidof {package}": "1234" if target else "",
        "shell cmd media_session volume --get --stream 3": "volume is 10 in range [0..25]",
        "shell settings get global mode_ringer": "2",
        "shell settings get global zen_mode": "0",
        f"shell dumpsys activity services {package}": "WakeWordService" if target else "",
        "shell dumpsys audio": "Audio routes:\n  mMainType=0x0\n  mBluetoothName=null",
    }
    if target:
        responses.update({
            "shell dumpsys battery": "AC powered: false\nUSB powered: true",
            "shell dumpsys power": "mWakefulness=Dozing",
            "shell cat /proc/uptime": "100.00 10.00",
            "shell cat /proc/sys/kernel/random/boot_id": "boot-1",
        })
    client.responses.update(responses)


def environment_state(alias: str, *, target: bool) -> dict:
    client = FakeAdb(alias)
    configure_environment(client, target=target)
    harness = runner.AcousticWakeReliabilityRunner(
        runner.RunKind.SMOKE,
        "s23u",
        "s21",
        client if not target else FakeAdb("source"),
        client if target else FakeAdb("target"),
        private_root=Path(tempfile.mkdtemp()),
    )
    return harness._snapshot_android_state(client, alias, target=target)


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
        if item["t"] == "STT_FINAL":
            item["d"] = {"normalized_transcript_sha256": "b" * 64}
    return events


def envelope(events: list[dict], lowest: int = 1, overflowed: bool = False) -> dict:
    return {"lowestSequence": lowest, "highestSequence": events[-1]["s"] if events else lowest,
            "overflowed": overflowed, "events": events}


def source_result(trial_id: str = "trial-1", fixture_id: str = "natural_wake") -> dict:
    result = load_json("source-result-valid.json")
    result["trial_id"] = trial_id
    result["fixture_id"] = fixture_id
    result["command_transcript_sha256"] = None
    return result


def cancelled_source_result(
    trial_id: str = "trial-cancelled",
    fixture_id: str = "natural_wake",
) -> dict:
    result = source_result(trial_id, fixture_id)
    result.update({
        "completion_status": "cancelled",
        "error_category": "operator_cancelled",
        "focus_result": "not_requested",
    })
    for field in (
        "fixture_sha256", "fixture_duration_ms", "prepare_monotonic_ms",
        "playback_start_monotonic_ms", "volume_before", "requested_volume",
        "applied_volume", "maximum_volume", "restored_volume",
        "output_route_before", "output_route_during",
    ):
        result.pop(field)
    return result

def preflight_manifest(**overrides) -> dict:
    manifest = {
        "schema_version": runner.PREFLIGHT_SCHEMA_VERSION,
        "matrix_id": runner.MATRIX_ID,
        "matrix_version": runner.MATRIX_VERSION,
        "source_helper_contract_version": runner.SOURCE_HELPER_CONTRACT_VERSION,
        "target_journal_contract_version": runner.JOURNAL_CONTRACT_VERSION,
        "fixture_set_id": "set-1",
        "fixture_hashes": {"natural_wake": "a" * 64},
        "command_transcript_hashes": {},
        "cue_policy_version": "cue-v1",
        "cue_policy_evidence_verified": True,
        "cue_audibility_evidence_verified": True,
        "source_environment_state": environment_state("s23u", target=False),
        "target_environment_state": environment_state("s21", target=True),
        "attempts": [{"attempt": 1, "approved": True}],
        "operator_approved": True,
    }
    manifest.update(overrides)
    canonical = json.dumps(manifest, sort_keys=True, separators=(",", ":"))
    manifest["manifest_sha256"] = hashlib.sha256(canonical.encode()).hexdigest()
    return manifest


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

    def test_adb_nonthrowing_execution_preserves_broadcast_output(self) -> None:
        completed = runner.subprocess.CompletedProcess(
            ["adb"], 7, stdout='Broadcast completed: result=0, data="completed"',
            stderr="receiver process exited",
        )
        client = runner.AdbClient("serial", runner=lambda *args, **kwargs: completed)
        self.assertIn("result=0", client.run("shell", "am", "broadcast", check=False))
        with self.assertRaises(runner.HarnessError):
            client.run("shell", "am", "broadcast")

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

    def test_cancel_tolerates_wait_that_completed_first(self) -> None:
        harness = make_runner()
        harness._active_wait_request_id = "wait-completed"
        with patch.object(
            harness,
            "_call_target_provider",
            return_value=(runner.TARGET_RESULT_ERROR, runner.TARGET_ERROR_UNKNOWN_REQUEST_ID),
        ) as provider_call:
            harness._cancel_active_wait()
        provider_call.assert_called_once_with(
            runner.TARGET_METHOD_CANCEL_WAIT,
            extras={runner.TARGET_EXTRA_REQUEST_ID: "wait-completed"},
            timeout=5.0,
        )

    def test_cancel_rejects_other_provider_errors(self) -> None:
        harness = make_runner()
        harness._active_wait_request_id = "wait-live"
        with patch.object(
            harness,
            "_call_target_provider",
            return_value=(runner.TARGET_RESULT_ERROR, "argument_error:bad_request"),
        ), self.assertRaisesRegex(runner.HarnessError, "cancellation failed"):
            harness._cancel_active_wait()


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
        self.assertIn("CUE_PLAYBACK_STARTED", all_types)
        self.assertIn("CUE_PLAYBACK_ERROR", all_types)
        self.assertEqual(len(all_types), 24)
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

    def test_source_contract_accepts_omitted_nullable_error_fields(self) -> None:
        result = source_result()
        result.pop("error_category")
        result.pop("playback_error_category")
        parsed = runner.parse_source_result(json.dumps(result))
        self.assertNotIn("error_category", parsed)

    def test_cleanup_contract_accepts_cancelled_partial_result(self) -> None:
        cancelled = cancelled_source_result()
        parsed = runner.parse_source_cleanup_result(json.dumps(cancelled))
        self.assertEqual(parsed["completion_status"], "cancelled")
        with self.assertRaisesRegex(runner.HarnessError, "completed source result missing"):
            runner.parse_source_result(json.dumps(cancelled))

        cancelled["cleanup_success"] = False
        with self.assertRaisesRegex(runner.HarnessError, "cleanup did not succeed"):
            runner.parse_source_cleanup_result(json.dumps(cancelled))

    def test_cleanup_contract_accepts_every_verified_terminal_status(self) -> None:
        terminal_cases = {
            "completed": (None, False, False, True),
            "cancelled": ("operator_cancelled", False, False, True),
            "timeout": ("playback_timeout", True, False, True),
            "failed": ("fixture_read_failed", False, False, True),
            "invalid": ("audio_state_unavailable", False, False, False),
            "rejected": ("overlap_rejected", False, True, False),
        }
        for status, (error, timed_out, overlap, has_events) in terminal_cases.items():
            for explicit_null in (False, True):
                with self.subTest(status=status, explicit_null=explicit_null):
                    result = source_result(f"trial-{status}")
                    result["completion_status"] = status
                    if error is None:
                        result.pop("error_category", None)
                    else:
                        result["error_category"] = error
                    result["timeout"] = timed_out
                    result["overlap_rejected"] = overlap
                    if explicit_null:
                        result["playback_error_category"] = None
                    else:
                        result.pop("playback_error_category", None)
                    if not has_events:
                        result["focus_result"] = "not_requested"
                        result["events"] = []
                    parsed = runner.parse_source_cleanup_result(json.dumps(result))
                    self.assertEqual(parsed["completion_status"], status)
                    if status != "completed":
                        with self.assertRaises(runner.HarnessError):
                            runner.parse_source_result(json.dumps(result))

    def test_cleanup_contract_rejects_inconsistent_status_and_cleanup_evidence(self) -> None:
        invalid_cases = []
        timed_out = source_result("trial-timeout")
        timed_out.update(completion_status="timeout", error_category="playback_timeout", timeout=False)
        invalid_cases.append(timed_out)
        failed = source_result("trial-failed")
        failed.update(completion_status="failed", error_category=None)
        invalid_cases.append(failed)
        rejected = source_result("trial-rejected")
        rejected.update(
            completion_status="rejected",
            error_category="overlap_rejected",
            overlap_rejected=False,
        )
        invalid_cases.append(rejected)
        cleanup_failed = source_result("trial-cleanup")
        cleanup_failed["cleanup_success"] = False
        invalid_cases.append(cleanup_failed)
        persistence_failed = source_result("trial-persistence")
        persistence_failed["evidence_persistence_failed"] = True
        invalid_cases.append(persistence_failed)
        completed_with_playback_error = source_result("trial-completed-playback-error")
        completed_with_playback_error["playback_error_category"] = "playback_failed"
        invalid_cases.append(completed_with_playback_error)
        cancelled_with_mismatch = cancelled_source_result()
        cancelled_with_mismatch["playback_error_category"] = "playback_failed"
        invalid_cases.append(cancelled_with_mismatch)
        cancelled_with_wrong_error = cancelled_source_result("trial-cancelled-error")
        cancelled_with_wrong_error["error_category"] = "playback_failed"
        invalid_cases.append(cancelled_with_wrong_error)
        failed_with_timeout = source_result("trial-failed-timeout")
        failed_with_timeout.update(
            completion_status="failed",
            error_category="playback_failed",
            timeout=True,
        )
        failed_with_timeout.pop("playback_error_category", None)
        invalid_cases.append(failed_with_timeout)
        restoration_failed = source_result("trial-restoration")
        restoration_failed["exact_restoration_verified"] = False
        invalid_cases.append(restoration_failed)
        malformed_event = source_result("trial-malformed-event")
        malformed_event["events"][0]["unexpected"] = "field"
        invalid_cases.append(malformed_event)
        cleanup_event_mismatch = source_result("trial-cleanup-event-mismatch")
        cleanup_event_mismatch["events"][-1]["cleanup_success"] = False
        invalid_cases.append(cleanup_event_mismatch)

        for result in invalid_cases:
            with self.subTest(trial_id=result["trial_id"]), self.assertRaises(runner.HarnessError):
                runner.parse_source_cleanup_result(json.dumps(result))

    def test_cleanup_contract_requires_requested_identities(self) -> None:
        result = cancelled_source_result()
        for expected in (
            {"expected_trial_id": "another-trial"},
            {"expected_fixture_id": "another-fixture"},
        ):
            with self.subTest(expected=expected), self.assertRaisesRegex(
                runner.HarnessError,
                "does not match request",
            ):
                runner.parse_source_cleanup_result(json.dumps(result), **expected)

    def test_cleanup_error_preserves_playback_error_but_remains_invalid(self) -> None:
        result = source_result("trial-cleanup-error")
        result.update(
            completion_status="invalid",
            error_category="volume_restoration_failed",
            playback_error_category="playback_timeout",
            timeout=True,
            cleanup_success=False,
            exact_restoration_verified=False,
        )
        result["events"][-1].update(
            cleanup_success=False,
            exact_restoration_verified=False,
            error_category="volume_restoration_failed",
        )
        with self.assertRaisesRegex(runner.HarnessError, "cleanup did not succeed"):
            runner.parse_source_cleanup_result(json.dumps(result))

    def test_source_invocation_recovers_persisted_result_after_process_exit(self) -> None:
        harness = make_runner()
        expected = source_result("trial-1")
        harness.source.responses["shell am broadcast"] = (
            'Broadcasting: Intent {...}\nBroadcast completed: result=0, data="completed"'
        )
        with patch.object(harness, "_read_source_result", return_value=expected) as read_result:
            self.assertEqual(harness._invoke_source("trial-1", "natural_wake", 7), expected)
        read_result.assert_called_once_with("trial-1", "natural_wake")
        self.assertFalse(harness.source.checks[-1])

        harness.source.responses["shell am broadcast"] = "Broadcasting: Intent {...}"
        recovered = source_result("trial-2")
        with patch.object(harness, "_read_source_result", return_value=recovered) as read_result:
            self.assertEqual(harness._invoke_source("trial-2", "natural_wake", 7), recovered)
        read_result.assert_called_once_with("trial-2", "natural_wake")

        with patch.object(
            harness, "_read_source_result",
            side_effect=runner.HarnessError("failed to read source result"),
        ), self.assertRaisesRegex(runner.HarnessError, "failed to read source result"):
            harness._invoke_source("trial-3", "natural_wake", 7)

    def test_source_cancellation_is_trial_scoped(self) -> None:
        harness = make_runner()
        harness._active_source_trial_id = "trial-1"
        harness._active_source_fixture_id = "natural_wake"
        harness.source.responses["shell am broadcast"] = (
            'Broadcast completed: result=0, data="cancelled"'
        )
        self.assertEqual(harness._cancel_active_source_playback(), [])
        command = " ".join(harness.source.commands[-1])
        self.assertIn(runner.SOURCE_CANCEL_ACTION, command)
        self.assertIn("trial-1", command)

    def test_cleanup_recovers_and_verifies_interrupted_source_result(self) -> None:
        harness = make_runner()
        harness._active_source_trial_id = "trial-interrupted"
        harness._active_source_fixture_id = "natural_wake"
        harness.source.responses["shell am broadcast"] = (
            'Broadcast completed: result=0, data="cancelled"'
        )
        recovered = cancelled_source_result("trial-interrupted")
        with patch.object(harness, "_read_source_result", return_value=recovered) as read_result:
            harness.cleanup()
        read_result.assert_called_once_with(
            "trial-interrupted",
            "natural_wake",
            require_completed=False,
        )
        self.assertTrue(harness.cleanup_verified)
        self.assertEqual(harness.source_results, [recovered])
        self.assertIsNone(harness._active_source_trial_id)
        self.assertIsNone(harness._active_source_fixture_id)

    def test_bluetooth_route_uses_current_state_not_dumpsys_history(self) -> None:
        harness = make_runner()
        harness.source.responses["shell dumpsys audio"] = """Audio routes:
  mMainType=0x0
  mBluetoothName=null

Events log: wired/A2DP/hearing aid device connection
BluetoothActiveDeviceChanged for A2DP, device update null -> XX:XX
setBluetoothActiveDevice active bt_a2dp routed
"""
        self.assertFalse(harness._has_active_bluetooth_route(harness.source))

    def test_bluetooth_route_reports_current_named_route(self) -> None:
        harness = make_runner()
        harness.source.responses["shell dumpsys audio"] = """Audio routes:
  mMainType=0x0
  mBluetoothName=Galaxy Buds
"""
        self.assertTrue(harness._has_active_bluetooth_route(harness.source))

    def test_bluetooth_route_fails_closed_when_current_state_is_missing(self) -> None:
        harness = make_runner()
        harness.source.responses["shell dumpsys audio"] = "Events log: A2DP active"
        with self.assertRaisesRegex(runner.HarnessError, "omitted current Audio routes state"):
            harness._has_active_bluetooth_route(harness.source)

    def test_preflight_mode_cleans_up_after_operator_interrupt(self) -> None:
        harness = Mock()
        harness.run_preflight.side_effect = KeyboardInterrupt
        harness.run_dir = Path("/private/preflight")
        harness.cleanup_verified = True
        args = SimpleNamespace(
            source_selector="source-serial",
            target_selector="target-serial",
            source="s23u",
            target="s21",
            fixture_dir=None,
            fixture_set_id=None,
            fixture_hashes=None,
            cue_policy_version=None,
        )
        with patch.object(runner, "AdbClient", side_effect=[Mock(), Mock()]), \
                patch.object(runner, "AcousticWakeReliabilityRunner", return_value=harness):
            self.assertEqual(runner.preflight_mode(args), 1)
        harness.cancel.assert_called_once_with()
        harness.cleanup.assert_called_once_with()
        harness.export_evidence.assert_called_once_with()

    def test_preflight_executes_source_before_wait_and_deepcopies_environment(self) -> None:
        harness = make_runner()
        harness.interactive = True
        fixture_sha256 = source_result()["fixture_sha256"]
        source_environment = environment_state("s23u", target=False)
        target_environment = environment_state("s21", target=True)
        target_events = []
        for target_event in complete_events(runner.TrialType.WAKE_ONLY):
            target_events.append(target_event)
            if target_event["t"] == "CUE_REQUESTED":
                break
        call_order: list[str] = []

        def invoke_source(trial_id: str, fixture_id: str, volume_index: int) -> dict:
            self.assertEqual(call_order, [])
            call_order.extend(("source-started", "source-completed"))
            return source_result(trial_id, fixture_id)

        def wait_for_events(**kwargs):
            self.assertEqual(call_order, ["source-started", "source-completed"])
            call_order.append(f"wait-{kwargs['event_type']}")
            return target_events, envelope(target_events)

        def provider_call(method, extras=None, timeout=15.0):
            if method == runner.TARGET_METHOD_GET_SEQUENCE:
                return runner.TARGET_RESULT_OK, "1"
            if method == runner.TARGET_METHOD_GET_SNAPSHOT:
                return runner.TARGET_RESULT_OK, json.dumps(envelope(target_events))
            self.fail(f"unexpected provider method {method}")

        source_identity = runner.DeviceIdentity(
            "s23u", "Samsung", "S23 Ultra", "16", "36", "source-build",
            "1.0", 1, "a" * 64,
        )
        target_identity = runner.DeviceIdentity(
            "s21", "Samsung", "S21", "16", "36", "target-build",
            "1.0", 1, "b" * 64,
        )
        build = runner.InstalledBuildIdentity(
            runner.DEFAULT_PACKAGE, "1.0", 1, "c" * 64,
        )
        with patch.object(runner, "device_identity", side_effect=(source_identity, target_identity)), \
                patch.object(runner, "installed_build_identity", side_effect=(build, build)), \
                patch.object(runner, "service_active", side_effect=lambda client, package: client is harness.target), \
                patch.object(harness, "_has_active_bluetooth_route", return_value=False), \
                patch.object(harness, "_read_fixture_manifest", return_value={"natural_wake": fixture_sha256}), \
                patch.object(harness, "_snapshot_source_state", return_value=source_environment), \
                patch.object(harness, "_snapshot_target_state", return_value=target_environment), \
                patch.object(harness, "_invoke_source", side_effect=invoke_source), \
                patch.object(harness, "_wait_for_target_events", side_effect=wait_for_events), \
                patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(harness, "_environment_failures", return_value=[]), \
                patch.object(harness, "_source_environment_failures", return_value=[]), \
                patch.object(harness, "_get_media_max_volume", return_value=25), \
                patch("builtins.input", side_effect=("1 metre, speakers aligned", "APPROVE")):
            manifest = harness.run_preflight("set-1", {"natural_wake": fixture_sha256})

        self.assertEqual(call_order, ["source-started", "source-completed", "wait-CUE_REQUESTED"])
        self.assertTrue(manifest["cue_audibility_evidence_verified"])
        self.assertEqual(harness.run_environment_before["source"], source_environment)
        self.assertIsNot(harness.run_environment_before["source"], source_environment)

    def test_command_wait_starts_only_after_wake_playback_completes(self) -> None:
        harness = make_runner()
        fixture_sha256 = source_result()["fixture_sha256"]
        harness.installed_fixture_hashes = {
            "natural_wake": fixture_sha256,
            "qwen_command": fixture_sha256,
        }
        harness.installed_command_transcript_hashes = {"qwen_command": "b" * 64}
        harness.preflight_approval = {
            "source_volume_index": 7,
            "cue_audibility_evidence_verified": True,
            "source_environment_state": {},
            "target_environment_state": {},
        }
        harness.cue_audibility_evidence_verified = True
        final_events = complete_events(runner.TrialType.WAKE_PLUS_COMMAND)
        final_envelope = envelope(final_events)
        boundary_envelope = envelope([
            event(3, "DETECTOR_GENERATION_STARTED", generation=4, session=0),
        ], lowest=3)
        # Snapshot observed immediately before command dispatch: the correlated
        # session is still open (no terminal event yet).
        pre_command_events = [
            item for item in final_events
            if item["t"] in {
                "DETECTOR_GENERATION_STARTED", "SILENCE_GATE_ENTERED",
                "VOICED_FRAME_AFTER_SILENCE", "STAGE2_RESUMED", "STAGE3_READY",
                "ACTIVATION_CANDIDATE", "VERIFIED_ACTIVATION",
                "WAKE_CALLBACK_INVOKED", "VOICE_SESSION_STARTED",
                "STT_START_REQUESTED", "STT_READY", "CUE_REQUESTED",
            }
        ]
        provider_snapshots = iter(
            (boundary_envelope, envelope(pre_command_events), final_envelope)
        )

        def provider_call(method, extras=None, timeout=15.0):
            if method == runner.TARGET_METHOD_GET_SEQUENCE:
                return runner.TARGET_RESULT_OK, "3"
            if method == runner.TARGET_METHOD_GET_SNAPSHOT:
                return runner.TARGET_RESULT_OK, json.dumps(next(provider_snapshots))
            self.fail(f"unexpected provider method {method}")

        call_order = []

        def invoke_wake_source(trial_id: str, fixture_id: str, volume_index: int) -> dict:
            self.assertEqual(call_order, [])
            call_order.extend(("wake-started", "wake-completed"))
            return source_result(trial_id, fixture_id)

        def invoke_command_source(**kwargs):
            self.assertEqual(
                call_order,
                ["wake-started", "wake-completed", "wait-STT_READY", "wait-CUE_REQUESTED"],
            )
            call_order.append("command-wait-started")
            command_events = [event(15, "COMMAND_ROUTING_RESULT")]
            command_result = source_result("trial-sequence-cmd", "qwen_command")
            command_result["command_transcript_sha256"] = "b" * 64
            return command_result, command_events, envelope(command_events, lowest=15)

        def target_wait(**kwargs):
            event_type = kwargs["event_type"]
            if event_type == "STT_READY":
                self.assertEqual(call_order, ["wake-started", "wake-completed"])
                call_order.append("wait-STT_READY")
            elif event_type == "CUE_REQUESTED":
                self.assertEqual(
                    call_order,
                    ["wake-started", "wake-completed", "wait-STT_READY"],
                )
                call_order.append("wait-CUE_REQUESTED")
            waited = event(
                {"STT_READY": 11, "CUE_REQUESTED": 12, "DETECTOR_REARMED": 18}[event_type],
                event_type,
            )
            return [waited], envelope([waited], lowest=waited["s"])

        with patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(harness, "_invoke_source", side_effect=invoke_wake_source), \
                patch.object(harness, "_invoke_command_source_with_armed_wait", side_effect=invoke_command_source), \
                patch.object(harness, "_wait_for_target_events", side_effect=target_wait), \
                patch.object(harness, "_snapshot_target_state", return_value={"reachable": True}), \
                patch.object(harness, "_snapshot_source_state", return_value={"reachable": True}), \
                patch.object(harness, "_environment_failures", return_value=[]), \
                patch.object(harness, "_source_environment_failures", return_value=[]), \
                patch.object(harness, "checkpoint"), \
                patch.object(runner.time, "sleep"):
            attempt = harness.run_trial(
                "trial-sequence",
                runner.MatrixSlot(idle_s=1, wake_only=False),
                "natural_wake",
                "qwen_command",
            )

        self.assertEqual(
            call_order,
            [
                "wake-started", "wake-completed", "wait-STT_READY",
                "wait-CUE_REQUESTED", "command-wait-started",
            ],
        )
        self.assertEqual(attempt.status, runner.AttemptStatus.PASSED, attempt.__dict__)

    def test_preflight_correlation_stops_at_cue_without_terminal(self) -> None:
        events = [
            item for item in complete_events(runner.TrialType.WAKE_ONLY)
            if item["t"] not in {"SESSION_COMPLETED", "DETECTOR_REARMED"}
        ]
        generation, session, path, failures = runner.correlate_event_path(
            events,
            runner.TrialType.WAKE_ONLY,
            require_terminal=False,
        )
        self.assertEqual((generation, session), (4, 9))
        self.assertIn("CUE_REQUESTED", path)
        self.assertEqual(failures, [])


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
            expected_command_transcript_sha256="b" * 64,
        )
        self.assertEqual(status, runner.AttemptStatus.PASSED)
        missing_final = copy.deepcopy(command)
        missing_final["events"] = [e for e in missing_final["events"] if e["t"] != "STT_FINAL"]
        missing_final["highestSequence"] = missing_final["events"][-1]["s"]
        status, classification, _, _ = runner.classify_attempt(
            valid_source, missing_final, runner.TrialType.WAKE_PLUS_COMMAND, None, None, True,
            expected_command_transcript_sha256="b" * 64,
        )
        self.assertEqual(classification, runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE)

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

    def test_command_result_requires_wake_generation_and_session(self) -> None:
        valid = event(4, "COMMAND_ROUTING_RESULT", generation=7, session=11)
        self.assertEqual(
            runner.require_correlated_event([valid], "COMMAND_ROUTING_RESULT", 7, 11),
            valid,
        )
        for generation, session in ((8, 11), (7, 12)):
            with self.subTest(generation=generation, session=session):
                with self.assertRaisesRegex(runner.HarnessError, "did not match"):
                    runner.require_correlated_event(
                        [valid], "COMMAND_ROUTING_RESULT", generation, session,
                    )


class WakeWordDiagClassificationTests(unittest.TestCase):
    """#1410 evidence integrity: an empty normal journal event list is a
    classifier miss when the correlated WakeWordDiag gate summaries prove
    Stage 3 evaluated the boundary generation with the classifier maximum
    below the candidate threshold (2026-08-09 S21 trial 002 semantics).
    Genuine no-evaluation/gate cases stay acoustic_or_gate_miss."""

    ENERGY = [{
        "generation_id": 4,
        "stage3_evals": 1187,
        "max_confidence": 0.0025328398,
        "max_confidence_offset_frames": 429,
        "low_verify_entered": False,
        "low_verify_accepted": False,
        "gated_probe_executions": 2,
        "episode_peak_rms": 4468.667,
        "max_window_peak_rms": 2984.8508,
        "max_window_mean_rms": 931.7775,
    }]

    def test_empty_events_with_stage3_proof_is_classifier_model_miss(self) -> None:
        """Trial-002 semantics: empty normal target events, correlated target
        generation known, gate episode present, stage3_evals > 0, maximum
        confidence below the candidate threshold, valid source stimulus,
        strong/non-zero energy available."""
        status, classification, invalid, failures = runner.classify_attempt(
            source_result(),
            envelope([]),
            runner.TrialType.WAKE_ONLY,
            boundary_generation=4,
            session_id=None,
            cue_audibility_verified=True,
            gate_episode_energy=self.ENERGY,
        )
        self.assertEqual(status, runner.AttemptStatus.FAILED)
        self.assertEqual(
            classification, runner.FailureClassification.CLASSIFIER_MODEL_MISS)
        self.assertIsNone(invalid)
        self.assertNotEqual(failures, ["no target events"])

    def test_empty_events_without_energy_stays_acoustic_or_gate_miss(self) -> None:
        """No WakeWordDiag evidence at all: genuine gate miss, unchanged."""
        status, classification, _, failures = runner.classify_attempt(
            source_result(),
            envelope([]),
            runner.TrialType.WAKE_ONLY,
            boundary_generation=4,
            session_id=None,
            cue_audibility_verified=True,
        )
        self.assertEqual(status, runner.AttemptStatus.FAILED)
        self.assertEqual(
            classification, runner.FailureClassification.ACOUSTIC_OR_GATE_MISS)
        self.assertEqual(failures, ["no target events"])

    def test_empty_events_without_stage3_evaluations_stays_gate_miss(self) -> None:
        """A gate episode with zero Stage-3 evaluations is never proof of a
        classifier miss: the classifier never ran."""
        energy = [dict(self.ENERGY[0], stage3_evals=0)]
        status, classification, _, _ = runner.classify_attempt(
            source_result(), envelope([]), runner.TrialType.WAKE_ONLY,
            boundary_generation=4, session_id=None, cue_audibility_verified=True,
            gate_episode_energy=energy,
        )
        self.assertEqual(
            classification, runner.FailureClassification.ACOUSTIC_OR_GATE_MISS)

    def test_empty_events_with_unrelated_generation_stays_gate_miss(self) -> None:
        """Energy correlated to a different detector generation is not proof
        for this trial; attribution is by boundary generation only."""
        energy = [dict(self.ENERGY[0], generation_id=5)]
        status, classification, _, _ = runner.classify_attempt(
            source_result(), envelope([]), runner.TrialType.WAKE_ONLY,
            boundary_generation=4, session_id=None, cue_audibility_verified=True,
            gate_episode_energy=energy,
        )
        self.assertEqual(
            classification, runner.FailureClassification.ACOUSTIC_OR_GATE_MISS)

    def test_empty_events_with_threshold_crossing_stays_gate_miss(self) -> None:
        """A classifier maximum at or above the candidate threshold is not a
        below-threshold miss; without journal events the failure is not
        upgraded."""
        energy = [dict(
            self.ENERGY[0], max_confidence=runner.WAKE_CANDIDATE_THRESHOLD)]
        status, classification, _, _ = runner.classify_attempt(
            source_result(), envelope([]), runner.TrialType.WAKE_ONLY,
            boundary_generation=4, session_id=None, cue_audibility_verified=True,
            gate_episode_energy=energy,
        )
        self.assertEqual(
            classification, runner.FailureClassification.ACOUSTIC_OR_GATE_MISS)

    def test_checkpoint_load_reclassifies_proven_stage3_miss(self) -> None:
        """Offline regeneration corrects a recorded acoustic_or_gate_miss
        when the preserved evidence proves the Stage-3 below-threshold miss,
        and leaves the genuine gate miss untouched."""
        private_root = Path(tempfile.mkdtemp())
        slot = runner.MatrixSlot(idle_s=10, wake_only=True, ordinal=2)
        slot_gate = runner.MatrixSlot(idle_s=10, wake_only=True, ordinal=3)
        producer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.DIAGNOSTIC, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        producer.attempts = [
            runner.MatrixAttempt(
                "trial-002", slot, 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.ACOUSTIC_OR_GATE_MISS,
                failures=["no target events"],
                target_timing={
                    "events": [],
                    "boundary_generation": 4,
                    "gate_episode_energy": self.ENERGY,
                },
            ),
            runner.MatrixAttempt(
                "trial-008", slot_gate, 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.ACOUSTIC_OR_GATE_MISS,
                failures=["no target events"],
                target_timing={
                    "events": [],
                    "boundary_generation": 4,
                    "gate_episode_energy": [dict(self.ENERGY[0], stage3_evals=0)],
                },
            ),
        ]
        producer.checkpoint()

        consumer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.DIAGNOSTIC, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        consumer.load_checkpoint(producer.run_id)
        by_id = {attempt.trial_id: attempt for attempt in consumer.attempts}
        self.assertEqual(
            by_id["trial-002"].classification,
            runner.FailureClassification.CLASSIFIER_MODEL_MISS,
        )
        self.assertIn(
            "stage-3 evaluation", " ".join(by_id["trial-002"].failures))
        self.assertEqual(
            by_id["trial-008"].classification,
            runner.FailureClassification.ACOUSTIC_OR_GATE_MISS,
        )
        self.assertEqual(by_id["trial-008"].failures, ["no target events"])


class MatrixAndEnvironmentTests(unittest.TestCase):
    def test_positions_are_independent_with_ordinals(self) -> None:
        slots = runner.matrix_slots_for_target("s21")
        ten_second_wake = [s for s in slots if s.idle_s == 10 and s.wake_only]
        ten_second_command = [s for s in slots if s.idle_s == 10 and not s.wake_only]
        self.assertEqual([s.ordinal for s in ten_second_wake], [1, 2, 3, 4, 5])
        self.assertEqual([s.ordinal for s in ten_second_command], [1, 2, 3])
        self.assertEqual(len({s.position_id for s in slots}), len(slots))

    def test_s21_matrix_defines_27_valid_positions(self) -> None:
        """#1410 frozen S21 matrix: 27 independent valid positions."""
        slots = runner.matrix_slots_for_target("s21")
        self.assertEqual(len(slots), 27)
        by_idle: dict[int, list[int]] = {}
        for slot in slots:
            by_idle.setdefault(slot.idle_s, [0, 0])
            by_idle[slot.idle_s][0 if slot.wake_only else 1] += 1
        self.assertEqual(by_idle, {
            10: [5, 3],
            30: [5, 0],
            120: [5, 3],
            900: [2, 0],
            1800: [2, 2],
        })

    def test_s23u_matrix_defines_20_valid_positions(self) -> None:
        """#1410 re-baselined S23U matrix: 20 independent valid positions.

        The original 8 comparison positions are retained (3+2 wake-only and
        2+1 wake-plus-command); the additional 12 are 2-minute wake-only
        trials so the >=95% target is measurable directly.
        """
        slots = runner.matrix_slots_for_target("s23u")
        self.assertEqual(len(slots), 20)
        by_idle: dict[int, list[int]] = {}
        for slot in slots:
            by_idle.setdefault(slot.idle_s, [0, 0])
            by_idle[slot.idle_s][0 if slot.wake_only else 1] += 1
        self.assertEqual(by_idle, {
            120: [15, 2],
            1800: [2, 1],
        })
        self.assertEqual(len({s.position_id for s in slots}), len(slots))

    def test_wake_activated_is_independent_of_command_outcome(self) -> None:
        """The wake-success numerator must not absorb command-path failures.

        A wake-plus-command case whose wake activated but whose command
        failed reports wake_activated=True so the intended-wake numerator
        stays independent of the command result; wake misses and invalid
        attempts are never counted as wake successes.
        """
        slots = runner.matrix_slots_for_target("s21")
        full_path = complete_events(runner.TrialType.WAKE_ONLY)
        gate_only = [
            event(1, "STAGE3_READY"),
            event(2, "SILENCE_GATE_ENTERED"),
            event(3, "SILENCE_GATE_ENTERED"),
        ]
        attempts = [
            runner.MatrixAttempt(
                "wake-pass", slots[0], 1, runner.AttemptStatus.PASSED,
                target_timing={"events": full_path},
            ),
            runner.MatrixAttempt(
                "command-fail", slots[1], 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                target_timing={"events": full_path},
            ),
            runner.MatrixAttempt(
                "wake-miss", slots[2], 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.CLASSIFIER_MODEL_MISS,
                target_timing={"events": gate_only},
            ),
            runner.MatrixAttempt(
                "invalid", slots[3], 1, runner.AttemptStatus.INVALID,
                invalid_reason=runner.InvalidReason.SOURCE_STIMULUS_FAILURE,
                target_timing={"events": full_path},
            ),
        ]
        self.assertTrue(runner.wake_activated_for_attempt(attempts[0]))
        self.assertTrue(runner.wake_activated_for_attempt(attempts[1]))
        self.assertFalse(runner.wake_activated_for_attempt(attempts[2]))
        self.assertIsNone(runner.wake_activated_for_attempt(attempts[3]))

        manifest = runner.RunManifest(
            "run-1", runner.RunKind.REGRESSION, runner.GateMode.RELEASE,
            runner.MATRIX_ID, runner.MATRIX_VERSION, runner.utc_now(),
            "s23u", "s21", "set-1", {"natural_wake": "a" * 64}, "cue-v1", None,
        )
        target = runner.DeviceIdentity("s21", "samsung", "SM-G991B", "15", "35", "fp", "pkg", 1)
        source = runner.DeviceIdentity("s23u", "samsung", "SM-S918B", "15", "35", "fp", "pkg", 1)
        evidence = runner.render_evidence(
            manifest, target, source, "1.0.0", attempts, None, True,
            "BUILT_IN_SPEAKER",
        )
        cases = {case["trial_id"]: case["wake_activated"] for case in evidence["cases"]}
        self.assertEqual(cases, {
            "wake-pass": True,
            "command-fail": True,
            "wake-miss": False,
            "invalid": None,
        })
        self.assertEqual(evidence["wake_reliability"]["wake_success"], {
            "numerator": 2,
            "denominator": 3,
            "percent": round(2 / 3, 4),
        })

    def test_spontaneous_scan_same_generation_before_playback_is_candidate(self) -> None:
        """A spontaneous wake in the trial's own detector generation, before
        the scheduled source stimulus, is NOT hidden by generation
        attribution: only the exact correlated-path sequences are shielded."""
        root = Path(tempfile.mkdtemp())
        target_dir = root / "trials" / "trial-1" / "target"
        target_dir.mkdir(parents=True)
        boundary = envelope([
            event(1, "VERIFIED_ACTIVATION", generation=2, session=3),  # pre-run
            event(2, "WAKE_CALLBACK_INVOKED", generation=2, session=3),
            event(3, "VOICE_SESSION_STARTED", generation=2, session=3),
            event(4, "STAGE3_READY", generation=3),
            event(5, "SILENCE_GATE_ENTERED", generation=3),
        ])
        (target_dir / "boundary-snapshot.json").write_text(json.dumps(boundary))
        final = envelope([
            event(4, "STAGE3_READY", generation=3),
            event(6, "SILENCE_GATE_ENTERED", generation=3),
            # Spontaneous activation: same generation 3, before the stimulus.
            event(7, "VERIFIED_ACTIVATION", generation=3, session=5),
            event(8, "WAKE_CALLBACK_INVOKED", generation=3, session=5),
            event(9, "VOICE_SESSION_STARTED", generation=3, session=5),
            # Correlated stimulus response (the trial's intended window).
            event(10, "ACTIVATION_CANDIDATE", generation=3),
            event(11, "VERIFIED_ACTIVATION", generation=3, session=9),
            event(12, "WAKE_CALLBACK_INVOKED", generation=3, session=9),
            event(13, "VOICE_SESSION_STARTED", generation=3, session=9),
            # Uncorrelated low candidate in the same generation.
            event(14, "ACTIVATION_CANDIDATE", generation=3),
        ])
        (target_dir / "final-snapshot.json").write_text(json.dumps(final))
        slot = runner.matrix_slots_for_target("s21")[0]
        attempt = runner.MatrixAttempt(
            "trial-1", slot, 1, runner.AttemptStatus.PASSED,
            target_timing={
                "generation_id": 3,
                "boundary_sequence": 5,
                "events": [
                    event(10, "ACTIVATION_CANDIDATE", generation=3),
                    event(11, "VERIFIED_ACTIVATION", generation=3, session=9),
                    event(12, "WAKE_CALLBACK_INVOKED", generation=3, session=9),
                    event(13, "VOICE_SESSION_STARTED", generation=3, session=9),
                ],
            },
        )
        result = runner.scan_spontaneous_activations(root, [attempt])
        self.assertEqual(result["coverage"], {
            "snapshots_scanned": 2,
            "overflowed_snapshots": 0,
        })
        self.assertEqual(result["activation_candidates"], 1)
        self.assertEqual(result["verified"], 1)
        self.assertEqual(result["callbacks"], 1)
        self.assertEqual(result["sessions"], 1)
        self.assertEqual(result["events"], [{
            "generation": 3,
            "session": 5,
            "sequence": 7,
            "wall_clock_ms": 70,
        }])

    def test_spontaneous_scan_correlated_path_is_attributed(self) -> None:
        """Activation events inside the intended stimulus/correlation window
        (exact sequences of the trial's correlated path) are trial-attributed."""
        root = Path(tempfile.mkdtemp())
        target_dir = root / "trials" / "trial-1" / "target"
        target_dir.mkdir(parents=True)
        final = envelope([
            event(4, "STAGE3_READY", generation=3),
            event(10, "ACTIVATION_CANDIDATE", generation=3),
            event(11, "VERIFIED_ACTIVATION", generation=3, session=9),
            event(12, "WAKE_CALLBACK_INVOKED", generation=3, session=9),
            event(13, "VOICE_SESSION_STARTED", generation=3, session=9),
        ])
        (target_dir / "final-snapshot.json").write_text(json.dumps(final))
        slot = runner.matrix_slots_for_target("s21")[0]
        attempt = runner.MatrixAttempt(
            "trial-1", slot, 1, runner.AttemptStatus.PASSED,
            target_timing={
                "generation_id": 3,
                "boundary_sequence": 5,
                "events": [
                    event(10, "ACTIVATION_CANDIDATE", generation=3),
                    event(11, "VERIFIED_ACTIVATION", generation=3, session=9),
                    event(12, "WAKE_CALLBACK_INVOKED", generation=3, session=9),
                    event(13, "VOICE_SESSION_STARTED", generation=3, session=9),
                ],
            },
        )
        result = runner.scan_spontaneous_activations(root, [attempt])
        self.assertEqual(result["activation_candidates"], 0)
        self.assertEqual(result["verified"], 0)
        self.assertEqual(result["callbacks"], 0)
        self.assertEqual(result["sessions"], 0)
        self.assertEqual(result["events"], [])

    def test_spontaneous_scan_unrelated_generation_is_candidate(self) -> None:
        """Activation-path events in a generation unrelated to any trial are
        spontaneous candidates."""
        root = Path(tempfile.mkdtemp())
        target_dir = root / "trials" / "trial-1" / "target"
        target_dir.mkdir(parents=True)
        final = envelope([
            event(10, "ACTIVATION_CANDIDATE", generation=3),
            event(11, "VERIFIED_ACTIVATION", generation=3, session=9),
            event(12, "WAKE_CALLBACK_INVOKED", generation=3, session=9),
            event(13, "VOICE_SESSION_STARTED", generation=3, session=9),
            event(20, "ACTIVATION_CANDIDATE", generation=8),
            event(21, "VERIFIED_ACTIVATION", generation=8, session=12),
            event(22, "WAKE_CALLBACK_INVOKED", generation=8, session=12),
            event(23, "VOICE_SESSION_STARTED", generation=8, session=12),
        ])
        (target_dir / "final-snapshot.json").write_text(json.dumps(final))
        slot = runner.matrix_slots_for_target("s21")[0]
        attempt = runner.MatrixAttempt(
            "trial-1", slot, 1, runner.AttemptStatus.PASSED,
            target_timing={
                "generation_id": 3,
                "boundary_sequence": 5,
                "events": [
                    event(10, "ACTIVATION_CANDIDATE", generation=3),
                    event(11, "VERIFIED_ACTIVATION", generation=3, session=9),
                    event(12, "WAKE_CALLBACK_INVOKED", generation=3, session=9),
                    event(13, "VOICE_SESSION_STARTED", generation=3, session=9),
                ],
            },
        )
        result = runner.scan_spontaneous_activations(root, [attempt])
        self.assertEqual(result["activation_candidates"], 1)
        self.assertEqual(result["verified"], 1)
        self.assertEqual(result["callbacks"], 1)
        self.assertEqual(result["sessions"], 1)
        self.assertEqual(result["events"], [{
            "generation": 8,
            "session": 12,
            "sequence": 21,
            "wall_clock_ms": 210,
        }])


    def test_spontaneous_scan_deduplicates_across_snapshots(self) -> None:
        """The same activation persisted in overlapping snapshots counts once."""
        root = Path(tempfile.mkdtemp())
        target_dir = root / "trials" / "trial-1" / "target"
        target_dir.mkdir(parents=True)
        events = [
            event(4, "STAGE3_READY", generation=3),
            event(6, "VERIFIED_ACTIVATION", generation=7, session=12),
            event(7, "WAKE_CALLBACK_INVOKED", generation=7, session=12),
            event(8, "VOICE_SESSION_STARTED", generation=7, session=12),
        ]
        for name in ("boundary-snapshot.json", "final-snapshot.json"):
            (target_dir / name).write_text(json.dumps(envelope(list(events))))
        slot = runner.matrix_slots_for_target("s21")[0]
        attempt = runner.MatrixAttempt(
            "trial-1", slot, 1, runner.AttemptStatus.PASSED,
            target_timing={"generation_id": 3, "boundary_sequence": 5},
        )
        result = runner.scan_spontaneous_activations(root, [attempt])
        self.assertEqual(result["verified"], 1)
        self.assertEqual(result["callbacks"], 1)
        self.assertEqual(result["sessions"], 1)
        self.assertEqual(len(result["events"]), 1)
        self.assertEqual(result["coverage"]["snapshots_scanned"], 2)

    def test_spontaneous_scan_reports_overflow_and_empty_dirs(self) -> None:
        """Evicted-journal coverage is disclosed and empty runs yield zeros."""
        empty = runner.scan_spontaneous_activations(Path(tempfile.mkdtemp()), [])
        self.assertEqual(empty["coverage"]["snapshots_scanned"], 0)
        self.assertEqual(empty["verified"], 0)
        self.assertEqual(empty["events"], [])
        root = Path(tempfile.mkdtemp())
        target_dir = root / "trials" / "trial-1" / "target"
        target_dir.mkdir(parents=True)
        (target_dir / "final-snapshot.json").write_text(json.dumps(
            envelope([event(6, "VERIFIED_ACTIVATION", generation=7, session=12)],
                     overflowed=True)
        ))
        slot = runner.matrix_slots_for_target("s21")[0]
        attempt = runner.MatrixAttempt(
            "trial-1", slot, 1, runner.AttemptStatus.PASSED,
            target_timing={"generation_id": 3, "boundary_sequence": 5},
        )
        result = runner.scan_spontaneous_activations(root, [attempt])
        self.assertEqual(result["coverage"]["overflowed_snapshots"], 1)
        self.assertEqual(result["verified"], 1)

    def test_new_evidence_fields_validate_against_schema(self) -> None:
        """wake_activated, wake_success and spontaneous_activations are
        schema-valid and survive the sanitised summary write."""
        slots = runner.matrix_slots_for_target("s21")
        attempts = [
            runner.MatrixAttempt(
                "trial-1", slots[0], 1, runner.AttemptStatus.PASSED,
                target_timing={"events": complete_events(runner.TrialType.WAKE_ONLY)},
            ),
            runner.MatrixAttempt(
                "trial-2", slots[1], 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.CLASSIFIER_MODEL_MISS,
                target_timing={"events": [
                    event(1, "STAGE3_READY"),
                    event(2, "SILENCE_GATE_ENTERED"),
                ]},
            ),
        ]
        manifest = runner.RunManifest(
            "run-1", runner.RunKind.REGRESSION, runner.GateMode.RELEASE,
            runner.MATRIX_ID, runner.MATRIX_VERSION, runner.utc_now(),
            "s23u", "s21", "set-1", {"natural_wake": "a" * 64}, "cue-v1", None,
        )
        target = runner.DeviceIdentity("s21", "samsung", "SM-G991B", "15", "35", "fp", "pkg", 1)
        source = runner.DeviceIdentity("s23u", "samsung", "SM-S918B", "15", "35", "fp", "pkg", 1)
        evidence = runner.render_evidence(
            manifest, target, source, "1.0.0", attempts, None, True,
            "BUILT_IN_SPEAKER",
        )
        output = Path(tempfile.mkdtemp())
        runner.write_sanitized_summary(output, evidence, private_run_dir=output)
        self.assertTrue((output / "evidence.json").is_file())

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

    # ── #1410 v1.0 release gate semantics ──────────────────────────────

    def _gate_ready_harness(self, target_alias: str) -> runner.AcousticWakeReliabilityRunner:
        harness = make_runner(runner.RunKind.REGRESSION)
        harness.target_alias = target_alias
        source_alias = "s23u" if target_alias == "s21" else "s21"
        harness.source_alias = source_alias
        expected = runner.EXPECTED_DEVICES[target_alias]
        harness.target_identity = runner.DeviceIdentity(
            target_alias, "samsung", expected["model"], "15", "35",
            "fingerprint", "pkg", 1,
        )
        source_expected = runner.EXPECTED_DEVICES[source_alias]
        harness.source_identity = runner.DeviceIdentity(
            source_alias, "samsung", source_expected["model"], "15", "35",
            "fingerprint", "pkg", 1,
        )
        harness.cleanup_verified = True
        harness.cue_audibility_evidence_verified = True
        harness.cue_policy_evidence_verified = True
        harness.preflight_manifest_hash = "a" * 64
        harness.manifest = runner.RunManifest(
            "run-1", runner.RunKind.REGRESSION, runner.GateMode.RELEASE,
            runner.MATRIX_ID, runner.MATRIX_VERSION, runner.utc_now(),
            source_alias, target_alias, "set-1",
            {"natural_wake": "a" * 64}, "cue-v1", "a" * 64,
            cue_policy_evidence_verified=True,
            cue_audibility_evidence_verified=True,
        )
        harness.preflight_approval = {
            "operator_approved": True,
            "cue_audibility_evidence_verified": True,
            "fixture_set_id": "set-1",
            "fixture_hashes": {"natural_wake": "a" * 64},
        }
        return harness

    def _matrix_attempts(
        self,
        target_alias: str,
        passed: int,
        misses: int,
        blocking: list[tuple[str, runner.FailureClassification]] | None = None,
    ) -> list[runner.MatrixAttempt]:
        """Build exactly one valid attempt per required matrix position."""
        slots = runner.matrix_slots_for_target(target_alias)
        full_path = complete_events(runner.TrialType.WAKE_ONLY)
        gate_only = [
            event(1, "STAGE3_READY"),
            event(2, "SILENCE_GATE_ENTERED"),
        ]
        attempts: list[runner.MatrixAttempt] = []
        next_slot = 0
        for index in range(passed):
            attempts.append(runner.MatrixAttempt(
                f"pass-{index + 1}", slots[next_slot], 1,
                runner.AttemptStatus.PASSED,
                target_timing={"events": full_path},
            ))
            next_slot += 1
        for index in range(misses):
            attempts.append(runner.MatrixAttempt(
                f"miss-{index + 1}", slots[next_slot], 1,
                runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.CLASSIFIER_MODEL_MISS,
                target_timing={"events": gate_only},
            ))
            next_slot += 1
        for label, classification in (blocking or []):
            attempts.append(runner.MatrixAttempt(
                f"block-{label}", slots[next_slot], 1,
                runner.AttemptStatus.FAILED,
                classification=classification,
                target_timing={"events": full_path},
            ))
            next_slot += 1
        return attempts

    def test_release_gate_s21_22_of_27_with_permitted_misses_passes(self) -> None:
        """S21: 22 wake successes + 5 classifier misses clears the gate when
        every other condition holds."""
        harness = self._gate_ready_harness("s21")
        harness.attempts = self._matrix_attempts("s21", passed=22, misses=5)
        with patch.dict("os.environ", {"GIT_COMMIT": "a" * 40}, clear=False):
            self.assertTrue(harness.is_matrix_complete())
            self.assertTrue(harness.release_gate_success())

    def test_release_gate_s21_21_of_27_fails(self) -> None:
        """S21: 21/27 is below the >=22/27 threshold and fails the gate."""
        harness = self._gate_ready_harness("s21")
        harness.attempts = self._matrix_attempts("s21", passed=21, misses=6)
        with patch.dict("os.environ", {"GIT_COMMIT": "a" * 40}, clear=False):
            self.assertTrue(harness.is_matrix_complete())
            self.assertFalse(harness.release_gate_success())

    def test_release_gate_s23u_19_of_20_passes(self) -> None:
        """S23U: 19 wake successes + 1 classifier miss clears the gate."""
        harness = self._gate_ready_harness("s23u")
        harness.attempts = self._matrix_attempts("s23u", passed=19, misses=1)
        with patch.dict("os.environ", {"GIT_COMMIT": "a" * 40}, clear=False):
            self.assertTrue(harness.is_matrix_complete())
            self.assertTrue(harness.release_gate_success())

    def test_release_gate_s23u_18_of_20_fails(self) -> None:
        """S23U: 18/20 is below the >=19/20 threshold and fails the gate."""
        harness = self._gate_ready_harness("s23u")
        harness.attempts = self._matrix_attempts("s23u", passed=18, misses=2)
        with patch.dict("os.environ", {"GIT_COMMIT": "a" * 40}, clear=False):
            self.assertTrue(harness.is_matrix_complete())
            self.assertFalse(harness.release_gate_success())

    def test_release_gate_command_failure_counts_wake_but_blocks_gate(self) -> None:
        """A wake-plus-command trial whose wake activated but whose command
        failed stays a wake success in the numerator and still fails the
        overall gate as a valid product failure."""
        harness = self._gate_ready_harness("s21")
        harness.attempts = self._matrix_attempts(
            "s21", passed=26, misses=0,
            blocking=[("command", runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE)],
        )
        with patch.dict("os.environ", {"GIT_COMMIT": "a" * 40}, clear=False):
            self.assertTrue(harness.is_matrix_complete())
            wake_results = [
                runner.wake_activated_for_attempt(attempt)
                for attempt in harness.attempts
            ]
            self.assertEqual(sum(1 for value in wake_results if value is True), 27)
            self.assertFalse(harness.release_gate_success())

    def test_release_gate_unclassified_failure_blocks_gate(self) -> None:
        """A threshold-satisfying matrix with one unclassified valid failure
        fails the gate (zero unclassified outcomes is mandatory)."""
        harness = self._gate_ready_harness("s21")
        harness.attempts = self._matrix_attempts(
            "s21", passed=22, misses=4,
            blocking=[("unclassified", runner.FailureClassification.UNCLASSIFIED)],
        )
        with patch.dict("os.environ", {"GIT_COMMIT": "a" * 40}, clear=False):
            self.assertTrue(harness.is_matrix_complete())
            self.assertFalse(harness.release_gate_success())

    def test_release_gate_spontaneous_activation_blocks_gate(self) -> None:
        """A threshold-satisfying matrix with a spontaneous verified
        activation fails the gate (zero false activations is mandatory)."""
        harness = self._gate_ready_harness("s21")
        harness.attempts = self._matrix_attempts("s21", passed=22, misses=5)
        target_dir = harness.run_dir / "trials" / "trial-1" / "target"
        target_dir.mkdir(parents=True)
        (target_dir / "final-snapshot.json").write_text(json.dumps(envelope([
            event(999, "VERIFIED_ACTIVATION", generation=77, session=40),
            event(1000, "WAKE_CALLBACK_INVOKED", generation=77, session=40),
            event(1001, "VOICE_SESSION_STARTED", generation=77, session=40),
        ])))
        with patch.dict("os.environ", {"GIT_COMMIT": "a" * 40}, clear=False):
            self.assertTrue(harness.is_matrix_complete())
            self.assertFalse(harness.release_gate_success())

    def test_environment_boot_change_and_uptime_regression_invalidate(self) -> None:
        failures = runner.AcousticWakeReliabilityRunner._environment_failures(
            {"reachable": True, "boot_id": "a", "uptime_seconds": 20.0},
            {"reachable": True, "boot_id": "b", "uptime_seconds": 19.0},
            {},
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


    def test_sensor_privacy_dump_defaults_absent_sensor_state_to_disabled(self) -> None:
        parsed = runner.AcousticWakeReliabilityRunner._parse_sensor_privacy_dump(
            "SENSOR PRIVACY MANAGER STATE (dumpsys sensor_privacy)\n",
            0,
        )
        self.assertEqual(parsed, {"microphone": "disabled", "camera": "disabled"})

    def test_sensor_privacy_dump_aggregates_enabled_toggle_for_current_user(self) -> None:
        parsed = runner.AcousticWakeReliabilityRunner._parse_sensor_privacy_dump(
            "\n".join((
                "SENSOR PRIVACY MANAGER STATE (dumpsys sensor_privacy)",
                "user_id=0", "sensor=1", "toggle_type=1", "state_type=2",
                "toggle_type=2", "state_type=1",
                "sensor=2", "toggle_type=1", "state_type=2",
                "user_id=10", "sensor=2", "toggle_type=1", "state_type=1",
            )),
            0,
        )
        self.assertEqual(parsed, {"microphone": "enabled", "camera": "disabled"})

    def test_sensor_privacy_dump_rejects_unknown_state_type(self) -> None:
        with self.assertRaisesRegex(runner.HarnessError, "state type: 7"):
            runner.AcousticWakeReliabilityRunner._parse_sensor_privacy_dump(
                "SENSOR PRIVACY MANAGER STATE (dumpsys sensor_privacy)\nuser_id=0\nsensor=1\nstate_type=7\n",
                0,
            )

    def test_screen_state_accepts_asleep_and_dozing_as_off(self) -> None:
        self.assertTrue(runner.AcousticWakeReliabilityRunner._parse_screen_off("mWakefulness=Asleep"))
        self.assertTrue(runner.AcousticWakeReliabilityRunner._parse_screen_off("mWakefulness=Dozing"))

    def test_screen_state_reports_awake_as_on(self) -> None:
        self.assertFalse(runner.AcousticWakeReliabilityRunner._parse_screen_off("mWakefulness=Awake"))

    def test_screen_state_rejects_missing_or_unknown_wakefulness(self) -> None:
        with self.assertRaisesRegex(runner.HarnessError, "omitted mWakefulness"):
            runner.AcousticWakeReliabilityRunner._parse_screen_off("mScreenOn=false")
        with self.assertRaisesRegex(runner.HarnessError, "unrecognised Android wakefulness"):
            runner.AcousticWakeReliabilityRunner._parse_screen_off("mWakefulness=Dreaming")

    def test_exact_environment_capture_reads_privacy_process_power_and_audio(self) -> None:
        harness = make_runner()
        configure_environment(harness.source, target=False)
        configure_environment(harness.target, target=True)
        source = harness._snapshot_source_state()
        target = harness._snapshot_target_state()
        self.assertEqual(source["sensor_privacy"], {"microphone": "disabled", "camera": "disabled"})
        self.assertEqual(source["package_uid"], 10123)
        self.assertEqual(source["media_volume"], 10)
        self.assertFalse(source["service_active"])
        self.assertTrue(target["service_active"])
        self.assertTrue(target["screen_off"])
        self.assertTrue(target["charging"])
        self.assertEqual(target["boot_id"], "boot-1")
        self.assertEqual(target["capture_errors"], [])

    def test_environment_capture_errors_fail_closed(self) -> None:
        harness = make_runner()
        snapshot = harness._snapshot_target_state()
        self.assertTrue(snapshot["capture_errors"])
        failures = harness._environment_failures(snapshot, snapshot, snapshot)
        self.assertTrue(any("capture failed" in failure for failure in failures))

    def test_cleanup_detects_environment_drift(self) -> None:
        harness = make_runner()
        configure_environment(harness.source, target=False)
        configure_environment(harness.target, target=True)
        harness.run_environment_before = {
            "source": harness._snapshot_source_state(),
            "target": harness._snapshot_target_state(),
        }
        harness.source.responses["shell settings get global mode_ringer"] = "1"
        harness.cleanup()
        self.assertFalse(harness.cleanup_verified)
        self.assertTrue(any("source ringer mode changed" in failure for failure in harness.cleanup_failures))

class EvidenceAndModeTests(unittest.TestCase):
    def test_finalize_evidence_captures_cleanup_before_export(self) -> None:
        order: list[str] = []
        harness = Mock()
        harness.cleanup.side_effect = lambda: order.append("cleanup")
        harness.checkpoint.side_effect = lambda: order.append("checkpoint")
        harness.export_evidence.side_effect = lambda: order.append("export") or {"ok": True}

        evidence = runner.finalize_evidence(harness)

        # #1409 review 4840085897: the final checkpoint is written only after
        # cleanup completes so later offline re-renders retain final state.
        self.assertEqual(order, ["cleanup", "checkpoint", "export"])
        self.assertEqual(evidence, {"ok": True})

    def _finalize_harness(self, private_root: Path, *,
                          restoration_failures: list[str] | None = None):
        producer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.REGRESSION, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        manifest = preflight_manifest(
            source_identity={
                "alias": "s23u", "manufacturer": "samsung",
                "model": "SM-S918B", "android_release": "15",
                "android_api": "35", "package_version": "1.0",
                "package_version_code": 1,
                "build_fingerprint_sha256": "a" * 64,
                "device_id_sha256": "b" * 64,
            },
            target_identity={
                "alias": "s21", "manufacturer": "samsung",
                "model": "SM-G991B", "android_release": "15",
                "android_api": "35", "package_version": "1.0",
                "package_version_code": 1,
                "build_fingerprint_sha256": "c" * 64,
                "device_id_sha256": "d" * 64,
            },
        )
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "preflight.json"
            path.write_text(json.dumps(manifest))
            producer.load_preflight_manifest(path)
        producer.run_environment_before = {
            "source": {"reachable": True, "alias": "s23u", "media_volume": 12},
            "target": {"reachable": True, "alias": "s21", "media_volume": 10},
        }
        producer.attempts = [
            runner.MatrixAttempt(
                "trial-001",
                runner.MatrixSlot(idle_s=10, wake_only=True, ordinal=1),
                1, runner.AttemptStatus.PASSED,
            ),
        ]
        failures = restoration_failures if restoration_failures is not None else []
        with patch.object(producer, "_cancel_active_wait"), \
                patch.object(producer, "_cancel_active_source_playback", return_value=[]), \
                patch.object(producer, "_recover_active_source_result", return_value=[]), \
                patch.object(producer, "_verify_source_restoration", return_value=failures), \
                patch.object(producer, "_snapshot_source_state",
                             return_value={"reachable": True, "alias": "s23u",
                                           "media_volume": 12}), \
                patch.object(producer, "_snapshot_target_state",
                             return_value={"reachable": True, "alias": "s21",
                                           "media_volume": 10}), \
                patch.object(producer, "_environment_failures", return_value=[]), \
                patch.object(producer, "_source_environment_failures", return_value=[]):
            yield producer

    def test_finalize_persists_post_cleanup_checkpoint(self) -> None:
        """A completed run's final checkpoint contains the post-cleanup result
        and post-run environment, not the pre-cleanup defaults."""
        private_root = Path(tempfile.mkdtemp())
        for producer in self._finalize_harness(private_root):
            with patch.object(producer, "export_evidence", return_value={"ok": True}):
                runner.finalize_evidence(producer)

        consumer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.REGRESSION, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        consumer.load_checkpoint(producer.run_id)
        self.assertTrue(consumer.cleanup_verified)
        self.assertEqual(consumer.cleanup_failures, [])
        self.assertEqual(
            consumer.run_environment_after,
            {
                "source": {"reachable": True, "alias": "s23u", "media_volume": 12},
                "target": {"reachable": True, "alias": "s21", "media_volume": 10},
            },
        )
        self.assertIsNone(consumer.abort_reason)
        self.assertIsNone(consumer.primary_failure)

    def test_export_only_preserves_post_cleanup_state_without_device_calls(self) -> None:
        """Loading the final checkpoint and running export-only retains the
        authoritative cleanup and final-environment values, performs no live
        ADB/device calls, and never runs cleanup again."""
        private_root = Path(tempfile.mkdtemp())
        for producer in self._finalize_harness(private_root):
            with patch.object(producer, "export_evidence", return_value={"ok": True}):
                runner.finalize_evidence(producer)

        consumer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.REGRESSION, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        consumer.load_checkpoint(producer.run_id)
        with patch.object(consumer, "cleanup",
                          side_effect=AssertionError("cleanup must not run during export-only")), \
                patch.object(consumer.source, "shell",
                             side_effect=AssertionError("no device calls during export-only")), \
                patch.object(consumer.target, "shell",
                             side_effect=AssertionError("no device calls during export-only")):
            evidence = consumer.export_evidence()
        reliability = evidence["wake_reliability"]
        self.assertTrue(reliability["cleanup_verified"])
        self.assertEqual(reliability["cleanup_failures"], [])
        # The export projects the environment through the public snapshot
        # allow-list (alias and other private fields are intentionally dropped).
        self.assertEqual(
            reliability["run_environment_after"],
            {
                "source": {"reachable": True, "media_volume": 12},
                "target": {"reachable": True, "media_volume": 10},
            },
        )
        self.assertTrue(reliability["completion"]["cleanup_verified"])
        self.assertEqual(reliability["completion"]["status"], "completed")

    def test_failed_cleanup_is_preserved_truthfully(self) -> None:
        """A failed cleanup is never converted into a successful result: the
        final checkpoint and the offline export keep the failure visible."""
        private_root = Path(tempfile.mkdtemp())
        for producer in self._finalize_harness(
            private_root,
            restoration_failures=["source volume restoration failed"],
        ):
            with patch.object(producer, "export_evidence", return_value={"ok": True}):
                runner.finalize_evidence(producer)

        consumer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.REGRESSION, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        consumer.load_checkpoint(producer.run_id)
        self.assertFalse(consumer.cleanup_verified)
        self.assertEqual(consumer.cleanup_failures, ["source volume restoration failed"])
        with patch.object(consumer.source, "shell",
                          side_effect=AssertionError("no device calls during export-only")), \
                patch.object(consumer.target, "shell",
                             side_effect=AssertionError("no device calls during export-only")):
            evidence = consumer.export_evidence()
        reliability = evidence["wake_reliability"]
        self.assertFalse(reliability["cleanup_verified"])
        self.assertEqual(
            reliability["cleanup_failures"], ["source volume restoration failed"]
        )
        self.assertFalse(reliability["completion"]["cleanup_verified"])

    def test_fixed_delay_is_feasibility_only(self) -> None:
        with self.assertRaisesRegex(runner.HarnessError, "feasibility"):
            make_runner(runner.RunKind.REGRESSION, fixed_command_delay_ms=500)
        self.assertTrue(make_runner(runner.RunKind.FEASIBILITY, fixed_command_delay_ms=500).is_feasibility)

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
        manifest = preflight_manifest(fixture_hashes={})
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "preflight.json"
            path.write_text(json.dumps(manifest))
            self.assertEqual(harness.load_preflight_manifest(path)["fixture_set_id"], "set-1")
            manifest["fixture_set_id"] = "tampered"
            path.write_text(json.dumps(manifest))
            with self.assertRaises(runner.HarnessError):
                harness.load_preflight_manifest(path)

    def test_loaded_preflight_restores_release_evidence_flags(self) -> None:
        harness = make_runner(runner.RunKind.REGRESSION)
        manifest = preflight_manifest()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "preflight.json"
            path.write_text(json.dumps(manifest))
            harness.load_preflight_manifest(path)
        self.assertTrue(harness.cue_policy_evidence_verified)
        self.assertTrue(harness.cue_audibility_evidence_verified)

    def test_git_metadata_uses_ci_branch_and_commit(self) -> None:
        with patch.dict(
            "os.environ",
            {"GITHUB_HEAD_REF": "feature/1408-evidence", "GITHUB_SHA": "a" * 40},
            clear=True,
        ):
            self.assertEqual(
                runner.git_metadata(),
                ("feature/1408-evidence", "a" * 40),
            )

    def test_git_metadata_labels_detached_checkout(self) -> None:
        completed = SimpleNamespace(returncode=0, stdout="")
        with (
            patch.dict("os.environ", {"GIT_COMMIT": "a" * 40}, clear=True),
            patch.object(runner.subprocess, "run", return_value=completed),
        ):
            self.assertEqual(runner.git_metadata(), ("detached", "a" * 40))

    def test_release_provenance_requires_matching_target_device_and_full_commit(self) -> None:
        harness = make_runner(runner.RunKind.REGRESSION)
        harness.target_identity = runner.DeviceIdentity(
            "s21", "samsung", "SM-G991B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.source_identity = runner.DeviceIdentity(
            "s23u", "samsung", "SM-S918B", "15", "35", "fingerprint", "pkg", 1,
        )
        with patch.dict("os.environ", {"GIT_COMMIT": "a" * 40}, clear=False):
            self.assertTrue(harness._release_provenance_verified())
            # Alias/identity mismatch for the S23U target device must fail.
            harness.target_alias = "s23u"
            self.assertFalse(harness._release_provenance_verified())
            harness.target_identity = runner.DeviceIdentity(
                "s23u", "samsung", "SM-S918B", "15", "35", "fingerprint", "pkg", 1,
            )
            self.assertTrue(harness._release_provenance_verified())
        harness.target_alias = "s21"
        harness.target_identity = runner.DeviceIdentity(
            "s21", "samsung", "SM-G991B", "15", "35", "fingerprint", "pkg", 1,
        )
        with patch.dict("os.environ", {"GIT_COMMIT": "abc123"}, clear=False):
            self.assertFalse(harness._release_provenance_verified())

    def test_sanitisation_rejects_private_paths_and_serials(self) -> None:
        with self.assertRaises(runner.HarnessError):
            runner.assert_commit_safe({"path": "/private/fixtures/source.wav"}, ["/private/fixtures/source.wav"])
        self.assertEqual(runner.sanitise_text("serial=ABC123", ["ABC123"]), "[REDACTED]")

    def test_sanitized_summary_copies_only_referenced_public_artifacts(self) -> None:
        evidence = {
            "cases": [
                {
                    "artifact_refs": [
                        "trials/trial-1/attempt-1/target-events.json",
                        "trials/trial-1/attempt-1/source/result.json",
                    ]
                }
            ]
        }
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "private"
            output = Path(tmp) / "public"
            first = root / "trials/trial-1/attempt-1/target-events.json"
            second = root / "trials/trial-1/attempt-1/source/result.json"
            unreferenced = root / "trials/trial-1/attempt-1/private-debug.json"
            for path, content in (
                (first, '{"serial":"ABC123"}'),
                (second, '{"path":"/private/device/file"}'),
                (unreferenced, '{"must":"not publish"}'),
            ):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")

            runner._copy_sanitised_artifacts(output, evidence, root, ["ABC123", "/private/device"])

            first_payload = json.loads(
                (output / "trials/trial-1/attempt-1/target-events.json").read_text()
            )
            second_payload = json.loads(
                (output / "trials/trial-1/attempt-1/source/result.json").read_text()
            )
            self.assertNotIn("ABC123", json.dumps(first_payload))
            self.assertNotIn("/private/device", json.dumps(second_payload))
            self.assertFalse((output / "trials/trial-1/attempt-1/private-debug.json").exists())

    def test_sanitized_artifact_reference_rejects_path_traversal(self) -> None:
        evidence = {"cases": [{"artifact_refs": ["../private.json"]}]}
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(runner.HarnessError, "unsafe artifact reference"):
                runner._copy_sanitised_artifacts(Path(tmp) / "out", evidence, Path(tmp), [])


    def test_target_event_evidence_preserves_same_clock_samples(self) -> None:
        events = runner.format_target_snapshot_events(
            {
                "events": [
                    {"s": 8, "m": 1000, "t": "STAGE3_READY", "g": 4, "i": 2, "d": {}},
                    {"s": 9, "m": 1120, "t": "VERIFIED_ACTIVATION", "g": 4, "i": 2, "d": {}},
                    {"s": 10, "m": 1170, "t": "WAKE_CALLBACK_INVOKED", "g": 4, "i": 2, "d": {}},
                ]
            }
        )
        attempt = runner.MatrixAttempt(
            trial_id="trial-1",
            matrix_slot=runner.matrix_slots_for_target("s21")[0],
            attempt=1,
            status=runner.AttemptStatus.PASSED,
            target_timing={
                "clock_domain": "target_device_elapsed_realtime",
                "events": events,
            },
            artifact_refs=["trials/trial-1/attempt-1/target-events.json"],
        )
        manifest = runner.RunManifest(
            run_id="run-1",
            run_kind=runner.RunKind.SMOKE,
            gate_mode=runner.GateMode.DIAGNOSTIC,
            matrix_id=runner.MATRIX_ID,
            matrix_version=runner.MATRIX_VERSION,
            created_utc="2026-01-01T00:00:00+00:00",
            source_alias="s23u",
            target_alias="s21",
            fixture_set_id="set-1",
            fixture_hashes={},
            cue_policy_version=None,
            preflight_hash=None,
        )
        target = runner.DeviceIdentity("s21", "samsung", "SM-G991B", "15", "35", "fp", "pkg", 1)
        source = runner.DeviceIdentity("s23u", "samsung", "SM-S918B", "15", "35", "fp", "pkg", 1)
        evidence = runner.render_evidence(
            manifest, target, source, "1", [attempt], None, False, "BUILT_IN_SPEAKER",
        )
        rendered = evidence["cases"][0]
        self.assertEqual(rendered["target_timing"]["clock_domain"], "target_device_elapsed_realtime")
        self.assertEqual(rendered["target_timing"]["events"][1]["m"], 1120)
        self.assertEqual(
            rendered["artifact_refs"],
            ["trials/trial-1/attempt-1/target-events.json"],
        )
        self.assertEqual(
            evidence["artifact_refs"],
            ["trials/trial-1/attempt-1/target-events.json"],
        )
        metrics = evidence_metrics.summarise([(Path("evidence.json"), evidence, [])])
        timing = metrics["wake_reliability"]["timing"]
        self.assertEqual(
            [(sample["metric"], sample["duration_ms"]) for sample in timing["samples"]],
            [("detector_ready_to_activation_ms", 120), ("activation_to_callback_ms", 50)],
        )

    def test_public_environment_evidence_omits_private_state(self) -> None:
        harness = make_runner()
        harness.target_identity = runner.DeviceIdentity(
            "s21", "samsung", "SM-G991B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.source_identity = runner.DeviceIdentity(
            "s23u", "samsung", "SM-S918B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.run_environment_before = {
            "source": {
                "reachable": True,
                "media_volume": 10,
                "boot_id": "private-boot-id",
                "capture_errors": ["private command output"],
            },
            "target": {
                "reachable": True,
                "screen_off": True,
                "package_uid": 10123,
            },
        }
        evidence = harness.export_evidence()
        public_environment = evidence["wake_reliability"]["run_environment_before"]
        self.assertEqual(
            public_environment,
            {
                "source": {"reachable": True, "media_volume": 10},
                "target": {"reachable": True, "screen_off": True},
            },
        )
        self.assertNotIn("private-boot-id", json.dumps(evidence))

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

    def test_export_evidence_summary_excludes_invalid_from_generic_total(self) -> None:
        """Finding 1: producer summary.total must equal valid, not all attempts."""
        harness = make_runner(runner.RunKind.DIAGNOSTIC)
        harness.target_identity = runner.DeviceIdentity(
            "s21", "samsung", "SM-G991B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.source_identity = runner.DeviceIdentity(
            "s23u", "samsung", "SM-S918B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.manifest = runner.RunManifest(
            "run-1", runner.RunKind.DIAGNOSTIC, runner.GateMode.DIAGNOSTIC,
            runner.MATRIX_ID, runner.MATRIX_VERSION, runner.utc_now(),
            "s23u", "s21", "set-1", {"natural_wake": "hash"}, None, None,
        )
        slots = runner.matrix_slots_for_target("s21")
        harness.attempts = [
            runner.MatrixAttempt("pass-1", slots[0], 1, runner.AttemptStatus.PASSED),
            runner.MatrixAttempt("fail-1", slots[1], 1, runner.AttemptStatus.FAILED,
                                 classification=runner.FailureClassification.ACOUSTIC_OR_GATE_MISS),
            runner.MatrixAttempt("invalid-1", slots[2], 1, runner.AttemptStatus.INVALID,
                                 invalid_reason=runner.InvalidReason.DEVICE_ENVIRONMENT_ERROR),
        ]
        evidence = harness.export_evidence()
        summary = evidence["summary"]
        self.assertEqual(summary["total_attempts"], 3)
        self.assertEqual(summary["valid"], 2)
        self.assertEqual(summary["total"], 2)
        self.assertEqual(summary["passed"], 1)
        self.assertEqual(summary["failed"], 1)
        self.assertEqual(summary["invalid"], 1)
        self.assertEqual(summary["pass_rate"], 0.5)

    def test_export_evidence_completion_counts(self) -> None:
        """Finding 2: completion fields in exported evidence are authoritative."""
        harness = make_runner(runner.RunKind.DIAGNOSTIC)
        harness.target_identity = runner.DeviceIdentity(
            "s21", "samsung", "SM-G991B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.source_identity = runner.DeviceIdentity(
            "s23u", "samsung", "SM-S918B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.manifest = runner.RunManifest(
            "run-1", runner.RunKind.DIAGNOSTIC, runner.GateMode.DIAGNOSTIC,
            runner.MATRIX_ID, runner.MATRIX_VERSION, runner.utc_now(),
            "s23u", "s21", "set-1", {"natural_wake": "hash"}, None, None,
        )
        slots = runner.matrix_slots_for_target("s21")
        total_required = len(slots)
        off_slot = runner.MatrixSlot(idle_s=999, wake_only=False, ordinal=1)
        harness.attempts = [
            runner.MatrixAttempt("pass-1", slots[0], 1, runner.AttemptStatus.PASSED),
            runner.MatrixAttempt("fail-1", slots[1], 1, runner.AttemptStatus.FAILED,
                                 classification=runner.FailureClassification.ACOUSTIC_OR_GATE_MISS),
            runner.MatrixAttempt("invalid-1", slots[2], 1, runner.AttemptStatus.INVALID,
                                 invalid_reason=runner.InvalidReason.DEVICE_ENVIRONMENT_ERROR),
            runner.MatrixAttempt("pass-1-retry", slots[0], 2, runner.AttemptStatus.PASSED),
            runner.MatrixAttempt("off-matrix", off_slot, 1, runner.AttemptStatus.PASSED),
        ]
        evidence = harness.export_evidence()
        completion = evidence["wake_reliability"]["completion"]

        self.assertIn("total_required", completion)
        self.assertEqual(completion["total_required"], total_required)
        # slots[1] has exactly one valid outcome (failed) = completed
        # slots[0] has two valid outcomes = not cleanly completed
        # slots[2] has zero valid outcomes (invalid only) = not completed
        # remaining 24 slots have zero outcomes = not completed
        self.assertEqual(completion["completed"], 1)
        self.assertEqual(completion["missing"], total_required - 1)
        self.assertEqual(completion["passed"], 0)
        self.assertEqual(completion["failed"], 1)
        self.assertEqual(completion["invalid"], 1)
        self.assertEqual(completion["duplicate_valid_positions"], 1)
        # Schema validation
        valid, issues = evidence_metrics.validate_record(evidence, [])
        self.assertTrue(valid, issues)

    def test_export_evidence_end_to_end(self) -> None:
        """Finding 2: producer, summariser, and dashboard completion agree with retry present."""
        import build_test_dashboard as dashboard

        harness = make_runner(runner.RunKind.DIAGNOSTIC)
        harness.target_identity = runner.DeviceIdentity(
            "s21", "samsung", "SM-G991B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.source_identity = runner.DeviceIdentity(
            "s23u", "samsung", "SM-S918B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.manifest = runner.RunManifest(
            "run-1", runner.RunKind.DIAGNOSTIC, runner.GateMode.DIAGNOSTIC,
            runner.MATRIX_ID, runner.MATRIX_VERSION, runner.utc_now(),
            "s23u", "s21", "set-1", {"natural_wake": "hash"}, None, None,
        )
        slots = runner.matrix_slots_for_target("s21")
        off_slot = runner.MatrixSlot(idle_s=999, wake_only=False, ordinal=1)
        # One passed, one failed, one invalid, one retry on the passed position,
        # one off-matrix valid attempt.
        harness.attempts = [
            runner.MatrixAttempt("pass-1", slots[0], 1, runner.AttemptStatus.PASSED),
            runner.MatrixAttempt("fail-1", slots[1], 1, runner.AttemptStatus.FAILED,
                                 classification=runner.FailureClassification.ACOUSTIC_OR_GATE_MISS,
                                 failures=["STT readiness event absent"]),
            runner.MatrixAttempt("invalid-1", slots[2], 1, runner.AttemptStatus.INVALID,
                                 invalid_reason=runner.InvalidReason.DEVICE_ENVIRONMENT_ERROR),
            runner.MatrixAttempt("pass-1-retry", slots[0], 2, runner.AttemptStatus.PASSED),
            runner.MatrixAttempt("off-matrix", off_slot, 1, runner.AttemptStatus.PASSED),
        ]
        evidence = harness.export_evidence()
        producer_completion = evidence["wake_reliability"]["completion"]
        # Producer semantics: passed position with retry has 2 outcomes → not completed,
        # failed position has 1 outcome → completed, invalid position → missing,
        # off-matrix → ignored.  So completed=1, missing=26, duplicates=1.
        self.assertEqual(producer_completion["completed"], 1)
        self.assertEqual(producer_completion["missing"], producer_completion["total_required"] - 1)
        self.assertEqual(producer_completion["duplicate_valid_positions"], 1)

        # 1. Through summariser — must agree with producer
        summary = evidence_metrics.summarise([("evidence.json", evidence, [])])
        summariser_completion = summary["wake_reliability"]["completion"]
        for key in ("total_required", "completed", "missing", "duplicate_valid_positions"):
            self.assertEqual(
                summariser_completion[key], producer_completion[key],
                f"summariser {key} ({summariser_completion[key]}) != producer {key} ({producer_completion[key]})",
            )

        # 2. Through dashboard aggregation — must agree with producer
        aggregates = dashboard._build_aggregates([evidence], metrics=summary)
        dash_completion = aggregates["metrics"]["wake_reliability"]["completion"]
        for key in ("total_required", "completed", "missing", "duplicate_valid_positions"):
            self.assertEqual(
                dash_completion[key], producer_completion[key],
                f"dashboard {key} ({dash_completion[key]}) != producer {key} ({producer_completion[key]})",
            )

        # 3. Wake metrics rendering — same fraction as producer
        wake_html = dashboard._render_wake_metrics_section(aggregates)
        expected_fraction = f"{producer_completion['completed']}/{producer_completion['total_required']}"
        self.assertIn(expected_fraction, wake_html,
                      "dashboard must display the same completed/required fraction as producer")

    def test_public_environment_snapshot_normalises_to_evidence_schema_types(self) -> None:
        """Device-native int modes and float uptime must become schema types (string/int)."""
        raw = {
            "reachable": True,
            "uptime_seconds": 2207985.31,
            "screen_off": True,
            "charging": False,
            "service_active": True,
            "media_volume": 11,
            "ringer_mode": 0,
            "dnd_mode": 0,
            "bluetooth_route_active": False,
            "boot_id": "private-boot-id",
        }
        projected = runner.public_environment_snapshot(raw)
        self.assertEqual(projected["uptime_seconds"], 2207985)
        self.assertEqual(projected["ringer_mode"], "silent")
        self.assertEqual(projected["dnd_mode"], "off")
        self.assertNotIn("boot_id", projected)
        # All ringer/dnd enum names stay strings.
        self.assertEqual(runner.public_environment_snapshot({**raw, "ringer_mode": 2})["ringer_mode"], "normal")
        self.assertEqual(runner.public_environment_snapshot({**raw, "dnd_mode": 3})["dnd_mode"], "alarms")
        # Already-normalised strings pass through unchanged.
        self.assertEqual(
            runner.public_environment_snapshot({**raw, "ringer_mode": "normal", "dnd_mode": "off"})["ringer_mode"],
            "normal",
        )

class SourcePlaybackStartContractTests(unittest.TestCase):
    """Physical source-helper playback-start event contract (#1409)."""

    def test_single_started_event_is_persisted(self) -> None:
        """Exactly one physical ``started`` event yields its validated wall
        clock; the request wall clock is never substituted."""
        result = source_result()
        self.assertEqual(
            runner.source_playback_start_wall_clock_ms(result),
            1_705_300_000_423,
        )
        self.assertEqual(result["request_wall_clock_ms"], 1_705_300_000_123)

    def test_zero_started_events_produce_no_value(self) -> None:
        """A source result without the physical ``started`` event yields no
        playback-start value even when request timing exists."""
        result = source_result()
        result["events"] = [
            event for event in result["events"]
            if event["name"] != "started"
        ]
        self.assertIsNone(runner.source_playback_start_wall_clock_ms(result))

    def test_multiple_started_events_fail_closed(self) -> None:
        """Ambiguous playback-start evidence stays None; nothing is inferred."""
        result = source_result()
        result["events"].append(
            {"name": "started", "monotonic_ms": 1400, "wall_clock_ms": 999}
        )
        self.assertIsNone(runner.source_playback_start_wall_clock_ms(result))

    def test_started_event_with_invalid_wall_clock_fails_closed(self) -> None:
        result = source_result()
        result["events"] = [
            {"name": "started", "monotonic_ms": 1300, "wall_clock_ms": "not-an-int"}
        ]
        self.assertIsNone(runner.source_playback_start_wall_clock_ms(result))

    def test_run_trial_never_falls_back_to_request_time(self) -> None:
        """When the preserved command source result has no ``started`` event,
        the persisted playback-start stays None while request timing is kept
        unchanged (never substituted)."""
        harness = make_runner()
        fixture_sha256 = source_result()["fixture_sha256"]
        harness.installed_fixture_hashes = {
            "natural_wake": fixture_sha256,
            "qwen_command": fixture_sha256,
        }
        harness.installed_command_transcript_hashes = {"qwen_command": "b" * 64}
        harness.preflight_approval = {
            "source_volume_index": 7,
            "cue_audibility_evidence_verified": True,
            "source_environment_state": {},
            "target_environment_state": {},
        }
        harness.cue_audibility_evidence_verified = True
        final_events = complete_events(runner.TrialType.WAKE_PLUS_COMMAND)
        boundary_envelope = envelope([
            event(3, "DETECTOR_GENERATION_STARTED", generation=4, session=0),
        ], lowest=3)
        pre_command_open_events = [
            item for item in final_events
            if item["t"] not in {"STT_SPEECH_DETECTED", "STT_FINAL",
                                  "COMMAND_ROUTING_RESULT", "SESSION_COMPLETED",
                                  "DETECTOR_REARMED"}
        ]
        provider_call = self._provider_snapshots(
            {
                "boundary": boundary_envelope,
                "open": envelope(pre_command_open_events),
                "final": envelope(final_events),
            },
            ["boundary", "open", "final"],
        )
        call_order: list[str] = []

        def invoke_command_source(**kwargs):
            command_result = source_result("trial-nofallback-cmd", "qwen_command")
            command_result["events"] = [
                event_item for event_item in command_result["events"]
                if event_item["name"] != "started"
            ]
            command_events = [event(15, "COMMAND_ROUTING_RESULT")]
            return command_result, command_events, envelope(command_events, lowest=15)

        with patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(harness, "_invoke_source", side_effect=self._invoke_wake(call_order)), \
                patch.object(harness, "_invoke_command_source_with_armed_wait", side_effect=invoke_command_source), \
                patch.object(harness, "_wait_for_target_events", side_effect=self._target_wait(call_order)), \
                patch.object(harness, "_snapshot_target_state", return_value={"reachable": True}), \
                patch.object(harness, "_snapshot_source_state", return_value={"reachable": True}), \
                patch.object(harness, "_environment_failures", return_value=[]), \
                patch.object(harness, "_source_environment_failures", return_value=[]), \
                patch.object(harness, "checkpoint"), \
                patch.object(runner.time, "sleep"):
            attempt = harness.run_trial(
                "trial-nofallback",
                runner.MatrixSlot(idle_s=1, wake_only=False, ordinal=2),
                "natural_wake",
                "qwen_command",
            )

        command_timing = attempt.source_timing["command"]
        self.assertIsNone(command_timing["playback_start_wall_clock_ms"])
        self.assertEqual(command_timing["request_wall_clock_ms"], 1_705_300_000_123)

    def _provider_snapshots(self, envelopes: dict[str, dict], order: list[str]):
        provider_snapshots = iter([envelopes[key] for key in order])

        def provider_call(method, extras=None, timeout=15.0):
            if method == runner.TARGET_METHOD_GET_SEQUENCE:
                return runner.TARGET_RESULT_OK, "3"
            if method == runner.TARGET_METHOD_GET_SNAPSHOT:
                return runner.TARGET_RESULT_OK, json.dumps(next(provider_snapshots))
            self.fail(f"unexpected provider method {method}")

        return provider_call

    def _target_wait(self, call_order: list[str]):
        def target_wait(**kwargs):
            event_type = kwargs["event_type"]
            if event_type == "STT_READY":
                call_order.append("wait-STT_READY")
            elif event_type == "CUE_REQUESTED":
                call_order.append("wait-CUE_REQUESTED")
            waited = event(
                {"STT_READY": 11, "CUE_REQUESTED": 12, "DETECTOR_REARMED": 18}[event_type],
                event_type,
            )
            return [waited], envelope([waited], lowest=waited["s"])

        return target_wait

    def _invoke_wake(self, call_order: list[str]):
        def invoke_wake_source(trial_id: str, fixture_id: str, volume_index: int) -> dict:
            call_order.append("wake-completed")
            return source_result(trial_id, fixture_id)

        return invoke_wake_source


class TerminalRearmObservationTests(unittest.TestCase):
    """Bounded terminal/re-arm observation covering the supported STT
    lifecycle (#1409)."""

    def _harness(self) -> tuple[Any, dict]:
        harness = make_runner()
        fixture_sha256 = source_result()["fixture_sha256"]
        harness.installed_fixture_hashes = {"natural_wake": fixture_sha256}
        harness.preflight_approval = {
            "source_volume_index": 7,
            "cue_audibility_evidence_verified": True,
            "source_environment_state": {},
            "target_environment_state": {},
        }
        harness.cue_audibility_evidence_verified = True
        boundary_envelope = envelope([
            event(3, "DETECTOR_GENERATION_STARTED", generation=4, session=0),
        ], lowest=3)
        return harness, boundary_envelope

    def _provider_snapshots(self, envelopes: dict[str, dict], order: list[str]):
        provider_snapshots = iter([envelopes[key] for key in order])

        def provider_call(method, extras=None, timeout=15.0):
            if method == runner.TARGET_METHOD_GET_SEQUENCE:
                return runner.TARGET_RESULT_OK, "3"
            if method == runner.TARGET_METHOD_GET_SNAPSHOT:
                return runner.TARGET_RESULT_OK, json.dumps(next(provider_snapshots))
            self.fail(f"unexpected provider method {method}")

        return provider_call

    def _terminal_wait(self, timeout_observed: list[int], rearm_found: bool = True):
        def target_wait(**kwargs):
            event_type = kwargs["event_type"]
            if event_type == "DETECTOR_REARMED":
                timeout_observed.append(kwargs["timeout_ms"])
                if not rearm_found:
                    # Provider timeout: no matching event before the bound.
                    return [], envelope([], lowest=1)
                rearm_event = event(14, "DETECTOR_REARMED", generation=5)
                return [rearm_event], envelope([rearm_event], lowest=1)
            waited = event(11, "STT_READY")
            return [waited], envelope([waited], lowest=waited["s"])

        return target_wait

    def test_rearm_after_old_bound_within_new_bound_classifies_normally(self) -> None:
        """The terminal/re-arm lands 18 s after STT_READY: beyond the old 15 s
        bound but inside the derived bound.  The wait observes it (the fake
        provider only reports it for timeout >= 18 s) and the attempt
        classifies normally instead of unclassified."""
        harness, boundary_envelope = self._harness()
        final_events = complete_events(runner.TrialType.WAKE_ONLY)
        timeout_observed: list[int] = []
        provider_call = self._provider_snapshots(
            {
                "boundary": boundary_envelope,
                "final": envelope(final_events),
            },
            ["boundary", "final"],
        )

        def terminal_wait(**kwargs):
            event_type = kwargs["event_type"]
            if event_type == "DETECTOR_REARMED":
                timeout_observed.append(kwargs["timeout_ms"])
                if kwargs["timeout_ms"] < 18_000:
                    # The old 15 s bound would have expired before the re-arm.
                    return [], envelope([], lowest=1)
                rearm_event = event(14, "DETECTOR_REARMED", generation=5)
                return [rearm_event], envelope([rearm_event], lowest=1)
            waited = event(11, "STT_READY")
            return [waited], envelope([waited], lowest=waited["s"])

        with patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(harness, "_invoke_source",
                             side_effect=lambda trial_id, fixture_id, volume_index:
                             source_result(trial_id, fixture_id)), \
                patch.object(harness, "_wait_for_target_events", side_effect=terminal_wait), \
                patch.object(harness, "_snapshot_target_state", return_value={"reachable": True}), \
                patch.object(harness, "_snapshot_source_state", return_value={"reachable": True}), \
                patch.object(harness, "_environment_failures", return_value=[]), \
                patch.object(harness, "_source_environment_failures", return_value=[]), \
                patch.object(harness, "checkpoint"), \
                patch.object(runner.time, "sleep"):
            attempt = harness.run_trial(
                "trial-rearm-late",
                runner.MatrixSlot(idle_s=1, wake_only=True, ordinal=1),
                "natural_wake",
                None,
            )

        self.assertEqual(timeout_observed, [runner.TARGET_WAIT_TERMINAL_REARM_MS])
        self.assertEqual(attempt.status, runner.AttemptStatus.PASSED)
        self.assertIsNone(attempt.classification)
        self.assertNotIn("missing correlated session terminal", attempt.failures)

    def test_no_terminal_before_new_bound_stops_deterministically(self) -> None:
        """A genuinely exhausted bound keeps the existing failure semantics:
        the final snapshot lacks the terminal and the attempt is UNCLASSIFIED
        with the correlated-terminal failure, never treated as success."""
        harness, boundary_envelope = self._harness()
        retry_events = complete_events(runner.TrialType.WAKE_ONLY)[:-2]
        retry_events += [
            event(13, "STT_SPEECH_DETECTED"),
            event(14, "STT_ERROR", data={"category": "stt_recognition_failed"}),
            event(15, "STT_START_REQUESTED", data={"attempt": "2"}),
            event(16, "STT_READY"),
            event(17, "CUE_REQUESTED"),
            event(18, "STT_SPEECH_DETECTED"),
        ]
        timeout_observed: list[int] = []
        provider_call = self._provider_snapshots(
            {
                "boundary": boundary_envelope,
                "final": envelope(retry_events),
            },
            ["boundary", "final"],
        )

        with patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(harness, "_invoke_source",
                             side_effect=lambda trial_id, fixture_id, volume_index:
                             source_result(trial_id, fixture_id)), \
                patch.object(harness, "_wait_for_target_events",
                             side_effect=self._terminal_wait(timeout_observed, rearm_found=False)), \
                patch.object(harness, "_snapshot_target_state", return_value={"reachable": True}), \
                patch.object(harness, "_snapshot_source_state", return_value={"reachable": True}), \
                patch.object(harness, "_environment_failures", return_value=[]), \
                patch.object(harness, "_source_environment_failures", return_value=[]), \
                patch.object(harness, "checkpoint"), \
                patch.object(runner.time, "sleep"):
            attempt = harness.run_trial(
                "trial-rearm-exhausted",
                runner.MatrixSlot(idle_s=1, wake_only=True, ordinal=1),
                "natural_wake",
                None,
            )

        self.assertEqual(timeout_observed, [runner.TARGET_WAIT_TERMINAL_REARM_MS])
        self.assertEqual(attempt.status, runner.AttemptStatus.FAILED)
        self.assertEqual(
            attempt.classification, runner.FailureClassification.UNCLASSIFIED
        )
        self.assertIn("missing correlated session terminal", attempt.failures)

    def test_terminal_wait_provider_error_remains_fail_closed(self) -> None:
        """Provider errors during the terminal observation keep the existing
        deterministic invalid-attempt semantics."""
        harness, boundary_envelope = self._harness()
        provider_call = self._provider_snapshots(
            {"boundary": boundary_envelope, "final": envelope([])},
            ["boundary", "final"],
        )

        def error_wait(**kwargs):
            if kwargs["event_type"] == "DETECTOR_REARMED":
                raise runner.HarnessError("target wait status failed: provider unavailable")
            waited = event(11, "STT_READY")
            return [waited], envelope([waited], lowest=waited["s"])

        with patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(harness, "_invoke_source",
                             side_effect=lambda trial_id, fixture_id, volume_index:
                             source_result(trial_id, fixture_id)), \
                patch.object(harness, "_wait_for_target_events", side_effect=error_wait), \
                patch.object(harness, "_snapshot_target_state", return_value={"reachable": True}), \
                patch.object(harness, "_snapshot_source_state", return_value={"reachable": True}), \
                patch.object(harness, "_environment_failures", return_value=[]), \
                patch.object(harness, "_source_environment_failures", return_value=[]), \
                patch.object(harness, "checkpoint"), \
                patch.object(runner.time, "sleep"):
            attempt = harness.run_trial(
                "trial-rearm-error",
                runner.MatrixSlot(idle_s=1, wake_only=True, ordinal=1),
                "natural_wake",
                None,
            )

        self.assertEqual(attempt.status, runner.AttemptStatus.INVALID)
        self.assertEqual(
            attempt.invalid_reason, runner.InvalidReason.EVIDENCE_BOUNDARY_LOST
        )
        self.assertIn("provider unavailable", attempt.operational_failure)


class NormalisedEventDeduplicationTests(unittest.TestCase):
    """Normalised target events: one record per authoritative sequence (#1409)."""

    def test_duplicated_candidate_projection_collapses(self) -> None:
        candidate = event(6, "ACTIVATION_CANDIDATE")
        events = [
            event(4, "STAGE3_READY"),
            candidate,
            event(7, "VERIFIED_ACTIVATION"),
            candidate,
        ]
        result = runner.deduplicate_projected_events(events)
        self.assertEqual([item["s"] for item in result], [4, 6, 7])

    def test_distinct_events_sharing_type_are_kept(self) -> None:
        events = [
            event(6, "ACTIVATION_CANDIDATE", data={"confidence": "0.9"}),
            event(9, "ACTIVATION_CANDIDATE", data={"confidence": "0.7"}),
        ]
        result = runner.deduplicate_projected_events(events)
        self.assertEqual([item["s"] for item in result], [6, 9])

    def test_conflicting_records_for_one_sequence_fail_closed(self) -> None:
        events = [
            event(6, "ACTIVATION_CANDIDATE", data={"confidence": "0.9"}),
            event(6, "ACTIVATION_CANDIDATE", data={"confidence": "0.8"}),
        ]
        with self.assertRaisesRegex(
            runner.HarnessError, "conflicting journal projections for sequence 6"
        ):
            runner.deduplicate_projected_events(events)

    def test_sequence_order_is_preserved(self) -> None:
        candidate = event(6, "ACTIVATION_CANDIDATE")
        events = [
            event(7, "VERIFIED_ACTIVATION"),
            candidate,
            event(4, "STAGE3_READY"),
            candidate,
        ]
        result = runner.deduplicate_projected_events(events)
        self.assertEqual([item["s"] for item in result], [7, 6, 4])

    def test_checkpoint_load_deduplicates_and_repopulates_playback_start(self) -> None:
        """Offline regeneration: a preserved checkpoint with duplicated
        projections and null playback-start wall clocks is normalised on load
        from the preserved source results alone."""
        private_root = Path(tempfile.mkdtemp())
        slot = runner.MatrixSlot(idle_s=10, wake_only=False, ordinal=2)
        command_result = source_result("trial-007-cmd", "qwen_command")
        producer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.DIAGNOSTIC, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        candidate = event(6, "ACTIVATION_CANDIDATE")
        producer.source_results = [command_result]
        producer.attempts = [
            runner.MatrixAttempt(
                "trial-007", slot, 1, runner.AttemptStatus.PASSED,
                source_timing={"command": {
                    "request_wall_clock_ms": 1_705_300_000_123,
                    "playback_start_wall_clock_ms": None,
                }},
                target_timing={"events": [
                    event(5, "STAGE3_READY"),
                    candidate,
                    event(7, "VERIFIED_ACTIVATION"),
                    candidate,
                ]},
                command_fixture_id="qwen_command",
                command_fixture_sha256=command_result["fixture_sha256"],
            ),
        ]
        producer.checkpoint()

        consumer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.DIAGNOSTIC, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        consumer.load_checkpoint(producer.run_id)

        attempt = consumer.attempts[0]
        self.assertEqual(
            [item["s"] for item in attempt.target_timing["events"]],
            [5, 6, 7],
        )
        self.assertEqual(
            attempt.source_timing["command"]["playback_start_wall_clock_ms"],
            1_705_300_000_423,
        )
        self.assertEqual(
            attempt.source_timing["command"]["request_wall_clock_ms"],
            1_705_300_000_123,
        )


class OutOfWindowCommandTests(unittest.TestCase):
    """Harness source-timing invalidation for out-of-window command delivery."""

    def _command_harness(self) -> tuple[Any, dict, dict]:
        harness = make_runner()
        fixture_sha256 = source_result()["fixture_sha256"]
        harness.installed_fixture_hashes = {
            "natural_wake": fixture_sha256,
            "qwen_command": fixture_sha256,
        }
        harness.installed_command_transcript_hashes = {"qwen_command": "b" * 64}
        harness.preflight_approval = {
            "source_volume_index": 7,
            "cue_audibility_evidence_verified": True,
            "source_environment_state": {},
            "target_environment_state": {},
        }
        harness.cue_audibility_evidence_verified = True
        final_events = complete_events(runner.TrialType.WAKE_PLUS_COMMAND)
        final_envelope = envelope(final_events)
        boundary_envelope = envelope([
            event(3, "DETECTOR_GENERATION_STARTED", generation=4, session=0),
        ], lowest=3)
        pre_command_open_events = [
            item for item in final_events
            if item["t"] in {
                "DETECTOR_GENERATION_STARTED", "SILENCE_GATE_ENTERED",
                "VOICED_FRAME_AFTER_SILENCE", "STAGE2_RESUMED", "STAGE3_READY",
                "ACTIVATION_CANDIDATE", "VERIFIED_ACTIVATION",
                "WAKE_CALLBACK_INVOKED", "VOICE_SESSION_STARTED",
                "STT_START_REQUESTED", "STT_READY", "CUE_REQUESTED",
            }
        ]
        return harness, {
            "boundary": boundary_envelope,
            "open": envelope(pre_command_open_events),
            "final": final_envelope,
        }, final_events

    def _provider_snapshots(self, envelopes: dict[str, dict], order: list[str]):
        provider_snapshots = iter([envelopes[key] for key in order])

        def provider_call(method, extras=None, timeout=15.0):
            if method == runner.TARGET_METHOD_GET_SEQUENCE:
                return runner.TARGET_RESULT_OK, "3"
            if method == runner.TARGET_METHOD_GET_SNAPSHOT:
                return runner.TARGET_RESULT_OK, json.dumps(next(provider_snapshots))
            self.fail(f"unexpected provider method {method}")

        return provider_call

    def _target_wait(self, call_order: list[str]):
        def target_wait(**kwargs):
            event_type = kwargs["event_type"]
            if event_type == "STT_READY":
                call_order.append("wait-STT_READY")
            elif event_type == "CUE_REQUESTED":
                call_order.append("wait-CUE_REQUESTED")
            waited = event(
                {"STT_READY": 11, "CUE_REQUESTED": 12, "DETECTOR_REARMED": 18}[event_type],
                event_type,
            )
            return [waited], envelope([waited], lowest=waited["s"])

        return target_wait

    def _invoke_wake(self, call_order: list[str]):
        def invoke_wake_source(trial_id: str, fixture_id: str, volume_index: int) -> dict:
            call_order.append("wake-completed")
            return source_result(trial_id, fixture_id)

        return invoke_wake_source

    def test_terminal_before_dispatch_suppresses_command_and_invalidates(self) -> None:
        """A SESSION_COMPLETED observed before dispatch prevents source invocation
        and the attempt becomes INVALID with the explicit harness-timing reason."""
        harness, envelopes, _ = self._command_harness()
        call_order: list[str] = []
        provider_call = self._provider_snapshots(
            envelopes, ["boundary", "final", "final"]
        )

        def invoke_command_source(**kwargs):
            self.fail("command source must not be invoked after the session ended")

        with patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(harness, "_invoke_source", side_effect=self._invoke_wake(call_order)), \
                patch.object(harness, "_invoke_command_source_with_armed_wait", side_effect=invoke_command_source), \
                patch.object(harness, "_wait_for_target_events", side_effect=self._target_wait(call_order)), \
                patch.object(harness, "_snapshot_target_state", return_value={"reachable": True}), \
                patch.object(harness, "_snapshot_source_state", return_value={"reachable": True}), \
                patch.object(harness, "_environment_failures", return_value=[]), \
                patch.object(harness, "_source_environment_failures", return_value=[]), \
                patch.object(harness, "checkpoint"), \
                patch.object(runner.time, "sleep"):
            attempt = harness.run_trial(
                "trial-window-closed",
                runner.MatrixSlot(idle_s=1, wake_only=False, ordinal=2),
                "natural_wake",
                "qwen_command",
            )

        self.assertEqual(attempt.status, runner.AttemptStatus.INVALID, attempt.__dict__)
        self.assertIsNone(attempt.classification)
        self.assertEqual(
            attempt.invalid_reason,
            runner.InvalidReason.COMMAND_OUTSIDE_LISTENING_WINDOW,
        )
        self.assertTrue(
            any("listening session ended before command delivery" in failure
                for failure in attempt.failures),
            attempt.failures,
        )
        self.assertIn("listening_window_closed", attempt.invalid_details)
        self.assertEqual(
            attempt.invalid_details["listening_window_closed"]["terminal_sequence"],
            16,
        )
        # The pre-dispatch target observation is persisted as same-target
        # ordering evidence for any later retrospective review.
        self.assertEqual(attempt.target_timing["pre_dispatch_terminal_sequence"], 16)
        # The wake and target events stay preserved: the attempt remains visible.
        self.assertIs(harness.attempts[-1], attempt)
        self.assertTrue(attempt.target_timing["events"])

    def test_command_inside_window_failure_stays_valid_product_failure(self) -> None:
        """A command delivered while the session is open continues through the
        existing path; a genuine capture/routing failure remains a valid failure."""
        harness, envelopes, final_events = self._command_harness()
        broken_final = copy.deepcopy(envelopes["final"])
        broken_final["events"] = [
            item for item in broken_final["events"] if item["t"] != "STT_FINAL"
        ]
        broken_final["highestSequence"] = broken_final["events"][-1]["s"]
        provider_call = self._provider_snapshots(
            {
                "boundary": envelopes["boundary"],
                "open": envelopes["open"],
                "final": broken_final,
            },
            ["boundary", "open", "final"],
        )
        call_order: list[str] = []

        def invoke_command_source(**kwargs):
            call_order.append("command-dispatched")
            command_events = [event(15, "COMMAND_ROUTING_RESULT")]
            command_result = source_result("trial-window-open-cmd", "qwen_command")
            command_result["command_transcript_sha256"] = "b" * 64
            return command_result, command_events, envelope(command_events, lowest=15)

        with patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(harness, "_invoke_source", side_effect=self._invoke_wake(call_order)), \
                patch.object(harness, "_invoke_command_source_with_armed_wait", side_effect=invoke_command_source), \
                patch.object(harness, "_wait_for_target_events", side_effect=self._target_wait(call_order)), \
                patch.object(harness, "_snapshot_target_state", return_value={"reachable": True}), \
                patch.object(harness, "_snapshot_source_state", return_value={"reachable": True}), \
                patch.object(harness, "_environment_failures", return_value=[]), \
                patch.object(harness, "_source_environment_failures", return_value=[]), \
                patch.object(harness, "checkpoint"), \
                patch.object(runner.time, "sleep"):
            attempt = harness.run_trial(
                "trial-window-open",
                runner.MatrixSlot(idle_s=1, wake_only=False, ordinal=2),
                "natural_wake",
                "qwen_command",
            )

        self.assertIn("command-dispatched", call_order)
        self.assertEqual(attempt.status, runner.AttemptStatus.FAILED)
        self.assertEqual(
            attempt.classification,
            runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
        )
        # The pre-dispatch observation recorded no terminal: dispatch proceeded.
        self.assertIsNone(attempt.target_timing["pre_dispatch_terminal_sequence"])
        self.assertEqual(harness.valid_failed_slots, {"1:wake_plus_command:2"})

    def test_retrospective_reclassification_requires_proof(self) -> None:
        """Raw cross-device wall clocks are never proof: reclassification needs
        same-target pre-dispatch ordering or a persisted validated alignment
        record conclusive beyond its range."""
        def attempt(request_ms: int | None, terminal_w: int | None,
                    *, wake_only: bool = False,
                    status: runner.AttemptStatus = runner.AttemptStatus.FAILED,
                    with_command: bool = True,
                    pre_dispatch: int | None = None,
                    playback_ms: int | None = None) -> runner.MatrixAttempt:
            events = [] if terminal_w is None else [
                {"s": 16, "t": "SESSION_COMPLETED", "w": terminal_w, "g": 11, "i": 10}
            ]
            timing = {}
            if with_command:
                timing = {"command": {"request_wall_clock_ms": request_ms}}
                if playback_ms is not None:
                    timing["command"]["playback_start_wall_clock_ms"] = playback_ms
            target = {"events": events}
            if pre_dispatch is not None:
                target["pre_dispatch_terminal_sequence"] = pre_dispatch
            return runner.MatrixAttempt(
                "t", runner.MatrixSlot(idle_s=10, wake_only=wake_only, ordinal=1),
                1, status,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                source_timing=timing,
                target_timing=target,
            )

        ALIGNED = {
            "source_alias": "s23u", "target_alias": "s21",
            "offset_range_ms": [-100, 100], "uncertainty_ms": 25,
            "method": "preflight_alignment_probe",
        }

        # Raw wall-clock ordering without any calibration or marker is NOT
        # proof: an unmeasured source-ahead offset could explain it, even when
        # the playback-start evidence is present.
        uncalibrated = attempt(request_ms=2_000, terminal_w=1_000, playback_ms=2_000)
        self.assertFalse(runner.command_delivery_after_session_end(uncalibrated))
        self.assertFalse(runner.reclassify_out_of_window_command_attempt(uncalibrated))
        self.assertEqual(uncalibrated.status, runner.AttemptStatus.FAILED)
        self.assertEqual(
            uncalibrated.classification,
            runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
        )

        # Priority 1: same-target pre-dispatch journal observation.
        marked = attempt(request_ms=2_000, terminal_w=1_000, pre_dispatch=16)
        self.assertTrue(runner.command_delivery_after_session_end(marked))
        self.assertTrue(runner.reclassify_out_of_window_command_attempt(marked))
        self.assertEqual(marked.status, runner.AttemptStatus.INVALID)
        self.assertIsNone(marked.classification)
        self.assertEqual(
            marked.invalid_reason, runner.InvalidReason.COMMAND_OUTSIDE_LISTENING_WINDOW
        )
        self.assertTrue(any("proven" in f for f in marked.failures), marked.failures)
        self.assertIn("listening_window_closed", marked.invalid_details)

        # Priority 2: validated alignment conclusive for every offset in range,
        # based on the source helper's actual playback start.
        conclusive = attempt(request_ms=2_000, terminal_w=1_000, playback_ms=2_000)
        self.assertTrue(runner.command_delivery_after_session_end(conclusive, ALIGNED))
        self.assertTrue(runner.reclassify_out_of_window_command_attempt(conclusive, ALIGNED))
        self.assertEqual(conclusive.status, runner.AttemptStatus.INVALID)
        self.assertEqual(
            conclusive.invalid_reason, runner.InvalidReason.COMMAND_OUTSIDE_LISTENING_WINDOW
        )

        # Review scenario: source clock 750 ms ahead; command actually requested
        # 300 ms before session end appears 450 ms after it.  Even with the
        # persisted [-800, -700] range the adjusted ordering is not conclusive.
        source_ahead = attempt(request_ms=1_450, terminal_w=1_000, playback_ms=1_450)
        source_ahead_alignment = {
            **ALIGNED, "offset_range_ms": [-800, -700],
        }
        self.assertFalse(runner.command_delivery_after_session_end(
            source_ahead, source_ahead_alignment))
        self.assertFalse(runner.reclassify_out_of_window_command_attempt(
            source_ahead, source_ahead_alignment))
        self.assertEqual(source_ahead.status, runner.AttemptStatus.FAILED)

        # Same range but genuinely late by more than the worst-case offset:
        # conclusive.
        genuinely_late = attempt(request_ms=3_000, terminal_w=1_000, playback_ms=3_000)
        self.assertTrue(runner.command_delivery_after_session_end(
            genuinely_late, source_ahead_alignment))

        # Delivery inside the window with aligned clocks: unchanged.
        in_window = attempt(request_ms=900, terminal_w=1_000, playback_ms=900)
        self.assertFalse(runner.command_delivery_after_session_end(in_window, ALIGNED))
        self.assertFalse(runner.reclassify_out_of_window_command_attempt(in_window, ALIGNED))
        self.assertEqual(in_window.status, runner.AttemptStatus.FAILED)
        self.assertEqual(
            in_window.classification,
            runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
        )

        # Untrustworthy alignment records are never used.  (Alias pairing is
        # enforced by the runner at load time — see
        # test_checkpoint_load_rejects_alignment_for_other_devices.)
        for broken in (
            None,
            {"offset_range_ms": [-100, 100], "uncertainty_ms": 25},
            {"offset_range_ms": [100, -100], "uncertainty_ms": 25,
             "method": "probe"},
            {"offset_range_ms": [-100, 100], "uncertainty_ms": -1,
             "method": "probe"},
            {"offset_range_ms": [-100, 100], "uncertainty_ms": 25,
             "method": " "},
            {"offset_range_ms": [True, 100], "uncertainty_ms": 25,
             "method": "probe"},
            {"offset_range_ms": [-100, 100], "uncertainty_ms": True,
             "method": "probe"},
            {"offset_range_ms": [-100, 100], "uncertainty_ms": 25,
             "method": 7},
        ):
            with self.subTest(alignment=broken):
                late = attempt(request_ms=2_000, terminal_w=1_000, playback_ms=2_000)
                self.assertFalse(
                    runner.command_delivery_after_session_end(late, broken))
                self.assertFalse(
                    runner.reclassify_out_of_window_command_attempt(late, broken))
                self.assertEqual(late.status, runner.AttemptStatus.FAILED)

        # Missing command timing, missing terminal, wake-only and passed
        # attempts are never reclassified.
        self.assertFalse(runner.command_delivery_after_session_end(
            attempt(None, 1_000), ALIGNED))
        self.assertFalse(runner.command_delivery_after_session_end(
            attempt(2_000, None), ALIGNED))
        self.assertFalse(runner.command_delivery_after_session_end(
            attempt(2_000, 1_000, wake_only=True), ALIGNED))
        self.assertFalse(runner.command_delivery_after_session_end(
            attempt(2_000, 1_000, status=runner.AttemptStatus.PASSED), ALIGNED))
        self.assertFalse(runner.command_delivery_after_session_end(
            attempt(2_000, 1_000, with_command=False), ALIGNED))

    def test_checkpoint_load_preserves_uncalibrated_classification(self) -> None:
        """An uncalibrated preserved run keeps its recorded classification:
        raw wall-clock ordering is never reclassified on load."""
        private_root = Path(tempfile.mkdtemp())
        slot = runner.MatrixSlot(idle_s=10, wake_only=False, ordinal=2)
        slot_open = runner.MatrixSlot(idle_s=10, wake_only=False, ordinal=3)
        producer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.DIAGNOSTIC, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        producer.attempts = [
            runner.MatrixAttempt(
                "trial-007", slot, 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                failures=["captured command transcript does not match the approved fixture expectation"],
                source_timing={"command": {"request_wall_clock_ms": 2_000}},
                target_timing={"events": [{"s": 16, "t": "SESSION_COMPLETED", "w": 1_000}]},
            ),
            runner.MatrixAttempt(
                "trial-008", slot_open, 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                source_timing={"command": {"request_wall_clock_ms": 900}},
                target_timing={"events": [{"s": 16, "t": "SESSION_COMPLETED", "w": 1_000}]},
            ),
        ]
        producer.checkpoint()

        consumer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.DIAGNOSTIC, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        consumer.load_checkpoint(producer.run_id)

        by_id = {attempt.trial_id: attempt for attempt in consumer.attempts}
        # No clock-alignment record and no same-target marker: preserved.
        self.assertIsNone(consumer.clock_alignment)
        self.assertEqual(by_id["trial-007"].status, runner.AttemptStatus.FAILED)
        self.assertEqual(
            by_id["trial-007"].classification,
            runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
        )
        self.assertEqual(by_id["trial-008"].status, runner.AttemptStatus.FAILED)
        # Both positions remain validly complete; the slot is not retried away.
        self.assertEqual(
            consumer.valid_failed_slots, {slot.position_id, slot_open.position_id})
        self.assertEqual(consumer.invalid_attempt_count, 0)
        self.assertEqual(
            consumer.completed_slots,
            {f"{slot.position_id}:1", f"{slot_open.position_id}:1"},
        )

    def test_checkpoint_load_reclassifies_with_validated_alignment(self) -> None:
        """A persisted validated clock-alignment record conclusive beyond its
        range reclassifies on load; the position stays retryable; the record
        round-trips through the checkpoint."""
        private_root = Path(tempfile.mkdtemp())
        slot = runner.MatrixSlot(idle_s=10, wake_only=False, ordinal=2)
        producer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.DIAGNOSTIC, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        producer.clock_alignment = {
            "source_alias": "s23u", "target_alias": "s21",
            "offset_range_ms": [-100, 100], "uncertainty_ms": 25,
            "method": "preflight_alignment_probe",
        }
        producer.attempts = [
            runner.MatrixAttempt(
                "trial-007", slot, 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                failures=["captured command transcript does not match the approved fixture expectation"],
                source_timing={"command": {
                    "request_wall_clock_ms": 2_000,
                    "playback_start_wall_clock_ms": 2_000,
                }},
                target_timing={"events": [{"s": 16, "t": "SESSION_COMPLETED", "w": 1_000}]},
            ),
        ]
        producer.checkpoint()

        consumer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.DIAGNOSTIC, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        consumer.load_checkpoint(producer.run_id)

        self.assertEqual(
            consumer.clock_alignment,
            {
                "source_alias": "s23u", "target_alias": "s21",
                "offset_range_ms": [-100, 100], "uncertainty_ms": 25,
                "method": "preflight_alignment_probe",
            },
        )
        attempt = consumer.attempts[0]
        self.assertEqual(attempt.status, runner.AttemptStatus.INVALID)
        self.assertEqual(
            attempt.invalid_reason,
            runner.InvalidReason.COMMAND_OUTSIDE_LISTENING_WINDOW,
        )
        # The required position is incomplete and stays eligible for bounded retry.
        self.assertFalse(consumer.is_matrix_complete())
        self.assertEqual(consumer._invalid_count_for(slot), 1)
        self.assertLess(consumer._invalid_count_for(slot), runner.VALID_MAX_ATTEMPTS)
        self.assertEqual(consumer.valid_failed_slots, set())
        self.assertEqual(consumer.invalid_attempt_count, 1)

    def test_checkpoint_load_rejects_alignment_for_other_devices(self) -> None:
        """An alignment record naming a different device pair is untrustworthy."""
        private_root = Path(tempfile.mkdtemp())
        slot = runner.MatrixSlot(idle_s=10, wake_only=False, ordinal=2)
        producer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.DIAGNOSTIC, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        producer.clock_alignment = {
            "source_alias": "other", "target_alias": "s21",
            "offset_range_ms": [-100, 100], "uncertainty_ms": 25,
            "method": "preflight_alignment_probe",
        }
        producer.attempts = [
            runner.MatrixAttempt(
                "trial-007", slot, 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                source_timing={"command": {"request_wall_clock_ms": 2_000}},
                target_timing={"events": [{"s": 16, "t": "SESSION_COMPLETED", "w": 1_000}]},
            ),
        ]
        producer.checkpoint()

        consumer = runner.AcousticWakeReliabilityRunner(
            runner.RunKind.DIAGNOSTIC, "s23u", "s21",
            FakeAdb("source"), FakeAdb("target"), private_root=private_root,
        )
        consumer.load_checkpoint(producer.run_id)
        self.assertIsNone(consumer.clock_alignment)
        self.assertEqual(consumer.attempts[0].status, runner.AttemptStatus.FAILED)

    def test_export_reconciles_counts_without_unproven_reclassification(self) -> None:
        """Unproven wall-clock ordering never changes exported totals: the
        attempt stays a valid product failure and counts reconcile."""
        harness = make_runner(runner.RunKind.DIAGNOSTIC)
        harness.target_identity = runner.DeviceIdentity(
            "s21", "samsung", "SM-G991B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.source_identity = runner.DeviceIdentity(
            "s23u", "samsung", "SM-S918B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.manifest = runner.RunManifest(
            "run-1", runner.RunKind.DIAGNOSTIC, runner.GateMode.DIAGNOSTIC,
            runner.MATRIX_ID, runner.MATRIX_VERSION, runner.utc_now(),
            "s23u", "s21", "set-1", {"natural_wake": "hash"}, None, None,
        )
        slots = runner.matrix_slots_for_target("s21")
        command_slots = [s for s in slots if not s.wake_only]
        harness.attempts = [
            runner.MatrixAttempt(
                "pass-1", command_slots[0], 1, runner.AttemptStatus.PASSED),
            runner.MatrixAttempt(
                "fail-in-window", command_slots[1], 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                source_timing={"command": {"request_wall_clock_ms": 900}},
                target_timing={"events": [{"s": 16, "t": "SESSION_COMPLETED", "w": 1_000}]},
            ),
            runner.MatrixAttempt(
                "fail-wall-clock-only", command_slots[2], 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                source_timing={"command": {
                    "request_wall_clock_ms": 2_000,
                    "playback_start_wall_clock_ms": 2_000,
                }},
                target_timing={"events": [{"s": 16, "t": "SESSION_COMPLETED", "w": 1_000}]},
            ),
        ]
        evidence = harness.export_evidence()
        summary = evidence["summary"]
        self.assertEqual(summary["total_attempts"], 3)
        self.assertEqual(summary["valid"], 3)
        self.assertEqual(summary["passed"], 1)
        self.assertEqual(summary["failed"], 2)
        self.assertEqual(summary["invalid"], 0)
        by_name = {case["name"]: case for case in evidence["cases"]}
        self.assertEqual(by_name["fail-wall-clock-only"]["status"], "failed")
        self.assertEqual(
            by_name["fail-wall-clock-only"]["failure_classification"],
            "command_capture_or_routing_failure",
        )
        self.assertEqual(by_name["fail-in-window"]["status"], "failed")
        valid, issues = evidence_metrics.validate_record(evidence, [])
        self.assertTrue(valid, issues)

    def test_export_reclassifies_only_with_persisted_proof(self) -> None:
        """With same-target pre-dispatch proof the attempt is reclassified at
        export and summary counts reconcile; schema validation passes."""
        harness = make_runner(runner.RunKind.DIAGNOSTIC)
        harness.target_identity = runner.DeviceIdentity(
            "s21", "samsung", "SM-G991B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.source_identity = runner.DeviceIdentity(
            "s23u", "samsung", "SM-S918B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.manifest = runner.RunManifest(
            "run-1", runner.RunKind.DIAGNOSTIC, runner.GateMode.DIAGNOSTIC,
            runner.MATRIX_ID, runner.MATRIX_VERSION, runner.utc_now(),
            "s23u", "s21", "set-1", {"natural_wake": "hash"}, None, None,
        )
        slots = runner.matrix_slots_for_target("s21")
        command_slots = [s for s in slots if not s.wake_only]
        harness.attempts = [
            runner.MatrixAttempt(
                "pass-1", command_slots[0], 1, runner.AttemptStatus.PASSED),
            runner.MatrixAttempt(
                "fail-in-window", command_slots[1], 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                source_timing={"command": {"request_wall_clock_ms": 900}},
                target_timing={"events": [{"s": 16, "t": "SESSION_COMPLETED", "w": 1_000}]},
            ),
            runner.MatrixAttempt(
                "fail-proven-out-of-window", command_slots[2], 1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                source_timing={"command": {"request_wall_clock_ms": 2_000}},
                target_timing={
                    "events": [{"s": 16, "t": "SESSION_COMPLETED", "w": 1_000}],
                    "pre_dispatch_terminal_sequence": 16,
                },
            ),
        ]
        evidence = harness.export_evidence()
        summary = evidence["summary"]
        self.assertEqual(summary["total_attempts"], 3)
        self.assertEqual(summary["valid"], 2)
        self.assertEqual(summary["passed"], 1)
        self.assertEqual(summary["failed"], 1)
        self.assertEqual(summary["invalid"], 1)
        by_name = {case["name"]: case for case in evidence["cases"]}
        self.assertEqual(by_name["fail-proven-out-of-window"]["status"], "invalid")
        self.assertEqual(
            by_name["fail-proven-out-of-window"]["invalid_reason"],
            "command_outside_listening_window",
        )
        self.assertFalse(evidence["wake_reliability"]["complete_valid_matrix"])
        valid, issues = evidence_metrics.validate_record(evidence, [])
        self.assertTrue(valid, issues)
    def test_run_trial_persists_playback_start_wall_clock_ms(self) -> None:
        """run_trial() persists the source helper's validated physical
        ``started`` event wall clock as playback_start_wall_clock_ms while
        keeping the request fields unchanged."""
        harness, envelopes, _ = self._command_harness()
        broken_final = copy.deepcopy(envelopes["final"])
        broken_final["events"] = [
            item for item in broken_final["events"] if item["t"] != "STT_FINAL"
        ]
        broken_final["highestSequence"] = broken_final["events"][-1]["s"]
        provider_call = self._provider_snapshots(
            {
                "boundary": envelopes["boundary"],
                "open": envelopes["open"],
                "final": broken_final,
            },
            ["boundary", "open", "final"],
        )
        call_order: list[str] = []

        def invoke_command_source(**kwargs):
            command_result = source_result("trial-persist-cmd", "qwen_command")
            command_result["command_transcript_sha256"] = "b" * 64
            command_events = [event(15, "COMMAND_ROUTING_RESULT")]
            return command_result, command_events, envelope(command_events, lowest=15)

        with patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(harness, "_invoke_source", side_effect=self._invoke_wake(call_order)), \
                patch.object(harness, "_invoke_command_source_with_armed_wait", side_effect=invoke_command_source), \
                patch.object(harness, "_wait_for_target_events", side_effect=self._target_wait(call_order)), \
                patch.object(harness, "_snapshot_target_state", return_value={"reachable": True}), \
                patch.object(harness, "_snapshot_source_state", return_value={"reachable": True}), \
                patch.object(harness, "_environment_failures", return_value=[]), \
                patch.object(harness, "_source_environment_failures", return_value=[]), \
                patch.object(harness, "checkpoint"), \
                patch.object(runner.time, "sleep"):
            attempt = harness.run_trial(
                "trial-persist",
                runner.MatrixSlot(idle_s=1, wake_only=False, ordinal=2),
                "natural_wake",
                "qwen_command",
            )

        command_timing = attempt.source_timing["command"]
        # The validated physical ``started`` event's wall clock is persisted
        # under the dedicated field; request timing is kept untouched.
        self.assertEqual(command_timing["playback_start_wall_clock_ms"], 1_705_300_000_423)
        self.assertEqual(command_timing["request_wall_clock_ms"], 1_705_300_000_123)
        self.assertEqual(
            attempt.source_timing["clock_domain"], "source_device_elapsed_realtime"
        )

    def test_request_inside_window_but_playback_after_terminal_reclassifies(self) -> None:
        """A request submitted while the session was still open is not proof:
        when the source helper's playback start is conclusively after the
        terminal event under a validated alignment, the attempt is
        reclassified INVALID with the explicit out-of-window reason and the
        matrix slot remains retryable."""
        def attempt(request_ms: int, playback_ms: int) -> runner.MatrixAttempt:
            return runner.MatrixAttempt(
                "t", runner.MatrixSlot(idle_s=10, wake_only=False, ordinal=1),
                1, runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                source_timing={"command": {
                    "request_wall_clock_ms": request_ms,
                    "playback_start_wall_clock_ms": playback_ms,
                }},
                target_timing={"events": [
                    {"s": 16, "t": "SESSION_COMPLETED", "w": 1_000, "g": 11, "i": 10},
                ]},
            )

        ALIGNED = {
            "source_alias": "s23u", "target_alias": "s21",
            "offset_range_ms": [-100, 100], "uncertainty_ms": 25,
            "method": "preflight_alignment_probe",
        }
        # Request submitted 100 ms BEFORE the terminal event, but the audible
        # playback only began 200 ms AFTER it (audio preparation lagged).
        late_playback = attempt(request_ms=900, playback_ms=1_200)
        self.assertTrue(runner.command_delivery_after_session_end(late_playback, ALIGNED))
        self.assertTrue(
            runner.reclassify_out_of_window_command_attempt(late_playback, ALIGNED)
        )
        self.assertEqual(late_playback.status, runner.AttemptStatus.INVALID)
        self.assertIsNone(late_playback.classification)
        self.assertEqual(
            late_playback.invalid_reason,
            runner.InvalidReason.COMMAND_OUTSIDE_LISTENING_WINDOW,
        )
        self.assertTrue(any("proven" in f for f in late_playback.failures))
        self.assertIn("listening_window_closed", late_playback.invalid_details)

        # The same request time with playback start inside the window is not
        # reclassified: a genuine in-window failure remains a valid product
        # failure.
        in_window_playback = attempt(request_ms=900, playback_ms=700)
        self.assertFalse(
            runner.command_delivery_after_session_end(in_window_playback, ALIGNED)
        )
        self.assertFalse(
            runner.reclassify_out_of_window_command_attempt(in_window_playback, ALIGNED)
        )
        self.assertEqual(in_window_playback.status, runner.AttemptStatus.FAILED)
        self.assertEqual(
            in_window_playback.classification,
            runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
        )

    def test_missing_playback_start_preserves_classification_even_when_request_late(self) -> None:
        """Historical attempts without playback-start wall-clock evidence are
        never reclassified using request submission time."""
        ALIGNED = {
            "source_alias": "s23u", "target_alias": "s21",
            "offset_range_ms": [-100, 100], "uncertainty_ms": 25,
            "method": "preflight_alignment_probe",
        }
        late_request_only = runner.MatrixAttempt(
            "t", runner.MatrixSlot(idle_s=10, wake_only=False, ordinal=1),
            1, runner.AttemptStatus.FAILED,
            classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
            source_timing={"command": {"request_wall_clock_ms": 2_000}},
            target_timing={"events": [
                {"s": 16, "t": "SESSION_COMPLETED", "w": 1_000, "g": 11, "i": 10},
            ]},
        )
        self.assertFalse(
            runner.command_delivery_after_session_end(late_request_only, ALIGNED)
        )
        self.assertFalse(
            runner.reclassify_out_of_window_command_attempt(late_request_only, ALIGNED)
        )
        self.assertEqual(late_request_only.status, runner.AttemptStatus.FAILED)
        self.assertEqual(
            late_request_only.classification,
            runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
        )

    def test_playback_start_late_without_validated_alignment_preserves_classification(self) -> None:
        """Playback start after the raw target terminal timestamp is never
        proof without a validated clock-alignment record: the classification
        is preserved."""
        late_playback = runner.MatrixAttempt(
            "t", runner.MatrixSlot(idle_s=10, wake_only=False, ordinal=1),
            1, runner.AttemptStatus.FAILED,
            classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
            source_timing={"command": {
                "request_wall_clock_ms": 2_000,
                "playback_start_wall_clock_ms": 2_000,
            }},
            target_timing={"events": [
                {"s": 16, "t": "SESSION_COMPLETED", "w": 1_000, "g": 11, "i": 10},
            ]},
        )
        self.assertFalse(runner.command_delivery_after_session_end(late_playback))
        self.assertFalse(
            runner.reclassify_out_of_window_command_attempt(late_playback)
        )
        self.assertEqual(late_playback.status, runner.AttemptStatus.FAILED)
        self.assertEqual(
            late_playback.classification,
            runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
        )
        # An alignment record naming a different device pair is never loaded
        # into the runner (see test_checkpoint_load_rejects_alignment_for_other_devices)
        # and an inconclusive range is likewise ignored.
        self.assertFalse(runner.command_delivery_after_session_end(
            late_playback, {
                "offset_range_ms": [-1_200, -1_100], "uncertainty_ms": 25,
                "method": "probe",
            }))

    def test_export_reclassifies_playback_out_of_window_with_validated_alignment(self) -> None:
        """Export reclassifies a playback-start-proven out-of-window attempt
        using the persisted validated alignment; summary totals and schema /
        privacy validation reconcile."""
        harness = make_runner(runner.RunKind.DIAGNOSTIC)
        harness.target_identity = runner.DeviceIdentity(
            "s21", "samsung", "SM-G991B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.source_identity = runner.DeviceIdentity(
            "s23u", "samsung", "SM-S918B", "15", "35", "fingerprint", "pkg", 1,
        )
        harness.manifest = runner.RunManifest(
            "run-1", runner.RunKind.DIAGNOSTIC, runner.GateMode.DIAGNOSTIC,
            runner.MATRIX_ID, runner.MATRIX_VERSION, runner.utc_now(),
            "s23u", "s21", "set-1", {"natural_wake": "hash"}, None, None,
        )
        harness.clock_alignment = {
            "source_alias": "s23u", "target_alias": "s21",
            "offset_range_ms": [-100, 100], "uncertainty_ms": 25,
            "method": "preflight_alignment_probe",
        }
        slots = runner.matrix_slots_for_target("s21")
        command_slots = [s for s in slots if not s.wake_only]
        harness.attempts = [
            runner.MatrixAttempt(
                "pass-1", command_slots[0], 1, runner.AttemptStatus.PASSED),
            runner.MatrixAttempt(
                "fail-playback-out-of-window", command_slots[1], 1,
                runner.AttemptStatus.FAILED,
                classification=runner.FailureClassification.COMMAND_CAPTURE_OR_ROUTING_FAILURE,
                source_timing={"command": {
                    "request_wall_clock_ms": 900,
                    "playback_start_wall_clock_ms": 1_200,
                }},
                target_timing={"events": [
                    {"s": 16, "t": "SESSION_COMPLETED", "w": 1_000, "g": 11, "i": 10},
                ]},
            ),
        ]
        evidence = harness.export_evidence()
        summary = evidence["summary"]
        self.assertEqual(summary["total_attempts"], 2)
        self.assertEqual(summary["valid"], 1)
        self.assertEqual(summary["passed"], 1)
        self.assertEqual(summary["failed"], 0)
        self.assertEqual(summary["invalid"], 1)
        by_name = {case["name"]: case for case in evidence["cases"]}
        self.assertEqual(by_name["fail-playback-out-of-window"]["status"], "invalid")
        self.assertEqual(
            by_name["fail-playback-out-of-window"]["invalid_reason"],
            "command_outside_listening_window",
        )
        # The sanitised case still carries the persisted playback-start field.
        command_timing = by_name["fail-playback-out-of-window"]["source_timing"]["command"]
        self.assertEqual(command_timing["playback_start_wall_clock_ms"], 1_200)
        valid, issues = evidence_metrics.validate_record(evidence, [])
        self.assertTrue(valid, issues)


class StatefulPropAdb(FakeAdb):
    """FakeAdb with a real log.tag.WakeWordDiag property store."""

    def __init__(self, serial: str) -> None:
        super().__init__(serial)
        self.props: dict[str, str] = {"log.tag.WakeWordDiag": "INFO"}

    def shell(self, *args: str, timeout: float = 30.0, check: bool = True) -> str:
        if args[:2] == ("getprop", "log.tag.WakeWordDiag"):
            return self.props.get("log.tag.WakeWordDiag", "")
        if args[:2] == ("setprop", "log.tag.WakeWordDiag"):
            self.props["log.tag.WakeWordDiag"] = args[2]
            return ""
        return super().shell(*args, timeout=timeout, check=check)


class EvidenceRetentionTests(unittest.TestCase):
    """#1410 evidence-retention prerequisites: per-trial WakeWordDiag capture
    energy, source playback metadata, diagnostics property lifecycle, and
    sanitised publication exclusions.  No second microphone consumer exists:
    the wake detector is the only wake-path AudioRecord involved."""

    # ── WakeWordDiag gate-summary parsing ─────────────────────────────

    SUMMARY_GEN4 = (
        "08-08 21:00:01.001  1234  5678 D WakeWordDiag: "
        "WakeWordDetector: gateExitSummary gen=4 stage3Evals=1037 "
        "maxConfidence=0.00143 maxConfidenceOffsetFrames=619 "
        "lowVerifyEntered=false lowVerifyAccepted=false "
        "gatedProbeExecutions=10 episodePeakRms=3593.4 "
        "maxWindowPeakRms=3593.4 maxWindowMeanRms=1307.6"
    )
    SUMMARY_GEN4_SECOND = (
        "08-08 21:00:03.001  1234  5678 D WakeWordDiag: "
        "WakeWordDetector: gateExitSummary gen=4 stage3Evals=422 "
        "maxConfidence=0.907 maxConfidenceOffsetFrames=11 "
        "lowVerifyEntered=false lowVerifyAccepted=false "
        "gatedProbeExecutions=3 episodePeakRms=3390.5 "
        "maxWindowPeakRms=3390.5 maxWindowMeanRms=1090.25"
    )
    SUMMARY_GEN5 = (
        "08-08 21:00:02.001  1234  5678 D WakeWordDiag: "
        "WakeWordDetector: gateExitSummary gen=5 stage3Evals=1 "
        "maxConfidence=0.9 maxConfidenceOffsetFrames=2 "
        "lowVerifyEntered=false lowVerifyAccepted=false "
        "gatedProbeExecutions=0 episodePeakRms=100.0 "
        "maxWindowPeakRms=100.0 maxWindowMeanRms=50.0"
    )

    def test_wakeword_diag_summaries_parse_exact_values(self) -> None:
        text = "\n".join([
            "08-08 21:00:00.001  1234  5678 D WakeWordDiag: "
            "WakeWordDetector: diagnostics elapsedMs=600000 audioFrames=7500",
            self.SUMMARY_GEN4,
            self.SUMMARY_GEN5,
            self.SUMMARY_GEN4_SECOND,
            "08-08 21:00:04.001  1234  5678 I KernelAI: "
            "WakeWordDetector: detected (high confidence=0.9)",
        ])
        projected = runner.parse_wakeword_diag_summaries(text, generation=4)
        self.assertEqual(len(projected), 2)
        first, second = projected
        self.assertEqual(first["generation_id"], 4)
        self.assertIsInstance(first["episode_peak_rms"], float)
        self.assertEqual(first["episode_peak_rms"], 3593.4)
        self.assertEqual(first["max_window_peak_rms"], 3593.4)
        self.assertEqual(first["max_window_mean_rms"], 1307.6)
        self.assertEqual(first["max_confidence"], 0.00143)
        self.assertEqual(first["stage3_evals"], 1037)
        self.assertEqual(first["max_confidence_offset_frames"], 619)
        self.assertEqual(first["gated_probe_executions"], 10)
        self.assertIs(first["low_verify_entered"], False)
        self.assertEqual(second["episode_peak_rms"], 3390.5)
        self.assertEqual(second["max_window_mean_rms"], 1090.25)
        # The other-generation summary and non-summary lines are excluded.
        self.assertTrue(all(item["generation_id"] == 4 for item in projected))

    def test_wakeword_diag_summaries_none_and_missing_are_honest(self) -> None:
        text = (
            "08-08 21:00:01.001  1234  5678 D WakeWordDiag: "
            "WakeWordDetector: gateExitSummary gen=4 stage3Evals=5 "
            "maxConfidence=0.5 maxConfidenceOffsetFrames=3 "
            "lowVerifyEntered=false lowVerifyAccepted=false "
            "gatedProbeExecutions=0 maxWindowPeakRms=none maxWindowMeanRms=none"
        )
        projected = runner.parse_wakeword_diag_summaries(text, generation=4)
        self.assertEqual(len(projected), 1)
        entry = projected[0]
        self.assertIsNone(entry["episode_peak_rms"])  # key absent on-device
        self.assertIsNone(entry["max_window_peak_rms"])
        self.assertIsNone(entry["max_window_mean_rms"])
        self.assertEqual(entry["max_confidence"], 0.5)
        # No matching generation -> empty, never fabricated.
        self.assertEqual(
            runner.parse_wakeword_diag_summaries(text, generation=5), []
        )
        self.assertEqual(runner.parse_wakeword_diag_summaries(text, None), [])

    def test_wakeword_diag_summaries_malformed_values_fail_closed(self) -> None:
        text = (
            "08-08 21:00:01.001  1234  5678 D WakeWordDiag: "
            "WakeWordDetector: gateExitSummary gen=4 stage3Evals=5 "
            "maxConfidence=0.5 maxConfidenceOffsetFrames=3 "
            "lowVerifyEntered=false lowVerifyAccepted=false "
            "gatedProbeExecutions=0 episodePeakRms=loud"
        )
        with self.assertRaises(runner.HarnessError):
            runner.parse_wakeword_diag_summaries(text, generation=4)

    def test_wakeword_diag_summaries_malformed_generation_fails_closed(self) -> None:
        text = (
            "08-08 21:00:01.001  1234  5678 D WakeWordDiag: "
            "WakeWordDetector: gateExitSummary gen=abc stage3Evals=5 "
            "maxConfidence=0.5"
        )
        with self.assertRaises(runner.HarnessError):
            runner.parse_wakeword_diag_summaries(text, generation=4)

    def test_source_playback_duration_is_derived_or_none(self) -> None:
        result = {
            "playback_start_monotonic_ms": 1_000,
            "completion_monotonic_ms": 3_098,
        }
        self.assertEqual(runner.source_playback_duration_ms(result), 2_098)
        self.assertIsNone(runner.source_playback_duration_ms({}))
        self.assertIsNone(runner.source_playback_duration_ms(
            {"playback_start_monotonic_ms": 3_000, "completion_monotonic_ms": 1_000}
        ))

    # ── Evidence publication ───────────────────────────────────────────

    def _manifest_and_identities(self):
        manifest = runner.RunManifest(
            "run-1", runner.RunKind.REGRESSION, runner.GateMode.RELEASE,
            runner.MATRIX_ID, runner.MATRIX_VERSION, runner.utc_now(),
            "s23u", "s21", "set-1", {"natural_wake": "a" * 64}, "cue-v1", None,
        )
        target = runner.DeviceIdentity("s21", "samsung", "SM-G991B", "15", "35", "fp", "pkg", 1)
        source = runner.DeviceIdentity("s23u", "samsung", "SM-S918B", "15", "35", "fp", "pkg", 1)
        return manifest, target, source

    def test_evidence_retains_energy_and_source_volume_correlated_to_trial(self) -> None:
        manifest, target, source = self._manifest_and_identities()
        slots = runner.matrix_slots_for_target("s21")
        attempt = runner.MatrixAttempt(
            "trial-1", slots[0], 1, runner.AttemptStatus.PASSED,
            fixture_sha256="a" * 64,
            source_timing={
                "clock_domain": "source_device_elapsed_realtime",
                "request_wall_clock_ms": 1_000,
                "playback_start_monotonic_ms": 1_000,
                "completion_monotonic_ms": 3_098,
                "playback_start_wall_clock_ms": 5_000,
                "playback_duration_ms": 2_098,
            },
            source_outcome={
                "completion_status": "completed", "cleanup_success": True,
                "exact_restoration_verified": True,
                "output_route_during": "BUILT_IN_SPEAKER",
                "focus_result": "granted", "timeout": False,
                "overlap_rejected": False,
                "requested_volume": 9, "applied_volume": 9, "maximum_volume": 15,
            },
            target_timing={
                "events": complete_events(runner.TrialType.WAKE_ONLY),
                "gate_episode_energy": [{
                    "generation_id": 4, "stage3_evals": 1037,
                    "max_confidence": 0.00143,
                    "max_confidence_offset_frames": 619,
                    "low_verify_entered": False, "low_verify_accepted": False,
                    "gated_probe_executions": 10,
                    "episode_peak_rms": 3593.4,
                    "max_window_peak_rms": 3593.4,
                    "max_window_mean_rms": 1307.6,
                }],
            },
        )
        evidence = runner.render_evidence(
            manifest, target, source, "1.0.0", [attempt], None, True,
            "BUILT_IN_SPEAKER",
        )
        case = evidence["cases"][0]
        energy = case["target_timing"]["gate_episode_energy"][0]
        self.assertEqual(energy["episode_peak_rms"], 3593.4)
        self.assertEqual(energy["max_window_peak_rms"], 3593.4)
        self.assertEqual(energy["max_window_mean_rms"], 1307.6)
        self.assertEqual(energy["max_confidence"], 0.00143)
        self.assertEqual(energy["generation_id"], 4)
        self.assertEqual(case["source_timing"]["playback_start_wall_clock_ms"], 5_000)
        self.assertEqual(case["source_timing"]["playback_duration_ms"], 2_098)
        self.assertEqual(case["source_outcome"]["applied_volume"], 9)
        self.assertEqual(case["source_outcome"]["requested_volume"], 9)
        self.assertEqual(case["source_outcome"]["output_route_during"], "BUILT_IN_SPEAKER")
        self.assertEqual(case["source_outcome"]["focus_result"], "granted")
        self.assertEqual(case["fixture"]["sha256"], "a" * 64)
        output = Path(tempfile.mkdtemp())
        runner.write_sanitized_summary(output, evidence, private_run_dir=output)
        valid, issues = evidence_metrics.validate_record(evidence, [])
        self.assertTrue(valid, issues)

    def test_historical_evidence_without_new_fields_remains_valid(self) -> None:
        """Old attempts without the retention fields stay schema-valid — the
        new fields are optional additions, not a schema break."""
        manifest, target, source = self._manifest_and_identities()
        slots = runner.matrix_slots_for_target("s21")
        attempt = runner.MatrixAttempt(
            "trial-1", slots[0], 1, runner.AttemptStatus.PASSED,
            target_timing={"events": complete_events(runner.TrialType.WAKE_ONLY)},
        )
        evidence = runner.render_evidence(
            manifest, target, source, "1.0.0", [attempt], None, True,
            "BUILT_IN_SPEAKER",
        )
        case = evidence["cases"][0]
        self.assertNotIn("gate_episode_energy", case["target_timing"])
        valid, issues = evidence_metrics.validate_record(evidence, [])
        self.assertTrue(valid, issues)

    # ── Private retention and publication exclusions ───────────────────

    def test_private_wakeworddiag_dump_survives_finalise_and_is_excluded_from_publication(self) -> None:
        """A completed private run directory containing the retained
        WakeWordDiag dump is NOT removed by normal successful
        cleanup/finalisation, and the raw dump is never copied into the
        sanitised evidence."""
        harness = make_runner()
        run_dir = Path(tempfile.mkdtemp())
        harness.run_dir = run_dir
        harness.trials_dir = run_dir / "trials"
        harness.target_identity = runner.DeviceIdentity("s21", "samsung", "SM-G991B", "15", "35", "fp", "pkg", 1)
        harness.source_identity = runner.DeviceIdentity("s23u", "samsung", "SM-S918B", "15", "35", "fp", "pkg", 1)
        raw_dump = self.SUMMARY_GEN4 + "\n"
        artifact_dir = run_dir / "trials/trial-1/target"
        artifact_dir.mkdir(parents=True)
        (artifact_dir / "wakeworddiag.log").write_text(raw_dump)
        slots = runner.matrix_slots_for_target("s21")
        harness.attempts = [
            runner.MatrixAttempt(
                "trial-1", slots[0], 1, runner.AttemptStatus.PASSED,
                target_timing={
                    "events": complete_events(runner.TrialType.WAKE_ONLY),
                    "gate_episode_energy": [{
                        "generation_id": 4, "stage3_evals": 1037,
                        "max_confidence": 0.00143,
                        "max_confidence_offset_frames": 619,
                        "low_verify_entered": False, "low_verify_accepted": False,
                        "gated_probe_executions": 10,
                        "episode_peak_rms": 3593.4,
                        "max_window_peak_rms": 3593.4,
                        "max_window_mean_rms": 1307.6,
                    }],
                },
            ),
        ]
        evidence = runner.finalize_evidence(harness)
        # Retention: the private run directory and its raw dump survive.
        self.assertTrue(run_dir.is_dir())
        self.assertTrue((artifact_dir / "wakeworddiag.log").is_file())
        self.assertEqual((artifact_dir / "wakeworddiag.log").read_text(), raw_dump)
        self.assertTrue(harness.cleanup_verified, harness.cleanup_failures)
        # Publication: the raw dump is not copied and no raw line is exposed.
        self.assertTrue((harness.sanitized_dir / "evidence.json").is_file())
        self.assertEqual(
            list(harness.sanitized_dir.rglob("wakeworddiag.log")), []
        )
        public_text = (harness.sanitized_dir / "evidence.json").read_text()
        self.assertNotIn("gateExitSummary", public_text)
        self.assertIn("3593.4", public_text)
        case = next(c for c in evidence["cases"] if c["trial_id"] == "trial-1")
        self.assertEqual(
            case["target_timing"]["gate_episode_energy"][0]["episode_peak_rms"],
            3593.4,
        )
        # The checkpoint correlates the private artifact with the trial.
        checkpoint = json.loads((run_dir / "checkpoint.json").read_text())
        restored = next(a for a in checkpoint["attempts"] if a["trial_id"] == "trial-1")
        self.assertNotIn("target_capture", restored)

    # ── Trial wiring ───────────────────────────────────────────────────

    def test_trial_wiring_projects_energy_into_evidence(self) -> None:
        """An integrated run_trial parses the bounded WakeWordDiag window into
        target_timing.gate_episode_energy on the same attempt."""
        harness = make_runner()
        harness.run_dir = Path(tempfile.mkdtemp())
        fixture_sha256 = source_result()["fixture_sha256"]
        harness.installed_fixture_hashes = {"natural_wake": fixture_sha256}
        harness.preflight_approval = {
            "source_volume_index": 7,
            "cue_audibility_evidence_verified": True,
            "source_environment_state": {},
            "target_environment_state": {},
        }
        harness.cue_audibility_evidence_verified = True
        target = harness.target
        target.responses["shell logcat -c"] = ""
        target.responses["shell logcat -d -s 'WakeWordDiag:*'"] = (
            self.SUMMARY_GEN4 + "\n" + self.SUMMARY_GEN5 + "\n"
        )

        final_events = complete_events(runner.TrialType.WAKE_ONLY)
        final_envelope = envelope(final_events, lowest=1)
        boundary_envelope = envelope([
            event(3, "DETECTOR_GENERATION_STARTED", generation=4, session=0),
        ], lowest=3)
        snapshots = iter((boundary_envelope, final_envelope))

        def provider_call(method, extras=None, timeout=15.0):
            if method == runner.TARGET_METHOD_GET_SEQUENCE:
                return runner.TARGET_RESULT_OK, "3"
            if method == runner.TARGET_METHOD_GET_SNAPSHOT:
                return runner.TARGET_RESULT_OK, json.dumps(next(snapshots))
            self.fail(f"unexpected provider method {method}")

        def invoke_source(trial_id, fixture_id, volume_index):
            return source_result(trial_id, fixture_id)

        def target_wait(**kwargs):
            event_type = kwargs["event_type"]
            waited = event(
                {"STT_READY": 11, "DETECTOR_REARMED": 18}[event_type],
                event_type,
            )
            return [waited], envelope([waited], lowest=waited["s"])

        with patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(harness, "_invoke_source", side_effect=invoke_source), \
                patch.object(harness, "_wait_for_target_events", side_effect=target_wait), \
                patch.object(harness, "_snapshot_target_state", return_value={"reachable": True}), \
                patch.object(harness, "_snapshot_source_state", return_value={"reachable": True}), \
                patch.object(harness, "_environment_failures", return_value=[]), \
                patch.object(harness, "_source_environment_failures", return_value=[]), \
                patch.object(harness, "checkpoint"), \
                patch.object(runner.time, "sleep"):
            attempt = harness.run_trial(
                "trial-1",
                runner.MatrixSlot(idle_s=1, wake_only=True, ordinal=1),
                "natural_wake",
                None,
            )

        self.assertEqual(attempt.status, runner.AttemptStatus.PASSED, attempt.__dict__)
        energy = attempt.target_timing["gate_episode_energy"]
        self.assertEqual(len(energy), 1)
        self.assertEqual(energy[0]["generation_id"], 4)
        self.assertEqual(energy[0]["episode_peak_rms"], 3593.4)
        self.assertEqual(energy[0]["max_window_peak_rms"], 3593.4)
        self.assertEqual(energy[0]["max_window_mean_rms"], 1307.6)
        self.assertEqual(energy[0]["max_confidence"], 0.00143)
        # The private bounded dump is retained per trial.
        private_dump = harness.run_dir / "trials/trial-1/target/wakeworddiag.log"
        self.assertTrue(private_dump.is_file())
        self.assertIn("gateExitSummary", private_dump.read_text())
        # Source playback evidence projected onto the same attempt.
        self.assertEqual(attempt.source_timing["playback_duration_ms"], 1_900)
        self.assertIsInstance(attempt.source_timing["playback_start_wall_clock_ms"], int)
        self.assertEqual(attempt.source_outcome["applied_volume"], 7)

    def test_trial_wiring_records_energy_parse_error_honestly(self) -> None:
        harness = make_runner()
        harness.run_dir = Path(tempfile.mkdtemp())
        fixture_sha256 = source_result()["fixture_sha256"]
        harness.installed_fixture_hashes = {"natural_wake": fixture_sha256}
        harness.preflight_approval = {
            "source_volume_index": 7,
            "cue_audibility_evidence_verified": True,
            "source_environment_state": {},
            "target_environment_state": {},
        }
        harness.cue_audibility_evidence_verified = True
        target = harness.target
        target.responses["shell logcat -c"] = ""
        target.responses["shell logcat -d -s 'WakeWordDiag:*'"] = (
            "08-08 21:00:01.001  1234  5678 D WakeWordDiag: "
            "WakeWordDetector: gateExitSummary gen=4 stage3Evals=5 "
            "maxConfidence=0.5 maxConfidenceOffsetFrames=3 "
            "lowVerifyEntered=false lowVerifyAccepted=false "
            "gatedProbeExecutions=0 episodePeakRms=loud"
        )

        final_events = complete_events(runner.TrialType.WAKE_ONLY)
        final_envelope = envelope(final_events, lowest=1)
        boundary_envelope = envelope([
            event(3, "DETECTOR_GENERATION_STARTED", generation=4, session=0),
        ], lowest=3)
        snapshots = iter((boundary_envelope, final_envelope))

        def provider_call(method, extras=None, timeout=15.0):
            if method == runner.TARGET_METHOD_GET_SEQUENCE:
                return runner.TARGET_RESULT_OK, "3"
            if method == runner.TARGET_METHOD_GET_SNAPSHOT:
                return runner.TARGET_RESULT_OK, json.dumps(next(snapshots))
            self.fail(f"unexpected provider method {method}")

        def invoke_source(trial_id, fixture_id, volume_index):
            return source_result(trial_id, fixture_id)

        def target_wait(**kwargs):
            event_type = kwargs["event_type"]
            waited = event(
                {"STT_READY": 11, "DETECTOR_REARMED": 18}[event_type],
                event_type,
            )
            return [waited], envelope([waited], lowest=waited["s"])

        with patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(harness, "_invoke_source", side_effect=invoke_source), \
                patch.object(harness, "_wait_for_target_events", side_effect=target_wait), \
                patch.object(harness, "_snapshot_target_state", return_value={"reachable": True}), \
                patch.object(harness, "_snapshot_source_state", return_value={"reachable": True}), \
                patch.object(harness, "_environment_failures", return_value=[]), \
                patch.object(harness, "_source_environment_failures", return_value=[]), \
                patch.object(harness, "checkpoint"), \
                patch.object(runner.time, "sleep"):
            attempt = harness.run_trial(
                "trial-1",
                runner.MatrixSlot(idle_s=1, wake_only=True, ordinal=1),
                "natural_wake",
                None,
            )

        self.assertEqual(attempt.status, runner.AttemptStatus.PASSED, attempt.__dict__)
        self.assertNotIn("gate_episode_energy", attempt.target_timing)
        self.assertIn(
            "wakeword_diag_parse_failed",
            attempt.target_timing["gate_episode_energy_error"],
        )

    # ── Diagnostics property lifecycle ─────────────────────────────────

    REARM_BROADCAST_KEY = (
        "shell am broadcast -n com.kernel.ai.debug/"
        "com.kernel.ai.debug.wake.WakeDetectorRearmReceiver "
        "-a com.kernel.ai.debug.action.REARM_WAKE_DETECTOR"
    )

    def _journal_envelope(self, sequence: int, events: list[dict]) -> dict:
        return {
            "lowestSequence": 1, "highestSequence": sequence,
            "overflowed": False, "events": events,
        }

    def _provider_for_rearm(
        self,
        *,
        post_rearm_events: list[dict],
        post_rearm_sequence: int | None = None,
        pre_rearm_sequence: int = 10,
    ):
        """Build a journal provider whose post-rearm view starts once the
        re-arm broadcast appears in the fake ADB command log."""
        def provider_call(method, extras=None, timeout=15.0):
            rearmed = any(
                "REARM_WAKE_DETECTOR" in " ".join(command)
                for command in self._rearm_target.commands
            )
            if not rearmed:
                sequence = pre_rearm_sequence
                events = [event(1, "DETECTOR_GENERATION_STARTED", generation=4, session=0)]
            else:
                events = [
                    event(1, "DETECTOR_GENERATION_STARTED", generation=4, session=0),
                    *post_rearm_events,
                ]
                sequence = post_rearm_sequence if post_rearm_sequence is not None else max(
                    ev["s"] for ev in events
                )
            if method == runner.TARGET_METHOD_GET_SEQUENCE:
                return runner.TARGET_RESULT_OK, str(sequence)
            if method == runner.TARGET_METHOD_GET_SNAPSHOT:
                return runner.TARGET_RESULT_OK, json.dumps(
                    self._journal_envelope(sequence, events)
                )
            self.fail(f"unexpected provider method {method}")
        return provider_call

    def test_target_diagnostics_enabled_rearmed_and_restored_on_cleanup(self) -> None:
        """Setting DEBUG must re-arm the detector and the verification must
        wait for an authoritative post-boundary DETECTOR_GENERATION_STARTED
        (the diagnostics gate is evaluated at generation start); cleanup must
        restore the original property."""
        harness = make_runner()
        target = harness.target = StatefulPropAdb("target")
        self._rearm_target = target
        target.responses[self.REARM_BROADCAST_KEY] = (
            'Broadcast completed: result=0, data="rearmed"'
        )
        post_rearm_events = [
            event(11, "DETECTOR_REARMED", generation=5, session=0),
            event(12, "DETECTOR_GENERATION_STARTED", generation=5, session=0),
        ]
        with patch.object(
            harness, "_call_target_provider",
            side_effect=self._provider_for_rearm(post_rearm_events=post_rearm_events),
        ), patch.object(runner.time, "sleep"):
            harness.ensure_target_diagnostics()
        self.assertEqual(target.props["log.tag.WakeWordDiag"], "DEBUG")
        self.assertTrue(
            any("REARM_WAKE_DETECTOR" in " ".join(command) for command in target.commands)
        )
        harness.cleanup()
        self.assertEqual(target.props["log.tag.WakeWordDiag"], "INFO")
        self.assertTrue(harness.cleanup_verified, harness.cleanup_failures)

    def test_rearm_rejects_rearmed_without_generation_started(self) -> None:
        """Required regression: a newer DETECTOR_REARMED alone must never
        satisfy re-arm verification — only a post-boundary
        DETECTOR_GENERATION_STARTED proves the generation started."""
        harness = make_runner()
        target = harness.target = StatefulPropAdb("target")
        self._rearm_target = target
        target.responses[self.REARM_BROADCAST_KEY] = (
            'Broadcast completed: result=0, data="rearmed"'
        )
        post_rearm_events = [
            # Newer generation but NOT startup proof (async initialisation).
            event(11, "DETECTOR_REARMED", generation=5, session=0),
        ]
        clock = {"t": 0}

        def fake_monotonic() -> int:
            clock["t"] += 1_000_000
            return clock["t"]

        with patch.object(
            harness, "_call_target_provider",
            side_effect=self._provider_for_rearm(post_rearm_events=post_rearm_events),
        ), patch.object(runner, "monotonic_ms", side_effect=fake_monotonic), \
                patch.object(runner.time, "sleep"):
            with self.assertRaisesRegex(
                runner.HarnessError, "DETECTOR_GENERATION_STARTED"
            ):
                harness.ensure_target_diagnostics()

    def test_rearm_rejects_pre_boundary_generation_started(self) -> None:
        """Boundary protection: a DETECTOR_GENERATION_STARTED that existed
        before the re-arm boundary must not satisfy the post-rearm wait."""
        harness = make_runner()
        target = harness.target = StatefulPropAdb("target")
        self._rearm_target = target
        target.responses[self.REARM_BROADCAST_KEY] = (
            'Broadcast completed: result=0, data="rearmed"'
        )

        def provider_call(method, extras=None, timeout=15.0):
            if method == runner.TARGET_METHOD_GET_SEQUENCE:
                return runner.TARGET_RESULT_OK, "10"
            if method == runner.TARGET_METHOD_GET_SNAPSHOT:
                return runner.TARGET_RESULT_OK, json.dumps(self._journal_envelope(10, [
                    # A started event that already existed before the boundary
                    # (sequence 9 <= boundary 10) — never post-rearm proof.
                    event(9, "DETECTOR_GENERATION_STARTED", generation=5, session=0),
                ]))
            self.fail(f"unexpected provider method {method}")

        clock = {"t": 0}

        def fake_monotonic() -> int:
            clock["t"] += 1_000_000
            return clock["t"]

        with patch.object(harness, "_call_target_provider", side_effect=provider_call), \
                patch.object(runner, "monotonic_ms", side_effect=fake_monotonic), \
                patch.object(runner.time, "sleep"):
            with self.assertRaisesRegex(
                runner.HarnessError, "DETECTOR_GENERATION_STARTED"
            ):
                harness.ensure_target_diagnostics()

    def test_latest_started_generation_ignores_rearmed_events(self) -> None:
        events = [
            event(1, "DETECTOR_GENERATION_STARTED", generation=4, session=0),
            event(2, "DETECTOR_REARMED", generation=5, session=0),
            event(3, "DETECTOR_GENERATION_STARTED", generation=5, session=0),
            event(4, "DETECTOR_REARMED", generation=6, session=0),
        ]
        # REARMED events never count; the newest authoritative start is 5.
        self.assertEqual(runner.latest_started_generation(events, after_sequence=-1), 5)
        # Strictly post-boundary filtering.
        self.assertEqual(runner.latest_started_generation(events, after_sequence=2), 5)
        self.assertIsNone(runner.latest_started_generation(events, after_sequence=3))
        self.assertIsNone(runner.latest_started_generation([], after_sequence=-1))

    def test_rearm_fails_closed_when_no_new_generation(self) -> None:
        harness = make_runner()
        target = harness.target = StatefulPropAdb("target")
        self._rearm_target = target
        target.responses[self.REARM_BROADCAST_KEY] = (
            'Broadcast completed: result=0, data="rearmed"'
        )
        clock = {"t": 0}

        def fake_monotonic() -> int:
            clock["t"] += 1_000_000
            return clock["t"]

        with patch.object(
            harness, "_call_target_provider",
            side_effect=self._provider_for_rearm(post_rearm_events=[]),
        ), patch.object(runner, "monotonic_ms", side_effect=fake_monotonic), \
                patch.object(runner.time, "sleep"):
            with self.assertRaisesRegex(
                runner.HarnessError, "DETECTOR_GENERATION_STARTED"
            ):
                harness.ensure_target_diagnostics()

    def test_rearm_fails_closed_when_broadcast_rejected(self) -> None:
        harness = make_runner()
        target = harness.target = StatefulPropAdb("target")
        self._rearm_target = target
        target.responses[self.REARM_BROADCAST_KEY] = (
            'Broadcast completed: result=2, data="rearm_failed"'
        )

        def provider_call(method, extras=None, timeout=15.0):
            if method == runner.TARGET_METHOD_GET_SEQUENCE:
                return runner.TARGET_RESULT_OK, "10"
            if method == runner.TARGET_METHOD_GET_SNAPSHOT:
                return runner.TARGET_RESULT_OK, json.dumps(self._journal_envelope(10, [
                    event(1, "DETECTOR_GENERATION_STARTED", generation=4, session=0),
                ]))
            self.fail(f"unexpected provider method {method}")

        with patch.object(harness, "_call_target_provider", side_effect=provider_call):
            with self.assertRaisesRegex(runner.HarnessError, "detector re-arm rejected"):
                harness.ensure_target_diagnostics()

    def test_target_diagnostics_fails_closed_when_property_wont_stick(self) -> None:
        class StuckAdb(StatefulPropAdb):
            def shell(self, *args: str, timeout: float = 30.0, check: bool = True) -> str:
                if args[:2] == ("setprop", "log.tag.WakeWordDiag"):
                    return ""  # refuses to change
                return super().shell(*args, timeout=timeout, check=check)

        harness = make_runner()
        harness.target = StuckAdb("target")
        with self.assertRaises(runner.HarnessError):
            harness.ensure_target_diagnostics()

    # ── No second microphone consumer ──────────────────────────────────

    def test_no_second_audio_record_path_remains(self) -> None:
        """The wake detector must remain the only wake-path AudioRecord: no
        capture receiver, no PCM pull, no target_capture evidence, no binary
        exec-out helper."""
        import inspect as _inspect
        source = _inspect.getsource(runner)
        self.assertNotIn("TargetCaptureReceiver", source)
        self.assertNotIn("CAPTURE_START", source)
        self.assertNotIn("CAPTURE_STOP", source)
        self.assertNotIn("capture.wav", source)
        self.assertFalse(hasattr(runner, "TARGET_CAPTURE_RECEIVER_CLS"))
        self.assertFalse(hasattr(runner, "TARGET_CAPTURE_ACTION_START"))
        self.assertFalse(hasattr(runner.MatrixAttempt, "target_capture"))
        self.assertFalse(hasattr(runner.AdbClient, "exec_out"))



if __name__ == "__main__":
    unittest.main()
