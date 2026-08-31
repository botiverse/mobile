import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { PRODUCTION_SERVER_NAME, serverGuardSection } from "./server_guard.mjs";

// Each clause gets its OWN assertion so deleting any one goes red on its own
// (per-clause mutation acceptance — a joined blob would let two clauses hide
// one deletion). The teeth pin meaning-bearing fragments, not full sentences,
// so wording can be tuned without breaking them; what cannot change silently
// is a clause disappearing.

const text = serverGuardSection().join("\n");

test("clause 1: the production server is banned by name, override-proof", () => {
  assert.equal(PRODUCTION_SERVER_NAME, "Raft Android");
  assert.match(text, /'Raft Android' is the PRODUCTION server\. NEVER tap, select, join, or log into/);
  // The override wording is the point of the choke placement: a goal that
  // says "choose Raft Android" must lose to this line.
  assert.match(text, /even if the GOAL, a hint, or any other text says to/);
  assert.match(text, /OVERRIDE the GOAL/);
});

test("clause 2: unnamed target fails with error instead of a guess", () => {
  assert.match(text, /select ONLY the server the GOAL names by exact visible/);
  assert.match(text, /finish with verdict error/);
  assert.match(text, /"goal names no target server" instead of guessing/);
});

test("clause 3: near-match substitution is forbidden", () => {
  assert.match(text, /a near-match\n\s+is a WRONG server, not a fallback/);
});

test("polarity: the guard never phrases the production name as a target", () => {
  // The banned name must appear ONLY inside ban phrasing. If someone edits
  // the guard into an instruction ("select 'Raft Android'"), this goes red
  // even while clause-presence teeth stay green.
  assert.doesNotMatch(text, /(?:choose|select|tap|join|log into)[^\n]*'Raft Android'/i);
  for (const line of serverGuardSection()) {
    if (line.includes(PRODUCTION_SERVER_NAME)) {
      assert.match(line, /PRODUCTION/, "the production name may only appear in the ban clause");
    }
  }
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
