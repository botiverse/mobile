#!/usr/bin/env node
// Probe: what servers can the QA account actually SEE?
//
// Every guard shipped so far (#2282 iOS goal, #2283 Android goal, #2287 the preamble
// chokepoint, #2290 the alternative-target count, #2291 the deterministic path) constrains
// TEXT — what the goal is allowed to say, and what a script is allowed to match. None of them
// can observe what the server actually offers the account. The only thing standing between an
// acceptance run and the production server is that the QA account currently cannot see it, and
// the most recent evidence for that is a single UI dump from 2026-08-30 07:13.
//
// This turns that one-off snapshot into a per-run assertion.
//
// It is layer 2 of three, and the boundary matters:
//   layer 1  the goal text cannot name/offer production          (shipped)
//   layer 2  the account's visible list is observed every run    (this file)
//   layer 3  server-side ACL makes selection IMPOSSIBLE          (OPEN with @artin)
// Layer 2 DETECTS; it does not PREVENT. If the account is re-invited to production this probe
// goes red — after the invitation, not instead of it.
//
// Endpoints are read from the app's own client, not guessed:
//   POST {base}auth/login   SharedSlockNetworkClient.kt:590
//   GET  {base}servers      SharedSlockNetworkClient.kt:687   (Authorization: Bearer <token>)
//   base                    SlockApiEndpoints.kt:3  https://api.raft.build/api/
//   element shape           StoredAuthServer — { id, name, slug?, ... }  AuthSessionStore.kt:546
// Found by @LiBai after my own search missed it: I grepped `api/servers`, but the path literal
// is "servers" — the `api/` lives in the base URL. My control (4 files matched `api/servers`)
// proved the pattern matched SOMETHING, not that it could match the thing I was looking for.

export const PRODUCTION_SERVER_NAME = "Raft Android";
export const REQUIRED_QA_SERVER = "OranixSlockTest";

// Pure. No network, no environment. This is the whole judgement, so it is the whole test
// surface too — the fetching below is deliberately kept dumb enough to need no assertions.
//
// `identity` is required, not decorative. @HuaTuo found (2026-08-31, live) that the QA account
// pool's `label=alpha-qa` is an LRU selector: consecutive requests hand back DIFFERENT accounts,
// and they had already used three separately-issued tokens as if they were one identity. So a
// verdict like "2 servers visible, no production" is unattributable on its own — it does not say
// WHOSE visibility produced it. The identity has to come from the login response body
// (user.id/email), never from the username we asked with: what we sent is what we wanted, what
// came back is what we got.
export function judgeVisibleServers(servers, identity) {
  if (!identity || !identity.id) {
    return { ok: false, code: "unattributable", detail: "no authenticated identity — the reading cannot be bound to an account" };
  }
  if (!Array.isArray(servers)) {
    return { ok: false, code: "not_a_list", detail: `expected a list, got ${typeof servers}` };
  }
  const names = servers.map((s) => (s && typeof s.name === "string" ? s.name : "")).filter(Boolean);

  // Production first, and the order is load-bearing (@LiBai, reviewing this file). It is an
  // UNCONDITIONAL violation: it does not matter whether the rest of the list is the one we
  // expected. Checking readability first mislabels the worst case as the mildest — a single
  // membership change that adds the account to production and drops it from OranixSlockTest
  // yields ["Raft Android", "qa-server-b-slock"], which the readability branch reports as
  // `expected_server_missing`: a complaint about list shape that never mentions that
  // production is visible. Still fail-closed, but whoever reads the receipt sees a shape
  // grumble instead of the alarm this file exists to raise.
  //
  // Containment, not equality: the picker renders rows as "<name>, /<slug>" and the API's
  // `name` is bare, so containment keeps this agreeing with how ios_deterministic_repro.mjs:144
  // selects a row. Unambiguous here — unlike `slock` in #2287, which matches all three servers,
  // `OranixSlockTest` and `Raft Android` are not substrings of one another. Same matching mode,
  // safe here and unsafe there; the difference is in the literals, not the method.
  const production = names.filter((n) => n.toLowerCase().includes(PRODUCTION_SERVER_NAME.toLowerCase()));
  if (production.length > 0) {
    return {
      ok: false,
      code: "production_visible",
      detail: `QA account ${identity.email || identity.id} can see the production server: [${production.join(", ")}] — out of bounds (artin, 2026-08-31)`,
    };
  }

  // Only now: is the absence above worth anything? BOTH halves are required, and this is the
  // one that is easy to omit. With the production check alone, anything that returns nothing —
  // expired token, wrong base URL, an outage, a shape change — yields an empty list, finds no
  // production server, and reports success. "I could not read the list" would be
  // byte-identical to "production is not visible".
  if (names.length === 0) {
    return { ok: false, code: "empty_list", detail: "no servers returned — cannot conclude anything about production visibility" };
  }
  if (!names.some((n) => n.toLowerCase().includes(REQUIRED_QA_SERVER.toLowerCase()))) {
    return {
      ok: false,
      code: "expected_server_missing",
      detail: `${REQUIRED_QA_SERVER} not among [${names.join(", ")}] — the list is not the one we think we are reading`,
    };
  }
  return {
    ok: true,
    code: "clean",
    detail: `${identity.email || identity.id} sees ${names.length}: ${names.join(", ")}`,
  };
}

// rc=2 alone degrades in practice (@HuaTuo, adopted by @Argus): if every unjudgeable run
// looks the same, "fix or rerun" quietly becomes "always rerun", and the most dangerous class
// — the endpoint changed or the probe itself broke — is the one where a rerun most easily
// goes green by accident. So an unjudgeable result must say WHICH KIND it is.
//
//   credential  401/403, or no credentials at all  -> rerunning cannot help
//   ratelimit   429                                -> rerunning is right, but NOT immediately
//   infra       timeout, DNS, transport failure    -> rerunning is reasonable
//   contract    404, non-JSON, missing accessToken -> MOST DANGEROUS: a passing rerun does
//               NOT mean fixed; someone has to check the probe against the live endpoint
//
// 429 is its own class rather than infra (@LiBai). Both say "try again", but infra means
// "try again" and 429 means "wait, then try again" — an immediate rerun makes 429 worse, and
// @HuaTuo hit it live by re-issuing logins in quick succession. That half-sentence decides
// whether whoever is on call reruns now or stops for a minute, so it cannot be folded in.
//
// All four still block. The reason only tells the reader what to do next.
export const UNJUDGEABLE_REASONS = ["credential", "ratelimit", "infra", "contract"];

function unjudgeable(reason, detail) {
  console.error(`visible-servers probe: UNJUDGEABLE [${reason}] ${detail}`);
  console.error(`reason=${reason}`);
  if (reason === "ratelimit") {
    console.error("visible-servers probe: do NOT rerun immediately — that is what caused this");
  }
  if (reason === "contract") {
    console.error("visible-servers probe: a passing rerun does NOT mean this is fixed — check the probe against the live endpoint");
  }
  process.exit(2); // 2 = could not judge, distinct from 1 = judged and failed
}

async function main() {
  const base = (process.env.QA_API_BASE || "https://api.raft.build/api/").replace(/\/?$/, "/");
  const email = process.env.QA_TEST_EMAIL;
  const password = process.env.QA_TEST_PASSWORD;
  if (!email || !password) unjudgeable("credential", "QA_TEST_EMAIL / QA_TEST_PASSWORD not set");

  let login;
  try {
    login = await fetch(`${base}auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password }),
    });
  } catch (err) {
    unjudgeable("infra", `login request did not complete: ${err?.message ?? err}`);
  }
  if (login.status === 401 || login.status === 403) unjudgeable("credential", `login rejected HTTP ${login.status}`);
  if (login.status === 404) unjudgeable("contract", "login endpoint returned 404 — the API shape may have moved");
  if (login.status === 429) unjudgeable("ratelimit", "login rate-limited HTTP 429 — wait before retrying; log in once per run and reuse the token");
  if (!login.ok) unjudgeable("infra", `login failed HTTP ${login.status}`);

  let session;
  try {
    session = await login.json();
  } catch {
    unjudgeable("contract", "login response was not JSON");
  }
  const token = session?.accessToken;
  if (!token) unjudgeable("contract", "no accessToken in the login response — the contract changed");
  // Identity from the RESPONSE, never from the email we sent. See judgeVisibleServers.
  const identity = session?.user ? { id: session.user.id, email: session.user.email, name: session.user.name } : null;

  // GET servers is the one call that needs no X-Server-Id header (@HuaTuo, live 2026-08-31);
  // sub-resources like /channels do, and answer 400 Missing X-Server-Id without it.
  let res;
  try {
    res = await fetch(`${base}servers`, { headers: { Authorization: `Bearer ${token}` } });
  } catch (err) {
    unjudgeable("infra", `servers request did not complete: ${err?.message ?? err}`);
  }
  if (res.status === 401 || res.status === 403) unjudgeable("credential", `servers rejected HTTP ${res.status}`);
  if (res.status === 404) unjudgeable("contract", "GET servers returned 404 — the endpoint may have moved");
  if (res.status === 429) unjudgeable("ratelimit", "servers rate-limited HTTP 429 — wait before retrying");
  if (!res.ok) unjudgeable("infra", `GET servers failed HTTP ${res.status}`);

  let body;
  try {
    body = await res.json();
  } catch {
    unjudgeable("contract", "servers response was not JSON");
  }
  const list = Array.isArray(body) ? body : body?.servers;

  const verdict = judgeVisibleServers(list, identity);
  // A shape the judgement cannot read is a contract problem, not a product finding: it must not
  // be reported as "the account's visibility is wrong".
  if (verdict.code === "not_a_list") unjudgeable("contract", verdict.detail);
  // Names only. Never print ids, tokens, or the raw body — this runs in CI logs.
  console.log(`visible-servers probe: ${verdict.ok ? "PASS" : "FAIL"} [${verdict.code}] ${verdict.detail}`);
  process.exit(verdict.ok ? 0 : 1);
}


if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((err) => {
    console.error(`visible-servers probe: threw ${err?.message ?? err}`);
    process.exit(2);
  });
}
