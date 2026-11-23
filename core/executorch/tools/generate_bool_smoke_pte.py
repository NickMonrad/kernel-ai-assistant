#!/usr/bin/env python3
"""Generate the tiny .pte used by BoolInputSmokeTest in :core:executorch's instrumented tests.

One method, ``masked``: (float32 [1, 4], bool [1, 4]) -> float32 [1, 4], computing
``torch.where(mask, x, 0)`` on ExecuTorch's portable kernels. The output only matches
``x`` at the true positions if the runtime reads each BOOL element from the input buffer, so the
smoke test uses a mixed mask.

Use the same exporter as generate_smoke_pte.py (executorch 1.5.1 and its pinned torch):

    .venv/bin/python core/executorch/tools/generate_bool_smoke_pte.py

Expected values are asserted in BoolInputSmokeTest; keep the two in step.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import torch
from executorch.exir import to_edge_transform_and_lower

DEFAULT_OUTPUT = (
    Path(__file__).resolve().parent.parent
    / "src/androidTest/assets/bool_smoke.pte"
)


class MaskedSelect(torch.nn.Module):
    def forward(self, x: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        return torch.where(mask, x, torch.zeros_like(x))


def build() -> bytes:
    module = MaskedSelect().eval()
    program = torch.export.export(
        module,
        (torch.zeros(1, 4), torch.ones(1, 4, dtype=torch.bool)),
    )
    edge = to_edge_transform_and_lower({"masked": program})
    return edge.to_executorch().buffer


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    data = build()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(data)
    print(f"wrote {args.output} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
