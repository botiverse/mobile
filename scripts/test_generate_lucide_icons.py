import importlib.util
import json
import os
import re
import tempfile
import unittest


SCRIPT_PATH = os.path.join(os.path.dirname(__file__), "generate_lucide_icons.py")
SPEC = importlib.util.spec_from_file_location("generate_lucide_icons", SCRIPT_PATH)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class GenerateLucideIconsTest(unittest.TestCase):
    def test_default_targets_include_ios(self):
        self.assertIn("ios", GENERATOR.parse_targets(GENERATOR.DEFAULT_TARGETS))

    def test_default_targets_include_android_path_registry(self):
        self.assertIn("android-path", GENERATOR.parse_targets(GENERATOR.DEFAULT_TARGETS))

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

    def test_strict_svg_parser_preserves_rendering_semantics(self):
        parsed = GENERATOR.parse_svg_strict(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="1 2 32 16"
                 fill="currentColor" stroke="#112233" stroke-width="3"
                 stroke-linecap="square" stroke-linejoin="bevel">
              <g opacity="0.5" fill-rule="evenodd">
                <path d="M 1 2 L 3 4 Z" fill-opacity="0.4" stroke-opacity="0.6" />
              </g>
            </svg>
            """
        )

        self.assertEqual(1.0, parsed["viewBoxMinX"])
        self.assertEqual(2.0, parsed["viewBoxMinY"])
        self.assertEqual(32.0, parsed["viewBoxWidth"])
        self.assertEqual(16.0, parsed["viewBoxHeight"])
        self.assertEqual(
            {
                "pathData": "M 1 2 L 3 4 Z",
                "fillPaint": "currentColor",
                "strokePaint": "#112233",
                "fillOpacity": 0.2,
                "strokeOpacity": 0.3,
                "strokeWidth": 3.0,
                "strokeLineCap": "square",
                "strokeLineJoin": "bevel",
                "fillRule": "evenodd",
            },
            parsed["paths"][0],
        )

    def test_strict_svg_parser_rejects_transform_dash_and_unknown_attributes(self):
        invalid_attributes = [
            'transform="translate(1 2)"',
            'stroke-dasharray="2 2"',
            'data-unsupported="true"',
        ]
        for invalid_attribute in invalid_attributes:
            with self.subTest(attribute=invalid_attribute):
                with self.assertRaisesRegex(RuntimeError, "Unsupported SVG attribute"):
                    GENERATOR.parse_svg_strict(
                        f"""
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                          <path d="M 0 0 L 1 1" {invalid_attribute} />
                        </svg>
                        """
                    )

    def test_strict_svg_parser_rejects_unknown_elements_and_enum_values(self):
        with self.assertRaisesRegex(RuntimeError, "Unsupported SVG element"):
            GENERATOR.parse_svg_strict(
                """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                  <text>no</text>
                </svg>
                """
            )
        with self.assertRaisesRegex(RuntimeError, "stroke-linecap"):
            GENERATOR.parse_svg_strict(
                """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"
                     stroke-linecap="triangle">
                  <path d="M 0 0 L 1 1" />
                </svg>
                """
            )

    def test_strict_svg_parser_rejects_malformed_geometry(self):
        invalid_geometry = [
            '<path d="L 1 2" />',
            '<path d="M 1" />',
            '<path d="M 0 0 A 1 1 0 2 0 3 3" />',
            '<polyline points="0,0 1" />',
        ]
        for geometry in invalid_geometry:
            with self.subTest(geometry=geometry):
                with self.assertRaises(RuntimeError):
                    GENERATOR.parse_svg_strict(
                        f"""
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                          {geometry}
                        </svg>
                        """
                    )
    def test_android_path_catalog_matches_shared_enum_and_regenerates_identically(self):
        with open(GENERATOR.DEFAULT_SHARED_CATALOG_OUTPUT, encoding="utf-8") as file:
            shared_catalog = file.read()
        shared_names = re.findall(
            r'^\s+\w+\("([^"]+)"\)[,;]$',
            shared_catalog,
            re.MULTILINE,
        )
        self.assertEqual(GENERATOR.LUCIDE_ICONS, shared_names)

        first = GENERATOR.android_path_catalog_source(
            GENERATOR.LUCIDE_ICONS,
            GENERATOR.DEFAULT_VERSION,
            GENERATOR.DEFAULT_OHOS_RESFILE_OUTPUT,
            None,
        )
        second = GENERATOR.android_path_catalog_source(
            GENERATOR.LUCIDE_ICONS,
            GENERATOR.DEFAULT_VERSION,
            GENERATOR.DEFAULT_OHOS_RESFILE_OUTPUT,
            None,
        )
        self.assertEqual(first, second)
        with open(GENERATOR.DEFAULT_ANDROID_PATH_OUTPUT, encoding="utf-8") as file:
            self.assertEqual(first, file.read())


if __name__ == "__main__":
    unittest.main()
