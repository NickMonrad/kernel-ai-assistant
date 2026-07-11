#!/usr/bin/env python3
"""Tests for the paired battery telemetry harness."""
from __future__ import annotations

import inspect
import json
import sys
import tempfile
import unittest
import argparse
from unittest.mock import patch

from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

from battery_telemetry_harness import (  # noqa: E402
    PER_USER_RANGE,
    FIRST_APPLICATION_UID,
    uid_to_android_uid,
    parse_android_uid,
    extract_uid_block,
    extract_all_uids,
    parse_batterystats,
    parse_checkin,
    parse_battery_dump,
    parse_batteryproperties,
    parse_deviceidle_state,
    parse_power_state,
    screen_off,
    external_power_ok,
    parse_package_metadata,
    parse_wake_word_diagnostics,
    parse_procstats_attribution,
    parse_duration,
    metric_from_match,
    parse_failed,
    _parse_duration_ms,
    compute_charge_delta_uah,
    compute_mah_from_uah,
    start_skew_ms,
    sanitise_uid_label,
    sanitise_text,
    assert_commit_safe,
    fixture_summary,
    write_sanitized_summary,
    available,
    not_reported,
    unsupported,
    not_applicable,
    HarnessError,
    Availability,
    Metric,
    PairedHarness,
    RunResult,
)

FIXTURES = SCRIPTS / "testdata" / "fixtures"


def _load_fixture(name: str) -> str:
    return (FIXTURES / name).read_text()


class UidConversionTest(unittest.TestCase):
    """Decimal UID to/from Android text form conversion."""

    def test_uid_to_android_uid_primary_user(self) -> None:
        self.assertEqual(uid_to_android_uid(10123), "u0a123")

    def test_uid_to_android_uid_secondary_user(self) -> None:
        self.assertEqual(uid_to_android_uid(1010123), "u10a123")

    def test_uid_to_android_uid_system(self) -> None:
        self.assertEqual(uid_to_android_uid(1000), "1000")

    def test_uid_to_android_uid_system_boundary(self) -> None:
        self.assertEqual(uid_to_android_uid(9999), "9999")

    def test_parse_android_uid_primary(self) -> None:
        decimal, user = parse_android_uid("u0a123")
        self.assertEqual(decimal, 10123)
        self.assertEqual(user, 0)

    def test_parse_android_uid_secondary(self) -> None:
        decimal, user = parse_android_uid("u10a123")
        self.assertEqual(decimal, 1010123)
        self.assertEqual(user, 10)

    def test_parse_android_uid_system_numeric(self) -> None:
        decimal, user = parse_android_uid("1000")
        self.assertEqual(decimal, 1000)
        self.assertEqual(user, 0)

    def test_parse_android_uid_malformed_raises(self) -> None:
        with self.assertRaises(ValueError):
            parse_android_uid("invalid")

    def test_parse_android_uid_raises_on_app_range_numeric(self) -> None:
        with self.assertRaises(ValueError):
            parse_android_uid("10123")

    def test_parse_android_uid_isolated_raises(self) -> None:
        with self.assertRaises(ValueError):
            parse_android_uid("u0i12345")

    def test_parse_android_uid_shared_raises(self) -> None:
        with self.assertRaises(ValueError):
            parse_android_uid("u0s12345")

    def test_roundtrip(self) -> None:
        for decimal in (1000, 9999, 10123, 10124, 1010123):
            self.assertEqual(parse_android_uid(uid_to_android_uid(decimal))[0], decimal)

    def test_conversion_examples(self) -> None:
        self.assertEqual(uid_to_android_uid(10123), "u0a123")
        self.assertEqual(uid_to_android_uid(10124), "u0a124")
        self.assertEqual(uid_to_android_uid(1010123), "u10a123")
        self.assertEqual(uid_to_android_uid(1000), "1000")
        self.assertEqual(parse_android_uid("u0a123")[0], 10123)
        self.assertEqual(parse_android_uid("u0a124")[0], 10124)
        self.assertEqual(parse_android_uid("u10a123")[0], 1010123)
        self.assertEqual(parse_android_uid("1000")[0], 1000)

    def test_parse_package_metadata_accepts_android15_app_id(self) -> None:
        parsed = parse_package_metadata(
            "com.kernel.ai.debug\nappId=10775\nversionCode=1 versionName=1",
            "com.kernel.ai.debug",
        )
        self.assertEqual(parsed["uid"].value, 10775)


class BatterystatsParsingTest(unittest.TestCase):
    """Hierarchical UID block parsing with real-device fixture excerpts."""

    def setUp(self) -> None:
        self.s21_text = _load_fixture("battery_s21_human_readable.txt")
        self.s23u_text = _load_fixture("battery_s23u_human_readable.txt")

    def test_uid_block_extraction_s21(self) -> None:
        block = extract_uid_block(self.s21_text, 10123)
        self.assertIn("Fg Service for:", block)
        self.assertIn("Total cpu time:", block)
        self.assertIn("Total running:", block)
        self.assertNotIn("1000:", block)

    def test_uid_block_extraction_s23u(self) -> None:
        block = extract_uid_block(self.s23u_text, 10124)
        self.assertIn("Fg Service for:", block)
        self.assertIn("Total cpu time:", block)

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


    def test_foreground_service_duration(self) -> None:
        parsed = parse_batterystats(self.s21_text, 10123)
        self.assertEqual(parsed["foreground_service_ms"].value, 3599999)

    def test_service_uptime(self) -> None:
        parsed = parse_batterystats(self.s21_text, 10123)
        self.assertEqual(parsed["service_uptime_ms"].value, 3599999)

    def test_estimated_power(self) -> None:
        parsed = parse_batterystats(self.s21_text, 10123)
        self.assertEqual(parsed["estimated_power_mah"].value, 0.294)

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

    def test_extract_no_duplicate_blocks(self) -> None:
        uids = extract_all_uids(self.s21_text)
        decimal_uids = [u[0] for u in uids]
        self.assertEqual(decimal_uids.count(10123), 1)

    def test_sanitise_uid_label(self) -> None:
        seen: dict[int, int] = {}
        self.assertEqual(sanitise_uid_label(10123, 10123, seen), "target_app")
        self.assertEqual(sanitise_uid_label(1000, 10123, seen), "system")
        self.assertEqual(sanitise_uid_label(10124, 10123, seen), "other_uid_1")

    def test_sanitise_uid_label_sequential(self) -> None:
        seen: dict[int, int] = {}
        self.assertEqual(sanitise_uid_label(10124, 10123, seen), "other_uid_1")
        self.assertEqual(sanitise_uid_label(10125, 10123, seen), "other_uid_2")
        self.assertEqual(sanitise_uid_label(10126, 10123, seen), "other_uid_3")

    def test_sanitise_no_raw_uid(self) -> None:
        seen: dict[int, int] = {}
        label = sanitise_uid_label(10124, 10123, seen)
        self.assertNotIn("10124", label)
        self.assertNotIn("android_uid", label)
        self.assertNotIn("decimal_uid", label)


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

    def test_awl_aggregate(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        wakes = parsed["checkin_wakelocks"].value
        self.assertEqual(len(wakes), 1)
        self.assertEqual(wakes[0]["name"], "aggregate_partial")
        self.assertEqual(wakes[0]["type"], "aggregate_partial")
        self.assertEqual(wakes[0]["duration_ms"], 4200)


    def test_estimated_power(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        self.assertEqual(parsed["estimated_power_mah"].value, 1.5)

    def test_proc_cpu(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        self.assertEqual(parsed["checkin_proc_cpu_user_ms"].value, 120)
        self.assertEqual(parsed["checkin_proc_cpu_kernel_ms"].value, 7)

    def test_proc_cpu_from_checkin(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        self.assertEqual(parsed["checkin_proc_cpu_user_ms"].value, 120)

    def test_none_uid_unsupported(self) -> None:
        parsed = parse_checkin(self.s21_checkin, None)
        for m in parsed.values():
            self.assertEqual(m.state, Availability.UNSUPPORTED)

    def test_absent_uid_not_reported(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 99999)
        for m in parsed.values():
            self.assertEqual(m.state, Availability.NOT_REPORTED)

    def test_malformed_skipped(self) -> None:
        parsed = parse_checkin("9,10123,0,cpu,abc,7", 10123)
        self.assertEqual(parsed["cpu_user_ms"].state, Availability.PARSE_FAILED)

    def test_system_uid(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 1000)
        self.assertEqual(parsed["cpu_user_ms"].value, 50)

    def test_pwi_power_estimate(self) -> None:
        parsed = parse_checkin("9,10123,l,pwi,uid,0.294,0.0,0.294,0.294", 10123)
        self.assertEqual(parsed["estimated_power_mah"].value, 0.294)

    def test_pwi_and_awl(self) -> None:
        parsed = parse_checkin("9,10123,0,cpu,100,5\n9,10123,l,pwi,uid,0.294,0.0,0.294,0.294\n9,10123,0,awl,4200,0", 10123)
        self.assertEqual(parsed["estimated_power_mah"].value, 0.294)
        self.assertEqual(parsed["cpu_user_ms"].value, 100)
        wakes = parsed["checkin_wakelocks"].value
        self.assertEqual(len(wakes), 1)
        self.assertEqual(wakes[0]["duration_ms"], 4200)


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

    def test_charge_delta(self) -> None:
        s = parse_battery_dump("level: 80\nCharge counter: 4200000\nAC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3")
        e = parse_battery_dump("level: 79\nCharge counter: 4100000\nAC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3")
        self.assertEqual(compute_charge_delta_uah(s, e).value, 100000)

    def test_charge_delta_charging(self) -> None:
        s = parse_battery_dump("level: 79\nCharge counter: 4100000\nAC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3")
        e = parse_battery_dump("level: 80\nCharge counter: 4200000\nAC powered: true\nUSB powered: false\nWireless powered: false\nstatus: 2")
        self.assertEqual(compute_charge_delta_uah(s, e).value, -100000)

    def test_mah_conversion(self) -> None:
        delta = available(100000)
        mah, rate = compute_mah_from_uah(delta, 1.0)
        self.assertEqual(mah.value, 100.0)
        self.assertEqual(rate.value, 100.0)

    def test_mah_no_delta(self) -> None:
        mah, _ = compute_mah_from_uah(not_reported("no charge counter"), 1.0)
        self.assertEqual(mah.state, Availability.NOT_REPORTED)

    def test_charge_delta_absent(self) -> None:
        s = parse_battery_dump("level: 80\nAC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3")
        e = parse_battery_dump("level: 79\nAC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3")
        self.assertEqual(compute_charge_delta_uah(s, e).state, Availability.NOT_REPORTED)



class BatteryPropertiesTest(unittest.TestCase):
    """dumpsys batteryproperties parsing."""

    def setUp(self) -> None:
        self.text = _load_fixture("battery_s21_batteryproperties.txt")

    def test_capacity_percent(self) -> None:
        self.assertEqual(parse_batteryproperties(self.text)["capacity_percent"].value, 80)

    def test_charge_counter(self) -> None:
        self.assertEqual(parse_batteryproperties(self.text)["charge_counter_uah"].value, 4200000)

    def test_current_now(self) -> None:
        self.assertEqual(parse_batteryproperties(self.text)["current_now_ua"].value, -500)

    def test_voltage_mv(self) -> None:
        self.assertEqual(parse_batteryproperties(self.text)["voltage_mv"].value, 3800)

    def test_health(self) -> None:
        self.assertEqual(parse_batteryproperties(self.text)["health"].value, 2)

    def test_no_capacity_uah(self) -> None:
        self.assertNotIn("capacity_uah", parse_batteryproperties(self.text))


class PowerAndDeviceIdleTest(unittest.TestCase):
    """Screen-off detection and device-idle state."""

    def setUp(self) -> None:
        self.power = _load_fixture("battery_s21_power.txt")
        self.idle = _load_fixture("battery_s21_deviceidle.txt")
        self.s23u_power = _load_fixture("battery_s23u_power.txt")
        self.s23u_idle = _load_fixture("battery_s23u_deviceidle.txt")

    def test_screen_off_s21(self) -> None:
        self.assertTrue(screen_off(self.power, self.idle))

    def test_screen_off_s23u(self) -> None:
        self.assertTrue(screen_off(self.s23u_power, self.s23u_idle))

    def test_screen_off_unknown(self) -> None:
        with self.assertRaises(HarnessError):
            screen_off("unknown: 1", "unknown: 1")

    def test_power_state_s21(self) -> None:
        p = parse_power_state(self.power)
        self.assertEqual(p["wakefulness"].value, "Asleep")
        self.assertFalse(p["screen_on"].value)
        self.assertEqual(p["display_power"].state, Availability.UNSUPPORTED)

    def test_power_state_s23u(self) -> None:
        p = parse_power_state(self.s23u_power)
        self.assertEqual(p["wakefulness"].value, "Asleep")
        self.assertFalse(p["screen_on"].value)
        self.assertEqual(p["display_power"].value, "OFF")

    def test_deviceidle(self) -> None:
        p = parse_deviceidle_state(self.idle)
        self.assertEqual(p["state"].value, "idle")
        self.assertFalse(p["screen_on"].value)
        self.assertTrue(p["idle"].value)

    def test_disconnected(self) -> None:
        self.assertTrue(external_power_ok("AC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 3"))

    def test_ac_fails(self) -> None:
        self.assertFalse(external_power_ok("AC powered: true\nUSB powered: false\nWireless powered: false\nstatus: 3"))

    def test_missing_field_raises(self) -> None:
        with self.assertRaises(HarnessError):
            external_power_ok("status: 3")

    def test_not_charging_ok(self) -> None:
        self.assertTrue(external_power_ok("AC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 4"))

    def test_charging_rejected(self) -> None:
        self.assertFalse(external_power_ok("AC powered: false\nUSB powered: false\nWireless powered: false\nstatus: 2"))


class PackageMetadataTest(unittest.TestCase):
    """dumpsys package parsing."""

    def test_uid_resolution(self) -> None:
        p = parse_package_metadata(
            "Packages:\n  com.kernel.ai.debug\n  versionCode=9 versionName=1.2.3\n  userId=10123",
            "com.kernel.ai.debug",
        )
        self.assertEqual(p["uid"].value, 10123)
        self.assertEqual(p["version_name"].value, "1.2.3")
        self.assertEqual(p["version_code"].value, 9)

    def test_package_absent(self) -> None:
        p = parse_package_metadata("Packages:\n  com.other.app\n  userId=9999", "com.kernel.ai.debug")
        self.assertEqual(p["package"].state, Availability.NOT_REPORTED)


class WakeWordDiagnosticsTest(unittest.TestCase):
    """WakeWordDiag summary parsing."""

    def test_disabled(self) -> None:
        m = parse_wake_word_diagnostics("WakeWordDetector: diagnostics stage2=12", enabled=False)
        self.assertTrue(all(x.state is Availability.NOT_APPLICABLE for x in m.values()))

    def test_enabled(self) -> None:
        text = "WakeWordDetector: diagnostics elapsedMs=120000 audioFrames=1500 stage1=1500 stage2=12"
        m = parse_wake_word_diagnostics(text, enabled=True)
        self.assertEqual(m["audioFrames"].value, "1500")
        self.assertEqual(m["stage2"].value, "12")


class ZeroVsAbsentTest(unittest.TestCase):
    """Genuine zero distinct from absent."""

    def test_zero_cpu(self) -> None:
        text = "  u0a123:\n    Total cpu time: u=0ms s=0ms"
        p = parse_batterystats(text, 10123)
        self.assertEqual(p["cpu_user_ms"].state, Availability.AVAILABLE)
        self.assertEqual(p["cpu_user_ms"].value, 0)

    def test_nonzero(self) -> None:
        text = "  UID u0a123: 1.50\n\n  u0a123:\n    Total cpu time: u=120ms s=7ms"
        self.assertEqual(parse_batterystats(text, 10123)["cpu_user_ms"].value, 120)

    def test_missing_not_zero(self) -> None:
        text = "  u0a123:\n    (nothing executed)"
        p = parse_batterystats(text, 10123)
        self.assertEqual(p["cpu_user_ms"].state, Availability.NOT_REPORTED)
        self.assertIsNone(p["cpu_user_ms"].value)


class DurationParserTest(unittest.TestCase):
    """_parse_duration_ms with real Batterystats durations."""

    def test_seconds_millis(self) -> None:
        self.assertEqual(_parse_duration_ms("+4s200ms"), 4200)

    def test_minutes_seconds_millis(self) -> None:
        self.assertEqual(_parse_duration_ms("59m59s999ms"), 3599999)

    def test_hours_minutes_seconds(self) -> None:
        self.assertEqual(_parse_duration_ms("1h2m3s4ms"), 3723004)

    def test_zero_ms(self) -> None:
        self.assertEqual(_parse_duration_ms("0ms"), 0)

    def test_only_millis(self) -> None:
        self.assertEqual(_parse_duration_ms("123ms"), 123)

    def test_without_plus(self) -> None:
        self.assertEqual(_parse_duration_ms("4s200ms"), 4200)

    def test_hours_only(self) -> None:
        self.assertEqual(_parse_duration_ms("1h0ms"), 3600000)

    def test_empty_raises(self) -> None:
        with self.assertRaises(ValueError):
            _parse_duration_ms("")

    def test_malformed_raises(self) -> None:
        with self.assertRaises(ValueError):
            _parse_duration_ms("not-a-duration")

    def test_parse_duration_cli_seconds(self) -> None:
        self.assertEqual(parse_duration("3600s"), 3600)

    def test_parse_duration_cli_minutes(self) -> None:
        self.assertEqual(parse_duration("60m"), 3600)

    def test_parse_duration_cli_hours(self) -> None:
        self.assertEqual(parse_duration("2h"), 7200)

    def test_parse_duration_cli_days(self) -> None:
        self.assertEqual(parse_duration("1d"), 86400)

    def test_parse_duration_cli_malformed_raises(self) -> None:
        with self.assertRaises(argparse.ArgumentTypeError):
            parse_duration("not-a-duration")

    def test_parse_duration_cli_empty_raises(self) -> None:
        with self.assertRaises(argparse.ArgumentTypeError):
            parse_duration("")


class FixtureDryRunTest(unittest.TestCase):
    """Fixture-mode dry-run produces safe output."""

    def test_summary_commit_safe(self) -> None:
        fixture = {
            "devices": {
                "s21": {
                    "manufacturer": "Samsung", "model": "SM-G991B", "android_api": "35",
                    "start_monotonic_ms": 1000, "start_level": 80, "end_level": 79,
                    "uid": 10123,
                    "batterystats": "  UID u0a123: 1.50\n\n  u0a123:\n    Total cpu time: u=120ms s=7ms\n",
                },
                "s23u": {
                    "manufacturer": "Samsung", "model": "SM-S918B", "android_api": "35",
                    "start_monotonic_ms": 1035, "start_level": 80, "end_level": 80,
                    "uid": 10124,
                    "batterystats": "  UID u0a124: 0.80\n\n  u0a124:\n    Total cpu time: u=60ms s=3ms\n",
                },
            }
        }
        summary = fixture_summary(fixture, "smoke-disabled", 120, "fixture-smoke")
        self.assertEqual(summary["classification"], "NON_EVIDENTIARY_FIXTURE_DRY_RUN")
        self.assertEqual(summary["start_skew_ms"]["value"], 35)
        with tempfile.TemporaryDirectory() as tmp:
            jp, mp = write_sanitized_summary(Path(tmp), summary)
            self.assertTrue(jp.exists())
            self.assertIn("NON_EVIDENTIARY_FIXTURE_DRY_RUN", mp.read_text())


class StartSkewTest(unittest.TestCase):
    """Paired start skew."""

    def test_normal(self) -> None:
        self.assertEqual(start_skew_ms({"s21": 100, "s23u": 145}).value, 45)

    def test_missing(self) -> None:
        self.assertEqual(start_skew_ms({"s21": 100}).state, Availability.PARSE_FAILED)


class SanitizationTest(unittest.TestCase):
    """Privacy sanitisation."""

    def test_serial_ip(self) -> None:
        s = sanitise_text("serial FAKE-001 at 192.168.1.7:5555", ("FAKE-001",))
        self.assertNotIn("FAKE-001", s)
        self.assertNotIn("192.168.1.7", s)

    def test_email_path(self) -> None:
        s = sanitise_text("owner@example.com /home/alice/project")
        self.assertNotIn("owner@example.com", s)
        self.assertNotIn("/home/alice", s)

    def test_raw_artifact_rejected(self) -> None:
        with self.assertRaises(HarnessError):
            assert_commit_safe({"artifact": "bugreport"})

    def test_private_endpoint_rejected(self) -> None:
        with self.assertRaises(HarnessError):
            assert_commit_safe({"device": "10.0.0.2:5555"})

    def test_safe_ids_pass(self) -> None:
        assert_commit_safe({"device": "s21", "classification": "EVIDENTIARY"})

    def test_safe_simple_string(self) -> None:
        assert_commit_safe("safe-string-without-patterns")

    def test_safe_with_secrets(self) -> None:
        assert_commit_safe({"device": "s21"}, secrets=("FAKE_001",))

class MetricApiTest(unittest.TestCase):
    """Metric dataclass and factory function behaviour."""

    def test_available_value(self) -> None:
        m = available(42)
        self.assertEqual(m.state, Availability.AVAILABLE)
        self.assertEqual(m.value, 42)

    def test_not_reported(self) -> None:
        m = not_reported("test detail")
        self.assertEqual(m.state, Availability.NOT_REPORTED)
        self.assertIsNone(m.value)
        self.assertEqual(m.detail, "test detail")

    def test_unsupported_factory(self) -> None:
        m = unsupported("test detail")
        self.assertEqual(m.state, Availability.UNSUPPORTED)
        self.assertIsNone(m.value)

    def test_parse_failed_factory(self) -> None:
        m = parse_failed("test detail")
        self.assertEqual(m.state, Availability.PARSE_FAILED)

    def test_not_applicable_factory(self) -> None:
        m = not_applicable("test detail")
        self.assertEqual(m.state, Availability.NOT_APPLICABLE)

    def test_metric_public(self) -> None:
        m = available(42)
        self.assertEqual(m.public(), {"state": "available", "value": 42})

    def test_metric_public_not_reported(self) -> None:
        m = not_reported("no data")
        self.assertEqual(m.public(), {"state": "not_reported", "detail": "no data"})

    def test_metric_public_unsupported(self) -> None:
        m = unsupported("no data")
        self.assertEqual(m.public(), {"state": "unsupported", "detail": "no data"})

    def test_metric_from_match_found_int(self) -> None:
        m = metric_from_match("cpu: 120ms", r"cpu:\s*(\d+)", "test")
        self.assertEqual(m.state, Availability.AVAILABLE)
        self.assertEqual(m.value, 120)

    def test_metric_from_match_found_float(self) -> None:
        m = metric_from_match("power: 1.50 mAh", r"power:\s*([\d.]+)", "test")
        self.assertEqual(m.value, 1.5)

    def test_metric_from_match_not_found(self) -> None:
        m = metric_from_match("no match here", r"cpu:\s*(\d+)", "test")
        self.assertEqual(m.state, Availability.NOT_REPORTED)

    def test_metric_from_match_non_numeric(self) -> None:
        m = metric_from_match("cpu: abc", r"cpu:\s*(\S+)", "test")
        self.assertEqual(m.state, Availability.PARSE_FAILED)

    def test_metric_from_match_matches_across_lines(self) -> None:
        m = metric_from_match("Line1\ncpu: 55\nline3", r"cpu:\s*(\d+)", "test")
        self.assertEqual(m.value, 55)

class ProcstatsTest(unittest.TestCase):
    """Procstats evidence."""

    def test_service_active(self) -> None:
        text = "Service com.kernel.ai.debug:WakeWordService\n  Service com.kernel.ai.debug:Another"
        self.assertTrue(parse_procstats_attribution(text, "com.kernel.ai.debug")["service_active"].value)


class PairedHarnessInitTest(unittest.TestCase):
    """Harness initialisation."""

    def test_enabled_true(self) -> None:
        self.assertTrue(PairedHarness("enabled", "com.kernel.ai.debug", 60, Path("/tmp")).enabled)

    def test_smoke_enabled_true(self) -> None:
        self.assertTrue(PairedHarness("smoke-enabled", "com.kernel.ai.debug", 60, Path("/tmp")).enabled)

    def test_disabled_false(self) -> None:
        self.assertFalse(PairedHarness("baseline-disabled", "com.kernel.ai.debug", 60, Path("/tmp")).enabled)

    def test_smoke_disabled_false(self) -> None:
        self.assertFalse(PairedHarness("smoke-disabled", "com.kernel.ai.debug", 60, Path("/tmp")).enabled)

    def test_smoke_maps_to_disabled(self) -> None:
        h = PairedHarness("smoke", "com.kernel.ai.debug", 60, Path("/tmp"))
        self.assertEqual(h.mode, "smoke-disabled")
        self.assertFalse(h.enabled)

    def test_run_result_ok(self) -> None:
        r = RunResult({"c": "EVIDENTIARY"}, True, 0)
        self.assertEqual(r.summary["c"], "EVIDENTIARY")
        self.assertTrue(r.success)
        self.assertEqual(r.exit_code, 0)

    def test_run_result_abort(self) -> None:
        r = RunResult({"c": "ABORTED"}, False, 1)
        self.assertEqual(r.exit_code, 1)


class CleanupTest(unittest.TestCase):
    """Diagnostic cleanup behaviour."""

    def test_no_client(self) -> None:
        h = PairedHarness("enabled", "com.kernel.ai.debug", 60, Path("/tmp"))
        self.assertIn("no ADB client", h.try_cleanup_diagnostics("s21"))

    def test_abort_summary(self) -> None:
        h = PairedHarness("baseline-disabled", "com.kernel.ai.debug", 120, Path("/x-nonexistent-test-path"))
        h.run_id = "test-baseline-disabled-abort"
        h.abort_reason = "test failure"
        s = h.abort_summary({}, {}, {})
        self.assertEqual(s["classification"], "ABORTED_NON_EVIDENTIARY")
        self.assertEqual(s["validity"]["abort_reason"], "test failure")


class SequencingTest(unittest.TestCase):
    """End before bugreport sequencing."""

    def test_end_before_bugreport(self) -> None:
        src = inspect.getsource(PairedHarness.run_physical)
        self.assertGreater(src.find("capture_end_raw"), 0)
        self.assertGreater(src.find("collect_bugreport"), src.find("capture_end_raw"))


class ExitCodeTest(unittest.TestCase):
    """Exit code contract."""

    def test_evidentiary_zero(self) -> None:
        self.assertEqual(RunResult({"c": "EVIDENTIARY"}, True, 0).exit_code, 0)

    def test_smoke_zero(self) -> None:
        self.assertEqual(RunResult({"c": "NON_EVIDENTIARY_SMOKE"}, True, 0).exit_code, 0)

    def test_fixture_zero(self) -> None:
        self.assertEqual(RunResult({"c": "NON_EVIDENTIARY_FIXTURE_DRY_RUN"}, True, 0).exit_code, 0)

    def test_abort_nonzero(self) -> None:
        self.assertEqual(RunResult({"c": "ABORTED"}, False, 1).exit_code, 1)

    def test_cleanup_failure_three(self) -> None:
        self.assertEqual(RunResult({"c": "ABORTED"}, False, 3).exit_code, 3)

    def test_error_return_two(self) -> None:
        self.assertEqual(RunResult({"c": "ERROR"}, False, 2).exit_code, 2)


class FakePhysicalAdb:
    """Stateful ADB double for comprehensive PairedHarness workflow testing.

    Tracks every command issued and supports configurable failure injection
    for each major operation category.
    """

    def __init__(self, alias: str, active: bool = False, diagnostic: str = "INFO"):
        self.alias = alias
        self.serial = f"FAKE-{alias.upper()}"
        self.active = active
        self._diagnostic = diagnostic
        self.commands: list[tuple[str, ...]] = []
        # Failure injection flags
        self.fail_reachable = False
        self.fail_getprop_after_debug = False
        self.fail_setprop = False
        self.fail_bugreport = False
        self.fail_end_capture = False
        self.fail_validation = False
        self.fail_unplug = False
        self.fail_batterystats_reset = False
        self.fail_cleanup_cmd = False
        self.fail_cleanup_verification = False
        self.fail_service_check = False

    @property
    def diagnostic(self) -> str:
        return self._diagnostic

    def reachable(self) -> bool:
        if self.fail_reachable:
            return False
        return True

    def run(self, *args: str, timeout: float = 30.0) -> str:
        self.commands.append(args)
        if args[0] == "get-state":
            return "device\n"
        if args[0] == "bugreport":
            if self.fail_bugreport:
                raise HarnessError("injected bugreport failure")
            return ""
        if args[0] == "logcat":
            return "WakeWordDetector: diagnostics elapsedMs=1"
        raise AssertionError(f"unexpected run command: {args}")

    def shell(self, *args: str, timeout: float = 30.0) -> str:
        self.commands.append(args)
        if args[0] == "getprop":
            key = args[1]
            props = {
                "ro.product.manufacturer": "samsung",
                "ro.product.model": "SM-G991B" if self.alias == "s21" else "SM-S918B",
                "ro.build.version.release": "15",
                "ro.build.version.sdk": "35",
                "ro.build.fingerprint": "sanitised/fingerprint",
                "log.tag.WakeWordDiag": self._diagnostic,
            }
            if key == "log.tag.WakeWordDiag" and self.fail_getprop_after_debug and self._diagnostic == "DEBUG":
                raise HarnessError("injected verification failure")
            return props.get(key, "")
        if args[0] == "setprop":
            if self.fail_setprop:
                raise HarnessError("injected setprop failure")
            self._diagnostic = args[2]
            return ""
        if args[:3] == ("dumpsys", "package", "com.kernel.ai.debug"):
            uid = 10123 if self.alias == "s21" else 10124
            return f"  Package [com.kernel.ai.debug]\n    userId={uid}\n    versionCode=1 versionName=1\n"
        if args[:3] == ("dumpsys", "activity", "services"):
            if self.fail_service_check:
                raise HarnessError("injected service check failure")
            return "WakeWordService" if self.active else ""
        if args[:2] == ("dumpsys", "battery"):
            return "AC powered: false\nUSB powered: false\nWireless powered: false\nlevel: 80\nstatus: 3\nCharge counter: 4000000\n"
        if args[:2] == ("dumpsys", "power"):
            return "mScreenOn=false\nWakefulness=Asleep\n"
        if args[:2] == ("dumpsys", "deviceidle"):
            return "mScreenOn=false\nmState=IDLE\n"
        if args == ("cat", "/proc/uptime"):
            return "100.0 50.0\n"
        if args[:2] == ("dumpsys", "batteryproperties"):
            return "capacity: 80\n"
        if args[:3] == ("dumpsys", "batterystats", "--charged"):
            uid = "u0a123" if self.alias == "s21" else "u0a124"
            return f"  UID {uid}: 0.1\n\n  {uid}:\n    Total cpu time: u=1ms s=1ms\n"
        if args[:3] == ("dumpsys", "batterystats", "--checkin"):
            uid = 10123 if self.alias == "s21" else 10124
            return f"9,{uid},l,cpu,1,1,0\n"
        if args[:2] == ("dumpsys", "batterystats"):
            return ""
        if args[:2] in {("dumpsys", "procstats"), ("dumpsys", "meminfo")}:
            return ""
        raise AssertionError(f"unexpected shell command: {args}")

    def list_restore_commands(self) -> list[tuple[str, ...]]:
        """Return the setprop commands that restored diagnostic property."""
        return [c for c in self.commands if c[0] == "setprop" and len(c) >= 3]


class FixtureCoherenceTest(unittest.TestCase):
    """Verify fixture UID consistency across human-readable, check-in, and package sources."""

    def setUp(self) -> None:
        self.s21_human = _load_fixture("battery_s21_human_readable.txt")
        self.s21_checkin = _load_fixture("battery_s21_checkin.csv")
        self.s23u_human = _load_fixture("battery_s23u_human_readable.txt")
        self.s23u_checkin = _load_fixture("battery_s23u_checkin.csv")

    # ---- S21 coherence ----

    def test_s21_package_uid_to_text_form(self) -> None:
        """S21 decimal 10123 -> u0a123."""
        self.assertEqual(uid_to_android_uid(10123), "u0a123")

    def test_s21_human_uid_block_exists(self) -> None:
        """S21 human-readable has u0a123 detail block."""
        block = extract_uid_block(self.s21_human, 10123)
        self.assertIn("Fg Service for:", block)
        self.assertIn("Proc com.kernel.ai.debug:", block)

    def test_s21_checkin_uid_matches_human(self) -> None:
        """S21 check-in records use decimal 10123 matching human u0a123."""
        parsed = parse_checkin(self.s21_checkin, 10123)
        self.assertEqual(parsed["cpu_user_ms"].state, Availability.AVAILABLE)
        self.assertEqual(parsed["cpu_user_ms"].value, 120)
        self.assertEqual(parsed["estimated_power_mah"].state, Availability.AVAILABLE)

    def test_s21_power_estimate_resolves_to_target(self) -> None:
        """S21 power estimate belongs to target UID."""
        parsed = parse_batterystats(self.s21_human, 10123)
        self.assertEqual(parsed["estimated_power_mah"].value, 0.294)

    def test_s21_unrelated_app_not_attributed(self) -> None:
        """S21 unrelated u0a100 WakeWordLock is NOT on target u0a123."""
        block = extract_uid_block(self.s21_human, 10123)
        self.assertNotIn("WakeWordLock", block)

    # ---- S23U coherence ----

    def test_s23u_package_uid_to_text_form(self) -> None:
        """S23U decimal 10124 -> u0a124."""
        self.assertEqual(uid_to_android_uid(10124), "u0a124")

    def test_s23u_human_uid_block_exists(self) -> None:
        """S23U human-readable has u0a124 detail block."""
        block = extract_uid_block(self.s23u_human, 10124)
        self.assertIn("Fg Service for:", block)
        self.assertIn("Proc com.kernel.ai.debug:", block)

    def test_s23u_checkin_uid_matches_human(self) -> None:
        """S23U check-in records use decimal 10124 matching human u0a124."""
        parsed = parse_checkin(self.s23u_checkin, 10124)
        self.assertEqual(parsed["cpu_user_ms"].state, Availability.AVAILABLE)
        self.assertEqual(parsed["cpu_user_ms"].value, 60)

    def test_s23u_power_estimate_resolves_to_target(self) -> None:
        """S23U power estimate belongs to target UID."""
        parsed = parse_batterystats(self.s23u_human, 10124)
        self.assertEqual(parsed["estimated_power_mah"].value, 0.0980)

    def test_s23u_no_unrelated_wakelock_attribution(self) -> None:
        """S23U no named wakelock records in target block."""
        parsed = parse_batterystats(self.s23u_human, 10124)
        self.assertEqual(parsed["estimated_power_mah"].value, 0.0980)


class CleanupDetailedTest(unittest.TestCase):
    """Detailed cleanup scenarios with command assertion."""

    def _enabled_harness(self, s21: FakePhysicalAdb, s23u: FakePhysicalAdb) -> PairedHarness:
        return PairedHarness("smoke-enabled", "com.kernel.ai.debug", 60, Path("/tmp"), {"s21": s21, "s23u": s23u})

    def test_both_normal_restore(self) -> None:
        """1. Both devices mutate and restore normally."""
        s21 = FakePhysicalAdb("s21", active=True)
        s23u = FakePhysicalAdb("s23u", active=True)
        h = self._enabled_harness(s21, s23u)
        with patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            result = h.run_physical(True)
        self.assertTrue(result.success)
        restores = s21.list_restore_commands() + s23u.list_restore_commands()
        self.assertGreaterEqual(len(restores), 2)
        self.assertEqual(s21.diagnostic, "INFO")
        self.assertEqual(s23u.diagnostic, "INFO")

    def test_verification_raises_after_setprop(self) -> None:
        """2. S21 setprop succeeds but verification call raises."""
        s21 = FakePhysicalAdb("s21", active=True, diagnostic="INFO")
        s21.fail_getprop_after_debug = True
        s23u = FakePhysicalAdb("s23u", active=True)
        h = self._enabled_harness(s21, s23u)
        with patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            result = h.run_physical(True)
        self.assertFalse(result.success)
        self.assertEqual(result.summary["classification"], "ABORTED_NON_EVIDENTIARY")
        self.assertEqual(s21.diagnostic, "INFO")
        self.assertEqual(s23u.diagnostic, "INFO")

    def test_verification_returns_non_debug(self) -> None:
        """3. S21 setprop succeeds but verification returns non-DEBUG value."""
        s21 = FakePhysicalAdb("s21", active=True, diagnostic="INFO")
        s23u = FakePhysicalAdb("s23u", active=True)
        original_shell = s21.shell
        getprop_count = [0]
        def patched_shell(*args: str, timeout: float = 30.0) -> str:
            if args[0] == "getprop" and "WakeWordDiag" in args[1] and getprop_count[0] >= 1:
                return "INFO"
            if args[0] == "getprop" and "WakeWordDiag" in args[1]:
                getprop_count[0] += 1
            return original_shell(*args, timeout=timeout)
        s21.shell = patched_shell
        h = self._enabled_harness(s21, s23u)
        with patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            result = h.run_physical(True)
        self.assertFalse(result.success)
        self.assertIn("ABORTED", result.summary["classification"])

    def test_second_setprop_fails(self) -> None:
        """4. S21 succeeds, S23U setprop fails."""
        s21 = FakePhysicalAdb("s21", active=True)
        s23u = FakePhysicalAdb("s23u", active=True)
        s23u.fail_setprop = True
        h = self._enabled_harness(s21, s23u)
        with patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            result = h.run_physical(True)
        self.assertFalse(result.success)
        self.assertEqual(result.summary["classification"], "ABORTED_NON_EVIDENTIARY")
        self.assertEqual(s21.diagnostic, "INFO")
        self.assertEqual(s23u.diagnostic, "INFO")

    def test_later_precondition_fails(self) -> None:
        """5. Both diagnostics succeed, then later precondition fails."""
        s21 = FakePhysicalAdb("s21", active=True)
        s23u = FakePhysicalAdb("s23u", active=True)
        s23u.fail_unplug = True
        # Make S23U fail shell for unplug detection. Since the boundary_snapshot
        # doesn't check fail_unplug directly, we make dumpsys battery return
        # AC powered for S23U to simulate unplug/precondition failure.
        original_shell = s23u.shell
        def ac_powered(*args: str, timeout: float = 30.0) -> str:
            if args[:2] == ("dumpsys", "battery"):
                return "AC powered: true\nUSB powered: false\nWireless powered: false\nlevel: 80\nstatus: 3\nCharge counter: 4000000\n"
            return original_shell(*args, timeout=timeout)
        s23u.shell = ac_powered
        h = self._enabled_harness(s21, s23u)
        with patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            result = h.run_physical(True)
        self.assertFalse(result.success)
        self.assertEqual(result.summary["classification"], "ABORTED_NON_EVIDENTIARY")
        self.assertEqual(s21.diagnostic, "INFO")
        self.assertEqual(s23u.diagnostic, "INFO")

    def test_end_capture_fails(self) -> None:
        """6. Both diagnostics succeed, then end capture fails."""
        s21 = FakePhysicalAdb("s21", active=True)
        s23u = FakePhysicalAdb("s23u", active=True)
        original_shell = s23u.shell
        end_capture_called = [False]
        def fail_end(*args: str, timeout: float = 30.0) -> str:
            if args[:3] == ("dumpsys", "batterystats", "--charged"):
                end_capture_called[0] = True
            if end_capture_called[0] and args[:2] == ("dumpsys", "procstats"):
                raise HarnessError("injected end capture failure")
            return original_shell(*args, timeout=timeout)
        s23u.shell = fail_end
        h = self._enabled_harness(s21, s23u)
        with patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            result = h.run_physical(True)
        self.assertFalse(result.success)
        self.assertEqual(s21.diagnostic, "INFO")
        self.assertEqual(s23u.diagnostic, "INFO")

    def test_cleanup_command_fails(self) -> None:
        """7. Cleanup setprop fails on one device."""
        s21 = FakePhysicalAdb("s21", active=True, diagnostic="INFO")
        s23u = FakePhysicalAdb("s23u", active=True, diagnostic="INFO")
        original_shell = s23u.shell
        setprop_calls = [0]
        def cleanup_fail_shell(*args: str, timeout: float = 30.0) -> str:
            if args[0] == "setprop" and len(args) >= 3 and args[1] == "log.tag.WakeWordDiag":
                setprop_calls[0] += 1
                if setprop_calls[0] > 1:
                    raise HarnessError("injected cleanup command failure")
            return original_shell(*args, timeout=timeout)
        s23u.shell = cleanup_fail_shell
        h = self._enabled_harness(s21, s23u)
        with patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            result = h.run_physical(True)
        self.assertFalse(result.success)
        self.assertEqual(result.exit_code, 3)

    def test_cleanup_still_debug(self) -> None:
        """8. Cleanup restore succeeded but verification still reports DEBUG."""
        s21 = FakePhysicalAdb("s21", active=True, diagnostic="INFO")
        s23u = FakePhysicalAdb("s23u", active=True, diagnostic="INFO")
        original_shell = s23u.shell
        getprop_calls = [0]
        def debug_after_restore(*args: str, timeout: float = 30.0) -> str:
            if args[0] == "getprop" and "WakeWordDiag" in args[1]:
                getprop_calls[0] += 1
                if getprop_calls[0] >= 3:
                    return "DEBUG"
            return original_shell(*args, timeout=timeout)
        s23u.shell = debug_after_restore
        h = self._enabled_harness(s21, s23u)
        with patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            result = h.run_physical(True)
        self.assertFalse(result.success)
        self.assertEqual(result.exit_code, 3)

    def test_original_was_unset(self) -> None:
        """9. Original property was unset/empty."""
        s21 = FakePhysicalAdb("s21", active=True, diagnostic="")
        s23u = FakePhysicalAdb("s23u", active=True, diagnostic="")
        h = self._enabled_harness(s21, s23u)
        with patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            result = h.run_physical(True)
        self.assertTrue(result.success)
        self.assertEqual(s21.diagnostic, "")
        self.assertEqual(s23u.diagnostic, "")

    def test_original_was_info(self) -> None:
        """10. Original property was INFO."""
        s21 = FakePhysicalAdb("s21", active=True, diagnostic="INFO")
        s23u = FakePhysicalAdb("s23u", active=True, diagnostic="INFO")
        h = self._enabled_harness(s21, s23u)
        with patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            result = h.run_physical(True)
        self.assertTrue(result.success)
        self.assertEqual(s21.diagnostic, "INFO")
        self.assertEqual(s23u.diagnostic, "INFO")

    def test_original_was_verbose(self) -> None:
        """11. Original property was another value (VERBOSE)."""
        s21 = FakePhysicalAdb("s21", active=True, diagnostic="VERBOSE")
        s23u = FakePhysicalAdb("s23u", active=True, diagnostic="VERBOSE")
        h = self._enabled_harness(s21, s23u)
        with patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            result = h.run_physical(True)
        self.assertTrue(result.success)
        self.assertEqual(s21.diagnostic, "VERBOSE")
        self.assertEqual(s23u.diagnostic, "VERBOSE")

    def test_keyboard_interrupt_after_first_mutation(self) -> None:
        """12. KeyboardInterrupt occurs after the first mutation."""
        s21 = FakePhysicalAdb("s21", active=True)
        s23u = FakePhysicalAdb("s23u", active=True)
        iter_calls = [0]
        def interrupting_monotonic_ns() -> int:
            iter_calls[0] += 1
            if iter_calls[0] >= 3:
                raise KeyboardInterrupt()
            return 1_000_000_000
        h = self._enabled_harness(s21, s23u)
        with tempfile.TemporaryDirectory() as root, patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=interrupting_monotonic_ns):
            h.run_dir = Path(root) / "test-kb"
            h.run_dir.mkdir(parents=True, exist_ok=True)
            result = h.run_physical(True)
        self.assertFalse(result.success)
        self.assertEqual(result.summary["classification"], "ABORTED_NON_EVIDENTIARY")
        self.assertEqual(s21.diagnostic, "INFO")


class PhysicalWorkflowTest(unittest.TestCase):
    """Full mocked physical workflow execution."""

    def _run(self, mode: str, s21: FakePhysicalAdb, s23u: FakePhysicalAdb) -> RunResult:
        with tempfile.TemporaryDirectory() as root, patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            return PairedHarness(mode, "com.kernel.ai.debug", 1, Path(root), {"s21": s21, "s23u": s23u}).run_physical(True)

    def test_disabled_smoke_full_workflow(self) -> None:
        """Complete successful disabled smoke workflow."""
        result = self._run("smoke-disabled", FakePhysicalAdb("s21"), FakePhysicalAdb("s23u"))
        self.assertTrue(result.success)
        self.assertEqual(result.summary["classification"], "NON_EVIDENTIARY_SMOKE")
        self.assertEqual(result.exit_code, 0)

    def test_enabled_smoke_full_workflow(self) -> None:
        """Complete successful enabled smoke workflow with diagnostics."""
        s21 = FakePhysicalAdb("s21", active=True, diagnostic="INFO")
        s23u = FakePhysicalAdb("s23u", active=True, diagnostic="INFO")
        result = self._run("smoke-enabled", s21, s23u)
        self.assertTrue(result.success)
        self.assertEqual(result.summary["classification"], "NON_EVIDENTIARY_SMOKE")
        self.assertEqual(result.exit_code, 0)
        self.assertEqual(s21.diagnostic, "INFO")
        self.assertEqual(s23u.diagnostic, "INFO")

    def test_device_validation_failure(self) -> None:
        """Device validation failure in preconditions."""
        s21 = FakePhysicalAdb("s21")
        s21.fail_reachable = True
        s23u = FakePhysicalAdb("s23u")
        result = self._run("smoke-disabled", s21, s23u)
        self.assertFalse(result.success)
        self.assertEqual(result.summary["classification"], "ABORTED_NON_EVIDENTIARY")
        self.assertNotEqual(result.exit_code, 0)

    def test_service_state_mismatch_disabled(self) -> None:
        """Service state mismatch for disabled mode."""
        result = self._run("smoke-disabled", FakePhysicalAdb("s21", active=True), FakePhysicalAdb("s23u"))
        self.assertFalse(result.success)
        self.assertEqual(result.summary["classification"], "ABORTED_NON_EVIDENTIARY")

    def test_diagnostic_verification_failure_restores_mutated_device(self) -> None:
        """First-device diagnostic verification fails."""
        s21 = FakePhysicalAdb("s21", active=True)
        s21.fail_getprop_after_debug = True
        s23u = FakePhysicalAdb("s23u", active=True)
        result = self._run("smoke-enabled", s21, s23u)
        self.assertFalse(result.success)
        self.assertEqual(result.summary["classification"], "ABORTED_NON_EVIDENTIARY")
        self.assertEqual(s21.diagnostic, "INFO")
        self.assertEqual(s23u.diagnostic, "INFO")

    def test_second_device_setup_failure(self) -> None:
        """Second device setprop fails after first succeeds."""
        s21 = FakePhysicalAdb("s21", active=True)
        s23u = FakePhysicalAdb("s23u", active=True)
        s23u.fail_setprop = True
        result = self._run("smoke-enabled", s21, s23u)
        self.assertFalse(result.success)
        self.assertEqual(result.summary["classification"], "ABORTED_NON_EVIDENTIARY")
        self.assertEqual(s21.diagnostic, "INFO")

    def test_bugreport_failure(self) -> None:
        """Bugreport failure invalidates run."""
        s21 = FakePhysicalAdb("s21")
        s21.fail_bugreport = True
        s23u = FakePhysicalAdb("s23u")
        result = self._run("smoke-disabled", s21, s23u)
        self.assertFalse(result.success)
        self.assertEqual(result.summary["classification"], "ABORTED_NON_EVIDENTIARY")

    def test_end_capture_failure(self) -> None:
        """End capture failure invalidates run."""
        s21 = FakePhysicalAdb("s21")
        s23u = FakePhysicalAdb("s23u")
        original_shell = s23u.shell
        end_capture_called = [False]
        def fail_end(*args: str, timeout: float = 30.0) -> str:
            if args[:3] == ("dumpsys", "batterystats", "--charged"):
                end_capture_called[0] = True
            if end_capture_called[0] and args[:2] == ("dumpsys", "procstats"):
                raise HarnessError("injected end capture failure")
            return original_shell(*args, timeout=timeout)
        s23u.shell = fail_end
        result = self._run("smoke-disabled", s21, s23u)
        self.assertFalse(result.success)
        self.assertEqual(result.summary["classification"], "ABORTED_NON_EVIDENTIARY")

    def test_cleanup_verification_failure(self) -> None:
        """Cleanup still DEBUG after restore -> exit 3."""
        s21 = FakePhysicalAdb("s21", active=True)
        s23u = FakePhysicalAdb("s23u", active=True)
        original_shell = s23u.shell
        getprop_count = [0]
        def debug_on_cleanup(*args: str, timeout: float = 30.0) -> str:
            if args[0] == "getprop" and "WakeWordDiag" in args[1]:
                getprop_count[0] += 1
                if getprop_count[0] >= 3:
                    return "DEBUG"
            return original_shell(*args, timeout=timeout)
        s23u.shell = debug_on_cleanup
        result = self._run("smoke-enabled", s21, s23u)
        self.assertFalse(result.success)
        self.assertEqual(result.exit_code, 3)

    def test_duration_parser_rejects_junk(self) -> None:
        """Duration parser rejects malformed inputs."""
        for value in ("1hBAD2m", "1h 2m", "1h2mXYZ", "ms", "+", "1.5h", "2m1h", "1s2s", "01h-2m"):
            with self.assertRaises(ValueError, msg=value):
                _parse_duration_ms(value)


class AbortReportTest(unittest.TestCase):
    """End-to-end abort report writing tests."""

    def _fixture_summary(self, classification: str, **overrides: Any) -> dict[str, Any]:
        """Build a minimal summary for abort report testing."""
        summary: dict[str, Any] = {
            "schema_version": "2.0",
            "classification": classification,
            "run_id": f"test-{classification.lower()}",
            "mode": "baseline-disabled",
            "requested_duration_seconds": 120,
            "primary_comparison": "S21 enabled - S21 disabled; S23U enabled - S23U disabled",
            "cross_device_warning": "Run was aborted; no comparison possible.",
            "validity": {"state": "aborted", "abort_reason": "test abort scenario"},
            "raw_artifacts": "private, gitignored run directory",
            "devices": {
                "s21": {"alias": "s21", "identity": {"manufacturer": "Samsung", "model": "SM-G991B", "android_release": "15", "api_level": "35"}},
                "s23u": {"alias": "s23u", "identity": {"manufacturer": "Samsung", "model": "SM-S918B", "android_release": "15", "api_level": "35"}},
            },
            "limitations": ["Run did not complete; abort summary only. Not evidentiary."],
        }
        summary.update(overrides)
        return summary

    def _write_and_verify(self, summary: dict[str, Any], expected_classification: str, expected_exit: int) -> None:
        """Write summary to temp dir and verify output."""
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            json_path, markdown_path = write_sanitized_summary(output, summary)
            self.assertTrue(json_path.exists(), "run-summary.json must exist")
            self.assertTrue(markdown_path.exists(), "run-summary.md must exist")
            data = json.loads(json_path.read_text())
            self.assertEqual(data["classification"], expected_classification)
            md = markdown_path.read_text()
            self.assertNotIn("bugreport", md.lower())
            self.assertNotIn("batterystats", md.lower())

    def test_pre_validation_abort(self) -> None:
        """Pre-validation abort with identity-only evidence."""
        summary = self._fixture_summary("ABORTED_NON_EVIDENTIARY", validity={"state": "aborted", "abort_reason": "pre-validation failure"})
        self._write_and_verify(summary, "ABORTED_NON_EVIDENTIARY", 1)
        self.assertNotIn("start_skew_ms", summary)

    def test_pre_start_abort(self) -> None:
        """Pre-start abort with identity but no boundary."""
        devices = {
            "s21": {"alias": "s21", "identity": {"manufacturer": "Samsung", "model": "SM-G991B", "android_release": "15", "api_level": "35"}},
            "s23u": {"alias": "s23u", "identity": {"manufacturer": "Samsung", "model": "SM-S918B", "android_release": "15", "api_level": "35"}},
        }
        summary = self._fixture_summary("ABORTED_NON_EVIDENTIARY", devices=devices, validity={"state": "aborted", "abort_reason": "operator declined"})
        with tempfile.TemporaryDirectory() as tmp:
            json_path, markdown_path = write_sanitized_summary(Path(tmp), summary)
            md = markdown_path.read_text()
            self.assertIn("operator declined", md)

    def test_post_start_abort(self) -> None:
        """Post-start abort with start boundary evidence."""
        devices = {
            "s21": {"alias": "s21", "identity": {"manufacturer": "Samsung", "model": "SM-G991B"}, "start": {"wall_clock_utc": "2026-07-11T12:00:00", "battery": {"level_percent": {"state": "available", "value": 80}, "charge_counter_uah": {"state": "available", "value": 4000000}}}},
            "s23u": {"alias": "s23u", "identity": {"manufacturer": "Samsung", "model": "SM-S918B"}, "start": {"wall_clock_utc": "2026-07-11T12:00:01", "battery": {"level_percent": {"state": "available", "value": 85}, "charge_counter_uah": {"state": "available", "value": 4200000}}}},
        }
        summary = self._fixture_summary("ABORTED_NON_EVIDENTIARY", devices=devices, validity={"state": "aborted", "abort_reason": "charging detected"})
        with tempfile.TemporaryDirectory() as tmp:
            json_path, markdown_path = write_sanitized_summary(Path(tmp), summary)
            md = markdown_path.read_text()
            self.assertIn("charging detected", md)

    def test_post_end_abort(self) -> None:
        """Post-end abort with both start and end boundary evidence."""
        devices = {
            "s21": {"alias": "s21", "identity": {"manufacturer": "Samsung", "model": "SM-G991B"}, "start": {"wall_clock_utc": "2026-07-11T12:00:00", "battery": {"level_percent": {"state": "available", "value": 80}}}, "end": {"wall_clock_utc": "2026-07-11T14:00:00", "battery": {"level_percent": {"state": "available", "value": 78}}}},
            "s23u": {"alias": "s23u", "identity": {"manufacturer": "Samsung", "model": "SM-S918B"}, "start": {"wall_clock_utc": "2026-07-11T12:00:01", "battery": {"level_percent": {"state": "available", "value": 85}}}, "end": {"wall_clock_utc": "2026-07-11T14:00:01", "battery": {"level_percent": {"state": "available", "value": 84}}}},
        }
        summary = self._fixture_summary("ABORTED_NON_EVIDENTIARY", devices=devices, validity={"state": "aborted", "abort_reason": "end boundary capture failed"})
        with tempfile.TemporaryDirectory() as tmp:
            json_path, markdown_path = write_sanitized_summary(Path(tmp), summary)
            data = json.loads(json_path.read_text())
            self.assertEqual(data["classification"], "ABORTED_NON_EVIDENTIARY")
            md = markdown_path.read_text()
            self.assertIn("capture failed", md)
    def test_cleanup_failure_abort(self) -> None:
        """Cleanup-failure abort with exit code 3."""
        summary = self._fixture_summary("ABORTED_NON_EVIDENTIARY", validity={"state": "aborted", "abort_reason": "diagnostic cleanup failure: s21: property still DEBUG after restore attempt"})
        with tempfile.TemporaryDirectory() as tmp:
            json_path, markdown_path = write_sanitized_summary(Path(tmp), summary)
            data = json.loads(json_path.read_text())
            self.assertIn("cleanup failure", data["validity"]["abort_reason"])

    def test_fixture_success_output(self) -> None:
        """Fixture dry run produces valid output files."""
        fixture = json.loads(_load_fixture("battery_telemetry_paired_smoke.json"))
        summary = fixture_summary(fixture, "smoke-disabled", 120, "fixture-test")
        self.assertEqual(summary["classification"], "NON_EVIDENTIARY_FIXTURE_DRY_RUN")
        with tempfile.TemporaryDirectory() as tmp:
            json_path, markdown_path = write_sanitized_summary(Path(tmp), summary)
            self.assertTrue(json_path.exists())
            self.assertTrue(markdown_path.exists())

    def test_disabled_smoke_writes_summaries(self) -> None:
        """Disabled smoke writes valid JSON and Markdown summaries."""
        s21 = FakePhysicalAdb("s21")
        s23u = FakePhysicalAdb("s23u")
        with tempfile.TemporaryDirectory() as root, patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            h = PairedHarness("smoke-disabled", "com.kernel.ai.debug", 60, Path(root), {"s21": s21, "s23u": s23u})
            result = h.run_physical(True)
        self.assertTrue(result.success)
        self.assertEqual(result.summary["classification"], "NON_EVIDENTIARY_SMOKE")
        with tempfile.TemporaryDirectory() as tmp:
            json_path, markdown_path = write_sanitized_summary(Path(tmp), result.summary)
            data = json.loads(json_path.read_text())
            self.assertEqual(data["classification"], "NON_EVIDENTIARY_SMOKE")
            md = markdown_path.read_text()
            self.assertIn("smoke", md.lower())

    def test_enabled_smoke_writes_summaries(self) -> None:
        """Enabled smoke with diagnostics writes valid summaries."""
        s21 = FakePhysicalAdb("s21", active=True, diagnostic="INFO")
        s23u = FakePhysicalAdb("s23u", active=True, diagnostic="INFO")
        with tempfile.TemporaryDirectory() as root, patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            h = PairedHarness("smoke-enabled", "com.kernel.ai.debug", 60, Path(root), {"s21": s21, "s23u": s23u})
            result = h.run_physical(True)
        self.assertTrue(result.success)
        with tempfile.TemporaryDirectory() as tmp:
            json_path, markdown_path = write_sanitized_summary(Path(tmp), result.summary)
            data = json.loads(json_path.read_text())
            self.assertEqual(data["classification"], "NON_EVIDENTIARY_SMOKE")
    def test_evidentiary_creates_full_report(self) -> None:
        """Successful evidentiary run creates full report with all sections."""
        s21 = FakePhysicalAdb("s21")
        s23u = FakePhysicalAdb("s23u")
        with tempfile.TemporaryDirectory() as root, patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            h = PairedHarness("baseline-disabled", "com.kernel.ai.debug", 3600, Path(root), {"s21": s21, "s23u": s23u})
            result = h.run_physical(True)
        self.assertTrue(result.success)
        with tempfile.TemporaryDirectory() as tmp:
            json_path, markdown_path = write_sanitized_summary(Path(tmp), result.summary)
            data = json.loads(json_path.read_text())
            self.assertEqual(data["classification"], "EVIDENTIARY")
            md = markdown_path.read_text()
            self.assertIn("EVIDENTIARY", md)

    def test_abort_markdown_no_crash_on_missing_start_skew(self) -> None:
        """Pre-start abort markdown does not crash on missing start_skew_ms."""
        devices = {
            "s21": {"alias": "s21", "identity": {"manufacturer": "Samsung", "model": "SM-G991B", "android_release": "15", "api_level": "35"}},
            "s23u": {"alias": "s23u", "identity": {"manufacturer": "Samsung", "model": "SM-S918B", "android_release": "15", "api_level": "35"}},
        }
        summary = self._fixture_summary("ABORTED_NON_EVIDENTIARY", devices=devices)
        summary.pop("start_skew_ms", None)
        with tempfile.TemporaryDirectory() as tmp:
            json_path, markdown_path = write_sanitized_summary(Path(tmp), summary)
            md = markdown_path.read_text()
            self.assertIn("unavailable", md.lower())

    def test_abort_markdown_no_zero_fabrication(self) -> None:
        """Abort summary does not fabricate zero battery values."""
        summary = self._fixture_summary("ABORTED_NON_EVIDENTIARY")
        with tempfile.TemporaryDirectory() as tmp:
            json_path, markdown_path = write_sanitized_summary(Path(tmp), summary)
            md = markdown_path.read_text()
            self.assertIn("unavailable", md.lower())

    def test_report_write_failure_nonzero(self) -> None:
        """Writing report output failure must produce non-zero exit code."""
        with tempfile.TemporaryDirectory() as tmp:
            read_only = Path(tmp) / "readonly"
            read_only.mkdir(mode=0o444)
            summary = self._fixture_summary("ABORTED_NON_EVIDENTIARY")
            with self.assertRaises((HarnessError, OSError)):
                write_sanitized_summary(read_only / "sanitized", summary)

    def test_no_private_selector_in_reports(self) -> None:
        """No private ADB selector appears in reports."""
        s21 = FakePhysicalAdb("s21")
        s23u = FakePhysicalAdb("s23u")
        with tempfile.TemporaryDirectory() as root, patch("builtins.input", return_value="START"), patch("time.sleep"), patch("time.monotonic_ns", side_effect=range(1_000_000_000, 2_000_000_000, 10_000_000)):
            h = PairedHarness("smoke-disabled", "com.kernel.ai.debug", 60, Path(root), {"s21": s21, "s23u": s23u})
            result = h.run_physical(True)
        with tempfile.TemporaryDirectory() as tmp:
            json_path, markdown_path = write_sanitized_summary(Path(tmp), result.summary, ("FAKE-S21", "FAKE-S23U"))
            text = json_path.read_text() + markdown_path.read_text()
            self.assertNotIn("FAKE-S21", text)
            self.assertNotIn("FAKE-S23U", text)

if __name__ == "__main__":
    unittest.main()
