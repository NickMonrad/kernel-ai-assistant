#!/usr/bin/env python3
"""Generate the tiny .pte used by :core:executorch's instrumented smoke test.

The program is self-contained and synthetic: no downloaded weights. It has two methods so
the smoke test exercises named-method dispatch, the XNNPACK delegate and a non-float dtype:

- ``forward``: float32 [1, 4] -> float32 [1, 3]; ``Linear(4, 3)`` with fixed weights,
  lowered to the XNNPACK delegate (the backend asr4all's INT8 exports use).
- ``triple``: int64 [2] -> int64 [2]; ``x * 3`` on ExecuTorch's portable kernels.

The exporter must match the runtime: install the PyPI release with the same version as
``executorch`` in gradle/libs.versions.toml and the torch version pinned by that release
(``torch_pin.py`` at the release tag). For 1.5.1:

    uv venv --python 3.12 .venv
    uv pip install --python .venv/bin/python torch==2.14.0 \
        --index-url https://download.pytorch.org/whl/cpu
    uv pip install --python .venv/bin/python executorch==1.5.1
    .venv/bin/python core/executorch/tools/generate_smoke_pte.py

Expected values are asserted in ExecuTorchSmokeTest; keep the two in step.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

import torch
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from executorch.exir import to_edge_transform_and_lower

DEFAULT_OUTPUT = (
    Path(__file__).resolve().parent.parent
    / "src/androidTest/assets/executorch_smoke.pte"
)


class FixedLinear(torch.nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.linear = torch.nn.Linear(4, 3)
        with torch.no_grad():
            # Row r is [r+1, r+1, r+1, r+1]; bias is [0.5, -0.5, 1.0].
            self.linear.weight.copy_(
                torch.arange(1, 4, dtype=torch.float32).unsqueeze(1).expand(3, 4)
            )
            self.linear.bias.copy_(torch.tensor([0.5, -0.5, 1.0]))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.linear(x)


class Triple(torch.nn.Module):
    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return x * 3


def build() -> bytes:
    linear = FixedLinear().eval()
    triple = Triple().eval()
    programs = {
        "forward": torch.export.export(linear, (torch.zeros(1, 4),)),
        "triple": torch.export.export(triple, (torch.zeros(2, dtype=torch.int64),)),
    }
    # Only `forward` is delegated; `triple` stays on the portable kernels.
    edge = to_edge_transform_and_lower(
        programs,
        partitioner={"forward": [XnnpackPartitioner()], "triple": []},
    )
    return edge.to_executorch().buffer


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    data = build()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(data)
    print(f"{args.output} {len(data)} bytes sha256={hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    main()
