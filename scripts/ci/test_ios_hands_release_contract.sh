#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

workflow = Path(".github/workflows/ios-release.yml").read_text()
publisher = Path("scripts/publish_ios_hands_release.sh").read_text()
paired_sop = Path("docs/mobile-paired-release-sop.md").read_text()
ios_practices = Path("docs/ios-hands-release-practices-2026-07-09.md").read_text()

required_version_fragments = (
    "release_notes_version:",
    'required: true',
    'RELEASE_NOTES_VERSION="${{ inputs.release_notes_version }}"',
    'release_notes_version must be an X.Y.Z version',
    'release_notes_version=${RELEASE_NOTES_VERSION}',
    'change-logs/${{ needs.resolve.outputs.release_notes_version }}/ios',
)
for fragment in required_version_fragments:
    if fragment not in workflow:
        raise SystemExit(f"iOS workflow release-notes version contract missing: {fragment}")
if "change-logs/${{ needs.resolve.outputs.version_name }}/ios" in workflow:
    raise SystemExit("iOS workflow must not derive Mobile release notes from iOS marketing version")
if "-f release_notes_version=X.Y.Z" not in paired_sop:
    raise SystemExit("paired release SOP must pass the Mobile release-notes version")

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

required_publisher_fragments = (
    "SLOCK_RELEASE_CHANGELOG_JA_FILE=change-logs/1.6.0/ios/ja.md",
    '[ -n "${SLOCK_RELEASE_CHANGELOG_JA_FILE:-}" ] && [ -s "${SLOCK_RELEASE_CHANGELOG_JA_FILE}" ]',
    '--changelog-file "ja=${SLOCK_RELEASE_CHANGELOG_JA_FILE}"',
    'reviewed changelog variables require non-empty zh-CN, en, and ja files',
)
for fragment in required_publisher_fragments:
    if fragment not in publisher:
        raise SystemExit(f"iOS publisher contract missing: {fragment}")

required_notification_receipt_fragments = (
    "`external_build_state=IN_BETA_TESTING`",
    "`not_requested`",
    "`scheduled`",
    "`sent`",
    "`already_sent`",
)
for path, text in (
    ("docs/mobile-paired-release-sop.md", paired_sop),
    ("docs/ios-hands-release-practices-2026-07-09.md", ios_practices),
):
    for fragment in required_notification_receipt_fragments:
        if fragment not in text:
            raise SystemExit(f"{path} TestFlight notification receipt contract missing: {fragment}")
if "`notification=sent|already_sent` operation receipt" not in paired_sop:
    raise SystemExit("paired release SOP must preserve the TestFlight notification operation receipt")
if "`notification=sent` (or `already_sent`" not in ios_practices:
    raise SystemExit("iOS practices must accept sent and already_sent notification receipts")
if "without `--wait`" not in paired_sop or "Do not combine this receipt-producing call with `--wait`" not in ios_practices:
    raise SystemExit("TestFlight notification receipt contract must preserve POST response without --wait")
for path, text in (
    ("docs/mobile-paired-release-sop.md", paired_sop),
    ("docs/ios-hands-release-practices-2026-07-09.md", ios_practices),
):
    if "first" not in text or "`scheduled`" not in text or "replay" not in text or "`already_sent`" not in text:
        raise SystemExit(f"{path} must preserve the scheduled-to-already_sent replay contract")

example = ios_practices.split(
    "Example for an already-approved external build:", 1
)[1].split("Do not infer notification", 1)[0]
publish_command = example.split("hands builds testflight-status", 1)[0]
if "--wait" in publish_command:
    raise SystemExit(
        "TestFlight publish example must preserve the POST notification receipt; do not use --wait"
    )
if "hands builds testflight-status" not in example:
    raise SystemExit("TestFlight publish example must use a separate status readback")

print("iOS Hands release notes contract: PASS")
PY
