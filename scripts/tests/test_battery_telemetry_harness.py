#!/usr/bin/env python3
"""Tests for the paired battery telemetry harness."""
from __future__ import annotations

import json
import inspect
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

from battery_telemetry_harness import (  # noqa: E402
    Availability,
    HarnessError,
    Metric,
    PairedHarness,
    available,
    assert_commit_safe,
    compute_charge_delta_uah,
    compute_mah_from_uah,
    extract_all_uids,
    extract_uid_block,
    external_power_ok,
    fixture_summary,
    not_reported,
    parse_android_uid,
    parse_battery_dump,
    parse_batterystats,
    parse_batteryproperties,
    parse_checkin,
    parse_deviceidle_state,
    parse_package_metadata,
    parse_power_state,
    parse_procstats_attribution,
    parse_wake_word_diagnostics,
    sanitise_text,
    sanitise_uid_label,
    screen_off,
    start_skew_ms,
    uid_to_android_uid,
    write_sanitized_summary,
)

FIXTURES = SCRIPTS / "testdata" / "fixtures"


def _load_fixture(name: str) -> str:
    return (FIXTURES / name).read_text()


class UidConversionTest(unittest.TestCase):
    """Decimal UID to/from Android u0aNNN conversion."""

    def test_uid_to_android_uid_primary_user(self) -> None:
        self.assertEqual(uid_to_android_uid(10123), "u0a10123")

    def test_uid_to_android_uid_secondary_user(self) -> None:
        self.assertEqual(uid_to_android_uid(1010123), "u10a10123")

    def test_uid_to_android_uid_system(self) -> None:
        self.assertEqual(uid_to_android_uid(1000), "u0a1000")

    def test_parse_android_uid_primary(self) -> None:
        decimal, user = parse_android_uid("u0a10123")
        self.assertEqual(decimal, 10123)
        self.assertEqual(user, 0)

    def test_parse_android_uid_secondary(self) -> None:
        decimal, user = parse_android_uid("u10a10123")
        self.assertEqual(decimal, 1010123)
        self.assertEqual(user, 10)

    def test_parse_android_uid_malformed_raises(self) -> None:
        with self.assertRaises(ValueError):
            parse_android_uid("invalid")

    def test_roundtrip(self) -> None:
        for decimal in (1000, 10123, 10124, 1010123, 1100123):
            self.assertEqual(parse_android_uid(uid_to_android_uid(decimal))[0], decimal)


class BatterystatsParsingTest(unittest.TestCase):
    """Hierarchical UID block parsing with real-device fixture excerpts."""

    def setUp(self) -> None:
        self.s21_text = _load_fixture("battery_s21_human_readable.txt")
        self.s23u_text = _load_fixture("battery_s23u_human_readable.txt")

    def test_uid_block_extraction_s21(self) -> None:
        block = extract_uid_block(self.s21_text, 10123)
        self.assertIn("Wake lock: WakeWordLock", block)
        self.assertIn("cpu:", block)
        self.assertIn("power:", block)
        self.assertNotIn("Uid u0a1000", block)

    def test_uid_block_extraction_s23u(self) -> None:
        block = extract_uid_block(self.s23u_text, 10124)
        self.assertIn("Wake lock: WakeWordLock", block)
        self.assertIn("cpu:", block)

    def test_uid_block_absent_uid_returns_empty(self) -> None:
        block = extract_uid_block(self.s21_text, 99999)
        self.assertEqual(block, "")

    def test_cpu_user_time_s21(self) -> None:
        parsed = parse_batterystats(self.s21_text, 10123)
        self.assertEqual(parsed["cpu_user_ms"].value, 120)
        self.assertEqual(parsed["cpu_kernel_ms"].value, 7)

    def test_cpu_user_time_s23u(self) -> None:
        parsed = parse_batterystats(self.s23u_text, 10124)
        self.assertEqual(parsed["cpu_user_ms"].value, 60)
        self.assertEqual(parsed["cpu_kernel_ms"].value, 3)

    def test_partial_wakelock_s21(self) -> None:
        parsed = parse_batterystats(self.s21_text, 10123)
        wakes = parsed["partial_wakelocks"].value
        self.assertEqual(len(wakes), 1)
        self.assertEqual(wakes[0]["name"], "WakeWordLock")
        self.assertEqual(wakes[0]["duration_ms"], 4200)

    def test_foreground_service_duration(self) -> None:
        parsed = parse_batterystats(self.s21_text, 10123)
        self.assertEqual(parsed["foreground_service_ms"].value, 3599999)

    def test_service_uptime(self) -> None:
        parsed = parse_batterystats(self.s21_text, 10123)
        self.assertEqual(parsed["service_uptime_ms"].value, 3599999)

    def test_audio_duration(self) -> None:
        parsed = parse_batterystats(self.s21_text, 10123)
        self.assertEqual(parsed["audio_duration_ms"].value, 3599999)

    def test_estimated_power(self) -> None:
        parsed = parse_batterystats(self.s21_text, 10123)
        self.assertEqual(parsed["estimated_power_mah"].value, 1.5)

    def test_absent_uid_returns_not_reported(self) -> None:
        parsed = parse_batterystats(self.s21_text, 99999)
        for key, metric in parsed.items():
            self.assertEqual(metric.state, Availability.NOT_REPORTED)

    def test_none_uid_returns_unsupported(self) -> None:
        parsed = parse_batterystats(self.s21_text, None)
        for metric in parsed.values():
            self.assertEqual(metric.state, Availability.UNSUPPORTED)

    def test_extract_all_uids(self) -> None:
        uids = extract_all_uids(self.s21_text)
        decimal_uids = {u[0] for u in uids}
        self.assertIn(10123, decimal_uids)
        self.assertIn(1000, decimal_uids)
        self.assertIn(1001, decimal_uids)

    def test_sanitise_uid_label(self) -> None:
        self.assertEqual(sanitise_uid_label(10123, 10123), "target_app")
        self.assertEqual(sanitise_uid_label(1000, 10123), "system")
        self.assertEqual(sanitise_uid_label(10124, 10123), "other_uid_10124")


class CheckinParsingTest(unittest.TestCase):
    """Real check-in record format parsing."""

    def setUp(self) -> None:
        self.s21_checkin = _load_fixture("battery_s21_checkin.csv")
        self.s23u_checkin = _load_fixture("battery_s23u_checkin.csv")

    def test_uid_cpu_s21(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        self.assertEqual(parsed["cpu_user_ms"].value, 120)
        self.assertEqual(parsed["cpu_kernel_ms"].value, 7)

    def test_uid_cpu_s23u(self) -> None:
        parsed = parse_checkin(self.s23u_checkin, 10124)
        self.assertEqual(parsed["cpu_user_ms"].value, 60)
        self.assertEqual(parsed["cpu_kernel_ms"].value, 3)

    def test_wakelock_record(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        wakes = parsed["checkin_wakelocks"].value
        self.assertEqual(len(wakes), 1)
        self.assertEqual(wakes[0]["name"], "WakeWordLock")
        self.assertEqual(wakes[0]["duration_ms"], 4200)

    def test_foreground_service_record(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        self.assertEqual(parsed["checkin_foreground_service_ms"].value, 3600000)

    def test_proc_cpu_record(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        self.assertEqual(parsed["checkin_proc_cpu_user_ms"].value, 120)
        self.assertEqual(parsed["checkin_proc_cpu_kernel_ms"].value, 7)

    def test_audio_record(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        self.assertEqual(parsed["checkin_audio_ms"].value, 3600000)

    def test_none_uid_returns_unsupported(self) -> None:
        parsed = parse_checkin(self.s21_checkin, None)
        for metric in parsed.values():
            self.assertEqual(metric.state, Availability.UNSUPPORTED)

    def test_absent_uid_returns_not_reported(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 99999)
        for metric in parsed.values():
            self.assertEqual(metric.state, Availability.NOT_REPORTED)

    def test_malformed_cpu_returns_parse_failed(self) -> None:
        malformed = "9,0,uid,10123,cpu,not-a-number,7"
        parsed = parse_checkin(malformed, 10123)
        self.assertEqual(parsed["cpu_user_ms"].state, Availability.PARSE_FAILED)


class BatteryDumpParsingTest(unittest.TestCase):
    """dumpsys battery parsing."""

    def test_start_battery_s21(self) -> None:
        text = _load_fixture("battery_s21_start_battery.txt")
        parsed = parse_battery_dump(text)
        self.assertEqual(parsed["level_percent"].value, 80)
        self.assertEqual(parsed["charge_counter_uah"].value, 4200000)
        self.assertEqual(parsed["voltage_mv"].value, 3800)
        self.assertEqual(parsed["temperature_tenths_c"].value, 250)
        self.assertEqual(parsed["status"].value, 3)
        self.assertFalse(parsed["ac_powered"].value)
        self.assertFalse(parsed["usb_powered"].value)
        self.assertFalse(parsed["wireless_powered"].value)

    def test_end_battery_s21(self) -> None:
        text = _load_fixture("battery_s21_end_battery.txt")
        parsed = parse_battery_dump(text)
        self.assertEqual(parsed["level_percent"].value, 79)
        self.assertEqual(parsed["charge_counter_uah"].value, 4100000)

    def test_absent_field_not_reported(self) -> None:
        parsed = parse_battery_dump("level: 50\nstatus: 3\nAC powered: false\nUSB powered: false\nWireless powered: false\n")
        self.assertEqual(parsed["charge_counter_uah"].state, Availability.NOT_REPORTED)
        self.assertEqual(parsed["voltage_mv"].state, Availability.NOT_REPORTED)

    def test_charge_delta_computation(self) -> None:
        start = parse_battery_dump("level: 80\nCharge counter: 4200000\nAC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3")
        end = parse_battery_dump("level: 79\nCharge counter: 4100000\nAC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3")
        delta = compute_charge_delta_uah(start, end)
        self.assertEqual(delta.value, 100000)
        self.assertEqual(delta.state, Availability.AVAILABLE)

    def test_charge_delta_negative_when_charging(self) -> None:
        start = parse_battery_dump("level: 79\nCharge counter: 4100000\nAC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3")
        end = parse_battery_dump("level: 80\nCharge counter: 4200000\nAC powered: true\nUSB powered: false\nWireless powered: false\nstatus: 2")
        delta = compute_charge_delta_uah(start, end)
        self.assertEqual(delta.value, -100000)

    def test_mah_conversion(self) -> None:
        delta = available(100000)
        mah, rate = compute_mah_from_uah(delta, 1.0)
        self.assertEqual(mah.value, 100.0)
        self.assertEqual(rate.value, 100.0)

    def test_mah_unavailable_without_delta(self) -> None:
        delta = not_reported("no charge counter")
        mah, rate = compute_mah_from_uah(delta, 1.0)
        self.assertEqual(mah.state, Availability.NOT_REPORTED)


class BatteryPropertiesParsingTest(unittest.TestCase):
    """dumpsys batteryproperties parsing."""

    def setUp(self) -> None:
        self.text = _load_fixture("battery_s21_batteryproperties.txt")

    def test_capacity(self) -> None:
        parsed = parse_batteryproperties(self.text)
        self.assertEqual(parsed["capacity_uah"].value, 4000000)

    def test_charge_counter(self) -> None:
        parsed = parse_batteryproperties(self.text)
        self.assertEqual(parsed["charge_counter_uah"].value, 4200000)

    def test_current_now(self) -> None:
        parsed = parse_batteryproperties(self.text)
        self.assertEqual(parsed["current_now_ua"].value, -500)

    def test_health(self) -> None:
        parsed = parse_batteryproperties(self.text)
        self.assertEqual(parsed["health"].value, 2)


class PowerAndDeviceIdleTest(unittest.TestCase):
    """Screen-off detection and device-idle state extraction."""

    def setUp(self) -> None:
        self.power = _load_fixture("battery_s21_power.txt")
        self.idle = _load_fixture("battery_s21_deviceidle.txt")
        self.s23u_power = _load_fixture("battery_s23u_power.txt")
        self.s23u_idle = _load_fixture("battery_s23u_deviceidle.txt")

    def test_screen_off_s21(self) -> None:
        self.assertTrue(screen_off(self.power, self.idle))

    def test_screen_off_s23u(self) -> None:
        self.assertTrue(screen_off(self.s23u_power, self.s23u_idle))

    def test_screen_off_fails_without_known_field(self) -> None:
        with self.assertRaises(HarnessError):
            screen_off("unknown: 1", "unknown: 1")

    def test_power_state_s21(self) -> None:
        parsed = parse_power_state(self.power)
        self.assertEqual(parsed["wakefulness"].value, "Asleep")
        self.assertFalse(parsed["screen_on"].value)
        self.assertEqual(parsed["display_power"].state, Availability.UNSUPPORTED)

    def test_power_state_s23u(self) -> None:
        parsed = parse_power_state(self.s23u_power)
        self.assertEqual(parsed["wakefulness"].value, "Asleep")
        self.assertFalse(parsed["screen_on"].value)
        self.assertEqual(parsed["display_power"].value, "OFF")

    def test_deviceidle_state(self) -> None:
        parsed = parse_deviceidle_state(self.idle)
        self.assertEqual(parsed["state"].value, "idle")
        self.assertFalse(parsed["screen_on"].value)
        self.assertTrue(parsed["idle"].value)

    def test_external_power_ok_disconnected(self) -> None:
        text = "AC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3"
        self.assertTrue(external_power_ok(text))

    def test_external_power_fails_on_ac(self) -> None:
        text = "AC powered: true\nUSB powered: false\nWireless powered: false\nstatus: 3"
        self.assertFalse(external_power_ok(text))

    def test_external_power_missing_field_raises(self) -> None:
        with self.assertRaises(HarnessError):
            external_power_ok("status: 3")

    def test_external_power_accepts_not_charging(self) -> None:
        text = "AC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 4"
        self.assertTrue(external_power_ok(text))

    def test_external_power_rejects_charging(self) -> None:
        text = "AC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 2"
        self.assertFalse(external_power_ok(text))


class PackageMetadataTest(unittest.TestCase):
    """dumpsys package parsing."""

    def test_uid_resolution(self) -> None:
        parsed = parse_package_metadata(
            "Packages:\n  com.kernel.ai.debug\n  versionCode=9 versionName=1.2.3\n  userId=10123",
            "com.kernel.ai.debug",
        )
        self.assertEqual(parsed["uid"].value, 10123)
        self.assertEqual(parsed["version_name"].value, "1.2.3")
        self.assertEqual(parsed["version_code"].value, 9)

    def test_package_absent(self) -> None:
        parsed = parse_package_metadata(
            "Packages:\n  com.other.app\n  userId=9999", "com.kernel.ai.debug"
        )
        self.assertEqual(parsed["package"].state, Availability.NOT_REPORTED)


class WakeWordDiagnosticsTest(unittest.TestCase):
    """WakeWordDiag summary parsing."""

    def test_disabled_returns_not_applicable(self) -> None:
        metrics = parse_wake_word_diagnostics(
            "WakeWordDetector: diagnostics stage2=12", enabled=False
        )
        self.assertTrue(all(m.state is Availability.NOT_APPLICABLE for m in metrics.values()))

    def test_enabled_parses_fields(self) -> None:
        text = "WakeWordDetector: diagnostics elapsedMs=120000 audioFrames=1500 stage1=1500 stage2=12 stage3=10"
        metrics = parse_wake_word_diagnostics(text, enabled=True)
        self.assertEqual(metrics["audioFrames"].value, "1500")
        self.assertEqual(metrics["stage2"].value, "12")


class ZeroVsAbsentTest(unittest.TestCase):
    """Genuine zero must be distinct from absent/parse-failure."""

    def test_genuine_zero_cpu(self) -> None:
        text = "  Uid u0a10123:\n    cpu:\n      user: 0ms\n      system: 0ms\n    power: 0.00 mAh"
        parsed = parse_batterystats(text, 10123)
        self.assertEqual(parsed["cpu_user_ms"].state, Availability.AVAILABLE)
        self.assertEqual(parsed["cpu_user_ms"].value, 0)

    def test_nonzero_is_not_zero(self) -> None:
        text = "  Uid u0a10123:\n    cpu:\n      user: 120ms\n      system: 7ms\n    power: 1.50 mAh"
        parsed = parse_batterystats(text, 10123)
        self.assertEqual(parsed["cpu_user_ms"].value, 120)

    def test_missing_cpu_is_not_zero(self) -> None:
        text = "  Uid u0a10123:\n    Wake lock: WakeWordLock +1s000ms (partial) count 1\n    power: 0.50 mAh"
        parsed = parse_batterystats(text, 10123)
        self.assertEqual(parsed["cpu_user_ms"].state, Availability.NOT_REPORTED)
        self.assertIsNone(parsed["cpu_user_ms"].value)


class FixtureDryRunTest(unittest.TestCase):
    """Fixture-mode dry-run produces safe output with real format."""

    def test_fixture_summary_is_commit_safe(self) -> None:
        fixture = {
            "devices": {
                "s21": {
                    "manufacturer": "Samsung", "model": "SM-G991B", "android_api": "35",
                    "start_monotonic_ms": 1000, "start_level": 80, "end_level": 79,
                    "uid": 10123,
                    "batterystats": (
                        "  Uid u0a10123:\n"
                        "    cpu:\n      user: 120ms\n      system: 7ms\n"
                        "    Wake lock: WakeWordLock +4s200ms (partial) count 14\n"
                        "    power: 1.50 mAh\n"
                    ),
                },
                "s23u": {
                    "manufacturer": "Samsung", "model": "SM-S918B", "android_api": "35",
                    "start_monotonic_ms": 1035, "start_level": 80, "end_level": 80,
                    "uid": 10124,
                    "batterystats": (
                        "  Uid u0a10124:\n"
                        "    cpu:\n      user: 60ms\n      system: 3ms\n"
                        "    Wake lock: WakeWordLock +2s100ms (partial) count 8\n"
                        "    power: 0.80 mAh\n"
                    ),
                },
            }
        }
        summary = fixture_summary(fixture, "smoke", 120, "fixture-smoke")
        self.assertEqual(summary["classification"], "NON_EVIDENTIARY_FIXTURE_DRY_RUN")
        self.assertEqual(summary["start_skew_ms"]["value"], 35)
        with tempfile.TemporaryDirectory() as temp:
            json_path, markdown_path = write_sanitized_summary(Path(temp), summary)
            self.assertTrue(json_path.exists())
            self.assertIn("NON_EVIDENTIARY_FIXTURE_DRY_RUN", markdown_path.read_text())


class StartSkewTest(unittest.TestCase):
    """Paired start skew computation."""

    def test_normal_skew(self) -> None:
        self.assertEqual(start_skew_ms({"s21": 100, "s23u": 145}).value, 45)

    def test_missing_device_returns_parse_failed(self) -> None:
        self.assertEqual(start_skew_ms({"s21": 100}).state, Availability.PARSE_FAILED)


class SanitizationTest(unittest.TestCase):
    """Privacy sanitisation."""

    def test_sanitizes_serial_and_ip(self) -> None:
        text = "serial FAKE-001 at 192.168.1.7:5555"
        sanitized = sanitise_text(text, ("FAKE-001",))
        self.assertNotIn("FAKE-001", sanitized)
        self.assertNotIn("192.168.1.7", sanitized)

    def test_sanitizes_email_and_home_path(self) -> None:
        text = "owner@example.com /home/alice/project"
        sanitized = sanitise_text(text)
        self.assertNotIn("owner@example.com", sanitized)
        self.assertNotIn("/home/alice", sanitized)

    def test_rejects_raw_artifact_reference(self) -> None:
        with self.assertRaises(HarnessError):
            assert_commit_safe({"artifact": "bugreport"})

    def test_rejects_private_endpoint(self) -> None:
        with self.assertRaises(HarnessError):
            assert_commit_safe({"device": "10.0.0.2:5555"})


class ProcstatsParsingTest(unittest.TestCase):
    """Procstats evidence parsing."""

    def test_service_active_detected(self) -> None:
        text = (
            "Service com.kernel.ai.debug:WakeWordService\n"
            "  Service com.kernel.ai.debug:AnotherService"
        )
        parsed = parse_procstats_attribution(text, "com.kernel.ai.debug")
        self.assertTrue(parsed["service_active"].value)


class CleanupBehaviorTest(unittest.TestCase):
    """Diagnostic cleanup try/finally behaviour."""

    def test_cleanup_graceful_without_client(self) -> None:
        harness = PairedHarness("enabled", "com.kernel.ai.debug", 60, Path("/tmp"))
        err = harness.try_cleanup_diagnostics("s21")
        self.assertIsNotNone(err)
        self.assertIn("no ADB client", err)

    def test_abort_summary_produced_on_failure(self) -> None:
        harness = PairedHarness("baseline-disabled", "com.kernel.ai.debug", 120, Path("/tmp"))
        harness.abort_reason = "test precondition failure"
        summary = harness.abort_summary({}, {}, {})
        self.assertEqual(summary["classification"], "ABORTED_NON_EVIDENTIARY")
        self.assertEqual(summary["validity"]["state"], "aborted")
        self.assertEqual(summary["validity"]["abort_reason"], "test precondition failure")


class EndFirstThenBugreportTest(unittest.TestCase):
    """Official end boundary must precede bugreport capture."""

    def test_run_physical_sequences_end_before_bugreport(self) -> None:
        source = inspect.getsource(PairedHarness.run_physical)
        end_pos = source.find("capture_end_raw")
        bugreport_pos = source.find("collect_bugreport")
        self.assertGreater(end_pos, 0)
        self.assertGreater(bugreport_pos, end_pos,
                           "bugreport must come after end boundary capture")


if __name__ == "__main__":
    unittest.main()
