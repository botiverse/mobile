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
if (!input || !output || !verdictPath || !pidPath || !packageId) {
  console.error("usage: scope_logcat.mjs --input full.txt --output logcat.txt --verdict verdict.json --pids app-pids.txt --package id");
  process.exit(2);
}

const pids = [...new Set((fs.existsSync(pidPath) ? fs.readFileSync(pidPath, "utf8") : "")
  .split(/\s+/)
  .map(Number)
  .filter((pid) => Number.isInteger(pid) && pid > 0))];
const lines = fs.existsSync(input) ? fs.readFileSync(input, "utf8").split("\n") : [];
const allowed = new Set(pids);
const scoped = lines.filter((line) => {
  const match = /^\S+\s+\S+\s+(\d+)\s+\d+\s/.exec(line);
  return match && allowed.has(Number(match[1]));
});
fs.writeFileSync(output, scoped.length ? `${scoped.join("\n")}\n` : "");

if (fs.existsSync(verdictPath)) {
  const verdict = JSON.parse(fs.readFileSync(verdictPath, "utf8"));
  verdict.log_scope = {
    filtered: pids.length > 0,
    pid: pids[0] ?? null,
    pids,
    package_id: packageId,
  };
  fs.writeFileSync(verdictPath, `${JSON.stringify(verdict, null, 2)}\n`);
}

console.log(JSON.stringify({ package_id: packageId, pids, input_lines: lines.length, scoped_lines: scoped.length }));
