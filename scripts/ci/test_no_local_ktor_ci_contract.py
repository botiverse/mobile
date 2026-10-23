#!/usr/bin/env python3
"""Ktor must come only from published Maven Central artifacts, never a local fork.

Since artin's 2026-10-23 transport switch (#3598) Ktor is the app's HTTP
client, so :shared declares it directly. The contract still exists to keep a
local Ktor fork (#2098) from coming back:

- io.ktor may appear only in compose/gradle/libs.versions.toml, and only as
  `group = "io.ktor"` library entries whose version is `version.ref = "ktor"`.
- The `ktor` version is a plain published release (e.g. 2.3.7): no -SNAPSHOT,
  no local/fork suffix. It must equal NetworkKMM's Ktor so both share one
  binary-compatible copy.
- No other checked-in Gradle/lock/metadata file under compose/ references
  io.ktor (no inline coordinates, no includeBuild / substitution of a fork).
"""
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "compose" / "gradle" / "libs.versions.toml"
RELEASE_VERSION = re.compile(r"^\d+\.\d+\.\d+$")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def check_catalog() -> None:
    text = CATALOG.read_text()
    version = re.search(r'^ktor\s*=\s*"([^"]+)"', text, re.MULTILINE)
    require(version is not None, "libs.versions.toml must pin the Ktor version as `ktor = \"x.y.z\"`")
    require(
        RELEASE_VERSION.match(version.group(1)) is not None,
        f"Ktor must be a published release, got {version.group(1)!r}",
    )
    for line in text.splitlines():
        if "io.ktor" not in line.lower():
            continue
        require(
            re.search(r'group\s*=\s*"io\.ktor"', line) is not None
            and re.search(r'version\.ref\s*=\s*"ktor"', line) is not None,
            f"Ktor catalog entries must be `group = \"io.ktor\"` with `version.ref = \"ktor\"`: {line.strip()}",
        )


def main() -> None:
    consumer_suffixes = {
        ".gradle",
        ".kts",
        ".toml",
        ".lock",
        ".module",
        ".pom",
        ".properties",
        ".xml",
        ".json",
    }
    for path in (ROOT / "compose").rglob("*"):
        if not path.is_file() or path.suffix not in consumer_suffixes or "build" in path.parts:
            continue
        if path == CATALOG:
            continue
        source = path.read_text(errors="replace").lower()
        require("io.ktor" not in source, f"checked-in consumer metadata references io.ktor outside the catalog: {path}")
    check_catalog()

    print("no-local-Ktor CI contract: PASS")


if __name__ == "__main__":
    main()
