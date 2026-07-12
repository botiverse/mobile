## Summary

<!-- What changed and why. -->

## Projection contract

<!-- DELETE this whole section if this PR does NOT add or change a fact→view projection
     (a header/banner/row/launch-payload/badge/parser — any narrowing of a canonical wire frame
     or store row into a view). Otherwise confirm the docs/projection-contract.md §3 checklist: -->

- [ ] **Fact source** named — the canonical fact/type this derives from; no field invented in the projector (a needed-but-absent field is a *new fact* → manifest first)
- [ ] **Non-decomposable inputs** — identity/paired data crosses as a value type, not bare scalars
- [ ] **Derivation-purity test** — `view == projector(fact)` asserted line by line
- [ ] **Boundary reverse-closure gate** attached/extended — G6 (wire) / (甲) (persist) / #715 (identity·route) / invariant-bounded (mutation)
- [ ] **Real-path negative** — a RED specimen that bites the real callsite on an adversarial input (two items + partial watermark, explicit-null, empty list — not a one-item happy path)
- [ ] **Mutation granularity** — if this projection also clears/segments state, it is bounded by its invariant (clear only `seq <= watermark`; decide once)

> Review red-line: a new/changed projection without its gate does not merge. See `docs/projection-contract.md`.
