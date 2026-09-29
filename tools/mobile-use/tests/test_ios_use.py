from __future__ import annotations

import json
import tempfile
import time
import unittest
from pathlib import Path

from device_use.runner import CommandResult
from ios_use.agent_device import AgentDeviceSession, map_action, selector_for_text
from ios_use.qa_loop import map_ios_use_action, next_seq, send_action, session_identity, session_matches
from ios_use.simctl import IosUseError


class IosUseQaLoopTest(unittest.TestCase):
    def test_map_tap_text_and_wait_after(self) -> None:
        self.assertEqual(
            map_ios_use_action({"type": "tapText", "text": "joint-slock-test", "waitAfter": 1}),
            [
                {"type": "tapText", "text": "joint-slock-test"},
                {"type": "wait", "seconds": 1.0},
            ],
        )

    def test_map_endpoint_swipe_to_drag(self) -> None:
        self.assertEqual(
            map_ios_use_action(
                {
                    "type": "swipe",
                    "x": 180.0,
                    "y": 760.0,
                    "x2": 180.0,
                    "y2": 320.0,
                    "coordinateSpace": "points",
                }
            ),
            [
                {
                    "type": "drag",
                    "x": 180.0,
                    "y": 760.0,
                    "x2": 180.0,
                    "y2": 320.0,
                    "holdSeconds": 0.05,
                    "coordinateSpace": "points",
                }
            ],
        )

    def test_send_action_reads_result_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            io_dir = Path(temp)
            (io_dir / "res-1.json").write_text(json.dumps({"ok": True, "data": {"label": "Home"}}), encoding="utf-8")
            data = send_action(io_dir, {"type": "tapText", "text": "Home"}, timeout=1)
            self.assertEqual(data, {"label": "Home"})
            self.assertEqual(json.loads((io_dir / "cmd-1.json").read_text(encoding="utf-8"))["text"], "Home")
            self.assertEqual(next_seq(io_dir), 2)

    def test_send_action_raises_harness_error(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            io_dir = Path(temp)
            (io_dir / "res-1.json").write_text(json.dumps({"ok": False, "error": "missing node"}), encoding="utf-8")
            with self.assertRaisesRegex(IosUseError, "missing node"):
                send_action(io_dir, {"type": "tapText", "text": "nope"}, timeout=1)

    def test_session_matches_requires_bundle_and_workspace(self) -> None:
        wanted = session_identity(
            workspace=Path("/tmp/iosApp.xcworkspace"),
            scheme="iosAppUITests",
            configuration="Debug",
            bundle="build.raft.app",
            device="A-UDID",
        )
        self.assertTrue(session_matches(wanted, wanted))
        self.assertFalse(session_matches(None, wanted))
        mismatch = dict(wanted)
        mismatch["bundle"] = "build.raft.app.alpha"
        self.assertFalse(session_matches(mismatch, wanted))
        mismatch = dict(wanted)
        mismatch["workspace"] = "/tmp/other.xcworkspace"
        self.assertFalse(session_matches(mismatch, wanted))

    def test_send_action_times_out(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            started = time.time()
            with self.assertRaisesRegex(IosUseError, "timeout"):
                send_action(Path(temp), {"type": "tap"}, timeout=0.3)
            self.assertGreaterEqual(time.time() - started, 0.3)


class IosUseAgentDeviceTest(unittest.TestCase):
    def test_map_tap_text(self) -> None:
        self.assertEqual(
            map_action({"type": "tapText", "text": "all", "waitAfter": 1}),
            [
                ["press", 'role="button" label="all" || label="all" || text="all"', "--settle"],
                ["__sleep", "1.0"],
            ],
        )
        self.assertIn('role="button" label="say \\"hi\\""', selector_for_text('say "hi"'))

    def test_map_normalized_tap_and_edge_swipe(self) -> None:
        self.assertEqual(
            map_action({"type": "tap", "x": 0.5, "y": 0.25}, screen_px=(400, 800)),
            [["press", "200", "200", "--settle"]],
        )
        self.assertEqual(
            map_action({"type": "swipe", "direction": "right"}),
            [["gesture", "swipe", "right"]],
        )
        self.assertEqual(
            map_action({"type": "swipe", "x": 4, "y": 420, "x2": 280, "y2": 420, "coordinateSpace": "points"}),
            [["swipe", "4", "420", "280", "420"]],
        )

    def test_session_runs_open_then_press(self) -> None:
        calls: list[list[str]] = []

        def fake_run(argv, **kwargs):
            calls.append(list(argv))
            return CommandResult(argv=list(argv), returncode=0, stdout="ok", stderr="")

        session = AgentDeviceSession(
            binary="/opt/agent-device",
            bundle="build.raft.app",
            device="UDID-1",
            session="ios-use-test",
            runner=fake_run,
        )
        session.ensure_open()
        session.apply({"type": "tapText", "text": "Home"}, screen_px=(390, 844))
        self.assertEqual(calls[0][:2], ["/opt/agent-device", "open"])
        self.assertIn("build.raft.app", calls[0])
        self.assertEqual(calls[1][1], "press")
        self.assertIn('label="Home"', calls[1][2])
        self.assertIn("--session", calls[0])
        self.assertIn("ios-use-test", calls[0])

    def test_nonzero_cli_becomes_ios_use_error(self) -> None:
        def fake_run(argv, **kwargs):
            return CommandResult(argv=list(argv), returncode=1, stdout="", stderr="Error (COMMAND_FAILED): boom")

        session = AgentDeviceSession(binary="agent-device", bundle="build.raft.app", device="D", runner=fake_run)
        with self.assertRaisesRegex(IosUseError, "boom"):
            session.ensure_open()


if __name__ == "__main__":
    unittest.main()
