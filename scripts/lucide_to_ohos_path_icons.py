#!/usr/bin/env python3
"""
Generate an OHOS ArkTS Path registry from the same Lucide SVG source set.

The existing OHOS icon path uses Image(...svg).supportSvg2(true). This script
keeps those SVG files as the left-side baseline and generates a Path-backed
registry for side-by-side clarity checks.
"""

import argparse
import os
import re
from xml.etree import ElementTree as ET

from lucide_to_android_compose_icons import parse_svg
from lucide_to_shared_icons import DEFAULT_ICONS, download_svg


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
DEFAULT_OUTPUT = os.path.join(
    REPO_ROOT,
    "compose",
    "ohosApp",
    "entry",
    "src",
    "main",
    "ets",
    "kuikly",
    "generated",
    "SlockLucidePathIcons.ets",
)
DEFAULT_RESFILE_OUTPUT = os.path.join(
    REPO_ROOT,
    "compose",
    "ohosApp",
    "entry",
    "src",
    "main",
    "resources",
    "resfile",
    "lucide",
)
BRAND_SVGS = {
    "slock-icon": os.path.join(
        REPO_ROOT,
        "third_party",
        "slock-source",
        "packages",
        "web",
        "public",
        "brand",
        "slock-icon.svg",
    ),
    "slock-lockup": os.path.join(
        REPO_ROOT,
        "app",
        "src",
        "main",
        "assets",
        "brand",
        "logo-lockup-dark.svg",
    ),
}
NORMALIZED_VIEWPORT = 24.0
PATH_TOKEN_RE = re.compile(r"[AaCcHhLlMmQqSsTtVvZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
PATH_COMMAND_ARITY = {
    "M": 2,
    "L": 2,
    "H": 1,
    "V": 1,
    "C": 6,
    "S": 4,
    "Q": 4,
    "T": 2,
    "A": 7,
    "Z": 0,
}


def ets_string(value):
    return value.replace("\\", "\\\\").replace("'", "\\'")


def normalize_svg_for_ohos(svg_text):
    return svg_text.replace('stroke="currentColor"', 'stroke="#000000"')


def svg_for_path_parse(svg_text):
    svg_text = re.sub(r"<defs>.*?</defs>", "", svg_text, flags=re.DOTALL)
    if not re.search(r"<svg[^>]*\sstroke=", svg_text):
        svg_text = re.sub(r"<svg", '<svg stroke="none"', svg_text, count=1)
    return svg_text


def normalize_icon_name(value):
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", value.strip())
    return value.replace("_", "-").lower()


def read_svg(icon_name, version, resfile_output):
    brand_path = BRAND_SVGS.get(icon_name)
    if brand_path:
        with open(brand_path, "r", encoding="utf-8") as file:
            svg_text = file.read().strip()
    else:
        existing_path = os.path.join(resfile_output, f"{icon_name}.svg")
        if os.path.exists(existing_path):
            with open(existing_path, "r", encoding="utf-8") as file:
                svg_text = file.read().strip()
        else:
            svg_text = download_svg(icon_name, version)
    return normalize_svg_for_ohos(svg_text)


def write_svg_baseline(icon_name, svg_text, resfile_output):
    os.makedirs(resfile_output, exist_ok=True)
    out_path = os.path.join(resfile_output, f"{icon_name}.svg")
    with open(out_path, "w", encoding="utf-8") as file:
        file.write(svg_text)


def svg_viewbox(svg_text):
    root = ET.fromstring(svg_text)
    view_box = root.get("viewBox")
    if view_box:
        nums = [float(part) for part in re.findall(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", view_box)]
        if len(nums) == 4 and nums[2] > 0 and nums[3] > 0:
            return nums[2], nums[3]
    width = parse_dimension(root.get("width")) or 24.0
    height = parse_dimension(root.get("height")) or 24.0
    return width, height


def parse_dimension(raw):
    if not raw:
        return None
    match = re.search(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", raw)
    return float(match.group(0)) if match else None


def line_cap_expr(value):
    return {
        "butt": "LineCapStyle.Butt",
        "round": "LineCapStyle.Round",
        "square": "LineCapStyle.Square",
    }.get(value, "LineCapStyle.Round")


def line_join_expr(value):
    return {
        "miter": "LineJoinStyle.Miter",
        "round": "LineJoinStyle.Round",
        "bevel": "LineJoinStyle.Bevel",
    }.get(value, "LineJoinStyle.Round")


def stroke_width(path):
    raw = path.get("strokeWidth", "0")
    try:
        return float(raw)
    except ValueError:
        return 0.0


def format_number(value):
    if abs(value) < 0.000001:
        value = 0.0
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return text or "0"


def normalize_path_data(path_data, viewport_width, viewport_height):
    scale = NORMALIZED_VIEWPORT / max(viewport_width, viewport_height)
    offset_x = (NORMALIZED_VIEWPORT - viewport_width * scale) / 2.0
    offset_y = (NORMALIZED_VIEWPORT - viewport_height * scale) / 2.0
    tokens = PATH_TOKEN_RE.findall(path_data)
    result = []
    index = 0
    command = None
    while index < len(tokens):
        token = tokens[index]
        if re.fullmatch(r"[AaCcHhLlMmQqSsTtVvZz]", token):
            command = token
            result.append(command)
            index += 1
            if command.upper() == "Z":
                command = None
            continue
        if command is None:
            result.append(token)
            index += 1
            continue

        arity = PATH_COMMAND_ARITY[command.upper()]
        if arity == 0 or index + arity > len(tokens):
            result.append(token)
            index += 1
            continue

        values = tokens[index:index + arity]
        transformed = transform_command_values(command, values, scale, offset_x, offset_y)
        result.extend(transformed)
        index += arity
        if command == "M":
            command = "L"
        elif command == "m":
            command = "l"
    return " ".join(result), viewport_width * scale, viewport_height * scale


def transform_command_values(command, values, scale, offset_x, offset_y):
    absolute = command.isupper()
    upper = command.upper()

    def transform_x(raw):
        value = float(raw) * scale
        return value + offset_x if absolute else value

    def transform_y(raw):
        value = float(raw) * scale
        return value + offset_y if absolute else value

    if upper in ("M", "L", "T"):
        return [format_number(transform_x(values[0])), format_number(transform_y(values[1]))]
    if upper == "H":
        value = float(values[0]) * scale
        if absolute:
            value += offset_x
        return [format_number(value)]
    if upper == "V":
        value = float(values[0]) * scale
        if absolute:
            value += offset_y
        return [format_number(value)]
    if upper in ("S", "Q"):
        return [
            format_number(transform_x(values[0])),
            format_number(transform_y(values[1])),
            format_number(transform_x(values[2])),
            format_number(transform_y(values[3])),
        ]
    if upper == "C":
        return [
            format_number(transform_x(values[0])),
            format_number(transform_y(values[1])),
            format_number(transform_x(values[2])),
            format_number(transform_y(values[3])),
            format_number(transform_x(values[4])),
            format_number(transform_y(values[5])),
        ]
    if upper == "A":
        return [
            format_number(float(values[0]) * scale),
            format_number(float(values[1]) * scale),
            values[2],
            values[3],
            values[4],
            format_number(transform_x(values[5])),
            format_number(transform_y(values[6])),
        ]
    return values


def entry_expr(path, viewport_width, viewport_height):
    has_fill = bool(path.get("fillColor"))
    has_stroke = bool(path.get("strokeColor"))
    commands, normalized_width, normalized_height = normalize_path_data(
        path["pathData"],
        viewport_width,
        viewport_height,
    )
    return (
        "    {\n"
        f"      commands: '{ets_string(commands)}',\n"
        f"      fillOpacity: {1 if has_fill else 0},\n"
        f"      strokeOpacity: {1 if has_stroke else 0},\n"
        f"      strokeWidth: {stroke_width(path) * (NORMALIZED_VIEWPORT / max(viewport_width, viewport_height)):g},\n"
        f"      strokeLineCap: {line_cap_expr(path.get('strokeLineCap'))},\n"
        f"      strokeLineJoin: {line_join_expr(path.get('strokeLineJoin'))},\n"
        f"      viewportWidth: {normalized_width:g},\n"
        f"      viewportHeight: {normalized_height:g},\n"
        "    }"
    )


def generate(icons, version, output, resfile_output):
    icons = [normalize_icon_name(icon) for icon in icons]
    cases = []
    for icon in icons:
        svg_text = read_svg(icon, version, resfile_output)
        write_svg_baseline(icon, svg_text, resfile_output)
        viewport_width, viewport_height = svg_viewbox(svg_text)
        paths = parse_svg(svg_for_path_parse(svg_text))
        entries = ",\n".join(entry_expr(path, viewport_width, viewport_height) for path in paths)
        cases.append((icon, entries))

    lines = [
        "// Generated by scripts/lucide_to_ohos_path_icons.py. Do not edit by hand.",
        "",
        "export interface SlockLucidePathEntry {",
        "  commands: string;",
        "  fillOpacity: number;",
        "  strokeOpacity: number;",
        "  strokeWidth: number;",
        "  strokeLineCap: LineCapStyle;",
        "  strokeLineJoin: LineJoinStyle;",
        "  viewportWidth: number;",
        "  viewportHeight: number;",
        "}",
        "",
        "export const SlockLucidePathIconNames: string[] = [",
        *[f"  '{icon}'," for icon in icons],
        "];",
        "",
        "export function slockLucidePathEntries(name: string): SlockLucidePathEntry[] {",
        "  switch (normalizeSlockLucidePathIconName(name)) {",
    ]
    for icon, entries in cases:
        lines.extend(
            [
                f"    case '{icon}':",
                "      return [",
                entries,
                "      ];",
            ]
        )
    lines.extend(
        [
            "    default:",
            "      return [];",
            "  }",
            "}",
            "",
            "function normalizeSlockLucidePathIconName(value: string): string {",
            "  if (value === null || value === undefined) {",
            "    return '';",
            "  }",
            "  return value",
            "    .trim()",
            "    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')",
            "    .replace(/_/g, '-')",
            "    .toLowerCase();",
            "}",
            "",
        ]
    )

    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w", encoding="utf-8") as file:
        file.write("\n".join(lines))
    print(f"Generated {len(icons)} OHOS path icons -> {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("icons", nargs="*", help="Icon names, defaults to brand + Slock Lucide set")
    parser.add_argument("--version", default="1.14.0")
    parser.add_argument("--output", default=DEFAULT_OUTPUT)
    parser.add_argument("--resfile-output", default=DEFAULT_RESFILE_OUTPUT)
    args = parser.parse_args()
    icons = args.icons or [*BRAND_SVGS.keys(), *DEFAULT_ICONS]
    generate(icons, args.version, os.path.abspath(args.output), os.path.abspath(args.resfile_output))


if __name__ == "__main__":
    main()
