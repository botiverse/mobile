#!/usr/bin/env node
import fs from "node:fs";

const argv = process.argv.slice(2);
const arg = (name, fallback = "") => {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : fallback;
};

const input = arg("--input");
const output = arg("--output");
const verdictPath = arg("--verdict");
const pidPath = arg("--pids");
const packageId = arg("--package");
const generation = arg("--generation");
const format = arg("--format", "android-threadtime");
const startedAt = arg("--started-at");
const alreadyFiltered = arg("--already-filtered", "false") === "true";
if (!input || !output || !verdictPath || !pidPath || !packageId || !generation) {
  console.error("usage: scope_logcat.mjs --input full.txt --output logcat.txt --verdict verdict.json --pids app-pids.txt --package id --generation run:attempt");
  process.exit(2);
}

const pids = [...new Set((fs.existsSync(pidPath) ? fs.readFileSync(pidPath, "utf8") : "")
  .split(/\s+/)
  .map(Number)
  .filter((pid) => Number.isInteger(pid) && pid > 0))];
const lines = fs.existsSync(input) ? fs.readFileSync(input, "utf8").split("\n") : [];
const allowed = new Set(pids);
const startedMs = startedAt ? Date.parse(startedAt) : Number.NaN;
const scoped = lines.filter((line) => {
  if (!line) return false;
  if (format === "ios-ndjson") {
    try {
      const entry = JSON.parse(line);
      const pid = Number(entry.processID ?? entry.processIdentifier ?? entry.pid);
      const timestamp = Date.parse(String(entry.timestamp ?? entry.date ?? ""));
      return allowed.has(pid) && Number.isFinite(startedMs) && Number.isFinite(timestamp) && timestamp >= startedMs;
    } catch { return false; }
  }
  if (alreadyFiltered) return true;
  const match = /^\S+\s+\S+\s+(\d+)\s+\d+\s/.exec(line);
  return Boolean(match && allowed.has(Number(match[1])));
});
fs.writeFileSync(output, scoped.length ? `${scoped.join("\n")}\n` : "");

if (fs.existsSync(verdictPath)) {
  const verdict = JSON.parse(fs.readFileSync(verdictPath, "utf8"));
  verdict.log_scope = {
    filtered: pids.length > 0,
    pid: pids[0] ?? null,
    pids,
    package_id: packageId,
    generation,
  };
  fs.writeFileSync(verdictPath, `${JSON.stringify(verdict, null, 2)}\n`);
}

console.log(JSON.stringify({ package_id: packageId, pids, format, started_at: startedAt || null, already_filtered: alreadyFiltered, input_lines: lines.length, scoped_lines: scoped.length }));
