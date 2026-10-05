import fs from "node:fs";
import path from "node:path";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function headerValue(headers, name) {
  if (!headers) return null;
  if (typeof headers.get === "function") return headers.get(name);
  const entry = Object.entries(headers).find(([key]) => key.toLowerCase() === name.toLowerCase());
  return entry ? String(entry[1]) : null;
}

export function parseRetryAfterMs(value, at = Date.now()) {
  if (value == null || value === "") return null;
  if (/^\d+(?:\.\d+)?$/.test(String(value).trim())) return Math.max(0, Math.ceil(Number(value) * 1000));
  const date = Date.parse(String(value));
  return Number.isFinite(date) ? Math.max(0, date - at) : null;
}

export function classifyModelError(error, at = Date.now()) {
  const source = error?.modelResponse ?? error ?? {};
  const message = String(source.errorMessage ?? error?.message ?? error ?? "model request failed");
  const status = Number(source.status ?? source.statusCode ?? error?.status ?? error?.statusCode ?? (/\b429\b/.test(message) ? 429 : 0)) || null;
  const headers = source.headers ?? error?.headers;
  const retryAfterMs = parseRetryAfterMs(
    headerValue(headers, "retry-after") ?? source.retryAfter ?? error?.retryAfter ?? /retry[- ]after[:= ]+(\d+(?:\.\d+)?)/i.exec(message)?.[1],
    at,
  );
  const requestId = String(
    headerValue(headers, "x-request-id") ?? headerValue(headers, "request-id") ??
    source.requestId ?? source.request_id ?? error?.requestId ??
    /request[_ -]?id[:= ]+([A-Za-z0-9._:-]+)/i.exec(message)?.[1] ?? "",
  ) || null;
  const quota = /(?:quota|credit|balance|insufficient|plan (?:is )?exhaust|quota exceeded|out of tokens)/i.test(message);
  const rate = status === 429 || /(?:rate.?limit|too many requests|\brpm\b|\btpm\b|concurr|parallel session|\b2062\b)/i.test(message);
  return {
    classification: quota ? "quota_exhaustion" : rate ? "concurrency_or_rpm" : "provider_error",
    status,
    retryAfterMs,
    requestId,
    message,
  };
}

export function retryDelayMs(attempt, facts, { baseMs = 5_000, maxMs = 60_000, jitter = Math.random } = {}) {
  if (facts.retryAfterMs != null) return Math.max(0, facts.retryAfterMs);
  const exponential = Math.min(maxMs, baseMs * (2 ** Math.max(0, attempt - 1)));
  return Math.min(maxMs, Math.round(exponential * (0.75 + Math.max(0, Math.min(1, jitter())) * 0.5)));
}

export async function runWithModelRetries({
  invoke,
  capacity = null,
  maxAttempts = 3,
  onError = () => {},
  sleepImpl = sleep,
  retryOptions,
  retryBudgetMs = 300_000,
}) {
  const attempts = Math.max(1, Math.min(6, Number(maxAttempts) || 3));
  const budget = Math.max(0, Number(retryBudgetMs) || 0);
  let backoffSpent = 0;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    let lease = null;
    let failure = null;
    let result;
    try {
      lease = await capacity?.acquire();
      result = await invoke(attempt);
    } catch (error) {
      failure = { error, facts: classifyModelError(error) };
    }
    const releaseConfirmed = !lease || !capacity ? true : await capacity.release(lease);
    if (!releaseConfirmed) {
      const releaseError = new Error("Stamp model capacity release could not be confirmed");
      releaseError.code = "MODEL_CAPACITY_RELEASE_UNCONFIRMED";
      await onError({
        attempt, maxAttempts: attempts, retryable: false, retryDelayMs: 0,
        retryBudgetMs: budget, retryBudgetRemainingMs: Math.max(0, budget - backoffSpent), retryBudgetExceeded: false,
        classification: "capacity_release_failure", status: null, retryAfterMs: null,
        requestId: null, message: releaseError.message,
      });
      throw releaseError;
    }
    if (!failure) return result;
    const proposedDelay = retryDelayMs(attempt, failure.facts, retryOptions);
    const remaining = Math.max(0, budget - backoffSpent);
    const budgetExceeded = proposedDelay > remaining;
    const retryable = failure.facts.classification !== "quota_exhaustion" && attempt < attempts && !budgetExceeded;
    const retryDelay = retryable ? proposedDelay : 0;
    await onError({
      attempt, maxAttempts: attempts, retryable, retryDelayMs: retryDelay,
      retryBudgetMs: budget, retryBudgetRemainingMs: remaining, retryBudgetExceeded: budgetExceeded,
      ...failure.facts,
    });
    if (!retryable) throw failure.error;
    await sleepImpl(retryDelay);
    backoffSpent += retryDelay;
  }
  throw new Error("model retry loop exhausted");
}

export class StampModelCapacityClient {
  constructor({ stampUrl, stampToken, runCaseId, claimToken, ciJobRef, provider, model, outDir, onEvent, capacityPath, fetchImpl = fetch, sleepImpl = sleep, queueTimeoutMs = 900_000, releaseAttempts = 3, releaseRetryBaseMs = 1_000 }) {
    this.base = `${String(stampUrl).replace(/\/$/, "")}${capacityPath || `/api/agent/run-cases/${runCaseId}/model-capacity`}`;
    this.stampToken = stampToken;
    this.claimToken = claimToken;
    this.ciJobRef = ciJobRef;
    this.provider = provider;
    this.model = model;
    this.outDir = outDir;
    this.onEvent = onEvent;
    this.fetchImpl = fetchImpl;
    this.sleepImpl = sleepImpl;
    this.queueTimeoutMs = queueTimeoutMs;
    this.releaseAttempts = Math.max(1, Math.min(5, Number(releaseAttempts) || 3));
    this.releaseRetryBaseMs = Math.max(100, Number(releaseRetryBaseMs) || 1_000);
  }

  event(event, detail = {}) {
    const record = { ts: Date.now(), event, provider: this.provider, model: this.model, ...detail };
    try {
      fs.mkdirSync(this.outDir, { recursive: true });
      fs.appendFileSync(path.join(this.outDir, "model-capacity.jsonl"), `${JSON.stringify(record)}\n`);
    } catch {}
    this.onEvent?.(record);
  }

  headers() {
    return {
      authorization: `Bearer ${this.stampToken}`,
      "content-type": "application/json",
      "x-stamp-ci-job-ref": this.ciJobRef,
      ...(this.claimToken ? { "x-stamp-claim-token": this.claimToken } : {}),
    };
  }

  async acquire() {
    const started = Date.now();
    let lastQueue = "";
    while (Date.now() - started <= this.queueTimeoutMs) {
      const response = await this.fetchImpl(`${this.base}/acquire`, {
        method: "POST", headers: this.headers(),
        body: JSON.stringify({ provider: this.provider, model: this.model }),
      });
      const payload = await response.json().catch(() => ({}));
      if (response.status === 200 && payload.status === "acquired" && payload.lease_id) {
        this.event("acquired", {
          lease_id: payload.lease_id, slot: payload.slot, active: payload.active,
          queued: payload.queued, limit: payload.limit, account_key: payload.account_key,
          waited_ms: Date.now() - started,
        });
        return payload;
      }
      if (response.status !== 202 || payload.status !== "queued") {
        throw new Error(`Stamp model capacity acquire failed: http ${response.status} ${String(payload.code ?? payload.error ?? "")}`.trim());
      }
      const queueKey = `${payload.active}/${payload.queued}/${payload.queue_position}`;
      if (queueKey !== lastQueue) {
        this.event("queued", {
          active: payload.active, queued: payload.queued, limit: payload.limit,
          queue_position: payload.queue_position, account_key: payload.account_key,
        });
        lastQueue = queueKey;
      }
      const headerDelay = parseRetryAfterMs(response.headers?.get?.("retry-after"));
      const delay = Math.max(250, Number(payload.retry_after_ms ?? headerDelay ?? 5_000));
      await this.sleepImpl(delay);
    }
    this.event("queue_timeout", { waited_ms: Date.now() - started });
    await this.cancel();
    throw new Error(`Stamp model capacity queue timeout after ${this.queueTimeoutMs}ms`);
  }

  async release(lease) {
    if (!lease?.lease_id) return true;
    for (let attempt = 1; attempt <= this.releaseAttempts; attempt++) {
      try {
        const response = await this.fetchImpl(`${this.base}/release`, {
          method: "POST", headers: this.headers(), body: JSON.stringify({ lease_id: lease.lease_id }),
        });
        const payload = await response.json().catch(() => ({}));
        if (response.ok) {
          this.event("released", {
            lease_id: lease.lease_id, attempt, status: response.status,
            active: payload.active, queued: payload.queued, limit: payload.limit, account_key: payload.account_key,
            idempotent: payload.idempotent === true,
          });
          return true;
        }
        this.event("release_failed", { lease_id: lease.lease_id, attempt, status: response.status });
      } catch (error) {
        this.event("release_failed", { lease_id: lease.lease_id, attempt, error: String(error?.message ?? error).slice(0, 200) });
      }
      if (attempt < this.releaseAttempts) await this.sleepImpl(this.releaseRetryBaseMs * attempt);
    }
    this.event("release_terminal_failure", { lease_id: lease.lease_id, attempts: this.releaseAttempts });
    return false;
  }

  async cancel() {
    try {
      const response = await this.fetchImpl(`${this.base}/cancel`, {
        method: "POST", headers: this.headers(), body: "{}",
      });
      const payload = await response.json().catch(() => ({}));
      this.event(response.ok ? "queue_cancelled" : "queue_cancel_failed", {
        status: response.status, active: payload.active, queued: payload.queued,
        limit: payload.limit, account_key: payload.account_key,
      });
    } catch (error) {
      this.event("queue_cancel_failed", { error: String(error?.message ?? error).slice(0, 200) });
    }
  }
}

export function capacityClientFromEnv({ outDir, onEvent } = {}) {
  const runCaseId = process.env.STAMP_RUN_CASE_ID || "";
  const stampUrl = process.env.STAMP_URL || "";
  const stampToken = process.env.STAMP_TOKEN || "";
  if (!runCaseId && !stampUrl && !stampToken) return null;
  if (!runCaseId || !stampUrl || !stampToken) throw new Error("partial Stamp model capacity configuration");
  const claimFile = process.env.STAMP_CLAIM_TOKEN_FILE || path.join(process.env.RUNNER_TEMP || outDir || ".", `stamp-claim-${runCaseId}`);
  const claimToken = fs.readFileSync(claimFile, "utf8").trim();
  if (!claimToken) throw new Error("Stamp claim token missing before model capacity acquire");
  const ciJobRef = process.env.STAMP_CI_JOB_REF || `${process.env.GITHUB_SERVER_URL || "https://github.com"}/${process.env.GITHUB_REPOSITORY || ""}/actions/runs/${process.env.GITHUB_RUN_ID || ""}`;
  return new StampModelCapacityClient({
    stampUrl, stampToken, runCaseId, claimToken, ciJobRef,
    provider: process.env.PI_PROVIDER || "minimax",
    model: process.env.PI_MODEL || "MiniMax-M3.1-Flash-Preview",
    outDir: outDir || "qa-evidence", onEvent,
    queueTimeoutMs: Number(process.env.QA_MODEL_QUEUE_TIMEOUT_MS || "900000"),
    releaseAttempts: Number(process.env.QA_MODEL_RELEASE_ATTEMPTS || "3"),
  });
}
