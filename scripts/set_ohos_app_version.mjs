#!/usr/bin/env node

import { readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import process from "node:process";

function usage() {
  console.error(
    "Usage: set_ohos_app_version.mjs <app.json5> <version-name> <version-code>",
  );
}

const [file, versionName, versionCodeInput] = process.argv.slice(2);
if (!file || !versionName || !versionCodeInput) {
  usage();
  process.exit(2);
}

if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(versionName)) {
  throw new Error(`Invalid OHOS version name: ${versionName}`);
}

const versionCode = Number(versionCodeInput);
if (!Number.isSafeInteger(versionCode) || versionCode <= 0) {
  throw new Error(`Invalid OHOS version code: ${versionCodeInput}`);
}

const source = await readFile(file, "utf8");
let document;
try {
  document = JSON.parse(source);
} catch (error) {
  throw new Error(`${file} must remain valid JSON-compatible JSON5`, {
    cause: error,
  });
}

if (!document.app || typeof document.app !== "object") {
  throw new Error(`${file} is missing the top-level app object`);
}

document.app.versionName = versionName;
document.app.versionCode = versionCode;

const temporary = path.join(
  path.dirname(file),
  `.${path.basename(file)}.${process.pid}.tmp`,
);
await writeFile(temporary, `${JSON.stringify(document, null, 2)}\n`, "utf8");
await rename(temporary, file);

console.log(`OHOS app version set to ${versionName} (${versionCode})`);
