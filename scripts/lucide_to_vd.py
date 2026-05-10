#!/usr/bin/env python3
"""
Download Lucide SVG icons and convert them to Android Vector Drawables.

Usage:
    python scripts/lucide_to_vd.py house square-check-big users settings
    python scripts/lucide_to_vd.py --version 1.14.0 house square-check-big

Downloaded SVGs are converted and written to:
    app/src/main/res/drawable/lucide_<icon_name>.xml

SVG path data is 100% compatible with Android pathData, so no path
conversion is needed — only attribute names change. Non-path shapes
(circle, rect, line, polyline, polygon, ellipse) are expanded to path data.
"""

import re
import sys
import os
import urllib.request
from xml.etree import ElementTree as ET

DEFAULT_VERSION = "1.14.0"

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_DIR = os.path.join(_SCRIPT_DIR, "..", "app", "src", "main", "res", "drawable")

SVG_NS = "http://www.w3.org/2000/svg"


# ---------------------------------------------------------------------------
# Shape → path data converters
# ---------------------------------------------------------------------------

def _circle_to_path(cx, cy, r):
    cx, cy, r = float(cx), float(cy), float(r)
    # Two 180° arcs because a full-circle arc is degenerate in SVG
    return (
        f"M {cx - r} {cy} "
        f"A {r} {r} 0 1 0 {cx + r} {cy} "
        f"A {r} {r} 0 1 0 {cx - r} {cy} Z"
    )


def _ellipse_to_path(cx, cy, rx, ry):
    cx, cy, rx, ry = float(cx), float(cy), float(rx), float(ry)
    return (
        f"M {cx - rx} {cy} "
        f"A {rx} {ry} 0 1 0 {cx + rx} {cy} "
        f"A {rx} {ry} 0 1 0 {cx - rx} {cy} Z"
    )


def _line_to_path(x1, y1, x2, y2):
    return f"M {x1} {y1} L {x2} {y2}"


def _rect_to_path(x, y, w, h, rx=0.0, ry=0.0):
    x, y, w, h = float(x), float(y), float(w), float(h)
    rx, ry = float(rx), float(ry)
    if rx == 0 and ry == 0:
        return f"M {x} {y} H {x + w} V {y + h} H {x} Z"
    rx = min(rx, w / 2)
    ry = min(ry, h / 2)
    return (
        f"M {x + rx} {y} H {x + w - rx} "
        f"A {rx} {ry} 0 0 1 {x + w} {y + ry} "
        f"V {y + h - ry} "
        f"A {rx} {ry} 0 0 1 {x + w - rx} {y + h} "
        f"H {x + rx} "
        f"A {rx} {ry} 0 0 1 {x} {y + h - ry} "
        f"V {y + ry} "
        f"A {rx} {ry} 0 0 1 {x + rx} {y} Z"
    )


def _points_to_path(points, close=False):
    nums = re.findall(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", points)
    coords = list(map(float, nums))
    pairs = [(coords[i], coords[i + 1]) for i in range(0, len(coords) - 1, 2)]
    if not pairs:
        return ""
    d = f"M {pairs[0][0]} {pairs[0][1]}"
    for px, py in pairs[1:]:
        d += f" L {px} {py}"
    if close:
        d += " Z"
    return d


# ---------------------------------------------------------------------------
# SVG parser
# ---------------------------------------------------------------------------

def _strip_ns(tag):
    return tag.replace(f"{{{SVG_NS}}}", "")


def _resolve_color(value):
    """Return an Android-compatible hex color, or None for transparent/none."""
    if not value or value in ("none", "transparent"):
        return None
    if value == "currentColor":
        return "#000000"  # will be overridden by Compose tint anyway
    return value


def parse_svg(svg_text):
    """Parse a Lucide SVG string into a list of path dicts for Android VD."""
    ET.register_namespace("", SVG_NS)
    root = ET.fromstring(svg_text)

    # Defaults declared on the root <svg> element (Lucide puts them here)
    svg_defaults = {
        "stroke":           root.get("stroke", "currentColor"),
        "fill":             root.get("fill", "none"),
        "stroke-width":     root.get("stroke-width", "2"),
        "stroke-linecap":   root.get("stroke-linecap", "round"),
        "stroke-linejoin":  root.get("stroke-linejoin", "round"),
    }

    result = []

    def visit(el, inherited):
        tag = _strip_ns(el.tag)

        # Each element inherits from its parent context, then overrides
        ctx = {k: el.get(k, inherited[k]) for k in inherited}

        path_data = None
        if tag == "path":
            path_data = el.get("d", "")
        elif tag == "circle":
            path_data = _circle_to_path(
                el.get("cx", 0), el.get("cy", 0), el.get("r", 0))
        elif tag == "ellipse":
            path_data = _ellipse_to_path(
                el.get("cx", 0), el.get("cy", 0),
                el.get("rx", 0), el.get("ry", 0))
        elif tag == "line":
            path_data = _line_to_path(
                el.get("x1", 0), el.get("y1", 0),
                el.get("x2", 0), el.get("y2", 0))
        elif tag == "rect":
            path_data = _rect_to_path(
                el.get("x", 0), el.get("y", 0),
                el.get("width", 0), el.get("height", 0),
                el.get("rx", 0), el.get("ry", 0))
        elif tag == "polyline":
            path_data = _points_to_path(el.get("points", ""), close=False)
        elif tag == "polygon":
            path_data = _points_to_path(el.get("points", ""), close=True)

        if path_data:
            stroke_color = _resolve_color(ctx["stroke"])
            fill_color   = _resolve_color(ctx["fill"])
            entry = {"pathData": path_data}
            if fill_color:
                entry["fillColor"] = fill_color
            if stroke_color:
                entry["strokeColor"]     = stroke_color
                entry["strokeWidth"]     = ctx["stroke-width"]
                entry["strokeLineCap"]   = ctx["stroke-linecap"]
                entry["strokeLineJoin"]  = ctx["stroke-linejoin"]
            result.append(entry)

        for child in el:
            visit(child, ctx)

    for child in root:
        visit(child, svg_defaults)

    return result


# ---------------------------------------------------------------------------
# Android VD XML renderer
# ---------------------------------------------------------------------------

def to_vd_xml(paths):
    lines = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24">',
    ]
    for p in paths:
        attrs = [f'android:pathData="{p["pathData"]}"']
        if p.get("fillColor"):
            attrs.append(f'android:fillColor="{p["fillColor"]}"')
        if p.get("strokeColor"):
            attrs.append(f'android:strokeColor="{p["strokeColor"]}"')
            attrs.append(f'android:strokeWidth="{p["strokeWidth"]}"')
            attrs.append(f'android:strokeLineCap="{p["strokeLineCap"]}"')
            attrs.append(f'android:strokeLineJoin="{p["strokeLineJoin"]}"')
        indent = "\n        "
        lines.append(f"    <path\n        {indent.join(attrs)} />")
    lines.append("</vector>")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Download + convert
# ---------------------------------------------------------------------------

def download_svg(name, version):
    url = (
        f"https://raw.githubusercontent.com/lucide-icons/lucide"
        f"/{version}/icons/{name}.svg"
    )
    print(f"  ↓  {url}")
    with urllib.request.urlopen(url) as resp:
        return resp.read().decode("utf-8")


def convert(name, version, output_dir):
    print(f"[{name}]")
    try:
        svg = download_svg(name, version)
    except Exception as e:
        print(f"  ✗ download failed: {e}")
        return False
    try:
        paths = parse_svg(svg)
    except Exception as e:
        print(f"  ✗ parse failed: {e}")
        return False
    xml = to_vd_xml(paths)
    filename = f"lucide_{name.replace('-', '_')}.xml"
    out = os.path.join(output_dir, filename)
    with open(out, "w") as f:
        f.write(xml + "\n")
    print(f"  ✓  {out}")
    return True


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(1)

    version = DEFAULT_VERSION
    icon_names = []
    i = 0
    while i < len(args):
        if args[i] == "--version" and i + 1 < len(args):
            version = args[i + 1]
            i += 2
        else:
            icon_names.append(args[i])
            i += 1

    if not icon_names:
        print(__doc__)
        sys.exit(1)

    output_dir = os.path.abspath(OUTPUT_DIR)
    os.makedirs(output_dir, exist_ok=True)
    print(f"Lucide {version}  →  {output_dir}\n")

    ok = sum(convert(n, version, output_dir) for n in icon_names)
    print(f"\nDone: {ok}/{len(icon_names)} converted.")


if __name__ == "__main__":
    main()
