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
