# Slock App State First Principles

This document is the app-wide state contract for the KMP/shared shell. It is
not a Thread-only guideline. Home, Thread, Search/Saved, Tasks, Members,
Settings, auth/session, realtime, and platform capability surfaces should all
follow the same rules.

Implementation helpers and the current KMP state-library survey live in
`docs/shared-mvi-foundation.md`.

Status note, 2026-06-27: this file is a normative first-principles contract,
not a live task-status report. For the current shared-store inventory and which
stores use `MviStoreRuntime` directly, read `docs/shared-mvi-foundation.md`.
Older dated audits can be historical context only.

## Goal

The app should feel stable even while network, realtime, local cache, markdown,
attachments, presence, summaries, and platform bridges update in the background.
The user should see a deterministic screen state first, then quiet incremental
updates. They should not see whole pages rebuild, jump, flicker, or slowly
"spit out" rows because backend enrichment or local write-through ran again.

## Non-Negotiable Rules

1. **One canonical app state per server/session**
   - A selected server/session owns one canonical state graph.
   - UI screens render projections of that graph; they do not own duplicate
     business snapshots.
   - Switching server/session is the only normal reason to discard the whole
     graph.

2. **Entities are normalized before UI state**
   - Store messages, channels, members, agents, tasks, files, and settings as
     keyed entities.
   - Route state keeps ordered ids, cursors, selected ids, visibility flags,
     pending actions, and error/effect state.
   - Do not copy the same full message/member/channel object into multiple
     route snapshots unless it is a final UI projection.

3. **Reducers own mutation; UI only emits intents**
   - UI may keep ephemeral interaction state such as text focus, menu open, or
     local draft text while editing.
   - Business state, cache state, loading state, action pending state, and
     error/success feedback must live in commonMain stores/reducers.
   - Android/iOS hosts provide capabilities only: storage, network, router,
     notification, file picker, image preview, safe-area, and system APIs.

4. **No-op writes must be skipped before state emission**
   - Repository/write-through/realtime layers must compare entity version,
     sequence, updated timestamp, status, and relevant payload fields before
     emitting.
   - If data is unchanged, log a skip reason and do not call the reducer with a
     new full snapshot.
   - Repeated heartbeat, presence echo, same thread payload, and summary
     enrichment misses must not trigger whole-page recomposition.

5. **Refresh is patch-first, not reload-first**
   - Realtime and background refresh should apply targeted patches to canonical
     entities.
   - Full snapshot reload is allowed only for cold start, explicit user refresh,
     selected server/session switch, or a confirmed data gap.
   - A background refresh must preserve already-rendered content and should not
     set visible global loading.

6. **Initial render has a hard budget**
   - A route must render cached/stable shell content first.
   - Expensive enrichment, members/agents joins, summaries, files, markdown, and
     derived metadata run after first content unless required for correctness.
   - Initial Thread timeline should request/render the latest configured window
     only. Current default: latest 10 messages.

7. **Selectors must be stable and narrow**
   - UI collects route selectors, not the whole app graph.
   - Selectors return persistent/structurally stable lists when ids and visible
     entity versions did not change.
   - Updating one member presence must not rebuild every Home row or every
     Thread message row unless that row displays that member and the visible
     field changed.

8. **Concurrency is queued by key**
   - Mutations for the same entity key or route key are serialized or coalesced.
   - Newer responses win over older responses using request id, sequence, or
     monotonically increasing generation.
   - Duplicate in-flight requests for the same key should share a result or
     collapse to one active job.

9. **Main/UI thread is render-only**
   - Parsing, normalization, diffing, markdown parsing, attachment metadata
     mapping, and large JSON transforms should not block the UI thread.
   - Store reducers must do small immutable updates. Heavy work is prepared in
     repositories/data sources or background dispatchers, then reduced as a
     small patch.
   - Kuikly/Compose rendering should receive final display models, not run
     expensive joins during composition.

10. **Every state transition is observable**
    - High-risk data paths need structured logs with route key, entity count,
      changed count, skipped reason, request source, and elapsed time.
    - Logs must not include tokens, Bearer headers, passwords, raw account data,
      or sensitive message body content.

## Architecture Shape

### Canonical Store

Use one app-level state graph per server/session:

```text
SlockAppState
  session: AuthSessionState
  selectedServer: ServerId
  entities:
    channelsById
    conversationsById
    messagesById
    membersById
    agentsById
    tasksById
    filesById
    settingsByServerId
  routes:
    home
    threadById
    search
    saved
    tasks
    members
    settings
  effects:
    pending navigation / toast / capability effects
```

Existing shared stores can remain split by route while migrating, but their data
flow must move toward this model: canonical entities first, route projections
second, UI display models last.

### Data Pipeline

```text
network / local cache / realtime
  -> parse DTO
  -> normalize entities
  -> diff against canonical state
  -> reduce changed entities + route cursors
  -> selectors build stable display models
  -> UI renders
```

Avoid this anti-pattern:

```text
network / local cache / realtime
  -> build full route payload
  -> write full route payload
  -> UI receives whole new list
  -> enrichment builds another full route payload
  -> UI receives whole new list again
```

## Refresh Categories

| Category | Visible loading | Allowed data shape | Examples |
| --- | --- | --- | --- |
| Cold start, no cache | Yes | Minimal shell, then first snapshot | First app launch |
| Cold start, cache exists | No global loading after shell | Cached content first, network patch later | Opening Home/Thread offline |
| Route return/resume | No | Background patch only | Thread -> Home |
| User pull/explicit refresh | Yes, local affordance only | Snapshot or patch | Home pull refresh |
| Realtime event | No | Patch only; full reload only on gap | message:new, unread, presence |
| Server/session switch | Yes | New graph | User changes workspace |

## Thread First Implementation Plan

Task #108 already reduced Thread initial payload pressure:

- Initial and paging request size is configurable through
  `SharedThreadPaging.initialPageSize`.
- Current default is 10 latest messages.
- Cached 50-message snapshots are trimmed to latest 10 before first render.
- Older messages remain available through `canLoadMoreBefore`.

Task #109 should not just tune the number. It should make Thread state stable:

1. **Normalize timeline entities**
   - Store `messagesById`, `threadsByParentMessageId`, `messageOrderByThreadId`,
     `tasksById`, `attachmentsById`, `membersById`, `agentsById`.
   - Thread route state stores only `threadId`, visible message ids, cursors,
     pending send ids, selected panel, and load/action states.

2. **Diff `write_thread` by ids and versions**
   - Compare visible message ids, last seq, task ids/versions, reply summary
     versions, attachment versions, and relevant channel/thread metadata.
   - If unchanged, log `write_thread_skip reason=unchanged`.
   - If only reply summary changes, reduce a summary patch, not a full
     10-message payload.

3. **Move enrichment behind stable selectors**
   - Members/agents enrichment must not block first content.
   - Summary joins should produce small `ThreadSummaryPatch`.
   - Missing summary/member data should render a deterministic placeholder and
     patch the affected rows only when available.

4. **Keep pagination independent**
   - Initial window: latest 10.
   - Load older: append/prepend one configured page without changing current
     visible anchor.
   - New messages: append/patch only if the user is at bottom; otherwise update
     unread/new-message affordance.

5. **Add regression logs/tests**
   - Same thread + same messages + same seq does not emit a new route state.
   - Summary-only update changes only summary selector output.
   - Member presence update changes only rows that display that member.
   - Opening a cached thread with 50 stored messages renders 10 first and keeps
     older pagination available.

## App-Wide Follow-Up Plan

After Thread proves the model, apply the same pattern in priority order:

1. **Home**
   - Normalize channels, DMs, unread counts, presence, and server metadata.
   - Realtime presence/heartbeat must be no-op skipped before Home selectors.
   - Route return/resume stays silent unless user explicitly refreshes.

2. **Search/Saved**
   - Search results and saved items should be keyed by entity/result id.
   - Pagination adds result ids; unsave/search filter changes patch only the
     affected result set.

3. **Settings**
   - Server/account/billing snapshots remain repository-backed, but action
     state is keyed by action and entity id.
   - Mutations patch settings entities and show confirmation/effects without
     rebuilding unrelated settings pages.

4. **Members/Agents**
   - Member/agent detail should share canonical member/agent entities with
     Home, Thread, Settings Computers, and DMs.
   - Presence and lifecycle changes patch one entity, not every route payload.

5. **Tasks**
   - Task status mutations update task entities optimistically with rollback.
   - Thread task chips, global Tasks, and channel Tasks all select from the same
     task entity store.

## PR Review Checklist

Before accepting state-affecting code:

- Does the UI emit intents instead of reading/writing repository/cache directly?
- Does the code normalize entities before route/display state?
- Can it skip unchanged payloads before emitting state?
- Does it preserve existing content on background refresh/error?
- Does it avoid full snapshot reload for realtime events?
- Does it keep heavy parsing/diffing/enrichment off the UI thread?
- Are selectors stable enough that unrelated entity changes do not rebuild the
  whole screen?
- Are request races gated by key/generation?
- Are logs sufficient to prove changed count vs skipped count without leaking
  sensitive data?

## Current Decisions

- Initial Thread page size is 10, not 50.
- The page size must stay configurable through shared constants/config, not
  buried inside one API call.
- `write_home_realtime` no-op skip and `write_thread` unchanged skip are
  necessary but not sufficient; the long-term fix is canonical normalized state.
- Reverse/bottom-anchored LazyList is a rendering capability and should not be
  used to mask unstable data-state churn.
