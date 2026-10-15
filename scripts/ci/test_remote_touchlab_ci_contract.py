#!/usr/bin/env python3
"""Remote OHOS fork-dependency contract.

The OHOS SQLDelight native-driver stack (bytemain forks of Touchlab Stately and
SQLiter plus SQLDelight itself) is consumed from Raft Artifacts as published,
immutable coordinates. No workflow may rebuild any of them from a submodule or
hydrate them through mavenLocal, and the OHOS Gradle build must route every one
of them to Raft Artifacts.
"""
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = (
    ROOT / ".github/workflows/test.yml",
    ROOT / ".github/workflows/ohos-release.yml",
)
SETTINGS = ROOT / "compose/settings.ohos.gradle.kts"
SHARED_BUILD = ROOT / "compose/shared/build.ohos.gradle.kts"

SQLDELIGHT_VERSION = "2.1.0-KBA-003"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def main() -> None:
    sources = {path: path.read_text() for path in WORKFLOWS}
    combined = "\n".join(sources.values())

    forbidden = (
        # Touchlab forks: never built or hydrated locally.
        "git rev-parse HEAD:third_party/stately",
        "git rev-parse HEAD:third_party/sqliter",
        "git submodule sync third_party/stately",
        "git submodule update --init third_party/stately",
        "pushd third_party/stately",
        "pushd third_party/sqliter",
        ":stately-strict:publish",
        ":stately-concurrency:publish",
        ":sqliter-driver:publish",
        "co/touchlab",
        # SQLDelight fork: consumed from Raft Artifacts, never from the
        # submodule source build or a mavenLocal cache.
        "third_party/sqldelight",
        "publish-forkdeps",
        "fork-deps",
        "forkdeps",
        ":sqldelight-gradle-plugin:publishToMavenLocal",
        ":drivers:native-driver:publishOhosArm64PublicationToMavenLocal",
        "app/cash/sqldelight",
        "RaftArtifactsTouchlab",
        "SLOCK_SKIP_OHOS_LOCAL_DEP_PREP",
        ".m2/repository",
        "mavenLocal()",
    )
    for value in forbidden:
        require(
            value not in combined,
            f"active CI still builds or hydrates an OHOS fork dependency locally: {value}",
        )

    for path, source in sources.items():
        require(
            "scripts/ci/test_remote_touchlab_ci_contract.py" in source,
            f"{path.name} no longer runs the remote fork-dependency contract",
        )

    settings = SETTINGS.read_text()
    for value in (
        "ensureSubmodule",
        "mavenArtifactExists",
        "needsSqlDelight",
        "skipOhosLocalDependencyPrep",
        "third_party/sqldelight",
        "publishToMavenLocal",
    ):
        require(value not in settings, f"OHOS consumer still prepares SQLDelight locally: {value}")
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
    require(
        settings.count('includeGroup("app.cash.sqldelight")') == 2,
        "OHOS consumer must route app.cash.sqldelight to Raft Artifacts for both "
        "plugin resolution and dependency resolution",
    )

    shared_build = SHARED_BUILD.read_text()
    require(
        shared_build.count("app.cash.sqldelight") == 3,
        "OHOS shared build must declare exactly the plugin, runtime and native-driver-ohosarm64 SQLDelight coordinates",
    )
    for coordinate in (
        f'id("app.cash.sqldelight") version "{SQLDELIGHT_VERSION}"',
        f'"app.cash.sqldelight:runtime:{SQLDELIGHT_VERSION}"',
        f'"app.cash.sqldelight:native-driver-ohosarm64:{SQLDELIGHT_VERSION}"',
    ):
        require(coordinate in shared_build, f"OHOS shared build does not pin {coordinate}")

    print("remote OHOS fork-dependency contract: PASS")


if __name__ == "__main__":
    main()
