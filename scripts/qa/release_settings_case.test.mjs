// Release settings lane evidence (synthetic files): the record the matched case writes passes the
// same gates the Alpha path uses (agent readRecord, secure_evidence.mjs before both uploads);
// without a match there is no record and nothing is uploaded.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { readRecord } from "./login_phase.mjs";
import { gate } from "./secure_evidence.mjs";
import { threadtimeStamp, writeReleaseLoginRecord } from "./scenarios/release_settings_case.mjs";

const env = { GITHUB_RUN_ID: "4242", GITHUB_RUN_ATTEMPT: "1" };
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "release-case-"));

test("device date -> threadtime login_end; anything else is no time", () => {
  assert.equal(threadtimeStamp("11-19 08:30:01.123456789\n"), "11-19 08:30:01.123");
  assert.equal(threadtimeStamp("11-19 08:30:01.123"), "11-19 08:30:01.123");
  for (const bad of ["", "date: bad format", "11-19 08:30:01", "2026-11-19 08:30:01.123", null]) assert.equal(threadtimeStamp(bad), null, String(bad));
  assert.throws(() => writeReleaseLoginRecord(tmp(), null, env), /no device time/);
});

test("the matched case's record passes the agent and the evidence gate; log lines up to the match are dropped", () => {
  const dir = tmp();
  const record = writeReleaseLoginRecord(dir, "11-19 08:30:01.123", env);
  assert.deepEqual(Object.keys(record).sort(), ["contract", "lane", "login_end", "mode", "reason", "run", "status"]);
  assert.ok(!JSON.stringify(record).includes("@"), "no account data");
  assert.equal(readRecord(dir, env).ok, true);
  assert.equal(readRecord(dir, { ...env, GITHUB_RUN_ATTEMPT: "2" }).ok, false, "bound to this run attempt");
  fs.writeFileSync(path.join(dir, "logcat.txt"), [
    "11-19 08:29:59.000  100  100 I SlockAppLogin: app_login_complete result=app_shell reason=exchanged",
    "11-19 08:30:01.123  100  100 I Raft: at the match",
    "11-19 08:30:05.000  100  100 I Raft: settings opened",
  ].join("\n"));
  fs.writeFileSync(path.join(dir, "verdict.json"), "{\"verdict\":\"pass\"}\n");
  fs.writeFileSync(path.join(dir, "step-01.png"), "png");
  fs.writeFileSync(path.join(dir, "release-login-summary.json"), "{\"identity_receipt\":{\"state\":\"matched\"}}\n");
  const result = gate(dir, env);
  assert.deepEqual(result, { ok: true, reason: null });
  assert.equal(fs.readFileSync(path.join(dir, "logcat.txt"), "utf8"), "11-19 08:30:05.000  100  100 I Raft: settings opened");
  for (const f of ["step-01.png", "verdict.json", "release-login-summary.json"]) assert.ok(fs.existsSync(path.join(dir, f)), f);
  fs.rmSync(dir, { recursive: true });
});

test("without a match there is no record: the gate withholds everything, as on the Alpha path", () => {
  const dir = tmp();
  fs.writeFileSync(path.join(dir, "release-login-summary.json"), "{\"identity_receipt\":{\"state\":\"awaiting_confirmation\"}}\n");
  fs.writeFileSync(path.join(dir, "agent-rc.txt"), "2\n");
  const result = gate(dir, env);
  assert.equal(result.ok, false);
  assert.match(result.reason, /login record missing/);
  assert.deepEqual(fs.readdirSync(dir).sort(), ["agent-rc.txt", "evidence-gate.json", "verdict.json"]);
  fs.rmSync(dir, { recursive: true });
});

test("the wrapper clears a stale record and the case writes the record before the agent runs", () => {
  const wrapper = fs.readFileSync(new URL("./release_settings_login.sh", import.meta.url), "utf8");
  assert.match(wrapper, /rm -f qa-evidence\/agent-rc\.txt qa-evidence\/login-record\.json/);
  const mod = fs.readFileSync(new URL("./scenarios/release_settings_case.mjs", import.meta.url), "utf8");
  assert.ok(mod.indexOf("writeReleaseLoginRecord(EVIDENCE") < mod.indexOf("spawnSync(\"bash\""));
});
