import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
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

test("enforcement point: preamble() actually splices the guard after the goal", () => {
  // A correct guard module proves nothing if nothing injects it (test the
  // enforcement point, not the predicate). The agent script is not
  // import-safe (top-level main()), so this pins the source: the import
  // exists and the splice sits directly adjacent to opts.goal inside
  // preamble(), where it can override goal text from ANY source.
  const src = fs.readFileSync(new URL("./acceptance_agent.mjs", import.meta.url), "utf8");
  assert.match(src, /import \{ serverGuardSection \} from "\.\/server_guard\.mjs"/);
  assert.match(src, /opts\.goal,\n[\s\S]{0,400}?\.\.\.serverGuardSection\(\),/);
  // And exactly one injection site — a second call elsewhere would mean the
  // rule text can drift between two contexts.
  assert.equal(src.match(/serverGuardSection\(\)/g)?.length, 1);
});

test("deterministic repro path only ever selects a QA server", () => {
  // ios_deterministic_repro.mjs is the scripted no-model path: it reads no
  // goal, so NONE of the goal/preamble layers can see it. Today it is safe
  // because it hardcodes the QA server — but nothing pinned that literal,
  // so rewriting it would turn every guard green while pointing the
  // deterministic path at production (XiShi, fifth surface, 2026-08-31).
  const src = fs.readFileSync(new URL("./ios_deterministic_repro.mjs", import.meta.url), "utf8");
  // Pin the SELECTION expression itself — the line that decides which row
  // gets tapped — not just any mention of the name (a partial swap left
  // other mentions matching a looser assertion during mutation testing).
  // Changing the target to the other QA server is legitimate — via a
  // conscious edit here.
  assert.match(src, /\.find\(\(l\) => \/OranixSlockTest\/\.test\(l\)\)/,
    "the picker row-selection expression must target the QA server literal");
});

test("survival: the text-prune loop can never reach the preamble message", () => {
  // The guard lives in messages[0]; pruneImages trims long TEXT blocks only
  // from i >= 1, which is the entire reason the ban survives a long run. If
  // someone "unifies" that start to i >= 0, the ban gets trimmed to a stub in
  // exactly the runs long enough to need it — while every other test here
  // stays green (XiShi, #2287 review). Only the TEXT loop start is
  // load-bearing: the image loop legitimately visits messages[0].
  const src = fs.readFileSync(new URL("./acceptance_agent.mjs", import.meta.url), "utf8");
  const textPrune = src.match(/The preamble \(first message\) is never trimmed\.[\s\S]{0,200}?for \(let i = messages\.length - 1; i >= (\d+); i--\)/);
  assert.ok(textPrune, "text-prune loop (with its never-trimmed comment) must exist");
  assert.equal(textPrune[1], "1", "text-prune loop must start at i >= 1 so messages[0] is untouchable");
});
