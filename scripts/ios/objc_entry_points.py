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
property it calls through a `<File>Kt` facade. Swift also uses Kotlin types it never names (a value
whose type it infers, an argument a Kotlin callback hands it, a member inherited from a Kotlin
supertype), so every type the exported API of a listed type mentions is listed too, transitively.
Run it whenever Swift starts using new Kotlin API; CI's iOS compile fails on a member Swift uses
that the list leaves out.

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
    "compose/shared-data/src/commonMain/kotlin",
    "compose/shared-data/src/iosMain/kotlin",
    "compose/shared-data/src/nativeMain/kotlin",
    "compose/ui-cmp/src/commonMain/kotlin",
    "compose/ui-cmp/src/iosMain/kotlin",
    "third_party/kuikly-compose-markdown/src/commonMain/kotlin",
    "third_party/markdown-parser/src/commonMain/kotlin",
]
# Modules the framework does not export: Kotlin/Native still translates a class of theirs that the
# exported API mentions (`ChannelId` of :core, the type of RaftRealtimeAppScopeRoomCommandDecision's
# `channelId`), its members filtered by this list like any other. Scanned for classes only: their
# top-level functions are never exported.
REFERENCED_ROOTS = [
    "compose/core/src/commonMain/kotlin",
    "compose/core/src/iosMain/kotlin",
    "compose/designsystem/src/commonMain/kotlin",
    "compose/designsystem/src/iosMain/kotlin",
    "compose/realtime-socket/src/commonMain/kotlin",
    "compose/realtime-socket/src/iosMain/kotlin",
]
SWIFT_ROOT = "compose/iosApp"
# Kotlin standard library types Swift may use through their K/N Objective-C names without naming
# them (a value whose type Swift infers): their members stay exported.
ALWAYS_CLASSES = {
    "kotlin.Array", "kotlin.ByteArray", "kotlin.IntArray", "kotlin.LongArray", "kotlin.ShortArray",
    "kotlin.FloatArray", "kotlin.DoubleArray", "kotlin.BooleanArray", "kotlin.CharArray",
    "kotlin.Throwable", "kotlin.Exception", "kotlin.RuntimeException", "kotlin.IllegalStateException",
    "kotlin.IllegalArgumentException", "kotlin.Pair", "kotlin.Triple",
}
# Any's members: never entry points of an interface (Kotlin/Native leaves them out of a protocol).
ANY_MEMBERS = {"equals", "hashCode", "toString"}

ANNOTATION = r"@[A-Za-z_][\w.:]*(?:\([^()]*\))?"
HIDDEN = {"private", "internal", "protected"}
DECL = re.compile(
    r"^\s*(?P<mods>(?:(?:" + ANNOTATION + r"|public|internal|private|protected|data|sealed|abstract|open|enum|annotation|value|inline|expect|actual|fun|companion|inner|external)\s+)*)"
    r"(?P<kind>class|interface|object)\b\s*(?P<name>[A-Za-z_][A-Za-z0-9_]*)?"
)
TOP_FUN = re.compile(
    r"^(?P<mods>(?:(?:public|internal|private|inline|suspend|operator|infix|tailrec|external|expect|actual|@[A-Za-z.]+(?:\([^)]*\))?)\s+)*)"
    r"(?:fun|val|var|const\s+val)\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>?, ]+\.)?(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)


def kotlin_files():
    """(path, exported) of every Kotlin source file scanned."""
    for root in KOTLIN_ROOTS + REFERENCED_ROOTS:
        base = os.path.join(ROOT, root)
        for dirpath, _, files in os.walk(base):
            for f in files:
                if f.endswith(".kt"):
                    yield os.path.join(dirpath, f), root in KOTLIN_ROOTS


MEMBER = re.compile(
    r"^\s*(?P<mods>(?:(?:" + ANNOTATION + r"|override|suspend|abstract|open|final|public|internal|private|protected|operator|infix|inline|external|actual|expect|const|lateinit|tailrec)\s+)*)"
    r"(?P<kind>fun|val|var|constructor)\b(?:\s*<[^>]*>)?\s*(?:[A-Za-z0-9_.<>?, *]+\.)?(?P<name>[A-Za-z_][A-Za-z0-9_]*)?"
)
# A type reference in a declaration: `Name`, `Outer.Inner`, or fully qualified `pkg.Name`.
TYPE_REF = re.compile(r"(?<![\w.])((?:[a-z_]\w*\.)*)([A-Z]\w*(?:\.[A-Z]\w*)*)")


def strip_kotlin(text):
    """Comments and string/char literal contents out, line breaks kept: a brace or parenthesis in
    either would throw off the depth counts that tell members and nested classes apart."""
    def blank(m):
        s = m.group(0)
        return ('""' if s[0] == '"' else "''" if s[0] == "'" else "") + "\n" * s.count("\n")
    return re.sub(r'//[^\n]*|/\*.*?\*/|"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])+\'', blank, text, flags=re.S)


def scan_kotlin():
    """Public classes by fq name -> {kind, members, sigs, scope}; facades `<File>Kt` -> (package, top-level names);
    top-level callables (package, name) -> sigs.

    `members`: an interface's own declared members (kind, name). `sigs`: (file context, text) of the
    class header and of its public members' declarations, whose types Swift can reach (resolved by
    `exported_types` in the context (package, explicit imports, star imports) and the enclosing
    classes `scope`)."""
    decls = {}
    facades = {}
    toplevel = {}
    for path, exported in kotlin_files():
        with open(path, encoding="utf-8") as fh:
            raw = fh.read()
        text = strip_kotlin(raw)
        pkg_match = re.search(r"^package\s+([A-Za-z0-9_.]+)", text, re.M)
        pkg = pkg_match.group(1) if pkg_match else ""
        jvm_name = re.search(r'^@file:JvmName\("([A-Za-z0-9_]+)"\)', raw, re.M)
        facade = (jvm_name.group(1) if jvm_name else os.path.basename(path)[:-3] + "Kt")
        imports, stars = {}, []
        for m in re.finditer(r"^import\s+([\w.]+?)(\.\*)?(?:\s+as\s+(\w+))?\s*$", text, re.M):
            if m.group(2):
                stars.append(m.group(1))
            else:
                imports[m.group(3) or m.group(1).rsplit(".", 1)[-1]] = m.group(1)
        ctx = (pkg, imports, stars)
        # Class nesting by brace depth, private and internal classes included so that what they
        # nest gets its real fq name (and stays out: it is not public either).
        stack = []  # {"fq", "depth", "public", "opened" (its body began)}
        depth = paren = 0
        sig = None  # (sig list, lines) of a declaration whose parentheses are still open
        prev = ""
        for line in text.split("\n"):
            s = line.strip()
            if sig is None and s and paren == 0:  # no declaration starts inside parentheses
                # Leave the classes this line is outside of: a closed body, or a body-less class
                # (`data class Ready(val candidate: C) : Finish`) once its header is over.
                while stack and depth <= stack[-1]["depth"] and (
                    stack[-1]["opened"] or not (prev.endswith((":", ",")) or s.startswith((":", ",", "{", "where")))
                ):
                    stack.pop()
                top = stack[-1] if stack else None
                body = depth == 0 if top is None else top["opened"] and depth == top["depth"] + 1
                m = DECL.match(line) if body else None
                mods = set(m.group("mods").split()) if m else set()
                if m and (m.group("name") or "companion" in mods):
                    fq = ".".join(p for p in [top["fq"] if top else pkg, m.group("name") or "Companion"] if p)
                    public = (top is None or top["public"]) and not mods & HIDDEN
                    stack.append({"fq": fq, "depth": depth, "public": public, "opened": False})
                    if public:
                        d = decls.setdefault(fq, {"kind": m.group("kind"), "members": set(), "sigs": []})
                        d["scope"] = [e["fq"] for e in stack]
                        sig = (d["sigs"], [])
                elif top is not None and body:
                    mm = MEMBER.match(line)
                    if mm and top["public"] and not set(mm.group("mods").split()) & HIDDEN:
                        d = decls[top["fq"]]
                        if mm.group("name") and mm.group("kind") != "constructor":
                            d["members"].add(("function" if mm.group("kind") == "fun" else "property", mm.group("name")))
                        sig = (d["sigs"], [])
                elif top is None and depth == 0 and exported:
                    t = TOP_FUN.match(line)
                    if t and "private" not in t.group("mods").split() and "internal" not in t.group("mods").split():
                        facades.setdefault(facade, (pkg, set()))[1].add(t.group("name"))
                        sig = (toplevel.setdefault((pkg, t.group("name")), []), [])
            if sig is not None:
                sig[1].append(line)
            depth += line.count("{") - line.count("}")
            paren = max(0, paren + line.count("(") + line.count("[") - line.count(")") - line.count("]"))
            if stack and not stack[-1]["opened"] and depth > stack[-1]["depth"]:
                stack[-1]["opened"] = True
            if sig is not None and paren == 0 and not s.endswith((",", ":")):  # `) : Base(),` continues
                sig[0].append((ctx, " ".join(sig[1])))
                sig = None
            if s:
                prev = s
    return decls, facades, toplevel


def exported_types(decl, known):
    """fq names of our public classes that `decl`'s header and public member declarations mention.

    Each declaration counts up to its body, or up to its initializer when its type is written out
    (an inferred type is read off the initializer), without a constructor's `: this(...)` call; a
    class header without a public constructor counts only its supertypes and public `val`/`var`
    parameters (Objective-C sees neither the call nor such a constructor). Names resolve the way
    Kotlin looks them up: nested classes of the enclosing classes, explicit imports, the package,
    star imports."""
    found = set()
    for (pkg, imports, stars), text in decl["sigs"]:
        text = re.sub(ANNOTATION, " ", text).replace("->", " ")
        text = re.sub(r"\)\s*:\s*(?:this|super)\s*\(.*", ")", text, flags=re.S)
        level = 0
        for i, c in enumerate(text):
            level += (c in "([") - (c in ")]")
            if level == 0 and c == "{":
                text = text[:i]
                break
            if level == 0 and c == "=" and i and text[i + 1:i + 2] != "=" and text[i - 1] not in "!<>=":
                head = text[:i]
                while re.search(r"\([^()]*\)|<[^<>()]*>", head):  # parameter lists and type arguments out
                    head = re.sub(r"\([^()]*\)|<[^<>()]*>", "", head)
                if ":" in head:
                    text = text[:i]
                break
        ctor = re.search(r"\b(?:private|internal|protected)\s+constructor\s*\(", text)
        if ctor:
            start, level = ctor.end(), 1
            for end in range(start, len(text)):
                level += (text[end] in "([") - (text[end] in ")]")
                if level == 0:
                    break
            params, part, level = [], "", 0
            for c in text[start:end] + ",":
                level += (c in "([<") - (c in ")]>")
                if c == "," and level == 0:
                    params.append(part)
                    part = ""
                else:
                    part += c
            kept = [p for p in params if re.match(r"\s*(?:\w+\s+)*(?:val|var)\b", p) and not set(p.split()) & HIDDEN]
            text = text[:start] + ",".join(kept) + text[end:]
        for m in TYPE_REF.finditer(text):
            if m.group(1):
                found.add(m.group(1) + m.group(2))
                continue
            head, _, rest = m.group(2).partition(".")
            levels = [[f"{s}.{head}"] for s in reversed(decl["scope"])]
            levels.append([imports[head]] if head in imports else [])
            if head not in imports:
                levels += [[f"{pkg}.{head}" if pkg else head], [f"{p}.{head}" for p in stars]]
            for level_names in levels:
                hits = [n for n in level_names if n in known]
                if hits:
                    found.update(f"{n}.{rest}" if rest else n for n in hits)
                    break
    return {n for n in found if n in known}


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
    decls, facades, toplevel = scan_kotlin()
    # Swift and Objective-C name a Kotlin class by its Objective-C name minus the `Shared` prefix:
    # a top-level class by its own name, a nested class after its outer one, `Outer.Inner` (Swift,
    # when the outer is a class) or `OuterInner` (Swift when the outer is an interface or itself
    # nested, e.g. `RaftAvatarNativeStageOpenOpened`; Objective-C always). Both forms meet in the
    # joined name (Swift's dotted form is joined below). A bare inner name (`Error`, `Content`) is
    # never Kotlin's: it is Swift's or Foundation's own type.
    classes = {}  # Swift/Objective-C name -> fq names
    for fq, d in decls.items():
        names = [e.rsplit(".", 1)[-1] for e in d["scope"]]
        classes.setdefault(names[0] + "".join(n[:1].upper() + n[1:] for n in names[1:]), set()).add(fq)
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
            name = m.group(1).rstrip("_")  # Kotlin/Native appends `_` to a clashing name
            if name in classes:
                used_classes.update(classes[name])
            elif name.startswith("Kotlin") and len(name) > 6 and name[6].isupper():
                used_classes.add("kotlin." + name[6:])
            if m.group(2):
                used_classes.update(classes.get(name + m.group(2).rstrip("_"), ()))
    # Objective-C files of the host app use the framework's prefixed names (SharedFoo, SharedFooCompanion,
    # SharedFooKt): a class keeps all its members; a facade keeps all its top-level functions.
    for path in objc_sources():
        with open(path, encoding="utf-8") as fh:
            text = re.sub(r"//[^\n]*", " ", re.sub(r"/\*.*?\*/", " ", fh.read(), flags=re.S))
        for m in re.finditer(r"\bShared([A-Z][A-Za-z0-9_]*)", text):
            name = m.group(1).rstrip("_")
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
    # Swift may hold Kotlin values without naming their type: the stdlib arrays and throwables.
    used_classes.update(ALWAYS_CLASSES)
    # Swift also uses Kotlin types it never names: `let page = RaftCmpIos.shared.makePage(...)`, then
    # `page.viewController` (a RaftIosPage), an argument a Kotlin callback hands it, a member a class
    # inherits from a Kotlin supertype (Objective-C declares it on the supertype only). So every type
    # the exported API of a listed type mentions (supertypes, constructor parameters, member
    # signatures, its companion's) is listed too, transitively; that includes the super-interfaces of
    # an interface Swift implements, whose members Kotlin calls on it. Top-level callables Swift calls
    # hand it values the same way.
    for pkg, name in used_top:
        used_classes.update(exported_types({"sigs": toplevel.get((pkg, name), []), "scope": []}, decls))
    todo = list(used_classes)
    while todo:
        fq = todo.pop()
        for owner in (fq, fq + ".Companion"):
            for ref in exported_types(decls[owner], decls) if owner in decls else ():
                if ref not in used_classes:
                    used_classes.add(ref)
                    todo.append(ref)
    for fq in sorted(used_classes):
        if decls.get(fq, {}).get("kind") == "interface":
            # A Kotlin interface keeps its own members (and its super-interfaces theirs, above) and
            # nothing else: a wildcard would also match Any's equals, hashCode and toString and make
            # them required protocol methods no Swift class implements.
            for kind, name in sorted(decls[fq]["members"]):
                if name in ANY_MEMBERS:
                    continue
                lines.append(f"{kind} {fq}.{name}")
                if kind == "property":
                    # A property's accessors are separate callables (`<get-x>`, `<set-x>`): listing the
                    # property alone exports it without its getter, and Kotlin/Native's export then
                    # fails an assertion on every class overriding it (ObjCExportMapper.bridgePropertyType:
                    # isBaseProperty). A setter line for a val matches nothing and is harmless.
                    lines.append(f"function {fq}.<get-{name}>")
                    lines.append(f"function {fq}.<set-{name}>")
            lines.append(f"callable {fq}.Companion.*")
            continue
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
        print(f"kotlin public classes scanned: {len(decls)}, facades: {len(facades)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
