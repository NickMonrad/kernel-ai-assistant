#!/usr/bin/env python3
"""
Converts a raw Piper voice (model + .onnx.json) into the minimal Sherpa-ONNX
directory layout used by the Android TTS spike:

  output-dir/
    model.onnx
    tokens.txt
    model.onnx.json

`tokens.txt` is generated from Piper's `phoneme_id_map`, matching the format
used by sherpa-onnx/scripts/piper/add_meta_data.py: one `token id` pair per line.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--voice-key", required=True)
    parser.add_argument("--input-model", required=True)
    parser.add_argument("--input-config", required=True)
    parser.add_argument("--output-dir", required=True)
    return parser.parse_args()


def build_tokens_lines(config: dict) -> list[str]:
    phoneme_id_map = config.get("phoneme_id_map")
    if not isinstance(phoneme_id_map, dict) or not phoneme_id_map:
        raise ValueError("Piper config is missing phoneme_id_map")

    tokens: list[tuple[int, str]] = []
    for symbol, raw_id in phoneme_id_map.items():
        token_id = raw_id[0] if isinstance(raw_id, list) else raw_id
        if not isinstance(token_id, int):
            raise ValueError(f"Unexpected token id for {symbol!r}: {raw_id!r}")
        if symbol == "\n":
            continue
        tokens.append((token_id, symbol))

    tokens.sort(key=lambda item: item[0])
    return [f"{symbol} {token_id}" for token_id, symbol in tokens]


def main() -> None:
    args = parse_args()

    input_model = Path(args.input_model)
    input_config = Path(args.input_config)
    output_dir = Path(args.output_dir)

    if not input_model.is_file():
        raise SystemExit(f"Missing Piper model: {input_model}")
    if not input_config.is_file():
        raise SystemExit(f"Missing Piper config: {input_config}")

    output_dir.mkdir(parents=True, exist_ok=True)

    with input_config.open("r", encoding="utf-8") as file:
        config = json.load(file)

    tokens_lines = build_tokens_lines(config)

    shutil.copy2(input_model, output_dir / "model.onnx")
    shutil.copy2(input_config, output_dir / "model.onnx.json")

    with (output_dir / "tokens.txt").open("w", encoding="utf-8") as file:
        file.write("\n".join(tokens_lines))
        file.write("\n")

    print(f"Converted {args.voice_key} into {output_dir}")
    print(f"Generated {len(tokens_lines)} token lines")


if __name__ == "__main__":
    main()
