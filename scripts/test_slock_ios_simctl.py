from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "slock_ios_simctl.py"


def load_helper():
    spec = importlib.util.spec_from_file_location("slock_ios_simctl", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"unable to load {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SlockIosSimctlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.helper = load_helper()

    def test_tap_routes_window_points_to_xcuitest(self) -> None:
        args = self.helper.build_parser().parse_args(["--device", "A-UDID", "tap", "180", "760"])
        with mock.patch.object(self.helper, "run_xcuitest_actions", return_value={"device": "A-UDID"}) as run:
            result = self.helper.cmd_tap(args)

        self.assertEqual(
            run.call_args.args[1],
            [{"type": "tap", "x": 180.0, "y": 760.0, "coordinateSpace": "points"}],
        )
        self.assertEqual(result["input_backend"], "XCUITest")

    def test_swipe_preserves_arbitrary_normalized_endpoints(self) -> None:
        args = self.helper.build_parser().parse_args(
            [
                "--device",
                "A-UDID",
                "swipe",
                "0.5",
                "0.8",
                "0.5",
                "0.2",
                "--coordinate-space",
                "normalized",
            ]
        )
        with mock.patch.object(self.helper, "run_xcuitest_actions", return_value={"device": "A-UDID"}) as run:
            self.helper.cmd_swipe(args)

        self.assertEqual(
            run.call_args.args[1],
            [
                {
                    "type": "swipe",
                    "x": 0.5,
                    "y": 0.8,
                    "x2": 0.5,
                    "y2": 0.2,
                    "coordinateSpace": "normalized",
                }
            ],
        )

    def test_global_coordinates_fail_before_any_input_backend_runs(self) -> None:
        args = self.helper.build_parser().parse_args(
            ["--device", "A-UDID", "tap", "620", "980", "--coordinate-space", "global"]
        )
        with mock.patch.object(self.helper, "run_xcuitest_actions") as run:
            with self.assertRaisesRegex(self.helper.CliError, "intentionally unsupported"):
                self.helper.cmd_tap(args)
        run.assert_not_called()

    def test_booted_alias_resolves_to_one_concrete_udid(self) -> None:
        args = self.helper.build_parser().parse_args(["tap", "0.5", "0.5", "--coordinate-space", "normalized"])
        payload = '{"devices":{"runtime":[{"udid":"ONLY-UDID","state":"Booted","isAvailable":true}]}}'
        completed = subprocess.CompletedProcess([], 0, stdout=payload, stderr="")
        with mock.patch.object(self.helper, "run_simctl", return_value=completed):
            self.assertEqual(self.helper.resolve_xcuitest_device(args), "ONLY-UDID")


class IosUseXcuitestSelectionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        mobile_use_src = REPO_ROOT / "tools" / "mobile-use" / "src"
        sys.path.insert(0, str(mobile_use_src))
        from ios_use.config import IosConfig
        from ios_use.simctl import IosSimulator

        cls.IosConfig = IosConfig
        cls.IosSimulator = IosSimulator

    def test_interact_starts_persistent_qa_loop_not_oneshot_ios_use(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            workspace = root / "iosApp.xcworkspace"
            workspace.mkdir()
            config = self.IosConfig(device="A-UDID", workspace=workspace, derived_data=root)
            simulator = self.IosSimulator(config)
            calls: list[list[str]] = []
            io_dir = root / "ios-use-qa-loop" / "A-UDID" / "io"

            def fake_xcodebuild(args, **_kwargs):
                calls.append(args)
                from device_use.runner import CommandResult

                return CommandResult(argv=["xcodebuild", *args], returncode=0, stdout="", stderr="")

            def fake_start(args, *, log_path, env=None):
                calls.append(args)
                captured["env"] = env
                io_dir.mkdir(parents=True, exist_ok=True)
                (io_dir / "ready.json").write_text('{"ok":true,"ready":true}', encoding="utf-8")
                (io_dir / "res-1.json").write_text('{"ok":true,"data":{"tapped":true}}', encoding="utf-8")
                return 4242

            captured: dict[str, object] = {}
            with mock.patch.object(simulator, "xcodebuild", side_effect=fake_xcodebuild), mock.patch.object(
                simulator, "start_xcodebuild_background", side_effect=fake_start
            ):
                result = simulator.interact([{"type": "tap", "x": 0.5, "y": 0.5}], device="A-UDID")

        self.assertEqual(result["harness"], "QaAcceptanceLoopTests")
        self.assertEqual(result["loop_pid"], 4242)
        self.assertTrue(any("build-for-testing" in call for call in calls))
        self.assertTrue(
            any("-only-testing:iosAppUITests/QaAcceptanceLoopTests" in call for call in calls)
        )
        self.assertFalse(
            any("IosUseInteractionTests" in part for call in calls for part in call)
        )
        env = captured["env"]
        self.assertIsInstance(env, dict)
        self.assertEqual(env["TEST_RUNNER_QA_LOOP_IO_DIR"], str(io_dir))
        self.assertEqual(env["TEST_RUNNER_QA_LOOP_BUNDLE"], "build.raft.app")

    def _write_running_loop(self, root: Path, workspace: Path, *, bundle: str, pid: int) -> Path:
        from ios_use.qa_loop import session_identity, write_session

        io_root = root / "ios-use-qa-loop" / "A-UDID"
        io_dir = io_root / "io"
        io_dir.mkdir(parents=True)
        (io_dir / "ready.json").write_text('{"ok":true,"ready":true}', encoding="utf-8")
        (io_root / "loop.pid").write_text(str(pid), encoding="utf-8")
        write_session(
            io_root,
            session_identity(
                workspace=workspace,
                scheme="iosAppUITests",
                configuration="Debug",
                bundle=bundle,
                device="A-UDID",
            ),
            pid=pid,
        )
        return io_dir

    def test_interact_reuses_running_qa_loop(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            workspace = root / "iosApp.xcworkspace"
            workspace.mkdir()
            io_dir = self._write_running_loop(root, workspace, bundle="build.raft.app", pid=os.getpid())
            (io_dir / "res-1.json").write_text('{"ok":true}', encoding="utf-8")
            config = self.IosConfig(device="A-UDID", workspace=workspace, derived_data=root)
            simulator = self.IosSimulator(config)
            with mock.patch.object(simulator, "xcodebuild") as xcodebuild, mock.patch.object(
                simulator, "start_xcodebuild_background"
            ) as start:
                result = simulator.interact([{"type": "tapText", "text": "joint-slock-test"}], device="A-UDID")
            xcodebuild.assert_not_called()
            start.assert_not_called()
            self.assertEqual(result["harness"], "QaAcceptanceLoopTests")
            self.assertEqual(result["loop_pid"], os.getpid())
            cmd = json.loads((io_dir / "cmd-1.json").read_text(encoding="utf-8"))
            self.assertEqual(cmd, {"type": "tapText", "text": "joint-slock-test"})

    def test_interact_does_not_reuse_loop_when_bundle_changes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            workspace = root / "iosApp.xcworkspace"
            workspace.mkdir()
            io_dir = self._write_running_loop(root, workspace, bundle="build.raft.app", pid=1111)
            config = self.IosConfig(device="A-UDID", workspace=workspace, derived_data=root, bundle="build.raft.app")
            simulator = self.IosSimulator(config)
            stopped: list[int] = []
            calls: list[list[str]] = []

            def fake_xcodebuild(args, **_kwargs):
                calls.append(args)
                from device_use.runner import CommandResult

                return CommandResult(argv=["xcodebuild", *args], returncode=0, stdout="", stderr="")

            def fake_start(args, *, log_path, env=None):
                calls.append(args)
                io_dir.mkdir(parents=True, exist_ok=True)
                (io_dir / "ready.json").write_text('{"ok":true,"ready":true}', encoding="utf-8")
                (io_dir / "res-1.json").write_text('{"ok":true}', encoding="utf-8")
                return 2222

            with mock.patch("ios_use.simctl.qa_loop.pid_alive", side_effect=lambda pid: pid in {1111, 2222}), mock.patch(
                "ios_use.simctl.qa_loop.stop_process_group", side_effect=lambda pid, **_k: stopped.append(pid)
            ), mock.patch.object(simulator, "xcodebuild", side_effect=fake_xcodebuild), mock.patch.object(
                simulator, "start_xcodebuild_background", side_effect=fake_start
            ):
                result = simulator.interact(
                    [{"type": "tap", "x": 0.5, "y": 0.5}],
                    device="A-UDID",
                    bundle="build.raft.app.alpha",
                )

            self.assertEqual(stopped, [1111])
            self.assertTrue(any("build-for-testing" in call for call in calls))
            self.assertTrue(any("-only-testing:iosAppUITests/QaAcceptanceLoopTests" in call for call in calls))
            self.assertEqual(result["bundle"], "build.raft.app.alpha")
            self.assertEqual(result["loop_pid"], 2222)
            from ios_use.qa_loop import read_session

            stored = read_session(root / "ios-use-qa-loop" / "A-UDID")
            self.assertIsNotNone(stored)
            self.assertEqual(stored["bundle"], "build.raft.app.alpha")
            self.assertEqual(stored["pid"], 2222)


if __name__ == "__main__":
    unittest.main()
