#!/usr/bin/env python3
"""Static contract for the Mobile Kuikly immutable-Raft cutover."""

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RELEASE = "2.28.0-raft.16-diag2"
NORMAL = f"{RELEASE}-2.1.21"
OHOS = f"{RELEASE}-2.0.21-ohos"


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
require(sum(gav.endswith(f":{OHOS}") for gav in closure) == 9, "OHOS GAV count drifted")
require(
    all(":core-gradle-plugin:" not in gav for gav in closure),
    "the independently versioned build plugin must not enter the runtime release closure",
)

version_source = read("compose/buildSrc/src/main/java/KotlinBuildVar.kt")
require(f'KUIKLY_VERSION = "{RELEASE}"' in version_source, "buildSrc release pin drifted")
require(
    "core-gradle-plugin" not in version_source,
    "non-JS Mobile build must not retain the independently versioned Kuikly build plugin",
)
version_catalog = read("compose/gradle/libs.versions.toml")
require(f'kuiklyAndroid = "{NORMAL}"' in version_catalog, "Android catalog pin drifted")
require(f'kuiklyOhos = "{OHOS}"' in version_catalog, "OHOS catalog pin drifted")

for settings_path in ("compose/settings.gradle.kts", "compose/settings.ohos.gradle.kts"):
    settings = read(settings_path)
    require("RaftArtifactsKuiklyRelease" in settings, f"{settings_path} misses the Raft release repo")
    require('includeGroup("com.tencent.kuikly-open")' in settings, f"{settings_path} misses Kuikly exclusivity")
    require("exclusiveContent" in settings, f"{settings_path} must make the release repo exclusive")

require(
    'id("com.tencent.kuikly-open.kuikly")' not in read("compose/shared/build.gradle.kts"),
    "non-JS Mobile shared module must not apply the Kuikly JS build plugin",
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

ohos_package = read("compose/ohosApp/entry/oh-package.json5")
har_spec = f"file:../../build/kuikly/core-render-ohos-{OHOS}.har"
require(har_spec in ohos_package, "OHOS renderer is not pinned to the manifest-generated HAR")
require("kuikly-render-staging2" not in ohos_package, "OHOS local staging HAR fallback remains")
ohos_lock = read("compose/ohosApp/entry/oh-package-lock.json5")
har_lock_path = f"../../build/kuikly/core-render-ohos-{OHOS}.har"
require(har_lock_path in ohos_lock, "OHOS lockfile does not bind the manifest-generated HAR")
require("kuikly-render-staging2" not in ohos_lock, "OHOS lockfile retains the staging2 HAR")
require(
    not list((ROOT / "compose/ohosApp/entry/libs").glob("kuikly-render-staging2-*")),
    "checked-in staging2 renderer artifacts remain",
)

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
    "verify_kuikly_release_manifest.sh" in read("scripts/release_android_preflight.sh"),
    "Android release preflight does not verify the immutable Kuikly release",
)

workflows = (
    ".github/workflows/android-alpha.yml",
    ".github/workflows/android-release.yml",
    ".github/workflows/android-testbox.yml",
    ".github/workflows/ios-release.yml",
    ".github/workflows/ohos-release.yml",
    ".github/workflows/qa-acceptance-ios.yml",
    ".github/workflows/qa-acceptance.yml",
    ".github/workflows/test.yml",
    ".github/workflows/visual-android-testbox.yml",
    ".github/workflows/visual-testing.yml",
)
for workflow_path in workflows:
    workflow = read(workflow_path)
    require(
        "verify_kuikly_release_manifest.sh" in workflow,
        f"{workflow_path} misses the immutable manifest admission gate",
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
for workflow_path in (".github/workflows/test.yml", ".github/workflows/ohos-release.yml"):
    require(
        "verify_kuikly_ohos_renderer.sh" in read(workflow_path),
        f"{workflow_path} does not validate the manifest-bound renderer binary",
    )
renderer_verifier = read("scripts/ci/verify_kuikly_ohos_renderer.sh")
require('command -v node' in renderer_verifier, "OHOS renderer verifier misses ambient Node")
require(
    'DEVECO_SDK_HOME}/../tool/node/bin/node' in renderer_verifier,
    "OHOS renderer verifier misses the DevEco command-line-tools Node fallback",
)

print("Kuikly immutable-Raft consumer source contract: PASS")
