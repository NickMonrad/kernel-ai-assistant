#!/usr/bin/env python3
"""Run deterministic on-device permission scenarios with local evidence output.

First slice for issue #1330:
- deterministic ADB-driven execution only
- local report output only; no publishing, PR comments, or durable evidence upload
- S21-first physical validation
- data-driven scenario definitions loaded from Python constants

The runner writes a rich local report (`result.json`, `summary.md`, `logcat.txt`, screenshots)
and a lightweight schema-compatible `evidence.json` derived from the richer output, using
explicit non-inference model metadata for permission-only scenarios.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from permission_scenario_defs import DEFAULT_UX_THRESHOLDS, FIXTURES, SCENARIOS

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent
REPO = "NickMonrad/kernel-ai-assistant"
SCHEMA_VERSION = "1.0"
SUITE = "permission_scenarios"
APP_PACKAGE = "com.kernel.ai.debug"
MAIN_ACTIVITY = "com.kernel.ai.MainActivity"
UI_DUMP_REMOTE_PATH = "/sdcard/permission-runner-ui.xml"

SETTINGS_PACKAGE_PREFIX = "com.android.settings"
LOGCAT_TAG_SPECS = [
    "KernelAI:D",
    "AndroidRuntime:E",
    "System.err:W",
]

NON_INFERENCE_MODEL = {
    "name": "not_applicable",
    "runtime": "permission_scenario_runner",
    "backend": "adb",
}

# ── Schema validation constants ──────────────────────────────────────────────
SUPPORTED_ACTIONS = frozenset({
    "set_permission_state", "launch_main", "launch_quick_action",
    "tap_visible", "tap_toggle_for_text", "set_toggle_state",
    "check_default_assistant_ready", "press_home", "press_back",
})

SUPPORTED_PERMISSION_STATES = frozenset({"granted", "revoked", "prompt", "blocked"})

REQUIRED_SCENARIO_FIELDS = frozenset({"id", "title", "capability", "tags", "steps"})

REQUIRED_STEP_FIELDS = frozenset({"id", "action", "expected"})


class RunnerError(RuntimeError):
    """Base runner error."""


class DeviceUnavailable(RunnerError):
    """ADB device or serial mismatch."""


class StepFailure(RunnerError):
    """Scenario step failed."""


class ScenarioBlocked(RunnerError):
    """Scenario cannot proceed deterministically on the current device state."""


@dataclass(slots=True)
class UiNode:
    text: str
    content_desc: str
    resource_id: str
    class_name: str
    bounds: tuple[int, int, int, int] | None
    clickable: bool
    enabled: bool
    checked: bool | None
    package: str

    @property
    def center(self) -> tuple[int, int] | None:
        if self.bounds is None:
            return None
        left, top, right, bottom = self.bounds
        return ((left + right) // 2, (top + bottom) // 2)


@dataclass(slots=True)
class StepTrace:
    index: int
    id: str
    action: str
    expected: str
    actual: str
    result: str
    duration_ms: int
    phase: str = "main"
    screenshot: str | None = None
    screenshot_error: str | None = None
    debug: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class ScenarioResult:
    schema_version: str
    source: str
    suite: str
    scenario_id: str
    scenario_title: str
    timestamp: str
    repo: str
    branch: str | None
    commit: str | None
    pr: int | None
    device: dict[str, Any]
    functional_result: str
    ux_result: str
    step_count: int
    tap_count: int
    settings_hops: int
    back_presses: int
    duration_seconds: float
    manual_intervention_required: bool
    steps: list[StepTrace]
    artifacts: dict[str, Any]
    ux_warnings: list[str] = field(default_factory=list)
    blocked_reason: str | None = None
    fixtures_used: dict[str, object] = field(default_factory=dict)



@dataclass(slots=True)
class RunResult:
    schema_version: str
    source: str
    suite: str
    timestamp: str
    repo: str
    branch: str | None
    commit: str | None
    pr: int | None
    run_id: str
    device: dict[str, Any]
    thresholds: dict[str, Any]
    summary: dict[str, Any]
    scenarios: list[ScenarioResult]
    artifacts: dict[str, Any]


class AdbClient:
    def __init__(self, serial: str | None) -> None:
        self.serial = serial

    def _base_cmd(self) -> list[str]:
        cmd = ["adb"]
        if self.serial:
            cmd.extend(["-s", self.serial])
        return cmd

    def run(
        self,
        *args: str,
        timeout: float = 30.0,
        check: bool = True,
        text: bool = True,
        input_text: str | bytes | None = None,
    ) -> subprocess.CompletedProcess[str] | subprocess.CompletedProcess[bytes]:
        cmd = self._base_cmd() + list(args)
        result = subprocess.run(
            cmd,
            input=input_text,
            capture_output=True,
            text=text,
            timeout=timeout,
        )
        if check and result.returncode != 0:
            stderr = result.stderr.strip() if isinstance(result.stderr, str) else (result.stderr or b"").decode(errors="replace").strip()
            stdout = result.stdout.strip() if isinstance(result.stdout, str) else (result.stdout or b"").decode(errors="replace").strip()
            detail = stderr or stdout or f"exit {result.returncode}"
            raise RunnerError(f"ADB command failed: {' '.join(cmd)} :: {detail}")
        return result

    def shell(self, command: str, timeout: float = 30.0, check: bool = True) -> str:
        result = self.run("shell", command, timeout=timeout, check=check, text=True)
        assert isinstance(result.stdout, str)
        return result.stdout.strip()

    def shell_bool(self, command: str, timeout: float = 30.0) -> bool:
        try:
            self.shell(command, timeout=timeout, check=True)
        except RunnerError:
            return False
        return True

    def exec_out(self, *args: str, timeout: float = 30.0) -> bytes:
        result = self.run("exec-out", *args, timeout=timeout, check=True, text=False)
        assert isinstance(result.stdout, bytes)
        return result.stdout

    def ensure_device_available(self) -> None:
        result = self.run("devices", timeout=10, check=True, text=True)
        assert isinstance(result.stdout, str)
        lines = [line.strip() for line in result.stdout.splitlines()[1:] if line.strip()]
        if not lines:
            raise DeviceUnavailable("No adb devices attached")
        if self.serial and not any(line.startswith(f"{self.serial}\tdevice") for line in lines):
            raise DeviceUnavailable(
                f"Requested serial {self.serial!r} not in adb devices output: {lines}"
            )

    def current_package(self) -> str:
        output = self.shell("dumpsys activity activities", timeout=20)
        match = re.search(r"topResumedActivity=.*? ([^/\s]+)/", output)
        if match:
            return match.group(1)
        return self.shell("dumpsys window windows | grep mCurrentFocus", timeout=20, check=False) or ""


class LogcatCapture:
    USER_RE = re.compile(r"(HuggingFaceAuthManager: restored auth=[^,]+, user=)(\S+)")

    def __init__(self, adb: AdbClient) -> None:
        self._adb = adb

    def start(self) -> None:
        return

    def stop(self, pid: str | None) -> list[str]:
        if not pid:
            return ["<logcat capture skipped: app pid unavailable>"]
        cmd = ["logcat", "--pid", pid, "-d", "-t", "200", "-v", "threadtime", "-s", *LOGCAT_TAG_SPECS]
        result = self._adb.run(*cmd, timeout=30, check=False, text=True)
        stdout = result.stdout if isinstance(result.stdout, str) else (result.stdout or b"").decode("utf-8", errors="replace")
        if not stdout.strip():
            return ["<no matching app logcat lines captured>"]
        return [self._redact(line) for line in stdout.splitlines()]

    def _redact(self, line: str) -> str:
        return self.USER_RE.sub(r"\1<redacted>", line)


class UiAutomatorView:
    BOUNDS_RE = re.compile(r"\[(\d+),(\d+)]\[(\d+),(\d+)]")

    def __init__(self, adb: AdbClient) -> None:
        self._adb = adb

    def dump_nodes(self, timeout: float = 15.0) -> list[UiNode]:
        self._adb.shell(f"uiautomator dump {UI_DUMP_REMOTE_PATH}", timeout=timeout)
        xml_text = self._adb.exec_out("cat", UI_DUMP_REMOTE_PATH, timeout=timeout).decode("utf-8", errors="replace")
        return self._parse_nodes(xml_text)

    def _parse_nodes(self, xml_text: str) -> list[UiNode]:
        try:
            root = ET.fromstring(xml_text)
        except ET.ParseError as exc:
            raise RunnerError(f"Could not parse UI dump XML: {exc}") from exc
        nodes: list[UiNode] = []
        for el in root.iter("node"):
            checked_raw = el.attrib.get("checked")
            checked = None if checked_raw not in {"true", "false"} else checked_raw == "true"
            nodes.append(
                UiNode(
                    text=(el.attrib.get("text") or "").strip(),
                    content_desc=(el.attrib.get("content-desc") or "").strip(),
                    resource_id=(el.attrib.get("resource-id") or "").strip(),
                    class_name=(el.attrib.get("class") or "").strip(),
                    bounds=self._parse_bounds(el.attrib.get("bounds")),
                    clickable=(el.attrib.get("clickable") == "true"),
                    enabled=(el.attrib.get("enabled") != "false"),
                    checked=checked,
                    package=(el.attrib.get("package") or "").strip(),
                )
            )
        return nodes

    def _parse_bounds(self, raw: str | None) -> tuple[int, int, int, int] | None:
        if not raw:
            return None
        match = self.BOUNDS_RE.fullmatch(raw)
        if not match:
            return None
        return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


class ScenarioRunner:
    def __init__(
        self,
        adb: AdbClient,
        device: dict[str, Any],
        branch: str | None,
        commit: str | None,
        pr: int | None,
        run_dir: Path,
        thresholds: dict[str, Any],
    ) -> None:
        self.adb = adb
        self.device = device
        self.branch = branch
        self.commit = commit
        self.pr = pr
        self.run_dir = run_dir
        self.thresholds = thresholds
        self.ui = UiAutomatorView(adb)
        self.screenshots_dir = run_dir / "screenshots"
        self.screenshots_dir.mkdir(parents=True, exist_ok=True)
        self.logcat_path = run_dir / "logcat.txt"

    def wake_and_home(self) -> None:
        self.adb.shell("input keyevent KEYCODE_WAKEUP", timeout=10, check=False)
        time.sleep(0.5)
        self.adb.shell("input swipe 540 2200 540 500 500", timeout=10, check=False)
        time.sleep(1.0)
        self.adb.shell("input keyevent KEYCODE_HOME", timeout=10, check=False)
        time.sleep(1.0)

    def run_scenario(self, scenario: dict[str, Any]) -> ScenarioResult:
        started = time.monotonic()
        steps: list[StepTrace] = []
        tap_count = 0
        settings_hops = 0
        back_presses = 0
        self.wake_and_home()
        self.adb.shell(f"am force-stop {APP_PACKAGE}", timeout=20, check=False)
        time.sleep(1.0)
        manual_intervention_required = False
        logcat_capture = LogcatCapture(self.adb)
        scenario_timestamp = now_iso()
        current_pid: str | None = None
        logcat_capture.start()
        functional_result = "pass"
        blocked_reason: str | None = None

        preconditions = scenario.get("preconditions", [])
        main_steps = scenario.get("steps", [])
        cleanup_steps = scenario.get("cleanup", [])

        try:
            # ── Phase 1: Preconditions ────────────────────────────────────
            # Precondition failure → blocked (not product failure).
            for index, step in enumerate(preconditions, start=1):
                step_started = time.monotonic()
                action = step["action"]
                expected = step.get("expected", action)
                debug: dict[str, Any] = {}
                actual = ""
                result = "pass"
                try:
                    actual, _ = self._execute_step(step)
                    self._apply_expectations(step)
                    result = "pass"
                except (ScenarioBlocked, StepFailure) as exc:
                    functional_result = "blocked"
                    blocked_reason = str(exc)
                    actual = str(exc)
                    result = "blocked"
                duration_ms = int((time.monotonic() - step_started) * 1000)
                if functional_result != "pass":
                    debug.update(self._collect_debug_state())
                steps.append(StepTrace(
                    index=index, id=step["id"], action=action, expected=expected,
                    actual=actual or "ok", result=result, duration_ms=duration_ms,
                    phase="precondition", debug=debug,
                ))
                if result != "pass":
                    break

            # ── Phase 2: Main steps ───────────────────────────────────────
            if functional_result == "pass":
                offset = len(preconditions)
                for index, step in enumerate(main_steps, start=1 + offset):
                    step_started = time.monotonic()
                    action = step["action"]
                    expected = step.get("expected", action)
                    screenshot_error: str | None = None
                    debug = {}
                    actual = ""
                    try:
                        actual, delta = self._execute_step(step)
                        tap_count += delta.get("tap_count", 0)
                        settings_hops += delta.get("settings_hops", 0)
                        back_presses += delta.get("back_presses", 0)
                        manual_intervention_required = manual_intervention_required or delta.get("manual_intervention_required", False)
                        current_pid = (delta.get("current_pid") or current_pid) if isinstance(delta.get("current_pid"), str) else current_pid
                        self._apply_expectations(step)
                        result = "pass"
                    except ScenarioBlocked as exc:
                        functional_result = "blocked"
                        blocked_reason = str(exc)
                        actual = str(exc)
                        result = "blocked"
                    except StepFailure as exc:
                        functional_result = "fail"
                        actual = str(exc)
                        result = "fail"
                    duration_ms = int((time.monotonic() - step_started) * 1000)
                    screenshot_path = None
                    if step.get("screenshot"):
                        screenshot_path, screenshot_error = self._capture_screenshot(
                            scenario_id=scenario["id"],
                            step_index=index,
                            step_id=step["id"],
                        )
                    if functional_result != "pass":
                        debug.update(self._collect_debug_state())
                    steps.append(StepTrace(
                        index=index, id=step["id"], action=action, expected=expected,
                        actual=actual or "ok", result=result, duration_ms=duration_ms,
                        screenshot=screenshot_path, screenshot_error=screenshot_error,
                        phase="main", debug=debug,
                    ))
                    if functional_result != "pass":
                        break

        finally:
            # ── Phase 3: Cleanup — always, best-effort ──────────────────
            cleanup_offset = len(preconditions) + len(main_steps)
            for index, step in enumerate(cleanup_steps, start=1 + cleanup_offset):
                step_started = time.monotonic()
                action = step["action"]
                expected = step.get("expected", action)
                actual = ""
                result = "pass"
                try:
                    actual, _ = self._execute_step(step)
                    self._apply_expectations(step)
                    result = "pass"
                except Exception as exc:
                    actual = f"Cleanup error (best-effort): {exc}"
                    result = "error"
                duration_ms = int((time.monotonic() - step_started) * 1000)
                steps.append(StepTrace(
                    index=index, id=step["id"], action=action, expected=expected,
                    actual=actual or "ok", result=result, duration_ms=duration_ms,
                    phase="cleanup",
                ))

            scenario_lines = logcat_capture.stop(current_pid)
            self._append_scenario_logcat(scenario["id"], scenario_lines)

        duration_seconds = round(time.monotonic() - started, 2)
        ux_result, ux_warnings = self._evaluate_ux(
            functional_result=functional_result,
            step_count=len([s for s in steps if s.phase == "main"]),
            tap_count=tap_count,
            settings_hops=settings_hops,
            back_presses=back_presses,
            duration_seconds=duration_seconds,
            manual_intervention_required=manual_intervention_required,
        )
        artifacts = {
            "screenshots": [trace.screenshot for trace in steps if trace.screenshot],
            "logcat": relpath(self.logcat_path, self.run_dir),
            "raw_json": "result.json",
            "summary": "summary.md",
            "evidence": "evidence.json",
        }
        merged_fixtures = merge_fixtures(scenario)
        return ScenarioResult(
            schema_version=SCHEMA_VERSION,
            source="on_device",
            suite=SUITE,
            scenario_id=scenario["id"],
            scenario_title=scenario["title"],
            timestamp=scenario_timestamp,
            repo=REPO,
            branch=self.branch,
            commit=self.commit,
            pr=self.pr,
            device=self.device,
            functional_result=functional_result,
            ux_result=ux_result,
            step_count=len(steps),
            tap_count=tap_count,
            settings_hops=settings_hops,
            back_presses=back_presses,
            duration_seconds=duration_seconds,
            manual_intervention_required=manual_intervention_required,
            steps=steps,
            artifacts=artifacts,
            ux_warnings=ux_warnings,
            blocked_reason=blocked_reason,
            fixtures_used=merged_fixtures,
        )

    def _execute_step(self, step: dict[str, Any]) -> tuple[str, dict[str, int | bool]]:
        action = step["action"]
        if action == "set_permission_state":
            permissions = [step["permission"], *step.get("also_apply", [])]
            state = step["state"]
            for permission in permissions:
                self._set_permission_state(permission, state)
            return f"Permissions set to {state}: {', '.join(permissions)}", {}
        if action == "launch_main":
            self.wake_and_home()
            self.adb.shell(f"am start -W -n {APP_PACKAGE}/{MAIN_ACTIVITY}", timeout=45)
            self._wait_for_package(APP_PACKAGE, timeout_seconds=20)
            return "MainActivity launched", {"current_pid": self._current_pid() or ""}
        if action == "launch_quick_action":
            self.wake_and_home()
            query = step["query"]
            encoded = uri_encode(query)
            command = (
                f"am start -W -S -n {APP_PACKAGE}/{MAIN_ACTIVITY} "
                f"--es quick_action_input_encoded {encoded} --ez quick_action_is_voice false"
            )
            self.adb.shell(command, timeout=45)
            self._wait_for_package(APP_PACKAGE, timeout_seconds=20)
            return f"Quick action launched: {query}", {"current_pid": self._current_pid() or ""}
        if action == "tap_visible":
            node = self._find_target(step["target"], timeout_seconds=step.get("timeout_seconds", 8))
            center = node.center
            if center is None:
                raise StepFailure(f"Visible target has no tappable bounds: {step['target']}")
            x, y = center
            self.adb.shell(f"input tap {x} {y}", timeout=10)
            time.sleep(0.8)
            deltas = {"tap_count": 1}
            if self._current_package().startswith(SETTINGS_PACKAGE_PREFIX):
                deltas["settings_hops"] = 1
            return f"Tapped target {describe_target(step['target'])}", deltas
        if action == "tap_toggle_for_text":
            return self._tap_toggle_for_text(step["anchor_text"], timeout_seconds=float(step.get("timeout_seconds", 8)))
        if action == "set_toggle_state":
            return self._set_toggle_state_for_text(
                step["anchor_text"],
                bool(step["checked"]),
                timeout_seconds=float(step.get("timeout_seconds", 8)),
            )
        if action == "check_default_assistant_ready":
            return self._check_default_assistant_ready(), {}

        if action == "press_home":
            self.adb.shell("input keyevent KEYCODE_HOME", timeout=10)
            time.sleep(0.5)
            return "Pressed HOME", {}
        if action == "press_back":
            self.adb.shell("input keyevent KEYCODE_BACK", timeout=10)
            time.sleep(0.5)
            return "Pressed BACK", {"back_presses": 1}
        raise StepFailure(f"Unsupported action: {action}")

    def _apply_expectations(self, step: dict[str, Any]) -> None:
        timeout_seconds = float(step.get("timeout_seconds", 8))
        blocked_marker = step.get("blocked_if_visible")
        if blocked_marker:
            texts = blocked_marker.get("texts", [])
            if texts and self._wait_for_any_text(texts, timeout_seconds=1.0):
                raise ScenarioBlocked(blocked_marker.get("reason", f"Blocked by visible prerequisite: {texts!r}"))
        for text in step.get("expected_visible", []):
            if not self._wait_for_text(text, timeout_seconds=timeout_seconds):
                raise StepFailure(f"Expected text not visible: {text}")
        any_visible = step.get("expected_any_visible", [])
        if any_visible and not self._wait_for_any_text(any_visible, timeout_seconds=timeout_seconds):
            raise StepFailure(f"Expected one of {any_visible!r} to be visible")
        toggle_expectation = step.get("expected_toggle_state")
        if toggle_expectation:
            anchor = self._find_target({"text": toggle_expectation["anchor_text"]}, timeout_seconds=timeout_seconds)
            switch = self._find_switch_for_anchor(anchor, timeout_seconds=timeout_seconds)
            if switch.checked is not bool(toggle_expectation["checked"]):
                raise StepFailure(
                    f"Expected toggle for {toggle_expectation['anchor_text']!r} to be {toggle_expectation['checked']}; saw {switch.checked}"
                )
        for text in step.get("expected_not_visible", []):
            if self._is_text_visible(text, timeout_seconds=1.0):
                raise StepFailure(f"Unexpected text visible: {text}")

    def _set_permission_state(self, permission: str, state: str) -> None:
        if state == "granted":
            self.adb.shell(f"pm grant {APP_PACKAGE} {permission}")
            self.adb.shell(
                f"pm clear-permission-flags {APP_PACKAGE} {permission} user-set user-fixed",
                check=False,
            )
            return
        if state == "revoked":
            self.adb.shell(f"pm revoke {APP_PACKAGE} {permission}", check=False)
            self.adb.shell(
                f"pm clear-permission-flags {APP_PACKAGE} {permission} user-set user-fixed",
                check=False,
            )
            return
        if state == "prompt":
            self.adb.shell(f"pm revoke {APP_PACKAGE} {permission}", check=False)
            self.adb.shell(
                f"pm clear-permission-flags {APP_PACKAGE} {permission} user-set user-fixed",
                check=False,
            )
            return
        if state == "blocked":
            self.adb.shell(f"pm revoke {APP_PACKAGE} {permission}", check=False)
            self.adb.shell(
                f"pm set-permission-flags {APP_PACKAGE} {permission} user-set user-fixed",
                check=False,
            )
            return
        raise StepFailure(f"Unsupported permission state: {state}")

    def _check_default_assistant_ready(self) -> str:
        configured, detail = self._detect_default_assistant_state()
        if configured:
            return f"Default assistant ready: {detail}"
        raise ScenarioBlocked(
            "Jandal is not configured as the Android default assistant; configure it manually before running Hey Jandal voice scenarios. "
            f"Detection: {detail}"
        )

    def _detect_default_assistant_state(self) -> tuple[bool, str]:
        role_holders = self.adb.shell("cmd role get-role-holders android.app.role.ASSISTANT", timeout=20, check=False)
        voice_interaction = self.adb.shell("settings get secure voice_interaction_service", timeout=20, check=False)
        assistant_setting = self.adb.shell("settings get secure assistant", timeout=20, check=False)
        details = [
            f"role_holders={role_holders or '<empty>'}",
            f"voice_interaction_service={voice_interaction or '<empty>'}",
            f"assistant={assistant_setting or '<empty>'}",
        ]
        package_match = any(APP_PACKAGE in value for value in (role_holders, voice_interaction, assistant_setting) if value)
        foreign_holder = any(value and value.lower() not in {"null", "none"} for value in (role_holders, voice_interaction, assistant_setting))
        if package_match:
            return True, "; ".join(details)
        if foreign_holder:
            return False, "; ".join(details)
        return False, "Could not deterministically detect the assistant role. " + "; ".join(details)

    def _tap_toggle_for_text(self, anchor_text: str, timeout_seconds: float) -> tuple[str, dict[str, int | bool]]:
        anchor = self._find_target({"text": anchor_text}, timeout_seconds=timeout_seconds)
        switch = self._find_switch_for_anchor(anchor, timeout_seconds=timeout_seconds)
        center = switch.center
        if center is None:
            raise StepFailure(f"Toggle for {anchor_text!r} has no tappable bounds")
        x, y = center
        before = switch.checked
        self.adb.shell(f"input tap {x} {y}", timeout=10)
        time.sleep(0.8)
        return f"Tapped toggle for {anchor_text} (before={before})", {"tap_count": 1}

    def _set_toggle_state_for_text(self, anchor_text: str, checked: bool, timeout_seconds: float) -> tuple[str, dict[str, int | bool]]:
        anchor = self._find_target({"text": anchor_text}, timeout_seconds=timeout_seconds)
        switch = self._find_switch_for_anchor(anchor, timeout_seconds=timeout_seconds)
        if switch.checked is checked:
            return f"Toggle for {anchor_text} already {'on' if checked else 'off'}", {}
        center = switch.center
        if center is None:
            raise StepFailure(f"Toggle for {anchor_text!r} has no tappable bounds")
        x, y = center
        self.adb.shell(f"input tap {x} {y}", timeout=10)
        time.sleep(0.8)
        anchor_after = self._find_target({"text": anchor_text}, timeout_seconds=timeout_seconds)
        switch_after = self._find_switch_for_anchor(anchor_after, timeout_seconds=timeout_seconds)
        if switch_after.checked is not checked:
            raise StepFailure(
                f"Toggle for {anchor_text!r} did not reach expected state {checked}; saw {switch_after.checked}"
            )
        return f"Set toggle for {anchor_text} to {'on' if checked else 'off'}", {"tap_count": 1}

    def _find_switch_for_anchor(self, anchor: UiNode, timeout_seconds: float) -> UiNode:
        anchor_center = anchor.center
        if anchor_center is None:
            raise StepFailure(f"Anchor text has no bounds: {anchor.text!r}")
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            nodes = self.ui.dump_nodes()
            candidates = [
                node
                for node in nodes
                if node.center is not None
                and node.enabled
                and (
                    node.class_name.endswith("Switch")
                    or (node.checked is not None and node.clickable)
                )
            ]
            if candidates:
                candidates.sort(
                    key=lambda node: (
                        abs((node.center or (0, 0))[1] - anchor_center[1]),
                        abs((node.center or (0, 0))[0] - anchor_center[0]),
                    )
                )
                best = candidates[0]
                if best.center and abs(best.center[1] - anchor_center[1]) <= 140:
                    return best
            time.sleep(0.3)
        raise StepFailure(f"Could not find toggle associated with {anchor.text!r}")

    def _find_target(self, target: dict[str, Any], timeout_seconds: float) -> UiNode:
        deadline = time.monotonic() + timeout_seconds
        last_nodes: list[UiNode] = []
        while time.monotonic() < deadline:
            nodes = self.ui.dump_nodes()
            last_nodes = nodes
            if match := find_matching_node(nodes, target):
                return match
            time.sleep(0.5)
        raise StepFailure(
            f"Target not visible within {timeout_seconds}s: {describe_target(target)}; "
            f"visible sample={visible_sample(last_nodes)}"
        )

    def _wait_for_text(self, text: str, timeout_seconds: float) -> bool:
        return self._wait_for_any_text([text], timeout_seconds=timeout_seconds, exact=True)

    def _wait_for_any_text(self, texts: Iterable[str], timeout_seconds: float, exact: bool = True) -> bool:
        wanted = list(texts)
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            nodes = self.ui.dump_nodes()
            for node in nodes:
                haystacks = [node.text, node.content_desc]
                for needle in wanted:
                    if exact:
                        if needle in haystacks:
                            return True
                    elif any(needle in hay for hay in haystacks if hay):
                        return True
            time.sleep(0.5)
        return False

    def _is_text_visible(self, text: str, timeout_seconds: float) -> bool:
        return self._wait_for_any_text([text], timeout_seconds=timeout_seconds, exact=True)

    def _wait_for_package(self, package_name: str, timeout_seconds: float) -> None:
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            current = self._current_package()
            if current == package_name:
                return
            time.sleep(0.5)
        raise StepFailure(f"Expected package {package_name} in foreground; saw {self._current_package()!r}")

    def _current_package(self) -> str:
        output = self.adb.shell("dumpsys activity activities", timeout=20, check=False)
        for pattern in (
            r"topResumedActivity=.*? ([^/\s]+)/",
            r"mResumedActivity:.*? ([^/\s]+)/",
        ):
            match = re.search(pattern, output)
            if match:
                return match.group(1)
        window = self.adb.shell("dumpsys window windows", timeout=20, check=False)
        match = re.search(r"mCurrentFocus=.*? ([^/\s]+)/", window)
        if match:
            return match.group(1)
        return ""

    def _current_pid(self) -> str | None:
        raw = self.adb.shell(f"pidof {APP_PACKAGE}", timeout=10, check=False).strip()
        if not raw:
            return None
        return raw.split()[0]

    def _collect_debug_state(self) -> dict[str, Any]:
        focused_window = self.adb.shell("dumpsys window windows", timeout=20, check=False)
        resumed_activity = self.adb.shell("dumpsys activity activities", timeout=20, check=False)
        return {
            "current_package": self._current_package(),
            "focused_window": first_matching_line(focused_window, ["mCurrentFocus", "mFocusedApp"]),
            "resumed_activity": first_matching_line(resumed_activity, ["topResumedActivity", "mResumedActivity", "ResumedActivity"]),
        }

    def _capture_screenshot(self, scenario_id: str, step_index: int, step_id: str) -> tuple[str | None, str | None]:
        filename = f"{step_index:02d}-{slugify(scenario_id)}-{slugify(step_id)}.png"
        destination = self.screenshots_dir / filename
        try:
            png = self.adb.exec_out("screencap", "-p", timeout=30)
            destination.write_bytes(png)
            return relpath(destination, self.run_dir), None
        except Exception as exc:
            return None, str(exc)

    def _append_scenario_logcat(self, scenario_id: str, lines: list[str]) -> None:
        with self.logcat_path.open("a", encoding="utf-8") as handle:
            handle.write(f"===== {scenario_id} =====\n")
            if lines:
                handle.write("\n".join(lines))
                handle.write("\n")
            else:
                handle.write("<no captured logcat lines>\n")
            handle.write("\n")

    def _evaluate_ux(
        self,
        *,
        functional_result: str,
        step_count: int,
        tap_count: int,
        settings_hops: int,
        back_presses: int,
        duration_seconds: float,
        manual_intervention_required: bool,
    ) -> tuple[str, list[str]]:
        if functional_result in {"blocked", "skipped"}:
            return "not_assessed", []
        warnings: list[str] = []
        if step_count > self.thresholds["max_steps"]:
            warnings.append(f"steps {step_count} > {self.thresholds['max_steps']}")
        if tap_count > self.thresholds["max_user_taps"]:
            warnings.append(f"taps {tap_count} > {self.thresholds['max_user_taps']}")
        if settings_hops > self.thresholds["max_settings_hops"]:
            warnings.append(f"settings hops {settings_hops} > {self.thresholds['max_settings_hops']}")
        if duration_seconds > self.thresholds["max_duration_seconds"]:
            warnings.append(
                f"duration {duration_seconds:.1f}s > {self.thresholds['max_duration_seconds']}s"
            )
        if back_presses > self.thresholds["max_back_presses"]:
            warnings.append(f"back presses {back_presses} > {self.thresholds['max_back_presses']}")
        if manual_intervention_required and self.thresholds["fail_on_manual_intervention"]:
            warnings.append("manual intervention required")
        if functional_result == "fail":
            return "fail", warnings
        if warnings:
            return "warning", warnings
        return "pass", []


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run deterministic permission scenarios on a physical Android device")
    parser.add_argument("--device-id", required=True, help="Device registry ID from scripts/testdata/devices.yaml")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), help="ADB serial to target")
    parser.add_argument(
        "--scenarios",
        required=True,
        help="Comma-separated scenario IDs, e.g. mic_denied_enable_hey_jandal,weather_location_denied",
    )
    parser.add_argument(
        "--out-dir",
        default=str(HERE / "test-reports" / "permissions"),
        help="Parent directory for timestamped local reports",
    )
    parser.add_argument("--pr", type=int, default=None, help="Optional PR number for report metadata")
    parser.add_argument("--branch", default=None, help="Override git branch metadata")
    parser.add_argument("--commit", default=None, help="Override git commit metadata")
    parser.add_argument("--list-scenarios", action="store_true", help="List available scenarios and exit")
    parser.add_argument("--dry-run", action="store_true", help="Print scenario plan without executing on device")
    return parser.parse_args(argv)
def load_devices() -> dict[str, dict[str, Any]]:
    sys.path.insert(0, str(HERE))
    from summarise_test_report import load_devices as load_devices_impl

    return load_devices_impl()


def resolve_device(device_id: str, serial: str | None) -> dict[str, Any]:
    devices = load_devices()
    if device_id not in devices:
        known = ", ".join(sorted(devices))
        raise RunnerError(f"Unknown --device-id {device_id!r}; valid IDs: {known}")
    entry = dict(devices[device_id])
    return {
        "id": device_id,
        "serial": serial,
        "label": entry.get("label", device_id),
        "manufacturer": entry.get("manufacturer", "Unknown"),
        "model": entry.get("model", "Unknown"),
        "soc": entry.get("soc", "Unknown"),
        "tier": entry.get("tier", "tracked"),
        "android_api": entry.get("android_api"),
        "execution": entry.get("execution", "physical"),
    }


def load_scenarios() -> list[dict[str, Any]]:
    return [dict(scenario) for scenario in SCENARIOS]


def select_scenarios(selected_ids: list[str]) -> list[dict[str, Any]]:
    scenarios = {scenario["id"]: scenario for scenario in load_scenarios()}
    missing = [scenario_id for scenario_id in selected_ids if scenario_id not in scenarios]
    if missing:
        raise RunnerError(f"Unknown scenario IDs: {', '.join(missing)}")
    return [scenarios[scenario_id] for scenario_id in selected_ids]


def get_scenario_by_id(scenario_id: str) -> dict[str, object] | None:
    """Look up a scenario by ID. Returns None if not found."""
    for scenario in load_scenarios():
        if scenario["id"] == scenario_id:
            return scenario
    return None


def validate_scenario_definitions(scenarios: list[dict[str, object]]) -> list[str]:
    """Validate all scenario definitions. Returns list of error messages."""
    errors: list[str] = []
    seen_ids: set[str] = set()
    for scenario in scenarios:
        scenario_errors = _validate_scenario(scenario, seen_ids)
        errors.extend(scenario_errors)
        scenario_id = str(scenario.get("id", "<unknown>"))
        seen_ids.add(scenario_id)
    return errors


def _validate_scenario(scenario: dict[str, object], seen_ids: set[str]) -> list[str]:
    errors: list[str] = []
    scenario_id = str(scenario.get("id", "<unknown>"))

    # Check required fields
    for field in REQUIRED_SCENARIO_FIELDS:
        if field not in scenario:
            errors.append(f"Scenario {scenario_id!r}: missing required field {field!r}")

    # Check for duplicate IDs
    if scenario_id in seen_ids:
        errors.append(f"Duplicate scenario ID: {scenario_id!r}")

    # Check tags is a list
    tags = scenario.get("tags")
    if tags is not None and not isinstance(tags, list):
        errors.append(f"Scenario {scenario_id!r}: tags must be a list, got {type(tags).__name__}")

    # Validate main steps
    steps = scenario.get("steps", [])
    if not isinstance(steps, list):
        errors.append(f"Scenario {scenario_id!r}: steps must be a list")
        return errors

    seen_step_ids: set[str] = set()
    for step_idx, step in enumerate(steps):
        step_id = str(step.get("id", f"<step {step_idx}>"))
        step_errors = _validate_step(step, step_idx, scenario_id, seen_step_ids)
        errors.extend(step_errors)
        seen_step_ids.add(step_id)

    # Validate optional blocks (preconditions, cleanup) if present
    for block_name in ("preconditions", "cleanup"):
        block = scenario.get(block_name, [])
        if block and not isinstance(block, list):
            errors.append(f"Scenario {scenario_id!r}: {block_name} must be a list")
        elif block:
            seen_block_ids: set[str] = set()
            for block_idx, block_step in enumerate(block):
                step_id = str(block_step.get("id", f"<{block_name} {block_idx}>"))
                step_errors = _validate_step(block_step, block_idx, f"{scenario_id}/{block_name}", seen_block_ids)
                errors.extend(step_errors)
                seen_block_ids.add(step_id)

    # Validate fixtures if present
    fixtures = scenario.get("fixtures")
    if fixtures is not None and not isinstance(fixtures, dict):
        errors.append(f"Scenario {scenario_id!r}: fixtures must be a dict")

    return errors


def _validate_step(step: dict[str, object], step_idx: int, parent_id: str, seen_step_ids: set[str]) -> list[str]:
    errors: list[str] = []
    step_id = str(step.get("id", f"<step {step_idx}>"))
    label = f"{parent_id}/step {step_id!r}"

    # Check required step fields
    for field in REQUIRED_STEP_FIELDS:
        if field not in step:
            errors.append(f"{label}: missing required field {field!r}")

    # Duplicate step ID within same parent
    if step_id in seen_step_ids:
        errors.append(f"{label}: duplicate step ID within same block")

    # Validate action
    action = step.get("action", "")
    if action and action not in SUPPORTED_ACTIONS:
        errors.append(f"{label}: unsupported action {action!r}; supported: {sorted(SUPPORTED_ACTIONS)}")

    # Validate action-specific fields
    if action == "set_permission_state":
        perm = step.get("permission", "")
        state = step.get("state", "")
        if not perm:
            errors.append(f"{label}: action {action!r} requires 'permission' field")
        if not state:
            errors.append(f"{label}: action {action!r} requires 'state' field")
        elif state not in SUPPORTED_PERMISSION_STATES:
            errors.append(f"{label}: unsupported permission state {state!r}; supported: {sorted(SUPPORTED_PERMISSION_STATES)}")
    elif action == "tap_visible" and "target" not in step:
        errors.append(f"{label}: action {action!r} requires 'target' field")
    elif action == "tap_toggle_for_text" and "anchor_text" not in step:
        errors.append(f"{label}: action {action!r} requires 'anchor_text' field")
    elif action == "set_toggle_state":
        if "anchor_text" not in step:
            errors.append(f"{label}: action {action!r} requires 'anchor_text' field")
        if "checked" not in step:
            errors.append(f"{label}: action {action!r} requires 'checked' field")
    elif action == "launch_quick_action" and "query" not in step:
        errors.append(f"{label}: action {action!r} requires 'query' field")

    return errors

def merge_fixtures(scenario: dict[str, object]) -> dict[str, object]:
    """Merge global FIXTURES with per-scenario overrides."""
    merged = dict(FIXTURES)
    merged.update(scenario.get("fixtures", {}) or {})
    return merged



def detect_git_metadata(branch_override: str | None, commit_override: str | None) -> tuple[str | None, str | None]:
    branch = branch_override or git_output("git branch --show-current")
    commit = commit_override or git_output("git rev-parse HEAD")
    return branch or None, commit or None


def git_output(command: str) -> str | None:
    try:
        result = subprocess.run(
            shlex.split(command),
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=10,
            check=True,
        )
    except Exception:
        return None
    return result.stdout.strip() or None


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def build_run_result(
    *,
    timestamp: str,
    run_id: str,
    branch: str | None,
    commit: str | None,
    pr: int | None,
    device: dict[str, Any],
    thresholds: dict[str, Any],
    scenarios: list[ScenarioResult],
) -> RunResult:
    summary = {
        "total": len(scenarios),
        "functional": count_by(s.functional_result for s in scenarios),
        "ux": count_by(s.ux_result for s in scenarios),
        "example_report": "summary.md",
    }
    return RunResult(
        schema_version=SCHEMA_VERSION,
        source="on_device",
        suite=SUITE,
        timestamp=timestamp,
        repo=REPO,
        branch=branch,
        commit=commit,
        pr=pr,
        run_id=run_id,
        device=device,
        thresholds=thresholds,
        summary=summary,
        scenarios=scenarios,
        artifacts={
            "raw_json": "result.json",
            "summary": "summary.md",
            "logcat": "logcat.txt",
            "evidence": "evidence.json",
            "screenshots_dir": "screenshots",
        },
    )


def to_evidence(run_result: RunResult) -> dict[str, Any]:
    cases = []
    for scenario in run_result.scenarios:
        if scenario.functional_result not in {"pass", "fail"}:
            continue
        failures: list[str] = []
        if scenario.functional_result != "pass":
            failures.extend(
                step.actual for step in scenario.steps if step.result in {"fail", "blocked", "skipped"}
            )
        cases.append(
            {
                "name": scenario.scenario_id,
                "passed": scenario.functional_result == "pass",
                "expected_tool": None,
                "actual_tool": None,
                "expected_result_mode": "success",
                "actual_result_mode": "success" if scenario.functional_result == "pass" else "unknown",
                "chip_present": False,
                "skill_result_present": False,
                "message_saved": False,
                "retry_seen": False,
                "slot_fill_seen": False,
                "failure_category": map_failure_category(scenario.functional_result),
                "failures": failures,
            }
        )
    passed = sum(1 for case in cases if case["passed"])
    failed = len(cases) - passed
    evidence_device = dict(run_result.device)
    evidence_device["serial"] = None
    return {
        "schema_version": SCHEMA_VERSION,
        "source": "on_device",
        "suite": SUITE,
        "timestamp": run_result.timestamp,
        "repo": REPO,
        "branch": run_result.branch,
        "commit": run_result.commit,
        "pr": run_result.pr,
        "release": None,
        "run_id": run_result.run_id,
        "device": evidence_device,
        "model": dict(NON_INFERENCE_MODEL),
        "summary": {
            "total": len(cases),
            "passed": passed,
            "failed": failed,
            "pass_rate": round((passed / len(cases)) if cases else 0.0, 3),
        },
        "cases": cases,
    }


def map_failure_category(functional_result: str) -> str | None:
    if functional_result == "pass":
        return None
    if functional_result == "blocked":
        return "device_environment_error"
    return "harness_error"


def write_summary(run_result: RunResult, path: Path) -> str:
    lines = [
        "# Permission scenario evidence",
        "",
        f"**Source:** `{run_result.source}`",
        f"**Suite:** `{run_result.suite}`",
        f"**Device:** {run_result.device['label']} ({run_result.device['manufacturer']}, `{run_result.device['id']}`)",
        f"**Commit:** `{short_sha(run_result.commit)}`" if run_result.commit else "**Commit:** unknown",
        f"**Branch:** `{run_result.branch}`" if run_result.branch else "**Branch:** unknown",
        f"**Run ID:** `{run_result.run_id}`",
        "",
        "| Scenario | Functional | UX | Steps | Taps | Settings hops | Back | Duration |",
        "|---|---|---|---:|---:|---:|---:|---:|",
    ]
    for scenario in run_result.scenarios:
        lines.append(
            "| {title} | {functional} | {ux} | {steps} | {taps} | {settings} | {back} | {duration:.1f}s |".format(
                title=scenario.scenario_title,
                functional=scenario.functional_result,
                ux=scenario.ux_result,
                steps=scenario.step_count,
                taps=scenario.tap_count,
                settings=scenario.settings_hops,
                back=scenario.back_presses,
                duration=scenario.duration_seconds,
            )
        )
    lines.extend([
        "",
        "## Artifacts",
        "",
        f"- Raw report: `{run_result.artifacts['raw_json']}`",
        f"- Schema-compatible evidence: `{run_result.artifacts['evidence']}`",
        f"- Logcat: `{run_result.artifacts['logcat']}`",
        f"- Screenshots: `{run_result.artifacts['screenshots_dir']}/`",
        "",
    ])
    for scenario in run_result.scenarios:
        lines.append(f"## {scenario.scenario_title}")
        lines.append("")
        lines.append(f"- Scenario ID: `{scenario.scenario_id}`")
        lines.append(f"- Functional result: `{scenario.functional_result}`")
        lines.append(f"- UX result: `{scenario.ux_result}`")
        if scenario.blocked_reason:
            lines.append(f"- Blocked reason: {scenario.blocked_reason}")
        if scenario.ux_warnings:
            lines.append(f"- UX warnings: {', '.join(scenario.ux_warnings)}")
        lines.append("- Steps:")
        for step in scenario.steps:
            screenshot = f" (`{step.screenshot}`)" if step.screenshot else ""
            lines.append(
                f"  - {step.index:02d}. `{step.id}` — {step.result}: {step.actual}{screenshot}"
            )
        lines.append("")
    summary = "\n".join(lines).rstrip() + "\n"
    path.write_text(summary, encoding="utf-8")
    return summary


def visible_sample(nodes: list[UiNode], limit: int = 8) -> list[str]:
    sample: list[str] = []
    for node in nodes:
        for value in (node.text, node.content_desc):
            if value and value not in sample:
                sample.append(value)
                if len(sample) >= limit:
                    return sample
    return sample


def find_matching_node(nodes: list[UiNode], target: dict[str, Any]) -> UiNode | None:
    text = target.get("text")
    if text:
        for node in nodes:
            if node.text == text or node.content_desc == text:
                return node
    contains = target.get("text_contains")
    if contains:
        for node in nodes:
            if contains in node.text or contains in node.content_desc:
                return node
    content_desc = target.get("content_desc")
    if content_desc:
        for node in nodes:
            if node.content_desc == content_desc:
                return node
    resource_id = target.get("resource_id")
    if resource_id:
        for node in nodes:
            if node.resource_id == resource_id:
                return node
    any_text = target.get("any_text")
    if any_text:
        for option in any_text:
            for node in nodes:
                if node.text == option or node.content_desc == option:
                    return node
    return None


def describe_target(target: dict[str, Any]) -> str:
    for key in ("text", "text_contains", "content_desc", "resource_id", "any_text"):
        if key in target:
            return f"{key}={target[key]!r}"
    return repr(target)


def first_matching_line(text: str, needles: list[str]) -> str:
    for line in text.splitlines():
        if any(needle in line for needle in needles):
            return line.strip()
    return "<not found>"


def uri_encode(value: str) -> str:
    from urllib.parse import quote

    return quote(value, safe="")


def slugify(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-") or "step"


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def short_sha(sha: str | None) -> str:
    return sha[:7] if sha else "unknown"


def relpath(path: Path, base: Path) -> str:
    return str(path.relative_to(base))


def count_by(values: Iterable[str]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for value in values:
        counts[value] = counts.get(value, 0) + 1
    return counts


def list_scenarios() -> None:
    """Print available scenarios with tags and cleanup info."""
    print(f"{'Scenario ID':<42} {'Capability':<28} {'Tags':<40} Cleanup")
    print("-" * 140)
    for scenario in load_scenarios():
        sid = scenario["id"]
        cap = scenario.get("capability", "")
        tags = ", ".join(scenario.get("tags", []))
        cleanup = "yes" if scenario.get("cleanup") else "-"
        print(f"{sid:<42} {cap:<28} {tags:<40} {cleanup}")
    print()
    print("Use --scenarios SCENARIO_ID(S) to select and execute.")
    print("Use --dry-run to preview without a device.")



def build_dry_run_plan(scenarios: list[dict[str, object]]) -> list[dict[str, object]]:
    """Build a dry-run plan summary without touching a device."""
    plan: list[dict[str, object]] = []
    for scenario in scenarios:
        preconditions = list(scenario.get("preconditions", []))
        steps = list(scenario.get("steps", []))
        cleanup = list(scenario.get("cleanup", []))

        permissions_touched: set[str] = set()
        screenshot_count = 0
        for step in preconditions + steps + cleanup:
            if step.get("action") == "set_permission_state":
                permissions_touched.add(str(step.get("permission", "")))
                for extra in step.get("also_apply", []):
                    permissions_touched.add(str(extra))
            if step.get("screenshot"):
                screenshot_count += 1

        plan.append({
            "id": scenario.get("id"),
            "title": scenario.get("title"),
            "capability": scenario.get("capability"),
            "tags": list(scenario.get("tags", [])),
            "fixtures": merge_fixtures(scenario),
            "precondition_count": len(preconditions),
            "step_count": len(steps),
            "cleanup_count": len(cleanup),
            "permissions_touched": sorted(permissions_touched),
            "screenshot_count": screenshot_count,
        })
    return plan


def print_dry_run_plan(plan: list[dict[str, object]]) -> None:
    """Print a human-readable dry-run plan."""
    print("=" * 60)
    print("PERMISSION SCENARIO RUNNER — DRY RUN")
    print("=" * 60)
    print()
    total_screenshots = 0
    total_permissions: set[str] = set()
    for entry in plan:
        print(f"--- {entry['id']}: {entry['title']} ---")
        print(f"  Capability: {entry['capability']}")
        print(f"  Tags:       {', '.join(entry['tags'])}")
        print(f"  Fixtures:   {entry['fixtures']}")
        print(f"  Preconditions: {entry['precondition_count']} step(s)")
        print(f"  Main steps:    {entry['step_count']} step(s)")
        print(f"  Cleanup:       {entry['cleanup_count']} step(s)")
        if entry["permissions_touched"]:
            print(f"  Permissions touched: {', '.join(entry['permissions_touched'])}")
        if entry["screenshot_count"]:
            print(f"  Screenshots captured: {entry['screenshot_count']}")
        total_screenshots += entry["screenshot_count"]
        total_permissions.update(entry["permissions_touched"])
        print()
    print("-- Summary --")
    print(f"  Scenarios: {len(plan)}")
    print(f"  Total screenshots: {total_screenshots}")
    if total_permissions:
        print(f"  All permissions touched: {', '.join(sorted(total_permissions))}")
    print(f"  Device: NOT TOUCHED (dry-run)")

def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.list_scenarios:
        list_scenarios()
        return 0
    selected_ids = [item.strip() for item in args.scenarios.split(",") if item.strip()]
    if not selected_ids:
        raise RunnerError("At least one scenario ID is required")

    # Validate ALL scenario definitions, not just selected ones.
    # This ensures broken scenarios don't sit unnoticed in the repo.
    all_validation_errors = validate_scenario_definitions(load_scenarios())
    if all_validation_errors:
        print("Scenario definition validation FAILED:", file=sys.stderr)
        for error in all_validation_errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    scenarios = select_scenarios(selected_ids)

    if args.dry_run:
        plan = build_dry_run_plan(scenarios)
        print_dry_run_plan(plan)
        return 0

    adb = AdbClient(args.serial)
    adb.ensure_device_available()
    device = resolve_device(args.device_id, args.serial)
    branch, commit = detect_git_metadata(args.branch, args.commit)
    timestamp = now_iso()
    timestamp_path = timestamp.replace(":", "-")
    run_dir = Path(args.out_dir) / timestamp_path
    run_dir.mkdir(parents=True, exist_ok=True)
    runner = ScenarioRunner(
        adb=adb,
        device=device,
        branch=branch,
        commit=commit,
        pr=args.pr,
        run_dir=run_dir,
        thresholds=dict(DEFAULT_UX_THRESHOLDS),
    )
    scenarios = [runner.run_scenario(scenario) for scenario in scenarios]
    run_id = f"on_device-{timestamp_path}-{args.device_id}"
    run_result = build_run_result(
        timestamp=timestamp,
        run_id=run_id,
        branch=branch,
        commit=commit,
        pr=args.pr,
        device=device,
        thresholds=dict(DEFAULT_UX_THRESHOLDS),
        scenarios=scenarios,
    )
    write_json(run_dir / "result.json", asdict(run_result))
    write_json(run_dir / "evidence.json", to_evidence(run_result))
    write_summary(run_result, run_dir / "summary.md")
    print(f"Permission scenario report: {run_dir}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DeviceUnavailable as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
    except RunnerError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
