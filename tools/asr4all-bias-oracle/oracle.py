#!/usr/bin/env python3
"""Offline place-slot oracle for #2093 step 1 (go/no-go for decoder biasing).

Input: logits dumps written by Asr4AllHostHarnessTest (`dump_logits_dir`): per clip and size,
`<clip>.<size>.<tier>.logits.f32` (float32 LE, [frames, vocab]) plus a `.json` sidecar. Model
vocab, blank id and BPE merges come from each size's model directory (`config.json`, `merges.txt`).

Per clip:
  1. greedy CTC text -> slot. Slot modes:
       trigger  the gate (`--gate v1|v2`) fires on the greedy text; slot = frames after the anchor.
       hint     caller slot-fill hint: if the gate does not fire, the whole utterance is the slot.
       full     whole utterance (diagnostic).
  2. For every candidate row (place or impostor × respelled variant, each segmented once by the
     model's merges-rank BPE): raw = max over spans [a,b) in the slot of
     Σ_{t in [a,b)} (logp[t, path_t] − M_t), where path is the best CTC path collapsing to the
     segmentation (Viterbi: max, not sum) and M_t is the frame max. Frames outside the span
     contribute nothing (free filler), so raw ≤ 0.
  3. Score(β) = raw + β·n_pieces. Step-2 rule (β fixed at 4.0): an exact folded match of the slot text to
     a competitor or impostor vetoes; otherwise accept iff the top candidate is an NZ place with Score > 0,
     beats the runner-up of any kind by MARGIN, and its span ends at a greedy word boundary.

The Viterbi is β-independent, so each clip is scored once and β is swept over cached rows.
Gold labels, transcripts and per-clip rows stay in the output directory; only aggregate summaries
are meant for the PR (see README).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

import numpy as np

import lexicon

NEG = -1.0e30
MARGIN = 1.0  # step-2 rule: the top must beat the runner-up of any kind by at least this (score units)
WORD_RE = re.compile(r"[a-z']+")
PREP_IN = {"in", "at", "for"}
SPACE = "\u2581"  # SentencePiece word marker used in merges.txt; config vocab writes it as " "


# ---------------------------------------------------------------- model / dump loading

@dataclass
class Model:
    size: str
    pieces: list[str]
    index: dict[str, int]
    merge_rank: dict[tuple[str, str], int]

    @property
    def blank(self) -> int:
        return len(self.pieces)

    @classmethod
    def load(cls, size: str, model_dir: Path) -> "Model":
        pieces = list(json.loads((model_dir / "config.json").read_text())["vocab"])
        ranks: dict[tuple[str, str], int] = {}
        for line in (model_dir / "merges.txt").read_text(encoding="utf-8", errors="replace").splitlines():
            parts = line.split("\t")
            if len(parts) == 2 and not line.startswith("#"):
                ranks.setdefault((parts[0], parts[1]), len(ranks))
        return cls(size, pieces, {p: i for i, p in enumerate(pieces)}, ranks)


@dataclass
class Dump:
    clip: str
    logp: np.ndarray  # [T, V] log-softmax, float64


def load_dump(json_path: Path) -> Dump:
    meta = json.loads(json_path.read_text())
    t, v = meta["frames"], meta["vocab_size"]
    raw_path = json_path.with_name(json_path.name[: -len(".json")] + ".logits.f32")
    raw = np.fromfile(raw_path, dtype="<f4").reshape(t, v).astype(np.float64)
    m = raw.max(axis=1, keepdims=True)
    logp = raw - m - np.log(np.exp(raw - m).sum(axis=1, keepdims=True))
    return Dump(clip=meta["clip"], logp=logp)


# ---------------------------------------------------------------- greedy CTC

def greedy_tokens(logp: np.ndarray, blank: int) -> list[tuple[int, int, int]]:
    """CTC best path collapsed to (token id, first frame, last frame)."""
    toks: list[list[int]] = []
    prev = -1
    for t, v in enumerate(logp.argmax(axis=1)):
        v = int(v)
        if v != blank:
            if v == prev and toks:
                toks[-1][2] = t
            else:
                toks.append([v, t, t])
        prev = v
    return [(a, b, c) for a, b, c in toks]


def greedy_text(model: Model, toks) -> tuple[str, np.ndarray]:
    pieces = [model.pieces[i] for i, _, _ in toks]
    text = "".join(pieces)
    char_tok = np.repeat(np.arange(len(toks)), [len(p) for p in pieces]) if toks else np.zeros(0, int)
    return text, char_tok


# ---------------------------------------------------------------- trigger gate

def _lev(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def _word_is(tok: str, want: str, exact: bool = False) -> bool:
    """Greedy CTC garbles words: exact match, or edit distance 1 for words of 4+ letters (unless exact)."""
    if tok == want:
        return True
    return not exact and len(want) >= 4 and _lev(tok, want) <= 1


NAV_VERBS = ("navigate", "directions", "drive", "route", "travel", "trip", "far", "long", "take", "get", "go")
EXACT_ONLY = ("long", "take")  # fuzzy matching makes "along" a "long" and "make" a "take"
NUMERAL_VERBS = ("take", "get", "go")  # "two" after these is usually the numeral
WEATHER_WORDS = ("weather", "forecast", "raining", "rain", "temperature")
TO_ALIASES = ("to", "too", "two")


def gate_v1(text: str) -> int | None:
    """Original gate (before #2093 step 2): exact phrasings only. Returns anchor end char index."""
    m = re.search(r"(navigate to|directions to|take me to|weather (?:in|at))(?= )", text)
    return m.end() - 1 if m else None


def gate_v2(text: str) -> int | None:
    """Generic travel/weather phrasings, fuzzy on words, anchored on the preposition that starts
    the slot. Travel: a travel verb followed by 'to' within 7 words (covers "take me to", "how far
    is it ... over to", "how long is the drive ... to", "add a trip to"). 'two' is not accepted as 'to'
    after take/get/go (numeral: "take two tablets"). Weather: a weather word followed by 'in', 'at' or
    'for' within 3 words. Returns the anchor's last char index or None.
    """
    words = [(m.group(0), m.end() - 1) for m in WORD_RE.finditer(text)]
    best: int | None = None
    for i, (w, _) in enumerate(words):
        verb = next((v for v in NAV_VERBS if _word_is(w, v, exact=v in EXACT_ONLY)), None)
        if verb is not None:
            for j in range(i + 1, min(i + 8, len(words))):
                tok, end = words[j]
                if tok in TO_ALIASES and not (tok == "two" and verb in NUMERAL_VERBS):
                    best = end if best is None else min(best, end)
                    break
        if any(_word_is(w, wx) for wx in WEATHER_WORDS):
            for j in range(i + 1, min(i + 4, len(words))):
                tok, end = words[j]
                if tok in PREP_IN:
                    best = end if best is None else min(best, end)
                    break
    return best


GATES = {"v1": gate_v1, "v2": gate_v2}


def slot_for(mode: str, gate: str, toks, frames: int, text: str, char_tok: np.ndarray):
    """(start, end) frame span of the slot, or None when the slot is not scored."""
    if mode == "full":
        return (0, frames)
    anchor = GATES[gate](text)
    if anchor is None:
        if mode == "hint":
            return (0, frames)
        return None
    tok = toks[int(char_tok[anchor])]
    start = tok[2] + 1
    return (start, frames) if start < frames else None


# ---------------------------------------------------------------- BPE segmentation

def bpe_segment(model: Model, phrase: str) -> list[int]:
    """Canonical segmentation of ' ' + phrase by the model's merges-rank BPE.

    Symbols start as single characters with the space as SPACE; the lowest-rank adjacent pair is
    merged repeatedly until none is in the merge table (standard BPE). Every final symbol must be a
    vocab piece; a miss raises, it is never silently dropped.
    """
    syms = list(SPACE + phrase.replace(" ", SPACE))
    while True:
        best_rank, best_pos = None, -1
        for i in range(len(syms) - 1):
            rank = model.merge_rank.get((syms[i], syms[i + 1]))
            if rank is not None and (best_rank is None or rank < best_rank):
                best_rank, best_pos = rank, i
        if best_rank is None:
            break
        left, right = syms[best_pos], syms[best_pos + 1]
        out: list[str] = []
        i = 0
        while i < len(syms):
            if i < len(syms) - 1 and syms[i] == left and syms[i + 1] == right:
                out.append(left + right)
                i += 2
            else:
                out.append(syms[i])
                i += 1
        syms = out
    return [model.index[s.replace(SPACE, " ")] for s in syms]


def ctc_ext(seg: list[int], blank: int) -> list[int]:
    """Interleave blanks: [blank, l1, blank, l2, ..., blank]. Labels sit at odd indices."""
    ext = [blank]
    for tok in seg:
        ext += [tok, blank]
    return ext


# ---------------------------------------------------------------- batched span Viterbi

def span_best(pen: np.ndarray, ext: np.ndarray):
    """Best CTC span per row, with the span's frames. All rows share one label-sequence length.

    pen: [T, V] penalty E[t, v] = logp[t, v] − M_t (≤ 0) over the slot frames.
    ext: [R, S] blank-interleaved label sequences.
    Returns (best [R], start [R], end [R]): best = max over spans [start, end) of the best path inside
    the span that collapses to the row's labels, Σ E[t, path_t]; start/end are that span's frames.
    Start and end are free, so best ≤ 0.
    """
    rows, s_len = ext.shape
    skip = np.zeros((rows, s_len), dtype=bool)
    if s_len > 2:
        odd = (np.arange(2, s_len) % 2) == 1  # label positions may skip a blank
        skip[:, 2:] = odd[None, :] & (ext[:, 2:] != ext[:, :-2])
    start_ok = np.zeros((rows, s_len), dtype=bool)
    start_ok[:, :2] = True
    alpha = np.full((rows, s_len), NEG)
    st = np.zeros((rows, s_len), dtype=np.int64)  # start frame of the best path ending in each state
    best = np.full(rows, NEG)
    best_a = np.zeros(rows, dtype=np.int64)
    best_b = np.zeros(rows, dtype=np.int64)
    row_ix = np.arange(rows)
    for t in range(pen.shape[0]):
        # predecessors: stay, previous state, two back (only across a blank, where skip is set)
        m, ms = alpha.copy(), st.copy()
        prev = np.full((rows, s_len), NEG)
        prev[:, 1:] = alpha[:, :-1]
        prev_st = np.zeros((rows, s_len), dtype=np.int64)
        prev_st[:, 1:] = st[:, :-1]
        take = prev > m
        m, ms = np.where(take, prev, m), np.where(take, prev_st, ms)
        two = np.full((rows, s_len), NEG)
        two[:, 2:] = np.where(skip[:, 2:], alpha[:, :-2], NEG)
        two_st = np.zeros((rows, s_len), dtype=np.int64)
        two_st[:, 2:] = st[:, :-2]
        take = two > m
        m, ms = np.where(take, two, m), np.where(take, two_st, ms)
        restart = start_ok & (m < 0)
        m = np.where(start_ok, np.maximum(m, 0.0), m)
        ms = np.where(restart, t, ms)
        alpha, st = m + pen[t][ext], ms
        end_state = np.where(alpha[:, -1] >= alpha[:, -2], s_len - 1, s_len - 2)
        end_val = alpha[row_ix, end_state]
        improved = end_val > best
        best = np.where(improved, end_val, best)
        best_a = np.where(improved, st[row_ix, end_state], best_a)
        best_b = np.where(improved, t + 1, best_b)
    return best, best_a, best_b


def span_scores(pen: np.ndarray, ext: np.ndarray) -> np.ndarray:
    return span_best(pen, ext)[0]


# ---------------------------------------------------------------- candidate table

def _rows(path: Path) -> list:
    """JSON list, or the generator's {header, places} object."""
    data = json.loads(path.read_text(encoding="utf-8"))
    return data["places"] if isinstance(data, dict) else data


def load_names(path: Path | None, default: list[str]) -> list[str]:
    if path is None:
        return default
    data = _rows(path)
    return [row["name"] for row in data] if data and isinstance(data[0], dict) else list(data)


def load_places(path: Path | None, default: list[str]) -> list[dict]:
    """Place rows: {name, role: rewritable|competitor, prio}. Plain strings are rewritable, prio 0."""
    if path is None:
        return [{"name": n, "role": "rewritable", "prio": 0} for n in default]
    data = _rows(path)
    if data and isinstance(data[0], dict):
        return [{"name": r["name"], "role": r.get("role", "rewritable"), "prio": int(r.get("prio", 0)),
                 "homograph": bool(r.get("homograph", False))} for r in data]
    return [{"name": n, "role": "rewritable", "prio": 0} for n in data]


@dataclass
class Entries:
    names: list[str]
    kinds: list[str]         # "nz" (rewritable) | "comp" (competitor) | "imp" (impostor)
    prio: np.ndarray         # [E] tie-break prior, larger = preferred
    row_entry: np.ndarray    # [R] entry index
    row_variant: np.ndarray  # [R] 0 = canonical fold
    row_n: np.ndarray        # [R] pieces
    row_ext: list[list[int]]
    homograph: frozenset = frozenset()  # entry indices of NZ rows whose folded name is a GeoNames AU or foreign city name (veto)

    @classmethod
    def build(cls, model: Model, places: list[dict], impostors: list[str]) -> "Entries":
        names, kinds, prio = [], [], []
        re_, rv, rn, rext = [], [], [], []
        seen: set[str] = set()
        skipped: list[str] = []
        homo_keys = {lexicon.fold(p["name"]) for p in places if p.get("homograph")}
        # Rewritable first, then competitors, then impostors: a folded collision keeps the earlier entry.
        order = [("nz" if p["role"] == "rewritable" else "comp", p["name"], p["prio"])
                 for p in places if p["role"] == "rewritable"]
        order += [("comp", p["name"], p["prio"]) for p in places if p["role"] != "rewritable"]
        order += [("imp", display, 0) for display in impostors]
        for kind, display, pr in order:
            key = lexicon.fold(display)
            if key in seen:
                skipped.append(f"{kind}:{display}")
                continue
            seen.add(key)
            names.append(display)
            kinds.append(kind)
            prio.append(pr)
        if skipped:
            print(f"skipped {len(skipped)} duplicate folded names: {skipped[:8]}...", file=sys.stderr)
        for e, display in enumerate(names):
            for vi, variant in enumerate(lexicon.respellings(display)):
                seg = bpe_segment(model, variant)
                re_.append(e)
                rv.append(vi)
                rn.append(len(seg))
                rext.append(ctc_ext(seg, model.blank))
        homograph = frozenset(i for i, n in enumerate(names) if kinds[i] == "nz" and lexicon.fold(n) in homo_keys)
        return cls(names, kinds, np.array(prio, dtype=float), np.array(re_), np.array(rv),
                   np.array(rn, dtype=float), rext, homograph)

    def by_length(self) -> dict[int, np.ndarray]:
        groups: dict[int, list[int]] = {}
        for i, ext in enumerate(self.row_ext):
            groups.setdefault(len(ext), []).append(i)
        return {k: np.array(v) for k, v in groups.items()}


def raw_scores(dump: Dump, entries: Entries, span: tuple[int, int]):
    """Per candidate row: best raw Δ (≤ 0), and the absolute frame span [start, end) of that best path.
    Computed once per clip and size; β-independent."""
    a, b = span
    lp = dump.logp[a:b]
    pen = lp - lp.max(axis=1, keepdims=True)
    out = np.full(len(entries.row_ext), NEG)
    starts = np.zeros(len(entries.row_ext), dtype=np.int64)
    ends = np.zeros(len(entries.row_ext), dtype=np.int64)
    for _, idxs in entries.by_length().items():
        ext = np.array([entries.row_ext[i] for i in idxs], dtype=np.int64)
        best, sa, sb = span_best(pen, ext)
        out[idxs] = best
        starts[idxs] = sa + a
        ends[idxs] = sb + a
    return out, starts, ends


def best_per_entry(score: np.ndarray, entries: Entries, canonical_only: bool):
    """Best row per entry: returns (best score, row index) arrays over entries."""
    n_entries = len(entries.names)
    mask = np.ones(len(score), dtype=bool) if not canonical_only else entries.row_variant == 0
    idx = np.flatnonzero(mask)
    best = np.full(n_entries, NEG)
    arg = np.full(n_entries, -1, dtype=np.int64)
    if idx.size == 0:
        return best, arg
    order = idx[np.lexsort((-score[idx], entries.row_entry[idx]))]
    ent_sorted = entries.row_entry[order]
    first = np.r_[True, ent_sorted[1:] != ent_sorted[:-1]]
    sel = order[first]
    best[entries.row_entry[sel]] = score[sel]
    arg[entries.row_entry[sel]] = sel
    return best, arg


def argbest(best: np.ndarray, idx: np.ndarray) -> int:
    return int(idx[np.argmax(best[idx])])


@dataclass
class Clip:
    """One clip's greedy decode, for the word-boundary and exact-match checks."""
    text: str
    char_tok: np.ndarray
    toks: list[tuple[int, int, int]]
    slot_text: str  # folded greedy text inside the slot; "" when the slot is not scored

    def chars_in(self, a: int, b: int) -> list[int]:
        """Greedy characters whose token starts in frames [a, b)."""
        return [c for c in range(len(self.text)) if a <= self.toks[int(self.char_tok[c])][1] < b]


def make_clip(text: str, char_tok: np.ndarray, toks, span) -> Clip:
    clip = Clip(text, char_tok, toks, "")
    if span is not None:
        clip.slot_text = lexicon.fold("".join(text[c] for c in clip.chars_in(*span)))
    return clip


def boundary_check(clip: Clip, a: int, b: int, name_words: int) -> str | None:
    """The span [a, b) must end at a word boundary of the greedy text and cover at most
    name_words + 1 words. Returns the failing outcome, or None."""
    idx = clip.chars_in(a, b)
    if not idx:
        return "boundary"
    last = idx[-1]
    nxt = clip.text[last + 1] if last + 1 < len(clip.text) else " "
    if clip.text[last] != " " and nxt != " ":
        return "boundary"
    words = len("".join(clip.text[c] for c in idx).split())
    return "word_count" if words > name_words + 1 else None


def evaluate(raw: np.ndarray, ra: np.ndarray, rb: np.ndarray, entries: Entries, beta: float,
             gold: list[str], clip: Clip, exact: dict[str, int]) -> dict:
    """Decision for one clip at one β: the step-2 rule, no prior. Score = raw + β·n.

    Checks, in order:
      - exact veto: the folded greedy slot text equals a competitor or impostor name -> no rewrite;
        equals a rewritable name -> rewrite to that name (canonical spelling only), unless the name is a
        homograph (GeoNames AU or foreign city name): then veto, reason homograph;
      - the overall top must be rewritable with Score > 0 (competitor or impostor top -> no rewrite);
      - accept only if the top beats the runner-up of any kind by MARGIN and its span ends at a word boundary
        covering at most name words + 1 words; otherwise outcome "suggest" (Did you mean X?), reason = the check.
    rw_top is the best rewritable entry by Score (the rank-1 measure, reporting only).
    """
    score = raw + beta * entries.row_n
    best_any, arg_any = best_per_entry(score, entries, canonical_only=False)
    best_can, _ = best_per_entry(score, entries, canonical_only=True)
    kinds = np.array(entries.kinds)
    rw_idx = np.flatnonzero(kinds == "nz")
    for g in gold:
        if g not in entries.names:
            raise SystemExit(f"gold name not in candidate list: {g!r}")
    top = int(np.argmax(best_any))
    rest = best_any.copy()
    rest[top] = NEG
    margin = float(best_any[top] - rest.max())
    reason: str | None = None
    hit = exact.get(clip.slot_text) if clip.slot_text else None
    pos = bool(best_any[top] > 0)
    if hit is not None and hit in entries.homograph:
        final, outcome, reason = hit, "veto", "homograph"
    elif hit is not None and kinds[hit] == "nz":
        final, outcome = hit, "accept"
    elif hit is not None:
        final, outcome = hit, "veto"
    else:
        final = top
        if not pos:
            outcome = "below_threshold"
        elif kinds[top] == "comp":
            outcome = "abstain_competitor"
        elif kinds[top] == "imp":
            outcome = "impostor_top"
        else:
            row = int(arg_any[top])
            name_words = len(lexicon.fold(entries.names[top]).split())
            reason = "margin" if margin < MARGIN else boundary_check(clip, int(ra[row]), int(rb[row]), name_words)
            outcome = "suggest" if reason else "accept"
    # the place text the rewrite would replace: the exact slot on an exact hit, else the top's matched frames
    row_top = int(arg_any[top])
    match = clip.slot_text if hit is not None else lexicon.fold(
        "".join(clip.text[c] for c in clip.chars_in(int(ra[row_top]), int(rb[row_top]))))
    return {
        "outcome": outcome,
        "reason": reason,
        "accept": outcome == "accept",
        "top_name": entries.names[final],
        "top_kind": str(kinds[final]),
        "margin": round(margin, 2),
        "rw_top_name": entries.names[argbest(best_any, rw_idx)],
        "rw_top_canon_name": entries.names[argbest(best_can, rw_idx)],
        "match": match,
    }


# ---------------------------------------------------------------- CLI

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dumps", type=Path, required=True, help="harness dump_logits_dir")
    ap.add_argument("--model", action="append", required=True, help="SIZE=model dir (repeatable)")
    ap.add_argument("--labels", type=Path, required=True,
                    help='JSON {"<clip>": {"split": str, "gold": [display names]}}; private')
    ap.add_argument("--slot", choices=["trigger", "hint", "full"], default="full")
    ap.add_argument("--gate", choices=sorted(GATES), default="v2", help="trigger gate version (trigger/hint)")
    ap.add_argument("--places", type=Path,
                    help="place candidates JSON with role/prio (default: lexicon.NZ_PLACES, all rewritable)")
    ap.add_argument("--impostors", type=Path, help="impostor list JSON (default: lexicon.IMPOSTORS)")
    ap.add_argument("--betas", default="4", help="β values; the step-2 rule fixes β = 4.0")
    ap.add_argument("--out", type=Path, required=True, help="private output dir")
    args = ap.parse_args(argv)

    betas = [float(x) for x in args.betas.split(",")]
    labels = json.loads(args.labels.read_text())
    places = load_places(args.places, lexicon.NZ_PLACES)
    impostors = load_names(args.impostors, lexicon.IMPOSTORS)
    args.out.mkdir(parents=True, exist_ok=True)

    rows: list[dict] = []
    for spec in args.model:
        size, _, d = spec.partition("=")
        model = Model.load(size, Path(d))
        entries = Entries.build(model, places, impostors)
        exact = {lexicon.fold(n): i for i, n in enumerate(entries.names)}
        print(f"[{size}] rewritable={int(sum(k == 'nz' for k in entries.kinds))} "
              f"competitors={int(sum(k == 'comp' for k in entries.kinds))} "
              f"impostors={int(sum(k == 'imp' for k in entries.kinds))}", file=sys.stderr)
        for json_path in sorted(args.dumps.glob(f"*.{size}.*.json")):
            dump = load_dump(json_path)
            lab = labels.get(dump.clip)
            if lab is None:
                continue
            toks = greedy_tokens(dump.logp, model.blank)
            text, char_tok = greedy_text(model, toks)
            span = slot_for(args.slot, args.gate, toks, dump.logp.shape[0], text, char_tok)
            clip = make_clip(text, char_tok, toks, span)
            row = {"clip": dump.clip, "size": size, "split": lab["split"], "gold": lab["gold"],
                   "gate": GATES[args.gate](text) is not None, "scored": span is not None}
            if span is not None:
                raw, ra, rb = raw_scores(dump, entries, span)
                for beta in betas:
                    row[f"b{beta:g}"] = evaluate(raw, ra, rb, entries, beta, lab["gold"], clip, exact)
            rows.append(row)

    tag = f"{args.slot}.{args.gate}"
    (args.out / f"per_clip.{tag}.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1))
    summary = summarise(rows, betas)
    (args.out / f"summary.{tag}.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1))
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 0


def summarise(rows: list[dict], betas: list[float]) -> dict:
    out: dict = {}
    for size in sorted({r["size"] for r in rows}):
        sz = [r for r in rows if r["size"] == size]
        place = [r for r in sz if r["gold"]]
        nonplace = [r for r in sz if not r["gold"]]
        per_beta = {}
        for beta in betas:
            key = f"b{beta:g}"
            scored = [r for r in sz if key in r]
            gold_rows = [r for r in scored if r["gold"]]
            nonp = [r for r in scored if not r["gold"]]
            per_beta[key] = {
                # denominators are clips; a clip with two gold names counts once
                "gold_clips_scored": len(gold_rows),
                "gold_instances_scored": sum(len(r["gold"]) for r in gold_rows),
                "rank1_rewritable": sum(r[key]["rw_top_name"] in r["gold"] for r in gold_rows),
                "rank1_rewritable_canonical": sum(r[key]["rw_top_canon_name"] in r["gold"] for r in gold_rows),
                "accepted": sum(r[key]["accept"] and r[key]["top_name"] in r["gold"] for r in gold_rows),
                "substituted": sum(r[key]["accept"] and r[key]["top_name"] not in r["gold"] for r in gold_rows),
                "abstained_competitor_won": sum(r[key]["outcome"] == "abstain_competitor" for r in gold_rows),
                "suggested": sum(r[key]["outcome"] == "suggest" for r in gold_rows),
                "suggested_correct": sum(r[key]["outcome"] == "suggest" and r[key]["top_name"] in r["gold"]
                                         for r in gold_rows),
                "suggest_reasons": dict(Counter(r[key]["reason"] for r in gold_rows
                                                if r[key]["outcome"] == "suggest")),
                "gold_outcomes": dict(Counter(r[key]["outcome"] for r in gold_rows)),
                "nonplace_clips_scored": len(nonp),
                "nonplace_false_accept": sum(r[key]["accept"] for r in nonp),
                "nonplace_suggested": sum(r[key]["outcome"] == "suggest" for r in nonp),
                "nonplace_outcomes": dict(Counter(r[key]["outcome"] for r in nonp)),
            }
        out[size] = {
            "clips": len(sz),
            "gate_fired_place_clips": sum(r["gate"] for r in place),
            "place_clips": len(place),
            "gate_fired_nonplace_clips": sum(r["gate"] for r in nonplace),
            "nonplace_clips": len(nonplace),
            "scored_clips": sum(r["scored"] for r in sz),
            "by_beta": per_beta,
        }
    return out


if __name__ == "__main__":
    raise SystemExit(main())
