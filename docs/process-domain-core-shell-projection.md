# Process Domain Core and Shell UI Projection Contract

- Status: Accepted
- First production example: server members, task #283 / PR #984
- First example merged to `main`: `2089819ee5ec3f9c24f6ac3b8a488a42c745e4d4`

This document records the lifetime and threading boundary for shared KMP data
that is expensive to fetch, normalize, merge, diff, or index. It is the default
design reference for moving repeated domain work out of AppShell/UI owners.

The short rule is:

> Share domain facts, cache, reductions, and active flights at process scope;
> keep selection, route, viewport, effects, and render state at shell scope.

Moving work to a process-owned coroutine scope is not sufficient by itself.
Heavy work must also leave the UI dispatcher, and publication back to a shell
must be small, owner-fenced, and safe after account/server/window changes.

## Problem This Solves

A process can contain several consumers of the same server data: an AppShell,
Activity, Thread, Settings, a foreground reconciler, and standalone routes.
If every shell-scoped store owns its own repository cache, request mutex, and
reducer, one network result can be fetched and applied repeatedly. That causes:

- duplicate requests and duplicate parsing/normalization;
- long `apply`/merge work on a UI owner;
- consumer cancellation aborting work needed by another consumer;
- whole-list publication and unnecessary recomposition;
- inconsistent snapshots between windows or routes;
- late results from an old account/server publishing into a new owner.

The server-members incident was the first production example: provider and
Home consumers repeatedly forced and reduced the same roster. The accepted
fix did not make the whole Home or Members UI store process-global. It created
one process-owned read core and kept shell facades for selection and UI state.

## Required Architecture

```text
network / database / realtime
        |
        v
Process domain core
  owner-keyed cache + active flight + generation
  background parse / normalize / merge / diff / index
  immutable snapshot + version + diagnostics
        |
        v
Shell-scoped facade / route store
  selected server + loading/error intent + route generation
  narrow selector / snapshot reference publication
        |
        v
Page projection
  viewport + focus + menu + banner + navigation effects
```

### Process-owned responsibilities

A process domain core may own:

- immutable domain snapshots and monotonically increasing versions;
- cache policy, bounded lifetime, TTL/LRU, and invalidation;
- one active request/reduction per owner key;
- request coalescing so later consumers join an existing flight;
- DTO parsing, normalization, deduplication, merge, diff, and indexes;
- realtime/domain patches that are independent of a particular route;
- structured, non-sensitive timing and change diagnostics;
- account/environment/server generation fences for publication.

The owner key must include every identity that changes the meaning or authority
of the data. The minimum server-scoped key is:

```text
apiEnvironment + accountId + serverId
```

Add a domain identity when one server has multiple independent buckets. Add an
auth/owner generation when credentials or authority can rotate without changing
the visible string ids.

### Shell-owned responsibilities

Shell/AppShell/route stores retain:

- current server selection, including `selectedServer = null` semantics;
- current tab/filter, loading affordance, retry intent, and route generation;
- list position, viewport anchors, visible read markers, and scroll ownership;
- pending navigation, modal/menu/focus, foreground banner, and one-off effects;
- shell-specific auth/network facades required by mutations that are not yet
  explicitly server-addressed;
- projection leases and subscriptions for the shell's visible server/route;
- any reference to Pager, Bridge, Activity, Composable callback, or host view.

Closing a shell releases these owners and subscriptions. It must not cancel a
process producer that is still needed by another consumer.

## Threading and Publication Contract

Process lifetime and background execution are separate decisions. A process
singleton that runs a 3,000-row merge on Main still blocks rendering.

The required pipeline is:

1. Fetch/read below the UI layer.
2. Parse, normalize, reconcile, diff, and build indexes on a background
   dispatcher or otherwise off the UI owner.
3. Produce an immutable domain snapshot or a narrow patch with a version.
4. Re-check the owner/generation immediately before publication.
5. Publish only a snapshot reference/version or changed projection needed by
   that shell.

UI publication should be O(1) where practical, or proportional to the actual
visible delta. It must not rebuild the whole domain table merely to install an
already-reduced process snapshot.

Cancellation follows ownership:

- cancelling one shell waiter does not cancel the process producer;
- invalidating an account/environment/server advances its generation;
- a late result may finish, but it cannot commit or publish after invalidation;
- a new generation must not join an old generation's active flight.

## Read and Mutation Boundaries

Read sharing is safe only when the request is fully addressed by the core owner
key. Mutations require a stricter rule:

> A process-owned mutation must carry an explicit server/domain identity. It
> must never infer authority from a process-wide "current server".

Until a mutation API is explicitly server-addressed, keep it behind the
shell-bound session/repository facade. Do not move invite/remove/role/profile/
avatar/channel-member writes into a process core merely because roster reads
are already shared.

For a process-owned optimistic mutation:

- serialize or coalesce by owner + entity/action key;
- apply the optimistic patch to the canonical snapshot once;
- publish the same versioned result to interested shells;
- rollback only if the mutation attempt and generation are still current;
- reject late success/failure after logout, account switch, or owner rotation.

## Lifecycle and Memory

Process-owned does not mean immortal.

- Logout, account replacement, or API environment change invalidates all
  affected owner generations and clears their cache/flight authority.
- Server-specific invalidation advances that owner generation.
- Cache entries must be bounded by count and/or TTL.
- A process core must not retain Pager, Bridge, Activity, view, Composable
  callback, shell logger, or route callback.
- Shell close removes shell facades and subscriptions; process cache eviction
  follows cache policy rather than window lifetime.

## Diagnostics

High-cost cores should emit enough structured data to prove that work is shared
and off the render path. Recommended fields:

```text
ownerHash serverId source priority force reason
flight=start|join|cache_hit
decodeMs reduceMs diffMs publishMs
inputCount changedCount snapshotVersion generation
publicationCurrent skipReason
```

Do not log tokens, authorization headers, credentials, or message bodies.
Prefer a hashed composite owner plus a non-sensitive server id when needed for
operational correlation.

## Production Reference: Server Members

The current example is implemented by:

- `compose/shared/src/commonMain/kotlin/build/raft/app/data/ProcessServerMembersCore.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/SharedServerMembersStore.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/RaftSharedStoreProvider.kt`
- `compose/shared/src/commonTest/kotlin/build/raft/app/data/ProcessServerMembersCoreTest.kt`

`ProcessServerMembersCore` owns immutable roster snapshots, versions, bounded
cache entries, active network flights, and process/owner generations. Its owner
is `(environmentId, accountId, serverId)`. Multiple shell consumers join the
same flight, and cancelling the first waiter does not cancel the producer.
Invalidation makes old completions non-publishable.

`RaftSharedStoreProvider` creates one process members core while retaining
shell-scoped `SharedServerMembersStore`, auth, network, Home, Activity,
navigation, and foreground-banner owners. `releaseShellStoreScope()` disposes
the shell-owned objects without turning the process roster core into a UI store.

This example is a template for ownership and publication, not a requirement to
copy its exact class shape into every domain.

## Migration Checklist

Before promoting another domain (Tasks, Home directory, Activity summary,
profiles, or message indexes), answer all of the following:

1. What exact repeated fetch/reduce/apply cost is being removed?
2. What is the complete owner key?
3. Which facts are process canonical, and which state remains shell/route UI?
4. Can every read be addressed without a process "current server" fallback?
5. Are mutations explicitly server/domain-addressed? If not, which writes stay
   shell-bound?
6. Where do parse/normalize/merge/diff execute, and what remains on the UI
   dispatcher?
7. How do concurrent consumers join one flight?
8. How are old completions fenced after logout/account/env/server changes?
9. What bounds cache lifetime and size?
10. Can the core retain any Pager/Bridge/Activity/callback? The answer must be
    no.
11. What proves narrow/no-op publication rather than whole-list replacement?
12. Which tests prove same-owner sharing, cross-owner isolation, cancellation
    independence, and stale-generation rejection?

## Required Tests

At minimum, each new process core must prove:

- same owner + simultaneous shell consumers => one flight and one reduction;
- cancelling one waiter does not cancel the shared producer;
- different servers/accounts/environments use independent owners;
- account/environment/server invalidation rejects an old completion;
- a fresh generation wins cache and publication;
- cache hit does not repeat heavy reduction;
- shell close does not publish into or retain the closed shell;
- no-op input does not emit a new broad UI snapshot;
- mutations, if included, use explicit identity and generation-safe rollback.

## Anti-patterns

Do not:

- make an entire Home/Activity/Thread UI store a process singleton;
- store a process-wide current server and let commands infer from it;
- move only the coroutine scope while leaving heavy reducers on Main;
- capture a caller-owned fetch closure whose session can disappear mid-flight;
- let one shell's cancellation cancel a producer shared by other shells;
- publish a process snapshot without an owner/generation/terminal check;
- keep unbounded per-server caches after windows close;
- pass full process snapshots through route/pageData payloads;
- use documentation as a substitute for tests at the ownership boundary.

## Relationship to Other Contracts

- `docs/slock-app-state-first-principles.md` defines canonical state,
  patch-first refresh, narrow selectors, and render-only UI threading.
- `docs/shared-mvi-foundation.md` defines reducer/store/repository boundaries.
- `docs/ios-turbo-safe-shell-contract.md` defines the iOS-only disposable
  startup-visual boundary; it never becomes process or shell product-state
  authority.
- `docs/rfc-multi-server-shell.md` defines multi-window/server isolation.
- `docs/realtime-session-owner-boundary-spec.md` defines process realtime
  authority and shell projections.

When these documents appear to conflict, preserve explicit owner identity and
shell isolation first; never resolve ambiguity by introducing a process-wide
current-server fallback.
