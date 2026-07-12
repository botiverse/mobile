import importlib.util
import json
import os
import tempfile
import unittest


SCRIPT_PATH = os.path.join(os.path.dirname(__file__), "generate_lucide_icons.py")
SPEC = importlib.util.spec_from_file_location("generate_lucide_icons", SCRIPT_PATH)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class GenerateLucideIconsTest(unittest.TestCase):
    def test_default_targets_include_ios(self):
        self.assertIn("ios", GENERATOR.parse_targets(GENERATOR.DEFAULT_TARGETS))

    def test_repository_ios_catalog_covers_manifest(self):
        GENERATOR.validate_ios_assets(
            GENERATOR.LUCIDE_ICONS,
            GENERATOR.DEFAULT_IOS_ASSET_CATALOG,
        )

    def test_ios_template_rendering_is_opt_in(self):
        brand_contents = json.loads(GENERATOR.ios_imageset_contents("brand.svg"))
        icon_contents = json.loads(
            GENERATOR.ios_imageset_contents("bell.svg", template=True)
        )

        self.assertNotIn(
            "template-rendering-intent",
            brand_contents["properties"],
        )
        self.assertEqual(
            "template",
            icon_contents["properties"]["template-rendering-intent"],
        )

    def test_ios_asset_validation_requires_manifest_entries(self):
        with tempfile.TemporaryDirectory() as catalog:
            bell_dir = os.path.join(catalog, "lucide-bell.imageset")
            os.makedirs(bell_dir)
            with open(os.path.join(bell_dir, "bell.svg"), "w", encoding="utf-8") as file:
                file.write("<svg />\n")
            with open(os.path.join(bell_dir, "Contents.json"), "w", encoding="utf-8") as file:
                json.dump({"images": [{"filename": "bell.svg"}]}, file)

            with self.assertRaisesRegex(RuntimeError, "bell-off"):
                GENERATOR.validate_ios_assets(["bell", "bell-off"], catalog)

            bell_off_dir = os.path.join(catalog, "lucide-bell-off.imageset")
            os.makedirs(bell_off_dir)
            with open(os.path.join(bell_off_dir, "bell-off.svg"), "w", encoding="utf-8") as file:
                file.write("<svg />\n")
            with open(os.path.join(bell_off_dir, "Contents.json"), "w", encoding="utf-8") as file:
                json.dump({"images": [{"filename": "bell-off.svg"}]}, file)

            GENERATOR.validate_ios_assets(["bell", "bell-off"], catalog)


if __name__ == "__main__":
    unittest.main()
