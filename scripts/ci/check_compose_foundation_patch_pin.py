#!/usr/bin/env python3
"""The Compose foundation patch pin must match the app's Compose Multiplatform version.

compose/gradle.properties pins androidx.compose.foundation:foundation-android (Android only) to a
patched build (third_party/compose-foundation-1.12.1/README.md):

    raft.composeFoundationPatch.composeMultiplatform=1.12.2  # the CMP version whose Android foundation is base
    raft.composeFoundationPatch.base=1.12.1                  # the official version the patch is built from
    raft.composeFoundationPatch.version=1.12.1-raft.1        # the patched build the app resolves instead
    raft.composeFoundationPatch.sha256.foundation-android=<64 hex>  # published AAR bytes

compose/build.gradle.kts rewrites only requests for exactly `base`. After a Compose Multiplatform
upgrade (composePlugin in compose/gradle/libs.versions.toml) the app's Android foundation moves on:
the rule would silently stop matching and the fix would be gone, or a stale patched AAR could be
mixed with a newer Compose. So:

- pin present (any of composeMultiplatform, base, version set) -> all three must be set,
  composePlugin must equal `composeMultiplatform`, `version` must be `<base>-raft.<N>`, the sha256
  property must be 64 hex characters, and the gate must stay wired: compose/build.gradle.kts
  registers verifyComposeFoundationPatch (which fails the build when foundation-android resolves
  to anything but `version`) and each workflow in WORKFLOWS runs `:verifyComposeFoundationPatch`
  on a non-comment line;
- pin absent -> nothing to check.

Usage: check_compose_foundation_patch_pin.py [--root DIR] | --self-test
"""
from __future__ import annotations

import argparse
import contextlib
import io
import os
import re
import sys
import tempfile
from pathlib import Path

README = "third_party/compose-foundation-1.12.1/README.md"
PROPERTIES = Path("compose/gradle.properties")
CATALOG = Path("compose/gradle/libs.versions.toml")
COMPOSE_KEY = "raft.composeFoundationPatch.composeMultiplatform"
BASE_KEY = "raft.composeFoundationPatch.base"
VERSION_KEY = "raft.composeFoundationPatch.version"
SHA_KEYS = ("raft.composeFoundationPatch.sha256.foundation-android",)
BUILD_SCRIPT = Path("compose/build.gradle.kts")
TASK_REGISTRATION = 'tasks.register("verifyComposeFoundationPatch")'
# Workflows that build the Android app: CI compile and tests, the release APK/AAB, the Alpha APK.
WORKFLOWS = (
    Path(".github/workflows/test.yml"),
    Path(".github/workflows/android-release.yml"),
    Path(".github/workflows/android-alpha.yml"),
)
TASK_CALL = re.compile(r"^(?!\s*#).*(?<![\w:-]):verifyComposeFoundationPatch\b", re.MULTILINE)


def read_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line[0] in "#!":
            continue
        key, sep, value = line.partition("=")
        if sep:
            props[key.strip()] = value.strip()
    return props


def compose_version(path: Path) -> str | None:
    match = re.search(r'^composePlugin\s*=\s*"([^"]+)"', path.read_text(), re.MULTILINE)
    return match.group(1) if match else None


def check(root: Path) -> list[str]:
    props = read_properties(root / PROPERTIES)
    compose = props.get(COMPOSE_KEY)
    base = props.get(BASE_KEY)
    version = props.get(VERSION_KEY)
    if compose is None and base is None and version is None:
        return []
    errors: list[str] = []
    if not compose or not base or not version:
        errors.append(
            f"{PROPERTIES}: set all of {COMPOSE_KEY}, {BASE_KEY} and {VERSION_KEY} (Compose foundation "
            f"patch pin, {README}), or delete them all to drop the pin."
        )
        return errors
    if not re.fullmatch(re.escape(base) + r"-raft\.\d+", version):
        errors.append(
            f"{PROPERTIES}: {VERSION_KEY}={version} must be '{base}-raft.<N>' "
            f"(the patched build of {BASE_KEY}={base}; {README})."
        )
    for key in SHA_KEYS:
        value = props.get(key)
        if value is None:
            errors.append(f"{PROPERTIES}: {key} is missing; the pin needs the published AAR SHA-256 ({README}).")
        elif not re.fullmatch(r"[0-9a-f]{64}", value):
            errors.append(f"{PROPERTIES}: {key}={value} is not a lowercase 64-hex SHA-256.")
    build_script = root / BUILD_SCRIPT
    if not build_script.is_file() or TASK_REGISTRATION not in build_script.read_text():
        errors.append(
            f"{BUILD_SCRIPT} no longer has {TASK_REGISTRATION}: the Compose foundation pin is on, so the "
            f"check that its AAR resolves stays ({README})."
        )
    for workflow in WORKFLOWS:
        path = root / workflow
        if not path.is_file() or not TASK_CALL.search(path.read_text()):
            errors.append(
                f"{workflow} must run `./gradlew :verifyComposeFoundationPatch` while the Compose foundation "
                f"pin is on ({README})."
            )
    app_version = compose_version(root / CATALOG)
    if app_version is None:
        errors.append(f"{CATALOG}: no `composePlugin = \"x.y.z\"` version found.")
    elif app_version != compose:
        errors.append(
            f"Compose Multiplatform is {app_version} ({CATALOG} composePlugin) but the Compose foundation "
            f"patch in {PROPERTIES} is for Compose Multiplatform {compose}, whose Android foundation is "
            f"{base} ({VERSION_KEY}={version}). The upgrade happened, so the patch is left behind: if the "
            f"androidx foundation of {app_version} contains the fix, remove the pin ({COMPOSE_KEY}, "
            f"{BASE_KEY}, {VERSION_KEY}, the sha256 property, the RaftArtifactsComposeFoundationPatch "
            f"repository, the rule and verifyComposeFoundationPatch in compose/build.gradle.kts and the "
            f":verifyComposeFoundationPatch workflow calls; see 'When to remove' in {README}); otherwise "
            f"re-make the patch for that foundation version and point the properties at it."
        )
    return errors


def run(root: Path) -> int:
    errors = check(root)
    for error in errors:
        print(f"::error::{error}" if "GITHUB_ACTIONS" in os.environ else error)
    if errors:
        return 1
    print("compose-foundation patch pin: PASS")
    return 0


# ---------------------------------------------------------------- self-test


def self_test() -> int:
    catalog = '[versions]\nkotlin = "2.4.21"\ncomposePlugin = "{cmp}"\n'
    sha = "0123456789abcdef" * 4
    shas = f"{SHA_KEYS[0]}={sha}\n"
    good = f"{COMPOSE_KEY}=1.12.2\n{BASE_KEY}=1.12.1\n{VERSION_KEY}=1.12.1-raft.1\n" + shas
    build = 'allprojects {}\n' + TASK_REGISTRATION + ' {\n}\n'
    call = "      - run: ./gradlew :verifyComposeFoundationPatch :androidApp:assembleRelease --no-daemon\n"
    wired = {w: "jobs:\n  build:\n    steps:\n" + call for w in WORKFLOWS}
    other = "      - run: ./gradlew :androidApp:assembleRelease --no-daemon\n"

    def with_workflow(index: int, text: str) -> dict[Path, str]:
        return {**wired, WORKFLOWS[index]: "jobs:\n" + text}

    # (name, composePlugin, properties, build script, workflows, expected error substring or None = pass)
    cases = [
        ("pinned, same Compose Multiplatform", "1.12.2", good, build, wired, None),
        ("no pin, nothing wired", "1.13.0", "", "", {}, None),
        ("upgraded with pin left", "1.12.3", good, build, wired, "The upgrade happened"),
        ("pinned version off base", "1.12.2", good.replace("=1.12.1-raft.1", "=1.12.2-raft.1"), build, wired,
         "must be '1.12.1-raft.<N>'"),
        ("pinned version not raft", "1.12.2", good.replace("=1.12.1-raft.1", "=1.12.1"), build, wired,
         "must be '1.12.1-raft.<N>'"),
        ("composeMultiplatform missing", "1.12.2", good.replace(f"{COMPOSE_KEY}=1.12.2\n", ""), build, wired,
         "set all of"),
        ("base missing", "1.12.2", good.replace(f"{BASE_KEY}=1.12.1\n", ""), build, wired, "set all of"),
        ("version missing", "1.12.2", good.replace(f"{VERSION_KEY}=1.12.1-raft.1\n", ""), build, wired, "set all of"),
        ("spaces around =", "1.12.2", good.replace("=", " = "), build, wired, None),
        ("commented-out pin", "1.13.0", "".join("#" + line + "\n" for line in good.splitlines()), "", {}, None),
        ("sha missing", "1.12.2", good.replace(shas, ""), build, wired, f"{SHA_KEYS[0]} is missing"),
        ("sha too short", "1.12.2", good.replace(sha, sha[:-1]), build, wired, "not a lowercase 64-hex"),
        ("sha not hex", "1.12.2", good.replace(sha, "g" * 64), build, wired, "not a lowercase 64-hex"),
        ("sha uppercase", "1.12.2", good.replace(sha, sha.upper()), build, wired, "not a lowercase 64-hex"),
        ("task registration removed", "1.12.2", good, "allprojects {}\n", wired, "no longer has"),
        ("test.yml call removed", "1.12.2", good, build, with_workflow(0, other), "test.yml must run"),
        ("android-release.yml call removed", "1.12.2", good, build, with_workflow(1, other),
         "android-release.yml must run"),
        ("android-alpha.yml call removed", "1.12.2", good, build, with_workflow(2, other), "android-alpha.yml must run"),
        ("android-release.yml call commented out", "1.12.2", good, build,
         with_workflow(1, "      # ./gradlew :verifyComposeFoundationPatch\n" + other), "android-release.yml must run"),
        ("android-alpha.yml workflow deleted", "1.12.2", good, build,
         {w: t for w, t in wired.items() if w != WORKFLOWS[2]}, "android-alpha.yml must run"),
        ("only a subproject task", "1.12.2", good, build,
         with_workflow(1, "      - run: ./gradlew :androidApp:verifyComposeFoundationPatch\n"),
         "android-release.yml must run"),
        ("call in a tasks array", "1.12.2", good, build,
         with_workflow(0, "          tasks=(\n            :verifyComposeFoundationPatch\n          )\n"), None),
        ("call in a quoted args array", "1.12.2", good, build,
         with_workflow(1, '          GRADLE_ARGS=(\n            ":verifyComposeFoundationPatch"\n          )\n'), None),
    ]
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        for i, (name, cmp, props, build_text, workflows, expected) in enumerate(cases):
            root = Path(tmp) / str(i)
            (root / CATALOG).parent.mkdir(parents=True)
            (root / CATALOG).write_text(catalog.format(cmp=cmp))
            (root / PROPERTIES).write_text("org.gradle.caching=true\n" + props)
            (root / BUILD_SCRIPT).write_text(build_text)
            for workflow, text in workflows.items():
                (root / workflow).parent.mkdir(parents=True, exist_ok=True)
                (root / workflow).write_text(text)
            errors = check(root)
            ok = (not errors) if expected is None else any(expected in e for e in errors)
            if not ok:
                failed += 1
                print(f"self-test FAIL: {name}: expected {expected or 'pass'}, got {errors}")
        # run() exit code on a stale pin, with the ::error:: output kept out of the CI log.
        root = Path(tmp) / "run"
        (root / CATALOG).parent.mkdir(parents=True)
        (root / CATALOG).write_text(catalog.format(cmp="1.12.3"))
        (root / PROPERTIES).write_text(good)
        (root / BUILD_SCRIPT).write_text(build)
        with contextlib.redirect_stdout(io.StringIO()):
            code = run(root)
        if code != 1:
            failed += 1
            print(f"self-test FAIL: run() on a stale pin returned {code}, expected 1")
    print("check_compose_foundation_patch_pin self-test: " + ("FAILED" if failed else "OK") + f" ({len(cases) + 1} cases)")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run(Path(args.root))


if __name__ == "__main__":
    sys.exit(main())
