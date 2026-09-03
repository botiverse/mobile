#!/usr/bin/env bash
# Linux-runnable contract test for export_ios_simulator_app.sh: PlistBuddy and
# ditto are shimmed so the fail-closed branches and the metadata shape the
# consumer (prepare_pinned_ios_app.sh) validates can be pinned without a Mac.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/export_ios_simulator_app.sh"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
SHA40=0123456789abcdef0123456789abcdef01234567

# PlistBuddy shim: Info.plist is a key=value file; `Print :Key` and `Print Key`.
cat > "$T/plistbuddy" <<'SH'
#!/usr/bin/env bash
key="${2#Print }"; key="${key#:}"; file="$3"
line=$(grep -m1 "^$key=" "$file" || true)
[ -n "$line" ] || { echo "Print: Entry, \"$key\", Does Not Exist" >&2; exit 1; }
printf '%s\n' "${line#*=}"
SH
# ditto shim: `-c -k --sequesterRsrc --keepParent <src> <zip>` via python zipfile.
cat > "$T/ditto" <<'SH'
#!/usr/bin/env bash
src="${@: -2:1}"; dst="${@: -1}"
python3 - "$src" "$dst" <<'PY'
import os, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
base = os.path.basename(src.rstrip('/'))
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.join(base, os.path.relpath(p, src)))
PY
SH
chmod +x "$T/plistbuddy" "$T/ditto"
export PLIST_BUDDY="$T/plistbuddy" DITTO_BIN="$T/ditto"

mk_app() { # mk_app <dir> <DTPlatformName> <HandsBuildCommit|-absent->
  local d="$1/Products"; rm -rf "$d"; mkdir -p "$d/raft.app"
  printf 'payload-%s\n' "$RANDOM" > "$d/raft.app/raft"
  {
    echo "CFBundleIdentifier=build.raft.app.simqa"
    echo "CFBundleShortVersionString=1.2.3"
    echo "CFBundleVersion=456"
    echo "CFBundleExecutable=raft"
    [ "$2" = "-absent-" ] || echo "DTPlatformName=$2"
    [ "$3" = "-absent-" ] || echo "HandsBuildCommit=$3"
  } > "$d/raft.app/Info.plist"
  echo "$d"
}
expect_refuse() { # expect_refuse <label> <products> <needle>
  local out; if out=$("$SCRIPT" "$2" "$T/out-$1" "$SHA40" 4242 2>&1); then
    echo "FAIL $1: expected refusal, got success"; echo "$out"; exit 1; fi
  grep -q -- "$3" <<<"$out" || { echo "FAIL $1: refusal reason missing '$3'"; echo "$out"; exit 1; }
  [ ! -e "$T/out-$1/metadata.json" ] || { echo "FAIL $1: metadata written despite refusal"; exit 1; }
  echo "ok   $1 refused: $3"
}

expect_refuse device      "$(mk_app "$T/a" iphoneos "$SHA40")"       "non-simulator slice"
expect_refuse noplatform  "$(mk_app "$T/b" -absent- "$SHA40")"       "DTPlatformName=<absent>"
expect_refuse nohands     "$(mk_app "$T/c" iphonesimulator -absent-)" "got: '<empty>'"
expect_refuse unknown     "$(mk_app "$T/d" iphonesimulator unknown)"  "got: 'unknown'"
expect_refuse shortsha    "$(mk_app "$T/e" iphonesimulator 0123456)"  "got: '0123456'"
expect_refuse uppersha    "$(mk_app "$T/f" iphonesimulator 0123456789ABCDEF0123456789ABCDEF01234567)" "HandsBuildCommit"
# Length boundary (@LiBai review): the 40-`?` glob is the ONLY thing the guard
# measures, and a copy-paste with 39 or 41 marks fails silently (39 admits a
# short sha, 41 makes the guard constant-false). 39 and 41 hex must both be
# refused so a miscounted literal turns red instead of relying on a human count.
SHA39=${SHA40:0:39}; SHA41="${SHA40}0"
expect_refuse hands39     "$(mk_app "$T/f39" iphonesimulator "$SHA39")" "got: '$SHA39'"
expect_refuse hands41     "$(mk_app "$T/f41" iphonesimulator "$SHA41")" "got: '$SHA41'"
# source commit shape is enforced before anything is read — same boundary.
for bad in abc1234 "$SHA39" "$SHA41"; do
  if "$SCRIPT" "$(mk_app "$T/g" iphonesimulator "$SHA40")" "$T/out-src" "$bad" 4242 >/dev/null 2>&1; then
    echo "FAIL source commit '$bad' (${#bad} chars) accepted"; exit 1; fi
  echo "ok   source commit of ${#bad} chars refused"
done

# Happy path: shape must be exactly what prepare_pinned_ios_app.sh reads.
good=$(mk_app "$T/h" iphonesimulator "$SHA40")
"$SCRIPT" "$good" "$T/out-good" "$SHA40" 987654321 >/dev/null
zip="$T/out-good/raft-ios-simulator.app.zip"; meta="$T/out-good/metadata.json"
test -s "$zip"; test -s "$meta"; test -s "$zip.sha256"
sha=$(sha256sum "$zip" | awk '{print $1}'); size=$(stat -c '%s' "$zip")
ZIP_SHA="$sha" ZIP_SIZE="$size" SRC="$SHA40" python3 - "$meta" <<'PY'
import json, os, sys
m = json.load(open(sys.argv[1]))
required = ["platform","artifact_sha256","artifact_size","source_commit","github_run_id","bundle_id","version","version_code"]
missing = [k for k in required if k not in m]
assert not missing, f"missing consumer-required keys: {missing}"
assert m["platform"] == "ios-simulator", m["platform"]
assert m["artifact_sha256"] == os.environ["ZIP_SHA"], "artifact_sha256 must be the zip's sha"
assert m["artifact_size"] == int(os.environ["ZIP_SIZE"]) and isinstance(m["artifact_size"], int), "artifact_size must be the zip's byte size as a JSON number"
assert m["source_commit"] == os.environ["SRC"]
assert m["github_run_id"] == "987654321", "github_run_id must be the producer run id, as a string"
assert (m["bundle_id"], m["version"], m["version_code"]) == ("build.raft.app.simqa", "1.2.3", "456")
assert m["hands_build_commit"] == os.environ["SRC"] and m["dt_platform_name"] == "iphonesimulator"
assert len(m["binary_sha256"]) == 64
print("ok   happy path: consumer-required keys present, sha/size bound to the zip")
PY
# The consumer's own first-stage check (run id / sha / size equality) must pass on this file.
ACTUAL_SHA="$sha" ACTUAL_SIZE="$size" APP_RUN_ID=987654321 EXPECTED_SHA="" EXPECTED_SOURCE="" \
  EXPECTED_BUNDLE="" EXPECTED_VERSION="" EXPECTED_CODE="" node - "$meta" <<'NODE'
const fs = require("fs"); const e = process.env;
const v = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
if (v.platform !== "ios-simulator") throw new Error("metadata platform mismatch");
const checks = [["build run", String(v.github_run_id||""), e.APP_RUN_ID], ["sha", String(v.artifact_sha256||""), e.ACTUAL_SHA], ["size", String(v.artifact_size ?? ""), e.ACTUAL_SIZE]];
for (const [n, a, b] of checks) if (a !== b) throw new Error(`${n} mismatch: ${a} vs ${b}`);
console.log("ok   consumer first-stage equality (run id, sha, size) passes");
NODE
echo "ALL PASS"
