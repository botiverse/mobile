#!/usr/bin/env python3
"""
Download Lucide SVG icons and generate a Kuikly Compose shared icon catalog.

The generated catalog stores both the original SVG payload and a small vector
command model that can be rendered by Kuikly Canvas from commonMain.
"""

import argparse
import os
import re
import time
import urllib.request
from xml.etree import ElementTree as ET

DEFAULT_VERSION = "1.14.0"
SVG_NS = "http://www.w3.org/2000/svg"

DEFAULT_ICONS = [
    "activity",
    "archive",
    "arrow-left",
    "arrow-up-down",
    "ban",
    "bell-ring",
    "bookmark",
    "bot",
    "bug",
    "check",
    "chevron-down",
    "chevron-left",
    "chevron-right",
    "circle",
    "circle-check-big",
    "columns-3",
    "copy",
    "external-link",
    "eye",
    "file-text",
    "folder-open",
    "globe",
    "hash",
    "house",
    "image",
    "image-plus",
    "inbox",
    "key",
    "layout-list",
    "link",
    "list-todo",
    "log-in",
    "log-out",
    "mail",
    "message-square",
    "monitor",
    "paperclip",
    "pencil",
    "play",
    "plus",
    "refresh-cw",
    "search",
    "send",
    "settings",
    "share-2",
    "shield-check",
    "smartphone",
    "square",
    "square-check-big",
    "trash",
    "user",
    "users",
    "x",
]


def download_svg(name, version):
    url = f"https://raw.githubusercontent.com/lucide-icons/lucide/{version}/icons/{name}.svg"
    for attempt in range(3):
        try:
            print(f"  ↓  {url}")
            request = urllib.request.Request(url, headers={"User-Agent": "slock-lucide-generator"})
            with urllib.request.urlopen(request, timeout=20) as resp:
                return resp.read().decode("utf-8").strip()
        except Exception:
            if attempt == 2:
                raise
            time.sleep(1 + attempt)


def strip_ns(tag):
    return tag.replace(f"{{{SVG_NS}}}", "")


def number(value, fallback=0.0):
    if value is None or value == "":
        return fallback
    return float(value)


def style(root, el):
    return {
        "fill": el.get("fill", root.get("fill", "none")),
        "stroke": el.get("stroke", root.get("stroke", "currentColor")),
        "stroke-width": el.get("stroke-width", root.get("stroke-width", "2")),
        "stroke-linecap": el.get("stroke-linecap", root.get("stroke-linecap", "round")),
        "stroke-linejoin": el.get("stroke-linejoin", root.get("stroke-linejoin", "round")),
    }


TOKEN_RE = re.compile(r"[AaCcHhLlMmQqSsTtVvZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")


def tokenize_path(data):
    return TOKEN_RE.findall(data.replace(",", " "))


def is_command(token):
    return len(token) == 1 and token.isalpha()


def parse_path(data):
    tokens = tokenize_path(data)
    result = []
    index = 0
    command = None
    current = (0.0, 0.0)
    start = (0.0, 0.0)
    last_cubic_control = None
    last_quad_control = None

    def read_float():
        nonlocal index
        value = float(tokens[index])
        index += 1
        return value

    def point(relative=False):
        x = read_float()
        y = read_float()
        if relative:
            return current[0] + x, current[1] + y
        return x, y

    while index < len(tokens):
        if is_command(tokens[index]):
            command = tokens[index]
            index += 1
        if command is None:
            raise ValueError(f"path data has no command: {data}")

        relative = command.islower()
        op = command.upper()

        if op == "M":
            x, y = point(relative)
            result.append(("MoveTo", x, y))
            current = (x, y)
            start = current
            command = "l" if relative else "L"
            last_cubic_control = None
            last_quad_control = None
            continue
        elif op == "L":
            while index < len(tokens) and not is_command(tokens[index]):
                x, y = point(relative)
                result.append(("LineTo", x, y))
                current = (x, y)
            last_cubic_control = None
            last_quad_control = None
        elif op == "H":
            while index < len(tokens) and not is_command(tokens[index]):
                x = read_float()
                if relative:
                    x += current[0]
                result.append(("LineTo", x, current[1]))
                current = (x, current[1])
            last_cubic_control = None
            last_quad_control = None
        elif op == "V":
            while index < len(tokens) and not is_command(tokens[index]):
                y = read_float()
                if relative:
                    y += current[1]
                result.append(("LineTo", current[0], y))
                current = (current[0], y)
            last_cubic_control = None
            last_quad_control = None
        elif op == "C":
            while index < len(tokens) and not is_command(tokens[index]):
                c1 = point(relative)
                c2 = point(relative)
                end = point(relative)
                result.append(("CubicTo", c1[0], c1[1], c2[0], c2[1], end[0], end[1]))
                current = end
                last_cubic_control = c2
                last_quad_control = None
        elif op == "S":
            while index < len(tokens) and not is_command(tokens[index]):
                c1 = (
                    current[0] * 2 - last_cubic_control[0],
                    current[1] * 2 - last_cubic_control[1],
                ) if last_cubic_control else current
                c2 = point(relative)
                end = point(relative)
                result.append(("CubicTo", c1[0], c1[1], c2[0], c2[1], end[0], end[1]))
                current = end
                last_cubic_control = c2
                last_quad_control = None
        elif op == "Q":
            while index < len(tokens) and not is_command(tokens[index]):
                c = point(relative)
                end = point(relative)
                result.append(("QuadraticTo", c[0], c[1], end[0], end[1]))
                current = end
                last_quad_control = c
                last_cubic_control = None
        elif op == "T":
            while index < len(tokens) and not is_command(tokens[index]):
                c = (
                    current[0] * 2 - last_quad_control[0],
                    current[1] * 2 - last_quad_control[1],
                ) if last_quad_control else current
                end = point(relative)
                result.append(("QuadraticTo", c[0], c[1], end[0], end[1]))
                current = end
                last_quad_control = c
                last_cubic_control = None
        elif op == "A":
            while index < len(tokens) and not is_command(tokens[index]):
                # Elliptical arcs are rare in Lucide path elements. Keep the
                # endpoint so the catalog remains drawable; circle elements are
                # emitted as real Circle commands above.
                read_float()
                read_float()
                read_float()
                read_float()
                read_float()
                x = read_float()
                y = read_float()
                if relative:
                    x += current[0]
                    y += current[1]
                result.append(("LineTo", x, y))
                current = (x, y)
            last_cubic_control = None
            last_quad_control = None
        elif op == "Z":
            result.append(("Close",))
            current = start
            last_cubic_control = None
            last_quad_control = None
            continue
        else:
            raise ValueError(f"unsupported path command {command}")
    return result


def rect_commands(el):
    x = number(el.get("x"))
    y = number(el.get("y"))
    w = number(el.get("width"))
    h = number(el.get("height"))
    rx = number(el.get("rx"), number(el.get("ry")))
    ry = number(el.get("ry"), rx)
    if rx == 0 and ry == 0:
        return [
            ("MoveTo", x, y),
            ("LineTo", x + w, y),
            ("LineTo", x + w, y + h),
            ("LineTo", x, y + h),
            ("Close",),
        ]
    rx = min(rx, w / 2)
    ry = min(ry, h / 2)
    return [
        ("MoveTo", x + rx, y),
        ("LineTo", x + w - rx, y),
        ("QuadraticTo", x + w, y, x + w, y + ry),
        ("LineTo", x + w, y + h - ry),
        ("QuadraticTo", x + w, y + h, x + w - rx, y + h),
        ("LineTo", x + rx, y + h),
        ("QuadraticTo", x, y + h, x, y + h - ry),
        ("LineTo", x, y + ry),
        ("QuadraticTo", x, y, x + rx, y),
        ("Close",),
    ]


def points_commands(points, close):
    nums = [float(n) for n in re.findall(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", points)]
    pairs = list(zip(nums[::2], nums[1::2]))
    if not pairs:
        return []
    commands = [("MoveTo", pairs[0][0], pairs[0][1])]
    commands.extend(("LineTo", x, y) for x, y in pairs[1:])
    if close:
        commands.append(("Close",))
    return commands


def parse_svg_nodes(svg):
    ET.register_namespace("", SVG_NS)
    root = ET.fromstring(svg)
    nodes = []

    def visit(el):
        tag = strip_ns(el.tag)
        commands = []
        if tag == "path":
            commands = parse_path(el.get("d", ""))
        elif tag == "circle":
            commands = [("Circle", number(el.get("cx")), number(el.get("cy")), number(el.get("r")))]
        elif tag == "line":
            commands = [
                ("MoveTo", number(el.get("x1")), number(el.get("y1"))),
                ("LineTo", number(el.get("x2")), number(el.get("y2"))),
            ]
        elif tag == "rect":
            commands = rect_commands(el)
        elif tag == "polyline":
            commands = points_commands(el.get("points", ""), False)
        elif tag == "polygon":
            commands = points_commands(el.get("points", ""), True)
        if commands:
            nodes.append((style(root, el), commands))
        for child in el:
            visit(child)

    for child in root:
        visit(child)
    return nodes


def kotlin_float(value):
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    if text == "-0":
        text = "0"
    return f"{text}f"


def kotlin_string(value):
    return value.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"").replace("$", "${'$'}")


def pascal_name(name):
    return "".join(part.capitalize() for part in name.split("-"))


def line_cap(value):
    return {
        "butt": "SharedIconLineCap.Butt",
        "round": "SharedIconLineCap.Round",
        "square": "SharedIconLineCap.Square",
    }.get(value, "SharedIconLineCap.Round")


def line_join(value):
    return {
        "miter": "SharedIconLineJoin.Miter",
        "round": "SharedIconLineJoin.Round",
        "bevel": "SharedIconLineJoin.Bevel",
    }.get(value, "SharedIconLineJoin.Round")


def command_expr(command):
    name = command[0]
    values = ", ".join(kotlin_float(v) for v in command[1:])
    if name == "Close":
        return "SharedIconCommand.Close"
    return f"SharedIconCommand.{name}({values})"


def node_expr(node):
    style_attrs, commands = node
    command_list = ", ".join(command_expr(command) for command in commands)
    return (
        "SharedIconNode("
        f"commands = listOf({command_list}), "
        f"fill = {repr_nullable(style_attrs['fill'])}, "
        f"stroke = {repr_nullable(style_attrs['stroke'])}, "
        f"strokeWidth = {kotlin_float(float(style_attrs['stroke-width']))}, "
        f"strokeLineCap = {line_cap(style_attrs['stroke-linecap'])}, "
        f"strokeLineJoin = {line_join(style_attrs['stroke-linejoin'])}"
        ")"
    )


def repr_nullable(value):
    if value in (None, ""):
        return "null"
    return f"\"{kotlin_string(value)}\""


def generate(icons, version, output):
    entries = []
    for name in icons:
        svg = download_svg(name, version)
        nodes = parse_svg_nodes(svg)
        if not nodes:
            raise ValueError(f"{name} has no drawable nodes")
        prop = pascal_name(name)
        entries.append((name, prop, svg, nodes))

    lines = [
        "package ai.slock.compose.icons",
        "",
        "// Generated by scripts/lucide_to_shared_icons.py. Do not edit by hand.",
        "object LucideIcons {",
        f"    const val Version: String = \"{version}\"",
        "",
    ]
    for name, prop, svg, nodes in entries:
        node_list = ",\n            ".join(node_expr(node) for node in nodes)
        lines.extend([
            f"    val {prop}: SharedIcon = SharedIcon(",
            f"        name = \"{name}\",",
            f"        displayName = \"Lucide{prop}\",",
            f"        rawSvg = \"\"\"{kotlin_string(svg)}\"\"\",",
            "        nodes = listOf(",
            f"            {node_list}",
            "        )",
            "    )",
            "",
        ])
    all_props = ", ".join(prop for _, prop, _, _ in entries)
    lines.extend([
        f"    val All: List<SharedIcon> = listOf({all_props})",
        "",
        "    private val iconsByName: Map<String, SharedIcon> = All.associateBy { it.name }",
        "",
        "    fun byName(name: String): SharedIcon? = iconsByName[name]",
        "}",
        "",
    ])
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w", encoding="utf-8") as file:
        file.write("\n".join(lines))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("icons", nargs="*", help="Lucide icon names. Defaults to Slock's shared set.")
    parser.add_argument("--version", default=DEFAULT_VERSION)
    parser.add_argument(
        "--output",
        default=os.path.join(
            os.path.dirname(os.path.abspath(__file__)),
            "..",
            "compose",
            "shared",
            "src",
            "commonMain",
            "kotlin",
            "ai",
            "slock",
            "compose",
            "icons",
            "LucideIcons.kt",
        ),
    )
    args = parser.parse_args()
    generate(args.icons or DEFAULT_ICONS, args.version, os.path.abspath(args.output))
    print(f"Generated {len(args.icons or DEFAULT_ICONS)} icons -> {os.path.abspath(args.output)}")


if __name__ == "__main__":
    main()
