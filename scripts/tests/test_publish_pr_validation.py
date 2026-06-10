#!/usr/bin/env python3
"""Tests for PR number validation guardrail in publish_test_evidence.py.

Covers the pure ``_check_pr_mismatches()`` function (no side effects).
``_validate_pr_number()`` is tested via a lightweight wrapper that captures
``sys.exit`` — the exit path is the critical behaviour difference between
``--allow-pr-mismatch`` and its absence.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

# Import the module-under-test via its file path
HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

import publish_test_evidence as pte


class CheckPrMismatchesTest(unittest.TestCase):
    """Tests for the pure _check_pr_mismatches() function."""

    # ── Release mode (cli_pr is None) ──────────────────────────────────────

    def test_release_mode_returns_empty(self) -> None:
        """cli_pr=None (release-scoped) → no mismatches regardless of evidence."""
        self.assertEqual(pte._check_pr_mismatches(None, {1171}, None), [])
        self.assertEqual(pte._check_pr_mismatches(None, set(), 1171), [])
        self.assertEqual(pte._check_pr_mismatches(None, {1154, 1160}, 1171), [])

    # ── No mismatches ──────────────────────────────────────────────────────

    def test_no_evidence_files_ok(self) -> None:
        """No evidence PRs and no detected PR → empty list."""
        self.assertEqual(pte._check_pr_mismatches(1171, set(), None), [])

    def test_all_evidence_match(self) -> None:
        """Single evidence PR matches cli_pr, no detected PR."""
        self.assertEqual(pte._check_pr_mismatches(1171, {1171}, None), [])

    def test_multiple_evidence_all_match(self) -> None:
        """Multiple evidence PRs all matching cli_pr."""
        self.assertEqual(
            pte._check_pr_mismatches(1171, {1171, 1171}, None),
            [],
        )

    def test_detected_pr_matches_no_evidence(self) -> None:
        """detected_pr matches cli_pr, no evidence PRs → ok."""
        self.assertEqual(pte._check_pr_mismatches(1171, set(), 1171), [])

    def test_everything_matches(self) -> None:
        """cli_pr, evidence_prs, and detected_pr all agree."""
        self.assertEqual(
            pte._check_pr_mismatches(1171, {1171, 1171}, 1171),
            [],
        )

    # ── Single mismatch: evidence ──────────────────────────────────────────

    def test_evidence_mismatch(self) -> None:
        """Single evidence PR differs from cli_pr."""
        mismatches = pte._check_pr_mismatches(1171, {1154}, None)
        self.assertEqual(len(mismatches), 1)
        self.assertIn("1154", mismatches[0])
        self.assertIn("1171", mismatches[0])

    def test_evidence_mismatch_with_none_prs(self) -> None:
        """None evidence PRs are also mismatches alongside real mismatches."""
        mismatches = pte._check_pr_mismatches(1171, {None, 1154, None}, None)
        self.assertEqual(len(mismatches), 2, "Should report None mismatch + 1154 mismatch")
        self.assertTrue(any("null/missing" in m and "1171" in m for m in mismatches),
                        f"Expected null evidence mismatch, got: {mismatches}")
        self.assertTrue(any("1154" in m for m in mismatches))

    def test_multiple_evidence_mismatches(self) -> None:
        """Multiple evidence PRs, none matching cli_pr."""
        mismatches = pte._check_pr_mismatches(1171, {1154, 1160}, None)
        self.assertEqual(len(mismatches), 2)
        self.assertIn("1154", mismatches[0])
        self.assertIn("1160", mismatches[1])

    def test_mixed_evidence_match_and_mismatch(self) -> None:
        """Some evidence matches, some doesn't — only mismatches reported."""
        mismatches = pte._check_pr_mismatches(1171, {1171, 1154}, None)
        self.assertEqual(len(mismatches), 1)
        self.assertIn("1154", mismatches[0])

    # ── Single mismatch: detected PR ───────────────────────────────────────

    def test_detected_pr_mismatch_only(self) -> None:
        """detected_pr differs from cli_pr, no evidence PR → mismatch."""
        mismatches = pte._check_pr_mismatches(1171, set(), 1154)
        self.assertEqual(len(mismatches), 1)
        self.assertIn("1171", mismatches[0])
        self.assertIn("1154", mismatches[0])

    # ── Both mismatches ────────────────────────────────────────────────────

    def test_both_evidence_and_detected_mismatch(self) -> None:
        """Both evidence PR and detected PR differ from cli_pr."""
        mismatches = pte._check_pr_mismatches(1171, {1154}, 1160)
        self.assertEqual(len(mismatches), 2)
        self.assertIn("1154", mismatches[0])
    def test_null_evidence_mismatch(self) -> None:
        """None evidence PR with cli_pr set → mismatch reported."""
        mismatches = pte._check_pr_mismatches(1171, {None}, None)
        self.assertEqual(len(mismatches), 1)
        self.assertIn("null/missing", mismatches[0])
        self.assertIn("1171", mismatches[0])

    def test_null_evidence_with_other_matches(self) -> None:
        """Mix of matching and null evidence → only null reported as mismatch."""
        mismatches = pte._check_pr_mismatches(1171, {1171, None, 1171}, None)
        self.assertEqual(len(mismatches), 1)
        self.assertIn("null/missing", mismatches[0])


class ValidatePrNumberTest(unittest.TestCase):
    """Tests for _validate_pr_number() — uses capture to avoid sys.exit.

    We inject a _detect_current_pr_number that returns a known value so the
    gh-dependent path is deterministic.
    """

    def setUp(self) -> None:
        self._orig_detect = pte._detect_current_pr_number

    def tearDown(self) -> None:
        pte._detect_current_pr_number = self._orig_detect
        pte._check_pr_mismatches = pte._check_pr_mismatches  # restore

    # ── Success paths ──────────────────────────────────────────────────────

    def test_release_mode_no_validation(self) -> None:
        """cli_pr=None → returns without checking anything."""
        pte._detect_current_pr_number = lambda: 999  # would mismatch, but unreachable
        pte._validate_pr_number(None, {1171}, allow_mismatch=False)
        # No exception = pass

    def test_no_mismatch_quiet_return(self) -> None:
        """All evidence PRs match → returns without messages."""
        pte._detect_current_pr_number = lambda: None
        pte._validate_pr_number(1171, {1171}, allow_mismatch=False)
        # No exception = pass

    # ── Mismatch → sys.exit ────────────────────────────────────────────────

    def test_evidence_mismatch_exits(self) -> None:
        """Evidence PR differs from --pr; allow_mismatch=False → sys.exit(1)."""
        pte._detect_current_pr_number = lambda: None
        with self.assertRaises(SystemExit) as ctx:
            pte._validate_pr_number(1171, {1154}, allow_mismatch=False)
        self.assertEqual(ctx.exception.code, 1)

    def test_detected_mismatch_exits(self) -> None:
        """detected PR differs from --pr; allow_mismatch=False → sys.exit(1)."""
        pte._detect_current_pr_number = lambda: 1154
        with self.assertRaises(SystemExit) as ctx:
            pte._validate_pr_number(1171, {1171}, allow_mismatch=False)
        self.assertEqual(ctx.exception.code, 1)

    def test_dual_mismatch_exits(self) -> None:
        """Both evidence and detected mismatch → sys.exit(1)."""
        pte._detect_current_pr_number = lambda: 1154
        with self.assertRaises(SystemExit) as ctx:
            pte._validate_pr_number(1171, {1160}, allow_mismatch=False)
        self.assertEqual(ctx.exception.code, 1)
    def test_null_evidence_exits(self) -> None:
        """Null evidence PR with cli_pr; allow_mismatch=False → sys.exit(1)."""
        pte._detect_current_pr_number = lambda: None
        with self.assertRaises(SystemExit) as ctx:
            pte._validate_pr_number(1171, {None}, allow_mismatch=False)
        self.assertEqual(ctx.exception.code, 1)

    # ── Mismatch + --allow-pr-mismatch → warning, no exit ──────────────────

    def test_mismatch_allowed_does_not_exit(self) -> None:
        """allow_mismatch=True → warning printed, no exit."""
        pte._detect_current_pr_number = lambda: 1154
        # Should not raise SystemExit
        pte._validate_pr_number(1171, {1160}, allow_mismatch=True)
        # No exception = pass

    def test_detected_mismatch_allowed_does_not_exit(self) -> None:
        """detected mismatch with allow_mismatch → warning, no exit."""
        pte._detect_current_pr_number = lambda: 1154
        pte._validate_pr_number(1171, set(), allow_mismatch=True)
        # No exception = pass
    def test_null_evidence_allowed_does_not_exit(self) -> None:
        """Null evidence with allow_mismatch → warning, no exit."""
        pte._detect_current_pr_number = lambda: None
        pte._validate_pr_number(1171, {None}, allow_mismatch=True)
        # No exception = pass


if __name__ == "__main__":
    unittest.main()
