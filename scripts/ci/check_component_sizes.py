#!/usr/bin/env python3
"""The app reads raft-ui's component sizes from the tokens, not as numbers (#proj-mobile:#318).

Component tokens are sizes the Web and the apps both draw (raft-design
design/foundations/tokens.md#component-tokens): control and row heights, heads, bars, the list
toolbar, the tab bar. The app has them generated as `RaftUiComponentTokens`
(tools/design-tokens/gen_component_tokens.py) and reads them through `RaftTheme.sizes` /
`RaftTheme.controls`. The same two rules as raft-ui's own check (raft-design #1393,
packages/ui/src/size-tokens.test.ts), over the app's and the design system's main Kotlin:

- A hand-written copy of a token's value is gone. A size named after a token
  (`val OptionHeight = 36.dp`, `listToolbarHeight = 49.dp`) whose value is one of that token's
  values reads the token instead; so do the known copies in HAND_COPIES.
- A line that reads a component token picks no value of its own by the pointer or the window
  (`if (pointerInput == RaftPointerInput.Fine) ...`, `RaftScale.Wide`): the token already switches
  between touch, a mouse and a desktop.

- A page head in compose/shared (a size whose name says it belongs to a head: `Head`, `Header`,
  `TopBar`, `TitleBar`, `AppBar`) reads its head tokens even when its name is the app's own: a
  head height that is a `header-height` / `header-height-short` value reads
  `RaftTheme.controls.headHeight` (the token for the context), a head button size that is a
  `bar-button-size` value `RaftTheme.controls.barButtonSize`, a head icon that is a
  `control-icon-size` value `RaftTheme.controls.barIconSize` (#wg-mobile-sync:#268). The design
  system already reads them through `RaftTheme.controls`.

Either one is allowed on a line right under (or ending in) an `// app-only: <reason>` comment,
which says why the app needs its own value, or `// not a token: <reason>` for a size that only
shares a token's name and value (the Web writes it as a number too).

Exit codes: 0 = clean; 1 = violations; 2 = usage error.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TOKENS = "tools/design-tokens/manifest.json"
ROOTS = (
    "compose/designsystem/src/commonMain/kotlin",
    "compose/shared/src/commonMain/kotlin",
)
APP_ONLY = re.compile(r"//\s*(app-only|not a token):\s*\S")

# A token's value written by hand where the name doesn't say which token it is, and the token.
HAND_COPIES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"RaftTheme\.controls\.rowHeight\s*\?:\s*RaftMenuMetrics\.OptionHeight"), "option.height (RaftTheme.sizes.optionHeight)"),
    (re.compile(r"RaftTheme\.controls\.rowHeight\s*\?:\s*32\.dp"), "control-height-small (RaftTheme.sizes.controlHeightSmall)"),
    (re.compile(r"\b44\.dp\s+else\s+49\.dp\b|\b49\.dp\s+else\s+44\.dp\b"), "list-toolbar.height (RaftTheme.sizes.listToolbarHeight)"),
    (re.compile(r"\bContentHeight\s*:\s*Dp\s*=\s*52\.dp"), "mobile-nav.height"),
    (re.compile(r"\bMinBottomPadding\s*:\s*Dp\s*=\s*8\.dp"), "mobile-nav.inset-bottom-min"),
]

APP_ROOT = "compose/shared/src/commonMain/kotlin"
HEAD_NAME = re.compile(r"(Head|Header|TopBar|TitleBar|AppBar)(?!ing)")
# (what the name ends in, the tokens whose values it may not copy, what to read instead)
HEAD_PARTS: list[tuple[re.Pattern[str], tuple[str, ...], str]] = [
    (re.compile(r"Height$"), ("headerHeight", "headerHeightShort"), "RaftTheme.controls.headHeight"),
    (re.compile(r"(Action|Button)(Size|Width)$"), ("barButtonSize",), "RaftTheme.controls.barButtonSize"),
    (re.compile(r"(Action|Button)?IconSize$"), ("controlIconSize",), "RaftTheme.controls.barIconSize"),
]

PICKS_BY_CONTEXT = re.compile(r"RaftPointerInput\.(Fine|Touch)|RaftScale\.(Wide|Compact)")
READS_TOKEN = re.compile(r"RaftTheme\.sizes\.|RaftUiComponentTokens\.(?!of\()")
DP_DECL = re.compile(r"\b(?:val\s+)?([A-Za-z_]\w*)\s*(?::\s*Dp\s*)?=\s*(-?\d+(?:\.\d+)?)\.dp\b(?!\s*[-+*/])")


def load_tokens(root: Path) -> dict[str, set[float]]:
    """{camelName: values in any context} for every dimension token."""
    manifest = json.loads((root / TOKENS).read_text())
    resolved = json.loads((root / "tools/design-tokens" / manifest["sources"]["raftUiResolvedTokens"]["path"]).read_text())
    out: dict[str, set[float]] = {}

    def walk(node: dict, path: tuple[str, ...]) -> None:
        for key, value in node.items():
            if key.startswith("$") or not isinstance(value, dict):
                continue
            if "$value" in value:
                if value.get("$type") == "dimension":
                    parts = "-".join(path + (key,)).split("-")
                    name = parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])
                    out[name] = {float(v) for v in value["$value"].values()}
            else:
                walk(value, path + (key,))

    walk(resolved, ())
    return out


def _code(line: str) -> str:
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', line.split("//")[0])


def violations(rel: str, text: str, tokens: dict[str, set[float]]) -> list[str]:
    problems: list[str] = []
    lines = text.split("\n")
    in_doc = False
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("/*"):
            in_doc = True
        if in_doc:
            if "*/" in stripped:
                in_doc = False
            continue
        if stripped.startswith("//"):
            continue
        allowed = bool(APP_ONLY.search(line)) or (i > 0 and APP_ONLY.search(lines[i - 1]) is not None)
        if allowed:
            continue
        code = _code(line)
        for pattern, use in HAND_COPIES:
            if pattern.search(code):
                problems.append(f"{rel}:{i + 1}: a token's value by hand; read {use}")
        for match in DP_DECL.finditer(code):
            name, value = match.group(1), float(match.group(2))
            lowered = name[:1].lower() + name[1:]
            named = False
            for token, values in tokens.items():
                if (lowered == token or lowered.endswith(token[:1].upper() + token[1:])) and value in values and value != 0:
                    named = True
                    problems.append(f"{rel}:{i + 1}: `{name}` = {match.group(2)}.dp copies the `{token}` token; read it (RaftTheme.sizes.{token})")
            if not named and rel.startswith(APP_ROOT) and HEAD_NAME.search(name):
                for ending, head_tokens, read in HEAD_PARTS:
                    if not ending.search(name):
                        continue
                    hit = [token for token in head_tokens if value in tokens.get(token, set())]
                    if hit and value != 0:
                        problems.append(
                            f"{rel}:{i + 1}: page head size `{name}` = {match.group(2)}.dp is a `{hit[0]}` value; read {read} "
                            "(or say why: `// app-only: <reason>` / `// not a token: <reason>`)"
                        )
                    break
        if READS_TOKEN.search(code) and PICKS_BY_CONTEXT.search(code):
            problems.append(f"{rel}:{i + 1}: reads a component token and picks by the pointer or the window too; the token already switches (or say why: `// app-only: <reason>`)")
    return problems


def run(root: Path) -> list[str]:
    tokens = load_tokens(root)
    problems: list[str] = []
    for base in ROOTS:
        directory = root / base
        if not directory.is_dir():
            problems.append(f"{base}: missing source root")
            continue
        for path in sorted(directory.rglob("*.kt")):
            if path.name.endswith(".g.kt"):
                continue
            rel = str(path.relative_to(root))
            problems += violations(rel, path.read_text(encoding="utf-8"), tokens)
    return problems


def self_test() -> None:
    tokens = {"optionHeight": {36.0, 34.0}, "listToolbarHeight": {49.0, 44.0}, "controlHeight": {44.0, 34.0}}
    assert violations("a.kt", "    val OptionHeight = 36.dp", tokens), "a copy named after its token"
    assert violations("a.kt", "    val menuOptionHeight: Dp = 34.dp", tokens), "a name ending in the token's"
    assert not violations("a.kt", "    val OptionHeight = 40.dp", tokens), "not a token value"
    assert not violations("a.kt", "    val OptionHeight = RaftTheme.sizes.optionHeight", tokens)
    assert not violations("a.kt", "    // app-only: the sheet's own row\n    val OptionHeight = 36.dp", tokens), "app-only"
    assert not violations("a.kt", "    val OptionHeight = 36.dp // app-only: a picker's row", tokens), "app-only, same line"
    assert not violations("a.kt", "    /**\n     * val OptionHeight = 36.dp\n     */", tokens), "a doc comment"
    assert violations("a.kt", "fun h(s: RaftScale, p: RaftPointerInput) = if (s == RaftScale.Wide && p == RaftPointerInput.Fine) 44.dp else 49.dp", tokens), "a hand copy"
    assert violations("a.kt", "    val h = if (pointer == RaftPointerInput.Fine) 30.dp else RaftTheme.sizes.listToolbarHeight", tokens), "a breakpoint of its own"
    assert not violations("a.kt", "    val h = RaftTheme.sizes.listToolbarHeight", tokens)
    assert not violations("a.kt", '    val label = "OptionHeight = 36.dp"', tokens), "a string"
    assert not violations("a.kt", "    // not a token: web `stack-y-6`\n    val ListSectionGap = 34.dp", {"sectionGap": {34.0}}), "not a token"
    assert not violations("a.kt", "    RaftUiComponentTokens.of(wide = scale == RaftScale.Wide, fine = true)", tokens), "the context pick itself"
    heads = {"headerHeight": {44.0, 56.0}, "headerHeightShort": {40.0, 48.0}, "barButtonSize": {28.0, 44.0}, "controlIconSize": {18.0, 22.0}}
    app = APP_ROOT + "/x/Page.kt"
    assert violations(app, "private val TaskHeadHeight = 48.dp", heads), "a page head's height"
    assert violations(app, "private val PageHeaderMinHeight: Dp = 56.dp", heads), "a page head's min height"
    assert violations(app, "private val ThreadHeaderActionSize = 44.dp", heads), "a head button"
    assert violations(app, "private val PageHeaderIconSize = 22.dp", heads), "a head icon"
    assert not violations(app, "private val TaskHeadHeight = 52.dp", heads), "not a head value"
    assert not violations(app, "private val SectionHeadingHeight = 48.dp", heads), "a heading is not a head"
    assert not violations(app, "private val TaskHeadHeight = RaftTheme.controls.headHeight", heads)
    assert not violations(app, "// not a token: web `min-h-10`\nprivate val CommentsHeaderMinHeight = 40.dp", heads), "not a token"
    assert not violations("compose/designsystem/src/commonMain/kotlin/a.kt", "private val TaskHeadHeight = 48.dp", heads), "app code only"
    print("check_component_sizes self-test: ok")


def main(argv: list[str]) -> int:
    if argv[1:] == ["--self-test"]:
        self_test()
        return 0
    if argv[1:]:
        print("usage: check_component_sizes.py [--self-test]", file=sys.stderr)
        return 2
    problems = run(REPO_ROOT)
    if problems:
        print("Component sizes written by hand (scripts/ci/check_component_sizes.py):")
        for p in problems:
            print(f"  {p}")
        return 1
    print("component sizes: every token-owned size reads its token")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
