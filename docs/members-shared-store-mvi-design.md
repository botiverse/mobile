# Members Shared Store MVI Design

Task: #KMP task #118

This document defines the shared-store boundary for Members, member detail,
agent detail, Thread member picker, and Add Members flows. It is intentionally
design-only for this slice: the next implementation should add a real store with
tests instead of extending the current page-local state.

## Current State

Current Members behavior is split across multiple UI surfaces:

- `MembersPage.kt` renders a `KuiklyMembersPayload` passed from outside the
  page. It owns selected member detail state with local Compose state.
- `SettingsHomePage.kt` has `SharedMembersScreen`, which uses a page-local
  `SharedMembersUiState` and directly calls `MemberRepository.getMembers`.
- `ThreadPage.kt` owns channel member modal state, add-member search/selection,
  and remove-member detail modal state inside the Thread UI. Thread data is
  currently embedded in `KuiklyThreadScreenPayload`.
- `SharedThreadStore.kt` can fetch channel members and member candidates through
  `MemberRepository`, but those facts are still a Thread payload slice.
- `MemberRepository` already exposes the useful lower boundary:
  server members, member profile, agent detail, channel members, add channel
  member, and remove channel member.

The gap is not repository coverage. The gap is that Members lacks one shared
store boundary, one reducer contract, and explicit no-op semantics.

## Store Boundary

Add a `SharedMembersStore` in `commonMain` and expose it through a
`SharedMembersViewModel` / `SharedStateHolder` facade. It should use
`MviStoreRuntime` unless a later implementation proves a narrower helper is
better.

The shared store owns reusable business facts:

- server-scoped member list for the selected server
- normalized member entities keyed by `MemberKey(kind, id)`
- agent profiles merged into the server member list
- presence and agent status for known members
- member role, permission badges, avatar, handle, and profile fields
- per-member detail loading/content/error state
- per-agent detail sections when opened from member detail
- channel member list and candidate list by `ChannelId`
- add/remove member mutation status by channel/member key
- permission blockers and typed load failures

The shared store may own light presentation state when it must survive tab
switches or be shared by multiple surfaces:

- selected list filter: all / agents / humans / offline
- current stable search query for the Members tab
- currently selected member key for the Members tab detail route

Keep ephemeral draft state route-local:

- add-member modal search text
- add-member selected candidate before submit
- create-channel member picker search and selected checkboxes
- expanded/collapsed visual groups
- transient copy/toast/navigation effects
- Thread modal open/closed state and local back-stack state

Rule of thumb: if the value should survive repository refresh, realtime patch,
or route re-entry, it can be store presentation state. If it is only an in-flight
dialog draft, keep it local and turn submit into a typed store intent.

## Canonical Entities

Use a normalized state shape so detail fetches and presence patches do not
rebuild lists:

```kotlin
data class MemberKey(
    val kind: MemberKind,
    val id: MemberId
)

data class SharedMembersState(
    val server: ServerSummary? = null,
    val entities: Map<MemberKey, MemberProfile> = emptyMap(),
    val serverList: MembersListSlice = MembersListSlice(),
    val channelMembers: Map<ChannelId, ChannelMemberSlice> = emptyMap(),
    val details: Map<MemberKey, MemberDetailSlice> = emptyMap(),
    val presentation: MembersPresentationState = MembersPresentationState(),
    val effect: SharedMembersEffect? = null
)
```

Suggested slices:

- `MembersListSlice`
  - `memberKeys: List<MemberKey>`
  - `loadState: ScreenLoadState`
  - `errorCode: String?`
  - `noPermissionCode: String?`
  - `lastLoadedServerId: ServerId?`

- `ChannelMemberSlice`
  - `memberKeys: List<MemberKey>`
  - `candidateKeys: List<MemberKey>`
  - `canAddMembers: Boolean`
  - `loadState: ScreenLoadState`
  - `addStatus: MutationStatus<MemberKey>`
  - `removeStatus: MutationStatus<MemberKey>`
  - `errorCode: String?`

- `MemberDetailSlice`
  - `profileState: ScreenLoadState`
  - `profile: MemberProfile?`
  - `agentDetailState: ScreenLoadState`
  - `agentDetail: AgentDetailSnapshot?`
  - `errorCode: String?`
  - `noPermissionCode: String?`

- `MembersPresentationState`
  - `query: String`
  - `kindFilter: MemberKind?`
  - `presenceFilter: Presence?`
  - `selectedMemberKey: MemberKey?`

`MemberProfile` remains the canonical member entity for this first pass because
the repository already returns it and UI already consumes it. Do not invent a
parallel UI DTO until the reducer proves a real gap.

## Reducer Events

Separate UI-facing actions from store reducer intents:

```kotlin
sealed interface SharedMembersAction {
    data object Start : SharedMembersAction
    data object Refresh : SharedMembersAction
    data class SearchChanged(val query: String) : SharedMembersAction
    data class FilterKind(val kind: MemberKind?) : SharedMembersAction
    data class OpenMember(val key: MemberKey) : SharedMembersAction
    data object CloseMember : SharedMembersAction
    data class LoadChannelMembers(val channelId: ChannelId) : SharedMembersAction
    data class AddChannelMember(val channelId: ChannelId, val key: MemberKey) : SharedMembersAction
    data class RemoveChannelMember(val channelId: ChannelId, val key: MemberKey) : SharedMembersAction
}
```

```kotlin
sealed interface SharedMembersIntent {
    data class LoadStarted(val serverId: ServerId) : SharedMembersIntent
    data class MembersLoaded(val snapshot: MemberSnapshot) : SharedMembersIntent
    data class MembersLoadFailed(val code: String) : SharedMembersIntent
    data class MembersNoPermission(val code: String) : SharedMembersIntent

    data class SearchUpdated(val query: String) : SharedMembersIntent
    data class KindFilterUpdated(val kind: MemberKind?) : SharedMembersIntent
    data class MemberSelected(val key: MemberKey?) : SharedMembersIntent

    data class DetailLoadStarted(val key: MemberKey) : SharedMembersIntent
    data class ProfileLoaded(val key: MemberKey, val profile: MemberProfile) : SharedMembersIntent
    data class AgentDetailLoaded(val key: MemberKey, val detail: AgentDetailSnapshot) : SharedMembersIntent
    data class DetailLoadFailed(val key: MemberKey, val code: String) : SharedMembersIntent

    data class ChannelMembersLoadStarted(val channelId: ChannelId) : SharedMembersIntent
    data class ChannelMembersLoaded(val channelId: ChannelId, val snapshot: ChannelMemberSnapshot) : SharedMembersIntent
    data class ChannelMembersLoadFailed(val channelId: ChannelId, val code: String) : SharedMembersIntent

    data class AddMemberStarted(val channelId: ChannelId, val key: MemberKey) : SharedMembersIntent
    data class AddMemberSucceeded(val channelId: ChannelId, val snapshot: ChannelMemberSnapshot) : SharedMembersIntent
    data class AddMemberFailed(val channelId: ChannelId, val key: MemberKey, val code: String) : SharedMembersIntent

    data class RemoveMemberStarted(val channelId: ChannelId, val key: MemberKey) : SharedMembersIntent
    data class RemoveMemberSucceeded(val channelId: ChannelId, val key: MemberKey) : SharedMembersIntent
    data class RemoveMemberFailed(val channelId: ChannelId, val key: MemberKey, val code: String) : SharedMembersIntent

    data class PresencePatched(val key: MemberKey, val presence: Presence, val agentStatus: AgentStatus?) : SharedMembersIntent
    data class MemberPatched(val profile: MemberProfile) : SharedMembersIntent
    data class MemberRemoved(val key: MemberKey) : SharedMembersIntent
}
```

Effects should handle one-off outputs:

- copy member id
- open direct message
- toast mutation failure when the UI should not derive it from state
- route to agent detail sections

## No-op And Equality Rules

The reducer must be stricter than plain data-class replacement.

- Loading the same `MemberSnapshot` twice is a no-op: no new `StateFlow`
  emission, no list reference churn.
- Entity merge preserves existing `MemberProfile` references when the incoming
  profile is equal.
- Presence/status patch only replaces the target member entity. Server list
  key order and unrelated member object references must remain stable.
- Search/filter changes only update `presentation`; they must not mutate
  `entities`, `serverList`, or channel slices.
- Detail load success updates `details[key]` and `entities[key]` if the profile
  is richer. It must not rebuild `serverList.memberKeys`.
- Agent detail load success updates only `details[key].agentDetail`.
- `OpenMember` for the already selected key is a no-op.
- `AddChannelMember` is a no-op if the key is already in
  `channelMembers[channelId].memberKeys`.
- `RemoveChannelMember` is a no-op if the key is absent.
- Duplicate add/remove requests share an `MviInFlightGuard` key:
  `members:add:<channelId>:<memberKey>` and
  `members:remove:<channelId>:<memberKey>`.
- Stale responses are ignored by request token or by checking the response
  channel/server key before applying the intent.
- Permission failures update only the affected slice and do not clear cached
  content.

## Surface Ownership

### Members Tab

Use `SharedMembersViewModel` as the canonical owner. Replace the current
`SharedMembersUiState` local state in `SettingsHomePage.kt`.

`MembersRouteContent` should receive a projected UI payload derived from store
state, but selected member and search can move into `MembersPresentationState`
when the UX wants them to survive tab switches.

### Member Detail

Member detail should not be a separate page-local fetch. `OpenMember(key)`
selects the member and starts profile/detail loading when needed.

Human detail loads `MemberRepository.getMemberProfile`.

Agent detail loads `MemberRepository.getAgentDetail` and can progressively
hydrate direct messages, reminders, activity, workspaces, and skills by adding
detail intents later. First pass should only hydrate the existing
`AgentDetailSnapshot` aggregate.

### Thread Members Modal

Thread modal open/close and back stack remain route-local. Channel membership
facts should eventually come from `SharedMembersStore.channelMembers[channelId]`
or from a shared reducer that both Thread and Members use.

For the first implementation, do not rewrite Thread membership if it risks
colliding with current Thread Store work. Keep Thread using its current member
payload slice, but align event names and reducer semantics with this design so
the later migration is mechanical.

### Thread Add Members

Query and selected candidate are route-local. Submit dispatches a typed
`AddChannelMember(channelId, key)` action. The store owns in-flight/failed
mutation status and the resulting channel member slice.

### Create Channel Member Picker

Create-channel picker search and selected checkboxes remain local draft state.
The candidate list should be projected from the server member list once the
Members store exists. Creating a channel consumes selected ids from local draft;
the Members store should not own a half-created channel form.

## Realtime

Realtime events should enter as reducer intents, not as ad hoc UI patch calls.

Supported reducer events:

- presence changed -> `PresencePatched`
- member profile changed -> `MemberPatched`
- member removed/deactivated -> `MemberRemoved`
- channel member added -> channel slice adds one key if not already present
- channel member removed -> channel slice removes one key
- agent status changed -> `PresencePatched` with `agentStatus`

If a realtime event lacks enough identity to resolve a `MemberKey`, drop it and
log a non-sensitive diagnostic rather than fabricating member state.

## First Implementation Slice

Recommended first slice:

1. Add `SharedMembersStore.kt` with state, action, intent, reducers, and
   `MviStoreRuntime`.
2. Add `SharedMembersViewModel` that reads session/server id, calls
   `MemberRepository.getMembers`, and dispatches typed result intents.
3. Wire only `SharedMembersScreen` to the new store.
4. Keep `MembersPage.kt` UI mostly unchanged by adding a projection from
   `SharedMembersState` to `KuiklyMembersPayload`.
5. Add detail selection and detail loading for member/agent from the store.
6. Leave Thread modal/add-member and create-channel picker route-local in this
   first slice, but document their event mapping to the new store.

This first slice proves the MVI boundary without touching every consumer at
once.

## Required Tests For Implementation

Add focused tests before marking the implementation done:

- loading members twice with identical content is a no-op
- presence patch changes only the target member entity/reference
- search/filter updates presentation only and preserves business snapshot
- detail load hydrates detail state without rebuilding the list
- agent detail load hydrates only that member detail slice
- add member no-ops when the member is already in channel
- remove member no-ops when the member is not in channel
- duplicate add/remove mutation is ignored by in-flight guard
- no-permission keeps cached content and marks only the affected slice
- stale channel response is ignored when channel id does not match the slice

## Non-goals

- Do not put raw API responses, tokens, bearer headers, or server-id headers in
  UI.
- Do not move text-field drafts and checkboxes into the canonical member
  snapshot.
- Do not build a second member data source; reuse `MemberRepository`.
- Do not rewrite Thread membership as part of the Members-tab first slice unless
  it can be done with focused tests and no collision with Thread Store work.
