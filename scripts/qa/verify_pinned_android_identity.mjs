import fs from "node:fs";

function clean(value, name) {
  const text = String(value ?? "");
  if (!text || /[\r\n]/.test(text)) throw new Error(`invalid ${name}`);
  return text;
}

export function verifyPinnedAndroidIdentity({ manifest, observed, expected }) {
  if (!manifest || manifest.source_commit_verified === false) throw new Error("artifact manifest does not verify source commit");
  if (manifest.platform !== "android") throw new Error("artifact manifest platform is not android");
  const source = clean(manifest.source_commit, "manifest source_commit");
  const manifestSha = clean(manifest.apk_sha256, "manifest apk_sha256");
  const actual = {
    sha256: clean(observed.sha256, "observed sha256"),
    sourceCommit: source,
    version: clean(observed.version, "observed version"),
    versionCode: clean(observed.versionCode, "observed version code"),
    packageId: clean(observed.packageId, "observed package id"),
  };
  if (!/^[0-9a-f]{64}$/.test(actual.sha256) || !/^[0-9a-f]{40}$/.test(source)) throw new Error("invalid digest/source shape");
  if (manifestSha !== actual.sha256) throw new Error("manifest/APK SHA mismatch");
  for (const [key, value] of [["version", manifest.app_version], ["versionCode", manifest.version_code], ["packageId", manifest.package]]) {
    if (value != null && String(value) !== actual[key]) throw new Error(`manifest/observed ${key} mismatch`);
  }
  for (const [key, value] of Object.entries(expected || {})) {
    if (value && String(value) !== actual[key]) throw new Error(`frozen ${key} mismatch`);
  }
  return actual;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    const manifest = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
    const observed = JSON.parse(fs.readFileSync(process.argv[3], "utf8"));
    const actual = verifyPinnedAndroidIdentity({
      manifest,
      observed,
      expected: {
        sha256: process.env.ARTIFACT_SHA256 || "",
        sourceCommit: process.env.ARTIFACT_SOURCE_COMMIT || "",
        version: process.env.ARTIFACT_VERSION || "",
        versionCode: process.env.ARTIFACT_VERSION_CODE || "",
        packageId: process.env.ARTIFACT_PACKAGE_ID || "",
      },
    });
    if (process.env.GITHUB_ENV) {
      fs.appendFileSync(process.env.GITHUB_ENV, [
        `STAMP_VERIFIED_ARTIFACT_SHA256=${actual.sha256}`,
        `STAMP_VERIFIED_SOURCE_COMMIT=${actual.sourceCommit}`,
        `STAMP_VERIFIED_APP_VERSION=${actual.version}`,
        `STAMP_VERIFIED_VERSION_CODE=${actual.versionCode}`,
        `STAMP_VERIFIED_PACKAGE_ID=${actual.packageId}`,
      ].join("\n") + "\n");
    }
    console.log(JSON.stringify(actual));
  } catch (error) {
    console.error(`pinned Android identity error: ${error.message}`);
    process.exit(2);
  }
}
