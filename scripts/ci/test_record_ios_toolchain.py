#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("record_ios_toolchain.sh").resolve()


class RecordIosToolchainTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        (bin_dir / "xcodebuild").write_text('#!/bin/sh\nprintf "Xcode %s\\nBuild version 1A1\\n" "$FAKE_XCODE"\n')
        (bin_dir / "xcrun").write_text(
            '#!/bin/sh\ncase "$2" in iphoneos) echo "$FAKE_DEVICE_SDK" ;; *) echo "$FAKE_SIM_SDK" ;; esac\n')
        for tool in bin_dir.iterdir():
            tool.chmod(0o755)
        self.summary = root / "summary.md"
        self.env = dict(
            os.environ,
            PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
            GITHUB_STEP_SUMMARY=str(self.summary),
            FAKE_XCODE="26.6",
            FAKE_DEVICE_SDK="26.5",
            FAKE_SIM_SDK="26.5",
            XCODE_APP_PATH="/Applications/Xcode_26.6.app",
            RAFT_DEFAULT_XCODE_APP="/Applications/Xcode_26.6.app",
        )

    def run_script(self, success=True):
        result = subprocess.run(["bash", str(SCRIPT)], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, success, result.stderr)
        return result

    def test_default_lane_records_versions_and_letterboxed_tier(self):
        out = self.run_script().stdout
        summary = self.summary.read_text()
        self.assertIn("Xcode 26.6", summary)
        self.assertIn("iPhoneOS SDK: `26.5`", summary)
        self.assertIn("iOS 26 SDK or earlier", summary)
        self.assertIn("Xcode lane: default", summary)
        self.assertNotIn("::warning::", out)

    def test_opt_in_27_1_lane_is_flagged(self):
        self.env.update(FAKE_XCODE="27.1", FAKE_DEVICE_SDK="27.1", FAKE_SIM_SDK="27.1",
                        XCODE_APP_PATH="/Applications/Xcode_27.1.app")
        out = self.run_script().stdout
        summary = self.summary.read_text()
        self.assertIn("iOS 27.1 SDK or later", summary)
        self.assertIn("opt-in (default is Xcode_26.6.app)", summary)
        self.assertIn("::warning::Built with Xcode_27.1.app", out)

    def test_27_0_and_later_minor_tiers(self):
        self.env["FAKE_DEVICE_SDK"] = "27"
        self.run_script()
        self.assertIn("iOS 27.0 SDK", self.summary.read_text())
        self.summary.unlink()
        self.env["FAKE_DEVICE_SDK"] = "28.0"
        self.run_script()
        self.assertIn("iOS 27.1 SDK or later", self.summary.read_text())

    def test_unexpected_sdk_version_fails(self):
        self.env["FAKE_DEVICE_SDK"] = "not-a-version"
        self.run_script(False)


if __name__ == "__main__":
    unittest.main()
