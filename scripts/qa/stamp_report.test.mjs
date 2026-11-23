import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { createHash } from "node:crypto";
import os from "node:os";
import path from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { canonicalJson, sha256Digest } from "./mobile_login_bootstrap_lib.mjs";
import { writeRecord } from "./login_phase.mjs";

// #222: report-evidence runs the evidence gate first; these fixtures are runs that never sign in.
const noLogin = (dir, run, attempt = process.env.GITHUB_RUN_ATTEMPT) =>
  writeRecord(dir, { mode: "none", status: "no-login", login_end: null }, { GITHUB_RUN_ID: run, GITHUB_RUN_ATTEMPT: attempt });

/** A complete, digest-correct issuance response bound to the given run case, CI job and device binding. */
function issuedLogin({ runCaseId, runId, binding, requestId = "request-1", applicationId = "build.raft.app.alpha" }) {
  const envelope = {
    version: "qa-bootstrap-login/v1", run_case_id: runCaseId, attempt: 1,
    ci_job_ref: `https://github.com/botiverse/mobile/actions/runs/${runId}`, principal_id: "user-1", server_id: "server-1",
    target_route: "compose_slock_app_shell", application_id: applicationId,
    signing_cert_sha256: binding.signing_cert_sha256, boot_generation: binding.boot_generation,
    user: { id: "user-1", email: "qa@example.invalid" }, access_token: "access-secret", refresh_token: "refresh-secret",
  };
  return { request_id: requestId, envelope_digest: sha256Digest(Buffer.from(canonicalJson(envelope), "utf8")), envelope, expires_at: "2026-11-14T00:10:00Z" };
}

test("review agent fails closed on an invalid frozen timeout before any network call", () => {
  // Every spawn runs in (and writes its record to) a throwaway directory so
  // the real agent entry can never leave artifacts in the repo tree.
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "review-agent-timeout-"));
  const agentPath = new URL("./review_agent.mjs", import.meta.url).pathname;
  const baseEnv = {
    ...process.env,
    STAMP_RUN_ID: "run-x",
    STAMP_REVIEWER: "agent-review-1",
    STAMP_URL: "https://stamp.invalid",
    STAMP_TOKEN: "token-x",
    MINIMAX_API_KEY: "key-x",
    PI_PROVIDER: "minimax",
    PI_MODEL: "MiniMax-M3.1-Flash-Preview",
  };
  const spawnAgent = (overrides, name) =>
    execFileSync(process.execPath, [agentPath], {
      cwd: scratch,
      env: { ...baseEnv, STAMP_REVIEW_OUT: path.join(scratch, name), ...overrides },
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      timeout: 30000,
    });
  try {
    for (const bad of ["abc", "-1", "0", "59999", "1800001", "600000.5"]) {
      let failed = false;
      try {
        spawnAgent({ PI_REVIEW_TIMEOUT_MS: bad }, `bad-${bad}.json`);
      } catch (error) {
        failed = true;
        assert.match(String(error.stderr), /PI_REVIEW_TIMEOUT_MS must be an integer in \[60000, 1800000\]/);
      }
      assert.ok(failed, `PI_REVIEW_TIMEOUT_MS=${bad} must fail closed`);
    }
    // The valid default path must get PAST timeout validation (it then fails
    // on the unreachable Stamp URL, not on the timeout gate).
    let out = "";
    try {
      spawnAgent({}, "control.json");
    } catch (error) {
      out = String(error.stderr);
    }
    assert.doesNotMatch(out, /PI_REVIEW_TIMEOUT_MS/);
  } finally {
    fs.rmSync(scratch, { recursive: true, force: true });
  }
  // Repo tree must stay artifact-free after exercising the real agent entry.
  const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..", "..");
  for (const dir of [repoRoot, path.join(repoRoot, "scripts", "qa")]) {
    assert.deepEqual(
      fs.readdirSync(dir).filter((f) => /^panel-review-.*\.json$/.test(f)),
      [],
      `stray review records in ${dir}`,
    );
  }
});

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

test("claim binds frozen builds and reports direct APK provenance separately", (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-claim-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
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

  const env = {
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
  };
  const reporter = new URL("./stamp_report.sh", import.meta.url).pathname;
  const claim = (extra = {}) => execFileSync("bash", [reporter, "claim", "qa-evidence"], {
    cwd: root, env: { ...env, ...extra }, stdio: "pipe",
  });
  claim();
  assert.deepEqual(JSON.parse(fs.readFileSync(payloadLog, "utf8")), {
    ci_job_ref: "https://github.com/botiverse/mobile/actions/runs/123",
    source_commit: "frozen-source",
    apk_sha256: "frozen-sha",
    app_version: "1.0",
    version_code: 1,
    package_id: "build.raft.app",
  });
  assert.equal(fs.readFileSync(path.join(runnerTemp, "stamp-claim-rc-frozen"), "utf8"), "lease-token");

  const apks = path.join(root, "apks");
  fs.mkdirSync(apks);
  const apk = Buffer.from("downloaded APK fixture");
  fs.writeFileSync(path.join(apks, "pinned.apk"), apk);
  const digest = createHash("sha256").update(apk).digest("hex");
  const info = { platform: "android", source_commit_verified: false, apk_sha256: digest,
    package: "build.raft.app", app_version: "1.26.0", version_code: "12600002" };
  const metadata = path.join(apks, "build-info.json");
  fs.writeFileSync(metadata, JSON.stringify(info));
  const direct = { STAMP_DIRECT_ANDROID_APK: "true", GITHUB_SHA: "b".repeat(40) };
  claim(direct);
  assert.deepEqual(JSON.parse(fs.readFileSync(payloadLog, "utf8")), {
    ci_job_ref: "https://github.com/botiverse/mobile/actions/runs/123", workflow_source_commit: direct.GITHUB_SHA,
    source_commit: null, apk_sha256: digest, app_version: "1.26.0", version_code: 12600002, package_id: "build.raft.app",
  });
  fs.rmSync(payloadLog);
  fs.writeFileSync(metadata, JSON.stringify({ ...info, apk_sha256: "0".repeat(64) }));
  assert.throws(() => claim(direct), /direct APK does not match/);
  assert.equal(fs.existsSync(payloadLog), false);
});

test("legacy credential phase freezes the actual CI login email after claim without exposing password", () => {
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
  assert.equal(fs.readFileSync(count, "utf8").trim(), "1", "claim itself never posts executor identity");
  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "credential-receipt", "qa-evidence"], {
    cwd: root,
    env: {
      ...process.env,
      PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
      RUNNER_TEMP: runnerTemp,
      STAMP_RUN_CASE_ID: "rc-email",
      STAMP_URL: "https://stamp.test",
      STAMP_TOKEN: "test-token",
      GITHUB_RUN_ID: "123",
      GITHUB_REPOSITORY: "botiverse/mobile",
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

test("legacy credential phase failure preserves the claim token for workflow-error cleanup", () => {
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
  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "claim", "qa-evidence"], {
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
  });
  assert.throws(() => execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "credential-receipt", "qa-evidence"], {
    cwd: root,
    env: {
      ...process.env,
      PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
      RUNNER_TEMP: runnerTemp,
      STAMP_RUN_CASE_ID: "rc-email-fail",
      STAMP_URL: "https://stamp.test",
      STAMP_TOKEN: "test-token",
      GITHUB_RUN_ID: "123",
      GITHUB_REPOSITORY: "botiverse/mobile",
      QA_TEST_EMAIL: "qa@example.test",
    },
    stdio: "pipe",
  }));
  assert.equal(fs.readFileSync(path.join(runnerTemp, "stamp-claim-rc-email-fail"), "utf8"), "lease-token");
});

test("bootstrap claim and credential phase make zero executor credential-receipt calls", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-bootstrap-no-receipt-"));
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(bin); fs.mkdirSync(runnerTemp); fs.mkdirSync(path.join(root, "qa-evidence"));
  const calls = path.join(root, "calls");
  fs.writeFileSync(calls, "0");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
out=""
while [ "$#" -gt 0 ]; do case "$1" in -o) out="$2"; shift 2;; *) shift;; esac; done
n=$(( $(cat "${calls}") + 1 )); echo "$n" > "${calls}"
printf '%s' '{"run_case_id":"rc-bootstrap","claim_token":"lease-token","auth_bootstrap":{"required":true,"contract_version":"qa-bootstrap-login/v1"}}' > "$out"
`, { mode: 0o755 });
  const env = {
    ...process.env,
    PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
    RUNNER_TEMP: runnerTemp,
    STAMP_RUN_CASE_ID: "rc-bootstrap",
    STAMP_URL: "https://stamp.test",
    STAMP_TOKEN: "test-token",
    GITHUB_RUN_ID: "123",
    GITHUB_REPOSITORY: "botiverse/mobile",
    STAMP_VERIFIED_SOURCE_COMMIT: "source",
    STAMP_VERIFIED_ARTIFACT_SHA256: "sha",
    STAMP_VERIFIED_APP_VERSION: "1.0",
    STAMP_VERIFIED_VERSION_CODE: "1",
    STAMP_VERIFIED_PACKAGE_ID: "build.raft.app",
    QA_TEST_EMAIL: "qa@example.test",
  };
  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "claim", "qa-evidence"], { cwd: root, env });
  execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "credential-receipt", "qa-evidence"], { cwd: root, env });
  assert.equal(fs.readFileSync(calls, "utf8").trim(), "1", "only claim may call Stamp");
});

test("the claim spec keeps the claimed case_id (#216 login acceptance), only as a well-formed case id", () => {
  for (const [caseId, kept] of [["login-email-password", "login-email-password"], ["send-message-in-channel", "send-message-in-channel"],
    ["login-email-password\ncase_id=x", null], ["Login-Email-Password", null], [42, null], [undefined, null]]) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-claim-case-"));
    const bin = path.join(root, "bin");
    const runnerTemp = path.join(root, "runner-temp");
    fs.mkdirSync(bin); fs.mkdirSync(runnerTemp); fs.mkdirSync(path.join(root, "qa-evidence"));
    const reply = path.join(root, "claim-reply.json");
    fs.writeFileSync(reply, JSON.stringify({ run_case_id: "rc-case", case_id: caseId, claim_token: "lease-token", auth_bootstrap: { required: false } }));
    fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
out=""
while [ "$#" -gt 0 ]; do case "$1" in -o) out="$2"; shift 2;; *) shift;; esac; done
cp "${reply}" "$out"
`, { mode: 0o755 });
    execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, "claim", "qa-evidence"], { cwd: root, env: {
      ...process.env, PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`, RUNNER_TEMP: runnerTemp,
      STAMP_RUN_CASE_ID: "rc-case", STAMP_URL: "https://stamp.test", STAMP_TOKEN: "test-token", GITHUB_RUN_ID: "123", GITHUB_REPOSITORY: "botiverse/mobile",
      STAMP_VERIFIED_SOURCE_COMMIT: "source", STAMP_VERIFIED_ARTIFACT_SHA256: "sha", STAMP_VERIFIED_APP_VERSION: "1.0",
      STAMP_VERIFIED_VERSION_CODE: "1", STAMP_VERIFIED_PACKAGE_ID: "build.raft.app",
    } });
    const spec = JSON.parse(fs.readFileSync(path.join(runnerTemp, "stamp-bootstrap-spec-rc-case.json"), "utf8"));
    assert.deepEqual([spec.required, spec.run_case_id, spec.case_id], [false, "rc-case", kept], JSON.stringify(caseId));
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("the claimed case login-email-password sends neither legacy account report; every other case sends both as before", () => {
  for (const [caseId, reports] of [["login-email-password", 0], ["send-message-in-channel", 2]]) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-login-acceptance-"));
    const evidence = path.join(root, "qa-evidence");
    const bin = path.join(root, "bin");
    const runnerTemp = path.join(root, "runner-temp");
    fs.mkdirSync(evidence); fs.mkdirSync(bin); fs.mkdirSync(runnerTemp);
    const reply = path.join(root, "claim-reply.json");
    fs.writeFileSync(reply, JSON.stringify({ run_case_id: "rc-legacy", case_id: caseId, claim_token: "lease-token", auth_bootstrap: { required: false } }));
    const calls = path.join(root, "calls.log");
    const receipts = path.join(root, "receipts.log");
    fs.writeFileSync(calls, "");
    fs.writeFileSync(receipts, "");
    fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
args="$*"; out=""; payload=""
while [ "$#" -gt 0 ]; do case "$1" in -o) out="$2"; shift 2;; --data-binary) payload="$2"; shift 2;; *) shift;; esac; done
printf '%s\\n' "$args" >> "${calls}"
if [[ "$args" == *credential-receipt* ]]; then cat "\${payload#@}" >> "${receipts}"; echo >> "${receipts}"; fi
if [ -n "$out" ]; then cp "${reply}" "$out"; fi
exit 0
`, { mode: 0o755 });
    // A post-login evidence directory that passes the gate, with the app's own identity line in the scoped log.
    fs.writeFileSync(path.join(evidence, "logcat.txt"), "07-23 I/SlockSharedAuth: shared_auth_session QA_CREDENTIAL_IDENTITY version=1 principalId=user-123 authGeneration=9\n");
    fs.writeFileSync(path.join(evidence, "verdict.json"), JSON.stringify({ verdict: "pass", log_scope: { filtered: true, package_id: "build.raft.app", pids: [1234], generation: "99:1" } }));
    noLogin(evidence, "99");
    const env = {
      ...process.env, PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`, RUNNER_TEMP: runnerTemp,
      STAMP_RUN_CASE_ID: "rc-legacy", STAMP_URL: "https://stamp.test", STAMP_TOKEN: "test-token", GITHUB_RUN_ID: "99", GITHUB_REPOSITORY: "botiverse/mobile",
      STAMP_VERIFIED_SOURCE_COMMIT: "source", STAMP_VERIFIED_ARTIFACT_SHA256: "sha", STAMP_VERIFIED_APP_VERSION: "1.0",
      STAMP_VERIFIED_VERSION_CODE: "1", STAMP_VERIFIED_PACKAGE_ID: "build.raft.app",
      QA_TEST_EMAIL: "qa@example.test", QA_TEST_PASSWORD: "must-not-appear",
    };
    const out = ["claim", "credential-receipt", "report-evidence"].map((phase) =>
      execFileSync("bash", [new URL("./stamp_report.sh", import.meta.url).pathname, phase, "qa-evidence"], { cwd: root, env, encoding: "utf8" })).join("");
    const sent = fs.readFileSync(calls, "utf8").split("\n").filter((line) => line.includes("/credential-receipt"));
    assert.equal(sent.length, reports, caseId);
    assert.ok(fs.readFileSync(calls, "utf8").includes("/evidence?kind=logcat"), `${caseId}: the evidence itself is still reported`);
    if (reports) {
      assert.deepEqual(fs.readFileSync(receipts, "utf8").trim().split("\n").map((l) => JSON.parse(l)),
        [{ login_email: "qa@example.test", stable_subject: null }, { login_email: "qa@example.test", stable_subject: "user-123" }]);
    } else {
      assert.equal(fs.readFileSync(receipts, "utf8"), "", "no QA email or account id leaves the job");
      assert.match(out, /stamp: the login acceptance case sends no executor credential receipt\n/);
      assert.match(out, /stamp: the login acceptance case skips the executor credential receipt refresh\n/);
    }
    fs.rmSync(root, { recursive: true, force: true });
  }
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
  fs.writeFileSync(path.join(evidence, "step-00.png"), "step zero raw");
  fs.writeFileSync(path.join(evidence, "step-19.png"), "step nineteen");
  fs.writeFileSync(path.join(evidence, "verdict.json"), JSON.stringify({
    verdict: "pass",
    log_scope: { filtered: true, package_id: "build.raft.app", pids: [1234], generation: "99:1" },
  }));
  noLogin(evidence, "99");
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
  assert.match(raw, /[?&]step=(?:0|19)(?:&|\s)/);
  // The raw twin of an annotated (assertion) step is uploaded too.
  assert(calls.some((call) => call.includes("kind=screenshot&") && /[?&]step=0(?:&|\s)/.test(call)));
  assert(calls.some((call) => call.includes("kind=screenshot&") && /[?&]step=19(?:&|\s)/.test(call)));
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
  fs.writeFileSync(path.join(evidence, "logcat.txt"), "07-23 I/SlockSharedAuth: shared_auth_session QA_CREDENTIAL_IDENTITY version=1 principalId=user-123 authGeneration=9\n");
  fs.writeFileSync(path.join(evidence, "verdict.json"), JSON.stringify({
    verdict: "harness",
    reason: "fixture setup",
    log_scope: { filtered: true, package_id: "build.raft.app", pids: [1234], generation: "99:1" },
  }));
  noLogin(evidence, "99");
  fs.writeFileSync(path.join(runnerTemp, "stamp-claim-rc-subject"), "lease-token", { mode: 0o600 });
  fs.writeFileSync(path.join(runnerTemp, "stamp-bootstrap-spec-rc-subject.json"), JSON.stringify({ required: false }), { mode: 0o600 });
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
  fs.writeFileSync(path.join(evidence, "logcat.txt"), "rendered message body principalId=attacker-user\n");
  fs.writeFileSync(path.join(evidence, "system-log-raft.txt"), "foreign process principalId=foreign-user\n");
  fs.writeFileSync(path.join(evidence, "session.jsonl"), '{"assistant":"principalId=forged-user"}\n');
  fs.writeFileSync(path.join(evidence, "verdict.json"), JSON.stringify({
    verdict: "harness",
    reason: "fixture setup",
    log_scope: { filtered: true, package_id: "build.raft.app", pids: [1234], generation: "99:1" },
  }));
  noLogin(evidence, "99");
  fs.writeFileSync(path.join(runnerTemp, "stamp-claim-rc-forgery"), "lease-token", { mode: 0o600 });
  fs.writeFileSync(path.join(runnerTemp, "stamp-bootstrap-spec-rc-forgery.json"), JSON.stringify({ required: false }), { mode: 0o600 });
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

test("review panel reads mutable provider/model vars once, in a validating freeze job", () => {
  const panel = fs.readFileSync(new URL("../../.github/workflows/qa-review-panel.yml", import.meta.url), "utf8");
  // Single-execution freeze: only the freeze job reads mutable repo vars;
  // reviewers and the verify gate consume the frozen outputs.
  assert.match(panel, /PANEL_PROVIDER: \$\{\{ vars\.PI_REVIEW_PROVIDER \|\| '/);
  assert.match(panel, /PANEL_MODEL: \$\{\{ vars\.PI_REVIEW_MODEL \|\| '/);
  assert.match(panel, /PANEL_TIMEOUT_MS: \$\{\{ vars\.PI_REVIEW_TIMEOUT_MS \|\| '/);
  assert.match(panel, /PI_PROVIDER: \$\{\{ needs\.freeze\.outputs\.provider \}\}/);
  assert.match(panel, /PI_MODEL: \$\{\{ needs\.freeze\.outputs\.model \}\}/);
  assert.match(panel, /PI_REVIEW_TIMEOUT_MS: \$\{\{ needs\.freeze\.outputs\.timeout_ms \}\}/);
  assert.match(panel, /PANEL_PROVIDER: \$\{\{ needs\.freeze\.outputs\.provider \}\}/);
  assert.match(panel, /needs: \[freeze, review\]/);
  assert.equal((panel.match(/vars\.PI_REVIEW_/g) || []).length, 3, "mutable vars must be read only in the freeze job");
  // Frozen outputs are single-line safe tokens: injection via a mutated repo
  // var must fail the freeze job instead of forging extra outputs.
  assert.match(panel, /PI_REVIEW_PROVIDER must match \[A-Za-z0-9\._-\]\+/);
  assert.match(panel, /PI_REVIEW_MODEL must match \[A-Za-z0-9\._-\]\+/);
  assert.match(panel, /\.provider == \$provider and \.model == \$model/);
});

test("Android and iOS freeze legacy email before execution and expose none for bootstrap", () => {
  const legacy = String.raw`\(inputs\.stamp_run_case_id == '' \|\| steps\.stamp-claim\.outputs\.bootstrap_required != 'true'\)`;
  for (const workflow of ["qa-acceptance.yml", "qa-acceptance-ios.yml"]) {
    const source = fs.readFileSync(new URL(`../../.github/workflows/${workflow}`, import.meta.url), "utf8");
    const claim = source.indexOf("name: Claim Stamp run case and resolve auth mode");
    const preflight = source.indexOf("name: Preflight — QA account must not see the production server");
    const receipt = source.indexOf("name: Freeze legacy credential identity receipt");
    const execute = source.indexOf("name: Run acceptance agent");
    assert.ok(claim >= 0 && preflight > claim && receipt > preflight && execute > receipt, workflow);
    assert.equal(source.slice(claim, preflight).includes("QA_TEST_EMAIL"), false, `${workflow} claim has no credential identity`);
    assert.equal(source.slice(claim, preflight).includes("QA_LOGIN_ACCEPTANCE_"), false, `${workflow} claim has no acceptance credential identity`);
    // The QA account reaches steps only through their own env: from secrets.*, never through
    // $GITHUB_ENV (job-wide) and never on the bootstrap path.
    assert.equal(/env\.QA_TEST_|env\.QA_LOGIN_ACCEPTANCE_|QA_TEST_\w*_SECRET/.test(source), false, `${workflow} reads QA credentials from the job env`);
    const preflightBlock = source.slice(preflight, receipt);
    assert.match(preflightBlock, /bootstrap_required != 'true'/);
    const receiptBlock = source.slice(receipt, execute);
    assert.match(receiptBlock, /bootstrap_required != 'true'/);
    assert.equal((receiptBlock.match(/stamp_report\.sh credential-receipt/g) ?? []).length, 1);
    const executeBlock = source.slice(execute, source.indexOf("\n      - name:", execute + 1));
    assert.match(executeBlock, /STAMP_URL:.*stamp_run_case_id != ''/);
    assert.match(executeBlock, /STAMP_TOKEN:.*stamp_run_case_id != ''/);
    if (workflow === "qa-acceptance-ios.yml") {
      assert.match(preflightBlock, /QA_TEST_PASSWORD: \$\{\{ secrets\.QA_TEST_PASSWORD \}\}/);
      // #222: iOS has no safe way to type a secret; the agent step gets no account at all.
      assert.doesNotMatch(executeBlock, /QA_TEST_/);
      assert.match(executeBlock, new RegExp(`QA_PASSWORD_LOGIN: \\$\\{\\{ ${legacy} && 'true' \\|\\| 'false' \\}\\}`));
      continue;
    }
    // Android: login-email-password fetches its runtime credential from Testbed via OIDC (zero QA repo secrets,
    // zero fallback to QA_TEST_*); the release settings lane gets the account only via Stage release login credential.
    const notRelease = String.raw`inputs\.login_mode != 'release-prelogin'`;
    const notLoginAcceptance = String.raw`steps\.stamp-claim\.outputs\.claimed_case != 'login-email-password'`;
    assert.match(preflightBlock, new RegExp(`QA_TEST_EMAIL: \\$\\{\\{ ${notLoginAcceptance} && secrets\\.QA_TEST_EMAIL \\|\\| '' \\}\\}`));
    assert.match(preflightBlock, new RegExp(`QA_TEST_PASSWORD: \\$\\{\\{ ${notLoginAcceptance} && secrets\\.QA_TEST_PASSWORD \\|\\| '' \\}\\}`));
    assert.doesNotMatch(source, /secrets\.QA_LOGIN_ACCEPTANCE_/, "no QA_LOGIN_ACCEPTANCE_* repository secrets anywhere in qa-acceptance.yml");
    assert.equal(source.includes("name: Stage login acceptance credential"), false, "no static secret staging step for login-email-password");
    const receiptStep = source.slice(receipt, source.indexOf("\n      - name:", receipt + 1));
    assert.match(receiptStep, new RegExp(notLoginAcceptance));
    assert.doesNotMatch(receiptStep, /QA_LOGIN_ACCEPTANCE_/);
    assert.match(executeBlock, new RegExp(`QA_TEST_EMAIL: \\$\\{\\{ ${notRelease} && ${legacy} && ${notLoginAcceptance} && secrets\\.QA_TEST_EMAIL \\|\\| '' \\}\\}`));
    assert.match(executeBlock, new RegExp(`QA_TEST_PASSWORD: \\$\\{\\{ ${notRelease} && ${legacy} && ${notLoginAcceptance} && secrets\\.QA_TEST_PASSWORD \\|\\| '' \\}\\}`));
    // Neither the release lane's nor login-email-password's account reaches the emulator step's env
    // (every line and child process would inherit it).
    assert.doesNotMatch(executeBlock, /QA_RELEASE_LOGIN_/);
    assert.doesNotMatch(executeBlock, /QA_LOGIN_ACCEPTANCE_EMAIL:|QA_LOGIN_ACCEPTANCE_PASSWORD:|secrets\.QA_LOGIN_ACCEPTANCE_/);
    assert.equal((executeBlock.match(/secrets\.QA_TEST_/g) ?? []).length, 2, "only the two legacy-gated keys");
    const stage = source.slice(source.indexOf("name: Stage release login credential"), execute);
    assert.match(stage, /if: \$\{\{ inputs\.stamp_run_case_id != '' && inputs\.login_mode == 'release-prelogin' \}\}/);
    assert.match(stage, /umask 077/);
    assert.match(preflightBlock, new RegExp(notRelease));
    assert.match(receiptBlock, new RegExp(notRelease));
  }
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
  noLogin(evidence, "500");
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
});

test("login bootstrap keeps session material private and deletes it after terminal", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-bootstrap-login-"));
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  const terminalPayload = path.join(root, "terminal.json");
  const deviceBinding = { signing_cert_sha256: `sha256:${"c".repeat(64)}`, boot_generation: "sha256:boot" };
  const issued = issuedLogin({ runCaseId: "rc-login", runId: "321", binding: deviceBinding });
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
out=""; payload=""; url=""
trap '[ -z "$w" ] || printf 200' EXIT
while [ "$#" -gt 0 ]; do
  case "$1" in
    -w) w="$2"; shift 2;;
    -o) out="$2"; shift 2;;
    --data-binary) payload="$2"; shift 2;;
    http*) url="$1"; shift;;
    *) shift;;
  esac
done
case "$url" in
  */claim) printf '%s' '{"run_case_id":"rc-login","claim_token":"lease-token","auth_bootstrap":{"required":true,"contract_version":"qa-bootstrap-login/v1","login_endpoint":"/login","ready_endpoint":"/ready","capture_proof_endpoint":"/api/agent/run-cases/rc-login/auth-bootstrap/capture-proof","terminal_endpoint":"/terminal"}}' > "$out";;
  */login) printf '%s' '${JSON.stringify(issued)}' > "$out";;
  */ready) printf '%s' '{"capture_window_nonce":"nonce-1","scope_generation":"generation-1"}' > "$out";;
  */terminal) cp "\${payload#@}" "${terminalPayload}";;
esac
`, { mode: 0o755 });
  const binding = path.join(root, "binding.json");
  const ready = path.join(root, "ready.json");
  const cleanup = path.join(root, "cleanup.json");
  fs.writeFileSync(binding, JSON.stringify(deviceBinding));
  fs.writeFileSync(ready, JSON.stringify({
    request_id: "request-1",
    envelope_digest: issued.envelope_digest,
    runtime_envelope: { ready_receipt_digest: issued.envelope_digest },
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

function bootstrapLoginHarness(name, curlBody) {
  const reporter = new URL("./stamp_report.sh", import.meta.url).pathname;
  const root = fs.mkdtempSync(path.join(os.tmpdir(), `stamp-login-${name}-`));
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(bin); fs.mkdirSync(runnerTemp); fs.mkdirSync(path.join(root, "qa-evidence"));
  const calls = path.join(root, "calls");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
printf -v call '%q ' "$@"
printf '%s\\n' "$call" >> "${calls}"
out=""; url=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2;;
    http*) url="$1"; shift;;
    *) shift;;
  esac
done
${curlBody}
`, { mode: 0o755 });
  fs.writeFileSync(path.join(runnerTemp, "stamp-claim-rc-x"), "claim-token", { mode: 0o600 });
  fs.writeFileSync(path.join(runnerTemp, "stamp-bootstrap-spec-rc-x.json"), JSON.stringify({
    required: true, contract_version: "qa-bootstrap-login/v1", login_endpoint: "/login",
    failure_terminal_endpoint: "/api/agent/run-cases/rc-x/auth-bootstrap/login-failure-terminal",
  }), { mode: 0o600 });
  const binding = path.join(root, "binding.json");
  fs.writeFileSync(binding, JSON.stringify({ signing_cert_sha256: `sha256:${"a".repeat(64)}`, boot_generation: "boot-1" }));
  const env = {
    PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
    RUNNER_TEMP: runnerTemp, STAMP_RUN_CASE_ID: "rc-x", STAMP_URL: "https://stamp.test", STAMP_TOKEN: "t",
    GITHUB_RUN_ID: "1", GITHUB_RUN_ATTEMPT: "1", GITHUB_REPOSITORY: "botiverse/mobile",
  };
  const phase = (...args) => {
    try { execFileSync("bash", [reporter, ...args], { cwd: root, env, stdio: "pipe" }); return 0; } catch (error) { return error.status; }
  };
  const outcomeFile = path.join(runnerTemp, "stamp-bootstrap-login-outcome-rc-x.json");
  const outcome = () => JSON.parse(fs.readFileSync(outcomeFile, "utf8"));
  const callLines = () => (fs.existsSync(calls) ? fs.readFileSync(calls, "utf8").trim().split("\n").filter(Boolean) : []);
  return {
    root, runnerTemp, env, reporter, binding, outcome, outcomeFile, callLines,
    login: () => phase("bootstrap-login", binding),
    failureTerminal: () => phase("bootstrap-failure-terminal", binding),
  };
}

const JOB_REF_X = "https://github.com/botiverse/mobile/actions/runs/1";
const rejectionReceipt = (overrides = {}) => JSON.stringify({
  contract: "qa-bootstrap-login-rejection/v1", issued: false, reason: "credential_rejected", run_case_id: "rc-x",
  attempt: 1, ci_job_ref: JOB_REF_X, signing_cert_sha256: `sha256:${"a".repeat(64)}`, boot_generation: "boot-1", ...overrides,
});
const X_BINDING = { signing_cert_sha256: `sha256:${"a".repeat(64)}`, boot_generation: "boot-1" };
const ISSUED = issuedLogin({ runCaseId: "rc-x", runId: "1", binding: X_BINDING });
const ISSUED_BODY = JSON.stringify(ISSUED);
// The shallow body b1c6af1d accepted as "issued" (Argus's replay): right field names, but no
// version/attempt/CI job/binding/access token/user, and a digest that is not the envelope's.
const SHALLOW_BODY = JSON.stringify({ request_id: "r", envelope: { run_case_id: "rc-x", refresh_token: "x" }, envelope_digest: `sha256:${"b".repeat(64)}`, expires_at: "soon" });
const withEnvelope = (edit) => { const v = structuredClone(ISSUED); edit(v.envelope); return JSON.stringify(v); };

test("bootstrap login is one call: no retry, and a lost reply is an unknown outcome that blocks the no-material terminal", () => {
  const scenario = (name, curlBody) => {
    const h = bootstrapLoginHarness(name, curlBody);
    const status = h.login();
    return { ...h, status, outcome: h.outcome() };
  };

  // Response lost after the request was sent (curl 52 empty reply): unknown, exit 3, exactly one call.
  const lost = scenario("lost", `printf 000; exit 52`);
  assert.equal(lost.status, 3);
  assert.equal(lost.outcome.outcome, "unknown");
  assert.equal(lost.outcome.retried, false);
  assert.equal(lost.outcome.github_run_attempt, "1");
  assert.equal(lost.callLines().length, 1);
  assert.doesNotMatch(lost.callLines()[0], /--retry/);
  assert.equal(fs.existsSync(path.join(lost.runnerTemp, "stamp-bootstrap-login-rc-x.json")), false);
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(lost.root, "qa-evidence", "bootstrap-login-outcome.json"), "utf8")), lost.outcome);
  // No second login for this run case, and no "no material was issued" terminal.
  assert.equal(lost.login(), 1);
  assert.equal(lost.failureTerminal(), 1);
  assert.equal(lost.callLines().length, 1, "neither a second login nor the no-material terminal may be called");

  // 5xx, a timeout and an unreadable 2xx may follow a committed issuance: unknown too.
  assert.equal(scenario("5xx", `printf 502; exit 0`).outcome.outcome, "unknown");
  assert.equal(scenario("timeout", `printf 000; exit 28`).outcome.outcome, "unknown");
  assert.equal(scenario("garbled", `printf '{"partial":' > "$out"; printf 200; exit 0`).outcome.outcome, "unknown");
  // A local connect failure is NOT "not sent": unknown, and the no-material terminal stays refused.
  const connect = scenario("connect", `printf 000; exit 7`);
  assert.equal(connect.outcome.outcome, "unknown");
  assert.equal(connect.outcome.detail, "curl_exit_7");
  assert.equal(connect.failureTerminal(), 1);
  assert.equal(connect.callLines().length, 1);

  const issued = scenario("issued", `printf '%s' '${ISSUED_BODY}' > "$out"; printf 200`);
  assert.equal(issued.status, 0);
  assert.equal(issued.outcome.outcome, "issued");
  assert.equal(fs.existsSync(path.join(issued.runnerTemp, "stamp-bootstrap-login-rc-x.json")), true);
  assert.equal(issued.login(), 1, "an issued run case is never issued again");
  assert.equal(issued.callLines().length, 1);
});

test("bootstrap login: generic 4xx (incl. 409 RETRY_REQUIRES_NEW_ATTEMPT) and partial 2xx are unknown, not 'nothing issued'", () => {
  for (const [name, body] of [
    ["409-retry", `printf '%s' '{"code":"BOOTSTRAP_LOGIN_RETRY_REQUIRES_NEW_ATTEMPT"}' > "$out"; printf 409`],
    ["409-empty", `printf 409`],
    ["400", `printf '%s' '{"code":"BAD_REQUEST"}' > "$out"; printf 400`],
    ["403-generic", `printf '%s' '{"error":"forbidden"}' > "$out"; printf 403`],
    // Receipt-shaped bodies that do not prove THIS attempt's rejection.
    ["receipt-other-case", `printf '%s' '${rejectionReceipt({ run_case_id: "rc-other" })}' > "$out"; printf 403`],
    ["receipt-other-job", `printf '%s' '${rejectionReceipt({ ci_job_ref: "https://github.com/botiverse/mobile/actions/runs/2" })}' > "$out"; printf 403`],
    ["receipt-other-binding", `printf '%s' '${rejectionReceipt({ boot_generation: "boot-2" })}' > "$out"; printf 403`],
    ["receipt-issued-true", `printf '%s' '${rejectionReceipt({ issued: true })}' > "$out"; printf 403`],
    ["receipt-extra-key", `printf '%s' '${rejectionReceipt({ note: "x" })}' > "$out"; printf 403`],
    ["receipt-on-200", `printf '%s' '${rejectionReceipt()}' > "$out"; printf 200`],
    // Partial / unproven 2xx.
    // Partial / unproven 2xx: everything must validate (shape, digest, run case, CI job, device binding).
    ["2xx-shallow (Argus)", `printf '%s' '${SHALLOW_BODY}' > "$out"; printf 200`],
    ["2xx-wrong-case", `printf '%s' '${withEnvelope((e) => { e.run_case_id = "rc-other"; })}' > "$out"; printf 200`],
    ["2xx-wrong-job", `printf '%s' '${withEnvelope((e) => { e.ci_job_ref = "https://github.com/botiverse/mobile/actions/runs/2"; })}' > "$out"; printf 200`],
    ["2xx-wrong-device", `printf '%s' '${withEnvelope((e) => { e.boot_generation = "boot-2"; })}' > "$out"; printf 200`],
    ["2xx-wrong-cert", `printf '%s' '${withEnvelope((e) => { e.signing_cert_sha256 = `sha256:${"f".repeat(64)}`; })}' > "$out"; printf 200`],
    ["2xx-digest-not-of-envelope", `printf '%s' '${JSON.stringify({ ...ISSUED, envelope_digest: `sha256:${"b".repeat(64)}` })}' > "$out"; printf 201`],
    ["2xx-no-refresh", `printf '%s' '${withEnvelope((e) => { e.refresh_token = ""; })}' > "$out"; printf 200`],
    ["2xx-missing-user", `printf '%s' '${withEnvelope((e) => { delete e.user; })}' > "$out"; printf 200`],
    ["2xx-no-expiry", `printf '%s' '${JSON.stringify({ ...ISSUED, expires_at: undefined })}' > "$out"; printf 200`],
    ["2xx-truncated-json", `printf '%s' '${ISSUED_BODY.slice(0, 150)}' > "$out"; printf 200`],
    ["2xx-empty", `printf 204`],
  ]) {
    const h = bootstrapLoginHarness(name, body);
    assert.equal(h.login(), 3, name);
    assert.equal(h.outcome().outcome, "unknown", name);
    assert.equal(fs.existsSync(path.join(h.runnerTemp, "stamp-bootstrap-login-rc-x.json")), false, name);
    assert.equal(h.failureTerminal(), 1, `${name}: the no-material terminal must stay refused`);
    assert.equal(h.callLines().length, 1, `${name}: no second call of any kind`);
  }
});

test("bootstrap login: no 4xx body is accepted as 'nothing issued' (receipt deferred until Testbed carries an authoritative attempt)", () => {
  // Argus's replay (review of b1c6af1d): an otherwise exact receipt for attempt 999 while the local
  // GitHub run attempt is 1 used to be "rejected" and then posted the no-material terminal.
  const h = bootstrapLoginHarness("different-attempt", `case "$url" in */login) printf '%s' '${rejectionReceipt({ attempt: 999 })}' > "$out"; printf 403;; *) printf 200;; esac; exit 0`);
  assert.equal(h.login(), 3);
  assert.equal(h.outcome().outcome, "unknown");
  assert.equal(h.outcome().detail, "unproven_4xx");
  assert.equal(h.failureTerminal(), 1);
  assert.equal(h.callLines().length, 1, "no no-material terminal call");
  // Even a receipt that matches every locally known value (attempt 1 = GITHUB_RUN_ATTEMPT) is unknown:
  // the GitHub run attempt is not the Testbed run-case attempt, and no trusted attempt exists yet.
  const exact = bootstrapLoginHarness("exact-receipt", `case "$url" in */login) printf '%s' '${rejectionReceipt()}' > "$out"; printf 403;; *) printf 200;; esac; exit 0`);
  assert.equal(exact.login(), 3);
  assert.equal(exact.outcome().outcome, "unknown");
  assert.equal(exact.failureTerminal(), 1);
  assert.equal(exact.callLines().length, 1);
  // Positive control: a run case where bootstrap-login was never attempted may still post the
  // no-material terminal (exactly one call, to the canonical endpoint).
  const never = bootstrapLoginHarness("never-attempted", `printf 200; exit 0`);
  assert.equal(never.failureTerminal(), 0);
  assert.equal(never.callLines().length, 1);
  assert.match(never.callLines()[0], /\/api\/agent\/run-cases\/rc-x\/auth-bootstrap\/login-failure-terminal/);
});

test("bootstrap login: the in_flight intent is persisted before the call; a killed sender never re-issues or claims 'not sent'", () => {
  // The fake endpoint records the intent it sees on disk, then the sender (the reporter, and its
  // command-substitution subshell when bash did not exec curl directly) is SIGKILLed mid-call.
  const h = bootstrapLoginHarness("killed", `cp "$RUNNER_TEMP/stamp-bootstrap-login-outcome-rc-x.json" "$RUNNER_TEMP/seen-intent.json"; p=$PPID; pp=$(ps -o ppid= -p "$p" | tr -d " "); case "$(ps -o args= -p "$pp")" in *stamp_report.sh*) kill -9 "$pp";; esac; kill -9 "$p"; sleep 5; printf 200`);
  assert.notEqual(h.login(), 0);
  const seen = JSON.parse(fs.readFileSync(path.join(h.runnerTemp, "seen-intent.json"), "utf8"));
  assert.equal(seen.outcome, "in_flight", "the intent must be on disk before issuance is called");
  assert.equal(seen.run_case_id, "rc-x");
  assert.equal(h.outcome().outcome, "in_flight", "a killed sender leaves in_flight, never a guessed outcome");
  assert.equal(h.login(), 1, "no automatic second issuance");
  assert.equal(h.failureTerminal(), 1, "no 'no material' terminal while the first call's fate is unknown");
  assert.equal(h.callLines().length, 1);

  // Killed after taking the lock but before the intent was written: still refused (lock is never broken).
  const early = bootstrapLoginHarness("early", `printf 200`);
  fs.mkdirSync(path.join(early.runnerTemp, "stamp-bootstrap-login-lock-rc-x"));
  assert.equal(early.login(), 1);
  assert.equal(early.failureTerminal(), 1);
  assert.equal(early.callLines().length, 0);
});

// Argus's replays against b1c6af1d (supplementary package).
test("independent: well-shaped but tampered 2xx is unknown, not issued", () => {
  const h = bootstrapLoginHarness("tampered", `printf '%s' '${SHALLOW_BODY}' > "$out"; printf 200`);
  assert.equal(h.login(), 3);
  assert.equal(h.outcome().outcome, "unknown");
  assert.equal(h.outcome().detail, "unproven_2xx");
  assert.equal(fs.existsSync(path.join(h.runnerTemp, "stamp-bootstrap-login-rc-x.json")), false, "never under the consumed name");
  assert.equal(fs.readFileSync(path.join(h.runnerTemp, "stamp-bootstrap-login-rc-x.json.partial"), "utf8"), SHALLOW_BODY, "body kept as evidence");
  // Positive control: the complete, digest-correct, bound response is issued and moved into place.
  const ok = bootstrapLoginHarness("complete", `printf '%s' '${ISSUED_BODY}' > "$out"; printf 200`);
  assert.equal(ok.login(), 0);
  assert.equal(ok.outcome().outcome, "issued");
  assert.equal(fs.readFileSync(path.join(ok.runnerTemp, "stamp-bootstrap-login-rc-x.json"), "utf8"), ISSUED_BODY);
  assert.equal(fs.existsSync(path.join(ok.runnerTemp, "stamp-bootstrap-login-rc-x.json.partial")), false);
});

test("independent: killed curl after writing partial body must never invoke logout cleanup as if issued", () => {
  for (const [name, body] of [["partial", ISSUED_BODY.slice(0, 184)], ["complete-but-unrecorded", ISSUED_BODY], ["shallow", SHALLOW_BODY]]) {
    const h = bootstrapLoginHarness(`killed-${name}`, `case "$url" in */login) printf '%s' '${body}' > "$out"; p=$PPID; pp=$(ps -o ppid= -p "$p" | tr -d " "); case "$(ps -o args= -p "$pp")" in *stamp_report.sh*) kill -9 "$pp";; esac; kill -9 "$p"; sleep 1;; */status) printf '%s' '{"contract_version":"qa-bootstrap-login/v1","required":true,"status":"issued"}' > "$out";; esac`);
    const spec = path.join(h.runnerTemp, "stamp-bootstrap-spec-rc-x.json");
    fs.writeFileSync(spec, JSON.stringify({ ...JSON.parse(fs.readFileSync(spec, "utf8")), status_endpoint: "/status", terminal_endpoint: "/terminal" }));
    assert.notEqual(h.login(), 0, name);
    assert.equal(h.outcome().outcome, "in_flight", name);
    const partial = path.join(h.runnerTemp, "stamp-bootstrap-login-rc-x.json.partial");
    assert.equal(fs.readFileSync(partial, "utf8"), body, name);
    assert.equal(fs.existsSync(path.join(h.runnerTemp, "stamp-bootstrap-login-rc-x.json")), false, name);
    const run = spawnSync("bash", [h.reporter, "bootstrap-failure-terminal", h.binding], { cwd: h.root, env: h.env, encoding: "utf8" });
    assert.equal(run.status, 1, name);
    assert.match(run.stderr, /operator reconciliation required/, name);
    assert.equal(h.callLines().length, 1, `${name}: no status, cleanup or terminal call after a killed sender`);
    assert.equal(fs.readFileSync(partial, "utf8"), body, `${name}: the received bytes are kept, not deleted`);
    assert.equal(h.login(), 1, `${name}: never re-issued`);
  }
});

test("bootstrap login: concurrent invocations call issuance exactly once", async () => {
  const { spawn } = await import("node:child_process");
  const h = bootstrapLoginHarness("concurrent", `printf '%s %s\\n' "$FAKE_CALLER" "$url" >> "$RUNNER_TEMP/issuers"; sleep 1; printf '%s' '${ISSUED_BODY}' > "$out"; printf 200`);
  const statuses = await Promise.all(Array.from({ length: 6 }, (_, index) => new Promise((resolve, reject) => {
    const env = { ...h.env, FAKE_CALLER: String(index) };
    const child = spawn("bash", [h.reporter, "bootstrap-login", h.binding], { cwd: h.root, env, stdio: "ignore" });
    child.once("error", reject);
    child.once("exit", resolve);
  })));
  const issuers = fs.readFileSync(path.join(h.runnerTemp, "issuers"), "utf8").trim().split("\n");
  assert.deepEqual(issuers, [`${statuses.indexOf(0)} https://stamp.test/login`], "record every sender that reached issuance");
  assert.equal(h.callLines().length, 1, "only one invocation may call the issuance endpoint");
  assert.deepEqual(statuses.slice().sort(), [0, 1, 1, 1, 1, 1]);
  assert.equal(h.outcome().outcome, "issued");
});

test("no-material bootstrap failure confirms only the exact server terminal binding", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-bootstrap-no-material-"));
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(bin); fs.mkdirSync(runnerTemp);
  const payload = path.join(root, "payload.json");
  const calls = path.join(root, "calls");
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
url=""; data=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --data-binary) data="$2"; shift 2;;
    http*) url="$1"; shift;;
    *) shift;;
  esac
done
[ "$url" = "https://stamp.test/api/agent/run-cases/rc-no-material/auth-bootstrap/login-failure-terminal" ] || exit 23
cp "\${data#@}" "${payload}"
node -e 'const v=require(process.argv[1]);if(Object.keys(v).sort().join(",")!=="boot_generation,signing_cert_sha256"||v.signing_cert_sha256!=="sha256:${"a".repeat(64)}"||v.boot_generation!=="boot-generation-1")process.exit(2)' "${payload}" || exit 24
printf '%s\n' terminal >> "${calls}"
`, { mode: 0o755 });
  fs.writeFileSync(path.join(runnerTemp, "stamp-claim-rc-no-material"), "claim-token", { mode: 0o600 });
  const spec = path.join(runnerTemp, "stamp-bootstrap-spec-rc-no-material.json");
  fs.writeFileSync(spec, JSON.stringify({ required: true,
    failure_terminal_endpoint: "/api/agent/run-cases/rc-no-material/auth-bootstrap/login-failure-terminal" }), { mode: 0o600 });
  const binding = path.join(root, "binding.json");
  fs.writeFileSync(binding, JSON.stringify({ signing_cert_sha256: `sha256:${"a".repeat(64)}`,
    boot_generation: "boot-generation-1", unrelated_local_fact: "must-not-forward" }), { mode: 0o600 });
  const env = {
    ...process.env, PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
    RUNNER_TEMP: runnerTemp, STAMP_RUN_CASE_ID: "rc-no-material", STAMP_URL: "https://stamp.test",
    STAMP_TOKEN: "token-that-must-not-enter-payload", GITHUB_RUN_ID: "902", GITHUB_REPOSITORY: "botiverse/mobile",
  };
  const reporter = new URL("./stamp_report.sh", import.meta.url).pathname;
  const output = execFileSync("bash", [reporter, "bootstrap-failure-terminal", binding], { cwd: root, env, encoding: "utf8" });
  assert.match(output, /canonical no-material bootstrap failure terminal confirmed/);
  assert.deepEqual(JSON.parse(fs.readFileSync(payload, "utf8")), {
    signing_cert_sha256: `sha256:${"a".repeat(64)}`, boot_generation: "boot-generation-1",
  });
  assert.equal(fs.readFileSync(payload, "utf8").includes("token-that-must-not-enter-payload"), false);
  assert.equal(fs.readFileSync(payload, "utf8").includes("unrelated_local_fact"), false);
  assert.equal(fs.readFileSync(calls, "utf8").trim(), "terminal");

  fs.writeFileSync(spec, JSON.stringify({ required: true }), { mode: 0o600 });
  assert.throws(() => execFileSync("bash", [reporter, "bootstrap-failure-terminal", binding], { cwd: root, env }));
  fs.writeFileSync(spec, JSON.stringify({ required: true,
    failure_terminal_endpoint: "/api/agent/run-cases/rc-no-material/auth-bootstrap/login-failure-terminal" }), { mode: 0o600 });
  fs.writeFileSync(binding, JSON.stringify({ signing_cert_sha256: "sha256:bad", boot_generation: "boot-generation-1" }));
  assert.throws(() => execFileSync("bash", [reporter, "bootstrap-failure-terminal", binding], { cwd: root, env }));
  assert.equal(fs.readFileSync(calls, "utf8").trim(), "terminal", "negative contracts must not call the endpoint");
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
});

test("READY response loss resolves server authority before choosing completed versus aborted cleanup", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-ready-loss-"));
  const runnerTemp = path.join(root, "runner-temp");
  const bin = path.join(root, "bin");
  fs.mkdirSync(runnerTemp); fs.mkdirSync(bin);
  const reporter = path.join(root, "stamp_report.sh");
  fs.copyFileSync(new URL("./stamp_report.sh", import.meta.url), reporter);
  fs.copyFileSync(new URL("./mobile_login_bootstrap_lib.mjs", import.meta.url), path.join(root, "mobile_login_bootstrap_lib.mjs"));
  fs.chmodSync(reporter, 0o755);
  const calls = path.join(root, "calls");
  const serverState = path.join(root, "server-state");
  fs.writeFileSync(serverState, "ready");
  fs.writeFileSync(path.join(root, "mobile_login_bootstrap.mjs"), `
import fs from "node:fs";
import { createHash } from "node:crypto";
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
  const lossBinding = { signing_cert_sha256: `sha256:${"d".repeat(64)}`, boot_generation: "boot-loss" };
  const writeLogin = () => fs.writeFileSync(login, JSON.stringify(issuedLogin({ runCaseId: "rc-loss", runId: "901", binding: lossBinding })), { mode: 0o600 });
  writeLogin(); fs.writeFileSync(binding, JSON.stringify(lossBinding)); fs.writeFileSync(ready, "{}");
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

  // Positive control: this runner's recorded "issued" outcome + a completely validated response.
  const outcomeFile = path.join(runnerTemp, "stamp-bootstrap-login-outcome-rc-loss.json");
  fs.writeFileSync(calls, ""); writeLogin();
  fs.writeFileSync(outcomeFile, JSON.stringify({ contract: "qa-bootstrap-login-outcome/v1", outcome: "issued" }));
  execFileSync(reporter, ["bootstrap-failure-terminal", binding, ready], { cwd: root, env });
  assert.deepEqual(fs.readFileSync(calls, "utf8").trim().split("\n"), ["status", "cleanup:aborted", "terminal"]);

  // Negative: killed between the rename and recording "issued" (outcome still in_flight): even a
  // valid response file does not drive status/cleanup, and it is not deleted.
  fs.writeFileSync(calls, ""); writeLogin();
  fs.writeFileSync(outcomeFile, JSON.stringify({ contract: "qa-bootstrap-login-outcome/v1", outcome: "in_flight" }));
  assert.throws(() => execFileSync(reporter, ["bootstrap-failure-terminal", binding, ready], { cwd: root, env, stdio: "pipe" }));
  assert.equal(fs.readFileSync(calls, "utf8"), "");
  assert.equal(fs.existsSync(login), true);
  // Negative: a recorded "issued" but a response that no longer validates (tampered/partial).
  fs.writeFileSync(outcomeFile, JSON.stringify({ contract: "qa-bootstrap-login-outcome/v1", outcome: "issued" }));
  fs.writeFileSync(login, JSON.stringify(issuedLogin({ runCaseId: "rc-loss", runId: "901", binding: lossBinding })).slice(0, 200), { mode: 0o600 });
  assert.throws(() => execFileSync(reporter, ["bootstrap-failure-terminal", binding, ready], { cwd: root, env, stdio: "pipe" }));
  assert.equal(fs.readFileSync(calls, "utf8"), "");
  assert.equal(fs.existsSync(login), true, "partial material is reconciliation evidence, never deleted");
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
  noLogin(evidence, "900", "1");
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

// ---- Round 4 (Argus, 41151acd): no product verdict before a proven device cleanup ----------------

function iosReportHarness(name, { cleanup, extraEnv = {}, ownership } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), `ios-report-${name}-`));
  const evidence = path.join(root, "qa-evidence"), bin = path.join(root, "bin"), temp = path.join(root, "temp");
  fs.mkdirSync(evidence); fs.mkdirSync(bin); fs.mkdirSync(temp);
  fs.writeFileSync(path.join(evidence, "verdict.json"), JSON.stringify({ verdict: "pass" }));
  noLogin(evidence, "222", "1");
  if (cleanup !== undefined) fs.writeFileSync(path.join(evidence, "simulator-cleanup.json"), typeof cleanup === "string" ? cleanup : JSON.stringify(cleanup));
  fs.writeFileSync(path.join(temp, "stamp-claim-rc-ios"), "fake-lease");
  if (ownership !== null) fs.writeFileSync(path.join(temp, "raft-qa-simulator-ownership.json"), JSON.stringify(ownership ?? IOS_OWNERSHIP), { mode: 0o400 });
  // Fake Testbed: accepts the FIRST verdict while running, answers 409 RUN_CASE_NOT_RUNNING afterwards.
  fs.writeFileSync(path.join(bin, "curl"), `#!${process.execPath}
const fs=require('fs');const args=process.argv.slice(2),root=${JSON.stringify(root)};let data='',out='',url='';
for(let i=0;i<args.length;i++){if(args[i]==='--data-binary')data=args[++i];else if(args[i]==='-d')data=args[++i];else if(args[i]==='-o')out=args[++i];else if(/^https:/.test(args[i]))url=args[i];}
if(url.includes('/verdict')){const body=data.startsWith('@')?fs.readFileSync(data.slice(1),'utf8'):data;const exists=fs.existsSync(root+'/first-verdict');
fs.appendFileSync(root+'/calls',JSON.stringify({phase:exists?'later':'first',verdict:JSON.parse(body).verdict,...(JSON.parse(body).phase?{error_phase:JSON.parse(body).phase}:{})})+'\\n');
if(!exists){fs.writeFileSync(root+'/first-verdict',body);if(out)fs.writeFileSync(out,'{}');if(args.includes('-w'))process.stdout.write('200');}
else{if(out)fs.writeFileSync(out,JSON.stringify({code:'RUN_CASE_NOT_RUNNING'}));if(args.includes('-w'))process.stdout.write('409');process.exitCode=22;}}
`, { mode: 0o755 });
  const env = { ...process.env, PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`, RUNNER_TEMP: temp,
    STAMP_RUN_CASE_ID: "rc-ios", STAMP_URL: "https://stamp.test", STAMP_TOKEN: "fake-test-token", GITHUB_RUN_ID: "222", GITHUB_RUN_ATTEMPT: "1", GITHUB_REPOSITORY: "botiverse/mobile", ...extraEnv };
  delete env.QA_TEST_EMAIL;
  delete env.RAFT_QA_SIMULATOR_OWNERSHIP_RECEIPT;
  const reporter = new URL("./stamp_report.sh", import.meta.url).pathname;
  const phase = (...args) => spawnSync("bash", [reporter, ...args], { cwd: root, env, encoding: "utf8" }).status;
  const calls = () => (fs.existsSync(path.join(root, "calls")) ? fs.readFileSync(path.join(root, "calls"), "utf8").trim().split("\n").map((l) => JSON.parse(l)) : []);
  return { phase, calls };
}

const IOS_ENV = { STAMP_REQUIRE_DEVICE_CLEANUP: "1", STAMP_DEVICE_CLEANUP_RECORD: "qa-evidence/simulator-cleanup.json", STAMP_DEVICE_CLEANUP_OUTCOME: "success" };
const IOS_UDID = "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0";
const IOS_OWNERSHIP = { contract: "raft-qa-simulator-ownership/v1", udid: IOS_UDID, name: "raft-qa-run-222-1", github_run_id: "222", github_run_attempt: "1", created_at: "2026-11-15T00:00:00Z" };
const DELETED = {
  platform: "ios-simulator", github_run_id: "222", github_run_attempt: "1", simulator_udid: IOS_UDID, simulator_name: "raft-qa-run-222-1",
  cleanup_receipt: "simulator_deleted", simulator: "deleted_and_absent", app_process: "unknown", server_session: "not_proven_by_device_cleanup",
};

test("review-extra: after fake simulator cleanup failure, iOS report submits product verdict before error", () => {
  // Argus's replay (41151acd): cleanup unknown, then `report` sent pass and the later workflow-error got 409.
  const h = iosReportHarness("unknown", { cleanup: { cleanup_receipt: "unknown", simulator: "unknown", app_process: "unknown", server_session: "not_proven_by_device_cleanup" } });
  assert.equal(h.phase("report", "qa-evidence"), 1);
  assert.equal(h.phase("workflow-error", "qa-evidence"), 0, "the later workflow-error is an idempotent no-op");
  assert.deepEqual(h.calls(), [{ phase: "first", verdict: "error", error_phase: "device_cleanup" }, { phase: "later", verdict: "error", error_phase: "post_claim" }]);
  assert.equal(h.calls().some((c) => c.verdict === "pass"), false, "never pass after an unproven cleanup");
});

test("report: device cleanup gate (missing or malformed record → error only; simulator_deleted → product verdict; Android unchanged)", () => {
  for (const [name, cleanup] of [
    ["missing record", undefined],
    ["unparseable record", "not json"],
    ["deleted claimed but simulator unknown", { ...DELETED, simulator: "unknown" }],
    ["legacy clean", { cleanup_receipt: "clean" }],
    // Round 5 (Argus, c92fe10c): a simulator_deleted record that is not THIS run's.
    ["another run's record", { ...DELETED, github_run_id: "111" }],
    ["another attempt's record", { ...DELETED, github_run_attempt: "2" }],
    ["another simulator's record", { ...DELETED, simulator_udid: "11111111-2222-3333-4444-555555555555" }],
    ["another name in the record", { ...DELETED, simulator_name: "raft-qa-run-111-1" }],
    ["record without run binding", { cleanup_receipt: "simulator_deleted", simulator: "deleted_and_absent" }],
  ]) {
    const h = iosReportHarness(name.replace(/\W+/g, "-"), { cleanup, extraEnv: IOS_ENV });
    assert.equal(h.phase("report", "qa-evidence"), 1, name);
    assert.deepEqual(h.calls().map((c) => c.verdict), ["error"], name);
  }
  // A matching record is not enough: the delete step itself must have succeeded.
  for (const outcome of ["failure", "cancelled", "skipped", ""]) {
    const h = iosReportHarness(`outcome-${outcome || "missing"}`, { cleanup: DELETED, extraEnv: { ...IOS_ENV, STAMP_DEVICE_CLEANUP_OUTCOME: outcome } });
    assert.equal(h.phase("report", "qa-evidence"), 1, outcome);
    assert.deepEqual(h.calls().map((c) => c.verdict), ["error"], `delete step outcome ${outcome || "missing"}`);
  }
  // This run's record but no (or another run's) ownership receipt: error only.
  for (const [name, ownership] of [["no ownership receipt", null], ["foreign ownership receipt", { ...IOS_OWNERSHIP, github_run_id: "111", name: "raft-qa-run-111-1" }]]) {
    const h = iosReportHarness(name.replace(/\W+/g, "-"), { cleanup: DELETED, extraEnv: IOS_ENV, ownership });
    assert.equal(h.phase("report", "qa-evidence"), 1, name);
    assert.deepEqual(h.calls().map((c) => c.verdict), ["error"], name);
  }
  // Positive control: deletion proven → the product verdict is sent (once).
  const ok = iosReportHarness("deleted", { cleanup: DELETED, extraEnv: IOS_ENV });
  assert.equal(ok.phase("report", "qa-evidence"), 0);
  assert.deepEqual(ok.calls(), [{ phase: "first", verdict: "pass" }]);
  // Android (no requirement, no record): unchanged.
  const android = iosReportHarness("android", {});
  assert.equal(android.phase("report", "qa-evidence"), 0);
  assert.deepEqual(android.calls(), [{ phase: "first", verdict: "pass" }]);
});

test("iOS workflow: delete → read record → report, and the report step requires the cleanup record", () => {
  const workflow = fs.readFileSync(path.resolve(".github/workflows/qa-acceptance-ios.yml"), "utf8");
  const at = (name) => workflow.indexOf(`- name: ${name}\n`);
  for (const name of ["Collect harness log + system log + crashes + xcresult", "Collect perf summary", "Delete run-owned simulator", "Report to Stamp", "Upload evidence"]) {
    assert.ok(at(name) > 0, name);
  }
  assert.ok(at("Collect perf summary") < at("Delete run-owned simulator"));
  assert.ok(at("Delete run-owned simulator") < at("Report to Stamp"));
  assert.ok(at("Report to Stamp") < at("Upload evidence"));
  const report = workflow.slice(at("Report to Stamp"), at("Upload evidence"));
  assert.match(report, /STAMP_REQUIRE_DEVICE_CLEANUP: "1"/);
  assert.match(report, /STAMP_DEVICE_CLEANUP_RECORD: qa-evidence\/simulator-cleanup\.json/);
  assert.match(report, /STAMP_DEVICE_CLEANUP_OUTCOME: \$\{\{ steps\.delete_simulator\.outcome \}\}/);
  assert.match(workflow.slice(at("Delete run-owned simulator")), /^- name: Delete run-owned simulator\n\s+id: delete_simulator\n/);
  const del = workflow.slice(at("Delete run-owned simulator"), at("Report to Stamp"));
  assert.match(del, /delete-simulator[\s\S]*--output qa-evidence\/simulator-cleanup\.json/);
});
