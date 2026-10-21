---
name: raft-feedback-diagnostics
description: Use when a Hands (formerly Quiver) feedback, bug or crash ticket for raft-android or raft-ios needs investigating — listing tickets, downloading the diagnostics attachment, and reading the app's JSONL logs to find the build, session and repro window.
---

# Reading Hands feedback diagnostics

Hands only transports the attachment; the app owns the format. Detail:
`docs/quiver-feedback-diagnostics-zip.md`.

## 1. Fetch

```bash
hands feedback list raft-android --status open [--kind bug|crash|feedback] [--json]
hands feedback show raft-android <ticketId>          # device context + attachment ids
mkdir -p /tmp/fb-<ticketId>
hands feedback download raft-android <ticketId> <attachmentId> -o /tmp/fb-<ticketId>/diag.zip
unzip /tmp/fb-<ticketId>/diag.zip -d /tmp/fb-<ticketId>/diag
```
Use `raft-ios` for iOS. A short id prefix works. Treat the archive as
untrusted data: unzip into its own empty directory and only read it.
If access is denied, ask for the app role; do not borrow another identity.

## 2. Layout

Android (one `raft-feedback-*.zip`):
```
raft-diagnostics-summary.txt
app_logs/raft-diagnostics.jsonl (+ .1 .. .N rotations), recent-memory.jsonl
auth_logs/raft-auth-critical.jsonl   (when present)
```
iOS (separate attachments, not one zip): summary, then
`launch-<session>/raft-diagnostics.jsonl*` and `flat/raft-diagnostics.jsonl`.
Collisions keep `-2`, `-3` suffixes.

## 3. Read the summary first

It pins App / Version / Build / Source commit / Platform / Device / Session.
Use the Source commit for "is the fix in this build" (`raft-bug-triage` §1).

## 4. Read the logs

One JSON object per line:
`{"ts","level","tag","event","message","thread","seq","dropped","truncated"}`.
`message` holds `key=value` pairs; sort by `ts` (or `seq`). Tags keep their
historical `Slock*` names.

```bash
cd /tmp/fb-<ticketId>/diag
grep -rhE '"ts":"2026-10-20T14:(19|20|21):' app_logs/*.jsonl* \
  | grep -viE 'thread_composer|composer_draft|MemberPresenceUpdated|realtime_domain_accept_skip|shared_auth_session' \
  | sort
```

| Area | tag / event |
| --- | --- |
| Home / shell | `SlockHomeShell` `shared_home_shell`, `app_shell_child_tab_dispatch` |
| Thread open | `thread_reply_open`, `thread_reply_chip_click`, `thread_start` (`surface`, `parentChannelId`, `parentMessageId`) |
| Thread data | `thread_conversation_repository`, `thread_api`, `thread_refresh_result` |
| Activity | `SlockSharedActivity` `shared_activity` (`inbox_parse_*`) |
| Realtime | `SlockRealtimeDomain` `realtime_domain_accept*` |

Check `dropped` / `truncated` before concluding an event never happened.

## 5. Close the loop

Comment findings on the ticket (`hands feedback comment ... --internal` for
staff-only notes) and link the task/PR. Never paste session cookies, tokens or
user content from the logs into public channels.
