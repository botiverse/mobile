import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  StampModelCapacityClient,
  classifyModelError,
  parseRetryAfterMs,
  retryDelayMs,
  runWithModelRetries,
} from "./model_capacity.mjs";

test("429/2062 is concurrency_or_rpm while explicit exhaustion stays distinct", () => {
  const limited = classifyModelError({
    status: 429,
    message: "MiniMax-M3.1-Flash-Preview Token Plan rate limit reached (2062)",
    headers: { "retry-after": "7", "x-request-id": "req-rate" },
  });
  assert.equal(limited.classification, "concurrency_or_rpm");
  assert.equal(limited.retryAfterMs, 7_000);
  assert.equal(limited.requestId, "req-rate");
  assert.equal(retryDelayMs(2, limited, { jitter: () => 0 }), 7_000);
  assert.equal(retryDelayMs(1, { retryAfterMs: 120_000 }, { maxMs: 60_000 }), 120_000);

  const exhausted = classifyModelError(new Error("account quota exhausted / insufficient balance"));
  assert.equal(exhausted.classification, "quota_exhaustion");
  assert.equal(exhausted.retryAfterMs, null);
});

test("Retry-After supports delta seconds and HTTP dates", () => {
  assert.equal(parseRetryAfterMs("1.5", 1_000), 1_500);
  assert.equal(parseRetryAfterMs("Thu, 01 Jan 1970 00:00:03 GMT", 1_000), 2_000);
  assert.equal(parseRetryAfterMs("garbage", 1_000), null);
  assert.equal(retryDelayMs(1, { retryAfterMs: null }, { baseMs: 4_000, jitter: () => 0 }), 3_000);
  assert.equal(retryDelayMs(2, { retryAfterMs: null }, { baseMs: 4_000, jitter: () => 1 }), 10_000);
});

test("model retry releases capacity before Retry-After backoff and preserves request facts", async () => {
  const order = [];
  let calls = 0;
  const errors = [];
  const capacity = {
    acquire: async () => { order.push("acquire"); return { lease_id: `lease-${calls + 1}` }; },
    release: async () => { order.push("release"); return true; },
  };
  const result = await runWithModelRetries({
    capacity, maxAttempts: 3,
    invoke: async () => {
      calls++;
      order.push(`invoke-${calls}`);
      if (calls === 1) {
        const error = new Error("429 rate limit reached (2062)");
        error.status = 429;
        error.headers = { "retry-after": "4", "x-request-id": "req-429" };
        throw error;
      }
      return "ok";
    },
    onError: async (facts) => { order.push("on-error"); errors.push(facts); },
    sleepImpl: async (ms) => { order.push(`sleep-${ms}`); },
  });
  assert.equal(result, "ok");
  assert.deepEqual(order, ["acquire", "invoke-1", "release", "on-error", "sleep-4000", "acquire", "invoke-2", "release"]);
  assert.equal(errors[0].classification, "concurrency_or_rpm");
  assert.equal(errors[0].requestId, "req-429");
  assert.equal(errors[0].retryable, true);
});

test("Retry-After beyond the remaining retry budget fails without an early request", async () => {
  let calls = 0;
  const errors = [];
  await assert.rejects(() => runWithModelRetries({
    maxAttempts: 3, retryBudgetMs: 60_000,
    invoke: async () => {
      calls++;
      const error = new Error("429 rate limit; retry-after=120");
      error.status = 429;
      error.headers = { "retry-after": "120" };
      throw error;
    },
    onError: async (facts) => errors.push(facts),
    sleepImpl: async () => assert.fail("budget overflow must not sleep/retry early"),
  }), /429 rate limit/);
  assert.equal(calls, 1);
  assert.equal(errors[0].retryBudgetExceeded, true);
  assert.equal(errors[0].retryable, false);
  assert.equal(errors[0].retryAfterMs, 120_000);
});

test("unconfirmed capacity release fails closed before backoff or another model call", async () => {
  const order = [];
  let calls = 0;
  await assert.rejects(() => runWithModelRetries({
    capacity: {
      acquire: async () => ({ lease_id: "held" }),
      release: async () => { order.push("release-failed"); return false; },
    },
    maxAttempts: 3,
    invoke: async () => { calls++; throw new Error("429 rate limit"); },
    onError: async (facts) => order.push(facts.classification),
    sleepImpl: async () => order.push("sleep"),
  }), /release could not be confirmed/);
  assert.equal(calls, 1);
  assert.deepEqual(order, ["release-failed", "capacity_release_failure"]);
});

test("explicit quota exhaustion consumes no retry budget", async () => {
  let calls = 0;
  const facts = [];
  await assert.rejects(() => runWithModelRetries({
    maxAttempts: 3,
    invoke: async () => { calls++; throw new Error("insufficient balance: quota exhausted"); },
    onError: async (value) => facts.push(value),
    sleepImpl: async () => assert.fail("quota exhaustion must not sleep/retry"),
  }), /quota exhausted/);
  assert.equal(calls, 1);
  assert.equal(facts[0].classification, "quota_exhaustion");
  assert.equal(facts[0].retryable, false);
});

test("client records queued/active facts, waits, acquires, and releases", async () => {
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-model-capacity-"));
  const calls = [];
  const sleeps = [];
  const responses = [
    new Response(JSON.stringify({
      status: "queued", active: 3, queued: 5, limit: 3,
      queue_position: 2, retry_after_ms: 1_250,
    }), { status: 202, headers: { "content-type": "application/json", "retry-after": "2" } }),
    new Response(JSON.stringify({
      status: "acquired", lease_id: "lease-1", slot: 2,
      active: 3, queued: 4, limit: 3, retry_after_ms: 1_250,
    }), { status: 200, headers: { "content-type": "application/json" } }),
    new Response(JSON.stringify({ ok: true, released: true, active: 2, queued: 4, limit: 3 }), {
      status: 200, headers: { "content-type": "application/json" },
    }),
  ];
  const client = new StampModelCapacityClient({
    stampUrl: "https://stamp.example", stampToken: "deploy-token", runCaseId: "rc-1",
    claimToken: "claim-token", ciJobRef: "https://github.com/botiverse/mobile/actions/runs/1",
    provider: "minimax", model: "MiniMax-M3.1-Flash-Preview", outDir,
    fetchImpl: async (url, init) => { calls.push({ url, init }); return responses.shift(); },
    sleepImpl: async (ms) => { sleeps.push(ms); }, queueTimeoutMs: 60_000,
  });
  const lease = await client.acquire();
  assert.equal(lease.lease_id, "lease-1");
  assert.deepEqual(sleeps, [1_250]);
  await client.release(lease);
  assert.equal(calls.length, 3);
  assert.match(calls[0].url, /model-capacity\/acquire$/);
  assert.equal(calls[0].init.headers["x-stamp-claim-token"], "claim-token");
  assert.match(calls[2].url, /model-capacity\/release$/);

  const events = fs.readFileSync(path.join(outDir, "model-capacity.jsonl"), "utf8").trim().split("\n").map(JSON.parse);
  assert.deepEqual(events.map((event) => event.event), ["queued", "acquired", "released"]);
  assert.equal(events[0].active, 3);
  assert.equal(events[0].queued, 5);
  assert.equal(events[0].queue_position, 2);
});

test("queue timeout cancels its durable waiter", async () => {
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-model-capacity-timeout-"));
  const calls = [];
  const client = new StampModelCapacityClient({
    stampUrl: "https://stamp.example", stampToken: "deploy-token", runCaseId: "rc-timeout",
    claimToken: "claim-token", ciJobRef: "https://github.com/botiverse/mobile/actions/runs/2",
    provider: "minimax", model: "MiniMax-M3.1-Flash-Preview", outDir, queueTimeoutMs: -1,
    fetchImpl: async (url) => {
      calls.push(url);
      return new Response(JSON.stringify({ ok: true, cancelled: true, active: 3, queued: 0, limit: 3 }), {
        status: 200, headers: { "content-type": "application/json" },
      });
    },
  });
  await assert.rejects(() => client.acquire(), /queue timeout/);
  assert.equal(calls.length, 1);
  assert.match(calls[0], /model-capacity\/cancel$/);
  const events = fs.readFileSync(path.join(outDir, "model-capacity.jsonl"), "utf8").trim().split("\n").map(JSON.parse);
  assert.deepEqual(events.map((event) => event.event), ["queue_timeout", "queue_cancelled"]);
});

test("release retries response loss idempotently and confirms before returning", async () => {
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-model-capacity-release-"));
  const sleeps = [];
  let call = 0;
  const client = new StampModelCapacityClient({
    stampUrl: "https://stamp.example", stampToken: "deploy-token", runCaseId: "rc-release",
    claimToken: "claim-token", ciJobRef: "https://github.com/botiverse/mobile/actions/runs/3",
    provider: "minimax", model: "MiniMax-M3.1-Flash-Preview", outDir,
    fetchImpl: async () => {
      call++;
      if (call === 1) return new Response("{}", { status: 500, headers: { "content-type": "application/json" } });
      if (call === 2) throw new Error("response lost");
      return new Response(JSON.stringify({ ok: true, idempotent: true, active: 2, queued: 1, limit: 3 }), {
        status: 200, headers: { "content-type": "application/json" },
      });
    },
    sleepImpl: async (ms) => sleeps.push(ms), releaseAttempts: 3, releaseRetryBaseMs: 1_000,
  });
  assert.equal(await client.release({ lease_id: "lease-release" }), true);
  assert.deepEqual(sleeps, [1_000, 2_000]);
  const events = fs.readFileSync(path.join(outDir, "model-capacity.jsonl"), "utf8").trim().split("\n").map(JSON.parse);
  assert.deepEqual(events.map((event) => event.event), ["release_failed", "release_failed", "released"]);
  assert.equal(events[2].idempotent, true);
});

test("independent diagnosis reuses shared capacity client without executor claim authority", async () => {
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), "stamp-diagnosis-capacity-"));
  const calls = [];
  const responses = [
    new Response(JSON.stringify({ status: "acquired", lease_id: "review-lease", slot: 3, active: 1, queued: 0, limit: 3 }), { status: 200, headers: { "content-type": "application/json" } }),
    new Response(JSON.stringify({ ok: true, released: true, active: 0, queued: 0, limit: 3 }), { status: 200, headers: { "content-type": "application/json" } }),
  ];
  const client = new StampModelCapacityClient({
    stampUrl: "https://stamp.example", stampToken: "review-token", runCaseId: "rc-review",
    claimToken: "", ciJobRef: "https://github.com/botiverse/mobile/actions/runs/4",
    provider: "minimax", model: "MiniMax-M3.1-Flash-Preview", outDir,
    capacityPath: "/api/diagnoses/rc-review/model-capacity",
    fetchImpl: async (url, init) => { calls.push({ url, init }); return responses.shift(); },
  });
  const lease = await client.acquire();
  assert.equal(await client.release(lease), true);
  assert.equal(calls[0].init.headers["x-stamp-claim-token"], undefined);
  assert.match(calls[0].url, /api\/diagnoses\/rc-review\/model-capacity\/acquire$/);
  assert.match(calls[1].url, /api\/diagnoses\/rc-review\/model-capacity\/release$/);
});
