#!/usr/bin/env python3
"""
Download Lucide SVG icons and generate Android Compose ImageVector catalog.

This keeps the Android app on the same Lucide source list as shared.icons
without hand-maintained vector drawable XML.
"""

import os
import re
from xml.etree import ElementTree as ET

from lucide_to_shared_icons import DEFAULT_ICONS, DEFAULT_VERSION, download_svg, pascal_name

SVG_NS = "http://www.w3.org/2000/svg"

OUTPUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..",
    "app",
    "src",
    "main",
    "java",
    "ai",
    "slock",
    "android",
    "ui",
    "components",
    "SlockIcons.kt",
)


def kotlin_string(value):
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")


def circle_to_path(cx, cy, r):
    cx, cy, r = float(cx), float(cy), float(r)
    return (
        f"M {cx - r} {cy} "
        f"A {r} {r} 0 1 0 {cx + r} {cy} "
        f"A {r} {r} 0 1 0 {cx - r} {cy} Z"
    )


def ellipse_to_path(cx, cy, rx, ry):
    cx, cy, rx, ry = float(cx), float(cy), float(rx), float(ry)
    return (
        f"M {cx - rx} {cy} "
        f"A {rx} {ry} 0 1 0 {cx + rx} {cy} "
        f"A {rx} {ry} 0 1 0 {cx - rx} {cy} Z"
    )


def line_to_path(x1, y1, x2, y2):
    return f"M {x1} {y1} L {x2} {y2}"


def rect_to_path(x, y, w, h, rx=0.0, ry=0.0):
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


def points_to_path(points, close=False):
    nums = re.findall(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", points)
    coords = list(map(float, nums))
    pairs = [(coords[i], coords[i + 1]) for i in range(0, len(coords) - 1, 2)]
    if not pairs:
        return ""
    data = f"M {pairs[0][0]} {pairs[0][1]}"
    for px, py in pairs[1:]:
        data += f" L {px} {py}"
    if close:
        data += " Z"
    return data


def strip_ns(tag):
    return tag.replace(f"{{{SVG_NS}}}", "")


def resolve_color(value):
    if not value or value in ("none", "transparent"):
        return None
    if value == "currentColor":
        return "#000000"
    return value


def parse_svg(svg_text):
    ET.register_namespace("", SVG_NS)
    root = ET.fromstring(svg_text)
    svg_defaults = {
        "stroke": root.get("stroke", "currentColor"),
        "fill": root.get("fill", "none"),
        "stroke-width": root.get("stroke-width", "2"),
        "stroke-linecap": root.get("stroke-linecap", "round"),
        "stroke-linejoin": root.get("stroke-linejoin", "round"),
    }
    result = []

    def visit(el, inherited):
        tag = strip_ns(el.tag)
        ctx = {key: el.get(key, inherited[key]) for key in inherited}
        path_data = None
        if tag == "path":
            path_data = el.get("d", "")
        elif tag == "circle":
            path_data = circle_to_path(el.get("cx", 0), el.get("cy", 0), el.get("r", 0))
        elif tag == "ellipse":
            path_data = ellipse_to_path(
                el.get("cx", 0), el.get("cy", 0), el.get("rx", 0), el.get("ry", 0)
            )
        elif tag == "line":
            path_data = line_to_path(
                el.get("x1", 0), el.get("y1", 0), el.get("x2", 0), el.get("y2", 0)
            )
        elif tag == "rect":
            rx = el.get("rx", el.get("ry", 0))
            ry = el.get("ry", rx)
            path_data = rect_to_path(
                el.get("x", 0), el.get("y", 0), el.get("width", 0), el.get("height", 0), rx, ry
            )
        elif tag == "polyline":
            path_data = points_to_path(el.get("points", ""), close=False)
        elif tag == "polygon":
            path_data = points_to_path(el.get("points", ""), close=True)
        if path_data:
            entry = {"pathData": path_data}
            fill_color = resolve_color(ctx["fill"])
            stroke_color = resolve_color(ctx["stroke"])
            if fill_color:
                entry["fillColor"] = fill_color
            if stroke_color:
                entry["strokeColor"] = stroke_color
                entry["strokeWidth"] = ctx["stroke-width"]
                entry["strokeLineCap"] = ctx["stroke-linecap"]
                entry["strokeLineJoin"] = ctx["stroke-linejoin"]
            result.append(entry)
        for child in el:
            visit(child, ctx)

    for child in root:
        visit(child, svg_defaults)
    return result


def color_expr(value, default):
    if not value:
        return default
    if value == "#000000":
        return "Color.Black"
    return f"Color(0xFF{value.lstrip('#')})"


def path_expr(path):
    fill = "null"
    if path.get("fillColor"):
        fill = f"SolidColor({color_expr(path['fillColor'], 'Color.Black')})"
    stroke = "null"
    if path.get("strokeColor"):
        stroke = f"SolidColor({color_expr(path['strokeColor'], 'Color.Black')})"
    return "\n".join(
        [
            "            addPath(",
            f"                pathData = PathParser().parsePathString(\"{kotlin_string(path['pathData'])}\").toNodes(),",
            f"                fill = {fill},",
            f"                stroke = {stroke},",
            f"                strokeLineWidth = {path.get('strokeWidth', '0')}f,",
            f"                strokeLineCap = {line_cap(path.get('strokeLineCap'))},",
            f"                strokeLineJoin = {line_join(path.get('strokeLineJoin'))}",
            "            )",
        ]
    )


def line_cap(value):
    return {
        "butt": "StrokeCap.Butt",
        "round": "StrokeCap.Round",
        "square": "StrokeCap.Square",
    }.get(value, "StrokeCap.Round")


def line_join(value):
    return {
        "miter": "StrokeJoin.Miter",
        "round": "StrokeJoin.Round",
        "bevel": "StrokeJoin.Bevel",
    }.get(value, "StrokeJoin.Round")


def generate(version=DEFAULT_VERSION, icons=DEFAULT_ICONS, output=OUTPUT):
    lines = [
        "package ai.slock.android.ui.components",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.graphics.SolidColor",
        "import androidx.compose.ui.graphics.StrokeCap",
        "import androidx.compose.ui.graphics.StrokeJoin",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        "import androidx.compose.ui.graphics.vector.PathParser",
        "import androidx.compose.ui.unit.dp",
        "",
        "// Generated by scripts/lucide_to_android_compose_icons.py. Do not edit by hand.",
        "object SlockIcons {",
        f"    const val LucideVersion: String = \"{version}\"",
        "",
    ]
    for name in icons:
        prop = pascal_name(name)
        paths = parse_svg(download_svg(name, version))
        lines.extend(
            [
                f"    val {prop}: ImageVector by lazy {{",
                "        ImageVector.Builder(",
                f"            name = \"Lucide{prop}\",",
                "            defaultWidth = 24.dp,",
                "            defaultHeight = 24.dp,",
                "            viewportWidth = 24f,",
                "            viewportHeight = 24f",
                "        ).apply {",
            ]
        )
        for path in paths:
            lines.append(path_expr(path))
        lines.extend(["        }.build()", "    }", ""])
    lines.append("}")
    lines.append("")
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w", encoding="utf-8") as file:
        file.write("\n".join(lines))


if __name__ == "__main__":
    generate()
