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
import csv
import dataclasses
import io
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

PER_USER_RANGE = 100000
FIRST_APPLICATION_UID = 10000
DEFAULT_PRIVATE_ROOT = REPO_ROOT / "scripts" / "private-battery-runs"
DIAGNOSTIC_TAG = "WakeWordDiag"
EXPECTED_DEVICES = {
    "s21": {"manufacturer": "samsung", "model": "SM-G991B"},
    "s23u": {"manufacturer": "samsung", "model": "SM-S918B"},
}
PRIVATE_PATTERNS = (
    # IP addresses and ADB endpoints
    re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b"),
    # IPv6 addresses
    re.compile(r"\b(?:[0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F:]*\b"),
    # Email addresses
    re.compile(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b"),
    # Home directory paths
    re.compile(r"(?:/home/[^\s]+|~/(?:[^\s]+)?)"),
    # Pairing codes
    re.compile(r"(?i)(?:pair(?:ing)?\s*(?:code|pin)\s*[:=]\s*)\d+"),
    # Fields named "serial", "device_id", "adb_selector", "endpoint", "imei", "device_id" etc.
    re.compile(r"""(?i)(?:"(?:serial|device_id|adb_selector|endpoint|imei|mac_address|pairing_code)"\s*:\s*")([^"]+)"""),
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
    """Convert a decimal Android UID to the Android textual form.

    Android UID formatting:
      - System UIDs (UID < FIRST_APPLICATION_UID): formatted as decimal, e.g. ``1000``
      - Application UIDs: ``u<user_id>a<app_id>`` where
        ``user_id = UID // PER_USER_RANGE`` and
        ``app_id = (UID %% PER_USER_RANGE) - FIRST_APPLICATION_UID``

    Examples:
        ``1000`` (system) → ``"1000"``
        ``10123`` → ``"u0a123"``
        ``1010123`` → ``"u10a123"``

    Supports isolated (``u<user>i<app>``) and shared (``u<user>s<app>``) forms
    when explicitly requested via ``form`` parameter.
    """
    if decimal_uid < FIRST_APPLICATION_UID:
        return str(decimal_uid)
    user_id = decimal_uid // PER_USER_RANGE
    app_part = decimal_uid % PER_USER_RANGE
    app_id = app_part - FIRST_APPLICATION_UID
    if app_id < 0:
        # Should not happen for valid UIDs, but be safe
        return str(decimal_uid)
    return f"u{user_id}a{app_id}"



def parse_android_uid(text_uid: str) -> tuple[int, int]:
    """Parse a u<user_id>a<app_id> string into (decimal_uid, user_id).

    Supports:
      - Numeric system UIDs: ``"1000"`` → ``(1000, 0)``
      - Application UIDs: ``"u0a123"`` → ``(10123, 0)``
      - Secondary-user UIDs: ``"u10a123"`` → ``(1010123, 10)``

    Raises ``ValueError`` on malformed or unsupported forms.
    """
    text = text_uid.strip()
    # Numeric system UID
    if text.isdigit():
        uid = int(text)
        if uid < FIRST_APPLICATION_UID:
            return uid, 0
        # Numeric UID in the app range should use u<user>a<app> form
        raise ValueError(f"numeric UID {uid} is in application range; expected u<user>a<app> form")
    # Application / isolated / shared UID
    match = re.fullmatch(r"u(\d+)([ais])(\d+)", text)
    if match:
        user_id = int(match.group(1))
        form = match.group(2)  # 'a' = app, 'i' = isolated, 's' = shared
        app_id = int(match.group(3))
        if form == "a":
            return user_id * PER_USER_RANGE + FIRST_APPLICATION_UID + app_id, user_id
        elif form == "i":
            # Isolated processes use UIDs in a separate range
            raise ValueError(f"isolated UID parsing not yet implemented: {text_uid!r}")
        elif form == "s":
            # Shared/system UID form
            raise ValueError(f"shared UID parsing not yet implemented: {text_uid!r}")
    raise ValueError(f"malformed Android UID: {text_uid!r}")




def _parse_duration_ms(duration_str: str) -> int:
    """Convert a Batterystats duration string like ``+4s200ms`` to milliseconds.

    Supports combinations of hours (``h``), minutes (``m``), seconds (``s``),
    and milliseconds (``ms``) in strict order (h → m → s → ms).
    The leading ``+`` is optional.
    Raises ``ValueError`` on empty or malformed input.
    """
    value = duration_str.strip()
    if not value:
        raise ValueError("empty duration string")
    # Strict fullmatch with ordered groups — each component optional but must
    # appear in h → m → s → ms order if present; at least one is required.
    match = re.fullmatch(r"\+?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?(?:(\d+)ms)?", value)
    if not match or not any(match.groups()):
        raise ValueError(f"malformed duration: {duration_str!r}")
    h = int(match.group(1) or 0)
    m = int(match.group(2) or 0)
    s = int(match.group(3) or 0)
    ms = int(match.group(4) or 0)
    return h * 3600000 + m * 60000 + s * 1000 + ms

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

def extract_uid_block(text: str, decimal_uid: int) -> str:
    """Extract the Batterystats UID detail section for the given decimal UID.

    Human-readable format uses a ``UID u0aXXX: <power>`` power-estimate line
    (capital ``UID``) in the first section, and a ``u0aXXX:`` detail section
    (no ``UID`` prefix) later with ``Fg Service for:``, ``Total cpu time:``,
    and ``Proc <pkg>:`` lines.

    Returns the detail section block content or empty string if not found.
    """
    android_uid = uid_to_android_uid(decimal_uid)
    lines = text.splitlines()
    in_block = False
    block: list[str] = []
    for line in lines:
        stripped = line.strip()
        # Match "u0aXXX:" (detail section) - not "UID u0aXXX:" (power section)
        detail_header = re.match(r"^" + re.escape(android_uid) + r":$", stripped)
        if detail_header and not stripped.startswith("UID"):
            in_block = True
            continue
        if in_block:
            # End of block: next "u0aXXX:" or blank line followed by non-indented content
            next_detail = re.match(r"^(u\d+[ais]\d+|\d+):$", stripped)
            if next_detail and not stripped.startswith("UID"):
                break
            if not stripped:
                break
            if not line.startswith("    ") and not line.startswith("      "):
                if not any(kw in stripped for kw in ("Fg Service", "Total running", "Total cpu", "Proc", "(nothing")):
                    break
            block.append(line)
    return "\n".join(block)


def extract_all_uids(text: str) -> list[tuple[int, str, str]]:
    """Extract all UIDs from Batterystats human-readable output.

    Returns list of (decimal_uid, android_uid_or_numeric, block_text) for all
    UIDs found in the detail section. Used for top-consumer reporting.
    Supports both ``u0aXXX:`` (app) and ``<N>:`` (system) detail headers.
    Only captures the detail section, not the power-estimate section.
    """
    uids: list[tuple[int, str, str]] = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        stripped = lines[i].strip()
        # Match u0aXXX: (app detail) or <N>: (system detail)
        match = re.match(r"^(u\d+[ais]\d+|\d+):$", stripped)
        if match:
            uid_text = match.group(1)
            # Skip "UID u0aXXX:" power headers
            if lines[i].lstrip().startswith("UID "):
                i += 1
                continue
            if uid_text.isdigit():
                decimal_uid = int(uid_text)
                android_uid = uid_text
            else:
                decimal_uid, _ = parse_android_uid(uid_text)
                android_uid = uid_text
            block_lines: list[str] = []
            i += 1
            while i < len(lines):
                s = lines[i].strip()
                next_header = re.match(r"^(u\d+[ais]\d+|\d+):$", s)
                if next_header:
                    i -= 1
                    break
                if not s:
                    break
                block_lines.append(lines[i])
                i += 1
            uids.append((decimal_uid, android_uid, "\n".join(block_lines)))
        i += 1
    return uids


def parse_batterystats(text: str, uid: int | None) -> dict[str, Metric]:
    """Parse Batterystats human-readable output for the target UID.

    Android 15 ``dumpsys batterystats --charged`` uses a ``UID u0aXXX:`` power
    estimate section and a ``u0aXXX:`` detail section.

    Supported detail fields:
      - ``Fg Service for: <duration>`` → foreground_service_ms
      - ``Total cpu time: u=<us> s=<ks>`` → cpu_user_ms, cpu_kernel_ms
      - ``Proc <pkg>: CPU: <us> usr + <ks> krn`` → proc_cpu_user_ms, proc_cpu_kernel_ms
      - ``Total running: <duration>`` → service_uptime_ms
    """
    if uid is None:
        return {key: unsupported("package UID unavailable") for key in (
            "cpu_user_ms", "cpu_kernel_ms", "foreground_service_ms",
            "service_uptime_ms", "estimated_power_mah",
            "proc_cpu_user_ms", "proc_cpu_kernel_ms")}

    block = extract_uid_block(text, uid)
    if not block:
        return {key: not_reported(f"UID {uid} absent from Batterystats detail") for key in (
            "cpu_user_ms", "cpu_kernel_ms", "foreground_service_ms",
            "service_uptime_ms", "estimated_power_mah",
            "proc_cpu_user_ms", "proc_cpu_kernel_ms")}

    result: dict[str, Metric] = {}

    # Foreground service duration
    fg = re.search(r"Fg Service for:\s*([^\n]+)", block)
    if fg:
        try:
            result["foreground_service_ms"] = available(_parse_duration_ms(re.sub(r"\s+", "", fg.group(1))))
        except ValueError:
            result["foreground_service_ms"] = parse_failed("Fg Service duration malformed")
    else:
        result["foreground_service_ms"] = not_reported("foreground service duration not reported")

    # Service uptime (Total running)
    run = re.search(r"Total running:\s*([^\n]+)", block)
    if run:
        try:
            result["service_uptime_ms"] = available(_parse_duration_ms(re.sub(r"\s+", "", run.group(1))))
        except ValueError:
            result["service_uptime_ms"] = parse_failed("Total running duration malformed")
    else:
        result["service_uptime_ms"] = not_reported("total running time not reported")

    # CPU time from "Total cpu time: u=<us> s=<ks>"
    cpu = re.search(r"Total cpu time:\s*u=(\d+[smhd]+\S*)\s*s=(\d+[smhd]+\S*)", block)
    if cpu:
        try:
            result["cpu_user_ms"] = available(_parse_duration_ms(cpu.group(1)))
            result["cpu_kernel_ms"] = available(_parse_duration_ms(cpu.group(2)))
        except ValueError:
            result["cpu_user_ms"] = parse_failed("Total cpu time user malformed")
            result["cpu_kernel_ms"] = parse_failed("Total cpu time kernel malformed")
    else:
        result["cpu_user_ms"] = not_reported("total cpu time not reported")
        result["cpu_kernel_ms"] = not_reported("total cpu time not reported")

    # Process CPU from "Proc <pkg>: CPU: <us> usr + <ks> krn"
    proc = re.search(r"Proc\s+\S+:\s*CPU:\s*(\d+[smhd]+\S*)\s+usr\s+\+\s*(\d+[smhd]+\S*)\s+krn", block)
    if proc:
        try:
            result["proc_cpu_user_ms"] = available(_parse_duration_ms(proc.group(1)))
            result["proc_cpu_kernel_ms"] = available(_parse_duration_ms(proc.group(2)))
        except ValueError:
            result["proc_cpu_user_ms"] = parse_failed("proc CPU user malformed")
            result["proc_cpu_kernel_ms"] = parse_failed("proc CPU kernel malformed")
    else:
        result["proc_cpu_user_ms"] = not_reported("process CPU not reported")
        result["proc_cpu_kernel_ms"] = not_reported("process CPU not reported")

    # Estimated power (from power estimation section, not block)
    android_uid = uid_to_android_uid(uid)
    power_match = re.search(
        r"UID\s+" + re.escape(android_uid) + r":\s*([\d.]+)",
        text, re.MULTILINE
    )
    if power_match:
        result["estimated_power_mah"] = available(round(float(power_match.group(1)), 4))
    else:
        result["estimated_power_mah"] = not_reported("estimated power not reported")

    return result
def parse_checkin(text: str, uid: int | None) -> dict[str, Metric]:
    """Parse Android Batterystats check-in records using the ``csv`` module.

    Samsung Android 15 check-in format:
      ``version,uid,which,tag,<data>``

    The ``which`` column is normally ``l`` (since-last-charged aggregation).
    The tag identifying the record type is at column index 3.

    Supported record tags (verified from S21 and S23U Android 15 output):
      - ``cpu`` — per-UID CPU: ``version,uid,which,cpu,user_ms,system_ms,io_ms``
      - ``pr`` — process CPU: ``version,uid,which,pr,\"process\",user_ms,system_ms,iowait,0,0,count``
      - ``awl`` — aggregate wakelock: ``version,uid,which,awl,partial_dur,bg_partial_dur``
      - ``pwi`` — power estimate: ``version,uid,which,pwi,uid/other,computed,min,max,raw``
        (parsed as estimated_power_mah from the computed column)

    Tags ``wl``, ``fgs``, ``au``/``aud`` are not emitted by these devices for idle
    app scenarios. When present from other builds they may be added.

    Malformed target-UID records return ``parse_failed``. Unrelated UID records
    are safely ignored.
    """
    if uid is None:
        return {key: unsupported("package UID unavailable") for key in (
            "cpu_user_ms", "cpu_kernel_ms", "checkin_wakelocks",
            "checkin_proc_cpu_user_ms", "checkin_proc_cpu_kernel_ms",
            "estimated_power_mah")}

    uid_str = str(uid)
    result: dict[str, Metric] = {
        "cpu_user_ms": not_reported("UID not found in check-in"),
        "cpu_kernel_ms": not_reported("UID not found in check-in"),
        "checkin_wakelocks": not_reported("no check-in wakelock records for this UID"),
        "checkin_proc_cpu_user_ms": not_reported("no check-in proc record for this UID"),
        "checkin_proc_cpu_kernel_ms": not_reported("no check-in proc record for this UID"),
        "estimated_power_mah": not_reported("no check-in power estimate for this UID"),
    }
    wakelocks: list[dict[str, int | str]] = []
    wl_uid_set = False

    reader = csv.reader(io.StringIO(text))
    for row in reader:
        if not row or row[0].startswith("#"):
            continue
        if len(row) < 4:
            continue

        # Column layout: [0]=version, [1]=uid, [2]=which, [3]=tag, [4+]=data
        record_uid = row[1].strip()
        if record_uid != uid_str:
            continue
        tag = row[3].strip()

        try:
            if tag == "cpu" and len(row) >= 6:
                # 9,uid,l,cpu,user_ms,system_ms,io_ms
                result["cpu_user_ms"] = available(int(row[4]))
                result["cpu_kernel_ms"] = available(int(row[5]))

            elif tag == "pr" and len(row) >= 7:
                # 9,uid,l,pr,"process",user_ms,system_ms,iowait,0,0,count
                result["checkin_proc_cpu_user_ms"] = available(int(row[5]))
                result["checkin_proc_cpu_kernel_ms"] = available(int(row[6]))

            elif tag == "awl" and len(row) >= 5:
                # 9,uid,l,awl,partial_dur,bg_partial_dur
                partial_dur = int(row[4])
                wakelocks.append({
                    "name": "aggregate_partial",
                    "type": "aggregate_partial",
                    "duration_ms": partial_dur,
                })
                if not wl_uid_set:
                    result["checkin_wakelocks"] = available(wakelocks)
                    wl_uid_set = True

            elif tag == "pwi" and len(row) >= 6 and row[4] == "uid":
                # 9,uid,l,pwi,uid/other,computed_power,min,max,raw
                result["estimated_power_mah"] = available(round(float(row[5]), 4))

        except (ValueError, IndexError) as exc:
            if tag in ("cpu", "pr", "awl", "pwi"):
                metric_key = {
                    "cpu": "cpu_user_ms",
                    "pr": "checkin_proc_cpu_user_ms",
                    "awl": "checkin_wakelocks",
                    "pwi": "estimated_power_mah",
                }.get(tag)
                result[metric_key] = parse_failed(
                    f"check-in {tag} record malformed: {exc}"
                ) if metric_key else result.get("cpu_user_ms", not_reported("unknown"))

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
    """Parse dumpsys batteryproperties output for capacity/health evidence.

    Battery property field semantics:
      - ``capacity_percent``: Android ``BATTERY_PROPERTY_CAPACITY`` — remaining
        capacity as an integer percentage (0–100), NOT µAh.
      - ``charge_counter_uah``: ``BATTERY_PROPERTY_CHARGE_COUNTER`` in µAh.
      - ``current_now_ua``: ``BATTERY_PROPERTY_CURRENT_NOW`` in µA.
      - ``voltage_mv``: Voltage in millivolts (value ~3800 = 3.8 V).
      - ``temperature_tenths_c``: Tenths of Celsius (value 250 = 25.0°C).
      - ``health``: Battery health enum (2 = good).

    Samsung/OEM fields (``remaining_capacity``, ``design_capacity``,
    ``full_charge_capacity``) are exposed with units marked as
    ``unit_unknown`` unless the device output or vendor documentation
    establishes the unit.
    """
    result: dict[str, Metric] = {}
    extracts = {
        "capacity_percent": (r"capacity:\s*(\d+)", "batteryproperties capacity"),
        "charge_counter_uah": (r"charge_counter:\s*(\d+)", "batteryproperties charge counter"),
        "remaining_capacity": (r"remaining_capacity:\s*(\d+)", "remaining capacity (Samsung, unit unknown)"),
        "current_now_ua": (r"current_now:\s*(-?\d+)", "current now"),
        "design_capacity": (r"(?i)design_capacity:\s*(\d+)", "design capacity (Samsung, unit unknown)"),
        "health": (r"health:\s*(\d+)", "batteryproperties health"),
        "temperature_tenths_c": (r"temperature:\s*(\d+)", "batteryproperties temperature"),
        "voltage_mv": (r"^[\s]*voltage:\s*(\d+)", "batteryproperties voltage"),
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
        return (not_reported("charge delta unavailable for mAh conversion"),
                not_reported("charge delta unavailable for rate conversion"))
    mah = delta_uah.value / 1000.0
    rate = mah / duration_hours if duration_hours > 0 else 0.0
    return (available(round(mah, 3)), available(round(rate, 3)))
def sanitise_uid_label(uid: int, known_app_uid: int | None, seen_uids: dict[int, int] | None = None) -> str:
    """Map a decimal UID to a sanitised label for top-consumer reporting.

    Known app UID gets labelled ``target_app``; system UIDs (<10000) get
    ``system``; other UIDs get sequential anonymous aliases
    (``other_uid_1``, ``other_uid_2``, …).

    When ``seen_uids`` is provided, the caller controls the numbering order.
    When omitted, a module-level counter is used (not suitable for parallel
    or isolated calls).
    """
    if known_app_uid is not None and uid == known_app_uid:
        return "target_app"
    if uid < FIRST_APPLICATION_UID:
        return "system"

    if seen_uids is not None:
        if uid not in seen_uids:
            seen_uids[uid] = len(seen_uids) + 1
        return f"other_uid_{seen_uids[uid]}"

    # Module-level static tracking for callers that don't provide seen_uids
    _counter = getattr(sanitise_uid_label, "_counter", 0)
    _seen: dict[int, int] = getattr(sanitise_uid_label, "_seen", {})
    if uid not in _seen:
        _counter += 1
        _seen[uid] = _counter
        sanitise_uid_label._counter = _counter
        sanitise_uid_label._seen = _seen
    return f"other_uid_{_seen[uid]}"


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
        if mode == "smoke":
            mode = "smoke-disabled"
        self.mode = mode
        self.enabled = mode in ("enabled", "smoke-enabled")
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
        """Capture a boundary snapshot including parsed metrics, raw texts, and device uptime."""
        client = self.clients[alias]
        battery_text = client.shell("dumpsys", "battery")
        power_text = client.shell("dumpsys", "power")
        idle_text = client.shell("dumpsys", "deviceidle")
        services_text = client.shell("dumpsys", "activity", "services", self.package)
        batteryproperties_text = client.shell("dumpsys", "batteryproperties")
        # Capture device uptime for reboot detection
        uptime_text = client.shell("cat", "/proc/uptime")
        uptime_seconds = float(uptime_text.split()[0]) if uptime_text.strip() else 0.0
        return {
            "phase": phase,
            "wall_clock_utc": utc_now(),
            "monotonic_ms": time.monotonic_ns() // 1_000_000,
            "uptime_seconds": uptime_seconds,
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

    def top_consumers(self, raw_batterystats: str, known_uid: int | None) -> dict[str, Metric]:
        """Extract top other power-consuming UIDs with sequential anonymous aliases.

        Extracts estimated power from the ``UID u0aXXX: <power>`` power estimation
        section (not the detail block). UIDs with no power estimate are excluded.
        """
        if not raw_batterystats:
            return {"top_consumers": not_reported("Batterystats not available")}
        all_uids = extract_all_uids(raw_batterystats)
        if not all_uids:
            return {"top_consumers": not_reported("no UIDs found in Batterystats")}
        consumers: list[dict[str, Any]] = []
        seen: dict[int, int] = {}
        for decimal_uid, android_uid, _block in all_uids:
            # Extract power from power estimation section: "UID u0aXXX: <power>"
            uid_text = android_uid
            power_match = re.search(
                r"UID\s+" + re.escape(uid_text) + r":\s*([\d.]+)",
                raw_batterystats, re.MULTILINE
            )
            power = float(power_match.group(1)) if power_match else 0.0
            label = sanitise_uid_label(decimal_uid, known_uid, seen)
            if label != "target_app" and power > 0:
                consumers.append({"label": label, "estimated_power_mah": round(power, 4)})
        consumers.sort(key=lambda c: c["estimated_power_mah"], reverse=True)
        return {"top_consumers": available(consumers[:5])} if consumers else {"top_consumers": not_reported("no non-target consumers above zero power")}

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
            charge_delta: Metric = not_reported("charge counter unavailable")
            mah_consumed: Metric = not_reported("charge delta unavailable")
            mah_per_hour: Metric = not_reported("charge delta unavailable")
            actual_elapsed_s = (ends[alias]["monotonic_ms"] - starts[alias]["monotonic_ms"]) / 1000.0

            # Nonpositive elapsed invalidates the entire paired run — no reliable timing.
            if actual_elapsed_s <= 0:
                raise HarnessError(f"{alias}: nonpositive monotonic elapsed ({actual_elapsed_s}s) — pair invalidated")
            elif battery_start["level_percent"].state is Availability.AVAILABLE and battery_end["level_percent"].state is Availability.AVAILABLE:
                loss = battery_start["level_percent"].value - battery_end["level_percent"].value
                delta = available(loss)
                elapsed_hours = actual_elapsed_s / 3600.0
                rate = available(round(loss / elapsed_hours, 3)) if elapsed_hours > 0 else parse_failed("zero elapsed hours")

            # Charge-counter delta (using actual elapsed time)
            if actual_elapsed_s > 0:
                charge_delta = compute_charge_delta_uah(battery_start, battery_end)
                mah_consumed, mah_per_hour = compute_mah_from_uah(charge_delta, actual_elapsed_s / 3600.0)

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

            # Top consumers (sanitised with sequential aliases)
            top = self.top_consumers(raw_end[alias].get("batterystats-charged.txt", ""), uid)
            top_consumers_public = {key: value.public() for key, value in top.items()}

            # Battery health from batteryproperties (start)
            health_props = {
                key: props_start[key].public() if key in props_start else not_reported(f"{key} not in batteryproperties").public()
                for key in ("capacity_percent", "charge_counter_uah", "remaining_capacity",
                            "current_now_ua", "health", "temperature_tenths_c", "voltage_mv")
            }
            if "design_capacity" in props_start:
                health_props["design_capacity"] = props_start["design_capacity"].public()

            start_uptime = starts[alias].get("uptime_seconds", 0)
            end_uptime = ends[alias].get("uptime_seconds", 0)
            # Uptime decrease → likely reboot → invalidate the pair.
            if start_uptime > 0 and end_uptime > 0 and end_uptime < start_uptime:
                raise HarnessError(f"{alias}: uptime decreased from {start_uptime}s to {end_uptime}s — possible reboot, pair invalidated")
            reboot_detected = start_uptime > end_uptime and start_uptime > 0
            screen_info = {
                "start_wakefulness": starts[alias]["power"].get("wakefulness", not_reported("not reported")).public(),
                "end_wakefulness": ends[alias]["power"].get("wakefulness", not_reported("not reported")).public(),
                "start_screen_on": starts[alias]["power"].get("screen_on", not_reported("not reported")).public(),
                "end_screen_on": ends[alias]["power"].get("screen_on", not_reported("not reported")).public(),
                "reboot_detected": (available(reboot_detected) if start_uptime > 0 and end_uptime > 0 else not_reported("device uptime unavailable")).public(),
            }

            # Actual elapsed time and end skew
            end_skew = abs(ends["s21"]["monotonic_ms"] - ends["s23u"]["monotonic_ms"])

            # Build device section
            devices[alias] = {
                "identity": identity.public(),
                "start": self._public_snapshot(starts[alias], "start"),
                "end": self._public_snapshot(ends[alias], "end"),
                "actual_elapsed_seconds": round(actual_elapsed_s, 1),
                "whole_device": {
                    "battery_delta_percentage_points": delta.public(),
                    "battery_percentage_points_per_hour": rate.public(),
                    "charge_delta_uah": charge_delta.public(),
                    "mah_consumed": mah_consumed.public(),
                    "mah_per_hour": mah_per_hour.public(),
                },
                "battery_health": health_props,
                "battery_end": {key: battery_end[key].public() for key in ("charge_counter_uah", "voltage_mv", "temperature_tenths_c", "health", "cycle_count", "full_charge_uah") if key in battery_end},
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
            "requested_duration_seconds": self.duration_seconds,
            "primary_comparison": "S21 enabled − S21 disabled; S23U enabled − S23U disabled",
            "cross_device_warning": "Percentage-point differences are not equal energy differences across batteries; use only as secondary context.",
            "start_skew_ms": start_skew_ms({alias: starts[alias]["monotonic_ms"] for alias in starts}).public(),
            "end_skew_ms": available(end_skew).public(),
            "validity": {"state": "valid" if classification == "EVIDENTIARY" else "non_evidentiary", "abort_reason": self.abort_reason},
            "raw_artifacts": "private, gitignored run directory; not named in commit-safe output",
            "devices": devices,
            "limitations": [
                "Missing platform/OEM fields retain explicit availability states and are never converted to zero.",
                "No accelerator assignment is claimed without native provider evidence.",
                "Smoke and fixture runs never support battery or causal conclusions.",
                "mAh consumption derived from charge-counter delta; sign convention: positive = consumption.",
                "Samsung-specific fields (remaining_capacity, design_capacity) use unit_unknown unless verified from vendor documentation.",
            ],
        }
        secrets = tuple(c.serial for c in self.clients.values())
        assert_commit_safe(summary, secrets)
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

    def run_physical(self, interactive: bool) -> RunResult:
        """Execute a paired physical run with abort-safe cleanup.

        The enabled lifecycle (WakeWordDiag DEBUG) is wrapped in try/finally
        so diagnostics are restored even after precondition failure, ADB loss,
        KeyboardInterrupt, or parser error.

        Returns a ``RunResult`` with structured summary and exit code.
        """
        self.write_private_manifest()
        self.validate_devices()
        cleanup_errors: list[str] = []
        # Per-device diagnostic tracking: maps alias to original property value
        diag_originals: dict[str, str | None] = {}
        diag_changed: dict[str, bool] = {"s21": False, "s23u": False}
        starts: dict[str, dict[str, Any]] = {}
        ends: dict[str, dict[str, Any]] = {}
        raw: dict[str, dict[str, str]] = {}
        classification = "ABORTED_NON_EVIDENTIARY"

        try:
            # --- Operator gates ---
            if self.mode == "baseline-disabled":
                prompt_for_confirmation("Manually disable Listen for Hey Jandal on both S21 and S23 Ultra. Return only after both toggles are off.", interactive)
            elif self.mode in ("enabled", "smoke-enabled"):
                prompt_for_confirmation("Manually enable Listen for Hey Jandal on both devices, verify the intended build/assets, and complete one pre-test wake smoke on each device.", interactive)
            else:
                prompt_for_confirmation("This is a NON_EVIDENTIARY_SMOKE. Ensure wake word is disabled on both devices. Confirm both device states.", interactive)

            self.verify_mode_service_state()

            # --- Diagnostics setup (per-device, transactional) ---
            if self.enabled:
                for alias, client in self.clients.items():
                    # Read original property
                    try:
                        orig = client.shell("getprop", f"log.tag.{DIAGNOSTIC_TAG}").strip()
                        diag_originals[alias] = orig if orig else None
                    except (HarnessError, OSError, subprocess.TimeoutExpired) as exc:
                        raise HarnessError(f"{alias}: cannot read initial WakeWordDiag property: {exc}") from exc
                    # Set DEBUG
                    client.shell("setprop", f"log.tag.{DIAGNOSTIC_TAG}", "DEBUG")
                    diag_changed[alias] = True
                    # Verify
                    level = client.shell("getprop", f"log.tag.{DIAGNOSTIC_TAG}").strip().upper()
                    if level != "DEBUG":
                        raise HarnessError(f"{alias}: WakeWordDiag property did not take effect (got {level})")

                prompt_for_confirmation("Restart or re-arm both detectors after setting WakeWordDiag DEBUG, then wait for and confirm a WakeWordDiag summary can be observed locally.", interactive)
                self.verify_enabled_diagnostics()
                self.verify_mode_service_state()

            # --- Pre-reset check ---
            prompt_for_confirmation("Physically unplug charger and USB from both devices. Turn both screens off and lock them. Confirm only when both are ready.", interactive)
            pre_reset = {alias: self.boundary_snapshot(alias, "pre_reset") for alias in self.clients}
            self.verify_unplugged_pair(pre_reset)

            for client in self.clients.values():
                client.shell("dumpsys", "batterystats", "--reset")

            start_wall = utc_now()
            start_mono = time.monotonic_ns() // 1_000_000
            starts = {alias: self.boundary_snapshot(alias, "start") for alias in self.clients}
            self.verify_unplugged_pair(starts)
            for alias in self.clients:
                self.capture_start_raw(alias, starts)

            # --- Wait / idle window ---
            if self.mode == "smoke":
                wait_seconds = min(self.duration_seconds, 120)
                classification = "NON_EVIDENTIARY_SMOKE"
            elif self.mode in ("smoke-disabled", "smoke-enabled"):
                wait_seconds = min(self.duration_seconds, 300)
                classification = "NON_EVIDENTIARY_SMOKE"
            else:
                wait_seconds = self.duration_seconds
                classification = "EVIDENTIARY"

            time.sleep(wait_seconds)

            end_wall = utc_now()
            end_mono = time.monotonic_ns() // 1_000_000

            # --- End boundary (before bugreport) ---
            ends = {alias: self.boundary_snapshot(alias, "end") for alias in self.clients}
            self.verify_unplugged_pair(ends)

            # --- End raw capture ---
            raw = {}
            for alias in self.clients:
                raw[alias] = self.capture_end_raw(alias)

            # --- Bugreport (after official end, failure invalidates) ---
            for alias in self.clients:
                try:
                    self.collect_bugreport(alias)
                except (HarnessError, OSError, subprocess.TimeoutExpired) as exc:
                    raise HarnessError(f"{alias}: bugreport failed — {exc}. Run invalidated.") from exc

        except (HarnessError, OSError, subprocess.TimeoutExpired, KeyboardInterrupt) as exc:
            if isinstance(exc, KeyboardInterrupt):
                self.abort_reason = "operator keyboard interrupt"
            else:
                self.abort_reason = str(exc)
            classification = "ABORTED_NON_EVIDENTIARY"

        finally:
            # --- Cleanup: restore diagnostics per device ---
            for alias in ("s21", "s23u"):
                if not diag_changed.get(alias):
                    continue
                client = self.clients.get(alias)
                if not client:
                    continue
                try:
                    orig = diag_originals.get(alias)
                    if orig is None or orig == "":
                        # Property was unset — clear it
                        client.shell("setprop", f"log.tag.{DIAGNOSTIC_TAG}", "")
                    else:
                        # Restore original value
                        client.shell("setprop", f"log.tag.{DIAGNOSTIC_TAG}", orig)
                    # Verify it's no longer DEBUG
                    level = client.shell("getprop", f"log.tag.{DIAGNOSTIC_TAG}").strip().upper()
                    if level == "DEBUG":
                        cleanup_errors.append(f"{alias}: property still DEBUG after restore attempt")
                except (HarnessError, OSError, subprocess.TimeoutExpired) as exc:
                    cleanup_errors.append(f"{alias}: cleanup failed — {exc}")

        # --- Outcome ---
        # Cleanup failures take precedence: they invalidate even an already-aborted run.
        if cleanup_errors:
            self.abort_reason = f"diagnostic cleanup failure: {'; '.join(cleanup_errors)}"
            for alias in self.clients:
                if starts.get(alias):
                    self.capture_start_raw(alias, starts)
                end_data = ends.get(alias)
                raw_data = raw.get(alias)
                if raw_data:
                    for filename, content in raw_data.items():
                        if content:
                            self.private_write(alias, "partial", filename, content)
            summary = self.abort_summary(starts, ends, raw)
            return RunResult(summary, False, 3)

        if self.abort_reason:
            for alias in self.clients:
                if starts.get(alias):
                    self.capture_start_raw(alias, starts)
                end_data = ends.get(alias)
                raw_data = raw.get(alias)
                if raw_data:
                    for filename, content in raw_data.items():
                        if content:
                            self.private_write(alias, "partial", filename, content)
            summary = self.abort_summary(starts, ends, raw)
            return RunResult(summary, False, 1)

        summary = self.public_summary(starts, ends, raw, classification)
        return RunResult(summary, True, 0)

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
            "requested_duration_seconds": self.duration_seconds,
            "primary_comparison": "S21 enabled − S21 disabled; S23U enabled − S23U disabled",
            "cross_device_warning": "Run was aborted; no comparison possible.",
            "validity": {"state": "aborted", "abort_reason": self.abort_reason},
            "raw_artifacts": "private, gitignored run directory; partial evidence preserved",
            "devices": devices,
            "limitations": ["Run did not complete; abort summary only. Not evidentiary."],
        }
        secrets = tuple(c.serial for c in self.clients.values())
        assert_commit_safe(summary, secrets)
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
        "requested_duration_seconds": duration_seconds,
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
    classification = summary.get("classification", "UNKNOWN")
    duration = summary.get("requested_duration_seconds", summary.get("duration_seconds", "?"))
    validity = summary.get("validity", {})
    validity_state = validity.get("state", "unknown") if isinstance(validity, dict) else "unknown"
    abort_reason = validity.get("abort_reason") if isinstance(validity, dict) else None

    lines = [
        "# Paired battery telemetry summary",
        "",
        f"**Classification:** `{classification}`",
        f"**Duration:** {duration} seconds",
        "",
        "> Primary comparison: **S21 enabled − S21 disabled** and **S23U enabled − S23U disabled**. Cross-device percentage points are secondary context only.",
        "",
        "| Device | Battery delta | Rate | Start skew |",
        "| --- | ---: | ---: | ---: |",
    ]

    start_skew = summary.get("start_skew_ms", {})
    if isinstance(start_skew, dict):
        skew = str(start_skew.get("value", start_skew.get("state", "unavailable")))
    else:
        skew = "unavailable"

    for alias, device in summary.get("devices", {}).items():
        whole_device = device.get("whole_device", {}) if isinstance(device, dict) else {}
        if isinstance(whole_device, dict):
            delta = whole_device.get("battery_delta_percentage_points", {})
            rate = whole_device.get("battery_percentage_points_per_hour", {})
        else:
            delta = not_reported("whole_device not available").public()
            rate = not_reported("whole_device not available").public()
        if isinstance(delta, dict):
            delta_str = str(delta.get("value", delta.get("state", "unavailable")))
        else:
            delta_str = str(delta)
        if isinstance(rate, dict):
            rate_str = str(rate.get("value", rate.get("state", "unavailable")))
        else:
            rate_str = str(rate)
        lines.append(f"| {alias} | {delta_str} pp | {rate_str} pp/h | {skew} ms |")

    lines.extend([
        "",
        "## Validity",
        "",
        f"- State: `{validity_state}`",
        "- Raw artifacts: private and gitignored; no raw path or device identifier is published.",
        "- No causal or release recommendation may be made from smoke or fixture output.",
    ])

    if abort_reason:
        lines.append(f"- Abort reason: {abort_reason}")

    return "\n".join(lines) + "\n"


def write_sanitized_summary(output_dir: Path, summary: dict[str, Any], secrets: Iterable[str] = ()) -> tuple[Path, Path]:
    assert_commit_safe(summary, secrets)
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / "run-summary.json"
    markdown_path = output_dir / "run-summary.md"
    json_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    markdown_path.write_text(render_markdown(summary))
    return json_path, markdown_path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("smoke", "smoke-disabled", "smoke-enabled", "baseline-disabled", "enabled"),
                        help="``smoke`` is an alias for ``smoke-disabled``")
    parser.add_argument("--s21", default=os.environ.get("JANDAL_S21_ADB"), help="private S21 ADB identifier; never emitted")
    parser.add_argument("--s23u", default=os.environ.get("JANDAL_S23U_ADB"), help="private S23U ADB identifier; never emitted")
    parser.add_argument("--duration", default="4h", type=parse_duration)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--private-root", type=Path, default=DEFAULT_PRIVATE_ROOT)
    parser.add_argument("--fixture", type=Path, help="synthetic non-interactive fixture; no ADB commands run")
    parser.add_argument("--interactive", action="store_true", help="allow physical run after explicit operator confirmations")
    return parser

@dataclasses.dataclass
class RunResult:
    """Structured outcome for CLI exit code determination."""
    summary: dict[str, Any]
    success: bool
    exit_code: int


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.fixture:
            fixture = json.loads(args.fixture.read_text())
            summary = fixture_summary(fixture, args.mode, args.duration, f"fixture-{args.mode}")
            output = args.private_root / "fixture-sanitized"
            json_path, markdown_path = write_sanitized_summary(output, summary)
            print(f"NON_EVIDENTIARY_FIXTURE_DRY_RUN summary written: {json_path.name}, {markdown_path.name}")
            return RunResult(summary, True, 0).exit_code
        if not args.s21 or not args.s23u:
            raise HarnessError("--s21 and --s23u (or private environment variables) are required for physical execution")
        harness = PairedHarness(args.mode, args.package, args.duration, args.private_root, {"s21": AdbClient(args.s21), "s23u": AdbClient(args.s23u)})
        result = harness.run_physical(args.interactive)
        adb_secrets = (args.s21, args.s23u)
        try:
            json_path, markdown_path = write_sanitized_summary(harness.run_dir / "sanitized", result.summary, adb_secrets)
            print(f"{result.summary['classification']} summary written: {json_path.name}, {markdown_path.name}")
        except (HarnessError, OSError) as report_error:
            adb_selectors = (args.s21, args.s23u)
            print(f"ABORTED (report write failed): {sanitise_text(str(report_error), adb_selectors)}", file=sys.stderr)
            return 2
        return result.exit_code
    except (HarnessError, json.JSONDecodeError, OSError) as error:
        adb_selectors = (getattr(args, 's21', '') or '', getattr(args, 's23u', '') or '')
        print(f"ABORTED: {sanitise_text(str(error), adb_selectors)}", file=sys.stderr)
        return 2
if __name__ == "__main__":
    raise SystemExit(main())
