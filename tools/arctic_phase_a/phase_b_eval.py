#!/usr/bin/env python3
"""Score current EmbeddingGemma/L2 and Arctic/cosine on fixed Phase-B fixtures."""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import math
import pathlib
import struct
import unicodedata

import numpy as np
from ai_edge_litert.interpreter import Interpreter

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURE = pathlib.Path(__file__).with_name("phase_b_corpus.json")
KIWI = ROOT / "core/inference/src/main/assets/nz_truth_memories.json"
PREFIX = "Represent this sentence for searching relevant passages: "
SEQ = 512


def sha(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def varint(data: bytes, pos: int) -> tuple[int, int]:
    value = shift = 0
    while pos < len(data):
        byte = data[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, pos
        shift += 7
    raise ValueError("truncated protobuf varint")


def skip_field(data: bytes, pos: int, wire: int) -> int:
    if wire == 0:
        while data[pos] & 0x80:
            pos += 1
        return pos + 1
    if wire == 1:
        return pos + 8
    if wire == 2:
        size, pos = varint(data, pos)
        return pos + size
    if wire == 5:
        return pos + 4
    raise ValueError(f"unsupported protobuf wire type {wire}")


def sentencepiece_vocab(path: pathlib.Path) -> tuple[dict[str, tuple[int, float, int]], int]:
    data = path.read_bytes()
    pieces: dict[str, tuple[int, float, int]] = {}
    pos = 0
    index = 0
    while pos < len(data):
        tag, pos = varint(data, pos)
        field, wire = tag >> 3, tag & 7
        if field != 1 or wire != 2:
            pos = skip_field(data, pos, wire)
            continue
        length, pos = varint(data, pos)
        end = pos + length
        text, score, kind = "", 0.0, 1
        while pos < end:
            item_tag, pos = varint(data, pos)
            item_field, item_wire = item_tag >> 3, item_tag & 7
            if item_field == 1 and item_wire == 2:
                n, pos = varint(data, pos)
                text = data[pos:pos + n].decode("utf-8")
                pos += n
            elif item_field == 2 and item_wire == 5:
                score = struct.unpack_from("<f", data, pos)[0]
                pos += 4
            elif item_field == 3 and item_wire == 0:
                kind, pos = varint(data, pos)
            else:
                pos = skip_field(data, pos, item_wire)
        pieces[text] = (index, score, kind)
        index += 1
    bos = pieces.get("<bos>", pieces.get("<s>", (2, 0.0, 0)))[0]
    return pieces, bos


def sentencepiece_encode(text: str, vocab: dict[str, tuple[int, float, int]], bos: int) -> list[int]:
    normalized = ""
    pending = True
    for char in text:
        if char in " \t\n\r":
            pending = True
        else:
            if pending:
                normalized += "▁"
            normalized += char
            pending = False
    chars = list(normalized)
    n = len(chars)
    neg_inf = float("-inf")
    dp = [neg_inf] * (n + 1)
    back = [-1] * (n + 1)
    dp[0] = 0.0
    for start in range(n):
        if dp[start] == neg_inf:
            continue
        candidate = ""
        for end in range(start + 1, n + 1):
            candidate += chars[end - 1]
            piece = vocab.get(candidate)
            if piece is not None and piece[2] != 5:
                score = dp[start] + piece[1]
                if score > dp[end]:
                    dp[end], back[end] = score, start
        if dp[start + 1] == neg_inf:
            dp[start + 1], back[start + 1] = dp[start] - 10.0, start
    ids: list[int] = []
    at = n
    while at > 0:
        previous = back[at]
        if previous < 0:
            break
        piece_text = "".join(chars[previous:at])
        ids.append(vocab.get(piece_text, (3, 0.0, 0))[0])
        at = previous
    ids.reverse()
    return ([bos] + ids)[:SEQ]


def is_whitespace(char: str) -> bool:
    return char in (" ", "\t", "\n", "\r") or unicodedata.category(char) == "Zs"


def is_chinese(char: str) -> bool:
    code_point = ord(char)
    return (
        0x4E00 <= code_point <= 0x9FFF
        or 0x3400 <= code_point <= 0x4DBF
        or 0x20000 <= code_point <= 0x2A6DF
        or 0x2A700 <= code_point <= 0x2B73F
        or 0x2B740 <= code_point <= 0x2B81F
        or 0x2B820 <= code_point <= 0x2CEAF
        or 0xF900 <= code_point <= 0xFAFF
        or 0x2F800 <= code_point <= 0x2FA1F
    )


def wordpiece_encode(text: str, vocab: dict[str, int]) -> tuple[np.ndarray, np.ndarray]:
    chars = []
    for char in text:
        category = unicodedata.category(char)
        if char in "\x00\ufffd":
            continue
        if category in ("Cc", "Cf") and char not in "\t\n\r":
            continue
        chars.append(char)
    normalized = "".join(chars).lower()
    normalized = "".join(c for c in unicodedata.normalize("NFD", normalized)
                         if unicodedata.category(c) != "Mn")
    tokens: list[str] = []
    buffer = ""
    for char in normalized:
        category = unicodedata.category(char)
        if is_whitespace(char):
            if buffer:
                tokens.append(buffer)
                buffer = ""
        elif is_chinese(char):
            if buffer:
                tokens.append(buffer)
                buffer = ""
            tokens.append(char)
        elif category.startswith("P"):
            if buffer:
                tokens.append(buffer)
                buffer = ""
            tokens.append(char)
        else:
            buffer += char
    if buffer:
        tokens.append(buffer)
    pieces: list[str] = []
    for token in tokens:
        if len(token) > 100:
            pieces.append("[UNK]")
            continue
        start = 0
        found: list[str] = []
        while start < len(token):
            end = len(token)
            piece = None
            while start < end:
                candidate = token[start:end] if start == 0 else "##" + token[start:end]
                if candidate in vocab:
                    piece = candidate
                    break
                end -= 1
            if piece is None:
                found = ["[UNK]"]
                break
            found.append(piece)
            start = end
        pieces.extend(found)
    ids = np.zeros(SEQ, dtype=np.int32)
    mask = np.zeros(SEQ, dtype=np.int32)
    cls_id, sep_id, unk_id = vocab.get("[CLS]", 0), vocab.get("[SEP]", 0), vocab.get("[UNK]", 0)
    retained = pieces[:SEQ - 2]
    ids[0] = cls_id
    mask[0] = 1
    for i, piece in enumerate(retained, 1):
        ids[i] = vocab.get(piece, unk_id)
        mask[i] = 1
    ids[len(retained) + 1] = sep_id
    mask[len(retained) + 1] = 1
    return ids, mask


class Model:
    def __init__(self, path: pathlib.Path, arctic: bool, vocab_path: pathlib.Path, sentencepiece_path: pathlib.Path):
        self.interpreter = Interpreter(model_path=str(path), num_threads=4)
        self.interpreter.allocate_tensors()
        self.inputs = self.interpreter.get_input_details()
        self.output = self.interpreter.get_output_details()[0]
        self.arctic = arctic
        if arctic:
            self.vocab = {line.rstrip("\n\r"): i for i, line in enumerate(vocab_path.read_text().splitlines())}
        else:
            self.vocab, self.bos = sentencepiece_vocab(sentencepiece_path)

    def embed(self, text: str, query: bool) -> np.ndarray:
        if self.arctic:
            values = wordpiece_encode((PREFIX + text) if query else text, self.vocab)
            for detail, value in zip(self.inputs, values):
                self.interpreter.set_tensor(detail["index"], value.reshape(1, SEQ))
        else:
            token_ids = sentencepiece_encode(text, self.vocab, self.bos)
            ids = np.zeros((1, SEQ), dtype=np.int32)
            ids[0, :len(token_ids)] = token_ids
            self.interpreter.set_tensor(self.inputs[0]["index"], ids)
        self.interpreter.invoke()
        vector = self.interpreter.get_tensor(self.output["index"])[0].astype(np.float64)
        norm = np.linalg.norm(vector)
        if not np.isfinite(norm) or norm == 0:
            raise ValueError("embedding output is non-finite or zero")
        return vector / norm


def load_fixture() -> tuple[dict, list[dict]]:
    fixture = json.loads(FIXTURE.read_text())
    kiwi = json.loads(KIWI.read_text())
    kiwi_docs = [{"id": x["id"], "text": x.get("vector_text", x["definition"]), "vibe": int(x.get("vibe_level", 1))}
                 for x in kiwi]
    consumers = []
    for name, group in fixture["consumers"].items():
        consumers.append({
            "name": name,
            "top_k": group["top_k"],
            "docs": [{"id": x[0], "text": x[1], "vibe": None} for x in group["documents"]],
            "queries": [{"text": q[0], "relevant": [q[1]], "vibe": None} for q in group["queries"]],
        })
    consumers.append({
        "name": "kiwi",
        "top_k": 2,
        "docs": kiwi_docs,
        "queries": [{"text": q[0], "relevant": [q[1]], "vibe": int(q[2])} for q in fixture["kiwi_queries"]],
    })
    consumers.append({
        "name": "identity",
        "top_k": 11,
        "docs": kiwi_docs,
        "queries": [{"text": q[0], "relevant": [q[1]], "vibe": int(q[2])}
                    for q in fixture["kiwi_queries"]],
    })
    legacy = fixture["legacy_phase_a"]
    consumers.append({
        "name": "legacy_phase_a",
        "top_k": 3,
        "docs": [{"id": x[0], "text": x[1], "vibe": None} for x in legacy["documents"]],
        "queries": [{"text": q[0], "relevant": [q[1]], "vibe": None} for q in legacy["queries"]],
    })
    return fixture, consumers


def calc_distances(model: Model, consumer: dict, arctic: bool) -> list[dict]:
    vectors = [model.embed(d["text"], False) for d in consumer["docs"]]
    ids = [model.embed(q["text"], True) for q in consumer["queries"]]
    out = []
    for q, qvec in zip(consumer["queries"], ids):
        pairs = []
        for doc, dvec in zip(consumer["docs"], vectors):
            distance = (1.0 - float(np.dot(qvec, dvec))) if arctic else float(np.linalg.norm(qvec - dvec))
            pairs.append({"id": doc["id"], "distance": distance, "vibe": doc["vibe"]})
        out.append({**q, "ranked": sorted(pairs, key=lambda x: x["distance"])})
    return out


def baseline_limits(name: str) -> dict[int | None, float]:
    if name == "message":
        return {None: 0.90}
    if name == "core":
        return {None: 1.25}
    if name == "episodic":
        return {None: 1.10}
    if name in ("kiwi", "identity"):
        return {1: 1.25, 2: 1.25, 3: 1.20, 4: 1.20, 5: 1.20}
    return {None: 0.90}


def get_limit(limits: dict[int | None, float], vibe: int | None) -> float:
    return limits[vibe] if vibe in limits else limits.get(None, 0.0)


def subset_metrics(rows: list[dict], top_k: int, limits: dict[int | None, float]) -> dict:
    tp = fp = relevant_total = 0
    hits = {1: 0, 3: 0, 5: 0}
    reciprocal = 0.0
    retrieved = []
    for row in rows:
        relevant = set(row["relevant"])
        ranked = row["ranked"]
        relevant_total += len(relevant)
        ranks = [i + 1 for i, item in enumerate(ranked) if item["id"] in relevant]
        if ranks:
            reciprocal += 1.0 / ranks[0]
        for k in hits:
            if any(item["id"] in relevant for item in ranked[:k]):
                hits[k] += 1
        selected = [item for item in ranked[:top_k] if item["distance"] <= get_limit(limits, item["vibe"])]
        tp += sum(item["id"] in relevant for item in selected)
        fp += sum(item["id"] not in relevant for item in selected)
        retrieved.extend({"query": row["text"], **item, "relevant": item["id"] in relevant}
                         for item in selected)
    query_count = len(rows)
    negative_total = sum(max(0, len(row["ranked"]) - len(row["relevant"])) for row in rows)
    return {
        "queries": query_count,
        "relevant_pairs": relevant_total,
        "negative_pairs": negative_total,
        "threshold_tp": tp,
        "threshold_fp": fp,
        "precision": round(tp / (tp + fp), 4) if tp + fp else 0.0,
        "recall": round(tp / relevant_total, 4) if relevant_total else 0.0,
        "false_positive_rate": round(fp / negative_total, 4) if negative_total else 0.0,
        "mrr": round(reciprocal / query_count, 4) if query_count else 0.0,
        "recall_at_1": round(hits[1] / query_count, 4) if query_count else 0.0,
        "recall_at_3": round(hits[3] / query_count, 4) if query_count else 0.0,
        "recall_at_5": round(hits[5] / query_count, 4) if query_count else 0.0,
        "retrieved_false_positives": retrieved[:0] + [x for x in retrieved if not x["relevant"]],
    }


def fit_limit(rows: list[dict], top_k: int, vibe_filter: int | None, fp_budget: int) -> float:
    distances = sorted({math.ceil(item["distance"] * 1000 - 1e-9) / 1000
                        for row in rows for item in row["ranked"][:top_k]
                        if vibe_filter is None or item["vibe"] == vibe_filter})
    candidates = [0.0] + [x for x in distances if x <= 1.0]
    best_tp, best_limit = 0, -1.0
    for limit in candidates:
        tp = fp = 0
        for row in rows:
            relevant = set(row["relevant"])
            for item in row["ranked"][:top_k]:
                if vibe_filter is not None and item["vibe"] != vibe_filter:
                    continue
                if item["distance"] <= limit:
                    if item["id"] in relevant:
                        tp += 1
                    else:
                        fp += 1
        if fp <= fp_budget and (tp > best_tp or (tp == best_tp and limit > best_limit)):
            best_tp, best_limit = tp, limit
    return best_limit


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", type=pathlib.Path, default=pathlib.Path("/tmp/phaseb-artifacts"))
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("/tmp/phaseb-artifacts/phase_b_eval.json"))
    args = parser.parse_args()
    fixture, consumers = load_fixture()
    artifact_paths = {
        "arctic": args.artifacts / "arctic-embed-m-v1.5-int8.tflite",
        "gemma": args.artifacts / "embeddinggemma-300M_seq512_mixed-precision.tflite",
        "vocab": args.artifacts / "arctic-embed-m-v1.5-vocab.txt",
        "sentencepiece": args.artifacts / "sentencepiece.model",
    }
    arctic = Model(artifact_paths["arctic"], True, artifact_paths["vocab"], artifact_paths["sentencepiece"])
    gemma = Model(artifact_paths["gemma"], False, artifact_paths["vocab"], artifact_paths["sentencepiece"])
    results = {
        "schema": 1,
        "fixture_sha256": sha(FIXTURE),
        "kiwi_corpus_sha256": sha(KIWI),
        "artifact_sha256": {key: sha(path) for key, path in artifact_paths.items()},
        "runtime": "LiteRT host CPU, 4 threads; exact pinned model artifacts; project tokenizer logic ported from current Kotlin implementations",
        "selection_rule": "Choose the highest observed cosine-distance cutoff on calibration queries whose false-positive count does not exceed the current EmbeddingGemma/L2 baseline; thresholds are selected on calibration queries only.",
        "consumers": {},
    }
    for consumer in consumers:
        name, top_k = consumer["name"], consumer["top_k"]
        if name == "legacy_phase_a":
            eg_rows = calc_distances(gemma, consumer, False)
            arctic_rows = calc_distances(arctic, consumer, True)
            misses = []
            for q, row in zip(consumer["queries"], arctic_rows):
                top = row["ranked"][:top_k]
                target = q["relevant"][0]
                if not any(item["id"] == target and item["distance"] <= 0.55 for item in top):
                    misses.append({"query": q["text"], "relevant": target,
                                   "top3": [{"id": x["id"], "distance": round(x["distance"], 6)} for x in top]})
            results["consumers"][name] = {
                "baseline": subset_metrics(eg_rows, top_k, {None: 0.90}),
                "arctic_at_0_55": subset_metrics(arctic_rows, top_k, {None: 0.55}),
                "missed_positives_at_0_55": misses,
            }
            continue
        eg_rows = calc_distances(gemma, consumer, False)
        arctic_rows = calc_distances(arctic, consumer, True)
        train_n = 15 if name in ("kiwi", "identity") else 6
        if name in ("kiwi", "identity"):
            fit_indices = [i for i in range(len(consumer["queries"])) if (i % 5) < 3]
            heldout_indices = [i for i in range(len(consumer["queries"])) if (i % 5) >= 3]
            fit_eg = [eg_rows[i] for i in fit_indices]
            fit_arctic = [arctic_rows[i] for i in fit_indices]
            baseline = baseline_limits(name)
            candidate_limits = {}
            for vibe in range(1, 6):
                baseline_fp = subset_metrics(fit_eg, top_k, {vibe: baseline[vibe]})["threshold_fp"]
                candidate_limits[vibe] = fit_limit(fit_arctic, top_k, vibe, baseline_fp)
            holdout_eg = [eg_rows[i] for i in heldout_indices]
            holdout_arctic = [arctic_rows[i] for i in heldout_indices]
            full_eg, full_arctic = eg_rows, arctic_rows
        else:
            fit_eg, fit_arctic = eg_rows[:train_n], arctic_rows[:train_n]
            baseline = baseline_limits(name)
            baseline_fp = subset_metrics(fit_eg, top_k, baseline)["threshold_fp"]
            candidate_limits = {None: fit_limit(fit_arctic, top_k, None, baseline_fp)}
            holdout_eg, holdout_arctic = eg_rows[train_n:], arctic_rows[train_n:]
            full_eg, full_arctic = eg_rows, arctic_rows
        results["consumers"][name] = {
            "top_k": top_k,
            "calibration_queries": len(fit_eg),
            "heldout_queries": len(holdout_eg),
            "baseline_l2_limits": {str(k): v for k, v in baseline.items()},
            "arctic_cosine_limits": {str(k): v for k, v in candidate_limits.items()},
            "calibration_baseline": subset_metrics(fit_eg, top_k, baseline),
            "calibration_arctic": subset_metrics(fit_arctic, top_k, candidate_limits),
            "heldout_baseline": subset_metrics(holdout_eg, top_k, baseline),
            "heldout_arctic": subset_metrics(holdout_arctic, top_k, candidate_limits),
            "all_baseline": subset_metrics(full_eg, top_k, baseline),
            "all_arctic": subset_metrics(full_arctic, top_k, candidate_limits),
            "heldout_query_rankings": [
                {"query": row["text"], "target": row["relevant"][0],
                 "eg_top5": [{"id": x["id"], "distance": round(x["distance"], 6)} for x in eg["ranked"][:5]],
                 "arctic_top5": [{"id": x["id"], "distance": round(x["distance"], 6)} for x in row["ranked"][:5]]}
                for eg, row in zip(holdout_eg, holdout_arctic)
            ],
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({name: {"limits": value.get("arctic_cosine_limits"),
                             "baseline_holdout": value.get("heldout_baseline"),
                             "arctic_holdout": value.get("heldout_arctic"),
                             "legacy_misses": [m["relevant"] for m in value.get("missed_positives_at_0_55", [])]}
                      for name, value in results["consumers"].items()}, indent=2, ensure_ascii=False))
    print(f"evidence={args.output}")


if __name__ == "__main__":
    main()
