#!/usr/bin/env python3
"""Generate the theme decor art (the frame's pixel sprites) from raft-ui themes.css.

raft-ui `themes.css` (vendored under source/, pinned in manifest.json) gives every theme
with decor two inline SVG data URIs:

  --theme-decor-top    190x48, painted on the top bar (`frame-decor-top`, styles.css)
  --theme-decor-rail   48x112, painted at the foot of the rail's nav (`frame-decor-rail`)

on `:is(:root, .light, .dark)[data-theme="x"]`; a theme may repaint them for dark mode on
`.dark[data-theme="x"]` (Weather, Spooky). Each SVG is `shape-rendering="crispEdges"` pixel
art: `<g transform="translate(tx ty) scale(s)">` groups of `<path fill="#rrggbb">` whose `d`
is only unit-grid rectangles (`Mx yhWvHh-Wz`). This script checks that shape (anything else
fails the run), applies each group's transform, and writes every path as one layer of
rectangles in the SVG's own pixels (1 CSS px = 1dp) to

  compose/designsystem/src/commonMain/kotlin/build/raft/designsystem/tokens/RaftThemeDecorArt.g.kt

Usage:
  gen_theme_decor.py           regenerate the Kotlin file
  gen_theme_decor.py --check   fail if the sources do not match manifest.json or the file is stale
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.parse
from dataclasses import dataclass

from gen_tokens import (
    MANIFEST,
    REPO,
    THEME_IDS,
    _norm_selector,
    declarations,
    enum_name,
    load_sources,
    strip_comments,
    top_level_blocks,
    verify_manifest,
)

OUTPUT = REPO / "compose/designsystem/src/commonMain/kotlin/build/raft/designsystem/tokens/RaftThemeDecorArt.g.kt"
SLOTS = {"top": (190, 48), "rail": (48, 112)}

SVG_RE = re.compile(
    r"^<svg xmlns='http://www.w3.org/2000/svg' width='(\d+)' height='(\d+)' shape-rendering='crispEdges'>(.*)</svg>$",
    re.S,
)
GROUP_RE = re.compile(r"<g transform='translate\((\d+) (\d+)\) scale\((\d+)\)'>(.*?)</g>", re.S)
PATH_RE = re.compile(r"<path fill='#([0-9a-fA-F]{6})' d='([^']*)'/>")
RECT_RE = re.compile(r"M(\d+) (\d+)h(\d+)v(\d+)h-(\d+)z")
URL_RE = re.compile(r'^url\("data:image/svg\+xml,(.*)"\)$', re.S)


class DecorError(Exception):
    pass


@dataclass(frozen=True)
class Layer:
    argb: str
    rects: tuple[tuple[int, int, int, int], ...]


@dataclass(frozen=True)
class Art:
    width: int
    height: int
    layers: tuple[Layer, ...]


def parse_svg(value: str, slot: str) -> Art:
    m = URL_RE.match(value)
    if not m:
        raise DecorError(f"--theme-decor-{slot} is not an SVG data URI: {value[:60]}")
    svg = urllib.parse.unquote(m.group(1))
    sm = SVG_RE.match(svg)
    if not sm:
        raise DecorError(f"unexpected <svg> header: {svg[:120]}")
    width, height, body = int(sm.group(1)), int(sm.group(2)), sm.group(3)
    if (width, height) != SLOTS[slot]:
        raise DecorError(f"--theme-decor-{slot} is {width}x{height}, expected {SLOTS[slot]}")
    layers = []
    consumed = 0
    for gm in GROUP_RE.finditer(body):
        if gm.start() != consumed:
            raise DecorError(f"unexpected SVG content: {body[consumed:gm.start()][:80]}")
        consumed = gm.end()
        tx, ty, scale, inner = int(gm.group(1)), int(gm.group(2)), int(gm.group(3)), gm.group(4)
        pos = 0
        for pm in PATH_RE.finditer(inner):
            if pm.start() != pos:
                raise DecorError(f"unexpected group content: {inner[pos:pm.start()][:80]}")
            pos = pm.end()
            d = pm.group(2)
            rects = []
            rpos = 0
            for rm in RECT_RE.finditer(d):
                if rm.start() != rpos:
                    raise DecorError(f"path data is not unit rectangles: {d[rpos:rm.start()][:40]}")
                rpos = rm.end()
                x, y, w, h, back = (int(g) for g in rm.groups())
                if back != w:
                    raise DecorError(f"open rectangle in path data: {rm.group(0)}")
                rects.append((tx + x * scale, ty + y * scale, w * scale, h * scale))
            if rpos != len(d) or not rects:
                raise DecorError(f"path data is not unit rectangles: {d[rpos:][:40]}")
            for x, y, w, h in rects:
                if x < 0 or y < 0 or x + w > width or y + h > height:
                    raise DecorError(f"rectangle {x},{y} {w}x{h} outside the {width}x{height} art")
            layers.append(Layer("0xFF" + pm.group(1).upper(), tuple(rects)))
        if pos != len(inner):
            raise DecorError(f"unexpected group content: {inner[pos:][:80]}")
    if consumed != len(body) or not layers:
        raise DecorError(f"unexpected SVG content: {body[consumed:][:80]}")
    return Art(width, height, tuple(layers))


def collect(themes_css: str) -> dict[tuple[str, str, bool], Art]:
    """(theme id, slot, dark) -> art, for every theme with decor."""
    blocks = top_level_blocks(strip_comments(themes_css))
    found: dict[tuple[str, str, str], str] = {}  # (theme, slot, scope) -> value
    for prelude, body in blocks:
        selector = _norm_selector(prelude)
        if selector.startswith("@"):
            continue  # the `prefers-color-scheme` copy for a root without a mode class
        for theme_id in THEME_IDS:
            scopes = {
                f':is(:root, .light, .dark)[data-theme="{theme_id}"]': "any",
                f'.light[data-theme="{theme_id}"], :root:not(.light):not(.dark)[data-theme="{theme_id}"]': "light",
                f'.dark[data-theme="{theme_id}"]': "dark",
            }
            scope = scopes.get(selector)
            if scope is None:
                continue
            for name, value in declarations(body):
                for slot in SLOTS:
                    if name == f"--theme-decor-{slot}":
                        found[(theme_id, slot, scope)] = value
    for prelude, _ in blocks:
        if "--theme-decor" in prelude:
            raise DecorError(f"unexpected selector {prelude!r}")
    arts: dict[tuple[str, str, bool], Art] = {}
    for theme_id in THEME_IDS:
        for slot in SLOTS:
            for dark in (False, True):
                value = (
                    found.get((theme_id, slot, "dark" if dark else "light"))
                    or found.get((theme_id, slot, "any"))
                )
                if value is not None:
                    arts[(theme_id, slot, dark)] = parse_svg(value, slot)
    return arts


def kotlin_val(theme_id: str, slot: str, dark: bool) -> str:
    return f"{theme_id}{slot.capitalize()}{'Dark' if dark else ''}"


def render(arts: dict[tuple[str, str, bool], Art], origin: str, sha: str) -> str:
    out = [
        "// GENERATED by tools/design-tokens/gen_theme_decor.py — DO NOT EDIT.",
        "// Regenerate: python3 tools/design-tokens/gen_theme_decor.py",
        f"// Source (tools/design-tokens/manifest.json): {origin} sha256={sha[:16]}",
        "",
        "package build.raft.designsystem.tokens",
        "",
        "/**",
        " * raft-ui `themes.css` `--theme-decor-top` (190x48) and `--theme-decor-rail` (48x112): each",
        " * theme's pixel art for the frame, as [RaftPixelArt] in the SVG's own pixels (1 CSS px = 1dp).",
        " * Null for a theme without decor (Raft, Boring). Dark mode takes the `.dark[data-theme]`",
        " * repaint where a theme has one, else the same art as light.",
        " */",
        "internal object RaftThemeDecorArt {",
        "    fun top(theme: RaftThemeId, dark: Boolean): RaftPixelArt? = when (theme) {",
    ]

    def branch(slot: str) -> list[str]:
        lines = []
        for theme_id in THEME_IDS:
            light = arts.get((theme_id, slot, False))
            dark = arts.get((theme_id, slot, True))
            if light is None and dark is None:
                continue
            if light is None or dark is None:
                raise DecorError(f"{theme_id} has {slot} decor in one mode only")
            light_val = kotlin_val(theme_id, slot, False)
            if light == dark:
                lines.append(f"        RaftThemeId.{enum_name(theme_id)} -> {light_val}")
            else:
                dark_val = kotlin_val(theme_id, slot, True)
                lines.append(f"        RaftThemeId.{enum_name(theme_id)} -> if (dark) {dark_val} else {light_val}")
        lines.append("        else -> null")
        lines.append("    }")
        return lines

    out += branch("top")
    out.append("")
    out.append("    fun rail(theme: RaftThemeId, dark: Boolean): RaftPixelArt? = when (theme) {")
    out += branch("rail")
    for theme_id in THEME_IDS:
        for slot in SLOTS:
            for dark in (False, True):
                art = arts.get((theme_id, slot, dark))
                if art is None or (dark and art == arts.get((theme_id, slot, False))):
                    continue
                out.append("")
                out.append(f"    private val {kotlin_val(theme_id, slot, dark)}: RaftPixelArt by lazy {{")
                out.append(f"        RaftPixelArt(")
                out.append(f"            width = {art.width},")
                out.append(f"            height = {art.height},")
                out.append(f"            layers = listOf(")
                for layer in art.layers:
                    rects = " ".join(f"{x} {y} {w} {h}" for x, y, w, h in layer.rects)
                    out.append(f'                RaftPixelArt.Layer({layer.argb}, "{rects}"),')
                out.append("            ),")
                out.append("        )")
                out.append("    }")
    out.append("}")
    return "\n".join(out) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    errors = verify_manifest()
    if errors:
        print("design sources do not match manifest.json:\n  " + "\n  ".join(errors), file=sys.stderr)
        return 1
    import json

    entry = json.loads(MANIFEST.read_text())["sources"]["raftUiThemes"]
    arts = collect(load_sources()["raftUiThemes"])
    text = render(arts, entry["origin"], entry["sha256"])
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text() != text:
            print(f"{OUTPUT.relative_to(REPO)} is stale: run python3 tools/design-tokens/gen_theme_decor.py", file=sys.stderr)
            return 1
        print(f"theme decor up to date ({len(arts)} arts)")
        return 0
    OUTPUT.write_text(text)
    print(f"wrote {OUTPUT.relative_to(REPO)} ({len(arts)} arts)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except DecorError as e:
        print(f"theme decor: {e}", file=sys.stderr)
        sys.exit(1)
