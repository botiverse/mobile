#!/usr/bin/env bash
# Print the UDID of any available iPhone simulator, for use as an xcodebuild
# build destination (`-destination "platform=iOS Simulator,id=$UDID"`).
#
# Build with a CONCRETE destination, not "generic/platform=iOS Simulator":
# with the generic destination the Podfile "[CP-User] Copy kmmresource MR
# bundle to app" phase (:shared:copyFrameworkResourcesToApp) recompiles and
# relinks the Kotlin/Native framework instead of reusing the one the sync step
# just built (~20 extra minutes; ios-simulator-app run 38906495675, visual
# run 39190258424). Building does not need the simulator to be booted.
#
# BUILD ONLY: the UDID is whichever iPhone is listed first, with no runtime or
# device-model filter. That is safe because nothing is booted here; do not
# reuse it to boot a simulator or run tests.
set -euo pipefail
xcrun simctl list devices available -j | python3 -c '
import json, sys
d = json.load(sys.stdin)
udid = next((x["udid"] for devs in d["devices"].values() for x in devs if "iPhone" in x["name"]), None)
if not udid:
    sys.exit("no available iPhone simulator")
print(udid)
'
