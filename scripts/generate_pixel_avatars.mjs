#!/usr/bin/env node
// Regenerates the fixed pixel-avatar table from the CANONICAL avatar
// definition in the Slock repo (packages/shared/assets/pixelAvatars.json;
// older checkouts keep it at packages/web/assets/avatars/pixelAvatars.json) —
// the same input that drives the web PixelAvatar.tsx — so mobile and web
// avatars can never drift (task #352).
//
// Outputs:
//   compose/shared/src/commonMain/kotlin/build/raft/app/RaftPixelAvatarData.kt
//     (palette maps, fixed specs, reserved keys, default key, data version)
//
// Point SLOCK_SOURCE_REPO (or SLOCK_REACT_REPO_DIR) at a Slock web checkout,
// mirroring scripts/slock-visual-testing.mjs resolution.
//
// Use --check to verify the committed file matches the canonical JSON without
// writing (CI drift gate, same UX as slock-visual-testing.mjs
// generate-fixture-data --check).
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
// Preferred location first; the old web-package location is the fallback for
// checkouts that predate the move to packages/shared.
const CANONICAL_RELATIVE_PATHS = [
  join("packages", "shared", "assets", "pixelAvatars.json"),
  join("packages", "web", "assets", "avatars", "pixelAvatars.json"),
];

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
    for (const relativePath of CANONICAL_RELATIVE_PATHS) {
      if (existsSync(join(root, relativePath))) {
        return { root, relativePath };
      }
    }
  }
  throw new Error(
    `Cannot find a Slock checkout with ${CANONICAL_RELATIVE_PATHS.join(" or ")}. ` +
      "Set SLOCK_SOURCE_REPO to a botiverse/slock checkout.",
  );
}

const { root: slockSourceRepo, relativePath: canonicalRelativePath } = resolveSlockSourceRepo();
const canonical = JSON.parse(readFileSync(join(slockSourceRepo, canonicalRelativePath), "utf8"));

if (!Number.isInteger(canonical.version)) {
  throw new Error("Canonical pixelAvatars.json is missing an integer `version`.");
}
if (!canonical.palette || typeof canonical.palette !== "object") {
  throw new Error("Canonical pixelAvatars.json is missing `palette`.");
}
if (!canonical.avatars || typeof canonical.avatars !== "object" || Object.keys(canonical.avatars).length === 0) {
  throw new Error("Canonical pixelAvatars.json has no `avatars`.");
}
if (!Array.isArray(canonical.reservedKeys)) {
  throw new Error("Canonical pixelAvatars.json is missing `reservedKeys`.");
}
if (typeof canonical.defaultKey !== "string" || !canonical.avatars[canonical.defaultKey]) {
  throw new Error("Canonical pixelAvatars.json `defaultKey` does not name an avatar.");
}

// Palette letter -> "#AARRGGBB" string ('_'/"transparent" -> "#00000000").
function paletteArgb(letter, value) {
  if (value === "transparent") return "#00000000";
  if (!/^#[0-9A-Fa-f]{6}$/.test(value)) {
    throw new Error(`Palette entry '${letter}' has an unsupported color value: ${value}`);
  }
  return `#FF${value.slice(1).toUpperCase()}`;
}

// "#RRGGBB" -> Kotlin `Color(0xFFRRGGBB)` literal.
function kotlinColorLiteral(hex) {
  if (!/^#[0-9A-Fa-f]{6}$/.test(hex)) {
    throw new Error(`Unsupported literal color value: ${hex}`);
  }
  return `Color(0xFF${hex.slice(1).toUpperCase()})`;
}

// Palette letter -> Kotlin `Color` expression.
function paletteKotlinColor(letter, value) {
  return value === "transparent" ? "Color.Transparent" : kotlinColorLiteral(value);
}

const paletteEntries = Object.entries(canonical.palette);
for (const [letter] of paletteEntries) {
  if (letter.length !== 1) {
    throw new Error(`Palette letters must be single characters, got '${letter}'.`);
  }
}
const paletteLetters = new Set(paletteEntries.map(([letter]) => letter));

const avatarEntries = Object.entries(canonical.avatars).map(([key, avatar]) => {
  if (!Array.isArray(avatar.grid) || avatar.grid.length !== 8 || avatar.grid.some((row) => row.length !== 8)) {
    throw new Error(`Avatar '${key}' grid must be 8 rows of 8 letters.`);
  }
  for (const row of avatar.grid) {
    for (const letter of row) {
      if (!paletteLetters.has(letter)) {
        throw new Error(`Avatar '${key}' grid uses unknown palette letter '${letter}'.`);
      }
    }
  }
  const bg = avatar.bg;
  let background;
  let literalBg = null;
  if (typeof bg === "string" && bg.startsWith("#")) {
    // One-off literal background (finch, mug) intentionally NOT in the palette.
    background = kotlinColorLiteral(bg);
    literalBg = bg.toUpperCase();
  } else if (paletteLetters.has(bg) && bg !== "_") {
    background = `PixelAvatarPalette.getValue('${bg}')`;
  } else {
    throw new Error(`Avatar '${key}' bg must be a palette letter or a #hex literal, got '${bg}'.`);
  }
  return { key, background, literalBg, grid: avatar.grid };
});

for (const reserved of canonical.reservedKeys) {
  if (!canonical.avatars[reserved]) {
    throw new Error(`Reserved key '${reserved}' does not name an avatar.`);
  }
}

// Literal (non-palette) backgrounds in avatar order, deduped, for the
// known-background ARGB lookup used by the native avatar view path.
const literalBackgrounds = [...new Set(avatarEntries.map((entry) => entry.literalBg).filter(Boolean))];

const paletteArgbBody = paletteEntries
  .map(([letter, value]) => `'${letter}' to "${paletteArgb(letter, value)}"`)
  .join(", ");
const paletteBody = paletteEntries
  .map(([letter, value]) => `'${letter}' to ${paletteKotlinColor(letter, value)}`)
  .join(", ");
const literalBackgroundBody = literalBackgrounds
  .map((hex) => `${kotlinColorLiteral(hex)} to "#FF${hex.slice(1)}"`)
  .join(", ");
const specsBody = avatarEntries
  .map(({ key, background, grid }) => {
    const rows = grid.map((row) => `"${row}"`).join(", ");
    return `    "${key}" to PixelAvatarSpec(${background}, listOf(${rows}))`;
  })
  .join(",\n");
const reservedBody = canonical.reservedKeys.map((key) => `"${key}"`).join(", ");
// The picker's series (one heading and one row of avatars each). Older canonical
// documents have none; the picker then shows every avatar in one untitled group.
const canonicalSeries = Array.isArray(canonical.series) ? canonical.series : [];
for (const series of canonicalSeries) {
  if (typeof series.key !== "string" || !Array.isArray(series.avatars)) {
    throw new Error("Each pixelAvatars.json `series` entry needs a `key` and an `avatars` list.");
  }
  for (const key of series.avatars) {
    if (!canonical.avatars[key]) {
      throw new Error(`Series '${series.key}' names unknown avatar '${key}'.`);
    }
  }
}
const seriesBody = canonicalSeries
  .map((series) => `    PixelAvatarSeries("${series.key}", listOf(${series.avatars.map((key) => `"${key}"`).join(", ")}))`)
  .join(",\n");

const rendered = `// GENERATED by scripts/generate_pixel_avatars.mjs from slock ${canonicalRelativePath.split("\\").join("/")} — do not edit
// Regenerate: node scripts/generate_pixel_avatars.mjs (set SLOCK_SOURCE_REPO to a botiverse/slock checkout).
package build.raft.app

import androidx.compose.ui.graphics.Color

/** Canonical pixelAvatars.json \`version\` this table was generated from. */
internal const val PixelAvatarDataVersion = ${canonical.version}

internal val PixelAvatarPaletteArgb = mapOf(${paletteArgbBody})
internal val PixelAvatarPalette = mapOf(${paletteBody})

/**
 * ARGB strings for the literal (non-palette) \`bg\` hexes in pixelAvatars.json —
 * one-off backgrounds (finch, mug) intentionally NOT promoted into the shared
 * palette. Merged into the known-background lookup for the native avatar view.
 */
internal val PixelAvatarLiteralBackgroundArgb: Map<Color, String> = mapOf(${literalBackgroundBody})

/** Fixed avatars in canonical entry order — it is the picker/grid order. */
internal val PixelAvatarFixedSpecs = mapOf(
${specsBody}
)

/**
 * Avatar keys reserved for specific agents (mirrors React \`RESERVED_AVATAR_KEYS\`).
 * Excluded from random selection (CreateAgentDialog) and the manual picker grid
 * (AgentDetailPanel).
 */
internal val PixelAvatarReservedKeys: Set<String> = setOf(${reservedBody})

internal const val PixelAvatarDefaultKey = "${canonical.defaultKey}"

/** The avatar picker's series in canonical order (mirrors React \`AVATAR_SERIES\`). */
internal val PixelAvatarBundledSeries: List<PixelAvatarSeries> = listOf(
${seriesBody}
)
`;

const outPath = resolve(
  repoRoot,
  "compose/shared/src/commonMain/kotlin/build/raft/app/RaftPixelAvatarData.kt",
);
const checkMode = process.argv.includes("--check");

if (checkMode) {
  const existing = existsSync(outPath) ? readFileSync(outPath, "utf8") : null;
  if (existing === rendered) {
    console.log(`generate_pixel_avatars: ${outPath} is up to date`);
  } else {
    throw new Error(
      `generate_pixel_avatars: ${outPath} ${existing === null ? "is missing" : "has drifted from pixelAvatars.json"}. ` +
        "Regenerate with node scripts/generate_pixel_avatars.mjs.",
    );
  }
} else {
  writeFileSync(outPath, rendered);
  console.log(
    `Generated ${outPath} (${avatarEntries.length} avatars, data version ${canonical.version}, from ${slockSourceRepo})`,
  );
}
