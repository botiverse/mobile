// "Unknown fails closed" regressions for #222 / Mobile #4830 review (Argus).
// FAKE values and fake tools only: no device, simulator, network or account.
// Symbols that did not exist at a36fc69 are imported per test, so each test is
// red on its own against that head instead of the whole file failing to load.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync, spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const mod = (f) => import(path.join(here, f));
const FAKE_PASSWORD = "FAKE-pw-7Q";
const FAKE_EMAIL = "fake.qa@example.test";
const SECRETS = { QA_TEST_PASSWORD: FAKE_PASSWORD, QA_TEST_EMAIL: FAKE_EMAIL };
const tmp = (p) => fs.mkdtempSync(path.join(os.tmpdir(), p));
// Pixel-like fake media: a PNG/MP4 header and fake pixels that never contain
// the secret as text (a byte scan cannot see a rendered password).
const fakePng = (label) => Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.from(`FAKE-PIXELS:${label}:FAKE-p`)]);
const fakeMp4 = (label) => Buffer.concat([Buffer.from([0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70]), Buffer.from(`FAKE-FRAMES:${label}:fake.qa@`)]);
const record = (r, binding = { github_run_id: "", github_run_attempt: "" }) =>
  JSON.stringify({ contract: "qa-capture-suppression/v1", unresolved: false, active: false, completed: true, binding, phases: [], ...r });

function evidenceDir(suppression) {
  const dir = tmp("failclosed-evidence-");
  fs.writeFileSync(path.join(dir, "step-00.png"), fakePng("login form"));
  fs.writeFileSync(path.join(dir, "qa-rec-01.mp4"), fakeMp4("login form"));
  fs.writeFileSync(path.join(dir, "activity-top.txt"), "TASK com.android.chrome\n");
  fs.writeFileSync(path.join(dir, "logcat.txt"), "11-15 06:00:10.100  1  1 I App: FAKE_FRAGMENT\n");
  fs.writeFileSync(path.join(dir, "session.jsonl"), '{"reply":"I see the login page"}\n');
  fs.mkdirSync(path.join(dir, "run.xcresult", "Data"), { recursive: true });
  fs.writeFileSync(path.join(dir, "run.xcresult", "Data", "data.0"), Buffer.from([0, 1, 2, 3]));
  fs.writeFileSync(path.join(dir, "verdict.json"), JSON.stringify({ verdict: "pass", reason: "fake" }));
  if (suppression !== undefined) fs.writeFileSync(path.join(dir, "capture-suppression.json"), suppression);
  return dir;
}
const files = (dir) => {
  const out = [];
  const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, e.name); if (e.isDirectory()) walk(p); else out.push(path.relative(dir, p)); } };
  walk(dir);
  return out.sort();
};

// ---------------------------------------------------------------- finding 1

test("F1 gate: a missing, {}, malformed, foreign-run or unfinished record means the whole run is a login window (nothing leaves, UNKNOWN)", async () => {
  const { gateEvidence } = await mod("secure_evidence.mjs");
  const cases = [
    ["missing", undefined],
    ["{}", "{}"],
    ["malformed", "{"],
    ["wrong schema (no contract)", JSON.stringify({ unresolved: false, active: false, phases: [] })],
    ["another run's record", record({}, { github_run_id: "1", github_run_attempt: "1" })],
    ["never finalised (producer crashed)", record({ completed: false })],
    ["invalid phase", record({ phases: [{ status: "resumed", recording: "stopped" }] })],
  ];
  for (const [name, supp] of cases) {
    const dir = evidenceDir(supp);
    try {
      const { ok, report } = gateEvidence(dir, SECRETS);
      assert.equal(ok, false, `${name}: egress must be refused`);
      assert.equal(report.status, "UNKNOWN", name);
      const left = files(dir);
      for (const f of ["step-00.png", "qa-rec-01.mp4", "activity-top.txt", "logcat.txt", "session.jsonl", "run.xcresult/Data/data.0"]) {
        assert.ok(!left.includes(f), `${name}: ${f} left in the artifact dir`);
      }
      assert.ok(left.includes("verdict.json"), name);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  }
  // Positive control: a finalised record of this run with no window keeps everything.
  const dir = evidenceDir(record({}));
  try {
    const { ok, report } = gateEvidence(dir, SECRETS);
    assert.equal(ok, true);
    assert.equal(report.status, "clean");
    assert.ok(files(dir).includes("step-00.png") && files(dir).includes("qa-rec-01.mp4"));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("F1 gate CLI + report: no record -> exit 1, gate record UNKNOWN, and stamp_report uploads nothing and sends no product verdict", () => {
  const root = tmp("failclosed-report-");
  const ev = path.join(root, "qa-evidence"), bin = path.join(root, "bin"), rt = path.join(root, "rt"), sent = path.join(root, "sent");
  for (const d of [bin, rt, sent]) fs.mkdirSync(d);
  fs.cpSync(evidenceDir(undefined), ev, { recursive: true });
  fs.writeFileSync(path.join(rt, "stamp-claim-rc-f1"), "lease", { mode: 0o600 });
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
a="$*"; p=""; while [ "$#" -gt 0 ]; do case "$1" in --data-binary) p="$2"; shift 2;; -d) p="$2"; shift 2;; *) shift;; esac; done
n=$(ls "${sent}" | wc -l); printf '%s\\n' "$a" > "${sent}/$n.args"; case "$p" in @*) cp "\${p#@}" "${sent}/$n.body";; *) printf '%s' "$p" > "${sent}/$n.body";; esac; exit 0
`, { mode: 0o755 });
  const env = { ...process.env, PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`, RUNNER_TEMP: rt,
    STAMP_RUN_CASE_ID: "rc-f1", STAMP_URL: "https://stamp.test", STAMP_TOKEN: "fake-token", QA_TEST_EMAIL: "", QA_TEST_PASSWORD: FAKE_PASSWORD,
    GITHUB_RUN_ID: "4830", GITHUB_RUN_ATTEMPT: "1" };
  try {
    const gate = spawnSync(process.execPath, [path.join(here, "secure_evidence.mjs"), "--dir", ev], { env, encoding: "utf8" });
    assert.equal(gate.status, 1, "gate must fail the step (workflow then uploads no artifact)");
    const g = JSON.parse(fs.readFileSync(path.join(ev, "secure-evidence-gate.json"), "utf8"));
    assert.equal(g.status, "UNKNOWN");
    assert.equal(g.ok, false);
    spawnSync("bash", [path.join(here, "stamp_report.sh"), "report", "qa-evidence"], { cwd: root, env, encoding: "utf8" });
    const args = fs.readdirSync(sent).filter((f) => f.endsWith(".args")).map((f) => fs.readFileSync(path.join(sent, f), "utf8")).join("\n");
    assert.doesNotMatch(args, /kind=(screenshot|recording|logcat|session_log)/, "evidence left for Testbed");
    const verdicts = fs.readdirSync(sent).filter((f) => f.endsWith(".body") && /\/verdict/.test(fs.readFileSync(path.join(sent, f.replace(".body", ".args")), "utf8")))
      .map((f) => JSON.parse(fs.readFileSync(path.join(sent, f), "utf8")).verdict);
    assert.deepEqual(verdicts, ["harness"], "no product verdict without a passing gate");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------- finding 9

test("F9 logs: a line from another clock than every window, or a log with no timestamp, drops the log and marks the gate UNKNOWN", async () => {
  const { filterLogWindows, gateEvidence } = await mod("secure_evidence.mjs");
  const deviceWin = { status: "UNKNOWN", recording: "stopped", device_start_local: "11-15 06:00:00", start_ts: Date.parse("2026-11-15T06:00:00Z") };
  const hostWin = { status: "UNKNOWN", recording: "not_recorded", start_ts: Date.parse("2026-11-15T06:00:00Z") };
  const android = "11-15 06:00:10.100 1 1 I App: FAKE_FRAGMENT";
  const ios = "2026-11-15 06:00:10.123456+0000 iosApp[1]: FAKE_FRAGMENT";
  assert.equal(filterLogWindows(android, [deviceWin]).withheld, 1, "same clock: withheld");
  assert.equal(filterLogWindows(ios, [hostWin]).withheld, 1, "same clock: withheld");
  assert.equal(filterLogWindows(ios, [deviceWin]), null, "host line vs device-only window");
  assert.equal(filterLogWindows(android, [hostWin]), null, "device line vs host-only window");
  assert.equal(filterLogWindows("no timestamp FAKE_FRAGMENT\nstill none", [hostWin]), null, "unsupported format");
  const dir = tmp("failclosed-logs-");
  try {
    fs.writeFileSync(path.join(dir, "capture-suppression.json"), record({
      phases: [{ start_ts: 1, end_ts: 2, status: "resumed", recording: "stopped", device_start_local: "11-15 06:00:00", device_end_local: "11-15 06:01:00" }],
    }));
    fs.writeFileSync(path.join(dir, "system-log-raft.txt"), `${ios}\n`);
    fs.writeFileSync(path.join(dir, "verdict.json"), "{}");
    const { report } = gateEvidence(dir, {});
    assert.ok(!fs.existsSync(path.join(dir, "system-log-raft.txt")), "cross-clock log withheld");
    assert.equal(report.status, "UNKNOWN");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("F9 xcresult/opaque: an unresolved window removes .xcresult bundles and opaque files the byte scan cannot read", async () => {
  const { gateEvidence } = await mod("secure_evidence.mjs");
  const dir = tmp("failclosed-xcresult-");
  try {
    fs.mkdirSync(path.join(dir, "run.xcresult", "Data"), { recursive: true });
    fs.writeFileSync(path.join(dir, "run.xcresult", "Data", "data.0"), Buffer.from([0, 1, 2, 3, 4, 5]));
    fs.writeFileSync(path.join(dir, "run.xcresult", "Info.plist"), "<plist/>");
    fs.writeFileSync(path.join(dir, "blob.bin"), Buffer.from([0, 0xff, 0x10]));
    fs.writeFileSync(path.join(dir, "capture-suppression.json"), record({
      unresolved: true, active: true, completed: false, phases: [{ status: "UNKNOWN", start_ts: 1, recording: "not_recorded" }],
    }));
    const { report } = gateEvidence(dir, SECRETS);
    assert.deepEqual(files(dir).filter((f) => f !== "capture-suppression.json"), []);
    assert.equal(report.status, "UNKNOWN");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------- finding 7

test("F7 report-verdict: a product verdict needs a passing gate record bound to this run and this exact verdict.json", () => {
  const sha = (b) => execFileSync(process.execPath, ["-e", "process.stdout.write(require('crypto').createHash('sha256').update(require('fs').readFileSync(process.argv[1])).digest('hex'))", b], { encoding: "utf8" });
  const run = (name, { gate, outcome, mutateVerdict = false } = {}) => {
    const root = tmp("failclosed-verdict-");
    const ev = path.join(root, "qa-evidence"), bin = path.join(root, "bin"), rt = path.join(root, "rt"), sent = path.join(root, "sent.jsonl");
    for (const d of [ev, bin, rt]) fs.mkdirSync(d);
    fs.writeFileSync(path.join(ev, "verdict.json"), JSON.stringify({ verdict: "pass", reason: "fake" }));
    const vsha = sha(path.join(ev, "verdict.json"));
    if (gate) fs.writeFileSync(path.join(ev, "secure-evidence-gate.json"), JSON.stringify(gate(vsha)));
    if (mutateVerdict) fs.writeFileSync(path.join(ev, "verdict.json"), JSON.stringify({ verdict: "pass", reason: "edited after the gate" }));
    fs.writeFileSync(path.join(rt, "stamp-claim-rc-f7"), "lease", { mode: 0o600 });
    fs.writeFileSync(path.join(bin, "curl"), `#!${process.execPath}
const fs=require("fs");const a=process.argv.slice(2);let d="";for(let i=0;i<a.length;i++)if(a[i]==="--data-binary"||a[i]==="-d")d=a[++i];
if(a.some((x)=>/\\/verdict$/.test(x))){const b=d.startsWith("@")?fs.readFileSync(d.slice(1),"utf8"):d;fs.appendFileSync(${JSON.stringify(sent)},JSON.parse(b).verdict+"\\n");}
`, { mode: 0o755 });
    const env = { ...process.env, PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`, RUNNER_TEMP: rt,
      STAMP_RUN_CASE_ID: "rc-f7", STAMP_URL: "https://stamp.test", STAMP_TOKEN: "fake-token", GITHUB_RUN_ID: "4830", GITHUB_RUN_ATTEMPT: "1" };
    delete env.STAMP_EVIDENCE_GATE_OUTCOME;
    if (outcome !== undefined) env.STAMP_EVIDENCE_GATE_OUTCOME = outcome;
    spawnSync("bash", [path.join(here, "stamp_report.sh"), "report-verdict", "qa-evidence", "0"], { cwd: root, env, encoding: "utf8" });
    const out = fs.existsSync(sent) ? fs.readFileSync(sent, "utf8").trim().split("\n") : [];
    fs.rmSync(root, { recursive: true, force: true });
    return out;
  };
  const good = (s) => ({ contract: "qa-secure-evidence-gate/v2", ok: true, status: "clean", binding: { github_run_id: "4830", github_run_attempt: "1" }, verdict_sha256: s });
  assert.deepEqual(run("no gate record"), ["harness"]);
  assert.deepEqual(run("gate failed", { gate: (s) => ({ ...good(s), ok: false, status: "UNKNOWN" }) }), ["harness"]);
  assert.deepEqual(run("gate UNKNOWN (unresolved window)", { gate: (s) => ({ ...good(s), status: "UNKNOWN" }) }), ["harness"]);
  assert.deepEqual(run("another run's gate", { gate: (s) => ({ ...good(s), binding: { github_run_id: "1", github_run_attempt: "1" } }) }), ["harness"]);
  assert.deepEqual(run("verdict edited after the gate", { gate: good, mutateVerdict: true }), ["harness"]);
  assert.deepEqual(run("workflow gate step failed", { gate: good, outcome: "failure" }), ["harness"]);
  assert.deepEqual(run("positive control", { gate: good }), ["pass"]);
  assert.deepEqual(run("positive control, workflow gate step succeeded", { gate: good, outcome: "success" }), ["pass"]);
});

// ------------------------------------------------- coordinator: log cursor

test("login-log cursor: an opening line with the cursor's exact timestamp is new; the same line is not seen twice", () => {
  return mod("secure_input.mjs").then(({ loginLogState }) => {
    const line = (t, pid, msg) => `${t} ${pid} ${pid} I SlockAppLogin: ${msg}`;
    const first = [line("1000.100", 111, "web_login_attempt phase=idle reason=browser_closed")].join("\n");
    const s1 = loginLogState(first);
    assert.equal(s1.newest, 1000.1);
    // A second attempt opens in the SAME millisecond as the cursor.
    const second = [first, line("1000.100", 111, "web_login_attempt phase=opening")].join("\n");
    const s2 = loginLogState(second, s1.cursor ?? s1.newest);
    assert.equal(s2.openedAfter, 1000.1, "same-timestamp opening missed");
    // Re-reading the same log with the new cursor does not reopen it.
    assert.equal(loginLogState(second, s2.cursor).openedAfter, null);
    // An identical message from another process at the same ms is still new.
    const third = [second, line("1000.100", 222, "web_login_attempt phase=opening")].join("\n");
    assert.equal(loginLogState(third, s2.cursor).openedAfter, 1000.1);
  });
});

// ------------------------------------------------- detectors (F4 / F8 / F3)

test("detectors fail closed: iOS 'Continue as' / provider buttons / empty tree; Android unlisted package", async () => {
  const { loginSurfaceFromNodes, snapshotToDescribeNodes } = await mod("ios_driver.mjs");
  const { isExternalLoginSurface } = await mod("secure_input.mjs");
  const node = (label, role = "button") => snapshotToDescribeNodes([{ role, label, rect: { x: 0, y: 0, width: 10, height: 10 } }]);
  assert.equal(loginSurfaceFromNodes(node("Continue as fake person")), true);
  assert.equal(loginSurfaceFromNodes(node("Sign in with FakeProvider")), true);
  assert.equal(loginSurfaceFromNodes(node("“Raft” Wants to Use “example.test” to Sign In")), true);
  assert.equal(loginSurfaceFromNodes([]), null, "an empty tree cannot prove the screen is clear");
  assert.equal(loginSurfaceFromNodes(node("Home")), false, "control: an ordinary screen");
  assert.equal(isExternalLoginSurface("example.loginprovider.web", "build.raft.app"), true);
  assert.equal(isExternalLoginSurface("com.android.systemui", "build.raft.app"), false);
});

// ---------------------------------------------------------------- finding 5

test("F5 ownership: hosted needs this run's receipt, standalone needs a token, both need the device's marker; console bound to the same emulator", async () => {
  const { verifySeatOwnership, claimHostedSeat, OWNER_MARKER } = await mod("device_ownership.mjs");
  const device = { serial: "emulator-5560", marker: "", avd: "Fake_AVD_34" };
  const writes = [];
  const adb = (args) => {
    const a = args.join(" ");
    if (a === "get-serialno") return `${device.serial}\n`;
    if (a === `shell cat ${OWNER_MARKER}`) { if (!device.marker) throw new Error("no such file"); return `${device.marker}\n`; }
    if (a === "shell getprop ro.boot.qemu.avd_name") return `${device.avd}\n`;
    if (a === "shell getprop ro.kernel.qemu.avd_name") return "\n";
    const m = /^shell umask 077; echo ([0-9a-f]+) > /.exec(a);
    if (m) { device.marker = m[1]; writes.push(a); return ""; }
    throw new Error(`unexpected adb ${a}`);
  };
  const root = tmp("failclosed-own-");
  const receipt = path.join(root, "receipt.json");
  const hosted = { GITHUB_ACTIONS: "true", GITHUB_RUN_ID: "4830", GITHUB_RUN_ATTEMPT: "1" };
  try {
    assert.equal(verifySeatOwnership({ adb, env: {} }).owned, false, "standalone without a token");
    device.marker = "operator-token-0123456789";
    assert.equal(verifySeatOwnership({ adb, env: {}, token: "operator-token-0123456789" }).owned, true);
    assert.equal(verifySeatOwnership({ adb, env: {}, token: "another-token-0123456789" }).owned, false, "marker mismatch");
    assert.equal(verifySeatOwnership({ adb, env: {}, token: "operator-token-0123456789", device: "emulator-5554" }).owned, false, "--device is another target");
    assert.equal(verifySeatOwnership({ adb, env: { QA_EMULATOR_CONSOLE_PORT: "5554" }, token: "operator-token-0123456789" }).owned, false, "console override names another emulator");
    assert.equal(verifySeatOwnership({ adb, env: hosted, token: "operator-token-0123456789" }).owned, false, "hosted refuses a standalone token");
    assert.equal(verifySeatOwnership({ adb, env: {}, receiptPath: receipt }).owned, false, "standalone refuses a hosted receipt");
    // Hosted: the claim refuses an emulator that already has an owner, then binds a fresh one.
    assert.throws(() => claimHostedSeat({ adb, env: hosted, receiptPath: receipt }), /already carries an owner marker/);
    device.marker = "";
    claimHostedSeat({ adb, env: hosted, receiptPath: receipt });
    assert.equal(writes.length, 1);
    const ok = verifySeatOwnership({ adb, env: hosted, receiptPath: receipt });
    assert.deepEqual([ok.owned, ok.mode, ok.serial, ok.consolePort, ok.avdName], [true, "hosted", "emulator-5560", 5560, "Fake_AVD_34"]);
    assert.equal(verifySeatOwnership({ adb, env: { ...hosted, GITHUB_RUN_ATTEMPT: "2" }, receiptPath: receipt }).owned, false, "re-run attempt");
    device.serial = "emulator-5562";
    assert.equal(verifySeatOwnership({ adb, env: hosted, receiptPath: receipt }).owned, false, "another emulator");
    device.serial = "R58M00FAKE";
    assert.equal(verifySeatOwnership({ adb, env: {}, token: "operator-token-0123456789" }).owned, false, "a physical phone is never modified");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------- finding 6

// Deterministic scheduling model of the workflow loop (Argus's recording-race
// model, extended): atomic loop steps interleave with the agent's device
// operations in every order the scheduler chooses.
function loopModel(initialPc) {
  const m = { pause: null, ack: null, done: false, running: initialPc === "rec", i: initialPc === "rec" ? 1 : 2, pc: initialPc, segments: new Set(initialPc === "rec" ? [1] : [1]), startsWhilePaused: 0 };
  m.step = () => {
    switch (m.pc) {
      case "check": // [ -e $P ] ... ; [ $i -le 8 ] || break
        if (m.pause) { m.ack = m.pause; m.pc = "sleep"; } else if (m.i > 8) { m.done = true; m.pc = "exit"; } else m.pc = "start";
        break;
      case "start": // screenrecord ... (the flag was checked BEFORE this step)
        if (m.pause && m.ack === m.pause) m.startsWhilePaused++;
        m.running = true; m.segments.add(m.i); m.pc = "rec";
        break;
      case "rec": break; // recording continues until interrupted
      case "sleep": m.pc = "check"; break;
      default: break;
    }
  };
  m.interrupt = () => { if (m.running) { m.running = false; m.i++; m.pc = "check"; } };
  return m;
}
const name = (i) => `/sdcard/qa-rec-${String(i).padStart(2, "0")}.mp4`;
function modelOps(m, choose) {
  const sched = () => { for (let k = choose(); k > 0; k--) m.step(); };
  return {
    writePause: (n) => { sched(); m.pause = n; return true; },
    interrupt: () => { sched(); m.interrupt(); },
    readAck: () => { sched(); return m.ack; },
    loopDone: () => { sched(); return m.done; },
    recorderRunning: () => { sched(); return m.running; },
    list: () => { sched(); return [...m.segments].sort((a, b) => a - b).map(name); },
    remove: (f) => { sched(); m.segments.delete(Number(/(\d+)\.mp4$/.exec(f)[1])); },
  };
}

test("F6 recorder fence: the reviewer's interleaving (checked, paused, then started) is caught: acknowledged before typing, racing segment dropped, nothing starts while paused", async () => {
  const { pauseRecorder } = await mod("secure_input.mjs");
  const NONCE = "FakeNonce0123456789abcdef";
  // Exhaustive over 0..2 loop steps before each of the first 9 agent operations, from each loop position.
  let runs = 0, stopped = 0;
  for (const initialPc of ["rec", "check", "start", "sleep"]) {
    for (let code = 0; code < 3 ** 9; code++) {
      const m = loopModel(initialPc);
      const boundary = 1; // segment 1 was current at the previous clean check
      let c = code;
      const choose = () => { const k = c % 3; c = Math.floor(c / 3); return k; };
      const r = await pauseRecorder({ ops: modelOps(m, choose), nonce: NONCE, boundary, attempts: 6, sleep: async () => { m.step(); } });
      runs++;
      if (!r.stopped) continue;
      stopped++;
      assert.equal(m.running, false, `recorder running when typing may start (pc=${initialPc} code=${code})`);
      for (let k = 0; k < 50; k++) m.step(); // the window stays open: the loop keeps running
      assert.equal(m.running, false, `a recorder started after the acknowledged pause (pc=${initialPc} code=${code})`);
      assert.equal(m.startsWhilePaused, 0);
      assert.deepEqual([...m.segments].filter((i) => i >= boundary), [], `a segment from the window is left (pc=${initialPc} code=${code})`);
    }
  }
  assert.ok(stopped > runs / 2, `the handshake must normally complete (${stopped}/${runs})`);
  // The reviewer's exact order: the loop has checked (pc=start), the agent pauses + interrupts
  // (nothing running yet), then the loop starts a segment. Not missed, and dropped.
  const m = loopModel("start");
  const seq = [0, 0, 1]; // writePause, interrupt (no-op), then the delayed start happens before readAck
  let n = 0;
  const r = await pauseRecorder({ ops: modelOps(m, () => seq[n++] ?? 1), nonce: NONCE, boundary: 1, attempts: 10, sleep: async () => {} });
  assert.equal(r.stopped, true);
  assert.ok(r.dropped.includes("qa-rec-02.mp4"), JSON.stringify(r));
  assert.equal(m.running, false);
  // Cancellation / unconfirmed stop: a loop that never acknowledges (dead without DONE) is UNKNOWN.
  const dead = loopModel("exit");
  dead.pc = "dead";
  const r2 = await pauseRecorder({ ops: modelOps(dead, () => 0), nonce: NONCE, boundary: 1, attempts: 5, sleep: async () => {} });
  assert.equal(r2.stopped, false);
  // A recorder that ignores interrupts never yields an acknowledgement either.
  const stuck = loopModel("rec");
  stuck.interrupt = () => {};
  assert.equal((await pauseRecorder({ ops: modelOps(stuck, () => 1), nonce: NONCE, boundary: 1, attempts: 5, sleep: async () => {} })).stopped, false);
});

test("F6 recorder fence (model check of the old algorithm): two empty process reads let the delayed segment record the login window", () => {
  // a36fc69's stopRecording, transcribed onto the same model: touch, pkill, 2 empty pidof reads, delete newest.
  const m = loopModel("start");
  m.pause = "legacy"; // touch flag
  m.interrupt(); // pkill: nothing running yet
  const empty = !m.running && !m.running;
  const newest = Math.max(...m.segments);
  m.segments.delete(newest);
  assert.ok(empty);
  m.step(); // the delayed start
  assert.equal(m.running, true, "the model reproduces the race the fence closes");
});

test("F6 recorder fence on a real shell: the canonical loop acknowledges, never starts while paused, even with a slow recorder start", async () => {
  const { RECORDING_LOOP_SH, pauseRecorder } = await mod("secure_input.mjs");
  const dir = tmp("failclosed-loop-");
  const bin = path.join(dir, "bin");
  fs.mkdirSync(bin);
  // Fake screenrecord: slow start, then "records" until SIGINT.
  fs.writeFileSync(path.join(bin, "screenrecord"), `#!/bin/sh
out=""; for a in "$@"; do out="$a"; done
trap 'rm -f "${dir}/running"; exit 0' INT
sleep 0.3
echo $$ > "${dir}/running"; : > "$out"
while :; do sleep 0.05; done
`, { mode: 0o755 });
  const script = RECORDING_LOOP_SH.replaceAll("/data/local/tmp", dir).replaceAll("/sdcard", dir).replace("--time-limit 180", "--time-limit 180");
  const loop = spawn("sh", ["-c", script], { env: { ...process.env, PATH: `${bin}:${process.env.PATH}` }, detached: true, stdio: "ignore" });
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const seg = () => fs.readdirSync(dir).filter((f) => /^qa-rec-\d+\.mp4$/.test(f)).sort();
  const P = path.join(dir, "qa-rec-pause");
  try {
    for (let i = 0; i < 40 && !fs.existsSync(path.join(dir, "running")); i++) await wait(50);
    const ops = {
      writePause: (n) => { fs.writeFileSync(P, `${n}\n`); return true; },
      interrupt: () => { try { const pid = Number(fs.readFileSync(path.join(dir, "running"), "utf8")); process.kill(pid, "SIGINT"); } catch { /* none */ } },
      readAck: () => { try { return fs.readFileSync(path.join(dir, "qa-rec-ack"), "utf8").trim(); } catch { return null; } },
      loopDone: () => fs.existsSync(path.join(dir, "qa-rec-done")),
      recorderRunning: () => fs.existsSync(path.join(dir, "running")),
      list: () => seg().map((f) => `/sdcard/${f}`),
      remove: (f) => fs.rmSync(path.join(dir, path.basename(f)), { force: true }),
    };
    const r = await pauseRecorder({ ops, nonce: "ShellNonce0123456789", boundary: 1, attempts: 60, sleep: (ms) => wait(Math.min(ms, 100)) });
    assert.equal(r.stopped, true, JSON.stringify(r));
    assert.deepEqual(seg(), [], "every segment from the window is gone");
    await wait(1500); // window open: the loop keeps polling the flag
    assert.deepEqual(seg(), [], "a segment started while paused");
    assert.ok(!fs.existsSync(path.join(dir, "running")));
    fs.rmSync(P); // receipts: resume
    for (let i = 0; i < 60 && !seg().length; i++) await wait(50);
    assert.equal(seg().length, 1, "recording resumes in a new segment after the flag is removed");
  } finally {
    try { process.kill(-loop.pid, "SIGKILL"); } catch { /* gone */ }
    try { const pid = Number(fs.readFileSync(path.join(dir, "running"), "utf8")); process.kill(pid, "SIGKILL"); } catch { /* gone */ }
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ----------------------------------------------- iOS fakes (F2 / F4 / F8)

function iosFakes({ snapshots }) {
  const root = tmp("failclosed-ios-");
  const bin = path.join(root, "bin"), io = path.join(root, "io"), out = path.join(root, "qa-evidence");
  for (const d of [bin, io, out]) fs.mkdirSync(d);
  const calls = path.join(root, "calls.jsonl");
  const profile = path.join(root, "iPhone Fake.simdevicetype");
  fs.writeFileSync(path.join(bin, "xcrun"), `#!${process.execPath}
const fs=require("fs");const a=process.argv.slice(2);fs.appendFileSync(${JSON.stringify(calls)},JSON.stringify(["xcrun",...a])+"\\n");
const j=a.join(" ");
if(j==="simctl list devices -j"){console.log(JSON.stringify({devices:{"com.apple.CoreSimulator.SimRuntime.iOS-18-0":[{udid:"0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0",state:"Booted",deviceTypeIdentifier:"com.apple.CoreSimulator.SimDeviceType.iPhone-Fake"}]}}));process.exit(0)}
if(j==="simctl list devicetypes -j"){console.log(JSON.stringify({devicetypes:[{identifier:"com.apple.CoreSimulator.SimDeviceType.iPhone-Fake",bundlePath:${JSON.stringify(profile)}}]}));process.exit(0)}
if(a.includes("screenshot")){const b=Buffer.alloc(24);Buffer.from([137,80,78,71,13,10,26,10]).copy(b);b.writeUInt32BE(1179,16);b.writeUInt32BE(2556,20);fs.writeFileSync(a.at(-1),Buffer.concat([b,Buffer.from("FAKE-LOGIN-PIXELS")]));process.exit(0)}
if(a.includes("launchctl")){console.log("build.raft.app");process.exit(0)}
`, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, "plutil"), `#!${process.execPath}
console.log(JSON.stringify({mainScreenWidth:1179,mainScreenHeight:2556,mainScreenScale:3}));
`, { mode: 0o755 });
  const snapFile = path.join(root, "snapshots.json");
  fs.writeFileSync(snapFile, JSON.stringify(snapshots));
  fs.writeFileSync(path.join(bin, "agent-device"), `#!${process.execPath}
const fs=require("fs");const a=process.argv.slice(2);fs.appendFileSync(${JSON.stringify(calls)},JSON.stringify(["agent-device",...a])+"\\n");
if(a.includes("snapshot")){const c=${JSON.stringify(path.join(root, "count"))};const n=fs.existsSync(c)?Number(fs.readFileSync(c,"utf8")):0;fs.writeFileSync(c,String(n+1));
const list=JSON.parse(fs.readFileSync(${JSON.stringify(snapFile)},"utf8"));const s=list[Math.min(n,list.length-1)];
if(s==="fail"){console.error("fake inaccessible accessibility tree");process.exit(2)}console.log(JSON.stringify({nodes:s}));process.exit(0)}
console.log("ok");
`, { mode: 0o755 });
  const env = { PATH: `${bin}:${path.dirname(process.execPath)}:/usr/bin:/bin`, HOME: root, AGENT_DEVICE_BIN: path.join(bin, "agent-device"), ...SECRETS };
  const callList = () => (fs.existsSync(calls) ? fs.readFileSync(calls, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)) : []);
  return { root, bin, io, out, env, callList, cleanup: () => fs.rmSync(root, { recursive: true, force: true }) };
}
const rect = { x: 0, y: 0, width: 393, height: 852 };
const SECURE_FIELD = [{ role: "secureTextField", label: "Password", rect }];
const SIGN_IN_BUTTON = [{ role: "button", label: "Sign In", rect }];
const CONTINUE_AS = [{ role: "button", label: "Continue as fake-existing-account", rect }];

test("F2 iOS driver: readiness, the login probe and geometry take NO screenshot (geometry from device info)", async () => {
  const { createIosDriver } = await mod("ios_driver.mjs");
  const f = iosFakes({ snapshots: [SECURE_FIELD] });
  const old = process.env.PATH;
  process.env.PATH = f.env.PATH;
  try {
    const d = createIosDriver({ ioDir: f.io, driver: "agent-device", binary: path.join(f.bin, "agent-device"), udid: "booted" });
    await d.waitReady();
    assert.equal(await d.loginSurfaceVisible(), true);
    assert.deepEqual(d.screenSize(), { w: 1179, h: 2556 });
    assert.equal(f.callList().filter((c) => c.includes("screenshot")).length, 0, "a screenshot was taken before the gate");
    assert.ok(!fs.existsSync(path.join(f.io, "shot.png")));
  } finally {
    process.env.PATH = old;
    f.cleanup();
  }
});

function runIosAgent(f, script) {
  const model = path.join(f.root, "model.mjs");
  fs.writeFileSync(model, `import fs from "node:fs";
const script=${JSON.stringify(script)};let n=0;
export function builtinModels(){return {getModel:(p,m)=>({id:m,provider:p}),async complete(m,{messages}){fs.appendFileSync(${JSON.stringify(path.join(f.root, "model.jsonl"))},JSON.stringify(messages)+"\\n");const a=script[Math.min(n++,script.length-1)];return {stopReason:"stop",content:[{type:"text",text:JSON.stringify(a)}],usage:{}}}}}`);
  const r = spawnSync(process.execPath, [path.join(here, "acceptance_agent.mjs"), "--platform", "ios", "--ios-io-dir", f.io, "--out", f.out,
    "--goal", "fake login", "--max-turns", "20", "--max-minutes", "10"], {
    env: { ...f.env, PI_API_KEY: "fake-key", SLOCK_PI_AI_MODULE: model, QA_PLAYBOOK: path.join(f.root, "none.md") }, encoding: "utf8", timeout: 60000,
  });
  const modelPayload = fs.existsSync(path.join(f.root, "model.jsonl")) ? fs.readFileSync(path.join(f.root, "model.jsonl"), "utf8") : "";
  const read = (n) => JSON.parse(fs.readFileSync(path.join(f.out, n), "utf8"));
  return { ...r, modelPayload, read };
}

test("F2 iOS agent: a login page on the first frame is never captured (no screenshot call, no image to the model)", () => {
  const f = iosFakes({ snapshots: [SECURE_FIELD] });
  try {
    const r = runIosAgent(f, [{ action: "secret_phase", state: "end" }]);
    assert.equal(f.callList().filter((c) => c.includes("screenshot")).length, 0, r.stderr);
    assert.ok(!fs.readdirSync(f.out).some((x) => x.endsWith(".png")));
    assert.ok(!r.modelPayload.includes('"type":"image"'));
    assert.equal(r.read("verdict.json").secure_input.status, "UNKNOWN");
  } finally {
    f.cleanup();
  }
});

test("F4 iOS agent: an unknown login probe (tree unreadable or empty) suppresses capture and ends UNKNOWN; the model's pass is not honoured", () => {
  for (const [name, snapshots] of [["unreadable", [SIGN_IN_BUTTON, "fail"]], ["empty", [SIGN_IN_BUTTON, []]]]) {
    const f = iosFakes({ snapshots });
    try {
      const r = runIosAgent(f, [{ action: "finish", verdict: "pass", reason: "fake", diagnosis: null, annotations: [] }]);
      assert.equal(f.callList().filter((c) => c.includes("screenshot")).length, 0, `${name}: screenshot taken\n${r.stderr}`);
      assert.ok(!r.modelPayload.includes('"type":"image"'), `${name}: image sent to the model`);
      const supp = r.read("capture-suppression.json");
      assert.equal(supp.unresolved, true, name);
      assert.equal(supp.completed, true, name);
      const v = r.read("verdict.json");
      assert.equal(v.verdict, "error", name);
      assert.equal(v.secure_input.status, "UNKNOWN", name);
      assert.equal(r.status, 2, name);
    } finally {
      f.cleanup();
    }
  }
});

test("F8 deterministic repro: a signed-in browser ('Continue as') or a password field stops before ANY capture or dump; record written, UNKNOWN", () => {
  for (const [name, snapshots] of [["Continue as", [CONTINUE_AS]], ["password field", [SECURE_FIELD]], ["probe unreadable", [SIGN_IN_BUTTON, "fail"]]]) {
    const f = iosFakes({ snapshots });
    try {
      const r = spawnSync(process.execPath, [path.join(here, "ios_deterministic_repro.mjs"), "--ios-io-dir", f.io, "--out", f.out], { env: f.env, encoding: "utf8", timeout: 60000 });
      const out = fs.readdirSync(f.out);
      assert.ok(!out.some((x) => x.endsWith(".png")), `${name}: screenshot saved\n${r.stderr}`);
      assert.ok(!out.some((x) => /dump/.test(x)), `${name}: UI dump saved`);
      assert.equal(f.callList().filter((c) => c.includes("screenshot")).length, 0, name);
      for (const x of out) assert.ok(!fs.readFileSync(path.join(f.out, x), "utf8").includes("fake-existing-account"), `${name}: identity in ${x}`);
      const supp = JSON.parse(fs.readFileSync(path.join(f.out, "capture-suppression.json"), "utf8"));
      assert.equal(supp.unresolved, true, name);
      const v = JSON.parse(fs.readFileSync(path.join(f.out, "verdict.json"), "utf8"));
      assert.equal(v.verdict, "error", name);
      assert.equal(v.secure_input?.status, "UNKNOWN", name);
      assert.equal(r.status, 2, name);
    } finally {
      f.cleanup();
    }
  }
});

test("F8 workflow: the deterministic repro step gets no QA credentials", () => {
  const wf = fs.readFileSync(path.resolve(here, "../../.github/workflows/qa-acceptance-ios.yml"), "utf8");
  const i = wf.indexOf("- name: Run deterministic repro\n");
  assert.ok(i > 0);
  const step = wf.slice(i, wf.indexOf("\n      - ", i + 1));
  assert.doesNotMatch(step, /QA_TEST_(EMAIL|PASSWORD)/);
});
