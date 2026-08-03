#!/usr/bin/env bash

set -euo pipefail

python3 <<'PY'
from pathlib import Path

build_path = Path("compose/shared/build.gradle.kts")
workflow_path = Path(".github/workflows/ios-release.yml")
build = build_path.read_text()
workflow = workflow_path.read_text()

flag = "-Xdisable-phases=RemoveRedundantCallsToStaticInitializersPhase"
heap_line = (
    "org.gradle.jvmargs=-Xmx8g -Xss4m -XX:MaxMetaspaceSize=1g "
    "-Dfile.encoding=UTF-8 -Dkotlin.daemon.jvm.options\\=\"-Xmx8g,-Xss4m\""
)


def validate(build_text: str, workflow_text: str) -> None:
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

    if workflow_text.count(heap_line) != 1:
        raise ValueError(f"{workflow_path}: expected the release runner's exact 8 GiB heap contract")
    if "-Xmx12g" in workflow_text:
        raise ValueError(f"{workflow_path}: temporary 12 GiB carrier fallback must not become permanent")


def expect_failure(name: str, build_text: str = build, workflow_text: str = workflow) -> None:
    try:
        validate(build_text, workflow_text)
    except (ValueError, IndexError):
        print(f"ok {name}")
        return
    raise SystemExit(f"mutation fixture unexpectedly passed: {name}")


validate(build, workflow)
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
expect_failure("12g-fallback", workflow_text=workflow.replace("-Xmx8g", "-Xmx12g"))

print("iOS Kotlin/Native optimizer contract: PASS")
PY
