#!/usr/bin/env python3
"""Generate the Kotlin/Native Objective-C export entry points for the iOS `shared` framework.

Kotlin/Native exports every public declaration of the framework's own module (and of every
`export(...)`ed module) to Objective-C, with a bridge for each member, and keeps all of it alive
through dead-code elimination. `-Xbinary=objcExportEntryPointsPath=<file>` narrows that to the
callables listed in the file (format: `callable|function|property <fq.Name.member or .*>`, one per
line; see JetBrains/kotlin native/objcexport-header-generator ObjCEntryPoints.kt).

The Swift app (compose/iosApp) is the only Objective-C/Swift consumer, so the list is what Swift
names: every Kotlin class, object or interface it mentions (all members: Swift may implement an
interface, so each of its methods must stay callable from Kotlin), and every top-level function or
property it calls through a `<File>Kt` facade. Run it whenever Swift starts using new Kotlin API;
CI's iOS compile fails on a member Swift uses that the list leaves out.

Usage: objc_entry_points.py [--check] [--out compose/shared/objc-entry-points.txt]
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KOTLIN_ROOTS = [
    "compose/shared/src/commonMain/kotlin",
    "compose/shared/src/iosMain/kotlin",
    "compose/shared/src/nativeMain/kotlin",
    "compose/ui-cmp/src/commonMain/kotlin",
    "compose/ui-cmp/src/iosMain/kotlin",
    "third_party/kuikly-compose-markdown/src/commonMain/kotlin",
    "third_party/markdown-parser/src/commonMain/kotlin",
]
SWIFT_ROOT = "compose/iosApp"
# Kotlin standard library and kotlinx types Swift may use through their K/N Objective-C names.
STDLIB_PREFIXES = {"Kotlin": "kotlin"}

DECL = re.compile(
    r"^(?P<indent>\s*)(?P<mods>(?:(?:public|internal|private|protected|data|sealed|abstract|open|enum|annotation|value|inline|expect|actual|fun|companion|inner|external)\s+)*)"
    r"(?P<kind>class|interface|object)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)
TOP_FUN = re.compile(
    r"^(?P<mods>(?:(?:public|internal|private|inline|suspend|operator|infix|tailrec|external|expect|actual|@[A-Za-z.]+(?:\([^)]*\))?)\s+)*)"
    r"(?:fun|val|var|const\s+val)\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>?, ]+\.)?(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)


def kotlin_files():
    for root in KOTLIN_ROOTS:
        base = os.path.join(ROOT, root)
        for dirpath, _, files in os.walk(base):
            for f in files:
                if f.endswith(".kt"):
                    yield os.path.join(dirpath, f)


def scan_kotlin():
    """Public classes by simple name -> set of fq names; facades `<File>Kt` -> (package, top-level names)."""
    classes = {}
    facades = {}
    for path in kotlin_files():
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        pkg_match = re.search(r"^package\s+([A-Za-z0-9_.]+)", text, re.M)
        pkg = pkg_match.group(1) if pkg_match else ""
        jvm_name = re.search(r'^@file:JvmName\("([A-Za-z0-9_]+)"\)', text, re.M)
        facade = (jvm_name.group(1) if jvm_name else os.path.basename(path)[:-3] + "Kt")
        # Class nesting by brace depth (good enough for this codebase's formatting).
        stack = []  # (name, depth)
        depth = 0
        for line in text.split("\n"):
            stripped = line.strip()
            if not stripped.startswith("//") and not stripped.startswith("*"):
                m = DECL.match(line)
                if m and "private" not in m.group("mods").split() and "internal" not in m.group("mods").split():
                    while stack and stack[-1][1] >= depth:
                        stack.pop()
                    outer = [n for n, _ in stack]
                    fq = ".".join([p for p in [pkg] if p] + outer + [m.group("name")])
                    classes.setdefault(m.group("name"), set()).add(fq)
                    stack.append((m.group("name"), depth))
                elif depth == 0:
                    t = TOP_FUN.match(line)
                    if t and "private" not in t.group("mods").split() and "internal" not in t.group("mods").split():
                        facades.setdefault(facade, (pkg, set()))[1].add(t.group("name"))
            depth += line.count("{") - line.count("}")
            while stack and stack[-1][1] >= depth and depth >= 0:
                # a class body closed
                if line.count("}") and stack[-1][1] >= depth:
                    stack.pop()
                else:
                    break
    return classes, facades


def swift_sources():
    base = os.path.join(ROOT, SWIFT_ROOT)
    for dirpath, _, files in os.walk(base):
        if "/Pods" in dirpath or "/build" in dirpath:
            continue
        for f in files:
            if f.endswith(".swift"):
                yield os.path.join(dirpath, f)


def objc_sources():
    base = os.path.join(ROOT, SWIFT_ROOT)
    for dirpath, _, files in os.walk(base):
        if "/Pods" in dirpath or "/build" in dirpath:
            continue
        for f in files:
            if f.endswith((".m", ".mm", ".h")):
                yield os.path.join(dirpath, f)


def strip_swift_comments_and_strings(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"""(?:.|\n)*?"""', '""', text)
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="compose/shared/objc-entry-points.txt")
    ap.add_argument("--check", action="store_true", help="fail when the committed file is not current")
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()
    classes, facades = scan_kotlin()
    used_classes = set()
    used_top = set()
    unknown_facades = set()
    unknown_objc = set()
    for path in swift_sources():
        with open(path, encoding="utf-8") as fh:
            text = strip_swift_comments_and_strings(fh.read())
        for m in re.finditer(r"\b([A-Z][A-Za-z0-9_]*)Kt\.([A-Za-z_][A-Za-z0-9_]*)", text):
            facade = m.group(1) + "Kt"
            if facade in facades:
                pkg, names = facades[facade]
                used_top.add((pkg, m.group(2)))
            else:
                unknown_facades.add(facade)
        for m in re.finditer(r"\b([A-Z][A-Za-z0-9_]*)(?:\.([A-Z][A-Za-z0-9_]*))?", text):
            name = m.group(1)
            if name in classes:
                for fq in classes[name]:
                    used_classes.add(fq)
                inner = m.group(2)
                if inner and inner in classes:
                    for fq in classes[inner]:
                        if fq.endswith("." + name + "." + inner):
                            used_classes.add(fq)
            elif name.startswith("Kotlin") and len(name) > 6 and name[6].isupper():
                used_classes.add("kotlin." + name[6:])
    # Objective-C files of the host app use the framework's prefixed names (SharedFoo, SharedFooCompanion,
    # SharedFooKt): a class keeps all its members; a facade keeps all its top-level functions.
    for path in objc_sources():
        with open(path, encoding="utf-8") as fh:
            text = re.sub(r"//[^\n]*", " ", re.sub(r"/\*.*?\*/", " ", fh.read(), flags=re.S))
        for m in re.finditer(r"\bShared([A-Z][A-Za-z0-9_]*)", text):
            name = m.group(1)
            if name.endswith("Kt") and name in facades:
                pkg, names = facades[name]
                used_top.update((pkg, n) for n in names)
                continue
            base = name[: -len("Companion")] if name.endswith("Companion") and name[: -len("Companion")] in classes else name
            if base in classes:
                used_classes.update(classes[base])
            elif not name.endswith("Kt"):
                unknown_objc.add(name)
    lines = [
        "// Generated by scripts/ios/objc_entry_points.py from the Swift app's use of Kotlin API.",
        "// Kotlin/Native exports only these callables to Objective-C (-Xbinary=objcExportEntryPointsPath).",
    ]
    for fq in sorted(used_classes):
        lines.append(f"callable {fq}.*")
        lines.append(f"callable {fq}.Companion.*")
    for pkg, name in sorted(used_top):
        lines.append(f"callable {pkg + '.' if pkg else ''}{name}")
    content = "\n".join(lines) + "\n"
    out = os.path.join(ROOT, args.out)
    if args.check:
        current = open(out, encoding="utf-8").read() if os.path.exists(out) else ""
        if current != content:
            print(f"{args.out} is out of date: run scripts/ios/objc_entry_points.py", file=sys.stderr)
            return 1
        return 0
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(content)
    if args.report or True:
        print(f"classes: {len(used_classes)}  top-level: {len(used_top)}  unknown facades: {sorted(unknown_facades)}  unknown objc: {sorted(unknown_objc)}")
        print(f"kotlin public classes scanned: {sum(len(v) for v in classes.values())}, facades: {len(facades)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
