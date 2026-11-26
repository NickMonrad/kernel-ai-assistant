"""Synthetic tests for suggest_v41 (no audio, no private rows)."""
from __future__ import annotations

import unittest

import numpy as np

import oracle
import suggest_v41 as s5


def ev(**kw) -> dict:
    base = {"outcome": "suggest", "reason": "margin", "top_kind": "nz", "top_name": "Waikato", "match": "waikato"}
    base.update(kw)
    return base


class DecideS5(unittest.TestCase):
    def test_margin_nz_prompts(self):
        d = s5.decide_s5(ev(match="waikat"))
        self.assertEqual(d["kind"], "prompt")
        self.assertEqual(d["name"], "Waikato")

    def test_identity_margin_gives_none(self):
        d = s5.decide_s5(ev(match="waikato"))
        self.assertEqual(d["kind"], "none")

    def test_competitor_top_falls_back_to_s4(self):
        d = s5.decide_s5(ev(top_kind="comp", match="x"))
        self.assertEqual(d["kind"], "none")


class Bounds(unittest.TestCase):
    def test_zero_of_twelve_upper(self):
        self.assertAlmostEqual(s5.upper(0, 12), 0.2425, places=4)

    def test_zero_of_thirty_upper(self):
        self.assertAlmostEqual(s5.upper(0, 30), 0.1135, places=4)


class StartBoundary(unittest.TestCase):
    def setUp(self):
        text = "ab cd"
        char_tok = np.arange(5)
        toks = [(0, i, i + 1) for i in range(5)]
        self.clip = oracle.Clip(text, char_tok, toks, "")

    def test_mid_word_start_fails(self):
        self.assertTrue(s5.start_boundary_fail(self.clip, 4, 5))  # 'd' after 'c'

    def test_word_start_passes(self):
        self.assertFalse(s5.start_boundary_fail(self.clip, 3, 5))  # 'c' after space

    def test_first_char_passes(self):
        self.assertFalse(s5.start_boundary_fail(self.clip, 0, 2))

    def test_no_chars_fails(self):
        self.assertTrue(s5.start_boundary_fail(self.clip, 9, 10))


class Aggregate(unittest.TestCase):
    def row(self, **kw):
        base = {"size": "M", "slot": "hint", "clip": "c", "cls": "gold_navigate", "carrier": "trigger",
                "gold": "Waikato", "s4": {"kind": "prompt", "name": "Waikato"},
                "s5": {"kind": "prompt", "name": "Waikato"}, "silent_start_fail": None}
        base.update(kw)
        return base

    def test_gold_wrong_prompt_fails_p1(self):
        r = self.row(s4={"kind": "prompt", "name": "Hamilton"}, s5={"kind": "prompt", "name": "Hamilton"})
        out = s5.aggregate([r])
        self.assertFalse(out["s4|M|hint"]["pass"]["P1"])

    def test_clean_gold_right_prompt_passes(self):
        out = s5.aggregate([self.row()])
        self.assertTrue(out["s5|M|hint"]["pass"]["all"])
