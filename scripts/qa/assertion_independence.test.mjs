import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { serverGuardSection } from "./server_guard.mjs";

// WHAT THIS PINS, and why prose was not enough.
//
// server_guard.test.mjs gives every clause its OWN assertion. The reason was
// written as a comment there (":6-10") and nowhere else: verifying a guard with
// ONE block-level mutation — delete the whole section, watch it go red — cannot
// distinguish a load-bearing clause from a decorative one that no assertion
// pins, because the block mutation kills everything at once. A comment stops
// the reader who is already reading; it does not stop the rewrite that merges
// nine assertions into one regex, which leaves every test green.
//
// So the property "each assertion can fail ON ITS OWN" is pinned here as
// behaviour: mutate ONE clause at a time and require the failures to be
// individually attributable.
//
// The predicates are not restated here — they are EXTRACTED from
// server_guard.test.mjs and executed. Restating them would create a second copy
// free to drift from the one that actually guards production.

const TEST_FILE = new URL("./server_guard.test.mjs", import.meta.url);
const src = fs.readFileSync(TEST_FILE, "utf8");

const PREDICATE_RE =
  /assert\.(match|doesNotMatch)\(\s*text\s*,\s*(\/(?:\\.|\[[^\]]*\]|[^/\\])+\/[gimsuy]*)/g;

const predicates = [];
for (const m of src.matchAll(PREDICATE_RE)) {
  const lit = m[2];
  const lastSlash = lit.lastIndexOf("/");
  predicates.push({
    kind: m[1],
    label: `${m[1]} ${lit.length > 56 ? `${lit.slice(0, 56)}…` : lit}`,
    re: new RegExp(lit.slice(1, lastSlash), lit.slice(lastSlash + 1)),
  });
}

// A doesNotMatch clause asserts an ABSENCE, so its mutation is an insertion,
// not a deletion. Each needs a sample that violates it. The coverage check
// below makes an unlisted absence-clause go red rather than be skipped —
// otherwise adding one would silently shrink this test's reach.
const VIOLATIONS = [
  { for: /exact visible label/, sample: "exact visible label" },
];

const text = serverGuardSection().join("\n");

/** @returns indices of predicates that FAIL against `t` */
const failuresOf = (t) =>
  predicates
    .map((p, i) => ((p.kind === "match" ? p.re.test(t) : !p.re.test(t)) ? null : i))
    .filter((i) => i !== null);

const violationFor = (p) => VIOLATIONS.find((v) => v.for.test(p.re.source))?.sample;

/** Mutate exactly ONE clause: delete what it pins, or inject what it forbids. */
const mutateOne = (p) => {
  if (p.kind === "match") {
    const hit = text.match(p.re);
    assert.ok(hit, `cannot mutate a predicate that does not match the real text: ${p.label}`);
    return text.replace(hit[0], "");
  }
  return `${text}\n${violationFor(p)}`;
};

test("the extraction itself is alive (a silent zero would pass everything below)", () => {
  // Every loop in this file iterates `predicates`. If PREDICATE_RE ever stops
  // matching — a reformat, a rename — the loops run zero times and every
  // assertion below passes vacuously. So cross-check the count against a second,
  // deliberately dumber extraction that shares no regex with the first.
  const dumbCount =
    src.split("assert.match(text").length - 1 + (src.split("assert.doesNotMatch(text").length - 1);
  assert.ok(dumbCount > 0, "the dumb counter found no predicates — is server_guard.test.mjs intact?");
  assert.equal(
    predicates.length,
    dumbCount,
    `extracted ${predicates.length} predicates but counted ${dumbCount} — the extractor is missing some`,
  );
  // What bears the load is the equality between the regex extractor and the
  // dumb counter above. This absolute floor merely guards against a degenerate
  // 0 or 1 edge case (task #252, Jony/LiBai).
  assert.ok(predicates.length >= 2, `only ${predicates.length} predicates — expected at least 2`);
});

test("every absence-clause has a violation sample (no clause is silently skipped)", () => {
  const uncovered = predicates.filter((p) => p.kind === "doesNotMatch" && !violationFor(p));
  assert.deepEqual(
    uncovered.map((p) => p.label),
    [],
    "a doesNotMatch clause has no violation sample, so it would never be mutated here",
  );
});

test("baseline: all clauses hold on the real guard text", () => {
  assert.deepEqual(
    failuresOf(text).map((i) => predicates[i].label),
    [],
    "the unmutated guard text must satisfy every clause",
  );
});

test("each clause can fail ON ITS OWN — one-clause mutation kills its own assertion", () => {
  for (const [i, p] of predicates.entries()) {
    const failing = failuresOf(mutateOne(p));
    assert.ok(
      failing.includes(i),
      `mutating clause ${i} did not fail clause ${i} (${p.label}) — it is not pinned by its own assertion`,
    );
  }
});

test("no two clauses share a failure signature — this is what forbids merging them", () => {
  // If nine assertions are "simplified" into one regex, every single-clause
  // mutation produces the SAME failure, and the guard can no longer say WHICH
  // clause went missing. Distinct signatures are what make the failures
  // attributable, and attributability is the whole property.
  const seen = new Map();
  for (const [i, p] of predicates.entries()) {
    const sig = JSON.stringify(failuresOf(mutateOne(p)));
    const prev = seen.get(sig);
    assert.equal(
      prev,
      undefined,
      `clauses ${prev} and ${i} are indistinguishable: both fail as ${sig}. ` +
        `Merged assertions cannot attribute a deletion. (${p.label})`,
    );
    seen.set(sig, i);
  }
  assert.equal(seen.size, predicates.length);
});

test("a block-level mutation is NOT acceptance evidence, and this test says so", () => {
  // Deleting the whole section goes red — which is exactly why it proves
  // nothing: it produces ONE undifferentiated failure covering every clause,
  // so a clause that no assertion pins is indistinguishable from one that is.
  const blockFailures = failuresOf("");
  assert.equal(
    blockFailures.length,
    predicates.filter((p) => p.kind === "match").length,
    "a block mutation should kill every presence-clause at once — that is the point",
  );
  // It yields a single signature for all clauses, so it cannot satisfy the
  // per-clause requirement above. Stated as an assertion so the claim is
  // executable rather than a comment.
  assert.ok(
    blockFailures.length > 1,
    "with >1 clause, a block mutation cannot attribute a failure to any one of them",
  );
});

test("NEGATIVE CONTROL: the merged form this test forbids is actually rejected by it", () => {
  // Everything above only has teeth if it would REJECT the simplification it
  // exists to prevent. Build that simplification and run the same analysis:
  // one regex OR-ing every presence-clause, in place of one assertion each.
  const presence = predicates.filter((p) => p.kind === "match");
  const merged = new RegExp(presence.map((p) => `(?:${p.re.source})`).join("|"));

  const mergedFailures = (t) => (merged.test(t) ? [] : [0]);
  const signatures = presence.map((p) => JSON.stringify(mergedFailures(mutateOne(p))));

  // Under the merged form, deleting one clause leaves the others matching, so
  // the mutation kills NOTHING — the defect this whole file exists to catch.
  assert.ok(
    signatures.some((s) => s === "[]"),
    "expected the merged form to let a single-clause deletion pass unnoticed",
  );
  // And it collapses every distinct signature into one.
  assert.ok(
    new Set(signatures).size < presence.length,
    "expected the merged form to collapse the per-clause failure signatures",
  );
});
