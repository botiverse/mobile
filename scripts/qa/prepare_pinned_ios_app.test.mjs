import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";

function fixture({ metadataOverrides = {}, plistOverrides = {} } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "pinned-ios-"));
  const bin = path.join(root, "bin");
  const products = path.join(root, "products");
  fs.mkdirSync(bin);
  fs.mkdirSync(path.join(products, "iosApp.app"), { recursive: true });
  fs.writeFileSync(path.join(products, "iosApp.app", "old-marker"), "old");
  const zip = path.join(root, "app.zip");
  fs.writeFileSync(zip, "exact zip bytes");
  const sha = crypto.createHash("sha256").update(fs.readFileSync(zip)).digest("hex");
  const metadata = path.join(root, "metadata.json");
  fs.writeFileSync(metadata, JSON.stringify({
    platform: "ios-simulator", artifact_sha256: sha, artifact_size: fs.statSync(zip).size,
    source_commit: "4".repeat(40), bundle_id: "build.raft.app", version: "1.0",
    version_code: "1", github_run_id: "29700366778",
    ...metadataOverrides,
  }));
  const dittoLog = path.join(root, "ditto.log");
  fs.writeFileSync(path.join(bin, "ditto"), `#!/usr/bin/env bash
printf '%s\n' "$*" >> "${dittoLog}"
if [ "$1" = "-x" ]; then
  dest="$4"
  mkdir -p "$dest/iosApp.app"
  printf plist > "$dest/iosApp.app/Info.plist"
  printf pinned > "$dest/iosApp.app/pinned-marker"
else
  src="$1"; dest="$2"
  mkdir -p "$dest"
  cp -R "$src/." "$dest/"
fi
`, { mode: 0o755 });
  const plist = { bundle: "build.raft.app", version: "1.0", code: "1", ...plistOverrides };
  fs.writeFileSync(path.join(bin, "PlistBuddy"), `#!/usr/bin/env bash
case "$2" in
  *CFBundleIdentifier*) printf '${plist.bundle}\n';;
  *CFBundleShortVersionString*) printf '${plist.version}\n';;
  *CFBundleVersion*) printf '${plist.code}\n';;
  *) exit 2;;
esac
`, { mode: 0o755 });
  return { bin, products, zip, metadata, dittoLog, sha };
}

test("wrong pinned ZIP SHA fails before extraction or app replacement", () => {
  const f = fixture();
  assert.throws(() => execFileSync("bash", [
    new URL("./prepare_pinned_ios_app.sh", import.meta.url).pathname,
    f.zip, f.metadata, f.products, "29700366778", "wrong-sha", "4".repeat(40), "build.raft.app", "1.0", "1",
  ], { env: { ...process.env, GITHUB_ENV: "", DITTO_BIN: path.join(f.bin, "ditto"), PLIST_BUDDY: path.join(f.bin, "PlistBuddy") } }));
  assert.equal(fs.existsSync(f.dittoLog), false);
  assert.equal(fs.readFileSync(path.join(f.products, "iosApp.app", "old-marker"), "utf8"), "old");
});

test("verified pinned app replaces only the built app product", () => {
  const f = fixture();
  execFileSync("bash", [
    new URL("./prepare_pinned_ios_app.sh", import.meta.url).pathname,
    f.zip, f.metadata, f.products, "29700366778", f.sha, "4".repeat(40), "build.raft.app", "1.0", "1",
  ], { env: { ...process.env, GITHUB_ENV: "", PATH: `${path.dirname(process.execPath)}:${process.env.PATH}`, DITTO_BIN: path.join(f.bin, "ditto"), PLIST_BUDDY: path.join(f.bin, "PlistBuddy") } });
  assert.equal(fs.existsSync(path.join(f.products, "iosApp.app", "old-marker")), false);
  assert.equal(fs.readFileSync(path.join(f.products, "iosApp.app", "pinned-marker"), "utf8"), "pinned");
});

test("rejects source commit not bound by the downloaded metadata", () => {
  const f = fixture();
  assert.throws(() => execFileSync("bash", [
    new URL("./prepare_pinned_ios_app.sh", import.meta.url).pathname,
    f.zip, f.metadata, f.products, "29700366778", f.sha, "5".repeat(40), "build.raft.app", "1.0", "1",
  ], { env: { ...process.env, GITHUB_ENV: "", PATH: `${path.dirname(process.execPath)}:${process.env.PATH}`, DITTO_BIN: path.join(f.bin, "ditto"), PLIST_BUDDY: path.join(f.bin, "PlistBuddy") } }));
  assert.equal(fs.existsSync(f.dittoLog), false);
});

for (const [name, fixtureOptions] of [
  ["bundle", { plistOverrides: { bundle: "wrong.bundle" } }],
  ["version", { plistOverrides: { version: "9.9" } }],
  ["version code", { plistOverrides: { code: "999" } }],
]) {
  test(`rejects metadata/plist ${name} drift before replacing the target app`, () => {
    const f = fixture(fixtureOptions);
    assert.throws(() => execFileSync("bash", [
      new URL("./prepare_pinned_ios_app.sh", import.meta.url).pathname,
      f.zip, f.metadata, f.products, "29700366778", "", "", "", "", "",
    ], { env: { ...process.env, GITHUB_ENV: "", PATH: `${path.dirname(process.execPath)}:${process.env.PATH}`, DITTO_BIN: path.join(f.bin, "ditto"), PLIST_BUDDY: path.join(f.bin, "PlistBuddy") } }), /Command failed/);
    assert.equal(fs.readFileSync(path.join(f.products, "iosApp.app", "old-marker"), "utf8"), "old");
  });
}

test("rejects a non-iOS-simulator artifact manifest before extraction", () => {
  const f = fixture({ metadataOverrides: { platform: "ios-device" } });
  assert.throws(() => execFileSync("bash", [
    new URL("./prepare_pinned_ios_app.sh", import.meta.url).pathname,
    f.zip, f.metadata, f.products, "29700366778", "", "", "", "", "",
  ], { env: { ...process.env, GITHUB_ENV: "", PATH: `${path.dirname(process.execPath)}:${process.env.PATH}`, DITTO_BIN: path.join(f.bin, "ditto"), PLIST_BUDDY: path.join(f.bin, "PlistBuddy") } }), /Command failed/);
  assert.equal(fs.existsSync(f.dittoLog), false);
});

function runPinned(f, extraEnv = {}) {
  return execFileSync("bash", [
    new URL("./prepare_pinned_ios_app.sh", import.meta.url).pathname,
    f.zip, f.metadata, f.products, "29700366778", f.sha, "4".repeat(40), "build.raft.app", "1.0", "1",
  ], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], env: { ...process.env, GITHUB_ENV: "", PATH: `${path.dirname(process.execPath)}:${process.env.PATH}`, DITTO_BIN: path.join(f.bin, "ditto"), PLIST_BUDDY: path.join(f.bin, "PlistBuddy"), ...extraEnv } });
}

const optInLane = { xcode_lane: "opt-in", xcode_version: "27.1", xcode_app: "Xcode_27.1.app", ios_sdk_version: "27.1" };

test("refuses an app from the opt-in Xcode lane before extraction", () => {
  const f = fixture({ metadataOverrides: optInLane });
  for (const allow of [undefined, "", "false", "1"]) {
    const env = allow === undefined ? {} : { ALLOW_NON_DEFAULT_XCODE_LANE: allow };
    assert.throws(() => runPinned(f, env), (error) => {
      assert.equal(error.status, 3);
      assert.match(error.stderr, /built on the 'opt-in' Xcode lane: Xcode 27\.1 \(Xcode_27\.1\.app\), iPhoneSimulator SDK 27\.1/);
      assert.match(error.stderr, /allow_non_default_xcode_lane=true/);
      return true;
    });
  }
  assert.equal(fs.existsSync(f.dittoLog), false);
  assert.equal(fs.readFileSync(path.join(f.products, "iosApp.app", "old-marker"), "utf8"), "old");
});

test("accepts an opt-in lane app only when explicitly allowed, with a warning", () => {
  const f = fixture({ metadataOverrides: optInLane });
  const out = runPinned(f, { ALLOW_NON_DEFAULT_XCODE_LANE: "true" });
  assert.match(out, /::warning::accepting a pinned iOS app from the 'opt-in' Xcode lane/);
  assert.equal(fs.readFileSync(path.join(f.products, "iosApp.app", "pinned-marker"), "utf8"), "pinned");
});

test("accepts a default lane app and logs its toolchain", () => {
  const f = fixture({ metadataOverrides: { xcode_lane: "default", xcode_version: "26.6", xcode_app: "Xcode_26.6.app", ios_sdk_version: "26.5" } });
  const out = runPinned(f);
  assert.match(out, /Xcode lane: default, Xcode 26\.6 \(Xcode_26\.6\.app\), iPhoneSimulator SDK 26\.5/);
  assert.doesNotMatch(out, /::warning::/);
});

test("accepts a carrier without xcode_lane (predates stamping) with a warning", () => {
  const f = fixture();
  const out = runPinned(f);
  assert.match(out, /::warning::pinned iOS app metadata has no xcode_lane/);
});
