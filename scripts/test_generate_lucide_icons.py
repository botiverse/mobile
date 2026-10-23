import importlib.util
import json
import os
import re
import tempfile
import unittest
from unittest import mock


SCRIPT_PATH = os.path.join(os.path.dirname(__file__), "generate_lucide_icons.py")
SPEC = importlib.util.spec_from_file_location("generate_lucide_icons", SCRIPT_PATH)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class GenerateLucideIconsTest(unittest.TestCase):
    def test_default_targets_include_ios(self):
        self.assertIn("ios", GENERATOR.parse_targets(GENERATOR.DEFAULT_TARGETS))

    def test_default_targets_generate_compose_table_not_native_renderers(self):
        targets = GENERATOR.parse_targets(GENERATOR.DEFAULT_TARGETS)
        self.assertIn("compose", targets)
        self.assertIn("shared", targets)
        for removed in ("android", "android-path"):
            with self.assertRaises(Exception):
                GENERATOR.parse_targets(removed)

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

    def test_strict_svg_parser_rejects_unmodeled_paints(self):
        for paint in ("url(#gradient)", "rgb(0, 0, 0)", "var(--icon-color)", "#000000FF"):
            with self.subTest(paint=paint):
                with self.assertRaisesRegex(RuntimeError, "Unsupported SVG fill paint"):
                    GENERATOR.parse_svg_strict(
                        f"""
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                          <path d="M 0 0 L 1 1" fill="{paint}" />
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
            '<path d="M 0 0 A -1 1 0 0 0 3 3" />',
            '<path d="M 1e999 0 L 1 1" />',
            '<path d="M 0 0 A 1e999 1 0 0 0 2 2" />',
            '<path d="M 1e-999 0 L 1 1" />',
            '<circle cx="1" cy="1" r="-1" />',
            '<ellipse cx="1" cy="1" rx="1" ry="-1" />',
            '<rect width="-1" height="1" />',
            '<rect width="1" height="1" rx="-1" />',
            '<polyline points="0,0 1" />',
            '<polyline points="0,0" />',
            '<polygon points="0,0 1,1" />',
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

    def test_strict_svg_parser_skips_explicit_zero_area_primitives(self):
        parsed = GENERATOR.parse_svg_strict(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <circle cx="1" cy="1" r="0" />
              <ellipse cx="1" cy="1" rx="0" ry="1" />
              <rect width="0" height="1" />
              <path d="M 0 0 L 1 1" />
            </svg>
            """
        )
        self.assertEqual(1, len(parsed["paths"]))
        self.assertEqual("M 0 0 L 1 1", parsed["paths"][0]["pathData"])

    def test_strict_svg_parser_accepts_finite_scientific_path_numbers(self):
        parsed = GENERATOR.parse_svg_strict(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path d="M 1e2 1e-2 L 2.5e1 -3e0" />
            </svg>
            """
        )
        self.assertEqual("M 1e2 1e-2 L 2.5e1 -3e0", parsed["paths"][0]["pathData"])
        self.assertNotEqual("0.0f", GENERATOR.kotlin_float(1e-5))
    def test_shared_catalog_matches_manifest(self):
        with open(GENERATOR.DEFAULT_SHARED_CATALOG_OUTPUT, encoding="utf-8") as file:
            shared_catalog = file.read()
        shared_names = re.findall(
            r'^\s+\w+\("([^"]+)"\)[,;]$',
            shared_catalog,
            re.MULTILINE,
        )
        self.assertEqual(GENERATOR.LUCIDE_ICONS, shared_names)
        self.assertEqual(GENERATOR.shared_catalog_source(GENERATOR.LUCIDE_ICONS), shared_catalog)

    def test_compose_table_covers_manifest_in_enum_order(self):
        with open(GENERATOR.DEFAULT_COMPOSE_OUTPUT, encoding="utf-8") as file:
            source = file.read()
        spec_body = source.split("fun spec(name: RaftLucideIconName)", 1)[1].split("fun filledSpec", 1)[0]
        branches = re.findall(r"^\s+RaftLucideIconName\.(\w+) ->$", spec_body, re.MULTILINE)
        self.assertEqual([GENERATOR.pascal_name(icon) for icon in GENERATOR.LUCIDE_ICONS], branches)
        self.assertNotIn("else ->", spec_body)

    def _write_bookmark_checkout(self, checkout):
        icons_dir = os.path.join(checkout, "icons")
        os.makedirs(icons_dir)
        with open(os.path.join(icons_dir, "bookmark.svg"), "w", encoding="utf-8") as file:
            file.write(
                """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"
                     fill="none" stroke="currentColor" stroke-width="2"
                     stroke-linecap="round" stroke-linejoin="round">
                  <path d="M 5 3 L 19 3 L 19 21 L 12 17 L 5 21 Z" />
                  <circle cx="12" cy="9" r="1" fill="currentColor" stroke-width="1.5" />
                </svg>
                """
            )

    def test_compose_table_is_deterministic_compact_and_carries_filled_variant(self):
        with tempfile.TemporaryDirectory() as checkout:
            self._write_bookmark_checkout(checkout)
            first = GENERATOR.compose_catalog_source(["bookmark"], GENERATOR.DEFAULT_VERSION, checkout)
            second = GENERATOR.compose_catalog_source(["bookmark"], GENERATOR.DEFAULT_VERSION, checkout)

        self.assertEqual(first, second)
        self.assertIn("// Generated by scripts/generate_lucide_icons.py. Do not edit by hand.", first)
        self.assertIn(GENERATOR.LUCIDE_SOURCE_COMMITS[GENERATOR.DEFAULT_VERSION], first)
        # Default Lucide stroke model is implied, not repeated per path.
        self.assertIn('RaftLucidePathSpec("M 5 3 L 19 3 L 19 21 L 12 17 L 5 21 Z"),', first)
        # Per-path SVG overrides survive.
        self.assertIn('fillPaint = "currentColor", strokeWidth = 1.5f)', first)
        spec_body = first.split("fun spec(", 1)[1].split("fun filledSpec", 1)[0]
        filled_body = first.split("fun filledSpec", 1)[1]
        self.assertIn("RaftLucideIconName.Bookmark ->", spec_body)
        self.assertIn('"filled-bookmark"', filled_body)
        self.assertIn(
            'RaftLucidePathSpec("M 5 3 L 19 3 L 19 21 L 12 17 L 5 21 Z", fillPaint = "currentColor")',
            filled_body,
        )

    def test_compose_path_entry_emits_only_non_default_attributes(self):
        path = {
            "pathData": "M 0 0 L 1 1",
            "fillPaint": None,
            "strokePaint": "#112233",
            "fillOpacity": 0.0,
            "strokeOpacity": 0.5,
            "strokeWidth": 3.0,
            "strokeLineCap": "square",
            "strokeLineJoin": "bevel",
            "fillRule": "evenodd",
        }
        self.assertEqual(
            'RaftLucidePathSpec("M 0 0 L 1 1", strokePaint = "#112233", strokeOpacity = 0.5f, '
            "strokeWidth = 3f, strokeLineCap = RaftLucideStrokeLineCap.Square, "
            "strokeLineJoin = RaftLucideStrokeLineJoin.Bevel, fillRule = RaftLucideFillRule.EvenOdd)",
            GENERATOR.compose_path_entry_expr(path),
        )

    def test_repository_compose_table_matches_pinned_checkout_when_available(self):
        checkout = GENERATOR.find_cached_lucide_checkout(
            GENERATOR.DEFAULT_VERSION,
            GENERATOR.DEFAULT_LUCIDE_CACHE_DIR,
        )
        if checkout is None:
            self.skipTest("Pinned Lucide checkout is not cached")
        regenerated = GENERATOR.compose_catalog_source(
            GENERATOR.LUCIDE_ICONS,
            GENERATOR.DEFAULT_VERSION,
            checkout,
        )
        with open(GENERATOR.DEFAULT_COMPOSE_OUTPUT, encoding="utf-8") as file:
            self.assertEqual(regenerated, file.read())

    def test_check_mode_reports_stale_output_without_writing(self):
        with tempfile.TemporaryDirectory() as output:
            path = os.path.join(output, "Out.kt")
            self.assertFalse(GENERATOR.check_text(path, "expected"))
            self.assertFalse(os.path.exists(path))
            with open(path, "w", encoding="utf-8") as file:
                file.write("expected")
            self.assertTrue(GENERATOR.check_text(path, "expected"))
            self.assertFalse(GENERATOR.check_text(path, "other"))

    def test_compose_writer_rejects_non_git_source_directory(self):
        with tempfile.TemporaryDirectory() as checkout, tempfile.TemporaryDirectory() as output:
            with self.assertRaisesRegex(RuntimeError, "git-backed Lucide checkout"):
                GENERATOR.generate_compose(
                    [],
                    GENERATOR.DEFAULT_VERSION,
                    os.path.join(output, "RaftLucideIconPaths.kt"),
                    checkout,
                )

    def test_pinned_source_requires_exact_commit_and_tag(self):
        expected_commit = GENERATOR.LUCIDE_SOURCE_COMMITS[GENERATOR.DEFAULT_VERSION]
        with tempfile.TemporaryDirectory() as checkout:
            os.makedirs(os.path.join(checkout, ".git"))
            with mock.patch.object(GENERATOR, "checkout_head", return_value="0" * 40), \
                 mock.patch.object(GENERATOR, "checkout_matches_version", return_value=True):
                with self.assertRaisesRegex(RuntimeError, "requires Lucide 1.14.0 commit"):
                    GENERATOR.verify_pinned_source_checkout(
                        checkout, GENERATOR.DEFAULT_VERSION
                    )
            with mock.patch.object(GENERATOR, "checkout_head", return_value=expected_commit), \
                 mock.patch.object(GENERATOR, "checkout_matches_version", return_value=False):
                with self.assertRaisesRegex(RuntimeError, "exact tag 1.14.0"):
                    GENERATOR.verify_pinned_source_checkout(
                        checkout, GENERATOR.DEFAULT_VERSION
                    )
            with mock.patch.object(GENERATOR, "checkout_head", return_value=expected_commit), \
                 mock.patch.object(GENERATOR, "checkout_matches_version", return_value=True):
                self.assertEqual(
                    expected_commit,
                    GENERATOR.verify_pinned_source_checkout(
                        checkout, GENERATOR.DEFAULT_VERSION
                    ),
                )


if __name__ == "__main__":
    unittest.main()
