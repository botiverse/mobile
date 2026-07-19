import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";

test("claim payload uses frozen artifact identity overrides", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-claim-"));
  const evidence = path.join(root, "qa-evidence");
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(evidence);
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  const payloadLog = path.join(root, "payload.json");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
out=""; payload=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2;;
    --data-binary) payload="$2"; shift 2;;
    *) shift;;
  esac
done
cp "\${payload#@}" "${payloadLog}"
printf '{"claim_token":"lease-token"}' > "$out"
`, { mode: 0o755 });

  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "claim", "qa-evidence"], {
    cwd: root,
    env: {
      ...process.env,
      PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
      RUNNER_TEMP: runnerTemp,
      STAMP_RUN_CASE_ID: "rc-frozen",
      STAMP_URL: "https://stamp.test",
      STAMP_TOKEN: "test-token",
      GITHUB_SHA: "workflow-head",
      GITHUB_RUN_ID: "123",
      GITHUB_REPOSITORY: "botiverse/mobile",
      STAMP_VERIFIED_SOURCE_COMMIT: "frozen-source",
      STAMP_VERIFIED_ARTIFACT_SHA256: "frozen-sha",
      STAMP_VERIFIED_APP_VERSION: "1.0",
      STAMP_VERIFIED_VERSION_CODE: "1",
      STAMP_VERIFIED_PACKAGE_ID: "build.raft.app",
    },
  });
  assert.deepEqual(JSON.parse(fs.readFileSync(payloadLog, "utf8")), {
    ci_job_ref: "https://github.com/botiverse/mobile/actions/runs/123",
    source_commit: "frozen-source",
    apk_sha256: "frozen-sha",
    app_version: "1.0",
    version_code: 1,
    package_id: "build.raft.app",
  });
  assert.equal(fs.readFileSync(path.join(runnerTemp, "stamp-claim-rc-frozen"), "utf8"), "lease-token");
});

test("report reads a relative verdict path and freezes scope before writes", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-report-"));
  const evidence = path.join(root, "qa-evidence");
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(evidence);
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  fs.writeFileSync(path.join(evidence, "logcat.txt"), "app log\n");
  fs.writeFileSync(path.join(evidence, "verdict.json"), JSON.stringify({
    verdict: "pass",
    log_scope: { filtered: true, package_id: "build.raft.app", pids: [1234], generation: "99:1" },
  }));
  fs.writeFileSync(path.join(runnerTemp, "stamp-claim-rc1"), "lease-token", { mode: 0o600 });
  const curlLog = path.join(root, "curl.log");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash\nprintf '%s\\n' "$*" >> "${curlLog}"\nexit 0\n`, { mode: 0o755 });

  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "report", "qa-evidence"], {
    cwd: root,
    env: {
      ...process.env,
      PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
      RUNNER_TEMP: runnerTemp,
      STAMP_RUN_CASE_ID: "rc1",
      STAMP_URL: "https://stamp.test",
      STAMP_TOKEN: "test-token",
      GITHUB_RUN_ID: "99",
      GITHUB_REPOSITORY: "botiverse/mobile",
    },
  });
  const calls = fs.readFileSync(curlLog, "utf8").trim().split("\n");
  assert.match(calls[0], /execution-scope/);
  assert.match(calls[0], /X-Stamp-Claim-Token: lease-token/);
  assert(calls.some((call) => call.includes("/evidence?kind=logcat")));
  assert.match(calls.at(-1), /\/verdict/);
});
