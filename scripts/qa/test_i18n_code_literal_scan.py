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
        findings = self.scan(".kt", 'Text(stringResource(MR.strings.settings_title))\n')
        self.assertEqual([], findings)

    def test_log_and_machine_literals_are_not_reported(self) -> None:
        source = '\n'.join(
            [
                'Log.i("Settings", "Loading settings")',
                'val pageName = "compose_settings_route"',
                'Text("https://example.com")',
            ]
        )
        self.assertEqual([], self.scan(".kt", source))

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


if __name__ == "__main__":
    unittest.main()
