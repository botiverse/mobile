---
name: raft-release-changeset
description: Use when a task, PR or owner decision targets a specific Raft Mobile version (e.g. "ship in 1.15.0") and needs its user-facing changeset, or when aggregating changesets into per-platform release notes for a train.
---

# Raft release changesets

A version-targeted change is not complete without its own changeset in that
version's directory. Detail: AGENTS.md "Versioned Mobile Changesets",
`docs/mobile-paired-release-sop.md` §2 and §4, notes format in
`docs/android-hands-release-practices-2026-07-02.md` → "Release Notes".

## Write a changeset (feature author)

1. Only if a version was explicitly assigned. Do not guess the "next" version;
   without an assignment, leave it for release-lane classification.
2. Create one kebab-case file per user-facing feature/improvement/fix:
   `change-logs/<X.Y.Z>/changesets/<short-name>.md`
   (examples: `change-logs/1.15.0/changesets/`).
3. Frontmatter (all keys required):
   ```markdown
   ---
   type: added | changed | improved | fixed | removed
   area: <area, e.g. notifications, thread, sync-core>
   platforms:
     - android
     - ios
   pull_request: <product PR number>
   rollout: full | canary
   ---

   ## zh-CN

   <user-visible behaviour and impact>

   ## en

   <same claims in English>
   ```
   These are the dominant values in `change-logs/*/changesets/` (`fixed`,
   `added`, `improved`, `changed`; `rollout: full`, `canary` for flagged
   rollouts); don't invent new ones.
   `platforms` lists only platforms that ship the behaviour (`android`,
   `ios`).
4. Write what the user sees, not how: no class names, test lists, commit
   hashes, PR numbers in prose, or architecture narration.
5. Ship it in the product PR. If the version was assigned after merge, open a
   follow-up changeset PR right away referencing the product PR. Replace any
   `pull_request: TBD` with the real number before the changeset merges.
6. Before moving the task to In Review: file present, both locales match the
   shipped scope, PR number and rollout accurate.

Do not edit `change-logs/<X.Y.Z>/<platform>/{zh-CN,en,ja}.md` as a feature
author; the release lane owns aggregation.

## Aggregate (release operator)

1. For each shipped platform, write `change-logs/<X.Y.Z>/android/{zh-CN,en,ja}.md`
   and `change-logs/<X.Y.Z>/ios/{zh-CN,en,ja}.md` from the accepted changesets
   only — never from git subjects.
2. Format: up to three sections in this order, omit empty ones:
   `✨ **新功能** / ✨ **New** / ✨ **新機能**`,
   `🔧 **改进** / 🔧 **Improved** / 🔧 **改善**`,
   `🐛 **修复** / 🐛 **Fixed** / 🐛 **修正**`; bullets do not repeat the platform
   name; fold small related fixes into one line.
3. Terms: compare nouns/status labels with the live catalogs —
   slock `packages/web/src/i18n/messages/` and
   `compose/shared/src/commonMain/composeResources/values-<locale>/strings.xml`; Japanese
   terms in `docs/i18n-ja-glossary.md`.
4. All three locales carry the same claims and section counts; the owner
   approves the files before any draft notes are applied.
