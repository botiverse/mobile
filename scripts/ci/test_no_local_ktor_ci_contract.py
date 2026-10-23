#!/usr/bin/env python3
"""Ktor must reach the build only through the pinned public NetworkKMM module.

No checked-in Gradle/catalog/lock/metadata file under compose/ may reference
io.ktor directly.
"""
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


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
        source = path.read_text(errors="replace").lower()
        require("io.ktor" not in source, f"checked-in consumer metadata references io.ktor: {path}")

    print("no-local-Ktor CI contract: PASS")


if __name__ == "__main__":
    main()
