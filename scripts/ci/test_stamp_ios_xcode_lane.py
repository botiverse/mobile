#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("stamp_ios_xcode_lane.py").resolve()
BASE = {"platform": "ios-simulator", "artifact_sha256": "a" * 64, "github_run_id": "42"}


class StampIosXcodeLaneTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.bin = root / "bin"
        self.bin.mkdir()
        self.write_tool("xcodebuild", 'printf "Xcode %s\\nBuild version 17F42\\n" "$FAKE_XCODE"')
        self.write_tool("xcrun", 'echo "$FAKE_SIM_SDK"')
        self.metadata = root / "metadata.json"
        self.metadata.write_text(json.dumps(BASE))
        self.env = dict(
            os.environ,
            PATH=f"{self.bin}{os.pathsep}{os.environ['PATH']}",
            FAKE_XCODE="26.6",
            FAKE_SIM_SDK="26.5",
            XCODE_APP_PATH="/Applications/Xcode_26.6.app",
            RAFT_DEFAULT_XCODE_APP="/Applications/Xcode_26.6.app",
        )

    def write_tool(self, name, body):
        tool = self.bin / name
        tool.write_text(f"#!/bin/sh\n{body}\n")
        tool.chmod(0o755)

    def run_script(self, success=True):
        result = subprocess.run([sys.executable, str(SCRIPT), str(self.metadata)],
                                env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, success, result.stderr)
        return result

    def test_default_lane(self):
        self.run_script()
        data = json.loads(self.metadata.read_text())
        self.assertEqual(data["xcode_lane"], "default")
        self.assertEqual(data["xcode_version"], "26.6")
        self.assertEqual(data["xcode_build"], "17F42")
        self.assertEqual(data["ios_sdk_version"], "26.5")
        self.assertEqual(data["xcode_app"], "Xcode_26.6.app")
        for key, value in BASE.items():
            self.assertEqual(data[key], value)

    def test_trailing_slash_is_still_default(self):
        self.env["XCODE_APP_PATH"] = "/Applications/Xcode_26.6.app/"
        self.run_script()
        self.assertEqual(json.loads(self.metadata.read_text())["xcode_lane"], "default")

    def test_opt_in_27_1_lane(self):
        self.env.update(XCODE_APP_PATH="/Applications/Xcode_27.1.app", FAKE_XCODE="27.1",
                        FAKE_SIM_SDK="27.1")
        self.run_script()
        data = json.loads(self.metadata.read_text())
        self.assertEqual(data["xcode_lane"], "opt-in")
        self.assertEqual(data["ios_sdk_version"], "27.1")
        self.assertEqual(data["xcode_app"], "Xcode_27.1.app")

    def test_refuses_unknown_lane_or_toolchain(self):
        original = self.metadata.read_text()
        for env in ({"RAFT_DEFAULT_XCODE_APP": ""}, {"XCODE_APP_PATH": ""},
                    {"FAKE_SIM_SDK": "garbage"}, {"FAKE_XCODE": ""}):
            with self.subTest(env=env):
                saved = {key: self.env[key] for key in env}
                self.env.update(env)
                self.run_script(False)
                self.env.update(saved)
                self.assertEqual(self.metadata.read_text(), original)

    def test_refuses_failing_xcrun(self):
        self.write_tool("xcrun", "exit 1")
        self.run_script(False)


if __name__ == "__main__":
    unittest.main()
