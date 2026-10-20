# Shared MVI Foundation

This document records the lightweight shared-state foundation used by Slock
KMP/common code. It is intentionally small: feature stores keep their own
business state and reducers, while the foundation enforces the contracts that
prevent state churn.

For the lifetime/threading boundary between process-owned domain work and
shell-owned UI projection, read
`docs/process-domain-core-shell-projection.md`.

## Goals

- Keep business facts in shared stores, repositories, and data sources.
- Let UI render state and emit intents; UI must not own cache, realtime, API,
  token, header, or write-through timing.
- Serialize reducer application through one queue per store.
- Skip no-op state writes before emitting to `StateFlow`.
- Preserve stable row/list references when only one entity changes.
- Keep mutations idempotent with feature-owned action keys and in-flight guards.
- Map repository/data-source failures through typed `DataResult`, never raw JSON
  or platform exceptions.
- Log non-sensitive transitions so performance/debug investigations can prove
  whether a render was caused by a real state change.

## Foundation Types

`MviStoreRuntime<State, Intent>` owns:

- `state: StateFlow<State>`
- `dispatch(intent)` for async queue-backed intent processing
- `acceptQueued(intent)` for suspend callers that need a completion result
- `accept(intent)` for synchronous tests and already-serialized call sites
- an equality/no-op gate with `MviTransition`

`MviInFlightGuard` owns feature-level single-flight keys such as
`settings:server:delete` or `thread:mark-read:<threadId>`.

`MviEffectEmitter<Effect>` provides a small `SharedFlow` channel for one-off
effects such as navigation, toast, copy/share, permission prompt, or host
capability requests.

`MviStaleToken` is a tiny request-token primitive. Stores can increment or
replace tokens before starting async work, then ignore responses whose token is
not current.

`DataResult.failureCodeOrNull()` is the store-facing mapper for typed lower
boundary failures.

## Lower Boundary

The expected direction is:

```text
UI -> SharedStateHolder -> UseCase -> Repository -> LocalDataSource/DAO
                                             \-> RemoteDataSource/API/HostCapability
```

Repositories and data sources return typed domain snapshots, patches, or
`DataResult` failures. Host capabilities expose platform actions such as secure
store, notification permission, file picker, router, or open URL. They must not
become hidden state machines.

Repository and data source are deliberately separate:

- A repository is the domain-facing contract for a feature. It is where cache
  vs remote policy, write-through, realtime patch merge, no-op/diff decisions,
  and fallback behavior belong.
- A data source is a concrete external boundary: Ktor/API transport,
  SQLDelight/native database DAO, local KV store, or host capability.
- A feature that only has one remote call may temporarily have a thin
  repository implementation. That is a migration bridge, not a pattern to copy
  blindly. If a repository never owns policy, it should be collapsed back into
  the use case or replaced by a real repository when cache/realtime arrives.

Cache, realtime, and write-through events must re-enter through store/reducer
paths. They should not directly mutate UI collections or trigger a full page
reload when a narrow patch is enough.

```mermaid
graph TD
    UI[UI Layer<br/>Kuikly Compose / Android Compose / SwiftUI]

    subgraph CommonMain[Shared Logic]
        StateHolder[SharedStateHolder<br/>StateFlow UIState + Flow UIEvent]
        Runtime[MviStoreRuntime<br/>queued reducer + no-op gate]
        UseCase[UseCase / Interactor]
        Repository[Repository]
        Local[LocalDataSource / DAO<br/>SQLDelight / Native DB / KV]
        Remote[RemoteDataSource<br/>Ktor / WebSocket / HostCapability]
    end

    StateHolder -->|UIState| UI
    StateHolder -->|UIEvent| UI
    UI -->|UIAction| StateHolder
    StateHolder --> Runtime
    StateHolder -->|Call| UseCase
    UseCase --> Repository
    Repository -->|Observe local truth| Local
    Repository -->|Refresh / mutate / realtime sync| Remote
    Remote -->|DataResult / Patch| Repository
    Repository -->|Upsert / patch| Local
    Local -->|Flow of domain state| Repository
    Repository -->|Typed result| UseCase
    UseCase -->|Intent / Result| StateHolder
```

## Library Survey

The current decision is to borrow proven ideas from KMP state libraries without
adopting a heavy framework in this migration slice.

- Orbit MVI exposes container state and side-effect flows through a
  `ContainerHost`; its docs treat side effects as one-off events such as
  navigation, analytics, or toasts. Source: <https://orbit-mvi.org/Core/> and
  <https://orbit-mvi.org/Core/architecture>.
- MVIKotlin models a store with `Intent`, `State`, and `Label`, and separates
  asynchronous business logic (`Executor`) from synchronous state mutation
  (`Reducer`). Source: <https://arkivanov.github.io/MVIKotlin/store.html>.
- Decompose focuses on KMP component/lifecycle/navigation ownership and exposes
  state streams with `Value`, keeping navigation out of UI-only code. Source:
  <https://arkivanov.github.io/Decompose/component/overview/>.
- Molecule can produce a `StateFlow` from Compose runtime state, but it requires
  the Compose compiler/runtime and is better treated as a presenter tool than as
  Slock's business-state source of truth. Source:
  <https://github.com/cashapp/molecule> and
  <https://cashapp.github.io/molecule/docs/latest/molecule-runtime/app.cash.molecule/launch-molecule.html>.
- Ballast is a KMP state-management framework with platform-agnostic
  coroutine/flow support and a repository layer concept. It is useful as a
  reference for long-lived repository boundaries, but it is too opinionated for
  this incremental migration. Source:
  <https://copper-leaf.github.io/ballast/wiki/platforms/> and
  <https://copper-leaf.github.io/ballast/wiki/modules/ballast-repository/>.

## What Slock Adopts

- Typed `Intent -> Reducer -> State` updates.
- One-off platform/UI outputs should be effects, not state fields that linger
  forever.
- Reducers are serialized and observable.
- No-op state writes are skipped before StateFlow emission.
- Store tests prove transition behavior, in-flight behavior, and typed failure
  mapping.
- Lifecycle/navigation stays outside reducers; reducers own only business state.

## What Slock Avoids For Now

- A whole-app framework migration in one step.
- Store base classes that force every feature into the same executor hierarchy.
- Compose-runtime state producers as canonical business facts.
- UI-layer debounce as a replacement for repository/write-through no-op gates.
- Raw API response, token, bearer header, or `X-Server-Id` access in UI code.

## Current Store Map

As of 2026-06-27, this document is the current shared-store boundary reference,
not a task-status snapshot. Older dated audits may still be useful historical
context, but check this section and the code before treating an audit claim as
current.

`RaftSharedStoreProvider` is the app/session provider for process-level shared
stores and shared infrastructure:

- auth/session: `KuiklyMMKVAuthSessionStore`, `SharedAuthDataSource`,
  `SharedAuthGateStore`;
- local persistence: `SqlDelightRelationalStore`,
  `RelationalComposeLocalDataStore`, local preference stores;
- app/domain projections: `SharedConversationDirectoryStore`,
  `SharedServerMembersStore`, `SharedAgentDetailStore`,
  `SharedTasksDataStore`, `SharedActivityStore` cache, Home/Saved state cache;
- composer persistence: `SharedThreadComposerDraftPersistSlice`;
- realtime ingress/projection: `SharedRealtimeDomainEventOwner`;
- configuration: `SharedApiEnvironmentStore`.

Feature stores are deliberately mixed during migration:

- Runtime-backed stores using `MviStoreRuntime` directly:
  `SharedTasksStore` and `SharedThreadComposerDraftStore`.
- Local reducer/state-flow stores that still follow the same intent/reducer
  contract without the runtime helper: `SharedHomeStore`,
  `SharedHomeShellStore`, `SharedActivityStore`,
  `SharedHomeSearchSavedStore`, `SharedThreadStore`,
  `SharedThreadFilesStore`, `SharedMembersStore`,
  `SharedServerMembersStore`, `SharedAgentDetailStore`,
  `SharedConversationDirectoryStore`, `SharedSettingsStore`,
  `SharedComputersStore`, and `SettingsStandaloneStore`.
- Data/repository boundaries such as `ConversationRepositoryDataSource`,
  `HomeDataSource`, `InboxSavedSearchRepositoryDataSource`,
  `MemberRepositoryDataSource`, `SettingsRepositoryDataSource`,
  `SharedTasksRepository`, and `ReadReceiptRepositoryDataSource` own typed
  remote/local/host-capability calls below the stores.

Do not infer that a feature is non-MVI just because it does not instantiate
`MviStoreRuntime`. The current requirement is the contract: UI emits intents,
stores/repositories own business state and cache/realtime/write-through, and
unchanged updates are skipped before broad StateFlow emission where practical.
Migrating another store onto `MviStoreRuntime` is a focused cleanup, not a
prerequisite for using that store as the product state owner.

## Current Runtime Consumers

`SharedTasksStore` remains the canonical small runtime-backed feature store:

- UI dispatches typed task intents.
- Repository/data-source code owns remote/local task reads and mutations.
- Store state emission goes through `MviStoreRuntime`, keeping the reducer
  queue, no-op gate, and transition log.
- Mutation/load failures are mapped through typed `DataResult.failureCodeOrNull`
  and exposed through task UI state/events.

`SharedThreadComposerDraftStore` is also runtime-backed:

- Draft text, task-mode state, and persisted active-draft records flow through
  typed intents.
- `SharedThreadComposerDraftPersistSlice` persists accepted transitions and is
  provided from `RaftSharedStoreProvider` so Home and Thread consume the same
  draft source.

Thread, Home, Activity, Members, Settings, and Search/Saved already have shared
stores and should be extended in place. Do not restart old "move this surface
to a shared store" work from stale audit documents.
