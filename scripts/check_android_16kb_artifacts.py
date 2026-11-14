#!/usr/bin/env python3
"""Verify Android 16 KB native ELF, APK ZIP, and App Bundle alignment."""
from __future__ import annotations

import argparse
import json
import platform
import re
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

PAGE_SIZE = 16 * 1024
NATIVE_LIBRARY = re.compile(r"(?:^|/)lib/(arm64-v8a|x86_64)/[^/]+\.so$")


@dataclass(frozen=True)
class LoadSegment:
    alignment: int
    address: int
    memory_size: int
    writable: bool


def parse_program_headers(output: str) -> tuple[list[LoadSegment], list[tuple[int, int]]]:
    """Return PT_LOAD segments and GNU_RELRO (VirtAddr, MemSize) pairs."""
    loads: list[LoadSegment] = []
    relro: list[tuple[int, int]] = []
    for line in output.splitlines():
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "LOAD":
            loads.append(LoadSegment(
                alignment=int(parts[-1], 0),
                address=int(parts[2], 0),
                memory_size=int(parts[5], 0),
                writable=any("W" in flag for flag in parts[-3:-1]),
            ))
        elif parts[0] == "GNU_RELRO" and len(parts) >= 6:
            relro.append((int(parts[2], 0), int(parts[5], 0)))
    if not loads:
        raise ValueError("ELF program headers contain no PT_LOAD segments")
    return loads, relro


def alignment_issues(
    loads: Iterable[LoadSegment],
    relro: Iterable[tuple[int, int]],
) -> list[str]:
    segments = list(loads)
    issues = [
        f"PT_LOAD p_align={segment.alignment:#x} is below {PAGE_SIZE:#x}"
        for segment in segments
        if segment.alignment < PAGE_SIZE
    ]
    for address, size in relro:
        end = address + size
        if end % PAGE_SIZE == 0:
            continue
        page_end = (end + PAGE_SIZE - 1) // PAGE_SIZE * PAGE_SIZE
        own_segment = [
            segment for segment in segments
            if segment.address == address and segment.memory_size <= size
        ]
        writable_overlap = any(
            segment not in own_segment
            and segment.writable
            and segment.address < page_end
            and segment.address + segment.memory_size > end
            for segment in segments
        )
        if not own_segment or writable_overlap:
            issues.append(
                f"GNU_RELRO end {end:#x} shares a LOAD page with writable data "
                f"before {page_end:#x}"
            )
    return issues


def bundle_alignment_issues(config: dict) -> list[str]:
    native = config.get("optimizations", {}).get("uncompressNativeLibraries", {})
    issues = []
    if native.get("enabled") is not True:
        issues.append("AAB does not enable uncompressed native libraries")
    if native.get("alignment") != "PAGE_ALIGNMENT_16K":
        issues.append("AAB native-library alignment is not PAGE_ALIGNMENT_16K")
    return issues


def host_tag() -> str:
    system = platform.system()
    machine = platform.machine().lower()
    if system == "Linux" and machine in {"x86_64", "amd64"}:
        return "linux-x86_64"
    if system == "Darwin" and machine in {"x86_64", "amd64"}:
        return "darwin-x86_64"
    if system == "Darwin" and machine in {"arm64", "aarch64"}:
        return "darwin-arm64"
    raise RuntimeError(f"Unsupported NDK host: {system} {machine}")


def highest_version_directory(parent: Path) -> Path:
    versions = []
    if parent.is_dir():
        for path in parent.iterdir():
            if path.is_dir() and re.fullmatch(r"\d+(?:\.\d+)*", path.name):
                versions.append((tuple(int(part) for part in path.name.split(".")), path))
    if not versions:
        raise FileNotFoundError(f"No installed Android SDK tool directory under {parent}")
    return max(versions, key=lambda item: item[0])[1]


def check_native_archive(
    archive_path: Path,
    readelf: Path,
    *,
    require_native: bool = False,
) -> list[str]:
    failures: list[str] = []
    checked = 0
    with zipfile.ZipFile(archive_path) as archive:
        entries = [
            name for name in archive.namelist()
            if NATIVE_LIBRARY.search(name)
        ]
        if require_native and not entries:
            failures.append(f"{archive_path}: no arm64-v8a or x86_64 shared libraries")
        for name in sorted(entries):
            result = subprocess.run(
                [str(readelf), "-lW", "-"],
                input=archive.read(name),
                capture_output=True,
                check=False,
            )
            if result.returncode:
                detail = result.stderr.decode(errors="replace").strip()
                failures.append(f"{archive_path}:{name}: llvm-readelf failed: {detail}")
                continue
            try:
                loads, relro = parse_program_headers(result.stdout.decode(errors="replace"))
            except (ValueError, IndexError) as error:
                failures.append(f"{archive_path}:{name}: {error}")
                continue
            issues = alignment_issues(loads, relro)
            if issues:
                failures.extend(f"{archive_path}:{name}: {issue}" for issue in issues)
            else:
                checked += 1
                print(f"PASS {archive_path}:{name} PT_LOAD={','.join(f'{v.alignment:#x}' for v in loads)}")
    if not checked and not failures:
        print(f"INFO {archive_path}: no 64-bit native libraries (valid for some test APKs)")
    elif checked:
        print(f"PASS {archive_path}: {checked} 64-bit native libraries checked")
    return failures


def check_apk_zip_alignment(apk: Path, zipalign: Path) -> list[str]:
    result = subprocess.run(
        [str(zipalign), "-c", "-P", "16", "4", str(apk)],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode:
        detail = (result.stdout + result.stderr).strip()
        return [f"{apk}: zipalign -P 16 failed: {detail}"]
    print(f"PASS {apk}: zipalign -P 16")
    return []


def check_bundle_config(bundle: Path, bundletool: Path) -> list[str]:
    command = (
        ["java", "-jar", str(bundletool)]
        if bundletool.suffix == ".jar"
        else [str(bundletool)]
    )
    result = subprocess.run(
        [*command, "dump", "config", f"--bundle={bundle}"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode:
        detail = (result.stdout + result.stderr).strip()
        return [f"{bundle}: bundletool dump config failed: {detail}"]
    try:
        config = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        return [f"{bundle}: bundletool returned invalid JSON: {error}"]
    issues = bundle_alignment_issues(config)
    if issues:
        return [f"{bundle}: {issue}" for issue in issues]
    print(f"PASS {bundle}: PAGE_ALIGNMENT_16K")
    return []


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-root", type=Path, required=True)
    parser.add_argument("--bundletool", type=Path, required=True,
                        help="bundletool executable or bundletool-all JAR")
    parser.add_argument("--apk", type=Path, action="append", default=[],
                        help="APK to check; repeat for debug, release, and test APKs")
    parser.add_argument("--aab", type=Path, required=True,
                        help="release App Bundle to check")
    args = parser.parse_args(argv)

    try:
        ndk = highest_version_directory(args.sdk_root / "ndk")
        build_tools = highest_version_directory(args.sdk_root / "build-tools")
        readelf = ndk / "toolchains" / "llvm" / "prebuilt" / host_tag() / "bin" / "llvm-readelf"
        zipalign = build_tools / ("zipalign.exe" if platform.system() == "Windows" else "zipalign")
        for tool in (readelf, zipalign, args.bundletool, args.aab, *args.apk):
            if not tool.is_file():
                raise FileNotFoundError(f"Required file not found: {tool}")
    except (FileNotFoundError, RuntimeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 2

    failures: list[str] = []
    for apk in args.apk:
        failures.extend(check_apk_zip_alignment(apk, zipalign))
        failures.extend(check_native_archive(apk, readelf))
    failures.extend(check_bundle_config(args.aab, args.bundletool))
    failures.extend(check_native_archive(args.aab, readelf, require_native=True))

    if failures:
        for failure in failures:
            print(f"FAIL {failure}", file=sys.stderr)
        return 1
    print("16 KB artifact checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
