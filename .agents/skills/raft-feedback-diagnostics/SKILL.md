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
app_logs/launch-<id>/raft-diagnostics.jsonl[.N], recent-memory.jsonl
app_logs/feedback-selection.txt      (1.23+: which shards were attached / omitted)
auth_logs/raft-auth-critical.jsonl   (when present)
```
From 1.23 a report attaches at most 1 MiB (768 KiB from 1.25) of app logs, newest launch first (a
shard may be a tail that starts at a full line); older shards stay on the
device. iOS applies the same 1 MiB budget to its attachments.
iOS (separate attachments, not one zip): summary, then
`launch-<session>/launch-<UTC yyyyMMddTHHmmssZ>-raft-diagnostics.jsonl[.N]` and
`flat/raft-diagnostics.jsonl`. The leaf name carries the launch time, so names
are unique across launches and sort oldest to newest; glob
`*raft-diagnostics.jsonl*`, not a bare `raft-diagnostics.jsonl*`. Collisions
keep `-2`, `-3` suffixes.

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

From 1.23 routine high-frequency events are summarized
(`RaftDiagnosticsVolumePolicy`, shared): `RaftDiagnostics diagnostics_summary
key=<tag>:<event>[:<sub>] count= kept= suppressed= windowMs= sampleIds= distinctIds=`
replaces the lines past the first one(s) per key and minute. Duplicate realtime
skips, avatar, markdown preparse/recompose, viewport intents and per-request
network successes are not written at all unless Settings > Developer > Verbose
diagnostics was on; `diagnostics_verbose_off` carries their counts per minute.
Warnings, errors, failures, auth, sends, navigation, artifact viewer/WebView,
thread reads, activity, tasks and lifecycle lines are never summarized. An
absent routine line is therefore not proof it never happened: check the
summaries. Builds up to 1.22 wrote `diagnostics_coalesced` counts instead.

From 1.25: budget 768 KiB and shards older than 6 h are left out (except the
newest shard of the newest launch and of the one before); routine keys keep 2
lines/min; routine warn lines (`scroll_frames`, `ime_animation_end`, cancelled
requests) and per-keystroke composer lines are summarized, entries may carry
`{metric=min..max,last=v}`; info failures without a request/trace id keep 5/min
(`failed:<key>` in the summary). `app_logs/recent-memory.jsonl` is the last
3 minutes at full detail (summarized lines included; dedupe by `seq`). Details:
`docs/quiver-feedback-diagnostics-zip.md` § 1.25 writer.

## 5. Close the loop

Comment findings on the ticket (`hands feedback comment ... --internal` for
staff-only notes) and link the task/PR. Never paste session cookies, tokens or
user content from the logs into public channels.
