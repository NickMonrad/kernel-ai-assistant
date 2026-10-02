#!/usr/bin/env python3
"""Regression tests for the Nextcloud sharing physical acceptance runner."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

import nextcloud_sharing_acceptance as acceptance  # noqa: E402


class DevicePreflightTest(unittest.TestCase):
    def test_accepts_target_process_stopped_by_instrumentation(self) -> None:
        serial = "test-s21-serial"
        adb = ("adb", "-s", serial)
        device = acceptance.Device("owner/S21", serial, acceptance.OWNER_MODEL)
        expected_version = 42
        responses = {
            (*adb, "get-serialno"): serial,
            (*adb, "shell", "getprop", "ro.product.model"): acceptance.OWNER_MODEL,
            (*adb, "shell", "pm", "path", acceptance.APP_ID): "package:/installed/app.apk",
            (*adb, "shell", "pm", "path", acceptance.TEST_ID): "package:/installed/test.apk",
            (*adb, "shell", "dumpsys", "package", acceptance.APP_ID): "versionCode=42 minSdk=35",
        }

        def run_checked(args: list[str], **_kwargs: object) -> str:
            command = tuple(args)
            if command == (*adb, "shell", "pidof", acceptance.APP_ID):
                return ""
            return responses[command]

        with patch.object(acceptance, "run_checked", side_effect=run_checked):
            acceptance.verify_device(device, expected_version)


if __name__ == "__main__":
    unittest.main()
