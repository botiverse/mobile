#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const sourceSvg = resolve(repoRoot, "scripts/assets/thread-reactions/reaction_atlas_v1.svg");
const outPath = resolve(
  repoRoot,
  "compose/shared/src/commonMain/resources/MR/assets/reactions/reaction_atlas_v1.png",
);

if (!existsSync(sourceSvg)) {
  throw new Error(`Reaction atlas SVG source is missing: ${sourceSvg}`);
}

try {
  execFileSync("rsvg-convert", [
    "--format",
    "png",
    "--width",
    "504",
    "--height",
    "72",
    "--output",
    outPath,
    sourceSvg,
  ]);
} catch (error) {
  if (error.code === "ENOENT") {
    throw new Error("rsvg-convert is required to regenerate the thread reaction atlas.");
  }
  throw error;
}

console.log(`Generated ${outPath}`);
