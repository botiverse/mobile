#!/usr/bin/env node
import fs from "node:fs";

const SUBJECT = /^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,255}$/;

export function extractStableSubject(texts) {
  const found = new Set();
  for (const text of texts) {
    for (const line of String(text).split(/\r?\n/)) {
      let message = null;
      try {
        const parsed = JSON.parse(line);
        if (typeof parsed?.eventMessage === "string" && parsed.eventMessage.startsWith("[SlockSharedAuth][info] ")) {
          message = parsed.eventMessage.slice("[SlockSharedAuth][info] ".length);
        }
      } catch {}
      if (message === null) {
        const android = /(?:^|\s)I\/SlockSharedAuth(?:\(\s*\d+\))?:\s+(.+)$/.exec(line);
        const ios = /\b[^\s]+\[\d+\]:\s+\([^)]*\)\s+\[SlockSharedAuth\]\[info\]\s+(.+)$/.exec(line);
        message = android?.[1] ?? ios?.[1] ?? null;
      }
      if (message === null) continue;
      const match = /^shared_auth_session QA_CREDENTIAL_IDENTITY version=1 principalId=([^\s]+) authGeneration=([1-9][0-9]*)$/.exec(message);
      if (match && SUBJECT.test(match[1])) found.add(match[1]);
    }
  }
  return found.size === 1 ? [...found][0] : null;
}

export function credentialReceipt(email, texts = []) {
  if (typeof email !== "string" || email !== email.trim() || email.length > 320 ||
      !/^[^\s@]+@[^\s@]+$/.test(email)) return null;
  return { login_email: email, stable_subject: extractStableSubject(texts) };
}

if (process.argv[1] === new URL(import.meta.url).pathname) {
  const args = process.argv.slice(2);
  const outIndex = args.indexOf("--out");
  if (outIndex < 0 || !args[outIndex + 1]) process.exit(2);
  const logs = [];
  for (let index = 0; index < args.length; index++) {
    if (args[index] === "--log" && args[index + 1] && fs.existsSync(args[index + 1])) {
      logs.push(fs.readFileSync(args[++index], "utf8"));
    }
  }
  const receipt = credentialReceipt(process.env.QA_TEST_EMAIL, logs);
  if (!receipt) process.exit(3);
  fs.writeFileSync(args[outIndex + 1], JSON.stringify(receipt), { mode: 0o600 });
}
