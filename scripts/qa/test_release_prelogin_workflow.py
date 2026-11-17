#!/usr/bin/env python3
"""Fake-workflow replay of qa-acceptance.yml's release pre-login wiring (fake tools only).

Runs the ACTUAL "Run acceptance agent" emulator script the way reactivecircus/android-emulator-runner
does (each non-comment line in its own `sh -c`, stopping at the first line that exits non-zero),
with fake adb / node / bash / aapt in a temp dir: no device, no network, no credential. Then runs
the ACTUAL "Report verdict" step with a fake reporter. Checks (Argus review of PR 4946):

  * a failed pre-login still reaches the pre-login cleanup + verdict lines;
  * so does a run where every adb and agent call fails;
  * the non-pre-login (Alpha / password) path keeps its old stop behaviour;
  * "Finalize release pre-login receipts" runs always() and gates the receipt upload;
  * Report verdict takes the pre-login rc only from report-gate: an agent FAIL with an incomplete
    outcome is reported as harness (prerequisite 1), never as a product fail.
"""
from __future__ import annotations

import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
WF = yaml.safe_load((ROOT / ".github/workflows/qa-acceptance.yml").read_text())
STEPS = WF["jobs"]["acceptance"]["steps"]


def step(name: str) -> dict:
    return next(s for s in STEPS if s.get("name") == name)


SCRIPT_LINES = [
    re.sub(r"\$\{\{[^}]*\}\}", "fake", line.strip())
    for line in step("Run acceptance agent")["with"]["script"].splitlines()
    if line.strip() and not line.strip().startswith("#")
]

FAKE_NODE = r"""#!/bin/sh
echo "node $*" >> "$FAKE_ROOT/calls"
case "$1 $2" in
  'scripts/qa/release_prelogin.mjs login') [ "$FAKE_LOGIN" = ok ] && exit 0; exit 1;;
  'scripts/qa/login_phase.mjs login') [ "$FAKE_LOGIN" = ok ] && exit 0; exit 1;;
  'scripts/qa/release_prelogin.mjs cleanup') touch "$FAKE_ROOT/cleanup-called"; exit 1;;
  'scripts/qa/release_prelogin.mjs verdict') touch "$FAKE_ROOT/verdict-called"; exit 0;;
  'scripts/qa/release_prelogin.mjs report-gate') printf '%s\n' "$FAKE_GATE"; exit 0;;
  'scripts/qa/acceptance_agent.mjs '*) exit "${FAKE_AGENT_RC:-0}";;
esac
[ "$FAKE_ALL_FAIL" = 1 ] && exit 1
exit 0
"""
FAKE_ADB = """#!/bin/sh
echo "adb $*" >> "$FAKE_ROOT/calls"
[ "$FAKE_ALL_FAIL" = 1 ] && exit 1
exit 0
"""
FAKE_BASH = """#!/bin/sh
echo "bash $*" >> "$FAKE_ROOT/calls"
[ "$FAKE_ALL_FAIL" = 1 ] && exit 1
exit 0
"""


class Sandbox:
    def __init__(self, **env: str):
        self.dir = pathlib.Path(tempfile.mkdtemp(prefix="prelogin-wf-"))
        for sub in ("bin", "apks", "qa-evidence", "qa-prelogin"):
            (self.dir / sub).mkdir()
        (self.dir / "apks/app.apk").write_text("synthetic apk")
        (self.dir / "apks/build-info.json").write_text("{}")
        bt = self.dir / "sdk/build-tools/1"
        bt.mkdir(parents=True)
        self._exe(bt / "aapt", "#!/bin/sh\necho \"package: name='build.raft.app'\"\necho \"launchable-activity: name='build.raft.app.MainActivity'\"\n")
        for name, body in (("node", FAKE_NODE), ("adb", FAKE_ADB), ("bash", FAKE_BASH), ("sleep", "#!/bin/sh\nexit 0\n")):
            self._exe(self.dir / "bin" / name, body)
        self.env = {
            **os.environ,
            "PATH": f"{self.dir}/bin:{os.environ['PATH']}",
            "FAKE_ROOT": str(self.dir),
            "ANDROID_HOME": str(self.dir / "sdk"),
            "RUNNER_TEMP": str(self.dir),
            "GITHUB_RUN_ID": "111",
            "GITHUB_RUN_ATTEMPT": "1",
            "STAMP_BOOTSTRAP_REQUIRED": "false",
            "QA_MAX_TURNS": "1",
            "QA_MAX_MINUTES": "1",
            "QA_NO_PROGRESS_TURNS": "1",
            "PI_API_KEY": "fake-only",
            "QA_GOAL": "fake ordinary goal",
            "GITHUB_STEP_SUMMARY": str(self.dir / "summary.md"),
            **env,
        }

    @staticmethod
    def _exe(p: pathlib.Path, body: str) -> None:
        p.write_text(body)
        p.chmod(0o755)

    def run_emulator_script(self) -> int | None:
        """Returns the index of the line the runner stopped at, or None when every line passed."""
        for index, line in enumerate(SCRIPT_LINES):
            p = subprocess.run(["/bin/sh", "-c", line], cwd=self.dir, env=self.env, capture_output=True, text=True, timeout=60)
            if p.returncode:
                return index
        return None

    def calls(self) -> list[str]:
        f = self.dir / "calls"
        return f.read_text().splitlines() if f.exists() else []

    def close(self) -> None:
        shutil.rmtree(self.dir, ignore_errors=True)


def line_index(fragment: str) -> int:
    return next(i for i, line in enumerate(SCRIPT_LINES) if fragment in line)


class EmulatorScriptTest(unittest.TestCase):
    def replay(self, **env: str) -> tuple[int | None, Sandbox]:
        box = Sandbox(**env)
        self.addCleanup(box.close)
        return box.run_emulator_script(), box

    def test_failed_prelogin_still_runs_cleanup_and_verdict(self):
        stopped, box = self.replay(QA_RELEASE_PRELOGIN="true", FAKE_LOGIN="fail")
        self.assertIsNone(stopped, SCRIPT_LINES[stopped] if stopped is not None else "")
        self.assertTrue((box.dir / "cleanup-called").exists())
        self.assertTrue((box.dir / "verdict-called").exists())
        self.assertEqual((box.dir / "qa-evidence/agent-rc.txt").read_text().strip(), "2")
        self.assertFalse(any("acceptance_agent.mjs" in c for c in box.calls()), "the agent must not run after an unknown login")
        self.assertTrue(any("release_prelogin.mjs verdict" in c and "--verdict qa-evidence/verdict.json" in c for c in box.calls()))

    def test_every_adb_and_agent_failure_still_reaches_cleanup(self):
        stopped, box = self.replay(QA_RELEASE_PRELOGIN="true", FAKE_LOGIN="ok", FAKE_ALL_FAIL="1", FAKE_AGENT_RC="1")
        # The install line before the login may stop the script (nothing to clean then: the login
        # never started). Every line from the pre-login login on must not.
        login = line_index("release_prelogin.mjs login")
        cleanup = line_index("release_prelogin.mjs cleanup")
        if stopped is not None:
            self.assertLess(stopped, login, f"stopped after the login at: {SCRIPT_LINES[stopped][:120]}")
            return
        self.assertTrue((box.dir / "cleanup-called").exists())
        self.assertLess(login, cleanup)

    def test_lines_after_login_survive_failures_when_starting_from_login(self):
        box = Sandbox(QA_RELEASE_PRELOGIN="true", FAKE_LOGIN="ok", FAKE_ALL_FAIL="1", FAKE_AGENT_RC="1")
        self.addCleanup(box.close)
        (box.dir / "qa-app-package.txt").write_text("build.raft.app\n")
        login = line_index("release_prelogin.mjs login")
        cleanup = line_index("release_prelogin.mjs cleanup")
        for line in SCRIPT_LINES[login : cleanup + 1]:
            p = subprocess.run(["/bin/sh", "-c", line], cwd=box.dir, env=box.env, capture_output=True, text=True, timeout=60)
            self.assertEqual(p.returncode, 0, f"the runner would stop before cleanup at: {line[:160]}")
        self.assertTrue((box.dir / "cleanup-called").exists())
        self.assertTrue((box.dir / "verdict-called").exists())

    def test_successful_prelogin_runs_agent_then_cleanup(self):
        stopped, box = self.replay(QA_RELEASE_PRELOGIN="true", FAKE_LOGIN="ok")
        self.assertIsNone(stopped)
        calls = box.calls()
        agent = next(i for i, c in enumerate(calls) if "acceptance_agent.mjs" in c)
        clean = next(i for i, c in enumerate(calls) if "release_prelogin.mjs cleanup" in c)
        self.assertLess(agent, clean)

    def test_non_prelogin_path_keeps_its_stop_behaviour(self):
        stopped, box = self.replay(QA_RELEASE_PRELOGIN="", FAKE_LOGIN="fail")
        self.assertEqual(stopped, line_index("screenrecord --size"), "Alpha/password path: unchanged stop at the recorder line")
        self.assertFalse((box.dir / "cleanup-called").exists())
        self.assertFalse(any("release_prelogin.mjs" in c for c in box.calls()))
        stopped, box = self.replay(QA_RELEASE_PRELOGIN="", FAKE_LOGIN="ok")
        self.assertIsNone(stopped)
        self.assertFalse(any("release_prelogin.mjs" in c for c in box.calls()))


class FinalizeAndUploadTest(unittest.TestCase):
    def test_finalize_always_runs_and_gates_upload(self):
        fin = step("Finalize release pre-login receipts")
        self.assertIn("always()", fin["if"])
        self.assertIn("release_prelogin.mjs finalize", fin["run"])
        self.assertNotIn("secrets.", str(fin.get("env", {})))
        up = step("Upload release pre-login receipts")
        self.assertIn("steps.prelogin-finalize.outcome == 'success'", up["if"])
        names = [s.get("name") for s in STEPS]
        self.assertLess(names.index("Run acceptance agent"), names.index("Finalize release pre-login receipts"))
        self.assertLess(names.index("Finalize release pre-login receipts"), names.index("Upload release pre-login receipts"))

    def test_plan_consumes_a_claim_bound_spec_and_mints_nothing(self):
        plan = step("Release pre-login plan")["run"]
        self.assertIn("--claim-response", plan)
        self.assertIn("--aapt", plan)
        self.assertNotIn("grep -q '^application-debuggable'", plan)
        for removed in ("--target", "--case-file", "--build-debuggable"):
            self.assertNotIn(removed, plan)

    def test_no_prelogin_credential_is_wired_yet(self):
        text = (ROOT / ".github/workflows/qa-acceptance.yml").read_text()
        self.assertNotIn("QA_PRELOGIN_EMAIL", text)
        self.assertNotIn("QA_STAGING_TEST_", text)


class ReportVerdictTest(unittest.TestCase):
    def report(self, agent_rc: str, gate: str) -> tuple[int, str]:
        box = Sandbox(RELEASE_PRELOGIN="{}", FAKE_GATE=gate)
        self.addCleanup(box.close)
        (box.dir / "qa-evidence/agent-rc.txt").write_text(f"{agent_rc}\n")
        (box.dir / "qa-evidence/verdict.json").write_text('{"verdict":"fail"}')
        p = subprocess.run(["/bin/bash", "-eo", "pipefail", "-c", step("Report verdict")["run"]], cwd=box.dir, env=box.env, capture_output=True, text=True, timeout=60)
        reporter = next((c for c in box.calls() if "report-verdict" in c), "")
        return p.returncode, reporter

    def test_agent_fail_with_incomplete_outcome_is_harness_not_product_fail(self):
        rc, reporter = self.report("1", "3")
        self.assertEqual(rc, 3)
        self.assertTrue(reporter.endswith("report-verdict qa-evidence 1"), reporter)

    def test_bound_product_fail_stays_fail(self):
        rc, reporter = self.report("1", "1")
        self.assertEqual(rc, 1)
        self.assertTrue(reporter.endswith("report-verdict qa-evidence 0"), reporter)

    def test_gate_missing_or_garbage_fails_closed(self):
        for gate in ("", "0 1", "pass"):
            rc, reporter = self.report("0", gate)
            self.assertEqual(rc, 3, gate)
            self.assertTrue(reporter.endswith("report-verdict qa-evidence 1"), reporter)

    def test_agent_pass_with_bound_pass(self):
        rc, reporter = self.report("0", "0")
        self.assertEqual(rc, 0)
        self.assertTrue(reporter.endswith("report-verdict qa-evidence 0"), reporter)


if __name__ == "__main__":
    unittest.main(verbosity=2)
