#!/usr/bin/env node
import crypto from "node:crypto";
import { execFileSync } from "node:child_process";
import { adbRunAsShellArgs, normalizeAndroidUserId } from "./mobile_login_bootstrap_lib.mjs";

const packageIndex = process.argv.indexOf("--package");
const applicationId = packageIndex >= 0 ? process.argv[packageIndex + 1] : "";
if (!/^[A-Za-z][A-Za-z0-9_.]*$/.test(applicationId)) {
  throw new Error("--package must be a valid Android application id");
}

class ProbeWaitError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

function emitPhase(phase, outcome, code = "") {
  const suffix = code ? ` code=${code}` : "";
  process.stdout.write(`qa-bootstrap-run-as-probe: phase=${phase} outcome=${outcome}${suffix}\n`);
}

function waitForAdb() {
  execFileSync("adb", ["wait-for-device"], { timeout: 15_000, stdio: "ignore" });
}

async function waitForCurrentUnlockedUser(expectedUserId, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let lastObservation = "unavailable";
  while (Date.now() < deadline) {
    try {
      waitForAdb();
      const current = normalizeAndroidUserId(
        execFileSync("adb", ["shell", "am", "get-current-user"], {
          encoding: "utf8",
          timeout: 15_000,
        }),
      );
      const unlocked = execFileSync(
        "adb",
        ["shell", "cmd", "user", "is-user-unlocked", expectedUserId],
        { encoding: "utf8", timeout: 15_000 },
      ).trim();
      lastObservation = "state";
      if (current === expectedUserId && unlocked === "true") return;
    } catch {
      lastObservation = "unavailable";
      // Switching Android users can restart the ADB transport. The exact state
      // is authoritative once readable; transient transport loss is retried.
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new ProbeWaitError(lastObservation === "state" ? "state_timeout" : "read_unavailable");
}

async function waitForUserAbsent(userId, timeoutMs) {
  const escapedUserId = userId.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const userEntry = new RegExp(`UserInfo\\{${escapedUserId}:`);
  const deadline = Date.now() + timeoutMs;
  let lastObservation = "unavailable";
  while (Date.now() < deadline) {
    try {
      waitForAdb();
      const users = execFileSync("adb", ["shell", "pm", "list", "users"], {
        encoding: "utf8",
        timeout: 15_000,
      });
      if (!userEntry.test(users)) return;
      lastObservation = "present";
    } catch {
      lastObservation = "unavailable";
      // Absence cannot be inferred from an unreadable user list. Retry until
      // the bounded deadline, then report observation failure separately.
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new ProbeWaitError(lastObservation === "present" ? "present_timeout" : "read_unavailable");
}

async function waitForLogcatStage(expectedStage, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let lastObservation = "unavailable";
  while (Date.now() < deadline) {
    try {
      waitForAdb();
      const logcat = execFileSync(
        "adb",
        ["logcat", "-d", "-v", "brief", "-s", "SlockQaBootstrap:I", "*:S"],
        { encoding: "utf8", timeout: 15_000 },
      );
      lastObservation = "stage_missing";
      if (logcat.includes(expectedStage)) return;
    } catch {
      lastObservation = "unavailable";
      // Keep the product stage distinct from a transient log transport read.
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new ProbeWaitError(lastObservation === "stage_missing" ? "stage_timeout" : "read_unavailable");
}

const originalUserId = normalizeAndroidUserId(
  execFileSync("adb", ["shell", "am", "get-current-user"], { encoding: "utf8", timeout: 15_000 }),
);
const bytes = Buffer.from("qa-bootstrap-user-root-probe-v1", "utf8");
const requestId = "host-user-root-probe";
const relative = `files/qa-bootstrap/inbox/${requestId}.session`;
const installScript = [
  "set -eu",
  "umask 077",
  "mkdir -p files/qa-bootstrap/inbox",
  `tmp=${relative}.tmp`,
  `dst=${relative}`,
  "trap 'rm -f \"$tmp\"' EXIT",
  "cat > \"$tmp\"",
  "chmod 600 \"$tmp\"",
  "mv -f \"$tmp\" \"$dst\"",
  "trap - EXIT",
].join("; ");

const digest = crypto.createHash("sha256").update(bytes).digest("hex");
let probeUserId;
let consumed = false;
let activePhase = "create_user";
let activePhaseRecorded = false;
let primaryFailure = "";
const beginPhase = (phase) => {
  activePhase = phase;
  activePhaseRecorded = false;
};
const passPhase = () => {
  emitPhase(activePhase, "pass");
  activePhaseRecorded = true;
};
try {
  const created = execFileSync(
    "adb",
    ["shell", "pm", "create-user", "--ephemeral", "qa-bootstrap-probe"],
    { encoding: "utf8", timeout: 30_000 },
  ).trim();
  const createdMatch = created.match(/^Success: created user id ([0-9]+)$/);
  if (!createdMatch) throw new Error("secondary Android user creation failed");
  probeUserId = normalizeAndroidUserId(createdMatch[1]);
  if (probeUserId === originalUserId) throw new Error("secondary Android user was not distinct");
  passPhase();

  beginPhase("install_existing");
  execFileSync(
    "adb",
    ["shell", "cmd", "package", "install-existing", "--user", probeUserId, applicationId],
    { timeout: 30_000, stdio: "ignore" },
  );
  passPhase();
  beginPhase("switch_target");
  execFileSync("adb", ["shell", "am", "switch-user", probeUserId], {
    timeout: 30_000,
    stdio: "ignore",
  });
  passPhase();
  beginPhase("target_unlocked");
  await waitForCurrentUnlockedUser(probeUserId, 30_000);
  passPhase();

  beginPhase("private_write");
  execFileSync(
    "adb",
    adbRunAsShellArgs("exec-in", applicationId, probeUserId, installScript),
    { input: bytes, timeout: 15_000, stdio: ["pipe", "ignore", "inherit"] },
  );
  passPhase();
  beginPhase("host_readback");
  execFileSync(
    "adb",
    adbRunAsShellArgs(
      "exec-out",
      applicationId,
      probeUserId,
      [
        `test "$(stat -c %a ${relative})" = 600`,
        `test "$(wc -c < ${relative} | tr -d '[:space:]')" = ${bytes.length}`,
        `test "$(sha256sum ${relative} | cut -d ' ' -f 1)" = ${digest}`,
      ].join("; "),
    ),
    { timeout: 15_000, stdio: ["ignore", "ignore", "inherit"] },
  );
  passPhase();

  beginPhase("activity_start");
  execFileSync("adb", ["logcat", "-c"], { timeout: 15_000, stdio: "ignore" });
  execFileSync(
    "adb",
    [
      "shell", "am", "start", "--user", probeUserId,
      "-n", `${applicationId}/.core.data.auth.QaSessionBootstrapActivity`,
      "--es", "qaBootstrapRequestId", requestId,
      "--es", "qaBootstrapEnvelopeDigest", `sha256:${digest}`,
    ],
    { timeout: 15_000, stdio: "ignore" },
  );
  passPhase();
  beginPhase("activity_consume");
  await waitForLogcatStage("QA_BOOTSTRAP_STAGE stage=inbox_consumed", 15_000);
  consumed = true;
  passPhase();
} catch (error) {
  primaryFailure = activePhase;
  if (!activePhaseRecorded) {
    const code = error instanceof ProbeWaitError ? error.code : "command_failed";
    emitPhase(activePhase, "fail", code);
    activePhaseRecorded = true;
  }
} finally {
  if (probeUserId) {
    const cleanupFailures = [];
    const cleanup = (label, args, timeout) => {
      try {
        execFileSync("adb", args, { timeout, stdio: "ignore" });
        emitPhase(`cleanup_${label}`, "pass");
      } catch {
        cleanupFailures.push(label);
        emitPhase(`cleanup_${label}`, "fail", "command_failed");
      }
    };
    cleanup("force_stop", ["shell", "am", "force-stop", "--user", probeUserId, applicationId], 15_000);
    cleanup("package_clear", ["shell", "pm", "clear", "--user", probeUserId, applicationId], 30_000);
    cleanup("switch_original", ["shell", "am", "switch-user", originalUserId], 30_000);
    try {
      await waitForCurrentUnlockedUser(originalUserId, 30_000);
      emitPhase("cleanup_wait_original", "pass");
    } catch (error) {
      const code = error instanceof ProbeWaitError ? error.code : "read_unavailable";
      cleanupFailures.push(`wait_original_${code}`);
      emitPhase("cleanup_wait_original", "fail", code);
    }
    // Ephemeral users may be removed automatically when switching away. An
    // explicit remove can therefore report failure even though cleanup is
    // already complete; the authoritative gate is exact absence afterward.
    try {
      execFileSync("adb", ["shell", "pm", "remove-user", probeUserId], {
        timeout: 30_000,
        stdio: "ignore",
      });
    } catch {
      // Checked by the exact absence readback below.
    }
    emitPhase("cleanup_remove", "attempted");
    try {
      await waitForUserAbsent(probeUserId, 30_000);
      emitPhase("cleanup_user_absence", "pass");
    } catch (error) {
      const code = error instanceof ProbeWaitError ? error.code : "read_unavailable";
      cleanupFailures.push(`user_absence_${code}`);
      emitPhase("cleanup_user_absence", "fail", code);
    }
    if (cleanupFailures.length) {
      emitPhase("cleanup_terminal", "fail", cleanupFailures.join("_and_"));
      primaryFailure ||= "cleanup_terminal";
    } else {
      emitPhase("cleanup_terminal", "pass");
    }
  } else {
    emitPhase("cleanup_terminal", "not_required");
  }
}
if (primaryFailure || !consumed) {
  throw new Error(`run-as probe failed at fixed phase: ${primaryFailure || "activity_consume"}`);
}
console.log("qa-bootstrap-run-as-probe: activity-consumed");
