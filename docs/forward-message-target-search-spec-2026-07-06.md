# Forward Message Target Search Spec

Date: 2026-07-06

Status: tracking spec for task #544.

## Scope

Forward-message target selection needs two layers:

1. Default state: show only recent and immediately useful targets so the picker stays lightweight.
2. Search state: when the user enters a query, search the server for all discoverable forward targets, including targets that are not already loaded in the local home/sidebar stores.

The picker must not receive a full target directory through route/pageData. Route/pageData may carry only identity and intent, such as the selected message ids and forward mode. Target data must come from stores/repositories and server-backed search.

## Target Types

The forward picker recognizes these target classes:

- `recent_channel`: a joined, non-archived channel from the user's existing channels, ordered by recent activity or the existing sidebar/home ordering.
- `recent_dm`: an existing DM channel from `/channels/dm`; the backend currently returns this list by recent message order.
- `public_channel_unjoined`: a visible public channel in the same server where `joined=false`. This target is discoverable but not directly sendable until the user confirms joining.
- `human_dm_candidate`: a discoverable human in the same server where no DM currently exists with the user.
- `agent_dm_candidate`: a discoverable non-deleted agent in the same server where no DM currently exists with the user.

## Default State

The default picker state must not show full member, agent, or channel directories.

Recommended default sources:

- Existing DMs: first `N` items from `/channels/dm`.
- Joined channels: first `N` non-archived channels from `/channels` where `joined=true`, ordered by recent activity or by the same ordering already used by the sidebar/home surface.

The default list may be mixed or grouped under a single "Recent" section. The important behavior is that large servers do not turn the picker into a directory page before the user searches.

## Search State

When the query is non-empty, the picker must call a server-backed target search. It must not only filter whatever the frontend already has loaded.

This is intentionally different from the current client-side search rail pattern based on loaded `channels`, `members`, `agents`, and `dmChannels`; that boundary only finds targets the client has already loaded and misses never-DMed people/agents or visible unjoined public channels.

Recommended API:

```http
GET /api/targets/search?purpose=forward_message&q=<query>&limit=<limit>
```

An equivalent dedicated endpoint is also acceptable:

```http
GET /api/forward-targets/search?q=<query>&limit=<limit>
```

Recommended response item shape:

```ts
type ForwardTargetSearchItem = {
  type: "channel" | "dm" | "human" | "agent";
  id: string; // channelId, userId, or agentId
  title: string;
  subtitle?: string;
  avatarUrl?: string;
  channelId?: string; // present for existing channel/DM targets
  joined?: boolean; // channel targets only
  dmExists?: boolean; // human/agent targets only
  canForwardNow: boolean;
  requiredAction?: "join_channel" | "create_dm" | null;
};
```

Sorting:

- Exact handle/name match first.
- Prefix match second.
- Substring match after prefix.
- Existing recent DMs and joined channels get a light boost.
- A name-matched unjoined channel must not outrank an exact DM/person match.

## Permissions And Visibility

- Public channels may be returned even when `joined=false`.
- Private and joint channels may be returned only when the current user already has access or membership. Unjoined private/joint channels must not leak existence through this search.
- Archived channels are not forward targets by default. If a product rule later requires archived targets to be visible, they must be disabled with a clear reason.
- Human search must obey the same server-side human directory visibility rules used by the members directory. Rules such as `hideHumansFromMembers` must not be bypassed by forward search.
- Agent search excludes deleted agents. Stopped or inactive agents may be returned; their state is display metadata and does not block DM creation.
- `canForwardNow` and `requiredAction` are server decisions. The client should display them and follow them, not infer permissions from partial local state.

## Execute Rules

### Joined Channel

If `type=channel`, `joined=true`, and `canForwardNow=true`, forward directly to that channel.

### Existing DM

If `type=dm`, or a human/agent result has `dmExists=true` and a `channelId`, forward directly to that DM channel.

### Human Or Agent Without Existing DM

If the selected target is a human or agent with `requiredAction="create_dm"`:

1. Call the existing `POST /channels/dm` flow to create or fetch the DM.
2. Forward to the returned DM channel.

This does not need a separate confirmation; creating the DM is a natural prerequisite of forwarding to a person or agent.

### Unjoined Public Channel

If the selected target is a public channel with `joined=false` and `requiredAction="join_channel"`:

1. Show a confirmation state explaining that the user must join this public channel before forwarding.
2. On confirmation, call `POST /channels/:id/join`.
3. If join succeeds, forward to that channel.
4. If join fails or permissions changed, stop and show the error. Do not send.

The client must not silently join and forward.

### Private Or Joint Channel Race

Unjoined private/joint channels should not appear in search. If a backend race still returns a non-forwardable private/joint target, the client handles it as disabled or a 404/permission error and must not send.

## UI Behavior

- Initial focus is in the search box when the picker opens.
- Default state title/section can be "Recent".
- Search state groups results by type:
  - People
  - Agents
  - Channels
- Unjoined public channels must have clear copy such as "Public channel - Not joined" and must open a join confirmation before forwarding.
- Human/agent targets without an existing DM can show "Start DM"; selecting them starts DM creation as part of the forward action.
- If multi-select is available, mixed targets are allowed, but submit must resolve each target independently.
- Any selected `join_channel` targets must be summarized before action, e.g. "Join X public channels and forward".

## Backend Service

Create a target-search service rather than expanding message search or the current client-side `searchEntities.ts` style rail.

Recommended data sources:

- Channels: query server channels; return joined state; private/joint only when the user has access.
- Humans: query server members and apply directory visibility rules.
- Agents: query server agents and exclude deleted agents.
- DM existence: left join or lookup current user's existing DMs and return `channelId` when present.

The backend should compute `canForwardNow` and `requiredAction` so clients do not duplicate permission logic.

## Mobile/KMP Architecture Notes

- The picker route/pageData may carry only:
  - forward mode: merged or individual,
  - selected source message ids,
  - source thread/channel identity.
- The picker must load default recent targets through repositories/stores.
- Non-empty query must trigger server-backed search through a repository/data source.
- Forwarded content should be loaded from the canonical selected messages, not serialized into route/pageData.
- A received merged-forward message remains a normal message model with `forwardBundle`; tapping the message opens the readonly transcript from that loaded message state.

## Phasing

### Phase 1: Human/Agent Search And DM Creation

- Add server-backed search for never-DMed humans and agents.
- Reuse `POST /channels/dm` before forwarding.
- Single target only.
- Risk: low to medium.

### Phase 2: Unjoined Public Channels

- Include `public_channel_unjoined` in search results.
- Add joined state, disabled/required-action presentation, join confirmation, `POST /channels/:id/join`, and channel store refresh after join.
- Risk: medium to high because it adds permission confirmation and state refresh.

### Phase 3: Multi-Target Forward

- Support multi-target selection and batch execution.
- Resolve each target independently.
- Summarize all join-required targets before submit.
- Handle partial failures clearly.
- Risk: high; defer if initial forward only needs single target.

## Acceptance Cases

1. Recently used DMs and joined channels appear in default state, and a blank query does not display the full server member directory.
2. Searching for a never-DMed human returns that person; selecting them creates/gets the DM and completes forwarding.
3. Searching for a never-DMed agent returns that agent; selecting them creates/gets the DM and completes forwarding.
4. Searching for an unjoined public channel returns it with a clear "Not joined" marker; confirmation joins the channel and then forwards.
5. Searching for an inaccessible private channel returns no result and does not leak the name through the API.
6. When human directory visibility hides humans, forward target search hides the same humans.
7. Archived channels are not forwardable.
8. If join/create-DM succeeds but forward fails, the join/DM is not rolled back, and the UI shows forward failure without pretending success.
