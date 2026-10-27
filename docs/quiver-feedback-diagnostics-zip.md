# Reading Quiver feedback diagnostics attachments (Raft mobile)

When a user submits feedback in the app, the app bundles its **local**
diagnostics (a JSONL ring buffer it writes itself) and uploads them as Quiver
feedback **attachments**. Android creates a
`raft-feedback-*.zip`; iOS returns a summary plus separate log attachment
paths. Quiver only transports and stores those bytes — it does not define or
parse their contents. The app/shared layer owns these formats.

## Getting the zip from a feedback id

Quiver delivers the raw attachment. Given a feedback id (a full UUID or a short
prefix both work):

```bash
# 1. inspect the ticket — device context + attachment ids
quiver feedback show raft-android <ticketId>

# 2. download the raw attachment (bytes as-is; Quiver does not unzip)
quiver feedback download-attachment raft-android <ticketId> <attachmentId> -o diag.zip

# 3. Android only: unzip locally
unzip diag.zip -d diag/
```

(Retrieval details — auth, REST equivalents, short-id resolution — live in the
Quiver docs: `/docs/agent-cli-feedback`. Everything below is app-owned.)

## Android ZIP layout

```
raft-diagnostics-summary.txt       human-readable environment/session summary
app_logs/
  raft-diagnostics.jsonl           current app log (JSONL); rotations .1 .. .N
  recent-memory.jsonl              recent in-memory events
auth_logs/
  raft-auth-critical.jsonl         bounded auth-critical journal when present
```

Android writes one `raft-feedback-*.zip`. Its summary is the first thing to
read — it pins down the build and device:

```
App: Raft         Version: 1.0.4   Build: 1000400   Source: <commit>
Package: build.raft.app            Platform: Android 12 / SDK 31
Device: HUAWEI SGT-AL10            Session: Signed in / <user> / <server>
App JSONL log: enabled             (App-owned diagnostics JSONL)
```

## iOS attachment layout

iOS prepares a snapshot directory and returns multiple attachment paths rather
than one ZIP. The first path is the summary; subsequent paths are sanitized log
copies grouped by launch session:

```
raft-feedback-<snapshot>/
  raft-diagnostics-summary.txt
  launch-<session>/
    launch-20261024T003012Z-raft-diagnostics.jsonl
    launch-20261024T003012Z-raft-diagnostics.jsonl.1
  launch-<newer-session>/
    launch-20261025T091500Z-raft-diagnostics.jsonl
  flat/
    raft-diagnostics.jsonl
```

Each launch-session log leaf is prefixed with `launch-<UTC yyyyMMddTHHmmssZ>-`
(derived from the session directory) so attachments from different launches are
distinguishable by name and sort oldest to newest. Readers must match
`*raft-diagnostics.jsonl*`, not a bare `raft-diagnostics.jsonl*` prefix. `flat/`
logs keep the bare name. Android keeps the `launch-<session>/` directory in the
zip entry path, so it has no duplicate-name issue and is unchanged.

Name collisions are preserved with stable suffixes such as
`raft-diagnostics-2.jsonl` and `raft-diagnostics-3.jsonl`; source bytes are
never overwritten. Quiver receives each returned path as an attachment.

Legacy HarmonyOS builds (1.14.x and earlier) used a flat
`raft-feedback-*.zip` layout; it is documented on the `release/1.14.*`
branches.

## Local format boundary

New clients generate, discover, package, rotate, and clear only the `raft-*`
paths described above. They do not scan, migrate, package, or delete files from
older local diagnostic layouts. Historical Hands attachments are immutable and
keep their original names and bytes; Hands does not rename them server-side.

Internal `Slock*` class names, log tags, and stable event/schema identifiers are
not part of this filename migration. Consumers must continue accepting the
existing tag/event vocabulary unless a separate versioned contract replaces it.

## Log line format

Each diagnostics JSONL line is one JSON object. On Android these live under
`app_logs/`; iOS uses the paths described above:

```json
{"ts":"2026-07-08T14:16:33.386+08:00","level":"info","tag":"SlockKuikly",
 "event":"thread_reply_open","message":"thread_reply_open action=message_chip parentChannelId=… parentMessageId=…",
 "thread":"main","seq":69749452934564,"dropped":0,"truncated":false}
```

`tag` groups by subsystem, `event` is the specific log point, and `message`
carries the `key=value` payload. Sort by `ts` (or `seq`) to reconstruct order.

## Triage tips

The log is high-volume; filter aggressively. Common noise: `thread_composer_*`,
`*_composer_draft_*`, `MemberPresenceUpdated` / `realtime_domain_accept_skip`,
`shared_auth_session` / `mmkv_auth_key`.

Useful tags/events by area:

| Area | tag / event |
| --- | --- |
| Home / shell state | `SlockHomeShell` `shared_home_shell` (`selectedConversation`, `selectedTopAction`), `app_shell_child_tab_dispatch` (tab switches) |
| Thread open / nav | `SlockKuikly` `thread_reply_open`, `thread_reply_chip_click`, `thread_start` (note `surface=thread` vs `surface=channel`, `parentChannelId`, `parentMessageId`) |
| Activity / inbox | `SlockSharedActivity` / `shared_activity` (`inbox_parse_*`) |
| Activity scroll-to-top | `SlockHomeActivity` `activity_scroll_to_top_start` (`source=title\|tab\|pill`, `fromIndex`), `_end` (landed `index`), `_cancel` / `_retire` (`reason`), `_stale`, `_hold_correct` (`via=change\|layout`, `fromIndex` the list was carried to) |
| Thread load / data | `thread_conversation_repository`, `thread_api`, `thread_refresh_result` |
| Realtime | `SlockRealtimeDomain` `realtime_domain_accept*` |

Example — extract a repro window, drop the composer/presence noise, sort by ts:

```bash
grep -rhE '"ts":"2026-07-08T14:(19|20|21):' diag/app_logs/*.jsonl* \
  | grep -viE 'thread_composer|composer_draft|MemberPresenceUpdated|realtime_domain_accept_skip|shared_auth_session' \
  | sort
```

For a thread-open bug, the tell is in `thread_start`: a correct in-context open
logs `surface=thread` with a real `parentMessageId`; an open that lands on the
outer conversation logs `surface=channel` with an empty `parentMessageId`.
