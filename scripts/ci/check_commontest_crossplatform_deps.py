#!/usr/bin/env python3
"""Static contract: commonTest must not import test dependencies that are not
available to every target that compiles commonTest.

Incident-born gate (task #976): PR #1920 added a commonTest import of
kotlinx.coroutines.test.runTest. The OHOS test source set has no
kotlinx-coroutines-test KLIB, so compileTestKotlinOhosArm64 failed — but only
on main, because the OHOS shared compile tier does not gate ordinary PRs.
This check moves the failure back to every PR.

BOUNDARY — denylist form, read before extending:
  This gate seals the DEPENDENCIES LISTED BELOW, not the general class of
  "imports a target has not declared". A derived form (computing the allowed
  set from each target's actual test dependency declarations) is a separate,
  larger piece of work and is NOT what this script does. Closing task #976
  must not be recorded as "the class is sealed".

Precedent for the constraint already lives in the repo:
  SharedReplyThreadAccessIntegrationTest.kt: "OHOS commonTest has no kotlinx-coroutines-test KLIB"
  SharedHomeSearchSenderIdentityTest.kt:     same
  SlockMultipartTimeoutPolicyTest.kt:        "pure, OHOS-compatible: no coroutines-test"

Usage:
  check_commontest_crossplatform_deps.py            enforce against the repo
  check_commontest_crossplatform_deps.py --self-test  run synthetic mutation arms
"""

import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# Denylist: package prefixes forbidden in any commonTest source set, with the
# reason each is forbidden. Extend here when a new cross-platform-unavailable
# test dependency is identified (and update this docstring's incident list).
FORBIDDEN_PREFIXES = {
    "kotlinx.coroutines.test": (
        "no kotlinx-coroutines-test KLIB exists for the OHOS test targets; "
        "use the portable runBlocking form already used across commonTest"
    ),
}

IMPORT_RE = re.compile(r"^\s*import\s+([A-Za-z0-9_.]+)")


def iter_commontest_roots(base: Path):
    """Every src/commonTest/kotlin directory under the repo (module-generic)."""
    return sorted(p for p in base.rglob("src/commonTest/kotlin") if p.is_dir())


def scan_tree(base: Path):
    """Return a list of (file, line_number, import_path, reason) violations."""
    violations = []
    for root in iter_commontest_roots(base):
        for kt in sorted(root.rglob("*.kt")):
            text = kt.read_text(encoding="utf-8", errors="replace")
            for lineno, line in enumerate(text.splitlines(), start=1):
                m = IMPORT_RE.match(line)
                if not m:
                    continue
                target = m.group(1)
                for prefix, reason in FORBIDDEN_PREFIXES.items():
                    if target == prefix or target.startswith(prefix + "."):
                        violations.append((kt, lineno, target, reason))
    return violations


def enforce(base: Path) -> int:
    violations = scan_tree(base)
    if not violations:
        print("commonTest cross-platform test dependency contract: PASS")
        return 0
    print("commonTest cross-platform test dependency contract: FAIL")
    print("commonTest compiles for every shared target (incl. OHOS arm64);")
    print("the following imports are unavailable on at least one of them:")
    for path, lineno, target, reason in violations:
        rel = path.relative_to(base) if path.is_relative_to(base) else path
        print(f"  {rel}:{lineno}: import {target}")
        print(f"    -> {reason}")
    return 1


def self_test() -> int:
    clean = """package build.raft.app

import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

class PortableContractSelfTest {
    @Test
    fun portableForm() = runBlocking { CompletableDeferred(Unit).await() }
}
"""
    violating = clean.replace(
        "import kotlinx.coroutines.runBlocking",
        "import kotlinx.coroutines.runBlocking\nimport kotlinx.coroutines.test.runTest",
    )
    android_only = """package build.raft.app

import kotlinx.coroutines.test.runTest

// Legal outside commonTest: this lives in an androidUnitTest source set.
"""
    with tempfile.TemporaryDirectory() as td:
        base = Path(td)
        common_dir = base / "mod" / "src" / "commonTest" / "kotlin" / "build" / "raft" / "app"
        android_dir = base / "mod" / "src" / "androidUnitTest" / "kotlin" / "build" / "raft" / "app"
        common_dir.mkdir(parents=True)
        android_dir.mkdir(parents=True)

        # Arm 1: clean commonTest + androidUnitTest-only runTest => PASS.
        (common_dir / "Portable.kt").write_text(clean, encoding="utf-8")
        (android_dir / "AndroidOnly.kt").write_text(android_only, encoding="utf-8")
        if enforce(base) != 0:
            print("self-test FAIL: clean tree must pass", file=sys.stderr)
            return 1

        # Arm 2 (mutation): plant the #1920 import in commonTest => must FAIL.
        (common_dir / "Portable.kt").write_text(violating, encoding="utf-8")
        if enforce(base) == 0:
            print("self-test FAIL: planted violation must be caught", file=sys.stderr)
            return 1

        # Arm 3: restore clean, androidUnitTest import alone must stay legal.
        (common_dir / "Portable.kt").write_text(clean, encoding="utf-8")
        if enforce(base) != 0:
            print("self-test FAIL: androidUnitTest import must not be scanned", file=sys.stderr)
            return 1

    print("commonTest cross-platform dependency contract self-test: PASS (3 arms)")
    return 0


def main(argv) -> int:
    if "--self-test" in argv:
        return self_test()
    return enforce(REPO_ROOT)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
