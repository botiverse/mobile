#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const sourceDir = resolve(repoRoot, "scripts/assets/thread-reactions");
const manifestPath = resolve(
  repoRoot,
  "compose/shared/src/commonMain/resources/MR/assets/reactions/reaction_atlas_v1_manifest.json",
);
const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
const atlas = manifest.atlas;
const reactions = manifest.reactions;

if (manifest.schemaVersion !== 1) {
  throw new Error(`Unsupported reaction atlas schema version: ${manifest.schemaVersion}`);
}
if (!atlas || !Number.isInteger(atlas.width) || !Number.isInteger(atlas.height) || !atlas.image) {
  throw new Error("Reaction atlas manifest is missing atlas image dimensions.");
}
if (!Array.isArray(reactions) || reactions.length === 0) {
  throw new Error("Reaction atlas manifest has no reactions.");
}

const outPath = resolve(
  repoRoot,
  "compose/shared/src/commonMain/resources/MR/assets/reactions",
  atlas.image,
);

for (const reaction of reactions) {
  if (!reaction.name || !reaction.value || !reaction.rect) {
    throw new Error("Reaction atlas manifest includes an invalid reaction entry.");
  }
  const rect = reaction.rect;
  if (![rect.x, rect.y, rect.w, rect.h].every(Number.isInteger)) {
    throw new Error(`Reaction ${reaction.name} has an invalid rect.`);
  }
  if (rect.x < 0 || rect.y < 0 || rect.w <= 0 || rect.h <= 0 || rect.x + rect.w > atlas.width || rect.y + rect.h > atlas.height) {
    throw new Error(`Reaction ${reaction.name} rect is outside the atlas.`);
  }
  const sourceSvg = resolve(sourceDir, `${reaction.name}.svg`);
  if (!existsSync(sourceSvg)) {
    throw new Error(`Reaction SVG source is missing: ${sourceSvg}`);
  }
}

const tempDir = mkdtempSync(resolve(tmpdir(), "slock-reaction-atlas-"));
const atlasSvgPath = resolve(tempDir, "reaction_atlas_v1.svg");
function sourceBody(reaction) {
  const source = readFileSync(resolve(sourceDir, `${reaction}.svg`), "utf8");
  const body = source.match(/<svg\b[^>]*>([\s\S]*)<\/svg>/i)?.[1]?.trim();
  if (!body) {
    throw new Error(`Reaction SVG source has no renderable body: ${reaction}`);
  }
  return body;
}

const atlasSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="${atlas.width}" height="${atlas.height}" viewBox="0 0 ${atlas.width} ${atlas.height}">
${reactions
  .map((reaction) => {
    const rect = reaction.rect;
    return `  <g transform="translate(${rect.x} ${rect.y}) scale(${rect.w / 24} ${rect.h / 24})">
${sourceBody(reaction.name)
  .split("\n")
  .map((line) => `    ${line}`)
  .join("\n")}
  </g>`;
  })
  .join("\n")}
</svg>
`;
writeFileSync(atlasSvgPath, atlasSvg);

try {
  execFileSync("rsvg-convert", [
    "--format",
    "png",
    "--width",
    String(atlas.width),
    "--height",
    String(atlas.height),
    "--output",
    outPath,
    atlasSvgPath,
  ]);
} catch (error) {
  if (error.code === "ENOENT") {
    throw new Error("rsvg-convert is required to regenerate the thread reaction atlas.");
  }
  throw error;
}

console.log(`Generated ${outPath}`);
