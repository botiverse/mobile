#!/usr/bin/env python3
"""
Generate Slock Lucide assets from one manifest.

Outputs by default:
- Android Compose ImageVector catalog
- OHOS raw SVG resources and Path registry
- Shared Kuikly icon name registry
- iOS asset catalog SVG imagesets
- Shared/OHOS/iOS bundled brand SVG assets used by assets:// image URLs

Requirements for Android generation:
  brew install s2c   (dev-tonholo/svg-to-compose tap)
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import urllib.request
from xml.etree import ElementTree as ET


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
DEFAULT_VERSION = "1.14.0"
DEFAULT_TARGETS = "android,ohos,ios,shared,brand-assets"
DEFAULT_LUCIDE_CACHE_DIR = os.path.join(os.path.expanduser("~"), ".cache", "slock-lucide")
LUCIDE_REPO_URL = "https://github.com/lucide-icons/lucide.git"
LUCIDE_RAW_URL = "https://raw.githubusercontent.com/lucide-icons/lucide"
LUCIDE_API_URL = "https://api.github.com/repos/lucide-icons/lucide"
SVG_NS = "http://www.w3.org/2000/svg"
NORMALIZED_VIEWPORT = 24.0
BRAND_ASSET_SOURCE_DIR = os.path.join(SCRIPT_DIR, "assets", "brand")
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

LUCIDE_ICONS = [
    "activity",
    "align-horizontal-space-around",
    "app-window",
    "archive",
    "arrow-left",
    "arrow-up-down",
    "at-sign",
    "ban",
    "badge-info",
    "bell",
    "bell-off",
    "bell-ring",
    "blocks",
    "bookmark",
    "bookmark-minus",
    "bot",
    "bug",
    "building-2",
    "calendar",
    "chart-bar",
    "check",
    "chevron-down",
    "chevron-left",
    "chevron-right",
    "chevron-up",
    "circle",
    "circle-arrow-up",
    "circle-check",
    "circle-check-big",
    "circle-dashed",
    "circle-user",
    "clipboard",
    "clipboard-check",
    "clipboard-list",
    "clock",
    "clock-3",
    "columns-3",
    "copy",
    "credit-card",
    "download",
    "ellipsis",
    "menu",
    "eraser",
    "external-link",
    "eye",
    "eye-off",
    "file",
    "file-archive",
    "file-axis-3d",
    "file-badge",
    "file-box",
    "file-braces",
    "file-braces-corner",
    "file-chart-column",
    "file-chart-column-increasing",
    "file-chart-line",
    "file-chart-pie",
    "file-check",
    "file-check-corner",
    "file-code",
    "file-code-corner",
    "file-cog",
    "file-diff",
    "file-digit",
    "file-down",
    "file-exclamation-point",
    "file-headphone",
    "file-heart",
    "file-image",
    "file-input",
    "file-key",
    "file-lock",
    "file-minus",
    "file-minus-corner",
    "file-music",
    "file-output",
    "file-pen",
    "file-pen-line",
    "file-play",
    "file-plus",
    "file-plus-corner",
    "file-question-mark",
    "file-scan",
    "file-search",
    "file-search-corner",
    "file-signal",
    "file-sliders",
    "file-spreadsheet",
    "file-stack",
    "file-symlink",
    "file-terminal",
    "file-text",
    "file-type",
    "file-type-corner",
    "file-up",
    "file-user",
    "file-video-camera",
    "file-volume",
    "file-x",
    "file-x-corner",
    "files",
    "flag",
    "film",
    "flame",
    "folder",
    "folder-open",
    "funnel",
    "git-branch",
    "globe",
    "grip-vertical",
    "hash",
    "heart",
    "house",
    "image",
    "image-plus",
    "inbox",
    "info",
    "key",
    "key-round",
    "languages",
    "layout-list",
    "link",
    "link-2",
    "list-tree",
    "list-todo",
    "lock",
    "log-in",
    "log-out",
    "loader",
    "loader-circle",
    "mail",
    "mail-open",
    "mail-plus",
    "message-circle",
    "message-circle-off",
    "message-circle-plus",
    "message-square",
    "message-square-plus",
    "monitor",
    "monitor-x",
    "package-check",
    "package",
    "paperclip",
    "panel-bottom-open",
    "pause",
    "pencil",
    "pin",
    "pin-off",
    "play",
    "plus",
    "presentation",
    "refresh-cw",
    "rotate-ccw",
    "rotate-cw",
    "route",
    "save",
    "search",
    "send",
    "settings",
    "server",
    "share",
    "share-2",
    "sheet",
    "shield",
    "shield-check",
    "sliders-horizontal",
    "smartphone",
    "sparkles",
    "square",
    "square-check-big",
    "store",
    "terminal",
    "triangle-alert",
    "trash",
    "trash-2",
    "type",
    "unlink",
    "upload",
    "user",
    "user-minus",
    "user-plus",
    "users",
    "users-round",
    "volume-x",
    "wand",
    "waypoints",
    "x",
]

BRAND_SVGS = {
    "slock-icon": os.path.join(
        BRAND_ASSET_SOURCE_DIR,
        "slock-icon.svg",
    ),
    "slock-lockup": os.path.join(
        REPO_ROOT,
        "compose",
        "androidApp",
        "src",
        "main",
        "assets",
        "brand",
        "logo-lockup-dark.svg",
    ),
}

BRAND_ASSET_SVGS = {
    "raft-logo": [
        os.path.join(
            BRAND_ASSET_SOURCE_DIR,
            "raft-logo.svg",
        ),
        os.path.join(
            REPO_ROOT,
            "compose",
            "ohosApp",
            "entry",
            "src",
            "main",
            "resources",
            "resfile",
            "lucide",
            "raft-logo.svg",
        ),
    ],
    "slock-icon": [
        os.path.join(
            BRAND_ASSET_SOURCE_DIR,
            "slock-icon.svg",
        ),
        os.path.join(
            REPO_ROOT,
            "compose",
            "ohosApp",
            "entry",
            "src",
            "main",
            "resources",
            "resfile",
            "lucide",
            "slock-icon.svg",
        ),
    ],
}

ANDROID_FILLED_ICON_VARIANTS = {
    "bookmark": "filled-bookmark",
}

DEFAULT_ANDROID_OUTPUT = os.path.join(
    REPO_ROOT,
    "compose",
    "androidApp",
    "src",
    "main",
    "java",
    "build",
    "raft",
    "app",
    "ui",
    "icons",
    "LucideIcons.kt",
)
DEFAULT_OHOS_PATH_OUTPUT = os.path.join(
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
DEFAULT_OHOS_RESFILE_OUTPUT = os.path.join(
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
DEFAULT_SHARED_CATALOG_OUTPUT = os.path.join(
    REPO_ROOT,
    "compose",
    "shared",
    "src",
    "commonMain",
    "kotlin",
    "build",
    "raft",
    "app",
    "SlockLucideIconCatalog.kt",
)
DEFAULT_SHARED_ASSET_OUTPUT = os.path.join(
    REPO_ROOT,
    "compose",
    "shared",
    "src",
    "commonMain",
    "assets",
    "lucide",
)
DEFAULT_IOS_ASSET_CATALOG = os.path.join(
    REPO_ROOT,
    "compose",
    "iosApp",
    "iosApp",
    "Assets.xcassets",
)

_SVG_CACHE = {}


def normalize_icon_name(value):
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", value.strip())
    return value.replace("_", "-").lower()


def pascal_name(name):
    return "".join(part.capitalize() for part in name.split("-"))


def kotlin_string(value):
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def ets_string(value):
    return value.replace("\\", "\\\\").replace("'", "\\'")


def git_output(args, cwd=None):
    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed:\n{result.stderr.strip()}")
    return result.stdout.strip()


def resolve_lucide_commit(version):
    url = f"{LUCIDE_API_URL}/commits/{version}"
    req = urllib.request.Request(url, headers={"User-Agent": "slock-lucide-generator"})
    with urllib.request.urlopen(req, timeout=30) as response:
        data = json.loads(response.read().decode("utf-8"))
    commit_sha = data.get("sha")
    if not commit_sha:
        raise RuntimeError(f"Unable to resolve Lucide version '{version}' to a commit SHA")
    return commit_sha


def checkout_head(repo_dir):
    return git_output(["rev-parse", "HEAD"], cwd=repo_dir)


def checkout_matches_version(repo_dir, version):
    try:
        exact_tag = git_output(["describe", "--tags", "--exact-match", "HEAD"], cwd=repo_dir)
        return exact_tag == version
    except RuntimeError:
        return False


def verify_lucide_checkout(repo_dir, version, expected_commit=None):
    icons_dir = os.path.join(repo_dir, "icons")
    if not os.path.isdir(icons_dir):
        raise RuntimeError(f"Lucide checkout missing icons directory: {icons_dir}")
    git_dir = os.path.join(repo_dir, ".git")
    if os.path.isdir(git_dir) and expected_commit:
        actual = checkout_head(repo_dir)
        if actual != expected_commit:
            raise RuntimeError(
                f"Lucide checkout {repo_dir} is at {actual}, expected {expected_commit} for version {version}"
            )


def find_cached_lucide_checkout(version, cache_dir):
    root = os.path.abspath(os.path.expanduser(cache_dir))
    if not os.path.isdir(root):
        return None
    for name in sorted(os.listdir(root)):
        path = os.path.join(root, name)
        if not name.startswith("lucide-") or not os.path.isdir(path):
            continue
        try:
            verify_lucide_checkout(path, version)
        except RuntimeError:
            continue
        if os.path.isdir(os.path.join(path, ".git")) and checkout_matches_version(path, version):
            return path
    return None


def ensure_lucide_checkout(version, cache_dir):
    cached = find_cached_lucide_checkout(version, cache_dir)
    if cached:
        return cached

    expected_commit = resolve_lucide_commit(version)
    checkout_dir = os.path.join(os.path.abspath(os.path.expanduser(cache_dir)), f"lucide-{expected_commit}")
    if os.path.isdir(checkout_dir):
        verify_lucide_checkout(checkout_dir, version, expected_commit)
        return checkout_dir

    os.makedirs(os.path.dirname(checkout_dir), exist_ok=True)
    tmp_dir = f"{checkout_dir}.tmp"
    if os.path.exists(tmp_dir):
        shutil.rmtree(tmp_dir)
    git_output(["clone", "--depth", "1", "--branch", version, LUCIDE_REPO_URL, tmp_dir])
    verify_lucide_checkout(tmp_dir, version, expected_commit)
    os.replace(tmp_dir, checkout_dir)
    return checkout_dir


def resolve_lucide_source(version, cache_dir, repo_dir=None):
    if repo_dir:
        resolved = os.path.abspath(os.path.expanduser(repo_dir))
        expected_commit = None
        if os.path.isdir(os.path.join(resolved, ".git")) and not checkout_matches_version(resolved, version):
            expected_commit = resolve_lucide_commit(version)
        verify_lucide_checkout(resolved, version, expected_commit)
        return resolved
    return ensure_lucide_checkout(version, cache_dir)


def list_all_lucide_icons(lucide_source_dir):
    icons_dir = os.path.join(lucide_source_dir, "icons")
    return sorted(
        os.path.splitext(name)[0]
        for name in os.listdir(icons_dir)
        if name.endswith(".svg") and os.path.isfile(os.path.join(icons_dir, name))
    )


def read_lucide_svg(name, version, fallback_dir=None, lucide_source_dir=None):
    normalized = normalize_icon_name(name)
    cache_key = (version, normalized, lucide_source_dir or "")
    if cache_key in _SVG_CACHE:
        return _SVG_CACHE[cache_key]

    if lucide_source_dir:
        source_path = os.path.join(lucide_source_dir, "icons", f"{normalized}.svg")
        if os.path.exists(source_path):
            with open(source_path, "r", encoding="utf-8") as file:
                svg = file.read().strip()
                _SVG_CACHE[cache_key] = svg
                return svg

    fallback_path = os.path.join(fallback_dir or DEFAULT_OHOS_RESFILE_OUTPUT, f"{normalized}.svg")
    if os.path.exists(fallback_path):
        with open(fallback_path, "r", encoding="utf-8") as file:
            svg = file.read().strip()
            _SVG_CACHE[cache_key] = svg
            return svg

    url = f"{LUCIDE_RAW_URL}/{version}/icons/{normalized}.svg"
    last_error = None
    for attempt in range(3):
        try:
            print(f"download {normalized}")
            req = urllib.request.Request(url, headers={"User-Agent": "slock-lucide-generator"})
            with urllib.request.urlopen(req, timeout=20) as response:
                svg = response.read().decode("utf-8").strip()
                _SVG_CACHE[cache_key] = svg
                return svg
        except Exception as error:
            last_error = error
            if attempt < 2:
                time.sleep(1 + attempt)

    raise RuntimeError(f"Unable to download Lucide icon '{normalized}' from {url}") from last_error


def prefetch_lucide_svgs(icons, version, fallback_dir, lucide_source_dir, workers):
    if workers <= 1:
        return
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {
            executor.submit(read_lucide_svg, icon, version, fallback_dir, lucide_source_dir): icon
            for icon in icons
        }
        for future in as_completed(futures):
            icon = futures[future]
            try:
                future.result()
            except Exception as error:
                raise RuntimeError(f"Failed to prefetch Lucide icon '{icon}'") from error


def normalize_ohos_svg(svg_text):
    return svg_text.replace('stroke="currentColor"', 'stroke="#000000"')


def svg_for_path_parse(svg_text):
    svg_text = re.sub(r"<defs>.*?</defs>", "", svg_text, flags=re.DOTALL)
    if not re.search(r"<svg[^>]*\sstroke=", svg_text):
        svg_text = re.sub(r"<svg", '<svg stroke="none"', svg_text, count=1)
    return svg_text


def circle_to_path(cx, cy, radius):
    cx, cy, radius = float(cx), float(cy), float(radius)
    return (
        f"M {cx - radius} {cy} "
        f"A {radius} {radius} 0 1 0 {cx + radius} {cy} "
        f"A {radius} {radius} 0 1 0 {cx - radius} {cy} Z"
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


def rect_to_path(x, y, width, height, rx=0.0, ry=0.0):
    x, y, width, height = float(x), float(y), float(width), float(height)
    rx, ry = float(rx), float(ry)
    if rx == 0 and ry == 0:
        return f"M {x} {y} H {x + width} V {y + height} H {x} Z"
    rx = min(rx, width / 2)
    ry = min(ry, height / 2)
    return (
        f"M {x + rx} {y} H {x + width - rx} "
        f"A {rx} {ry} 0 0 1 {x + width} {y + ry} "
        f"V {y + height - ry} "
        f"A {rx} {ry} 0 0 1 {x + width - rx} {y + height} "
        f"H {x + rx} "
        f"A {rx} {ry} 0 0 1 {x} {y + height - ry} "
        f"V {y + ry} "
        f"A {rx} {ry} 0 0 1 {x + rx} {y} Z"
    )


def points_to_path(points, close=False):
    nums = re.findall(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", points)
    coords = list(map(float, nums))
    pairs = [(coords[index], coords[index + 1]) for index in range(0, len(coords) - 1, 2)]
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


def android_filled_variant_svg(icon_name, svg_text):
    normalized = normalize_icon_name(icon_name)
    if normalized != "bookmark":
        raise ValueError(f"Unsupported filled Android icon variant: {icon_name}")

    ET.register_namespace("", SVG_NS)
    root = ET.fromstring(svg_text)
    root.set("fill", "currentColor")
    root.set("stroke", "currentColor")
    for element in root.iter():
        if strip_ns(element.tag) == "path":
            element.set("fill", "currentColor")
            element.set("stroke", "currentColor")
    return ET.tostring(root, encoding="unicode")


def resolve_paint(value):
    if not value:
        return None
    normalized = value.strip()
    if normalized in ("none", "transparent"):
        return None
    if normalized == "currentColor":
        return "currentColor"
    return normalized


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

    def visit(element, inherited):
        tag = strip_ns(element.tag)
        ctx = {key: element.get(key, inherited[key]) for key in inherited}
        path_data = None
        if tag == "path":
            path_data = element.get("d", "")
        elif tag == "circle":
            path_data = circle_to_path(element.get("cx", 0), element.get("cy", 0), element.get("r", 0))
        elif tag == "ellipse":
            path_data = ellipse_to_path(
                element.get("cx", 0), element.get("cy", 0), element.get("rx", 0), element.get("ry", 0)
            )
        elif tag == "line":
            path_data = line_to_path(
                element.get("x1", 0), element.get("y1", 0), element.get("x2", 0), element.get("y2", 0)
            )
        elif tag == "rect":
            rx = element.get("rx", element.get("ry", 0))
            ry = element.get("ry", rx)
            path_data = rect_to_path(
                element.get("x", 0),
                element.get("y", 0),
                element.get("width", 0),
                element.get("height", 0),
                rx,
                ry,
            )
        elif tag == "polyline":
            path_data = points_to_path(element.get("points", ""), close=False)
        elif tag == "polygon":
            path_data = points_to_path(element.get("points", ""), close=True)

        if path_data:
            entry = {"pathData": path_data}
            fill_paint = resolve_paint(ctx["fill"])
            stroke_paint = resolve_paint(ctx["stroke"])
            if fill_paint:
                entry["fillPaint"] = fill_paint
            if stroke_paint:
                entry["strokePaint"] = stroke_paint
                entry["strokeWidth"] = ctx["stroke-width"]
                entry["strokeLineCap"] = ctx["stroke-linecap"]
                entry["strokeLineJoin"] = ctx["stroke-linejoin"]
            result.append(entry)
        for child in element:
            visit(child, ctx)

    for child in root:
        visit(child, svg_defaults)
    return result


def run_s2c(svg_path, out_path, package):
    result = subprocess.run(
        [
            "s2c",
            "-o",
            out_path,
            "-p",
            package,
            "-t",
            f"{package}.LucideIcons",
            "--no-preview",
            "--minified",
            "-opt=false",
            "--kmp=false",
            svg_path,
        ],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"s2c failed for {svg_path}:\n{result.stderr}")


def extract_builder_block(kt_content, prop):
    m_start = re.search(r"^\s*return (ImageVector\.Builder\()", kt_content, re.MULTILINE)
    if not m_start:
        raise ValueError(f"No ImageVector.Builder found for {prop}")

    m_end = re.search(r"(\.build\(\))\.also \{ \w+ = it \}", kt_content)
    if not m_end:
        raise ValueError(f"No .build().also found for {prop}")

    block = kt_content[m_start.start(1): m_end.end(1)]
    return re.sub(r'(name\s*=\s*")[^"]*(")', rf'\g<1>Lucide{prop}\2', block, count=1)


def collect_imports(kt_content):
    return [line for line in kt_content.splitlines() if line.startswith("import ")]


def android_vector_entry(icon_file_name, prop, svg, tmpdir, package):
    svg_path = os.path.join(tmpdir, f"{icon_file_name}.svg")
    with open(svg_path, "w", encoding="utf-8") as file:
        file.write(svg)

    out_path = os.path.join(tmpdir, f"{prop}.kt")
    run_s2c(svg_path, out_path, package)
    with open(out_path, "r", encoding="utf-8") as file:
        kt_content = file.read()

    return collect_imports(kt_content), extract_builder_block(kt_content, prop)


def generate_android(icons, version, output, fallback_dir, lucide_source_dir):
    package = "build.raft.app.ui.icons"
    entries = []
    variant_entries = []
    all_imports = set()

    with tempfile.TemporaryDirectory() as tmpdir:
        for name in icons:
            prop = pascal_name(name)
            svg = read_lucide_svg(name, version, fallback_dir=fallback_dir, lucide_source_dir=lucide_source_dir)
            imports, block = android_vector_entry(name, prop, svg, tmpdir, package)
            all_imports.update(imports)
            entries.append((name, prop, block))

            variant_name = ANDROID_FILLED_ICON_VARIANTS.get(name)
            if variant_name:
                variant_prop = pascal_name(variant_name)
                variant_imports, variant_block = android_vector_entry(
                    variant_name,
                    variant_prop,
                    android_filled_variant_svg(name, svg),
                    tmpdir,
                    package,
                )
                all_imports.update(variant_imports)
                variant_entries.append((variant_name, variant_prop, variant_block))

    sorted_imports = sorted(all_imports)
    sorted_imports.append("import java.util.Locale")

    lines = [
        f"package {package}",
        "",
        *sorted_imports,
        "",
        "// Generated by scripts/generate_lucide_icons.py. Do not edit by hand.",
        "object LucideIcons {",
        f'    const val Version: String = "{version}"',
        "",
    ]
    for _, prop, block in [*entries, *variant_entries]:
        indented = "\n".join("        " + line if line.strip() else "" for line in block.splitlines())
        lines.extend([f"    val {prop}: ImageVector by lazy {{", indented, "    }", ""])

    name_entries = ", ".join(f'"{name}"' for name, _, _ in entries)
    lines.extend(
        [
            f"    val Names: List<String> = listOf({name_entries})",
            "",
            "    fun byName(name: String): ImageVector? {",
            "        return when (name.toIconKey()) {",
        ]
    )
    for name, prop, _ in entries:
        lines.append(f'            "{name}" -> {prop}')
    lines.extend(
        [
            "            else -> null",
            "        }",
            "    }",
            "",
            "    private fun String.toIconKey(): String {",
            "        val normalized = trim()",
            "        if (normalized.isEmpty()) {",
            "            return normalized",
            "        }",
            "        return normalized",
            "            .replace(Regex(\"([a-z0-9])([A-Z])\"), \"$1-$2\")",
            "            .replace('_', '-')",
            "            .lowercase(Locale.US)",
            "    }",
            "",
            f"    val All: List<ImageVector> = listOf({', '.join(prop for _, prop, _ in entries)})",
            "}",
            "",
        ]
    )

    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w", encoding="utf-8") as file:
        file.write("\n".join(lines))
    if variant_entries:
        print(f"Generated {len(entries)} Android icons + {len(variant_entries)} variants -> {output}")
    else:
        print(f"Generated {len(entries)} Android icons -> {output}")


def parse_dimension(raw):
    if not raw:
        return None
    match = re.search(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", raw)
    return float(match.group(0)) if match else None


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
    try:
        return float(path.get("strokeWidth", "0"))
    except ValueError:
        return 0.0


def format_number(value):
    if abs(value) < 0.000001:
        value = 0.0
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return text or "0"


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
        result.extend(transform_command_values(command, values, scale, offset_x, offset_y))
        index += arity
        if command == "M":
            command = "L"
        elif command == "m":
            command = "l"
    return " ".join(result), viewport_width * scale, viewport_height * scale


def ohos_entry_expr(path, viewport_width, viewport_height):
    fill_paint = path.get("fillPaint") or "none"
    stroke_paint = path.get("strokePaint") or "none"
    has_fill = fill_paint != "none"
    has_stroke = stroke_paint != "none"
    commands, normalized_width, normalized_height = normalize_path_data(
        path["pathData"], viewport_width, viewport_height
    )
    return (
        "    {\n"
        f"      commands: '{ets_string(commands)}',\n"
        f"      fillPaint: '{ets_string(fill_paint)}',\n"
        f"      strokePaint: '{ets_string(stroke_paint)}',\n"
        f"      fillOpacity: {1 if has_fill else 0},\n"
        f"      strokeOpacity: {1 if has_stroke else 0},\n"
        f"      strokeWidth: {stroke_width(path) * (NORMALIZED_VIEWPORT / max(viewport_width, viewport_height)):g},\n"
        f"      strokeLineCap: {line_cap_expr(path.get('strokeLineCap'))},\n"
        f"      strokeLineJoin: {line_join_expr(path.get('strokeLineJoin'))},\n"
        f"      viewportWidth: {normalized_width:g},\n"
        f"      viewportHeight: {normalized_height:g},\n"
        "    }"
    )


def read_ohos_svg(icon_name, version, resfile_output, lucide_source_dir):
    brand_path = BRAND_SVGS.get(icon_name)
    if brand_path:
        if os.path.exists(brand_path):
            with open(brand_path, "r", encoding="utf-8") as file:
                return file.read().strip()
        fallback_path = os.path.join(resfile_output, f"{icon_name}.svg")
        if os.path.exists(fallback_path):
            with open(fallback_path, "r", encoding="utf-8") as file:
                return file.read().strip()
        raise RuntimeError(f"Brand SVG '{icon_name}' is missing at {brand_path} and {fallback_path}")
    return read_lucide_svg(icon_name, version, fallback_dir=resfile_output, lucide_source_dir=lucide_source_dir)


def read_brand_asset_svg(asset_name):
    candidates = BRAND_ASSET_SVGS.get(asset_name)
    if not candidates:
        raise RuntimeError(f"Unknown brand asset '{asset_name}'")
    for path in candidates:
        if path and os.path.exists(path):
            with open(path, "r", encoding="utf-8") as file:
                return file.read().strip() + "\n"
    raise RuntimeError(
        f"Brand asset SVG '{asset_name}' is missing. Checked: {', '.join(candidates)}"
    )


def ios_imageset_contents(filename, template=False):
    properties = {
        "preserves-vector-representation": True,
    }
    if template:
        properties["template-rendering-intent"] = "template"
    return json.dumps(
        {
            "images": [
                {
                    "filename": filename,
                    "idiom": "universal",
                }
            ],
            "info": {
                "author": "xcode",
                "version": 1,
            },
            "properties": properties,
        },
        indent=2,
    ) + "\n"


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as file:
        file.write(text)


def generate_brand_assets(shared_asset_output, ohos_resfile_output, ios_asset_catalog):
    shared_asset_output = os.path.abspath(shared_asset_output)
    ohos_resfile_output = os.path.abspath(ohos_resfile_output)
    ios_asset_catalog = os.path.abspath(ios_asset_catalog)
    for asset_name in BRAND_ASSET_SVGS.keys():
        svg_text = read_brand_asset_svg(asset_name)
        filename = f"{asset_name}.svg"
        write_text(os.path.join(shared_asset_output, filename), svg_text)
        write_text(os.path.join(ohos_resfile_output, filename), svg_text)

        imageset_dir = os.path.join(ios_asset_catalog, f"lucide-{asset_name}.imageset")
        write_text(os.path.join(imageset_dir, filename), svg_text)
        write_text(os.path.join(imageset_dir, "Contents.json"), ios_imageset_contents(filename))
    print(
        f"Generated {len(BRAND_ASSET_SVGS)} brand SVG assets -> "
        f"{shared_asset_output}, {ohos_resfile_output}, {ios_asset_catalog}"
    )


def generate_ios(icons, version, ios_asset_catalog, lucide_source_dir):
    ios_asset_catalog = os.path.abspath(ios_asset_catalog)
    for icon in icons:
        asset_name = normalize_icon_name(icon)
        svg_text = read_lucide_svg(
            asset_name,
            version,
            fallback_dir=None,
            lucide_source_dir=lucide_source_dir,
        ).strip().replace('stroke="currentColor"', 'stroke="#000000"')
        filename = f"{asset_name}.svg"
        imageset_dir = os.path.join(ios_asset_catalog, f"lucide-{asset_name}.imageset")
        write_text(os.path.join(imageset_dir, filename), svg_text)
        write_text(
            os.path.join(imageset_dir, "Contents.json"),
            ios_imageset_contents(filename, template=True),
        )
    validate_ios_assets(icons, ios_asset_catalog)
    print(f"Generated {len(icons)} iOS Lucide SVG assets -> {ios_asset_catalog}")


def validate_ios_assets(icons, ios_asset_catalog):
    missing = []
    for icon in icons:
        asset_name = normalize_icon_name(icon)
        filename = f"{asset_name}.svg"
        imageset_dir = os.path.join(ios_asset_catalog, f"lucide-{asset_name}.imageset")
        contents_path = os.path.join(imageset_dir, "Contents.json")
        svg_path = os.path.join(imageset_dir, filename)
        if not os.path.isfile(contents_path) or not os.path.isfile(svg_path):
            missing.append(asset_name)
            continue
        with open(contents_path, encoding="utf-8") as file:
            contents = json.load(file)
        filenames = {
            image.get("filename")
            for image in contents.get("images", [])
            if isinstance(image, dict)
        }
        if filename not in filenames:
            missing.append(asset_name)
    if missing:
        raise RuntimeError(
            "iOS Lucide asset catalog is incomplete for: " + ", ".join(sorted(missing))
        )


def generate_ohos(icons, version, output, resfile_output, lucide_source_dir):
    all_icons = [*BRAND_SVGS.keys(), *icons]
    cases = []
    os.makedirs(resfile_output, exist_ok=True)
    for icon in all_icons:
        svg_text = read_ohos_svg(icon, version, resfile_output, lucide_source_dir)
        with open(os.path.join(resfile_output, f"{icon}.svg"), "w", encoding="utf-8") as file:
            file.write(normalize_ohos_svg(svg_text))
        viewport_width, viewport_height = svg_viewbox(svg_text)
        paths = parse_svg(svg_for_path_parse(svg_text))
        entries = ",\n".join(ohos_entry_expr(path, viewport_width, viewport_height) for path in paths)
        cases.append((icon, entries))

    lines = [
        "// Generated by scripts/generate_lucide_icons.py. Do not edit by hand.",
        "",
        "export interface SlockLucidePathEntry {",
        "  commands: string;",
        "  fillPaint: string;",
        "  strokePaint: string;",
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
        *[f"  '{icon}'," for icon, _ in cases],
        "];",
        "",
        "export function slockLucidePathEntries(name: string): SlockLucidePathEntry[] {",
        "  switch (normalizeSlockLucidePathIconName(name)) {",
    ]
    for icon, entries in cases:
        lines.extend([f"    case '{icon}':", "      return [", entries, "      ];"])
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
    print(f"Generated {len(cases)} OHOS icons -> {output}")


def generate_shared_catalog(icons, output):
    brand_entries = ", ".join(f'"{name}"' for name in BRAND_SVGS.keys())
    lines = [
        "package build.raft.app",
        "",
        "import kotlinx.serialization.KSerializer",
        "import kotlinx.serialization.Serializable",
        "import kotlinx.serialization.descriptors.PrimitiveKind",
        "import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor",
        "import kotlinx.serialization.descriptors.SerialDescriptor",
        "import kotlinx.serialization.encoding.Decoder",
        "import kotlinx.serialization.encoding.Encoder",
        "",
        "// Generated by scripts/generate_lucide_icons.py. Do not edit by hand.",
        "@Serializable(with = SlockLucideIconNameWireSerializer::class)",
        "enum class SlockLucideIconName(val wireName: String) {",
    ]
    for index, icon in enumerate(icons):
        suffix = "," if index < len(icons) - 1 else ";"
        lines.append(f'    {pascal_name(icon)}("{icon}"){suffix}')
    lines.extend(
        [
            "",
            "    companion object {",
            "        fun fromWireName(value: String): SlockLucideIconName? =",
            "            entries.firstOrNull { it.wireName == value }",
            "    }",
            "}",
            "",
            "object SlockLucideIconNameWireSerializer : KSerializer<SlockLucideIconName> {",
            "    override val descriptor: SerialDescriptor =",
            '        PrimitiveSerialDescriptor("SlockLucideIconName", PrimitiveKind.STRING)',
            "",
            "    override fun serialize(encoder: Encoder, value: SlockLucideIconName) {",
            "        encoder.encodeString(value.wireName)",
            "    }",
            "",
            "    override fun deserialize(decoder: Decoder): SlockLucideIconName =",
            "        SlockLucideIconName.fromWireName(decoder.decodeString()) ?: SlockLucideIconName.Settings",
            "}",
            "",
            "internal object SlockLucideIcons {",
            "    val Names: List<String> = SlockLucideIconName.entries.map { it.wireName }",
            "}",
            "",
            "internal object SlockLucideIconCompareSet {",
            f"    val Names: List<String> = listOf({brand_entries}) + SlockLucideIcons.Names",
            "}",
            "",
        ]
    )
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w", encoding="utf-8") as file:
        file.write("\n".join(lines))
    print(f"Generated shared icon catalog -> {output}")


def parse_targets(value):
    targets = {part.strip().lower() for part in value.split(",") if part.strip()}
    valid = {"android", "ohos", "ios", "shared", "brand-assets"}
    unknown = targets - valid
    if unknown:
        raise argparse.ArgumentTypeError(f"Unknown target(s): {', '.join(sorted(unknown))}")
    if not targets:
        raise argparse.ArgumentTypeError("At least one target is required")
    return targets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("icons", nargs="*", help="Lucide icon names. Defaults to the Slock manifest in this file.")
    parser.add_argument("--version", default=DEFAULT_VERSION)
    parser.add_argument("--targets", type=parse_targets, default=parse_targets(DEFAULT_TARGETS))
    parser.add_argument("--android-output", default=DEFAULT_ANDROID_OUTPUT)
    parser.add_argument("--ohos-output", default=DEFAULT_OHOS_PATH_OUTPUT)
    parser.add_argument("--ohos-resfile-output", default=DEFAULT_OHOS_RESFILE_OUTPUT)
    parser.add_argument("--shared-catalog-output", default=DEFAULT_SHARED_CATALOG_OUTPUT)
    parser.add_argument("--shared-asset-output", default=DEFAULT_SHARED_ASSET_OUTPUT)
    parser.add_argument("--ios-asset-catalog", default=DEFAULT_IOS_ASSET_CATALOG)
    parser.add_argument("--lucide-cache-dir", default=DEFAULT_LUCIDE_CACHE_DIR)
    parser.add_argument("--lucide-repo-dir", default=None)
    parser.add_argument("--download-workers", type=int, default=8)
    parser.add_argument("--all-lucide-icons", action="store_true")
    args = parser.parse_args()

    lucide_targets = args.targets & {"android", "ohos", "ios", "shared"}
    lucide_source_dir = None
    icons = []
    if lucide_targets:
        lucide_source_dir = resolve_lucide_source(args.version, args.lucide_cache_dir, args.lucide_repo_dir)
        if args.all_lucide_icons:
            icons = list_all_lucide_icons(lucide_source_dir)
        else:
            icons = [normalize_icon_name(icon) for icon in (args.icons or LUCIDE_ICONS)]
        prefetch_lucide_svgs(
            icons,
            args.version,
            os.path.abspath(args.ohos_resfile_output),
            lucide_source_dir,
            args.download_workers,
        )
    if "android" in args.targets:
        generate_android(
            icons,
            args.version,
            os.path.abspath(args.android_output),
            os.path.abspath(args.ohos_resfile_output),
            lucide_source_dir,
        )
    if "ohos" in args.targets:
        generate_ohos(
            icons,
            args.version,
            os.path.abspath(args.ohos_output),
            os.path.abspath(args.ohos_resfile_output),
            lucide_source_dir,
        )
    if "ios" in args.targets:
        generate_ios(
            icons,
            args.version,
            os.path.abspath(args.ios_asset_catalog),
            lucide_source_dir,
        )
    if "shared" in args.targets:
        generate_shared_catalog(icons, os.path.abspath(args.shared_catalog_output))
    if "brand-assets" in args.targets:
        generate_brand_assets(
            os.path.abspath(args.shared_asset_output),
            os.path.abspath(args.ohos_resfile_output),
            os.path.abspath(args.ios_asset_catalog),
        )


if __name__ == "__main__":
    main()
