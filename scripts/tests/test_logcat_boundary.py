#!/usr/bin/env python3
"""Tests for ``_filter_lines_after_boundary`` — the logcat boundary-filtering
helper that prevents stale ring-buffer markers from contaminating harness
test results.

Coverage:
- boundary present, stale lines before it → only post-boundary lines returned
- multiple boundary markers → last boundary wins
- boundary at end of text, no following lines → empty string
- boundary present, no trailing newline → empty string
- boundary absent → ``__BOUNDARY_NOT_FOUND__`` diagnostic prefix
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from adb_harness.device import _filter_lines_after_boundary

BOUNDARY = "__ADB_HARNESS_BOUNDARY_test__"

STALE_LINE = "D/KernelAI(12345): NativeIntentHandler.handle: intent=get_time"
FRESH_LINE = "D/KernelAI(12345): ActionsViewModel: NeedsSlot for \"set an alarm\""

TEXT_WITH_STALE = (
    f"line before the warmup\n"
    f"{STALE_LINE}\n"
    f"{BOUNDARY}\n"
    f"{FRESH_LINE}\n"
)

TEXT_MULTIPLE_BOUNDARIES = (
    f"{STALE_LINE}\n"
    f"{BOUNDARY}\n"
    f"middle line that will be dropped\n"
    f"{BOUNDARY}\n"
    f"{FRESH_LINE}\n"
)


class BoundaryPresentFiltersStaleTest(unittest.TestCase):
    """Stale lines before the boundary are discarded."""

    def test_stale_lines_dropped(self) -> None:
        result = _filter_lines_after_boundary(TEXT_WITH_STALE, BOUNDARY)
        self.assertIn(FRESH_LINE, result)
        self.assertNotIn(STALE_LINE, result)

    def test_fresh_lines_kept(self) -> None:
        result = _filter_lines_after_boundary(TEXT_WITH_STALE, BOUNDARY)
        self.assertEqual(result.rstrip("\n"), FRESH_LINE)


class MultipleBoundariesUsesLastTest(unittest.TestCase):
    """When multiple boundary markers exist, content after the last one is kept."""

    def test_last_boundary_wins(self) -> None:
        result = _filter_lines_after_boundary(
            TEXT_MULTIPLE_BOUNDARIES, BOUNDARY
        )
        self.assertIn(FRESH_LINE, result)
        self.assertNotIn(STALE_LINE, result)
        self.assertNotIn("middle line that will be dropped", result)


class BoundaryAtEndReturnsEmptyTest(unittest.TestCase):
    """Boundary as the final text yields an empty string."""

    def test_boundary_at_end_no_newline(self) -> None:
        result = _filter_lines_after_boundary(f"{BOUNDARY}", BOUNDARY)
        self.assertEqual(result, "")

    def test_boundary_with_newline_no_following(self) -> None:
        result = _filter_lines_after_boundary(f"{BOUNDARY}\n", BOUNDARY)
        self.assertEqual(result, "")

    def test_stale_lines_then_boundary_no_following(self) -> None:
        text = f"{STALE_LINE}\n{BOUNDARY}"
        result = _filter_lines_after_boundary(text, BOUNDARY)
        self.assertEqual(result, "")


class BoundaryAbsentReturnsDiagnosticTest(unittest.TestCase):
    """Missing boundary prepends ``__BOUNDARY_NOT_FOUND__``."""

    def test_no_boundary_diagnostic_prefixed(self) -> None:
        text = f"{STALE_LINE}\n"
        result = _filter_lines_after_boundary(text, BOUNDARY)
        self.assertTrue(result.startswith("__BOUNDARY_NOT_FOUND__"))
        self.assertIn(STALE_LINE, result)

    def test_no_boundary_on_empty_text(self) -> None:
        result = _filter_lines_after_boundary("", BOUNDARY)
        self.assertEqual(result, "__BOUNDARY_NOT_FOUND__")


if __name__ == "__main__":
    unittest.main()
