#!/usr/bin/env python3
"""Paired, privacy-safe battery telemetry harness for Issue #1393.

This harness prepares two later controlled comparisons; it does not tune wake-word
production behavior.  Real runs require manual operator checkpoints.  A fixture
mode exercises parsing, sanitisation, reporting, and paired orchestration without
ADB or device state changes.

Examples (identifiers stay local and are never written to public output):
  python3 scripts/battery_telemetry_harness.py smoke --fixture fixture.json
  python3 scripts/battery_telemetry_harness.py baseline-disabled \
      --s21 "$JANDAL_S21_ADB" --s23u "$JANDAL_S23U_ADB" --duration 4h
  python3 scripts/battery_telemetry_harness.py enabled \
      --s21 "$JANDAL_S21_ADB" --s23u "$JANDAL_S23U_ADB" --duration 4h
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import os
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PACKAGE = "com.kernel.ai.debug"
DEFAULT_PRIVATE_ROOT = REPO_ROOT / "scripts" / "private-battery-runs"
DIAGNOSTIC_TAG = "WakeWordDiag"
EXPECTED_DEVICES = {
    "s21": {"manufacturer": "samsung", "model": "SM-G991B"},
    "s23u": {"manufacturer": "samsung", "model": "SM-S918B"},
}

PRIVATE_PATTERNS = (
    re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b"),
    re.compile(r"\b(?:[0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F:]*\b"),
    re.compile(r"\b[A-Z0-9]{8,}\b"),
    re.compile(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b"),
    re.compile(r"(?:/home/[^\s]+|~/(?:[^\s]+)?)"),
    re.compile(r"(?i)(?:pair(?:ing)?\s*(?:code|pin)\s*[:=]\s*)\d+"),
)
RAW_FILENAMES = {"bugreport", "batterystats-charged.txt", "batterystats-checkin.csv", "logcat.txt"}


class HarnessError(RuntimeError):
    """A precondition or collection failure that invalidates the paired attempt."""


class Availability(str, Enum):
    AVAILABLE = "available"
    NOT_REPORTED = "not_reported"
    UNSUPPORTED = "unsupported"
    PARSE_FAILED = "parse_failed"
    NOT_APPLICABLE = "not_applicable"


@dataclasses.dataclass(frozen=True)
class Metric:
    state: Availability
    value: Any = None
    detail: str | None = None

    def public(self) -> dict[str, Any]:
        result: dict[str, Any] = {"state": self.state.value}
        if self.value is not None:
            result["value"] = self.value
        if self.detail:
            result["detail"] = self.detail
        return result


def available(value: Any) -> Metric:
    return Metric(Availability.AVAILABLE, value)


def not_reported(detail: str) -> Metric:
    return Metric(Availability.NOT_REPORTED, detail=detail)


def unsupported(detail: str) -> Metric:
    return Metric(Availability.UNSUPPORTED, detail=detail)


def parse_failed(detail: str) -> Metric:
    return Metric(Availability.PARSE_FAILED, detail=detail)


def not_applicable(detail: str) -> Metric:
    return Metric(Availability.NOT_APPLICABLE, detail=detail)


def parse_duration(value: str) -> int:
    match = re.fullmatch(r"(\d+)([smhd])", value.strip().lower())
    if not match:
        raise argparse.ArgumentTypeError("duration must be an integer followed by s, m, h, or d")
    amount, unit = int(match.group(1)), match.group(2)
    return amount * {"s": 1, "m": 60, "h": 3600, "d": 86400}[unit]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def sanitise_text(text: str, secrets: Iterable[str] = ()) -> str:
    """Redact known device secrets and generic private identifiers from public output."""
    sanitized = text
    for secret in sorted({value for value in secrets if value}, key=len, reverse=True):
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
    if any(name in text.lower() for name in RAW_FILENAMES):
        raise HarnessError("sanitized output must not name raw artifact files")


def metric_from_match(text: str, pattern: str, label: str) -> Metric:
    match = re.search(pattern, text, re.IGNORECASE | re.MULTILINE)
    if not match:
        return not_reported(f"{label} not reported")
    try:
        raw = match.group(1)
        return available(float(raw) if "." in raw else int(raw))
    except ValueError:
        return parse_failed(f"{label} was present but non-numeric")


def parse_battery_dump(text: str) -> dict[str, Metric]:
    """Parse OEM battery output without converting absent values to zero."""
    values: dict[str, Metric] = {}
    aliases = {
        "level_percent": (r"^\s*level:\s*(-?\d+)", "battery level"),
        "charge_counter_uah": (r"^\s*Charge counter:\s*(-?\d+)", "charge counter"),
        "voltage_mv": (r"^\s*voltage:\s*(-?\d+)", "voltage"),
        "temperature_tenths_c": (r"^\s*temperature:\s*(-?\d+)", "temperature"),
        "health": (r"^\s*health:\s*(-?\d+)", "health"),
        "status": (r"^\s*status:\s*(-?\d+)", "status"),
        "cycle_count": (r"^\s*cycle count:\s*(-?\d+)", "cycle count"),
        "full_charge_uah": (r"^\s*(?:Full charge|full charge capacity):\s*(-?\d+)", "full charge capacity"),
    }
    for key, (pattern, label) in aliases.items():
        values[key] = metric_from_match(text, pattern, label)
    for key, label in (("ac_powered", "AC powered"), ("usb_powered", "USB powered"), ("wireless_powered", "Wireless powered")):
        match = re.search(rf"^\s*{re.escape(label)}:\s*(true|false)", text, re.IGNORECASE | re.MULTILINE)
        values[key] = available(match.group(1).lower() == "true") if match else not_reported(f"{label} not reported")
    return values


def parse_package_metadata(text: str, package: str) -> dict[str, Metric]:
    uid_match = re.search(r"\buserId=(\d+)", text)
    version_name = re.search(r"\bversionName=([^\s]+)", text)
    version_code = re.search(r"\bversionCode=(\d+)", text)
    return {
        "package": available(package) if package in text else not_reported("package absent from dumpsys package"),
        "uid": available(int(uid_match.group(1))) if uid_match else not_reported("Android package UID not reported"),
        "version_name": available(version_name.group(1)) if version_name else not_reported("version name not reported"),
        "version_code": available(int(version_code.group(1))) if version_code else not_reported("version code not reported"),
    }


def parse_batterystats(text: str, uid: int | None) -> dict[str, Metric]:
    """Parse common labelled Batterystats samples for a resolved app UID.

    Android and Samsung formats vary.  A UID must be resolved first; unmatched
    fields are reported as not_reported rather than silently treated as zero.
    """
    if uid is None:
        return {key: unsupported("package UID unavailable") for key in (
            "cpu_user_ms", "cpu_kernel_ms", "foreground_service_ms", "estimated_power_mah", "partial_wakelocks")}
    uid_markers = (rf"\buid[=:, ]+{uid}\b", rf"\bUID\s+{uid}\b", rf"\b{uid},")
    uid_lines = [line for line in text.splitlines() if any(re.search(marker, line, re.IGNORECASE) for marker in uid_markers)]
    if not uid_lines:
        return {key: not_reported(f"UID {uid} absent from complete Batterystats") for key in (
            "cpu_user_ms", "cpu_kernel_ms", "foreground_service_ms", "estimated_power_mah", "partial_wakelocks")}
    block = "\n".join(uid_lines)
    result = {
        "cpu_user_ms": metric_from_match(block, r"(?:cpu[ _-]*user|user[ _-]*cpu)[=:, ]+(\d+)", "app CPU user time"),
        "cpu_kernel_ms": metric_from_match(block, r"(?:cpu[ _-]*(?:kernel|system)|kernel[ _-]*cpu)[=:, ]+(\d+)", "app CPU kernel time"),
        "foreground_service_ms": metric_from_match(block, r"(?:foreground[ _-]*service|fgs)[=:, ]+(\d+)", "foreground service duration"),
        "estimated_power_mah": metric_from_match(block, r"(?:estimated[ _-]*power|power[ _-]*mah)[=:, ]+(\d+(?:\.\d+)?)", "estimated app power"),
    }
    wakelocks = re.findall(r"(?:partial[ _-]*wakelock|wl)[=:, ]+([^,; ]+)[,:; ]+(\d+)", block, re.IGNORECASE)
    result["partial_wakelocks"] = available([{"name": name, "duration_ms": int(duration)} for name, duration in wakelocks]) if wakelocks else not_reported("partial wakelocks not reported for UID")
    return result


def parse_checkin(text: str, uid: int | None) -> dict[str, Metric]:
    """Parse a deliberately narrow check-in representation and preserve absence."""
    if uid is None:
        return {"cpu_user_ms": unsupported("package UID unavailable"), "cpu_kernel_ms": unsupported("package UID unavailable")}
    rows = [row.split(",") for row in text.splitlines() if row.strip()]
    uid_rows = [row for row in rows if str(uid) in row]
    if not uid_rows:
        return {"cpu_user_ms": not_reported("UID absent from check-in"), "cpu_kernel_ms": not_reported("UID absent from check-in")}
    fields: dict[str, int] = {}
    for row in uid_rows:
        for index, token in enumerate(row[:-1]):
            if token in {"cpu_user_ms", "cpu_kernel_ms"}:
                try:
                    fields[token] = int(row[index + 1])
                except ValueError:
                    return {"cpu_user_ms": parse_failed("malformed check-in CPU value"), "cpu_kernel_ms": parse_failed("malformed check-in CPU value")}
    return {
        "cpu_user_ms": available(fields["cpu_user_ms"]) if "cpu_user_ms" in fields else not_reported("check-in CPU user time not reported"),
        "cpu_kernel_ms": available(fields["cpu_kernel_ms"]) if "cpu_kernel_ms" in fields else not_reported("check-in CPU kernel time not reported"),
    }


def parse_wake_word_diagnostics(text: str, enabled: bool) -> dict[str, Metric]:
    keys = ("audioFrames", "stage1", "stage2", "stage3", "stage2PerHour", "stage3PerHour", "silenceSkips", "silenceSkipRatio", "verifier", "verifierPasses", "verifierRejects", "highActivations", "verifiedActivations", "embeddingProvider")
    if not enabled:
        return {key: not_applicable("wake word disabled") for key in keys}
    lines = [line for line in text.splitlines() if "WakeWordDetector: diagnostics" in line]
    if not lines:
        return {key: not_reported("no WakeWordDiag summary captured") for key in keys}
    parsed = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^\s]+)", lines[-1]))
    return {key: available(parsed[key]) if key in parsed else not_reported(f"diagnostic field {key} not reported") for key in keys}


def start_skew_ms(start_times: dict[str, int]) -> Metric:
    if set(start_times) != {"s21", "s23u"}:
        return parse_failed("both paired monotonic timestamps are required")
    return available(abs(start_times["s21"] - start_times["s23u"]))


@dataclasses.dataclass
class DeviceIdentity:
    alias: str
    manufacturer: str
    model: str
    android_release: str
    android_api: str
    build_fingerprint: str
    package: dict[str, Metric]

    def public(self) -> dict[str, Any]:
        return {
            "alias": self.alias,
            "manufacturer": self.manufacturer,
            "model": self.model,
            "android_release": self.android_release,
            "android_api": self.android_api,
            "build_fingerprint": sanitise_text(self.build_fingerprint),
            "package": {key: value.public() for key, value in self.package.items()},
        }


class AdbClient:
    def __init__(self, serial: str, runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run):
        self.serial = serial
        self._runner = runner

    def run(self, *args: str, timeout: float = 30.0) -> str:
        result = self._runner(["adb", "-s", self.serial, *args], text=True, capture_output=True, timeout=timeout)
        if result.returncode != 0:
            raise HarnessError(f"ADB command failed: {' '.join(args)}")
        return result.stdout

    def shell(self, *args: str, timeout: float = 30.0) -> str:
        return self.run("shell", *args, timeout=timeout)

    def reachable(self) -> bool:
        return self.run("get-state").strip() == "device"


def device_identity(client: AdbClient, alias: str, package: str) -> DeviceIdentity:
    if not client.reachable():
        raise HarnessError(f"{alias}: ADB is not reachable")
    props = {key: client.shell("getprop", key).strip() for key in (
        "ro.product.manufacturer", "ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.build.fingerprint")}
    expected = EXPECTED_DEVICES[alias]
    if props["ro.product.manufacturer"].lower() != expected["manufacturer"] or props["ro.product.model"] != expected["model"]:
        raise HarnessError(f"{alias}: required device identity did not match approved model")
    package_dump = client.shell("dumpsys", "package", package)
    metadata = parse_package_metadata(package_dump, package)
    if metadata["package"].state is not Availability.AVAILABLE:
        raise HarnessError(f"{alias}: Jandal package is not installed")
    return DeviceIdentity(alias, props["ro.product.manufacturer"], props["ro.product.model"], props["ro.build.version.release"], props["ro.build.version.sdk"], props["ro.build.fingerprint"], metadata)


def service_active(client: AdbClient, package: str) -> bool:
    return "WakeWordService" in client.shell("dumpsys", "activity", "services", package)


def external_power_ok(battery: dict[str, Metric]) -> bool:
    return all(battery[key].state is Availability.AVAILABLE and battery[key].value is False for key in ("ac_powered", "usb_powered", "wireless_powered")) and battery["status"].state is Availability.AVAILABLE and battery["status"].value == 3


def screen_off(power: str, device_idle: str) -> bool:
    return "mScreenOn=false" in power or "mScreenOn=false" in device_idle


def prompt_for_confirmation(message: str, interactive: bool) -> None:
    if not interactive:
        raise HarnessError("physical execution requires --interactive operator confirmations")
    print(message)
    answer = input("Type START to continue, anything else aborts: ").strip()
    if answer != "START":
        raise HarnessError("operator did not confirm required manual checkpoint")


class PairedHarness:
    def __init__(self, mode: str, package: str, duration_seconds: int, private_root: Path, clients: dict[str, AdbClient] | None = None):
        self.mode = mode
        self.enabled = mode == "enabled"
        self.package = package
        self.duration_seconds = duration_seconds
        self.private_root = private_root
        self.clients = clients or {}
        self.run_id = f"{mode}-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{uuid.uuid4().hex[:8]}"
        self.run_dir = private_root / self.run_id
        self.abort_reason: str | None = None
        self.identities: dict[str, DeviceIdentity] = {}

    def validate_devices(self) -> None:
        if set(self.clients) != {"s21", "s23u"}:
            raise HarnessError("both explicitly selected paired devices are required")
        self.identities = {alias: device_identity(client, alias, self.package) for alias, client in self.clients.items()}

    def verify_mode_service_state(self) -> None:
        expected_active = self.enabled
        for alias, client in self.clients.items():
            actual = service_active(client, self.package)
            if actual != expected_active:
                state = "active" if expected_active else "inactive"
                raise HarnessError(f"{alias}: wake-word service must be {state} for {self.mode}")
            if not self.enabled:
                level = client.shell("getprop", f"log.tag.{DIAGNOSTIC_TAG}").strip().upper()
                if level == "DEBUG":
                    raise HarnessError(f"{alias}: {DIAGNOSTIC_TAG} DEBUG override must be removed for disabled baseline")

    def set_enabled_diagnostics(self) -> None:
        if not self.enabled:
            return
        for client in self.clients.values():
            client.shell("setprop", f"log.tag.{DIAGNOSTIC_TAG}", "DEBUG")

    def verify_enabled_diagnostics(self) -> None:
        for alias, client in self.clients.items():
            logcat = client.run("logcat", "-d", "-v", "threadtime", "-s", f"{DIAGNOSTIC_TAG}:D", "*:S", timeout=120)
            if "WakeWordDetector: diagnostics" not in logcat:
                raise HarnessError(f"{alias}: no {DIAGNOSTIC_TAG} summary observed after detector re-arm")

    def write_private_manifest(self) -> None:
        self.run_dir.mkdir(parents=True, exist_ok=True)
        manifest = {
            "run_id": self.run_id,
            "mode": self.mode,
            "created_utc": utc_now(),
            "private_adb_selectors": {alias: client.serial for alias, client in self.clients.items()},
        }
        (self.run_dir / "manifest-private.json").write_text(json.dumps(manifest, indent=2) + "\n")

    def boundary_snapshot(self, alias: str, phase: str) -> dict[str, Any]:
        client = self.clients[alias]
        battery_text = client.shell("dumpsys", "battery")
        power = client.shell("dumpsys", "power")
        idle = client.shell("dumpsys", "deviceidle")
        services = client.shell("dumpsys", "activity", "services", self.package)
        return {
            "phase": phase,
            "wall_clock_utc": utc_now(),
            "monotonic_ms": time.monotonic_ns() // 1_000_000,
            "battery": parse_battery_dump(battery_text),
            "screen_off": screen_off(power, idle),
            "service_active": "WakeWordService" in services,
            "device_idle_excerpt": sanitise_text("\n".join(line.strip() for line in idle.splitlines() if "mState" in line or "mScreenOn" in line)),
        }

    def verify_unplugged_pair(self, boundaries: dict[str, dict[str, Any]]) -> None:
        failures = []
        for alias, snapshot in boundaries.items():
            if not external_power_ok(snapshot["battery"]):
                failures.append(f"{alias}: external power or discharging precondition failed")
            if not snapshot["screen_off"]:
                failures.append(f"{alias}: screen-off precondition failed")
            if snapshot["service_active"] != self.enabled:
                failures.append(f"{alias}: unexpected wake-word service state")
        if failures:
            raise HarnessError("; ".join(failures))

    def private_write(self, alias: str, phase: str, name: str, content: str) -> None:
        path = self.run_dir / alias / phase / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def capture_end_raw(self, alias: str) -> dict[str, str]:
        client = self.clients[alias]
        commands = {
            "battery.txt": ("dumpsys", "battery"),
            "batteryproperties.txt": ("dumpsys", "batteryproperties"),
            "batterystats-charged.txt": ("dumpsys", "batterystats", "--charged"),
            "batterystats-checkin.csv": ("dumpsys", "batterystats", "--checkin"),
            "power.txt": ("dumpsys", "power"),
            "deviceidle.txt": ("dumpsys", "deviceidle"),
            "services.txt": ("dumpsys", "activity", "services", self.package),
            "procstats.txt": ("dumpsys", "procstats", "--hours", str(max(1, self.duration_seconds // 3600)), self.package),
            "meminfo.txt": ("dumpsys", "meminfo", self.package),
            "package.txt": ("dumpsys", "package", self.package),
        }
        raw: dict[str, str] = {}
        for filename, command in commands.items():
            raw[filename] = client.shell(*command, timeout=120)
            self.private_write(alias, "end", filename, raw[filename])
        if self.enabled:
            raw["logcat.txt"] = client.run("logcat", "-d", "-v", "threadtime", "-s", f"{DIAGNOSTIC_TAG}:D", "*:S", timeout=120)
            self.private_write(alias, "end", "logcat.txt", raw["logcat.txt"])
        return raw

    def collect_bugreport(self, alias: str) -> None:
        destination = self.run_dir / alias / "bugreport"
        destination.mkdir(parents=True, exist_ok=True)
        self.clients[alias].run("bugreport", str(destination), timeout=900)

    def public_summary(self, starts: dict[str, dict[str, Any]], ends: dict[str, dict[str, Any]], raw_end: dict[str, dict[str, str]], classification: str) -> dict[str, Any]:
        devices: dict[str, Any] = {}
        for alias in ("s21", "s23u"):
            identity = self.identities[alias]
            battery_start = starts[alias]["battery"]
            battery_end = ends[alias]["battery"]
            uid_metric = identity.package["uid"]
            uid = uid_metric.value if uid_metric.state is Availability.AVAILABLE else None
            stats = parse_batterystats(raw_end[alias].get("batterystats-charged.txt", ""), uid)
            checkin = parse_checkin(raw_end[alias].get("batterystats-checkin.csv", ""), uid)
            wake = parse_wake_word_diagnostics(raw_end[alias].get("logcat.txt", ""), self.enabled)
            delta = not_reported("boundary battery level unavailable")
            rate = not_reported("duration or battery level unavailable")
            if battery_start["level_percent"].state is Availability.AVAILABLE and battery_end["level_percent"].state is Availability.AVAILABLE:
                loss = battery_start["level_percent"].value - battery_end["level_percent"].value
                delta = available(loss)
                rate = available(round(loss * 3600 / self.duration_seconds, 3))
            devices[alias] = {
                "identity": identity.public(),
                "start": self._public_snapshot(starts[alias]),
                "end": self._public_snapshot(ends[alias]),
                "whole_device": {"battery_delta_percentage_points": delta.public(), "battery_percentage_points_per_hour": rate.public()},
                "battery_health": {key: battery_end[key].public() for key in ("charge_counter_uah", "voltage_mv", "temperature_tenths_c", "health", "cycle_count", "full_charge_uah")},
                "app_attribution": {key: value.public() for key, value in {**stats, **{f"checkin_{key}": value for key, value in checkin.items()}}.items()},
                "wake_word": {key: value.public() for key, value in wake.items()},
            }
        summary = {
            "schema_version": "1.0",
            "classification": classification,
            "run_id": self.run_id,
            "mode": self.mode,
            "duration_seconds": self.duration_seconds,
            "primary_comparison": "S21 enabled − S21 disabled; S23U enabled − S23U disabled",
            "cross_device_warning": "Percentage-point differences are not equal energy differences across batteries; use only as secondary context.",
            "start_skew_ms": start_skew_ms({alias: starts[alias]["monotonic_ms"] for alias in starts}).public(),
            "validity": {"state": "valid" if classification == "EVIDENTIARY" else "non_evidentiary", "abort_reason": self.abort_reason},
            "raw_artifacts": "private, gitignored run directory; not named in commit-safe output",
            "devices": devices,
            "limitations": [
                "Missing platform/OEM fields retain explicit availability states and are never converted to zero.",
                "No accelerator assignment is claimed without native provider evidence.",
                "Smoke and fixture runs never support battery or causal conclusions.",
            ],
        }
        assert_commit_safe(summary)
        return summary

    @staticmethod
    def _public_snapshot(snapshot: dict[str, Any]) -> dict[str, Any]:
        return {
            "wall_clock_utc": snapshot["wall_clock_utc"],
            "battery": {key: metric.public() for key, metric in snapshot["battery"].items()},
            "screen_off": snapshot["screen_off"],
            "service_active": snapshot["service_active"],
            "device_idle": snapshot["device_idle_excerpt"],
        }

    def run_physical(self, interactive: bool) -> dict[str, Any]:
        self.write_private_manifest()
        self.validate_devices()
        if self.mode == "baseline-disabled":
            prompt_for_confirmation("Manually disable Listen for Hey Jandal on both S21 and S23 Ultra. Return only after both toggles are off.", interactive)
        elif self.mode == "enabled":
            prompt_for_confirmation("Manually enable Listen for Hey Jandal on both devices, verify the intended build/assets, and complete one pre-test wake smoke on each device.", interactive)
        else:
            prompt_for_confirmation("This is a NON_EVIDENTIARY_SMOKE. Prepare the requested mode manually and confirm both device states.", interactive)
        self.verify_mode_service_state()
        self.set_enabled_diagnostics()
        if self.enabled:
            prompt_for_confirmation("Restart or re-arm both detectors after setting WakeWordDiag DEBUG, then wait for and confirm a WakeWordDiag summary can be observed locally.", interactive)
            self.verify_enabled_diagnostics()
            self.verify_mode_service_state()
        prompt_for_confirmation("Physically unplug charger and USB from both devices. Turn both screens off and lock them. Confirm only when both are ready.", interactive)
        pre_reset = {alias: self.boundary_snapshot(alias, "pre_reset") for alias in self.clients}
        self.verify_unplugged_pair(pre_reset)
        for client in self.clients.values():
            client.shell("dumpsys", "batterystats", "--reset")
        starts = {alias: self.boundary_snapshot(alias, "start") for alias in self.clients}
        self.verify_unplugged_pair(starts)
        if self.mode == "smoke":
            wait_seconds = min(self.duration_seconds, 120)
            classification = "NON_EVIDENTIARY_SMOKE"
        else:
            wait_seconds = self.duration_seconds
            classification = "EVIDENTIARY"
        time.sleep(wait_seconds)
        ends = {alias: self.boundary_snapshot(alias, "end") for alias in self.clients}
        self.verify_unplugged_pair(ends)
        raw = {alias: self.capture_end_raw(alias) for alias in self.clients}
        for alias in self.clients:
            self.collect_bugreport(alias)
        if self.enabled:
            for client in self.clients.values():
                client.shell("setprop", f"log.tag.{DIAGNOSTIC_TAG}", "INFO")
        return self.public_summary(starts, ends, raw, classification)


def fixture_summary(fixture: dict[str, Any], mode: str, duration_seconds: int, run_id: str) -> dict[str, Any]:
    """Produce a commit-safe dry-run summary from synthetic fixture data only."""
    devices = fixture.get("devices")
    if not isinstance(devices, dict) or set(devices) != {"s21", "s23u"}:
        raise HarnessError("fixture must provide exactly s21 and s23u devices")
    starts = {alias: int(device["start_monotonic_ms"]) for alias, device in devices.items()}
    output_devices: dict[str, Any] = {}
    for alias, device in devices.items():
        output_devices[alias] = {
            "identity": {"alias": alias, "manufacturer": device["manufacturer"], "model": device["model"], "android_api": device["android_api"]},
            "whole_device": {
                "battery_delta_percentage_points": available(device["start_level"] - device["end_level"]).public(),
                "battery_percentage_points_per_hour": available(round((device["start_level"] - device["end_level"]) * 3600 / duration_seconds, 3)).public(),
            },
            "app_attribution": {key: value.public() for key, value in parse_batterystats(device.get("batterystats", ""), device.get("uid")).items()},
            "wake_word": {key: value.public() for key, value in parse_wake_word_diagnostics(device.get("wakeword_diag", ""), mode == "enabled").items()},
        }
    summary = {
        "schema_version": "1.0",
        "classification": "NON_EVIDENTIARY_FIXTURE_DRY_RUN",
        "run_id": run_id,
        "mode": mode,
        "duration_seconds": duration_seconds,
        "primary_comparison": "S21 enabled − S21 disabled; S23U enabled − S23U disabled",
        "start_skew_ms": start_skew_ms(starts).public(),
        "validity": {"state": "fixture_only", "abort_reason": None},
        "raw_artifacts": "none; fixture mode performs no ADB or device state changes",
        "devices": output_devices,
        "limitations": ["Synthetic fixture only; no battery or causal conclusion."],
    }
    assert_commit_safe(summary)
    return summary


def render_markdown(summary: dict[str, Any]) -> str:
    lines = [
        "# Paired battery telemetry summary",
        "",
        f"**Classification:** `{summary['classification']}`",
        f"**Mode:** `{summary['mode']}`",
        f"**Duration:** {summary['duration_seconds']} seconds",
        "",
        "> Primary comparison: **S21 enabled − S21 disabled** and **S23U enabled − S23U disabled**. Cross-device percentage points are secondary context only.",
        "",
        "| Device | Battery delta | Rate | Start skew |",
        "| --- | ---: | ---: | ---: |",
    ]
    skew = summary["start_skew_ms"].get("value", "unavailable")
    for alias, device in summary["devices"].items():
        delta = device["whole_device"]["battery_delta_percentage_points"]
        rate = device["whole_device"]["battery_percentage_points_per_hour"]
        lines.append(f"| {alias} | {delta.get('value', delta['state'])} pp | {rate.get('value', rate['state'])} pp/h | {skew} ms |")
    lines.extend(["", "## Validity", "", f"- State: `{summary['validity']['state']}`", "- Raw artifacts: private and gitignored; no raw path or device identifier is published.", "- No causal or release recommendation may be made from smoke or fixture output."])
    return "\n".join(lines) + "\n"


def write_sanitized_summary(output_dir: Path, summary: dict[str, Any]) -> tuple[Path, Path]:
    assert_commit_safe(summary)
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / "run-summary.json"
    markdown_path = output_dir / "run-summary.md"
    json_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    markdown_path.write_text(render_markdown(summary))
    return json_path, markdown_path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("smoke", "baseline-disabled", "enabled"))
    parser.add_argument("--s21", default=os.environ.get("JANDAL_S21_ADB"), help="private S21 ADB identifier; never emitted")
    parser.add_argument("--s23u", default=os.environ.get("JANDAL_S23U_ADB"), help="private S23U ADB identifier; never emitted")
    parser.add_argument("--duration", default="4h", type=parse_duration)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--private-root", type=Path, default=DEFAULT_PRIVATE_ROOT)
    parser.add_argument("--fixture", type=Path, help="synthetic non-interactive fixture; no ADB commands run")
    parser.add_argument("--interactive", action="store_true", help="allow physical run after explicit operator confirmations")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.fixture:
            fixture = json.loads(args.fixture.read_text())
            summary = fixture_summary(fixture, args.mode, args.duration, f"fixture-{args.mode}")
            output = args.private_root / "fixture-sanitized"
            json_path, markdown_path = write_sanitized_summary(output, summary)
            print(f"NON_EVIDENTIARY_FIXTURE_DRY_RUN summary written: {json_path.name}, {markdown_path.name}")
            return 0
        if not args.s21 or not args.s23u:
            raise HarnessError("--s21 and --s23u (or private environment variables) are required for physical execution")
        harness = PairedHarness(args.mode, args.package, args.duration, args.private_root, {"s21": AdbClient(args.s21), "s23u": AdbClient(args.s23u)})
        summary = harness.run_physical(args.interactive)
        json_path, markdown_path = write_sanitized_summary(harness.run_dir / "sanitized", summary)
        print(f"{summary['classification']} summary written: {json_path.name}, {markdown_path.name}")
        return 0
    except (HarnessError, json.JSONDecodeError, OSError) as error:
        print(f"ABORTED: {sanitise_text(str(error), (args.s21 or '', args.s23u or ''))}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
