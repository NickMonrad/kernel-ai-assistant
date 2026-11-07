"""Unit tests for the #1736 demo capture runner and edit pipeline (no device, no ffmpeg)."""

from __future__ import annotations

import json
import shutil
import subprocess
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
        self.assertEqual(data["intro_order"], ["S00", "S01", "S02", "S03", "S04", "S05", "S06", "S07A", "S07B"])
        kinds = {"launch", "restart", "tap", "type", "wait", "wait_gone", "sleep", "key", "shell", "host", "open_url",
                 "home_page", "tap_xy"}
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
        # S07B shows the item on the user's own server, in the S23U browser (Nick, 7 Nov 2026).
        self.assertNotIn("device", by_id["S07B"])
        self.assertIn({"do": "open_url", "env": "DEMO_NC_SERVER", "path_env": "DEMO_NC_TASKS_PATH"},
                      [{k: st.get(k) for k in ("do", "env", "path_env")} for st in by_id["S07B"]["steps"]])
        # Opening the list directly keeps the Tasks sidebar (other accounts' lists) off camera.
        self.assertNotIn("tap", [st["do"] for st in by_id["S07B"]["steps"]])

    def test_airplane_badge_only_on_offline_block(self):
        data = cap.load_shots()
        by_id = {s["id"]: s for s in data["shots"]}
        offline = [sid for sid in data["intro_order"] if by_id[sid].get("offline")]
        self.assertEqual(offline, ["S00", "S01", "S02", "S03", "S04", "S05"])
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

    def test_title_card_slogan_and_icon(self):
        data = json.loads(edit.SHOTS_FILE.read_text())
        self.assertEqual(data["title_card"]["lines"], ["Can you handle the Jandal?"])
        svg = edit.icon_svg()
        self.assertIsNotNone(svg)
        self.assertIn('viewBox="0 0 108 108"', svg)  # whole jandal, not the 72-unit safe zone
        self.assertIn("#4F7942", svg)  # the launcher background green

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
        hidden = xml.replace('bounds="[0,100][500,200]"', 'bounds="[0,0][0,0]"')
        self.assertEqual(cap.nodes_containing(hidden, ["demo-user"]), [])  # zero-size node
        self.assertEqual(cap.nodes_containing("not xml", ["x"]), [])

    def test_airplane_commands(self):
        self.assertEqual(cap.airplane_commands(True),
                         ["cmd connectivity airplane-mode enable", "cmd wifi set-wifi-enabled disabled"])
        self.assertEqual(cap.airplane_commands(False),
                         ["cmd connectivity airplane-mode disable", "cmd wifi set-wifi-enabled enabled"])

    def test_validated_network_is_the_active_default_only(self):
        # Shapes from the S21 (Android 15): an IMS-only mobile network carries EVER_VALIDATED
        # and IS_VALIDATED, but only the active default network counts.
        mobile = ("  NetworkAgentInfo{network{111}  ni{MOBILE[LTE] CONNECTED extra: IMS} "
                  "Score(Policies : EVER_EVALUATED&EVER_VALIDATED&IS_VALIDATED ; KeepConnected : 0)")
        wifi = ("  NetworkAgentInfo{network{112}  ni{WIFI CONNECTED extra: } "
                "Score(Policies : TRANSPORT_PRIMARY&EVER_VALIDATED&IS_VALIDATED ; KeepConnected : 0)")
        self.assertTrue(cap.has_validated_network(f"Active default network: 112\n{mobile}\n{wifi}"))
        self.assertFalse(cap.has_validated_network(f"Active default network: none\n{mobile}\n{wifi}"))
        self.assertFalse(cap.has_validated_network(
            f"Active default network: 112\n{mobile}\n{wifi.replace('&IS_VALIDATED', '')}"))
        # network{11} must not match network{112}
        self.assertFalse(cap.has_validated_network(f"Active default network: 11\n{wifi}"))

    def test_lead_in_from_anchor(self):
        # S23U measurement: app appeared at 1.573 s in the clip, am start -W returned at 0.122 s.
        self.assertAlmostEqual(cap.lead_in_from_anchor([1.573, 4.268], 0.122), 1.451)
        self.assertIsNone(cap.lead_in_from_anchor([], 0.1))
        self.assertIsNone(cap.lead_in_from_anchor([9.0], 0.1))  # implausible: no launch frame
        log = "[Parsed_showinfo_1 @ 0x0] n:0 pts:1 pts_time:1.573 duration:1\n... pts_time:4.268 ..."
        self.assertEqual(cap.scene_changes(log), [1.573, 4.268])

    def test_count_matches_and_root_size(self):
        self.assertEqual(cap.count_matches(UI_XML, "actions"), 2)
        self.assertEqual(cap.count_matches("not xml", "x"), 0)
        self.assertEqual(cap.root_size(UI_XML), (1080, 2316))

    def test_shade_open(self):
        self.assertFalse(cap.shade_open('<node package="com.kernel.ai.debug" /><node package="com.kernel.ai.debug" />'))
        self.assertTrue(cap.shade_open('<node package="com.android.systemui" /><node package="com.android.systemui" />'
                                       '<node package="com.kernel.ai.debug" />'))
        self.assertFalse(cap.shade_open(UI_XML))

    def test_redact_needles(self):
        self.assertEqual(cap.redact_needles(["https://cloud.example/", "Demo"]),
                         ["https://cloud.example/", "cloud.example/", "cloud.example", "example", "Demo"])
        needles = cap.redact_needles(["https://nextcloud.someone-home.duckdns.org", "demo-user"])
        self.assertIn("someone-home", needles)  # survives "nextcloud.someone-home.duck…"
        self.assertNotIn("nextcloud", needles)  # would blur "Sync with Nextcloud"
        self.assertNotIn("duckdns", needles)

    def test_load_env_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "demo.env"
            f.write_text("# test account\nDEMO_NC_SERVER=https://cloud.example\nexport DEMO_NC_USER='demo'\n"
                         "DEMO_WAKE_CMD=\"python3 scripts/demo_wake.py --source X\"\n\nnot a line\n")
            env = {"DEMO_NC_USER": "already-set"}
            self.assertEqual(cap.load_env_file(f, env), ["DEMO_NC_SERVER", "DEMO_WAKE_CMD"])
            self.assertEqual(env, {"DEMO_NC_USER": "already-set", "DEMO_NC_SERVER": "https://cloud.example",
                                   "DEMO_WAKE_CMD": "python3 scripts/demo_wake.py --source X"})
            self.assertEqual(cap.load_env_file(Path(tmp) / "missing.env", {}), [])

    def test_unlock_pin_is_read_privately(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "device.env"
            f.write_text("S23U_PIN=1234\n")
            env = {"DEMO_UNLOCK_PIN_FILE": str(f)}
            self.assertEqual(cap.unlock_pin(env), "1234")
            self.assertNotIn("S23U_PIN", env)  # never copied into the environment
            self.assertIsNone(cap.unlock_pin({}))
            f.write_text("S23U_PIN=not-a-pin\n")
            self.assertIsNone(cap.unlock_pin(env))

    def test_unlock_failure_does_not_leak_the_pin(self):
        dev = cap.Device("SERIAL", "pkg", adb="adb")

        def fake_run(*args, timeout=30):
            if args[:3] == ("shell", "input", "text"):
                raise cap.CaptureError(f"adb failed ({' '.join(args)}): boom")
            return ""

        dev.run = fake_run
        with self.assertRaises(cap.CaptureError) as ctx:
            with unittest.mock.patch.object(cap.time, "sleep"):
                dev.unlock("98765")
        self.assertNotIn("98765", str(ctx.exception))
        self.assertIsNone(ctx.exception.__cause__)

    def test_intro_typing_is_sped_up_with_a_marker(self):
        data = cap.load_shots()
        by_id = {s["id"]: s for s in data["shots"]}
        for sid in data["intro_order"]:
            for st in by_id[sid]["steps"]:
                if st["do"] == "type":
                    self.assertTrue(st.get("compress") and st.get("compress_to", 9) <= 1.0, sid)

    def test_s00_navigates_off_camera(self):
        s00 = next(s for s in cap.load_shots()["shots"] if s["id"] == "S00")
        self.assertEqual([st["do"] for st in s00["setup"]], ["home_page"])
        self.assertNotIn("home_page", [st["do"] for st in s00["steps"]])

    def test_every_shot_starts_with_the_sync_anchor(self):
        for shot in cap.load_shots()["shots"]:
            first_active = next(st["do"] for st in shot["steps"] if st["do"] != "sleep")
            self.assertIn(first_active, ("launch", "restart"), shot["id"])


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

    def test_span_compress_to_overrides_max_wait(self):
        segs = edit.plan_segments(self.take([{"start": 3.0, "end": 6.0, "max_s": 0.8}]), max_wait=2.5)
        self.assertEqual([s.speed for s in segs], [1.0, 3.75, 1.0])
        self.assertAlmostEqual(segs[1].out_len, 0.8)
        self.assertEqual(len(edit.marker_windows(segs)), 1)

    def test_s03_typing_is_compressed_and_marked(self):
        by_id = {s["id"]: s for s in cap.load_shots()["shots"]}
        typed = [s for s in by_id["S03"]["steps"] if s["do"] == "type"]
        self.assertEqual(len(typed), 2)  # timer and alarm both stay in the shot
        self.assertTrue(all(s.get("compress") and s.get("compress_to") for s in typed))

    def test_missing_lead_in_is_refused(self):
        take = self.take([])
        take["lead_in_s"] = None
        with self.assertRaises(edit.EditError):
            edit.plan_segments(take)

    def test_scale_redact(self):
        take = {"id": "S07A", "redact": [[100, 200, 300, 260]], "screen_size": [1440, 3088], "clip_size": [1080, 2316]}
        self.assertEqual(edit.scale_redact(take), [[75, 150, 225, 195]])
        take["clip_size"] = [2316, 1080]  # rotated
        with self.assertRaises(edit.EditError):
            edit.scale_redact(take)
        take["clip_size"] = None
        with self.assertRaises(edit.EditError):
            edit.scale_redact(take)
        self.assertEqual(edit.scale_redact({"id": "S06", "redact": []}), [])

    def test_quick_action_results_are_checked(self):
        by_id = {s["id"]: s for s in cap.load_shots()["shots"]}
        for sid, text in (("S02", "shopping list"), ("S03", "Timer set for"), ("S03", "Alarm set for"),
                          ("S06", "°"), ("S07A", "shopping list")):
            self.assertIn({"text": text, "new": True}, [{k: st.get(k) for k in ("text", "new")}
                                                         for st in by_id[sid]["steps"] if st["do"] == "wait"], sid)

    def test_adjacent_compressed_spans_each_get_a_marker(self):
        segs = edit.plan_segments(self.take([{"start": 3.0, "end": 4.0, "max_s": 0.3},
                                             {"start": 4.0, "end": 6.0, "max_s": 0.8}]))
        self.assertEqual([s.speed for s in segs], [1.0, 3.333, 2.5, 1.0])
        self.assertEqual(len(edit.marker_windows(segs)), 2)

    def test_tail_pad_fills_a_clip_that_ends_early(self):
        seg = edit.Segment(10.0, 16.0, 1.0)
        self.assertEqual(edit.tail_pad(seg, 14.5), 1.5)
        self.assertEqual(edit.tail_pad(seg, 17.0), 0.0)
        self.assertEqual(edit.tail_pad(seg, None), 0.0)
        self.assertEqual(edit.tail_pad(edit.Segment(10.0, 16.0, 2.0), 14.0), 1.0)
        self.assertIn("tpad=stop_mode=clone:stop_duration=1.5", edit.segment_filter(1.0, 1.5))
        self.assertNotIn("tpad", edit.segment_filter(1.0))

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


def screen(*texts: str) -> str:
    nodes = "".join(f'<node text="{t}" class="android.widget.TextView" content-desc="" bounds="[0,{i * 100}][1080,{i * 100 + 90}]" />'
                    for i, t in enumerate(texts))
    return f'<hierarchy rotation="0"><node text="" class="x" content-desc="" bounds="[0,0][1080,2316]">{nodes}</node></hierarchy>'


class FakeDevice:
    """Plays back a list of screens; the last one repeats."""

    def __init__(self, screens):
        self.screens, self.taps, self.package = list(screens), [], "com.kernel.ai.debug"

    def dump_ui(self):
        return self.screens.pop(0) if len(self.screens) > 1 else self.screens[0]

    def tap_xy(self, x, y):
        self.taps.append((x, y))

    def run(self, *args, **kw):
        return ""

    def shell(self, cmd, **kw):
        return getattr(self, "shell_reply", "mInputShown=false")


class RunnerTest(unittest.TestCase):
    def test_wait_new_ignores_an_earlier_take(self):
        old = screen("Timer set for 10 minutes.")
        dev = FakeDevice([old, old, screen("Timer set for 10 minutes.", "Timer set for 10 minutes.")])
        runner = cap.ShotRunner(dev, 0.0, cap.StepLog(), poll=0)
        runner.baseline_xml = old
        runner.wait_new("Timer set for", timeout=5)
        with self.assertRaises(cap.CaptureError):
            runner.baseline_xml = screen("Timer set for", "Timer set for")
            runner.wait_new("Timer set for", timeout=0)

    def test_mangled_typing_is_retyped_before_send(self):
        field = '<node text="{}" class="android.widget.EditText" content-desc="" bounds="[0,1000][900,1100]" />'
        send = '<node text="" class="android.view.View" content-desc="Send" bounds="[900,1000][1080,1100]" />'
        wrap = '<hierarchy><node text="" class="x" content-desc="" bounds="[0,0][1080,2316]">{}</node></hierarchy>'
        empty = wrap.format(field.format("") + send)
        bad = wrap.format(field.format("Give me tree ideas for a rainy Sundayh") + send)
        good = wrap.format(field.format("Give me three ideas for a rainy Sunday") + send)
        dev = FakeDevice([empty, bad, good])
        dev.cmds = []
        dev.run = lambda *a, **k: dev.cmds.append(a) or ""
        runner = cap.ShotRunner(dev, 0.0, cap.StepLog(), poll=0)
        with unittest.mock.patch("time.sleep"):
            runner.type_and_send("Give me three ideas for a rainy Sunday")
        self.assertEqual(dev.taps[-1], (990, 1050))  # Send tapped only after the read-back matched
        self.assertIn(("shell", "input", "text", "Give%sme%sthree%sideas%sfor%sa%srainy%sSunday"), dev.cmds)
        dev = FakeDevice([empty, bad, bad])
        dev.run = lambda *a, **k: ""
        runner = cap.ShotRunner(dev, 0.0, cap.StepLog(), poll=0)
        with unittest.mock.patch("time.sleep"), self.assertRaises(cap.CaptureError):
            runner.type_and_send("Give me three ideas for a rainy Sunday")
        self.assertNotIn((990, 1050), dev.taps)  # never sent

    def test_open_url_uses_the_env_address_only(self):
        dev = FakeDevice([screen("x")])
        dev.cmds = []
        dev.run = lambda *a, **k: dev.cmds.append(a) or ""
        runner = cap.ShotRunner(dev, 0.0, cap.StepLog(), poll=0)
        step = {"do": "open_url", "env": "DEMO_NC_SERVER", "path": "/apps/tasks/"}
        with unittest.mock.patch("time.sleep"), \
                unittest.mock.patch.dict("os.environ", {"DEMO_NC_SERVER": "https://cloud.example/"}):
            runner.run_step(step)
        self.assertIn(("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
                       "-d", "https://cloud.example/apps/tasks/"), dev.cmds)
        with unittest.mock.patch("time.sleep"), unittest.mock.patch.dict(
                "os.environ", {"DEMO_NC_SERVER": "https://cloud.example", "DEMO_NC_TASKS_PATH": "/apps/tasks/calendars/x"}):
            runner.run_step(dict(step, path_env="DEMO_NC_TASKS_PATH"))
        self.assertIn(("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
                       "-d", "https://cloud.example/apps/tasks/calendars/x"), dev.cmds)
        for bad in ("", "http://cloud.example"):
            with unittest.mock.patch.dict("os.environ", {"DEMO_NC_SERVER": bad}), self.assertRaises(cap.CaptureError):
                runner.run_step(step)

    def test_optional_redact_values(self):
        shot = {"id": "S07B", "redact_env": ["DEMO_NC_SERVER"], "redact_env_optional": ["DEMO_NC_DISPLAY_NAME"]}
        with unittest.mock.patch.dict("os.environ", {"DEMO_NC_SERVER": "https://c.example"}, clear=False):
            os_env = __import__("os").environ
            os_env.pop("DEMO_NC_DISPLAY_NAME", None)
            self.assertEqual(cap.redact_values(shot), ["https://c.example"])
            with unittest.mock.patch.dict("os.environ", {"DEMO_NC_DISPLAY_NAME": "Jandal Test"}):
                self.assertEqual(cap.redact_values(shot), ["https://c.example", "Jandal Test"])

    def test_keyboard_is_hidden_after_send_only_when_shown(self):
        field = '<node text="{}" class="android.widget.EditText" content-desc="" bounds="[0,1000][900,1100]" />'
        send = '<node text="" class="android.view.View" content-desc="Send" bounds="[900,1000][1080,1100]" />'
        wrap = '<hierarchy><node text="" class="x" content-desc="" bounds="[0,0][1080,2316]">{}</node></hierarchy>'
        for shown, expect_back in (("mInputShown=true", True), ("mInputShown=false", False)):
            dev = FakeDevice([wrap.format(field.format("") + send), wrap.format(field.format("hi") + send)])
            dev.cmds, dev.shell_reply = [], shown
            dev.run = lambda *a, **k: dev.cmds.append(a) or ""
            runner = cap.ShotRunner(dev, 0.0, cap.StepLog(), poll=0)
            with unittest.mock.patch("time.sleep"):
                runner.type_and_send("hi")
            self.assertEqual(("shell", "input", "keyevent", "KEYCODE_BACK") in dev.cmds, expect_back, shown)

    def test_redaction_scans_every_screen_read_in_wait_loops(self):
        # The username shows only on an intermediate screen, between steps.
        dev = FakeDevice([screen("Loading"), screen("Signed in as demo-user"), screen("cheese")])
        log = cap.StepLog()
        runner = cap.ShotRunner(dev, 0.0, log, poll=0, redact=["demo-user"])
        with unittest.mock.patch("time.sleep"):
            runner.run_step({"do": "wait", "text": "cheese", "timeout": 5})
        self.assertEqual(log.redact, [[0, 0, 1080, 90]])


class WakeScriptTest(unittest.TestCase):
    def test_broadcast_uses_the_debug_receiver_and_approved_volume(self):
        import demo_wake
        args = demo_wake.broadcast_args("demo-s01-1-wake", "natural_wake", 9)
        self.assertEqual(args[:7], ["shell", "am", "broadcast", "-n", demo_wake.RECEIVER, "-a", demo_wake.ACTION])
        self.assertIn("natural_wake", args)
        self.assertEqual(args[args.index("volume_index") + 1], "9")
        self.assertTrue(demo_wake.RECEIVER.startswith("com.kernel.ai.debug/"))

    def test_broadcast_outcome(self):
        import demo_wake
        # Shape seen on the S21 when the app was in the background.
        self.assertEqual(demo_wake.broadcast_outcome(
            'Broadcasting: Intent { ... }\nBroadcast completed: result=1, data="audio_focus_denied"\n'),
            (1, "audio_focus_denied"))
        self.assertEqual(demo_wake.broadcast_outcome("Broadcast completed: result=0\n"), (0, ""))
        self.assertEqual(demo_wake.broadcast_outcome("garbage")[0], None)


@unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg not installed")
class FfmpegSmokeTest(unittest.TestCase):
    def test_blur_graph_and_segment_filter_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp) / "src.mp4", Path(tmp) / "out.mp4"
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=s=1080x2316:r=30:d=1",
                            "-pix_fmt", "yuv420p", str(src)], check=True)
            boxes = edit.blur_boxes([[40, 2000, 900, 2100], [1000, 2250, 1080, 2316], [10, 10, 26, 26]])
            graph = f"{edit.blur_graph(boxes)};[pre]{edit.segment_filter(2.0)}[v]"
            res = subprocess.run(["ffmpeg", "-v", "error", "-i", str(src), "-filter_complex", graph, "-map", "[v]",
                                  "-frames:v", "5", str(out)], capture_output=True, text=True)
            self.assertEqual(res.returncode, 0, res.stderr)


if __name__ == "__main__":
    unittest.main()
