#!/usr/bin/env python3
"""Synthetic unit and dry-run tests for the paired battery telemetry harness."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

from battery_telemetry_harness import (  # noqa: E402
    Availability,
    HarnessError,
    PairedHarness,
    available,
    assert_commit_safe,
    fixture_summary,
    parse_battery_dump,
    parse_batterystats,
    parse_checkin,
    parse_package_metadata,
    parse_wake_word_diagnostics,
    sanitise_text,
    start_skew_ms,
    write_sanitized_summary,
)


class FakeClient:
    def __init__(self, active: bool, diagnostic_level: str = "INFO"):
        self.active = active
        self.diagnostic_level = diagnostic_level

    def shell(self, *args: str, **_: object) -> str:
        command = " ".join(args)
        if command.startswith("dumpsys activity services"):
            return "WakeWordService" if self.active else ""
        if command.startswith("getprop log.tag.WakeWordDiag"):
            return self.diagnostic_level
        raise AssertionError(f"unexpected fake ADB command: {command}")


class BatteryTelemetryParsingTest(unittest.TestCase):
    def test_package_to_uid_resolution(self) -> None:
        parsed = parse_package_metadata("Packages:\n  com.kernel.ai.debug\n  versionCode=9 versionName=1.2.3\n  userId=10123", "com.kernel.ai.debug")
        self.assertEqual(parsed["uid"].value, 10123)
        self.assertEqual(parsed["version_name"].value, "1.2.3")

    def test_batterystats_attributes_cpu_and_partial_wakelock(self) -> None:
        dump = "uid=10123 cpu_user=120 cpu_kernel=7 foreground_service=3600 partial_wakelock=WakeWordLock,4200 estimated_power=1.5"
        parsed = parse_batterystats(dump, 10123)
        self.assertEqual(parsed["cpu_user_ms"].value, 120)
        self.assertEqual(parsed["cpu_kernel_ms"].value, 7)
        self.assertEqual(parsed["partial_wakelocks"].value[0]["name"], "WakeWordLock")
        self.assertEqual(parsed["estimated_power_mah"].value, 1.5)

    def test_absent_app_fields_are_not_zero(self) -> None:
        parsed = parse_batterystats("uid=1000 cpu_user=5", 10123)
        self.assertEqual(parsed["cpu_user_ms"].state, Availability.NOT_REPORTED)
        self.assertIsNone(parsed["cpu_user_ms"].value)

    def test_checkin_parses_uid_cpu_fields(self) -> None:
        parsed = parse_checkin("9,0,uid,10123,cpu_user_ms,42,cpu_kernel_ms,4", 10123)
        self.assertEqual(parsed["cpu_user_ms"].value, 42)
        self.assertEqual(parsed["cpu_kernel_ms"].value, 4)

    def test_charge_counter_availability_and_absence(self) -> None:
        present = parse_battery_dump("level: 70\nCharge counter: 4200000\nAC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3")
        absent = parse_battery_dump("level: 70\nAC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3")
        self.assertEqual(present["charge_counter_uah"].value, 4200000)
        self.assertEqual(absent["charge_counter_uah"].state, Availability.NOT_REPORTED)

    def test_parse_failure_is_distinct_from_genuine_zero(self) -> None:
        zero = parse_batterystats("uid=10123 cpu_user=0", 10123)
        malformed = parse_checkin("9,0,uid,10123,cpu_user_ms,not-a-number", 10123)
        self.assertEqual(zero["cpu_user_ms"].state, Availability.AVAILABLE)
        self.assertEqual(zero["cpu_user_ms"].value, 0)
        self.assertEqual(malformed["cpu_user_ms"].state, Availability.PARSE_FAILED)

    def test_disabled_runs_mark_wake_metrics_not_applicable(self) -> None:
        metrics = parse_wake_word_diagnostics("WakeWordDetector: diagnostics stage2=12", enabled=False)
        self.assertTrue(all(metric.state is Availability.NOT_APPLICABLE for metric in metrics.values()))


class PairedWorkflowTest(unittest.TestCase):
    def test_start_skew_requires_both_devices(self) -> None:
        self.assertEqual(start_skew_ms({"s21": 100, "s23u": 145}).value, 45)
        self.assertEqual(start_skew_ms({"s21": 100}).state, Availability.PARSE_FAILED)

    def test_aborts_paired_run_when_one_device_has_external_power(self) -> None:
        harness = PairedHarness("baseline-disabled", "com.kernel.ai.debug", 60, Path("/tmp"))
        off = {"ac_powered": available(False), "usb_powered": available(False), "wireless_powered": available(False), "status": available(3)}
        charging = {**off, "usb_powered": available(True)}
        with self.assertRaises(HarnessError):
            harness.verify_unplugged_pair({
                "s21": {"battery": off, "screen_off": True, "service_active": False},
                "s23u": {"battery": charging, "screen_off": True, "service_active": False},
            })

    def test_disabled_mode_requires_services_inactive_and_diag_off(self) -> None:
        harness = PairedHarness("baseline-disabled", "com.kernel.ai.debug", 60, Path("/tmp"), {"s21": FakeClient(False), "s23u": FakeClient(False)})
        harness.verify_mode_service_state()
        invalid = PairedHarness("baseline-disabled", "com.kernel.ai.debug", 60, Path("/tmp"), {"s21": FakeClient(True), "s23u": FakeClient(False)})
        with self.assertRaises(HarnessError):
            invalid.verify_mode_service_state()

    def test_enabled_mode_requires_services_active(self) -> None:
        harness = PairedHarness("enabled", "com.kernel.ai.debug", 60, Path("/tmp"), {"s21": FakeClient(True), "s23u": FakeClient(True)})
        harness.verify_mode_service_state()
        invalid = PairedHarness("enabled", "com.kernel.ai.debug", 60, Path("/tmp"), {"s21": FakeClient(True), "s23u": FakeClient(False)})
        with self.assertRaises(HarnessError):
            invalid.verify_mode_service_state()


class SanitizationAndReportingTest(unittest.TestCase):
    def test_sanitizes_serial_endpoints_accounts_and_private_paths(self) -> None:
        text = "serial R5CR605B71K at 192.168.1.7:5555 owner@example.com /home/alice/raw"
        sanitized = sanitise_text(text, ("R5CR605B71K",))
        self.assertNotIn("R5CR605B71K", sanitized)
        self.assertNotIn("192.168.1.7", sanitized)
        self.assertNotIn("owner@example.com", sanitized)
        self.assertNotIn("/home/alice", sanitized)

    def test_rejects_raw_artifact_references(self) -> None:
        with self.assertRaises(HarnessError):
            assert_commit_safe({"artifact": "bugreport"})

    def test_rejects_private_endpoint_in_public_summary(self) -> None:
        with self.assertRaises(HarnessError):
            assert_commit_safe({"device": "10.0.0.2:5555"})

    def test_fixture_summary_and_markdown_json_are_commit_safe(self) -> None:
        fixture = {
            "devices": {
                "s21": {"manufacturer": "Samsung", "model": "SM-G991B", "android_api": "35", "start_monotonic_ms": 1000, "start_level": 80, "end_level": 79, "uid": 10123, "batterystats": "uid=10123 cpu_user=0"},
                "s23u": {"manufacturer": "Samsung", "model": "SM-S918B", "android_api": "35", "start_monotonic_ms": 1035, "start_level": 80, "end_level": 80, "uid": 10124, "batterystats": "uid=10124 cpu_user=2"},
            }
        }
        summary = fixture_summary(fixture, "smoke", 120, "fixture-smoke")
        self.assertEqual(summary["classification"], "NON_EVIDENTIARY_FIXTURE_DRY_RUN")
        self.assertEqual(summary["start_skew_ms"]["value"], 35)
        with tempfile.TemporaryDirectory() as temp:
            json_path, markdown_path = write_sanitized_summary(Path(temp), summary)
            self.assertTrue(json_path.exists())
            self.assertIn("NON_EVIDENTIARY_FIXTURE_DRY_RUN", markdown_path.read_text())


if __name__ == "__main__":
    unittest.main()
