#!/usr/bin/env python3
"""Static contract for resolving the Hands Android SDK only from Raft Maven."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(f"Hands Raft consumer contract failed: {message}")


# Without exclusiveContent/includeGroup, Gradle may resolve build.hands from any
# other declared repository (dependency confusion), and the build still passes.
settings = read("compose/settings.gradle.kts")
require('includeGroup("build.hands")' in settings, "build.hands is not exclusive to Raft")
require("exclusiveContent" in settings, "Hands Raft repository is not exclusive")

for workflow in (ROOT / ".github/workflows").glob("*.yml"):
    source = workflow.read_text(encoding="utf-8")
    require(
        "compose/github-packages.properties" not in source,
        f"{workflow.relative_to(ROOT)} still writes obsolete Maven credentials",
    )

print("Hands Android SDK Raft consumer contract: PASS")
