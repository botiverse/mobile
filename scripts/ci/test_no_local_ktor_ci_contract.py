#!/usr/bin/env python3
import json
import time
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = (ROOT / ".github/workflows/test.yml",)
NETWORK_VERSIONS = ("0.1.0-raft.35",)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def read_public(version: str, extension: str) -> bytes:
    url = (
        "https://maven.artifacts.botiverse.dev/com/tencent/kuiklybase/network/"
        f"{version}/network-{version}.{extension}"
    )
    error = None
    for attempt in range(3):
        try:
            request = urllib.request.Request(
                url,
                headers={"User-Agent": "mobile-no-local-ktor-contract/1"},
            )
            with urllib.request.urlopen(request, timeout=20) as response:
                require(
                    response.status == 200,
                    f"unexpected NetworkKMM {version}.{extension} HTTP {response.status}",
                )
                return response.read()
        except Exception as exc:  # Network errors need bounded retries in Hosted CI.
            error = exc
            time.sleep(attempt + 1)
    raise SystemExit(f"unable to read NetworkKMM {version}.{extension}: {error}")


def main() -> None:
    sources = {path: path.read_text() for path in WORKFLOWS}
    combined = "\n".join(sources.values())

    forbidden = (
        "sqldelight-ktor-mavenlocal",
        'cp -a "$repo/io/ktor"',
        'cp -a "$forkdeps/io/ktor"',
    )
    for value in forbidden:
        require(value not in combined, f"active CI still builds or hydrates Ktor locally: {value}")

    # No workflow hydrates mavenLocal at all. Ktor can therefore only re-enter through a checked-in consumer, verified
    # below; the pinned public NetworkKMM module must still resolve at its expected version.
    for path, source in sources.items():
        for value in (".m2/repository", "mavenLocal()", "io/ktor", "io.ktor"):
            require(
                value not in source,
                f"{path.name} hydrates or references local Ktor content: {value}",
            )

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

    for version in NETWORK_VERSIONS:
        module = json.loads(read_public(version, "module"))
        require(module.get("component", {}).get("version") == version, "NetworkKMM module version drift")

    print("no-local-Ktor CI contract: PASS")


if __name__ == "__main__":
    main()
