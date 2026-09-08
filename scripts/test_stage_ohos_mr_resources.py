import json
from pathlib import Path
import subprocess
import tempfile
import unittest


class StageOhosResourcesTest(unittest.TestCase):
    def test_templates_are_decoded_once_before_formatting(self):
        script = Path(__file__).with_name("stage_ohos_mr_resources.sh")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generated = root / "generated"
            entry = root / "entry"
            templates = {
                "slock_thread_show_more": "Show more",
                "slock_auth_restore_tagline": r"AGENTS\nCREATE.",
                "slock_settings_diagnostics_feedback_composer_seed": r"诊断\n\n%s",
                "literal": r"keep \\n and \q",
                "existing": "already\na newline",
                "controls": r"one\ttwo\r\nthree",
            }
            for locale in ("base", "zh-CN", "ja"):
                path = generated / "res" / locale / "element" / "string.json"
                path.parent.mkdir(parents=True)
                path.write_text(json.dumps({"string": [
                    {"name": name, "value": value}
                    for name, value in templates.items()
                ]}), encoding="utf-8")
            assets = generated / "res/rawfile/assets/slock_reactions"
            assets.mkdir(parents=True)
            for name in ("reaction_atlas_v1.png", "reaction_atlas_v1_manifest.json"):
                (assets / name).write_bytes(b"fixture")
            dependency = generated / "mergeRes/base/element/string.json"
            dependency.parent.mkdir(parents=True)
            dependency.write_text('{"string": [{"name": "dependency", "value": "old"}]}')
            for attempt in range(2):
                subprocess.run(["bash", str(script), str(generated), str(entry)], check=True,
                               capture_output=True, text=True)
                for locale in ("base", "zh_CN", "ja"):
                    with self.subTest(attempt=attempt, locale=locale):
                        document = json.loads((entry / "mr-res" / locale / "element/string.json").read_text())
                        values = {item["name"]: item["value"] for item in document["string"]}
                        self.assertEqual("AGENTS\nCREATE.", values["slock_auth_restore_tagline"])
                        self.assertEqual("诊断\n\n%s", values["slock_settings_diagnostics_feedback_composer_seed"])
                        self.assertEqual(r"keep \n and \q", values["literal"])
                        self.assertEqual("already\na newline", values["existing"])
                        self.assertEqual("one\ttwo\r\nthree", values["controls"])
                        self.assertNotIn("dependency", values)
                        argument = r"user\ntext"
                        self.assertEqual("诊断\n\n" + argument,
                                         values["slock_settings_diagnostics_feedback_composer_seed"] % argument)
            source = json.loads((generated / "res/base/element/string.json").read_text())
            self.assertEqual(templates, {item["name"]: item["value"] for item in source["string"]})


if __name__ == "__main__":
    unittest.main()
