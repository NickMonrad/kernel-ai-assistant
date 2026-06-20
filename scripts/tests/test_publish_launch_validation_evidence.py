#!/usr/bin/env python3
"""Tests for publish_launch_validation_evidence.py.

Covers the ``--dry-run`` flag: verifies it prints the plan and exits before
running the normaliser, copying raw JSON, generating Markdown, or publishing.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
REPO_DIR = SCRIPT_DIR.parent
FIXTURE = SCRIPT_DIR / "testdata" / "fixtures" / "llm_tools_sample_raw.json"
LAUNCH_SCRIPT = SCRIPT_DIR / "publish_launch_validation_evidence.py"


class DryRunFlagTest(unittest.TestCase):
    """Verify that ``--dry-run`` prints the plan and exits without side effects."""

    def setUp(self) -> None:
        self._tmpdir = tempfile.mkdtemp(prefix="dryrun_test_")
        self._tmp = Path(self._tmpdir)

    def tearDown(self) -> None:
        # Clean up temp dir
        import shutil
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def _run_dry_run(self, extra_args: list[str] | None = None) -> subprocess.CompletedProcess:
        """Run the launch validation script with ``--dry-run`` and return the result."""
        cmd = [
            sys.executable, str(LAUNCH_SCRIPT),
            "--dry-run",
            "--input", str(FIXTURE),
            "--serial", "R5CR605B71K",
            "--out-dir", self._tmpdir,
            "--commit", "abcdef1234567890abcdef1234567890abcdef12",
            "--branch", "test-branch",
            "--pr", "9999",
        ]
        if extra_args:
            cmd.extend(extra_args)
        return subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            cwd=str(REPO_DIR),
        )

    def test_dry_run_returns_zero(self) -> None:
        """``--dry-run`` should exit 0."""
        result = self._run_dry_run()
        self.assertEqual(result.returncode, 0, msg=result.stderr)

    def test_dry_run_prints_plan(self) -> None:
        """``--dry-run`` should print the dry-run plan."""
        result = self._run_dry_run()
        self.assertIn("[DRY RUN]", result.stdout)
        self.assertIn("SKIP: --dry-run flag set", result.stdout)

    def test_dry_run_does_not_create_evidence_files(self) -> None:
        """``--dry-run`` should not create any evidence output files."""
        self._run_dry_run()
        # No evidence JSON
        ev_jsons = list(self._tmp.glob("*_evidence.json"))
        self.assertEqual(ev_jsons, [], f"Unexpected evidence JSON: {ev_jsons}")
        # No CSV
        csvs = list(self._tmp.glob("*_cases.csv"))
        self.assertEqual(csvs, [], f"Unexpected CSV: {csvs}")
        # No summary MD
        summaries = list(self._tmp.glob("*_summary.md"))
        self.assertEqual(summaries, [], f"Unexpected summary: {summaries}")
        # No launch validation summary
        lv_md = self._tmp / "launch-validation-summary.md"
        self.assertFalse(lv_md.exists(), "launch-validation-summary.md should not exist")
        # No raw/ subdirectory
        raw_dir = self._tmp / "raw"
        self.assertFalse(raw_dir.exists(), "raw/ subdirectory should not exist")

    def test_dry_run_does_not_fail_on_unknown_suite(self) -> None:
        """``--dry-run`` should work even with warnings (unknown suite type)."""
        # Create a fixture with an unknown suite
        import json
        weird = self._tmp / "weird_suite.json"
        weird.write_text(json.dumps({"suite": "unknown_suite", "results": [], "status": "passed"}))
        cmd = [
            sys.executable, str(LAUNCH_SCRIPT),
            "--dry-run",
            "--input", str(weird),
            "--serial", "R5CR605B71K",
            "--out-dir", self._tmp,
            "--commit", "abcdef1234567890abcdef1234567890abcdef12",
            "--branch", "test-branch",
            "--pr", "9999",
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, cwd=str(REPO_DIR))
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("SKIP: --dry-run flag set", result.stdout)


if __name__ == "__main__":
    unittest.main()

class NonDryRunTest(unittest.TestCase):
    """Verify that non-dry-run execution reaches the normalisation and publish paths."""

    def setUp(self) -> None:
        self._tmpdir = tempfile.mkdtemp(prefix="non_dryrun_test_")
        self._tmp = Path(self._tmpdir)

    def tearDown(self) -> None:
        import shutil
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    @staticmethod
    def _make_minimal_fixture(path: Path) -> None:
        """Write a minimal valid harness raw JSON to *path*."""
        import json
        data = {
            "suite": "skills",
            "device_id": "s21-exynos",
            "serial": "R5CR605B71K",
            "model_name": "Gemma 4 E-2B",
            "model_runtime": "LiteRT",
            "model_backend": "GPU",
            "timestamp": "2026-06-18T12:00:00",
            "status": "completed",
            "results": [
                {"name": "test_alarm", "status": "pass", "phase": "alarm_timer"},
            ],
            "summary": {"total": 1, "passed": 1, "failed": 0,
                         "skipped": 0, "xfail": 0},
        }
        path.write_text(json.dumps(data))

    def test_non_dry_run_reaches_normaliser(self) -> None:
        """Without ``--dry-run`` the normalisation subprocess should be invoked."""
        import json
        from unittest import mock

        fixture = self._tmp / "fixture.json"
        self._make_minimal_fixture(fixture)

        out_dir = self._tmp / "out"

        normaliser_cmds: list[list[str]] = []
        publisher_cmds: list[list[str]] = []

        def mock_run(cmd, *args, **kwargs):               # type: ignore
            cmd_str = " ".join(str(p) for p in cmd)
            if "normalise_skills_report" in cmd_str:
                normaliser_cmds.append(cmd)
                # Create the expected output files so the script can continue
                idx = cmd.index("--out-dir")
                cmd_out = Path(cmd[idx + 1])
                (cmd_out / "mock_skills_evidence.json").write_text(json.dumps({
                    "timestamp": "2026-06-18T12:00:00",
                    "source": "test",
                    "schema_version": "1.0",
                    "suite": "skills",
                    "device": {
                        "id": "s21-exynos",
                        "label": "Samsung Galaxy S21 (Exynos)",
                        "manufacturer": "Samsung",
                        "model": "SM-G991B",
                        "soc": "Exynos 2100",
                        "tier": "tracked",
                        "android_api": 35,
                        "execution": "physical",
                        "serial": "R5CR605B71K",
                    },
                    "model": {
                        "name": "Gemma 4 E-2B",
                        "runtime": "LiteRT",
                        "backend": "GPU",
                    },
                    "summary": {
                        "total": 1, "passed": 1, "failed": 0,
                        "pass_rate": 1.0,
                    },
                    "cases": [{
                        "name": "test_alarm",
                        "passed": True,
                        "phase": "alarm_timer",
                        "expected_tool": "alarm_set",
                        "actual_tool": "alarm_set",
                        "expected_result_mode": "success",
                        "actual_result_mode": "success",
                        "chip_present": True,
                        "skill_result_present": True,
                        "message_saved": True,
                        "retry_seen": False,
                        "slot_fill_seen": False,
                        "failure_category": None,
                        "failures": [],
                    }],
                }))
                (cmd_out / "mock_skills_cases.csv").write_text(
                    "case,status\ntest_alarm,pass\n"
                )
                (cmd_out / "mock_skills_summary.md").write_text(
                    "# Summary\n\nAll passed."
                )
                return subprocess.CompletedProcess(
                    args=cmd, returncode=0, stdout=b"", stderr=b""
                )
            elif "publish_test_evidence" in cmd_str:
                publisher_cmds.append(cmd)
                return subprocess.CompletedProcess(
                    args=cmd, returncode=0, stdout=b"", stderr=b""
                )
            # any other subprocess (git, etc.) -> pretend success
            return subprocess.CompletedProcess(
                args=cmd, returncode=0, stdout=b"", stderr=b""
            )

        old_argv = list(sys.argv)
        old_path = list(sys.path)
        try:
            sys.argv = [
                str(LAUNCH_SCRIPT),
                "--input", str(fixture),
                "--out-dir", str(out_dir),
                "--device-id", "s21-exynos",
                "--model-name", "Gemma 4 E-2B",
                "--model-runtime", "LiteRT",
                "--model-backend", "GPU",
                "--commit", "abc123def456",
                "--branch", "test-branch",
                "--serial", "R5CR605B71K",
                "--pr", "9999",
            ]

            import importlib.util
            spec = importlib.util.spec_from_file_location(
                "_test_launch_module", str(LAUNCH_SCRIPT),
            )
            mod = importlib.util.module_from_spec(spec)

            with mock.patch.object(subprocess, "run", side_effect=mock_run):
                spec.loader.exec_module(mod)
                mod.main()
        finally:
            sys.argv = old_argv
            sys.path = old_path

        self.assertEqual(
            len(normaliser_cmds), 1,
            "Normalisation should be called exactly once (dry-run=False)",
        )
        self.assertIn(
            "normalise_skills_report.py",
            " ".join(str(p) for p in normaliser_cmds[0]),
            "Normalisation command should reference the normaliser script",
        )
        self.assertEqual(
            len(publisher_cmds), 1,
            "Publisher should be called exactly once (dry-run=False)",
        )
