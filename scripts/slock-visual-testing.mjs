#!/usr/bin/env node
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const command = process.argv[2] || "";
const hasKmpTokenPath =
  process.argv.some((arg) => arg === "--kmp-token-path" || arg.startsWith("--kmp-token-path="));

if (command === "token-audit" && !hasKmpTokenPath) {
  process.argv.push(
    "--kmp-token-path",
    "compose/shared/src/commonMain/kotlin/build/raft/app/RaftDesignTokens.kt",
  );
}

const repoRoot = path.resolve(import.meta.dirname, "..");
const visualTestingCli = resolveVisualTestingCli(repoRoot);
if (!process.env.SLOCK_VISUAL_REACT_REPO_ROOT) {
  process.env.SLOCK_VISUAL_REACT_REPO_ROOT = inferSourceRepoRoot(visualTestingCli);
}

await import(pathToFileURL(visualTestingCli).href);

function resolveVisualTestingCli(root) {
  const explicitCli = process.env.SLOCK_VISUAL_TESTING_CLI;
  if (explicitCli) {
    const resolved = path.resolve(explicitCli);
    if (fs.existsSync(resolved)) return resolved;
    fail(`SLOCK_VISUAL_TESTING_CLI does not exist: ${resolved}`);
  }

  for (const sourceRoot of sourceRepoCandidates(root)) {
    const cli = path.join(sourceRoot, "packages", "visual-testing", "src", "cli.mjs");
    if (fs.existsSync(cli)) return cli;
  }

  fail(
    [
      "Cannot find the Slock visual-testing CLI.",
      "Set SLOCK_SOURCE_REPO or SLOCK_VISUAL_TESTING_CLI to an external React/source checkout.",
      "Do not create a retired in-repo Slock source directory inside the Android repo; it will show up as untracked local state.",
    ].join("\n"),
  );
}

function sourceRepoCandidates(root) {
  const home = os.homedir();
  return [
    process.env.SLOCK_SOURCE_REPO,
    process.env.SLOCK_REACT_REPO_DIR,
    process.env.SLOCK_VISUAL_REACT_REPO_ROOT,
    process.env.SLOCK_VISUAL_TESTING_REPO,
    path.join(home, "0Workspace", "github.com", "botiverse", "slock"),
    path.resolve(root, "..", "slock-source"),
    path.resolve(root, "..", "slock"),
    path.resolve(root, "..", "slock-web"),
  ].filter(Boolean).map((candidate) => path.resolve(candidate));
}

function inferSourceRepoRoot(cliPath) {
  return path.resolve(path.dirname(cliPath), "..", "..", "..");
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
