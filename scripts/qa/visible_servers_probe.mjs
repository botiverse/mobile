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
export function judgeVisibleServers(servers) {
  if (!Array.isArray(servers)) {
    return { ok: false, code: "not_a_list", detail: `expected a list, got ${typeof servers}` };
  }
  const names = servers.map((s) => (s && typeof s.name === "string" ? s.name : "")).filter(Boolean);

  // BOTH halves are required, and the second is the one that is easy to omit.
  // With only the production check, a request that returned nothing — expired token, wrong
  // base URL, an outage, a shape change — yields an empty list, finds no production server,
  // and reports success. "I could not read the list" would be indistinguishable from
  // "production is not visible". The non-empty + known-member check is what makes the
  // absence result mean anything at all.
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
  // Containment, not equality: the picker renders rows as "<name>, /<slug>", and the API's
  // `name` is the bare name. Matching by containment in both directions keeps this agreeing
  // with how ios_deterministic_repro.mjs:144 selects a row.
  const production = names.filter((n) => n.toLowerCase().includes(PRODUCTION_SERVER_NAME.toLowerCase()));
  if (production.length > 0) {
    return {
      ok: false,
      code: "production_visible",
      detail: `QA account can see the production server: [${production.join(", ")}] — out of bounds (artin, 2026-08-31)`,
    };
  }
  return { ok: true, code: "clean", detail: `${names.length} visible: ${names.join(", ")}` };
}

async function main() {
  const base = (process.env.QA_API_BASE || "https://api.raft.build/api/").replace(/\/?$/, "/");
  const email = process.env.QA_TEST_EMAIL;
  const password = process.env.QA_TEST_PASSWORD;
  if (!email || !password) {
    console.error("visible-servers probe: QA_TEST_EMAIL / QA_TEST_PASSWORD not set");
    process.exit(2); // 2 = could not run, distinct from 1 = judged and failed
  }

  const login = await fetch(`${base}auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!login.ok) {
    console.error(`visible-servers probe: login failed HTTP ${login.status}`);
    process.exit(2);
  }
  const session = await login.json();
  const token = session?.accessToken ?? session?.access_token ?? session?.session?.accessToken;
  if (!token) {
    console.error("visible-servers probe: no access token in the login response");
    process.exit(2);
  }

  const res = await fetch(`${base}servers`, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) {
    console.error(`visible-servers probe: GET servers failed HTTP ${res.status}`);
    process.exit(2);
  }
  const body = await res.json();
  const list = Array.isArray(body) ? body : body?.servers;

  const verdict = judgeVisibleServers(list);
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
