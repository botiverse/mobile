#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("bootstrap_ios_runner.sh").resolve()
FAKE = '''#!/usr/bin/env python3
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as out: out.write(json.dumps([name] + args) + '\\n')
if name == 'xcodebuild' and args == ['-checkFirstLaunchStatus']:
 sys.exit(int(os.environ.get('FIRST_LAUNCH_PENDING', '0')))
if name == 'sudo': sys.exit(7)
if name == 'xcrun':
 if os.environ.get('BAD_JSON'): print('bad json')
 else:
  runtimes = [] if os.environ.get('NO_RUNTIME') else [{'identifier': 'com.apple.CoreSimulator.SimRuntime.iOS-18-2', 'isAvailable': True}]
  print(json.dumps({'runtimes': runtimes}))
'''


class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in ("xcodebuild", "xcrun", "sudo", "pod", "brew"):
            tool = self.root / name
            tool.write_text(FAKE)
            tool.chmod(0o755)
        self.env = dict(os.environ, DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer",
                        RUNNER_TEMP=str(self.root), GITHUB_PATH=str(self.root / "github-path"),
                        CALLS=str(self.root / "calls"), PATH=str(self.root) + os.pathsep + os.environ["PATH"])

    def run_bootstrap(self, success=True):
        result = subprocess.run(["bash", str(SCRIPT)], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, success, result.stderr)
        return [json.loads(line) for line in (self.root / "calls").read_text().splitlines()]

    def test_existing_runtime_skips_download_and_elevation(self):
        calls = self.run_bootstrap()
        self.assertNotIn(["xcodebuild", "-downloadPlatform", "iOS"], calls)
        self.assertFalse(any(c[0] in ("sudo", "brew") for c in calls))

    def test_missing_runtime_downloads_ios(self):
        self.env["NO_RUNTIME"] = "1"
        self.assertIn(["xcodebuild", "-downloadPlatform", "iOS"], self.run_bootstrap())

    def test_invalid_runtime_response_fails_without_downloading(self):
        self.env["BAD_JSON"] = "1"
        calls = self.run_bootstrap(False)
        self.assertNotIn(["xcodebuild", "-downloadPlatform", "iOS"], calls)

    def test_first_launch_permission_failure_is_not_success(self):
        self.env["FIRST_LAUNCH_PENDING"] = "1"
        calls = self.run_bootstrap(False)
        self.assertIn(["sudo", "-n", "env", "DEVELOPER_DIR=" + self.env["DEVELOPER_DIR"],
                       "/usr/bin/xcodebuild", "-runFirstLaunch"], calls)
        self.assertFalse(any(c[0] == "xcrun" for c in calls))


if __name__ == "__main__":
    unittest.main()
