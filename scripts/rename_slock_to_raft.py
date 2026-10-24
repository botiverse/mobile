#!/usr/bin/env python3
"""Rename the legacy `Slock` brand (and trivial Kuikly-era names) to `Raft` in code.

The product is Raft. This script renames *code identifiers* and the files named after them; it
is deterministic and idempotent, so re-run it after every rebase that brings in old names:

    python3 scripts/rename_slock_to_raft.py              # dry run: print the plan summary
    python3 scripts/rename_slock_to_raft.py --verbose    # dry run with the full mapping/reports
    python3 scripts/rename_slock_to_raft.py --apply      # rewrite files and `git mv` renames
    python3 scripts/rename_slock_to_raft.py --check      # exit 1 if anything is left to rename

How it works
------------
1. Every tracked Kotlin/Java/Swift/ObjC file under CODE_ROOTS is lexed into code, comment and
   string segments. Identifier tokens found in *code* that contain `slock` (any case), plus the
   curated Kuikly-era names (KUIKLY_RENAME_PATTERNS), plus Android resource names and source
   file stems, form the candidate set. `rename_token()` derives the new name:
     * `SlockKuiklyX` -> `RaftX` (`SlockKuiklyBase*` keeps `KuiklyBase`, it is a real library),
     * a doubled brand collapses: `SlockRaftX` -> `RaftX`, `slockRaftX` -> `raftX`,
     * otherwise `Slock`/`slock`/`SLOCK` -> `Raft`/`raft`/`RAFT`,
     * OVERRIDES win over the rules (collision resolutions, clearer names).
2. Code and comments are rewritten token-by-token. String literals are left untouched: persisted
   keys, MMKV namespaces, wire fields, log tags, page/route names, env-var and Gradle property
   names all live in strings. Kotlin `$x`/`${...}` and Swift `\\(...)` interpolations are code.
   Exceptions that *are* code identifiers inside strings are handled explicitly:
   `#import "X.h"`, `buildConfigField(..., "NAME", ...)`, SQLDelight `create("Db")`, the
   STRING_TOKEN_RENAMES allowlist, and references to a declared type or a renamed source file
   (`build.raft.<pkg>.SlockX`, `SlockX.kt`, `res/.../slock_x.xml`). Those references use the
   explicit mapping only (never the general rule), so persisted strings that merely contain
   "slock" (intent actions, SharedPreferences names, channel ids) are never renamed. String
   literals in visual fixture sources (FIXTURE_TEXT_RE) are user-visible fixture text compared
   against the React baseline and are never rewritten (their identifiers still are); nor is
   a string elsewhere that quotes such fixture text (tests asserting on fixture content).
3. Text files (scripts, workflows, docs, pbxproj, baseline profiles, ProGuard, Android XML)
   get the same token mapping (plus the Kotlin file facade classes of renamed .kt files,
   `SlockFooKt` / `SlockFoo_androidKt`, which only exist in bytecode and baseline profiles), but only for tokens in the mapping (never the bare word "slock",
   so `botiverse/slock`, `SLOCK_SOURCE_REPO`, hosts, etc. are untouched). Dated docs snapshots
   and change-logs are historical and are not rewritten.
4. Files whose names contain a mapped token are renamed with `git mv`.
5. Never renamed (see PROTECTED and the manifest rules): Android string resource names,
   manifest `<receiver>`/`<provider>` classes (AppWidget hosts persist the provider component),
   `<activity-alias>` names. A renamed manifest `<activity>` keeps its old component name alive
   through an inserted `<activity-alias>` so pending notification Intents, dynamic/pinned
   shortcuts, recents tasks and external tools that name the old component keep resolving.

Collisions (a new name that already exists, or two old names mapping to one new name) are
reported; HARD ones (both names in one file, or both top-level type names) block --apply unless
resolved in OVERRIDES or acknowledged in ACCEPTED_COLLISIONS.
"""

from __future__ import annotations

import argparse
import collections
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SELF = "scripts/rename_slock_to_raft.py"

# --------------------------------------------------------------------------------------------
# Scope
# --------------------------------------------------------------------------------------------

# Roots whose source files are lexed (identifiers are collected here) and whose files may be
# renamed.
CODE_ROOTS = (
    "compose/",
    "third_party/kuikly-compose-markdown/",
)
CODE_EXTS = {".kt", ".kts", ".java", ".swift", ".m", ".mm", ".h"}

# Text files outside the code lexer that refer to code identifiers / renamed paths.
TEXT_ROOTS = (
    "compose/",
    "third_party/kuikly-compose-markdown/",
    "third_party/markdown-parser/",
    "scripts/",
    "tools/",
    ".github/",
    ".agents/",
    "docs/",
    "AGENTS.md",
    "CONTRIBUTING.md",
    "README.md",
)
TEXT_EXTS = {
    ".md", ".py", ".sh", ".mjs", ".js", ".ts", ".json", ".yml", ".yaml", ".toml", ".txt",
    ".pro", ".xml", ".pbxproj", ".xcscheme", ".plist", ".properties", ".rb", ".gradle",
    ".cfg", ".ini", ".xcconfig", ".strings", ".entitlements", ".yaml",
}
TEXT_EXCLUDE_RE = re.compile(
    r"""(
        ^change-logs/                                  # release notes: historical
      | ^docs/.*\d{4}-\d{2}-\d{2}[^/]*$                # dated audit/spec snapshots: historical
      | ^compose/.*/resources/                         # fixtures, wire payloads, MR resources
      | \.xcassets/                                    # asset catalog names are string resources
      | ^compose/iosApp/Podfile\.lock$
    )""",
    re.X,
)
# The SQLDelight schema itself holds persisted table/column names: rename the file only.
# Visual fixture sources: their string literals are fixture text (compared with React
# baselines), not code references. Identifiers in these files are still renamed.
FIXTURE_TEXT_RE = re.compile(r"(^|/)src/visualFixture[A-Za-z]*/")
CONTENT_FROZEN_EXTS = {".sq", ".sqm", ".db"}

# --------------------------------------------------------------------------------------------
# Naming rules
# --------------------------------------------------------------------------------------------

# Bare words: the product/server name in prose, hosts, repo names. Never identifiers to rename.
BARE_WORDS = {"slock", "Slock", "SLOCK", "kuikly", "Kuikly", "KUIKLY"}

# Kuikly-era names (no Slock prefix) that are trivially renamable: the shared UI payload/model
# types and host adapters that were named after the Kuikly renderer. The compat layer
# (JSONObject, Pager, KuiklyFont, ...), the KuiklyBase datetime library and the
# `com.tencent.kuiklybase` markdown fork keep their names on purpose.
KUIKLY_RENAME_PATTERNS = [
    re.compile(r"^(to)?Kuikly(Thread|Composer|Pending|SharedSettings|Settings|Members|MemberDetail|Snapshot)[A-Za-z0-9_]*$"),
    re.compile(r"^(detect|apply|commit)Kuikly(Composer)[A-Za-z0-9_]*$"),
    re.compile(r"^Kuikly(RealtimeEvents|AppUpdateEvents|AuthSessionEvents|HostNetworkEvents|ChatLayoutPreference[A-Za-z]*|NotificationPreference[A-Za-z]*|HostCapabilities|HostAdapters|DataAdapters)$"),
    re.compile(r"^kuikly(ChatLayout|Notification)PreferenceRepository$"),
    re.compile(r"^toKuiklySurfaceKind$"),
]

# Explicit names. Win over the rules. Used for collision resolutions and for names whose
# rule-derived form would be misleading.
OVERRIDES: dict[str, str] = {
    # Collision resolutions: the rule-derived name already exists in the same scope.
    "parsesSlockChannelLink": "parsesLegacyHostChannelLink",  # next to parsesRaftChannelLink
    "slockAppViewIntentDoesNotClearExistingTask": "legacyHostAppViewIntentDoesNotClearExistingTask",
    "slockModalDialogProperties": "raftModalDialogPropertiesWithoutScrim",  # wraps raftModalDialogProperties
    "slockMermaidInlineScale": "raftMermaidCardInlineScale",  # overload of raftMermaidInlineScale

    # Former Kuikly host subclass of SlockCmpActivity, kept only as a manifest entry point.
    # The rule would yield `RaftActivity`, which reads like the main activity.
    "SlockKuiklyActivity": "RaftLegacyHostActivity",
    "SlockKuiklyActivityTest": "RaftLegacyHostActivityTest",
}

# Whole-path file renames that win over the stem mapping (target stem already taken).
FILE_RENAME_OVERRIDES: dict[str, str] = {
    # RaftSandboxWebView.kt (expect host + config) already exists next to it.
    "compose/shared/src/commonMain/kotlin/build/raft/app/SlockSandboxWebView.kt":
        "compose/shared/src/commonMain/kotlin/build/raft/app/RaftWebView.kt",
}

# Identifiers that must keep their name (beyond the automatically derived protected sets).
PROTECTED: set[str] = set()

# Tokens that are code identifiers even though they appear inside string literals in code
# files: generated-source templates, build-time source checks, NSClassFromString/runtime class
# names, assertion/diagnostic messages that name a type. Renamed inside strings too.
# (Strings that name a renamed source file, `.../SlockX.kt`, or a fully qualified
# `build.raft.*.SlockX` class are renamed by rule, see rename_in_string().)
STRING_TOKEN_RENAMES: set[str] = {
    "slockNetworkClientConfig",               # :shared verifyNetworkTransportClientWiring check
    "SlockKuiklyBaseDatetimeEnabledByBuild",  # generated Kotlin source in shared/build.gradle.kts
    "SlockDatetimeBuildConfig",               # generated source file name in shared/build.gradle.kts
    "SlockLucideIconName",                    # PrimitiveSerialDescriptor name (never on the wire)
    "handleUnsupportedSlockWebLink",          # MainActivityLaunchTest source assertion
    "SharedSlockCmpIosBridge",                # NSClassFromString: Kotlin class exported to ObjC
    "SlockCmpBridgeHost",                     # NSClassFromString: ObjC host class
    "LocalSlockHost", "LocalSlockPageArgs", "SlockCmpRoot",
    "SlockAppShellActivity", "SlockAppShellContent", "SlockType", "SlockMarkdownBody",
    "SlockMarkdownTest", "LiveSlockDbFlowTest", "SlockIosAvatarCacheProviderTests",
    "SlockLiveRootAnchorRefTest", "SlockLiveRootAnchorLifecycleTest", "SlockPlatformLogTest",
    "SlockOwnerScopedCacheOwner", "SlockShellStoreScope",            # toString() of the type
    "SlockSqlDatabase",                       # store-threading labels name its methods
}

# Mapped tokens whose occurrences in text files (scripts, docs) refer to a *string* kept in code
# (log markers, JSON keys, preference names), not to the identifier. Not renamed in text files.
TEXT_KEEP_TOKENS: set[str] = {
    "SLOCK_RICHTEXT_CAPS",  # logcat marker parsed by scripts/collect_richtext_caps.py
    "slockRefChrome",       # JSON key inside that marker
}

# Collisions reviewed and accepted as intended (old -> new merges onto an existing name).
ACCEPTED_COLLISIONS: set[tuple[str, str]] = set()

IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
DOUBLED_RE = re.compile(r"SlockRaft|slockRaft")
APP_PREFIX_RE = re.compile(r"SlockRaft|slockRaft|Slock|slock")
DESIGNSYSTEM_ROOT = "compose/designsystem/"
BRAND_RE = re.compile(r"Slock|slock|SLOCK")  # case-sensitive: "operationsLock" is not the brand


def kotlin_facade(file_name: str) -> str:
    """Kotlin file facade class of `file_name` (`Foo.android.kt` -> `Foo_androidKt`)."""
    stem = file_name[: -len(".kt")]
    stem = re.sub(r"[^A-Za-z0-9_$]", "_", stem)
    return stem[:1].upper() + stem[1:] + "Kt"


def rename_token(tok: str) -> str | None:
    """Return the new name for `tok`, or None when it is not renamed."""
    if tok in OVERRIDES:
        return OVERRIDES[tok]
    if tok in BARE_WORDS or tok in PROTECTED:
        return None
    new = tok
    if BRAND_RE.search(tok):
        # SlockKuikly* -> Raft* (but SlockKuiklyBase* keeps the KuiklyBase library name).
        new = re.sub(r"SlockKuikly(?![Bb]ase)", "Raft", new)
        new = re.sub(r"slockKuikly(?![Bb]ase)", "raft", new)
        new = re.sub(r"SLOCK_KUIKLY_(?!BASE)", "RAFT_", new)
        new = re.sub(r"slock_kuikly(?=_|$)", "raft", new)
        # A doubled brand collapses.
        new = new.replace("SlockRaft", "Raft").replace("slockRaft", "raft")
        new = new.replace("SLOCK_RAFT_", "RAFT_").replace("slock_raft_", "raft_")
        new = new.replace("Slock", "Raft").replace("slock", "raft").replace("SLOCK", "RAFT")
        if BRAND_RE.search(new):  # mixed case such as sLock: leave for a human
            return None
    elif any(p.match(tok) for p in KUIKLY_RENAME_PATTERNS):
        new = tok.replace("Kuikly", "Raft").replace("kuikly", "raft")
    else:
        return None
    if not IDENT_RE.fullmatch(new) or new == tok:
        return None
    return new


def is_candidate(tok: str) -> bool:
    return bool(BRAND_RE.search(tok)) or any(p.match(tok) for p in KUIKLY_RENAME_PATTERNS) or tok in OVERRIDES


# --------------------------------------------------------------------------------------------
# Lexer: split source into ("code" | "comment" | "string", text) segments.
# --------------------------------------------------------------------------------------------


def lang_of(path: str) -> str | None:
    ext = os.path.splitext(path)[1]
    if ext in (".kt", ".kts"):
        return "kotlin"
    if ext == ".swift":
        return "swift"
    if ext in (".java", ".m", ".mm", ".h"):
        return "c"
    return None


class Lexer:
    def __init__(self, text: str, lang: str):
        self.s = text
        self.n = len(text)
        self.lang = lang
        self.out: list[tuple[str, str]] = []

    def emit(self, kind: str, text: str) -> None:
        if not text:
            return
        if self.out and self.out[-1][0] == kind:
            self.out[-1] = (kind, self.out[-1][1] + text)
        else:
            self.out.append((kind, text))

    def run(self) -> list[tuple[str, str]]:
        self.code(0, None)
        return self.out

    # Lex code starting at i. If `closer` is "}" or ")", stop (without consuming) at the
    # unbalanced closer; return the index reached.
    def code(self, i: int, closer: str | None) -> int:
        s, n = self.s, self.n
        depth = 0
        start = i
        opener = {"}": "{", ")": "("}.get(closer or "", None)
        line_start = True
        while i < n:
            c = s[i]
            if closer is not None:
                if c == opener:
                    depth += 1
                elif c == closer:
                    if depth == 0:
                        self.emit("code", s[start:i])
                        return i
                    depth -= 1
            if c == "/" and i + 1 < n and s[i + 1] == "/":
                self.emit("code", s[start:i])
                j = s.find("\n", i)
                j = n if j < 0 else j
                self.emit("comment", s[i:j])
                i = start = j
                continue
            if c == "/" and i + 1 < n and s[i + 1] == "*":
                self.emit("code", s[start:i])
                j = self.block_comment_end(i)
                self.emit("comment", s[i:j])
                i = start = j
                continue
            if self.lang == "c" and c == "#" and line_start:
                # Preprocessor: `#import "X.h"` names a file -> treat the whole line as code.
                j = s.find("\n", i)
                j = n if j < 0 else j
                line = s[i:j]
                if re.match(r"#\s*(import|include)\b", line):
                    i = j
                    continue
            if c == '"' or (self.lang == "swift" and c == "#" and self.swift_raw_start(i)):
                self.emit("code", s[start:i])
                i = start = self.string(i)
                continue
            if c == "'" and self.lang in ("kotlin", "c"):
                j = self.char_literal_end(i)
                if j is not None:
                    self.emit("code", s[start:i])
                    self.emit("string", s[i:j])
                    i = start = j
                    continue
            if c == "`" and self.lang == "kotlin":
                j = s.find("`", i + 1)
                if j > 0 and "\n" not in s[i:j]:
                    i = j + 1
                    continue
            if c == "\n":
                line_start = True
            elif not c.isspace():
                line_start = False
            i += 1
        self.emit("code", s[start:i])
        return i

    def block_comment_end(self, i: int) -> int:
        s, n = self.s, self.n
        nested = self.lang in ("kotlin", "swift")
        depth = 0
        j = i
        while j < n:
            if s.startswith("/*", j):
                depth += 1
                j += 2
                if not nested and depth > 1:
                    depth = 1
                continue
            if s.startswith("*/", j):
                depth -= 1
                j += 2
                if depth == 0:
                    return j
                continue
            j += 1
        return n

    def char_literal_end(self, i: int) -> int | None:
        s = self.s
        m = re.compile(r"'(\\u[0-9a-fA-F]{4}|\\.|[^'\\\n])'").match(s, i)
        return m.end() if m else None

    def swift_raw_start(self, i: int) -> bool:
        m = re.compile(r"#+\"").match(self.s, i)
        return bool(m)

    def string(self, i: int) -> int:
        s, n = self.s, self.n
        if self.lang == "swift":
            hashes = 0
            while i + hashes < n and s[i + hashes] == "#":
                hashes += 1
            q = i + hashes
            triple = s.startswith('"""', q)
            delim = ('"""' if triple else '"') + "#" * hashes
            j = q + (3 if triple else 1)
            seg_start = i
            interp = "\\" + "#" * hashes + "("
            while j < n:
                if s.startswith(interp, j):
                    j += len(interp)
                    self.emit("string", s[seg_start:j])
                    j = self.code(j, ")")
                    seg_start = j
                    continue
                if hashes == 0 and s[j] == "\\":
                    j += 2
                    continue
                if s.startswith(delim, j):
                    j += len(delim)
                    self.emit("string", s[seg_start:j])
                    return j
                if not triple and s[j] == "\n":
                    break
                j += 1
            self.emit("string", s[seg_start:j])
            return j
        if self.lang == "kotlin":
            triple = s.startswith('"""', i)
            j = i + (3 if triple else 1)
            seg_start = i
            while j < n:
                if s[j] == "$" and j + 1 < n:
                    if s[j + 1] == "{":
                        self.emit("string", s[seg_start:j + 2])
                        j = self.code(j + 2, "}")
                        seg_start = j
                        continue
                    m = IDENT_RE.match(s, j + 1)
                    if m:
                        self.emit("string", s[seg_start:j + 1])
                        self.emit("code", m.group(0))
                        j = seg_start = m.end()
                        continue
                if not triple and s[j] == "\\":
                    j += 2
                    continue
                if triple and s.startswith('"""', j):
                    j += 3
                    while j < n and s[j] == '"':
                        j += 1
                    self.emit("string", s[seg_start:j])
                    return j
                if not triple and s[j] == '"':
                    j += 1
                    self.emit("string", s[seg_start:j])
                    return j
                if not triple and s[j] == "\n":
                    break
                j += 1
            self.emit("string", s[seg_start:j])
            return j
        # C / ObjC / Java
        j = i + 1
        while j < n:
            if s[j] == "\\":
                j += 2
                continue
            if s[j] == '"':
                j += 1
                break
            if s[j] == "\n":
                break
            j += 1
        self.emit("string", s[i:j])
        return j


def lex(text: str, lang: str) -> list[tuple[str, str]]:
    return Lexer(text, lang).run()


# --------------------------------------------------------------------------------------------
# Repository scan
# --------------------------------------------------------------------------------------------


def git_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-z"], cwd=REPO, check=True, capture_output=True
    ).stdout.decode()
    return [p for p in out.split("\0") if p and (REPO / p).is_file()]


def in_roots(path: str, roots) -> bool:
    return any(path == r or path.startswith(r) for r in roots)


def is_code_file(path: str) -> bool:
    return in_roots(path, CODE_ROOTS) and os.path.splitext(path)[1] in CODE_EXTS


def is_text_file(path: str) -> bool:
    if path == SELF or is_code_file(path) or not in_roots(path, TEXT_ROOTS):
        return False
    if TEXT_EXCLUDE_RE.search(path):
        return False
    ext = os.path.splitext(path)[1]
    base = os.path.basename(path)
    return ext in TEXT_EXTS or base in {"Podfile", "Gemfile", "Makefile"} or (
        ext == "" and path.startswith("scripts/")
    )


def read(path: str) -> str | None:
    try:
        return (REPO / path).read_text(encoding="utf-8")
    except (UnicodeDecodeError, FileNotFoundError):
        return None


ANDROID_RES_RE = re.compile(r"^compose/[^/]+/src/[^/]+/res/(?P<type>[a-z]+)(-[^/]*)?/(?P<name>[^/]+)$")
VALUES_NAME_RE = re.compile(r'<(?P<tag>color|bool|dimen|integer|style|attr|declare-styleable|item|id|drawable|fraction)\b[^>]*\bname="(?P<name>[^"]+)"')
STRING_RES_RE = re.compile(r'<(string|plurals|string-array)\b[^>]*\bname="([^"]+)"')
ID_RE = re.compile(r'@\+id/([A-Za-z0-9_]+)')


class Scan:
    def __init__(self) -> None:
        self.files = git_files()
        self.code_files = [p for p in self.files if is_code_file(p)]
        self.text_files = [p for p in self.files if is_text_file(p)]
        self.segments: dict[str, list[tuple[str, str]]] = {}
        self.code_idents: collections.Counter[str] = collections.Counter()
        self.idents_by_file: dict[str, set[str]] = {}
        self.string_tokens: collections.defaultdict[str, set[str]] = collections.defaultdict(set)
        self.top_level_types: set[str] = set()
        self.declared_types: set[str] = set()
        self.android_res_names: set[str] = set()
        self.string_res_names: set[str] = set()
        self.protected_components: set[str] = set()
        self.bare_word_code_hits: collections.Counter[str] = collections.Counter()
        self._scan_code()
        self._scan_android()

    def _scan_code(self) -> None:
        decl = re.compile(
            r"^(?:[ \t]*(?:public|internal|private|protected|open|abstract|sealed|data|enum|annotation|value|inline|expect|actual|final|fun|@[A-Za-z]+(?:\([^)]*\))?)\s+)*"
            r"(?:class|interface|object|typealias|struct|protocol|enum)\s+([A-Z][A-Za-z0-9_]*)",
            re.M,
        )
        for p in self.code_files:
            text = read(p)
            if text is None:
                continue
            segs = lex(text, lang_of(p))
            self.segments[p] = segs
            idents: set[str] = set()
            for kind, chunk in segs:
                if kind == "code":
                    for tok in IDENT_RE.findall(chunk):
                        self.code_idents[tok] += 1
                        idents.add(tok)
                        if tok in BARE_WORDS:
                            self.bare_word_code_hits[f"{p}:{tok}"] += 1
                elif kind == "string":
                    for tok in IDENT_RE.findall(chunk):
                        if is_candidate(tok):
                            self.string_tokens[tok].add(p)
            self.idents_by_file[p] = idents
            code_only = "".join(c for k, c in segs if k == "code")
            for m in decl.finditer(code_only):
                self.declared_types.add(m.group(1))
                if m.group(0).lstrip() == m.group(0) or not m.group(0).startswith((" ", "\t")):
                    self.top_level_types.add(m.group(1))
            stem = os.path.basename(p).split(".")[0]
            self.code_idents[stem] += 0  # file stems participate in collision checks

    def _scan_android(self) -> None:
        for p in self.files:
            m = ANDROID_RES_RE.match(p)
            if not m:
                continue
            rtype = m.group("type")
            if rtype != "values":
                self.android_res_names.add(m.group("name").split(".")[0])
            text = read(p) if p.endswith(".xml") else None
            if text is None:
                continue
            if rtype == "values":
                for vm in VALUES_NAME_RE.finditer(text):
                    if vm.group("tag") != "item":
                        self.android_res_names.add(vm.group("name").replace(".", "_"))
                for sm in STRING_RES_RE.finditer(text):
                    self.string_res_names.add(sm.group(2))
            for im in ID_RE.finditer(text):
                self.android_res_names.add(im.group(1))
        for p in self.files:
            if not p.endswith("AndroidManifest.xml") or not p.startswith("compose/"):
                continue
            text = read(p) or ""
            for m in re.finditer(r"<(receiver|provider)\b[^>]*?android:name=\"([^\"]+)\"", text, re.S):
                self.protected_components.add(m.group(2).rsplit(".", 1)[-1])


# --------------------------------------------------------------------------------------------
# Plan
# --------------------------------------------------------------------------------------------


class Plan:
    def __init__(self, scan: Scan) -> None:
        self.scan = scan
        protected = set(PROTECTED) | scan.string_res_names | scan.protected_components
        self.protected_hits = sorted(t for t in protected if is_candidate(t))
        PROTECTED.update(protected)

        candidates: set[str] = set()
        for tok in scan.code_idents:
            if is_candidate(tok):
                candidates.add(tok)
        for tok in scan.android_res_names:
            if is_candidate(tok):
                candidates.add(tok)
        self.mapping: dict[str, str] = {}
        self.resolutions: dict[str, str] = {}
        existing = {t for t in scan.code_idents if not is_candidate(t)}
        existing |= {t for t in scan.android_res_names if not is_candidate(t)}
        designsystem = set()
        for p, ids in scan.idents_by_file.items():
            if p.startswith(DESIGNSYSTEM_ROOT):
                designsystem |= {t for t in ids if not is_candidate(t)}
        first = {t: rename_token(t) for t in sorted(candidates)}
        plain_targets = {n for t, n in first.items() if n and not DOUBLED_RE.search(t)}
        for tok in sorted(candidates):
            new = first[tok]
            if not new:
                continue
            if tok not in OVERRIDES:
                # `SlockRaftX` is usually the raft-look implementation behind a `SlockX` that
                # dispatches on the theme family. When collapsing to `RaftX` would land on an
                # existing name (or on `SlockX`'s new name) it becomes `RaftLookX`.
                if DOUBLED_RE.search(tok) and (new in existing or new in plain_targets):
                    alt = DOUBLED_RE.sub(lambda m: "RaftLook" if m.group(0)[0] == "S" else "raftLook", tok)
                    alt = rename_token(alt) or alt
                    self.resolutions[tok] = f"{new} taken -> {alt} (raft-look variant)"
                    new = alt
                # App-module counterparts of :designsystem names get `RaftApp` so they never
                # share a simple name with the design system (no wrong auto-imports).
                if new in designsystem:
                    alt = APP_PREFIX_RE.sub(lambda m: "RaftApp" if m.group(0)[0] == "S" else "raftApp", tok, count=1)
                    alt = rename_token(alt) or alt
                    self.resolutions[tok] = f"{new} is a :designsystem name -> {alt}"
                    new = alt
            self.mapping[tok] = new
        for old, new in list(self.mapping.items()):
            # Kotlin file facades (`SlockFooKt`) follow their file's (possibly overridden) stem.
            if old.endswith("Kt") and old[:-2] in self.mapping:
                self.mapping[old] = self.mapping[old[:-2]] + "Kt"
        # Tokens kept in code string literals that are mapped elsewhere: ambiguous in text files.
        self.string_kept = {
            t: sorted(files) for t, files in scan.string_tokens.items()
            if t in self.mapping and t not in STRING_TOKEN_RENAMES
        }
        self._collisions()
        self.file_renames = self._file_renames()
        self._facades()
        # Tokens that may be renamed by rule inside string literals (FQNs, file references):
        # explicit mapping keys that are declared types or stems of renamed source files.
        renamed_stems = {os.path.basename(o).split(".")[0] for o, _ in self.file_renames}
        self.string_ref_keys = {
            t for t in self.mapping if t in scan.declared_types or t in renamed_stems
        } | set(self.facades)
        # Fixture text (string literals of visual fixture sources). A string elsewhere that
        # quotes it (a test asserting on fixture content) must keep the fixture's spelling.
        self.fixture_text = "\n".join(
            chunk.strip('"')
            for p, segs in scan.segments.items() if FIXTURE_TEXT_RE.search(p)
            for kind, chunk in segs if kind == "string"
        )

    def _collisions(self) -> None:
        scan = self.scan
        by_new: collections.defaultdict[str, list[str]] = collections.defaultdict(list)
        for old, new in self.mapping.items():
            by_new[new].append(old)
        self.hard: list[tuple[str, str, str]] = []
        self.soft: list[tuple[str, str, str]] = []
        for new, olds in sorted(by_new.items()):
            if len(olds) > 1:
                for old in olds:
                    if (old, new) not in ACCEPTED_COLLISIONS:
                        self.hard.append((old, new, "several old names map here: " + ", ".join(olds)))
        for old, new in sorted(self.mapping.items()):
            if new in self.mapping or (old, new) in ACCEPTED_COLLISIONS:
                continue
            exists = scan.code_idents.get(new) is not None or new in scan.android_res_names
            if not exists:
                continue
            shared = [p for p, ids in scan.idents_by_file.items() if old in ids and new in ids]
            both_types = old in scan.top_level_types and new in scan.top_level_types
            if shared or both_types:
                why = f"both in {shared[0]}" + (f" (+{len(shared) - 1})" if len(shared) > 1 else "") if shared else "both are top-level types"
                self.hard.append((old, new, why))
            else:
                self.soft.append((old, new, "new name already used elsewhere"))

    def _facades(self) -> None:
        """`SlockFooKt` -> `RaftFooKt` (and `SlockFoo_androidKt` ...) for renamed .kt files."""
        self.facades: dict[str, str] = {}
        for old, new in self.file_renames:
            if not old.endswith(".kt"):
                continue
            of, nf = kotlin_facade(os.path.basename(old)), kotlin_facade(os.path.basename(new))
            if of == nf:
                continue
            prev = self.mapping.get(of)
            if prev is not None and prev != nf:
                self.hard.append((of, nf, f"file facade already mapped to {prev}"))
                continue
            if self.facades.get(of, nf) != nf:
                self.hard.append((of, nf, f"file facade also maps to {self.facades[of]}"))
                continue
            self.facades[of] = nf
        targets = collections.Counter(self.facades.values())
        for of, nf in self.facades.items():
            if targets[nf] > 1:
                self.hard.append((of, nf, "several file facades map here"))
        self.mapping.update(self.facades)

    def _file_renames(self) -> list[tuple[str, str]]:
        renames = []
        for p in self.scan.files:
            if not in_roots(p, CODE_ROOTS) or TEXT_EXCLUDE_RE.search(p):
                continue
            parts = p.split("/")
            new_parts = []
            for part in parts:
                pieces = re.split(r"([^A-Za-z0-9_]+)", part)
                new_parts.append("".join(self.map_name_piece(x) for x in pieces))
            np = FILE_RENAME_OVERRIDES.get(p, "/".join(new_parts))
            if np != p:
                renames.append((p, np))
        files = set(self.scan.files)
        moved_away = {o for o, _ in renames}
        staying_lower = {f.lower() for f in files if f not in moved_away}
        targets = collections.Counter(n for _, n in renames)
        for o, n in renames:
            if (n in files and n not in moved_away) or targets[n] > 1 or (
                n.lower() in staying_lower
            ):
                self.hard.append((o, n, "file rename target already exists"))
        return renames

    def map_name_piece(self, piece: str) -> str:
        if not piece or not IDENT_RE.fullmatch(piece):
            return piece
        if piece in self.mapping:
            return self.mapping[piece]
        new = rename_token(piece) if is_candidate(piece) else None
        return new or piece

    # ---- rewriting ------------------------------------------------------------------------

    def sub_tokens(self, text: str, allow=None, deny=frozenset()) -> str:
        mapping = self.mapping

        def repl(m: re.Match) -> str:
            tok = m.group(0)
            if (allow is not None and tok not in allow) or tok in deny:
                return tok
            return mapping.get(tok, tok)

        return IDENT_RE.sub(repl, text)

    def rewrite_code(self, path: str) -> str | None:
        segs = self.scan.segments.get(path)
        if segs is None:
            return None
        out = []
        is_gradle = path.endswith(".gradle.kts")
        fixture_text = bool(FIXTURE_TEXT_RE.search(path))
        for kind, chunk in segs:
            if kind in ("code", "comment"):
                out.append(self.sub_tokens(chunk))
            elif fixture_text:
                out.append(chunk)
            else:
                out.append(self.rename_in_string(chunk))
        text = "".join(out)
        if is_gradle:
            text = self.rewrite_gradle_strings(text)
        return text

    # `SlockX.kt` / `SlockX.android.kt` / `SlockX.h` ...; `.xml` only as a path (`res/.../x.xml`):
    # a bare `slock_x.xml` is typically a SharedPreferences file name, which is persisted.
    STRING_FILE_REF_RE = re.compile(
        r"(?<![A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_]*)(?=(?:\.[a-z]+)*\.(?:kt|kts|swift|m|mm|h|java|sq)\b)"
        r"|(?<=/)([A-Za-z_][A-Za-z0-9_]*)(?=\.xml\b)"
    )
    STRING_FQN_RE = re.compile(r"(?<=\bbuild\.raft\.)((?:[a-z][a-z0-9_]*\.)*)([A-Za-z_][A-Za-z0-9_]*)")

    def map_string_ref(self, tok: str) -> str:
        """Rename a type / source-file reference inside a string: explicit mapping keys only."""
        return self.mapping[tok] if tok in self.string_ref_keys else tok

    def rename_in_string(self, chunk: str) -> str:
        """Strings stay untouched except for code identifiers (see STRING_TOKEN_RENAMES)."""
        inner = chunk.strip('"')
        if len(inner) >= 8 and not IDENT_RE.fullmatch(inner) and inner in self.fixture_text:
            return chunk  # quotes visual fixture text (see FIXTURE_TEXT_RE)
        # STRING_TOKEN_RENAMES is an explicit allowlist: map_name_piece (mapping, else the rules)
        # so that a re-run after a rebase still renames string-only references whose identifier
        # was renamed by an earlier run.
        chunk = IDENT_RE.sub(
            lambda m: self.map_name_piece(m.group(0)) if m.group(0) in STRING_TOKEN_RENAMES else m.group(0),
            chunk,
        )
        chunk = self.STRING_FILE_REF_RE.sub(lambda m: self.map_string_ref(m.group(0)), chunk)
        chunk = self.STRING_FQN_RE.sub(lambda m: m.group(1) + self.map_string_ref(m.group(2)), chunk)
        return chunk

    def rewrite_gradle_strings(self, text: str) -> str:
        # buildConfigField(type, "NAME", value): NAME becomes BuildConfig.NAME in code.
        def bcf(m: re.Match) -> str:
            return m.group(1) + self.map_name_piece(m.group(2)) + m.group(3)

        text = re.sub(r'(buildConfigField\(\s*"[^"]*"\s*,\s*")([A-Za-z0-9_]+)(")', bcf, text)
        # SQLDelight database class name, and the generated task names that embed it.
        text = re.sub(
            r'(\bcreate\(\s*")([A-Za-z0-9_]+)("\s*\)\s*\{)',
            lambda m: m.group(1) + self.map_name_piece(m.group(2)) + m.group(3),
            text,
        )
        text = re.sub(
            r'(")(generate|verify)([A-Za-z]*?Main)([A-Za-z0-9_]+?)(Interface|Migrations|Schema|Migration)(")',
            lambda m: m.group(1) + m.group(2) + m.group(3) + self.map_name_piece(m.group(4)) + m.group(5) + m.group(6),
            text,
        )
        return text

    def rewrite_text(self, path: str, text: str) -> str:
        if path.endswith("AndroidManifest.xml"):
            return self.rewrite_manifest(text)
        new = self.sub_tokens(text, deny=TEXT_KEEP_TOKENS)
        if path.endswith(".xml") and "/res/" in path:
            new = re.sub(r"\bTheme\.Slock\b", "Theme.Raft", new)
        return new

    def rewrite_manifest(self, text: str) -> str:
        out = []
        pos = 0
        aliases_present = set(re.findall(r'<activity-alias\b[^>]*?android:name="([^"]+)"', text, re.S))
        for m in re.finditer(r"<!--.*?-->|<[^!>][^>]*>", text, re.S):
            out.append(text[pos:m.start()])
            tag = m.group(0)
            pos = m.end()
            if tag.startswith("<!--"):
                out.append(self.sub_tokens(tag))
                continue
            if tag.startswith("<activity-alias"):
                # Alias names are OS-visible component names: never renamed.
                tag = re.sub(
                    r'(android:targetActivity=")([^"]+)(")',
                    lambda a: a.group(1) + self.sub_tokens(a.group(2)) + a.group(3),
                    tag,
                )
                out.append(tag)
                continue
            if tag.startswith("<activity") and not tag.startswith("<activity-"):
                nm = re.search(r'android:name="([^"]+)"', tag)
                old = nm.group(1) if nm else None
                new_tag = self.sub_tokens(tag)
                new_tag = re.sub(r"\bTheme\.Slock\b", "Theme.Raft", new_tag)
                out.append(new_tag)
                if old and old not in aliases_present:
                    new = self.sub_tokens(old)
                    if new != old:
                        exported = re.search(r'android:exported="([^"]+)"', tag)
                        if tag.rstrip().endswith("/>"):
                            end = pos
                        else:
                            close = text.find("</activity>", pos)
                            end = close + len("</activity>")
                            out.append(self.sub_tokens(text[pos:end]))
                            pos = end
                        line_start = text.rfind("\n", 0, m.start()) + 1
                        indent = re.match(r"[ \t]*", text[line_start:]).group(0)
                        out.append(
                            f"\n{indent}<!-- Pre-rename component name (Slock -> Raft), kept for Intents persisted by\n"
                            f"{indent}     older versions: notifications, shortcuts, recents, external tools. -->\n"
                            f"{indent}<activity-alias\n"
                            f'{indent}    android:name="{old}"\n'
                            + (f'{indent}    android:exported="{exported.group(1)}"\n' if exported else "")
                            + f'{indent}    android:targetActivity="{new}" />'
                        )
                        aliases_present.add(old)
                continue
            if tag.startswith(("<receiver", "<provider")):
                out.append(re.sub(r"\bTheme\.Slock\b", "Theme.Raft", tag))
                continue
            tag = self.sub_tokens(tag)
            out.append(re.sub(r"\bTheme\.Slock\b", "Theme.Raft", tag))
        out.append(text[pos:])
        return "".join(out)

    # ---- execution ------------------------------------------------------------------------

    def compute_changes(self) -> dict[str, str]:
        changes: dict[str, str] = {}
        for p in self.scan.code_files:
            new = self.rewrite_code(p)
            if new is not None and new != read(p):
                changes[p] = new
        for p in self.scan.text_files:
            if os.path.splitext(p)[1] in CONTENT_FROZEN_EXTS:
                continue
            text = read(p)
            if text is None:
                continue
            new = self.rewrite_text(p, text)
            if new != text:
                changes[p] = new
        return changes


def changed_tokens(old: str, new: str, mapping: dict[str, str]) -> collections.Counter:
    c: collections.Counter[str] = collections.Counter()
    for tok in IDENT_RE.findall(old):
        if tok in mapping:
            c[tok] += 1
    for tok in IDENT_RE.findall(new):
        if tok in mapping:
            c[tok] -= 1
    return +c


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="rewrite files and git mv renames")
    mode.add_argument("--check", action="store_true", help="exit 1 if anything is left to rename")
    ap.add_argument("--verbose", "-v", action="store_true", help="print the full mapping and reports")
    ap.add_argument("--force", action="store_true", help="apply even with unresolved HARD collisions")
    args = ap.parse_args()

    os.chdir(REPO)
    scan = Scan()
    plan = Plan(scan)
    changes = plan.compute_changes()

    used = collections.Counter()
    for p, new in changes.items():
        used.update(changed_tokens(read(p) or "", new, plan.mapping))
    for old, new in plan.file_renames:
        for piece in re.split(r"[^A-Za-z0-9_]+", old):
            if piece in plan.mapping:
                used[piece] += 0

    brand = sum(1 for t in plan.mapping if BRAND_RE.search(t))
    print(f"[rename] identifiers mapped: {len(plan.mapping)} ({brand} Slock*, {len(plan.mapping) - brand} Kuikly-era)")
    print(f"[rename] files to rewrite:   {len(changes)}")
    print(f"[rename] files to rename:    {len(plan.file_renames)}")
    print(f"[rename] occurrences:        {sum(used.values())}")
    print(f"[rename] collisions:         {len(plan.hard)} hard, {len(plan.soft)} soft")
    print(f"[rename] protected names:    {len(plan.protected_hits)}")
    if args.verbose or not (args.apply or args.check):
        top = sorted(used.items(), key=lambda kv: -kv[1])[:25]
        print("[rename] most frequent:", ", ".join(f"{k}->{plan.mapping[k]}({v})" for k, v in top))
    if args.verbose:
        print("\n== mapping")
        for old, new in sorted(plan.mapping.items()):
            print(f"  {old} -> {new}")
        print("\n== file renames")
        for old, new in plan.file_renames:
            print(f"  {old} -> {new}")
        print("\n== automatic collision resolutions")
        for t, why in sorted(plan.resolutions.items()):
            print(f"  {t}: {why}")
        print("\n== protected (never renamed)")
        for t in plan.protected_hits:
            print(f"  {t}")
        print("\n== mapped tokens also present inside code string literals (strings are kept)")
        for t, files in sorted(plan.string_kept.items()):
            print(f"  {t}: {', '.join(files[:3])}{' ...' if len(files) > 3 else ''}")
        print("\n== bare brand words left in code (review)")
        for k, v in sorted(scan.bare_word_code_hits.items()):
            print(f"  {k} x{v}")
        print("\n== files to rewrite")
        for p in sorted(changes):
            print(f"  {p}")
    if plan.hard or args.verbose:
        print("\n== HARD collisions")
        for old, new, why in plan.hard:
            print(f"  {old} -> {new}: {why}")
    if args.verbose:
        print("\n== soft collisions (new name exists elsewhere; review)")
        for old, new, why in plan.soft:
            print(f"  {old} -> {new}")

    if args.check:
        if changes or plan.file_renames:
            print("[rename] --check: FAILED, names left to rename (run with --apply)", file=sys.stderr)
            return 1
        print("[rename] --check: OK")
        return 0
    if not args.apply:
        print("[rename] dry run; pass --apply to rewrite")
        return 0
    if plan.hard and not args.force:
        print("[rename] refusing to apply: unresolved HARD collisions (add OVERRIDES or ACCEPTED_COLLISIONS)", file=sys.stderr)
        return 2
    for p, new in changes.items():
        (REPO / p).write_text(new, encoding="utf-8")
    for old, new in plan.file_renames:
        (REPO / new).parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "mv", old, new], cwd=REPO, check=True)
    print(f"[rename] applied: {len(changes)} files rewritten, {len(plan.file_renames)} renamed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
