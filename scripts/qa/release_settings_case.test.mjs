// Release settings lane evidence (synthetic files): the record the matched case writes passes the
// same gates the Alpha path uses (agent readRecord, secure_evidence.mjs before both uploads); one
// terminal decision invalidates it for both exits when the run cannot be vouched for.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { readRecord, writeRecord } from "./login_phase.mjs";
import { gate } from "./secure_evidence.mjs";
import { threadtimeStamp, writeReleaseLoginRecord } from "./scenarios/release_settings_case.mjs";
import { applyTerminalDecision, terminalDecision } from "./release_settings_terminal.mjs";

const env = { GITHUB_RUN_ID: "4242", GITHUB_RUN_ATTEMPT: "1" };
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "release-case-"));
const times = { exchangeAt: "11-19 08:29:59.000", loginEnd: "11-19 08:30:01.123" };

// A finished matched case's evidence directory.
function caseEvidence() {
  const dir = tmp();
  writeReleaseLoginRecord(dir, times, env);
  fs.writeFileSync(path.join(dir, "logcat.txt"), [
    "11-19 08:29:59.000  100  100 I SlockAppLogin: app_login_complete result=app_shell reason=exchanged",
    "11-19 08:30:01.123  100  100 I Raft: at the match",
    "11-19 08:30:05.000  100  100 I Raft: settings opened",
  ].join("\n"));
  fs.writeFileSync(path.join(dir, "verdict.json"), "{\"verdict\":\"pass\"}\n");
  fs.writeFileSync(path.join(dir, "agent-rc.txt"), "0\n");
  fs.writeFileSync(path.join(dir, "step-01.png"), "png");
  fs.writeFileSync(path.join(dir, "qa-rec-01.mp4"), "mp4");
  return dir;
}
const cleanSummary = () => ({
  result: "signed_in",
  identity_receipt: { receipt_check: "pass", os_check: "pass", user_check: "pending", server_check: "pending", pair_check: "pending", state: "matched" },
  identity_process: "same",
  scenario_result: "pass",
  cleanup: { capture_file: "deleted", browser: "restored", capture_browser: "uninstalled", web_session: "signed_out", app_session: "not_requested", identity_receipt_provider: "disabled" },
});

test("device date -> threadtime login_end; the record needs the observed exchange before it", () => {
  assert.equal(threadtimeStamp("11-19 08:30:01.123456789\n"), "11-19 08:30:01.123");
  for (const bad of ["", "date: bad format", "11-19 08:30:01", "2026-11-19 08:30:01.123", null]) assert.equal(threadtimeStamp(bad), null, String(bad));
  assert.throws(() => writeReleaseLoginRecord(tmp(), { exchangeAt: null, loginEnd: times.loginEnd }, env), /no observed app exchange/);
  assert.throws(() => writeReleaseLoginRecord(tmp(), { exchangeAt: times.exchangeAt, loginEnd: null }, env), /no device time/);
  assert.throws(() => writeReleaseLoginRecord(tmp(), { exchangeAt: times.loginEnd, loginEnd: times.exchangeAt }, env), /no device time/);
});

test("the release record is validated by the shared gate: this run only, exchange before the match", () => {
  const dir = tmp();
  const record = writeReleaseLoginRecord(dir, times, env);
  assert.deepEqual(Object.keys(record).sort(), ["contract", "exchange_at", "login_end", "mode", "reason", "run", "status"]);
  assert.equal(readRecord(dir, env).ok, true);
  assert.equal(readRecord(dir, { ...env, GITHUB_RUN_ATTEMPT: "2" }).ok, false, "another run attempt");
  for (const bad of [{ status: "complete" }, { exchange_at: "x" }, { login_end: null }, { exchange_at: "11-19 09:00:00.000" }]) {
    writeRecord(dir, { mode: "release-prelogin", status: "matched", exchange_at: times.exchangeAt, login_end: times.loginEnd, ...bad }, env);
    assert.equal(readRecord(dir, env).ok, false, JSON.stringify(bad));
  }
  fs.rmSync(dir, { recursive: true });
});

test("complete: the case verdict and evidence reach both exits; log lines up to the match are dropped", () => {
  const dir = caseEvidence();
  const decision = terminalDecision({ summary: cleanSummary(), rc: 0 });
  assert.equal(decision.complete, true);
  applyTerminalDecision(dir, decision, env);
  assert.deepEqual(gate(dir, env), { ok: true, reason: null });
  assert.equal(fs.readFileSync(path.join(dir, "logcat.txt"), "utf8"), "11-19 08:30:05.000  100  100 I Raft: settings opened");
  assert.equal(fs.readFileSync(path.join(dir, "agent-rc.txt"), "utf8"), "0\n");
  for (const f of ["step-01.png", "qa-rec-01.mp4", "verdict.json", "release-login-summary.json"]) assert.ok(fs.existsSync(path.join(dir, f)), f);
  const pub = JSON.parse(fs.readFileSync(path.join(dir, "release-login-summary.json"), "utf8"));
  assert.equal(pub.terminal, "complete");
  assert.equal(pub.app_session, "not_requested", "the app session is reported separately");
  fs.rmSync(dir, { recursive: true });
});

test("not vouched for: process changed, recorder not stopped, a required cleanup failed or a non-zero exit -> both exits withhold everything", () => {
  const cases = {
    "process changed": (s) => { s.identity_process = "changed"; },
    "recorder not stopped (case did not finish)": (s) => { s.scenario_result = "fail"; },
    "web session not signed out": (s) => { s.cleanup.web_session = "failed"; },
    "browser not restored": (s) => { s.cleanup.browser = "failed"; },
    "provider not disabled": (s) => { s.cleanup.identity_receipt_provider = "failed"; },
    "decision not matched": (s) => { s.identity_receipt.state = "awaiting_confirmation"; },
    "no summary": () => null,
  };
  for (const [name, mutate] of Object.entries(cases)) {
    const dir = caseEvidence();
    let summary = cleanSummary();
    summary = mutate(summary) === null ? null : summary;
    const decision = terminalDecision({ summary, rc: 0 });
    assert.equal(decision.complete, false, name);
    applyTerminalDecision(dir, decision, env);
    const result = gate(dir, env);
    assert.equal(result.ok, false, name);
    assert.deepEqual(fs.readdirSync(dir).sort(), ["agent-rc.txt", "evidence-gate.json", "login-record.json", "verdict.json"], name);
    assert.equal(JSON.parse(fs.readFileSync(path.join(dir, "verdict.json"), "utf8")).verdict, "unknown", name);
    assert.equal(fs.readFileSync(path.join(dir, "agent-rc.txt"), "utf8"), "2\n", name);
    fs.rmSync(dir, { recursive: true });
  }
  assert.equal(terminalDecision({ summary: cleanSummary(), rc: 1 }).complete, false, "non-zero driver exit");
  // App revocation is not a required cleanup in this lane.
  const s = cleanSummary();
  s.cleanup.app_session = "local_cleared_server_unconfirmed";
  assert.equal(terminalDecision({ summary: s, rc: 0 }).complete, true);
});

test("without a match there is no record: the gate withholds everything, as on the Alpha path", () => {
  const dir = tmp();
  applyTerminalDecision(dir, terminalDecision({ summary: { identity_receipt: { state: "unknown" } }, rc: 1 }), env);
  const result = gate(dir, env);
  assert.equal(result.ok, false);
  assert.match(result.reason, /login record missing/);
  fs.rmSync(dir, { recursive: true });
});

test("lane wiring: goal required, stale record cleared, record before the agent, recorder joined before cleanup", () => {
  const wrapper = fs.readFileSync(new URL("./release_settings_login.sh", import.meta.url), "utf8");
  assert.match(wrapper, /rm -f qa-evidence\/agent-rc\.txt qa-evidence\/login-record\.json/);
  assert.ok(wrapper.indexOf('if [[ -z "${QA_GOAL:-}" ]]') < wrapper.indexOf("testbed_release_settings.mjs challenge"), "no goal: stop before the challenge and sign-in");
  assert.match(wrapper, /release_settings_terminal\.mjs --summary "\$work\/summary\.json" --rc "\$rc" --evidence qa-evidence/);
  const mod = fs.readFileSync(new URL("./scenarios/release_settings_case.mjs", import.meta.url), "utf8");
  assert.ok(mod.indexOf("writeReleaseLoginRecord(EVIDENCE") < mod.indexOf("spawnSync(\"bash\""));
  const caseScript = fs.readFileSync(new URL("./release_settings_case.sh", import.meta.url), "utf8");
  assert.match(caseScript, /if \[ -z "\$\{QA_GOAL:-\}" \]; then .*exit 3; fi/);
  assert.doesNotMatch(caseScript, /DEFAULT_GOAL/);
  const loopKill = caseScript.indexOf("kill \\$(cat $LOOP_PID)");
  const recKill = caseScript.indexOf("pkill -INT screenrecord");
  const joined = caseScript.indexOf('if [ "$stopped" != yes ]');
  const pull = caseScript.indexOf("adb pull");
  assert.ok(loopKill > 0 && loopKill < recKill && recKill < joined && joined < pull, "loop stopped, segment stopped, both joined, then pulled");
  assert.match(caseScript, /exit 4\n/);
  const wf = fs.readFileSync(new URL("../../.github/workflows/qa-acceptance.yml", import.meta.url), "utf8");
  for (const re of [/pkill -INT screenrecord/, /ls \/sdcard\/qa-rec-\*\.mp4/]) {
    const line = wf.split("\n").find((l) => re.test(l));
    assert.match(line, /^\s+\[ -f qa-release-lane \] && exit 0; /, String(re));
  }
});
