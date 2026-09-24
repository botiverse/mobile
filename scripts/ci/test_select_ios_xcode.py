#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("select_ios_xcode.sh").resolve()


class XcodeSelectionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.app = self.root / "Xcode with spaces.app"
        self.developer = self.app / "Contents/Developer"
        binary = self.developer / "usr/bin/xcodebuild"
        binary.parent.mkdir(parents=True)
        binary.write_text('#!/bin/sh\n[ "$DEVELOPER_DIR" = "$EXPECTED_DEVELOPER" ] || exit 8\nexit "${XCODE_EXIT:-0}"\n')
        binary.chmod(0o755)
        self.envfile = self.root / "github-env"
        self.env = dict(os.environ, GITHUB_ENV=str(self.envfile),
                        XCODE_APP_PATH=str(self.app), DEVELOPER_DIR="/Library/Developer/CommandLineTools",
                        EXPECTED_DEVELOPER=str(self.developer))

    def run_selection(self, success=True):
        result = subprocess.run(["bash", str(SCRIPT)], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, success, result.stderr)

    def test_explicit_full_xcode_overrides_command_line_tools_per_job(self):
        self.run_selection()
        self.assertEqual(self.envfile.read_text(), f"DEVELOPER_DIR={self.developer}\n")

    def test_valid_existing_developer_dir_preserved(self):
        self.env["XCODE_APP_PATH"] = ""
        self.env["DEVELOPER_DIR"] = str(self.developer)
        self.run_selection()
        self.assertEqual(self.envfile.read_text(), f"DEVELOPER_DIR={self.developer}\n")

    def test_missing_full_xcode_does_not_export_success(self):
        self.env["XCODE_APP_PATH"] = str(self.root / "Missing.app")
        self.run_selection(False)
        self.assertFalse(self.envfile.exists())

    def test_non_app_path_is_rejected(self):
        self.env["XCODE_APP_PATH"] = "/Library/Developer/CommandLineTools"
        self.run_selection(False)
        self.assertFalse(self.envfile.exists())

    def test_broken_xcode_does_not_export_success(self):
        self.env["XCODE_EXIT"] = "7"
        self.run_selection(False)
        self.assertFalse(self.envfile.exists())

    def test_newline_path_cannot_inject_github_environment(self):
        self.env["XCODE_APP_PATH"] = str(self.app) + "\nINJECTED=yes.app"
        self.run_selection(False)
        self.assertFalse(self.envfile.exists())


if __name__ == "__main__":
    unittest.main()
