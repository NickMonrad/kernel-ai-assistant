"""Tests for the v4 suggest-first decision (suggest_v4.py). Synthetic oracle rows only."""
import unittest

import suggest_v4


def row(outcome, top_kind="nz", top="Levin", match="levin", reason=None):
    return {"outcome": outcome, "reason": reason, "accept": outcome == "accept", "top_name": top,
            "top_kind": top_kind, "margin": 2.0, "rw_top_name": top, "rw_top_canon_name": top, "match": match}


class DecideTest(unittest.TestCase):
    def test_accept_with_other_heard_text_prompts(self):
        d = suggest_v4.decide(row("accept", match="lemon"))
        self.assertEqual((d["kind"], d["name"]), ("prompt", "Levin"))

    def test_identity_is_silent_canonical_spelling(self):
        d = suggest_v4.decide(row("accept", top="Hāwera", match="hawera"))
        self.assertEqual((d["kind"], d["name"]), ("silent", "Hāwera"))

    def test_word_count_still_prompts(self):
        d = suggest_v4.decide(row("suggest", match="levin road", reason="word_count"))
        self.assertEqual(d["kind"], "prompt")

    def test_margin_and_boundary_block_prompt(self):
        for reason in ("margin", "boundary"):
            self.assertEqual(suggest_v4.decide(row("suggest", reason=reason))["kind"], "none")

    def test_competitor_top_and_veto_block_prompt(self):
        self.assertEqual(suggest_v4.decide(row("abstain_competitor", top_kind="comp"))["kind"], "none")
        self.assertEqual(suggest_v4.decide(row("impostor_top", top_kind="imp"))["kind"], "none")
        self.assertEqual(suggest_v4.decide(row("veto", reason="homograph"))["kind"], "none")
        self.assertEqual(suggest_v4.decide(row("below_threshold"))["kind"], "none")


class ScoreTest(unittest.TestCase):
    def test_gold_and_non_gold_counts(self):
        rows = [
            {"clip": "g1", "size": "M", "gold": ["Levin"], "b5": row("accept", match="lemon")},
            {"clip": "g2", "size": "M", "gold": ["Levin"], "b5": row("accept", top="Masterton", match="x")},
            {"clip": "n1", "size": "M", "gold": [], "b5": row("accept", match="paris")},
        ]
        classes = {"g1": "gold_bare", "g2": "gold_bare", "n1": "fb_foreign_city"}
        out = suggest_v4.score(rows, classes, beta="b5")
        self.assertEqual(out["M gold_bare"]["right_prompt"], 1)
        self.assertEqual(out["M gold_bare"]["wrong_prompt"], 1)
        self.assertEqual(out["M fb_foreign_city"]["false_prompt"], 1)


if __name__ == "__main__":
    unittest.main()
