#!/usr/bin/env python3
"""Run the two-account #1548 Nextcloud sharing acceptance on already-installed APKs.

This runner never installs APKs, reads application data outside the acceptance test, exports account
credentials, saves instrumentation output, or prints a server name or account identifier. The
recipient username is entered through a masked prompt and is sent only as an instrumentation extra so
the device test can verify account roles and address the intended sharee.
"""
from __future__ import annotations

import argparse
import base64
import getpass
import re
import secrets
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

APP_ID = "com.kernel.ai.debug"
TEST_ID = f"{APP_ID}.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "com.kernel.ai.nextcloudacceptance.NextcloudSharingAcceptanceTest"
TEST_METHOD = "runRequestedAcceptanceStep"
OWNER_MODEL = "SM-G991B"
RECIPIENT_MODEL = "SM-S918B"
NAME_RE = re.compile(r"^J1548-[0-9a-f]{16}$")
HASH_RE = re.compile(r"^[0-9a-f]{40}$")


class HarnessFailure(RuntimeError):
    pass


@dataclass(frozen=True)
class Device:
    alias: str
    serial: str
    model: str


FIRST_SCENARIO = (
    ("owner", "owner-create"),
    ("owner", "owner-publish"),
    ("owner", "owner-share-create"),
    ("recipient", "recipient-import"),
    ("recipient", "recipient-add-editable"),
    ("owner", "owner-verify-recipient-write"),
    ("owner", "owner-set-readonly"),
    ("recipient", "recipient-refresh-readonly"),
    ("owner", "owner-add-owner-update"),
    ("recipient", "recipient-pull-owner-update"),
    ("owner", "owner-set-editable"),
    ("recipient", "recipient-refresh-editable"),
    ("recipient", "recipient-stop-sync"),
    ("owner", "owner-set-readonly"),
    ("recipient", "recipient-stale-keep"),
    ("recipient", "recipient-keep-stale"),
    ("recipient", "recipient-proactive-copy"),
    ("owner", "owner-set-editable"),
    ("recipient", "recipient-refresh-after-keep"),
    ("owner", "owner-verify-stale-keep-absent"),
    ("recipient", "recipient-stop-sync"),
    ("owner", "owner-set-readonly"),
    ("recipient", "recipient-stale-discard"),
    ("recipient", "recipient-discard-stale"),
    ("owner", "owner-set-editable"),
    ("recipient", "recipient-refresh-after-discard"),
    ("owner", "owner-verify-stale-discard-absent"),
    ("owner", "owner-remove-share"),
    ("recipient", "recipient-resolve-removed-no-work"),
    ("owner", "owner-share-create"),
    ("recipient", "recipient-local-copy-no-reassociate"),
    ("owner", "owner-verify-local-only-absent"),
)

REMOVED_STALE_SCENARIO = (
    ("owner", "owner-create"),
    ("owner", "owner-publish"),
    ("owner", "owner-share-create"),
    ("recipient", "recipient-import"),
    ("recipient", "recipient-stop-sync"),
    ("owner", "owner-remove-share"),
    ("recipient", "recipient-stale-after-removal"),
    ("recipient", "recipient-keep-removed-stale"),
    ("owner", "owner-share-create"),
    ("recipient", "recipient-unbound-after-reshare"),
    ("owner", "owner-verify-removed-work-absent"),
)


def run_checked(args: Sequence[str], *, cwd: Path | None = None, timeout: int = 40) -> str:
    try:
        result = subprocess.run(
            list(args),
            cwd=cwd,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise HarnessFailure("A required local or device command did not complete.") from error
    if result.returncode != 0:
        raise HarnessFailure("A required local or device command failed.")
    return result.stdout.strip()


def verify_checkout(repository: Path, approved_head: str) -> None:
    if not HASH_RE.fullmatch(approved_head):
        raise HarnessFailure("--approved-head must be a full lowercase commit SHA.")
    head = run_checked(["git", "rev-parse", "HEAD"], cwd=repository)
    if head != approved_head:
        raise HarnessFailure("The checked-out commit is not the reviewer-approved head.")
    if run_checked(["git", "status", "--porcelain"], cwd=repository):
        raise HarnessFailure("The acceptance checkout must be clean.")


def verify_device(device: Device, expected_version_code: int) -> None:
    adb = ["adb", "-s", device.serial]
    if run_checked([*adb, "get-serialno"]) != device.serial:
        raise HarnessFailure(f"{device.alias}: connected serial mismatch.")
    if run_checked([*adb, "shell", "getprop", "ro.product.model"]) != device.model:
        raise HarnessFailure(f"{device.alias}: device model mismatch.")
    if not run_checked([*adb, "shell", "pm", "path", APP_ID]).startswith("package:"):
        raise HarnessFailure(f"{device.alias}: expected app package is not installed.")
    if not run_checked([*adb, "shell", "pm", "path", TEST_ID]).startswith("package:"):
        raise HarnessFailure(f"{device.alias}: matching acceptance test package is not installed.")
    package_dump = run_checked([*adb, "shell", "dumpsys", "package", APP_ID])
    match = re.search(r"\bversionCode=(\d+)\b", package_dump)
    if match is None or int(match.group(1)) != expected_version_code:
        raise HarnessFailure(f"{device.alias}: installed app version does not match the candidate.")
    try:
        running_pids = run_checked([*adb, "shell", "pidof", APP_ID])
    except HarnessFailure as error:
        raise HarnessFailure(f"{device.alias}: open the configured app and keep it running before acceptance.") from error
    if not re.fullmatch(r"\d+(?:\s+\d+)*", running_pids):
        raise HarnessFailure(f"{device.alias}: open the configured app and keep it running before acceptance.")


def invoke_step(
    device: Device,
    step: str,
    collection_name: str,
    recipient_username: str,
    approved_head: str,
    expected_version_code: int,
) -> None:
    # Re-check identity immediately before every test action, including cleanup.
    verify_device(device, expected_version_code)
    adb = ["adb", "-s", device.serial, "shell", "am", "instrument", "-w", "-r"]
    command = [
        *adb,
        "-e", "class", f"{TEST_CLASS}#{TEST_METHOD}",
        "-e", "nextcloud_acceptance", "true",
        "-e", "step", step,
        "-e", "collection_name", collection_name,
        "-e", "recipient_username_b64",
        base64.urlsafe_b64encode(recipient_username.encode("utf-8")).decode("ascii"),
        "-e", "reviewed_head", approved_head,
        f"{TEST_ID}/{RUNNER}",
    ]
    try:
        result = subprocess.run(
            command,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=150,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise HarnessFailure(f"{device.alias}/{step}: instrumentation did not complete.") from error
    if result.returncode != 0 or "INSTRUMENTATION_CODE: -1" not in result.stdout or "OK (1 test)" not in result.stdout:
        raise HarnessFailure(f"{device.alias}/{step}: acceptance assertion failed; raw output was suppressed.")
    print(f"[PASS] {device.alias} {step}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owner-serial", required=True, help="ADB serial of the owner-account S21 (SM-G991B).")
    parser.add_argument("--recipient-serial", required=True, help="ADB serial of the sharee-account S23 Ultra (SM-S918B).")
    parser.add_argument("--expected-version-code", required=True, type=int, help="Version code of the reviewed debug APK.")
    parser.add_argument("--approved-head", required=True, help="Full reviewer-approved Git commit SHA, installed on both devices.")
    args = parser.parse_args()

    repository = Path(__file__).resolve().parents[1]
    try:
        verify_checkout(repository, args.approved_head)
        if args.owner_serial == args.recipient_serial:
            raise HarnessFailure("Owner and recipient must be different devices.")
        if args.expected_version_code < 1:
            raise HarnessFailure("Expected version code must be positive.")
        if not sys.stdin.isatty():
            raise HarnessFailure("Run from an interactive terminal to enter the sharee username privately.")
        recipient_username = getpass.getpass("Recipient Nextcloud username (hidden): ").strip()
        if not recipient_username:
            raise HarnessFailure("Recipient username is required.")

        owner = Device("owner/S21", args.owner_serial, OWNER_MODEL)
        recipient = Device("sharee/S23U", args.recipient_serial, RECIPIENT_MODEL)
        names = [f"J1548-{secrets.token_hex(8)}", f"J1548-{secrets.token_hex(8)}"]
        created: list[str] = []
        devices = {"owner": owner, "recipient": recipient}
        failure: HarnessFailure | None = None
        unconfirmed_create: str | None = None

        try:
            for name, steps in zip(names, (FIRST_SCENARIO, REMOVED_STALE_SCENARIO), strict=True):
                for role, step in steps:
                    if step == "owner-create":
                        unconfirmed_create = name
                        print(f"[FIXTURE CANDIDATE] {name}")
                    invoke_step(
                        devices[role], step, name, recipient_username,
                        args.approved_head, args.expected_version_code,
                    )
                    if step == "owner-create":
                        created.append(name)
                        unconfirmed_create = None
        except HarnessFailure as error:
            failure = error
        finally:
            if unconfirmed_create is not None:
                print(
                    f"[CREATE INCOMPLETE] {unconfirmed_create}: owner-create did not return PASS; "
                    "no automatic cleanup was attempted because preflight/creation state is uncertain."
                )
            # Cleanup is limited to names whose local owner-list creation returned successfully.
            for name in created:
                cleanup_failed = False
                for device, step in (
                    (owner, "owner-cleanup"),
                    (recipient, "recipient-cleanup"),
                ):
                    try:
                        invoke_step(
                            device, step, name, recipient_username,
                            args.approved_head, args.expected_version_code,
                        )
                    except HarnessFailure:
                        cleanup_failed = True
                if cleanup_failed:
                    print(f"[CLEANUP INCOMPLETE] {name}: inspect only this generated fixture before retrying cleanup.")
                    if failure is None:
                        failure = HarnessFailure("Generated fixture cleanup did not complete safely.")
                else:
                    print(f"[CLEAN] {name}: generated collections retired; local tombstones retained.")

        if failure is not None:
            raise failure
        print("PASS: both real-account sharing lifecycles completed; generated fixtures cleaned.")
        return 0
    except HarnessFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
