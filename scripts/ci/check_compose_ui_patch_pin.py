#!/usr/bin/env python3
"""The CMP-10674 Compose UI pin must match the app's Compose Multiplatform version.

compose/gradle.properties pins org.jetbrains.compose.ui:ui-iosarm64 and :ui-iossimulatorarm64 to a
patched build (third_party/compose-ui-1.12.2/README.md):

    raft.composeUiPatch.base=1.12.2            # the official version the patch is built from
    raft.composeUiPatch.version=1.12.2-raft.1  # the patched build the app resolves instead

compose/build.gradle.kts rewrites only requests for exactly `base`. After a Compose Multiplatform
upgrade (composePlugin in compose/gradle/libs.versions.toml) the rule would silently stop matching
and the fix would be gone, or a stale patched klib could be mixed with a newer root module. So:

- pin present (either property set) -> both must be set, composePlugin must equal `base`, and
  `version` must be `<base>-raft.<N>`;
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
    app_version = compose_version(root / CATALOG)
    if app_version is None:
        errors.append(f"{CATALOG}: no `composePlugin = \"x.y.z\"` version found.")
    elif app_version != base:
        errors.append(
            f"Compose Multiplatform is {app_version} ({CATALOG} composePlugin) but the CMP-10674 "
            f"Compose UI pin in {PROPERTIES} patches {base} ({VERSION_KEY}={version}). The upgrade "
            f"happened: if {app_version} contains the fix, remove the pin ({BASE_KEY}, "
            f"{VERSION_KEY}, the RaftArtifactsComposeUiPatch repository and the rule in "
            f"compose/build.gradle.kts; see 'When to remove' in {README}); otherwise rebuild the "
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
    pin = "org.gradle.caching=true\n{props}"
    good = f"{BASE_KEY}=1.12.2\n{VERSION_KEY}=1.12.2-raft.1\n"
    cases = [
        # (name, composePlugin, properties, expected substring of the error or None = pass)
        ("pinned, same base", "1.12.2", good, None),
        ("no pin", "1.13.0", "", None),
        ("upgraded with pin left", "1.12.3", good, "The upgrade happened"),
        ("pinned version off base", "1.12.2", f"{BASE_KEY}=1.12.2\n{VERSION_KEY}=1.12.3-raft.1\n", "must be '1.12.2-raft.<N>'"),
        ("pinned version not raft", "1.12.2", f"{BASE_KEY}=1.12.2\n{VERSION_KEY}=1.12.2\n", "must be '1.12.2-raft.<N>'"),
        ("base without version", "1.12.2", f"{BASE_KEY}=1.12.2\n", "set both"),
        ("version without base", "1.12.2", f"{VERSION_KEY}=1.12.2-raft.1\n", "set both"),
        ("spaces around =", "1.12.2", f"{BASE_KEY} = 1.12.2\n{VERSION_KEY} = 1.12.2-raft.2\n", None),
        ("commented-out pin", "1.13.0", f"#{BASE_KEY}=1.12.2\n# {VERSION_KEY}=1.12.2-raft.1\n", None),
    ]
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / CATALOG).parent.mkdir(parents=True)
        for name, cmp, props, expected in cases:
            (root / CATALOG).write_text(catalog.format(cmp=cmp))
            (root / PROPERTIES).write_text(pin.format(props=props))
            errors = check(root)
            ok = (not errors) if expected is None else any(expected in e for e in errors)
            if not ok:
                failed += 1
                print(f"self-test FAIL: {name}: expected {expected or 'pass'}, got {errors}")
        # run() exit codes, with the ::error:: output kept out of the CI log.
        (root / CATALOG).write_text(catalog.format(cmp="1.12.3"))
        (root / PROPERTIES).write_text(pin.format(props=good))
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
