import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

// Runs the real failure diagnosis entry point in a child process. The child gets a minimal,
// hand-built environment (never process.env, which may carry real CI secrets) and a preloaded
// fetch stub that records every call and never touches the network.

const agent = fileURLToPath(new URL("./failure_diagnosis_agent.mjs", import.meta.url));
const DUMMY_STAMP_TOKEN = "dummy-stamp-token-not-a-secret";
const DUMMY_REVIEW_TOKEN = "dummy-review-token-not-a-secret";

function runAgent(extraEnv) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "failure-diagnosis-agent-test-"));
  const fetchLog = path.join(dir, "fetch.jsonl");
  const stub = path.join(dir, "fetch-stub.mjs");
  fs.writeFileSync(stub, `
import fs from "node:fs";
globalThis.fetch = async (url, init = {}) => {
  fs.appendFileSync(${JSON.stringify(fetchLog)}, JSON.stringify({ url: String(url), headers: init.headers || {} }) + "\\n");
  throw new Error("network is stubbed in this test");
};
`);
  const out = path.join(dir, "diagnosis.json");
  const result = spawnSync(process.execPath, ["--import", stub, agent, "--out", out], {
    cwd: dir,
    encoding: "utf8",
    timeout: 30_000,
    env: {
      PATH: process.env.PATH,
      HOME: dir,
      STAMP_URL: "http://127.0.0.1:9",
      STAMP_RUN_CASE_ID: "rc-test",
      MINIMAX_API_KEY: "dummy-model-key-not-a-secret",
      GITHUB_SERVER_URL: "https://github.example",
      GITHUB_REPOSITORY: "botiverse/mobile",
      GITHUB_RUN_ID: "1",
      STAMP_TOKEN: DUMMY_STAMP_TOKEN,
      ...extraEnv,
    },
  });
  const calls = fs.existsSync(fetchLog)
    ? fs.readFileSync(fetchLog, "utf8").trim().split("\n").map((line) => JSON.parse(line))
    : [];
  const wroteOut = fs.existsSync(out);
  fs.rmSync(dir, { recursive: true, force: true });
  return { ...result, calls, wroteOut };
}

test("refuses to run with only STAMP_TOKEN and makes no network call", () => {
  const result = runAgent({});
  assert.notEqual(result.status, 0, `agent must refuse without STAMP_REVIEW_TOKEN\n${result.stderr}`);
  assert.match(result.stderr, /dedicated STAMP_REVIEW_TOKEN/);
  assert.deepEqual(result.calls, [], "no request may be sent before the token check");
  assert.equal(result.wroteOut, false);
});

test("authenticates to Stamp with STAMP_REVIEW_TOKEN, never STAMP_TOKEN", () => {
  // Positive control: same environment plus the review token. The guard passes and the first
  // request reaches the (stubbed) network, so the refusal above is the token check, not a broken
  // harness.
  const result = runAgent({ STAMP_REVIEW_TOKEN: DUMMY_REVIEW_TOKEN });
  assert.equal(result.calls.length, 1, result.stderr);
  assert.equal(result.calls[0].url, "http://127.0.0.1:9/api/diagnoses/rc-test/context");
  assert.equal(result.calls[0].headers.authorization, `Bearer ${DUMMY_REVIEW_TOKEN}`);
  assert.ok(!JSON.stringify(result.calls).includes(DUMMY_STAMP_TOKEN));
  assert.notEqual(result.status, 0);
});
