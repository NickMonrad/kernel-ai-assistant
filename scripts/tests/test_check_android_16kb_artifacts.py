#!/usr/bin/env python3
"""Contract tests for the Android 16 KB artifact checker."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

import check_android_16kb_artifacts as checker  # noqa: E402


class ProgramHeaderTests(unittest.TestCase):
    def test_accepts_16kb_loads_and_relro(self) -> None:
        report = """\
Program Headers:
  Type           Offset   VirtAddr           PhysAddr           FileSiz  MemSiz   Flg Align
  LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x001000 0x001000 R   0x4000
  LOAD           0x001000 0x0000000000005000 0x0000000000005000 0x001000 0x001000 R E 0x4000
  GNU_RELRO      0x001000 0x0000000000007000 0x0000000000007000 0x001000 0x001000 R   0x1
"""
        loads, relro = checker.parse_program_headers(report)
        self.assertEqual([load.alignment for load in loads], [0x4000, 0x4000])
        self.assertEqual(checker.alignment_issues(loads, relro), [])

    def test_rejects_4kb_load(self) -> None:
        report = "LOAD 0x000000 0x000000 0x000000 0x001000 0x001000 R E 0x1000\n"
        loads, relro = checker.parse_program_headers(report)
        self.assertEqual(checker.alignment_issues(loads, relro), [
            "PT_LOAD p_align=0x1000 is below 0x4000",
        ])

    def test_rejects_misaligned_relro_sharing_writable_load(self) -> None:
        report = """\
LOAD 0x000000 0x00005000 0x00005000 0x003000 0x003000 RW 0x4000
GNU_RELRO 0x001000 0x00005000 0x00005000 0x001000 0x00001000 R 0x1
"""
        loads, relro = checker.parse_program_headers(report)
        self.assertIn(
            "GNU_RELRO end 0x6000 shares a LOAD page with writable data before 0x8000",
            checker.alignment_issues(loads, relro),
        )

    def test_accepts_own_relro_load_before_next_writable_page(self) -> None:
        report = """\
LOAD 0x000000 0x00005000 0x00005000 0x001000 0x001000 RW 0x4000
GNU_RELRO 0x001000 0x00005000 0x00005000 0x001000 0x001000 R 0x1
LOAD 0x004000 0x00009000 0x00009000 0x001000 0x001000 RW 0x4000
"""
        loads, relro = checker.parse_program_headers(report)
        self.assertEqual(checker.alignment_issues(loads, relro), [])

    def test_accepts_relro_covering_own_load_before_writable_page(self) -> None:
        report = """\
LOAD 0x001c40 0x00005c40 0x00005c40 0x000390 0x000390 RW 0x4000
GNU_RELRO 0x001c40 0x00005c40 0x00005c40 0x000390 0x0003c0 R 0x1
LOAD 0x001fd0 0x00009fd0 0x00009fd0 0x000000 0x000010 RW 0x4000
"""
        loads, relro = checker.parse_program_headers(report)
        self.assertEqual(checker.alignment_issues(loads, relro), [])

    def test_rejects_report_without_load_segments(self) -> None:
        with self.assertRaisesRegex(ValueError, "no PT_LOAD"):
            checker.parse_program_headers("There are no program headers in this file.\n")


class BundleConfigTests(unittest.TestCase):
    def test_requires_16kb_page_alignment(self) -> None:
        config = {
            "optimizations": {
                "uncompressNativeLibraries": {
                    "enabled": True,
                    "alignment": "PAGE_ALIGNMENT_16K",
                },
            },
        }
        self.assertEqual(checker.bundle_alignment_issues(config), [])

    def test_rejects_4kb_page_alignment(self) -> None:
        config = {
            "optimizations": {
                "uncompressNativeLibraries": {
                    "enabled": True,
                    "alignment": "PAGE_ALIGNMENT_4K",
                },
            },
        }
        self.assertEqual(checker.bundle_alignment_issues(config), [
            "AAB native-library alignment is not PAGE_ALIGNMENT_16K",
        ])


if __name__ == "__main__":
    unittest.main()
