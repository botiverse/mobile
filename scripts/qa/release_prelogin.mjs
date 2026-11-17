#!/usr/bin/env node
// Release pre-login for ORDINARY Testbed cases on release (non-debuggable) Android builds.
// Contract: docs/qa-acceptance/release-prelogin-contract.md. Rules: release_prelogin_lib.mjs.
//
//   plan     outside the emulator: is this case allowed (allow-list + never classes + release
//            build)? If so, a fresh attempt id + nonce bound to the frozen account/server.
//            Refused: exit 3 and refusal.json (the case is NOT run with a pre-login).
//   login    inside the emulator job, before the recorder / agent: capture off, the readiness
//            receiver enabled, release_auto_login.mjs --no-capture --defer-app-session
//            --strict-cleanup, the frozen server picked, then the app's own readiness receipt.
//            Writes receipt-capture.json, receipt-callback.json, receipt-ready.json and, only when
//            all three are good, a complete login-record.json for login_phase.mjs / the evidence
//            gate (mode "release-prelogin"). Exit 0 only then.
//   cleanup  always, after the case: the app's own Sign out (release_auto_login --phase sign-out),
//            the receiver disabled again; receipt-cleanup.json.
//   verdict  outcome.json + outcome.rc from the receipts and the agent's rc (0 pass, 1 fail,
//            3 unknown / not run).
//
// Credentials come only from QA_TEST_EMAIL / QA_TEST_PASSWORD in the environment and go only to
// the sign-in child (as QA_LOGIN_EMAIL / QA_LOGIN_PASSWORD); nothing here prints or writes them.
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { writeRecord, markUnknown, parseUiNodes } from "./login_phase.mjs";
import {
  RECEIVER_CLASS,
  EXTRA_ATTEMPT_ID,
  EXTRA_NONCE,
  READINESS_TAG,
  attemptOutcome,
  caseMetaFromYaml,
  componentStateFromDumpsys,
  newAttempt,
  preloginEligibility,
  receiptsLeak,
  runPreloginCleanup,
  runPreloginLogin,
  validateAttempt,
} from "./release_prelogin_lib.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const option = (name, fallback = "") => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] !== undefined && !argv[i + 1].startsWith("--") ? argv[i + 1] : fallback;
};
const OUT = option("out", "qa-prelogin");
const readJson = (file) => JSON.parse(fs.readFileSync(file, "utf8"));
const readJsonOr = (file, fallback = null) => { try { return readJson(file); } catch { return fallback; } };
const writeJson = (name, value) => {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, name), `${JSON.stringify(value, null, 2)}\n`);
};
const secrets = () => [process.env.QA_TEST_EMAIL, process.env.QA_TEST_PASSWORD, process.env.QA_LOGIN_EMAIL, process.env.QA_LOGIN_PASSWORD];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- plan ----------
function plan() {
  const caseId = option("case-id");
  const caseFile = option("case-file");
  const allowList = readJsonOr(option("allow-list", path.join(HERE, "release_prelogin_allowlist.json")));
  const debuggable = option("build-debuggable");
  const caseMeta = caseFile && fs.existsSync(caseFile) ? caseMetaFromYaml(fs.readFileSync(caseFile, "utf8")) : {};
  if (caseMeta.id && caseMeta.id !== caseId) return refuse({ eligible: false, reason: "case_file_id_mismatch" });
  const eligibility = preloginEligibility({
    caseId,
    caseMeta,
    allowList,
    buildDebuggable: debuggable === "true" ? true : debuggable === "false" ? false : undefined,
  });
  if (!eligibility.eligible) return refuse(eligibility);
  let attempt;
  try {
    attempt = newAttempt({
      caseId,
      runCaseId: option("run-case-id"),
      target: JSON.parse(option("target", "{}")),
      workflow: { runId: process.env.GITHUB_RUN_ID ?? null, runAttempt: process.env.GITHUB_RUN_ATTEMPT ?? null },
    });
  } catch (error) {
    return refuse({ eligible: false, reason: `target_invalid: ${error.message}` });
  }
  writeJson("eligibility.json", eligibility);
  writeJson("attempt.json", attempt);
  console.log(`release pre-login: case ${caseId} allowed; attempt ${attempt.attempt_id}`);
  return 0;
}
function refuse(eligibility) {
  writeJson("eligibility.json", eligibility);
  writeJson("refusal.json", { ...eligibility, note: "this case is not run with a release pre-login" });
  console.error(`release pre-login refused: ${eligibility.reason}${eligibility.class ? ` (${eligibility.class})` : ""}`);
  return 3;
}

// ---------- adb-backed device ----------
function adbDevice({ pkg, env: serverEnv = "staging" }) {
  const adb = (args, { input, allowFailure = true, timeoutMs = 60_000 } = {}) => {
    const r = spawnSync("adb", args, { encoding: "utf8", input, timeout: timeoutMs, maxBuffer: 64 * 1024 * 1024 });
    if (!allowFailure && (r.error || r.status !== 0)) throw new Error(`adb ${args.slice(0, 2).join(" ")} failed`);
    return String(r.stdout ?? "");
  };
  const shell = (cmd, opts) => adb(["shell", cmd], opts);
  const recorderRunning = () => !/rc=1\s*$/.test(shell("pidof screenrecord; echo rc=$?"));
  const component = `${pkg}/${RECEIVER_CLASS}`;
  const componentEnabledState = () => componentStateFromDumpsys(shell(`dumpsys package ${pkg}`), RECEIVER_CLASS);
  const runAutoLogin = (flags, env) => {
    const sumDir = path.join(OUT, flags.includes("sign-out") ? "sign-out-run" : "sign-in-run");
    fs.rmSync(sumDir, { recursive: true, force: true });
    spawnSync(process.execPath, [path.join(HERE, "release_auto_login.mjs"), "--package", pkg, "--out", sumDir, ...flags], {
      env, stdio: ["ignore", "inherit", "inherit"], timeout: 15 * 60_000,
    });
    return readJsonOr(path.join(sumDir, "summary.json"));
  };
  return {
    captureCount: () => 0, // this harness takes no screenshot / recording / dump of its own in the window
    recorderRunning: async () => recorderRunning(),
    stopRecorder: async () => {
      shell("pkill -INT screenrecord; true");
      for (let i = 0; i < 5 && recorderRunning(); i += 1) await sleep(1000);
      return !recorderRunning();
    },
    enableReceiver: async () => {
      shell(`pm enable ${component}`);
      return componentEnabledState() === "enabled";
    },
    disableReceiver: async () => {
      shell(`pm disable ${component}`);
      // "default" is the manifest's android:enabled="false".
      return ["disabled", "default"].includes(componentEnabledState());
    },
    runSignIn: async (flags) => {
      const env = { ...process.env, QA_LOGIN_EMAIL: process.env.QA_TEST_EMAIL ?? "", QA_LOGIN_PASSWORD: process.env.QA_TEST_PASSWORD ?? "" };
      return runAutoLogin([...flags, "--env", serverEnv, "--capture-browser-apk", option("capture-browser-apk")], env);
    },
    runSignOut: async () => {
      const env = { ...process.env };
      for (const k of ["QA_TEST_EMAIL", "QA_TEST_PASSWORD", "QA_LOGIN_EMAIL", "QA_LOGIN_PASSWORD"]) delete env[k];
      return runAutoLogin(["--phase", "sign-out", "--revoke-app-session", "--strict-cleanup", "--no-capture"], env);
    },
    // Choose server: tap the frozen server's exact name (exactly one match), from an in-memory UI
    // tree (never written to a file). Home already: nothing to do.
    selectServer: async (name) => {
      for (let i = 0; i < 20; i += 1) {
        const nodes = parseUiNodes(adb(["exec-out", "uiautomator", "dump", "/dev/tty"]));
        const mine = nodes.filter((n) => n.pkg === pkg);
        if (mine.some((n) => n.text === "Drafts")) return;
        const hits = mine.filter((n) => n.text === name && n.bounds);
        if (hits.length === 1) {
          const [x1, y1, x2, y2] = hits[0].bounds;
          shell(`input tap ${(x1 + x2) >> 1} ${(y1 + y2) >> 1}`);
        }
        await sleep(2000);
      }
    },
    appProcess: async () => {
      const pids = shell(`pidof ${pkg}`).trim().split(/\s+/).filter(Boolean);
      if (pids.length !== 1 || !/^\d+$/.test(pids[0])) return null;
      const stat = shell(`cat /proc/${pids[0]}/stat`);
      const start = stat.slice(stat.lastIndexOf(")") + 2).split(" ")[19];
      return /^\d+$/.test(start ?? "") ? `${pids[0]}:${start}` : null;
    },
    requestReadiness: async ({ attemptId, nonce }) => {
      // Explicit component; the receiver is DUMP-protected (adb's shell holds DUMP).
      shell(`am broadcast -n ${component} --es ${EXTRA_ATTEMPT_ID} ${attemptId} --es ${EXTRA_NONCE} ${nonce} >/dev/null 2>&1`);
    },
    readReadinessLog: async () => adb(["logcat", "-d", "-v", "threadtime", "-s", `${READINESS_TAG}:I`]),
    installed: async () => {
      const dump = shell(`dumpsys package ${pkg}`);
      return {
        package: pkg,
        version_name: /versionName=(\S+)/.exec(dump)?.[1] ?? null,
        version_code: Number(/versionCode=(\d+)/.exec(dump)?.[1] ?? NaN),
      };
    },
    sleep,
  };
}

// ---------- login ----------
async function login() {
  const evidence = option("evidence", "qa-evidence");
  const attempt = validateAttempt(readJson(path.join(OUT, "attempt.json")));
  const result = await runPreloginLogin({ attempt, device: adbDevice({ pkg: option("package", "build.raft.app"), env: attempt.target.env }) });
  const strip = ({ signInSummary, ...rest }) => rest;
  writeJson("receipt-capture.json", { attempt_id: attempt.attempt_id, ...result.capture });
  writeJson("receipt-callback.json", { attempt_id: attempt.attempt_id, ...result.callback });
  writeJson("receipt-ready.json", { attempt_id: attempt.attempt_id, nonce: attempt.nonce, target: attempt.target, ...result.readiness });
  if (result.signInSummary) writeJson("sign-in-summary.json", result.signInSummary);
  if (receiptsLeak([JSON.stringify(strip(result)), JSON.stringify(result.signInSummary ?? {})], secrets())) {
    fs.rmSync(OUT, { recursive: true, force: true });
    markUnknown(evidence, "release pre-login: a receipt carried a secret; receipts withheld");
    console.error("release pre-login: UNKNOWN (a receipt carried a secret)");
    return 1;
  }
  if (!result.ok) {
    markUnknown(evidence, `release pre-login: capture ${result.capture?.status}, callback ${result.callback.reason}, readiness ${result.readiness.reason}`);
    console.error(`release pre-login: UNKNOWN (capture=${result.capture?.reason} callback=${result.callback.reason} readiness=${result.readiness.reason})`);
    return 1;
  }
  writeRecord(evidence, { mode: "release-prelogin", status: "complete", login_end: result.loginEnd, attempt_id: attempt.attempt_id, reason: "release pre-login receipts ok" });
  console.log(`release pre-login: OK (attempt ${attempt.attempt_id})`);
  return 0;
}

// ---------- cleanup ----------
async function cleanup() {
  const attempt = readJsonOr(path.join(OUT, "attempt.json"));
  const signInSummary = readJsonOr(path.join(OUT, "sign-in-summary.json"));
  const receipt = await runPreloginCleanup({ signInSummary, device: adbDevice({ pkg: option("package", "build.raft.app"), env: attempt?.target?.env }) });
  writeJson("receipt-cleanup.json", { attempt_id: attempt?.attempt_id ?? null, ...receipt });
  console.log(`release pre-login cleanup: ${receipt.status}${receipt.unproven.length ? ` (unproven: ${receipt.unproven.join(",")})` : ""}`);
  return receipt.status === "complete" ? 0 : 1;
}

// ---------- verdict ----------
function verdict() {
  const r = (name) => readJsonOr(path.join(OUT, name));
  const agentRc = (() => { try { return fs.readFileSync(option("agent-rc"), "utf8").trim(); } catch { return ""; } })();
  const outcome = attemptOutcome({
    eligibility: r("eligibility.json"),
    capture: r("receipt-capture.json"),
    callback: r("receipt-callback.json"),
    readiness: r("receipt-ready.json"),
    cleanup: r("receipt-cleanup.json"),
    agentRc: agentRc === "" ? NaN : Number(agentRc),
  });
  const attempt = r("attempt.json");
  writeJson("outcome.json", { attempt_id: attempt?.attempt_id ?? null, case_id: attempt?.case_id ?? null, workflow: attempt?.workflow ?? null, ...outcome });
  fs.writeFileSync(path.join(OUT, "outcome.rc"), `${outcome.rc}\n`);
  // The receipts are uploaded as their own artifact: none may carry a QA secret (fails closed).
  const files = fs.readdirSync(OUT, { recursive: true }).map((f) => path.join(OUT, String(f))).filter((f) => fs.statSync(f).isFile());
  if (receiptsLeak(files.map((f) => fs.readFileSync(f, "utf8")), secrets())) {
    fs.rmSync(OUT, { recursive: true, force: true });
    writeJson("outcome.json", { outcome: "unknown", rc: 3, reason: "a receipt carried a secret; receipts withheld" });
    fs.writeFileSync(path.join(OUT, "outcome.rc"), "3\n");
  }
  console.log(`release pre-login outcome: ${outcome.outcome} (${outcome.reason})`);
  return 0;
}

// Any throw in the login window leaves an UNKNOWN record (never a complete one), without echoing
// the error text (a tool's message could carry what it was given).
async function guardedLogin() {
  try {
    return await login();
  } catch (error) {
    markUnknown(option("evidence", "qa-evidence"), `release pre-login failed (${error?.code || error?.name || "error"})`);
    console.error("release pre-login: UNKNOWN (the login step failed)");
    return 1;
  }
}

const commands = { plan, login: guardedLogin, cleanup, verdict };
if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  const command = commands[argv[0]];
  if (!command) {
    console.error("usage: release_prelogin.mjs plan|login|cleanup|verdict [options]");
    process.exit(2);
  }
  process.exitCode = await command();
}
