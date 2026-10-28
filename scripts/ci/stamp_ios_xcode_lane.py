#!/usr/bin/env python3
"""Stamp the Xcode, iOS SDK and Xcode lane into a Simulator app's metadata.json.

Usage: stamp_ios_xcode_lane.py <metadata.json>
Env:   XCODE_APP_PATH          the Xcode this job selected (scripts/ci/select_ios_xcode.sh)
       RAFT_DEFAULT_XCODE_APP  the workflow's pinned default Xcode

An app built on the opt-in lane (since 2026-10-28 the default is Xcode 27.1, so e.g. the
xcode_app_path=/Applications/Xcode_26.6.app fallback) is not the release toolchain: the iOS SDK
decides how the app lays out on iPhone Duo (26.x letterboxes it). Without these fields a
26.6-built carrier is indistinguishable from the 27.1 one, and qa-acceptance-ios.yml would
accept it as the release build. scripts/qa/prepare_pinned_ios_app.sh reads xcode_lane
and refuses anything but "default" unless the run allows it explicitly.

Fails (exit 2) when the lane or versions cannot be determined: this runs on the producer, and
a carrier must not be published with an unknown toolchain.
"""
import json
import os
import re
import subprocess
import sys


def run(*cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout.strip()


def fail(message):
    print(f"::error::stamp_ios_xcode_lane: {message}", file=sys.stderr)
    sys.exit(2)


def main(argv):
    if len(argv) != 2:
        fail("usage: stamp_ios_xcode_lane.py <metadata.json>")
    path = argv[1]
    selected = os.environ.get("XCODE_APP_PATH", "").rstrip("/")
    default = os.environ.get("RAFT_DEFAULT_XCODE_APP", "").rstrip("/")
    if not selected or not default:
        fail("XCODE_APP_PATH and RAFT_DEFAULT_XCODE_APP must both be set")
    lane = "default" if selected == default else "opt-in"

    try:
        version_out = run("xcodebuild", "-version")
        sdk = run("xcrun", "--sdk", "iphonesimulator", "--show-sdk-version")
    except (OSError, subprocess.CalledProcessError) as error:
        fail(f"could not read the toolchain: {error}")
    xcode = re.search(r"^Xcode (\S+)", version_out, re.M)
    build = re.search(r"^Build version (\S+)", version_out, re.M)
    if not xcode or not re.fullmatch(r"\d+(\.\d+)*", xcode.group(1)):
        fail(f"unexpected xcodebuild -version output: {version_out!r}")
    if not re.fullmatch(r"\d+(\.\d+)*", sdk):
        fail(f"unexpected iPhoneSimulator SDK version: {sdk!r}")

    with open(path, encoding="utf-8") as handle:
        metadata = json.load(handle)
    metadata.update(
        xcode_version=xcode.group(1),
        xcode_build=build.group(1) if build else "",
        ios_sdk_version=sdk,
        xcode_app=os.path.basename(selected),
        xcode_lane=lane,
    )
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(metadata, handle, indent=2)
        handle.write("\n")
    print(f"metadata.json: Xcode {xcode.group(1)} ({os.path.basename(selected)}), "
          f"iPhoneSimulator SDK {sdk}, xcode_lane={lane}")


if __name__ == "__main__":
    main(sys.argv)
