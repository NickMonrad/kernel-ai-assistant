#!/usr/bin/env python3
"""Regression tests for evidence publisher input compatibility."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from publish_test_evidence import _collect_input_files, _parse_args


class PublishTestEvidenceInputTest(unittest.TestCase):
    def test_single_input_file_matches_cli_option(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp) / "evidence.json"
            evidence.write_text("{}")
            args = _parse_args(
                [
                    "--input",
                    str(evidence),
                    "--source",
                    "ci",
                    "--pr",
                    "1152",
                    "--commit",
                    "2798a1dd0a08f654e6fea0f36d0accb2531fb5ef",
                    "--dry-run",
                ]
            )

            self.assertEqual(_collect_input_files(args), [evidence])


if __name__ == "__main__":
    unittest.main()
