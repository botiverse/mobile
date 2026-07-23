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

test("claim automatically freezes the actual CI login email without exposing password", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-credential-"));
  const evidence = path.join(root, "qa-evidence");
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(evidence);
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  const count = path.join(root, "count");
  fs.writeFileSync(count, "0");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
out=""; payload=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2;;
    --data-binary) payload="$2"; shift 2;;
    *) shift;;
  esac
done
n=$(( $(cat "${count}") + 1 )); echo "$n" > "${count}"
cp "\${payload#@}" "${root}/payload-$n.json"
if [ -n "$out" ]; then printf '{"claim_token":"lease-token"}' > "$out"; fi
`, { mode: 0o755 });

  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "claim", "qa-evidence"], {
    cwd: root,
    env: {
      ...process.env,
      PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
      RUNNER_TEMP: runnerTemp,
      STAMP_RUN_CASE_ID: "rc-email",
      STAMP_URL: "https://stamp.test",
      STAMP_TOKEN: "test-token",
      GITHUB_SHA: "source",
      GITHUB_RUN_ID: "123",
      GITHUB_REPOSITORY: "botiverse/mobile",
      STAMP_VERIFIED_SOURCE_COMMIT: "source",
      STAMP_VERIFIED_ARTIFACT_SHA256: "sha",
      STAMP_VERIFIED_APP_VERSION: "1.0",
      STAMP_VERIFIED_VERSION_CODE: "1",
      STAMP_VERIFIED_PACKAGE_ID: "build.raft.app",
      QA_TEST_EMAIL: "qa@example.test",
      QA_TEST_PASSWORD: "must-not-appear",
    },
  });
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root, "payload-2.json"), "utf8")), {
    login_email: "qa@example.test",
    stable_subject: null,
  });
  assert.equal(fs.readFileSync(path.join(root, "payload-2.json"), "utf8").includes("must-not-appear"), false);
});

test("credential receipt failure preserves the claim token for workflow-error cleanup", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-credential-fail-"));
  const evidence = path.join(root, "qa-evidence");
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(evidence);
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  const count = path.join(root, "count");
  fs.writeFileSync(count, "0");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
out=""
while [ "$#" -gt 0 ]; do case "$1" in -o) out="$2"; shift 2;; *) shift;; esac; done
n=$(( $(cat "${count}") + 1 )); echo "$n" > "${count}"
if [ "$n" -eq 1 ]; then printf '{"claim_token":"lease-token"}' > "$out"; exit 0; fi
exit 22
`, { mode: 0o755 });
  assert.throws(() => execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "claim", "qa-evidence"], {
    cwd: root,
    env: {
      ...process.env,
      PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
      RUNNER_TEMP: runnerTemp,
      STAMP_RUN_CASE_ID: "rc-email-fail",
      STAMP_URL: "https://stamp.test",
      STAMP_TOKEN: "test-token",
      GITHUB_SHA: "source",
      GITHUB_RUN_ID: "123",
      GITHUB_REPOSITORY: "botiverse/mobile",
      STAMP_VERIFIED_SOURCE_COMMIT: "source",
      STAMP_VERIFIED_ARTIFACT_SHA256: "sha",
      STAMP_VERIFIED_APP_VERSION: "1.0",
      STAMP_VERIFIED_VERSION_CODE: "1",
      STAMP_VERIFIED_PACKAGE_ID: "build.raft.app",
      QA_TEST_EMAIL: "qa@example.test",
    },
    stdio: "pipe",
  }));
  assert.equal(fs.readFileSync(path.join(runnerTemp, "stamp-claim-rc-email-fail"), "utf8"), "lease-token");
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

test("evidence reporting binds one app-scoped stable principal to the frozen email", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-subject-"));
  const evidence = path.join(root, "qa-evidence");
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(evidence);
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  fs.writeFileSync(path.join(evidence, "logcat.txt"), "authority ready principalId=user-123\n");
  fs.writeFileSync(path.join(evidence, "verdict.json"), JSON.stringify({
    verdict: "harness",
    reason: "fixture setup",
    log_scope: { filtered: true, package_id: "build.raft.app", pids: [1234], generation: "99:1" },
  }));
  fs.writeFileSync(path.join(runnerTemp, "stamp-claim-rc-subject"), "lease-token", { mode: 0o600 });
  const receipt = path.join(root, "receipt.json");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
args="$*"; payload=""
while [ "$#" -gt 0 ]; do case "$1" in --data-binary) payload="$2"; shift 2;; *) shift;; esac; done
if [[ "$args" == *credential-receipt* ]]; then cp "\${payload#@}" "${receipt}"; fi
exit 0
`, { mode: 0o755 });
  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "report-evidence", "qa-evidence"], {
    cwd: root,
    env: {
      ...process.env,
      PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
      RUNNER_TEMP: runnerTemp,
      STAMP_RUN_CASE_ID: "rc-subject",
      STAMP_URL: "https://stamp.test",
      STAMP_TOKEN: "test-token",
      GITHUB_RUN_ID: "99",
      GITHUB_REPOSITORY: "botiverse/mobile",
      QA_TEST_EMAIL: "qa@example.test",
      QA_TEST_PASSWORD: "must-not-appear",
    },
  });
  assert.deepEqual(JSON.parse(fs.readFileSync(receipt, "utf8")), {
    login_email: "qa@example.test",
    stable_subject: "user-123",
  });
});

test("evidence reporting ignores forged model and broad-system subjects and binds only after scope freeze", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-subject-forgery-"));
  const evidence = path.join(root, "qa-evidence");
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(evidence);
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  fs.writeFileSync(path.join(evidence, "logcat.txt"), "scoped product log without identity\n");
  fs.writeFileSync(path.join(evidence, "system-log-raft.txt"), "foreign process principalId=foreign-user\n");
  fs.writeFileSync(path.join(evidence, "session.jsonl"), '{"assistant":"principalId=forged-user"}\n');
  fs.writeFileSync(path.join(evidence, "verdict.json"), JSON.stringify({
    verdict: "harness",
    reason: "fixture setup",
    log_scope: { filtered: true, package_id: "build.raft.app", pids: [1234], generation: "99:1" },
  }));
  fs.writeFileSync(path.join(runnerTemp, "stamp-claim-rc-forgery"), "lease-token", { mode: 0o600 });
  const calls = path.join(root, "calls");
  const receipt = path.join(root, "receipt.json");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
args="$*"; payload=""
while [ "$#" -gt 0 ]; do case "$1" in --data-binary) payload="$2"; shift 2;; *) shift;; esac; done
printf '%s\n' "$args" >> "${calls}"
if [[ "$args" == *credential-receipt* ]]; then cp "\${payload#@}" "${receipt}"; fi
exit 0
`, { mode: 0o755 });
  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "report-evidence", "qa-evidence"], {
    cwd: root,
    env: {
      ...process.env,
      PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
      RUNNER_TEMP: runnerTemp,
      STAMP_RUN_CASE_ID: "rc-forgery",
      STAMP_URL: "https://stamp.test",
      STAMP_TOKEN: "test-token",
      GITHUB_RUN_ID: "99",
      GITHUB_REPOSITORY: "botiverse/mobile",
      QA_TEST_EMAIL: "qa@example.test",
    },
  });
  assert.deepEqual(JSON.parse(fs.readFileSync(receipt, "utf8")), {
    login_email: "qa@example.test",
    stable_subject: null,
  });
  const orderedCalls = fs.readFileSync(calls, "utf8").trim().split("\n");
  assert.ok(orderedCalls.findIndex((line) => line.includes("execution-scope")) < orderedCalls.findIndex((line) => line.includes("credential-receipt")));
});

test("iOS workflow terminalizes any post-claim failure and Qwen panel is the default", () => {
  const ios = fs.readFileSync(new URL("../../.github/workflows/qa-acceptance-ios.yml", import.meta.url), "utf8");
  assert.match(ios, /Close claimed Stamp attempt after workflow failure/);
  assert.match(ios, /failure\(\).*inputs\.stamp_run_case_id != ''/);
  assert.match(ios, /stamp_report\.sh workflow-error qa-evidence/);
  const panel = fs.readFileSync(new URL("../../.github/workflows/qa-review-panel.yml", import.meta.url), "utf8");
  assert.match(panel, /@earendil-works\/pi-ai@0\.81\.1/);
  assert.match(panel, /PI_PROVIDER:.*qwen-token-plan-cn/);
  assert.match(panel, /PI_MODEL:.*qwen3\.8-max-preview/);
  assert.match(panel, /QWEN_TOKEN_PLAN_CN_API_KEY/);
  assert.match(panel, /max-parallel: 1/);
  assert.match(panel, /timeout-minutes: 30/);
  assert.doesNotMatch(panel, /m3-review-/);
  const reviewer = fs.readFileSync(new URL("./review_agent.mjs", import.meta.url), "utf8");
  assert.match(reviewer, /attempt <= 2/);
  assert.match(reviewer, /timeoutMs: 600000/);
  assert.match(reviewer, /reasoningEffort: "low"/);
  assert.match(reviewer, /for \(const ev of reviewedEvidence\) await bytesFor\(ev\)/);
  const diagnosisAgent = fs.readFileSync(new URL("./failure_diagnosis_agent.mjs", import.meta.url), "utf8");
  assert.match(diagnosisAgent, /reasoningEffort: "low"/);
  const diagnosis = fs.readFileSync(new URL("../../.github/workflows/qa-failure-diagnosis.yml", import.meta.url), "utf8");
  assert.match(diagnosis, /@earendil-works\/pi-ai@0\.81\.1/);
  assert.match(diagnosis, /PI_PROVIDER:.*qwen-token-plan-cn/);
  assert.match(diagnosis, /PI_MODEL:.*qwen3\.8-max-preview/);
  assert.match(diagnosis, /QWEN_TOKEN_PLAN_CN_API_KEY/);
});

test("bootstrap reporting enforces ready evidence, torn-down verdict, and no post-terminal evidence retry", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-bootstrap-order-"));
  const evidence = path.join(root, "qa-evidence");
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(evidence);
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  fs.writeFileSync(path.join(evidence, "logcat.txt"), "app log\n");
  fs.writeFileSync(path.join(evidence, "step-01.png"), "screen");
  fs.writeFileSync(path.join(evidence, "verdict.json"), JSON.stringify({
    verdict: "pass",
    log_scope: { filtered: true, package_id: "build.raft.app", pids: [1234], generation: "500:1" },
  }));
  fs.writeFileSync(path.join(runnerTemp, "stamp-claim-rc-order"), "lease-token", { mode: 0o600 });
  fs.writeFileSync(path.join(runnerTemp, "stamp-bootstrap-spec-rc-order.json"), JSON.stringify({
    required: true, terminal_endpoint: "/terminal", capture_proof_endpoint: "/capture-proof",
  }), { mode: 0o600 });
  fs.writeFileSync(path.join(runnerTemp, "stamp-bootstrap-login-rc-order.json"), JSON.stringify({
    request_id: "request-1", envelope_digest: "sha256:digest", envelope: { refresh_token: "refresh-secret" },
  }), { mode: 0o600 });
  fs.writeFileSync(path.join(runnerTemp, "stamp-bootstrap-capture-context-rc-order.json"), JSON.stringify({
    capture_window_nonce: "nonce-1", scope_generation: "generation-1", capture_proof_endpoint: "/capture-proof",
  }), { mode: 0o600 });
  const cleanup = path.join(root, "cleanup.json");
  fs.writeFileSync(cleanup, JSON.stringify({ force_stopped: true, package_data_cleared: true }));
  const state = path.join(root, "state");
  const calls = path.join(root, "calls");
  fs.writeFileSync(state, "ready");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
url=""; out=""; payload=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2;;
    --data-binary) payload="$2"; shift 2;;
    http*) url="$1"; shift;;
    *) shift;;
  esac
done
current=$(cat "${state}")
case "$url" in
  */execution-scope) [ "$current" = ready ] || exit 22; printf '%s\n' scope >> "${calls}";;
  */capture-proof) [ "$current" = ready ] || exit 22; read -r nonce generation sha < <(node -e 'const v=require(process.argv[1].slice(1));process.stdout.write([v.capture_window_nonce,v.scope_generation,v.artifact_sha256].join(" "))' "$payload"); [ "$nonce" = nonce-1 ] && [ "$generation" = generation-1 ] || exit 22; printf '{"capture_proof_id":"proof-%s","capture_upload_identity":"upload-%s","artifact_sha256":"%s"}' "$sha" "$sha" "$sha" > "$out"; printf '%s\n' proof >> "${calls}";;
  */evidence*) [ "$current" = ready ] || exit 22; [[ "$url" == *"capture_proof_id=proof-"* && "$url" == *"capture_upload_identity=upload-"* ]] || exit 22; printf '%s' evidence > "${root}/evidence-seen"; printf '%s\n' evidence >> "${calls}";;
  */terminal) [ "$current" = ready ] && [ -f "${root}/evidence-seen" ] || exit 22; printf '%s' torn_down > "${state}"; printf '%s\n' terminal >> "${calls}";;
  */verdict) [ "$current" = torn_down ] || exit 22; printf '%s\n' verdict >> "${calls}";;
  *) exit 23;;
esac
`, { mode: 0o755 });
  const env = {
    ...process.env,
    PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
    RUNNER_TEMP: runnerTemp,
    STAMP_RUN_CASE_ID: "rc-order",
    STAMP_URL: "https://stamp.test",
    STAMP_TOKEN: "test-token",
    GITHUB_RUN_ID: "500",
    GITHUB_REPOSITORY: "botiverse/mobile",
  };
  const reporter = new URL("./stamp_report.sh", import.meta.url).pathname;

  execFileSync("bash", [reporter, "report-evidence", "qa-evidence"], { cwd: root, env });
  assert.throws(() => execFileSync("bash", [reporter, "report-verdict", "qa-evidence", "0"], { cwd: root, env }));
  execFileSync("bash", [reporter, "bootstrap-terminal", "completed", cleanup], { cwd: root, env });
  assert.throws(() => execFileSync("bash", [reporter, "report-evidence", "qa-evidence"], { cwd: root, env }));
  execFileSync("bash", [reporter, "report-verdict", "qa-evidence", "0"], { cwd: root, env });

  assert.equal(fs.readFileSync(state, "utf8"), "torn_down");
  assert.deepEqual(fs.readFileSync(calls, "utf8").trim().split("\n"), ["scope", "proof", "evidence", "proof", "evidence", "terminal", "verdict"]);
  const workflow = fs.readFileSync(path.resolve(".github/workflows/qa-acceptance.yml"), "utf8");
  assert(workflow.indexOf("report-evidence qa-evidence") < workflow.indexOf("bootstrap-terminal completed"));
  assert(workflow.indexOf("bootstrap-terminal completed") < workflow.indexOf("report-verdict qa-evidence"));
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
  */claim) printf '%s' '{"run_case_id":"rc-login","claim_token":"lease-token","auth_bootstrap":{"required":true,"contract_version":"qa-bootstrap-login/v1","login_endpoint":"/login","ready_endpoint":"/ready","capture_proof_endpoint":"/api/agent/run-cases/rc-login/auth-bootstrap/capture-proof","terminal_endpoint":"/terminal"}}' > "$out";;
  */login) printf '%s' '{"request_id":"request-1","envelope":{"run_case_id":"rc-login","attempt":1,"refresh_token":"refresh-secret"},"envelope_digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","expires_at":"soon"}' > "$out";;
  */ready) printf '%s' '{"capture_window_nonce":"nonce-1","scope_generation":"generation-1"}' > "$out";;
  */terminal) cp "\${payload#@}" "${terminalPayload}";;
esac
`, { mode: 0o755 });
  const binding = path.join(root, "binding.json");
  const ready = path.join(root, "ready.json");
  const cleanup = path.join(root, "cleanup.json");
  fs.writeFileSync(binding, JSON.stringify({ signing_cert_sha256: "sha256:cert", boot_generation: "sha256:boot" }));
  fs.writeFileSync(ready, JSON.stringify({
    request_id: "request-1",
    envelope_digest: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    runtime_envelope: { ready_receipt_digest: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" },
  }));
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

test("workflow-error closes claimed failures and treats an already-terminal attempt as an idempotent no-op", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-workflow-error-"));
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  fs.writeFileSync(path.join(runnerTemp, "stamp-claim-rc-error"), "lease-token", { mode: 0o600 });
  const payload = path.join(root, "payload.json");
  const mode = path.join(root, "mode");
  fs.writeFileSync(mode, "running");
  const calls = path.join(root, "calls");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
out=""; data=""; format=""; url=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2;;
    --data-binary) data="$2"; shift 2;;
    -w) format="$2"; shift 2;;
    http*) url="$1"; shift;;
    *) shift;;
  esac
done
cp "\${data#@}" "${payload}"
case "$url" in
  */terminal) printf '%s\n' terminal >> "${calls}"; printf '%s' terminal > "${mode}"; [ -n "$out" ] && printf '{}' > "$out"; printf '200';;
  */verdict) printf '%s\n' verdict >> "${calls}"; if [ "$(cat "${mode}")" = running ]; then printf '{}' > "$out"; printf '200'; else printf '{"code":"RUN_CASE_NOT_RUNNING"}' > "$out"; printf '409'; fi;;
esac
`, { mode: 0o755 });
  const env = {
    ...process.env,
    PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
    RUNNER_TEMP: runnerTemp,
    STAMP_RUN_CASE_ID: "rc-error",
    STAMP_URL: "https://stamp.test",
    STAMP_TOKEN: "test-token",
    GITHUB_RUN_ID: "777",
    GITHUB_REPOSITORY: "botiverse/mobile",
    STAMP_POSTCLAIM_ERROR_REASON: "emulator setup failed after claim",
    STAMP_POSTCLAIM_ERROR_PHASE: "emulator_setup",
  };
  const reporter = new URL("./stamp_report.sh", import.meta.url).pathname;
  execFileSync("bash", [reporter, "workflow-error"], { cwd: root, env });
  assert.deepEqual(JSON.parse(fs.readFileSync(payload, "utf8")), {
    verdict: "error", failure_class: "harness", phase: "emulator_setup", reason: "emulator setup failed after claim",
  });
  fs.writeFileSync(mode, "terminal");
  const output = execFileSync("bash", [reporter, "workflow-error"], { cwd: root, env, encoding: "utf8" });
  assert.match(output, /already terminal; no-op/);

  fs.writeFileSync(path.join(runnerTemp, "stamp-bootstrap-spec-rc-error.json"), JSON.stringify({ required: true, terminal_endpoint: "/terminal" }), { mode: 0o600 });
  fs.writeFileSync(path.join(runnerTemp, "stamp-bootstrap-login-rc-error.json"), JSON.stringify({
    request_id: "request", envelope_digest: "sha256:digest", envelope: { refresh_token: "refresh" },
  }), { mode: 0o600 });
  const cleanup = path.join(root, "cleanup.json");
  fs.writeFileSync(cleanup, JSON.stringify({ force_stopped: true, package_data_cleared: true }));
  fs.writeFileSync(mode, "issued");
  fs.writeFileSync(calls, "");
  execFileSync("bash", [reporter, "bootstrap-terminal", "aborted", cleanup], { cwd: root, env });
  execFileSync("bash", [reporter, "workflow-error"], { cwd: root, env });
  assert.deepEqual(fs.readFileSync(calls, "utf8").trim().split("\n"), ["terminal", "verdict"]);

  const workflow = fs.readFileSync(path.resolve(".github/workflows/qa-acceptance.yml"), "utf8");
  assert.match(workflow, /- name: Report verdict\n[\s\S]*?if: \$\{\{ always\(\) && !failure\(\) \}\}/);
  assert.match(workflow, /bootstrap-failure-terminal "\$RUNNER_TEMP\/stamp-bootstrap-device-binding\.json" "\$RUNNER_TEMP\/stamp-bootstrap-ready\.json"/);
  assert.match(workflow, /bash scripts\/qa\/stamp_report\.sh workflow-error/);
});

test("READY response loss resolves server authority before choosing completed versus aborted cleanup", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-ready-loss-"));
  const runnerTemp = path.join(root, "runner-temp");
  const bin = path.join(root, "bin");
  fs.mkdirSync(runnerTemp); fs.mkdirSync(bin);
  const reporter = path.join(root, "stamp_report.sh");
  fs.copyFileSync(new URL("./stamp_report.sh", import.meta.url), reporter);
  fs.chmodSync(reporter, 0o755);
  const calls = path.join(root, "calls");
  const serverState = path.join(root, "server-state");
  fs.writeFileSync(serverState, "ready");
  fs.writeFileSync(path.join(root, "mobile_login_bootstrap.mjs"), `
import fs from "node:fs";
const args=process.argv.slice(2); const value=(n)=>args[args.indexOf(n)+1];
fs.appendFileSync("${calls}", "cleanup:"+value("--outcome")+"\\n");
fs.writeFileSync(value("--output"), JSON.stringify({force_stopped:true,package_data_cleared:true}));
`);
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
url=""; out=""
while [ "$#" -gt 0 ]; do case "$1" in -o) out="$2"; shift 2;; http*) url="$1"; shift;; *) shift;; esac; done
case "$url" in
  */status) state=$(cat "${serverState}"); printf '{"contract_version":"qa-bootstrap-login/v1","required":true,"status":"%s"}' "$state" > "$out"; printf '%s\n' status >> "${calls}";;
  */terminal) printf '%s\n' terminal >> "${calls}";;
  *) exit 23;;
esac
`, { mode: 0o755 });
  const spec = path.join(runnerTemp, "stamp-bootstrap-spec-rc-loss.json");
  const login = path.join(runnerTemp, "stamp-bootstrap-login-rc-loss.json");
  const binding = path.join(root, "binding.json");
  const ready = path.join(root, "ready.json");
  fs.writeFileSync(spec, JSON.stringify({ required: true, status_endpoint: "/status", terminal_endpoint: "/terminal" }), { mode: 0o600 });
  const writeLogin = () => fs.writeFileSync(login, JSON.stringify({ request_id: "request", envelope_digest: "sha256:digest", envelope: { refresh_token: "refresh" } }), { mode: 0o600 });
  writeLogin(); fs.writeFileSync(binding, "{}"); fs.writeFileSync(ready, "{}");
  const env = {
    ...process.env, PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
    RUNNER_TEMP: runnerTemp, STAMP_RUN_CASE_ID: "rc-loss", STAMP_URL: "https://stamp.test", STAMP_TOKEN: "token",
    GITHUB_RUN_ID: "901", GITHUB_REPOSITORY: "botiverse/mobile",
  };

  // No local READY response exists: the server's authoritative ready state
  // still selects completed cleanup and logs out the session.
  execFileSync(reporter, ["bootstrap-failure-terminal", binding, ready], { cwd: root, env });
  assert.deepEqual(fs.readFileSync(calls, "utf8").trim().split("\n"), ["status", "cleanup:completed", "terminal"]);

  fs.writeFileSync(calls, ""); writeLogin(); fs.writeFileSync(serverState, "issued");
  execFileSync(reporter, ["bootstrap-failure-terminal", binding, ready], { cwd: root, env });
  assert.deepEqual(fs.readFileSync(calls, "utf8").trim().split("\n"), ["status", "cleanup:aborted", "terminal"]);
});

test("capture-proof reporting rejects missing scope, stale generation, proof SHA mismatch, and generation replay with new bytes", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-proof-red-"));
  const evidence = path.join(root, "qa-evidence");
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(evidence); fs.mkdirSync(bin); fs.mkdirSync(runnerTemp);
  fs.writeFileSync(path.join(evidence, "logcat.txt"), "first bytes\n");
  fs.writeFileSync(path.join(evidence, "verdict.json"), JSON.stringify({
    verdict: "pass", log_scope: { filtered: true, package_id: "build.raft.app", pids: [9], generation: "generation-1" },
  }));
  fs.writeFileSync(path.join(runnerTemp, "stamp-claim-rc-proof"), "lease", { mode: 0o600 });
  fs.writeFileSync(path.join(runnerTemp, "stamp-bootstrap-spec-rc-proof.json"), JSON.stringify({ required: true }), { mode: 0o600 });
  const context = path.join(runnerTemp, "stamp-bootstrap-capture-context-rc-proof.json");
  const mode = path.join(root, "mode");
  const ledger = path.join(root, "ledger");
  fs.writeFileSync(mode, "correct");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
url=""; out=""; payload=""
while [ "$#" -gt 0 ]; do case "$1" in -o) out="$2"; shift 2;; --data-binary) payload="$2"; shift 2;; http*) url="$1"; shift;; *) shift;; esac; done
case "$url" in
  */execution-scope) exit 0;;
  */capture-proof)
    read -r nonce generation sha command < <(node -e 'const v=require(process.argv[1].slice(1));process.stdout.write([v.capture_window_nonce,v.scope_generation,v.artifact_sha256,v.command_generation].join(" "))' "$payload")
    [ "$nonce" = nonce-1 ] && [ "$generation" = generation-1 ] || exit 22
    if [ -s "${ledger}" ]; then read -r old_command old_sha < "${ledger}"; [ "$old_command" != "$command" ] || [ "$old_sha" = "$sha" ] || exit 22; fi
    printf '%s %s\n' "$command" "$sha" > "${ledger}"
    response_sha="$sha"; [ "$(cat "${mode}")" != mismatch ] || response_sha=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
    printf '{"capture_proof_id":"proof","capture_upload_identity":"upload","artifact_sha256":"%s"}' "$response_sha" > "$out";;
  */evidence*) [[ "$url" == *"capture_proof_id=proof"* && "$url" == *"capture_upload_identity=upload"* ]] || exit 22;;
  *) exit 23;;
esac
`, { mode: 0o755 });
  const env = {
    ...process.env, PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
    RUNNER_TEMP: runnerTemp, STAMP_RUN_CASE_ID: "rc-proof", STAMP_URL: "https://stamp.test", STAMP_TOKEN: "token",
    GITHUB_RUN_ID: "900", GITHUB_RUN_ATTEMPT: "1", GITHUB_REPOSITORY: "botiverse/mobile",
  };
  const reporter = new URL("./stamp_report.sh", import.meta.url).pathname;
  const writeContext = (nonce, generation) => fs.writeFileSync(context, JSON.stringify({
    capture_window_nonce: nonce, scope_generation: generation,
    capture_proof_endpoint: "/api/agent/run-cases/rc-proof/auth-bootstrap/capture-proof",
  }), { mode: 0o600 });

  writeContext("", "generation-1");
  assert.throws(() => execFileSync("bash", [reporter, "report-evidence", "qa-evidence"], { cwd: root, env }));
  writeContext("nonce-1", "old-generation");
  assert.throws(() => execFileSync("bash", [reporter, "report-evidence", "qa-evidence"], { cwd: root, env }));
  writeContext("nonce-1", "generation-1");
  fs.writeFileSync(mode, "mismatch");
  assert.throws(() => execFileSync("bash", [reporter, "report-evidence", "qa-evidence"], { cwd: root, env }));
  fs.writeFileSync(mode, "correct");
  fs.rmSync(ledger, { force: true });
  execFileSync("bash", [reporter, "report-evidence", "qa-evidence"], { cwd: root, env });
  fs.writeFileSync(path.join(evidence, "logcat.txt"), "different bytes\n");
  assert.throws(() => execFileSync("bash", [reporter, "report-evidence", "qa-evidence"], { cwd: root, env }));
});
