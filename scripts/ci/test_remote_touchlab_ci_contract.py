#!/usr/bin/env python3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = (
    ROOT / ".github/workflows/test.yml",
    ROOT / ".github/workflows/ohos-release.yml",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def main() -> None:
    sources = {path: path.read_text() for path in WORKFLOWS}
    combined = "\n".join(sources.values())

    forbidden = (
        "git rev-parse HEAD:third_party/stately",
        "git rev-parse HEAD:third_party/sqliter",
        "git submodule sync third_party/stately",
        "git submodule update --init third_party/stately",
        "pushd third_party/stately",
        "pushd third_party/sqliter",
        ":stately-strict:publish",
        ":stately-concurrency:publish",
        ":sqliter-driver:publish",
        "forkdeps/co/touchlab/stately",
        "stage/co/touchlab/stately",
    )
    for value in forbidden:
        require(value not in combined, f"active CI still builds or hydrates Touchlab locally: {value}")

    for path, source in sources.items():
        require(
            'name = "RaftArtifactsTouchlab"' in source,
            f"{path.name} does not give the remaining SQLDelight build a Raft Touchlab repository",
        )
        require(
            'url = uri("https://maven.artifacts.botiverse.dev")' in source,
            f"{path.name} does not bind the Touchlab repository to Raft Artifacts",
        )
        require(
            'includeGroup("co.touchlab")' in source,
            f"{path.name} does not keep co.touchlab exclusive to Raft Artifacts",
        )
        require(
            'if (rootProject.name == "sqldelight")' in source,
            f"{path.name} does not scope the Touchlab repository to SQLDelight",
        )
        require(
            "git submodule update --init third_party/sqldelight" in source,
            f"{path.name} no longer prepares the still-required SQLDelight source",
        )
    test_source = sources[WORKFLOWS[0]]
    require(
        test_source.count('caches/modules-2/files-2.1/co.touchlab') == 4,
        "Test producer and both OHOS consumers must evict Gradle Touchlab caches",
    )
    require(
        test_source.count('test ! -e "$forkdeps/co/touchlab"') == 2,
        "both Test OHOS consumers must reject staged Touchlab mavenLocal content",
    )
    require(
        test_source.count('rm -rf "$dst/co/touchlab"') == 2,
        "both Test OHOS consumers must start without Touchlab in mavenLocal",
    )

    release_source = sources[WORKFLOWS[1]]
    require(
        release_source.count('caches/modules-2/files-2.1/co.touchlab') == 3,
        "OHOS Release producer and consumer must evict Gradle Touchlab caches",
    )
    require(
        'test ! -e "$src/co/touchlab"' in release_source,
        "OHOS Release must reject restored Touchlab mavenLocal content",
    )
    require(
        'rm -rf "$dst/co/touchlab"' in release_source,
        "OHOS Release must start without Touchlab in mavenLocal",
    )

    settings = (ROOT / "compose/settings.ohos.gradle.kts").read_text()
    for module in (
        "sqliter-driver",
        "sqliter-driver-ohosarm64",
        "stately-strict",
        "stately-strict-ohosarm64",
        "stately-concurrency",
        "stately-concurrency-ohosarm64",
    ):
        require(
            f'includeModule("co.touchlab", "{module}")' in settings,
            f"OHOS consumer no longer routes {module} to Raft Artifacts",
        )

    print("remote Touchlab CI contract: PASS")


if __name__ == "__main__":
    main()
