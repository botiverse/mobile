#!/usr/bin/env python3
"""Exercise simulator ownership without needing Xcode on the test runner."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("ios_visual_runner.sh").resolve()
UDID = "01234567-89AB-CDEF-0123-456789ABCDEF"
FAKE = '''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as out: out.write(json.dumps(args) + '\\n')
if args == ['simctl', 'list', 'runtimes', '-j']:
 print(json.dumps({'runtimes': [{'identifier': 'ios9', 'version': '9.0', 'platform': 'iOS', 'isAvailable': True}, {'identifier': 'com.apple.CoreSimulator.SimRuntime.iOS-18-2', 'version': '18.2', 'isAvailable': True}]}))
elif args == ['simctl', 'list', 'devicetypes', '-j']:
 print(json.dumps({'devicetypes': [{'name': 'iPhone 14', 'identifier': 'iphone14'}]}))
elif args[:2] == ['simctl', 'create']: print('01234567-89AB-CDEF-0123-456789ABCDEF')
elif len(args) > 1 and args[1] == os.environ.get('FAIL_COMMAND'): sys.exit(7)
'''


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        fake = self.root / "xcrun"
        fake.write_text(FAKE)
        fake.chmod(0o755)
        for name in ("xcodebuild", "pod"):
            tool = self.root / name
            tool.write_text("#!/bin/sh\nexit 0\n")
            tool.chmod(0o755)
        (self.root / "runner-temp").mkdir()
        self.env = dict(os.environ, RUNNER_TEMP=str(self.root / "runner-temp"),
                        GITHUB_ENV=str(self.root / "env"), SIM_DEVICE="iPhone 14",
                        GITHUB_WORKSPACE=str(self.root / "workspace"),
                        RUNNER_NAME="test-runner", GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1",
                        CALLS=str(self.root / "calls"),
                        PATH=str(self.root) + os.pathsep + os.environ["PATH"])
        self.run_phase("prepare")
        for line in (self.root / "env").read_text().splitlines():
            key, value = line.split("=", 1)
            self.env[key] = value
        self.capture = Path(self.env["IOS_CAPTURE_TEMP"])
        self.receipt = self.root / "workspace/visual-testing-results/ios-diagnostics/simulator-cleanup-failure.json"
        self.neighbor = self.root / "unrelated-device-data"
        self.neighbor.write_text("keep")

    def run_phase(self, phase, success=True):
        result = subprocess.run(["bash", str(SCRIPT), phase], env=self.env,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, success, result.stderr)

    def calls(self):
        return [json.loads(line) for line in (self.root / "calls").read_text().splitlines()]

    def test_fresh_device_numeric_runtime_and_exact_cleanup(self):
        self.run_phase("boot")
        self.assertIn(["simctl", "create", "visualcap-" + self.capture.name,
                       "iphone14", "com.apple.CoreSimulator.SimRuntime.iOS-18-2"], self.calls())
        self.run_phase("cleanup")
        self.assertEqual(self.calls()[-2:], [["simctl", "shutdown", UDID],
                                           ["simctl", "delete", UDID]])
        self.assertFalse(self.capture.exists())
        self.assertEqual(self.neighbor.read_text(), "keep")
        self.assertFalse(self.receipt.exists())

    def test_failed_boot_still_cleans_owned_device(self):
        self.env["FAIL_COMMAND"] = "boot"
        self.run_phase("boot", success=False)
        self.run_phase("cleanup")
        self.assertIn(["simctl", "delete", UDID], self.calls())
        self.assertFalse(self.capture.exists())

    def test_failed_delete_retains_record_and_fails(self):
        self.run_phase("boot")
        self.env["FAIL_COMMAND"] = "delete"
        self.run_phase("cleanup", success=False)
        self.assertEqual((self.capture / "simulator-udid").read_text().strip(), UDID)
        self.assertEqual(json.loads(self.receipt.read_text()), {
            "udid": UDID, "runner": "test-runner", "runId": "123", "runAttempt": "1",
            "temporaryDirectory": self.capture.name})
        # Simulate runner temp cleanup; the workspace artifact survives.
        import shutil
        shutil.rmtree(self.env["RUNNER_TEMP"])
        self.assertEqual(json.loads(self.receipt.read_text())["udid"], UDID)

    def test_pre_boot_failure_cleanup_never_touches_simulators(self):
        before = self.calls()
        self.run_phase("cleanup")
        self.assertEqual(before, self.calls())
        self.assertFalse(self.capture.exists())

    def test_rejects_foreign_directory_and_invalid_device(self):
        self.env["IOS_CAPTURE_TEMP"] = str(self.root)
        self.run_phase("cleanup", success=False)
        self.env["IOS_CAPTURE_TEMP"] = str(self.capture)
        (self.capture / "simulator-udid").write_text("all")
        self.run_phase("cleanup", success=False)
        self.assertTrue(self.capture.exists())
        self.assertTrue(self.neighbor.exists())


if __name__ == "__main__":
    unittest.main()
