#!/usr/bin/env python3
"""Check the dispatch boundary before any self-hosted bootstrap execution."""
import os
from pathlib import Path
import re
import subprocess
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / '.github/workflows/ios-runner-bootstrap.yml'


class BootstrapWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text()

    def assert_main_boundary(self, source):
        preflight, bootstrap = source.split('  bootstrap:\n', 1)
        self.assertIn('  workflow_dispatch:', preflight)
        self.assertNotIn('  pull_request:', preflight)
        self.assertIn('    runs-on: ubuntu-latest\n', preflight)
        self.assertIn('          DISPATCH_REF: ${{ github.ref }}\n', preflight)
        self.assertIn('    needs: preflight\n', bootstrap)
        self.assertIn("    if: ${{ github.ref == 'refs/heads/main' }}\n", bootstrap)
        self.assertIn('          ref: ${{ github.sha }}\n', bootstrap)
        self.assertIn('      group: jacky-macmini\n', bootstrap)
        self.assertIn('      labels: [self-hosted, macOS, ARM64, mac-mini-1]\n', bootstrap)
        return re.search(r'        run: \|\n((?:          .*\n)+)', preflight).group(1)

    def test_main_only_preflight_executes_before_mac_job(self):
        script = self.assert_main_boundary(self.workflow)
        for ref, allowed in [('refs/heads/main', True), ('refs/heads/feature', False),
                             ('refs/tags/main', False), ('', False)]:
            with self.subTest(ref=ref):
                result = subprocess.run(['bash', '-e', '-c', script],
                                        env=dict(os.environ, DISPATCH_REF=ref),
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, allowed, result.stderr)

    def test_removing_mac_ref_guard_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.assert_main_boundary(self.workflow.replace(
                "    if: ${{ github.ref == 'refs/heads/main' }}\n", ''))

    def test_removing_preflight_dependency_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.assert_main_boundary(self.workflow.replace('    needs: preflight\n', ''))


if __name__ == '__main__':
    unittest.main()
