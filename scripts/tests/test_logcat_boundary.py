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

from adb_harness.device import _filter_lines_after_boundary, _ORACLE_PROBES
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
    """Missing boundary returns ``__BOUNDARY_NOT_FOUND__`` without stale text."""

    def test_no_boundary_diagnostic_only(self) -> None:
        """Result is exactly ``__BOUNDARY_NOT_FOUND__``, no stale text appended."""
        text = f"{STALE_LINE}\n"
        result = _filter_lines_after_boundary(text, BOUNDARY)
        self.assertEqual(result, "__BOUNDARY_NOT_FOUND__")

    def test_no_boundary_on_empty_text(self) -> None:
        result = _filter_lines_after_boundary("", BOUNDARY)
        self.assertEqual(result, "__BOUNDARY_NOT_FOUND__")


class BoundaryAbsentExpectedNotMatchedTest(unittest.TestCase):
    """When boundary is absent, ``expected`` marker must not match stale text."""

    def test_expected_not_found_in_stale_when_boundary_absent(self) -> None:
        stale_text = (
            f"some unrelated line\n"
            f"{STALE_LINE}\n"
            f"more noise\n"
        )
        result = _filter_lines_after_boundary(stale_text, BOUNDARY)
        self.assertEqual(result, "__BOUNDARY_NOT_FOUND__")
        # The stale marker must NOT appear in the filtered output
        self.assertNotIn("get_time", result)


class DuplicateLinesAcrossBoundaryTest(unittest.TestCase):
    """Identical lines before and after boundary both preserved after filter."""

    DUP_LINE = "D/KernelAI(12345): SomeRepeatingMarker: intent=xyzzy"

    def test_fresh_copy_preserved_after_filter(self) -> None:
        text = (
            f"{self.DUP_LINE}\n"
            f"{BOUNDARY}\n"
            f"{self.DUP_LINE}\n"
            f"{FRESH_LINE}\n"
        )
        result = _filter_lines_after_boundary(text, BOUNDARY)
        self.assertIn(self.DUP_LINE, result)
        self.assertIn(FRESH_LINE, result)


class OracleProbeNotIdenticalToWarmupTest(unittest.TestCase):
    """Oracle probe text must differ from the common warmup query to avoid dedup."""

    WARMUP_TEXT = "what time is it"
    MAX_PROBES = 10

    def test_probe_list_not_empty(self) -> None:
        self.assertGreater(len(_ORACLE_PROBES), 0)

    def test_each_probe_has_four_elements(self) -> None:
        for i, probe in enumerate(_ORACLE_PROBES):
            with self.subTest(probe_index=i):
                self.assertEqual(len(probe), 4)

    def test_probe_differs_from_warmup_text(self) -> None:
        for i, (label, prompt, intent, marker) in enumerate(_ORACLE_PROBES):
            with self.subTest(probe_index=i, label=label):
                self.assertNotEqual(
                    prompt, self.WARMUP_TEXT,
                    f"Oracle probe {i} ({label!r}) uses warmup text {self.WARMUP_TEXT!r} "
                    f"— app may deduplicate the second identical query. "
                    f"Use different phrasing so the oracle can detect a fresh intent dispatch.",
                )

    def test_probe_expects_native_intent_handler(self) -> None:
        for i, (label, prompt, intent, marker) in enumerate(_ORACLE_PROBES):
            with self.subTest(probe_index=i, label=label):
                self.assertIn(
                    "NativeIntentHandler.handle", marker,
                    f"Oracle probe {i} ({label!r}) expected marker should reference "
                    f"NativeIntentHandler.handle",
                )

    def test_probe_list_not_excessive(self) -> None:
        self.assertLessEqual(len(_ORACLE_PROBES), self.MAX_PROBES)

if __name__ == "__main__":
    unittest.main()
