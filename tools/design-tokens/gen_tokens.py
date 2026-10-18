#!/usr/bin/env python3
"""Generate the mobile colour tokens from the Web client's CSS sources.

Sources (pinned in manifest.json, vendored under source/):
  - tailwindcss theme.css         @theme palette (gray, zinc, ...)
  - raft-ui styles.css            @theme aliases (--color-brutal-yellow, ...)
  - raft-ui foundation.css        unlayered theme variables (raft light / raft dark)
  - raft web index.css            product @theme overrides (brand hexes) and :root.dark

Cascade, matching how the Web build resolves these files:
  1. Every @theme block compiles into Tailwind's `theme` cascade layer. Blocks
     merge in import order (tailwind, raft-ui styles.css, web index.css) and
     the last declaration of a name wins.
  2. foundation.css and web `:root.dark` are unlayered, so they beat any
     @theme declaration of the same name. Inside them, the more specific
     theme selector wins (:where(:root) < .light / .dark < web :root.dark).

Colour maths follows CSS Color 4: oklch -> OKLab -> LMS -> XYZ-D65 -> linear
sRGB (unclamped); color-mix(in srgb-linear) interpolates premultiplied in
linear light. Chrome clips each channel to [0, 1] when painting (no CSS
chroma-reduction gamut map), and so does this generator.
`--oracle` compares every token against values painted by Chromium
(capture_chrome_oracle.mjs).

Usage:
  gen_tokens.py            regenerate the Kotlin file
  gen_tokens.py --check    fail if sources do not match manifest.json or the
                           Kotlin file is stale, or the oracle disagrees
  gen_tokens.py --self-test
  gen_tokens.py --dump-json  print resolved tokens (used by the oracle script)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
MANIFEST = HERE / "manifest.json"
ORACLE = HERE / "chrome-oracle.json"
OUTPUT = (
    REPO
    / "compose/shared/src/commonMain/kotlin/build/raft/app/designtokens/RaftUiColorTokens.g.kt"
)

THEMES = ("raftLight", "raftDark")
BASE_THEME = THEMES[0]


class TokenError(Exception):
    pass


# ── CSS parsing ──────────────────────────────────────────────────────────────


def strip_comments(css: str) -> str:
    return re.sub(r"/\*.*?\*/", "", css, flags=re.S)


def top_level_blocks(css: str) -> list[tuple[str, str]]:
    """Return (prelude, body) for every top-level `{...}` block."""
    blocks = []
    i, n = 0, len(css)
    start = 0
    while i < n:
        ch = css[i]
        if ch == ";":
            start = i + 1
        elif ch == "{":
            depth, j = 1, i + 1
            while j < n and depth:
                if css[j] == "{":
                    depth += 1
                elif css[j] == "}":
                    depth -= 1
                j += 1
            if depth:
                raise TokenError("unbalanced braces")
            blocks.append((css[start:i].strip(), css[i + 1 : j - 1]))
            i = j
            start = j
            continue
        i += 1
    return blocks


DECL_RE = re.compile(r"(--[A-Za-z0-9_-]+)\s*:\s*([^;{}]*?)\s*(?:;|$)", re.S)


def declarations(body: str) -> list[tuple[str, str]]:
    # Only direct declarations: drop nested blocks first.
    flat = []
    depth = 0
    for ch in body:
        if ch == "{":
            depth += 1
            flat.append(";")
        elif ch == "}":
            depth -= 1
        elif depth == 0:
            flat.append(ch)
    return [(m.group(1), " ".join(m.group(2).split())) for m in DECL_RE.finditer("".join(flat))]


# Selector -> {theme: rank inside the unlayered tier}. raft-ui 0.6.0 has one
# "raft" family: the root element carries `.light` or `.dark` (System resolves
# to one of them), so `.light` / `.dark` match at class specificity and beat
# the zero-specificity `:where(:root)` defaults. Equal ranks resolve by source
# order (the later declaration wins), as in CSS.
FOUNDATION_SCOPES = {
    ':where(:root)': {"raftLight": 0, "raftDark": 0},
    ':where(:root), .light': {"raftLight": 1, "raftDark": 0},
    '.dark': {"raftDark": 1},
    ':where(:root), .light, .dark': {"raftLight": 1, "raftDark": 1},
}
WEB_UNLAYERED_SCOPES = {":root.dark": {"raftDark": 2}}


def _norm_selector(prelude: str) -> str:
    return " ".join(prelude.replace("\n", " ").split())


def collect(sources: dict[str, str]) -> dict[str, dict[str, str]]:
    """Resolve the cascade to raw declared values per theme."""
    themed_layer: dict[str, str] = {}
    for key in ("tailwind", "raftUiStyles", "web"):
        for prelude, body in top_level_blocks(strip_comments(sources[key])):
            if re.fullmatch(r"@theme(\s+(default|inline|static|reference))*", _norm_selector(prelude)):
                for name, value in declarations(body):
                    themed_layer[name] = value

    per_theme = {t: dict(themed_layer) for t in THEMES}
    rank: dict[tuple[str, str], int] = {}

    def apply(scopes, css, label):
        for prelude, body in top_level_blocks(strip_comments(css)):
            sel = _norm_selector(prelude)
            if sel.startswith("@"):
                continue  # @media mirrors, @layer, @utility...: not token sources
            if sel not in scopes:
                continue
            for name, value in declarations(body):
                for t, r in scopes[sel].items():
                    if rank.get((t, name), -1) <= r:
                        per_theme[t][name] = value
                        rank[(t, name)] = r

    apply(FOUNDATION_SCOPES, sources["raftUiFoundation"], "foundation")
    apply(WEB_UNLAYERED_SCOPES, sources["web"], "web")
    return per_theme


def themed_names(sources: dict[str, str]) -> set[str]:
    """Names declared by a theme-specific selector: these vary per theme."""
    names = set()
    for css, scopes in ((sources["raftUiFoundation"], FOUNDATION_SCOPES), (sources["web"], WEB_UNLAYERED_SCOPES)):
        for prelude, body in top_level_blocks(strip_comments(css)):
            sel = _norm_selector(prelude)
            if sel in scopes and max(scopes[sel].values()) > 0 and len(scopes[sel]) < len(THEMES):
                names.update(n for n, _ in declarations(body))
    return names


# ── Colour maths ─────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Rgba:
    """Unclamped linear-light sRGB plus alpha."""

    r: float
    g: float
    b: float
    a: float

    def paint(self) -> tuple[int, int, int, int]:
        def ch(x):
            return round(min(1.0, max(0.0, srgb_encode(x))) * 255)

        return ch(self.r), ch(self.g), ch(self.b), round(min(1.0, max(0.0, self.a)) * 255)


def srgb_encode(x: float) -> float:
    s = -1.0 if x < 0 else 1.0
    x = abs(x)
    return s * (12.92 * x if x <= 0.0031308 else 1.055 * x ** (1 / 2.4) - 0.055)


def srgb_decode(x: float) -> float:
    s = -1.0 if x < 0 else 1.0
    x = abs(x)
    return s * (x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4)


def _mul(m, v):
    return tuple(sum(m[i][k] * v[k] for k in range(3)) for i in range(3))


OKLAB_TO_LMS = (
    (1.0, 0.3963377773761749, 0.2158037573099136),
    (1.0, -0.1055613458156586, -0.0638541728258133),
    (1.0, -0.0894841775298119, -1.2914855480194092),
)
LMS_TO_XYZ = (
    (1.2268798758459243, -0.5578149944602171, 0.2813910456659647),
    (-0.0405757452148008, 1.1122868032803170, -0.0717110580655164),
    (-0.0763729366746601, -0.4214933324022432, 1.5869240198367816),
)
XYZ_TO_LIN_SRGB = (
    (3.2409699419045226, -1.5373831775700940, -0.4986107602930034),
    (-0.9692436362808796, 1.8759675015077202, 0.0415550574071756),
    (0.0556300796969936, -0.2039769588889765, 1.0569715142428786),
)


def oklch_to_linear(l: float, c: float, h: float) -> tuple[float, float, float]:
    hr = math.radians(h)
    lms = _mul(OKLAB_TO_LMS, (l, c * math.cos(hr), c * math.sin(hr)))
    xyz = _mul(LMS_TO_XYZ, tuple(v**3 for v in lms))
    return _mul(XYZ_TO_LIN_SRGB, xyz)


def _num(tok: str, percent_scale: float = 1.0) -> float:
    if tok.endswith("%"):
        return float(tok[:-1]) / 100 * percent_scale
    return float(tok)


def _split_args(inner: str) -> list[str]:
    parts, depth, cur = [], 0, []
    for ch in inner:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur).strip())
    return parts


def _func(value: str) -> tuple[str, str] | None:
    m = re.fullmatch(r"([a-z-]+)\((.*)\)", value.strip(), re.S)
    return (m.group(1), m.group(2)) if m else None


COLOR_FUNCS = {"oklch", "rgb", "rgba", "color-mix", "var"}


class Resolver:
    def __init__(self, values: dict[str, str]):
        self.values = values
        self.cache: dict[str, Rgba | None] = {}

    def token(self, name: str, stack=()) -> Rgba | None:
        if name in stack:
            raise TokenError(f"var() cycle: {' -> '.join(stack + (name,))}")
        if name not in self.cache:
            if name not in self.values:
                return None
            self.cache[name] = self.color(self.values[name], stack + (name,))
        return self.cache[name]

    def color(self, value: str, stack=()) -> Rgba | None:
        """Parse a colour; None when the value is not a colour (a length, a shadow...)."""
        v = value.strip()
        if v.startswith("#"):
            return _hex(v)
        if v == "transparent":
            return Rgba(0, 0, 0, 0)
        f = _func(v)
        if f is None:
            if re.fullmatch(r"[a-z]+", v) and v not in ("inherit", "initial", "unset", "none"):
                raise TokenError(f"named colour not supported: {v!r}")
            return None
        fn, inner = f
        if fn not in COLOR_FUNCS:
            return None
        if fn == "var":
            args = _split_args(inner)
            ref = self.token(args[0].strip(), stack)
            if ref is None and len(args) > 1:
                return self.color(",".join(args[1:]), stack)
            if ref is None and args[0].strip() not in self.values:
                raise TokenError(f"undefined var {args[0].strip()} (in {stack[-1] if stack else '?'})")
            return ref
        if fn == "oklch":
            return _oklch(inner)
        if fn in ("rgb", "rgba"):
            return _rgb(inner)
        return self._mix(inner, stack)

    def _mix(self, inner: str, stack) -> Rgba:
        args = _split_args(inner)
        if len(args) != 3:
            raise TokenError(f"color-mix arity: {inner}")
        space = args[0].strip()
        if space not in ("in srgb-linear", "in srgb"):
            raise TokenError(f"color-mix space not supported: {space}")
        items = []
        for arg in args[1:]:
            m = re.fullmatch(r"(.*?)\s+(-?[\d.]+)%", arg.strip(), re.S)
            col, pct = (m.group(1), float(m.group(2)) / 100) if m else (arg.strip(), None)
            c = self.color(col, stack)
            if c is None:
                raise TokenError(f"color-mix operand is not a colour: {col}")
            items.append((c, pct))
        (c1, p1), (c2, p2) = items
        if p1 is None and p2 is None:
            p1 = p2 = 0.5
        elif p1 is None:
            p1 = 1 - p2
        elif p2 is None:
            p2 = 1 - p1
        total = p1 + p2
        if total <= 0:
            raise TokenError("color-mix percentages sum to zero")
        alpha_mult = min(total, 1.0)
        p1, p2 = p1 / total, p2 / total
        return mix(c1, p1, c2, p2, alpha_mult, linear=(space == "in srgb-linear"))


def mix(c1: Rgba, p1: float, c2: Rgba, p2: float, alpha_mult: float = 1.0, linear: bool = True) -> Rgba:
    def chans(c):
        return (c.r, c.g, c.b) if linear else tuple(srgb_encode(x) for x in (c.r, c.g, c.b))

    a = c1.a * p1 + c2.a * p2
    if a == 0:
        return Rgba(0, 0, 0, 0)
    out = [(x1 * c1.a * p1 + x2 * c2.a * p2) / a for x1, x2 in zip(chans(c1), chans(c2))]
    if not linear:
        out = [srgb_decode(x) for x in out]
    return Rgba(*out, a * alpha_mult)


def _alpha(tok: str | None) -> float:
    return 1.0 if tok is None else _num(tok)


def _oklch(inner: str) -> Rgba:
    main, _, alpha = inner.partition("/")
    parts = main.split()
    if len(parts) != 3:
        raise TokenError(f"oklch arity: {inner}")
    l = _num(parts[0])
    c = _num(parts[1], 0.4)
    h = 0.0 if parts[2] == "none" else float(parts[2].removesuffix("deg"))
    return Rgba(*oklch_to_linear(l, c, h), _alpha(alpha.strip() or None))


def _rgb(inner: str) -> Rgba:
    if "," in inner:
        parts = [p.strip() for p in inner.split(",")]
        rgb, alpha = parts[:3], (parts[3] if len(parts) > 3 else None)
    else:
        main, _, a = inner.partition("/")
        rgb, alpha = main.split(), (a.strip() or None)
    vals = [_num(p, 255.0) / 255 for p in rgb]
    return Rgba(*(srgb_decode(x) for x in vals), _alpha(alpha))


def _hex(v: str) -> Rgba:
    h = v[1:]
    if len(h) in (3, 4):
        h = "".join(ch * 2 for ch in h)
    if len(h) not in (6, 8):
        raise TokenError(f"bad hex colour {v}")
    vals = [int(h[i : i + 2], 16) / 255 for i in range(0, len(h), 2)]
    a = vals[3] if len(vals) == 4 else 1.0
    return Rgba(*(srgb_decode(x) for x in vals[:3]), a)


# ── Token model ──────────────────────────────────────────────────────────────


def kotlin_name(css_name: str) -> str:
    base = css_name.removeprefix("--").removeprefix("color-")
    parts = base.split("-")
    name = parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])
    return name if not name[:1].isdigit() else "c" + name


def resolve_all(sources: dict[str, str]) -> tuple[dict[str, dict[str, tuple[int, int, int, int]]], set[str]]:
    per_theme = collect(sources)
    out: dict[str, dict[str, tuple[int, int, int, int]]] = {}
    for theme, values in per_theme.items():
        r = Resolver(values)
        colours = {}
        for name in sorted(values):
            c = r.token(name)
            if c is not None:
                colours[name] = c.paint()
        out[theme] = colours
    names = set().union(*(set(v) for v in out.values()))
    # Themed = declared under a theme selector, or derived (var/color-mix) from one.
    themed = {n for n in themed_names(sources) if n in names}
    themed |= {n for n in names if len({out[t].get(n) for t in THEMES}) > 1}
    # A `--color-x` alias that duplicates a bare `--x` token adds nothing.
    for n in list(names):
        if n.startswith("--color-") and ("--" + n.removeprefix("--color-")) in names:
            bare = "--" + n.removeprefix("--color-")
            if all(out[t].get(n) == out[t].get(bare) for t in THEMES):
                for t in THEMES:
                    out[t].pop(n, None)
            else:
                raise TokenError(f"{n} and {bare} differ but map to the same Kotlin name")
    return out, themed


def load_sources() -> dict[str, str]:
    manifest = json.loads(MANIFEST.read_text())
    return {key: (HERE / entry["path"]).read_text() for key, entry in manifest["sources"].items()}


def verify_manifest() -> list[str]:
    manifest = json.loads(MANIFEST.read_text())
    errors = []
    for key, entry in manifest["sources"].items():
        digest = hashlib.sha256((HERE / entry["path"]).read_bytes()).hexdigest()
        if digest != entry["sha256"]:
            errors.append(f"{entry['path']}: sha256 {digest} != manifest {entry['sha256']}")
    return errors


def argb(rgba) -> str:
    r, g, b, a = rgba
    return f"0x{a:02X}{r:02X}{g:02X}{b:02X}"


def render(tokens, themed) -> str:
    manifest = json.loads(MANIFEST.read_text())
    shared = sorted(n for n in tokens[BASE_THEME] if n not in themed and all(n in tokens[t] for t in THEMES))
    for n in shared:
        if len({tokens[t][n] for t in THEMES}) != 1:
            raise TokenError(f"{n} is declared theme-agnostic but differs across themes")
    theme_only = sorted(n for n in themed if all(n in tokens[t] for t in THEMES))
    names = [kotlin_name(n) for n in shared + theme_only]
    dupes = {n for n in names if names.count(n) > 1}
    if dupes:
        raise TokenError(f"Kotlin name collision: {sorted(dupes)}")

    lines = [
        "// GENERATED by tools/design-tokens/gen_tokens.py — DO NOT EDIT.",
        "// Regenerate: python3 tools/design-tokens/gen_tokens.py",
        "// Sources (tools/design-tokens/manifest.json):",
    ]
    for entry in manifest["sources"].values():
        lines.append(f"//   {entry['origin']} sha256={entry['sha256'][:16]}")
    lines += [
        "",
        "package build.raft.app.designtokens",
        "",
        "import com.tencent.kuikly.compose.ui.graphics.Color",
        "",
        "/** Theme-invariant Web colours: raw ramps, brand colours and the Tailwind palette. */",
        "internal object RaftUiPalette {",
    ]
    for n in shared:
        lines.append(f"    /** `{n}` */")
        lines.append(f"    val {kotlin_name(n)} = Color({argb(tokens[BASE_THEME][n])})")
    lines += [
        "}",
        "",
        "/**",
        " * Colours that change with the Web theme (raft light / raft dark).",
        " * One object per theme; an abstract class (not a constructor) so the token",
        " * count is not bounded by the JVM's 255-slot parameter limit.",
        " */",
        "internal sealed class RaftUiThemeColors {",
    ]
    for n in theme_only:
        lines.append(f"    /** `{n}` */")
        lines.append(f"    abstract val {kotlin_name(n)}: Color")
    lines.append("")
    for theme in THEMES:
        obj = theme[0].upper() + theme[1:]
        lines.append(f"    data object {obj} : RaftUiThemeColors() {{")
        for n in theme_only:
            lines.append(f"        override val {kotlin_name(n)} = Color({argb(tokens[theme][n])})")
        lines.append("    }")
        if theme != THEMES[-1]:
            lines.append("")
    lines.append("}")
    return "\n".join(lines)


# ── Oracle ───────────────────────────────────────────────────────────────────


def over(rgba, bg) -> tuple[int, int, int]:
    """Composite an 8-bit painted colour over an opaque 8-bit background (gamma space, as Chrome does)."""
    r, g, b, a = rgba
    return tuple(round((c * a + k * (255 - a)) / 255) for c, k in zip((r, g, b), bg))


def check_oracle(tokens) -> list[str]:
    if not ORACLE.exists():
        return [f"{ORACLE.name} missing: run capture_chrome_oracle.mjs"]
    oracle = json.loads(ORACLE.read_text())
    errors = []
    for theme in THEMES:
        got = oracle["themes"].get(theme, {})
        missing = sorted(set(tokens[theme]) - set(got))
        if missing:
            errors.append(f"{theme}: oracle lacks {len(missing)} tokens, e.g. {missing[:3]}")
        for name, rgba in tokens[theme].items():
            if name not in got:
                continue
            for bg_key, bg in (("onWhite", (255, 255, 255)), ("onBlack", (0, 0, 0))):
                mine = over(rgba, bg)
                theirs = tuple(got[name][bg_key])
                if any(abs(x - y) > 1 for x, y in zip(mine, theirs)):
                    errors.append(f"{theme} {name} {bg_key}: generated {mine} != chrome {theirs}")
    return errors


def browser_css(sources: dict[str, str]) -> str:
    """The same sources laid out for Chrome to resolve: @theme bodies in the
    `theme` layer, foundation.css and web `:root.dark` unlayered. Chrome then
    does selector matching, var() resolution, colour maths and painting."""
    out = ["@layer theme;"]
    for key in ("tailwind", "raftUiStyles", "web"):
        for prelude, body in top_level_blocks(strip_comments(sources[key])):
            if re.fullmatch(r"@theme(\s+(default|inline|static|reference))*", _norm_selector(prelude)):
                decls = "".join(f"{n}: {v};" for n, v in declarations(body))
                out.append(f"@layer theme {{ :root {{ {decls} }} }}")
    out.append(strip_comments(sources["raftUiFoundation"]))
    for prelude, body in top_level_blocks(strip_comments(sources["web"])):
        if _norm_selector(prelude) in WEB_UNLAYERED_SCOPES:
            out.append(f"{prelude} {{ {body} }}")
    return "\n".join(out)


# ── Self-test ────────────────────────────────────────────────────────────────


def self_test() -> None:
    def paint(v, values=None):
        return Resolver(values or {}).color(v).paint()

    assert paint("oklch(1 0 0)") == (255, 255, 255, 255)
    assert paint("oklch(0 0 0)") == (0, 0, 0, 255)
    assert paint("#FFD440") == (255, 212, 64, 255)
    assert paint("#abc") == (0xAA, 0xBB, 0xCC, 255)
    assert paint("rgba(20, 17, 17, 0.12)") == (20, 17, 17, 31)
    assert paint("rgb(0 0 0 / 50%)") == (0, 0, 0, 128)
    # Tailwind gray-400 is documented as #99A1AF.
    assert paint("oklch(70.7% 0.022 261.325)")[:3] == (0x99, 0xA1, 0xAF)
    # Out-of-gamut chroma is clipped per channel, not chroma-reduced.
    hot = paint("oklch(0.7 0.4 30)")
    assert hot[0] == 255 and hot[3] == 255
    # var() chains, fallbacks and color-mix.
    vals = {"--a": "oklch(1 0 0)", "--b": "var(--a)", "--k": "oklch(0 0 0)"}
    assert paint("var(--b)", vals) == (255, 255, 255, 255)
    assert paint("var(--missing, #000)", vals) == (0, 0, 0, 255)
    half = paint("color-mix(in srgb-linear, var(--a) 50%, var(--k))", vals)
    assert half[:3] == (188, 188, 188), half  # 50% linear light = 188 in sRGB
    assert paint("color-mix(in srgb, #ffffff 50%, #000000)", vals)[0] in (127, 128)  # 127.5 in gamma space
    assert paint("color-mix(in srgb-linear, #fff 30%, #000 20%)", vals)[3] == 128  # alpha * 0.5
    # Non-colours are skipped; unsupported colour syntax fails closed.
    assert Resolver({}).color("4px 4px 0px #000") is None
    assert Resolver({}).color("16px") is None
    for bad in ("color-mix(in oklch, #fff, #000)", "red", "var(--nope)"):
        try:
            Resolver({}).color(bad)
        except TokenError:
            continue
        raise AssertionError(f"accepted {bad!r}")
    try:
        Resolver({"--x": "var(--y)", "--y": "var(--x)"}).token("--x")
        raise AssertionError("cycle accepted")
    except TokenError:
        pass
    # Cascade: unlayered foundation beats @theme; .light/.dark beat :where(:root); web :root.dark beats .dark.
    srcs = {
        "tailwind": "@theme { --color-gray-1: #111; --t: #010101; }",
        "raftUiStyles": "@theme { --color-brand: var(--brand-400); }",
        "raftUiFoundation": (
            ":where(:root) { --brand-400: #222; }\n"
            ":where(:root),\n.light { --fg: #000; --t: #020202; --seam: #444; }\n"
            ".dark { --fg: #eee; --seam: #555; }\n"
            "@media (prefers-color-scheme: dark) { :root:not(.light):not(.dark) { --fg: #f00; } }\n"
            ":where(:root), .light, .dark { --line-field: var(--fg); }"
        ),
        "web": "@theme { --color-brand: #FFD440; }\n:root.dark { --code: #0a0c10; --seam: #666; }\n@layer base { :root { --fg: #f00; } }",
    }
    toks, themed = resolve_all(srcs)
    assert toks["raftLight"]["--fg"] == (0, 0, 0, 255)
    assert toks["raftDark"]["--fg"] == (0xEE, 0xEE, 0xEE, 255), "@media mirror must be ignored; .dark wins"
    assert toks["raftLight"]["--t"] == (2, 2, 2, 255), "unlayered must beat @theme"
    assert toks["raftDark"]["--t"] == (2, 2, 2, 255), "dark inherits the :where(:root) default"
    assert toks["raftLight"]["--color-brand"] == (255, 212, 64, 255), "web @theme must win over raft-ui @theme"
    assert toks["raftDark"]["--seam"] == (0x66, 0x66, 0x66, 255), "web :root.dark beats foundation .dark"
    assert toks["raftDark"]["--line-field"] == (0xEE, 0xEE, 0xEE, 255), "var() resolves per theme"
    assert "--code" not in toks["raftLight"] and toks["raftDark"]["--code"] == (10, 12, 16, 255)
    assert {"--fg", "--code", "--seam", "--line-field"} <= themed, themed
    assert kotlin_name("--color-brutal-yellow-400") == "brutalYellow400"
    assert kotlin_name("--foreground-muted") == "foregroundMuted"
    assert over((0, 0, 0, 128), (255, 255, 255)) == (127, 127, 127)
    print("self-test OK")


# ── Entry point ──────────────────────────────────────────────────────────────


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--dump-json", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        self_test()
        return 0
    if args.dump_json:
        sources = load_sources()
        tokens, _ = resolve_all(sources)
        print(json.dumps({"css": browser_css(sources), "themes": {t: sorted(tokens[t]) for t in THEMES}}))
        return 0

    errors = verify_manifest()
    if errors:
        print("design sources do not match manifest.json:\n  " + "\n  ".join(errors), file=sys.stderr)
        return 1
    tokens, themed = resolve_all(load_sources())
    text = render(tokens, themed) + "\n"
    if args.check:
        problems = []
        if not OUTPUT.exists() or OUTPUT.read_text() != text:
            problems.append(f"{OUTPUT.relative_to(REPO)} is stale: run python3 tools/design-tokens/gen_tokens.py")
        problems += check_oracle(tokens)
        if problems:
            print("\n".join(problems), file=sys.stderr)
            return 1
        print(f"design tokens up to date ({sum(len(v) for v in tokens.values())} theme values)")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(text)
    print(f"wrote {OUTPUT.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
