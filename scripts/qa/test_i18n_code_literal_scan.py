#!/usr/bin/env python3

import json
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

    def test_localized_resource_reference_is_not_reported(self) -> None:
        findings = self.scan(".kt", 'Text(stringResource(MR.strings.settings_title))\n')
        self.assertEqual([], findings)

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

    def test_template_only_text_is_not_english_but_visible_copy_remains(self) -> None:
        source = '\n'.join(
            [
                'Text("${pageIndex + 1} / ${pages.size}")',
                'Text("Retry · ${row.code}")',
            ]
        )
        findings = self.scan(".kt", source)
        self.assertEqual(["Retry · ${row.code}"], [item.literal for item in findings])

    def test_kotlin_conditional_template_reports_visible_branch_literals(self) -> None:
        findings = self.scan(".kt", 'Text("${if (ok) \"Ready\" else \"Failed\"}")')
        self.assertEqual(["Ready", "Failed"], [item.literal for item in findings])

    def test_machine_branch_and_comparison_tokens_are_not_direct_copy(self) -> None:
        source = '\n'.join(
            [
                "Text(",
                "    text = when (mode) {",
                '        "create" -> stringResource(MR.strings.create)',
                '        "empty" -> stringResource(MR.strings.empty)',
                '        else -> "Visible English"',
                "    },",
                ")",
                'Text(text = if (title.equals("workspace", ignoreCase = true)) "Workspace" else title)',
                'Text(text = if (status == "failed") "Failure" else status)',
            ]
        )
        findings = self.scan(".kt", source)
        self.assertEqual(
            ["Visible English", "Workspace", "Failure"],
            [item.literal for item in findings],
        )

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
        self.assertIn(("Probe.kt", "Smoke", None), entries)
        self.assertEqual("internal probe", entries[("Probe.kt", "Smoke", None)].reason)

    def test_allowlist_resolution_is_occurrence_exact(self) -> None:
        findings = self.scan(".kt", '\n'.join(['Text("Shared label")', 'title = "Shared label"']))
        reviewed = scanner.ReviewedException(
            path="SettingsPage.kt",
            literal="Shared label",
            reason="internal fixture title",
            context='title = "Shared label"',
        )
        resolved = scanner.resolve_allowlist_occurrences(
            findings,
            {(reviewed.path, reviewed.literal, reviewed.context): reviewed},
        )
        remaining = [
            item
            for item in findings
            if (item.path, item.line, item.column, item.literal) not in resolved
        ]
        self.assertEqual(['Text("Shared label")'], [item.context for item in remaining])

    def test_ambiguous_allowlist_entry_fails_closed(self) -> None:
        findings = self.scan(".kt", '\n'.join(['Text("Shared label")', 'title = "Shared label"']))
        reviewed = scanner.ReviewedException(
            path="SettingsPage.kt",
            literal="Shared label",
            reason="ambiguous review",
        )
        with self.assertRaisesRegex(ValueError, "matches=2"):
            scanner.resolve_allowlist_occurrences(
                findings,
                {(reviewed.path, reviewed.literal, reviewed.context): reviewed},
            )

    def test_stale_allowlist_entry_is_reported_not_fatal(self) -> None:
        findings = self.scan(".kt", 'Text("Live label")')
        live = scanner.ReviewedException(path="SettingsPage.kt", literal="Live label", reason="kept")
        gone = scanner.ReviewedException(path="SettingsPage.kt", literal="Removed label", reason="code deleted")
        allowlist = {(e.path, e.literal, e.context): e for e in (live, gone)}
        resolved = scanner.resolve_allowlist_occurrences(findings, allowlist)
        self.assertEqual(1, len(resolved))
        self.assertEqual([gone], scanner.stale_allowlist_entries(findings, allowlist))
        report = scanner.markdown_report([], 1, 1, [gone])
        self.assertIn("Stale allowlist entries: 1", report)
        self.assertIn("Removed label", report)

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

    def high(self, source: str) -> list[str]:
        return [item.literal for item in self.scan(".kt", source) if item.confidence == "high"]

    def test_ui_property_is_high_only_for_its_own_value(self) -> None:
        cases = (
            # JSON keys, ids and status tokens sharing the line with a UI property name.
            ('label = msg(props["label"], 40, "$what.label"),', []),
            ('val label = obj.safeText("label", 80) ?: return null', []),
            ('add(RaftBaseMenuItem(id = "rename", label = labels.renameSection))', []),
            ('enum class Kind(val label: String) { Sample("sample"), Interaction("interaction") }', []),
            ('fun log(message: String) = report("Tag name", message)', []),
            ('if (title == "Members") return', []),
            # The value itself, including fallback chains and branches.
            ('result(status = "cancelled", message = "Download cancelled")', ["Download cancelled"]),
            ('val title = dm.name.trim().ifBlank { "Direct Message" }', ["Direct Message"]),
            ('subtitle = subtitle?.takeIf { it.isNotBlank() } ?: "Recent DM",', ["Recent DM"]),
            ('label = if (scheduled) "Scheduled" else "Cancel subscription",', ["Scheduled", "Cancel subscription"]),
            ('message = email?.let { "Invite created for $it" },', ["Invite created for $it"]),
            ('val title: String = "Settings",', ["Settings"]),
            ('fun validate(name: String, label: String = "Agent name"): String? {', ["Agent name"]),
        )
        for source, expected in cases:
            with self.subTest(source=source):
                self.assertEqual(expected, self.high(source))

    def test_literal_next_to_a_sink_is_medium_not_dropped(self) -> None:
        findings = self.scan(".kt", 'add(RaftBaseMenuItem(id = "rename", label = labels.renameSection))')
        self.assertEqual(
            [("rename", "medium", "near-ui-property")],
            [(item.literal, item.confidence, item.sink) for item in findings],
        )

    def test_direct_call_sink_is_high_only_for_its_own_arguments(self) -> None:
        cases = (
            ('Text("Hello there", modifier = Modifier.testTag("Hello tag"))', ["Hello there"]),
            ('Html("html"), Markdown("markdown"), Text("text"), Csv("csv"), Pdf("pdf");', []),
            ('value = ActionCardFormValue.Text("Shown text")', []),
            ('val channelId = root.text("channelId thread") ?: return null', []),
            ('RaftText("Compose Multiplatform", style = type.title)', ["Compose Multiplatform"]),
            ('host.capabilities.toast("Hello from the host")', ["Hello from the host"]),
            (
                '\n'.join(["Text(", '    text = "Shown text",', '    modifier = Modifier.testTag("Tag text"),', ")"]),
                ["Shown text"],
            ),
            ('\n'.join(["ActionCardSpecNode.Text(", '    tone = props.opt("tone value") { it },', ")"]), []),
        )
        for source, expected in cases:
            with self.subTest(source=source):
                self.assertEqual(expected, self.high(source))

    def test_log_regex_and_annotation_arguments_are_not_reported(self) -> None:
        sources = (
            '\n'.join(["@RequiresOptIn(", "    level = WARNING,", '    message = "Internal API only.",', ")"]),
            '\n'.join(["raftLogWarn(", '    tag = "Tag",', '    message = "Something failed badly",', ")"]),
            'private fun logAppLogin(message: String) = raftLogInfo("SlockAppLogin", message)',
            'val text = content.replace(Regex("\\\\s+ here"), " ").trim()',
            'Text(stringResource(Res.string.title)) // Text("Commented out")',
        )
        for source in sources:
            with self.subTest(source=source):
                self.assertEqual([], self.scan(".kt", source))

    def test_machine_shaped_literals_are_not_reported(self) -> None:
        sources = (
            'message = "play_services_check code=$code fcm_unavailable=$unavailable"',
            'val label = "server=${id} scope=${scope} done"',
            'label = "otterTuck",',
            'emptyText = { query -> noMatches.replace("\\u0001", query) },',
            '\n'.join(['val script = """', '    document.title = "Hello world";', '"""']),
        )
        for source in sources:
            with self.subTest(source=source):
                self.assertEqual([], self.scan(".kt", source))

    def test_allowlist_object_form_needs_a_reason_for_every_entry(self) -> None:
        valid = {
            "excludeSourceSets": [{"sourceSet": "commonTest", "reason": "unit tests"}],
            "excludePaths": [{"path": "src/Probe.kt", "reason": "developer probe"}],
            "occurrences": [{"path": "Page.kt", "literal": "Shared label", "count": 2, "reason": "fixture"}],
        }
        invalid = (
            {"excludePaths": [{"path": "src/Probe.kt"}]},
            {"excludeSourceSets": [{"sourceSet": "commonTest", "reason": " "}]},
            {"occurrences": [{"path": "Page.kt", "literal": "Label", "reason": "x", "count": 0}]},
            {"occurrences": [{"path": "Page.kt", "literal": "Label", "reason": "x", "line": 3}]},
            {"exclude": []},
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "allowlist.json"
            path.write_text(json.dumps(valid), encoding="utf-8")
            allowlist = scanner.load_allowlist(path)
            for payload in invalid:
                with self.subTest(payload=payload):
                    path.write_text(json.dumps(payload), encoding="utf-8")
                    with self.assertRaises(ValueError):
                        scanner.load_allowlist(path)
        self.assertEqual(2, allowlist.occurrences[("Page.kt", "Shared label", None)].count)
        self.assertEqual("developer probe", scanner.excluded_by("src/Probe.kt", allowlist.exclusions).reason)
        in_test_source_set = scanner.excluded_by("mod/src/commonTest/kotlin/A.kt", allowlist.exclusions)
        self.assertEqual("unit tests", in_test_source_set.reason)
        for kept in ("src/ProbePage.kt", "mod/src/commonMain/kotlin/commonTest/A.kt"):
            with self.subTest(kept=kept):
                self.assertIsNone(scanner.excluded_by(kept, allowlist.exclusions))

    def test_counted_allowlist_entry_covers_exactly_that_many_occurrences(self) -> None:
        findings = self.scan(".kt", '\n'.join(['title = "Shared label"', 'subtitle = x', 'title = "Shared label"']))
        entry = scanner.ReviewedException(path="SettingsPage.kt", literal="Shared label", reason="fixture", count=2)
        resolved = scanner.resolve_allowlist_occurrences(findings, {(entry.path, entry.literal, None): entry})
        self.assertEqual({1, 3}, {line for _, line, _, _ in resolved})
        too_few = scanner.ReviewedException(path="SettingsPage.kt", literal="Shared label", reason="fixture", count=1)
        with self.assertRaisesRegex(ValueError, "matches=2"):
            scanner.resolve_allowlist_occurrences(findings, {(too_few.path, too_few.literal, None): too_few})

    def test_main_skips_excluded_files_and_reports_stale_exclusions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ("app/src/main/Page.kt", "app/src/main/Probe.kt", "app/src/test/PageTest.kt"):
                (root / relative).parent.mkdir(parents=True, exist_ok=True)
                (root / relative).write_text('Text("Hardcoded English")\n', encoding="utf-8")
            allowlist = root / "allowlist.json"
            allowlist.write_text(
                json.dumps(
                    {
                        "excludeSourceSets": [{"sourceSet": "test", "reason": "unit tests"}],
                        "excludePaths": [
                            {"path": "app/src/main/Probe.kt", "reason": "developer probe"},
                            {"path": "app/src/main/Gone.kt", "reason": "moved away"},
                        ],
                    }
                ),
                encoding="utf-8",
            )
            output = root / "report.json"
            argv = ["--repo-root", str(root), "--source-root", "app/src", "--allowlist", str(allowlist)]
            exit_code = scanner.main(argv + ["--format", "json", "--output", str(output), "--fail-on-stale"])
            report = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(1, exit_code)
        self.assertEqual((1, 2), (report["scannedFileCount"], report["excludedFileCount"]))
        self.assertEqual(["app/src/main/Page.kt"], [item["path"] for item in report["findings"]])
        self.assertEqual(["app/src/main/Gone.kt"], report["staleExcludePaths"])


if __name__ == "__main__":
    unittest.main()
