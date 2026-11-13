import fs from "node:fs";

// Derives the source commit a pinned Android APK was built from, using only
// bytes inside the APK, and compares it with the frozen input commit.
//
// Sources (build.gradle.kts):
//  1. versionName `X.Y.Z-<suffix>+<shortsha>[-dirty]` (alpha/suffixed builds).
//  2. BuildConfig.GIT_COMMIT (`git rev-parse --short HEAD`), inlined by R8 as a
//     string constant in classes*.dex.
//
// `expected` must already be the FULL 40-char sha (the workflow resolves a
// short input through the GitHub commits API first). An embedded short sha
// only verifies when it is a prefix of that full sha AND at least
// MIN_SHORT_SHA hex chars long; shorter matches are too ambiguous.
//
// Returns { verified, observed, method, reason }. Throws on a definite
// mismatch (an embedded commit that contradicts the frozen input).

export const MIN_SHORT_SHA = 10;
const HEX_COMMIT = /^[0-9a-f]{7,40}$/;

function isPrefixOf(short, full) {
  return HEX_COMMIT.test(short) && full.startsWith(short);
}

function tooShort(observed, method) {
  return {
    verified: false,
    observed,
    method,
    reason: `APK-embedded commit ${observed} is shorter than ${MIN_SHORT_SHA} hex chars; too ambiguous to verify`,
  };
}

export function commitFromVersionName(versionName) {
  const match = /\+([0-9a-f]{7,40})(-dirty)?$/.exec(String(versionName || ""));
  if (!match) return null;
  return { commit: match[1], dirty: Boolean(match[2]) };
}

// Dex string_data_item: uleb128 utf16 length, MUTF-8 bytes, NUL. For hex
// strings shorter than 128 chars the length is one byte. Items are contiguous,
// so the previous item's NUL precedes the length byte.
export function dexHexStrings(buffer) {
  const found = new Set();
  const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer);
  for (let i = 1; i + 1 < bytes.length; i++) {
    const len = bytes[i];
    if (len < 7 || len > 40 || bytes[i - 1] !== 0) continue;
    const end = i + 1 + len;
    if (end >= bytes.length || bytes[end] !== 0) continue;
    let ok = true;
    for (let j = i + 1; j < end; j++) {
      const c = bytes[j];
      if (!((c >= 0x30 && c <= 0x39) || (c >= 0x61 && c <= 0x66))) { ok = false; break; }
    }
    if (ok) found.add(Buffer.from(bytes.subarray(i + 1, end)).toString("latin1"));
  }
  return [...found];
}

export function observeApkSourceCommit({ versionName, dex, expected }) {
  const frozen = String(expected || "").toLowerCase();
  if (!/^[0-9a-f]{40}$/.test(frozen)) throw new Error("frozen source commit must be a 40-char hex sha");

  const fromVersion = commitFromVersionName(versionName);
  if (fromVersion) {
    if (!isPrefixOf(fromVersion.commit, frozen)) {
      throw new Error(`APK versionName commit ${fromVersion.commit} does not match frozen source commit ${frozen}`);
    }
    if (fromVersion.dirty) {
      return { verified: false, observed: fromVersion.commit, method: "versionName", reason: "APK was built from a dirty working tree" };
    }
    if (fromVersion.commit.length < MIN_SHORT_SHA) return tooShort(fromVersion.commit, "versionName");
    return { verified: true, observed: fromVersion.commit, method: "versionName", reason: "" };
  }

  if (dex && dex.length) {
    const candidates = dexHexStrings(dex);
    const hit = candidates.filter((value) => isPrefixOf(value, frozen)).sort((a, b) => b.length - a.length)[0];
    if (hit && hit.length < MIN_SHORT_SHA) return tooShort(hit, "dex:BuildConfig.GIT_COMMIT");
    if (hit) return { verified: true, observed: hit, method: "dex:BuildConfig.GIT_COMMIT", reason: "" };
    return {
      verified: false,
      observed: "",
      method: "",
      reason: `no embedded commit matching the frozen source commit (versionName has no +sha; ${candidates.length} hex dex strings checked)`,
    };
  }
  return { verified: false, observed: "", method: "", reason: "no embedded commit readable (versionName has no +sha; no dex)" };
}

// CLI: node observe_apk_source_commit.mjs <versionName> <dex-file> <expected>
if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    const [versionName, dexPath, expected] = process.argv.slice(2);
    const dex = dexPath && fs.existsSync(dexPath) ? fs.readFileSync(dexPath) : null;
    console.log(JSON.stringify(observeApkSourceCommit({ versionName, dex, expected })));
  } catch (error) {
    console.error(`APK source commit error: ${error.message}`);
    process.exit(2);
  }
}
