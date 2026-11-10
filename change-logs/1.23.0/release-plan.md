# 1.23.0 release plan

## Owner and intent

- Release owner: @artin. Mobile DRI and release operator: @Lagoon.
- Dates (Asia/Shanghai): scope lock and branch cut 2026-11-10; target release 2026-11-11.
- Branch `release/1.23.0`, cut from a reviewed `main` commit at scope lock (not yet created).
- Delivery intent:
  - Android: Hands `preview` draft, then `preview`; `main` only after artin's OK. One APK per channel.
  - iOS: TestFlight internal (Botiverse) first; external Insiders only after artin has tested that build.
  - App Store: after artin's OK, with the new iPad 13" screenshots.

## Notion ledger

- Not created: the Mobile Release History page is private to Lagoon; owner asked (2026-11-10).

## Candidates

All changesets below are already on `main`. Owner = PR author.

| Changeset | PR | Platforms | Type | Rollout | State | Owner |
|---|---|---|---|---|---|---|
| `activity-top-row-steady` | #4461 | android, ios, desktop | fixed | full | accepted | @Lagoon |
| `agent-config-applies-when-idle` | #4475 | android, ios, desktop | changed | full | accepted | @Lagoon |
| `agent-status-retry-revoked` | #4478 | android, ios, desktop | added | full | accepted | @Lagoon |
| `announcement-links-open-in-browser` | #4462 | android, ios | fixed | full | accepted | @Lagoon |
| `app-link-card-refresh` | #4495 | ios, android, desktop | fixed | full | accepted | @Lagoon |
| `artifact-home-links-open-in-app` | #4482 | android, ios | changed | full | accepted | @Lagoon |
| `artifact-icon-file-code` | #4501 | android, ios | changed | full | accepted | @sindresorhus |
| `artifact-viewer-one-bar` | #4080 | android, ios | changed | full | accepted | @Lagoon |
| `claude-fallback-models` | #4494 | ios, android, desktop | changed | full | accepted | @Lagoon |
| `codex-limit-resets` | #4479 | android, ios, desktop | added | full | accepted | @Lagoon |
| `cold-start-store-off-main` | #4485 | android, ios | improved | full | accepted | @Lagoon |
| `composer-working-line` | #4477 | android, ios, desktop | changed | full | accepted | @Lagoon |
| `computer-card-create-agent` | #4498 | android, ios, desktop | added | full | accepted | @Lagoon |
| `computers-owner-and-offline-impact` | #4480 | android, ios, desktop | added | full | accepted | @Lagoon |
| `description-list-fit` | #4507 | android, ios | improved | full | accepted | @sindresorhus |
| `ds-clickable-new-sites` | #4490 | android, ios, desktop | changed | full | accepted | @sindresorhus |
| `failed-send-kept-after-server-switch` | #4491 | android, ios, desktop | fixed | full | accepted | @Lagoon |
| `ios-forward-gesture-works` | #4463 | ios | fixed | full | accepted | @Lagoon |
| `ios-native-nav-polish` | #4471 | ios | improved | full | accepted | @Lagoon |
| `lounge-sidebar-row` | #4506 | android, ios, desktop | improved | canary | accepted | @Lagoon |
| `math-emoji-crash` | #4497 | ios, android, desktop | fixed | full | accepted | @Lagoon |
| `message-menu-no-system-callout` | #4460 | ios, android | fixed | full | accepted | @Lagoon |
| `native-feedback-tickets` | #4483 | android, ios | improved | full | accepted | @Lagoon |
| `optional-marker-no-space` | #4504 | android, ios | fixed | full | accepted | @sindresorhus |
| `question-card-hint-after-try` | #4467 | ios, android | changed | full | accepted | @Lagoon |
| `question-card-redesign` | #4469 | ios, android | changed | full | accepted | @Lagoon |
| `recents-preview-app-background` | #4486 | android | fixed | full | accepted | @Lagoon |
| `selected-text-reply-open-crash` | #4509 | android | fixed | full | accepted | @Lagoon |
| `server-pages-three-columns` | #4500 | android, ios | changed | full | accepted | @sindresorhus |
| `server-switcher-menu` | #4464 | android, ios, desktop | improved | full | accepted | @Lagoon |
| `settings-search` | #4470 | ios, android, desktop | added | full | accepted | @Lagoon |
| `side-search-keeps-focus` | #4511 | ios, android, desktop | fixed | full | accepted | @Lagoon |
| `smaller-feedback-diagnostics` | #4465 | android, ios | improved | full | accepted | @Lagoon |
| `sync-cursor-two-strikes` | #4476 | android, ios, desktop | fixed | flag | accepted | @Lagoon |
| `sync-guest-cursor` | #4473 | android, ios, desktop | fixed | flag | accepted | @Lagoon |
| `task-backlog-status` | #4474 | android, ios, desktop | added | full | accepted | @Lagoon |
| `task-older-messages-kept-offline` | #4492 | android, ios, desktop | improved | full | accepted | @Lagoon |
| `top-bar-items-mouse` | #4502 | android, ios, desktop | fixed | full | accepted | @sindresorhus |
| `touch-hit-areas` | #4503 | android, ios | fixed | full | accepted | @sindresorhus |
| `unresolved-mentions-notice` | #4481 | android, ios, desktop | added | full | accepted | @Lagoon |
| `web-pages-stall-retry` | #4488 | ios, android, desktop | fixed | full | accepted | @Lagoon |

## Pending

| Item | PR | State | Notes |
|---|---|---|---|
| Feedback goes through the Raft server; new feedback form | #4496 | accepted once merged (merging now) | Changeset required before acceptance (§2). |
| iOS multi-window, or the #4505 stop-gap | #4508 / #4505 | proposed | Decision pending Jony's iPad re-verify. Exception owner: @artin. |

## Exceptions

- #4508 / #4505: entry after scope lock only with artin's recorded decision (owner @artin; lifts when one of the two is accepted or both are removed).
