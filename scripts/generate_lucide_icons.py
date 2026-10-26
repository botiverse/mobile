#!/usr/bin/env python3
"""
Generate Slock Lucide assets from one manifest.

Outputs by default:
- compose: commonMain Compose Multiplatform path table (RaftLucideIconPaths.kt) rendered by
  RaftLucideIcon through lazily built, cached ImageVectors
- shared: RaftLucideIconName registry (RaftLucideIconCatalog.kt)
- ios: iOS asset catalog SVG imagesets (still used by native iOS chrome)
- brand-assets: shared/iOS bundled brand SVG assets used by assets:// image URLs

Use --check to verify the committed compose/shared Kotlin outputs are up to date without writing.
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
DEFAULT_TARGETS = "compose,ios,shared,brand-assets"
CHECKABLE_TARGETS = {"compose", "shared"}
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
    "arrow-up-right",
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
    "check-check",
    "chevron-down",
    "chevron-left",
    "chevron-right",
    "chevron-up",
    "circle",
    "circle-arrow-up",
    "circle-check",
    "circle-check-big",
    "circle-dot",
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
    "ellipsis-vertical",
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
    "flask-conical",
    "folder",
    "folder-input",
    "folder-open",
    "folder-plus",
    "funnel",
    "git-branch",
    "globe",
    "grid-3x3",
    "grip-vertical",
    "hash",
    "heart",
    "house",
    "image",
    "image-off",
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
    "message-square-check",
    "message-square-dot",
    "message-square-plus",
    "monitor",
    "monitor-x",
    "mosaic",
    "music",
    "package-check",
    "package",
    "paperclip",
    "panel-bottom-open",
    "pause",
    "pencil",
    "pin",
    "pin-off",
    "play",
    "plug",
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
    "shield-alert",
    "shield-check",
    "sliders-horizontal",
    "smartphone",
    "smile-plus",
    "sparkles",
    "square",
    "square-check-big",
    "square-terminal",
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

FILLED_ICON_VARIANTS = {
    "bookmark": "filled-bookmark",
}

DEFAULT_COMPOSE_OUTPUT = os.path.join(
    REPO_ROOT,
    "compose",
    "shared",
    "src",
    "commonMain",
    "kotlin",
    "build",
    "raft",
    "app",
    "RaftLucideIconPaths.kt",
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
    "RaftLucideIconCatalog.kt",
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


def verify_pinned_source_checkout(repo_dir, version):
    if not repo_dir or not os.path.isdir(os.path.join(repo_dir, ".git")):
        raise RuntimeError("Lucide path generation requires a git-backed Lucide checkout")
    expected_commit = LUCIDE_SOURCE_COMMITS.get(version)
    if not expected_commit:
        raise RuntimeError(f"Lucide path generation has no pinned commit for Lucide {version}")
    actual_commit = checkout_head(repo_dir)
    if actual_commit != expected_commit:
        raise RuntimeError(
            f"Lucide path generation requires Lucide {version} commit {expected_commit}, "
            f"found {actual_commit}"
        )
    if not checkout_matches_version(repo_dir, version):
        raise RuntimeError(
            f"Lucide path generation requires Lucide checkout at exact tag {version}: {repo_dir}"
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


def filled_variant_svg(icon_name, svg_text):
    normalized = normalize_icon_name(icon_name)
    if normalized != "bookmark":
        raise ValueError(f"Unsupported filled icon variant: {icon_name}")

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
        raise RuntimeError(f"SVG number exceeds Kotlin Float range: {label}={value!r}")
    if decimal_value != 0 and magnitude < ANDROID_FLOAT_MIN_POSITIVE:
        raise RuntimeError(f"SVG number underflows Kotlin Float range: {label}={value!r}")
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


COMPOSE_PATH_DEFAULTS = {
    "fillPaint": None,
    "strokePaint": "currentColor",
    "strokeWidth": 2.0,
    "strokeLineCap": "round",
    "strokeLineJoin": "round",
    "fillRule": "nonzero",
}
COMPOSE_LINE_CAPS = {"butt": "Butt", "round": "Round", "square": "Square"}
COMPOSE_LINE_JOINS = {"miter": "Miter", "round": "Round", "bevel": "Bevel"}
COMPOSE_FILL_RULES = {"nonzero": "NonZero", "evenodd": "EvenOdd"}


def compose_path_entry_expr(path):
    """One RaftLucidePathSpec(...) call; only non-default attributes are emitted."""
    args = [f'"{kotlin_string(path["pathData"])}"']
    if path["fillPaint"] != COMPOSE_PATH_DEFAULTS["fillPaint"]:
        args.append(f"fillPaint = {kotlin_nullable_string(path['fillPaint'])}")
    if path["fillPaint"] is not None and path["fillOpacity"] != 1.0:
        args.append(f"fillOpacity = {kotlin_float(path['fillOpacity'])}")
    if path["strokePaint"] != COMPOSE_PATH_DEFAULTS["strokePaint"]:
        args.append(f"strokePaint = {kotlin_nullable_string(path['strokePaint'])}")
    if path["strokePaint"] is not None and path["strokeOpacity"] != 1.0:
        args.append(f"strokeOpacity = {kotlin_float(path['strokeOpacity'])}")
    if path["strokeWidth"] != COMPOSE_PATH_DEFAULTS["strokeWidth"]:
        args.append(f"strokeWidth = {kotlin_float(path['strokeWidth'])}")
    if path["strokeLineCap"] != COMPOSE_PATH_DEFAULTS["strokeLineCap"]:
        args.append(f"strokeLineCap = RaftLucideStrokeLineCap.{COMPOSE_LINE_CAPS[path['strokeLineCap']]}")
    if path["strokeLineJoin"] != COMPOSE_PATH_DEFAULTS["strokeLineJoin"]:
        args.append(f"strokeLineJoin = RaftLucideStrokeLineJoin.{COMPOSE_LINE_JOINS[path['strokeLineJoin']]}")
    if path["fillRule"] != COMPOSE_PATH_DEFAULTS["fillRule"]:
        args.append(f"fillRule = RaftLucideFillRule.{COMPOSE_FILL_RULES[path['fillRule']]}")
    return f"RaftLucidePathSpec({', '.join(args)})"


def compose_icon_expr(icon, parsed, indent):
    pad = " " * indent
    lines = [f"{pad}RaftLucideIconSpec(", f'{pad}    "{kotlin_string(icon)}",', f"{pad}    listOf("]
    for path in parsed["paths"]:
        lines.append(f"{pad}        {compose_path_entry_expr(path)},")
    lines.append(f"{pad}    ),")
    view_box = (
        ("viewBoxMinX", 0.0),
        ("viewBoxMinY", 0.0),
        ("viewBoxWidth", 24.0),
        ("viewBoxHeight", 24.0),
    )
    for key, default in view_box:
        if parsed[key] != default:
            lines.append(f"{pad}    {key} = {kotlin_float(parsed[key])},")
    lines.append(f"{pad})")
    return lines


def read_pinned_icon_svg(icon, lucide_source_dir):
    normalized = normalize_icon_name(icon)
    if normalized in CUSTOM_ICONS and os.path.isfile(CUSTOM_ICONS[normalized]):
        source_path = CUSTOM_ICONS[normalized]
    else:
        source_path = os.path.join(lucide_source_dir, "icons", f"{normalized}.svg")
        if not os.path.isfile(source_path):
            raise RuntimeError(f"Pinned Lucide source is missing icon: {source_path}")
    with open(source_path, encoding="utf-8") as file:
        return file.read().strip()


def compose_catalog_source(icons, version, lucide_source_dir):
    """Kotlin source for the commonMain Lucide path table consumed by RaftLucideIcon.

    The table only stores SVG path strings plus non-default presentation attributes; the
    runtime (RaftLucideImageVectors.kt) parses them lazily into cached ImageVectors. This keeps
    the generated file small and avoids hundreds of ImageVector.Builder call chains to compile.
    """
    if not lucide_source_dir:
        raise RuntimeError("Compose Lucide generation requires a pinned Lucide source checkout")
    source_commit = LUCIDE_SOURCE_COMMITS.get(version)
    if not source_commit:
        raise RuntimeError(f"Compose Lucide generation has no pinned commit for Lucide {version}")
    entries = [(icon, parse_svg_strict(read_pinned_icon_svg(icon, lucide_source_dir))) for icon in icons]
    variant_entries = []
    for source_name in FILLED_ICON_VARIANTS:
        if source_name not in icons:
            continue
        variant_svg = filled_variant_svg(source_name, read_pinned_icon_svg(source_name, lucide_source_dir))
        variant_entries.append((source_name, parse_svg_strict(variant_svg)))

    lines = [
        "package build.raft.app",
        "",
        "// Generated by scripts/generate_lucide_icons.py. Do not edit by hand.",
        f"// Source: Lucide {version} ({source_commit}) plus scripts/assets/brand custom icons.",
        "",
        "internal enum class RaftLucideStrokeLineCap { Butt, Round, Square }",
        "",
        "internal enum class RaftLucideStrokeLineJoin { Miter, Round, Bevel }",
        "",
        "internal enum class RaftLucideFillRule { NonZero, EvenOdd }",
        "",
        "/** One SVG path in viewBox units. Paints are `currentColor`, `#rgb`/`#rrggbb`, or null (none). */",
        "internal class RaftLucidePathSpec(",
        "    val pathData: String,",
        "    val fillPaint: String? = null,",
        "    val fillOpacity: Float = 1f,",
        '    val strokePaint: String? = "currentColor",',
        "    val strokeOpacity: Float = 1f,",
        "    val strokeWidth: Float = 2f,",
        "    val strokeLineCap: RaftLucideStrokeLineCap = RaftLucideStrokeLineCap.Round,",
        "    val strokeLineJoin: RaftLucideStrokeLineJoin = RaftLucideStrokeLineJoin.Round,",
        "    val fillRule: RaftLucideFillRule = RaftLucideFillRule.NonZero,",
        ")",
        "",
        "internal class RaftLucideIconSpec(",
        "    val name: String,",
        "    val paths: List<RaftLucidePathSpec>,",
        "    val viewBoxMinX: Float = 0f,",
        "    val viewBoxMinY: Float = 0f,",
        "    val viewBoxWidth: Float = 24f,",
        "    val viewBoxHeight: Float = 24f,",
        ")",
        "",
        "internal object RaftLucideIconPaths {",
        f'    const val Version: String = "{kotlin_string(version)}"',
        f'    const val SourceCommit: String = "{source_commit}"',
        "",
        "    fun spec(name: RaftLucideIconName): RaftLucideIconSpec =",
        "        when (name) {",
    ]
    for icon, parsed in entries:
        lines.append(f"            RaftLucideIconName.{pascal_name(icon)} ->")
        lines.extend(compose_icon_expr(icon, parsed, 16))
    lines.extend(
        [
            "        }",
            "",
            "    /** Filled variant used when RaftLucideIcon gets a `fill` colour; null if the icon has none. */",
            "    fun filledSpec(name: RaftLucideIconName): RaftLucideIconSpec? =",
            "        when (name) {",
        ]
    )
    for icon, parsed in variant_entries:
        lines.append(f"            RaftLucideIconName.{pascal_name(icon)} ->")
        lines.extend(compose_icon_expr(FILLED_ICON_VARIANTS[icon], parsed, 16))
    lines.extend(
        [
            "            else -> null",
            "        }",
            "}",
            "",
        ]
    )
    return "\n".join(lines)


def generate_compose(icons, version, output, lucide_source_dir, check=False):
    verify_pinned_source_checkout(lucide_source_dir, version)
    source = compose_catalog_source(icons, version, lucide_source_dir)
    if check:
        return check_text(output, source)
    write_text(output, source)
    print(f"Generated {len(icons)} Compose Lucide icons -> {output}")
    return True


def check_text(path, expected):
    try:
        with open(path, encoding="utf-8") as file:
            actual = file.read()
    except FileNotFoundError:
        actual = None
    if actual != expected:
        print(f"OUT OF DATE: {path}")
        return False
    print(f"up to date: {path}")
    return True


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


def shared_catalog_source(icons):
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
        "@Serializable(with = RaftLucideIconNameWireSerializer::class)",
        "enum class RaftLucideIconName(val wireName: String) {",
    ]
    for index, icon in enumerate(icons):
        suffix = "," if index < len(icons) - 1 else ";"
        lines.append(f'    {pascal_name(icon)}("{icon}"){suffix}')
    lines.extend(
        [
            "",
            "    companion object {",
            "        fun fromWireName(value: String): RaftLucideIconName? =",
            "            entries.firstOrNull { it.wireName == value }",
            "    }",
            "}",
            "",
            "object RaftLucideIconNameWireSerializer : KSerializer<RaftLucideIconName> {",
            "    override val descriptor: SerialDescriptor =",
            '        PrimitiveSerialDescriptor("RaftLucideIconName", PrimitiveKind.STRING)',
            "",
            "    override fun serialize(encoder: Encoder, value: RaftLucideIconName) {",
            "        encoder.encodeString(value.wireName)",
            "    }",
            "",
            "    override fun deserialize(decoder: Decoder): RaftLucideIconName =",
            "        RaftLucideIconName.fromWireName(decoder.decodeString()) ?: RaftLucideIconName.Settings",
            "}",
            "",
            "internal object RaftLucideIcons {",
            "    val Names: List<String> = RaftLucideIconName.entries.map { it.wireName }",
            "}",
            "",
            "internal object RaftLucideIconCompareSet {",
            f"    val Names: List<String> = listOf({brand_entries}) + RaftLucideIcons.Names",
            "}",
            "",
        ]
    )
    return "\n".join(lines)


def generate_shared_catalog(icons, output, check=False):
    source = shared_catalog_source(icons)
    if check:
        return check_text(output, source)
    write_text(output, source)
    print(f"Generated shared icon catalog -> {output}")
    return True


def parse_targets(value):
    targets = {part.strip().lower() for part in value.split(",") if part.strip()}
    valid = {"compose", "ios", "shared", "brand-assets"}
    unknown = targets - valid
    if unknown:
        raise argparse.ArgumentTypeError(f"Unknown target(s): {', '.join(sorted(unknown))}")
    if not targets:
        raise argparse.ArgumentTypeError("At least one target is required")
    return targets


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("icons", nargs="*", help="Lucide icon names. Defaults to the Slock manifest in this file.")
    parser.add_argument("--version", default=DEFAULT_VERSION)
    parser.add_argument("--targets", type=parse_targets, default=None)
    parser.add_argument("--compose-output", default=DEFAULT_COMPOSE_OUTPUT)
    parser.add_argument("--shared-catalog-output", default=DEFAULT_SHARED_CATALOG_OUTPUT)
    parser.add_argument("--shared-asset-output", default=DEFAULT_SHARED_ASSET_OUTPUT)
    parser.add_argument("--ios-asset-catalog", default=DEFAULT_IOS_ASSET_CATALOG)
    parser.add_argument("--lucide-cache-dir", default=DEFAULT_LUCIDE_CACHE_DIR)
    parser.add_argument("--lucide-repo-dir", default=None)
    parser.add_argument("--download-workers", type=int, default=8)
    parser.add_argument("--all-lucide-icons", action="store_true")
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verify the committed compose/shared Kotlin outputs match the generator (no writes).",
    )
    args = parser.parse_args()

    targets = args.targets
    if targets is None:
        targets = set(CHECKABLE_TARGETS) if args.check else parse_targets(DEFAULT_TARGETS)
    if args.check and targets - CHECKABLE_TARGETS:
        parser.error(f"--check only supports targets: {', '.join(sorted(CHECKABLE_TARGETS))}")

    lucide_targets = targets & {"compose", "ios", "shared"}
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
    ok = True
    if "compose" in targets:
        ok &= generate_compose(
            icons,
            args.version,
            os.path.abspath(args.compose_output),
            lucide_source_dir,
            check=args.check,
        )
    if "ios" in targets:
        generate_ios(
            icons,
            args.version,
            os.path.abspath(args.ios_asset_catalog),
            lucide_source_dir,
        )
    if "shared" in targets:
        ok &= generate_shared_catalog(icons, os.path.abspath(args.shared_catalog_output), check=args.check)
    if "brand-assets" in targets:
        generate_brand_assets(
            os.path.abspath(args.shared_asset_output),
            os.path.abspath(args.ios_asset_catalog),
        )
    if not ok:
        raise SystemExit("Lucide outputs are out of date; run scripts/generate_lucide_icons.py")


if __name__ == "__main__":
    main()
