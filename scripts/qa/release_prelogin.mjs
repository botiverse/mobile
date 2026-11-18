#!/usr/bin/env node
// Release pre-login for ORDINARY Testbed cases on a QA-instrumented release candidate
// (build type qaRelease). Contract: docs/qa-acceptance/release-prelogin-contract.md. Rules:
// release_prelogin_lib.mjs. Every reading that is missing or failed counts as unknown.
//
//   plan        (outside the emulator) Accepts ONLY a Testbed-frozen spec whose sha256 the Stamp
//               claim of this run case returns. It checks the goal, the artifact (sha256 + aapt
//               badging, debuggable known false), the case bytes and the Mobile allow-list (which
//               can only narrow). It mints nothing: the attempt id + nonce are Testbed's.
//               Refused: refusal.json, exit 3.
//   login       (emulator) Writes login-started.json first, then runs the login window: recorder
//               stopped + watched, device marker, receiver enabled + read back, sign-in with nothing
//               written to disk, frozen server picked, app readiness for a fresh request id. Writes
//               receipt-capture/-callback/-ready.json and, only when all are good, a complete
//               login-record.json. Credentials only from QA_PRELOGIN_EMAIL / QA_PRELOGIN_PASSWORD
//               (not wired in any workflow yet: separate review, see the contract doc).
//   cleanup     (emulator, always) The app's own Sign out, the receiver disabled + read back:
//               receipt-cleanup.json.
//   verdict     (emulator) outcome.json + outcome.rc from the receipts, agent rc and verdict.json.
//   finalize    (after the emulator, always) Records "cleanup not reached" when the emulator step
//               died before cleanup, recomputes the outcome, then SEALS qa-prelogin/ for upload:
//               only allow-listed files that pass uploadProblem, otherwise everything is withheld.
//   report-gate (Report verdict) Prints the rc the reporter may use: 0 / 1 only when outcome.json
//               matches the receipts and the agent's result now; else 3.
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { writeRecord, markUnknown, parseUiNodes } from "./login_phase.mjs";
import {
  EXTRA_ATTEMPT_ID,
  EXTRA_NONCE,
  EXTRA_REQUEST_ID,
  READINESS_TAG,
  RECEIVER_CLASS,
  UPLOAD_FILES,
  attemptFromSpec,
  attemptOutcome,
  caseMetaFromYaml,
  componentStateFromDumpsys,
  installedFromDumpsys,
  privateTargetFromSpec,
  sealManifest,
  verifySealed,
  preloginEligibility,
  receiptsLeak,
  reportGate,
  runPreloginCleanup,
  runPreloginLogin,
  sha256Hex,
  uploadProblem,
  validateAttempt,
} from "./release_prelogin_lib.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const option = (name, fallback = "") => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] !== undefined && !argv[i + 1].startsWith("--") ? argv[i + 1] : fallback;
};
const OUT = option("out", "qa-prelogin");
const SEALED = option("sealed", `${OUT}-sealed`);
const currentJobRef = () => `${process.env.GITHUB_SERVER_URL || "https://github.com"}/${process.env.GITHUB_REPOSITORY || ""}/actions/runs/${process.env.GITHUB_RUN_ID || ""}`;
const PRIVATE = option("private-dir", path.join(process.env.RUNNER_TEMP || os.tmpdir(), "qa-prelogin-private"));
const readJsonOr = (file, fallback = null) => { try { return JSON.parse(fs.readFileSync(file, "utf8")); } catch { return fallback; } };
const secrets = () => [process.env.QA_PRELOGIN_EMAIL, process.env.QA_PRELOGIN_PASSWORD, process.env.QA_TEST_EMAIL, process.env.QA_TEST_PASSWORD, process.env.QA_LOGIN_EMAIL, process.env.QA_LOGIN_PASSWORD];
// Every receipt write is checked against its strict schema and the secret values this process can
// see; a problem writes nothing (and the caller ends unknown).
const writeJson = (name, value) => {
  const text = `${JSON.stringify(value, null, 2)}\n`;
  if (receiptsLeak([text], secrets())) throw new Error(`receipt ${name} carried a secret; not written`);
  const problem = uploadProblem(name, text);
  if (problem) throw new Error(`receipt ${name} does not match its schema (${problem}); not written`);
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, name), text, { mode: 0o600 });
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const agentResult = () => {
  const rcText = (() => { try { return fs.readFileSync(option("agent-rc"), "utf8").trim(); } catch { return ""; } })();
  const verdict = readJsonOr(option("verdict"))?.verdict ?? null;
  return { agentRc: /^\d+$/.test(rcText) ? Number(rcText) : NaN, agentVerdict: typeof verdict === "string" ? verdict : null };
};

// ---------- plan ----------
function apkIdentity(apk, aapt) {
  if (!apk || !fs.existsSync(apk)) return { ok: false };
  const r = spawnSync(aapt, ["dump", "badging", apk], { encoding: "utf8", maxBuffer: 16 * 1024 * 1024 });
  if (r.error || r.status !== 0) return { ok: false };
  const out = String(r.stdout);
  const pkg = /package: name='([^']+)'/.exec(out);
  if (!pkg) return { ok: false };
  return {
    ok: true,
    sha256: sha256Hex(fs.readFileSync(apk)),
    package_id: pkg[1],
    version_name: /versionName='([^']*)'/.exec(out)?.[1] ?? null,
    version_code: Number(/versionCode='(\d+)'/.exec(out)?.[1] ?? NaN),
    debuggable: /^application-debuggable\b/m.test(out),
  };
}

function plan() {
  let spec = null;
  try { spec = JSON.parse(process.env.RELEASE_PRELOGIN || "null"); } catch {}
  // The minimal binding stamp_report.sh claim keeps from the authenticated claim response (never
  // the token or the response): it must name this run case and the CI job that claimed.
  const binding = readJsonOr(option("claim-binding"));
  const bindingOk = binding && binding.contract === "raft-release-prelogin-claim-binding/v1" && binding.run_case_id === option("run-case-id") && binding.ci_job_ref === currentJobRef();
  const casesDir = option("cases-dir", path.join(HERE, "../../docs/qa-acceptance/cases"));
  const caseId = typeof spec?.case?.id === "string" ? spec.case.id : "";
  const matches = caseId && fs.existsSync(casesDir)
    ? fs.readdirSync(casesDir).filter((f) => f.endsWith(".yaml")).map((f) => path.join(casesDir, f)).filter((f) => caseMetaFromYaml(fs.readFileSync(f, "utf8")).id === caseId)
    : [];
  const bootstrap = option("bootstrap-required");
  const eligibility = preloginEligibility({
    spec,
    claimRelease: bindingOk ? { spec_sha256: binding.spec_sha256, attempt_id: binding.attempt_id } : null,
    runCaseId: option("run-case-id"),
    goal: process.env.QA_GOAL ?? "",
    artifactSha256Input: process.env.ARTIFACT_SHA256 ?? "",
    build: apkIdentity(option("apk"), option("aapt", "aapt")),
    bootstrapRequired: bootstrap === "true" ? true : bootstrap === "false" ? false : undefined,
    caseBytes: matches.length === 1 ? fs.readFileSync(matches[0]) : null,
    allowList: readJsonOr(option("allow-list", path.join(HERE, "release_prelogin_allowlist.json"))),
  });
  writeJson("eligibility.json", eligibility);
  if (!eligibility.eligible) {
    writeJson("refusal.json", { ...eligibility, note: "this case is not run with a release pre-login" });
    console.error(`release pre-login refused: ${eligibility.reason}${eligibility.class ? ` (${eligibility.class})` : ""}`);
    return 3;
  }
  const attempt = attemptFromSpec(spec, { workflow: { runId: process.env.GITHUB_RUN_ID ?? null, runAttempt: process.env.GITHUB_RUN_ATTEMPT ?? null } });
  writeJson("attempt.json", attempt);
  // Cleartext target (server name for the in-memory pick) only in the private runner dir.
  fs.mkdirSync(PRIVATE, { recursive: true, mode: 0o700 });
  fs.writeFileSync(path.join(PRIVATE, "target.json"), JSON.stringify(privateTargetFromSpec(spec)), { mode: 0o600 });
  console.log(`release pre-login: Testbed attempt ${attempt.attempt_id} bound to workflow run ${attempt.workflow.run_id}/${attempt.workflow.run_attempt}`);
  return 0;
}

// ---------- adb-backed device ----------
const WINDOW_MARKER = "/data/local/tmp/qa-prelogin-window";
function adbDevice({ pkg, serverEnv, nonce }) {
  let captures = 0;
  const adb = (args, { input, timeoutMs = 60_000 } = {}) => {
    const joined = args.join(" ");
    if (/\bscreencap\b|\bscreenrecord\b(?!;)|uiautomator dump (?!\/dev\/tty)/.test(joined) && !/pidof screenrecord|pkill -INT screenrecord/.test(joined)) captures += 1;
    const r = spawnSync("adb", args, { encoding: "utf8", input, timeout: timeoutMs, maxBuffer: 64 * 1024 * 1024 });
    return { ok: !r.error && r.status === 0, out: String(r.stdout ?? "") };
  };
  const shell = (cmd, opts) => adb(["shell", cmd], opts);
  const recorderState = (text) => (/rc=0\s*$/.test(text) ? true : /rc=1\s*$/.test(text) ? false : null);
  const recorderRunning = () => recorderState(shell("pidof screenrecord; echo rc=$?").out);
  const component = `${pkg}/${RECEIVER_CLASS}`;
  const componentState = () => {
    const r = shell(`dumpsys package ${pkg}`);
    return r.ok ? componentStateFromDumpsys(r.out, RECEIVER_CLASS, pkg) : "unknown";
  };
  // The sign-in / sign-out child, run asynchronously so the recorder watch keeps polling. Its
  // summary goes to the PRIVATE dir (never uploaded). Bounded: SIGTERM at the limit, SIGKILL 10 s later.
  const runAutoLogin = (flags, env, label) => new Promise((resolve) => {
    const dir = path.join(PRIVATE, label);
    fs.rmSync(dir, { recursive: true, force: true });
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    const child = spawn(process.execPath, [path.join(HERE, "release_auto_login.mjs"), "--package", pkg, "--out", dir, ...flags], { env, stdio: ["ignore", "inherit", "inherit"] });
    const term = setTimeout(() => child.kill("SIGTERM"), 15 * 60_000);
    const kill = setTimeout(() => child.kill("SIGKILL"), 15 * 60_000 + 10_000);
    child.on("close", () => {
      clearTimeout(term);
      clearTimeout(kill);
      resolve(readJsonOr(path.join(dir, "summary.json")));
    });
  });
  return {
    captureCount: () => captures,
    recorderRunning: async () => recorderRunning(),
    stopRecorder: async () => {
      shell("pkill -INT screenrecord; true");
      for (let i = 0; i < 5 && recorderRunning() !== false; i += 1) await sleep(1000);
      return recorderRunning() === false;
    },
    startWindow: async () => /ok\s*$/.test(shell(`rm -f ${WINDOW_MARKER} && touch ${WINDOW_MARKER} && echo ok`).out),
    // Polls `pidof screenrecord` every second without blocking (async adb), for the whole window.
    watchRecorder: () => {
      const state = { polls: 0, seen: false, failed: false };
      let busy = false;
      const timer = setInterval(() => {
        if (busy) return;
        busy = true;
        const p = spawn("adb", ["shell", "pidof screenrecord; echo rc=$?"], { stdio: ["ignore", "pipe", "ignore"] });
        let text = "";
        p.stdout.on("data", (d) => { text += d; });
        p.on("close", () => {
          busy = false;
          state.polls += 1;
          const s = recorderState(text);
          if (s === true) state.seen = true;
          if (s === null) state.failed = true;
        });
      }, 1000);
      return { stop: async () => { clearInterval(timer); while (busy) await sleep(100); return { ...state }; } };
    },
    // Files on shared storage written since the window marker (app-private Android/ excluded). null
    // when the scan itself failed: a missing reading, never "nothing written".
    filesWrittenSince: async () => {
      const r = shell(`cd /sdcard/ && find . -path ./Android -prune -o -type f -newer ${WINDOW_MARKER} -print; echo rc=$?`);
      if (!r.ok || !/rc=0\s*$/.test(r.out)) return null;
      return r.out.split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("rc=")).map((l) => path.basename(l));
    },
    // The capture browser's private pending-login-URL file. Uninstalling the stub removes its private
    // data dir; otherwise delete the file and read its absence back. Any failed reading: unknown.
    captureBrowserFile: async () => {
      const stub = "build.qa.capturebrowser";
      const listed = () => {
        const r = shell(`pm list packages ${stub}; echo rc=$?`);
        if (!/rc=0\s*$/.test(r.out)) return null;
        return r.out.split("\n").some((l) => l.trim() === `package:${stub}`);
      };
      let present = listed();
      if (present === null) return "unknown";
      if (!present) return "stub_absent";
      shell(`run-as ${stub} rm -f files/captured_url files/captured_url.tmp`);
      const state = shell(`run-as ${stub} sh -c 'if [ -e files/captured_url ] || [ -e files/captured_url.tmp ]; then echo present; else echo absent; fi'`).out.trim();
      shell(`pm uninstall ${stub}`);
      present = listed();
      if (present === false) return "stub_absent";
      return state.endsWith("absent") ? "deleted_verified" : "unknown";
    },
    enableReceiver: async () => {
      shell(`pm enable ${component}`);
      return componentState();
    },
    disableReceiver: async () => {
      shell(`pm disable ${component}`);
      return componentState();
    },
    runSignIn: async (flags) => {
      const env = { ...process.env, QA_LOGIN_EMAIL: process.env.QA_PRELOGIN_EMAIL ?? "", QA_LOGIN_PASSWORD: process.env.QA_PRELOGIN_PASSWORD ?? "" };
      for (const k of ["QA_TEST_EMAIL", "QA_TEST_PASSWORD"]) delete env[k];
      return runAutoLogin([...flags, "--env", serverEnv, "--capture-browser-apk", option("capture-browser-apk")], env, "sign-in-run");
    },
    runSignOut: async () => {
      const env = { ...process.env };
      for (const k of ["QA_TEST_EMAIL", "QA_TEST_PASSWORD", "QA_LOGIN_EMAIL", "QA_LOGIN_PASSWORD", "QA_PRELOGIN_EMAIL", "QA_PRELOGIN_PASSWORD"]) delete env[k];
      return runAutoLogin(["--phase", "sign-out", "--revoke-app-session", "--strict-cleanup", "--no-capture"], env, "sign-out-run");
    },
    // Choose server: tap the frozen server's exact name (exactly one match), from an in-memory UI
    // tree (uiautomator's stdout; nothing written on the device). Home already: nothing to do.
    selectServer: async (name) => {
      for (let i = 0; i < 20; i += 1) {
        const nodes = parseUiNodes(adb(["exec-out", "uiautomator", "dump", "/dev/tty"]).out);
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
      const p = shell(`pidof ${pkg}`);
      const pids = p.ok ? p.out.trim().split(/\s+/).filter(Boolean) : [];
      if (pids.length !== 1 || !/^\d+$/.test(pids[0])) return null;
      const stat = shell(`cat /proc/${pids[0]}/stat`);
      if (!stat.ok) return null;
      const start = stat.out.slice(stat.out.lastIndexOf(")") + 2).split(" ")[19];
      return /^\d+$/.test(start ?? "") ? `${pids[0]}:${start}` : null;
    },
    newRequestId: () => crypto.randomBytes(16).toString("hex"),
    requestReadiness: async ({ attemptId, nonce: n, requestId }) => {
      // Explicit component; the receiver is DUMP-protected (adb's shell holds DUMP).
      shell(`am broadcast -n ${component} --es ${EXTRA_ATTEMPT_ID} ${attemptId} --es ${EXTRA_NONCE} ${n} --es ${EXTRA_REQUEST_ID} ${requestId} >/dev/null 2>&1`);
    },
    readReadinessLog: async () => {
      const r = adb(["logcat", "-d", "-v", "threadtime", "-s", `${READINESS_TAG}:I`]);
      return r.ok ? r.out : "";
    },
    installed: async () => {
      const r = shell(`dumpsys package ${pkg}`);
      return r.ok ? installedFromDumpsys(r.out, pkg) : { ok: false };
    },
    sleep,
    nonce,
  };
}

// ---------- login ----------
async function login() {
  const evidence = option("evidence", "qa-evidence");
  const attempt = validateAttempt(readJsonOr(path.join(OUT, "attempt.json")));
  if (!process.env.QA_PRELOGIN_EMAIL || !process.env.QA_PRELOGIN_PASSWORD) {
    // Not wired in any workflow yet (Argus decision 8): this path always ends here today.
    writeJson("receipt-callback.json", { attempt_id: attempt.attempt_id, status: "unknown", reason: "prelogin_credentials_not_configured" });
    markUnknown(evidence, "release pre-login: no reviewed pre-login credential (QA_PRELOGIN_*) in this step");
    console.error("release pre-login: UNKNOWN (no reviewed pre-login credential configured; nothing on the device was touched)");
    return 1;
  }
  // Intent first: if anything below is killed, finalize knows the device may hold a session.
  writeJson("login-started.json", { attempt_id: attempt.attempt_id, workflow: attempt.workflow, started_at: new Date().toISOString() });
  const device = adbDevice({ pkg: option("package", "build.raft.app"), serverEnv: attempt.target.env, nonce: attempt.nonce });
  const target = readJsonOr(path.join(PRIVATE, "target.json"));
  if (!target || target.attempt_id !== attempt.attempt_id) throw new Error("private target missing or for another attempt");
  const result = await runPreloginLogin({ attempt, serverName: target.server_name, device });
  fs.mkdirSync(PRIVATE, { recursive: true, mode: 0o700 });
  if (result.signInSummary) fs.writeFileSync(path.join(PRIVATE, "sign-in-summary.json"), JSON.stringify(result.signInSummary), { mode: 0o600 });
  writeJson("receipt-capture.json", { attempt_id: attempt.attempt_id, ...result.capture });
  writeJson("receipt-callback.json", { attempt_id: attempt.attempt_id, ...result.callback });
  writeJson("receipt-ready.json", { attempt_id: attempt.attempt_id, ...result.readiness });
  if (!result.ok) {
    markUnknown(evidence, `release pre-login: capture ${result.capture?.reason}, callback ${result.callback.reason}, readiness ${result.readiness.reason}`);
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
  const loginStarted = fs.existsSync(path.join(OUT, "login-started.json"));
  const signInSummary = readJsonOr(path.join(PRIVATE, "sign-in-summary.json")) ?? readJsonOr(path.join(PRIVATE, "sign-in-run", "summary.json"));
  const device = adbDevice({ pkg: option("package", "build.raft.app"), serverEnv: attempt?.target?.env ?? "staging" });
  const receipt = await runPreloginCleanup({ loginStarted, signInSummary, device });
  writeJson("receipt-cleanup.json", { attempt_id: attempt?.attempt_id ?? null, ...receipt });
  console.log(`release pre-login cleanup: local ${receipt.local_cleanup.status}, web ${receipt.web_session}, app server ${receipt.app_server_revocation}`);
  return receipt.local_cleanup.status === "complete" || receipt.local_cleanup.status === "not_started" ? 0 : 1;
}

// ---------- verdict ----------
function computeOutcome() {
  const r = (name) => readJsonOr(path.join(OUT, name));
  const attempt = r("attempt.json");
  const outcome = attemptOutcome({
    eligibility: r("eligibility.json"),
    capture: r("receipt-capture.json"),
    callback: r("receipt-callback.json"),
    readiness: r("receipt-ready.json"),
    cleanup: r("receipt-cleanup.json"),
    ...agentResult(),
  });
  writeJson("outcome.json", { attempt_id: attempt?.attempt_id ?? null, case_id: attempt?.case?.id ?? null, workflow: attempt?.workflow ?? null, ...outcome });
  fs.writeFileSync(path.join(OUT, "outcome.rc"), `${outcome.rc}\n`);
  return outcome;
}
function verdict() {
  const outcome = computeOutcome();
  console.log(`release pre-login outcome: ${outcome.outcome} (${outcome.reason})`);
  return 0;
}

// ---------- finalize + seal (the upload boundary) ----------
function finalize() {
  fs.mkdirSync(OUT, { recursive: true });
  const attempt = readJsonOr(path.join(OUT, "attempt.json"));
  if (fs.existsSync(path.join(OUT, "login-started.json")) && !fs.existsSync(path.join(OUT, "receipt-cleanup.json"))) {
    // The emulator step ended before cleanup (killed, cancelled, timed out). Nothing here can read
    // the device back, so nothing is claimed: every item unknown, the attempt kept for resolution.
    writeJson("receipt-cleanup.json", {
      attempt_id: attempt?.attempt_id ?? null,
      local_cleanup: { status: "unknown", items: {}, unproven: ["cleanup_not_reached"] },
      web_session: "unknown",
      app_server_revocation: "unknown",
      reason: "cleanup_not_reached_before_the_emulator_step_ended",
    });
  }
  try { computeOutcome(); } catch (error) { console.error(`release pre-login outcome not written (${error.message})`); }
  return seal();
}
function seal() {
  // 1. qa-prelogin/: drop everything that is not an allow-listed receipt passing its strict schema.
  const problems = [];
  for (const entry of fs.readdirSync(OUT, { withFileTypes: true })) {
    const full = path.join(OUT, entry.name);
    if (!entry.isFile() || !UPLOAD_FILES.includes(entry.name)) {
      fs.rmSync(full, { recursive: true, force: true });
      continue;
    }
    const problem = uploadProblem(entry.name, fs.readFileSync(full, "utf8"), secrets());
    if (problem) {
      problems.push(`${entry.name}: ${problem}`);
      fs.rmSync(full, { force: true });
    }
  }
  // 2. A FRESH sealed directory: byte copies of the checked files + a sha256 manifest, read-only.
  // Only this directory is uploaded (after verify-sealed); later writes to qa-prelogin/ never reach
  // the artifact.
  if (fs.existsSync(SEALED)) fs.chmodSync(SEALED, 0o700);
  fs.rmSync(SEALED, { recursive: true, force: true });
  fs.mkdirSync(SEALED, { recursive: true, mode: 0o700 });
  let entries;
  if (problems.length) {
    const attemptId = null;
    entries = [
      { name: "outcome.json", bytes: Buffer.from(`${JSON.stringify({ attempt_id: attemptId, outcome: "incomplete", rc: 3, reason: "receipts withheld at the upload boundary" }, null, 2)}\n`) },
      { name: "outcome.rc", bytes: Buffer.from("3\n") },
    ];
    console.error(`release pre-login receipts withheld (${problems.length} problem(s))`);
  } else {
    entries = fs.readdirSync(OUT).filter((n) => UPLOAD_FILES.includes(n)).map((name) => ({ name, bytes: fs.readFileSync(path.join(OUT, name)) }));
  }
  for (const e of entries) fs.writeFileSync(path.join(SEALED, e.name), e.bytes, { mode: 0o444 });
  fs.writeFileSync(path.join(SEALED, "MANIFEST.sha256"), sealManifest(entries), { mode: 0o444 });
  fs.chmodSync(SEALED, 0o555);
  console.log(`release pre-login receipts sealed (${entries.length} file(s))`);
  return 0;
}

function verifySealedCommand() {
  let entries = [];
  let manifest = "";
  try {
    manifest = fs.readFileSync(path.join(SEALED, "MANIFEST.sha256"), "utf8");
    entries = fs.readdirSync(SEALED).filter((n) => n !== "MANIFEST.sha256").map((name) => ({ name, bytes: fs.readFileSync(path.join(SEALED, name)) }));
  } catch {
    console.error("sealed receipts missing");
    return 1;
  }
  const problem = verifySealed(entries, manifest);
  if (problem) console.error(`sealed receipts rejected: ${problem}`);
  return problem ? 1 : 0;
}

// ---------- report gate ----------
function gate() {
  const files = {};
  for (const name of UPLOAD_FILES) {
    if (name.endsWith(".json")) {
      const v = readJsonOr(path.join(SEALED, name));
      if (v) files[name] = v;
    }
  }
  const { rc, reason } = reportGate({
    files,
    ...agentResult(),
    current: { runId: process.env.GITHUB_RUN_ID, runAttempt: process.env.GITHUB_RUN_ATTEMPT, runCaseId: process.env.STAMP_RUN_CASE_ID },
  });
  console.error(`release pre-login report gate: rc ${rc} (${reason})`);
  process.stdout.write(`${rc}\n`);
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

const commands = { plan, login: guardedLogin, cleanup, verdict, finalize, "verify-sealed": verifySealedCommand, "report-gate": gate };
if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  const command = commands[argv[0]];
  if (!command) {
    console.error("usage: release_prelogin.mjs plan|login|cleanup|verdict|finalize|verify-sealed|report-gate [options]");
    process.exit(2);
  }
  process.exitCode = await command();
}
