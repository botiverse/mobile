#!/usr/bin/env python3
"""Static contract for the Mobile Kuikly immutable-Raft cutover.

Since the Compose Multiplatform switch only the iOS host consumes Kuikly (the
OpenKuiklyIOSRender pod). This contract keeps that last consumer on the
immutable Raft release and keeps Kuikly out of the Gradle build and the
Android lanes.
"""

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RELEASE = "2.28.0-raft.15"
NORMAL = f"{RELEASE}-2.1.21"
OHOS = f"{RELEASE}-2.0.21-ohos"
KUIKLY_UI_COORDINATE = re.compile(r'["(]com\.tencent\.kuikly-open[:"]')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(f"Kuikly Raft consumer contract failed: {message}")


closure = json.loads(read("compose/gradle/kuikly-release-closure.json"))
require(len(closure) == 37 and len(set(closure)) == 37, "closure must contain 37 unique GAVs")
require(closure == sorted(closure), "closure must remain sorted")
require(all(gav.startswith("com.tencent.kuikly-open:") for gav in closure), "closure group drifted")
require(sum(gav.endswith(f":{NORMAL}") for gav in closure) == 28, "normal GAV count drifted")
# The published 2.28.0-raft.15 release set still carries the 9 OHOS GAVs; main
# no longer consumes them (OHOS froze at 1.14.x), but the manifest gate checks
# the closure against the immutable published manifest, so they stay listed
# until the next Kuikly release set drops the OHOS plane.
require(sum(gav.endswith(f":{OHOS}") for gav in closure) == 9, "OHOS GAV count drifted")
require(
    all(":core-gradle-plugin:" not in gav for gav in closure),
    "the independently versioned build plugin must not enter the runtime release closure",
)

# CMP switch: the Gradle build (Android host + :shared) no longer consumes Kuikly UI.
# Keep it that way; only the iOS host's OpenKuiklyIOSRender pod remains (below).
require(
    not (ROOT / "compose/buildSrc/src/main/java/KotlinBuildVar.kt").exists(),
    "retired buildSrc Kuikly version pin returned",
)
version_catalog = read("compose/gradle/libs.versions.toml")
require("com.tencent.kuikly-open" not in version_catalog, "version catalog re-added a Kuikly UI artifact")
require("kuiklyAndroid" not in version_catalog, "version catalog re-added the Kuikly Android pin")
require("kuiklyOhos" not in version_catalog, "OHOS catalog pin must stay removed")
for gradle_path in sorted((ROOT / "compose").glob("**/*.gradle.kts")):
    if "build" in gradle_path.relative_to(ROOT / "compose").parts[:-1]:
        continue
    rel = gradle_path.relative_to(ROOT).as_posix()
    require(
        not KUIKLY_UI_COORDINATE.search(read(rel)),
        f"{rel} still declares or admits Kuikly UI artifacts",
    )

podfile = read("compose/iosApp/Podfile")
require("fetch_kuikly_release_artifact.py" in podfile, "iOS podspec is not manifest-derived")
require(":podspec => kuikly_podspec" in podfile, "iOS renderer is not the immutable binary pod")
require("../../third_party/kuikly-ui" not in podfile, "iOS local Kuikly pod fallback remains")
podfile_lock = read("compose/iosApp/Podfile.lock")
require(
    f"OpenKuiklyIOSRender ({NORMAL})" in podfile_lock,
    "iOS lockfile does not bind the immutable renderer version",
)
require("third_party/kuikly-ui" not in podfile_lock, "iOS lockfile retains the Kuikly gitlink")
require(not (ROOT / "third_party/kuikly-ui").exists(), "obsolete Kuikly consumer gitlink remains")
require('submodule "third_party/kuikly-ui"' not in read(".gitmodules"), "Kuikly gitlink metadata remains")

for obsolete_script in (
    "scripts/build_kuikly_fork.sh",
    "scripts/publish_kuikly_staging2_all.sh",
    "scripts/update_kuikly_render_har.sh",
):
    require(not (ROOT / obsolete_script).exists(), f"obsolete source publisher remains: {obsolete_script}")

for release_script in (
    "scripts/release_android_preflight.sh",
    "scripts/release_android_hands_github.sh",
):
    release_source = read(release_script)
    require("third_party/kuikly-ui" not in release_source, f"{release_script} retains the gitlink gate")
    require(
        "verify_kuikly_release_manifest.sh" not in release_source,
        f"{release_script} still admits the retired Android Kuikly release",
    )

# Workflows that build the iOS host, which still pods OpenKuiklyIOSRender.
# TODO(cmp-switch): delete this contract, the manifest gate, the fetcher and the
# closure once the iOS host migration drops the pod.
ios_workflows = (
    ".github/workflows/ios-release.yml",
    ".github/workflows/ios-simulator-app.yml",
    ".github/workflows/qa-acceptance-ios.yml",
    ".github/workflows/test.yml",
    ".github/workflows/visual-testing.yml",
)
android_only_workflows = (
    ".github/workflows/android-alpha.yml",
    ".github/workflows/android-release.yml",
    ".github/workflows/android-testbox.yml",
    ".github/workflows/qa-acceptance.yml",
    ".github/workflows/visual-android-testbox.yml",
)
for workflow_path in ios_workflows + android_only_workflows:
    workflow = read(workflow_path)
    if workflow_path in ios_workflows:
        require(
            "verify_kuikly_release_manifest.sh" in workflow,
            f"{workflow_path} misses the immutable manifest admission gate",
        )
    else:
        require(
            "verify_kuikly_release_manifest.sh" not in workflow,
            f"{workflow_path} still admits the retired Android Kuikly release",
        )
    for forbidden in ("third_party/kuikly-ui", "build_kuikly_fork.sh", "kuikly-fork-mavenlocal"):
        require(forbidden not in workflow, f"{workflow_path} retains local Kuikly fallback: {forbidden}")

gate = read("scripts/ci/kuikly_release_manifest_gate.py")
require('manifest.get("releaseSet")' in gate, "manifest gate still expects the obsolete version field")
require('manifest.get("setSha256")' in gate, "manifest gate still expects the obsolete set digest field")
require('pub.get("size")' in gate, "manifest gate still expects the obsolete byte-count field")
require(
    'headers={"User-Agent": USER_AGENT}' in gate,
    "manifest gate uses Cloudflare-blocked urllib default user agent",
)
require("--admission-only" in gate, "manifest gate lacks the normal version-admission mode")
require(
    "KUIKLY_FULL_READBACK" in read("scripts/ci/verify_kuikly_release_manifest.sh"),
    "ordinary CI cannot distinguish version admission from exceptional full readback",
)
fetcher = read("scripts/ci/fetch_kuikly_release_artifact.py")
require('publication["size"]' in fetcher, "artifact fetcher does not use the production manifest schema")
print("Kuikly immutable-Raft consumer source contract: PASS")
