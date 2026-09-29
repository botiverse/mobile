import crypto from "node:crypto";

const DIGEST = /^sha256:[a-f0-9]{64}$/;
const REQUEST_ID = /^[A-Za-z0-9._-]{1,80}$/;
const APK_SIGNER_SHA256 =
  /^(?:V[0-9]+(?:\.[0-9]+)? )?Signer:?(?: #[1-9][0-9]*)?(?: \(minSdkVersion=([0-9]+), maxSdkVersion=([0-9]+)\))? certificate SHA-256 digest:\s*([A-Fa-f0-9]{64})$/;
const APK_SIGNER_PREFIX = /^(?:V[0-9]+(?:\.[0-9]+)? )?Signer\b/;

function quotePosixShellWord(value) {
  return `'${String(value).replaceAll("'", `'"'"'`)}'`;
}

export function adbRunAsShellArgs(direction, applicationId, script) {
  if (direction !== "exec-in" && direction !== "exec-out" && direction !== "shell") {
    throw new Error("ADB run-as direction is invalid");
  }
  if (!/^[A-Za-z][A-Za-z0-9_.]*$/.test(applicationId)) {
    throw new Error("Android application id is invalid");
  }
  if (typeof script !== "string" || !script) {
    throw new Error("ADB run-as script is invalid");
  }
  // exec-in/exec-out/shell flatten the remote command to a shell string. Keep the
  // complete sh -c program in one quoted remote argument; otherwise commands
  // after the first semicolon can run as the shell user outside run-as.
  const remote = `run-as ${quotePosixShellWord(applicationId)} sh -c ${quotePosixShellWord(script)}`;
  // `shell` is the shell-v2 channel (adb >= 24, device >= API 24): stdin EOF
  // travels as a CLOSE_STDIN packet instead of tearing the connection down,
  // and stdout plus the remote exit status come back on the same channel.
  // `-T` pins no-pty so stdin bytes are never line-processed or CRLF-rewritten.
  return direction === "shell" ? [direction, "-T", remote] : [direction, remote];
}

function requireAndroidDeviceSdk(deviceSdk) {
  if (!Number.isInteger(deviceSdk) || deviceSdk < 1 || deviceSdk > 0x7fffffff) {
    throw new Error("Android device SDK is invalid");
  }
}

export function apksignerVerifyArgs(apk, deviceSdk) {
  requireAndroidDeviceSdk(deviceSdk);
  return [
    "verify",
    "--min-sdk-version", String(deviceSdk),
    "--max-sdk-version", String(deviceSdk),
    "--print-certs",
    apk,
  ];
}

export function selectApkSigningCertificateSha256(apksignerOutput, deviceSdk) {
  requireAndroidDeviceSdk(deviceSdk);
  // apksigner renders a targeted v3.1 rotation as one certificate record per
  // SDK range. Android exposes only the certificate selected for the running
  // device through SigningInfo.apkContentsSigners, so the host must make the
  // same SDK-bound choice before requesting the exact-bound login envelope.
  const certificates = [];
  for (const rawLine of String(apksignerOutput).split(/\r?\n/)) {
    const line = rawLine.trim();
    const match = line.match(APK_SIGNER_SHA256);
    if (!match) {
      if (APK_SIGNER_PREFIX.test(line) && line.includes("certificate SHA-256 digest:")) {
        throw new Error("APK signer certificate record is invalid");
      }
      continue;
    }
    const scoped = match[1] !== undefined || match[2] !== undefined;
    if (scoped && (match[1] === undefined || match[2] === undefined)) {
      throw new Error("APK signer SDK range is invalid");
    }
    const minSdk = scoped ? Number(match[1]) : 1;
    const maxSdk = scoped ? Number(match[2]) : 0x7fffffff;
    if (
      !Number.isInteger(minSdk) || !Number.isInteger(maxSdk) ||
      minSdk < 1 || maxSdk < minSdk || maxSdk > 0x7fffffff
    ) throw new Error("APK signer SDK range is invalid");
    certificates.push({ minSdk, maxSdk, digest: match[3].toLowerCase() });
  }
  const applicable = certificates.filter(({ minSdk, maxSdk }) => deviceSdk >= minSdk && deviceSdk <= maxSdk);
  if (applicable.length !== 1) {
    throw new Error("APK must have exactly one signing certificate for the Android device SDK");
  }
  return `sha256:${applicable[0].digest}`;
}

export function canonicalJson(value) {
  if (value === null || typeof value === "boolean" || typeof value === "number" || typeof value === "string") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
  }
  throw new Error("unsupported canonical JSON value");
}

export function sha256Digest(bytes) {
  return `sha256:${crypto.createHash("sha256").update(bytes).digest("hex")}`;
}

export function validateLoginResponse(value, expected) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("login response must be an object");
  if (!REQUEST_ID.test(value.request_id ?? "") || !DIGEST.test(value.envelope_digest ?? "") || !value.envelope) {
    throw new Error("login response identity is invalid");
  }
  const envelope = value.envelope;
  const exactKeys = [
    "version", "run_case_id", "attempt", "ci_job_ref", "principal_id", "server_id", "target_route",
    "application_id", "signing_cert_sha256", "boot_generation", "user", "access_token", "refresh_token",
  ];
  if (!envelope || typeof envelope !== "object" || Array.isArray(envelope) ||
      Object.keys(envelope).sort().join("\0") !== exactKeys.sort().join("\0")) {
    throw new Error("login envelope keys are invalid");
  }
  if (
    envelope.version !== "qa-bootstrap-login/v1" ||
    envelope.run_case_id !== expected.runCaseId ||
    !Number.isInteger(envelope.attempt) || envelope.attempt < 1 ||
    envelope.ci_job_ref !== expected.ciJobRef ||
    envelope.target_route !== "compose_slock_app_shell" ||
    envelope.application_id !== expected.applicationId ||
    envelope.signing_cert_sha256 !== expected.signingCertSha256 ||
    envelope.boot_generation !== expected.bootGeneration ||
    typeof envelope.principal_id !== "string" || envelope.principal_id !== envelope.user?.id ||
    typeof envelope.server_id !== "string" || !envelope.server_id ||
    typeof envelope.access_token !== "string" || !envelope.access_token ||
    typeof envelope.refresh_token !== "string" || !envelope.refresh_token
  ) throw new Error("login envelope binding is invalid");
  const bytes = Buffer.from(canonicalJson(envelope), "utf8");
  if (sha256Digest(bytes) !== value.envelope_digest) {
    bytes.fill(0);
    throw new Error("login envelope digest mismatch");
  }
  return { requestId: value.request_id, envelopeDigest: value.envelope_digest, envelope, bytes };
}

export function parseReadyLine(text, expected) {
  const lines = String(text).split(/\r?\n/).filter((line) => line.includes("QA_BOOTSTRAP_READY "));
  for (let index = lines.length - 1; index >= 0; index -= 1) {
    const marker = lines[index].slice(lines[index].indexOf("QA_BOOTSTRAP_READY ") + "QA_BOOTSTRAP_READY ".length);
    const fields = Object.fromEntries(marker.split(/\s+/).map((entry) => {
      const separator = entry.indexOf("=");
      return separator > 0 ? [entry.slice(0, separator), entry.slice(separator + 1)] : [entry, ""];
    }));
    if (
      fields.envelopeDigest === expected.envelopeDigest &&
      fields.runCaseId === expected.runCaseId &&
      fields.attempt === String(expected.attempt) &&
      fields.ciJobRef === expected.ciJobRef &&
      fields.principalId === expected.principalId &&
      fields.serverId === expected.serverId &&
      fields.route === expected.targetRoute &&
      fields.applicationId === expected.applicationId &&
      fields.bootGeneration === expected.bootGeneration &&
      /^\d+$/.test(fields.pid ?? "") &&
      fields.processInstanceId && fields.processGeneration &&
      /^\d+$/.test(fields.deviceReadyElapsedRealtimeNanos ?? "") &&
      DIGEST.test(fields.readyReceiptDigest ?? "") &&
      DIGEST.test(fields.runtimeEnvelopeDigest ?? "") &&
      DIGEST.test(fields.routeReadyProofDigest ?? "")
    ) return { line: lines[index], fields };
  }
  return null;
}

export function parseBootstrapDiagnostics(text) {
  const diagnostics = [];
  const pattern = /QA_BOOTSTRAP_(?:STAGE|FAILURE) stage=[a-z0-9_]+(?: code=[a-z0-9_]+)?/g;
  for (const match of String(text).matchAll(pattern)) {
    if (!diagnostics.includes(match[0])) diagnostics.push(match[0]);
  }
  return diagnostics;
}

export function readyPayload(login, ready) {
  const fields = ready.fields;
  return {
    request_id: login.requestId,
    envelope_digest: login.envelopeDigest,
    runtime_envelope: {
      ready_receipt_digest: fields.readyReceiptDigest,
      pid: Number(fields.pid),
      process_instance_id: fields.processInstanceId,
      process_start: fields.processGeneration,
      process_generation: fields.processGeneration,
      boot_generation: fields.bootGeneration,
      device_ready_elapsed_realtime_nanos: fields.deviceReadyElapsedRealtimeNanos,
      route_ready_proof_digest: fields.routeReadyProofDigest,
      recovery_of: fields.recoveryOf ?? null,
    },
  };
}
