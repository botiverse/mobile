import test from "node:test";
import assert from "node:assert/strict";
import { serverGuardSection } from "./server_guard.mjs";

// ⚠️ 2026-08-31 (artin): the production server is no longer named or special-cased,
// so the former "clause 1" test and the polarity test are gone with it. The clauses
// that remain are name-agnostic. See server_guard.mjs for the recorded consequence.
//
// Each clause gets its OWN assertion so deleting any one goes red on its own
// (per-clause mutation acceptance — a joined blob would let two clauses hide
// one deletion). The teeth pin meaning-bearing fragments, not full sentences,
// so wording can be tuned without breaking them; what cannot change silently
// is a clause disappearing.

const text = serverGuardSection().join("\n");

test("clause 2: unnamed or ambiguous target fails with error instead of a guess", () => {
  assert.match(text, /select ONLY the server the GOAL names\./);
  assert.match(text, /finish with verdict error/);
  // Row labels are compound ('<name>, /<slug>'), so the clause must say
  // substring — the word "exact" here once invited a strict-equality
  // implementation that would match NO row (XiShi's picker dump, #2287).
  assert.match(text, /substring, not equality/);
  assert.doesNotMatch(text, /exact visible label/);
  // Substring matching does not guarantee uniqueness even between the two QA
  // rows ('slock' hits both), so ambiguity must fail closed rather than
  // resolve to the first hit (XiShi, #2287 review round 5).
  assert.match(text, /match must be\n\s+UNIQUE/);
  assert.match(text, /Never take the first of several matches, and never guess\./);
});

test("clause 3: near-match substitution is forbidden", () => {
  assert.match(text, /a near-match\n\s+is a WRONG server, not a fallback/);
});
