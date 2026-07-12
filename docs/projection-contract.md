# Projection Contract (mobile) — every fact→view narrowing is a contracted derivation

> Origin (artin, first-principles close-out 2026-07-12): projection bugs recurred N times (kind lost,
> `#channel` subtitle dropped, `else Human` invented, weak overload chosen, whole-scope clear). They are not
> bad luck — they are one hole: **a projection is a hand-written lossy function with no contract saying which
> facts must survive the narrowing.** This document closes the CLASS. Acceptance criterion is one sentence:
> *we do not hit this problem again.*

Status: standard / review red-line for the mobile repo (KMP shared + hosts). Living document.
Owner: HanXin (contract). Sweep of existing projections: task #518 (AD4 lead, AD2 gate-review).
Applies to: every place that narrows a FACT (wire frame / canonical store row) into a VIEW (header, banner,
row, launch payload, badge count, parser output, any UI/host model).

## 0. Definitions

- **Fact** — a canonical, contracted piece of truth: a wire frame validated against the producer
  manifest, or a canonical store row (`CanonicalMessageRow` / the persisted envelope). A fact's field set is
  *declared* (manifest / data class), so "what may exist" is enumerable.
- **Projection** — any pure narrowing `view = projector(fact)`: dropping fields, reshaping, choosing a
  subtitle, computing a badge count. The output is a VIEW, never a new fact.
- **The disease** — a projection may silently (a) **drop** a fact field, (b) **invent** a field the fact
  never carried, (c) **mis-source** (read the wrong/weaker input), or (d) **over-mutate** (clear/segment
  more than the invariant allows). All four are "a path the compiler does not forbid".

## 1. Three iron rules

Each rule already has a proven template — this is codification, not invention.

### Rule 1 — Facts cross boundaries only as non-decomposable types (never bare scalars)

Identity-bearing or paired data crosses a boundary as a value type that cannot be taken apart, so a
consumer cannot silently mis-pair or mis-source it. `id` travels with its `kind`; `sizeBytes` travels as its
raw value, whole. A **bare scalar at a boundary** (a raw id string, a naked `Int` count, a lone name) is a
**gate-red smell** — it is exactly what gets mis-sourced.

> **A boundary that accepts a bare scalar where an identity/pair is meant is a defect, even if today's
> caller happens to pass the right value.**

Template: `MemberDetailIdentity` (#123) — id+kind as one value class; `Attachment.sizeBytes` raw pass-through
(#706).

### Rule 2 — A projection may DERIVE, never CREATE

Every projection is a **pure function of a canonical fact**, and it is pinned by a **line-by-line
consistency test**: `expectedView == projector(fact)`, field by field. If the view contains anything not
derivable from the fact, that thing **is a new fact** — it must enter the contract first (producer manifest /
canonical schema), and must **never be born privately inside a mapper/projector** (no `else Human`, no
`senderName ?: "someone"` fabrication, no locally-synthesized label).

> **If you cannot write `assertEquals(expected, projector(fact))` line-by-line, the projection is creating,
> not deriving — stop and take the missing field through the fact contract.**

Template: `#709` — persisted scalars `==` `extract(decode(canonicalFactJson))` (the projection of the
envelope equals the decode of it); the canonical fact codec `(甲)` envelope==manifest.

### Rule 3 — Each boundary type has ONE mechanical reverse-closure gate + a negative that bites the REAL path

A projection boundary is pinned by a **mechanical reverse-closure gate**: the set of keys the projection
emits is *mechanically enumerated* and proven `⊆` the fact's *declared* field set (plus an explicit,
named exclusion ledger). Not a hand-written "we compared a few fields" test — a closure the machine walks.
And it carries a **negative specimen that fails on the REAL callsite / mutation path**, not on a helper.

> **"The gate bit the helper, not the callsite" is the recurring failure (weak-overload #718, the codec RED,
> the #517 whole-scope clear). A negative that only exercises the happy helper is a false green.**

One gate per boundary TYPE (reuse, do not multiply):

| boundary | gate | what it closes |
|---|---|---|
| wire → app message | **G6** reverse closure | every `message:*` post-projection key ⊆ canonical(19) + named exclusions |
| canonical → persisted envelope | **(甲)** manifest→envelope closure | envelope fields == manifest (byte-pinned) |
| identity / route / launch payload | **#715** boundary gate + meta-closure | launch payloads identity-only; file-discovery finds every projection |
| projection that also MUTATES state | invariant-bounded op gate | clear only `seq <= watermark` (not whole scope); decide once (not twice) |

## 2. Review red-line (non-negotiable)

> **A new or changed projection without its gate does not merge.**

A PR that adds/changes a fact→view narrowing MUST attach, in the PR, all three:
1. the **fact** it derives from (name the canonical type/field set),
2. the **reverse-closure gate** that pins it (new or extended),
3. the **real-path-biting negative** specimen.

Missing any one → blocked. This applies to hosts too: a host adapter must not re-derive or re-gate a fact
the shared core already owns (single source of truth — the #731 point-7 second-decide is the anti-pattern).

## 3. Onboarding checklist — copy this when you add ANY projection

When you write `view = f(fact)`:

1. **Fact source** — name the exact canonical fact/type it derives from. Need a field that isn't on it?
   **STOP** — that field is a *new fact*; take it through the producer manifest / canonical schema first.
   Do not synthesize it in the projector.
2. **Non-decomposable inputs** — identity/paired data enters as a value type, not bare scalars (Rule 1).
3. **Derivation-purity test** — write `assertEquals(expectedView, projector(fact))` line by line; assert no
   view field is absent from the fact (Rule 2).
4. **Boundary gate** — attach or extend the boundary's reverse-closure gate so the emitted key set is
   mechanically `⊆` the fact's declared fields (Rule 3). Pick the existing gate for that boundary type
   (wire=G6-style, persist=(甲)-style, identity/route=#715-style); do not invent a parallel one.
5. **Real-path negative** — add a RED specimen that FAILS if the projection drops / invents / mis-sources /
   over-mutates, and make it exercise the **real callsite** and an **adversarial** input (two items + a
   partial watermark, an explicit-null, an empty list — never a one-item happy path). If the only failing
   test hits a helper, you gated the wrong layer.
6. **Mutation granularity** (only if the projection also clears/segments state) — the mutation is bounded by
   its invariant: clear only `seq <= watermark` (retain later + unresolved-seq, fail-closed); decide the
   gate exactly once. This is the #517 / #731 class.

If a reviewer cannot check off all six from the PR, it is not ready.

## 4. Verified templates (reference implementations — copy these shapes)

- **G6** — wire post-projection reverse closure (`message:*` keys ⊆ canonical(19) + exclusions).
- **(甲)** — persist envelope==manifest reverse closure (`CanonicalFactEnvelopeManifestGateTest`).
- **#715** — identity/route boundary gate + file-discovery meta-closure (launch payloads identity-only).
- **#709** — derivation purity: persisted scalars == extract(decode(payload)); one-txn + continuation gates.
- **#123** — `MemberDetailIdentity` non-decomposable identity value type (id+kind paired).
- **#706** — raw value threaded whole (`sizeBytes`), formatting deferred to the view boundary.
- **#517** — mutation granularity: seq-bounded clear (`removeThrough(maxReadSeq)`), unresolved-seq fail-closed.

## 5. Why this kills the class (not just today's bugs)

The three diseases (drop / invent / mis-source) and the mutation disease (over-clear) all share one root:
*an error path the compiler does not forbid.* Each rule removes the path itself, not the symptom:
non-decomposable types (Rule 1) make mis-pairing unrepresentable; derivation-purity (Rule 2) makes a
fabricated field a compile/gate failure; the reverse-closure gate with a real-path negative (Rule 3) makes a
dropped/mis-sourced field a red gate on the actual callsite. When task #518's sweep has every existing
projection classified `has-gate / gate-added / retired`, and this red-line blocks every new one, the class
is closed — the same death as the identity class (#123) and the persistence class (#709).

## 6. Precedent rulings (task #518 sweep — binding examples of the rules above)

These are decided cases. Cite them when a reviewer or author hits the same shape.

### Ruling 1 — unknown kind/type/status is FAIL-CLOSED, not a default (Rule 2)

A projection that maps an unknown/other enum to a specific value (unknown sender kind → `Human`, unknown
message type → `Chat`, non-Agent → `Human`, unknown task status → `Todo`) is **creating** a fact the wire
did not carry. Terminal class is **add-gate + repair**, never "fossilize current behavior".

- Route every kind/status/type parse through **one shared strict parser** (the sole entry, e.g.
  `parsedMemberKindOrNull`); duplicated per-mapper parsing (canonical-row / REST / realtime / home / task /
  thread / **inbox**) drifts — gate parity across all paths. **Single entry is NOT self-enforcing by
  convention:** nothing stops a new `@Serializable` DTO from parsing a kind/type/status inline (a plain enum
  is constructible anywhere), and only per-consumer tests — not a mechanical gate — would catch it. Pin it
  with a **discovery meta-closure** (same shape as #715): mechanically find every wire→enum parse across the
  source sets and assert it routes through the shared strict parser; a new inline parse is RED. A drifting
  second path is a real defect, not hypothetical — e.g. `InboxParser`'s own `toSenderKind` accepted
  `{agent, user, human}`, which both bypassed the shared parser AND was the wrong dialect (the inbox wire
  emits `{user, agent, system}`, so it silently **over-rejected `system`** — dropped real data). Per-dialect
  accepted sets (frontend `{user, agent}` vs inbox/agent-visible `{user, agent, system}`) are an **explicit
  wire-contract parameter of the one shared parser**, never separate parsers with drifting sets.
- **Unknown fails closed** with a bounded diagnostic. It is NOT silently defaulted.
- A "documented default" is allowed **only with production-face evidence of the VALUE closed set** — and that
  evidence must be the **producer code / DB enum / serializer symbol** that emits the field, cited by name.
  **Key-set closure and value-set closure are DIFFERENT proofs and are NOT interchangeable.** A reverse-closure
  gate (G6 / (甲) / #715) proves the **key set** — *which* fields appear on the post-projection payload — it
  does **not** prove *which enum values* a field may take. Citing a key-existence proof as a value-set proof is
  itself an invention: it once green-lit a fabricated `senderType=system` fixture (the field exists on the
  wire, but that value does not on this face). So:
  - ❌ *wrong:* "G6 proves `message:*` `senderType` ∈ `{human, agent, system}`" — G6 proves `senderType` is a
    *present key*, not its value set; and that value set is not what the frontend producer emits.
  - ✅ *right (value set evidenced from the producer):* the frontend `message:*` / thread / realtime
    `senderType` closed set is `{user, agent}` (per the server producer/serializer), and a **system** message
    is `senderType=user` + `messageType=system` — `System` is projected from the strict `messageType=system`,
    never from a `senderType=system` (that value exists only on the *agent-facing* projection). A `senderType`
    default may therefore assume only `{user, agent}`, and dialect-specific accepted sets (frontend
    `{user, agent}` vs a normalized cache re-seed `{human, agent, system}`) must be distinguished **explicitly**
    (a wire-contract parameter), never merged by a global alias that eats the boundary difference.
  The default's comment MUST cite the **producer/DB-enum/serializer symbol** proving the value closed set. Wire
  with **no such producer evidence** (task assignee, home peer, `TaskStatus`, `MessageType` outside a
  producer-proven face) has no value-set evidence → **fail closed**.

### Ruling 2 — never gate a wrong assumption; repair the fact (Rule 2)

When a projection hardcodes a value because the wire lacks it, and the assumption is **factually false**, the
fix is a **typed fact**, not a gate around the guess. Adding a gate to a wrong assumption fossilizes a bug.

- Case: `TaskSummary → TaskThreadLaunchTarget` hardcoded `Channel` as the task's parent kind because the task
  wire carries no parent kind. But tasks can be created from a **DM** top-level message — DM-parented tasks
  exist — so inventing `Channel` is a latent #122-shape bug.
- Fix: add a **typed `parentKind`** to the server task wire (producer / manifest flow), and **fail closed**
  on the mobile side when it is absent. Do not gate the `Channel` guess.

### Note — presentation derivation is NOT invention

A *view choice derived from a fact* is allowed under Rule 2 and is not one of the above. `sizeLabel` (a format
of the raw `sizeBytes` fact, #706) and `senderName`-empty → `senderId` display fallback are **derived
presentation**, not fabricated facts — declare them as presentation derivations / registered known-deltas.
The product decides the *format / fallback choice*; there is no fail-closed here. Only unknown-enum →
concrete-value (Ruling 1) and wrong-assumption hardcodes (Ruling 2) are invention.
