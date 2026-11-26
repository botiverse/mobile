#!/usr/bin/env python3
"""Select impacted Android JVM unit test classes and Gradle modules for ordinary PRs.

Part of dm:@artin:#159 (PR CI round 3): on ordinary PRs (`android_unit_scope=jvm`),
run only the unit test classes impacted by the PR's changed files plus a curated
always-run smoke set (`scripts/ci/always_run_smoke_tests.txt`), and skip untouched
leaf/upstream module test tasks (`:core`, `:realtime-socket`, `:ui-cmp`, `:web-container`).

Safety model: strictly fail-closed to `mode=full` (the full `jvm` tier as before)
whenever diff metadata is unavailable, a production file is deleted, build/config/
resource/schema/workflow files change, a hub file with broad fan-out changes, or
selected `:shared` tests exceed MAX_SHARED_RATIO of all `:shared` test classes.
Post-merge `main`, `release-*`, nightly, merge-queue, `full-ci`, and `all`-tier PRs
never consult this selector and always run the full suite.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
from collections import defaultdict
from dataclasses import dataclass, field

# Maximum number of changed paths handled before failing closed to full suite.
MAX_CHANGED_PATHS = 200
# If selected :shared test classes exceed this fraction of all :shared test classes,
# running the full jvm suite avoids large filter lists for negligible savings.
MAX_SHARED_RATIO = 0.40
# A directly changed production file referenced by more than this many prod files
# is a broad shared hub -> fail closed to full suite.
DIRECT_HUB_FANOUT = 120
# During hop-1/hop-2 expansion, avoid fanning out through every symbol of huge UI
# aggregator files (e.g. RaftHomeComponents.kt with 100+ composables).
HOP1_MAX_DECLS = 20
HOP2_MAX_DECLS = 10
HOP2_HUB_CAP = 15

KNOWN_MODULES = (
    "core",
    "realtime-socket",
    "designsystem",
    "ui-cmp",
    "web-container",
    "shared-data",
    "shared",
    "androidApp",
    "desktopApp",
)

INDEXED_PROD_MODULES = (
    "core",
    "realtime-socket",
    "designsystem",
    "ui-cmp",
    "shared-data",
    "shared",
    "androidApp",
)

TEST_ROOTS_BY_MODULE: dict[str, tuple[str, ...]] = {
    "shared": ("src/commonTest", "src/androidUnitTest"),
    "androidApp": ("src/test",),
}

PROD_SOURCE_SETS = (
    "commonMain",
    "androidMain",
    "jvmCommonMain",
    "visualFixturesOn",
    "visualFixturesOff",
    "main",
    "debug",
    "release",
    "alpha",
    "play",
    "direct",
    "preview",
)

# Platform-specific production source sets that are never compiled by Android JVM unit tests.
# Kept separate from PROD_SOURCE_SETS so index_repository() only indexes Android-visible code.
NON_ANDROID_PROD_SOURCE_SETS = (
    "iosMain",
    "nativeMain",
    "desktopMain",
    "jsMain",
)

TEST_SOURCE_SETS = (
    "commonTest",
    "androidUnitTest",
    "androidHostTest",
    "desktopTest",
    "test",
    "iosTest",
    "androidTest",
    "androidInstrumentedTest",
    "connectedTest",
    "desktopVcase",
)

BENIGN_PATH_PREFIXES = (
    "change-logs/",
    "docs/",
    ".github/ISSUE_TEMPLATE/",
    "compose/iosApp/",
)

# Resource-catalog & copy verification tests executed whenever strings.xml changes.
STRINGS_XML_TESTS = (
    "build.raft.app.ComposeResourcesCatalogParityTest",
    "build.raft.app.WorkspaceTerminologyResourceTest",
    "build.raft.app.ExternalChannelCopyCatalogTest",
    "build.raft.app.AgentTypingCatalogTest",
    "build.raft.app.AgentActivityLocalizedCopyTest",
    "build.raft.app.RaftPluralsTest",
)

# Short or overly generic Kotlin identifiers excluded from symbol propagation.
GENERIC_SYMBOLS = frozenset({
    "Action", "Adapter", "Args", "Badge", "Base", "Body", "Bridge", "Builder",
    "Button", "Cache", "Callback", "Card", "Cell", "Check", "Chip", "Client",
    "Codec", "Color", "Colors", "Column", "Companion", "Config", "Container",
    "Content", "Context", "Contract", "Controller", "Converter", "Count", "Data",
    "Default", "Defaults", "Delegate", "Detail", "Details", "Dialog", "Draft",
    "Effect", "Empty", "Entry", "Error", "Event", "Events", "Factory", "Failure",
    "Field", "File", "Filter", "Flags", "Footer", "Format", "Frame", "Gate",
    "Group", "Guard", "Handler", "Header", "Helper", "Helpers", "Host", "Icon",
    "Icons", "Index", "Info", "Input", "Intent", "Item", "Items", "Kind", "Label",
    "Layout", "Level", "Limit", "Limits", "Line", "Link", "Links", "List",
    "Loader", "Loading", "Logger", "Main", "Mapper", "Menu", "Message", "Meta",
    "Metrics", "Mock", "Mode", "Model", "Models", "Modifier", "Name", "Node",
    "None", "Notice", "Option", "Options", "Order", "Other", "Output", "Owner",
    "Page", "Panel", "Params", "Parser", "Patch", "Path", "Payload", "Phase",
    "Picker", "Plan", "Policy", "Popup", "Preview", "Props", "Provider", "Query",
    "Queue", "Range", "Reader", "Record", "Reducer", "Registry", "Relay", "Reply",
    "Report", "Request", "Reset", "Resolver", "Resource", "Response", "Result",
    "Retry", "Role", "Root", "Route", "Router", "Rule", "Rules", "Runner", "Fake",
    "Sample", "Scale", "Schema", "Scope", "Screen", "Section", "Seed", "Selector",
    "Sender", "Server", "Service", "Session", "Settings", "Shape", "Sheet", "Shell",
    "Signal", "Size", "Slot", "Snapshot", "Sort", "Source", "Spec", "Stage",
    "State", "States", "Stats", "Status", "Step", "Steps", "Store", "Stream",
    "String", "Strings", "Stub", "Style", "Styles", "Summary", "Surface", "Sync",
    "Table", "Tabs", "Target", "Task", "Tasks", "Test", "Tests", "Text", "Theme",
    "Thread", "Tier", "Time", "Timer", "Title", "Toast",
    "Toolbar", "Tooltip", "Trace", "Track", "Trigger", "Type", "Types", "UiState",
    "Unknown", "Update", "Upload", "Utils", "Validator", "Value", "Values",
    "Version", "View", "ViewModel", "Window", "Worker", "Wrapper", "Writer",
    "build", "check", "clear", "close", "copy", "create", "decode", "default",
    "delete", "empty", "encode", "equals", "fetch", "filter", "find", "format",
    "from", "hashCode", "init", "invoke", "isEmpty", "load", "main", "make",
    "merge", "next", "open", "parse", "read", "render", "reset", "resolve",
    "save", "send", "show", "size", "start", "stop", "test", "toString", "update",
    "validate", "write",
})

SOCKETIO_INTEROP_TEST = "build.raft.app.SocketIoRealtimeClientInteropTest"

PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.M)
IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+(?:\.\*)?)", re.M)
TOP_TYPE_RE = re.compile(
    r"^(?!private\b)(?:(?:public|internal|protected|open|abstract|sealed|data|enum|"
    r"annotation|value|expect|actual|inline|fun)\s+)*"
    r"(?:class|interface|object|typealias)\s+([A-Za-z_]\w*)",
    re.M,
)
TOP_FN_VAL_RE = re.compile(
    r"^(?!private\b)(?:(?:public|internal|protected|open|abstract|expect|actual|"
    r"inline|suspend|const|tailrec|operator|infix)\s+)*"
    r"(?:fun|val|var)\s+(?:<[^>]+>\s+)?(?:[A-Z]\w*(?:<[^>]+>)?\??\.)?([A-Za-z_]\w*)",
    re.M,
)
TEST_CLASS_DECL_RE = re.compile(
    r"^(?:(?:@[\w.]+(?:\([^)\n]*\))?\s+)*)"
    r"(?:(?:public|internal|open|abstract|final)\s+)*"
    r"class\s+([A-Za-z_]\w*)\b",
    re.M,
)
WORD_RE = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]{3,}\b")
LINE_COMMENT_RE = re.compile(r"//[^\n]*")
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)
PROJECT_DEP_RE = re.compile(r'project\(\s*":([A-Za-z0-9_-]+)"\s*\)')
ROBOLECTRIC_MARKER_RE = re.compile(r"\b(?:RobolectricTestRunner|ParameterizedRobolectricTestRunner|AndroidJUnit4)\b")
SCREENSHOT_MARKER_RE = re.compile(r"\b(?:RaftRobolectricScreenshot|captureRoboImage|VCaseDesktopTest|VCaseRunner)\b")


def strip_comments(text: str) -> str:
    text = BLOCK_COMMENT_RE.sub(" ", text)
    text = LINE_COMMENT_RE.sub("", text)
    return text


def parse_module_deps(repo_root: pathlib.Path) -> tuple[dict[str, set[str]], dict[str, set[str]]]:
    """Parse compose/*/build.gradle.kts to build direct deps and reverse transitive dependents."""
    deps: dict[str, set[str]] = {m: set() for m in KNOWN_MODULES}
    compose_dir = repo_root / "compose"
    for mod in KNOWN_MODULES:
        gradle_file = compose_dir / mod / "build.gradle.kts"
        if not gradle_file.is_file():
            continue
        code = strip_comments(gradle_file.read_text(encoding="utf-8", errors="ignore"))
        for match in PROJECT_DEP_RE.finditer(code):
            dep = match.group(1)
            if dep in deps and dep != mod:
                deps[mod].add(dep)

    # Reverse adjacency
    rev_direct: dict[str, set[str]] = {m: set() for m in KNOWN_MODULES}
    for mod, mod_deps in deps.items():
        for dep in mod_deps:
            rev_direct[dep].add(mod)

    # Transitive reverse dependents
    rev_trans: dict[str, set[str]] = {}
    for mod in KNOWN_MODULES:
        seen: set[str] = set()
        stack = list(rev_direct[mod])
        while stack:
            cur = stack.pop()
            if cur in seen:
                continue
            seen.add(cur)
            stack.extend(rev_direct[cur])
        rev_trans[mod] = seen
    return deps, rev_trans


@dataclass
class IndexedFile:
    file_id: int
    rel_path: str
    module: str
    is_test: bool
    package: str
    exact_imports: dict[str, str]  # symbol -> package
    wildcard_imports: set[str]
    declared_symbols: set[str]
    stem_symbol: str | None
    words: set[str]
    test_classes: list[str] = field(default_factory=list)
    raw_has_pkg_ref_cache: str = ""


def is_benign_path(path: str) -> bool:
    if path.startswith(BENIGN_PATH_PREFIXES):
        return True
    if path.endswith(".md") and "/" not in path:
        return True
    if path in (".gitignore", "LICENSE", "compose/shared/objc-entry-points.txt"):
        return True
    # Screenshot goldens do not affect plain JVM unit tests (Roborazzi is off on jvm tier).
    if path.endswith(".png") and "/screenshots/" in path:
        return True
    return False


def is_strings_xml_resource(path: str) -> bool:
    return (
        path.startswith("compose/shared-data/src/commonMain/composeResources/values")
        and path.endswith("/strings.xml")
    )


def classify_source_path(path: str) -> tuple[str, str, bool] | None:
    """Return (module, source_set, is_test) if path is a Kotlin/Java file in a module src dir."""
    parts = path.split("/")
    if len(parts) < 5 or parts[0] != "compose" or parts[2] != "src":
        return None
    mod = parts[1]
    src_set = parts[3]
    if mod not in KNOWN_MODULES:
        return None
    if not (path.endswith(".kt") or path.endswith(".java")):
        return None
    # Non-code resources / sqldelight under src/ are handled as global fail-closed.
    if len(parts) >= 5 and parts[4] not in ("kotlin", "java"):
        return None
    if src_set in PROD_SOURCE_SETS or src_set in NON_ANDROID_PROD_SOURCE_SETS:
        return (mod, src_set, False)
    if src_set in TEST_SOURCE_SETS:
        return (mod, src_set, True)
    return None


def parse_smoke_tests(repo_root: pathlib.Path, smoke_file: pathlib.Path | None = None) -> dict[str, list[str]]:
    path = smoke_file or (repo_root / "scripts/ci/always_run_smoke_tests.txt")
    result: dict[str, list[str]] = defaultdict(list)
    if not path.is_file():
        return result
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if not line.startswith(":") or line.count(":") < 2:
            raise ValueError(f"Invalid smoke test line (expected :module:fqcn): {line!r}")
        _, mod, fqcn = line.split(":", 2)
        result[mod].append(fqcn)
    return result


def index_repository(repo_root: pathlib.Path) -> tuple[list[IndexedFile], dict[str, int], dict[str, list[int]], dict[str, int]]:
    files: list[IndexedFile] = []
    by_rel: dict[str, int] = {}
    word_to_files: dict[str, list[int]] = defaultdict(list)
    decl_counts: dict[str, int] = defaultdict(int)

    def add_file(path: pathlib.Path, mod: str, is_test: bool) -> None:
        rel = path.relative_to(repo_root).as_posix()
        if rel in by_rel:
            return
        raw = path.read_text(encoding="utf-8", errors="ignore")
        code = strip_comments(raw)
        pkg_m = PACKAGE_RE.search(code)
        pkg = pkg_m.group(1) if pkg_m else ""

        exact_imports: dict[str, str] = {}
        wildcard_imports: set[str] = set()
        for imp_m in IMPORT_RE.finditer(code):
            imp = imp_m.group(1)
            if imp.endswith(".*"):
                wildcard_imports.add(imp[:-2])
            elif "." in imp:
                imp_pkg, imp_sym = imp.rsplit(".", 1)
                exact_imports[imp_sym] = imp_pkg

        declared: set[str] = set()
        for m in TOP_TYPE_RE.finditer(code):
            sym = m.group(1)
            if len(sym) >= 4 and sym not in GENERIC_SYMBOLS:
                declared.add(sym)
        for m in TOP_FN_VAL_RE.finditer(code):
            sym = m.group(1)
            if len(sym) >= 4 and sym not in GENERIC_SYMBOLS:
                declared.add(sym)

        stem = path.stem
        # Strip common platform suffixes like Foo.android / Foo.ios
        if "." in stem:
            stem = stem.split(".", 1)[0]
        stem_sym = stem if len(stem) >= 6 and stem not in GENERIC_SYMBOLS else None

        words = set(WORD_RE.findall(code))

        test_classes: list[str] = []
        if is_test:
            has_test_annot = "@Test" in code or "@org.junit.Test" in code or "@kotlin.test.Test" in code
            for m in TEST_CLASS_DECL_RE.finditer(code):
                cls_name = m.group(1)
                if has_test_annot or cls_name.endswith("Test") or cls_name.endswith("Tests"):
                    fqcn = f"{pkg}.{cls_name}" if pkg else cls_name
                    test_classes.append(fqcn)

        fid = len(files)
        entry = IndexedFile(
            file_id=fid,
            rel_path=rel,
            module=mod,
            is_test=is_test,
            package=pkg,
            exact_imports=exact_imports,
            wildcard_imports=wildcard_imports,
            declared_symbols=declared,
            stem_symbol=stem_sym,
            words=words,
            test_classes=test_classes,
            raw_has_pkg_ref_cache=code,
        )
        files.append(entry)
        by_rel[rel] = fid
        for sym in declared:
            decl_counts[sym] += 1

    compose_dir = repo_root / "compose"
    for mod in INDEXED_PROD_MODULES:
        src_dir = compose_dir / mod / "src"
        if not src_dir.is_dir():
            continue
        for src_set in PROD_SOURCE_SETS:
            for lang in ("kotlin", "java"):
                root = src_dir / src_set / lang
                if not root.is_dir():
                    continue
                for p in sorted(root.rglob("*")):
                    if p.is_file() and p.suffix in (".kt", ".java"):
                        add_file(p, mod, is_test=False)

    for mod, test_roots in TEST_ROOTS_BY_MODULE.items():
        mod_dir = compose_dir / mod
        for rel_root in test_roots:
            root = mod_dir / rel_root
            if not root.is_dir():
                continue
            for p in sorted(root.rglob("*")):
                if p.is_file() and p.suffix in (".kt", ".java"):
                    add_file(p, mod, is_test=True)

    # Index only words that appear as declared symbols anywhere (keeps index small & fast).
    all_declared = set(decl_counts.keys())
    for entry in files:
        for w in entry.words & all_declared:
            word_to_files[w].append(entry.file_id)

    return files, by_rel, word_to_files, decl_counts


def can_reference(
    consumer: IndexedFile,
    producer: IndexedFile,
    sym: str,
    decl_counts: dict[str, int],
) -> bool:
    if consumer.file_id == producer.file_id:
        return False
    if consumer.package == producer.package:
        return True
    if producer.package in consumer.wildcard_imports:
        return True
    if consumer.exact_imports.get(sym) == producer.package:
        return True
    # Fully qualified reference in code (e.g. build.raft.app.foo.Bar)
    if producer.package and f"{producer.package}.{sym}" in consumer.raw_has_pkg_ref_cache:
        return True
    # Unique CamelCase symbol declared in only 1 file across the indexed repo
    # (handles Kotlin companion/nested or import-alias patterns cleanly).
    if sym[0].isupper() and decl_counts.get(sym, 0) == 1:
        return True
    return False


def parse_changed_status_lines(lines: list[str]) -> list[tuple[str, str]]:
    """Parse `git diff --name-status --no-renames` lines into (status, path) tuples."""
    parsed: list[tuple[str, str]] = []
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if line == "__CI_CHANGED_PATHS_UNKNOWN__":
            parsed.append(("?", line))
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            status = parts[0].strip()
            path = parts[-1].strip()
            parsed.append((status, path))
        else:
            # Plain path fallback (treat as M)
            parsed.append(("M", line))
    return parsed


def select_tests(
    repo_root: pathlib.Path,
    changed_entries: list[tuple[str, str]],
    smoke_file: pathlib.Path | None = None,
) -> dict:
    def full_result(reason: str) -> dict:
        return {
            "mode": "full",
            "reason": reason,
            "run_core_tests": True,
            "run_realtime_socket_tests": True,
            "run_ui_cmp_tests": True,
            "run_web_container_tests": True,
            "run_shared_tests": True,
            "run_android_app_tests": True,
            "need_socketio_server": True,
            "selected_classes": {"shared": [], "androidApp": []},
            "stats": {},
        }

    if not changed_entries:
        return full_result("empty-changed-list")
    if len(changed_entries) > MAX_CHANGED_PATHS:
        return full_result(f"too-many-changed-files:{len(changed_entries)}")

    _, rev_trans = parse_module_deps(repo_root)

    prod_touched_modules: set[str] = set()
    test_touched_modules: set[str] = set()
    changed_prod_paths: list[str] = []
    changed_test_paths: list[str] = []
    touched_strings_xml = False

    for status, path in changed_entries:
        if path == "__CI_CHANGED_PATHS_UNKNOWN__":
            return full_result("changed-paths-unknown")
        if is_benign_path(path):
            continue
        if is_strings_xml_resource(path):
            if status.startswith("D"):
                return full_result(f"deleted-strings-resource:{path}")
            touched_strings_xml = True
            prod_touched_modules.add("shared-data")
            continue
        classified = classify_source_path(path)
        if classified is None:
            return full_result(f"non-source-or-global-path:{path}")
        mod, src_set, is_test = classified
        # Non-Android / instrumented-only source sets do not affect Android JVM tests.
        # Exceptions: `:web-container:desktopTest` runs inside the Android CI job gated on
        # `run_web_container_tests`, so desktop changes inside `:web-container` trigger only
        # `:web-container` tests without fanning out into Android dependents.
        if mod == "desktopApp":
            continue
        if src_set in ("iosMain", "nativeMain", "jsMain", "iosTest", "androidTest", "androidInstrumentedTest", "connectedTest"):
            continue
        if src_set in ("desktopMain", "desktopTest", "desktopVcase"):
            if mod == "web-container":
                test_touched_modules.add("web-container")
            continue
        if not is_test:
            if status.startswith("D"):
                return full_result(f"deleted-production-source:{path}")
            prod_touched_modules.add(mod)
            changed_prod_paths.append(path)
        else:
            test_touched_modules.add(mod)
            if not status.startswith("D"):
                changed_test_paths.append(path)

    # All changed files were benign or non-Android (e.g., docs/iOS/desktopMain) -> run smoke only?
    # Fail closed if Android job somehow ran with zero recognized Android changes.
    if not prod_touched_modules and not test_touched_modules:
        return full_result("no-android-source-changes")

    impacted_modules = set(prod_touched_modules) | set(test_touched_modules)
    for mod in prod_touched_modules:
        impacted_modules.update(rev_trans.get(mod, set()))

    # If the PR only touched standalone modules (e.g., web-container, core, realtime-socket, ui-cmp)
    # and neither :shared nor :androidApp is impacted, skip :shared & :androidApp test filtering
    # and run only the impacted standalone modules + smoke? Even simpler: :shared & :androidApp
    # will run just their fast smoke sets (~2s) unless untouched, or run smoke always!
    smoke_map = parse_smoke_tests(repo_root, smoke_file)

    files, by_rel, word_to_files, decl_counts = index_repository(repo_root)

    total_shared_test_classes = sum(
        len(f.test_classes) for f in files if f.is_test and f.module == "shared"
    )

    # Map changed paths to IndexedFiles
    seed_prod_ids: set[int] = set()
    for p in changed_prod_paths:
        if p in by_rel:
            seed_prod_ids.add(by_rel[p])
        else:
            # Module like :web-container or :desktopApp not indexed for :shared/:androidApp symbols
            mod = p.split("/")[1]
            if mod in INDEXED_PROD_MODULES:
                return full_result(f"missing-changed-prod-file:{p}")

    seed_test_ids: set[int] = set()
    for p in changed_test_paths:
        if p in by_rel:
            seed_test_ids.add(by_rel[p])

    def find_referencing_files(
        producer_id: int,
        want_test: bool,
    ) -> set[int]:
        prod = files[producer_id]
        hits: set[int] = set()
        for sym in prod.declared_symbols:
            for cid in word_to_files.get(sym, ()):
                cand = files[cid]
                if cand.is_test != want_test:
                    continue
                if can_reference(cand, prod, sym, decl_counts):
                    hits.add(cid)
        return hits

    # Check if any directly changed prod file has zero non-generic declared symbols or is a broad hub.
    hop1_prod_ids: set[int] = set()
    for pid in seed_prod_ids:
        prod = files[pid]
        if not prod.declared_symbols and not prod.stem_symbol:
            return full_result(f"no-specific-symbols:{prod.rel_path}")
        refs = find_referencing_files(pid, want_test=False)
        if len(refs) > DIRECT_HUB_FANOUT:
            return full_result(f"broad-hub-changed:{prod.rel_path}({len(refs)})")
        hop1_prod_ids.update(refs)

    # Hop 2 across production files (only through focused non-aggregator hop-1 files).
    hop2_prod_ids: set[int] = set()
    for pid in hop1_prod_ids - seed_prod_ids:
        if len(files[pid].declared_symbols) > HOP2_MAX_DECLS:
            continue
        refs = find_referencing_files(pid, want_test=False)
        if len(refs) <= HOP2_HUB_CAP:
            hop2_prod_ids.update(refs)

    impacted_prod_ids = seed_prod_ids | hop1_prod_ids | hop2_prod_ids

    # Select test files
    selected_test_ids: set[int] = set(seed_test_ids)

    # 1. Propagate through modified test-helper files (2 hops across test files)
    helper_frontier = set(seed_test_ids)
    for _ in range(2):
        next_frontier: set[int] = set()
        for tid in helper_frontier:
            for cid in find_referencing_files(tid, want_test=True):
                if cid not in selected_test_ids:
                    selected_test_ids.add(cid)
                    next_frontier.add(cid)
        helper_frontier = next_frontier

    # 2. Test files referencing directly changed production files (full symbol match)
    for pid in seed_prod_ids:
        selected_test_ids.update(find_referencing_files(pid, want_test=True))

    # 3. Test files referencing hop-1 / hop-2 production callers (skip giant multi-feature
    #    aggregators' unrelated symbols; match focused callers directly and aggregators by stem)
    indirect_stems: set[str] = set()
    for pid in (hop1_prod_ids | hop2_prod_ids) - seed_prod_ids:
        f = files[pid]
        if len(f.declared_symbols) <= HOP1_MAX_DECLS:
            selected_test_ids.update(find_referencing_files(pid, want_test=True))
        elif f.stem_symbol:
            indirect_stems.add(f.stem_symbol)

    # 4. Filename-stem affinity for directly changed production files (+ indirect aggregators)
    direct_stems = {
        files[pid].stem_symbol
        for pid in seed_prod_ids
        if files[pid].stem_symbol is not None
    } | indirect_stems
    if direct_stems:
        for entry in files:
            if not entry.is_test:
                continue
            fname = entry.rel_path.rsplit("/", 1)[-1]
            for stem in direct_stems:
                if stem in fname:
                    selected_test_ids.add(entry.file_id)
                    break

    selected_by_mod: dict[str, set[str]] = {"shared": set(), "androidApp": set()}
    for tid in selected_test_ids:
        entry = files[tid]
        if entry.module in selected_by_mod:
            selected_by_mod[entry.module].update(entry.test_classes)

    if touched_strings_xml:
        existing_shared_fqcns = {
            cls for entry in files if entry.is_test and entry.module == "shared" for cls in entry.test_classes
        }
        for cls in STRINGS_XML_TESTS:
            if cls in existing_shared_fqcns:
                selected_by_mod["shared"].add(cls)

    # Union always-run smoke tests
    for mod in ("shared", "androidApp"):
        if mod in impacted_modules or selected_by_mod[mod]:
            selected_by_mod[mod].update(smoke_map.get(mod, ()))
        else:
            # Even when only a leaf module like :web-container changed, run tiny smoke set?
            # Keeping :shared/:androidApp smoke empty when completely unimpacted lets us skip them,
            # OR running smoke only when impacted. Running when impacted keeps standalone PRs super fast!
            pass

    if total_shared_test_classes > 0 and len(selected_by_mod["shared"]) > int(total_shared_test_classes * MAX_SHARED_RATIO):
        return full_result(
            f"shared-fanout-exceeded:{len(selected_by_mod['shared'])}/{total_shared_test_classes}"
        )

    run_core = "core" in impacted_modules
    run_realtime = "realtime-socket" in impacted_modules
    run_ui_cmp = "ui-cmp" in impacted_modules
    run_web_container = "web-container" in impacted_modules
    run_shared_tests = bool(selected_by_mod["shared"])
    run_android_app_tests = bool(selected_by_mod["androidApp"])
    need_socketio = run_realtime or (SOCKETIO_INTEROP_TEST in selected_by_mod["androidApp"])

    return {
        "mode": "selected",
        "reason": "changed-files-selected",
        "run_core_tests": run_core,
        "run_realtime_socket_tests": run_realtime,
        "run_ui_cmp_tests": run_ui_cmp,
        "run_web_container_tests": run_web_container,
        "run_shared_tests": run_shared_tests,
        "run_android_app_tests": run_android_app_tests,
        "need_socketio_server": need_socketio,
        "selected_classes": {
            "shared": sorted(selected_by_mod["shared"]),
            "androidApp": sorted(selected_by_mod["androidApp"]),
        },
        "stats": {
            "changed_prod_files": len(seed_prod_ids),
            "changed_test_files": len(seed_test_ids),
            "impacted_prod_files": len(impacted_prod_ids),
            "selected_shared_classes": len(selected_by_mod["shared"]),
            "total_shared_classes": total_shared_test_classes,
            "selected_android_app_classes": len(selected_by_mod["androidApp"]),
            "impacted_modules": sorted(impacted_modules),
        },
    }


def write_outputs(result: dict, output_dir: pathlib.Path, github_output: pathlib.Path | None) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    decision_path = output_dir / "decision.json"
    decision_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

    include_path = output_dir / "include-classes.txt"
    if result["mode"] == "selected":
        lines: list[str] = []
        for mod in ("shared", "androidApp"):
            for fqcn in result["selected_classes"].get(mod, ()):
                lines.append(f":{mod}:{fqcn}")
        include_path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    else:
        include_path.write_text("", encoding="utf-8")

    kv = {
        "selection_mode": result["mode"],
        "selection_reason": result["reason"],
        "include_classes_file": str(include_path) if result["mode"] == "selected" else "",
        "run_core_tests": str(result.get("run_core_tests", True)).lower(),
        "run_realtime_socket_tests": str(result.get("run_realtime_socket_tests", True)).lower(),
        "run_ui_cmp_tests": str(result.get("run_ui_cmp_tests", True)).lower(),
        "run_web_container_tests": str(result.get("run_web_container_tests", True)).lower(),
        "run_shared_tests": str(result.get("run_shared_tests", True)).lower(),
        "run_android_app_tests": str(result.get("run_android_app_tests", True)).lower(),
        "need_socketio_server": str(result.get("need_socketio_server", True)).lower(),
        "selected_shared_count": str(len(result["selected_classes"].get("shared", ()))),
        "selected_android_app_count": str(len(result["selected_classes"].get("androidApp", ()))),
    }
    if github_output is not None:
        with github_output.open("a", encoding="utf-8") as fh:
            for k, v in kv.items():
                fh.write(f"{k}={v}\n")


def run_self_test(repo_root: pathlib.Path) -> None:
    # 1. Verify every entry in always_run_smoke_tests.txt exists and is plain JVM (no Robolectric/screenshot).
    smoke_map = parse_smoke_tests(repo_root)
    assert smoke_map.get("shared"), "always_run_smoke_tests.txt must list :shared smoke classes"
    assert smoke_map.get("androidApp"), "always_run_smoke_tests.txt must list :androidApp smoke classes"

    files, _, _, _ = index_repository(repo_root)
    fqcn_to_file: dict[tuple[str, str], IndexedFile] = {}
    for entry in files:
        if entry.is_test:
            for cls in entry.test_classes:
                fqcn_to_file[(entry.module, cls)] = entry
    for mod, fqcns in smoke_map.items():
        for fqcn in fqcns:
            found = fqcn_to_file.get((mod, fqcn))
            assert found is not None, f"Smoke test missing from repo: :{mod}:{fqcn}"
            code = found.raw_has_pkg_ref_cache
            assert not ROBOLECTRIC_MARKER_RE.search(code), f"Smoke test uses Robolectric: :{mod}:{fqcn}"
            assert not SCREENSHOT_MARKER_RE.search(code), f"Smoke test uses screenshots: :{mod}:{fqcn}"

    # 2. Synthetic isolated repo tests
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        (root / "compose/core/build.gradle.kts").parent.mkdir(parents=True)
        (root / "compose/core/build.gradle.kts").write_text("// leaf\n", encoding="utf-8")
        (root / "compose/shared-data/build.gradle.kts").parent.mkdir(parents=True)
        (root / "compose/shared-data/build.gradle.kts").write_text('api(project(":core"))\n', encoding="utf-8")
        (root / "compose/shared/build.gradle.kts").parent.mkdir(parents=True)
        (root / "compose/shared/build.gradle.kts").write_text('api(project(":shared-data"))\n', encoding="utf-8")
        (root / "compose/androidApp/build.gradle.kts").parent.mkdir(parents=True)
        (root / "compose/androidApp/build.gradle.kts").write_text('implementation(project(":shared"))\n', encoding="utf-8")
        (root / "compose/web-container/build.gradle.kts").parent.mkdir(parents=True)
        (root / "compose/web-container/build.gradle.kts").write_text("// standalone\n", encoding="utf-8")

        def write(rel: str, content: str) -> None:
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")

        write(
            "scripts/ci/always_run_smoke_tests.txt",
            ":shared:build.raft.app.AlwaysSmokeTest\n:androidApp:build.raft.app.HostSmokeTest\n",
        )
        write(
            "compose/shared/src/commonMain/kotlin/build/raft/app/lounge/LoungeTabModel.kt",
            "package build.raft.app.lounge\nclass LoungeTabModel(val tab: Int)\n",
        )
        write(
            "compose/shared/src/commonMain/kotlin/build/raft/app/lounge/LoungeScreen.kt",
            "package build.raft.app.lounge\nfun renderLounge(m: LoungeTabModel) = m.tab\n",
        )
        write(
            "compose/shared/src/commonMain/kotlin/build/raft/app/other/OtherFeature.kt",
            "package build.raft.app.other\nclass OtherFeature\n",
        )
        write(
            "compose/shared/src/commonTest/kotlin/build/raft/app/AlwaysSmokeTest.kt",
            "package build.raft.app\nclass AlwaysSmokeTest { @Test fun ok() {} }\n",
        )
        write(
            "compose/shared/src/commonTest/kotlin/build/raft/app/lounge/LoungeTabModelTest.kt",
            "package build.raft.app.lounge\nclass LoungeTabModelTest { @Test fun t() { LoungeTabModel(1) } }\n",
        )
        write(
            "compose/shared/src/commonTest/kotlin/build/raft/app/lounge/LoungeScreenTest.kt",
            "package build.raft.app.lounge\nclass LoungeScreenTest { @Test fun t() { renderLounge(LoungeTabModel(1)) } }\n",
        )
        # Add 10 unrelated tests so ratio stays under MAX_SHARED_RATIO
        for i in range(10):
            write(
                f"compose/shared/src/commonTest/kotlin/build/raft/app/other/Unrelated{i}Test.kt",
                f"package build.raft.app.other\nclass Unrelated{i}Test {{ @Test fun t() {{ OtherFeature() }} }}\n",
            )
        write(
            "compose/shared/src/commonTest/kotlin/build/raft/app/other/OtherHelper.kt",
            "package build.raft.app.other\nfun makeOtherFixture() = OtherFeature()\n",
        )
        write(
            "compose/shared/src/commonTest/kotlin/build/raft/app/other/UsesHelperTest.kt",
            "package build.raft.app.other\nclass UsesHelperTest { @Test fun t() { makeOtherFixture() } }\n",
        )
        write(
            "compose/androidApp/src/test/kotlin/build/raft/app/HostSmokeTest.kt",
            "package build.raft.app\nclass HostSmokeTest { @Test fun ok() {} }\n",
        )
        write(
            "compose/web-container/src/commonMain/kotlin/build/raft/web/WebGuard.kt",
            "package build.raft.web\nclass WebGuard\n",
        )

        # Case A: editing LoungeTabModel + a changeset selects Lounge tests + smoke, skips unrelated & leaf modules
        res = select_tests(
            root,
            [
                ("M", "compose/shared/src/commonMain/kotlin/build/raft/app/lounge/LoungeTabModel.kt"),
                ("A", "change-logs/1.30.0/changesets/lounge.md"),
            ],
        )
        assert res["mode"] == "selected", res
        assert "build.raft.app.lounge.LoungeTabModelTest" in res["selected_classes"]["shared"], res
        assert "build.raft.app.lounge.LoungeScreenTest" in res["selected_classes"]["shared"], res
        assert "build.raft.app.AlwaysSmokeTest" in res["selected_classes"]["shared"], res
        assert "build.raft.app.other.Unrelated0Test" not in res["selected_classes"]["shared"], res
        assert res["run_web_container_tests"] is False, res
        assert res["run_core_tests"] is False, res
        assert res["need_socketio_server"] is False, res

        # Case B: editing a test helper propagates to tests using that helper
        res_helper = select_tests(
            root,
            [("M", "compose/shared/src/commonTest/kotlin/build/raft/app/other/OtherHelper.kt")],
        )
        assert res_helper["mode"] == "selected", res_helper
        assert "build.raft.app.other.UsesHelperTest" in res_helper["selected_classes"]["shared"], res_helper
        assert "build.raft.app.lounge.LoungeTabModelTest" not in res_helper["selected_classes"]["shared"], res_helper

        # Case C: editing standalone web-container only runs web-container (skips shared/androidApp)
        res_web = select_tests(
            root,
            [("M", "compose/web-container/src/commonMain/kotlin/build/raft/web/WebGuard.kt")],
        )
        assert res_web["mode"] == "selected", res_web
        assert res_web["run_web_container_tests"] is True, res_web
        assert res_web["run_shared_tests"] is False, res_web
        assert res_web["run_android_app_tests"] is False, res_web

        # Case D: deleting a production file fails closed to full
        res_del = select_tests(
            root,
            [("D", "compose/shared/src/commonMain/kotlin/build/raft/app/lounge/LoungeTabModel.kt")],
        )
        assert res_del["mode"] == "full" and "deleted-production-source" in res_del["reason"], res_del

        # Case E: touching strings.xml keeps selected mode + adds resource tests; touching non-strings resource fails closed
        res_str = select_tests(
            root,
            [
                ("M", "compose/shared/src/commonMain/kotlin/build/raft/app/lounge/LoungeTabModel.kt"),
                ("M", "compose/shared-data/src/commonMain/composeResources/values/strings.xml"),
                ("M", "compose/shared/src/androidUnitTest/screenshots/lounge.png"),
            ],
        )
        assert res_str["mode"] == "selected", res_str

        res_res = select_tests(
            root,
            [("M", "compose/shared-data/src/commonMain/composeResources/drawable/icon.webp")],
        )
        assert res_res["mode"] == "full", res_res

        # Case F: unknown diff sentinel fails closed to full
        res_unk = select_tests(root, [("?", "__CI_CHANGED_PATHS_UNKNOWN__")])
        assert res_unk["mode"] == "full", res_unk

        # Case G: non-Android platform source sets (iosMain/nativeMain/desktopMain/jsMain/iosTest/desktopApp)
        # and compose/shared/objc-entry-points.txt do not force full mode when paired with an Android change
        res_cross = select_tests(
            root,
            [
                ("M", "compose/shared/src/commonMain/kotlin/build/raft/app/lounge/LoungeTabModel.kt"),
                ("M", "compose/shared/src/iosMain/kotlin/build/raft/app/IosBridge.kt"),
                ("M", "compose/shared-data/src/nativeMain/kotlin/build/raft/app/NativeHelper.kt"),
                ("M", "compose/shared/src/desktopMain/kotlin/build/raft/app/DesktopBridge.kt"),
                ("M", "compose/shared/src/jsMain/kotlin/build/raft/app/JsStub.kt"),
                ("A", "compose/shared/src/iosTest/kotlin/build/raft/app/IosBridgeTest.kt"),
                ("M", "compose/desktopApp/src/main/kotlin/build/raft/app/desktop/App.kt"),
                ("M", "compose/shared/objc-entry-points.txt"),
            ],
        )
        assert res_cross["mode"] == "selected", res_cross
        assert "build.raft.app.lounge.LoungeTabModelTest" in res_cross["selected_classes"]["shared"], res_cross
        assert res_cross["run_web_container_tests"] is False, res_cross

        # Case H: web-container desktopMain/desktopTest runs :web-container tests (since Android CI runs
        # :web-container:desktopTest) without fanning out into :shared or :androidApp
        res_wc_desktop = select_tests(
            root,
            [("M", "compose/web-container/src/desktopMain/kotlin/build/raft/web/DesktopHost.kt")],
        )
        assert res_wc_desktop["mode"] == "selected", res_wc_desktop
        assert res_wc_desktop["run_web_container_tests"] is True, res_wc_desktop
        assert res_wc_desktop["run_shared_tests"] is False, res_wc_desktop
        assert res_wc_desktop["run_android_app_tests"] is False, res_wc_desktop

        # Case I: non-Android-only PR still fails closed safely if Android job runs
        res_ios_only = select_tests(
            root,
            [
                ("M", "compose/shared/src/iosMain/kotlin/build/raft/app/IosBridge.kt"),
                ("M", "compose/shared/objc-entry-points.txt"),
            ],
        )
        assert res_ios_only["mode"] == "full" and res_ios_only["reason"] == "no-android-source-changes", res_ios_only

    print(
        f"select_changed_tests self-test: PASS "
        f"(verified {len(smoke_map['shared'])} :shared + {len(smoke_map['androidApp'])} :androidApp smoke classes)"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".", help="Repository root path")
    parser.add_argument("--changed-status-file", help="File containing `git diff --name-status --no-renames` lines")
    parser.add_argument("--changed-status-b64", help="Base64-encoded `git diff --name-status --no-renames` lines")
    parser.add_argument("--base-ref", help="Git base ref to diff against HEAD (merge-base `base...HEAD`)")
    parser.add_argument("--audit-commit", help="Audit selection and recall against historical commit SHA^..SHA")
    parser.add_argument("--output-dir", help="Directory to write decision.json and include-classes.txt")
    parser.add_argument("--github-output", help="Optional path to append key=value step outputs")
    parser.add_argument("--self-test", action="store_true", help="Run built-in self-test")
    args = parser.parse_args()

    repo_root = pathlib.Path(args.repo_root).resolve()
    if args.self_test:
        run_self_test(repo_root)
        return 0

    lines: list[str] = []
    if args.audit_commit:
        out = subprocess.check_output(
            ["git", "-C", str(repo_root), "diff", "--name-status", "--no-renames", f"{args.audit_commit}^..{args.audit_commit}"],
            text=True,
        )
        lines = out.splitlines()
        raw_entries = parse_changed_status_lines(lines)
        # For historical pre-#5683 commits, remap moved compose/shared -> compose/shared-data paths if present on HEAD.
        entries: list[tuple[str, str]] = []
        for s, p in raw_entries:
            if not (repo_root / p).exists() and p.startswith("compose/shared/src/"):
                cand = "compose/shared-data/src/" + p[len("compose/shared/src/"):]
                if (repo_root / cand).exists():
                    p = cand
            entries.append((s, p))
        res = select_tests(repo_root, entries)
        # Leave-one-out recall check: hide changed test files to see if prod->test graph still catches them.
        prod_only = [(s, p) for s, p in entries if not (classify_source_path(p) and classify_source_path(p)[2])]
        changed_test_paths = [p for s, p in entries if (classify_source_path(p) and classify_source_path(p)[2]) and not s.startswith("D")]
        res_loo = select_tests(repo_root, prod_only) if prod_only else None
        # Compute how many changed test files' classes are covered by LOO
        loo_covered = 0
        loo_total_tests = 0
        if res_loo and changed_test_paths:
            all_loo = set(res_loo["selected_classes"]["shared"]) | set(res_loo["selected_classes"]["androidApp"])
            files_idx, by_rel_idx, _, _ = index_repository(repo_root)
            for tp in changed_test_paths:
                if tp in by_rel_idx:
                    tclasses = files_idx[by_rel_idx[tp]].test_classes
                    for tc in tclasses:
                        loo_total_tests += 1
                        if res_loo["mode"] == "full" or tc in all_loo:
                            loo_covered += 1
        print(json.dumps({
            "commit": args.audit_commit,
            "mode": res["mode"],
            "reason": res["reason"],
            "stats": res["stats"],
            "run_modules": {
                k: res.get(k) for k in (
                    "run_core_tests", "run_realtime_socket_tests", "run_ui_cmp_tests",
                    "run_web_container_tests", "run_shared_tests", "run_android_app_tests",
                    "need_socketio_server",
                )
            },
            "loo_mode": res_loo["mode"] if res_loo else None,
            "loo_shared_selected": len(res_loo["selected_classes"]["shared"]) if res_loo else None,
            "loo_recall": f"{loo_covered}/{loo_total_tests}" if loo_total_tests else "n/a",
        }, indent=2))
        return 0

    if args.changed_status_b64 is not None:
        if args.changed_status_b64.strip():
            try:
                decoded = base64.b64decode(args.changed_status_b64.strip()).decode("utf-8")
                lines = decoded.splitlines()
            except Exception:
                lines = ["__CI_CHANGED_PATHS_UNKNOWN__"]
    elif args.changed_status_file:
        p = pathlib.Path(args.changed_status_file)
        if p.is_file():
            lines = p.read_text(encoding="utf-8", errors="ignore").splitlines()
        else:
            lines = ["__CI_CHANGED_PATHS_UNKNOWN__"]
    elif args.base_ref:
        try:
            out = subprocess.check_output(
                ["git", "-C", str(repo_root), "diff", "--name-status", "--no-renames", f"{args.base_ref}...HEAD"],
                text=True,
            )
            lines = out.splitlines()
        except subprocess.CalledProcessError:
            lines = ["__CI_CHANGED_PATHS_UNKNOWN__"]

    entries = parse_changed_status_lines(lines)
    result = select_tests(repo_root, entries)

    if args.output_dir:
        gh_out = pathlib.Path(args.github_output) if args.github_output else None
        write_outputs(result, pathlib.Path(args.output_dir), gh_out)

    print(
        f"select_changed_tests: mode={result['mode']} reason={result['reason']} "
        f"shared={len(result['selected_classes'].get('shared', ()))} "
        f"androidApp={len(result['selected_classes'].get('androidApp', ()))} "
        f"modules=[core={result.get('run_core_tests', True)},realtime={result.get('run_realtime_socket_tests', True)},"
        f"ui_cmp={result.get('run_ui_cmp_tests', True)},web_container={result.get('run_web_container_tests', True)}] "
        f"socketio={result.get('need_socketio_server', True)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
