#!/usr/bin/env python3
"""Static contract: Kuikly UI stays out of Mobile after the CMP switch.

Mobile used to consume an immutable Raft release of Kuikly UI
(com.tencent.kuikly-open) on Android/KMP and the OpenKuiklyIOSRender pod on
iOS, admitted by a manifest gate. The Compose Multiplatform switch removed
every consumer, and the release closure, manifest gate and artifact fetcher
were deleted with them. This contract scans every Gradle build file and
workflow and fails if one consumes Kuikly UI or wires its retired plumbing
again.

KuiklyBase libraries that are not Kuikly UI are allowed and stay:
NetworkKMM (com.tencent.kuiklybase:network*), resource-core/resource-generator
and the vendored third_party/kuikly-compose-markdown sources.
"""

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
KUIKLY_UI_COORDINATE = re.compile(r'["(]com\.tencent\.kuikly-open[:".]')
KUIKLY_UI_PLUGIN = re.compile(r'id\("com\.tencent\.kuikly(?:-open)?[."]')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(f"no-Kuikly-UI consumer contract failed: {message}")


for gradle_path in sorted((ROOT / "compose").rglob("*.gradle.kts")):
    rel_parts = gradle_path.relative_to(ROOT / "compose").parts
    if "build" in rel_parts[:-1] or ".gradle" in rel_parts[:-1]:
        continue
    rel = gradle_path.relative_to(ROOT).as_posix()
    text = read(rel)
    require(not KUIKLY_UI_COORDINATE.search(text), f"{rel} declares or admits Kuikly UI artifacts")
    require(not KUIKLY_UI_PLUGIN.search(text), f"{rel} applies a Kuikly UI Gradle plugin")

consumers = sorted((ROOT / ".github/workflows").glob("*.yml")) + [
    ROOT / "scripts/release_android_preflight.sh",
    ROOT / "scripts/release_android_hands_github.sh",
]
for path in consumers:
    rel = path.relative_to(ROOT).as_posix()
    text = path.read_text(encoding="utf-8")
    for forbidden in (
        "verify_kuikly_release_manifest.sh",
        "kuikly_release_manifest_gate.py",
        "fetch_kuikly_release_artifact.py",
        "third_party/kuikly-ui",
        "build_kuikly_fork.sh",
        "kuikly-fork-mavenlocal",
    ):
        require(forbidden not in text, f"{rel} still wires retired Kuikly UI plumbing: {forbidden}")

print("no-Kuikly-UI consumer contract: PASS")
