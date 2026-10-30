#!/usr/bin/env python3
"""Contract tests for matched device-test APK provenance."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

import create_device_test_apk_manifest as manifest  # noqa: E402


class ApkMetadataTests(unittest.TestCase):
    def test_parses_package_and_version_metadata(self) -> None:
        self.assertEqual(
            manifest.parse_badging(
                "package: name='com.kernel.ai.debug' versionCode='1000003518' versionName='0.1.0'\n"
            ),
            ("com.kernel.ai.debug", 1_000_003_518, "0.1.0"),
        )

    def test_rejects_badging_without_package_record(self) -> None:
        with self.assertRaisesRegex(ValueError, "no package"):
            manifest.parse_badging("application-label:'Jandal'\n")

    def test_normalizes_v1_and_v2_signer_fingerprints(self) -> None:
        for prefix in ("Signer #1 ", "V2 Signer: "):
            with self.subTest(prefix=prefix):
                self.assertEqual(
                    manifest.parse_signer_certificates(
                        prefix
                        + "certificate SHA-256 digest: "
                        + ":".join(["AB"] * 32)
                    ),
                    ("ab" * 32,),
                )

    def test_rejects_signer_fingerprint_that_is_not_sha256(self) -> None:
        with self.assertRaisesRegex(ValueError, "no valid signer"):
            manifest.parse_signer_certificates("Signer #1 certificate SHA-256 digest: ab:cd")


class PairValidationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.certificate = ("a1" * 32,)
        self.app = (manifest.APP_PACKAGE, 1_000_003_518, "0.1.0", self.certificate)
        self.test = (manifest.TEST_PACKAGE, 1_000_003_518, "0.1.0-test", self.certificate)

    def test_accepts_matched_monotonic_pair(self) -> None:
        manifest.validate_pair(self.app, self.test, 1_000_003_518)

    def test_rejects_inconsistent_app_and_test_version_codes(self) -> None:
        with self.assertRaisesRegex(ValueError, "versionCodes"):
            manifest.validate_pair(self.app, (*self.test[:1], 3518, *self.test[2:]), 1_000_003_518)

    def test_rejects_different_signers(self) -> None:
        mismatched_test = (*self.test[:3], ("b2" * 32,))
        with self.assertRaisesRegex(ValueError, "signer certificate sets differ"):
            manifest.validate_pair(self.app, mismatched_test, 1_000_003_518)

    def test_device_version_code_is_monotonic_and_bounded(self) -> None:
        self.assertEqual(manifest.expected_device_test_version_code(3518), 1_000_003_518)
        with self.assertRaisesRegex(ValueError, "maximum"):
            manifest.expected_device_test_version_code(1_100_000_000)


if __name__ == "__main__":
    unittest.main()
