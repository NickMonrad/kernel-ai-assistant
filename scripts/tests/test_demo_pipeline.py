"""Unit tests for the #1736 demo capture runner and edit pipeline (no device, no ffmpeg)."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import demo_capture as cap  # noqa: E402
import demo_edit as edit  # noqa: E402

UI_XML = """<?xml version='1.0' encoding='UTF-8'?>
<hierarchy rotation="0">
  <node text="" class="android.widget.FrameLayout" content-desc="" bounds="[0,0][1080,2316]">
    <node text="Actions" class="android.widget.TextView" content-desc="" bounds="[400,2200][680,2300]" />
    <node text="" class="android.widget.EditText" content-desc="" bounds="[40,2000][900,2100]" />
    <node text="" class="android.view.View" content-desc="Send" bounds="[920,2000][1040,2100]" />
    <node text="Actions history" class="android.widget.TextView" content-desc="" bounds="[0,100][500,200]" />
  </node>
</hierarchy>"""


class ShotListTest(unittest.TestCase):
    def test_shots_json_is_consistent(self):
        data = cap.load_shots()
        ids = {s["id"] for s in data["shots"]}
        self.assertEqual(data["intro_order"], ["S01", "S02", "S03", "S04", "S05"])
        kinds = {"launch", "tap", "type", "wait", "wait_gone", "sleep", "key", "shell", "host"}
        for shot in data["shots"]:
            self.assertTrue(shot["caption"], shot["id"])
            for step in shot["steps"]:
                self.assertIn(step["do"], kinds, shot["id"])
            if shot.get("model"):
                self.assertTrue(any(s.get("compress") for s in shot["steps"]),
                                f"{shot['id']} model wait must be marked for compression")
        self.assertTrue(set(data["intro_order"]) <= ids)

    def test_select_shots(self):
        data = cap.load_shots()
        self.assertEqual([s["id"] for s in cap.select_shots(data, "S03,S01")], ["S03", "S01"])
        with self.assertRaises(cap.CaptureError):
            cap.select_shots(data, "S99")


class UiHelpersTest(unittest.TestCase):
    def test_escape_input_text(self):
        self.assertEqual(cap.escape_input_text("What's my coffee?"), "What\\'s%smy%scoffee\\?")
        self.assertEqual(cap.escape_input_text("10 minutes"), "10%sminutes")

    def test_find_node_exact_before_substring(self):
        self.assertEqual(cap.find_node(UI_XML, text="Actions"), (540, 2250))
        self.assertEqual(cap.find_node(UI_XML, desc="Send"), (980, 2050))
        self.assertEqual(cap.find_node(UI_XML, cls="android.widget.EditText"), (470, 2050))
        self.assertEqual(cap.find_node(UI_XML, text="history"), (250, 150))
        self.assertIsNone(cap.find_node(UI_XML, desc="Cancel response"))
        self.assertIsNone(cap.find_node("not xml", text="x"))


class PlanTest(unittest.TestCase):
    def take(self, compress):
        return {
            "lead_in_s": 2.0,
            "duration_s": 60.0,
            "events": [
                {"do": "launch", "start": 0.0, "end": 1.5},
                {"do": "tap", "start": 1.5, "end": 3.0},
                {"do": "type", "start": 3.0, "end": 6.0},
            ],
            "compress": compress,
        }

    def test_cuts_navigation_and_keeps_real_time(self):
        segs = edit.plan_segments(self.take([]))
        self.assertEqual(len(segs), 1)
        self.assertAlmostEqual(segs[0].src_start, 4.4)  # lead 2.0 + first input 3.0 - pre-roll 0.6
        self.assertAlmostEqual(segs[0].src_end, 62.0)
        self.assertEqual(segs[0].speed, 1.0)
        self.assertEqual(edit.marker_windows(segs), [])

    def test_compressed_wait_gets_marker_for_whole_span(self):
        segs = edit.plan_segments(self.take([{"start": 6.0, "end": 50.0}]), max_wait=2.5)
        self.assertEqual([s.speed for s in segs], [1.0, 17.6, 1.0])
        self.assertAlmostEqual(segs[1].out_len, 2.5)
        (start, end, factor), = edit.marker_windows(segs)
        self.assertAlmostEqual(start, 3.6)
        self.assertAlmostEqual(end, 6.1)
        self.assertEqual(factor, 18)

    def test_short_wait_is_not_sped_up_but_never_slowed(self):
        segs = edit.plan_segments(self.take([{"start": 6.0, "end": 7.0}]), max_wait=2.5)
        self.assertTrue(all(s.speed == 1.0 for s in segs))

    def test_pick_take(self):
        manifest = {"takes": [
            {"id": "S04", "take": 1, "status": "ok", "recorded_at": "a"},
            {"id": "S04", "take": 2, "status": "failed", "recorded_at": "b"},
            {"id": "S04", "take": 3, "status": "ok", "recorded_at": "c"},
        ]}
        self.assertEqual(edit.pick_take(manifest, "S04", {})["take"], 3)
        self.assertEqual(edit.pick_take(manifest, "S04", {"S04": 1})["take"], 1)
        with self.assertRaises(edit.EditError):
            edit.pick_take(manifest, "S04", {"S04": 2})
        with self.assertRaises(edit.EditError):
            edit.pick_take(manifest, "S05", {})

    def test_fit_text(self):
        self.assertEqual(edit.fit_text("Speaks Kiwi", 54), ("Speaks Kiwi", 54))
        text, size = edit.fit_text("Chat with a local AI. No cloud AI.", 54)
        self.assertEqual(text, "Chat with a local AI.\nNo cloud AI.")
        for caption in (s["caption"] for s in json.loads(edit.SHOTS_FILE.read_text())["shots"]):
            wrapped, size = edit.fit_text(caption, 54)
            longest = max(len(line) for line in wrapped.split("\n"))
            self.assertLessEqual(longest * 0.62 * size, 0.9 * edit.W + 1, caption)


if __name__ == "__main__":
    unittest.main()
