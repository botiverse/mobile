from __future__ import annotations

import importlib.util
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

    def test_helper_has_no_host_global_input_implementation(self) -> None:
        source = SCRIPT_PATH.read_text(encoding="utf-8")
        forbidden = ("osa" + "script", "System" + " Events", "front" + "most", "cli" + "click")
        for token in forbidden:
            self.assertNotIn(token, source)


class IosUseXcuitestSelectionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        mobile_use_src = REPO_ROOT / "tools" / "mobile-use" / "src"
        sys.path.insert(0, str(mobile_use_src))
        from ios_use.config import IosConfig
        from ios_use.simctl import IosSimulator

        cls.IosConfig = IosConfig
        cls.IosSimulator = IosSimulator

    def test_interact_runs_only_the_bounded_ios_use_test(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = self.IosConfig(device="A-UDID", workspace=root / "iosApp.xcworkspace", derived_data=root)
            simulator = self.IosSimulator(config)
            calls: list[list[str]] = []

            def fake_xcodebuild(args, **_kwargs):
                calls.append(args)
                from device_use.runner import CommandResult

                return CommandResult(argv=["xcodebuild", *args], returncode=0, stdout="", stderr="")

            with mock.patch.object(simulator, "xcodebuild", side_effect=fake_xcodebuild), mock.patch.object(
                simulator, "_patched_xctestrun", return_value=root / "actions.xctestrun"
            ):
                simulator.interact([{"type": "tap", "x": 0.5, "y": 0.5}], device="A-UDID")

        self.assertEqual(len(calls), 2)
        self.assertIn("-only-testing:iosAppUITests/IosUseInteractionTests/testRunIosUseActions", calls[1])
        self.assertIn("test-without-building", calls[1])


if __name__ == "__main__":
    unittest.main()
