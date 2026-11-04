"""Unit tests for the #1736 demo capture runner and edit pipeline (no device, no ffmpeg)."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
import unittest.mock
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
        self.assertEqual(data["intro_order"], ["S01", "S02", "S03", "S04", "S05", "S06", "S07A", "S07B"])
        kinds = {"launch", "restart", "tap", "type", "wait", "wait_gone", "sleep", "key", "shell", "host"}
        for shot in data["shots"]:
            self.assertTrue(shot["caption"], shot["id"])
            for step in shot["steps"]:
                self.assertIn(step["do"], kinds, shot["id"])
            if shot.get("model"):
                self.assertTrue(any(s.get("compress") for s in shot["steps"]),
                                f"{shot['id']} model wait must be marked for compression")
        self.assertTrue(set(data["intro_order"]) <= ids)

    def test_intro_order_and_captions_match_review(self):
        by_id = {s["id"]: s for s in cap.load_shots()["shots"]}
        self.assertEqual(by_id["S01"]["caption"], 'Optional "Hey Jandal" wake word')
        self.assertNotIn("hands-free", by_id["S01"]["caption"].lower())
        self.assertEqual(by_id["S02"]["caption"], "Lists that understand Kiwi")
        self.assertIn("Chuck bananas on the grocery list",
                      [s.get("text") for s in by_id["S02"]["steps"]])
        self.assertEqual(by_id["S03"]["caption"], "Timers and alarms, instantly")
        self.assertIn("What's the weather like in Wellington?",
                      [s.get("text") for s in by_id["S06"]["steps"]])
        self.assertEqual(by_id["S07B"]["device"], "second")

    def test_airplane_badge_only_on_offline_block(self):
        data = cap.load_shots()
        by_id = {s["id"]: s for s in data["shots"]}
        offline = [sid for sid in data["intro_order"] if by_id[sid].get("offline")]
        self.assertEqual(offline, ["S01", "S02", "S03", "S04", "S05"])
        for sid in ("S06", "S07A", "S07B"):
            self.assertFalse(by_id[sid].get("offline"), f"{sid} needs the network; never under the badge")

    def test_nextcloud_shots_require_blur(self):
        by_id = {s["id"]: s for s in cap.load_shots()["shots"]}
        for sid in ("S07A", "S07B"):
            self.assertEqual(by_id[sid]["redact_env"], ["DEMO_NC_SERVER", "DEMO_NC_USER"])
        with unittest.mock.patch.dict("os.environ", {"DEMO_NC_SERVER": "", "DEMO_NC_USER": "u"}):
            with self.assertRaises(cap.CaptureError):
                cap.redact_values(by_id["S07A"])
        with unittest.mock.patch.dict("os.environ", {"DEMO_NC_SERVER": "cloud.example", "DEMO_NC_USER": "u"}):
            self.assertEqual(cap.redact_values(by_id["S07A"]), ["cloud.example", "u"])

    def test_second_device_shots_cannot_be_offline(self):
        bad = {"shots": [{"id": "X", "device": "second", "offline": True, "steps": []}]}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "shots.json"
            path.write_text(json.dumps(bad))
            with self.assertRaises(cap.CaptureError):
                cap.load_shots(path)

    def test_end_card_variants(self):
        data = json.loads(edit.SHOTS_FILE.read_text())
        beta = edit.select_end_card(data, "beta")
        launch = edit.select_end_card(data, "launch")
        self.assertTrue(any("Join the beta" in line for line in beta["lines"]))
        self.assertFalse(any("Google Play" in line for line in beta["lines"]))
        self.assertTrue(any("Free on Google Play" in line for line in launch["lines"]))
        with self.assertRaises(edit.EditError):
            edit.select_end_card(data, "draft")

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

    def test_nodes_containing(self):
        xml = UI_XML.replace('text="Actions history"', 'text="Signed in as Demo-User on cloud.example"')
        self.assertEqual(cap.nodes_containing(xml, ["demo-user", "cloud.example"]), [(0, 100, 500, 200)])
        self.assertEqual(cap.nodes_containing(xml, [""]), [])
        self.assertEqual(cap.nodes_containing("not xml", ["x"]), [])

    def test_airplane_commands(self):
        self.assertEqual(cap.airplane_commands(True), ["cmd connectivity airplane-mode enable", "svc wifi disable"])
        self.assertEqual(cap.airplane_commands(False), ["cmd connectivity airplane-mode disable", "svc wifi enable"])
        dump = "  NetworkAgentInfo{network{100}  nc{[ Transports: WIFI Capabilities: INTERNET&VALIDATED]}}"
        self.assertTrue(cap.has_validated_network(dump))
        self.assertFalse(cap.has_validated_network(dump.replace("&VALIDATED", "")))


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

    def test_start_here_overrides_first_input(self):
        take = self.take([])
        take["events"][1]["start_here"] = True
        self.assertAlmostEqual(edit.plan_segments(take)[0].src_start, 2.9)  # 2.0 + 1.5 - 0.6

    def test_blur_boxes_and_graph(self):
        self.assertEqual(edit.blur_boxes([[101, 201, 300, 251]]), [(88, 188, 224, 76)])
        self.assertEqual(edit.blur_graph([]), "[0:v]null[pre]")
        graph = edit.blur_graph(edit.blur_boxes([[0, 0, 100, 50], [200, 300, 400, 360]]))
        self.assertTrue(graph.startswith("[0:v]split=3[base][c0][c1]"))
        self.assertIn("[base][b0]overlay=0:0[o0]", graph)
        self.assertTrue(graph.endswith("[o0][b1]overlay=188:288[pre]"))

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
