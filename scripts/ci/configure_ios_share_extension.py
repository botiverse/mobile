#!/usr/bin/env python3
"""Configure whether the iOS app embeds RaftShareExtension during CI release builds.

The Xcode project embeds `RaftShareExtension.appex` into `iosApp` by default so Simulator,
Debug, `test.yml`, and signed release builds with `SLOCK_IOS_SHARE_EXTENSION_PROFILE_BASE64`
all compile and bundle the Share Extension. When `ios-release.yml` runs before the Share
Extension App Store provisioning profile secret has been provisioned, calling `strip` removes
only the `iosApp` target dependency on `RaftShareExtension` and its `Embed App Extensions`
build phase from the ephemeral checkout's `project.pbxproj`, keeping `ios-release.yml` green
without touching any committed project structure.

Usage:
  configure_ios_share_extension.py strip PATH_TO_PBXPROJ
  configure_ios_share_extension.py --self-test
"""
import re
import sys
import tempfile
from pathlib import Path

EMBED_PHASE_RE = re.compile(
    r"^[ \t]*[A-Z0-9]+ /\* Embed App Extensions \*/,\n",
    re.MULTILINE,
)
TARGET_DEPENDENCY_RE = re.compile(
    r"^[ \t]*E36000080000000000000001 /\* PBXTargetDependency \*/,\n",
    re.MULTILINE,
)


def strip_share_extension_embedding(pbxproj_text: str) -> str:
    """Remove the Share Extension target dependency and Embed App Extensions phase from iosApp."""
    updated, embed_count = EMBED_PHASE_RE.subn("", pbxproj_text)
    if embed_count != 1:
        raise ValueError(
            f"Expected exactly 1 'Embed App Extensions' build phase entry, found {embed_count}"
        )
    updated, dep_count = TARGET_DEPENDENCY_RE.subn("", updated)
    if dep_count != 1:
        raise ValueError(
            f"Expected exactly 1 RaftShareExtension PBXTargetDependency entry, found {dep_count}"
        )
    return updated


def run_strip(pbxproj_path: str) -> int:
    path = Path(pbxproj_path)
    original = path.read_text(encoding="utf-8")
    updated = strip_share_extension_embedding(original)
    path.write_text(updated, encoding="utf-8")
    print(f"Stripped RaftShareExtension embedding from {pbxproj_path} for release archive without extension profile.")
    return 0


def self_test() -> int:
    repo_pbxproj = Path(__file__).resolve().parents[2] / "compose/iosApp/iosApp.xcodeproj/project.pbxproj"
    original = repo_pbxproj.read_text(encoding="utf-8")
    stripped = strip_share_extension_embedding(original)
    assert "/* Embed App Extensions */," not in stripped, "Embed App Extensions build phase entry remained"
    assert "E36000080000000000000001 /* PBXTargetDependency */," not in stripped, "Share Extension target dependency remained"
    # The main app target, test targets, and Share Extension target definition itself stay intact.
    assert "7555FF7A242A565900829871 /* iosApp */ = {" in stripped
    assert "E36000040000000000000001 /* RaftShareExtension */ = {" in stripped
    assert stripped.count("RAFT_QA_SESSION_BOOTSTRAP") == 1

    # Stripping twice fails loudly rather than silently succeeding.
    try:
        strip_share_extension_embedding(stripped)
        raise AssertionError("Expected second strip call to fail")
    except ValueError:
        pass

    with tempfile.TemporaryDirectory() as tmp:
        copy = Path(tmp, "project.pbxproj")
        copy.write_text(original, encoding="utf-8")
        assert run_strip(str(copy)) == 0
        assert copy.read_text(encoding="utf-8") == stripped

    print("configure_ios_share_extension self-test: OK")
    return 0


def main(argv: list[str]) -> int:
    if argv[1:] == ["--self-test"]:
        return self_test()
    if len(argv) == 3 and argv[1] == "strip":
        return run_strip(argv[2])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
