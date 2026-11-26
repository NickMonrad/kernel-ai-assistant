"""Export Kokoro-82M (hexgrad weights) to ExecuTorch .pte, mirroring the shipped software-mansion layout.

Mirrors NorbertKlockiewicz/kokoro-export (`export/export_duration_predictor.py`, `export/export_synthesizer.py`):
same wrappers, same weight-norm removal, same XNNPACK partitioner, same method name (`forward`) and input order.
Two deliberate differences, both needed to match the shipped signature (token axis 2..128, duration-tick axis
2..296; see KokoroEtDebugLane.kt):
  * dynamic min is 2 (the upstream CLI uses min 8 for tokens and 16 for durations);
  * `--precision int8` adds XNNPACK dynamic int8 quantisation of linear layers (dynamic activations, symmetric int8
    weights) before the final torch.export and lowering. Convolutions stay fp32.

Usage:
  KOKORO_UPSTREAM=<upstream checkout> HF_HUB_OFFLINE=1 python export_2116.py --precision fp32 --out <dir>
  KOKORO_UPSTREAM=<upstream checkout> HF_HUB_OFFLINE=1 python export_2116.py --precision int8 --out <dir>
Writes duration_predictor_std_xnnpack_<precision>.pte and synthesizer_std_xnnpack_<precision>.pte.
"""
import argparse
import importlib.util
import os
import time
from pathlib import Path

import torch
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from executorch.exir import to_edge_transform_and_lower
from kokoro import KModel
from torch.export import Dim

# Load the upstream exporter modules by path. Putting upstream/export on sys.path shadows the stdlib `profile`
# module (export/profile.py), which breaks torch._dynamo's cProfile import.
UPSTREAM = Path(os.environ.get("KOKORO_UPSTREAM", Path.home() / ".cache/kokoro-et-1557/upstream"))


def _load_upstream(name):
    spec = importlib.util.spec_from_file_location(name, UPSTREAM / "export" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_dp = _load_upstream("export_duration_predictor")
_syn = _load_upstream("export_synthesizer")

MAX_TOKENS = 128
MAX_DURATION = 296
MIN_TOKENS = 2  # shipped lane signature allows 2..128; upstream CLI used 8
MIN_DURATION = 2


def _lstm_decomposition():
    """Dynamic-length LSTM export needs torch's while-loop decomposition (upstream test_export.py uses it; the
    duration predictor file has it commented out). Without it the token dim is specialised to the example 128."""
    from torch.export._patches import register_lstm_while_loop_decomposition

    if not USE_LSTM_DECOMPOSITION:
        import contextlib

        return contextlib.nullcontext()
    return register_lstm_while_loop_decomposition()


USE_LSTM_DECOMPOSITION = os.environ.get("KOKORO_LSTM_DECOMP", "1") == "1"


def quantize_dynamic_int8(module, example_inputs, dynamic_shapes):
    """XNNPACK dynamic int8 linear (PT2E). Returns a re-exported ExportedProgram."""
    from executorch.backends.xnnpack.quantizer.xnnpack_quantizer import (
        XNNPACKQuantizer,
        get_symmetric_quantization_config,
    )
    from torchao.quantization.pt2e.quantize_pt2e import convert_pt2e, prepare_pt2e

    with _lstm_decomposition():
        exported = torch.export.export(module, example_inputs, dynamic_shapes=dynamic_shapes)
    gm = exported.module()
    quantizer = XNNPACKQuantizer().set_global(get_symmetric_quantization_config(is_dynamic=True))
    gm = prepare_pt2e(gm, quantizer)
    gm(*example_inputs)  # dynamic quantisation: activation scales are computed at run time; one pass initialises
    gm = convert_pt2e(gm)
    with _lstm_decomposition():
        return torch.export.export(gm, example_inputs, dynamic_shapes=dynamic_shapes)


def export_duration_predictor(precision: str, min_tokens: int):
    model = KModel(repo_id="hexgrad/Kokoro-82M", disable_complex=True, lstm_padding=None)
    dp = _dp.DurationPredictorWrapper(model).eval()
    inputs = _dp.build_inputs("inp-128", torch.float32)
    t = Dim("t", min=min_tokens, max=MAX_TOKENS)
    dynamic_shapes = ({1: t}, {1: t}, None, None)
    if precision == "int8":
        exported = quantize_dynamic_int8(dp, inputs, dynamic_shapes)
    else:
        with _lstm_decomposition():
            exported = torch.export.export(dp, inputs, dynamic_shapes=dynamic_shapes)
    return to_edge_transform_and_lower(exported, partitioner=[XnnpackPartitioner()]).to_executorch()


def export_synthesizer(precision: str, min_tokens: int):
    model = KModel(repo_id="hexgrad/Kokoro-82M", disable_complex=True, lstm_padding=MAX_TOKENS)
    syn = _syn.Synthesizer(model, duration_padding=MAX_DURATION).eval()
    _syn.remove_weight_norms(syn)
    inputs = _syn.build_inputs("inp-128", torch.float32, max_duration=MAX_DURATION)
    t = Dim("t", min=min_tokens, max=MAX_TOKENS)
    k = Dim("k", min=MIN_DURATION, max=MAX_DURATION)
    dynamic_shapes = ({1: t}, {1: t}, {0: k}, {1: t}, None)
    if precision == "int8":
        exported = quantize_dynamic_int8(syn, inputs, dynamic_shapes)
    else:
        with _lstm_decomposition():
            exported = torch.export.export(syn, inputs, dynamic_shapes=dynamic_shapes)
    return to_edge_transform_and_lower(exported, partitioner=[XnnpackPartitioner()]).to_executorch()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--precision", choices=["fp32", "int8"], required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--only", choices=["duration_predictor", "synthesizer"], default=None)
    parser.add_argument("--min-tokens", type=int, default=MIN_TOKENS, help="lower bound of the dynamic token axis")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    torch.manual_seed(0)
    jobs = {
        "duration_predictor": (export_duration_predictor, "duration_predictor_std_xnnpack_%s.pte"),
        "synthesizer": (export_synthesizer, "synthesizer_std_xnnpack_%s.pte"),
    }
    for name, (fn, pattern) in jobs.items():
        if args.only and args.only != name:
            continue
        t0 = time.time()
        program = fn(args.precision, args.min_tokens)
        path = os.path.join(args.out, pattern % args.precision)
        with open(path, "wb") as f:
            program.write_to_file(f)
        print(f"wrote {path} bytes={os.path.getsize(path)} secs={time.time() - t0:.0f}", flush=True)


if __name__ == "__main__":
    main()
