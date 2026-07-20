#!/usr/bin/env node
import fs from "node:fs";

const argv = process.argv.slice(2);
const arg = (name, fallback = "") => {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : fallback;
};

const input = arg("--input");
const output = arg("--output");
const startedAt = arg("--started-at");
if (!input || !output || !startedAt) {
  console.error("usage: derive_ios_log_pids.mjs --input logcat-iosApp.txt --output app-pids.txt --started-at <timestamp>");
  process.exit(2);
}

const startedMs = Date.parse(startedAt);
if (!Number.isFinite(startedMs)) {
  console.error(`invalid --started-at timestamp: ${startedAt}`);
  process.exit(2);
}

const lines = fs.existsSync(input) ? fs.readFileSync(input, "utf8").split("\n") : [];
const pids = new Set();
let eligibleLines = 0;
for (const line of lines) {
  if (!line) continue;
  try {
    const entry = JSON.parse(line);
    const timestamp = Date.parse(String(entry.timestamp ?? entry.date ?? ""));
    if (!Number.isFinite(timestamp) || timestamp < startedMs) continue;
    eligibleLines++;
    const pid = Number(entry.processID ?? entry.processIdentifier ?? entry.pid);
    if (Number.isInteger(pid) && pid > 0) pids.add(pid);
  } catch {
    // The unified log can contain an incomplete tail line. It is ineligible,
    // never a source of execution identity.
  }
}

const sortedPids = [...pids].sort((a, b) => a - b);
fs.writeFileSync(output, sortedPids.length ? `${sortedPids.join("\n")}\n` : "");
console.log(JSON.stringify({
  started_at: startedAt,
  input_lines: lines.filter(Boolean).length,
  eligible_lines: eligibleLines,
  pids: sortedPids,
}));

if (!sortedPids.length) {
  console.error("no positive iOS app PID found in the run-start-bounded NDJSON log");
  process.exit(1);
}
