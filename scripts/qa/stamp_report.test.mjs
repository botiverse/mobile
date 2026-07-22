import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";

test("pre-claim status freezes the GitHub run and reports the failing phase", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-dispatch-"));
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  fs.writeFileSync(path.join(runnerTemp, "stamp-preclaim-phase"), "fetch_pinned_apk");
  const payloadLog = path.join(root, "payload.json");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
payload=""
while [ "$#" -gt 0 ]; do
  case "$1" in --data-binary) payload="$2"; shift 2;; *) shift;; esac
done
cp "\${payload#@}" "${payloadLog}"
`, { mode: 0o755 });
  const env = {
    ...process.env,
    PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
    RUNNER_TEMP: runnerTemp,
    STAMP_RUN_CASE_ID: "rc-preclaim",
    STAMP_URL: "https://stamp.test",
    STAMP_TOKEN: "test-token",
    GITHUB_RUN_ID: "456",
    GITHUB_REPOSITORY: "botiverse/mobile",
    STAMP_PRECLAIM_ERROR_REASON: "artifact resolver failed",
  };
  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "dispatch-error"], { cwd: root, env });
  assert.deepEqual(JSON.parse(fs.readFileSync(payloadLog, "utf8")), {
    status: "error",
    ci_job_ref: "https://github.com/botiverse/mobile/actions/runs/456",
    phase: "fetch_pinned_apk",
    reason: "artifact resolver failed",
  });
  fs.writeFileSync(path.join(runnerTemp, "stamp-claim-rc-preclaim"), "lease", { mode: 0o600 });
  fs.rmSync(payloadLog);
  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "dispatch-error"], { cwd: root, env });
  assert.equal(fs.existsSync(payloadLog), false);
});

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
  fs.writeFileSync(path.join(evidence, "step-00.annotated.png"), "step zero");
  fs.writeFileSync(path.join(evidence, "step-19.png"), "step nineteen");
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
  const annotated = calls.find((call) => call.includes("kind=screenshot_annotated"));
  const raw = calls.find((call) => call.includes("kind=screenshot&"));
  assert.match(annotated, /[?&]step=0(?:&|\s)/);
  assert.match(raw, /[?&]step=19(?:&|\s)/);
  assert.notEqual(new URL(annotated.match(/https:\/\/\S+/)[0]).searchParams.get("seq"), new URL(raw.match(/https:\/\/\S+/)[0]).searchParams.get("seq"));
  assert.match(calls.at(-1), /\/verdict/);
});

test("login bootstrap keeps session material private and deletes it after terminal", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-bootstrap-login-"));
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  const terminalPayload = path.join(root, "terminal.json");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
out=""; payload=""; url=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2;;
    --data-binary) payload="$2"; shift 2;;
    http*) url="$1"; shift;;
    *) shift;;
  esac
done
case "$url" in
  */claim) printf '%s' '{"run_case_id":"rc-login","claim_token":"lease-token","auth_bootstrap":{"required":true,"contract_version":"qa-bootstrap-login/v1","login_endpoint":"/login","ready_endpoint":"/ready","terminal_endpoint":"/terminal"}}' > "$out";;
  */login) printf '%s' '{"request_id":"request-1","envelope":{"refresh_token":"refresh-secret"},"envelope_digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","expires_at":"soon"}' > "$out";;
  */ready) printf '%s' '{"ok":true}' > "$out";;
  */terminal) cp "\${payload#@}" "${terminalPayload}";;
esac
`, { mode: 0o755 });
  const binding = path.join(root, "binding.json");
  const ready = path.join(root, "ready.json");
  const cleanup = path.join(root, "cleanup.json");
  fs.writeFileSync(binding, JSON.stringify({ signing_cert_sha256: "sha256:cert", boot_generation: "sha256:boot" }));
  fs.writeFileSync(ready, JSON.stringify({ request_id: "request-1", envelope_digest: "sha256:a", runtime_envelope: {} }));
  fs.writeFileSync(cleanup, JSON.stringify({
    application_id: "build.raft.app.alpha", boot_generation: "sha256:boot", request_id: "request-1",
    envelope_digest: "sha256:a", process_instance_id: "p", process_generation: "g",
    force_stopped: true, package_data_cleared: true,
  }));
  const env = {
    ...process.env,
    PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
    RUNNER_TEMP: runnerTemp,
    STAMP_RUN_CASE_ID: "rc-login",
    STAMP_URL: "https://stamp.test",
    STAMP_TOKEN: "test-token",
    GITHUB_RUN_ID: "321",
    GITHUB_REPOSITORY: "botiverse/mobile",
    STAMP_VERIFIED_SOURCE_COMMIT: "source",
    STAMP_VERIFIED_ARTIFACT_SHA256: "sha",
    STAMP_VERIFIED_APP_VERSION: "1",
    STAMP_VERIFIED_VERSION_CODE: "1",
    STAMP_VERIFIED_PACKAGE_ID: "build.raft.app.alpha",
  };
  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "claim"], { cwd: root, env });
  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "bootstrap-login", binding], { cwd: root, env });
  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "bootstrap-ready", ready], { cwd: root, env });
  const output = execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "bootstrap-terminal", "completed", cleanup], {
    cwd: root, env, encoding: "utf8",
  });
  assert.equal(output.includes("refresh-secret"), false);
  assert.equal(JSON.parse(fs.readFileSync(terminalPayload, "utf8")).refresh_token, "refresh-secret");
  assert.equal(fs.existsSync(path.join(runnerTemp, "stamp-bootstrap-login-rc-login.json")), false);
});
