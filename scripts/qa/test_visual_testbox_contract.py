#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/visual-android-testbox.yml"
BEGIN_PIN = "233448af4bfdc6fca509a7f0974411ac6d8a8043"
RUN_PIN = "5ca05834db1d3813554d1dd109e5f2087a8d7cbc"


def main() -> int:
    # The interactive Testbox hands a shell to a human: it must stay manual-only, read-only,
    # pinned, and must drop checkout credentials and the Gradle daemon before the shell opens.
    workflow = WORKFLOW.read_text(encoding="utf-8")
    assert re.search(r"(?m)^on:\n  workflow_dispatch:\n", workflow)
    for forbidden in ("pull_request:", "push:", "schedule:", "release:", "upload-artifact", "pages:", "id-token: write"):
        assert forbidden not in workflow, forbidden
    assert "contents: read" in workflow and "packages: read" in workflow
    assert f"useblacksmith/begin-testbox@{BEGIN_PIN}" in workflow
    assert f"useblacksmith/run-testbox@{RUN_PIN}" in workflow
    hydrate_index = workflow.index("Hydrate visual APK build")
    cleanup_index = workflow.index("Remove checkout credentials before interactive Testbox")
    interactive_index = workflow.index("useblacksmith/run-testbox@")
    assert hydrate_index < cleanup_index < interactive_index
    cleanup = workflow[cleanup_index:interactive_index]
    assert "--unset-all http.https://github.com/.extraheader" in cleanup
    assert "./gradlew --stop" in cleanup
    print("visual Testbox workflow contract test: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
