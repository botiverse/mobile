#!/usr/bin/env python3
"""Static contract: mermaid consumer stays confined to platform actuals.

Incident-born gate (task #373 / PR #2497): PR-B copied the mermaid Kuikly
actual onto OHOS, producing three source-identical
SlockMermaidDiagramBlock.{android,ios,ohos}.kt files. Nothing previously
failed a PR if one plane drifted. This check moves that failure to every PR.

This asserts SOURCE identity of the three .kt actuals, not KBA vs 2.1.21
ABI/binary identity (those compilers are not interchangeable).

Assertions:
  1. The three platform actuals are byte-identical (or an explicit
     DIVERGENCE_ALLOWLIST entry records the exact unified-diff SHA-256 and
     a reason; mutating an allowlisted fork without updating the digest RED).
  2. commonMain / commonTest have zero `import build.raft.mermaid` lines.
  3. In compose/shared/build.ohos.gradle.kts, mermaid GAVs appear only in
     ohosArm64Main / ohosArm64Test and the version must end with `-ohos`.

Usage:
  check_mermaid_plane_confinement.py            enforce against the repo
  check_mermaid_plane_confinement.py --self-test  run synthetic mutation arms
"""

from __future__ import annotations

import hashlib
import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

ACTUAL_RELATIVE = {
    "android": Path(
        "compose/shared/src/androidMain/kotlin/build/raft/app/markdown/"
        "SlockMermaidDiagramBlock.android.kt"
    ),
    "ios": Path(
        "compose/shared/src/iosMain/kotlin/build/raft/app/markdown/"
        "SlockMermaidDiagramBlock.ios.kt"
    ),
    "ohos": Path(
        "compose/shared/src/ohosArm64Main/kotlin/build/raft/app/markdown/"
        "SlockMermaidDiagramBlock.ohos.kt"
    ),
}

COMMON_MAIN_ROOT = Path("compose/shared/src/commonMain/kotlin")
COMMON_TEST_ROOT = Path("compose/shared/src/commonTest/kotlin")
OHOS_GRADLE = Path("compose/shared/build.ohos.gradle.kts")

ALLOWED_OHOS_SOURCE_SETS = frozenset({"ohosArm64Main", "ohosArm64Test"})

# Empty: the three actuals must be source-byte-identical.
# To admit a platform-specific fork, add:
#   ("android", "ohos"): {
#       "reason": "non-empty reason; platform-specific behavior required",
#       "diff_sha256": "<sha256 of unified diff left -> right>",
#   }
# Changing the fork without updating diff_sha256 is RED. A missing reason is RED.
DIVERGENCE_ALLOWLIST: dict[tuple[str, str], dict[str, str]] = {}

IMPORT_RE = re.compile(r"^\s*import\s+(build\.raft\.mermaid(?:\.[A-Za-z0-9_]+)*)")
SOURCE_SET_RE = re.compile(r"^\s*val\s+(\w+)\s+by\s+(getting|creating)\b")
MERMAID_GAV_RE = re.compile(r'"build\.raft\.mermaid:([^"]+)"')


def _fail(header: str, details: list[str]) -> int:
    print("mermaid plane confinement contract: FAIL")
    print(f"  {header}")
    for line in details:
        print(f"  {line}")
    return 1


def _pass() -> int:
    print("mermaid plane confinement contract: PASS")
    return 0


def unified_diff_sha256(left: bytes, right: bytes, left_name: str, right_name: str) -> str:
    import difflib

    diff = difflib.unified_diff(
        left.decode("utf-8", errors="replace").splitlines(keepends=True),
        right.decode("utf-8", errors="replace").splitlines(keepends=True),
        fromfile=left_name,
        tofile=right_name,
    )
    payload = "".join(diff).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def check_actual_identity(base: Path) -> list[str]:
    missing = []
    bodies: dict[str, bytes] = {}
    for plane, rel in ACTUAL_RELATIVE.items():
        path = base / rel
        if not path.is_file():
            missing.append(f"required actual missing: {rel}")
            continue
        bodies[plane] = path.read_bytes()
    if missing:
        return missing

    violations: list[str] = []
    planes = ("android", "ios", "ohos")
    for i, left in enumerate(planes):
        for right in planes[i + 1 :]:
            if bodies[left] == bodies[right]:
                continue
            key = (left, right)
            entry = DIVERGENCE_ALLOWLIST.get(key) or DIVERGENCE_ALLOWLIST.get((right, left))
            digest = unified_diff_sha256(
                bodies[left],
                bodies[right],
                ACTUAL_RELATIVE[left].name,
                ACTUAL_RELATIVE[right].name,
            )
            if not entry:
                violations.append(
                    f"{left} vs {right} actuals differ (source byte-identity); "
                    f"diff_sha256={digest}. Add a DIVERGENCE_ALLOWLIST entry "
                    f"with reason + this digest only if a plane needs "
                    f"platform-specific behavior."
                )
                continue
            reason = (entry.get("reason") or "").strip()
            expected = (entry.get("diff_sha256") or "").strip().lower()
            if not reason:
                violations.append(
                    f"{left} vs {right} allowlist entry has empty reason"
                )
                continue
            if expected != digest:
                violations.append(
                    f"{left} vs {right} fork drifted from allowlisted diff "
                    f"(reason={reason!r}: expected {expected}, got {digest})"
                )
    return violations


def iter_common_roots(base: Path) -> list[Path]:
    roots = []
    for rel in (COMMON_MAIN_ROOT, COMMON_TEST_ROOT):
        roots.append(base / rel)
    for extra in sorted(base.rglob("src/commonMain/kotlin")) + sorted(
        base.rglob("src/commonTest/kotlin")
    ):
        if extra.is_dir() and extra not in roots:
            roots.append(extra)
    return roots


def check_common_imports(base: Path) -> list[str]:
    violations: list[str] = []
    for required in (COMMON_MAIN_ROOT, COMMON_TEST_ROOT):
        path = base / required
        if not path.is_dir():
            violations.append(f"required common root missing: {required}")
            continue
        if not any(path.rglob("*.kt")):
            violations.append(f"required common root has no Kotlin sources: {required}")
    if any(v.startswith("required common root") for v in violations):
        return violations

    for root in iter_common_roots(base):
        if not root.is_dir():
            continue
        kind = "commonTest" if "commonTest" in root.parts else "commonMain"
        for kt in sorted(root.rglob("*.kt")):
            text = kt.read_text(encoding="utf-8", errors="replace")
            for lineno, line in enumerate(text.splitlines(), start=1):
                m = IMPORT_RE.match(line)
                if not m:
                    continue
                rel = kt.relative_to(base) if kt.is_relative_to(base) else kt
                violations.append(
                    f"{rel}:{lineno}: {kind} import {m.group(1)} "
                    f"(mermaid types must stay out of common classpath)"
                )
    return violations


def strip_line_comment(line: str) -> str:
    in_string = False
    quote = ""
    escaped = False
    chars: list[str] = []
    i = 0
    while i < len(line):
        ch = line[i]
        if in_string:
            chars.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                in_string = False
            i += 1
            continue
        if ch in ("'", '"'):
            in_string = True
            quote = ch
            chars.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < len(line) and line[i + 1] == "/":
            break
        chars.append(ch)
        i += 1
    return "".join(chars)


def parse_ohos_mermaid_gavs(text: str) -> list[tuple[int, str, str, str]]:
    """Return (lineno, source_set_or_empty, artifact, version) for mermaid GAVs."""
    depth = 0
    stack: list[tuple[int, str]] = []
    pending: str | None = None
    found: list[tuple[int, str, str, str]] = []

    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = strip_line_comment(raw)
        m = SOURCE_SET_RE.match(line)
        if m:
            pending = m.group(1)

        in_string = False
        quote = ""
        escaped = False
        for ch in line:
            if in_string:
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == quote:
                    in_string = False
                continue
            if ch in ("'", '"'):
                in_string = True
                quote = ch
                continue
            if ch == "{":
                depth += 1
                if pending:
                    stack.append((depth, pending))
                    pending = None
            elif ch == "}":
                if stack and stack[-1][0] == depth:
                    stack.pop()
                depth -= 1

        if pending and "{" not in line:
            pending = None

        current = stack[-1][1] if stack else ""
        for gav in MERMAID_GAV_RE.findall(line):
            artifact, _, version = gav.partition(":")
            found.append((lineno, current, artifact, version))
    return found


def check_ohos_gradle(base: Path) -> list[str]:
    path = base / OHOS_GRADLE
    if not path.is_file():
        return [f"required OHOS gradle file missing: {OHOS_GRADLE}"]
    text = path.read_text(encoding="utf-8", errors="replace")
    violations: list[str] = []
    for lineno, source_set, artifact, version in parse_ohos_mermaid_gavs(text):
        coord = f"build.raft.mermaid:{artifact}:{version}"
        if source_set not in ALLOWED_OHOS_SOURCE_SETS:
            where = source_set or "<file scope>"
            violations.append(
                f"{OHOS_GRADLE}:{lineno}: mermaid GAV {coord} in {where}; "
                f"only {sorted(ALLOWED_OHOS_SOURCE_SETS)} may declare mermaid"
            )
        if not version.endswith("-ohos"):
            violations.append(
                f"{OHOS_GRADLE}:{lineno}: mermaid GAV {coord} is missing the "
                f"-ohos version suffix"
            )
    return violations


def enforce(base: Path) -> int:
    violations: list[str] = []
    violations.extend(check_actual_identity(base))
    violations.extend(check_common_imports(base))
    violations.extend(check_ohos_gradle(base))
    if violations:
        return _fail("right-cause confinement broken:", violations)
    return _pass()


CLEAN_ACTUAL = """package build.raft.app.markdown

import build.raft.mermaid.core.MermaidParser
import com.tencent.kuikly.compose.ui.text.TextStyle

internal actual fun SlockMermaidDiagramBlock(
    code: String,
    style: TextStyle,
    fallback: () -> Unit,
) {
    MermaidParser.parse(code)
}
"""

CLEAN_COMMON_MAIN = """package build.raft.app.markdown

import com.tencent.kuikly.compose.ui.text.TextStyle

/**
 * commonMain keeps only this signature so mermaid types never enter the
 * common classpath. Mentions of build.raft.mermaid in kdoc are allowed.
 */
internal expect fun SlockMermaidDiagramBlock(
    code: String,
    style: TextStyle,
    fallback: () -> Unit,
)
"""

CLEAN_COMMON_TEST = """package build.raft.app.markdown

import kotlin.test.Test

class PortableMarkdownTest {
    @Test
    fun noMermaidImport() = Unit
}
"""

CLEAN_OHOS_GRADLE = """
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.tencent.kuikly-open:core:1.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                api("com.tencent.kuikly-open:core-render-android:1.0")
            }
        }
        val ohosArm64Main by getting {
            dependencies {
                implementation("build.raft.mermaid:mermaid-core:0.1.2-ohos")
                implementation("build.raft.mermaid:mermaid-kuikly:0.1.2-ohos")
            }
        }
        val ohosArm64Test by getting {
            dependencies {
                implementation("build.raft.mermaid:mermaid-core:0.1.2-ohos")
            }
        }
    }
}
"""


def _write_clean_tree(base: Path) -> None:
    for rel in ACTUAL_RELATIVE.values():
        path = base / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(CLEAN_ACTUAL, encoding="utf-8")
    (base / COMMON_MAIN_ROOT).mkdir(parents=True, exist_ok=True)
    (base / COMMON_TEST_ROOT).mkdir(parents=True, exist_ok=True)
    (base / COMMON_MAIN_ROOT / "SlockMermaidDiagramBlock.kt").write_text(
        CLEAN_COMMON_MAIN, encoding="utf-8"
    )
    (base / COMMON_TEST_ROOT / "PortableMarkdownTest.kt").write_text(
        CLEAN_COMMON_TEST, encoding="utf-8"
    )
    gradle = base / OHOS_GRADLE
    gradle.parent.mkdir(parents=True, exist_ok=True)
    gradle.write_text(CLEAN_OHOS_GRADLE, encoding="utf-8")


def self_test() -> int:
    arms = 0

    def expect_fail(base: Path, label: str) -> int:
        nonlocal arms
        arms += 1
        if enforce(base) == 0:
            print(f"self-test FAIL: {label} must be caught", file=sys.stderr)
            return 1
        return 0

    def expect_pass(base: Path, label: str) -> int:
        nonlocal arms
        arms += 1
        if enforce(base) != 0:
            print(f"self-test FAIL: {label} must pass", file=sys.stderr)
            return 1
        return 0

    with tempfile.TemporaryDirectory() as td:
        base = Path(td)

        # Arm 0: missing required actual -> FAIL (empty scope never passes).
        empty = base / "missing"
        empty.mkdir()
        (empty / COMMON_MAIN_ROOT).mkdir(parents=True)
        (empty / COMMON_TEST_ROOT).mkdir(parents=True)
        (empty / COMMON_MAIN_ROOT / "X.kt").write_text("package x\n", encoding="utf-8")
        (empty / COMMON_TEST_ROOT / "Y.kt").write_text("package y\n", encoding="utf-8")
        if expect_fail(empty, "missing required actual") != 0:
            return 1

        _write_clean_tree(base)
        if expect_pass(base, "clean tree") != 0:
            return 1

        # Arm: OHOS actual rolled back to fallback stub -> FAIL (identity).
        stub = (
            "package build.raft.app.markdown\n"
            "internal actual fun SlockMermaidDiagramBlock("
            "code: String, style: com.tencent.kuikly.compose.ui.text.TextStyle, "
            "fallback: () -> Unit) { fallback() }\n"
        )
        (base / ACTUAL_RELATIVE["ohos"]).write_text(stub, encoding="utf-8")
        if expect_fail(base, "OHOS actual stub divergence") != 0:
            return 1
        (base / ACTUAL_RELATIVE["ohos"]).write_text(CLEAN_ACTUAL, encoding="utf-8")

        # Arm: mermaid import in commonMain -> FAIL.
        polluted = CLEAN_COMMON_MAIN.replace(
            "import com.tencent.kuikly.compose.ui.text.TextStyle",
            "import com.tencent.kuikly.compose.ui.text.TextStyle\n"
            "import build.raft.mermaid.core.MermaidParser",
        )
        common_main = base / COMMON_MAIN_ROOT / "SlockMermaidDiagramBlock.kt"
        common_main.write_text(polluted, encoding="utf-8")
        if expect_fail(base, "commonMain mermaid import") != 0:
            return 1
        common_main.write_text(CLEAN_COMMON_MAIN, encoding="utf-8")

        # Arm: mermaid import in commonTest -> FAIL.
        common_test = base / COMMON_TEST_ROOT / "PortableMarkdownTest.kt"
        common_test.write_text(
            CLEAN_COMMON_TEST.replace(
                "import kotlin.test.Test",
                "import kotlin.test.Test\nimport build.raft.mermaid.core.MermaidParser",
            ),
            encoding="utf-8",
        )
        if expect_fail(base, "commonTest mermaid import") != 0:
            return 1
        common_test.write_text(CLEAN_COMMON_TEST, encoding="utf-8")

        # Arm: mermaid GAV in OHOS commonMain -> FAIL.
        gradle = base / OHOS_GRADLE
        gradle.write_text(
            CLEAN_OHOS_GRADLE.replace(
                'implementation("com.tencent.kuikly-open:core:1.0")',
                'implementation("com.tencent.kuikly-open:core:1.0")\n'
                '                implementation("build.raft.mermaid:mermaid-core:0.1.2-ohos")',
            ),
            encoding="utf-8",
        )
        if expect_fail(base, "mermaid GAV in OHOS commonMain") != 0:
            return 1

        # Arm: mermaid GAV on ohosArm64Main without -ohos suffix -> FAIL.
        gradle.write_text(
            CLEAN_OHOS_GRADLE.replace(
                'implementation("build.raft.mermaid:mermaid-core:0.1.2-ohos")',
                'implementation("build.raft.mermaid:mermaid-core:0.1.2")',
                1,
            ),
            encoding="utf-8",
        )
        if expect_fail(base, "OHOS mermaid GAV missing -ohos suffix") != 0:
            return 1
        gradle.write_text(CLEAN_OHOS_GRADLE, encoding="utf-8")

        if expect_pass(base, "restored clean tree") != 0:
            return 1

    print(f"mermaid plane confinement contract self-test: PASS ({arms} arms)")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    return enforce(REPO_ROOT)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
