#!/usr/bin/env node
// Run ownership of the Android device the acceptance executor may MODIFY.
//
// The login window needs device-wide changes: stopping the screen recorder,
// turning autofill off, force-stopping and clearing the browser/login surface
// and the keyboard's learned data, and typing through the emulator console.
// They are only allowed on a disposable emulator that this run owns. Two
// separate paths prove that; neither accepts the other's proof:
//
//   hosted (GITHUB_ACTIONS=true): the workflow, right after the emulator it
//     created for this run has booted, runs `claim-hosted`. It requires a
//     fresh emulator (no owner marker yet), writes a random nonce to
//     OWNER_MARKER on the device and a receipt (run id, attempt, serial,
//     console port, AVD name, nonce) to a runner-private file. The agent gets
//     `--device-ownership-receipt <file>`; a standalone token is refused.
//   standalone (anything else): the operator who created a disposable
//     emulator writes a token of their choice to OWNER_MARKER and passes the
//     same token (`--device-owner-token` / QA_DEVICE_OWNER_TOKEN). A hosted
//     receipt is refused.
//
// Both: the ADB target must be an emulator (emulator-<port>), equal to
// --device when given; the marker on that target must equal the receipt nonce
// / token; the console port is the serial's own (an override naming another
// port is refused) and the console must later answer `avd name` with the AVD
// name the ADB target reports (secure_input.mjs emulatorConsoleType).
//
// Verification only reads the device. Without ownership the executor changes
// nothing: a login window then ends UNKNOWN (capture stays suppressed).
//
// Usage (workflow): node scripts/qa/device_ownership.mjs claim-hosted --receipt "$RUNNER_TEMP/qa-device-ownership.json"

import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import fs from "node:fs";
import { pathToFileURL } from "node:url";
import { emulatorConsolePort } from "./secure_input.mjs";

export const OWNER_MARKER = "/data/local/tmp/qa-run-owner";
export const OWNERSHIP_CONTRACT = "qa-device-ownership/v1";
const TOKEN_RE = /^[A-Za-z0-9_-]{16,128}$/;

function readAvdName(adb) {
  for (const prop of ["ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name"]) {
    try {
      const v = String(adb(["shell", "getprop", prop])).trim();
      if (/^[A-Za-z0-9._-]+$/.test(v)) return v;
    } catch { /* next */ }
  }
  return "";
}

// adb(args) -> stdout string (throws on failure). Returns { owned: true, mode,
// serial, consolePort, avdName } or { owned: false, reason }.
export function verifySeatOwnership({ adb, env = process.env, device = "", receiptPath = "", token = "" }) {
  const refuse = (reason) => ({ owned: false, reason });
  const hosted = env.GITHUB_ACTIONS === "true";
  let expected = "";
  let receipt = null;
  if (hosted) {
    if (token) return refuse("a standalone owner token is not accepted on a hosted runner; use the workflow's ownership receipt");
    if (!receiptPath) return refuse("no device ownership receipt for this hosted run");
    try { receipt = JSON.parse(fs.readFileSync(receiptPath, "utf8")); } catch { return refuse("device ownership receipt unreadable"); }
    if (receipt?.contract !== OWNERSHIP_CONTRACT || receipt.mode !== "hosted") return refuse("device ownership receipt has the wrong contract");
    if (!/^\d+$/.test(String(env.GITHUB_RUN_ID || "")) || receipt.github_run_id !== String(env.GITHUB_RUN_ID)
      || receipt.github_run_attempt !== String(env.GITHUB_RUN_ATTEMPT || "")) return refuse("device ownership receipt belongs to another run or attempt");
    if (!TOKEN_RE.test(String(receipt.nonce || ""))) return refuse("device ownership receipt has no valid nonce");
    expected = receipt.nonce;
  } else {
    if (receiptPath) return refuse("a hosted ownership receipt is not accepted outside GitHub Actions; pass --device-owner-token");
    if (!TOKEN_RE.test(String(token || ""))) return refuse("no device owner token (--device-owner-token / QA_DEVICE_OWNER_TOKEN, 16+ chars)");
    expected = token;
  }
  let serial = "";
  try { serial = String(adb(["get-serialno"])).trim(); } catch { return refuse("adb target unreadable"); }
  if (!/^emulator-\d+$/.test(serial)) return refuse("adb target is not an emulator: refusing to modify it");
  if (device && device !== serial) return refuse("adb target does not match --device");
  if (receipt && receipt.serial !== serial) return refuse("adb target is not the emulator this run claimed");
  let marker = "";
  try { marker = String(adb(["shell", "cat", OWNER_MARKER])).trim(); } catch { marker = ""; }
  if (marker !== expected) return refuse("device owner marker does not match this run");
  const consolePort = emulatorConsolePort(serial, env);
  if (!Number.isInteger(consolePort)) return refuse("emulator console port is not the adb target's own");
  if (receipt && receipt.console_port !== consolePort) return refuse("emulator console port differs from the claimed one");
  const avdName = readAvdName(adb);
  if (!avdName) return refuse("AVD name of the adb target unreadable (console cannot be bound)");
  if (receipt && receipt.avd_name !== avdName) return refuse("AVD name differs from the claimed emulator");
  return { owned: true, mode: hosted ? "hosted" : "standalone", serial, consolePort, avdName };
}

// Workflow-side claim of the emulator this run just created.
export function claimHostedSeat({ adb, env = process.env, receiptPath, nonce = randomBytes(24).toString("hex") }) {
  if (env.GITHUB_ACTIONS !== "true" || !/^\d+$/.test(String(env.GITHUB_RUN_ID || ""))) throw new Error("claim-hosted runs only inside a GitHub Actions run");
  if (!receiptPath) throw new Error("--receipt required");
  const serial = String(adb(["get-serialno"])).trim();
  if (!/^emulator-\d+$/.test(serial)) throw new Error("adb target is not an emulator");
  let existing = "";
  try { existing = String(adb(["shell", "cat", OWNER_MARKER])).trim(); } catch { existing = ""; }
  if (existing) throw new Error("emulator already carries an owner marker: not a fresh emulator of this run");
  adb(["shell", `umask 077; echo ${nonce} > ${OWNER_MARKER}`]);
  if (String(adb(["shell", "cat", OWNER_MARKER])).trim() !== nonce) throw new Error("owner marker could not be written");
  const avdName = readAvdName(adb);
  if (!avdName) throw new Error("AVD name unreadable");
  const receipt = {
    contract: OWNERSHIP_CONTRACT, mode: "hosted",
    github_run_id: String(env.GITHUB_RUN_ID), github_run_attempt: String(env.GITHUB_RUN_ATTEMPT || ""),
    serial, console_port: emulatorConsolePort(serial, {}), avd_name: avdName, nonce,
  };
  fs.writeFileSync(receiptPath, JSON.stringify(receipt), { mode: 0o600 });
  return { serial, avdName };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [cmd, ...rest] = process.argv.slice(2);
  const opt = (f) => { const i = rest.indexOf(f); return i >= 0 ? rest[i + 1] : ""; };
  const adb = (args) => execFileSync("adb", args, { encoding: "utf8", timeout: 60000, stdio: ["ignore", "pipe", "ignore"] });
  try {
    if (cmd !== "claim-hosted") throw new Error("usage: device_ownership.mjs claim-hosted --receipt <file>");
    const r = claimHostedSeat({ adb, receiptPath: opt("--receipt") });
    console.log(`device ownership: claimed ${r.serial} (${r.avdName}) for this run`);
  } catch (error) {
    console.error(`device ownership: ${String(error?.message || error)}`);
    process.exit(1);
  }
}
