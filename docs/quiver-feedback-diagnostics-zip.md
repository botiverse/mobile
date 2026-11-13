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
  launch-<id>/raft-diagnostics.jsonl  app log (JSONL) per launch; rotations .1 .. .N
  recent-memory.jsonl              recent in-memory events (1.25+: last minutes at full detail)
  feedback-selection.txt           (1.23+) shards attached / omitted under the budget
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

## Size budget and summaries (1.23+)

From 1.25 the budget is 768 KiB (`RaftFeedbackDiagnosticsBudget.ReportBudgetBytes`,
`recent-memory.jsonl` included) and shards last written more than 6 hours before the
report are left out, except the newest shard of the newest launch and of the launch
before it (the end of a launch that crashed or was killed). The zip summary's
"Recent logs" section is now a pointer to `app_logs/` and `auth_logs/` instead of a
~120 KB copy of them (the Settings copy/share text still carries the copy).

1.23 and 1.24: a report attaches at most 1 MiB of raw app logs (`RaftFeedbackDiagnosticsBudget`):
the newest launch newest shard first, with a reserve for the end of the launch
before it; a shard that does not fit whole is attached as its tail from the first
full line. iOS applies the same byte budget to its attachment list. Before 1.23
every shard of the last five launches went (fb-c04c1199: 31 shards, 29 MB).

The writer also summarizes (`RaftDiagnosticsVolumePolicy`): routine
high-frequency events keep their first line(s) per key and minute and one
`diagnostics_summary key=… count=… kept=… suppressed=… sampleIds=… distinctIds=…`
line; duplicate realtime skips, avatar, markdown preparse/recompose, viewport
intents and per-request network successes are written only with Settings >
Developer > Verbose diagnostics on (`diagnostics_verbose_off` counts them
otherwise). Warnings, errors and failures are always written (1.23 and 1.24; see
below for 1.25).

### 1.25 writer

Measured on three 1.24.0 reports (fb-93123592, 7e198cab, 9cf9735f): the writer
still produced 400–700 lines (~250 KB) per active minute, so 1 MiB held only the
last 3–5 minutes. From 1.25:

- Unlisted routine info events keep **2** lines per key and minute (was 10); the
  rest go into the minute's `diagnostics_summary`.
- Per-keystroke composer lines (`thread_composer_change[_result]`,
  `thread_composer_draft_persist_save[d]`, 3/min), `thread_read_tail` (3/min),
  `app_shell_parent_home_start_check` / `app_shell_child_tab_render` (2/min),
  `tasks_realtime_ignore` (1/min) are summarized. Composer send lines
  (`thread_composer_send_*`), thread read start/result/skip and
  `app_shell_child_tab_dispatch` are still always written.
- Routine **warn** lines are summarized too: `scroll_frames` (3/min),
  `ime_animation_end` (2/min, `timedOut=true` always written) and request finishes
  with `kind=CANCELLED` (2/min). Other warnings, including slow requests and
  `nav_transition_frames` (navigation from/to), are always written.
- Info lines that read like a failure are always written when they carry a
  request/trace id (`clientRequestId=`, `requestId=`, `traceId=` …); without one,
  5 per key and minute, counted as `failed:<key>` in the summary. Fields that say
  "no failure" (`failedAttachments=false`, `errorBytes=0`, `error=none`) no longer
  make a line look like a failure.
- Summary entries carry timings where the rule names them:
  `<key>=<suppressed>/<seen>[<sampleIds>;<distinct>]{<metric>=<min>..<max>,last=<v>;…}`,
  e.g. `scroll_frames=14/17[…]{durationMs=180..1290,last=372;jankFrames=0..6,last=0;longestFrameMs=11..96,last=11}`.
  Up to 2 sample ids per key (was 3).
- `network_request_unfinished`: the 8 newest open requests per export, plus one
  `network_request_unfinished_more count=… listed=8 total=…` line for the rest.
- `recent-memory.jsonl` (Android) is the in-memory ring of the last 3 minutes at
  **full detail**: every line, including the ones summarized out of the shards
  (not the Verbose-diagnostics categories), newest kept first up to ~190 KB, whole
  lines only. Its lines repeat the newest persisted lines; dedupe by `seq` when
  merging with the shards. In heavy use (typing, scrolling) 190 KB is about the
  last 30–60 s; when quieter, the full 3 minutes.

Always written, never summarized: crash / ANR / system-kill / process-exit and
memory context, auth trail, errors, warnings outside the list above, sends,
navigation and route opens (`thread_start`, `thread_route_open`,
`thread_reply_open`, `home_open_*`, `conversation_open`, `nav_transition_frames`,
`app_shell_child_tab_dispatch`), lifecycle / background transitions, socket /
reconnect / push / notification, artifact viewer / WebView, feedback and
diagnostics lines. The build stamp (Version / Version code / Build / Source) is in
the summary file.

## Triage tips

The log is high-volume; filter aggressively. From 1.25, a routine line missing
from the shards may be in `recent-memory.jsonl` (last minutes) or counted in a
`diagnostics_summary` line. Common noise: `thread_composer_*`,
`*_composer_draft_*`, `MemberPresenceUpdated` / `realtime_domain_accept_skip`,
`shared_auth_session` / `mmkv_auth_key`.

Useful tags/events by area:

| Area | tag / event |
| --- | --- |
| Home / shell state | `SlockHomeShell` `shared_home_shell` (`selectedConversation`, `selectedTopAction`), `app_shell_child_tab_dispatch` (tab switches) |
| Thread open / nav | `SlockKuikly` `thread_reply_open`, `thread_reply_chip_click`, `thread_start` (note `surface=thread` vs `surface=channel`, `parentChannelId`, `parentMessageId`) |
| Activity / inbox | `SlockSharedActivity` / `shared_activity` (`inbox_parse_*`) |
| Activity top edge | `SlockHomeActivity` `activity_keep_top_pin` (`fromIndex` where the list would have been carried, `anchorTop`) |
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
