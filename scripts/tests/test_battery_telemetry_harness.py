#!/usr/bin/env python3
"""Tests for the paired battery telemetry harness."""
from __future__ import annotations

import inspect
import json
import sys
import tempfile
import unittest
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
        self.assertNotIn("Uid 1000", block)

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

    def test_wakelock_with_type(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        wakes = parsed["checkin_wakelocks"].value
        self.assertEqual(len(wakes), 1)
        self.assertEqual(wakes[0]["name"], "WakeWordLock")
        self.assertEqual(wakes[0]["type"], "partial")
        self.assertEqual(wakes[0]["duration_ms"], 4200)
        self.assertEqual(wakes[0]["count"], 14)

    def test_fgs_not_sf(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        self.assertEqual(parsed["checkin_foreground_service_ms"].value, 3600000)

    def test_proc_cpu(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        self.assertEqual(parsed["checkin_proc_cpu_user_ms"].value, 120)
        self.assertEqual(parsed["checkin_proc_cpu_kernel_ms"].value, 7)

    def test_audio(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 10123)
        self.assertEqual(parsed["checkin_audio_ms"].value, 3600000)

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
        self.assertEqual(parsed["cpu_user_ms"].state, Availability.NOT_REPORTED)

    def test_system_uid(self) -> None:
        parsed = parse_checkin(self.s21_checkin, 1000)
        self.assertEqual(parsed["cpu_user_ms"].value, 50)


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
        text = "  Uid u0a123:\n    cpu:\n      user: 0ms\n      system: 0ms\n    power: 0.00 mAh"
        p = parse_batterystats(text, 10123)
        self.assertEqual(p["cpu_user_ms"].state, Availability.AVAILABLE)
        self.assertEqual(p["cpu_user_ms"].value, 0)

    def test_nonzero(self) -> None:
        text = "  Uid u0a123:\n    cpu:\n      user: 120ms\n      system: 7ms\n    power: 1.50 mAh"
        self.assertEqual(parse_batterystats(text, 10123)["cpu_user_ms"].value, 120)

    def test_missing_not_zero(self) -> None:
        text = "  Uid u0a123:\n    Wake lock: WakeWordLock +1s000ms (partial) count 1\n    power: 0.50 mAh"
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


class FixtureDryRunTest(unittest.TestCase):
    """Fixture-mode dry-run produces safe output."""

    def test_summary_commit_safe(self) -> None:
        fixture = {
            "devices": {
                "s21": {
                    "manufacturer": "Samsung", "model": "SM-G991B", "android_api": "35",
                    "start_monotonic_ms": 1000, "start_level": 80, "end_level": 79,
                    "uid": 10123,
                    "batterystats": "  Uid u0a123:\n    cpu:\n      user: 120ms\n      system: 7ms\n    Wake lock: WakeWordLock +4s200ms (partial) count 14\n    power: 1.50 mAh\n",
                },
                "s23u": {
                    "manufacturer": "Samsung", "model": "SM-S918B", "android_api": "35",
                    "start_monotonic_ms": 1035, "start_level": 80, "end_level": 80,
                    "uid": 10124,
                    "batterystats": "  Uid u0a124:\n    cpu:\n      user: 60ms\n      system: 3ms\n    Wake lock: WakeWordLock +2s100ms (partial) count 8\n    power: 0.80 mAh\n",
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


if __name__ == "__main__":
    unittest.main()
