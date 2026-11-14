#!/usr/bin/env python3
"""Package and host-verify the pinned four-ABI Inflect Sherpa JNI outputs."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

BASE_AAR_SHA256 = "233b6b19fb5515c047adebde0dbf873a9fd8ac23f1d2ff6a3701f7ffc923b23c"
JNI_SYMBOL = "Java_com_kernel_ai_core_voice_InflectPhonemizer_nativePhonemize"
ABI_BUILD_DIRS = {
    "arm64-v8a": "build-android-arm64-v8a",
    "armeabi-v7a": "build-android-armv7-eabi",
    "x86": "build-android-x86",
    "x86_64": "build-android-x86-64",
}
ELF_MACHINES = {
    "arm64-v8a": "AArch64",
    "armeabi-v7a": "ARM",
    "x86": "Intel 80386",
    "x86_64": "Advanced Micro Devices X86-64",
}
JNI_NAME = "libsherpa-onnx-jni.so"
NATIVE_NAMES = ("libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so", JNI_NAME)
ANDROID_SYSTEM_LIBS = {
    "libandroid.so",
    "liblog.so",
    "libm.so",
    "libdl.so",
    "libc.so",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run_tool(executable: Path, *args: str) -> str:
    result = subprocess.run(
        [str(executable), *args], check=True, capture_output=True, text=True
    )
    return result.stdout


def replacement_map(build_root: Path, nm: Path, readelf: Path) -> dict[str, bytes]:
    replacements: dict[str, bytes] = {}
    expected_needed = ANDROID_SYSTEM_LIBS | {"libonnxruntime.so"}
    reference_needed: set[str] | None = None
    for abi, build_dir in ABI_BUILD_DIRS.items():
        library = build_root / build_dir / "install/lib" / JNI_NAME
        if not library.is_file() or library.stat().st_size == 0:
            raise ValueError(f"missing non-empty build output: {library}")
        payload = library.read_bytes()
        if payload[:4] != b"\x7fELF":
            raise ValueError(f"not an ELF shared object: {library}")

        symbols = run_tool(nm, "-D", "--defined-only", str(library))
        if not re.search(rf"\b{re.escape(JNI_SYMBOL)}\b", symbols):
            raise ValueError(f"{abi} JNI output does not export {JNI_SYMBOL}")

        header = run_tool(readelf, "-h", str(library))
        machine = next(
            (line.split(":", 1)[1].strip() for line in header.splitlines() if "Machine:" in line),
            None,
        )
        if machine != ELF_MACHINES[abi]:
            raise ValueError(
                f"{abi} output has ELF machine {machine!r}; expected {ELF_MACHINES[abi]!r}"
            )
        dynamic = run_tool(readelf, "-d", str(library))
        needed = set(re.findall(r"Shared library: \[([^\]]+)\]", dynamic))
        if needed != expected_needed:
            raise ValueError(
                f"{abi} JNI DT_NEEDED set is {sorted(needed)!r}; "
                f"expected only platform libraries plus libonnxruntime.so"
            )
        if reference_needed is None:
            reference_needed = needed
        elif needed != reference_needed:
            raise ValueError(f"{abi} JNI dependencies differ from the other ABI builds")


        entry = f"jni/{abi}/{JNI_NAME}"
        replacements[entry] = payload
        print(f"{abi} ELF={machine}; {JNI_SYMBOL}; SHA-256={sha256(payload)}")
        print(f"{abi} DT_NEEDED={','.join(sorted(needed))}")
    return replacements


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-aar", type=Path, required=True)
    parser.add_argument("--build-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--llvm-nm", type=Path, required=True)
    parser.add_argument("--llvm-readelf", type=Path, required=True)
    args = parser.parse_args()

    if args.output.exists():
        raise SystemExit(f"refusing to overwrite existing output: {args.output}")
    if not args.base_aar.is_file():
        raise SystemExit(f"base AAR not found: {args.base_aar}")
    if sha256(args.base_aar.read_bytes()) != BASE_AAR_SHA256:
        raise SystemExit("stock base AAR SHA-256 mismatch")

    replacements = replacement_map(args.build_root, args.llvm_nm, args.llvm_readelf)
    output_entries: dict[str, bytes] = {}
    with zipfile.ZipFile(args.base_aar) as base:
        names = [info.filename for info in base.infolist()]
        if len(names) != len(set(names)):
            raise SystemExit("base AAR contains duplicate ZIP entry names")
        signatures = [
            name for name in names
            if name.upper().startswith("META-INF/")
            and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC", ".SIG"))
        ]
        if signatures:
            raise SystemExit(f"base AAR has signature metadata that repackaging would invalidate: {signatures}")
        for abi in ABI_BUILD_DIRS:
            for name in NATIVE_NAMES:
                entry = f"jni/{abi}/{name}"
                if entry not in names:
                    raise SystemExit(f"base AAR lacks required native entry: {entry}")
        for info in base.infolist():
            if info.is_dir():
                output_entries[info.filename] = b""
            else:
                output_entries[info.filename] = base.read(info.filename)

    if not set(replacements).issubset(output_entries):
        raise SystemExit("replacement JNI entries do not match the base AAR layout")
    output_entries.update(replacements)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    try:
        with os.fdopen(fd, "w+b") as raw, zipfile.ZipFile(
            raw, "w", compression=zipfile.ZIP_STORED
        ) as archive:
            for name in sorted(output_entries):
                is_dir = name.endswith("/")
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (0o40755 if is_dir else 0o100644) << 16
                info.compress_type = zipfile.ZIP_STORED
                archive.writestr(info, output_entries[name])
    except BaseException:
        args.output.unlink(missing_ok=True)
        raise

    with zipfile.ZipFile(args.output) as result:
        if result.testzip() is not None:
            args.output.unlink(missing_ok=True)
            raise SystemExit("generated AAR failed ZIP CRC validation")
        for name, expected in output_entries.items():
            if result.read(name) != expected:
                args.output.unlink(missing_ok=True)
                raise SystemExit(f"generated AAR entry mismatch: {name}")

    digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
    print(f"Output AAR SHA-256={digest}")
    print("Verified: 4 ABI JNI entries replaced; all other base AAR entries preserved byte-for-byte.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
