#!/usr/bin/env node
// qa/check_require_markers — generic logcat required-marker coverage gate.
//
// A visual M3 agent can "pass" a case without ever exercising the code seam
// it claims to verify (case-18 false-positive, run 29548403992). This generic
// gate is the workflow-level companion of the case-specific checkers (e.g.
// check_scroll_to_top.mjs): the case declares the production markers that MUST
// appear in logcat (with occurrence bounds), and any violation overrides the
// agent's verdict to coverage-invalid / no-verdict — never a product FAIL.
//
// Input format (comma-separated, one entry per marker):
//   marker|min|max     e.g. "thread_header_scroll_to_top|1|,slock_perf_launch|2|5"
//   min: minimum occurrences (default 1); max: maximum (empty = unbounded)
//
// Exit: 0 = all markers within bounds (gate PASS)
//       3 = any marker missing or out of bounds (coverage INVALID / no-verdict)
//       2 = usage error

import fs from "node:fs";

const arg = (f, d) => { const i = process.argv.indexOf(f); return i >= 0 ? process.argv[i + 1] : d; };
const logPath = arg("--log", "");
const markersArg = arg("--markers", "");
if (!logPath || !fs.existsSync(logPath) || !markersArg) {
  console.error("usage: check_require_markers.mjs --log <logcat.txt> --markers 'marker|min|max[,marker|min|max...]'");
  process.exit(2);
}

const entries = markersArg.split(",").map((s) => s.trim()).filter(Boolean).map((e) => {
  const [marker, minS, maxS] = e.split("|");
  const min = minS === undefined || minS === "" ? 1 : Number(minS);
  const max = maxS === undefined || maxS === "" ? null : Number(maxS);
  if (!marker || Number.isNaN(min) || (max !== null && Number.isNaN(max))) {
    console.error(`bad marker entry: "${e}" (want marker|min|max)`);
    process.exit(2);
  }
  return { marker, min, max };
});

const lines = fs.readFileSync(logPath, "utf8").split("\n");
const results = entries.map(({ marker, min, max }) => {
  const hitLines = [];
  lines.forEach((line, i) => { if (line.includes(marker)) hitLines.push(i + 1); });
  const count = hitLines.length;
  const ok = count >= min && (max === null || count <= max);
  return {
    marker, min, max, count, ok,
    // keep evidence small but sufficient for a reviewer to spot-check
    firstLines: hitLines.slice(0, 10),
    reason: ok ? "within bounds"
      : count < min ? `required marker absent or too rare (${count} < min ${min}) — command seam not exercised`
      : `marker count ${count} exceeds max ${max}`,
  };
});

const failed = results.filter((r) => !r.ok);
const report = {
  gate: "require_markers",
  markers: results,
  allWithinBounds: failed.length === 0,
  verdict: failed.length === 0 ? "pass" : "invalid",
  note: "invalid = coverage/no-verdict (NOT a product FAIL) — the case did not exercise the required seam",
};
console.log(JSON.stringify(report, null, 2));
process.exit(failed.length === 0 ? 0 : 3);
