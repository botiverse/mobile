#!/usr/bin/env node
import crypto from "node:crypto";
import { execFileSync } from "node:child_process";
import { adbRunAsShellArgs, normalizeAndroidUserId } from "./mobile_login_bootstrap_lib.mjs";

const packageIndex = process.argv.indexOf("--package");
const applicationId = packageIndex >= 0 ? process.argv[packageIndex + 1] : "";
if (!/^[A-Za-z][A-Za-z0-9_.]*$/.test(applicationId)) {
  throw new Error("--package must be a valid Android application id");
}

async function waitForCurrentUnlockedUser(expectedUserId, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
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
    if (current === expectedUserId && unlocked === "true") return;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("Android user did not become current and unlocked");
}

async function waitForUserAbsent(userId, timeoutMs) {
  const escapedUserId = userId.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const userEntry = new RegExp(`UserInfo\\{${escapedUserId}:`);
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const users = execFileSync("adb", ["shell", "pm", "list", "users"], {
      encoding: "utf8",
      timeout: 15_000,
    });
    if (!userEntry.test(users)) return;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("secondary Android user remained present");
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

  execFileSync(
    "adb",
    ["shell", "cmd", "package", "install-existing", "--user", probeUserId, applicationId],
    { timeout: 30_000, stdio: "ignore" },
  );
  execFileSync("adb", ["shell", "am", "switch-user", probeUserId], {
    timeout: 30_000,
    stdio: "ignore",
  });
  await waitForCurrentUnlockedUser(probeUserId, 30_000);

  execFileSync(
    "adb",
    adbRunAsShellArgs("exec-in", applicationId, probeUserId, installScript),
    { input: bytes, timeout: 15_000, stdio: ["pipe", "ignore", "inherit"] },
  );
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
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const logcat = execFileSync(
      "adb",
      ["logcat", "-d", "-v", "brief", "-s", "SlockQaBootstrap:I", "*:S"],
      { encoding: "utf8", timeout: 15_000 },
    );
    if (logcat.includes("QA_BOOTSTRAP_STAGE stage=inbox_consumed")) {
      consumed = true;
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
} finally {
  if (probeUserId) {
    const cleanupFailures = [];
    const cleanup = (label, args, timeout) => {
      try {
        execFileSync("adb", args, { timeout, stdio: "ignore" });
      } catch {
        cleanupFailures.push(label);
      }
    };
    cleanup("force_stop", ["shell", "am", "force-stop", "--user", probeUserId, applicationId], 15_000);
    cleanup("package_clear", ["shell", "pm", "clear", "--user", probeUserId, applicationId], 30_000);
    cleanup("switch_original", ["shell", "am", "switch-user", originalUserId], 30_000);
    try {
      await waitForCurrentUnlockedUser(originalUserId, 30_000);
    } catch {
      cleanupFailures.push("wait_original");
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
    try {
      await waitForUserAbsent(probeUserId, 30_000);
    } catch {
      cleanupFailures.push("user_absence");
    }
    if (cleanupFailures.length) {
      throw new Error(`secondary Android user cleanup failed: ${cleanupFailures.join(",")}`);
    }
  }
}
if (!consumed) {
  throw new Error("target activity did not consume the run-as probe");
}
console.log("qa-bootstrap-run-as-probe: activity-consumed");
