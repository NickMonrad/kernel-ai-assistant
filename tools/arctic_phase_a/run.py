#!/usr/bin/env python3
"""Convert pinned Arctic Embed M v1.5 to LiteRT and score deterministic fixtures."""
from __future__ import annotations

import argparse
import json
import hashlib
import pathlib
import statistics

import numpy as np
import sentence_transformers
import torch
from ai_edge_litert.interpreter import Interpreter
from ai_edge_quantizer import Quantizer
from sentence_transformers import SentenceTransformer
from transformers import AutoTokenizer

MODEL = "Snowflake/snowflake-arctic-embed-m-v1.5"
REVISION = "e58a8f756156a1293d763f17e3aae643474e9b8a"
QUERY_PREFIX = "Represent this sentence for searching relevant passages: "
MAX_TOKENS = 512

# Fixed corpus includes semantically close and unrelated negatives; labels identify
# the one intended relevant document for each query.
CORPUS = [
    ("kiwi_home", "I live in Wellington, New Zealand, and I grew up in Dunedin."),
    ("kiwi_work", "I work as a software engineer and mostly build Android apps."),
    ("kiwi_food", "I enjoy a cheese roll and a flat white from a cafe in Ōtepoti."),
    ("travel", "I am planning a weekend trip to Rotorua and want to visit the hot pools."),
    ("pet", "My dog Pippa is a black border collie who is afraid of thunderstorms."),
    ("family", "My sister Maia lives in Christchurch and visits over Matariki."),
    ("device", "My reference phone is a Samsung Galaxy S23 Ultra."),
    ("health", "I take a short walk after lunch to help with my back pain."),
    ("work_near", "I develop mobile software, especially Kotlin apps for Android."),
    ("home_near", "I used to live in Dunedin and now reside in the capital of New Zealand."),
    ("food_near", "A flat white and a toasted cheese roll are favourite New Zealand cafe foods."),
    ("weather", "The forecast says heavy rain and strong winds are expected tomorrow."),
    ("math", "The derivative of x squared is two times x."),
    ("history", "The Treaty of Waitangi was signed in 1840."),
    ("music", "I listen to jazz piano while cooking dinner."),
    ("money", "My monthly electricity bill is due on the fifteenth."),
    ("conversation", "Yesterday I said I felt tired after staying up late to finish a project."),
    ("episode", "After missing the bus, I walked to the station and called Maia; she picked me up."),
]
QUERIES = [
    ("Where is home now?", "home_near"),
    ("What city did I move to?", "home_near"),
    ("What do I build for work?", "work_near"),
    ("Which Android phone is my reference device?", "device"),
    ("What is my dog called and what frightens her?", "pet"),
    ("What New Zealand cafe food do I like?", "food_near"),
    ("Tell me about the trip I am planning.", "travel"),
    ("What helps with my back discomfort?", "health"),
    ("Where does Maia live?", "family"),
    ("What is the derivative of x^2?", "math"),
    ("What was signed in 1840?", "history"),
    ("What is the weather likely to be tomorrow?", "weather"),
    ("What happened after I missed the bus?", "episode"),
    ("Why was I tired yesterday?", "conversation"),
]


def encode(tokenizer, text: str, is_query: bool) -> dict[str, torch.Tensor]:
    value = QUERY_PREFIX + text if is_query else text
    return tokenizer(
        value,
        max_length=MAX_TOKENS,
        truncation=True,
        padding="max_length",
        return_tensors="pt",
    )


class ArcticEmbedding(torch.nn.Module):
    def __init__(self, transformer: torch.nn.Module):
        super().__init__()
        self.transformer = transformer

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        hidden = self.transformer(
            input_ids=input_ids.to(torch.int64),
            attention_mask=attention_mask.to(torch.int64),
        ).last_hidden_state
        return torch.nn.functional.normalize(hidden[:, 0, :], p=2, dim=-1)


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=pathlib.Path, default=pathlib.Path("tools/arctic_phase_a/.cache/arctic"))
    parser.add_argument("--artifact", type=pathlib.Path, default=pathlib.Path("tools/arctic_phase_a/out/arctic_m_v1_5_dynamic_int8.tflite"))
    parser.add_argument("--reuse-artifact", action="store_true")
    parser.add_argument("--report", type=pathlib.Path, default=pathlib.Path("tools/arctic_phase_a/out/fidelity.json"))
    parser.add_argument("--device-fixtures", type=pathlib.Path, default=pathlib.Path("tools/arctic_phase_a/out/device-fixtures.json"))
    args = parser.parse_args()
    args.model_dir.mkdir(parents=True, exist_ok=True)
    args.artifact.parent.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.device_fixtures.parent.mkdir(parents=True, exist_ok=True)

    tokenizer = AutoTokenizer.from_pretrained(MODEL, revision=REVISION, cache_dir=args.model_dir)
    reference = SentenceTransformer(MODEL, revision=REVISION, cache_folder=str(args.model_dir), device="cpu")
    reference.max_seq_length = MAX_TOKENS
    transformer = reference[0].auto_model.eval()
    wrapper = ArcticEmbedding(transformer).eval()
    if not args.reuse_artifact:
        import litert_torch

        sample = tokenizer("conversion shape check", max_length=MAX_TOKENS, truncation=True,
                           padding="max_length", return_tensors="pt")
        sample_args = (sample["input_ids"].to(torch.int32), sample["attention_mask"].to(torch.int32))
        float_artifact = args.artifact.with_suffix(".float.tflite")
        with torch.no_grad():
            edge_model = litert_torch.convert(wrapper, sample_args)
        edge_model.export(str(float_artifact))
        Quantizer(
            float_artifact,
            quantization_recipe=pathlib.Path(__file__).with_name("dynamic_wi8_afp32_recipe.json"),
        ).quantize(serialize_to_path=args.artifact)
        float_artifact.unlink()

    interpreter = Interpreter(model_path=str(args.artifact), num_threads=4)
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    if len(inputs) != 2 or len(outputs) != 1:
        raise RuntimeError(f"Unexpected TFLite IO: inputs={inputs}, outputs={outputs}")

    def run_candidate_features(values: dict[str, torch.Tensor]) -> np.ndarray:
        for position, detail in enumerate(inputs):
            key = ("input_ids", "attention_mask")[position]
            tensor = values[key].numpy().astype(detail["dtype"], copy=False)
            interpreter.set_tensor(detail["index"], tensor)
        interpreter.invoke()
        return interpreter.get_tensor(outputs[0]["index"])[0].astype(np.float32)

    def reference_vec(text: str, is_query: bool) -> np.ndarray:
        features = encode(tokenizer, text, is_query)
        with torch.inference_mode():
            return wrapper(features["input_ids"], features["attention_mask"])[0].cpu().numpy()

    examples = [(query, True) for query, _ in QUERIES[:2]]
    examples.extend((text, False) for _, text in CORPUS)
    examples.extend((query, True) for query, _ in QUERIES[2:])
    ref_vectors, lite_vectors = {}, {}
    similarities = []
    vector_details = []
    device_vectors = []
    for text, is_query in examples:
        features = encode(tokenizer, text, is_query)
        ref = reference_vec(text, is_query)
        lite = run_candidate_features(features)
        similarities.append(cosine(ref, lite))
        vector_details.append({
            "text": text,
            "is_query": is_query,
            "dimensions": int(lite.size),
            "finite": bool(np.isfinite(lite).all()),
            "reference_norm": float(np.linalg.norm(ref)),
            "converted_norm": float(np.linalg.norm(lite)),
            "cosine": similarities[-1],
        })
        ref_vectors[(text, is_query)] = ref
        lite_vectors[(text, is_query)] = lite
        if len(device_vectors) < 5:
            device_vectors.append({
                "text": text,
                "is_query": is_query,
                "input_ids": features["input_ids"][0].tolist(),
                "attention_mask": features["attention_mask"][0].tolist(),
                "reference": ref.tolist(),
            })

    args.device_fixtures.write_text(json.dumps({
        "model": MODEL,
        "revision": REVISION,
        "max_length": MAX_TOKENS,
        "query_prefix": QUERY_PREFIX,
        "cases": device_vectors,
    }, separators=(",", ":")) + "\n")
    # Rank using cosine directly, as required for this evaluation, without touching
    # the production sqlite-vec metric or thresholds.
    prefix_probe = QUERIES[0][0]
    prefixed_ref = ref_vectors[(prefix_probe, True)]
    prefixed_lite = lite_vectors[(prefix_probe, True)]
    unprefixed_features = encode(tokenizer, prefix_probe, False)
    unprefixed_ref = reference_vec(prefix_probe, False)
    unprefixed_lite = run_candidate_features(unprefixed_features)
    prefix_ref_scores = sorted(
        ((cosine(prefixed_ref, ref_vectors[(text, False)]), doc_id) for doc_id, text in CORPUS),
        reverse=True,
    )
    unprefixed_ref_scores = sorted(
        ((cosine(unprefixed_ref, ref_vectors[(text, False)]), doc_id) for doc_id, text in CORPUS),
        reverse=True,
    )
    prefix_lite_scores = sorted(
        ((cosine(prefixed_lite, lite_vectors[(text, False)]), doc_id) for doc_id, text in CORPUS),
        reverse=True,
    )
    unprefixed_lite_scores = sorted(
        ((cosine(unprefixed_lite, lite_vectors[(text, False)]), doc_id) for doc_id, text in CORPUS),
        reverse=True,
    )
    rank_rows = []
    for query, expected_id in QUERIES:
        ref_q, lite_q = ref_vectors[(query, True)], lite_vectors[(query, True)]
        ref_scores, lite_scores = [], []
        for doc_id, text in CORPUS:
            ref_d, lite_d = ref_vectors[(text, False)], lite_vectors[(text, False)]
            ref_scores.append((cosine(ref_q, ref_d), doc_id))
            lite_scores.append((cosine(lite_q, lite_d), doc_id))
        ref_rank = [doc for _, doc in sorted(ref_scores, reverse=True)]
        lite_rank = [doc for _, doc in sorted(lite_scores, reverse=True)]
        rank_rows.append({
            "query": query,
            "expected_relevant": expected_id,
            "reference_top5": ref_rank[:5],
            "converted_top5": lite_rank[:5],
            "reference_top1_relevant": ref_rank[0] == expected_id,
            "converted_top1_relevant": lite_rank[0] == expected_id,
            "top1_agreement": ref_rank[0] == lite_rank[0],
            "top5_overlap": len(set(ref_rank[:5]) & set(lite_rank[:5])),
            "reference_relevant_rank": ref_rank.index(expected_id) + 1,
            "converted_relevant_rank": lite_rank.index(expected_id) + 1,
        })

    report = {
        "model": MODEL,
        "revision": REVISION,
        "license": "Apache-2.0",
        "runtime_versions": {
            "torch": torch.__version__,
            "litert_torch": __import__("litert_torch").__version__,
            "ai_edge_litert": __import__("importlib.metadata", fromlist=["version"]).version("ai-edge-litert"),
            "transformers": __import__("transformers").__version__,
            "sentence_transformers": sentence_transformers.__version__,
            "ai_edge_quantizer": __import__("importlib.metadata", fromlist=["version"]).version("ai-edge-quantizer"),
        },
        "pipeline": {
            "tokenizer": "upstream uncased BERT WordPiece",
            "max_length": MAX_TOKENS,
            "query_prefix": QUERY_PREFIX,
            "document_prefix": "",
            "pooling": "last_hidden_state[:, 0, :] (CLS)",
            "normalization": "L2",
            "tokenizer_vocab_size": tokenizer.vocab_size,
            "special_token_ids": tokenizer.all_special_ids,
            "lowercases": bool(tokenizer.do_lower_case),
            "dimensions": 768,
            "quantization": "AI Edge Quantizer 8-bit symmetric channel-wise weight-only recipe",
            "io_contract": "int32 [1,512] input_ids + attention_mask; float32 [1,768] output",
        },
        "artifact": {
            "path": str(args.artifact),
            "size_bytes": args.artifact.stat().st_size,
            "sha256": hashlib.sha256(args.artifact.read_bytes()).hexdigest(),
            "inputs": [{"name": x["name"], "shape": x["shape"].tolist(), "dtype": str(x["dtype"])} for x in inputs],
            "outputs": [{"name": x["name"], "shape": x["shape"].tolist(), "dtype": str(x["dtype"])} for x in outputs],
            "ops": sorted({op["op_name"] for op in interpreter._get_ops_details()}),
        },
        "vectors": {
            "count": len(similarities),
            "mean_cosine": statistics.mean(similarities),
            "min_cosine": min(similarities),
            "p05_cosine": float(np.quantile(similarities, 0.05)),
            "p50_cosine": float(np.quantile(similarities, 0.5)),
            "max_cosine": max(similarities),
            "nonfinite_count": sum(not row["finite"] for row in vector_details),
            "details": vector_details,
        },
        "retrieval": {
            "query_count": len(rank_rows),
            "top1_agreement": sum(row["top1_agreement"] for row in rank_rows) / len(rank_rows),
            "mean_top5_overlap": statistics.mean(row["top5_overlap"] for row in rank_rows),
            "reference_relevant_top1": sum(row["reference_top1_relevant"] for row in rank_rows),
            "converted_relevant_top1": sum(row["converted_top1_relevant"] for row in rank_rows),
            "queries": rank_rows,
        },
        "query_prefix_probe": {
            "query": prefix_probe,
            "prefixed_reference_top1": prefix_ref_scores[0][1],
            "unprefixed_reference_top1": unprefixed_ref_scores[0][1],
            "prefixed_converted_top1": prefix_lite_scores[0][1],
            "unprefixed_converted_top1": unprefixed_lite_scores[0][1],
            "reference_prefix_delta_cosine": cosine(prefixed_ref, unprefixed_ref),
            "converted_prefix_delta_cosine": cosine(prefixed_lite, unprefixed_lite),
        },
    }
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: report[k] for k in ("artifact", "vectors", "retrieval")}, indent=2))


if __name__ == "__main__":
    main()
