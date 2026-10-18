# Raft Mobile Knowledge Base

Engineering knowledge for the Raft Mobile client, maintained by the team (humans and agents) that builds it.

## Guardrails (read before writing)

This repository is **public**. Every article here must be publishable as-is:

- **No secrets** — no credentials, tokens, signing material, QA account names, or internal hostnames/topology.
- **No org structure** — no team rosters, personnel assignments, or internal process charts. Refer to work by role ("device QA", "reviewer") or by public artifact (PR number, commit SHA).
- **No unreleased roadmap** — describe what shipped or is publicly in flight (open PRs), not plans.
- **Facts over lore** — every non-obvious claim should be checkable from public artifacts (this repo's code/history, public CI runs, public issue/PR numbers).

PRs touching this directory get the same review as code. Sentinel scans apply.

## Index format

One line per article: `| Title | Area | Owner | Verified at | Status |`
- **Owner** — who keeps it true.
- **Verified at** — the date the content was last checked against reality. Stale entries get marked `stale`, not silently trusted.
- **Status** — `current` | `stale` | `superseded by <link>`.

## Index

| Title | Area | Owner | Verified at | Status |
|---|---|---|---|---|
| [Thread fast-scroll blank rows: the reverseLayout re-anchor case](android-thread-scroll-blank-raft13.md) | thread / rendering | Mahua | 2026-10-18 | current |
| [Kuikly release-set pin upgrade recipe](kuikly-release-pin-recipe.md) | build / dependencies | Mahua | 2026-10-18 | current |
| [Android high refresh rate: enabling 120Hz and telling real jank from measurement artifacts](android-high-refresh-rate-and-jank-attribution.md) | performance / android | 沙琪玛 | 2026-10-18 | current |
| [Mermaid preview gesture ownership: keep diagram panning inside the card](mermaid-preview-gesture-boundary.md) | mermaid / gestures | Nathan | 2026-10-18 | current |
| [What a screenshot proves: grading mobile visual evidence](mobile-visual-evidence-ceiling.md) | verification / visual | 辣条 | 2026-10-18 | current |
| [Does the package contain the fix? Read the build's source, not the version string](release-provenance-containment.md) | release / provenance | 辣条 | 2026-10-18 | current |
| [Judging a Web → mobile capability gap, and ranking it](web-capability-port-criteria.md) | parity / process | 沈括 | 2026-10-18 | current |
| [Verify the exact head before merging — not "the PR"](exact-head-merge-verification.md) | release / verification | peng | 2026-10-18 | current |
| [Building and launching the iOS app on a simulator](ios-simulator-build-and-launch.md) | build / ios | 辣条 | 2026-10-18 | current |

## Planned sections

- `architecture/` — how the client is put together (KMP shared, Kuikly, platform shells)
- `release-runbook/` — how releases are cut, signed, and verified
- `qa-device-matrix/` — what is verified on which device/emulator/cloud tier, and what is explicitly not covered
- `verification-sop/` — how runtime claims are evidenced (carrier identity chain, paired A/B, log readback)
- `incidents/` — postmortems with mechanism-level root causes
- `parity/` — web → mobile capability port candidates and their state
