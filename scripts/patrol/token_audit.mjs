// patrol/token_audit.mjs — design-token audit lens (task from artin, #对话流专修 2026-07-15).
//
// Finds semantic/brand colors weakened with `.copy(alpha = …)` in shared UI
// code. The trigger bug: the image-annotation toolbar's destructive button
// used `AccentRed.copy(alpha = 0.18f)`, which composited with the brutal
// black shadow into an unreadable brown (fixed in mobile#996) — and a sibling
// existed in RaftFilePreview. Not every alpha wash is a bug (some tints
// deliberately mirror react bg-*/15), so filings go through the standard
// patrol verify stage (3×M3) which closes false positives.
//
// Usage: node scripts/patrol/token_audit.mjs [--live] [--repo owner/name] [--cap N]
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { runPatrol } from "./lib.mjs";

const args = process.argv.slice(2);
const arg = (name, dflt) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt;
};
const LIVE = args.includes("--live");
const REPO = arg("--repo", "botiverse/mobile");
const CAP = Number(arg("--cap", "10"));

const ROOT = "compose/shared/src/commonMain/kotlin/build/raft/app";

// Pattern classes. Each: { id, re, rank, why }
const PATTERNS = [
  {
    id: "semantic-alpha-decay",
    re: /RaftColor\.(Accent[A-Za-z]+|BrandYellow|SoftSignal|StateWarningBase)\.copy\(\s*alpha\s*=/,
    rank: 2,
    why: "Semantic/brand color weakened with alpha: composites unpredictably over shadows/surfaces (the mobile#996 brown-destructive bug class). If the tint is a deliberate react bg-*/N mirror, say so in a comment or move it to a named token.",
  },
  {
    id: "destructive-alpha",
    re: /destructive.*RaftColor\.AccentRed\.copy\(\s*alpha\s*=|RaftColor\.AccentRed\.copy\(\s*alpha\s*=[^)]*\).*destructive/i,
    rank: 3,
    why: "DESTRUCTIVE affordance rendered with a transparent red — exactly the mobile#996 bug. Destructive actions use the opaque Danger/AccentRed contract color.",
  },
];

const EXCLUDE = [/Test\.kt$/, /visualFixtures\//, /DesignTokens\.kt$/];

function* kotlinFiles(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* kotlinFiles(p);
    else if (entry.name.endsWith(".kt") && !EXCLUDE.some((re) => re.test(p))) yield p;
  }
}

// The documented close-out for a deliberate tint is a triage comment on the
// line above (or inline): once a human has judged it a react-parity wash, the
// lens must not re-file it. Only comments count — code content cannot exempt
// itself.
const TRIAGED = /\/\/.*(deliberate|token-audit|react-parity|react mirror|react:)/i;
const isTriaged = (lines, i) =>
  TRIAGED.test(lines[i]) || (i > 0 && TRIAGED.test(lines[i - 1]));

const byFile = new Map();
for (const file of kotlinFiles(ROOT)) {
  const lines = fs.readFileSync(file, "utf8").split("\n");
  lines.forEach((line, i) => {
    for (const pattern of PATTERNS) {
      if (isTriaged(lines, i)) continue;
      if (pattern.re.test(line)) {
        const rel = file;
        if (!byFile.has(rel)) byFile.set(rel, []);
        byFile.get(rel).push({ line: i + 1, text: line.trim(), pattern });
        break; // strongest-first ordering not needed; one hit per line
      }
    }
  });
}

const findings = [...byFile.entries()].map(([file, hits]) => {
  const short = path.basename(file);
  const maxRank = Math.max(...hits.map((h) => h.pattern.rank));
  const snippetHash = crypto.createHash("sha256")
    .update(hits.map((h) => h.text).join("\n")).digest("hex").slice(0, 16);
  const body = [
    `**File:** \`${file}\`  ·  **Hits:** ${hits.length}  ·  **Lens:** token-audit`,
    "",
    "### Suspect token usages",
    ...hits.map((h) => `- L${h.line} \`${h.text.slice(0, 160)}\`\n  - _${h.pattern.id}_: ${h.pattern.why}`),
    "",
    "### Triage guidance",
    "- Deliberate react `bg-*/N` tint mirrors are FINE — comment them or promote to a named token, then close.",
    "- Destructive/danger affordances must use the opaque contract color (see mobile#996).",
    "- Verify stage (3×M3) will close false positives; fixes follow the usual fix→capture-verify→merge loop.",
  ].join("\n");
  return {
    key: file,
    fingerprint: snippetHash,
    rank: maxRank * 100 + hits.length,
    title: `[token-audit] ${short}: ${hits.length} semantic-color alpha usage${hits.length > 1 ? "s" : ""} to triage`,
    body,
    labels: ["code-smell", "auto-triage", maxRank >= 3 ? "severity:high" : "severity:medium"],
  };
});

console.log(`[token-audit] files with hits: ${findings.length}`);
const report = runPatrol({ repo: REPO, lens: "token-audit", label: "code-smell", findings, cap: CAP, live: LIVE });
console.log(JSON.stringify(report, null, 1));
