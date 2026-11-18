#!/usr/bin/env python3
"""Package the STT-only (TTS=OFF) four-ABI Sherpa JNI into a deterministic AAR (#1793).

Starts from the stock no-ORT AAR, replaces the four JNI entries with the TTS=OFF builds,
and DROPS the stock c-api/cxx-api entries (they are TTS-enabled stock wrappers and are not
rebuilt here). Verifies every native output for absence of TTS/eSpeak/Piper symbols and
strings, ELF machine, DT_NEEDED, and the JNI export.
"""

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
JNI_SYMBOL = "Java_com_k2fsa_sherpa_onnx_OnlineRecognizer_newFromFile"
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
DROPPED_NAMES = ("libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so")
ANDROID_SYSTEM_LIBS = {"libandroid.so", "liblog.so", "libm.so", "libdl.so", "libc.so"}
FORBIDDEN_RE = re.compile(rb"\b(eSpeak-ng|espeak-ng|espeak_|espeakng|libespeak|piper|Piper|OfflineTts|InitEspeak|CallPhonemizeEspeak|Inflect|phonemize_)")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run_tool(executable: Path, *args: str) -> str:
    return subprocess.run([str(executable), *args], check=True, capture_output=True, text=True).stdout


def replacement_map(build_root: Path, nm: Path, readelf: Path) -> dict[str, bytes]:
    replacements: dict[str, bytes] = {}
    expected_needed = ANDROID_SYSTEM_LIBS | {"libonnxruntime.so"}
    for abi, build_dir in ABI_BUILD_DIRS.items():
        library = build_root / build_dir / "install/lib" / JNI_NAME
        if not library.is_file() or library.stat().st_size == 0:
            raise ValueError(f"missing build output: {library}")
        payload = library.read_bytes()
        if payload[:4] != b"\x7fELF":
            raise ValueError(f"not ELF: {library}")
        symbols = run_tool(nm, "-D", "--defined-only", str(library))
        if not re.search(r"\bNewFromFile\b|Java_com_k2fsa_sherpa_onnx_", symbols):
            raise ValueError(f"{abi} JNI exports no sherpa JNI symbols")
        for forbidden in ("espeak-ng", "espeak_", "piper", "offlinetts", "inflect", "phonemize_"):
            if forbidden in symbols.lower():
                raise ValueError(f"{abi} JNI exports forbidden symbol family {forbidden!r}")
        if FORBIDDEN_RE.search(payload):
            raise ValueError(f"{abi} JNI contains forbidden TTS/eSpeak/Piper string content")
        header = run_tool(readelf, "-h", str(library))
        machine = next((l.split(":", 1)[1].strip() for l in header.splitlines() if "Machine:" in l), None)
        if machine != ELF_MACHINES[abi]:
            raise ValueError(f"{abi} ELF machine {machine!r}")
        dynamic = run_tool(readelf, "-d", str(library))
        needed = set(re.findall(r"Shared library: \[([^\]]+)\]", dynamic))
        if needed != expected_needed:
            raise ValueError(f"{abi} DT_NEEDED {sorted(needed)!r} != {sorted(expected_needed)!r}")
        replacements[f"jni/{abi}/{JNI_NAME}"] = payload
        print(f"{abi} ELF={machine}; SHA-256={sha256(payload)}; DT_NEEDED={','.join(sorted(needed))}")
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
        raise SystemExit(f"refusing to overwrite: {args.output}")
    if sha256(args.base_aar.read_bytes()) != BASE_AAR_SHA256:
        raise SystemExit("stock base AAR SHA-256 mismatch")

    replacements = replacement_map(args.build_root, args.llvm_nm, args.llvm_readelf)
    with zipfile.ZipFile(args.base_aar) as base:
        names = [i.filename for i in base.infolist()]
        if len(names) != len(set(names)):
            raise SystemExit("duplicate ZIP entries in base AAR")
        if any(n.upper().startswith("META-INF/") and n.upper().endswith((".SF", ".RSA", ".DSA", ".EC", ".SIG")) for n in names):
            raise SystemExit("base AAR has signature metadata")
        output_entries: dict[str, bytes] = {}
        for info in base.infolist():
            if any(info.filename == f"jni/{abi}/{d}" for abi in ABI_BUILD_DIRS for d in DROPPED_NAMES):
                continue
            output_entries[info.filename] = b"" if info.is_dir() else base.read(info.filename)
    for abi in ABI_BUILD_DIRS:
        if f"jni/{abi}/{JNI_NAME}" not in output_entries:
            raise SystemExit(f"base AAR lacks {abi}/{JNI_NAME}")
    output_entries.update(replacements)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    try:
        with os.fdopen(fd, "w+b") as raw, zipfile.ZipFile(raw, "w", compression=zipfile.ZIP_STORED) as archive:
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
            raise SystemExit("ZIP CRC failure")
        for name, expected in output_entries.items():
            if result.read(name) != expected:
                args.output.unlink(missing_ok=True)
                raise SystemExit(f"entry mismatch: {name}")
        remaining_native = sorted(n for n in result.namelist() if n.endswith(".so"))
    print(f"Native entries: {remaining_native}")
    print(f"Output AAR SHA-256={sha256(args.output.read_bytes())}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
