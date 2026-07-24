import assert from "node:assert/strict";
import test from "node:test";
import {
  classifyError,
  normalizeBaseUrl,
  parseCurlTiming,
  QWEN_TOKEN_PLAN_CN_BASE,
  redactSecrets,
} from "./provider_probe.mjs";

test("base URL normalization accepts https and strips trailing slashes", () => {
  assert.equal(normalizeBaseUrl("https://example.com/v1/"), "https://example.com/v1");
  assert.equal(normalizeBaseUrl(QWEN_TOKEN_PLAN_CN_BASE), QWEN_TOKEN_PLAN_CN_BASE);
  assert.throws(() => normalizeBaseUrl("http://insecure.example.com"), /https/);
  assert.throws(() => normalizeBaseUrl("https://a.com https://b.com"), /single/);
  assert.throws(() => normalizeBaseUrl(""), /https/);
});

test("secret redaction removes the key and bearer tokens from any output", () => {
  const key = "sk-verysecretvalue123456";
  const text = `failed: Bearer ${key} rejected; raw=${key}`;
  const clean = redactSecrets(text, [key]);
  assert.ok(!clean.includes(key));
  assert.match(clean, /<redacted>/);
  // Short/empty secrets must not corrupt unrelated text.
  assert.equal(redactSecrets("plain text", ["", "abc"]), "plain text");
});

test("error classification splits timeout, dns, connect, and tls", () => {
  assert.equal(classifyError(Object.assign(new Error("x"), { name: "AbortError" })), "timeout");
  assert.equal(classifyError(new Error("Request timed out.")), "timeout");
  assert.equal(classifyError(new Error("getaddrinfo ENOTFOUND host")), "dns");
  assert.equal(classifyError(new Error("connect ECONNREFUSED 1.2.3.4")), "connect");
  assert.equal(classifyError(new Error("unable to verify certificate")), "tls");
  assert.equal(classifyError(new Error("weird")), "other");
});

test("curl timing line parses into numeric fields", () => {
  const timing = parseCurlTiming("dns=0.004 tcp=0.119 tls=0.249 ttfb=0.370 total=0.371 http=401");
  assert.equal(timing.http, 401);
  assert.equal(timing.ttfb, 0.37);
  assert.equal(timing.dns, 0.004);
});
