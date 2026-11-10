---
name: raft-release-changeset
description: Use when a task, PR or owner decision targets a specific Raft Mobile version (e.g. "ship in 1.15.0") and needs its user-facing changeset, or when aggregating changesets into per-platform release notes for a train.
---

# Raft release changesets

Read `docs/mobile-release-sop.md` §1 (writing a changeset: when, front matter,
wording) and §4 (aggregating into `change-logs/X.Y.Z/{android,ios}/{zh-CN,en,ja}.md`).

- Only for an explicitly assigned version; never guess the next one.
- One kebab-case file per user-visible change in
  `change-logs/X.Y.Z/changesets/`, shipped in the product PR, with the real
  `pull_request` number.
- Feature authors never edit the platform aggregates; the release operator
  writes them from accepted changesets only.
