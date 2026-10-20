// patrol/font_audit.mjs — typography contract lens (artin, #proj-uiux 2026-07-15:
// 字体问题也要巡检).
//
// Kuikly serializes FontFamily WITHOUT weight info (see RaftDesignTokens.kt):
// weight must be encoded in the family name and dispatched via
// withRaftFontWeight()/raftFontFamilyForWeight()/raftMonoFamilyForWeight().
// A naked `fontWeight = FontWeight.X` on a TextStyle/SpanStyle changes NOTHING
// on screen unless the style also swaps the family — the "bold that renders
// regular" trap (and the reverse: copy(fontWeight = Normal) on a bold-family
// style keeps rendering bold). The markdown pipeline auto-repairs via
// withResolvedRaftFontFamily(); plain Text() does not.
//
// Patterns:
//   naked-font-weight : `fontWeight = FontWeight.…` on a line with no family
//                       dispatch (heuristic; multi-line expressions triaged in
//                       the issue).
//   raw-font-family   : `FontFamily(` constructed outside RaftDesignTokens.
//
// Usage: node scripts/patrol/font_audit.mjs [--live] [--repo owner/name] [--cap N]
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { runPatrol } from "./lib.mjs";

const args = process.argv.slice(2);
const arg = (n, d) => { const i = args.indexOf(n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const LIVE = args.includes("--live");
const REPO = arg("--repo", "botiverse/mobile");
const CAP = Number(arg("--cap", "5"));

const ROOT = "compose/shared/src/commonMain/kotlin/build/raft/app";
const EXCLUDE_FILE = [/Test\.kt$/, /visualFixtures\//, /RaftDesignTokens\.kt$/];
const FAMILY_DISPATCH = /withRaftFontWeight|raftFontFamilyForWeight|raftMonoFamilyForWeight|raftSpanStyleForWeight|fontFamily\s*=/;
// Multi-line style expressions put the dispatch on its own line — commonly the
// house idiom `fontFamily = raftMonoFamilyForWeight(X), fontWeight = Normal`
// (family carries the weight; Normal suppresses synthetic bolding). Only the
// dispatch HELPERS count here: a plain `fontFamily = RaftMonoFamily` nearby
// does NOT fix the weight and must stay flagged (the #1051 class).
const DISPATCH_HELPERS = /withRaftFontWeight|raftFontFamilyForWeight|raftMonoFamilyForWeight|raftSpanStyleForWeight/;
const WINDOW_BEFORE = 10, WINDOW_AFTER = 3;
const dispatchNearby = (lines, i) => {
  for (let j = Math.max(0, i - WINDOW_BEFORE); j <= Math.min(lines.length - 1, i + WINDOW_AFTER); j++) {
    if (j !== i && DISPATCH_HELPERS.test(lines[j])) return true;
  }
  return false;
};

function* walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) yield* walk(p);
    else if (e.name.endsWith(".kt") && !EXCLUDE_FILE.some((re) => re.test(p))) yield p;
  }
}

const byFile = new Map();
const record = (file, i, text, pattern) => {
  if (!byFile.has(file)) byFile.set(file, []);
  byFile.get(file).push({ line: i + 1, text: text.trim(), pattern });
};

for (const file of walk(ROOT)) {
  const lines = fs.readFileSync(file, "utf8").split("\n");
  lines.forEach((line, i) => {
    if (/^\s*\/\//.test(line)) return;
    if (/fontWeight\s*=\s*FontWeight\./.test(line) && !FAMILY_DISPATCH.test(line) && !dispatchNearby(lines, i)) {
      record(file, i, line, "naked-font-weight");
    }
    if (/\bFontFamily\(/.test(line)) {
      record(file, i, line, "raw-font-family");
    }
  });
}

const findings = [...byFile.entries()].map(([file, hits]) => {
  const hash = crypto.createHash("sha256").update(hits.map((h) => h.text).join("\n")).digest("hex").slice(0, 16);
  return {
    key: file,
    fingerprint: hash,
    rank: hits.length,
    title: `[font-audit] ${path.basename(file)}: ${hits.length} typography contract suspect${hits.length > 1 ? "s" : ""}`,
    body: [
      `**File:** \`${file}\`  ·  **Hits:** ${hits.length}  ·  **Lens:** font-audit`,
      "",
      "### Suspect typography usages",
      ...hits.map((h) => `- L${h.line} _${h.pattern}_ \`${h.text.slice(0, 160)}\``),
      "",
      "Kuikly encodes weight in the FONT FAMILY name — a naked `fontWeight = FontWeight.X` renders the style's original family weight, not X. Use `withRaftFontWeight(...)` / `raftFontFamilyForWeight(...)` (or `raftSpanStyleForWeight` for spans). Raw `FontFamily(...)` belongs in RaftDesignTokens only.",
      "FPs: multi-line expressions that dispatch the family nearby — close with a note.",
    ].join("\n"),
    labels: ["code-smell", "auto-triage", "severity:medium"],
  };
});

console.log(`[font-audit] files with hits: ${findings.length}`);
const report = runPatrol({ repo: REPO, lens: "font-audit", label: "code-smell", findings, cap: CAP, live: LIVE });
console.log(JSON.stringify(report, null, 1));
