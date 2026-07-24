import assert from "node:assert/strict";
import test from "node:test";
import {
  classifyCurlExit,
  classifyError,
  COMPLETION_TIMEOUT_MAX_MS,
  COMPLETION_TIMEOUT_MIN_MS,
  normalizeBaseUrl,
  parseCurlTiming,
  PROBE_TARGETS,
  QWEN_TOKEN_PLAN_CN_BASE,
  redactSecrets,
  requireCompletionTimeoutMs,
  resolveProbeTarget,
} from "./provider_probe.mjs";

test("credentialed authorities come only from the frozen reviewed allowlist", () => {
  assert.equal(resolveProbeTarget("direct-qwen"), QWEN_TOKEN_PLAN_CN_BASE);
  // Every allowlist value must itself survive URL hardening.
  for (const [name, value] of Object.entries(PROBE_TARGETS)) {
    assert.equal(resolveProbeTarget(name), normalizeBaseUrl(value));
  }
  // Arbitrary hosts, URLs, and unknown names are not dispatchable.
  for (const bad of ["https://evil.example/v1", "cf-proxy", "", "direct-qwen ", "DIRECT-QWEN"]) {
    if (bad === "direct-qwen ") {
      // trimmed name is allowed
      assert.equal(resolveProbeTarget(bad), QWEN_TOKEN_PLAN_CN_BASE);
      continue;
    }
    assert.throws(() => resolveProbeTarget(bad), /unknown probe target/, JSON.stringify(bad));
  }
  assert.ok(Object.isFrozen(PROBE_TARGETS));
});

test("base URL normalization accepts https provider hosts and strips trailing slashes", () => {
  assert.equal(normalizeBaseUrl("https://example.com/v1/"), "https://example.com/v1");
  assert.equal(normalizeBaseUrl(QWEN_TOKEN_PLAN_CN_BASE), QWEN_TOKEN_PLAN_CN_BASE);
  assert.equal(normalizeBaseUrl("https://proxy.example.workers.dev/compatible-mode/v1"), "https://proxy.example.workers.dev/compatible-mode/v1");
});

test("base URL validation fails closed on scheme, credentials, query, hash, and path", () => {
  assert.throws(() => normalizeBaseUrl("http://insecure.example.com"), /https/);
  assert.throws(() => normalizeBaseUrl(""), /valid https/);
  assert.throws(() => normalizeBaseUrl("https://a.com https://b.com"), /valid https|https/);
  assert.throws(() => normalizeBaseUrl("https://user:pw@example.com/v1"), /credentials/);
  assert.throws(() => normalizeBaseUrl("https://example.com/v1?x=1"), /query or fragment/);
  assert.throws(() => normalizeBaseUrl("https://example.com/v1#frag"), /query or fragment/);
  assert.throws(() => normalizeBaseUrl("https://example.com/v1/%00bad"), /unsupported characters/);
  // The URL parser canonicalizes dot segments before the path check runs, so
  // `..` can never reach the origin string.
  assert.equal(normalizeBaseUrl("https://example.com/a/../b"), "https://example.com/b");
});

test("base URL validation rejects localhost and every IP-literal host class", () => {
  for (const bad of [
    "https://localhost/v1",
    "https://api.localhost/v1",
    "https://127.0.0.1/v1",
    "https://10.0.0.8/v1",
    "https://172.16.3.4/v1",
    "https://192.168.1.1/v1",
    "https://169.254.169.254/v1",
    "https://0.0.0.0/v1",
    "https://8.8.8.8/v1",
    "https://[::1]/v1",
    "https://[fe80::1]/v1",
    "https://[fd00::2]/v1",
  ]) {
    assert.throws(() => normalizeBaseUrl(bad), /localhost|IP literal/, bad);
  }
  // DNS terminal dot must not bypass the name checks, and single-label
  // internal names are rejected outright.
  assert.throws(() => normalizeBaseUrl("https://localhost./v1"), /localhost/);
  assert.throws(() => normalizeBaseUrl("https://api.localhost./v1"), /localhost/);
  assert.throws(() => normalizeBaseUrl("https://intranet/v1"), /single-label/);
  assert.throws(() => normalizeBaseUrl("https://intranet./v1"), /single-label/);
  assert.equal(normalizeBaseUrl("https://example.com./v1"), "https://example.com/v1");
});

test("secret redaction removes keys of any non-empty length and bearer tokens", () => {
  const key = "sk-verysecretvalue123456";
  const text = `failed: Bearer ${key} rejected; raw=${key}`;
  const clean = redactSecrets(text, [key]);
  assert.ok(!clean.includes(key));
  assert.match(clean, /<redacted>/);
  // Short keys are still keys.
  const short = redactSecrets("token=abc used", ["abc"]);
  assert.ok(!/token=abc\b/.test(short));
  assert.match(short, /<redacted>/);
  // Empty secrets must not corrupt unrelated text.
  assert.equal(redactSecrets("plain text", [""]), "plain text");
});

test("completion timeout requires a bounded finite integer before any network use", () => {
  assert.equal(requireCompletionTimeoutMs("90000"), 90000);
  assert.equal(requireCompletionTimeoutMs(COMPLETION_TIMEOUT_MIN_MS), COMPLETION_TIMEOUT_MIN_MS);
  assert.equal(requireCompletionTimeoutMs(COMPLETION_TIMEOUT_MAX_MS), COMPLETION_TIMEOUT_MAX_MS);
  for (const bad of ["abc", "-1", "0", "999", "300001", "90000.5", "Infinity", "NaN", "1e9"]) {
    assert.throws(() => requireCompletionTimeoutMs(bad), /completion timeout must be an integer/, bad);
  }
});

test("fetch-layer error classification splits timeout, dns, connect, tls, and redirect", () => {
  assert.equal(classifyError(Object.assign(new Error("x"), { name: "AbortError" })), "timeout");
  assert.equal(classifyError(new Error("Request timed out.")), "timeout");
  assert.equal(classifyError(new Error("getaddrinfo ENOTFOUND host")), "dns");
  assert.equal(classifyError(new Error("connect ECONNREFUSED 1.2.3.4")), "connect");
  assert.equal(classifyError(new Error("unable to verify certificate")), "tls");
  assert.equal(classifyError(new Error("unexpected redirect")), "redirect");
  assert.equal(classifyError(new Error("weird")), "other");
});

test("curl exit codes map to distinct layers instead of collapsing to connect", () => {
  assert.equal(classifyCurlExit(6), "dns");
  assert.equal(classifyCurlExit(5), "dns");
  assert.equal(classifyCurlExit(7), "connect");
  assert.equal(classifyCurlExit(28), "timeout");
  for (const tls of [35, 51, 53, 54, 58, 59, 60, 77, 83]) {
    assert.equal(classifyCurlExit(tls), "tls", `curl exit ${tls}`);
  }
  assert.equal(classifyCurlExit(22), "other");
  assert.equal(classifyCurlExit(1), "other");
});

test("curl timing line parses into numeric fields", () => {
  const timing = parseCurlTiming("dns=0.004 tcp=0.119 tls=0.249 ttfb=0.370 total=0.371 http=401");
  assert.equal(timing.http, 401);
  assert.equal(timing.ttfb, 0.37);
  assert.equal(timing.dns, 0.004);
});
