// patrol/glyph_audit.mjs — text-glyph-as-icon lens (task #494, artin).
//
// UI affordances must use Lucide icons, not unicode glyph characters ("✓",
// "×", arrows…) inside Text. Trigger: the env-var row's remove button was a
// literal "×" and the raft-ui menu drew its selected check as "✓" (both fixed
// alongside this lens). Per-file cluster issues via the shared patrol lib.
// Known-legit exclusions: masking dots ("•".repeat — password style), fixture
// message bodies, comments, and the typographic "→ v…" upgrade hint (web
// renders the same arrow as text).
//
// Usage: node scripts/patrol/glyph_audit.mjs [--live] [--repo owner/name] [--cap N]
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { runPatrol } from "./lib.mjs";

const args = process.argv.slice(2);
const arg = (n, d) => { const i = args.indexOf(n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const LIVE = args.includes("--live");
const REPO = arg("--repo", "botiverse/mobile");
const CAP = Number(arg("--cap", "5"));

// Roots cover every layer that can put a glyph on screen (artin's "还有啥
// 文件可能有" sweep, 2026-07-15): shared Kotlin, MR string RESOURCES (the
// composer "✓ Added" chips hid there — .kt-only scanning missed them),
// and both host app layers.
const KOTLIN_ROOTS = [
  "compose/shared/src/commonMain/kotlin/build/raft/app",
  "compose/androidApp/src/main/java",
];
const RESOURCE_GLOBS = [
  "compose/shared/src/commonMain/composeResources/values/strings.xml",
  "compose/shared/src/commonMain/composeResources/values-zh-rCN/strings.xml",
  "compose/androidApp/src/main/res/values/strings.xml",
  "compose/androidApp/src/main/res/values-zh-rCN/strings.xml",
];
const HOST_ROOTS = [
  { dir: "compose/iosApp/iosApp", exts: [".m", ".swift"] },
];
const GLYPHS = /[✓✔✗✘✕×➜➔←↑↓▲▼►◄▶◀‣▪✦★☆]/;
const EXCLUDE_FILE = [/Test\.kt$/, /visualFixtures\//];
const EXCLUDE_LINE = [
  /^\s*\/\//,                      // comments
  /"→ v\$/,                        // computers upgrade hint — web renders the same text arrow
  /"•"\.repeat/,                   // masking dots
  /body\s*=/,                      // fixture/sample message bodies
  /activity_updates_(one|many)/,  // localized content prefix, not an icon affordance
];

function* walk(dir, exts) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) yield* walk(p, exts);
    else if (exts.some((x) => e.name.endsWith(x)) && !EXCLUDE_FILE.some((re) => re.test(p))) yield p;
  }
}

const byFile = new Map();
const record = (file, i, line) => {
  if (!byFile.has(file)) byFile.set(file, []);
  byFile.get(file).push({ line: i + 1, text: line.trim() });
};
for (const root of KOTLIN_ROOTS) {
  if (!fs.existsSync(root)) continue;
  for (const file of walk(root, [".kt"])) {
    fs.readFileSync(file, "utf8").split("\n").forEach((line, i) => {
      if (!GLYPHS.test(line)) return;
      if (EXCLUDE_LINE.some((re) => re.test(line))) return;
      // Only flag glyphs inside string/char literals (UI-bound), not identifiers.
      if (!/["'][^"']*[✓✔✗✘✕×➜➔←↑↓▲▼►◄▶◀‣▪✦★☆][^"']*["']/.test(line)) return;
      record(file, i, line);
    });
  }
}
for (const file of RESOURCE_GLOBS) {
  if (!fs.existsSync(file)) continue;
  fs.readFileSync(file, "utf8").split("\n").forEach((line, i) => {
    if (GLYPHS.test(line) && /<string /.test(line) && !EXCLUDE_LINE.some((re) => re.test(line))) record(file, i, line);
  });
}
for (const { dir, exts } of HOST_ROOTS) {
  if (!fs.existsSync(dir)) continue;
  for (const file of walk(dir, exts)) {
    fs.readFileSync(file, "utf8").split("\n").forEach((line, i) => {
      if (GLYPHS.test(line) && !EXCLUDE_LINE.some((re) => re.test(line))) record(file, i, line);
    });
  }
}

const findings = [...byFile.entries()].map(([file, hits]) => {
  const hash = crypto.createHash("sha256").update(hits.map((h) => h.text).join("\n")).digest("hex").slice(0, 16);
  return {
    key: file,
    fingerprint: hash,
    rank: hits.length,
    title: `[glyph-audit] ${path.basename(file)}: ${hits.length} text glyph${hits.length > 1 ? "s" : ""} that should be Lucide icons`,
    body: [
      `**File:** \`${file}\`  ·  **Hits:** ${hits.length}  ·  **Lens:** glyph-audit`,
      "",
      "### Glyph characters in UI strings",
      ...hits.map((h) => `- L${h.line} \`${h.text.slice(0, 160)}\``),
      "",
      "UI affordances use Lucide icons (`RaftLucideIcon`), not unicode glyphs (task #494).",
      "Typographic uses (separators, masking dots, content text) are FPs — close with a note or extend the lens exclusions.",
    ].join("\n"),
    labels: ["code-smell", "auto-triage", "severity:medium"],
  };
});

console.log(`[glyph-audit] files with hits: ${findings.length}`);
const report = runPatrol({ repo: REPO, lens: "glyph-audit", label: "code-smell", findings, cap: CAP, live: LIVE });
console.log(JSON.stringify(report, null, 1));
