#!/usr/bin/env node
// Regenerates the thread reaction atlas from the CANONICAL reaction sprite
// definition in the Slock web repo (packages/web/assets/reactions/
// reactions.json + source/*.svg) — the same inputs that produce the web
// reaction-sprite.svg — so mobile and web glyphs can never drift (task #335).
//
// Outputs:
//   compose/shared/src/commonMain/composeResources/files/reactions/
//     reaction_atlas_v1.png            (rasterized atlas, 24dp cells @3x)
//     reaction_atlas_v1_manifest.json  (ids/emoji/aliases/labels + rects)
//
// Point SLOCK_SOURCE_REPO (or SLOCK_REACT_REPO_DIR) at a Slock web checkout,
// mirroring scripts/slock-visual-testing.mjs resolution.
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

function resolveSlockSourceRepo() {
  const candidates = [
    process.env.SLOCK_SOURCE_REPO,
    process.env.SLOCK_REACT_REPO_DIR,
    process.env.SLOCK_VISUAL_REACT_REPO_ROOT,
    join(homedir(), "0Workspace", "github.com", "botiverse", "slock"),
    resolve(repoRoot, "..", "slock-source"),
    resolve(repoRoot, "..", "slock"),
  ].filter(Boolean);
  for (const candidate of candidates) {
    const root = resolve(candidate);
    if (existsSync(join(root, "packages", "web", "assets", "reactions", "reactions.json"))) {
      return root;
    }
  }
  throw new Error(
    "Cannot find a Slock web checkout with packages/web/assets/reactions/reactions.json. " +
      "Set SLOCK_SOURCE_REPO to a botiverse/slock checkout.",
  );
}

const slockSourceRepo = resolveSlockSourceRepo();
const reactionsDir = join(slockSourceRepo, "packages", "web", "assets", "reactions");
const canonical = JSON.parse(readFileSync(join(reactionsDir, "reactions.json"), "utf8"));
if (!Array.isArray(canonical) || canonical.length === 0) {
  throw new Error("Canonical reactions.json is empty or not an array.");
}

// Mobile keeps localized labels the web definition does not carry yet;
// keyed by canonical id and merged over the web "en" label.
const LOCALIZED_LABELS = {
  thumbs_up: { "zh-Hans": "点赞", "zh-Hant": "讚" },
  heart: { "zh-Hans": "爱心", "zh-Hant": "愛心" },
  party_popper: { "zh-Hans": "庆祝", "zh-Hant": "慶祝" },
  eyes: { "zh-Hans": "关注", "zh-Hant": "關注" },
  fire: { "zh-Hans": "火", "zh-Hant": "火" },
  joy: { "zh-Hans": "大笑", "zh-Hant": "大笑" },
  check_mark: { "zh-Hans": "完成", "zh-Hant": "完成" },
};

const CELL_DP = 24;
const PIXEL_RATIO = 4;
const CELL_PX = CELL_DP * PIXEL_RATIO;

const atlas = {
  image: "reaction_atlas_v1.png",
  width: CELL_PX * canonical.length,
  height: CELL_PX,
  pixelRatio: PIXEL_RATIO,
  defaultDisplaySizeDp: 18,
};

const reactions = canonical.map((item, index) => {
  if (!item.id || !item.emoji || !item.source) {
    throw new Error(`Canonical reaction entry ${index} is missing id/emoji/source.`);
  }
  const sourceSvg = join(reactionsDir, "source", item.source);
  if (!existsSync(sourceSvg)) {
    throw new Error(`Canonical reaction SVG source is missing: ${sourceSvg}`);
  }
  return {
    name: item.id,
    value: item.emoji,
    aliases: item.aliases ?? [],
    labels: { en: item.label ?? item.id, ...(LOCALIZED_LABELS[item.id] ?? {}) },
    rect: { x: index * CELL_PX, y: 0, w: CELL_PX, h: CELL_PX },
    sourceSvg,
  };
});

function sourceBody(svgPath) {
  const source = readFileSync(svgPath, "utf8");
  const body = source.match(/<svg\b[^>]*>([\s\S]*)<\/svg>/i)?.[1]?.trim();
  if (!body) {
    throw new Error(`Reaction SVG source has no renderable body: ${svgPath}`);
  }
  return body;
}

const atlasSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="${atlas.width}" height="${atlas.height}" viewBox="0 0 ${atlas.width} ${atlas.height}">
${reactions
  .map((reaction) => {
    const rect = reaction.rect;
    return `  <g fill="none" transform="translate(${rect.x} ${rect.y}) scale(${rect.w / CELL_DP} ${rect.h / CELL_DP})">
${sourceBody(reaction.sourceSvg)
  .split("\n")
  .map((line) => `    ${line}`)
  .join("\n")}
  </g>`;
  })
  .join("\n")}
</svg>
`;

const assetsDir = resolve(repoRoot, "compose/shared/src/commonMain/composeResources/files/reactions");
const outPngPath = resolve(assetsDir, atlas.image);
const outManifestPath = resolve(assetsDir, "reaction_atlas_v1_manifest.json");

const tempDir = mkdtempSync(resolve(tmpdir(), "slock-reaction-atlas-"));
const atlasSvgPath = resolve(tempDir, "reaction_atlas_v1.svg");
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
    outPngPath,
    atlasSvgPath,
  ]);
} catch (error) {
  if (error.code === "ENOENT") {
    throw new Error("rsvg-convert is required to regenerate the thread reaction atlas.");
  }
  throw error;
}

const manifest = {
  schemaVersion: 1,
  canonicalSource: "packages/web/assets/reactions/reactions.json",
  atlas,
  reactions: reactions.map(({ sourceSvg, ...rest }) => rest),
};
writeFileSync(outManifestPath, `${JSON.stringify(manifest, null, 2)}\n`);

console.log(`Generated ${outPngPath}`);
console.log(`Generated ${outManifestPath} (${reactions.length} reactions from ${slockSourceRepo})`);
