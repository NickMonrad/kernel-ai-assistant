#!/usr/bin/env python3
"""Create canonical provenance for a matched debug app/instrumentation APK pair."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

VERSION_CODE_BASE = 1_000_000_000
ANDROID_MAX_VERSION_CODE = 2_100_000_000
APP_PACKAGE = "com.kernel.ai.debug"
TEST_PACKAGE = "com.kernel.ai.debug.test"
SHA_RE = re.compile(r"^[0-9a-fA-F]{40}$")
PACKAGE_RE = re.compile(
    r"^package: name='([^']+)' versionCode='([0-9]+)'(?: versionName='([^']*)')?",
    re.MULTILINE,
)
CERT_RE = re.compile(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)")


def expected_device_test_version_code(
    run_number: int, base: int = VERSION_CODE_BASE
) -> int:
    if run_number <= 0 or base <= 0:
        raise ValueError("run number and versionCode base must be positive")
    version_code = base + run_number
    if version_code >= ANDROID_MAX_VERSION_CODE:
        raise ValueError("device-test versionCode exceeds Android's supported maximum")
    return version_code


def parse_badging(output: str) -> tuple[str, int, str | None]:
    match = PACKAGE_RE.search(output)
    if match is None:
        raise ValueError("aapt output has no package name/versionCode record")
    return match.group(1), int(match.group(2)), match.group(3)


def parse_signer_certificates(output: str) -> tuple[str, ...]:
    certificates = tuple(
        match.replace(":", "").lower()
        for match in CERT_RE.findall(output)
    )
    if not certificates or any(
        re.fullmatch(r"[0-9a-f]{64}", value) is None for value in certificates
    ):
        raise ValueError("apksigner output has no valid signer certificate SHA-256 digest")
    return certificates


def validate_pair(
    app: tuple[str, int, str | None, tuple[str, ...]],
    test: tuple[str, int, str | None, tuple[str, ...]],
    expected_version_code: int,
) -> None:
    if app[0] != APP_PACKAGE:
        raise ValueError(f"app APK package is {app[0]!r}, expected {APP_PACKAGE!r}")
    if test[0] != TEST_PACKAGE:
        raise ValueError(f"test APK package is {test[0]!r}, expected {TEST_PACKAGE!r}")
    if app[1] != expected_version_code or test[1] != expected_version_code:
        raise ValueError(
            "app and instrumentation APK versionCodes must both equal the monotonic "
            f"device-test versionCode {expected_version_code}; got {app[1]} and {test[1]}"
        )
    if app[3] != test[3]:
        raise ValueError("app and instrumentation APK signer certificate sets differ")


def find_android_build_tools(sdk_root: Path) -> tuple[Path, Path]:
    tool_dirs = [path for path in (sdk_root / "build-tools").iterdir() if path.is_dir()]
    tool_dirs.sort(key=lambda path: tuple(int(part) for part in re.findall(r"\d+", path.name)))
    for tool_dir in reversed(tool_dirs):
        aapt = tool_dir / "aapt"
        apksigner = tool_dir / "apksigner"
        if aapt.is_file() and apksigner.is_file():
            return aapt, apksigner
    raise FileNotFoundError(
        f"no build-tools aapt and apksigner found under {sdk_root / 'build-tools'}"
    )


def inspect_apk(apk: Path, aapt: Path, apksigner: Path) -> tuple[str, int, str | None, tuple[str, ...]]:
    if not apk.is_file() or apk.stat().st_size == 0:
        raise FileNotFoundError(f"APK is absent or empty: {apk}")
    badging = subprocess.run(
        [str(aapt), "dump", "badging", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    signer_result = subprocess.run(
        [str(apksigner), "verify", "--print-certs", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    )
    signer_output = signer_result.stdout + "\n" + signer_result.stderr
    package, version_code, version_name = parse_badging(badging)
    return package, version_code, version_name, parse_signer_certificates(signer_output)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_manifest(args: argparse.Namespace) -> dict:
    if SHA_RE.fullmatch(args.source_head_sha) is None or SHA_RE.fullmatch(args.merge_sha) is None:
        raise ValueError("source head and merge SHA must be full 40-character Git SHAs")
    expected_code = expected_device_test_version_code(
        args.run_number, args.version_code_base
    )
    sdk_value = (
        args.sdk_root
        or os.environ.get("ANDROID_HOME")
        or os.environ.get("ANDROID_SDK_ROOT")
    )
    if not sdk_value:
        raise ValueError("pass --sdk-root or set ANDROID_HOME/ANDROID_SDK_ROOT")
    aapt, apksigner = find_android_build_tools(Path(sdk_value))
    app = inspect_apk(args.app_apk, aapt, apksigner)
    test = inspect_apk(args.test_apk, aapt, apksigner)
    validate_pair(app, test, expected_code)

    manifest = {
        "schema_version": 1,
        "repository": args.repository,
        "pull_request": args.pull_request or None,
        "event": args.event,
        "source_head_sha": args.source_head_sha.lower(),
        "merge_sha": args.merge_sha.lower(),
        "run_id": args.run_id,
        "run_number": args.run_number,
        "version_code_policy": (
            f"{args.version_code_base}+run_number (debug/device-test only)"
        ),
        "artifacts": {
            "app": {
                "path": args.app_apk.relative_to(Path.cwd()).as_posix(),
                "package_id": app[0],
                "version_code": app[1],
                "version_name": app[2],
                "signer_certificate_sha256": list(app[3]),
                "sha256": sha256_file(args.app_apk),
            },
            "instrumentation": {
                "path": args.test_apk.relative_to(Path.cwd()).as_posix(),
                "package_id": test[0],
                "version_code": test[1],
                "version_name": test[2],
                "signer_certificate_sha256": list(test[3]),
                "sha256": sha256_file(args.test_apk),
            },
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--pull-request", type=int)
    parser.add_argument("--event", required=True)
    parser.add_argument("--source-head-sha", required=True)
    parser.add_argument("--merge-sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-number", required=True, type=int)
    parser.add_argument("--version-code-base", type=int, default=VERSION_CODE_BASE)
    parser.add_argument("--sdk-root", type=Path)
    parser.add_argument("--app-apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        manifest = write_manifest(args)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.error(str(error))
    print(
        json.dumps(
            {
                "manifest": str(args.output),
                "source_head_sha": manifest["source_head_sha"],
                "merge_sha": manifest["merge_sha"],
            }
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
