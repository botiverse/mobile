# Kuikly Pager Boundary Spec for Thread

## Status

Draft for review after task #202 Thread first-open profiling.

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

## Current Slock Shape

Current Android host:

- `SlockKuiklyActivity` owns one `KuiklyRenderViewBaseDelegator`.
- `onCreate` attaches one native container via
  `kuiklyRenderViewDelegator.onAttach(container, "", spec.pageName, pageData)`.
- `SlockKuiklyTraceFrameLayout` wraps the host container and records
  `measure`, `layout`, and `dispatchDraw`.

Current shared shell:

- `SlockHomeComponents.kt` contains `ComposeSlockHomePage` and the structural
  home shell with bottom-tab state.
- The shell has carried retained Home/Tasks/Members/Settings UI and embedded
  Thread route state.
- Thread construction still has to coordinate route state, member suggestions,
  composer draft, files/tasks side stores, rich message rendering, and modals.

This makes Thread look like an internal tab/detail state inside a large app
surface rather than a Kuikly page with its own lifecycle.

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
- Full `KuiklyThreadScreenPayload` route payloads are rejected in production
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

## Migration Plan

### Phase 0: Document and Freeze the Contract

- Keep `40802fe8` as the short-term mitigation.
- Add this spec and link future Thread launch changes to it.
- Do not add more heavy state to the embedded Thread path.

### Phase 1: Standalone Thread Pager Proof

Create a proof path where Home/Tasks opens Thread as a standalone Pager instead
of embedded shell content.

Requirements:

- Use the existing `ComposeThreadPage` / `THREAD_PAGE` entry.
- Pass only route identity in PagerData.
- Keep `WireThreadLaunchPayload` as a hard whitelist. Descriptor tests must
  reject display/capability fields such as `title`, `dmPeerName`,
  `membersCount`, `joined`, `canSend`, `settingsActions`, `emptyTitle`,
  `messages`, `tasks`, `members`, `filesPayload`, and `composer`.
- Keep Thread internals materially unchanged from the embedded path for this
  proof. Do not bundle composer/action-card/files/task lazy mounting into the
  boundary experiment.
- Preserve auth/session/realtime behavior through the existing shared stores.
- Use the realtime ownership contract above: shell/session owns the connection,
  standalone Thread receives routed events and does not create a duplicate
  connection.
- Close with `RouterModule.closePage()`.
- No new Android multi-container architecture.
- Task Thread launch must pass task/thread identity and focused intent only.
  The opener must not prefetch a display payload or pre-create UI state just to
  fill the Thread Pager header. Thread Pager/store resolves create-or-open,
  auth-required, and canonical content after launch.

Measurements:

- Home -> `#all`.
- Tasks -> Thread with only task/thread identity launch params.
- Reply Thread from long-press/Open Thread.
- Back/close correctness.
- Composer send path.
- Attachment picker path.
- realtime event delivery and duplicate-connection check.

Acceptance:

- `thread_first_render`, `thread_payload_ready`, and `thread_first_frame` must
  improve or stay no worse than the embedded path.
- Android host `layout`/`dispatchDraw` for Thread first content should drop
  meaningfully from current traces.
- No duplicate realtime subscriptions/connections and no duplicate event apply.
- No lost Home/Tasks state on return.
- No stale badge/presence regression.
- No product display/capability state appears in `compose_thread` PagerData.
- No preloaded Thread messages/tasks/files/composer/settings/member payload
  appears in `compose_thread` PagerData.

### Phase 2: Remove Embedded Thread Hot Path

If Phase 1 passes:

- Route Thread openings through the Thread Pager by default.
- Keep embedded Thread only for explicitly supported two-pane/tablet mode.
- In two-pane mode, keep Home visible and model Thread as a second visible
  page-like surface with an explicit lifecycle contract, not as an accidental
  hidden retained route.

### Phase 3: In-Page Thread Tab Cleanup

Inside the Thread Pager:

- Keep Chat as the default mounted tab.
- Mount Tasks/Files only when selected or after first content is stable.
- Use Kuikly `Tabs` semantics only for the Thread page's local Chat/Tasks/Files
  switch, not for app-wide navigation.

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

We should proceed with a standalone Thread Pager proof before any multi-container
or native-host isolation experiment.

If the standalone Pager proof reduces host traversal, then the current embedded
Thread route is the wrong Kuikly boundary.

If it does not reduce traversal, then the next bottleneck is likely Thread's
internal layout/list/render structure, and we should optimize that inside the
Thread Pager rather than changing the host architecture.
