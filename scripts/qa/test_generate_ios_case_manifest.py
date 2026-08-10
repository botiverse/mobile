#!/usr/bin/env python3
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GENERATOR = ROOT / "scripts/generate_ios_case_manifest.py"
MENTION_CASES = (
    "components.thread.composer.pending-mention-batch-details",
    "components.thread.composer.pending-mention-batch-partial",
)


class GenerateIosCaseManifestTest(unittest.TestCase):
    def test_android_only_page_data_is_not_forwarded_to_ios(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "manifest.json"
            subprocess.run(
                [
                    "python3",
                    str(GENERATOR),
                    "--filter",
                    ",".join(MENTION_CASES),
                    "--out",
                    str(output),
                ],
                cwd=ROOT,
                check=True,
                capture_output=True,
                text=True,
            )
            manifest = json.loads(output.read_text())

        self.assertEqual(
            {case["caseId"] for case in manifest["cases"]},
            set(MENTION_CASES),
        )
        self.assertEqual(manifest["pending"], [])
        for case in manifest["cases"]:
            self.assertNotIn(
                "androidVisualMentionDrawerStartsClosed",
                case["pageData"],
            )
            self.assertIn("visualFixture", case["pageData"])
            self.assertTrue(case["pageData"]["slockVisualZeroSafeArea"])


if __name__ == "__main__":
    unittest.main()
