# Reading a Quiver feedback diagnostics zip (Raft mobile)

When a user submits feedback in the app, the app bundles its **local**
diagnostics (a JSONL ring buffer it writes itself) into a
`slock-feedback-*.zip` and uploads it as a Quiver feedback **attachment**.
Quiver only transports and stores that zip — it does not define or parse the
contents. This doc is the source of truth for that archive's layout; the
app/shared layer owns the format and it may change here.

## Getting the zip from a feedback id

Quiver delivers the raw attachment. Given a feedback id (a full UUID or a short
prefix both work):

```bash
# 1. inspect the ticket — device context + attachment ids
quiver feedback show raft-android <ticketId>

# 2. download the raw attachment (bytes as-is; Quiver does not unzip)
quiver feedback download-attachment raft-android <ticketId> <attachmentId> -o diag.zip

# 3. unzip locally
unzip diag.zip -d diag/
```

(Retrieval details — auth, REST equivalents, short-id resolution — live in the
Quiver docs: `/docs/agent-cli-feedback`. Everything below is app-owned.)

## Archive layout

```
slock-diagnostics-summary.txt      human-readable environment/session summary
app_logs/
  slock-diagnostics.jsonl          current app log (JSONL); rotations .1 .. .N
  recent-memory.jsonl              recent in-memory events
```

`slock-diagnostics-summary.txt` is the first thing to read — it pins down the
build and device:

```
App: Raft         Version: 1.0.4   Build: 1000400   Source: <commit>
Package: build.raft.app            Platform: Android 12 / SDK 31
Device: HUAWEI SGT-AL10            Session: Signed in / <user> / <server>
App JSONL log: enabled             (App-owned diagnostics JSONL)
```

## Log line format

Each `app_logs/*.jsonl` line is one JSON object:

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
