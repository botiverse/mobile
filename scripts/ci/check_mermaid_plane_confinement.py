#!/usr/bin/env python3
"""Static contract: mermaid consumer stays confined to platform actuals.

Incident-born gate (task #373 / PR #2497): the mermaid Kuikly actual is
copied per platform, producing source-identical
SlockMermaidDiagramBlock.{android,ios}.kt files. Nothing previously failed a
PR if one plane drifted. This check moves that failure to every PR. (The
former OHOS plane was removed from main when OHOS froze at 1.14.x.)

This asserts SOURCE identity of the .kt actuals, not ABI/binary identity.

Assertions:
  1. The platform actuals are byte-identical (or an explicit
     DIVERGENCE_ALLOWLIST entry records the exact unified-diff SHA-256 and
     a reason; mutating an allowlisted fork without updating the digest RED).
  2. commonMain / commonTest have zero `import build.raft.mermaid` lines.

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
}

COMMON_MAIN_ROOT = Path("compose/shared/src/commonMain/kotlin")
COMMON_TEST_ROOT = Path("compose/shared/src/commonTest/kotlin")

# Empty: the actuals must be source-byte-identical.
# To admit a platform-specific fork, add:
#   ("android", "ios"): {
#       "reason": "non-empty reason; platform-specific behavior required",
#       "diff_sha256": "<sha256 of unified diff left -> right>",
#   }
# Changing the fork without updating diff_sha256 is RED. A missing reason is RED.
DIVERGENCE_ALLOWLIST: dict[tuple[str, str], dict[str, str]] = {}

IMPORT_RE = re.compile(r"^\s*import\s+(build\.raft\.mermaid(?:\.[A-Za-z0-9_]+)*)")


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
    planes = tuple(ACTUAL_RELATIVE)
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


def enforce(base: Path) -> int:
    violations: list[str] = []
    violations.extend(check_actual_identity(base))
    violations.extend(check_common_imports(base))
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

        # Arm: iOS actual rolled back to fallback stub -> FAIL (identity).
        stub = (
            "package build.raft.app.markdown\n"
            "internal actual fun SlockMermaidDiagramBlock("
            "code: String, style: com.tencent.kuikly.compose.ui.text.TextStyle, "
            "fallback: () -> Unit) { fallback() }\n"
        )
        (base / ACTUAL_RELATIVE["ios"]).write_text(stub, encoding="utf-8")
        if expect_fail(base, "iOS actual stub divergence") != 0:
            return 1
        (base / ACTUAL_RELATIVE["ios"]).write_text(CLEAN_ACTUAL, encoding="utf-8")

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
