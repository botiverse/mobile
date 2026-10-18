# Judging a Web → mobile capability gap, and ranking it

Owner: 沈括 · Verified at: 2026-10-18 · Status: current

This is the procedure behind the mobile port-candidate list. It answers two questions in order: **is this actually a client gap?** and **how urgent is it?** It stops at *route to the owning lane with evidence* — the tracker does not implement, and does not decide product priority.

The hard part is almost never "does Web have it". It is the four steps below, each of which has produced a wrong candidate when skipped.

## 1. Is it a client gap at all?

Classify by **surface that owns the behaviour**, not by where the diff landed.

| Diff lives in | Owner of behaviour | Candidate? |
|---|---|---|
| Web app renderer, but the data contract is shared | mobile, if the contract is live | yes |
| Web-only presentation (AST/parser internals, desktop shell, browser APIs) | Web | no |
| Server payload / endpoint | server first, then clients | not yet — see step 3 |
| Shared contract, both clients already consume it | whoever is behind | yes, with contract evidence |

Concrete exclusions seen in one sweep (#10019/#10024 markdown AST + mention parsing → Web/server only; desktop idle detection; desktop release notes). A commit that mentions "mention" or "markdown" is not automatically a mobile gap.

**Check:** name the user-visible behaviour, then find the same behaviour in the mobile tree by *searching the behaviour*, e.g. the string users see, the store/repository field, the route. Component names differ across Web/Compose/Kuikly, so name-based search produces both false negatives and false positives.

## 2. Is mobile already doing it somewhere else

Mobile has three rendering surfaces: shared Compose routes, Kuikly pages, and native fallbacks. A capability can exist in one and be missing in another. Search all three before filing.

Also check whether the "gap" is a **deliberate narrow scope** recorded in a PR body. Several mobile PRs ship a capability with an explicit exclusion (long-press menus excluded from an overlay fix, tablet/fold states out of scope). That is a known gap, not an unnoticed one — the candidate is the excluded part, and should say so.

## 3. Contract prerequisites

Do not port against a contract that is not in production. If the Web capability depends on an endpoint, field, or read-state rollout that is staging-only, the candidate is real but **blocked**, and the block is attributable to the server owner, not to mobile.

Recording rule: write the blocking artifact (PR + deploy state) next to the candidate. "Waiting for backend" without a named PR is how a candidate silently rots for a month.

Counter-case worth knowing: when the server payload is *missing* fields, the client may still be able to act. In one case the client rejected realtime messages as "incomplete" — correct against the written contract, wrong against the product decision, which was "store the message with explicit unknown placeholders and backfill later". Ask "what does the product want when data is missing", not only "is the payload valid".

## 4. Ranking

| Level | Criterion | Examples |
|---|---|---|
| P0 | Data loss or duplication, wrong recipient, credential/identity displayed wrong, or a core loop unusable | task list stale after server switch; reply not persisted; sign-in button shows the wrong brand mark |
| P1 | A whole capability missing that users hit weekly, or workflow continuity broken | thread unread/jump affordances, task history timeline, activity overview |
| P2 | Consistency, secondary entries, single-screen deltas | alternate entry points, compact profile layout |

Two modifiers that change the level by one:

- **Default-path rule.** A defect on the path most users take is one level higher than the same defect on an opt-in path. A toggle that works only for pages without their own `viewport` meta is broken for the majority case, even though the toggle "works".
- **Silent-failure rule.** A capability that exists but produces no visible effect (or an effect indistinguishable from broken rendering) ranks above one that is visibly absent, because users cannot tell whether to retry.

## 5. Route, don't build

One candidate → one owning lane, one named owner, one state. The tracker's job is to keep the state honest:

`candidate → claimed (owner) → source exact → CI terminal → device provenance → signed/merged → released`

Evidence required per state:

| State | Evidence |
|---|---|
| claimed | owner name, PR or issue number |
| source exact | commit SHA + the PR head that produced it |
| CI terminal | the run ID that reached a terminal state (see trap below) |
| device provenance | same APK/build as the exact, install-time hash readback, device + API, fixture hash |
| signed/merged | approval bound to that exact + merge SHA read back from the branch |
| released | version/channel actually shipped |

The "same build" part is a containment question, not a version-string question: a later build of the same version can lack the fix entirely if it was cut from a release branch that never received it. The procedure — resolve the build's source commit from the run, then `git merge-base --is-ancestor <fix-merge-sha> <run-head-sha>` — is in [release-provenance-containment.md](release-provenance-containment.md).

## 6. Traps that have actually cost us time

**"Producer exists" ≠ "value delivered".** One Android prop went through four consecutive device failures: the value was never applied because the branch was missing, then because the branch existed but the setter never ran, then because the prop never reached the render layer, then because three plumbing sites (a cache key list, the update path, and the init path) never passed it. Each round, source review passed and CI was green. The only thing that caught it was a device reading of the actual value.

**CI green does not clear a runtime gate; CI red is not always a failure.** A `CI gate` check can go red because a duplicate workflow run was cancelled by a concurrency group, while the surviving run is still executing. Read the surviving run's terminal state before reporting red.

**Verify the tree you are quoting.** A gap report claimed a brand mark lived in three places (shared `MR/assets` SVG, an inline URL-encoded data URI, a leftover platform drawable) and that Android rendered from the inline copy. At the base commit that was wrong: the inline URI had been deleted when the three hosts were unified on one `assets://` path, so the shared SVG is the only live render source and the drawable is dead. The conclusion happened to survive (fix one asset, delete the dead one) but the mechanism was fabricated by a stale checkout — the report was produced by grepping a worktree that had not been updated for two months, while CI was green against a much newer base.

  Rules that prevent it: resolve the base commit first (`git ls-remote`, the PR's base SHA) and read through `git show <sha>:<path>` / `git grep <sha>`; a clean `git status` says nothing about age, and `git fetch --depth=1 <ref>` updates `FETCH_HEAD` without touching the working tree. Quote the base SHA next to every file-level claim, so a reader can re-check it.

  The same failure one level removed: a doc line in this repo still said the dead drawable was "used by the remaining native account screen" long after that screen was retired. Dead code is cheap to spot; dead *claims* about code survive reviews.

**A screenshot is evidence with a ceiling.** What a fixture screenshot, a reference image and an authenticated build each can and cannot support is graded in [mobile-visual-evidence-ceiling.md](mobile-visual-evidence-ceiling.md); "we saw it on device" is not one thing.

**Logic tests do not cover layout.** A jsdom/node harness can prove a viewport-meta rewrite happens; it cannot prove `window.innerWidth`. Anything whose criterion is a layout or timing value needs a device, and the fixture must include the failure shape (page that already declares a viewport; page that inserts one 1.5 s after load; both transition directions).

**Emulator ≠ physical device; emulator PASS does not cover vendor-specific rendering, signing chains, keyboard/IME, or push.**

**Missing coverage has names.** Record `NOT RUN` (not attempted), `NOT APPLICABLE` (structurally impossible here, with the seat named), and "not determinable" separately. A blank cell reads as PASS to the next person.

## 7. Checklist before filing a candidate

1. Behaviour named, in user terms.
2. Same behaviour searched in all mobile surfaces; result recorded.
3. Owning surface established (Web / server / mobile).
4. If server-dependent: is it in production? Named artifact if not.
5. Level assigned with the default-path and silent-failure modifiers considered.
6. One lane owner proposed; product owner notified of the priority, not asked to implement.
7. State recorded with the evidence column filled, or explicitly `candidate`.
8. Every file-level claim carries the base SHA it was read at.

## 8. Recording a candidate

Keep one line per candidate with: behaviour, level, owning lane, state, blocking artifact, last evidence date. Stale entries are marked, never silently trusted — an unverified P0 from three weeks ago is a claim, not a fact.

## Related

- [release-provenance-containment.md](release-provenance-containment.md) — whether a given build contains a given fix, by commit containment.
- [mobile-visual-evidence-ceiling.md](mobile-visual-evidence-ceiling.md) — what each class of visual evidence proves, and when a difference is real.
