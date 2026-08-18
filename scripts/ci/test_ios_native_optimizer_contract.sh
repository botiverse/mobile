#!/usr/bin/env bash

set -euo pipefail

python3 <<'PY'
from pathlib import Path

build_path = Path("compose/shared/build.gradle.kts")
build = build_path.read_text()

flag = "-Xdisable-phases=RemoveRedundantCallsToStaticInitializersPhase"


def validate(build_text: str) -> None:
    if build_text.count(flag) != 1:
        raise ValueError(f"{build_path}: expected exactly one {flag!r}")

    cocoapods_start = build_text.index("    cocoapods {")
    source_sets_start = build_text.index("    sourceSets {", cocoapods_start)
    cocoapods_block = build_text[cocoapods_start:source_sets_start]
    framework_start = cocoapods_block.index("        framework {")
    framework_end = cocoapods_block.index("        }\n", framework_start) + len("        }\n")
    framework_block = cocoapods_block[framework_start:framework_end]
    if framework_block.count(flag) != 1:
        raise ValueError(f"{build_path}: optimizer override must stay CocoaPods-framework scoped")

    if "objcExportEntryPointsPath" in build_text:
        raise ValueError(f"{build_path}: ObjC entry-point filtering is not the OOM fix")


def expect_failure(name: str, build_text: str = build) -> None:
    try:
        validate(build_text)
    except (ValueError, IndexError):
        print(f"ok {name}")
        return
    raise SystemExit(f"mutation fixture unexpectedly passed: {name}")


validate(build)
expect_failure("missing-phase", build_text=build.replace(flag, ""))
expect_failure("duplicate-phase", build_text=build.replace(flag, f"{flag},{flag}"))
expect_failure(
    "phase-outside-framework",
    build_text=build.replace(flag, "").replace(
        "        extraSpecAttributes",
        f"        // {flag}\n        extraSpecAttributes",
        1,
    ),
)
expect_failure("objc-entry-point-workaround", build_text=build + "\nobjcExportEntryPointsPath\n")

print("iOS Kotlin/Native optimizer contract: PASS")
PY
