#!/usr/bin/env python3
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
from datetime import datetime, timezone
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
RUNTIME_DIR = Path(os.environ.get("XDG_RUNTIME_DIR", "/tmp")) / "omp-kotlin-lsp"
REPO_KEY = hashlib.sha256(str(REPO_ROOT).encode("utf-8")).hexdigest()[:16]
LOCK_PATH = RUNTIME_DIR / f"{REPO_KEY}.lock"
META_PATH = RUNTIME_DIR / f"{REPO_KEY}.json"
DEFAULT_SERVER_BIN = Path(
    os.environ.get(
        "KOTLIN_LSP_SERVER_BIN",
        str(Path.home() / ".local" / "kotlin-lsp" / "kotlin-server-262.4739.0" / "bin" / "intellij-server"),
    )
)
LOCK_CONFLICT_EXIT = 73


def ensure_runtime_dir() -> None:
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def load_metadata() -> dict[str, Any] | None:
    try:
        return json.loads(META_PATH.read_text())
    except FileNotFoundError:
        return None
    except json.JSONDecodeError:
        return {"invalid": True, "path": str(META_PATH)}


def write_metadata(server_bin: Path, forwarded_args: list[str], lock_fd: int) -> None:
    metadata = {
        "repoRoot": str(REPO_ROOT),
        "repoKey": REPO_KEY,
        "ownerPid": os.getpid(),
        "ownerParentPid": os.getppid(),
        "serverBin": str(server_bin),
        "forwardedArgs": forwarded_args,
        "lockPath": str(LOCK_PATH),
        "metadataPath": str(META_PATH),
        "startedAt": utc_now(),
        "lspmuxPath": shutil.which("lspmux"),
        "notes": [
            "Only one Kotlin LSP owner is allowed per repo.",
            "OMP does not currently multiplex kotlin-lsp across sessions.",
        ],
    }
    META_PATH.write_text(json.dumps(metadata, indent=2) + "\n")
    os.set_inheritable(lock_fd, True)


def pid_alive(pid: int | None) -> bool:
    if pid is None or pid <= 0:
        return False
    return Path(f"/proc/{pid}").exists()


def read_cmdline(pid: int) -> str:
    try:
        raw = Path(f"/proc/{pid}/cmdline").read_bytes()
    except OSError:
        return ""
    return raw.replace(b"\0", b" ").decode("utf-8", errors="replace").strip()


def read_cwd(pid: int) -> str | None:
    try:
        return os.readlink(f"/proc/{pid}/cwd")
    except OSError:
        return None


def list_repo_processes() -> dict[str, list[dict[str, Any]]]:
    omp: list[dict[str, Any]] = []
    intellij: list[dict[str, Any]] = []
    repo_root = str(REPO_ROOT)
    for proc_dir in Path("/proc").iterdir():
        if not proc_dir.name.isdigit():
            continue
        pid = int(proc_dir.name)
        cwd = read_cwd(pid)
        if cwd != repo_root:
            continue
        cmdline = read_cmdline(pid)
        if not cmdline:
            continue
        entry = {"pid": pid, "cwd": cwd, "cmdline": cmdline}
        if "/.local/bin/omp" in cmdline or cmdline.endswith(" omp"):
            omp.append(entry)
        if "intellij-server" in cmdline:
            intellij.append(entry)
    omp.sort(key=lambda item: item["pid"])
    intellij.sort(key=lambda item: item["pid"])
    return {"omp": omp, "intellij": intellij}


def lock_is_held() -> bool:
    ensure_runtime_dir()
    fd = os.open(LOCK_PATH, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return True
        else:
            fcntl.flock(fd, fcntl.LOCK_UN)
            return False
    finally:
        os.close(fd)


def format_owner(metadata: dict[str, Any] | None) -> str:
    if not metadata:
        return "No metadata recorded."
    owner_pid = metadata.get("ownerPid")
    alive = pid_alive(owner_pid if isinstance(owner_pid, int) else None)
    status = "alive" if alive else "stale"
    lines = [
        f"Owner PID: {owner_pid} ({status})",
        f"Started: {metadata.get('startedAt', 'unknown')}",
        f"Server: {metadata.get('serverBin', 'unknown')}",
        f"Metadata: {META_PATH}",
    ]
    forwarded_args = metadata.get("forwardedArgs")
    if isinstance(forwarded_args, list):
        lines.append(f"Args: {' '.join(str(arg) for arg in forwarded_args) or '(none)'}")
    return "\n".join(lines)


def print_status() -> int:
    ensure_runtime_dir()
    metadata = load_metadata()
    processes = list_repo_processes()
    print(f"Repo root: {REPO_ROOT}")
    print(f"Pinned kotlin-lsp: {DEFAULT_SERVER_BIN}")
    print(f"Lock file: {LOCK_PATH}")
    print(f"Metadata file: {META_PATH}")
    print(f"Lock held: {'yes' if lock_is_held() else 'no'}")
    print(f"Metadata present: {'yes' if metadata else 'no'}")
    if metadata:
        print(format_owner(metadata))
    lspmux_path = shutil.which("lspmux")
    if lspmux_path:
        print(f"lspmux: installed at {lspmux_path} (OMP does not currently enable it for kotlin-lsp)")
    else:
        print("lspmux: not installed")
    if processes["omp"]:
        print("OMP processes for repo:")
        for proc in processes["omp"]:
            print(f"  - {proc['pid']}: {proc['cmdline']}")
    else:
        print("OMP processes for repo: none")
    if processes["intellij"]:
        print("intellij-server processes for repo:")
        for proc in processes["intellij"]:
            print(f"  - {proc['pid']}: {proc['cmdline']}")
    else:
        print("intellij-server processes for repo: none")
    return 0


def cleanup_stale() -> int:
    ensure_runtime_dir()
    fd = os.open(LOCK_PATH, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            metadata = load_metadata()
            print("Refusing to clean up: Kotlin LSP owner lock is live.", file=sys.stderr)
            if metadata:
                print(format_owner(metadata), file=sys.stderr)
            return 1
        metadata = load_metadata()
        if metadata and pid_alive(metadata.get("ownerPid") if isinstance(metadata.get("ownerPid"), int) else None):
            print("Refusing to clean up: owner PID is still alive.", file=sys.stderr)
            print(format_owner(metadata), file=sys.stderr)
            return 1
        if META_PATH.exists():
            META_PATH.unlink()
            print(f"Removed stale metadata: {META_PATH}")
        else:
            print("No stale metadata found.")
        try:
            LOCK_PATH.unlink()
            print(f"Removed unlocked lock file: {LOCK_PATH}")
        except FileNotFoundError:
            pass
        return 0
    finally:
        os.close(fd)


def fail_lock_conflict() -> int:
    metadata = load_metadata()
    print("Refusing to start a second kotlin-lsp owner for this repo.", file=sys.stderr)
    print(f"Repo root: {REPO_ROOT}", file=sys.stderr)
    print(f"Lock file: {LOCK_PATH}", file=sys.stderr)
    if metadata:
        print(format_owner(metadata), file=sys.stderr)
    else:
        print("Owner metadata is unavailable; another session may have started without the repo wrapper.", file=sys.stderr)
    processes = list_repo_processes()
    if processes["omp"]:
        print("OMP processes for repo:", file=sys.stderr)
        for proc in processes["omp"]:
            print(f"  - {proc['pid']}: {proc['cmdline']}", file=sys.stderr)
    if processes["intellij"]:
        print("intellij-server processes for repo:", file=sys.stderr)
        for proc in processes["intellij"]:
            print(f"  - {proc['pid']}: {proc['cmdline']}", file=sys.stderr)
    print(
        f"If the owner is stale, run: {Path(__file__).relative_to(REPO_ROOT)} --cleanup-stale",
        file=sys.stderr,
    )
    return LOCK_CONFLICT_EXIT


def launch(forwarded_args: list[str]) -> int:
    ensure_runtime_dir()
    server_bin = DEFAULT_SERVER_BIN
    if not server_bin.exists():
        print(f"Pinned kotlin-lsp binary does not exist: {server_bin}", file=sys.stderr)
        return 2
    if not os.access(server_bin, os.X_OK):
        print(f"Pinned kotlin-lsp binary is not executable: {server_bin}", file=sys.stderr)
        return 2

    fd = os.open(LOCK_PATH, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return fail_lock_conflict()
        write_metadata(server_bin, forwarded_args, fd)
        os.execv(str(server_bin), [str(server_bin), *forwarded_args])
        return 0
    finally:
        os.close(fd)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Repo-scoped kotlin-lsp owner wrapper")
    parser.add_argument("--status", action="store_true", help="Print current repo lock and process status")
    parser.add_argument("--cleanup-stale", action="store_true", help="Remove stale repo lock metadata when no owner is live")
    parser.add_argument("passthrough", nargs=argparse.REMAINDER, help="Arguments forwarded to intellij-server")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.status:
        return print_status()
    if args.cleanup_stale:
        return cleanup_stale()
    forwarded_args = args.passthrough
    if forwarded_args and forwarded_args[0] == "--":
        forwarded_args = forwarded_args[1:]
    return launch(forwarded_args)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
