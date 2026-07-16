# QA acceptance system

Agent-in-CI acceptance testing for the Raft app: an owner-written case goes
in, an M3 agent drives the real APK on a device, evidence (per-step
screenshots + steps.jsonl + verdict.json) comes out, a human makes the final
call. Origin: #proj-quality tasks #1–#4.

| Doc | What |
|-----|------|
| [`design.md`](design.md) | System design v1 (reviewed): data model, executor contract, state machine, asymmetric judging, hands release-gate integration, phasing |
| [`case-schema.md`](case-schema.md) | Case format v0.1: steps = goal + hints, assertions stay machine-checkable |
| [`cases/`](cases/) | The case library — 13 cases, indexed in [`cases/README.md`](cases/README.md) |
| [`annotation-spec.md`](annotation-spec.md) | Evidence annotation spec — sharp red-box overlay of assertion regions on screenshots |
| [`../qa-acceptance-operations.md`](../qa-acceptance-operations.md) | Operation playbook — how to drive the app accurately; injected into executor context (moves into this folder once PR #1077 lands; the runner's default path points there) |

Executor: `scripts/qa/acceptance_agent.mjs` + `.github/workflows/qa-acceptance.yml` (PR #1077).

**The loop that keeps this honest**: every failed round's root cause is
distilled back into the playbook or a case's known-pitfalls notes; agent
passes are only ever *likely-pass* — the final verdict belongs to the
requesting human.
