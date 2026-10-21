# Kuikly Pager Boundary Spec for Thread

## Status

> **Historical:** Kuikly-era spec. Kuikly UI and the HarmonyOS (OHOS) app were
> since removed (#3192, #3181); OHOS mentions below are kept for history.

Post-cleaned after task #203.

The mobile Thread launch-boundary work described here is implemented on local
`main`: app-shell openers route to standalone `compose_thread`,
`compose_thread` accepts only `WireThreadLaunchPayload` in production, the
app shell owns realtime/session lifecycle, and the former embedded Thread hot
path has been deleted. This document now records the contract, implementation
evidence, and the follow-ups that are deliberately outside the boundary
migration.

## Problem

Slock currently uses a large shared app-shell route model inside one Kuikly
Pager/ComposeContainer. Home, Tasks, Members, Settings, and embedded Thread
states are coordinated inside one retained shell. This matches the React mental
model, but it is not the cleanest Kuikly page boundary.

Recent Thread traces show the remaining first-open cost is dominated by Android
host traversal/layout/draw after we already moved obvious shared work out of the
first frame:

- Home -> `#all`, after `40802fe8`: `thread_first_render=179ms`,
  `thread_payload_ready=134ms`, `thread_first_frame=80ms`.
- Tasks -> Thread, after `40802fe8`: cached/content leg improved to
  23/31ms first-render frames, 48ms payload-ready, 58ms first-frame, but the
  initial origin-loading placeholder still had 250ms host traversal.
- Perfetto before this patch already pinned the hot path to
  `Choreographer -> traversal -> slock_kuikly_container_layout -> RV OnLayout`,
  not repository/network.

This suggests the next architectural question is not "can we split another
Composable", but whether Slock's page boundary is too coarse for Kuikly.

## Kuikly Model

The relevant Kuikly references point to these constraints:

- `references/KuiklyUI/docs/DevGuide/pager.md`: `Pager` is the Kuikly page
  entry class, similar to Android Activity or iOS ViewController.
- `references/KuiklyUI/docs/DevGuide/open-and-close-page.md`: navigation
  between pages is modeled through `RouterModule.openPage(...)` and
  `RouterModule.closePage()`.
- `references/KuiklyUI/docs/DevGuide/page-data.md`: each page gets
  `PagerData`, with business params passed from Native or the opener.
- `references/KuiklyUI/docs/DevGuide/multi-page.md`: dynamic pages are data
  isolated; cross-page coordination should use PagerData and notifications,
  not shared global observable state.
- `references/KuiklyUI/docs/DevGuide/pager-event.md`: the host can send page
  events through the Kuikly render delegator.
- `references/KuiklyUI/docs/API/components/tabs.md`: Kuikly supports tabs as
  UI components inside a page, paired with page/list scrolling primitives.

Therefore, yes: a Kuikly Pager can contain a tabbed UI. But that does not mean
one Pager should own every app route, every retained primary tab, and every
heavy detail surface. Tabs inside a Pager are appropriate for tightly related
views in one page. App-wide navigation and heavy detail pages should be treated
as page-boundary candidates.

## Post-Clean Slock Shape

Current Android host:

- `RaftLegacyHostActivity` owns one `KuiklyRenderViewBaseDelegator`.
- `onCreate` attaches one native container via
  `kuiklyRenderViewDelegator.onAttach(container, "", spec.pageName, pageData)`.
- `RaftTraceFrameLayout` wraps the host container and records
  `measure`, `layout`, and `dispatchDraw`.

Current shared shell:

- `RaftHomeComponents.kt` contains `ComposeRaftHomePage` and the structural
  home shell with bottom-tab state.
- The shell carries retained Home/Tasks/Members/Settings UI and owns the
  session-level realtime connection.
- Thread is no longer a retained embedded shell route on the mobile hot path.
  Shell openers call `RouterModule.openPage(THREAD_PAGE, pageData)` with
  `WireThreadLaunchPayload`.
- Standalone Thread construction still coordinates route state, member
  suggestions, composer draft, files/tasks side stores, rich message rendering,
  and modals, but those concerns are inside the Thread Pager boundary.

Thread now behaves like a Kuikly page with its own lifecycle rather than an
internal tab/detail state inside a large app surface.

## Architecture Hypothesis

Slock is likely misusing Kuikly by keeping too much app-shell state inside one
Pager. The more Kuikly-aligned model is:

- One Pager for the app shell / primary tab surface.
- Thread as its own Pager/page boundary, opened with a lightweight route key.
- Page params contain identity and launch intent, not full UI payloads.
- Durable product state is loaded through shared stores/repositories after the
  Thread Pager starts.
- Cross-page coordination uses store state, persisted state, notifications, or
  pager events, not global observable UI state.

This matches our existing launch-contract direction: route payloads should not
carry raw response blobs or full mutable UI state.

## Proposed Target

### Page Boundaries

Keep one app-shell Pager for:

- Home sidebar/list.
- Primary bottom tabs where the content is light enough and naturally part of
  the shell: Home, Tasks overview, Members overview, Settings overview.
- Global overlays that semantically belong to the shell.

Move to a separate Thread Pager for:

- Channel Thread.
- Reply Thread.
- Task Thread.
- Any conversation/detail surface that has a distinct lifecycle, heavy list,
  composer, attachments, markdown/action cards, and route-specific side stores.

Thread opening should use `RouterModule.openPage(THREAD_PAGE, params)` or the
host equivalent. The current `ThreadPage.kt` already has a standalone page
entry, so the migration is mostly about removing the app-shell embedded Thread
path from the hot route.

### Route Params

Thread PagerData should contain only:

- route identity: `serverId` / `serverSlug`, `channelId` or `threadId`,
  `streamChannelId`, `parentChannelId` / `parentMessageId`.
- launch intent: `surfaceKind`, selected tab, focused message id, origin parent
  message id, back label.
- host context: safe-area/time-format/local-time fields, debug/render flags.

Thread PagerData must not contain display, capability, or product-state hints:

- no title/subtitle/channel kind/private/joint label.
- no DM peer name/avatar/gravatar/header projection.
- no joined/canSend/canAttach/canCreateTask/member count/member candidates.
- no settings actions, composer state, files payload, messages, tasks,
  attachments, markdown/action-card state, or repository response body.
- no empty/loading copy overrides.

Thread must fetch/observe the real payload through:

- `SharedThreadStore`.
- repository/data-source.
- local fallback cache.
- realtime events.

Current guard:

- Production `compose_thread` launch is parsed through `WireThreadLaunchPayload`
  only.
- Full `RaftThreadScreenPayload` route payloads are rejected in production
  parser tests.
- `WireThreadLaunchPayload` descriptor tests enforce the whitelist above.

### Realtime Ownership

Phase 1 must keep one realtime owner for a session/server. The app shell remains
the owner of:

- realtime connection lifecycle.
- auth refresh requests.
- cursor policy.
- route-level join requests.
- fan-out of decoded `RealtimeEvent`s.

Standalone Thread Pager instances must not open a second realtime connection for
the same session/server. They receive events through a pager-event/notification
relay from the app shell or host, keyed by server id and thread route identity.

The proof must validate:

- channel message created/updated/deleted events reach the active standalone
  Thread Pager.
- reaction and saved-state updates reach the active standalone Thread Pager.
- task created/updated/deleted events reach task-bearing Thread views.
- auth refresh still happens once at the shell/session owner.
- route join is idempotent: opening the Thread Pager does not double-connect or
  double-apply the same realtime event.

### Tab Model

Do not model primary app tabs as separate Kuikly Tabs unless the screen is
semantically one page. For app navigation:

- Bottom tabs are shell navigation.
- Kuikly `Tabs`/`PageList` is for in-page segmented views, for example Thread
  Chat/Tasks/Files if we keep them in the same Thread page.
- If a tab's content is heavy and lifecycle-distinct, it should be considered
  for a Pager boundary or a lazily mounted sub-surface, not always-retained
  hidden UI.

### Thread First Screen Contract

The Thread Pager first screen must remain small:

- Header/title.
- Loading/empty/content shell.
- First viewport message list only.

Everything else is delayed or lazy:

- composer
- markdown rich body upgrade
- action cards
- attachment comments
- quoted previews
- task join
- files tab
- task tab
- modals/action sheets

The current `40802fe8` patch is a mitigation within the existing architecture;
this spec is the cleaner boundary.

This first-screen contract is not part of Phase 1 measurement unless explicitly
called out. Phase 1 measures the Pager boundary with Thread internals kept as
close as practical to the embedded path. Composer/action-card/files/task lazy
mounting is a later phase so performance attribution stays clean.

## Post-Clean Implementation Status

| Area | Status | Evidence |
| --- | --- | --- |
| Standalone route boundary | Done | `ef7f8a5d` routes Thread openers through standalone Pager; Home/Activity/Search/Saved/Tasks build `WireThreadLaunchPayload` and call `openPage(THREAD_PAGE, ...)`. |
| PagerData whitelist | Done | `3a5d728a` hardens launch payload boundary. `parseThreadScreenPayload()` now accepts only `WireThreadLaunchPayload` and returns `payload_parse_failed` for full content payloads. `ThreadPayloadTest` descriptor guards exclude `messages`, `tasks`, `members`, `filesPayload`, `composer`, display, and capability fields. |
| Realtime ownership | Done | `dd462520` relays shell realtime events into the standalone Pager; `05721047` keeps auth/session ownership in the shell/auth gate; Thread applies forwarded events through `SharedThreadIntent.ApplyRealtimeEvent` and does not create a second connection. |
| First-frame title/loading shell | Done | `87fae368` restores canonical standalone channel titles; `bc5fa8a6` keeps the standalone shell stable while canonical content loads. Header first-frame data comes from guarded store/candidate context, not full route payload. |
| Embedded Thread hot path | Done | `68764ac9` deletes the app-shell embedded Thread route; `977760e1` removes embedded Thread realtime residue. Mobile Thread navigation now uses the standalone Pager path. |
| Thread tab state isolation | Done for launch-boundary scope | `d6e74e4d`, `05afc7b7`, and `69a1dd5e` move Tasks, Files, and Composer store collection into their tab/subroute surfaces. The remaining route-level Tasks join logger is diagnostics-only. |
| Numeric performance proof | Follow-up | Field smoke after the boundary work accepted the direction, but formal trace artifacts remain tracked separately by task #207. This is not a blocker for the launch-boundary contract. |
| Future two-pane/tablet mode | Not implemented | The mobile hot path intentionally has no embedded Thread. Any two-pane mode needs a separate explicit lifecycle contract before reintroducing a visible second surface. |

## Migration Plan

### Phase 0: Document and Freeze the Contract - Done

- `40802fe8` remained a short-term mitigation while the boundary proof landed.
- This spec is now the canonical launch-boundary contract for Thread.
- The embedded Thread path was removed rather than extended.

### Phase 1: Standalone Thread Pager Proof - Done

Home/Tasks/Activity/Search/Saved open Thread as a standalone Pager instead of
embedded shell content.

Requirements:

- Use the existing `ComposeThreadPage` / `THREAD_PAGE` entry. Done.
- Pass only route identity in PagerData. Done.
- Keep `WireThreadLaunchPayload` as a hard whitelist. Done; descriptor tests
  reject display/capability fields such as `title`, `dmPeerName`,
  `membersCount`, `joined`, `canSend`, `settingsActions`, `emptyTitle`,
  `messages`, `tasks`, `members`, `filesPayload`, and `composer`.
- Keep Thread internals materially unchanged from the embedded path for this
  proof. Done for the boundary commits; tab cleanup landed afterward as scoped
  internal isolation.
- Preserve auth/session/realtime behavior through the existing shared stores.
  Done.
- Use the realtime ownership contract above: shell/session owns the connection,
  standalone Thread receives routed events and does not create a duplicate
  connection. Done.
- Close with `RouterModule.closePage()`. Done.
- No new Android multi-container architecture. Done.
- Task Thread launch must pass task/thread identity and focused intent only.
  The opener must not prefetch a display payload or pre-create UI state just to
  fill the Thread Pager header. Thread Pager/store resolves create-or-open,
  auth-required, and canonical content after launch. Done.

Measurements:

- Home -> `#all`: field smoke accepted the boundary direction after the title
  and loading-shell fixes.
- Tasks -> Thread with only task/thread identity launch params: implemented.
- Reply Thread from long-press/Open Thread: implemented, with later reply-route
  fixes handled as focused follow-ups.
- Back/close correctness: implemented through `RouterModule.closePage()`.
- Composer send path: preserved inside the standalone route.
- Attachment picker path: preserved inside the standalone route.
- realtime event delivery and duplicate-connection check: implemented in the
  relay/auth-gate work; formal trace proof remains task #207 follow-up.

Acceptance:

- `thread_first_render`, `thread_payload_ready`, and `thread_first_frame`
  improve or stay no worse than the embedded path: accepted by field smoke;
  formal trace capture remains task #207.
- Android host `layout`/`dispatchDraw` for Thread first content should drop
  meaningfully from current traces: task #207 follow-up.
- No duplicate realtime subscriptions/connections and no duplicate event apply:
  done by shell-owned realtime plus standalone relay.
- No lost Home/Tasks state on return: done by retaining Home/Tasks in the shell
  while Thread uses standalone router back/close.
- No stale badge/presence regression: done by shell-owned realtime projection.
- No product display/capability state appears in `compose_thread` PagerData:
  done and guarded by tests.
- No preloaded Thread messages/tasks/files/composer/settings/member payload
  appears in `compose_thread` PagerData: done and guarded by tests.

### Phase 2: Remove Embedded Thread Hot Path - Done

- Thread openings route through the Thread Pager by default.
- The mobile embedded Thread path has been deleted.
- Two-pane/tablet mode is not implemented. If needed later, it must be designed
  as an explicit second visible surface with its own lifecycle contract, not by
  restoring an accidental retained hidden route.

### Phase 3: In-Page Thread Tab Cleanup - Done for State Isolation

Inside the Thread Pager:

- Keep Chat as the default mounted tab. Done.
- Collect Tasks/Files only inside their selected tab surfaces. Done.
- Collect Composer draft state inside `ThreadComposerRoute`. Done.
- Use Kuikly `Tabs` semantics only for the Thread page's local Chat/Tasks/Files
  switch, not for app-wide navigation. Done.

The broader "first screen stays small" work continues as normal Thread
performance work: markdown rich-body upgrades, action-card staging, attachment
comment surfaces, and additional lazy/render pruning should remain separate
follow-up tasks so they do not reopen the launch-boundary decision.

## Risks

- Pager separation can duplicate store observers if stores are scoped to page
  instances instead of shared app/session services.
- PagerData misuse can reintroduce full route payloads.
- Realtime may connect twice if both shell and Thread page own connection.
- Back behavior can diverge between Android/OHOS/iOS if router semantics are
  patched only on Android.
- Two-pane mode needs a separate design; standalone mobile Pager should not
  block future two-pane.

## Non-Goals

- Do not introduce multiple Android host containers as the first fix.
- Do not rewrite the whole app shell.
- Do not pass full Thread payloads through routes.
- Do not use global observable state to share cross-page UI state.
- Do not assume Kuikly is inherently slow based on Slock's current shell shape.

## Decision Gate

Decision recorded: mobile Thread belongs behind a standalone Kuikly Pager
boundary, and app-shell embedded Thread should stay deleted.

Do not start a multi-container or native-host isolation experiment for this
problem unless a fresh trace shows the standalone Pager still bottlenecks in
host container traversal after Thread-internal render work.

Future performance work should optimize inside the Thread Pager first. Future
two-pane work should get its own explicit lifecycle and realtime contract before
any embedded/second-surface route is restored.

## Future Two-Pane Contract (task #17)

This section is the decision gate for Desktop view and adaptive list-detail
work. It permits a second visible Thread surface without undoing the standalone
Pager boundary above.

### Architecture Decision

- The AppShell Pager remains the session, auth-refresh, realtime-connection,
  selected-server, and top-level window owner.
- A visible conversation detail is a separate `THREAD_PAGE` child Pager hosted
  through `RaftChildPage`. The parent must not call
  `SharedThreadRouteContent` directly or restore an embedded Thread route.
- Parent-to-child launch data remains `WireThreadLaunchPayload` plus shell
  scope. It contains route identity and intent only. Messages, header display
  data, permissions, members, files, tasks, composer state, and fallback UI
  copy remain forbidden.
- The parent owns pane layout and selection. The child owns Thread rendering,
  local navigation, composer focus, draft projection, scroll, and route-local
  error/loading/content state.
- Phase 1 rail, Desktop-view preference, and global sidebar chrome are outside
  this contract. This contract is shared by all later list-detail consumers.

The existing cross-platform child-Pager primitive is the baseline, not a new
native multi-window experiment: Android, iOS, and OHOS already implement
`SlockKuiklyPageView`, while `SlockKuiklyChildPageView` forwards parent Pager
events and sends balanced child visibility events.

### Identity-Only Pane Selection

The parent keeps a typed selection with only:

- shell instance id and server id;
- conversation/thread identity;
- optional parent channel/message identity for replies;
- optional focused message id and navigation intent;
- a monotonically increasing selection generation.

The selection must not contain an `InboxItem`, Home row, message snapshot,
Thread payload, title, avatar, permissions, draft, scroll position, or other
product state. An Activity/Home/Search/Saved/notification source first resolves
through the canonical conversation directory into the same
`HomeMessageOpenRequest` / `ThreadLaunchConversationIdentity` contract used by
standalone navigation. Only then may the parent install a pane selection.

Selection rules:

- list refresh, unread/read changes, reorder, and pagination do not replace or
  clear the selected identity;
- selecting a new identity increments the generation and retires the previous
  child lease before the new child becomes active;
- server switch, logout, membership removal, and terminal auth loss clear the
  selection before another server/session becomes visible;
- deleted/no-access/unavailable content is rendered honestly by the child
  store. The parent must not guess another row or keep stale display metadata;
- no selection in a wide layout renders an explicit empty detail pane. It does
  not auto-select row zero unless a surface-specific product contract says so.

### Parent and Child Lifecycle State Machine

The product state is one of:

1. `ListOnly` — no pane selection is active.
2. `Split(selection)` — list and selected child are both visible.
3. `DetailOnly(selection)` — a child selected while wide remains mounted when
   the window becomes compact, preserving its Pager, draft, focus owner, and
   scroll until Back returns to the list.
4. `Inactive(selection?)` — the parent Pager is not visible; no child is
   lifecycle-visible even if its identity is retained.

Transitions are driven only by parent visibility, adaptive layout, explicit
selection, Back, and terminal session/server changes:

- `ListOnly + wide row open -> Split(new selection)`.
- A fresh compact row open keeps the existing standalone `RouterModule`
  navigation behavior; it does not create a second retained child.
- `Split + compact resize/fold -> DetailOnly(same selection and same child
  instance)`; `DetailOnly + wide -> Split(same selection and child)`.
- `Split/DetailOnly + parent disappear -> Inactive(selection)`; reappearance
  restores the layout allowed by current window facts without synthesizing a
  new selection.
- Switching identities deactivates the old child before mounting/activating the
  new child. A late old-generation load, lifecycle, navigation, or realtime
  callback is ignored.

`RaftChildPage` must emit exactly one `didAppear` when
`parentAppeared && childActive` becomes true, and exactly one `didDisappear`
when it becomes false. Recomposition, duplicate window facts, or row updates
must not replay lifecycle events. A child with `active=false` is clipped,
non-interactive, excluded from accessibility traversal, owns no input focus,
and cannot apply route-local realtime events.

### Back and Nested Navigation

Back is resolved from the innermost visible owner outward:

1. Child-owned modal, menu, selection toolbar, tab subroute, reply/task detail,
   or other nested Thread route handles Back first.
2. At the child Thread root, Back emits a typed close-detail request carrying
   shell id, selection key, and generation. It must not close the AppShell or
   mutate an unrelated/newer selection.
3. In `DetailOnly`, root Back returns to `ListOnly`. In `Split`, root Back
   clears the detail and shows the explicit empty pane.
4. Only when no detail or shell-owned overlay remains may Back leave the
   current shell surface or close the AppShell.

Nested conversation opens use identity-only requests. A surface may replace
the current detail selection or push within the child Pager, but the choice
must be explicit and generation-fenced; it may not open a hidden third Thread
consumer or pass a rendered payload back through the parent.

### Realtime and Room Ownership

There is exactly one realtime connection/auth-refresh/cursor authority per
shell session and server: the AppShell owner. A child Thread is a projection
consumer, never a connection owner.

Multiple visible projections are expected to observe the same canonical event
(for example Home unread state and Thread messages). That is not duplicate
ingest. The following are forbidden:

- a child opening a second websocket or auth-refresh loop;
- two page-local sinks independently mutating the same canonical store for one
  event;
- an inactive/old-generation child applying route-local events;
- one consumer leaving a room that another visible consumer still needs.

Room membership therefore requires a shell/server-scoped lease registry keyed
by room id and a unique consumer token. It sends a join only on `0 -> 1`, sends
a leave only on `1 -> 0`, and rejects releases from an old selection generation
or server grant. The visible child acquires leases for
`activeRealtimeRoomIds()` after canonical route resolution and releases them on
deactivation, identity replacement, server/auth teardown, or disposal.
Heartbeat/gap recovery remains owned once per canonical Thread projection;
pane layout changes must not reset its cursor or apply an event twice.

### Resize, Draft, Scroll, Focus, and Hinge

- Wide/compact transitions retain the same selected child instance when the
  detail was already open, so canonical composer draft, unsent attachments,
  input focus, Thread tab, and scroll are not reconstructed from route data.
- Draft authority remains `SharedThreadComposerDraftPersistSlice`, keyed by
  canonical Thread identity. The pane parent never copies composer state.
- The list and detail have independent scroll owners. Reordering the list does
  not move the detail; detail scrolling does not mutate list position.
- In split mode the composer, IME insets, focus traversal, pointer hit testing,
  and accessibility semantics belong to the detail bounds only. No control may
  span or receive input through a folding hinge.
- Window facts are host layout facts, not route data. Duplicate resize/fold
  emissions reduce idempotently and must not remount the child.
- `posture=unknown` is an honest pre-settle host fact, not evidence that a
  second pane is safe. The effective product layout remains Compact while
  posture is unknown, even when width is above the wide threshold. It may
  upgrade only after an explicit settled posture arrives. That settle
  transition preserves any retained selection/child generation and must not
  create a wide-then-compact first-frame flash.

### Session and Failure Teardown

Every asynchronous child effect is fenced by
`(shellInstanceId, serverId, selectionGeneration)`.

On server switch, logout, account switch, membership removal, or terminal auth
loss, the parent deactivates the child, releases room leases, clears selection,
and only then exposes the successor server/session. Late callbacks from the old
tuple cannot restore selection, show a toast in the new server, write a draft,
or navigate.

Child load failure renders a retryable detail error while the list remains
usable. Retrying reuses the same identity and advances a load attempt, not the
selection generation. A terminal deleted/no-access result remains visible
until the user clears it or selects another row; it never falls back to stale
cached display content.

### Required Verification Before Product Use

Shared behavior tests must exercise public reducers/coordinators, not grep
production source:

- all `ListOnly`/`Split`/`DetailOnly`/`Inactive` transitions;
- unknown-posture first frame stays Compact and a later settled posture
  upgrades at most once without replacing the selection or child;
- duplicate window/lifecycle signals produce no duplicate child lifecycle;
- selection A -> B rejects late A load/navigation/realtime callbacks;
- read/unread/reorder/pagination preserve selection;
- server/auth teardown clears selection and leases before successor attach;
- room lease `0 -> 1` join and `1 -> 0` leave with two consumers;
- root Back generation-CAS behavior and nested-child-first Back ordering;
- launch serializer/parser guards continue excluding product content.

Each platform must then run the same real-component matrix:

- compact row open remains standalone;
- wide row open shows list + canonical child Thread;
- wide -> compact -> wide retains selection, draft, tab, and scroll;
- rapid A/B selection plus realtime and resize has no stale flash or duplicate
  message/reaction/task apply;
- logout/server switch/no-access/deleted/error-retry paths are honest;
- IME, hinge, safe areas, pointer bounds, keyboard navigation, and
  accessibility traversal stay pane-local.

Android verification covers resizable tablet, split screen, and a fold posture;
iOS covers iPad resize/rotation; OHOS covers large-window resize and fold-status
changes. A PASS on one host does not imply another. Preview-only, fixture-only,
or Android-only proof cannot enable Desktop view globally.

### Implementation Gate

No product list-detail task may mount a visible Thread child until all of these
exist:

1. host window-fact delivery for the target platform;
2. a pure pane lifecycle/selection reducer with conservative unknown-posture
   settling and the tests above;
3. generation-fenced child root-Back delivery;
4. shell/server-scoped realtime room leases;
5. identity-only child launch using the existing Thread parser guards;
6. at least one target-host real-component lifecycle/resize proof.

This gate deliberately allows platform rollout to proceed independently while
keeping the shared contract identical. It does not permit an Android-only
layout branch to become the shared default for iOS or OHOS.
