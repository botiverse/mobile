#!/usr/bin/env python3
"""Audit production Kotlin and ArkTS for likely user-visible English literals.

This is a review scanner, not a compiler parser. It deliberately narrows the
search to production source roots and UI-shaped sinks (Text, Button, title,
label, placeholder, toast, dialog, widget text, etc.), then reports exact
file/line/context evidence. Machine strings, resource references, logging,
routes, URLs, protocol tokens, tests, and visual fixtures are excluded.

The default exit status is zero so teams can triage the first report. Use
--fail-on-findings after reviewed exceptions have been recorded.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from dataclasses import replace
from pathlib import Path


DEFAULT_ROOTS = (
    Path("compose/shared/src/commonMain/kotlin"),
    Path("compose/shared/src/androidMain/kotlin"),
    Path("compose/shared/src/iosMain/kotlin"),
    Path("compose/shared/src/jsMain/kotlin"),
    Path("compose/shared/src/nativeMain/kotlin"),
    Path("compose/shared/src/ohosArm64Main/kotlin"),
    Path("compose/androidApp/src/main/java"),
    Path("compose/ohosApp/entry/src/main/ets"),
)
DEFAULT_ALLOWLIST = Path("scripts/i18n_code_literal_allowlist.json")
DEFAULT_MR_BASE = Path("compose/shared/src/commonMain/resources/MR/base/strings.xml")

DOUBLE_QUOTED_RE = re.compile(r'"(?:\\.|[^"\\])*"')
SINGLE_QUOTED_RE = re.compile(r"'(?:\\.|[^'\\])*'")
ASCII_LETTER_RE = re.compile(r"[A-Za-z]")
LOCALIZED_CHAR_RE = re.compile(r"[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]")
URL_RE = re.compile(r"^(?:https?|wss?)://", re.IGNORECASE)
EMAIL_RE = re.compile(r"^[^\s@]+@[^\s@]+\.[^\s@]+$")

HIGH_SINKS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("text-call", re.compile(r"\b(?:Text|BasicText|Button|Slock\w*Text)\s*\(")),
    ("arkui-text", re.compile(r"\b(?:Text|Button|Span)\s*\(|\.text\s*\(")),
    (
        "ui-property",
        re.compile(
            r"\b(?:text|title|subtitle|label|placeholder|contentDescription|description|"
            r"message|emptyText|errorText|buttonText|actionText|headerText)\s*[:=]"
        ),
    ),
    ("toast-dialog", re.compile(r"\b(?:showToast|Toast|AlertDialog|Dialog|Snackbar)\b")),
)

MEDIUM_SINK_RE = re.compile(
    r"\b(?:title|subtitle|label|placeholder|description|message|prompt|empty|error|action|"
    r"button|header|caption|hint|status)(?:Text|Label|Title|Message|Description)?\b",
    re.IGNORECASE,
)
UI_PATH_RE = re.compile(
    r"(?:Page|Screen|View|Widget|Activity|Component|Dialog|Panel|Card|Form|Settings|Auth)",
    re.IGNORECASE,
)

NON_UI_CONTEXT_RE = re.compile(
    r"\b(?:Log\.|console\.|println\(|print\(|recordLog|diagnostic|trace|metric|"
    r"SerialName|JsonNames?|Regex\(|routeName|pageName|eventName|headerName|userAgent|"
    r"contentType|mimeType|queryParam|apiPath)\b",
    re.IGNORECASE,
)
RESOURCE_REFERENCE_RE = re.compile(r"(?:MR\.strings|R\.string|\$r\(|app\.string\.)")

EXACT_TECHNICAL_LITERALS = {
    "API",
    "Android",
    "Bearer",
    "Claude Code",
    "DM",
    "GET",
    "GitHub",
    "HTML",
    "HTTP",
    "HTTPS",
    "ID",
    "JSON",
    "KMP",
    "Kuikly",
    "MCP",
    "Markdown",
    "OAuth",
    "OHOS",
    "POST",
    "Raft",
    "SDK",
    "Socket",
    "URL",
    "UUID",
    "WebSocket",
    "iOS",
}
INTERNAL_FIELD_LITERALS = {
    "body",
    "description",
    "false",
    "image",
    "message",
    "string",
    "text",
    "title",
    "true",
    "unknown",
    "yes",
}
DIRECT_SINKS = {"text-call", "arkui-text", "toast-dialog", "multiline-toast-dialog"}


def is_direct_sink(sink: str) -> bool:
    return sink in DIRECT_SINKS


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    column: int
    language: str
    confidence: str
    sink: str
    literal: str
    context: str
    catalog_keys: tuple[str, ...] = ()


@dataclass(frozen=True)
class ReviewedException:
    path: str
    literal: str
    reason: str


def decoded_literal(token: str) -> str:
    quote = token[0]
    body = token[1:-1]
    # Enough decoding for audit output without interpreting arbitrary source.
    return (
        body.replace(r"\n", "\n")
        .replace(r"\t", "\t")
        .replace(rf"\{quote}", quote)
        .replace(r"\\", "\\")
    )


def is_pure_english_candidate(value: str) -> bool:
    stripped = value.strip()
    if len(stripped) < 2 or not ASCII_LETTER_RE.search(stripped):
        return False
    if LOCALIZED_CHAR_RE.search(stripped):
        return False
    if stripped in EXACT_TECHNICAL_LITERALS or stripped in INTERNAL_FIELD_LITERALS:
        return False
    if URL_RE.search(stripped) or EMAIL_RE.fullmatch(stripped):
        return False
    if stripped.startswith(("app.string.", "sys.string.", "resources/")):
        return False
    # Paths, package/class names, JSON keys, and machine identifiers generally
    # contain separators but no whitespace. Keep normal single-word labels such
    # as "Loading" eligible.
    if not re.search(r"\s", stripped) and re.search(r"[/_.:$]", stripped):
        return False
    if re.fullmatch(r"[a-z][a-z0-9]*(?:[-_][a-z0-9]+)+", stripped):
        return False
    if re.fullmatch(r"[A-Fa-f0-9-]{16,}", stripped):
        return False
    return True


def source_language(path: Path) -> str:
    return "arkts" if path.suffix == ".ets" else "kotlin"


def literal_tokens(line: str, language: str) -> list[tuple[int, str]]:
    tokens = [(match.start(), match.group()) for match in DOUBLE_QUOTED_RE.finditer(line)]
    if language == "arkts":
        tokens.extend((match.start(), match.group()) for match in SINGLE_QUOTED_RE.finditer(line))
    return sorted(tokens)


def masked_source_line(line: str, language: str) -> str:
    masked = DOUBLE_QUOTED_RE.sub('""', line)
    if language == "arkts":
        masked = SINGLE_QUOTED_RE.sub("''", masked)
    return masked


def has_open_multiline_call(
    lines: list[str],
    index: int,
    language: str,
    pattern: re.Pattern[str],
) -> bool:
    start = max(0, index - 4)
    masked = [masked_source_line(item, language) for item in lines[start : index + 1]]
    for relative_index in range(len(masked) - 1, -1, -1):
        line = masked[relative_index]
        match = pattern.search(line)
        if match is None:
            continue
        balance = 0
        opened = False
        # Only scan through the line before the candidate. If the call closed
        # earlier, unrelated calls on the candidate line must not make it look
        # open (e.g. a Toast followed by a multi-line log call).
        for candidate_line in masked[relative_index:-1]:
            source = candidate_line[match.start() :] if not opened else candidate_line
            for char in source:
                if char == "(":
                    balance += 1
                    opened = True
                elif char == ")" and opened:
                    balance -= 1
                    if balance == 0:
                        return False
        return opened and balance > 0
    return False


def classify_context(path: Path, lines: list[str], index: int, language: str) -> tuple[str, str] | None:
    line = lines[index]
    masked_line = masked_source_line(line, language)
    window_lines = lines[max(0, index - 2) : min(len(lines), index + 3)]
    window = " ".join(window_lines)
    masked_window = " ".join(masked_source_line(item, language) for item in window_lines)
    if RESOURCE_REFERENCE_RE.search(window) or NON_UI_CONTEXT_RE.search(masked_line):
        return None
    for name, pattern in HIGH_SINKS:
        if pattern.search(masked_line):
            return "high", name
    toast_pattern = dict(HIGH_SINKS)["toast-dialog"]
    if has_open_multiline_call(lines, index, language, toast_pattern):
        return "high", "multiline-toast-dialog"
    for name, pattern in HIGH_SINKS:
        if pattern.search(masked_window):
            return "medium", f"near-{name}"
    if UI_PATH_RE.search(path.name) and MEDIUM_SINK_RE.search(masked_line):
        return "medium", "ui-file-property"
    return None


def scan_file(path: Path, repo_root: Path) -> list[Finding]:
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    language = source_language(path)
    findings: list[Finding] = []
    for index, line in enumerate(lines):
        stripped = line.lstrip()
        if stripped.startswith(("//", "/*", "*", "import ", "package ")):
            continue
        classification = classify_context(path, lines, index, language)
        if classification is None:
            continue
        confidence, sink = classification
        for column, token in literal_tokens(line, language):
            value = decoded_literal(token)
            if not is_pure_english_candidate(value):
                continue
            findings.append(
                Finding(
                    path=str(path.relative_to(repo_root)),
                    line=index + 1,
                    column=column + 1,
                    language=language,
                    confidence=confidence,
                    sink=sink,
                    literal=value,
                    context=line.strip(),
                )
            )
    return findings


def parse_allowlist(path: Path) -> dict[tuple[str, str], ReviewedException]:
    if not path.exists():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise ValueError(f"allowlist must be an array: {path}")
    result: dict[tuple[str, str], ReviewedException] = {}
    for index, item in enumerate(payload):
        if not isinstance(item, dict):
            raise ValueError(f"allowlist entry {index} must be an object: {path}")
        source_path = item.get("path")
        literal = item.get("literal")
        reason = item.get("reason")
        if not all(isinstance(value, str) and value.strip() for value in (source_path, literal, reason)):
            raise ValueError(f"allowlist entry {index} requires path, literal, and reason: {path}")
        entry = ReviewedException(source_path, literal, reason)
        key = (entry.path, entry.literal)
        if key in result:
            raise ValueError(f"duplicate allowlist entry: {entry.path} / {entry.literal}")
        result[key] = entry
    return result


def parse_mr_value_index(path: Path) -> dict[str, tuple[str, ...]]:
    if not path.exists():
        return {}
    values: dict[str, list[str]] = {}
    for element in ET.parse(path).getroot().findall("string"):
        key = element.attrib.get("name")
        if not key:
            continue
        value = "".join(element.itertext()).strip()
        values.setdefault(value, []).append(key)
    return {value: tuple(sorted(keys)) for value, keys in values.items()}


def markdown_report(findings: list[Finding], scanned_files: int, suppressed_count: int) -> str:
    lines = [
        "# Kotlin / ArkTS user-visible English literal scan",
        "",
        f"Scanned production files: **{scanned_files}** · findings: **{len(findings)}** · "
        f"reviewed exceptions: **{suppressed_count}**",
        "",
        "| confidence | source | location | literal | existing MR key(s) | sink | context |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for item in findings:
        values = [
            item.confidence,
            item.language,
            f"{item.path}:{item.line}",
            item.literal,
            ", ".join(item.catalog_keys),
            item.sink,
            item.context,
        ]
        escaped = [value.replace("|", "\\|").replace("\n", r"\n") for value in values]
        lines.append("| " + " | ".join(escaped) + " |")
    return "\n".join(lines) + "\n"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    parser.add_argument("--source-root", action="append", type=Path, dest="source_roots")
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--allowlist", type=Path, default=DEFAULT_ALLOWLIST)
    parser.add_argument("--mr-base", type=Path, default=DEFAULT_MR_BASE)
    parser.add_argument(
        "--direct-only",
        action="store_true",
        help="Only report direct Text/Button/toast/dialog sinks; omit property/model candidates.",
    )
    parser.add_argument(
        "--catalog-backed-only",
        action="store_true",
        help="Only report literals that exactly match an existing MR/base value.",
    )
    parser.add_argument("--fail-on-findings", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    repo_root = args.repo_root.resolve()
    roots = args.source_roots or list(DEFAULT_ROOTS)
    source_files: list[Path] = []
    for relative_root in roots:
        root = relative_root if relative_root.is_absolute() else repo_root / relative_root
        if not root.exists():
            continue
        source_files.extend(path for path in root.rglob("*") if path.suffix in {".kt", ".ets"})
    source_files = sorted(set(source_files))

    findings: list[Finding] = []
    for path in source_files:
        findings.extend(scan_file(path, repo_root))
    mr_base_path = args.mr_base if args.mr_base.is_absolute() else repo_root / args.mr_base
    mr_values = parse_mr_value_index(mr_base_path)
    findings = [replace(item, catalog_keys=mr_values.get(item.literal, ())) for item in findings]
    if args.direct_only:
        findings = [item for item in findings if is_direct_sink(item.sink)]
    if args.catalog_backed_only:
        findings = [item for item in findings if item.catalog_keys]
    allowlist_path = args.allowlist if args.allowlist.is_absolute() else repo_root / args.allowlist
    allowlist = parse_allowlist(allowlist_path)
    before_suppression = len(findings)
    findings = [item for item in findings if (item.path, item.literal) not in allowlist]
    suppressed_count = before_suppression - len(findings)
    findings.sort(key=lambda item: (item.confidence != "high", item.path, item.line, item.column))

    if args.format == "json":
        output = json.dumps(
            {
                "scannedFileCount": len(source_files),
                "findingCount": len(findings),
                "reviewedExceptionCount": suppressed_count,
                "findings": [asdict(item) for item in findings],
            },
            ensure_ascii=False,
            indent=2,
        ) + "\n"
    else:
        output = markdown_report(findings, len(source_files), suppressed_count)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)
    return 1 if args.fail_on_findings and findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
