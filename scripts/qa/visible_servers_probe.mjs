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
//   POST {base}auth/login   SharedRaftNetworkClient.kt:590
//   GET  {base}servers      SharedRaftNetworkClient.kt:687   (Authorization: Bearer <token>)
//   base                    RaftApiEndpoints.kt:3  https://api.raft.build/api/
//   element shape           StoredAuthServer — { id, name, slug?, ... }  AuthSessionStore.kt:546
// Found by @LiBai after my own search missed it: I grepped `api/servers`, but the path literal
// is "servers" — the `api/` lives in the base URL. My control (4 files matched `api/servers`)
// proved the pattern matched SOMETHING, not that it could match the thing I was looking for.

import { pathToFileURL } from "node:url";
import { serverRef, userRef } from "./app_identity_receipt_lib.mjs";
import { readCredentialFile, readTestbedInput } from "./login_phase.mjs";

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
//
// The receipt names the account by its user id only, never its email: the CI jobs tee this output
// into the evidence they upload (qa-acceptance*.yml), and the repository is public.
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
      detail: `QA account ${identity.id} can see the production server: [${production.join(", ")}] — out of bounds (artin, 2026-08-31)`,
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
    detail: `QA account ${identity.id} sees ${names.length}: ${names.join(", ")}`,
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

const PRODUCTION_API = "https://api.raft.build/api/";

class ProbeError extends Error {
  constructor(reason, detail) {
    super(detail);
    this.reason = reason;
  }
}

async function jsonResponse(url, options, phase) {
  let response;
  try {
    response = await fetch(url, options);
  } catch (error) {
    throw new ProbeError("infra", `${phase} request did not complete: ${error?.message ?? error}`);
  }
  if (response.status === 401 || response.status === 403) throw new ProbeError("credential", `${phase} rejected HTTP ${response.status}`);
  const path = phase === "servers" ? "GET servers" : "login";
  if (response.status === 429) throw new ProbeError("ratelimit", phase === "login"
    ? "login rate-limited HTTP 429 — wait before retrying; log in once per run and reuse the token"
    : "servers rate-limited HTTP 429 — wait before retrying");
  if (response.status === 404) throw new ProbeError("contract", phase === "login"
    ? "login endpoint returned 404 — the API shape may have moved" : "GET servers returned 404 — the endpoint may have moved");
  if (!response.ok) throw new ProbeError("infra", `${path} failed HTTP ${response.status}`);
  try {
    return await response.json();
  } catch {
    throw new ProbeError("contract", `${phase} response was not JSON`);
  }
}

function assignedAccountCode(list, identity, acceptance) {
  if (typeof identity?.id !== "string" || userRef(acceptance.nonce, identity.id) !== acceptance.expected.userRef) {
    return "configured_user_mismatch";
  }
  const matches = list.filter(server => server?.slug === acceptance.serverSlug);
  const [target] = matches;
  if (matches.length !== 1 || typeof target.id !== "string" || list.filter(server => server?.id === target.id).length !== 1 ||
      serverRef(acceptance.nonce, target.id, target.slug) !== acceptance.expected.serverRef) {
    return "configured_membership_mismatch";
  }
  return "account_and_server_confirmed";
}

async function probe(inputFile, credentialFile) {
  const base = (process.env.QA_API_BASE || PRODUCTION_API).replace(/\/?$/, "/");
  let acceptance;
  let email;
  let password;
  if (inputFile) {
    if (base !== PRODUCTION_API || !process.env.STAMP_RUN_CASE_ID) throw new ProbeError("contract", "private_target_unavailable");
    acceptance = readTestbedInput(inputFile, process.env, { required: true, consume: false });
    if (acceptance.problem || acceptance.binding.attempt !== 1) throw new ProbeError("contract", "private_target_unavailable");
    // Private login acceptance consumes the exact 0600 credential file fetched from Testbed (never QA_TEST_* env).
    const cred = readCredentialFile(credentialFile, { consume: false });
    if (cred.problem || !cred.email || !cred.password) {
      throw new ProbeError("credential", cred.problem || "credential_file_missing");
    }
    email = cred.email;
    password = cred.password;
  } else {
    email = process.env.QA_TEST_EMAIL;
    password = process.env.QA_TEST_PASSWORD;
    if (!email || !password) {
      throw new ProbeError("credential", "QA_TEST_EMAIL / QA_TEST_PASSWORD not set");
    }
  }

  // The dedicated job pins the first-party production origin and keeps the login on its runner.
  const requestOptions = () => inputFile ? { redirect: "error", signal: AbortSignal.timeout(20000) } : {};
  const session = await jsonResponse(`${base}auth/login`, {
    ...requestOptions(), method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email, password }),
  }, "login");
  try {
    if (!session?.accessToken) throw new ProbeError("contract", "no accessToken in the login response — the contract changed");
    if (inputFile && (typeof session.accessToken !== "string" || typeof session.refreshToken !== "string" || !session.refreshToken)) {
      throw new ProbeError("contract", "incomplete login response");
    }
    const body = await jsonResponse(`${base}servers`, {
      ...requestOptions(), headers: { Authorization: `Bearer ${session.accessToken}` },
    }, "servers");
    // The authenticated GET /api/servers is an array. Ordinary callers also support the legacy wrapper.
    let list = body;
    if (!inputFile && !Array.isArray(body)) list = body?.servers;
    const visibility = judgeVisibleServers(list, session.user);
    if (visibility.code === "not_a_list") throw new ProbeError("contract", visibility.detail);
    if (!acceptance || !visibility.ok) return visibility;
    const code = assignedAccountCode(list, session.user, acceptance);
    return { ok: code === "account_and_server_confirmed", code };
  } finally {
    if (inputFile && typeof session?.refreshToken === "string" && session.refreshToken) {
      // Only the session this probe just opened. The emulator still starts signed out.
      let response;
      try {
        response = await fetch(`${base}auth/logout`, {
          ...requestOptions(), method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken: session.refreshToken }),
        });
      } catch { /* Report unavailable cleanup as an infrastructure problem. */ }
      if (!response?.ok) throw new ProbeError("infra", "probe_logout_unconfirmed");
    }
  }
}

function parsePrivateArgs(args) {
  let inputFile = "";
  let credentialFile = "";
  for (let i = 0; i < args.length; i += 2) {
    const flag = args[i];
    const value = args[i + 1];
    if (!value) throw new ProbeError("contract", "private_target_unavailable");
    if (flag === "--testbed-input" && !inputFile) inputFile = value;
    else if (flag === "--credential-file" && !credentialFile) credentialFile = value;
    else throw new ProbeError("contract", "private_target_unavailable");
  }
  if (!inputFile) throw new ProbeError("contract", "private_target_unavailable");
  if (!credentialFile) throw new ProbeError("credential", "credential_file_missing");
  return { inputFile, credentialFile };
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  const args = process.argv.slice(2);
  const privateMode = args.length !== 0;
  try {
    const { inputFile, credentialFile } = privateMode ? parsePrivateArgs(args) : { inputFile: null, credentialFile: null };
    const verdict = await probe(inputFile, credentialFile);
    if (privateMode) console.log(`QA account preflight: ${verdict.code}`);
    else console.log(`visible-servers probe: ${verdict.ok ? "PASS" : "FAIL"} [${verdict.code}] ${verdict.detail}`);
    process.exitCode = verdict.ok ? 0 : 1;
  } catch (error) {
    const reason = error instanceof ProbeError ? error.reason : "contract";
    if (privateMode) {
      console.error(`QA account preflight: unavailable (${reason})`);
    } else {
      console.error(`visible-servers probe: UNJUDGEABLE [${reason}] ${error.message}`);
      console.error(`reason=${reason}`);
      if (reason === "ratelimit") console.error("visible-servers probe: do NOT rerun immediately — that is what caused this");
      if (reason === "contract") console.error("visible-servers probe: a passing rerun does NOT mean this is fixed — check the probe against the live endpoint");
    }
    process.exitCode = 2;
  }
}
