#!/usr/bin/env python3
"""Audit production Kotlin for likely user-visible English literals.

This is a review scanner, not a compiler parser. It deliberately narrows the
search to production source roots and UI-shaped sinks (Text, Button, title,
label, placeholder, toast, dialog, widget text, etc.), then reports exact
file/line/context evidence. Machine strings, resource references, logging,
routes, URLs, protocol tokens, tests, and visual fixtures are excluded.

High confidence means the literal itself is what the sink shows: a direct
argument of a Text/Button/toast/dialog call (not of a call nested in it), or
the value of a UI property (`title = "…"`, `label: String = "…"`, and their
`?:` / `.ifBlank { "…" }` / `if … else` branches). Other literals on such a
line, such as JSON keys, status tokens and ids, are reported as medium.

The allowlist file records reviewed exclusions: source sets and files that
never ship or are developer-only (`excludeSourceSets`, `excludePaths`), and
single occurrences that are not visible (`occurrences`). Every entry needs a
reason.

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
    Path("compose/core/src/commonMain/kotlin"),
    Path("compose/core/src/androidMain/kotlin"),
    Path("compose/core/src/iosMain/kotlin"),
    Path("compose/ui-cmp/src/commonMain/kotlin"),
    Path("compose/ui-cmp/src/iosMain/kotlin"),
    Path("compose/shared-data/src/commonMain/kotlin"),
    Path("compose/shared-data/src/androidMain/kotlin"),
    Path("compose/shared-data/src/iosMain/kotlin"),
    Path("compose/shared-data/src/nativeMain/kotlin"),
    Path("compose/shared/src/commonMain/kotlin"),
    Path("compose/shared/src/androidMain/kotlin"),
    Path("compose/shared/src/iosMain/kotlin"),
    Path("compose/shared/src/jsMain/kotlin"),
    Path("compose/shared/src/nativeMain/kotlin"),
    Path("compose/androidApp/src/main/java"),
)
DEFAULT_ALLOWLIST = Path("scripts/i18n_code_literal_allowlist.json")
DEFAULT_MR_BASE = Path("compose/shared-data/src/commonMain/composeResources/values/strings.xml")

ASCII_LETTER_RE = re.compile(r"[A-Za-z]")
LOCALIZED_CHAR_RE = re.compile(r"[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]")
URL_RE = re.compile(r"^(?:https?|wss?)://", re.IGNORECASE)
EMAIL_RE = re.compile(r"^[^\s@]+@[^\s@]+\.[^\s@]+$")

# Compose-style calls only: `Foo.Text(` is a model constructor (ActionCardFormValue.Text, ...).
TEXT_CALL_RE = re.compile(r"(?<![.\w])(?:Text|BasicText|Button|Raft\w*Text)\s*\(")
UI_PROPERTY_RE = re.compile(
    r"\b(?:text|title|subtitle|label|placeholder|contentDescription|description|"
    r"message|emptyText|errorText|buttonText|actionText|headerText)\s*[:=]"
)
TOAST_DIALOG_RE = re.compile(r"\b(?:Toast|AlertDialog|Dialog|Snackbar)\b|\.(?:toast|errorToast)\s*\(")
# The call whose arguments a toast/dialog literal must be in (AlertDialog.Builder(host) takes none).
TOAST_DIALOG_CALL_RE = re.compile(
    r"(?:\bToast\.makeText|(?<![.\w])(?:AlertDialog|Dialog|Snackbar)|\.(?:toast|errorToast))\s*\("
)

HIGH_SINKS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("text-call", TEXT_CALL_RE),
    ("ui-property", UI_PROPERTY_RE),
    ("toast-dialog", TOAST_DIALOG_RE),
)
DIRECT_CALL_SINKS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("text-call", TEXT_CALL_RE),
    ("toast-dialog", TOAST_DIALOG_CALL_RE),
)
# Previous lines searched for a still-open direct call or an enclosing log/annotation call.
MULTILINE_LOOKBACK = 4

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
    r"\b(?:Log\.|console\.|recordLog|diagnostic|trace|metric|"
    r"SerialName|JsonNames?|routeName|pageName|eventName|headerName|userAgent|"
    r"contentType|mimeType|queryParam|apiPath)\b",
    re.IGNORECASE,
)
# A literal inside the parentheses of one of these calls is never shown: log lines,
# regex patterns and annotation arguments (@RequiresOptIn(message = …), @Deprecated(…)).
NON_UI_CALL_RE = re.compile(
    r"@[\w.]+"
    r"|(?:[\w.]*\.)?(?:raftLog\w*|raftLogger|recordLog|recordCriticalLog|logOnce|diagnosticsLogger|"
    r"logDebug|logInfo|logWarn|logError|println|print|NSLog|Regex)"
    r"|(?:[\w.]*\.)?(?:RaftAppLog|Log)\.\w+"
    r"|[\w.]*\.log"
)
# Machine-shaped literals: an id such as `otterTuck`, or a log line that starts
# with a snake_case event (`play_services_check code=$code`) or a key=value pair.
LOWER_CAMEL_ID_RE = re.compile(r"[a-z][a-z0-9]*(?:[A-Z][A-Za-z0-9]*)+")
LOG_LINE_RE = re.compile(r"[a-z][a-z0-9]*(?:_[a-z0-9]+)+(?:\s|$)|[A-Za-z_][\w.]*=\S")
ESCAPE_RE = re.compile(r"\\(u[0-9A-Fa-f]{4}|.)")
ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "\\": "\\", '"': '"', "'": "'"}
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
DIRECT_SINKS = {
    "text-call",
    "toast-dialog",
    "multiline-text-call",
    "multiline-toast-dialog",
}


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
    """Reviewed occurrences of one literal in one file: `count` identical ones, all not visible."""

    path: str
    literal: str
    reason: str
    context: str | None = None
    count: int = 1


@dataclass(frozen=True)
class PathExclusion:
    """A file (`path`) or source set (`source_set`, the directory under `src/`) the scan skips."""

    reason: str
    path: str | None = None
    source_set: str | None = None


@dataclass(frozen=True)
class Allowlist:
    exclusions: tuple[PathExclusion, ...]
    occurrences: dict[tuple[str, str, str | None], ReviewedException]


def decoded_literal(token: str) -> str:
    body = token[1:-1]

    # Enough decoding for audit output without interpreting arbitrary source. `\$` stays
    # escaped so visible_literal_text() does not mistake it for a template.
    def decode(match: re.Match[str]) -> str:
        escape = match.group(1)
        if escape.startswith("u") and len(escape) == 5:
            return chr(int(escape[1:], 16))
        return ESCAPES.get(escape, match.group(0))

    return ESCAPE_RE.sub(decode, body)


def visible_literal_text(value: str) -> str:
    """Remove Kotlin template expressions while retaining visible copy."""
    result: list[str] = []
    index = 0
    while index < len(value):
        if value[index] != "$" or index + 1 >= len(value):
            result.append(value[index])
            index += 1
            continue
        if value[index + 1] == "{":
            depth = 1
            index += 2
            while index < len(value) and depth > 0:
                if value[index] == "{":
                    depth += 1
                elif value[index] == "}":
                    depth -= 1
                index += 1
            continue
        identifier = re.match(r"[A-Za-z_][A-Za-z0-9_]*", value[index + 1 :])
        if identifier is not None:
            index += 1 + len(identifier.group())
            continue
        result.append(value[index])
        index += 1
    return "".join(result)


def is_pure_english_candidate(value: str) -> bool:
    stripped = value.strip()
    visible = visible_literal_text(stripped).strip()
    if len(visible) < 2 or not ASCII_LETTER_RE.search(visible):
        return False
    if LOCALIZED_CHAR_RE.search(visible):
        return False
    if stripped in EXACT_TECHNICAL_LITERALS or stripped in INTERNAL_FIELD_LITERALS:
        return False
    if URL_RE.search(stripped) or EMAIL_RE.fullmatch(stripped):
        return False
    if stripped.startswith("resources/"):
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
    if LOWER_CAMEL_ID_RE.fullmatch(stripped) or LOG_LINE_RE.match(stripped):
        return False
    return True


def source_language(path: Path) -> str:
    return "kotlin"


def parse_literal_at(
    line: str,
    start: int,
    delimiter: str,
    language: str,
) -> tuple[int, list[tuple[int, str]]]:
    nested: list[tuple[int, str]] = []
    index = start + 1
    while index < len(line):
        if line[index] == "\\":
            index += 2
            continue
        if line.startswith("${", index):
            depth = 1
            index += 2
            while index < len(line) and depth > 0:
                if line[index] == '"':
                    end, inner = parse_literal_at(line, index, line[index], language)
                    nested.append((index, line[index:end]))
                    nested.extend(inner)
                    index = end
                    continue
                if line[index] == "{":
                    depth += 1
                elif line[index] == "}":
                    depth -= 1
                index += 1
            continue
        if line[index] == delimiter:
            return index + 1, nested
        index += 1
    return len(line), nested


def literal_tokens(line: str, language: str) -> list[tuple[int, str]]:
    delimiters = {'"'}
    tokens: list[tuple[int, str]] = []
    index = 0
    while index < len(line):
        if line[index] not in delimiters:
            index += 1
            continue
        end, nested = parse_literal_at(line, index, line[index], language)
        tokens.append((index, line[index:end]))
        tokens.extend(nested)
        index = end
    return sorted(tokens)


def is_machine_control_token(line: str, column: int, token: str) -> bool:
    """Reject branch/comparison keys nested inside an otherwise direct UI call."""
    before = line[:column]
    after = line[column + len(token) :]
    return bool(
        re.match(r"\s*->", after)
        or re.search(r"(?:==|!=)\s*$", before)
        or re.search(r"\.equals\s*\(\s*$", before)
    )


CHAR_LITERAL_RE = re.compile(r"'(?:\\u[0-9A-Fa-f]{4}|\\.|[^'\\])'")
OPENERS = "([{"
CLOSERS = ")]}"


@dataclass(frozen=True)
class SourceLine:
    text: str
    # Literals, char literals, comments and raw strings blanked (same width): what is left is code.
    code: str
    # Column where a // comment starts (len(text) when there is none).
    code_end: int
    # Spans inside a multi-line raw string or block comment: a quote there does not start a literal.
    dead: tuple[tuple[int, int], ...]


def source_lines(lines: list[str], language: str) -> list[SourceLine]:
    """Blank everything that is not code, carrying block comments and raw strings across lines."""
    result: list[SourceLine] = []
    in_block = False
    in_raw = False
    for line in lines:
        characters = list(line)
        dead: list[tuple[int, int]] = []
        code_end = len(line)
        index = 0

        def blank(start: int, end: int, is_dead: bool) -> None:
            characters[start:end] = " " * (end - start)
            if is_dead:
                dead.append((start, end))

        while index < len(line):
            if in_block or in_raw:
                close = line.find("*/" if in_block else '"""', index)
                end = len(line) if close < 0 else close + (2 if in_block else 3)
                blank(index, end, True)
                if close >= 0:
                    in_block = in_raw = False
                index = end
                continue
            if line.startswith("//", index):
                blank(index, len(line), False)
                code_end = index
                break
            if line.startswith("/*", index) or line.startswith('"""', index):
                is_block = line.startswith("/*", index)
                close = line.find("*/" if is_block else '"""', index + (2 if is_block else 3))
                if close < 0:
                    in_block, in_raw = is_block, not is_block
                    blank(index, len(line), True)
                    break
                end = close + (2 if is_block else 3)
                # A one-line raw string stays tokenizable, as before; a block comment never is.
                blank(index, end, is_block)
                index = end
                continue
            char = line[index]
            if char == '"':
                end, _ = parse_literal_at(line, index, char, language)
                blank(index, end, False)
                index = end
                continue
            if char == "'":
                match = CHAR_LITERAL_RE.match(line, index)
                if match is not None:
                    blank(index, match.end(), False)
                    index = match.end()
                    continue
            index += 1
        result.append(SourceLine(line, "".join(characters), code_end, tuple(dead)))
    return result


def call_name_before(code: str, paren: int) -> tuple[str, str]:
    """The dotted name written before a "(" (e.g. `bridge.toast`, `@RequiresOptIn`) and the char before it."""
    index = paren - 1
    while index >= 0 and code[index].isspace():
        index -= 1
    end = index + 1
    while index >= 0 and (code[index].isalnum() or code[index] in "_.@"):
        index -= 1
    previous = code[end - 1] if end > 0 else ""
    if previous == ">" and end > 1 and code[end - 2] == "-":
        previous = "->"
    return code[index + 1 : end], previous


def opens_call(code: str, paren: int) -> bool:
    """True for the "(" of a call or condition, false for a grouping paren such as `= (a + b)`."""
    name, previous = call_name_before(code, paren)
    if name in ("else", "return"):
        return False
    return bool(name) or previous in (")", "]", ">")


@dataclass(frozen=True)
class Group:
    """An open (, [ or { and, for a "(", the call it belongs to."""

    kind: str
    line: int
    name: str = ""
    call: bool = False
    sink: str | None = None


def default_value_start(code: str, index: int) -> int | None:
    """After `name:`, skip the type; return where the value after its `=` starts, if there is one."""
    depth = 0
    for position in range(index, len(code)):
        char = code[position]
        if char in "([<":
            depth += 1
        elif char == ">" and code[position - 1] != "-":
            depth -= 1
        elif char in ")]":
            if depth == 0:
                return None
            depth -= 1
        elif char == "{" or (char in ",;" and depth == 0):
            return None
        elif char == "=" and depth == 0:
            return None if code.startswith("=", position + 1) else position + 1
    return None


def value_end(code: str, start: int) -> int:
    """End of the argument/assignment value starting at `start`: the next top-level , or ; or closer."""
    depth = 0
    for position in range(start, len(code)):
        char = code[position]
        if char in OPENERS:
            depth += 1
        elif char in CLOSERS:
            if depth == 0:
                return position
            depth -= 1
        elif char in ",;" and depth == 0:
            return position
    return len(code)


def ui_property_values(code: str) -> list[tuple[int, int]]:
    """Spans of the values the UI property names on this line govern (`title = …`, `label: String = …`)."""
    spans: list[tuple[int, int]] = []
    for match in UI_PROPERTY_RE.finditer(code):
        operator = match.end() - 1
        if code.startswith(code[operator], operator + 1):
            continue  # `title == x` or `Foo::title`
        start = operator + 1 if code[operator] == "=" else default_value_start(code, operator + 1)
        if start is not None:
            spans.append((start, value_end(code, start)))
    return spans


def value_nesting(code: str, start: int, column: int) -> int:
    """Calls and index brackets between a value's start and a literal. Lambdas, `when`/`ifBlank`
    blocks and grouping parens do not count, so `x ?: "A"`, `if (c) "A" else "B"` and
    `.ifBlank { "A" }` are the value itself while `opt("label")` and `props["label"]` are not."""
    stack: list[bool] = []
    for position in range(start, column):
        char = code[position]
        if char == "(":
            stack.append(opens_call(code, position))
        elif char == "[":
            stack.append(True)
        elif char == "{":
            stack.append(False)
        elif char in CLOSERS and stack:
            stack.pop()
    return sum(stack)


class KotlinSource:
    def __init__(self, path: Path, lines: list[str], language: str) -> None:
        self.path = path
        self.language = language
        self.lines = source_lines(lines, language)
        self._events: dict[int, list[tuple[int, str, Group | None]]] = {}
        self._ui_values: dict[int, list[tuple[int, int]]] = {}

    def tokens(self, index: int) -> list[tuple[int, str]]:
        line = self.lines[index]
        text = line.text
        for start, end in line.dead:
            text = text[:start] + " " * (end - start) + text[end:]
        return literal_tokens(text, self.language)

    def events(self, index: int) -> list[tuple[int, str, Group | None]]:
        """Brackets on a line, with the Group each opener pushes."""
        cached = self._events.get(index)
        if cached is not None:
            return cached
        code = self.lines[index].code
        sinks: dict[int, str] = {}
        for name, pattern in DIRECT_CALL_SINKS:
            for match in pattern.finditer(code):
                sinks.setdefault(match.end() - 1, name)
        events: list[tuple[int, str, Group | None]] = []
        for column, char in enumerate(code):
            if char == "(":
                name, _ = call_name_before(code, column)
                events.append((column, char, Group("(", index, name, opens_call(code, column), sinks.get(column))))
            elif char in "[{":
                events.append((column, char, Group(char, index)))
            elif char in CLOSERS:
                events.append((column, char, None))
        self._events[index] = events
        return events

    def open_groups(self, index: int, column: int) -> list[Group]:
        """Brackets still open at a literal, looking back MULTILINE_LOOKBACK lines."""
        stack: list[Group] = []
        for line_index in range(max(0, index - MULTILINE_LOOKBACK), index + 1):
            for event_column, char, group in self.events(line_index):
                if line_index == index and event_column >= column:
                    break
                if group is not None:
                    stack.append(group)
                elif stack:
                    stack.pop()
        return stack

    def is_non_ui_token(self, index: int, column: int, groups: list[Group]) -> bool:
        """Exclude a comment, a log/regex/annotation argument, or a non-UI expression on this line."""
        line = self.lines[index]
        if column >= line.code_end:
            return True
        if any(group.kind == "(" and group.name and NON_UI_CALL_RE.fullmatch(group.name) for group in groups):
            return True
        expression_start = line.code.rfind(";", 0, column) + 1
        expression_end = line.code.find(";", column, line.code_end)
        if expression_end < 0:
            expression_end = line.code_end
        return NON_UI_CONTEXT_RE.search(line.code[expression_start:expression_end]) is not None

    def classify(self, index: int, column: int, groups: list[Group]) -> tuple[str, str] | None:
        code = self.lines[index].code
        # High: the literal is what the sink shows. A direct argument of the innermost open
        # Text/Button/toast/dialog call; lambdas and grouping parens in between are transparent,
        # a nested call or index (testTag("…"), opt("…"), props["…"]) is not.
        open_sink: Group | None = None
        for position in range(len(groups) - 1, -1, -1):
            group = groups[position]
            if group.sink is None:
                continue
            open_sink = group
            inner = groups[position + 1 :]
            if all(item.kind == "{" or (item.kind == "(" and not item.call) for item in inner):
                return "high", group.sink if group.line == index else f"multiline-{group.sink}"
            break
        # High: the value of a UI property on this line, not any other literal on it.
        values = self._ui_values.get(index)
        if values is None:
            values = self._ui_values[index] = ui_property_values(code)
        for start, end in values:
            if start <= column < end and value_nesting(code, start, column) == 0:
                return "high", "ui-property"
        # Medium: next to a sink without being its text (JSON keys, ids, status tokens, tags).
        if open_sink is not None:
            return "medium", f"near-{open_sink.sink}"
        for name, pattern in HIGH_SINKS:
            if pattern.search(code):
                return "medium", f"near-{name}"
        window = " ".join(line.code for line in self.lines[max(0, index - 2) : index + 3])
        for name, pattern in HIGH_SINKS:
            if pattern.search(window):
                return "medium", f"near-{name}"
        if UI_PATH_RE.search(self.path.name) and MEDIUM_SINK_RE.search(code):
            return "medium", "ui-file-property"
        return None


def scan_file(path: Path, repo_root: Path) -> list[Finding]:
    text = path.read_text(encoding="utf-8", errors="replace")
    language = source_language(path)
    source = KotlinSource(path, text.splitlines(), language)
    findings: list[Finding] = []
    for index, line in enumerate(source.lines):
        stripped = line.text.lstrip()
        if stripped.startswith(("//", "/*", "*", "import ", "package ")):
            continue
        for column, token in source.tokens(index):
            if is_machine_control_token(line.text, column, token):
                continue
            value = decoded_literal(token)
            if not is_pure_english_candidate(value):
                continue
            groups = source.open_groups(index, column)
            if source.is_non_ui_token(index, column, groups):
                continue
            classification = source.classify(index, column, groups)
            if classification is None:
                continue
            confidence, sink = classification
            findings.append(
                Finding(
                    path=str(path.relative_to(repo_root)),
                    line=index + 1,
                    column=column + 1,
                    language=language,
                    confidence=confidence,
                    sink=sink,
                    literal=value,
                    context=line.text.strip(),
                )
            )
    return findings


OCCURRENCE_KEYS = {"path", "literal", "reason", "context", "count"}


def _nonblank(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def parse_occurrences(items: object, path: Path) -> dict[tuple[str, str, str | None], ReviewedException]:
    if not isinstance(items, list):
        raise ValueError(f"allowlist occurrences must be an array: {path}")
    result: dict[tuple[str, str, str | None], ReviewedException] = {}
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            raise ValueError(f"allowlist entry {index} must be an object: {path}")
        unknown = set(item) - OCCURRENCE_KEYS
        if unknown:
            raise ValueError(f"allowlist entry {index} has unknown keys {sorted(unknown)}: {path}")
        source_path = item.get("path")
        literal = item.get("literal")
        reason = item.get("reason")
        context = item.get("context")
        count = item.get("count", 1)
        if not all(_nonblank(value) for value in (source_path, literal, reason)):
            raise ValueError(f"allowlist entry {index} requires path, literal, and reason: {path}")
        if context is not None and not _nonblank(context):
            raise ValueError(f"allowlist entry {index} context must be a nonblank string: {path}")
        if isinstance(count, bool) or not isinstance(count, int) or count < 1:
            raise ValueError(f"allowlist entry {index} count must be a positive integer: {path}")
        entry = ReviewedException(source_path, literal, reason, context, count)
        key = (entry.path, entry.literal, entry.context)
        if key in result:
            raise ValueError(f"duplicate allowlist entry: {entry.path} / {entry.literal} / {entry.context}")
        result[key] = entry
    return result


def parse_exclusions(payload: dict[str, object], path: Path) -> tuple[PathExclusion, ...]:
    exclusions: list[PathExclusion] = []
    seen: set[tuple[str | None, str | None]] = set()
    for field, key in (("excludeSourceSets", "sourceSet"), ("excludePaths", "path")):
        items = payload.get(field, [])
        if not isinstance(items, list):
            raise ValueError(f"allowlist {field} must be an array: {path}")
        for index, item in enumerate(items):
            if not isinstance(item, dict) or set(item) != {key, "reason"}:
                raise ValueError(f"allowlist {field} entry {index} needs exactly {key} and reason: {path}")
            name, reason = item[key], item["reason"]
            if not _nonblank(name) or not _nonblank(reason):
                raise ValueError(f"allowlist {field} entry {index} needs a nonblank {key} and reason: {path}")
            exclusion = (
                PathExclusion(reason, source_set=name) if key == "sourceSet" else PathExclusion(reason, path=name)
            )
            identity = (exclusion.path, exclusion.source_set)
            if identity in seen:
                raise ValueError(f"duplicate allowlist {field} entry: {name}")
            seen.add(identity)
            exclusions.append(exclusion)
    return tuple(exclusions)


def load_allowlist(path: Path) -> Allowlist:
    """Read the reviewed exclusions and occurrences (an object; a bare array is occurrences only)."""
    if not path.exists():
        return Allowlist((), {})
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, list):
        return Allowlist((), parse_occurrences(payload, path))
    if not isinstance(payload, dict):
        raise ValueError(f"allowlist must be an object or an array: {path}")
    unknown = set(payload) - {"excludeSourceSets", "excludePaths", "occurrences"}
    if unknown:
        raise ValueError(f"allowlist has unknown keys {sorted(unknown)}: {path}")
    return Allowlist(parse_exclusions(payload, path), parse_occurrences(payload.get("occurrences", []), path))


def parse_allowlist(path: Path) -> dict[tuple[str, str, str | None], ReviewedException]:
    return load_allowlist(path).occurrences


def excluded_by(relative_path: str, exclusions: tuple[PathExclusion, ...]) -> PathExclusion | None:
    """The exclusion covering a repo-relative path: the exact file, or its source set (dir under src/)."""
    parts = relative_path.split("/")
    for exclusion in exclusions:
        if exclusion.path is not None and exclusion.path == relative_path:
            return exclusion
        if exclusion.source_set is not None and any(
            parts[index] == "src" and parts[index + 1] == exclusion.source_set for index in range(len(parts) - 1)
        ):
            return exclusion
    return None


def stale_exclusions(exclusions: tuple[PathExclusion, ...], repo_root: Path) -> list[PathExclusion]:
    """Excluded files that no longer exist (moved or deleted); update or remove the entry."""
    return [item for item in exclusions if item.path is not None and not (repo_root / item.path).is_file()]


def _matches(entry: ReviewedException, finding: Finding) -> bool:
    return (
        finding.path == entry.path
        and finding.literal == entry.literal
        and (entry.context is None or finding.context == entry.context)
    )


def resolve_allowlist_occurrences(
    findings: list[Finding],
    allowlist: dict[tuple[str, str, str | None], ReviewedException],
) -> set[tuple[str, int, int, str]]:
    """Occurrences the reviewed entries cover. An entry covers exactly `count` occurrences, wherever
    they are in the file: one more or one fewer fails, so new copy is never hidden by an old review."""
    resolved: set[tuple[str, int, int, str]] = set()
    for entry in allowlist.values():
        matches = [finding for finding in findings if _matches(entry, finding)]
        if not matches:
            # The reviewed code changed or was removed; stale_allowlist_entries reports it.
            continue
        if len(matches) != entry.count:
            lines = ", ".join(str(finding.line) for finding in matches)
            raise ValueError(
                f"allowlist entry must resolve to exactly {entry.count} occurrence(s): "
                f"{entry.path} / {entry.literal} / {entry.context} (matches={len(matches)}, lines {lines})"
            )
        resolved.update((finding.path, finding.line, finding.column, finding.literal) for finding in matches)
    return resolved


def stale_allowlist_entries(
    findings: list[Finding],
    allowlist: dict[tuple[str, str, str | None], ReviewedException],
) -> list[ReviewedException]:
    """Reviewed exceptions that no longer match any occurrence; remove them from the allowlist."""
    stale = [entry for entry in allowlist.values() if not any(_matches(entry, finding) for finding in findings)]
    return sorted(stale, key=lambda entry: (entry.path, entry.literal, entry.context or ""))


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


def markdown_report(
    findings: list[Finding],
    scanned_files: int,
    suppressed_count: int,
    stale: list[ReviewedException] | None = None,
    excluded_files: int = 0,
    stale_excluded: list[PathExclusion] | None = None,
) -> str:
    high = sum(1 for item in findings if item.confidence == "high")
    lines = [
        "# Kotlin user-visible English literal scan",
        "",
        f"Scanned production files: **{scanned_files}** (excluded: **{excluded_files}**) · "
        f"findings: **{len(findings)}** (high: **{high}**, medium: **{len(findings) - high}**) · "
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
    stale = stale or []
    stale_excluded = stale_excluded or []
    if stale or stale_excluded:
        lines += [
            "",
            f"## Stale allowlist entries: {len(stale) + len(stale_excluded)}",
            "",
            "These reviewed exceptions match nothing any more; remove them from the allowlist.",
            "",
        ]
        lines += [f"- `{entry.path}` / `{entry.literal}`" + (f" / `{entry.context}`" if entry.context else "") for entry in stale]
        lines += [f"- excludePaths `{entry.path}` (no such file)" for entry in stale_excluded]
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
    parser.add_argument(
        "--fail-on-stale",
        action="store_true",
        help="Exit 1 when an allowlist entry no longer matches any occurrence (or names a missing file).",
    )
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
        source_files.extend(path for path in root.rglob("*") if path.suffix == ".kt")
    allowlist_path = args.allowlist if args.allowlist.is_absolute() else repo_root / args.allowlist
    allowlist = load_allowlist(allowlist_path)
    candidates = sorted(set(source_files))
    source_files = [
        path for path in candidates if excluded_by(path.relative_to(repo_root).as_posix(), allowlist.exclusions) is None
    ]
    excluded_count = len(candidates) - len(source_files)

    findings: list[Finding] = []
    for path in source_files:
        findings.extend(scan_file(path, repo_root))
    mr_base_path = args.mr_base if args.mr_base.is_absolute() else repo_root / args.mr_base
    mr_values = parse_mr_value_index(mr_base_path)
    findings = [replace(item, catalog_keys=mr_values.get(item.literal, ())) for item in findings]
    reviewed_occurrences = resolve_allowlist_occurrences(findings, allowlist.occurrences)
    stale = stale_allowlist_entries(findings, allowlist.occurrences)
    stale_excluded = stale_exclusions(allowlist.exclusions, repo_root)
    if args.direct_only:
        findings = [item for item in findings if is_direct_sink(item.sink)]
    if args.catalog_backed_only:
        findings = [item for item in findings if item.catalog_keys]
    before_suppression = len(findings)
    findings = [
        item
        for item in findings
        if (item.path, item.line, item.column, item.literal) not in reviewed_occurrences
    ]
    suppressed_count = before_suppression - len(findings)
    findings.sort(key=lambda item: (item.confidence != "high", item.path, item.line, item.column))

    if args.format == "json":
        output = json.dumps(
            {
                "scannedFileCount": len(source_files),
                "excludedFileCount": excluded_count,
                "findingCount": len(findings),
                "reviewedExceptionCount": suppressed_count,
                "staleAllowlistEntries": [asdict(entry) for entry in stale],
                "staleExcludePaths": [entry.path for entry in stale_excluded],
                "findings": [asdict(item) for item in findings],
            },
            ensure_ascii=False,
            indent=2,
        ) + "\n"
    else:
        output = markdown_report(findings, len(source_files), suppressed_count, stale, excluded_count, stale_excluded)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)
    if args.fail_on_findings and findings:
        return 1
    return 1 if args.fail_on_stale and (stale or stale_excluded) else 0


if __name__ == "__main__":
    raise SystemExit(main())
