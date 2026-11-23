"""Checks for the #2093 oracle. Run: python3 -m unittest -v test_oracle

- Viterbi span score equals brute force over all paths on tiny random inputs.
- BPE: merges applied lowest-rank first; a toy merge table gives the expected single piece.
- Gate v2: travel and weather phrasings, fuzzy garbled words, and negatives.
- Fixture check on the LibriSpeech logits fixture: needs the M model dir; set
  ASR4ALL_MODEL_M=<dir with config.json and merges.txt>; skipped otherwise.
"""

import itertools
import json
import os
import unittest
from pathlib import Path

import numpy as np

import lexicon
from oracle import (
    SPACE, Entries, Model, bpe_segment, ctc_ext, evaluate, gate_v1, gate_v2, greedy_text, greedy_tokens,
    span_best, span_scores, make_clip,
)

FIXTURES = Path(__file__).resolve().parents[2] / "core/voice/src/test/resources/asr4all/fixtures"


def brute_span_score(pen: np.ndarray, labels: list[int], blank: int) -> float:
    """Max over spans and over all paths in the span that collapse to labels, of Σ pen[t, path_t]."""
    t_len = pen.shape[0]
    best = -np.inf
    for a in range(t_len):
        for b in range(a + 1, t_len + 1):
            for path in itertools.product(range(pen.shape[1]), repeat=b - a):
                collapsed, prev = [], None
                for v in path:
                    if v != blank and v != prev:
                        collapsed.append(v)
                    prev = v
                if collapsed != labels:
                    continue
                best = max(best, sum(pen[a + i, v] for i, v in enumerate(path)))
    return best


class ViterbiTest(unittest.TestCase):
    def test_matches_bruteforce(self):
        rng = np.random.default_rng(0)
        blank = 3
        checked = 0
        for trial in range(30):
            t_len = int(rng.integers(3, 6))
            logp = np.log(rng.dirichlet(np.ones(4), size=t_len))
            pen = logp - logp.max(axis=1, keepdims=True)
            labels = [int(x) for x in rng.integers(0, 3, size=int(rng.integers(1, 3)))]
            if any(labels[i] == labels[i + 1] for i in range(len(labels) - 1)):
                continue  # repeated labels need a separating blank in the path model
            got = span_scores(pen, np.array([ctc_ext(labels, blank)]))[0]
            self.assertAlmostEqual(got, brute_span_score(pen, labels, blank), places=9, msg=f"trial {trial}")
            checked += 1
        self.assertGreater(checked, 10)


def toy_model(merges: list[tuple[str, str]]) -> Model:
    pieces = [SPACE, "t", "h", "e", SPACE + "t", "he", SPACE + "th", SPACE + "the", "a"]
    pieces = [p.replace(SPACE, " ") for p in pieces]
    index = {p: i for i, p in enumerate(pieces)}
    return Model("toy", pieces, index, {pair: r for r, pair in enumerate(merges)})


class BpeTest(unittest.TestCase):
    def test_chain_merges_to_one_piece(self):
        m = toy_model([(SPACE, "t"), (SPACE + "t", "h"), (SPACE + "th", "e")])
        self.assertEqual([m.pieces[i] for i in bpe_segment(m, "the")], [" the"])

    def test_rank_order_decides_split(self):
        # 'h'+'e' ranks first, so the chain breaks: " t" + "he", not " the".
        m = toy_model([("h", "e"), (SPACE, "t"), (SPACE + "t", "h"), (SPACE + "th", "e")])
        self.assertEqual([m.pieces[i] for i in bpe_segment(m, "the")], [" t", "he"])

    def test_segmentation_decodes_to_phrase(self):
        m = toy_model([(SPACE, "t")])
        seg = bpe_segment(m, "the")
        self.assertEqual("".join(m.pieces[i] for i in seg), " the")


class GateTest(unittest.TestCase):
    def test_v2_fires_on_travel_and_weather(self):
        self.assertIsNotNone(gate_v2("navigate to puky kai"))
        self.assertIsNotNone(gate_v2("how far is it from x over to y"))
        self.assertIsNotNone(gate_v2("how long is the drive from x to y"))
        self.assertIsNotNone(gate_v2("what's the weather like in y tomorrow"))

    def test_v2_fuzzy_garbled_verb(self):
        self.assertIsNotNone(gate_v2("navigte to y"))  # edit distance 1 on a 4+ letter word

    def test_v2_anchor_is_preposition_end(self):
        text = "navigate to puka"
        self.assertEqual(text[gate_v2(text)], "o")

    def test_negatives(self):
        self.assertIsNone(gate_v2("remind me to call auntie"))
        self.assertIsNone(gate_v2("turn the torch on"))

    def test_v1_misses_v2_cases(self):
        self.assertIsNone(gate_v1("how far is it over to y"))
        self.assertIsNotNone(gate_v2("how far is it over to y"))


class LexiconTest(unittest.TestCase):
    def test_fold_strips_macrons(self):
        self.assertEqual(lexicon.fold("Whakatāne"), "whakatane")

    def test_canonical_first_and_capped(self):
        vs = lexicon.respellings("Whakatāne")
        self.assertEqual(vs[0], "whakatane")
        self.assertIn("whakatarne", vs)
        self.assertLessEqual(len(vs), lexicon.VARIANT_CAP)
        self.assertEqual(len(vs), len(set(vs)))


@unittest.skipUnless(os.environ.get("ASR4ALL_MODEL_M"), "set ASR4ALL_MODEL_M to the M model dir")
class FixtureTest(unittest.TestCase):
    """LibriSpeech 'quilter' fixture: the phrase spotted on its own frames beats a non-clip word."""

    @classmethod
    def setUpClass(cls):
        cls.model = Model.load("M", Path(os.environ["ASR4ALL_MODEL_M"]))
        meta = json.loads((FIXTURES / "1272-128104-0000_m_stream_c16r4.json").read_text())
        t, v = meta["logits_shape"]
        raw = np.fromfile(FIXTURES / "1272-128104-0000_m_stream_c16r4_logits.f32", dtype="<f4").reshape(t, v)
        raw = raw.astype(np.float64)
        m = raw.max(axis=1, keepdims=True)
        cls.logp = raw - m - np.log(np.exp(raw - m).sum(axis=1, keepdims=True))
        cls.text, _ = greedy_text(cls.model, greedy_tokens(cls.logp, cls.model.blank))

    def per_piece(self, phrase: str) -> float:
        pen = self.logp - self.logp.max(axis=1, keepdims=True)
        seg = bpe_segment(self.model, phrase)
        return span_scores(pen, np.array([ctc_ext(seg, self.model.blank)]))[0] / len(seg)

    def test_clip_word_beats_nonclip_word(self):
        self.assertIn("quilter", self.text.replace(" ", ""), "fixture text lost its 'quilter' content")
        self.assertGreater(self.per_piece("quilter"), self.per_piece("zanzibar"))


class DecisionTest(unittest.TestCase):
    """Step-2 decision rule (no prior): veto, top kind, margin over the runner-up of any kind,
    word boundary and word count. Clips here are one greedy character per frame."""

    @staticmethod
    def _entries(names, kinds):
        n = len(names)
        return Entries(names, kinds, np.zeros(n), np.arange(n), np.zeros(n, dtype=int), np.ones(n), [[0]] * n)

    @staticmethod
    def _clip(text):
        return make_clip(text, np.arange(len(text)), [(0, i, i) for i in range(len(text))], (0, len(text)))

    def _run(self, names, kinds, raw, text, ra, rb, exact=None):
        """Score = raw + 2.0 (beta 2, n = 1 per row)."""
        out = evaluate(np.array(raw, dtype=float), np.array(ra), np.array(rb), self._entries(names, kinds),
                       2.0, [], self._clip(text), exact or {})
        return out

    def test_competitor_top_abstains_and_is_not_rewritten(self):
        out = self._run(["Taupō", "Karori"], ["nz", "comp"], [-1.0, -0.1], "zz", [0, 0], [0, 0])
        self.assertEqual(out["outcome"], "abstain_competitor")  # Karori 1.9 over Taupō 1.0
        self.assertEqual(out["rw_top_name"], "Taupō")  # rank-1 among rewritable is still reported
        self.assertFalse(out["accept"])

    def test_impostor_top_is_not_rewritten(self):
        out = self._run(["Taupō", "Paris"], ["nz", "imp"], [-1.4, -0.5], "zz", [0, 0], [0, 0])
        self.assertEqual(out["outcome"], "impostor_top")  # Paris 1.5 over Taupō 0.6
        self.assertFalse(out["accept"])

    def test_margin_below_one_is_not_rewritten(self):
        out = self._run(["Rotorua Town", "Rotorua City"], ["nz", "nz"], [0.0, -0.5], "rotorua", [0, 0], [6, 6])
        self.assertEqual(out["outcome"], "margin")  # 2.0 vs 1.5

    def test_margin_exactly_one_with_word_boundary_accepts(self):
        out = self._run(["Taupō", "Karori"], ["nz", "comp"], [-0.5, -1.5], "taupo hi", [0, 0], [5, 5])
        self.assertEqual(out["outcome"], "accept")  # Taupō 1.5 over Karori 0.5: margin 1.0
        self.assertEqual(out["top_name"], "Taupō")

    def test_prefix_of_longer_word_is_a_boundary_failure(self):
        bad = self._run(["Bulls"], ["nz"], [-0.5], "bullshit", [0], [5])
        self.assertEqual(bad["outcome"], "boundary")
        good = self._run(["Bulls"], ["nz"], [-0.5], "bulls hit", [0], [5])
        self.assertEqual(good["outcome"], "accept")

    def test_span_with_more_words_than_the_name_allows(self):
        out = self._run(["Bulls"], ["nz"], [-0.5], "bulls a b", [0], [9])
        self.assertEqual(out["outcome"], "word_count")  # 3 words > 1 + 1

    def test_exact_competitor_match_vetoes(self):
        out = self._run(["Karori", "Taupō"], ["comp", "nz"], [0.0, -5.0], "karori", [0, 0], [6, 6],
                        exact={"karori": 0})
        self.assertEqual(out["outcome"], "veto")
        self.assertFalse(out["accept"])

    def test_exact_rewritable_match_rewrites_to_canonical_name(self):
        out = self._run(["Taupō", "Karori"], ["nz", "comp"], [-5.0, -5.0], "taupo", [0, 0], [5, 5],
                        exact={"taupo": 0})
        self.assertEqual(out["outcome"], "accept")
        self.assertEqual(out["top_name"], "Taupō")

    def test_nonpositive_top_is_not_rewritten(self):
        out = self._run(["Taupō"], ["nz"], [-3.0], "zz", [0], [0])
        self.assertEqual(out["outcome"], "below_threshold")


class GateV2Test(unittest.TestCase):
    def test_travel_phrase_fires(self):
        self.assertIsNotNone(gate_v2("take me to Taupō"))

    def test_take_two_and_along_to_do_not_fire(self):
        self.assertIsNone(gate_v2("take two tablets"))
        self.assertIsNone(gate_v2("walk along to the shops"))


class SpanFramesTest(unittest.TestCase):
    """span_best reports the frames of the best span, which the word-boundary check depends on."""

    def test_span_frames_cover_the_matched_labels(self):
        pen = np.full((6, 3), -5.0)
        pen[2, 0] = 0.0  # label 0 at frame 2
        pen[3, 2] = 0.0  # blank (id 2) at frame 3
        pen[4, 1] = 0.0  # label 1 at frame 4
        best, a, b = span_best(pen, np.array([ctc_ext([0, 1], 2)]))
        self.assertAlmostEqual(best[0], 0.0)
        self.assertEqual((a[0], b[0]), (2, 5))


if __name__ == "__main__":
    unittest.main()
