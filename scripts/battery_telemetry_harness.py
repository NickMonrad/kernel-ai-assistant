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


def uid_to_android_uid(decimal_uid: int) -> str:
    """Convert a decimal Android UID to the u0aNNN textual form.

    Android encodes <user_id> * 100000 + <app_id> into the decimal UID.
    The textual form is u<user_id>a<app_id>.
    Secondary users (work profile etc.) produce u10aNNN, u11aNNN, etc.
    """
    user_id = decimal_uid // 100000
    app_id = decimal_uid % 100000
    return f"u{user_id}a{app_id}"


def parse_android_uid(text_uid: str) -> tuple[int, int]:
    """Parse a u<user_id>a<app_id> string into (decimal_uid, user_id).

    Returns (decimal_uid, user_id) or raises ValueError on malformed input.
    """
    match = re.fullmatch(r"u(\d+)a(\d+)", text_uid.strip())
    if not match:
        raise ValueError(f"malformed Android UID: {text_uid!r}")
    user_id = int(match.group(1))
    app_id = int(match.group(2))
    return user_id * 100000 + app_id, user_id


def extract_uid_block(text: str, decimal_uid: int) -> str:
    """Extract the Batterystats UID block for the given decimal UID.

    Human-readable format uses indented ``Uid u0aNNN:`` blocks. Returns the
    block content (without the ``Uid u0aNNN:`` header) or empty string if not
    found.
    """
    android_uid = uid_to_android_uid(decimal_uid)
    lines = text.splitlines()
    in_block = False
    block: list[str] = []
    for line in lines:
        stripped = line.strip()
        uid_header = re.match(r"^Uid\s+" + re.escape(android_uid) + r":\s*$", stripped)
        if uid_header:
            in_block = True
            continue
        if in_block:
            if stripped.startswith("Uid ") or stripped.startswith("Device idle"):
                break
            if stripped:
                block.append(line)
    return "\n".join(block)


def extract_all_uids(text: str) -> list[tuple[int, str, str]]:
    """Extract all UIDs from Batterystats human-readable output.

    Returns list of (decimal_uid, android_uid_string, block_text) for all
    Uids found. Used for top-consumer reporting.
    """
    uids: list[tuple[int, str, str]] = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        stripped = lines[i].strip()
        match = re.match(r"Uid\s+(u\d+a\d+):\s*$", stripped)
        if match:
            android_uid = match.group(1)
            decimal_uid, _ = parse_android_uid(android_uid)
            block_lines: list[str] = []
            i += 1
            while i < len(lines):
                s = lines[i].strip()
                if s.startswith("Uid ") or s.startswith("Device idle") or s.startswith("Device type"):
                    i -= 1
                    break
                if s:
                    block_lines.append(lines[i])
                i += 1
            uids.append((decimal_uid, android_uid, "\n".join(block_lines)))
        i += 1
    return uids


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


def _parse_duration_ms(duration_str: str) -> int:
    """Convert a Batterystats duration string like +4s200ms to milliseconds."""
    total = 0
    for match in re.finditer(r"(?:(\d+)h)?\s*(?:(\d+)m)?\s*(?:(\d+)s)?\s*(?:(\d+)ms)?", duration_str.replace("+", "").replace(" ", "")):
        h, m, s, ms = (int(g) if g else 0 for g in match.groups())
        total += h * 3600000 + m * 60000 + s * 1000 + ms
    return total


def parse_batterystats(text: str, uid: int | None) -> dict[str, Metric]:
    """Parse hierarchical Batterystats human-readable UID blocks.

    Android 15 ``dumpsys batterystats --charged`` uses ``Uid u0aNNN:``
    headers with nested child lines.  The block for the resolved UID is
    extracted first, then individual fields are parsed within that block.

    Supports CPU (user/system), partial wakelocks (named + duration),
    foreground-service duration, audio duration, service uptime, and
    estimated power mAh.
    """
    if uid is None:
        return {key: unsupported("package UID unavailable") for key in (
            "cpu_user_ms", "cpu_kernel_ms", "foreground_service_ms",
            "estimated_power_mah", "partial_wakelocks", "audio_duration_ms",
            "service_uptime_ms")}
    block = extract_uid_block(text, uid)
    if not block:
        return {key: not_reported(f"UID {uid} absent from Batterystats") for key in (
            "cpu_user_ms", "cpu_kernel_ms", "foreground_service_ms",
            "estimated_power_mah", "partial_wakelocks", "audio_duration_ms",
            "service_uptime_ms")}

    result: dict[str, Metric] = {}

    # CPU user / system (inside cpu: block or proc: block)
    cpu_user = re.search(r"cpu:\s*\n(?:.*\n)*?\s*user:\s*(\d+)ms", block, re.MULTILINE)
    cpu_sys = re.search(r"cpu:\s*\n(?:.*\n)*?\s*(?:system|kernel):\s*(\d+)ms", block, re.MULTILINE)
    result["cpu_user_ms"] = available(int(cpu_user.group(1))) if cpu_user else not_reported("CPU user time not reported")
    result["cpu_kernel_ms"] = available(int(cpu_sys.group(1))) if cpu_sys else not_reported("CPU kernel time not reported")

    # Partial wakelocks: "Wake lock: <name> +<duration> (partial) count <N>"
    wakelock_matches = re.findall(r"Wake lock:\s*([^+]+)\s*\+(\S+)", block, re.MULTILINE)
    wakelocks = []
    for name_raw, dur_raw in wakelock_matches:
        name = name_raw.strip()
        dur_ms = _parse_duration_ms(dur_raw)
        wakelocks.append({"name": name, "duration_ms": dur_ms})
    result["partial_wakelocks"] = available(wakelocks) if wakelocks else not_reported("no partial wakelocks reported for this UID")

    # Foreground service / foreground activity duration
    fg = re.search(r"foreground duration:\s*(\S+(?:\s+\S+)?)", block)
    if fg:
        result["foreground_service_ms"] = available(_parse_duration_ms(fg.group(1)))
    else:
        result["foreground_service_ms"] = not_reported("foreground duration not reported")

    # Service uptime
    svc = re.search(r"Started service:\s*(\S+(?:\s+\S+)?)", block)
    if svc:
        result["service_uptime_ms"] = available(_parse_duration_ms(svc.group(1)))
    else:
        result["service_uptime_ms"] = not_reported("service uptime not reported")

    # Audio duration
    audio = re.search(r"Audio:\s*\n(?:.*\n)*?\s*duration:\s*(\S+(?:\s+\S+)?)", block, re.MULTILINE)
    if audio:
        result["audio_duration_ms"] = available(_parse_duration_ms(audio.group(1)))
    else:
        result["audio_duration_ms"] = not_reported("audio duration not reported")

    # Estimated power
    power = re.search(r"power:\s*(\d+(?:\.\d+)?)\s*mAh", block)
    result["estimated_power_mah"] = available(float(power.group(1))) if power else not_reported("estimated power not reported")

    return result


def parse_checkin(text: str, uid: int | None) -> dict[str, Metric]:
    """Parse real Android Batterystats check-in records.

    Check-in format uses comma-separated records with tag-based positioning.
    Relevant record types:

    - uid,<decimal_uid>,cpu,<user_ms>,<system_ms>
    - wl,<decimal_uid>,<name>,<duration_ms>,<count>
    - sf,<decimal_uid>,<duration_ms>,<count>
    - pr,<decimal_uid>,<proc>,<user_ms>,<system_ms>,<iowait>,<fault>,<fault>,<count>
    - au,<decimal_uid>,<duration_ms>,<count>
    - apk,<decimal_uid>,<package>,<version>,<version_code>
    """
    if uid is None:
        return {key: unsupported("package UID unavailable") for key in (
            "cpu_user_ms", "cpu_kernel_ms", "checkin_wakelocks",
            "checkin_foreground_service_ms", "checkin_audio_ms",
            "checkin_proc_cpu_user_ms", "checkin_proc_cpu_kernel_ms")}

    result: dict[str, Metric] = {
        "cpu_user_ms": not_reported("UID not found in check-in"),
        "cpu_kernel_ms": not_reported("UID not found in check-in"),
        "checkin_wakelocks": not_reported("no check-in wakelock records for this UID"),
        "checkin_foreground_service_ms": not_reported("no check-in foreground service record"),
        "checkin_audio_ms": not_reported("no check-in audio record"),
        "checkin_proc_cpu_user_ms": not_reported("no check-in proc record"),
        "checkin_proc_cpu_kernel_ms": not_reported("no check-in proc record"),
    }
    uid_str = str(uid)

    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split(",")
        if len(parts) < 3:
            continue
        # Skip header/metadata rows: i (version), l (battery), f (feature), etc.
        if parts[2] == "uid" and len(parts) >= 7 and parts[3] == uid_str and parts[4] == "cpu":
            try:
                result["cpu_user_ms"] = available(int(parts[5]))
                result["cpu_kernel_ms"] = available(int(parts[6]))
            except ValueError:
                result["cpu_user_ms"] = parse_failed("check-in uid CPU user value malformed")
                result["cpu_kernel_ms"] = parse_failed("check-in uid CPU kernel value malformed")
        elif parts[2] == "wl" and parts[3] == uid_str and len(parts) >= 5:
            try:
                wakelocks = [{"name": parts[4], "duration_ms": int(parts[5])}]
                # Optional count at parts[6]
                result["checkin_wakelocks"] = available(wakelocks)
            except ValueError:
                result["checkin_wakelocks"] = parse_failed("check-in wakelock duration malformed")
        elif parts[2] == "sf" and parts[3] == uid_str and len(parts) >= 5:
            try:
                result["checkin_foreground_service_ms"] = available(int(parts[4]))
            except ValueError:
                result["checkin_foreground_service_ms"] = parse_failed("check-in foreground service duration malformed")
        elif parts[2] == "au" and parts[3] == uid_str and len(parts) >= 5:
            try:
                result["checkin_audio_ms"] = available(int(parts[4]))
            except ValueError:
                result["checkin_audio_ms"] = parse_failed("check-in audio duration malformed")
        elif parts[2] == "pr" and parts[3] == uid_str and len(parts) >= 7:
            try:
                result["checkin_proc_cpu_user_ms"] = available(int(parts[5]))
                result["checkin_proc_cpu_kernel_ms"] = available(int(parts[6]))
            except ValueError:
                result["checkin_proc_cpu_user_ms"] = parse_failed("check-in proc CPU user value malformed")
                result["checkin_proc_cpu_kernel_ms"] = parse_failed("check-in proc CPU kernel value malformed")

    return result
def parse_wake_word_diagnostics(text: str, enabled: bool) -> dict[str, Metric]:
    """Parse aggregate WakeWordDetector diagnostic summaries from logcat output."""
    keys = ("audioFrames", "stage1", "stage2", "stage3", "stage2PerHour", "stage3PerHour",
            "silenceSkips", "silenceSkipRatio", "verifier", "verifierPasses", "verifierRejects",
            "highActivations", "verifiedActivations", "embeddingProvider")
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


def parse_batteryproperties(text: str) -> dict[str, Metric]:
    """Parse dumpsys batteryproperties output for capacity/health evidence."""
    result: dict[str, Metric] = {}
    extracts = {
        "capacity_uah": (r"capacity:\s*(\d+)", "batteryproperties capacity"),
        "charge_counter_uah": (r"charge_counter:\s*(\d+)", "batteryproperties charge counter"),
        "remaining_capacity_uah": (r"remaining_capacity:\s*(\d+)", "remaining capacity"),
        "current_now_ua": (r"current_now:\s*(-?\d+)", "current now"),
        "design_capacity_uah": (r"(?i)design_capacity:\s*(\d+)", "design capacity"),
        "health": (r"health:\s*(\d+)", "batteryproperties health"),
        "temperature_tenths_c": (r"temperature:\s*(\d+)", "batteryproperties temperature"),
        "voltage_uv": (r"voltage:\s*(\d+)", "batteryproperties voltage"),
    }
    for key, (pattern, label) in extracts.items():
        result[key] = metric_from_match(text, pattern, label)
    return result


def parse_deviceidle_state(text: str) -> dict[str, Metric]:
    """Extract device-idle/Doze state from dumpsys deviceidle output."""
    result: dict[str, Metric] = {}
    for key, pattern, is_bool in [
        ("state", r"mState=(.+)", False),
        ("light_state", r"mLightState=(.+)", False),
        ("screen_on", r"mScreenOn=(true|false)", True),
        ("idle", r"mIdle=(true|false)", True),
        ("charging", r"mCharging=(true|false)", True),
    ]:
        match = re.search(pattern, text)
        if match:
            raw = match.group(1).lower()
            result[key] = available(raw == "true") if is_bool else available(raw)
        else:
            result[key] = not_reported(f"deviceidle {key} not reported")
    return result


def parse_power_state(text: str) -> dict[str, Metric]:
    """Extract power/wakefulness/screen-off state from dumpsys power output.

    Android 15 uses ``Wakefulness=Asleep`` and ``mScreenOn=false``.
    Some builds add ``Display Power: state=OFF``.
    """
    result: dict[str, Metric] = {}
    wakefulness = re.search(r"Wakefulness=(\S+)", text)
    result["wakefulness"] = available(wakefulness.group(1)) if wakefulness else not_reported("wakefulness not reported")
    screen_on = re.search(r"mScreenOn=(true|false)", text)
    result["screen_on"] = available(screen_on.group(1).lower() == "true") if screen_on else not_reported("screen on state not reported")
    display_power = re.search(r"Display Power:\s*state=(\S+)", text)
    result["display_power"] = available(display_power.group(1)) if display_power else unsupported("display power field not exposed on this build")
    interactive = re.search(r"mInteractive=(true|false)", text)
    result["interactive"] = available(interactive.group(1).lower() == "true") if interactive else not_reported("interactive state not reported")
    return result


def parse_procstats_attribution(text: str, package: str) -> dict[str, Metric]:
    """Parse procstats output for service evidence and CPU attribution.

    Procstats provides service/process uptime and memory evidence that can
    corroborate Batterystats attribution.
    """
    result: dict[str, Metric] = {}
    service_lines = re.findall(rf"Service\s+{re.escape(package)}", text)
    result["service_active"] = available(len(service_lines) > 0) if service_lines else not_reported("service evidence not found in procstats")
    # Total CPU time in procstats (ms)
    cpu_match = re.search(rf"Total CPU:\s*(?:[^:]*:\s*)?(\S+)", text)
    if cpu_match:
        raw = cpu_match.group(1)
        try:
            result["procstats_cpu_ms"] = available(int(raw))
        except ValueError:
            result["procstats_cpu_ms"] = parse_failed("procstats CPU value malformed")
    else:
        result["procstats_cpu_ms"] = not_reported("procstats CPU time not reported")
    return result


def compute_charge_delta_uah(start: dict[str, Metric], end: dict[str, Metric]) -> Metric:
    """Compute consumed charge from start/end charge counters.

    Charge counter decreases as battery discharges.  Convention:
    delta = start_counter - end_counter (positive = consumption).
    Returns negative values if charging occurred (reverse direction).
    """
    s = start.get("charge_counter_uah")
    e = end.get("charge_counter_uah")
    if s is None or e is None:
        return not_reported("charge counter not reported")
    if s.state is not Availability.AVAILABLE or e.state is not Availability.AVAILABLE:
        return not_reported("charge counter unavailable at boundary")
    delta = s.value - e.value
    return available(delta)


def compute_mah_from_uah(delta_uah: Metric, duration_hours: float) -> tuple[Metric, Metric]:
    """Derive mAh consumed and mAh/hour from a charge-counter delta in µAh."""
    if delta_uah.state is not Availability.AVAILABLE:
        return (delta_uah, not_reported("mAh/hour unavailable; no charge counter delta"))
    mAh = delta_uah.value / 1000.0
    rate = mAh / duration_hours if duration_hours > 0 else not_reported("zero duration")
    return (available(round(mAh, 3)), available(round(rate, 3)))


def sanitise_uid_label(uid: int, known_app_uid: int | None) -> str:
    """Map a decimal UID to a sanitised label for top-consumer reporting.

    Known app UID gets labelled 'target_app'; other UIDs get generic labels.
    System UIDs (< 10000) are grouped as 'system'; others as 'other_uid_N'.
    This avoids publishing the user's private installed-app inventory.
    """
    if known_app_uid is not None and uid == known_app_uid:
        return "target_app"
    if uid < 10000:
        return "system"
    return f"other_uid_{uid}"


def screen_off(power_text: str, device_idle_text: str) -> bool:
    """Check screen-off status from dumpsys power and deviceidle output.

    Returns True if any supported indicator confirms the screen is off.
    Raises HarnessError when no known screen field is present (cannot assume).
    """
    # Check deviceidle first
    idle_screen = re.search(r"mScreenOn=(true|false)", device_idle_text)
    if idle_screen:
        return idle_screen.group(1).lower() == "false"
    # Check power state
    power_screen = re.search(r"mScreenOn=(true|false)", power_text)
    if power_screen:
        return power_screen.group(1).lower() == "false"
    # Check Wakefulness
    wakefulness = re.search(r"Wakefulness=(\S+)", power_text)
    if wakefulness:
        return wakefulness.group(1).lower() in ("asleep", "dose")
    # Check Display Power
    display = re.search(r"Display Power:\s*state=(\S+)", power_text)
    if display:
        return display.group(1).upper() == "OFF"
    raise HarnessError("no known screen-off indicator found; precondition cannot be verified")


def external_power_ok(battery_text: str) -> bool:
    """Verify device is disconnected from all charging sources.

    Samsung Android 15 ``dumpsys battery`` fields:
      AC powered: false, USB powered: false, Wireless powered: false
      status: 3 = DISCHARGING  (Samsung also uses 3 for not charging when unplugged)
      status: 4 = NOT_CHARGING (some builds)

    Both status values (3 and 4) are accepted as not externally powered.
    Raises HarnessError if required power fields are absent.
    """
    powered_keys = [("AC powered", "ac_powered"), ("USB powered", "usb_powered"), ("Wireless powered", "wireless_powered")]
    for label, key in powered_keys:
        match = re.search(rf"^\s*{re.escape(label)}:\s*(true|false)", battery_text, re.MULTILINE | re.IGNORECASE)
        if not match:
            raise HarnessError(f"required battery field '{label}' not found")
        if match.group(1).lower() == "true":
            return False
    status = re.search(r"^\s*status:\s*(\d+)", battery_text, re.MULTILINE)
    if not status:
        raise HarnessError("required battery field 'status' not found")
    status_val = int(status.group(1))
    # Samsung: 3 = DISCHARGING, 4 = NOT_CHARGING; both acceptable
    if status_val not in (3, 4):
        return False
    return True


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
        self.run_id = f"{mode}-{datetime.now(timezone.utc):%Y-%m-%dT%H-%M-%SZ}-{uuid.uuid4().hex[:8]}"
        self.package = package
        self.duration_seconds = duration_seconds
        self.private_root = private_root
        self.clients = clients or {}
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
        """Capture a boundary snapshot including parsed metrics and raw texts."""
        client = self.clients[alias]
        battery_text = client.shell("dumpsys", "battery")
        power_text = client.shell("dumpsys", "power")
        idle_text = client.shell("dumpsys", "deviceidle")
        services_text = client.shell("dumpsys", "activity", "services", self.package)
        batteryproperties_text = client.shell("dumpsys", "batteryproperties")
        return {
            "phase": phase,
            "wall_clock_utc": utc_now(),
            "monotonic_ms": time.monotonic_ns() // 1_000_000,
            "raw_battery": battery_text,
            "raw_power": power_text,
            "raw_deviceidle": idle_text,
            "raw_batteryproperties": batteryproperties_text,
            "battery": parse_battery_dump(battery_text),
            "batteryproperties": parse_batteryproperties(batteryproperties_text),
            "power": parse_power_state(power_text),
            "deviceidle": parse_deviceidle_state(idle_text),
            "screen_off": screen_off(power_text, idle_text),
            "service_active": "WakeWordService" in services_text,
            "services_text": services_text,
        }

    def verify_unplugged_pair(self, boundaries: dict[str, dict[str, Any]]) -> None:
        failures = []
        for alias, snapshot in boundaries.items():
            if not external_power_ok(snapshot["raw_battery"]):
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

    def capture_start_raw(self, alias: str, starts: dict[str, dict[str, Any]]) -> None:
        """Persist private start-boundary raw evidence for auditability."""
        snapshot = starts[alias]
        phase_dir = self.run_dir / alias / "start"
        phase_dir.mkdir(parents=True, exist_ok=True)
        (phase_dir / "battery.txt").write_text(snapshot["raw_battery"])
        (phase_dir / "batteryproperties.txt").write_text(snapshot["raw_batteryproperties"])
        (phase_dir / "power.txt").write_text(snapshot["raw_power"])
        (phase_dir / "deviceidle.txt").write_text(snapshot["raw_deviceidle"])
        (phase_dir / "services.txt").write_text(snapshot["services_text"])
        uid_text = self.clients[alias].shell("dumpsys", "package", self.package)
        (phase_dir / "package.txt").write_text(uid_text)

    def top_consumers(self, raw_batterystats: str, known_uid: int | None) -> dict[str, Metric]:
        """Extract top other power-consuming UIDs for device-idle context."""
        if not raw_batterystats:
            return {"top_consumers": not_reported("Batterystats not available")}
        all_uids = extract_all_uids(raw_batterystats)
        if not all_uids:
            return {"top_consumers": not_reported("no UIDs found in Batterystats")}
        consumers: list[dict[str, Any]] = []
        for decimal_uid, android_uid, block in all_uids:
            power_match = re.search(r"power:\s*(\d+(?:\.\d+)?)\s*mAh", block)
            power = float(power_match.group(1)) if power_match else 0.0
            label = sanitise_uid_label(decimal_uid, known_uid)
            if label != "target_app" and power > 0:
                consumers.append({"label": label, "estimated_power_mah": power, "android_uid": android_uid})
        consumers.sort(key=lambda c: c["estimated_power_mah"], reverse=True)
        return {"top_consumers": available(consumers[:5])} if consumers else {"top_consumers": not_reported("no non-target consumers above zero power")}

    def try_cleanup_diagnostics(self, alias: str) -> str | None:
        """Attempt to restore WakeWordDiag log level. Returns error message or None."""
        client = self.clients.get(alias)
        if not client:
            return f"{alias}: no ADB client"
        try:
            client.shell("setprop", f"log.tag.{DIAGNOSTIC_TAG}", "INFO")
            level = client.shell("getprop", f"log.tag.{DIAGNOSTIC_TAG}").strip().upper()
            if level == "DEBUG":
                return f"{alias}: property still DEBUG after restore attempt"
            return None
        except (HarnessError, OSError, subprocess.TimeoutExpired) as exc:
            return f"{alias}: cleanup failed — {exc}"

    def public_summary(self, starts: dict[str, dict[str, Any]], ends: dict[str, dict[str, Any]], raw_end: dict[str, dict[str, str]], classification: str) -> dict[str, Any]:
        """Build the sanitised public summary with all derived metrics."""
        devices: dict[str, Any] = {}
        for alias in ("s21", "s23u"):
            identity = self.identities[alias]
            battery_start = starts[alias]["battery"]
            battery_end = ends[alias]["battery"]
            props_start = starts[alias]["batteryproperties"]
            props_end = ends[alias]["batteryproperties"]
            uid_metric = identity.package["uid"]
            uid = uid_metric.value if uid_metric.state is Availability.AVAILABLE else None

            # App attribution from both Batterystats sources
            stats = parse_batterystats(raw_end[alias].get("batterystats-charged.txt", ""), uid)
            checkin = parse_checkin(raw_end[alias].get("batterystats-checkin.csv", ""), uid)
            wake = parse_wake_word_diagnostics(raw_end[alias].get("logcat.txt", ""), self.enabled)

            # Procstats evidence
            procstats_text = raw_end[alias].get("procstats.txt", "")
            procstats = parse_procstats_attribution(procstats_text, self.package)

            # Whole-device percentage delta
            delta = not_reported("boundary battery level unavailable")
            rate = not_reported("duration or battery level unavailable")
            if battery_start["level_percent"].state is Availability.AVAILABLE and battery_end["level_percent"].state is Availability.AVAILABLE:
                loss = battery_start["level_percent"].value - battery_end["level_percent"].value
                delta = available(loss)
                rate = available(round(loss * 3600 / self.duration_seconds, 3))

            # Charge-counter delta
            charge_delta = compute_charge_delta_uah(battery_start, battery_end)
            duration_hours = self.duration_seconds / 3600.0
            mah_consumed, mah_per_hour = compute_mah_from_uah(charge_delta, duration_hours)

            # Doze / device-idle continuity
            idle_start = starts[alias].get("deviceidle", {})
            idle_end = ends[alias].get("deviceidle", {})
            doze_info = {
                "start_state": idle_start.get("state", not_reported("start doze state unavailable")).public(),
                "end_state": idle_end.get("state", not_reported("end doze state unavailable")).public(),
                "start_idle": idle_start.get("idle", not_reported("start idle flag unavailable")).public(),
                "end_idle": idle_end.get("idle", not_reported("end idle flag unavailable")).public(),
                "start_charging": idle_start.get("charging", not_reported("start charging flag unavailable")).public(),
                "end_charging": idle_end.get("charging", not_reported("end charging flag unavailable")).public(),
            }

            # Top consumers (sanitised)
            top = self.top_consumers(raw_end[alias].get("batterystats-charged.txt", ""), uid)
            top_consumers_public = {key: value.public() for key, value in top.items()}

            # Battery health from batteryproperties (start)
            health_props = {
                key: props_start[key].public() if key in props_start else not_reported(f"{key} not in batteryproperties").public()
                for key in ("capacity_uah", "charge_counter_uah", "remaining_capacity_uah",
                            "current_now_ua", "health", "temperature_tenths_c", "voltage_uv")
            }
            # design capacity if exposed
            if "design_capacity_uah" in props_start:
                health_props["design_capacity_uah"] = props_start["design_capacity_uah"].public()

            # Screen-on / wakefulness continuity
            screen_info = {
                "start_wakefulness": starts[alias]["power"].get("wakefulness", not_reported("not reported")).public(),
                "end_wakefulness": ends[alias]["power"].get("wakefulness", not_reported("not reported")).public(),
                "start_screen_on": starts[alias]["power"].get("screen_on", not_reported("not reported")).public(),
                "end_screen_on": ends[alias]["power"].get("screen_on", not_reported("not reported")).public(),
                "reboot_detected": available(starts[alias]["monotonic_ms"] > ends[alias]["monotonic_ms"])
                    if (starts[alias]["monotonic_ms"] is not None and ends[alias]["monotonic_ms"] is not None)
                    else not_reported("monotonic clock unavailable"),
            }

            # Build device section
            devices[alias] = {
                "identity": identity.public(),
                "start": self._public_snapshot(starts[alias], "start"),
                "end": self._public_snapshot(ends[alias], "end"),
                "whole_device": {
                    "battery_delta_percentage_points": delta.public(),
                    "battery_percentage_points_per_hour": rate.public(),
                    "charge_delta_uah": charge_delta.public(),
                    "mah_consumed": mah_consumed.public(),
                    "mah_per_hour": mah_per_hour.public(),
                },
                "battery_health": health_props,
                "battery_end": {key: battery_end[key].public() for key in ("charge_counter_uah", "voltage_mv", "temperature_tenths_c", "health", "cycle_count", "full_charge_uah")},
                "app_attribution": {key: value.public() for key, value in {**stats, **{f"checkin_{key}": value for key, value in checkin.items()}, **{f"procstats_{key}": value for key, value in procstats.items()}}.items()},
                "wake_word": {key: value.public() for key, value in wake.items()},
                "doze": doze_info,
                "screen": screen_info,
                "top_consumers": top_consumers_public,
            }

        summary = {
            "schema_version": "2.0",
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
                "mAh consumption derived from charge-counter delta; sign convention: positive = consumption.",
            ],
        }
        assert_commit_safe(summary)
        return summary
    @staticmethod
    def _public_snapshot(snapshot: dict[str, Any], phase: str) -> dict[str, Any]:
        return {
            "wall_clock_utc": snapshot["wall_clock_utc"],
            "battery": {key: metric.public() for key, metric in snapshot["battery"].items()},
            "batteryproperties": {key: metric.public() for key, metric in snapshot["batteryproperties"].items()},
            "screen_off": snapshot["screen_off"],
            "service_active": snapshot["service_active"],
            "deviceidle": {key: metric.public() for key, metric in snapshot["deviceidle"].items()},
            "power": {key: metric.public() for key, metric in snapshot["power"].items()},
        }

    def run_physical(self, interactive: bool) -> dict[str, Any]:
        """Execute a paired physical run with abort-safe cleanup.

        The enabled lifecycle (WakeWordDiag DEBUG) is wrapped in try/finally
        so diagnostics are restored even after precondition failure, ADB loss,
        KeyboardInterrupt, or parser error.
        """
        self.write_private_manifest()
        self.validate_devices()
        cleanup_errors: list[str] = []
        diagnostics_was_enabled = False
        starts: dict[str, dict[str, Any]] = {}
        ends: dict[str, dict[str, Any]] = {}
        raw: dict[str, dict[str, str]] = {}
        classification = "ABORTED_NON_EVIDENTIARY"

        try:
            # --- Operator gates ---
            if self.mode == "baseline-disabled":
                prompt_for_confirmation("Manually disable Listen for Hey Jandal on both S21 and S23 Ultra. Return only after both toggles are off.", interactive)
            elif self.mode == "enabled":
                prompt_for_confirmation("Manually enable Listen for Hey Jandal on both devices, verify the intended build/assets, and complete one pre-test wake smoke on each device.", interactive)
            else:
                prompt_for_confirmation("This is a NON_EVIDENTIARY_SMOKE. Prepare the requested mode manually and confirm both device states.", interactive)

            self.verify_mode_service_state()
            self.set_enabled_diagnostics()
            diagnostics_was_enabled = self.enabled

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
            # Persist start raw evidence
            for alias in self.clients:
                self.capture_start_raw(alias, starts)

            # --- Wait / idle window ---
            if self.mode == "smoke":
                wait_seconds = min(self.duration_seconds, 120)
                classification = "NON_EVIDENTIARY_SMOKE"
            else:
                wait_seconds = self.duration_seconds
                classification = "EVIDENTIARY"
            time.sleep(wait_seconds)

            # --- End boundary (before bugreport) ---
            ends = {alias: self.boundary_snapshot(alias, "end") for alias in self.clients}
            self.verify_unplugged_pair(ends)

            # --- End raw capture ---
            raw = {}
            for alias in self.clients:
                raw[alias] = self.capture_end_raw(alias)

            # --- Bugreport (separate, after official end) ---
            for alias in self.clients:
                self.collect_bugreport(alias)

        except (HarnessError, OSError, subprocess.TimeoutExpired, KeyboardInterrupt) as exc:
            if isinstance(exc, KeyboardInterrupt):
                self.abort_reason = "operator keyboard interrupt"
            else:
                self.abort_reason = str(exc)
            classification = "ABORTED_NON_EVIDENTIARY"

        finally:
            # --- Cleanup: restore diagnostics on both devices independently ---
            if diagnostics_was_enabled:
                for alias in self.clients:
                    err = self.try_cleanup_diagnostics(alias)
                    if err:
                        cleanup_errors.append(err)
                        print(f"WARNING: {err}", file=sys.stderr)

        # --- Outcome ---
        if self.abort_reason:
            # Preserve partial evidence
            for alias in self.clients:
                if starts.get(alias):
                    self.capture_start_raw(alias, starts)
                end_data = ends.get(alias)
                raw_data = raw.get(alias)
                if raw_data:
                    for filename, content in raw_data.items():
                        if content:
                            self.private_write(alias, "partial", filename, content)
            return self.abort_summary(starts, ends, raw)

        # Successful completion
        if cleanup_errors:
            # Diagnostics was restored with warnings but run completed
            print(f"WARNING: cleanup issues on: {'; '.join(cleanup_errors)}", file=sys.stderr)
        return self.public_summary(starts, ends, raw, classification)

    def abort_summary(self, starts: dict[str, dict[str, Any]], ends: dict[str, dict[str, Any]], raw: dict[str, dict[str, str]]) -> dict[str, Any]:
        """Generate a non-evidentiary abort summary preserving partial evidence."""
        devices: dict[str, Any] = {}
        for alias in ("s21", "s23u"):
            device_info: dict[str, Any] = {"alias": alias}
            if alias in self.identities:
                device_info["identity"] = self.identities[alias].public()
            if starts.get(alias):
                device_info["start"] = {"wall_clock_utc": starts[alias]["wall_clock_utc"], "battery": {key: metric.public() for key, metric in starts[alias]["battery"].items()}}
            if ends.get(alias):
                device_info["end"] = {"wall_clock_utc": ends[alias]["wall_clock_utc"], "battery": {key: metric.public() for key, metric in ends[alias]["battery"].items()}}
            devices[alias] = device_info
        summary = {
            "schema_version": "2.0",
            "classification": "ABORTED_NON_EVIDENTIARY",
            "run_id": self.run_id,
            "mode": self.mode,
            "duration_seconds": self.duration_seconds,
            "primary_comparison": "S21 enabled − S21 disabled; S23U enabled − S23U disabled",
            "cross_device_warning": "Run was aborted; no comparison possible.",
            "validity": {"state": "aborted", "abort_reason": self.abort_reason},
            "raw_artifacts": "private, gitignored run directory; partial evidence preserved",
            "devices": devices,
            "limitations": ["Run did not complete; abort summary only. Not evidentiary."],
        }
        assert_commit_safe(summary)
        return summary


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
        "schema_version": "2.0",
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
