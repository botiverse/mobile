#!/usr/bin/env python3
"""Prepare the GitHub Release for a shipped mobile version from what Hands shipped.

For tag ``vX.Y.Z`` this resolves, from Hands:

- Android: the published (``active`` or ``superseded``) ``main`` release whose build is
  version ``X.Y.Z``; when there are several builds of that version, the highest
  versionCode. Its signed APK is downloaded and its SHA-256 checked against Hands, so the
  file attached to the GitHub Release is byte-for-byte the one users installed.
- Android AAB (with ``--aab-repo``): the signed AAB from the same android-release.yml run
  that built that APK, kept as a backup of the Hands-channel build (not the Google Play
  build). The run id comes from the Hands build provenance (``ci_run_id``); the run's
  ``*-aab`` artifact must carry a build identity whose APK SHA-256, versionCode,
  versionName and source commit equal the Hands build, an AAB SHA-256 equal to its
  signing receipt and the downloaded file, a strict JAR signature by the pinned upload
  key, and a manifest with the same package and version. A missing, expired or
  mismatching AAB is skipped with a warning; the APK still ships.
- iOS: the highest-versionCode build of ``X.Y.Z``. No IPA is attached; the body names the
  TestFlight build number.
- iOS Simulator app (with ``--ios-simulator-app`` and ``--commit``): the downloaded
  ``ios-simulator-app-<run>`` artifact of ios-simulator-app.yml (``raft-ios-simulator.app.zip``,
  a ditto zip of the ``.app``, plus ``metadata.json``). Attached as is, renamed, only when
  metadata.json names the tag commit as source and Hands build commit, the default Xcode lane
  and the zip's SHA-256; otherwise skipped with a warning.

The body uses the release notes stored on the Hands releases (what users saw), in
zh-CN / en / ja, one section per platform. A macOS section (unsigned dmg, first-launch
steps in the three languages) is added when ``--dmg`` is given.

Output goes to ``--out``: ``body.md``, the assets, and ``manifest.json`` (asset paths and
warnings for the caller). Creating or updating the Release itself is left to the caller
(``gh release`` in .github/workflows/github-release.yml), so this script needs only read
access to Hands (and ``actions: read`` through ``gh`` for the AAB).

Environment:
  HANDS_API                       default https://hands.build
  HANDS_ANDROID_BEARER_TOKEN      read token for the Android app
  HANDS_IOS_BEARER_TOKEN          read token for the iOS app
  HANDS_ANDROID_APP_ID            optional; resolved from the app list when unset
  HANDS_IOS_APP_ID                optional; resolved from the app list when unset
  GH_TOKEN                        for ``gh`` when ``--aab-repo`` is given
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ANDROID_SLUG = "raft-android"
IOS_SLUG = "raft-ios"
PUBLISHED = {"active", "superseded"}
USER_AGENT = "botiverse-mobile-github-release/1"
LANGS = [("zh-CN", "中文"), ("en", "English"), ("ja", "日本語")]
ANDROID_PACKAGE = "build.raft.app"
ANDROID_RELEASE_WORKFLOW = ".github/workflows/android-release.yml"
# Upload key pin, same as android-release.yml (sign-aab / sign_existing_aab).
AAB_UPLOAD_CERT_SHA256 = "5f08bbe82a27ccdad26c13523a60b5670d3aa7633e8ed2fd71fa6b5751f9b0b1"
WARNINGS: list[str] = []

MACOS_FIRST_LAUNCH = {
    "zh-CN": (
        "未签名，未经 Apple 公证。首次打开：\n\n"
        "1. 打开 dmg，把 Raft 拖到「应用程序」。\n"
        "2. 打开 Raft 会被系统拦截：到 系统设置 → 隐私与安全性，点「仍要打开」。"
    ),
    "en": (
        "Unsigned and not notarized. First launch:\n\n"
        "1. Open the dmg and drag Raft to Applications.\n"
        "2. macOS blocks the first open: go to System Settings → Privacy & Security "
        "and click \"Open Anyway\"."
    ),
    "ja": (
        "署名・公証なし。初回起動：\n\n"
        "1. dmg を開き、Raft を「アプリケーション」にドラッグします。\n"
        "2. 初回はブロックされます：システム設定 → プライバシーとセキュリティ で"
        "「このまま開く」をクリックします。"
    ),
}


IOS_SIMULATOR_INSTALL = {
    "zh-CN": (
        "iOS 模拟器包（Xcode {xcode}，Debug，{archs}）：只能装在 Mac 的 iOS 模拟器里，不能装真机。"
        "解压后 `xcrun simctl install booted {app}`。"
    ),
    "en": (
        "iOS Simulator app (Xcode {xcode}, Debug, {archs}): for the iOS Simulator on a Mac only, "
        "not for devices. Unzip, then `xcrun simctl install booted {app}`."
    ),
    "ja": (
        "iOS シミュレータ用アプリ（Xcode {xcode}、Debug、{archs}）：Mac の iOS シミュレータ専用で、"
        "実機にはインストールできません。解凍後 `xcrun simctl install booted {app}`。"
    ),
}


def die(message: str) -> None:
    print(f"github-release: {message}", file=sys.stderr)
    sys.exit(1)


def warn(message: str) -> None:
    print(f"::warning::github-release: {message}")
    WARNINGS.append(message)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def json_field(value) -> dict:
    """Hands returns *_json columns either decoded or as a JSON string."""
    if isinstance(value, dict):
        return value
    try:
        decoded = json.loads(value) if value else {}
    except ValueError:
        return {}
    return decoded if isinstance(decoded, dict) else {}


class Hands:
    def __init__(self, api: str, token: str) -> None:
        if not token:
            die("missing Hands bearer token")
        self.api = api.rstrip("/")
        self.token = token

    def get(self, path: str) -> dict:
        request = urllib.request.Request(
            f"{self.api}{path}",
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/json",
                # The default Python-urllib agent is refused at the edge (403).
                "User-Agent": USER_AGENT,
            },
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)

    def app_id(self, slug: str, explicit: str) -> str:
        if explicit:
            return explicit
        for app in self.get("/api/apps").get("apps", []):
            if app.get("slug") == slug:
                return app["id"]
        die(f"app {slug} not visible to this token")
        return ""

    def builds_of(self, app_id: str, version: str) -> list[dict]:
        builds = self.get(f"/api/apps/{app_id}/builds?limit=200").get("builds", [])
        return sorted(
            (b for b in builds if b.get("version_name") == version and b.get("status") == "succeeded"),
            key=lambda b: int(b.get("version_code") or 0),
            reverse=True,
        )

    def releases(self, app_id: str) -> list[dict]:
        return self.get(f"/api/apps/{app_id}/releases?limit=200").get("releases", [])


def notes_block(notes: dict | None) -> str:
    notes = {k.lower(): v for k, v in (notes or {}).items()}
    parts = []
    for lang, label in LANGS:
        text = notes.get(lang.lower()) or notes.get("zh-hans" if lang == "zh-CN" else lang)
        if text:
            parts.append(f"#### {label}\n\n{text.strip()}\n")
    return "\n".join(parts) if parts else "_No release notes on Hands._\n"


def release_notes(detail: dict) -> dict | None:
    release = detail.get("release", detail)
    notes = release.get("release_notes")
    if notes:
        return notes
    raw = release.get("changelog")
    try:
        return json.loads(raw) if raw else None
    except ValueError:
        return None


def _proto_fields(buf: bytes):
    """Yield (field number, value) from one protobuf message; length-delimited values as bytes."""
    pos = 0

    def varint() -> int:
        nonlocal pos
        result = shift = 0
        while True:
            byte = buf[pos]
            pos += 1
            result |= (byte & 0x7F) << shift
            if byte < 0x80:
                return result
            shift += 7

    while pos < len(buf):
        key = varint()
        number, wire = key >> 3, key & 7
        if wire == 0:
            yield number, varint()
        elif wire == 1:
            yield number, buf[pos:pos + 8]
            pos += 8
        elif wire == 2:
            size = varint()
            yield number, buf[pos:pos + size]
            pos += size
        elif wire == 5:
            yield number, buf[pos:pos + 4]
            pos += 4
        else:
            raise ValueError(f"unsupported protobuf wire type {wire}")


def aab_manifest(aab: Path) -> dict:
    """package / versionCode / versionName from base/manifest/AndroidManifest.xml.

    An AAB stores the manifest as an aapt2 proto XmlNode (Resources.proto): XmlNode.element=1;
    XmlElement.name=3, attribute=4; XmlAttribute.name=2, value=3 (source text),
    compiled_item=6 -> Item.str=2 (String.value=1) / Item.prim=7 (int_decimal_value=6).
    """
    with zipfile.ZipFile(aab) as archive:
        node = archive.read("base/manifest/AndroidManifest.xml")
    element = next((v for n, v in _proto_fields(node) if n == 1), b"")
    found: dict = {}
    name = ""
    for number, value in _proto_fields(element):
        if number == 3:
            name = value.decode()
        if number != 4:
            continue
        attr = dict(_proto_fields(value))
        attr_name = attr.get(2, b"").decode()
        text = attr.get(3, b"").decode()
        if not text and 6 in attr:
            item = dict(_proto_fields(attr[6]))
            if 2 in item:
                text = dict(_proto_fields(item[2])).get(1, b"").decode()
            elif 7 in item:
                prim = dict(_proto_fields(item[7]))
                if 6 in prim:
                    text = str(prim[6])
        if attr_name in ("package", "versionCode", "versionName"):
            found[attr_name] = text
    if name != "manifest":
        raise ValueError("AAB base manifest root is not <manifest>")
    return found


def gh_api(path: str) -> dict:
    result = subprocess.run(["gh", "api", path], check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def android_aab(version: str, out: Path, repo: str, build: dict, apk_sha256: str) -> tuple[str, Path] | None:
    """Download and verify the signed AAB of the run that built the Hands APK (see module doc)."""
    code = str(build["version_code"])
    metadata = json_field(build.get("build_metadata_json"))
    provenance = json_field(build.get("provenance_json"))
    run_id = str(provenance.get("ci_run_id") or metadata.get("ci_run_id") or "")
    source = provenance.get("source_commit") or metadata.get("source_commit") or ""
    if not re.fullmatch(r"[1-9][0-9]*", run_id):
        warn(f"Hands build {build['id']} records no CI run id; no AAB attached "
             "(builds published before android-release.yml recorded it)")
        return None
    try:
        run = gh_api(f"repos/{repo}/actions/runs/{run_id}")
        if run.get("path") != ANDROID_RELEASE_WORKFLOW or run.get("repository", {}).get("full_name") != repo:
            warn(f"run {run_id} is not {repo} {ANDROID_RELEASE_WORKFLOW}; no AAB attached")
            return None
        artifacts = [
            a for a in gh_api(f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100").get("artifacts", [])
            if a.get("name", "").endswith("-aab")
        ]
        live = [a for a in artifacts if not a.get("expired")]
        if len(live) != 1:
            state = "expired" if artifacts and not live else f"{len(live)} found"
            warn(f"run {run_id}: expected one signed AAB artifact ({state}); no AAB attached")
            return None
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(
                ["gh", "run", "download", run_id, "--repo", repo, "--name", live[0]["name"], "--dir", tmp],
                check=True, capture_output=True, text=True,
            )
            folder = Path(tmp)
            signed = folder / "androidApp-release.aab"
            receipt = json.loads((folder / "aab-signature.json").read_text())
            identity = json.loads((folder / "build-identity.json").read_text())
            aab_sha256 = sha256_of(signed)
            manifest = aab_manifest(signed)
            # Strict JAR signature check against the pinned upload certificate (needs a JDK).
            subprocess.run(
                [str(Path(__file__).resolve().parent / "verify_android_aab.sh"),
                 str(signed), AAB_UPLOAD_CERT_SHA256, str(folder / "verify.json")],
                check=True, capture_output=True, text=True,
            )
            checks = {
                "identity run id": (str(identity.get("ci_run_id")), run_id),
                "identity repository": (identity.get("repository"), repo),
                "identity APK SHA-256 (vs Hands)": (identity.get("apk_sha256"), apk_sha256),
                "identity versionCode": (str(identity.get("version_code")), code),
                "identity versionName": (identity.get("version_name"), version),
                "identity source commit": (identity.get("source_commit"), source),
                "identity AAB SHA-256": (identity.get("aab_sha256"), aab_sha256),
                "receipt AAB SHA-256": (receipt.get("sha256"), aab_sha256),
                "receipt upload key": (receipt.get("upload_key_cert_sha256"), AAB_UPLOAD_CERT_SHA256),
                "manifest package": (manifest.get("package"), ANDROID_PACKAGE),
                "manifest versionCode": (manifest.get("versionCode"), code),
                "manifest versionName": (manifest.get("versionName"), version),
            }
            bad = [f"{label} {got!r} != {want!r}" for label, (got, want) in checks.items() if got != want]
            if bad:
                warn(f"run {run_id} AAB does not match the Hands APK build, not attached: " + "; ".join(bad))
                return None
            aab = out / f"raft-android-{version}-{code}.aab"
            shutil.copyfile(signed, aab)
    except (subprocess.CalledProcessError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        detail = getattr(error, "stderr", "") or error
        warn(f"could not fetch the signed AAB of run {run_id}: {str(detail).strip()}; no AAB attached")
        return None
    line = (
        f"AAB SHA-256 `{aab_sha256}`: the Hands-channel build from the same CI run as the APK, "
        "kept as a backup (not the Google Play build).\n\n"
    )
    return line, aab


def android(version: str, out: Path, hands: Hands, app_id: str, aab_repo: str) -> tuple[str, list[Path]]:
    builds = {b["id"]: b for b in hands.builds_of(app_id, version)}
    if not builds:
        die(f"no Android build of {version} on Hands")
    candidates = [
        r for r in hands.releases(app_id)
        if r.get("build_id") in builds and r.get("status") in PUBLISHED
        and (r.get("channel") or "main") == "main"
    ]
    if not candidates:
        die(f"no published main release of Android {version}; publish it before tagging")
    release = max(candidates, key=lambda r: int(builds[r["build_id"]]["version_code"]))
    detail = hands.get(f"/api/apps/{app_id}/releases/{release['id']}")
    if (detail.get("release", {}).get("channel") or "main") != "main":
        die(f"release {release['id']} is not on main")
    build = builds[release["build_id"]]
    metadata = json_field(build.get("build_metadata_json"))
    # Same rule as check_android_hands_release_channel.sh: only an APK built for main
    # goes out as the main package (1.14.0 shipped the Play build by mistake).
    if metadata.get("channel") != "main":
        die(f"build {build['id']} records channel={metadata.get('channel')!r}, not main")
    apks = [a for a in detail.get("assets", []) if a.get("filetype") == "apk"]
    if len(apks) != 1:
        die(f"expected one APK on release {release['id']}, found {len(apks)}")
    asset = apks[0]
    signed = hands.get(f"/api/apps/{app_id}/builds/{build['id']}/assets/{asset['id']}/download?presign=1")
    url = signed.get("url") or signed.get("download_url")
    if not url:
        die("Hands did not return a download URL")
    code = build["version_code"]
    apk = out / f"raft-android-{version}-{code}.apk"
    digest = hashlib.sha256()
    download = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(download, timeout=600) as response, apk.open("wb") as handle:
        for chunk in iter(lambda: response.read(1 << 20), b""):
            digest.update(chunk)
            handle.write(chunk)
    expected = (asset.get("file_hash") or asset.get("sha256") or "").lower()
    if not expected or digest.hexdigest() != expected:
        die(f"APK SHA-256 {digest.hexdigest()} does not match Hands {expected or '(none)'}")
    assets = [apk]
    aab_line = ""
    if aab_repo:
        found = android_aab(version, out, aab_repo, build, expected)
        if found:
            aab_line, aab = found
            assets.append(aab)
    section = (
        f"## Android {version} ({code})\n\n"
        f"Hands `main`, release `{release['id']}`. Download: "
        f"<https://hands.build/apps/{ANDROID_SLUG}/v/{version}>\n\n"
        f"APK SHA-256 `{expected}`\n\n{aab_line}{notes_block(release_notes(detail))}"
    )
    return section, assets


def macos(version: str, out: Path, dmg: Path) -> tuple[str, Path]:
    if not dmg.is_file():
        die(f"dmg not found: {dmg}")
    asset = out / f"raft-macos-{version}.dmg"
    shutil.copyfile(dmg, asset)
    steps = notes_block(MACOS_FIRST_LAUNCH)
    section = (
        f"## macOS {version}\n\n"
        f"**Unsigned, not notarized.** dmg SHA-256 `{sha256_of(asset)}`\n\n{steps}"
    )
    return section, asset


def ios(version: str, hands: Hands, app_id: str) -> str:
    builds = hands.builds_of(app_id, version)
    if not builds:
        die(f"no iOS build of {version} on Hands")
    build = builds[0]
    release = next((r for r in hands.releases(app_id) if r.get("build_id") == build["id"]), None)
    notes = None
    if release:
        notes = release_notes(hands.get(f"/api/apps/{app_id}/releases/{release['id']}"))
    return (
        f"## iOS {version} ({build['version_code']})\n\n"
        f"TestFlight build `{build['version_code']}`. No IPA is attached.\n\n{notes_block(notes)}"
    )


def ios_simulator(version: str, out: Path, folder: Path, commit: str) -> tuple[str, Path] | None:
    """Verify and stage the ios-simulator-app.yml artifact (see module doc)."""
    try:
        metadata = json.loads((folder / "metadata.json").read_text(encoding="utf-8"))
        archive = folder / "raft-ios-simulator.app.zip"
        zip_sha256 = sha256_of(archive)
        with zipfile.ZipFile(archive) as bundle:
            tops = {name.split("/", 1)[0] for name in bundle.namelist()}
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        warn(f"unreadable iOS Simulator artifact ({error}); no Simulator app attached")
        return None
    apps = sorted(t for t in tops if t.endswith(".app"))
    checks = {
        "platform": (metadata.get("platform"), "ios-simulator"),
        "dt_platform_name": (metadata.get("dt_platform_name"), "iphonesimulator"),
        "source_commit (vs tag)": (metadata.get("source_commit"), commit),
        "hands_build_commit (vs tag)": (metadata.get("hands_build_commit"), commit),
        "xcode_lane": (metadata.get("xcode_lane"), "default"),
        "artifact_sha256 (vs zip)": (metadata.get("artifact_sha256"), zip_sha256),
        "one .app at the zip root": (len(apps), 1),
    }
    bad = [f"{label} {got!r} != {want!r}" for label, (got, want) in checks.items() if got != want]
    if bad:
        warn("iOS Simulator app does not match the tag, not attached: " + "; ".join(bad))
        return None
    asset = out / f"raft-ios-simulator-{version}-{commit[:7]}.zip"
    shutil.copyfile(archive, asset)
    # metadata.json does not record ARCHS; github-release.yml builds arm64 only.
    install = {
        lang: text.format(xcode=metadata.get("xcode_version") or "?", archs="arm64", app=apps[0])
        for lang, text in IOS_SIMULATOR_INSTALL.items()
    }
    section = (
        "### iOS Simulator app\n\n"
        f"`{asset.name}` SHA-256 `{zip_sha256}`, built from `{commit[:7]}` "
        f"(Xcode {metadata.get('xcode_version')}, iOS SDK {metadata.get('ios_sdk_version')}).\n\n"
        f"{notes_block(install)}"
    )
    return section, asset


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--tag", required=True, help="vX.Y.Z")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--dmg", type=Path, help="unsigned macOS dmg to attach (optional)")
    parser.add_argument(
        "--ios-simulator-app", type=Path,
        help="downloaded ios-simulator-app-<run> artifact directory to attach (optional; needs --commit)",
    )
    parser.add_argument("--commit", default="", help="the tag's commit (40-hex)")
    parser.add_argument(
        "--aab-repo", default="",
        help="OWNER/REPO of android-release.yml runs; attach the APK's signed AAB (needs gh)",
    )
    args = parser.parse_args()

    match = re.fullmatch(r"v(\d+\.\d+\.\d+)", args.tag)
    if not match:
        die(f"tag {args.tag} is not vX.Y.Z")
    version = match.group(1)
    if args.ios_simulator_app and not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        die("--ios-simulator-app needs --commit <40-hex tag commit>")
    args.out.mkdir(parents=True, exist_ok=True)
    api = os.environ.get("HANDS_API") or "https://hands.build"

    android_hands = Hands(api, os.environ.get("HANDS_ANDROID_BEARER_TOKEN", ""))
    ios_hands = Hands(api, os.environ.get("HANDS_IOS_BEARER_TOKEN", ""))
    android_section, android_assets = android(
        version, args.out, android_hands,
        android_hands.app_id(ANDROID_SLUG, os.environ.get("HANDS_ANDROID_APP_ID", "")),
        args.aab_repo,
    )
    ios_section = ios(
        version, ios_hands, ios_hands.app_id(IOS_SLUG, os.environ.get("HANDS_IOS_APP_ID", "")),
    )

    assets = [str(path) for path in android_assets]
    if args.ios_simulator_app:
        found = ios_simulator(version, args.out, args.ios_simulator_app, args.commit)
        if found:
            simulator_section, simulator_zip = found
            ios_section += "\n" + simulator_section
            assets.append(str(simulator_zip))
    sections = [android_section, ios_section]
    if args.dmg:
        macos_section, dmg = macos(version, args.out, args.dmg)
        assets.append(str(dmg))
        sections.append(macos_section)

    (args.out / "body.md").write_text(f"# Raft {version}\n\n" + "\n".join(sections), encoding="utf-8")
    (args.out / "manifest.json").write_text(
        json.dumps(
            {"tag": args.tag, "version": version, "assets": assets, "warnings": WARNINGS},
            indent=2, ensure_ascii=False,
        ) + "\n",
        encoding="utf-8",
    )
    print(f"github-release: prepared {args.tag} in {args.out}")


if __name__ == "__main__":
    main()
