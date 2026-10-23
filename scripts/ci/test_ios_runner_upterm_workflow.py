#!/usr/bin/env python3
"""Lock the one-time Mac Upterm workflow to its reviewed, read-only boundary."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest


WORKFLOW = Path(__file__).resolve().parents[2] / '.github/workflows/ios-runner-upterm.yml'


class UptermWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text()

    def assert_boundary(self, source):
        preflight, inspect = source.split('  inspect:\n', 1)
        self.assertIn('  workflow_dispatch:\n', preflight)
        self.assertNotIn('  pull_request:', preflight)
        self.assertNotIn('  push:', preflight)
        self.assertIn('permissions: {}\n', preflight)
        self.assertIn('    needs: preflight\n', inspect)
        self.assertIn("github.ref == 'refs/heads/main'", inspect)
        self.assertIn("github.actor == 'yezizp2012'", inspect)
        self.assertIn("inputs.confirm == 'task15-readonly-upterm'", inspect)
        self.assertNotIn('actions/checkout', source)
        self.assertNotIn('secrets.', source)
        self.assertNotIn('github.token', source)
        return re.search(r'        run: \|\n((?:          .*\n)+)', preflight).group(1)

    def run_preflight(self, script, ref, actor, confirm, now):
        with tempfile.TemporaryDirectory() as directory:
            date = Path(directory) / 'date'
            date.write_text(f'#!/bin/sh\nprintf "%s\\n" {now}\n')
            date.chmod(0o755)
            result = subprocess.run(
                ['bash', '-e', '-c', script],
                env=dict(
                    os.environ,
                    PATH=f'{directory}:{os.environ["PATH"]}',
                    DISPATCH_REF=ref,
                    DISPATCH_ACTOR=actor,
                    CONFIRM=confirm,
                ),
                capture_output=True,
                text=True,
            )
            return result

    def test_preflight_requires_main_actor_confirmation_and_live_window(self):
        script = self.assert_boundary(self.workflow)
        cases = [
            ('refs/heads/main', 'yezizp2012', 'task15-readonly-upterm', 1790344799, True),
            ('refs/heads/feature', 'yezizp2012', 'task15-readonly-upterm', 1790344799, False),
            ('refs/heads/main', 'someone-else', 'task15-readonly-upterm', 1790344799, False),
            ('refs/heads/main', 'yezizp2012', 'wrong', 1790344799, False),
            ('refs/heads/main', 'yezizp2012', 'task15-readonly-upterm', 1790344800, False),
        ]
        for ref, actor, confirm, now, allowed in cases:
            with self.subTest(ref=ref, actor=actor, confirm=confirm, now=now):
                result = self.run_preflight(script, ref, actor, confirm, now)
                self.assertEqual(result.returncode == 0, allowed, result.stderr)

    def test_remote_session_is_read_only_key_limited_and_ephemeral(self):
        self.assert_boundary(self.workflow)
        source = self.workflow
        self.assertIn('v0.31.0/upterm_darwin_arm64.tar.gz\n', source)
        self.assertIn('ce20d99c1ab41ba20ee3241db60773c766514b90e1a71bae11e279791516a85b', source)
        self.assertIn('@cert-authority uptermd.upterm.dev ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICiecex8Dq718eSe1CCLgLvDmI7AagvCtax7brPFWkh4', source)
        self.assertNotIn(
            '\n          uptermd.upterm.dev ssh-ed25519 '
            'AAAAC3NzaC1lZDI1NTE5AAAAICiecex8Dq718eSe1CCLgLvDmI7AagvCtax7brPFWkh4\n',
            source,
        )
        self.assertIn('            --read-only \\\n', source)
        self.assertIn('            --no-sftp \\\n', source)
        self.assertIn('            --private-key "$UPTERM_ROOT/host_key" \\\n', source)
        self.assertIn('            --authorized-user github:yezizp2012 \\\n', source)
        self.assertIn('            --join-timeout 5m \\\n', source)
        self.assertNotIn('--skip-host-key-check', source)
        self.assertNotIn('--allow-local-tcp-forwarding', source)
        self.assertIn('uses: actions/upload-artifact@b7c566a772e6b6bfb58ed0dc250532a479d7789f # v6', source)

    def cleanup_script(self):
        tail = self.workflow.split(
            '      - name: Stop the session and remove ephemeral material\n', 1
        )[1]
        script = re.search(r'        run: \|\n((?:          .*\n)+)', tail).group(1)
        return textwrap.dedent(script)

    def test_stop_failure_is_red_and_preserves_both_diagnostic_roots(self):
        run_id = f'test-{os.getpid()}'
        with tempfile.TemporaryDirectory() as runner_temp:
            directory_name = f'task15-upterm-{run_id}'
            root = Path(runner_temp) / directory_name
            runtime = Path('/tmp') / f'task15-upterm-runtime-{run_id}'
            root.mkdir()
            runtime.mkdir()
            fake = root / 'upterm'
            receipt = Path(runner_temp) / 'runtime-receipt'
            fake.write_text(
                '#!/bin/sh\nprintf "%s\\n" "$XDG_RUNTIME_DIR" > "$RECEIPT"\nexit 42\n'
            )
            fake.chmod(0o755)
            try:
                result = subprocess.run(
                    [
                        'bash', '--noprofile', '--norc', '-e', '-o', 'pipefail',
                        '-c', self.cleanup_script(),
                    ],
                    env=dict(
                        os.environ,
                        RUNNER_TEMP=runner_temp,
                        UPTERM_DIR_NAME=directory_name,
                        UPTERM_NAME=f'task15-xcode-{run_id}',
                        UPTERM_RUNTIME=str(runtime),
                        GITHUB_RUN_ID=run_id,
                        XDG_RUNTIME_DIR='/host/inherited-runtime',
                        RECEIPT=str(receipt),
                    ),
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(result.returncode, 42, result.stderr)
                self.assertTrue(root.exists())
                self.assertTrue(runtime.exists())
                self.assertIn('preserving diagnostics', result.stdout)
                self.assertEqual(receipt.read_text().strip(), str(runtime))
            finally:
                shutil.rmtree(root, ignore_errors=True)
                shutil.rmtree(runtime, ignore_errors=True)

    def test_successful_stop_removes_both_diagnostic_roots(self):
        run_id = f'test-success-{os.getpid()}'
        with tempfile.TemporaryDirectory() as runner_temp:
            directory_name = f'task15-upterm-{run_id}'
            root = Path(runner_temp) / directory_name
            runtime = Path('/tmp') / f'task15-upterm-runtime-{run_id}'
            root.mkdir()
            runtime.mkdir()
            fake = root / 'upterm'
            receipt = Path(runner_temp) / 'runtime-receipt'
            fake.write_text(
                '#!/bin/sh\nprintf "%s\\n" "$XDG_RUNTIME_DIR" > "$RECEIPT"\nexit 0\n'
            )
            fake.chmod(0o755)
            result = subprocess.run(
                [
                    'bash', '--noprofile', '--norc', '-e', '-o', 'pipefail',
                    '-c', self.cleanup_script(),
                ],
                env=dict(
                    os.environ,
                    RUNNER_TEMP=runner_temp,
                    UPTERM_DIR_NAME=directory_name,
                    UPTERM_NAME=f'task15-xcode-{run_id}',
                    UPTERM_RUNTIME=str(runtime),
                    GITHUB_RUN_ID=run_id,
                    XDG_RUNTIME_DIR='/host/inherited-runtime',
                    RECEIPT=str(receipt),
                ),
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(receipt.read_text().strip(), str(runtime))
            self.assertFalse(root.exists())
            self.assertFalse(runtime.exists())

    def test_removing_any_dispatch_guard_is_rejected(self):
        mutations = [
            ('    needs: preflight\n', ''),
            ("github.ref == 'refs/heads/main'", 'true'),
            ("github.actor == 'yezizp2012'", 'true'),
            ("inputs.confirm == 'task15-readonly-upterm'", 'true'),
            ('            --read-only \\\n', ''),
            ('            --no-sftp \\\n', ''),
        ]
        for old, new in mutations:
            with self.subTest(old=old):
                with self.assertRaises(AssertionError):
                    mutated = self.workflow.replace(old, new, 1)
                    if '            --' in old:
                        self.assert_session_source(mutated)
                    else:
                        self.assert_boundary(mutated)

    def assert_session_source(self, source):
        self.assertIn('            --read-only \\\n', source)
        self.assertIn('            --no-sftp \\\n', source)


if __name__ == '__main__':
    unittest.main()
