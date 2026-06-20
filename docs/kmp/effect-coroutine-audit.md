# KMP Effect / Coroutine Hot-Path Audit

Date: 2026-06-20
Task: #185 follow-up for closed #146
Audited baseline: current `main` after `374b3d73`

## Current Status

No open P0/P1 items remain from the #185 audit after the current follow-up.

Implemented on #185:

- Thread bottom-anchor scroll observation uses `distinctUntilChanged`, guards
  boolean writes, and no longer keys the anchor effect on the self-written
  `allowBottomAutoAnchor` flag.
- Thread older-autoload threshold observation uses `distinctUntilChanged` before
  dispatching `loadOlder`.
- Thread read-all uses a latest visible message marker and Store-owned
  in-flight / pending / completed guards so repeated visible-read intents for
  the same marker no-op and realtime bursts coalesce to the latest marker.
- Tasks stale-filter pruning keys on stable id sets instead of full option
  objects.
- Members activity bottom-anchor observation uses `distinctUntilChanged` and
  guards repeated boolean writes.
- `AGENTS.md` records the first-principles rule: hot Compose effects use stable
  signatures and equality gates; hot UI-to-IO intents are marker-based and
  single-flight in the Store.

## Fixed Items

### Thread Bottom-Anchor Feedback Loop

Status: fixed by `8d6d1c3b`.

The original issue was an effect/collector loop where `allowBottomAutoAnchor`
was both written by the scroll observer and used as a key of the anchor effect.
Current code keeps the anchor effect keyed to stable render-axis fields and
equality-gates scroll observations.

### Thread Read-All Per Message Count

Status: fixed by `8d6d1c3b`.

The original issue dispatched `MarkVisibleReadAll` from message-count changes
and let the Store issue parallel read-all requests. Current code dispatches by
latest visible marker and coalesces repeated markers in `SharedThreadStore`.

### Thread Older Autoload Repeated True Emissions

Status: fixed by `8d6d1c3b`.

The older-load threshold `snapshotFlow` now applies `distinctUntilChanged`, so
the `loadOlder` event fires on threshold transitions rather than repeated
equivalent scroll/layout emissions.

### Tasks Option-Pruning Effect Keys

Status: fixed in the current #185 cleanup.

The stale chip pruning effects now key on stable available id sets:

- channel names,
- creator identity ids,
- assignee member ids.

The effects no longer key on full option row objects.

### Members Activity Bottom Anchor

Status: fixed in the current #185 cleanup.

The agent activity bottom-anchor observer now applies `distinctUntilChanged`
and only writes `allowBottomAnchor` when the derived value changes.

## Downgraded / Previously Fixed Items

### Thread Render Logging

Status: fixed before this cleanup.

`ThreadPage` builds a `ThreadRenderSignature` and keys render diagnostics on
that signature rather than the full payload. Non-render metadata is excluded.

### Thread Composer Member Suggestions

Status: fixed before this cleanup.

`SharedMembersStore` exposes scoped selector flows for composer suggestions,
and Thread route consumption no longer observes arbitrary full member state for
the active composer.

### Settings/Auth Draft Sync Effects

Status: acceptable.

These effects use scalar keys and copy prop/state into draft fields. They are
not on a hot realtime path and do not currently need cleanup without a concrete
recomposition trace.

## Review Rules

- Key effects by the fields the effect body actually consumes.
- Do not use state written by an effect or sibling collector as a restart key
  for another effect that can write the same state again.
- Apply `distinctUntilChanged` or an equivalent equality gate to `snapshotFlow`
  used for scrolling, paging, visibility, or read markers.
- Move hot UI-to-IO work into Store intents with marker-based single-flight and
  coalescing.
- Shared stores that serve multiple route/channel/member scopes must expose
  scoped selectors or slice flows with equality/no-op gates.
