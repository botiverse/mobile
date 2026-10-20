#!/usr/bin/env python3
"""Report translucent semantic colors used as interactive action fills.

Translucent status/badge backgrounds are valid. Translucent fills passed to
buttons are risky in Kuikly's brutal UI because they can composite with the
black offset shadow or underlying media and read as an unrelated dark color.

The audit is report-only by default. Use --strict when a caller wants findings
to fail the command, and --json for patrol/CI ingestion.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path


ACTION_CALLS = (
    "Button",
    "RaftBrutalActionButton",
    "RaftBrutalIconButton",
    "RaftModalActionButton",
    "RaftUiButton",
)
SEMANTIC_TOKENS = (
    "AccentRed",
    "AccentOrange",
    "AccentPink",
    "AccentCyan",
    "AccentLime",
    "BrandYellow",
)
TOKEN_PATTERN = "|".join(SEMANTIC_TOKENS)
TRANSLUCENT_ASSIGNMENT_RE = re.compile(
    rf"(?P<property>backgroundColor|buttonColor|activeBackgroundColor|color)\s*=\s*"
    rf"RaftColor\.(?P<token>{TOKEN_PATTERN})\.copy\(\s*alpha\s*=\s*(?P<alpha>0(?:\.\d+)?)f?\s*\)"
)
TRANSLUCENT_BACKGROUND_RE = re.compile(
    rf"\.background\(\s*RaftColor\.(?P<token>{TOKEN_PATTERN})\.copy\("
    rf"\s*alpha\s*=\s*(?P<alpha>0(?:\.\d+)?)f?\s*\)"
)


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    kind: str
    token: str
    alpha: float
    excerpt: str


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def balanced_call_end(text: str, open_paren: int) -> int:
    depth = 0
    in_string = False
    escaped = False
    for index in range(open_paren, len(text)):
        char = text[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return index + 1
    return len(text)


def scan_text(path: str, text: str) -> list[Finding]:
    findings: list[Finding] = []

    for call_name in ACTION_CALLS:
        for call in re.finditer(rf"\b{re.escape(call_name)}\s*\(", text):
            open_paren = text.find("(", call.start())
            end = balanced_call_end(text, open_paren)
            block = text[call.start():end]
            for match in TRANSLUCENT_ASSIGNMENT_RE.finditer(block):
                absolute = call.start() + match.start()
                findings.append(
                    Finding(
                        path=path,
                        line=line_number(text, absolute),
                        kind=f"{call_name}.{match.group('property')}",
                        token=match.group("token"),
                        alpha=float(match.group("alpha")),
                        excerpt=" ".join(match.group(0).split()),
                    )
                )
            for match in TRANSLUCENT_BACKGROUND_RE.finditer(block):
                absolute = call.start() + match.start()
                findings.append(
                    Finding(
                        path=path,
                        line=line_number(text, absolute),
                        kind=f"{call_name}.modifier.background",
                        token=match.group("token"),
                        alpha=float(match.group("alpha")),
                        excerpt=" ".join(match.group(0).split()),
                    )
                )

    return findings


def scan_root(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for path in sorted(root.rglob("*.kt")):
        findings.extend(scan_text(str(path), path.read_text(encoding="utf-8")))
    return findings


def self_test() -> None:
    source = """
RaftBrutalIconButton(
    iconName = icon,
    onClick = {},
    backgroundColor = RaftColor.AccentRed.copy(alpha = 0.18f),
)
Box(Modifier.background(RaftColor.AccentOrange.copy(alpha = 0.2f)))
"""
    findings = scan_text("Fixture.kt", source)
    assert len(findings) == 1, findings
    assert findings[0].kind == "RaftBrutalIconButton.backgroundColor"
    assert findings[0].token == "AccentRed"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        default="compose/shared/src/commonMain/kotlin/build/raft/app",
        help="Kotlin source root to scan",
    )
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON")
    parser.add_argument("--strict", action="store_true", help="Exit 1 when findings exist")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("audit_translucent_action_tokens self-test: PASS")
        return 0

    findings = scan_root(Path(args.root))
    if args.json:
        print(json.dumps({"findings": [asdict(item) for item in findings]}, indent=2))
    elif findings:
        print(f"Found {len(findings)} translucent interactive action token use(s):")
        for item in findings:
            print(
                f"{item.path}:{item.line}: {item.kind} uses "
                f"RaftColor.{item.token} alpha={item.alpha:g} :: {item.excerpt}"
            )
    else:
        print("No translucent semantic action fills found.")
    return 1 if args.strict and findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
