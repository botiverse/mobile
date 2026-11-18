#!/usr/bin/env node
// QA-only (qa/4984-foldable): before the evidence gate, mask the QA credentials in the foldable
// driver's evidence instead of letting one UI dump withhold everything. Text files get the value
// replaced by ***; a binary file that contains it is deleted. Only file names are recorded.
// The gate (secure_evidence.mjs) still runs unchanged afterwards.
import fs from "node:fs";
import path from "node:path";
const dir = process.argv[2] || "qa-evidence/foldable"; // or the whole qa-evidence dir
const secrets = [process.env.QA_TEST_PASSWORD, process.env.QA_TEST_EMAIL].filter((v) => v && v.length >= 4);
const TEXT = /\.(txt|log|json|jsonl|xml|md|csv)$/i;
const hits = [];
const walk = (d) => (fs.existsSync(d) ? fs.readdirSync(d, { withFileTypes: true }) : []).flatMap((e) => (e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]));
for (const f of walk(dir)) {
  const bytes = fs.readFileSync(f);
  if (!secrets.some((s) => bytes.includes(s))) continue;
  if (TEXT.test(f)) { let t = bytes.toString("utf8"); for (const s of secrets) t = t.split(s).join("***"); fs.writeFileSync(f, t); hits.push(`masked ${path.basename(f)}`); }
  else { fs.rmSync(f); hits.push(`deleted ${path.basename(f)}`); }
}
fs.mkdirSync(dir, { recursive: true });
fs.writeFileSync(path.join(dir, "redactions.txt"), hits.join("\n") + "\n");
console.log(`foldable evidence: ${hits.length} file(s) masked/deleted`);
