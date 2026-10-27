#!/usr/bin/env python3
"""Prepare the GitHub Release for a shipped mobile version from what Hands shipped.

For tag ``vX.Y.Z`` this resolves, from Hands:

- Android: the published (``active`` or ``superseded``) ``main`` release whose build is
  version ``X.Y.Z``; when there are several builds of that version, the highest
  versionCode. Its signed APK is downloaded and its SHA-256 checked against Hands, so the
  file attached to the GitHub Release is byte-for-byte the one users installed.
- iOS: the highest-versionCode build of ``X.Y.Z``. No IPA is attached; the body names the
  TestFlight build number.

The body uses the release notes stored on the Hands releases (what users saw), in
zh-CN / en / ja, one section per platform. A macOS section is added when ``--dmg`` is
given (reserved for when the Mac app joins the release train).

Output goes to ``--out``: ``body.md``, the APK, and ``manifest.json`` (asset paths for
the caller to upload). Creating or updating the Release itself is left to the caller
(``gh release`` in .github/workflows/github-release.yml), so this script needs only
read access to Hands.

Environment:
  HANDS_API                       default https://hands.build
  HANDS_ANDROID_BEARER_TOKEN      read token for the Android app
  HANDS_IOS_BEARER_TOKEN          read token for the iOS app
  HANDS_ANDROID_APP_ID            optional; resolved from the app list when unset
  HANDS_IOS_APP_ID                optional; resolved from the app list when unset
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

ANDROID_SLUG = "raft-android"
IOS_SLUG = "raft-ios"
PUBLISHED = {"active", "superseded"}
USER_AGENT = "botiverse-mobile-github-release/1"
LANGS = [("zh-CN", "中文"), ("en", "English"), ("ja", "日本語")]


def die(message: str) -> None:
    print(f"github-release: {message}", file=sys.stderr)
    sys.exit(1)


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


def android(version: str, out: Path, hands: Hands, app_id: str) -> tuple[str, Path]:
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
    metadata = json.loads(build.get("build_metadata_json") or "{}")
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
    section = (
        f"## Android {version} ({code})\n\n"
        f"Hands `main`, release `{release['id']}`. Download: "
        f"<https://hands.build/apps/{ANDROID_SLUG}/v/{version}>\n\n"
        f"APK SHA-256 `{expected}`\n\n{notes_block(release_notes(detail))}"
    )
    return section, apk


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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--tag", required=True, help="vX.Y.Z")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--dmg", type=Path, help="macOS dmg to attach (optional)")
    args = parser.parse_args()

    match = re.fullmatch(r"v(\d+\.\d+\.\d+)", args.tag)
    if not match:
        die(f"tag {args.tag} is not vX.Y.Z")
    version = match.group(1)
    args.out.mkdir(parents=True, exist_ok=True)
    api = os.environ.get("HANDS_API") or "https://hands.build"

    android_hands = Hands(api, os.environ.get("HANDS_ANDROID_BEARER_TOKEN", ""))
    ios_hands = Hands(api, os.environ.get("HANDS_IOS_BEARER_TOKEN", ""))
    android_section, apk = android(
        version, args.out, android_hands,
        android_hands.app_id(ANDROID_SLUG, os.environ.get("HANDS_ANDROID_APP_ID", "")),
    )
    ios_section = ios(
        version, ios_hands, ios_hands.app_id(IOS_SLUG, os.environ.get("HANDS_IOS_APP_ID", "")),
    )

    assets = [str(apk)]
    sections = [android_section, ios_section]
    if args.dmg:
        if not args.dmg.is_file():
            die(f"dmg not found: {args.dmg}")
        assets.append(str(args.dmg))
        sections.append(f"## macOS {version}\n\nAttached: `{args.dmg.name}`\n")

    (args.out / "body.md").write_text(f"# Raft {version}\n\n" + "\n".join(sections), encoding="utf-8")
    (args.out / "manifest.json").write_text(
        json.dumps({"tag": args.tag, "version": version, "assets": assets}, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"github-release: prepared {args.tag} in {args.out}")


if __name__ == "__main__":
    main()
