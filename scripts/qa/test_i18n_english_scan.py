#!/usr/bin/env python3

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import i18n_english_scan as scanner  # noqa: E402


class I18nEnglishScanTest(unittest.TestCase):
    def test_normalization_removes_placeholders_urls_and_glossary_terms(self) -> None:
        normalized = scanner.normalized_for_language_detection(
            "Raft API %1$s https://example.com を開く",
            {"Raft", "API"},
        )
        self.assertEqual("を開く", normalized)

    def test_pure_english_identical_to_base_is_candidate(self) -> None:
        findings, suppressed = scanner.scan_locale(
            locale="ja",
            localized={"common_save": "Save"},
            base={"common_save": "Save"},
            latin_terms=set(),
            mixed_threshold=0.65,
        )
        self.assertEqual([], suppressed)
        self.assertEqual(1, len(findings))
        self.assertEqual("pure-english", findings[0].kind)
        self.assertTrue(findings[0].identical_to_base)

    def test_machine_and_brand_only_values_are_suppressed(self) -> None:
        findings, suppressed = scanner.scan_locale(
            locale="ja",
            localized={
                "settings_url": "https://example.com/api/",
                "product_name": "Raft",
                "locale_tag": "ja",
            },
            base={
                "settings_url": "https://example.com/api/",
                "product_name": "Raft",
                "locale_tag": "en",
            },
            latin_terms={"Raft"},
            mixed_threshold=0.65,
        )
        self.assertEqual([], findings)
        self.assertEqual(3, len(suppressed))

    def test_english_heavy_mixed_text_is_candidate(self) -> None:
        findings, _ = scanner.scan_locale(
            locale="ja",
            localized={"diagnostic": "Retry connection を確認"},
            base={"diagnostic": "Retry connection"},
            latin_terms=set(),
            mixed_threshold=0.65,
        )
        self.assertEqual(1, len(findings))
        self.assertEqual("english-heavy-mixed", findings[0].kind)

    def test_localized_text_with_allowed_latin_term_is_not_candidate(self) -> None:
        findings, _ = scanner.scan_locale(
            locale="ja",
            localized={"oauth_help": "OAuth 設定を開きます"},
            base={"oauth_help": "Open OAuth settings"},
            latin_terms={"OAuth"},
            mixed_threshold=0.65,
        )
        self.assertEqual([], findings)


if __name__ == "__main__":
    unittest.main()
