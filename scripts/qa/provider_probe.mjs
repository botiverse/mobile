#!/usr/bin/env node
// Review-panel provider network probe (task #354 panel-infra sub-item).
// Panel run 30102844933 burned 3x ~11min on `Request timed out` against
// qwen-token-plan-cn (cn-beijing Aliyun) without telling us WHICH layer was
// slow: runner egress, TLS, or model generation. This probe runs on the same
// runner class and splits those layers so the provider decision (direct vs
// CF proxy vs MiniMax fallback) is made on measurements, not guesses.
//
//   reachability — curl timing matrix (dns/tcp/tls/ttfb) against the
//                  provider base URL, unauthenticated (expects 401/404 fast).
//   completion   — minimal 1-token OpenAI-compatible chat completion with a
//                  hard timeout, authenticated; classifies error vs latency.
//
// Output is receipt-safe: latency numbers, HTTP status, and error class only.
// The API key never reaches argv, logs, or the report file.
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import process from "node:process";

export const QWEN_TOKEN_PLAN_CN_BASE =
  "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";

// Credentialed requests may ONLY go to authorities frozen in this reviewed
// allowlist. The dispatcher picks a NAME; the origin/path mapping ships in
// the repo and changes only through a reviewed PR (e.g. adding "cf-proxy"
// once the worker origin exists). A free-form URL input would let any
// dispatcher point the Authorization header at an arbitrary authority.
export const PROBE_TARGETS = Object.freeze({
  "direct-qwen": QWEN_TOKEN_PLAN_CN_BASE,
});

export function resolveProbeTarget(name) {
  const key = String(name || "").trim();
  if (!Object.prototype.hasOwnProperty.call(PROBE_TARGETS, key)) {
    throw new Error(`unknown probe target: ${key || "<empty>"}; allowed: ${Object.keys(PROBE_TARGETS).join(", ")}`);
  }
  // Defense in depth: the frozen value must itself survive URL hardening.
  return normalizeBaseUrl(PROBE_TARGETS[key]);
}

const KEY_ENV_ORDER = ["QWEN_TOKEN_PLAN_CN_API_KEY", "PI_API_KEY"];

// Hard timeout bounds: below the 10-minute job cap with margin, and never a
// value that setTimeout would coerce into an instant abort.
export const COMPLETION_TIMEOUT_MIN_MS = 1000;
export const COMPLETION_TIMEOUT_MAX_MS = 300000;

export function redactSecrets(text, secrets) {
  let out = String(text);
  // Any non-empty secret is redacted — a short key is still a key.
  for (const secret of secrets) {
    if (secret) out = out.split(secret).join("<redacted>");
  }
  // Belt-and-braces for bearer-ish tokens that slip through error messages.
  return out.replace(/(Bearer\s+)[A-Za-z0-9._-]{8,}/g, "$1<redacted>");
}

// Probe targets are provider domains or CF proxy hostnames — never raw IP
// literals, credentials, queries, or fragments. Rejecting every hostname
// without a letter kills the whole loopback/private/link-local literal class
// (127.0.0.1, 10.x, [::1], 169.254.x, ...) in one rule; localhost names are
// rejected explicitly.
const SAFE_PATH = /^[A-Za-z0-9._~/-]*$/;

export function normalizeBaseUrl(raw) {
  const trimmed = String(raw || "").trim();
  let url;
  try {
    url = new URL(trimmed);
  } catch {
    throw new Error("base URL must be a valid https:// URL");
  }
  if (url.protocol !== "https:") throw new Error("base URL must use https");
  if (url.username || url.password) throw new Error("base URL must not carry credentials");
  if (url.search || url.hash) throw new Error("base URL must not carry query or fragment");
  // Canonicalize the DNS terminal dot BEFORE the name checks so
  // "localhost." / "api.localhost." cannot bypass the comparisons below while
  // still resolving to the same host.
  const host = url.hostname.toLowerCase().replace(/\.$/, "");
  if (host === "localhost" || host.endsWith(".localhost")) {
    throw new Error("base URL host must not be localhost");
  }
  if (!/[a-z]/.test(host) || host.startsWith("[")) {
    throw new Error("base URL host must be a DNS name, not an IP literal");
  }
  if (!host.includes(".")) {
    throw new Error("base URL host must not be a single-label/internal name");
  }
  if (!SAFE_PATH.test(url.pathname) || url.pathname.split("/").includes("..")) {
    throw new Error("base URL path contains unsupported characters");
  }
  return `https://${host}${url.port ? `:${url.port}` : ""}${url.pathname}`.replace(/\/+$/, "");
}

export function requireCompletionTimeoutMs(raw) {
  const value = Number(raw);
  if (!Number.isInteger(value) || value < COMPLETION_TIMEOUT_MIN_MS || value > COMPLETION_TIMEOUT_MAX_MS) {
    throw new Error(
      `completion timeout must be an integer in [${COMPLETION_TIMEOUT_MIN_MS}, ${COMPLETION_TIMEOUT_MAX_MS}] ms, got: ${raw}`,
    );
  }
  return value;
}

export function classifyError(error) {
  const message = String(error?.message || error);
  if (error?.name === "AbortError" || /aborted|timed? ?out/i.test(message)) return "timeout";
  if (/ENOTFOUND|EAI_AGAIN/.test(message)) return "dns";
  if (/ECONNREFUSED|ECONNRESET|EHOSTUNREACH|ENETUNREACH|ETIMEDOUT/.test(message)) return "connect";
  if (/certificate|TLS|SSL/i.test(message)) return "tls";
  if (/redirect/i.test(message)) return "redirect";
  return "other";
}

// curl(1) exit codes — the layers this probe exists to distinguish must not
// collapse into one bucket.
export function classifyCurlExit(code) {
  if (code === 5 || code === 6) return "dns";
  if (code === 7) return "connect";
  if (code === 28) return "timeout";
  if ([35, 51, 53, 54, 58, 59, 60, 77, 82, 83, 90, 91].includes(code)) return "tls";
  return "other";
}

export function parseCurlTiming(stdout) {
  const timing = {};
  for (const pair of String(stdout).trim().split(" ")) {
    const [key, value] = pair.split("=");
    if (key && value !== undefined) timing[key] = Number(value);
  }
  return timing;
}

function curlProbe(url, maxTimeSeconds) {
  const format =
    "dns=%{time_namelookup} tcp=%{time_connect} tls=%{time_appconnect} ttfb=%{time_starttransfer} total=%{time_total} http=%{http_code}";
  const result = spawnSync(
    "curl",
    ["-o", "/dev/null", "-sS", "-w", format, "--max-time", String(maxTimeSeconds), url],
    { encoding: "utf8", timeout: (maxTimeSeconds + 10) * 1000 },
  );
  if (result.error) {
    return { ok: false, error_class: "spawn", error: String(result.error.message || result.error).slice(0, 200) };
  }
  if (result.status !== 0) {
    return {
      ok: false,
      curl_exit: result.status,
      error_class: classifyCurlExit(result.status),
      error: String(result.stderr || "").slice(0, 200),
    };
  }
  return { ok: true, ...parseCurlTiming(result.stdout) };
}

async function completionProbe(baseUrl, model, apiKey, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const startedAt = Date.now();
  try {
    const response = await fetch(`${baseUrl}/chat/completions`, {
      method: "POST",
      signal: controller.signal,
      redirect: "error",
      headers: {
        authorization: `Bearer ${apiKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model,
        messages: [{ role: "user", content: "Reply with the single word: pong" }],
        max_tokens: 1,
        stream: false,
      }),
    });
    const text = await response.text();
    let usable = false;
    try {
      const parsed = JSON.parse(text);
      usable = Boolean(parsed?.choices?.[0]?.message);
    } catch {
      usable = false;
    }
    return {
      ok: response.ok && usable,
      http: response.status,
      elapsed_ms: Date.now() - startedAt,
      body_usable: usable,
    };
  } catch (error) {
    return {
      ok: false,
      error_class: classifyError(error),
      elapsed_ms: Date.now() - startedAt,
    };
  } finally {
    clearTimeout(timer);
  }
}

async function main() {
  const args = process.argv.slice(2);
  const arg = (flag, fallback) => {
    const index = args.indexOf(flag);
    return index >= 0 ? args[index + 1] : fallback;
  };
  const targetName = String(arg("--target", "direct-qwen"));
  const baseUrl = resolveProbeTarget(targetName);
  const model = String(arg("--model", "qwen3.8-max-preview"));
  const outPath = String(arg("--out", "provider-probe.json"));
  const withCompletion = !args.includes("--no-completion");
  // Validate before any network activity so an invalid budget can never turn
  // into a misleading instant-abort measurement.
  const timeoutMs = withCompletion
    ? requireCompletionTimeoutMs(arg("--completion-timeout-ms", "90000"))
    : null;
  const apiKey = KEY_ENV_ORDER.map((name) => process.env[name] || "").find(Boolean) || "";

  const report = {
    target: targetName,
    base_url: baseUrl,
    model,
    reachability: [],
    completion: null,
  };
  for (let attempt = 1; attempt <= 3; attempt++) {
    const timing = curlProbe(`${baseUrl}/models`, 20);
    report.reachability.push(timing);
    console.log(`probe reachability attempt=${attempt} ${JSON.stringify(timing)}`);
  }
  if (withCompletion && apiKey) {
    report.completion = await completionProbe(baseUrl, model, apiKey, timeoutMs);
    console.log(`probe completion ${JSON.stringify(report.completion)}`);
  } else if (withCompletion) {
    report.completion = { ok: false, error_class: "no_api_key" };
    console.log("probe completion skipped: no API key in environment");
  }
  fs.writeFileSync(outPath, redactSecrets(JSON.stringify(report, null, 2), [apiKey]));

  const reachable = report.reachability.some((r) => r.ok && Number(r.http) > 0);
  const verdict = !reachable
    ? "unreachable"
    : report.completion == null
      ? "reachable_only"
      : report.completion.ok
        ? "healthy"
        : `reachable_but_completion_${report.completion.error_class || `http_${report.completion.http}`}`;
  console.log(`probe verdict: ${verdict}`);
}

const invokedDirectly = process.argv[1]?.endsWith("provider_probe.mjs");
if (invokedDirectly) {
  main().catch((error) => {
    console.error(redactSecrets(String(error?.stack || error), [process.env.QWEN_TOKEN_PLAN_CN_API_KEY || "", process.env.PI_API_KEY || ""]));
    process.exitCode = 1;
  });
}
