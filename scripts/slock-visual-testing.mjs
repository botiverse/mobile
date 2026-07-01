#!/usr/bin/env node
const command = process.argv[2] || "";
const hasKmpTokenPath =
  process.argv.some((arg) => arg === "--kmp-token-path" || arg.startsWith("--kmp-token-path="));

if (command === "token-audit" && !hasKmpTokenPath) {
  process.argv.push(
    "--kmp-token-path",
    "compose/shared/src/commonMain/kotlin/build/raft/app/SlockDesignTokens.kt",
  );
}

await import("../third_party/slock-source/packages/visual-testing/src/cli.mjs");
