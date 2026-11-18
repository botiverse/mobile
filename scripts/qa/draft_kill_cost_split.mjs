#!/usr/bin/env node
// The draft-kill journal write cost split, from an evidence directory's timing-lines.txt alone:
//   node scripts/qa/draft_kill_cost_split.mjs <timing-lines.txt> [--slow-us 1000] [--list 50]
// Prints the JSON report (method: costSplitReport in draft_kill_timing_analysis.mjs). The emulator run
// writes the same report as cost-split.json next to timing-lines.txt.
import fs from "node:fs";
import { costSplitReport } from "./draft_kill_timing_analysis.mjs";

const [file, ...rest] = process.argv.slice(2);
if (!file) {
  console.error("usage: draft_kill_cost_split.mjs <timing-lines.txt> [--slow-us N] [--list N]");
  process.exit(2);
}
const opt = (name, fallback) => {
  const i = rest.indexOf(name);
  return i >= 0 ? Number(rest[i + 1]) : fallback;
};
const lines = fs.readFileSync(file, "utf8").split("\n");
console.log(JSON.stringify(costSplitReport(lines, { slowUs: opt("--slow-us", 1000), listSlowest: opt("--list", 50) }), null, 1));
