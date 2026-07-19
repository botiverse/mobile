import fs from "node:fs";

export function validateFrozenInputs({ primaryPin = "", secondaryPin = "", identity = {} }) {
  const pins = [primaryPin, secondaryPin].filter(Boolean);
  if (pins.length > 1) throw new Error("multiple pinned artifact sources are not allowed");
  const values = Object.values(identity);
  if (values.some((value) => /[\r\n]/.test(value))) throw new Error("frozen identity fields must be single-line values");
  const present = values.filter((value) => value !== "").length;
  if (!pins.length && present) throw new Error("frozen identity requires a pinned artifact source");
  if (present !== 0 && present !== values.length) throw new Error("frozen identity must be complete or absent");
  return { pinned: pins.length === 1, strictIdentity: present === values.length };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    const result = validateFrozenInputs({
      primaryPin: process.env.PIN_PRIMARY || "",
      secondaryPin: process.env.PIN_SECONDARY || "",
      identity: {
        sha256: process.env.ARTIFACT_SHA256 || "",
        sourceCommit: process.env.ARTIFACT_SOURCE_COMMIT || "",
        version: process.env.ARTIFACT_VERSION || "",
        versionCode: process.env.ARTIFACT_VERSION_CODE || "",
        packageId: process.env.ARTIFACT_PACKAGE_ID || "",
      },
    });
    if (process.env.GITHUB_OUTPUT) {
      fs.appendFileSync(process.env.GITHUB_OUTPUT, `pinned=${result.pinned}\nstrict_identity=${result.strictIdentity}\n`);
    }
    console.log(JSON.stringify(result));
  } catch (error) {
    console.error(`frozen artifact input error: ${error.message}`);
    process.exit(2);
  }
}
