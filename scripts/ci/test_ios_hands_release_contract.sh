#!/usr/bin/env bash
set -euo pipefail

# The iOS release must refuse to publish unless reviewed zh-CN, en and ja notes
# are all present; otherwise TestFlight silently ships a build with missing or
# stale notes for some locales.
python3 - <<'PY'
from pathlib import Path

workflow = Path(".github/workflows/ios-release.yml").read_text()

required_files = (
    '${SLOCK_RELEASE_CHANGELOG_PLATFORM_DIR}/zh-CN.md',
    '${SLOCK_RELEASE_CHANGELOG_PLATFORM_DIR}/en.md',
    '${SLOCK_RELEASE_CHANGELOG_PLATFORM_DIR}/ja.md',
)
platform_guard = next(
    line for line in workflow.splitlines()
    if line.lstrip().startswith("if [ -s") and "SLOCK_RELEASE_CHANGELOG_PLATFORM_DIR" in line
)
for required_file in required_files:
    if required_file not in platform_guard:
        raise SystemExit(f"iOS workflow does not require {required_file}")

if workflow.count('export SLOCK_RELEASE_CHANGELOG_JA_FILE=') != 2:
    raise SystemExit("iOS workflow must export Japanese notes for platform and legacy layouts")
if workflow.count("changelog is incomplete; zh-CN.md, en.md, and ja.md are all required.") != 2:
    raise SystemExit("iOS workflow must fail closed on incomplete platform and legacy notes")
if "unset SLOCK_RELEASE_CHANGELOG_ZH_FILE SLOCK_RELEASE_CHANGELOG_EN_FILE SLOCK_RELEASE_CHANGELOG_JA_FILE" not in workflow:
    raise SystemExit("iOS workflow fallback must clear all reviewed-note variables")

print("iOS Hands release notes locale guard: PASS")
PY

# Audit tag helper must accept both canonical release/X.Y.Z and platform-ahead
# release/X.Y.Z-ios branches (SOP §3.1) while rejecting arbitrary branch names.
python3 - <<'PY'
import re
from pathlib import Path

script = Path("scripts/create_ios_release_audit_tag.sh").read_text()
match = re.search(r'\[\[ "\$\{RELEASE_BRANCH\}" =~ ([^ ]+) \]\]', script)
if not match:
    raise SystemExit("Could not locate RELEASE_BRANCH regex in scripts/create_ios_release_audit_tag.sh")
pattern = re.compile(match.group(1))
for good in ("release/1.29.0", "release/1.29.0-ios", "release-1.29.0"):
    if not pattern.match(good):
        raise SystemExit(f"Audit tag branch regex must accept {good!r}: {match.group(1)}")
for bad in ("main", "release/1.29.0-android", "release/1.29.0-ios-extra", "agent/lagoon/test"):
    if pattern.match(bad):
        raise SystemExit(f"Audit tag branch regex must reject {bad!r}: {match.group(1)}")
print("iOS audit tag branch regex guard: PASS")
PY

# Simulator and QA carrier builds must pass RAFT_GIT_COMMIT explicitly to xcodebuild,
# Info.plist must expand $(RAFT_GIT_COMMIT), and Stamp Hands Build Metadata must run
# AFTER Embed App Extensions with $(TARGET_BUILD_DIR)/$(INFOPLIST_PATH) in inputPaths
# (Xcode 27 runs ProcessInfoPlistFile after Embed App Extensions due to privacy-manifest
# scanning of RaftShareExtension.appex; an earlier phase skips stamping with a warning).
python3 - <<'PY'
import re
from pathlib import Path

sim_workflow = Path(".github/workflows/ios-simulator-app.yml").read_text()
if 'RAFT_GIT_COMMIT="$RAFT_GIT_COMMIT"' not in sim_workflow:
    raise SystemExit(
        'ios-simulator-app.yml must pass RAFT_GIT_COMMIT="$RAFT_GIT_COMMIT" to xcodebuild'
    )

qa_workflow = Path(".github/workflows/qa-acceptance-ios.yml").read_text()
if 'RAFT_GIT_COMMIT="$GITHUB_SHA"' not in qa_workflow:
    raise SystemExit(
        'qa-acceptance-ios.yml must pass RAFT_GIT_COMMIT="$GITHUB_SHA" to xcodebuild'
    )

info_plist = Path("compose/iosApp/iosApp/Info.plist").read_text()
if "<key>HandsBuildCommit</key>\n        <string>$(RAFT_GIT_COMMIT)</string>" not in info_plist:
    raise SystemExit("compose/iosApp/iosApp/Info.plist must bind HandsBuildCommit to $(RAFT_GIT_COMMIT)")

pbxproj = Path("compose/iosApp/iosApp.xcodeproj/project.pbxproj").read_text()
target_match = re.search(
    r'7555FF7A242A565900829871 /\* iosApp \*/ = \{.*?buildPhases = \((.*?)\);',
    pbxproj,
    re.S,
)
if not target_match:
    raise SystemExit("Could not locate iosApp target buildPhases in project.pbxproj")
phases = target_match.group(1)
embed_idx = phases.find("E36000050000000000000001 /* Embed App Extensions */")
stamp_idx = phases.find("D4C7A0122E15000100C0DE01 /* Stamp Hands Build Metadata */")
if embed_idx < 0 or stamp_idx < 0 or stamp_idx < embed_idx:
    raise SystemExit(
        "Stamp Hands Build Metadata must be ordered AFTER Embed App Extensions in iosApp buildPhases"
    )

phase_match = re.search(
    r'D4C7A0122E15000100C0DE01 /\* Stamp Hands Build Metadata \*/ = \{(.*?)\};',
    pbxproj,
    re.S,
)
if not phase_match:
    raise SystemExit("Could not locate Stamp Hands Build Metadata phase definition in project.pbxproj")
phase_body = phase_match.group(1)
if '"$(TARGET_BUILD_DIR)/$(INFOPLIST_PATH)"' not in phase_body:
    raise SystemExit(
        'Stamp Hands Build Metadata must declare "$(TARGET_BUILD_DIR)/$(INFOPLIST_PATH)" in inputPaths'
    )
if "alwaysOutOfDate = 1;" not in phase_body:
    raise SystemExit("Stamp Hands Build Metadata must set alwaysOutOfDate = 1")

print("iOS Simulator HandsBuildCommit xcodebuild & phase-order guard: PASS")
PY
