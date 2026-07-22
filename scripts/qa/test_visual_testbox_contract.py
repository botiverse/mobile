#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/visual-android-testbox.yml"
SKILL = ROOT / ".agents/skills/blacksmith-visual-testbox/SKILL.md"
HARNESS = ROOT / "compose/androidApp/src/androidTest/java/build/raft/app/visual/KmpVisualScreenshotCaptureTest.kt"
SETTINGS = ROOT / "compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt"
RUNNER = ROOT / "scripts/run_android_visual_capture.sh"
BEGIN_PIN = "233448af4bfdc6fca509a7f0974411ac6d8a8043"
RUN_PIN = "5ca05834db1d3813554d1dd109e5f2087a8d7cbc"


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    skill = SKILL.read_text(encoding="utf-8")
    harness = HARNESS.read_text(encoding="utf-8")
    settings = SETTINGS.read_text(encoding="utf-8")
    runner = RUNNER.read_text(encoding="utf-8")
    assert re.search(r"(?m)^on:\n  workflow_dispatch:\n", workflow)
    for forbidden in ("pull_request:", "push:", "schedule:", "release:", "upload-artifact", "pages:", "id-token: write"):
        assert forbidden not in workflow, forbidden
    assert "contents: read" in workflow and "packages: read" in workflow
    assert f"useblacksmith/begin-testbox@{BEGIN_PIN}" in workflow
    assert f"useblacksmith/run-testbox@{RUN_PIN}" in workflow
    write_index = workflow.index("Write GitHub Packages credentials")
    hydrate_index = workflow.index("Hydrate visual APK build")
    cleanup_index = workflow.index("Remove credentials before interactive Testbox")
    interactive_index = workflow.index("useblacksmith/run-testbox@")
    assert write_index < hydrate_index < cleanup_index < interactive_index
    cleanup = workflow[cleanup_index:interactive_index]
    assert "rm -f compose/github-packages.properties" in cleanup
    assert "--unset-all http.https://github.com/.extraheader" in cleanup
    assert "./gradlew --stop" in cleanup
    assert "Gradle daemon survived credential cleanup" in cleanup
    assert "test ! -e compose/github-packages.properties" in cleanup
    assert "GitHub checkout credential survived cleanup" in cleanup
    assert "Do not recreate that credential inside Testbox" in skill
    assert "product PASS/FAIL" in skill
    assert "whole-system logcat" in skill
    appearance = harness.split("fun captureSettingsAppearancePage()", 1)[1].split("\n    @Test", 1)[0]
    assert "compose_settings_route_visual" in appearance
    assert 'fixtureRevision", "settings-appearance-v1"' in appearance
    assert "renderedGoalSha256" in appearance
    assert "settings_route_visual_ready destination=appearance fixtureKey=canonical" in appearance
    assert "captureKmpSettingsPageCase" not in appearance
    assert "prior.sameAs(normalized)" in harness
    assert "clamped capture rejected" in harness
    forward_probe = harness.split("fun captureThreadForwardModalScrollProbe()", 1)[1].split("\n    private fun", 1)[0]
    assert "FORWARD_SCROLL_PROBE phase=swipe" in forward_probe
    assert "FORWARD_SCROLL_PROBE phase=tap" in forward_probe
    assert "writeSuccessfulVisualArtifacts(" in forward_probe
    assert "settings_route_visual_ready destination=${route.destination}" in settings
    assert '--all --shard-index "$SHARD_INDEX" --shard-total "$SHARD_TOTAL"' in runner
    assert "missing_artifact_cases" in runner
    assert "finish_early_failure" in runner
    print("visual Testbox workflow contract test: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
