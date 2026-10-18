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
elif args[:2] == ['simctl', 'create']:
 name = args[2]
 suffix = name.rsplit('-s', 1)[-1]
 if name.endswith('-s' + suffix) and suffix.isdigit():
     print('01234567-89AB-CDEF-0123-456789ABCD' + format(int(suffix), '02x'))
 else:
     print('01234567-89AB-CDEF-0123-456789ABCDEF')
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

    def test_multi_shard_creates_and_cleans_all_devices(self):
        self.env["IOS_CAPTURE_SHARDS"] = "3"
        self.run_phase("boot")
        creates = [c for c in self.calls() if c[:2] == ["simctl", "create"]]
        self.assertEqual(len(creates), 3)
        udids = [self.calls()[i][-1] for i, c in enumerate(self.calls())
                 if c[:2] == ["simctl", "boot"]]
        self.assertEqual(sorted(set(udids)), sorted(
            ["01234567-89AB-CDEF-0123-456789ABCD0%d" % i for i in range(3)]))
        self.run_phase("cleanup")
        deletes = [c[-1] for c in self.calls() if c[:2] == ["simctl", "delete"]]
        self.assertEqual(sorted(deletes), sorted(udids))
        self.assertFalse(self.capture.exists())

    def test_shards_out_of_range_rejected(self):
        self.env["IOS_CAPTURE_SHARDS"] = "99"
        result = subprocess.run(["bash", str(SCRIPT), "boot"], env=self.env,
                                capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("out of range", result.stderr)
        # Refuses before touching simctl create.
        self.assertFalse(any(c[:2] == ["simctl", "create"] for c in self.calls()))

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


SHARD_SCRIPT = Path(__file__).with_name("ios_visual_capture_shard.sh").resolve()
SHARD_FAKE = '''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
if args[:2] == ['simctl', 'launch']:
    print('SLOCK_VISUAL_FIXTURE_READY page=' + os.environ['SIMCTL_CHILD_SLOCK_VISUAL_FIXTURE_PAGE'], flush=True)
elif args[:3] == ['simctl', 'io', args[2] if len(args) > 2 else ''] and 'screenshot' in args:
    if os.environ.get('FAIL_SCREENSHOT'):
        sys.exit(7)
    open(args[-1], 'wb').write(b'png')
'''


class ShardCaptureTest(unittest.TestCase):
    """The shard loop must fail closed on unexpected simctl failures."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name, body in (("xcrun", SHARD_FAKE), ("plistbuddy", "#!/bin/sh\necho test.bundle\n")):
            tool = self.root / name
            tool.write_text(body)
            tool.chmod(0o755)
        self.temp = self.root / "capture"
        (self.temp / "DerivedData/Build/Products/Debug-iphonesimulator/App.app").mkdir(parents=True)
        self.cases = self.temp / "ios-cases.0.tsv"
        self.cases.write_text("case-a\tPageA\t{}\n")
        self.out = self.root / "results/ios"
        self.env = dict(os.environ, IOS_CAPTURE_TEMP=str(self.temp),
                        PLISTBUDDY=str(self.root / "plistbuddy"),
                        PATH=str(self.root) + os.pathsep + os.environ["PATH"])

    def run_shard(self):
        return subprocess.run(["bash", str(SHARD_SCRIPT), UDID, str(self.cases), str(self.out)],
                              env=self.env, capture_output=True, text=True)

    def test_successful_case_writes_png_and_tally(self):
        result = self.run_shard()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue((self.out / "case-a.png").exists())
        tally = (self.temp / f"shard-result.{UDID}").read_text()
        self.assertEqual("captured=1\nfailed=0\ntotal=1\n", tally)

    def test_screenshot_failure_fails_shard_without_tally(self):
        self.env["FAIL_SCREENSHOT"] = "1"
        result = self.run_shard()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertFalse((self.temp / f"shard-result.{UDID}").exists())


if __name__ == "__main__":
    unittest.main()
