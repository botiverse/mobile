#!/usr/bin/env python3
"""Find likely residual English in localized Android/Moko string catalogs.

The scanner intentionally reports evidence instead of treating every ASCII
letter as a translation bug. It removes formatting placeholders, URLs,
emails, machine-like sample values, and product/technical terms that the
Japanese glossary explicitly keeps in Latin script. The remaining text is
classified as either pure English or unusually English-heavy mixed text.

Exit status is zero by default so the report can be used for an audit. Pass
--fail-on-candidates when using it as a CI gate after the allowlist is stable.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


DEFAULT_CATALOG_ROOT = Path("compose/shared/src/commonMain/resources/MR")
DEFAULT_GLOSSARY = Path("docs/i18n-ja-glossary.md")
DEFAULT_ALLOWLIST = Path("scripts/i18n_english_allowlist.json")

FORMAT_PLACEHOLDER_RE = re.compile(
    r"%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[a-zA-Z]|%)"
)
BRACE_PLACEHOLDER_RE = re.compile(r"\{\{[^{}]+}}|\{[^{}]+}|\$\{[^{}]+}")
URL_RE = re.compile(r"(?:https?|wss?)://[^\s<]+", re.IGNORECASE)
EMAIL_RE = re.compile(r"\b[^\s@]+@[^\s@]+\.[^\s@]+\b")
XML_TAG_RE = re.compile(r"<[^>]+>")
ASCII_LETTER_RE = re.compile(r"[A-Za-z]")
LOCALIZED_CHAR_RE = re.compile(r"[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]")
WORD_RE = re.compile(r"[A-Za-z][A-Za-z0-9+.#_-]*")

# Values whose *shape* is data rather than prose. The key alone never
# suppresses a finding; the value must also match one of these forms.
MACHINE_VALUE_RES = (
    re.compile(r"^[a-z]{2,3}(?:-[A-Za-z0-9]+)*$"),
    re.compile(r"^@[A-Za-z0-9_.-]+$"),
    re.compile(r"^[A-Za-z0-9]+(?:[-_.:/][A-Za-z0-9]+)+$"),
    re.compile(r"^\d+(?:\.\d+){1,3}(?:[-+][A-Za-z0-9.-]+)?$"),
)

# Locale self-names and dense protocol/UI atoms intentionally stay in their
# established spelling. Product names come from the glossary dynamically.
EXACT_LATIN_ALLOWLIST = {
    "API",
    "CLI",
    "DM",
    "HTML",
    "HTTP",
    "HTTPS",
    "ID",
    "JSON",
    "MCP",
    "OAuth",
    "OK",
    "PWA",
    "QR",
    "UI",
    "URI",
    "URL",
    "UUID",
    "English",
}


@dataclass(frozen=True)
class Finding:
    locale: str
    key: str
    kind: str
    english_ratio: float
    value: str
    base_value: str
    normalized: str
    identical_to_base: bool


@dataclass(frozen=True)
class Suppressed:
    locale: str
    key: str
    reason: str
    value: str


def parse_catalog(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    result: dict[str, str] = {}
    for element in root.findall("string"):
        key = element.attrib.get("name")
        if not key:
            continue
        if key in result:
            raise ValueError(f"duplicate string key {key!r} in {path}")
        result[key] = "".join(element.itertext()).strip()
    return result


def glossary_latin_terms(path: Path) -> set[str]:
    if not path.exists():
        return set()
    text = path.read_text(encoding="utf-8")
    marker = "Brand names stay in Latin script:"
    start = text.find(marker)
    if start < 0:
        return set()
    paragraph = text[start:].split("\n\n", 1)[0]
    return {term.strip() for term in re.findall(r"\*\*([^*]+)\*\*", paragraph) if term.strip()}


def parse_allowlist(path: Path) -> dict[str, dict[str, str]]:
    if not path.exists():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"allowlist must be an object: {path}")
    result: dict[str, dict[str, str]] = {}
    for locale, entries in payload.items():
        if not isinstance(locale, str) or not isinstance(entries, dict):
            raise ValueError(f"allowlist locale entries must be objects: {path}")
        locale_entries: dict[str, str] = {}
        for key, reason in entries.items():
            if not isinstance(key, str) or not isinstance(reason, str) or not reason.strip():
                raise ValueError(f"allowlist entries require a non-empty reason: {locale}/{key}")
            locale_entries[key] = reason.strip()
        result[locale] = locale_entries
    return result


def remove_terms(text: str, terms: Iterable[str]) -> str:
    result = text
    for term in sorted(set(terms), key=len, reverse=True):
        result = re.sub(rf"(?<![A-Za-z0-9]){re.escape(term)}(?![A-Za-z0-9])", " ", result)
    return result


def normalized_for_language_detection(value: str, latin_terms: Iterable[str]) -> str:
    text = XML_TAG_RE.sub(" ", value)
    text = FORMAT_PLACEHOLDER_RE.sub(" ", text)
    text = BRACE_PLACEHOLDER_RE.sub(" ", text)
    text = URL_RE.sub(" ", text)
    text = EMAIL_RE.sub(" ", text)
    text = remove_terms(text, latin_terms)
    return re.sub(r"\s+", " ", text).strip()


def is_machine_value(value: str) -> bool:
    stripped = value.strip()
    if URL_RE.fullmatch(stripped) or EMAIL_RE.fullmatch(stripped):
        return True
    return any(pattern.fullmatch(stripped) for pattern in MACHINE_VALUE_RES)


def english_ratio(text: str) -> float:
    ascii_letters = len(ASCII_LETTER_RE.findall(text))
    localized_chars = len(LOCALIZED_CHAR_RE.findall(text))
    denominator = ascii_letters + localized_chars
    return ascii_letters / denominator if denominator else 0.0


def scan_locale(
    *,
    locale: str,
    localized: dict[str, str],
    base: dict[str, str],
    latin_terms: set[str],
    allowlist: dict[str, str],
    mixed_threshold: float,
) -> tuple[list[Finding], list[Suppressed]]:
    findings: list[Finding] = []
    suppressed: list[Suppressed] = []
    for key, value in localized.items():
        base_value = base.get(key, "")
        stripped = value.strip()

        if key in allowlist:
            suppressed.append(Suppressed(locale, key, f"reviewed allowlist: {allowlist[key]}", value))
            continue
        if key.endswith("_bilingual"):
            suppressed.append(Suppressed(locale, key, "intentional bilingual string", value))
            continue
        if stripped in EXACT_LATIN_ALLOWLIST:
            suppressed.append(Suppressed(locale, key, "exact UI/protocol atom", value))
            continue
        if is_machine_value(stripped):
            suppressed.append(Suppressed(locale, key, "machine/sample value", value))
            continue

        normalized = normalized_for_language_detection(value, latin_terms)
        words = WORD_RE.findall(normalized)
        ascii_count = len(ASCII_LETTER_RE.findall(normalized))
        localized_count = len(LOCALIZED_CHAR_RE.findall(normalized))
        if ascii_count == 0:
            if ASCII_LETTER_RE.search(value):
                suppressed.append(Suppressed(locale, key, "allowed Latin term only", value))
            continue

        ratio = english_ratio(normalized)
        kind: str | None = None
        if localized_count == 0 and words:
            kind = "pure-english"
        elif ratio >= mixed_threshold and words:
            kind = "english-heavy-mixed"
        if kind is None:
            continue

        findings.append(
            Finding(
                locale=locale,
                key=key,
                kind=kind,
                english_ratio=round(ratio, 4),
                value=value,
                base_value=base_value,
                normalized=normalized,
                identical_to_base=value == base_value,
            )
        )
    return findings, suppressed


def markdown_report(findings: list[Finding], suppressed: list[Suppressed]) -> str:
    lines = [
        "# Residual English scan",
        "",
        f"Candidates: **{len(findings)}** · intentionally suppressed: **{len(suppressed)}**",
        "",
    ]
    for locale in sorted({item.locale for item in findings} | {item.locale for item in suppressed}):
        locale_findings = [item for item in findings if item.locale == locale]
        locale_suppressed = [item for item in suppressed if item.locale == locale]
        lines.extend(
            [
                f"## {locale}",
                "",
                f"Candidates: {len(locale_findings)} · suppressed: {len(locale_suppressed)}",
                "",
            ]
        )
        if locale_findings:
            lines.extend(
                [
                    "| key | kind | ratio | identical to base | localized value | base value |",
                    "| --- | --- | ---: | :---: | --- | --- |",
                ]
            )
            for item in sorted(
                locale_findings,
                key=lambda candidate: (
                    not candidate.identical_to_base,
                    candidate.kind != "pure-english",
                    -candidate.english_ratio,
                    candidate.key,
                ),
            ):
                values = [
                    item.key,
                    item.kind,
                    f"{item.english_ratio:.2f}",
                    "yes" if item.identical_to_base else "no",
                    item.value,
                    item.base_value,
                ]
                escaped = [value.replace("|", "\\|").replace("\n", " ") for value in values]
                lines.append("| " + " | ".join(escaped) + " |")
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog-root", type=Path, default=DEFAULT_CATALOG_ROOT)
    parser.add_argument("--base-locale", default="base")
    parser.add_argument("--locale", action="append", dest="locales")
    parser.add_argument("--glossary", type=Path, default=DEFAULT_GLOSSARY)
    parser.add_argument("--allowlist", type=Path, default=DEFAULT_ALLOWLIST)
    parser.add_argument("--mixed-threshold", type=float, default=0.65)
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--include-suppressed", action="store_true")
    parser.add_argument("--fail-on-candidates", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not 0.0 <= args.mixed_threshold <= 1.0:
        raise SystemExit("--mixed-threshold must be between 0 and 1")

    base_path = args.catalog_root / args.base_locale / "strings.xml"
    base = parse_catalog(base_path)
    locales = args.locales or [
        path.parent.name
        for path in sorted(args.catalog_root.glob("*/strings.xml"))
        if path.parent.name != args.base_locale
    ]
    terms = glossary_latin_terms(args.glossary)
    allowlist = parse_allowlist(args.allowlist)
    findings: list[Finding] = []
    suppressed: list[Suppressed] = []
    for locale in locales:
        localized_path = args.catalog_root / locale / "strings.xml"
        localized = parse_catalog(localized_path)
        missing = sorted(set(base) - set(localized))
        extra = sorted(set(localized) - set(base))
        if missing or extra:
            raise SystemExit(
                f"catalog keys differ for {locale}: missing={len(missing)} extra={len(extra)}"
            )
        locale_findings, locale_suppressed = scan_locale(
            locale=locale,
            localized=localized,
            base=base,
            latin_terms=terms,
            allowlist=allowlist.get(locale, {}),
            mixed_threshold=args.mixed_threshold,
        )
        findings.extend(locale_findings)
        suppressed.extend(locale_suppressed)

    if args.format == "json":
        payload: dict[str, object] = {
            "catalogRoot": str(args.catalog_root),
            "baseLocale": args.base_locale,
            "locales": locales,
            "mixedThreshold": args.mixed_threshold,
            "glossaryLatinTerms": sorted(terms),
            "candidateCount": len(findings),
            "candidates": [asdict(item) for item in findings],
            "suppressedCount": len(suppressed),
        }
        if args.include_suppressed:
            payload["suppressed"] = [asdict(item) for item in suppressed]
        output = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    else:
        output = markdown_report(findings, suppressed)

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)
    return 1 if args.fail_on_candidates and findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
