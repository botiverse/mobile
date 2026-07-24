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

const KEY_ENV_ORDER = ["QWEN_TOKEN_PLAN_CN_API_KEY", "PI_API_KEY"];

export function redactSecrets(text, secrets) {
  let out = String(text);
  for (const secret of secrets) {
    if (secret && secret.length >= 8) out = out.split(secret).join("<redacted>");
  }
  // Belt-and-braces for bearer-ish tokens that slip through error messages.
  return out.replace(/(Bearer\s+)[A-Za-z0-9._-]{8,}/g, "$1<redacted>");
}

export function normalizeBaseUrl(raw) {
  const base = String(raw || "").trim().replace(/\/+$/, "");
  if (!/^https:\/\/[^\s]+$/.test(base)) {
    throw new Error("base URL must be a single https:// URL");
  }
  return base;
}

export function classifyError(error) {
  const message = String(error?.message || error);
  if (error?.name === "AbortError" || /aborted|timed? ?out/i.test(message)) return "timeout";
  if (/ENOTFOUND|EAI_AGAIN/.test(message)) return "dns";
  if (/ECONNREFUSED|ECONNRESET|EHOSTUNREACH|ENETUNREACH|ETIMEDOUT/.test(message)) return "connect";
  if (/certificate|TLS|SSL/i.test(message)) return "tls";
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
  if (result.status !== 0) {
    return { ok: false, error_class: "connect", error: String(result.stderr || "").slice(0, 200) };
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
      headers: {
        authorization: `Bearer ${apiKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model,
        messages: [{ role: "user", content: "Reply with the single word: pong" }],
        max_tokens: 8,
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
  const baseUrl = normalizeBaseUrl(arg("--base-url", QWEN_TOKEN_PLAN_CN_BASE));
  const model = String(arg("--model", "qwen3.8-max-preview"));
  const outPath = String(arg("--out", "provider-probe.json"));
  const timeoutMs = Number(arg("--completion-timeout-ms", "90000"));
  const withCompletion = !args.includes("--no-completion");
  const apiKey = KEY_ENV_ORDER.map((name) => process.env[name] || "").find(Boolean) || "";

  const report = {
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
