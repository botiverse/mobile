#!/usr/bin/env python3
"""The CMP-10674 Compose UI pin must match the app's Compose Multiplatform version.

compose/gradle.properties pins org.jetbrains.compose.ui:ui-iosarm64 and :ui-iossimulatorarm64 to a
patched build (third_party/compose-ui-1.12.2/README.md):

    raft.composeUiPatch.base=1.12.2            # the official version the patch is built from
    raft.composeUiPatch.version=1.12.2-raft.1  # the patched build the app resolves instead
    raft.composeUiPatch.sha256.ui-iosarm64=<64 hex>           # published klib bytes
    raft.composeUiPatch.sha256.ui-iossimulatorarm64=<64 hex>

compose/build.gradle.kts rewrites only requests for exactly `base`. After a Compose Multiplatform
upgrade (composePlugin in compose/gradle/libs.versions.toml) the rule would silently stop matching
and the fix would be gone, or a stale patched klib could be mixed with a newer root module. So:

- pin present (base or version set) -> both must be set, composePlugin must equal `base`,
  `version` must be `<base>-raft.<N>`, both sha256 properties must be 64 hex characters, and the
  gate must stay wired: compose/build.gradle.kts registers verifyComposeUiPatch and each workflow
  in WORKFLOWS runs `:verifyComposeUiPatch` on a non-comment line;
- pin absent -> nothing to check.

Usage: check_compose_ui_patch_pin.py [--root DIR] | --self-test
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

README = "third_party/compose-ui-1.12.2/README.md"
PROPERTIES = Path("compose/gradle.properties")
CATALOG = Path("compose/gradle/libs.versions.toml")
BASE_KEY = "raft.composeUiPatch.base"
VERSION_KEY = "raft.composeUiPatch.version"
SHA_KEYS = tuple(f"raft.composeUiPatch.sha256.{m}" for m in ("ui-iosarm64", "ui-iossimulatorarm64"))
BUILD_SCRIPT = Path("compose/build.gradle.kts")
TASK_REGISTRATION = 'tasks.register("verifyComposeUiPatch")'
# Workflows that build the iOS app: CI compile, the release IPA, the on-demand simulator app.
WORKFLOWS = (
    Path(".github/workflows/test.yml"),
    Path(".github/workflows/ios-release.yml"),
    Path(".github/workflows/ios-simulator-app.yml"),
)
TASK_CALL = re.compile(r"^(?!\s*#).*(?<![\w:-]):verifyComposeUiPatch\b", re.MULTILINE)


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
    base = props.get(BASE_KEY)
    version = props.get(VERSION_KEY)
    if base is None and version is None:
        return []
    errors: list[str] = []
    if not base or not version:
        errors.append(
            f"{PROPERTIES}: set both {BASE_KEY} and {VERSION_KEY} (CMP-10674 pin, {README}), "
            "or delete both to drop the pin."
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
            errors.append(f"{PROPERTIES}: {key} is missing; the pin needs the published klib SHA-256 ({README}).")
        elif not re.fullmatch(r"[0-9a-f]{64}", value):
            errors.append(f"{PROPERTIES}: {key}={value} is not a lowercase 64-hex SHA-256.")
    build_script = root / BUILD_SCRIPT
    if not build_script.is_file() or TASK_REGISTRATION not in build_script.read_text():
        errors.append(
            f"{BUILD_SCRIPT} no longer has {TASK_REGISTRATION}: the CMP-10674 pin is on, so the check "
            f"that its klibs resolve stays ({README})."
        )
    for workflow in WORKFLOWS:
        path = root / workflow
        if not path.is_file() or not TASK_CALL.search(path.read_text()):
            errors.append(
                f"{workflow} must run `./gradlew :verifyComposeUiPatch` while the CMP-10674 pin is on ({README})."
            )
    app_version = compose_version(root / CATALOG)
    if app_version is None:
        errors.append(f"{CATALOG}: no `composePlugin = \"x.y.z\"` version found.")
    elif app_version != base:
        errors.append(
            f"Compose Multiplatform is {app_version} ({CATALOG} composePlugin) but the CMP-10674 "
            f"Compose UI pin in {PROPERTIES} patches {base} ({VERSION_KEY}={version}). The upgrade "
            f"happened: if {app_version} contains the fix, remove the pin ({BASE_KEY}, "
            f"{VERSION_KEY}, the sha256 properties, the RaftArtifactsComposeUiPatch repository, the "
            f"rule and verifyComposeUiPatch in compose/build.gradle.kts and the :verifyComposeUiPatch "
            f"workflow calls; see 'When to remove' in {README}); otherwise rebuild the "
            f"patch for {app_version} and point both properties at it."
        )
    return errors


def run(root: Path) -> int:
    errors = check(root)
    for error in errors:
        print(f"::error::{error}" if "GITHUB_ACTIONS" in os.environ else error)
    if errors:
        return 1
    print("compose-ui patch pin: PASS")
    return 0


# ---------------------------------------------------------------- self-test


def self_test() -> int:
    catalog = '[versions]\nkotlin = "2.4.21"\ncomposePlugin = "{cmp}"\n'
    sha_a, sha_b = "a" * 64, "0123456789abcdef" * 4
    shas = f"{SHA_KEYS[0]}={sha_a}\n{SHA_KEYS[1]}={sha_b}\n"
    good = f"{BASE_KEY}=1.12.2\n{VERSION_KEY}=1.12.2-raft.1\n" + shas
    build = 'allprojects {}\n' + TASK_REGISTRATION + ' {\n}\n'
    call = "      - run: ./gradlew :verifyComposeUiPatch :shared:syncFramework --no-daemon\n"
    wired = {w: "jobs:\n  build:\n    steps:\n" + call for w in WORKFLOWS}
    other = "      - run: ./gradlew :shared:syncFramework --no-daemon\n"

    def with_workflow(index: int, text: str) -> dict[Path, str]:
        return {**wired, WORKFLOWS[index]: "jobs:\n" + text}

    # (name, composePlugin, properties, build script, workflows, expected error substring or None = pass)
    cases = [
        ("pinned, same base", "1.12.2", good, build, wired, None),
        ("no pin, nothing wired", "1.13.0", "", "", {}, None),
        ("upgraded with pin left", "1.12.3", good, build, wired, "The upgrade happened"),
        ("pinned version off base", "1.12.2", good.replace("=1.12.2-raft.1", "=1.12.3-raft.1"), build, wired,
         "must be '1.12.2-raft.<N>'"),
        ("pinned version not raft", "1.12.2", good.replace("=1.12.2-raft.1", "=1.12.2"), build, wired,
         "must be '1.12.2-raft.<N>'"),
        ("base without version", "1.12.2", f"{BASE_KEY}=1.12.2\n" + shas, build, wired, "set both"),
        ("version without base", "1.12.2", f"{VERSION_KEY}=1.12.2-raft.1\n" + shas, build, wired, "set both"),
        ("spaces around =", "1.12.2", good.replace("=", " = "), build, wired, None),
        ("commented-out pin", "1.13.0", "".join("#" + line + "\n" for line in good.splitlines()), "", {}, None),
        ("sha missing", "1.12.2", good.replace(f"{SHA_KEYS[1]}={sha_b}\n", ""), build, wired,
         f"{SHA_KEYS[1]} is missing"),
        ("sha too short", "1.12.2", good.replace(sha_a, "a" * 63), build, wired, "not a lowercase 64-hex"),
        ("sha not hex", "1.12.2", good.replace(sha_b, "g" * 64), build, wired, "not a lowercase 64-hex"),
        ("sha uppercase", "1.12.2", good.replace(sha_b, sha_b.upper()), build, wired, "not a lowercase 64-hex"),
        ("task registration removed", "1.12.2", good, "allprojects {}\n", wired, "no longer has"),
        ("test.yml call removed", "1.12.2", good, build, with_workflow(0, other), "test.yml must run"),
        ("ios-release.yml call removed", "1.12.2", good, build, with_workflow(1, other), "ios-release.yml must run"),
        ("ios-release.yml call commented out", "1.12.2", good, build,
         with_workflow(1, "      # ./gradlew :verifyComposeUiPatch\n" + other), "ios-release.yml must run"),
        ("ios-release.yml workflow deleted", "1.12.2", good, build,
         {w: t for w, t in wired.items() if w != WORKFLOWS[1]}, "ios-release.yml must run"),
        ("only a subproject task", "1.12.2", good, build,
         with_workflow(1, "      - run: ./gradlew :shared:verifyComposeUiPatch\n"), "ios-release.yml must run"),
        ("call in a tasks array", "1.12.2", good, build,
         with_workflow(0, "          tasks=(\n            :verifyComposeUiPatch\n          )\n"), None),
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
    print("check_compose_ui_patch_pin self-test: " + ("FAILED" if failed else "OK") + f" ({len(cases) + 1} cases)")
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
