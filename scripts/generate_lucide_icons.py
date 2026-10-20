#!/usr/bin/env python3
"""
Generate Slock Lucide assets from one manifest.

Outputs by default:
- Android Compose ImageVector catalog
- Shared Kuikly icon name registry
- iOS asset catalog SVG imagesets
- Shared/iOS bundled brand SVG assets used by assets:// image URLs

Requirements for Android generation:
  brew install s2c   (dev-tonholo/svg-to-compose tap)
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from decimal import Decimal, InvalidOperation
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
LUCIDE_SOURCE_COMMITS = {
    "1.14.0": "56e49f12166312f04af4cfd862621c93cf583979",
}
ANDROID_FLOAT_MAX = Decimal("3.4028234663852886e38")
ANDROID_FLOAT_MIN_POSITIVE = Decimal("1.401298464324817e-45")
DEFAULT_TARGETS = "android-path,ios,shared,brand-assets"
DEFAULT_LUCIDE_CACHE_DIR = os.path.join(os.path.expanduser("~"), ".cache", "slock-lucide")
LUCIDE_REPO_URL = "https://github.com/lucide-icons/lucide.git"
LUCIDE_RAW_URL = "https://raw.githubusercontent.com/lucide-icons/lucide"
LUCIDE_API_URL = "https://api.github.com/repos/lucide-icons/lucide"
SVG_NS = "http://www.w3.org/2000/svg"
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
    "blend",
    "blocks",
    "bookmark",
    "bookmark-minus",
    "bot",
    "bug",
    "building-2",
    "calendar",
    "camera",
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
    "code",
    "columns-3",
    "copy",
    "credit-card",
    "crop",
    "dices",
    "download",
    "ellipsis",
    "maximize-2",
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
    "grid-3x3",
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
    "map-pin",
    "message-circle",
    "message-circle-off",
    "message-circle-plus",
    "message-square",
    "message-square-plus",
    "monitor",
    "monitor-x",
    "mosaic",
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
    "redo-2",
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
    "undo-2",
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
    "zoom-in",
    "zoom-out",
]

CUSTOM_ICONS = {
    "mosaic": os.path.join(BRAND_ASSET_SOURCE_DIR, "mosaic.svg"),
}

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
    ],
    "slock-icon": [
        os.path.join(
            BRAND_ASSET_SOURCE_DIR,
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
DEFAULT_ANDROID_PATH_OUTPUT = os.path.join(
    REPO_ROOT,
    "compose",
    "androidApp",
    "src",
    "main",
    "java",
    "build",
    "raft",
    "app",
    "shared",
    "generated",
    "SlockLucidePathIcons.kt",
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


def verify_android_path_source_checkout(repo_dir, version):
    if not repo_dir or not os.path.isdir(os.path.join(repo_dir, ".git")):
        raise RuntimeError("Android path generation requires a git-backed Lucide checkout")
    expected_commit = LUCIDE_SOURCE_COMMITS.get(version)
    if not expected_commit:
        raise RuntimeError(f"Android path generation has no pinned commit for Lucide {version}")
    actual_commit = checkout_head(repo_dir)
    if actual_commit != expected_commit:
        raise RuntimeError(
            f"Android path generation requires Lucide {version} commit {expected_commit}, "
            f"found {actual_commit}"
        )
    if not checkout_matches_version(repo_dir, version):
        raise RuntimeError(
            f"Android path generation requires Lucide checkout at exact tag {version}: {repo_dir}"
        )
    return expected_commit


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

    if normalized in CUSTOM_ICONS and os.path.exists(CUSTOM_ICONS[normalized]):
        with open(CUSTOM_ICONS[normalized], "r", encoding="utf-8") as file:
            svg = file.read().strip()
            _SVG_CACHE[cache_key] = svg
            return svg

    if lucide_source_dir:
        source_path = os.path.join(lucide_source_dir, "icons", f"{normalized}.svg")
        if os.path.exists(source_path):
            with open(source_path, "r", encoding="utf-8") as file:
                svg = file.read().strip()
                _SVG_CACHE[cache_key] = svg
                return svg

    fallback_path = os.path.join(fallback_dir, f"{normalized}.svg") if fallback_dir else None
    if fallback_path and os.path.exists(fallback_path):
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


STRICT_PRESENTATION_ATTRIBUTES = {
    "fill",
    "stroke",
    "fill-opacity",
    "stroke-opacity",
    "opacity",
    "fill-rule",
    "stroke-width",
    "stroke-linecap",
    "stroke-linejoin",
}
STRICT_GEOMETRY_ATTRIBUTES = {
    "path": {"d"},
    "circle": {"cx", "cy", "r"},
    "ellipse": {"cx", "cy", "rx", "ry"},
    "line": {"x1", "y1", "x2", "y2"},
    "rect": {"x", "y", "width", "height", "rx", "ry"},
    "polyline": {"points"},
    "polygon": {"points"},
}
STRICT_CONTAINER_TAGS = {"svg", "g"}
STRICT_ROOT_ATTRIBUTES = {"width", "height", "viewBox"} | STRICT_PRESENTATION_ATTRIBUTES


def strict_float(value, label):
    try:
        decimal_value = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as error:
        raise RuntimeError(f"Invalid numeric SVG attribute {label}={value!r}") from error
    if not decimal_value.is_finite():
        raise RuntimeError(f"Non-finite SVG attribute {label}={value!r}")
    magnitude = abs(decimal_value)
    if magnitude > ANDROID_FLOAT_MAX:
        raise RuntimeError(f"SVG number exceeds Android Float range: {label}={value!r}")
    if decimal_value != 0 and magnitude < ANDROID_FLOAT_MIN_POSITIVE:
        raise RuntimeError(f"SVG number underflows Android Float range: {label}={value!r}")
    return float(decimal_value)


def strict_opacity(value, label):
    result = strict_float(value, label)
    if result < 0.0 or result > 1.0:
        raise RuntimeError(f"SVG opacity must be in [0, 1]: {label}={value!r}")
    return result


def strict_enum(value, label, allowed):
    if value not in allowed:
        raise RuntimeError(f"Unsupported SVG {label}={value!r}")
    return value


def strict_paint(value, label):
    if value is None:
        return None
    normalized = value.strip()
    if normalized in ("none", "transparent"):
        return None
    if normalized == "currentColor":
        return normalized
    if re.fullmatch(r"#[0-9A-Fa-f]{3}|#[0-9A-Fa-f]{6}", normalized):
        return normalized
    raise RuntimeError(f"Unsupported SVG {label} paint={value!r}")


def validate_strict_attributes(element, tag, allowed):
    unknown = sorted(set(element.attrib) - allowed)
    if unknown:
        raise RuntimeError(
            f"Unsupported SVG attribute(s) on <{tag}>: {', '.join(unknown)}"
        )


def validate_strict_path_data(path_data):
    if not path_data or not path_data.strip():
        raise RuntimeError("SVG path has empty d attribute")
    tokens = PATH_TOKEN_RE.findall(path_data)
    remainder = PATH_TOKEN_RE.sub("", path_data)
    if remainder.replace(",", "").strip():
        raise RuntimeError(f"Unsupported SVG path data: {path_data!r}")
    if not any(re.fullmatch(r"[AaCcHhLlMmQqSsTtVvZz]", token) for token in tokens):
        raise RuntimeError(f"SVG path has no commands: {path_data!r}")
    if tokens[0] not in ("M", "m"):
        raise RuntimeError(f"SVG path must start with M or m: {path_data!r}")

    command = None
    values = []

    def validate_values(active_command, active_values):
        if active_command is None:
            if active_values:
                raise RuntimeError(f"SVG path values have no command: {path_data!r}")
            return
        arity = PATH_COMMAND_ARITY[active_command.upper()]
        if arity == 0:
            if active_values:
                raise RuntimeError(f"SVG close command has values: {path_data!r}")
            return
        if not active_values or len(active_values) % arity != 0:
            raise RuntimeError(f"SVG path command {active_command} has invalid arity: {path_data!r}")
        if active_command.upper() == "A":
            for offset in range(0, len(active_values), arity):
                if float(active_values[offset]) < 0.0 or float(active_values[offset + 1]) < 0.0:
                    raise RuntimeError(f"SVG arc radii cannot be negative: {path_data!r}")
                if active_values[offset + 3] not in ("0", "1") or active_values[offset + 4] not in ("0", "1"):
                    raise RuntimeError(f"SVG arc flags must be 0 or 1: {path_data!r}")

    for token in tokens:
        if re.fullmatch(r"[AaCcHhLlMmQqSsTtVvZz]", token):
            validate_values(command, values)
            command = token
            values = []
        else:
            if command is None or command.upper() == "Z":
                raise RuntimeError(f"SVG path values have no command: {path_data!r}")
            strict_float(token, "pathData")
            values.append(token)
    validate_values(command, values)


def strict_number_list(value, label):
    number_pattern = r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?"
    tokens = re.findall(number_pattern, value)
    remainder = re.sub(number_pattern, "", value)
    if remainder.replace(",", "").strip():
        raise RuntimeError(f"Unsupported SVG {label} data: {value!r}")
    return [strict_float(token, label) for token in tokens]


def strict_points_to_path(points, close, minimum_points):
    coords = strict_number_list(points, "points")
    if len(coords) < 2 or len(coords) % 2 != 0:
        raise RuntimeError(f"SVG points must contain coordinate pairs: {points!r}")
    pairs = [(coords[index], coords[index + 1]) for index in range(0, len(coords), 2)]
    if len(pairs) < minimum_points:
        raise RuntimeError(
            f"SVG {'polygon' if close else 'polyline'} requires at least {minimum_points} points: {points!r}"
        )
    data = f"M {pairs[0][0]} {pairs[0][1]}"
    for px, py in pairs[1:]:
        data += f" L {px} {py}"
    if close:
        data += " Z"
    return data


def parse_svg_strict(svg_text):
    """Parse supported SVG geometry without silently dropping rendering semantics."""
    ET.register_namespace("", SVG_NS)
    root = ET.fromstring(svg_text)
    if strip_ns(root.tag) != "svg":
        raise RuntimeError(f"Expected <svg> root, found <{strip_ns(root.tag)}>")
    validate_strict_attributes(root, "svg", STRICT_ROOT_ATTRIBUTES)
    view_box = root.get("viewBox")
    if view_box:
        values = strict_number_list(view_box, "viewBox")
        if len(values) != 4 or values[2] <= 0.0 or values[3] <= 0.0:
            raise RuntimeError(f"Invalid SVG viewBox={view_box!r}")
        min_x, min_y, viewport_width, viewport_height = values
    else:
        min_x = 0.0
        min_y = 0.0
        viewport_width = strict_float(root.get("width", "24"), "width")
        viewport_height = strict_float(root.get("height", "24"), "height")
        if viewport_width <= 0.0 or viewport_height <= 0.0:
            raise RuntimeError("SVG width and height must be positive")

    defaults = {
        "fill": root.get("fill", "none"),
        "stroke": root.get("stroke", "currentColor"),
        "fill-opacity": root.get("fill-opacity", "1"),
        "stroke-opacity": root.get("stroke-opacity", "1"),
        "opacity": strict_opacity(root.get("opacity", "1"), "opacity"),
        "fill-rule": root.get("fill-rule", "nonzero"),
        "stroke-width": root.get("stroke-width", "2"),
        "stroke-linecap": root.get("stroke-linecap", "round"),
        "stroke-linejoin": root.get("stroke-linejoin", "round"),
    }
    paths = []

    def visit(element, inherited):
        tag = strip_ns(element.tag)
        if tag not in STRICT_CONTAINER_TAGS and tag not in STRICT_GEOMETRY_ATTRIBUTES:
            raise RuntimeError(f"Unsupported SVG element <{tag}>")
        allowed = STRICT_PRESENTATION_ATTRIBUTES | STRICT_GEOMETRY_ATTRIBUTES.get(tag, set())
        validate_strict_attributes(element, tag, allowed)

        context = dict(inherited)
        for key in STRICT_PRESENTATION_ATTRIBUTES - {"opacity"}:
            if key in element.attrib:
                context[key] = element.attrib[key]
        context["opacity"] = inherited["opacity"] * strict_opacity(
            element.get("opacity", "1"), "opacity"
        )

        if tag in STRICT_GEOMETRY_ATTRIBUTES:
            if list(element):
                raise RuntimeError(f"SVG geometry <{tag}> cannot contain child elements")
            if tag == "path":
                path_data = element.get("d", "")
            elif tag == "circle":
                radius = strict_float(element.get("r", "0"), "r")
                if radius < 0.0:
                    raise RuntimeError("SVG circle radius cannot be negative")
                path_data = circle_to_path(
                    strict_float(element.get("cx", "0"), "cx"),
                    strict_float(element.get("cy", "0"), "cy"),
                    radius,
                ) if radius > 0.0 else None
            elif tag == "ellipse":
                radius_x = strict_float(element.get("rx", "0"), "rx")
                radius_y = strict_float(element.get("ry", "0"), "ry")
                if radius_x < 0.0 or radius_y < 0.0:
                    raise RuntimeError("SVG ellipse radii cannot be negative")
                path_data = ellipse_to_path(
                    strict_float(element.get("cx", "0"), "cx"),
                    strict_float(element.get("cy", "0"), "cy"),
                    radius_x,
                    radius_y,
                ) if radius_x > 0.0 and radius_y > 0.0 else None
            elif tag == "line":
                path_data = line_to_path(
                    strict_float(element.get("x1", "0"), "x1"),
                    strict_float(element.get("y1", "0"), "y1"),
                    strict_float(element.get("x2", "0"), "x2"),
                    strict_float(element.get("y2", "0"), "y2"),
                )
            elif tag == "rect":
                rx = element.get("rx", element.get("ry", "0"))
                ry = element.get("ry", rx)
                width = strict_float(element.get("width", "0"), "width")
                height = strict_float(element.get("height", "0"), "height")
                radius_x = strict_float(rx, "rx")
                radius_y = strict_float(ry, "ry")
                if min(width, height, radius_x, radius_y) < 0.0:
                    raise RuntimeError("SVG rect dimensions and radii cannot be negative")
                path_data = (
                    rect_to_path(
                        strict_float(element.get("x", "0"), "x"),
                        strict_float(element.get("y", "0"), "y"),
                        width,
                        height,
                        radius_x,
                        radius_y,
                    )
                    if width > 0.0 and height > 0.0
                    else None
                )
            else:
                path_data = strict_points_to_path(
                    element.get("points", ""),
                    close=tag == "polygon",
                    minimum_points=3 if tag == "polygon" else 2,
                )

            if path_data is None:
                return
            validate_strict_path_data(path_data)
            fill = strict_paint(context["fill"], "fill")
            stroke = strict_paint(context["stroke"], "stroke")
            fill_opacity = strict_opacity(context["fill-opacity"], "fill-opacity") * context["opacity"]
            stroke_opacity = strict_opacity(context["stroke-opacity"], "stroke-opacity") * context["opacity"]
            stroke_width_value = strict_float(context["stroke-width"], "stroke-width")
            if stroke_width_value < 0.0:
                raise RuntimeError("SVG stroke-width cannot be negative")
            paths.append(
                {
                    "pathData": path_data,
                    "fillPaint": fill,
                    "strokePaint": stroke,
                    "fillOpacity": fill_opacity if fill else 0.0,
                    "strokeOpacity": stroke_opacity if stroke else 0.0,
                    "strokeWidth": stroke_width_value,
                    "strokeLineCap": strict_enum(
                        context["stroke-linecap"], "stroke-linecap", {"butt", "round", "square"}
                    ),
                    "strokeLineJoin": strict_enum(
                        context["stroke-linejoin"], "stroke-linejoin", {"miter", "round", "bevel"}
                    ),
                    "fillRule": strict_enum(
                        context["fill-rule"], "fill-rule", {"nonzero", "evenodd"}
                    ),
                }
            )

        for child in element:
            visit(child, context)

    for child in root:
        visit(child, defaults)
    if not paths:
        raise RuntimeError("SVG contains no supported paths")
    return {
        "viewBoxMinX": min_x,
        "viewBoxMinY": min_y,
        "viewBoxWidth": viewport_width,
        "viewBoxHeight": viewport_height,
        "paths": paths,
    }


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


def kotlin_float(value):
    number = float(value)
    if number == 0.0:
        number = 0.0
    if number.is_integer() and abs(number) <= 16_777_216:
        return f"{int(number)}f"
    text = format(number, ".9g")
    if "." not in text and "e" not in text.lower():
        text += ".0"
    return f"{text}f"


def kotlin_nullable_string(value):
    if value is None:
        return "null"
    return f'"{kotlin_string(value)}"'


def android_path_entry_expr(path):
    cap = {
        "butt": "Butt",
        "round": "Round",
        "square": "Square",
    }[path["strokeLineCap"]]
    join = {
        "miter": "Miter",
        "round": "Round",
        "bevel": "Bevel",
    }[path["strokeLineJoin"]]
    fill_rule = {
        "nonzero": "NonZero",
        "evenodd": "EvenOdd",
    }[path["fillRule"]]
    return "\n".join(
        [
            "            SlockLucidePathSpec(",
            f'                pathData = "{kotlin_string(path["pathData"])}",',
            f"                fillPaint = {kotlin_nullable_string(path['fillPaint'])},",
            f"                strokePaint = {kotlin_nullable_string(path['strokePaint'])},",
            f"                fillOpacity = {kotlin_float(path['fillOpacity'])},",
            f"                strokeOpacity = {kotlin_float(path['strokeOpacity'])},",
            f"                strokeWidth = {kotlin_float(path['strokeWidth'])},",
            f"                strokeLineCap = SlockLucideStrokeLineCap.{cap},",
            f"                strokeLineJoin = SlockLucideStrokeLineJoin.{join},",
            f"                fillRule = SlockLucideFillRule.{fill_rule},",
            "            ),",
        ]
    )


def android_path_icon_expr(icon, parsed):
    path_lines = "\n".join(android_path_entry_expr(path) for path in parsed["paths"])
    return "\n".join(
        [
            "        SlockLucideIconSpec(",
            f'            name = "{kotlin_string(icon)}",',
            "            viewBox =",
            "                SlockLucideViewBox(",
            f"                    minX = {kotlin_float(parsed['viewBoxMinX'])},",
            f"                    minY = {kotlin_float(parsed['viewBoxMinY'])},",
            f"                    width = {kotlin_float(parsed['viewBoxWidth'])},",
            f"                    height = {kotlin_float(parsed['viewBoxHeight'])},",
            "                ),",
            "            paths =",
            "                listOf(",
            path_lines,
            "                ),",
            "        )",
        ]
    )


def android_path_catalog_source(icons, version, fallback_dir, lucide_source_dir):
    if not lucide_source_dir:
        raise RuntimeError("Android path generation requires a pinned Lucide source checkout")
    source_commit = LUCIDE_SOURCE_COMMITS.get(version)
    if not source_commit:
        raise RuntimeError(f"Android path generation has no pinned commit for Lucide {version}")
    entries = []
    for icon in icons:
        normalized = normalize_icon_name(icon)
        if normalized in CUSTOM_ICONS and os.path.isfile(CUSTOM_ICONS[normalized]):
            source_path = CUSTOM_ICONS[normalized]
        else:
            source_path = os.path.join(lucide_source_dir, "icons", f"{normalized}.svg")
            if not os.path.isfile(source_path):
                raise RuntimeError(f"Pinned Lucide source is missing icon: {source_path}")
        with open(source_path, encoding="utf-8") as file:
            svg = file.read().strip()
        entries.append((icon, parse_svg_strict(svg)))

    variant_entries = []
    for source_name, variant_name in ANDROID_FILLED_ICON_VARIANTS.items():
        if source_name not in icons:
            continue
        source_path = os.path.join(lucide_source_dir, "icons", f"{source_name}.svg")
        with open(source_path, encoding="utf-8") as file:
            variant_svg = android_filled_variant_svg(source_name, file.read().strip())
        variant_entries.append((variant_name, parse_svg_strict(variant_svg)))
    all_entries = [*entries, *variant_entries]

    lines = [
        "package build.raft.app.shared.generated",
        "",
        "// Generated by scripts/generate_lucide_icons.py. Do not edit by hand.",
        "internal enum class SlockLucideStrokeLineCap { Butt, Round, Square }",
        "internal enum class SlockLucideStrokeLineJoin { Miter, Round, Bevel }",
        "internal enum class SlockLucideFillRule { NonZero, EvenOdd }",
        "",
        "internal data class SlockLucideViewBox(",
        "    val minX: Float,",
        "    val minY: Float,",
        "    val width: Float,",
        "    val height: Float,",
        ")",
        "",
        "internal data class SlockLucidePathSpec(",
        "    val pathData: String,",
        "    val fillPaint: String?,",
        "    val strokePaint: String?,",
        "    val fillOpacity: Float,",
        "    val strokeOpacity: Float,",
        "    val strokeWidth: Float,",
        "    val strokeLineCap: SlockLucideStrokeLineCap,",
        "    val strokeLineJoin: SlockLucideStrokeLineJoin,",
        "    val fillRule: SlockLucideFillRule,",
        ")",
        "",
        "internal data class SlockLucideIconSpec(",
        "    val name: String,",
        "    val viewBox: SlockLucideViewBox,",
        "    val paths: List<SlockLucidePathSpec>,",
        ")",
        "",
        "internal object SlockLucidePathIcons {",
        f'    const val Version: String = "{kotlin_string(version)}"',
        f'    const val SourceCommit: String = "{source_commit}"',
        "",
    ]
    for icon, parsed in all_entries:
        expression = android_path_icon_expr(icon, parsed)
        lines.append(f"    private val {pascal_name(icon)}: SlockLucideIconSpec by lazy {{")
        lines.extend(expression.splitlines())
        lines.extend(["    }", ""])
    lines.extend(
        [
            "    val Names: List<String> =",
            "        listOf(",
            *[f'            "{kotlin_string(icon)}",' for icon, _ in entries],
            "        )",
            "",
            "    val VariantNames: List<String> =",
            "        listOf(",
            *[f'            "{kotlin_string(icon)}",' for icon, _ in variant_entries],
            "        )",
            "",
            "    fun byName(name: String): SlockLucideIconSpec? =",
            "        when (name.toIconKey()) {",
        ]
    )
    for icon, _ in all_entries:
        lines.append(f'            "{kotlin_string(icon)}" -> {pascal_name(icon)}')
    lines.extend(
        [
            "            else -> null",
            "        }",
            "",
            "    fun filledVariantName(name: String): String? =",
            "        when (name.toIconKey()) {",
            *[
                f'            "{kotlin_string(source_name)}" -> "{kotlin_string(variant_name)}"'
                for source_name, variant_name in ANDROID_FILLED_ICON_VARIANTS.items()
                if source_name in icons
            ],
            "            else -> null",
            "        }",
            "",
            "    fun filledByName(name: String): SlockLucideIconSpec? =",
            "        filledVariantName(name)?.let(::byName)",
            "",
            "    private fun String.toIconKey(): String =",
            "        trim()",
            '            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")',
            "            .replace('_', '-')",
            "            .lowercase()",
            "}",
            "",
        ]
    )
    return "\n".join(lines)


def generate_android_paths(icons, version, output, fallback_dir, lucide_source_dir):
    verify_android_path_source_checkout(lucide_source_dir, version)
    source = android_path_catalog_source(
        icons,
        version,
        fallback_dir,
        lucide_source_dir,
    )
    write_text(output, source)
    print(f"Generated {len(icons)} Android path icons -> {output}")


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


def generate_brand_assets(shared_asset_output, ios_asset_catalog):
    shared_asset_output = os.path.abspath(shared_asset_output)
    ios_asset_catalog = os.path.abspath(ios_asset_catalog)
    for asset_name in BRAND_ASSET_SVGS.keys():
        svg_text = read_brand_asset_svg(asset_name)
        filename = f"{asset_name}.svg"
        write_text(os.path.join(shared_asset_output, filename), svg_text)

        imageset_dir = os.path.join(ios_asset_catalog, f"lucide-{asset_name}.imageset")
        write_text(os.path.join(imageset_dir, filename), svg_text)
        write_text(os.path.join(imageset_dir, "Contents.json"), ios_imageset_contents(filename))
    print(
        f"Generated {len(BRAND_ASSET_SVGS)} brand SVG assets -> "
        f"{shared_asset_output}, {ios_asset_catalog}"
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
        ).strip().replace('stroke="currentColor"', 'stroke="#000000"').replace('fill="currentColor"', 'fill="#000000"')
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
    valid = {"android", "android-path", "ios", "shared", "brand-assets"}
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
    parser.add_argument("--android-path-output", default=DEFAULT_ANDROID_PATH_OUTPUT)
    parser.add_argument("--shared-catalog-output", default=DEFAULT_SHARED_CATALOG_OUTPUT)
    parser.add_argument("--shared-asset-output", default=DEFAULT_SHARED_ASSET_OUTPUT)
    parser.add_argument("--ios-asset-catalog", default=DEFAULT_IOS_ASSET_CATALOG)
    parser.add_argument("--lucide-cache-dir", default=DEFAULT_LUCIDE_CACHE_DIR)
    parser.add_argument("--lucide-repo-dir", default=None)
    parser.add_argument("--download-workers", type=int, default=8)
    parser.add_argument("--all-lucide-icons", action="store_true")
    args = parser.parse_args()

    lucide_targets = args.targets & {"android", "android-path", "ios", "shared"}
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
            None,
            lucide_source_dir,
            args.download_workers,
        )
    if "android" in args.targets:
        generate_android(
            icons,
            args.version,
            os.path.abspath(args.android_output),
            None,
            lucide_source_dir,
        )
    if "android-path" in args.targets:
        generate_android_paths(
            icons,
            args.version,
            os.path.abspath(args.android_path_output),
            None,
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
            os.path.abspath(args.ios_asset_catalog),
        )


if __name__ == "__main__":
    main()
