#!/usr/bin/env python3

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import i18n_code_literal_scan as scanner  # noqa: E402


class I18nCodeLiteralScanTest(unittest.TestCase):
    def scan(self, suffix: str, source: str) -> list[scanner.Finding]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / f"SettingsPage{suffix}"
            path.write_text(source, encoding="utf-8")
            return scanner.scan_file(path, root)

    def test_kotlin_text_literal_is_high_confidence(self) -> None:
        findings = self.scan(".kt", 'Text("Loading settings")\n')
        self.assertEqual(1, len(findings))
        self.assertEqual("high", findings[0].confidence)
        self.assertEqual("Loading settings", findings[0].literal)

    def test_arkts_single_quoted_text_is_detected(self) -> None:
        findings = self.scan(".ets", "Text('No recent activity')\n")
        self.assertEqual(1, len(findings))
        self.assertEqual("arkts", findings[0].language)

    def test_localized_resource_reference_is_not_reported(self) -> None:
        kotlin_findings = self.scan(".kt", 'Text(stringResource(MR.strings.settings_title))\n')
        arkts_findings = self.scan(".ets", "Text($r('app.string.settings_title'))\n")
        self.assertEqual([], kotlin_findings)
        self.assertEqual([], arkts_findings)

    def test_adjacent_resource_reference_does_not_hide_hardcode_in_either_order(self) -> None:
        localized = "Text(stringResource(MR.strings.settings_title))"
        hardcoded = 'Text("Hardcoded English")'
        for source in (f"{hardcoded}\n{localized}\n", f"{localized}\n{hardcoded}\n"):
            with self.subTest(source=source):
                findings = self.scan(".kt", source)
                self.assertEqual(["Hardcoded English"], [item.literal for item in findings])
                self.assertTrue(scanner.is_direct_sink(findings[0].sink))

    def test_log_and_machine_literals_are_not_reported(self) -> None:
        source = '\n'.join(
            [
                'Log.i("Settings", "Loading settings")',
                'val pageName = "compose_settings_route"',
                'Text("https://example.com")',
            ]
        )
        self.assertEqual([], self.scan(".kt", source))

    def test_comment_or_log_sibling_does_not_hide_direct_text(self) -> None:
        sources = (
            'Text("Hardcoded English") // diagnostic fallback until loading completes',
            'Text("Hardcoded English"); Log.i("tag", "rendered")',
        )
        for source in sources:
            with self.subTest(source=source):
                findings = self.scan(".kt", source)
                self.assertEqual(["Hardcoded English"], [item.literal for item in findings])
                self.assertTrue(scanner.is_direct_sink(findings[0].sink))

    def test_pure_log_line_remains_excluded(self) -> None:
        self.assertEqual([], self.scan(".kt", 'Log.i("tag", "rendered")'))

    def test_nearby_multiline_ui_argument_is_medium_confidence(self) -> None:
        source = '\n'.join(
            [
                "SlockDialog(",
                '    title = "Delete server",',
                "    onDismiss = {},",
                ")",
            ]
        )
        findings = self.scan(".kt", source)
        self.assertEqual(1, len(findings))
        self.assertIn(findings[0].confidence, {"high", "medium"})

    def test_cjk_and_reviewed_exact_technical_name_are_not_reported(self) -> None:
        source = '\n'.join(['Text("设置")', 'Text("Android")'])
        self.assertEqual([], self.scan(".kt", source))

    def test_allowlist_is_exact_and_reasoned(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "allowlist.json"
            path.write_text(
                '[{"path":"Probe.kt","literal":"Smoke","reason":"internal probe"}]',
                encoding="utf-8",
            )
            entries = scanner.parse_allowlist(path)
        self.assertIn(("Probe.kt", "Smoke"), entries)
        self.assertEqual("internal probe", entries[("Probe.kt", "Smoke")].reason)

    def test_mr_value_index_maps_existing_translation_key(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "strings.xml"
            path.write_text(
                '<resources><string name="common_save">Save</string></resources>',
                encoding="utf-8",
            )
            values = scanner.parse_mr_value_index(path)
        self.assertEqual(("common_save",), values["Save"])

    def test_multiline_toast_literal_is_a_direct_sink(self) -> None:
        source = '\n'.join(
            [
                "Toast.makeText(",
                "    context,",
                '    "Email verified. Sign in to continue.",',
                "    Toast.LENGTH_SHORT,",
                ").show()",
            ]
        )
        findings = self.scan(".kt", source)
        self.assertEqual(1, len(findings))
        self.assertTrue(scanner.is_direct_sink(findings[0].sink))

    def test_multiline_text_positional_and_named_literals_are_direct(self) -> None:
        sources = (
            '\n'.join(["Text(", '    "Hardcoded English"', ")"]),
            '\n'.join(["Text(", '    text = "Hardcoded English"', ")"]),
        )
        for source in sources:
            with self.subTest(source=source):
                findings = self.scan(".kt", source)
                self.assertEqual(["Hardcoded English"], [item.literal for item in findings])
                self.assertEqual("multiline-text-call", findings[0].sink)
                self.assertTrue(scanner.is_direct_sink(findings[0].sink))

    def test_multiline_toast_next_to_resource_label_remains_a_direct_sink(self) -> None:
        source = '\n'.join(
            [
                "Text(stringResource(MR.strings.settings_title))",
                "Toast.makeText(",
                "    context,",
                '    "Email verified. Sign in to continue.",',
                "    Toast.LENGTH_SHORT,",
                ").show()",
            ]
        )
        findings = self.scan(".kt", source)
        self.assertEqual(
            ["Email verified. Sign in to continue."],
            [item.literal for item in findings],
        )
        self.assertEqual("multiline-toast-dialog", findings[0].sink)


if __name__ == "__main__":
    unittest.main()
