#!/usr/bin/env python3
"""Generate the mobile size tokens (lengths in dp) from the Web client's CSS sources.

Reads the same pinned sources as gen_tokens.py (manifest.json): Tailwind theme.css, raft-ui
styles.css + foundation.css and the Web index.css. Every custom property whose value resolves to
a length is resolved in three contexts, as the Web applies them:

  compact  a phone (below 48rem)
  wide     48rem and up, by touch (a tablet; raft-ui's `wideTouch`,
           `@media (width >= 48rem) and (pointer: coarse)`)
  fine     48rem and up with a mouse or trackpad (`@media (width >= 48rem) and (pointer: fine)`)

Only declarations on the root (`@theme`, `:root`, `:where(:root)`, `:host`, `.light`) count; the
dark mode, the per-theme blocks and `prefers-color-scheme` copies are colour-only for lengths.
`var()`, `calc()` (+ - * /), `px`, `rem` (16px) and Tailwind's `--spacing(n)` are resolved;
anything else (percentages, viewport units, keywords) is skipped. Writes
compose/designsystem/.../tokens/RaftUiDimensionTokens.g.kt.

  python3 tools/design-tokens/gen_dimensions.py          # regenerate
  python3 tools/design-tokens/gen_dimensions.py --check  # CI: the Kotlin file is current
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
OUT = ROOT / "compose/designsystem/src/commonMain/kotlin/build/raft/designsystem/tokens/RaftUiDimensionTokens.g.kt"
ROOT_SELECTORS = {"@theme", ":root", ":where(:root)", ":host", ".light"}
CONTEXTS = ("compact", "wide", "fine")
REM = 16.0


def strip_comments(css):
    return re.sub(r"/\*.*?\*/", "", css, flags=re.S)


def blocks(css):
    """Yield (prelude stack, declarations) for every block, outermost first."""
    stack, i, start, out = [], 0, 0, []
    decls = [[]]
    while i < len(css):
        c = css[i]
        if c == "{":
            stack.append(" ".join(css[start:i].split()))
            decls.append([])
            start = i + 1
        elif c == "}":
            text = css[start:i]
            decls[-1].extend(d for d in text.split(";") if ":" in d)
            out.append((tuple(stack), decls.pop()))
            stack.pop()
            start = i + 1
        elif c == ";":
            decls[-1].append(css[start:i])
            start = i + 1
        i += 1
    return out


def context_of(stack):
    """The contexts a block applies to, or None when it is not a root length block."""
    media = [p for p in stack if p.startswith("@media")]
    rules = [p for p in stack if not p.startswith("@media") and not p.startswith("@layer")]
    if not rules:
        return None
    selectors = {"@theme" if s.strip().startswith("@theme") else s.strip() for s in rules[-1].split(",")}
    if not selectors & ROOT_SELECTORS:
        return None
    m = " ".join(media)
    if "prefers-color-scheme" in m or "data-theme" in rules[-1]:
        return None
    if not m:
        return CONTEXTS
    wide = re.search(r"width\s*>=\s*48rem", m)
    fine = "pointer: fine" in m or "pointer:fine" in m
    coarse = "pointer: coarse" in m or "pointer:coarse" in m
    if wide and fine:
        return ("fine",)
    if wide and coarse:
        # Wide touch (raft-ui 0.103.4, raft-design #1416): 48rem and up by touch, a tablet.
        return ("wide",)
    if wide:
        return ("wide", "fine")
    if fine:
        # A mouse at any width (raft-ui 0.102.23): of the three contexts only `fine` has one;
        # the desktop block after it still wins there.
        return ("fine",)
    return None


def collect(paths, raw=None):
    raw = raw or {c: {} for c in CONTEXTS}
    for path in paths:
        for stack, decls in blocks(strip_comments(path.read_text())):
            ctx = context_of(stack)
            if not ctx:
                continue
            for d in decls:
                name, _, value = d.partition(":")
                name, value = name.strip(), value.strip()
                if not name.startswith("--"):
                    continue
                for c in ctx:
                    raw[c][name] = value
    return raw


class Unresolved(Exception):
    pass


def resolve(name, table, seen=()):
    if name in seen or name not in table:
        raise Unresolved(name)
    return evaluate(table[name], table, seen + (name,))


def evaluate(expr, table, seen):
    expr = expr.strip()
    expr = re.sub(r"--spacing\(([^)]*)\)", lambda m: f"({m.group(1)} * var(--spacing))", expr)
    if expr.startswith("calc(") and expr.endswith(")"):
        expr = expr[5:-1]
    def var(m):
        inner = m.group(1)
        name, _, fallback = inner.partition(",")
        try:
            return f"({resolve(name.strip(), table, seen)})"
        except Unresolved:
            if fallback.strip():
                return f"({evaluate(fallback, table, seen)})"
            raise
    while "var(" in expr:
        new = re.sub(r"var\(([^()]*(?:\([^()]*\)[^()]*)*)\)", var, expr, count=1)
        if new == expr:
            raise Unresolved(expr)
        expr = new
    expr = expr.replace("calc(", "(")
    expr = re.sub(r"(-?\d*\.?\d+)px\b", r"\1", expr)
    expr = re.sub(r"(-?\d*\.?\d+)rem\b", lambda m: str(float(m.group(1)) * REM), expr)
    if not re.fullmatch(r"[\d.\s+\-*/()]+", expr):
        raise Unresolved(expr)
    value = eval(expr, {"__builtins__": {}})
    return round(float(value), 4)


def camel(name):
    parts = name[2:].replace("--", "-").split("-")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def compute():
    """Every root length the sources declare, resolved: ({context: {name: px}}, names, sources)."""
    manifest = json.loads((HERE / "manifest.json").read_text())
    s = manifest["sources"]
    # Tailwind's theme resolves references (`--spacing`); only raft-ui's and the Web's names are emitted.
    tailwind = collect([HERE / s["tailwind"]["path"]])
    emitted = set()
    raw = {c: dict(tailwind[c]) for c in CONTEXTS}
    for k in ("raftUiStyles", "raftUiFoundation", "web"):
        part = collect([HERE / s[k]["path"]])
        for c in CONTEXTS:
            raw[c].update(part[c])
            emitted |= set(part[c])
    emitted.add("--spacing")
    values = {c: {} for c in CONTEXTS}
    for c in CONTEXTS:
        for name in raw[c]:
            try:
                values[c][name] = resolve(name, raw[c])
            except (Unresolved, SyntaxError, ZeroDivisionError):
                pass
    # Lengths only: a unitless number (a weight, a ratio) never came from px/rem/spacing.
    def is_length(name):
        v = raw["compact"].get(name) or raw["fine"].get(name) or ""
        return bool(re.search(r"px|rem|--spacing|var\(", v)) and not re.search(r"\d(%|vh|vw|s\b|ms\b|deg)", v)
    names = sorted(n for n in emitted if n in values["compact"] and is_length(n))
    names = [n for n in names if all(n in values[c] for c in CONTEXTS)]
    return values, names, s


def generate():
    values, names, s = compute()
    origins = "\n".join(f"//   {s[k]['origin']} sha256={s[k]['sha256'][:16]}" for k in ("tailwind", "raftUiStyles", "raftUiFoundation", "web"))
    def block(c):
        return "\n".join(f"        val {camel(n)}: Dp = {fmt(values[c][n])}.dp // {n}" for n in names)
    def fmt(v):
        return str(int(v)) if v == int(v) else repr(v)
    kt = f"""// GENERATED by tools/design-tokens/gen_dimensions.py — do not edit.
// Sources (tools/design-tokens/manifest.json):
{origins}
package build.raft.designsystem.tokens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Web design system's lengths (raft-ui and the Web's root custom properties), in dp, in the
 * three contexts the Web applies: [Compact] a phone, [Wide] 48rem and up by touch, [Fine] 48rem
 * and up with a mouse or trackpad. Names drop the leading `--` and are camel-cased.
 */
object RaftUiDimensionTokens {{
    sealed class Values {{
{chr(10).join(f"        abstract val {camel(n)}: Dp" for n in names)}
    }}

    data object Compact : Values() {{
{block("compact").replace("        val ", "        override val ")}
    }}

    data object Wide : Values() {{
{block("wide").replace("        val ", "        override val ")}
    }}

    data object Fine : Values() {{
{block("fine").replace("        val ", "        override val ")}
    }}
}}
"""
    return kt, len(names)


def main():
    kt, n = generate()
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text() != kt:
            sys.exit(f"{OUT.relative_to(ROOT)} is stale: run python3 tools/design-tokens/gen_dimensions.py")
        print(f"dimension tokens up to date ({n} lengths x 3 contexts)")
        return
    OUT.write_text(kt)
    print(f"wrote {OUT.relative_to(ROOT)} ({n} lengths x 3 contexts)")


if __name__ == "__main__":
    main()
