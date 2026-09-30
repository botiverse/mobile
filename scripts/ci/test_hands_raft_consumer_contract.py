#!/usr/bin/env python3
"""Static contract for resolving the Hands Android SDK only from Raft Maven."""

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(f"Hands Raft consumer contract failed: {message}")


settings = read("compose/settings.gradle.kts")
require('name = "RaftArtifactsHands"' in settings, "Hands Raft repository is missing")
require('includeGroup("build.hands")' in settings, "build.hands is not exclusive to Raft")
require("exclusiveContent" in settings, "Hands Raft repository is not exclusive")

for forbidden in (
    "GitHubPackagesHands",
    "maven.pkg.github.com/botiverse/hands",
    "githubPackagesCredentials",
    "github-packages.properties",
):
    require(forbidden not in settings, f"settings retains Hands fallback: {forbidden}")

metadata = json.loads(read("compose/config/hands-android-sdk.json"))
require(metadata["group"] == "build.hands", "Hands group drifted")
require(metadata["artifact"] == "hands-android-sdk", "Hands artifact drifted")
require(metadata["version"] == "0.13.0", "Hands version drifted")
require(
    metadata.get("sourceTag", "") in ("", f'android-sdk-v{metadata["version"]}'),
    "Hands source tag does not match the release version",
)
require(
    re.fullmatch(r"[0-9a-f]{40}", metadata["sourceCommit"]) is not None,
    "Hands source commit is not pinned",
)
require(
    re.fullmatch(r"[1-9][0-9]*", metadata["publishRunId"]) is not None,
    "Hands publication run is not pinned",
)
for key in ("aarSha256", "nativeSymbolsSha256"):
    value = metadata[key]
    require(len(value) == 64 and all(char in "0123456789abcdef" for char in value), f"{key} is invalid")

for workflow in (ROOT / ".github/workflows").glob("*.yml"):
    source = workflow.read_text(encoding="utf-8")
    require(
        "compose/github-packages.properties" not in source,
        f"{workflow.relative_to(ROOT)} still writes obsolete Maven credentials",
    )

require(
    not (ROOT / "compose/github-packages.properties.example").exists(),
    "obsolete GitHub Packages credentials example remains",
)

print("Hands Android SDK Raft consumer contract: PASS")
