#!/usr/bin/env python3
"""Black-box ExecuTorch method runner for the asr4all host harness (#2004).

Loads one .pte with the official `executorch` Python runtime and executes named methods on
request. It contains no asr4all logic: the Kotlin engine under test decides every input.

Protocol (little-endian, over stdin/stdout):
  request  := u8 op ...
    op 1 METHODS                                   -> ok, u32 n, n * str
    op 2 EXECUTE str method, u32 n, n * tensor     -> ok, u32 n, n * tensor
    op 4 QUIT
  response := u8 status (0 ok, 1 error: str message) ...
  str      := u32 byte length, utf-8 bytes
  tensor   := u8 dtype (0 float32, 1 int64), u32 ndim, ndim * i64 dims, raw data

Usage: et_bridge.py MODEL.pte
The XNNPACK thread pool is the runtime's default; the host harness is for output
comparison, not timing.
"""
import struct
import sys

import torch
from executorch.runtime import Runtime

DTYPES = {0: torch.float32, 1: torch.int64}
CODES = {torch.float32: 0, torch.int64: 1}


def read_exact(f, n):
    b = f.read(n)
    if len(b) != n:
        raise EOFError
    return b


def read_str(f):
    (n,) = struct.unpack("<I", read_exact(f, 4))
    return read_exact(f, n).decode()


def write_str(out, s):
    b = s.encode()
    out.write(struct.pack("<I", len(b)) + b)


def read_tensor(f):
    dtype, ndim = struct.unpack("<BI", read_exact(f, 5))
    dims = struct.unpack(f"<{ndim}q", read_exact(f, 8 * ndim)) if ndim else ()
    t = DTYPES[dtype]
    numel = 1
    for d in dims:
        numel *= d
    data = read_exact(f, numel * (4 if t == torch.float32 else 8))
    return torch.frombuffer(bytearray(data), dtype=t).reshape(dims)


def write_tensor(out, t):
    t = t.contiguous()
    out.write(struct.pack("<BI", CODES[t.dtype], t.dim()))
    if t.dim():
        out.write(struct.pack(f"<{t.dim()}q", *t.shape))
    out.write(t.numpy().tobytes())


def main():
    program = Runtime.get().load_program(sys.argv[1])
    methods = {}
    inp, out = sys.stdin.buffer, sys.stdout.buffer
    while True:
        try:
            (op,) = struct.unpack("<B", read_exact(inp, 1))
        except EOFError:
            return
        try:
            if op == 1:
                names = sorted(program.method_names)
                out.write(b"\x00" + struct.pack("<I", len(names)))
                for n in names:
                    write_str(out, n)
            elif op == 2:
                name = read_str(inp)
                (n,) = struct.unpack("<I", read_exact(inp, 4))
                args = [read_tensor(inp) for _ in range(n)]
                if name not in methods:
                    methods[name] = program.load_method(name)
                results = methods[name].execute(args)
                out.write(b"\x00" + struct.pack("<I", len(results)))
                for r in results:
                    write_tensor(out, r)
            elif op == 4:
                return
            else:
                raise ValueError(f"unknown op {op}")
        except EOFError:
            return
        except Exception as e:  # report, then exit: the request may be partly unread, so the stream cannot be resynchronised
            out.write(b"\x01")
            write_str(out, f"{type(e).__name__}: {e}")
            out.flush()
            return
        out.flush()


if __name__ == "__main__":
    main()
