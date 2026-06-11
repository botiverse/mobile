# Socket.IO React Parity Audit

Date: 2026-06-09 (original), updated 2026-06-11
Owner: Cindy (original), Android-Developer-4 (updates)

## 2026-06-11 Update: Typed Event Alignment & Raw Transport

Commit range: `1400b359`, `6d6bbfae`, `d6a4bcd3`, `a3a7cdbf`, `8346b041`, `43cbce83`, `3cd734ec`, `91384989`

### Completed

**New typed events:**
- `server:deleted` — typed event, mapper, handler (SettingsHomePage), Android/OHOS whitelist
- `server:plan-updated` — typed event, mapper, handler, Android/OHOS whitelist (previously listed as "not subscribed/mapped")

**DTO field alignment:**
- `senderMembershipStatus` — added to `Message` domain model, `MessageWireDto`, `toDomain()`, realtime mapper test
- Attachment `width`/`height` — added to `Attachment` domain model, `AttachmentWireDto`, `toDomain()`, realtime mapper test
- `MessageReaction` — already aligned (KMP-专家 task #244)

**Android raw transport:**
- Deleted `sendRealtimeEventToSharedShell()`, `RealtimeEvent.toSharedShellJson()`, `Message.toJson()`, `MessageReaction.toJson()`, `MemberProfile.toJson()`
- Android bridge now passes raw `eventName + rawPayload` from Socket.IO client, same as OHOS
- Added `observeRawEvents` to `SocketIoRealtimeClient` for raw pass-through
- Raw subscription lifecycle managed via `realtimeRawSubscription`
- Legacy `payload`/`parseSharedShellRealtimeEvent` path deprecated in shared shell

**Focused test:**
- `RealtimeEventMapperTest.messageNewRawPayloadMapsWithFullFields` — verifies `message:new` raw payload → `MessageNew` with `senderMembershipStatus`, attachment `width/height`, reactions
- 3/3 tests passing

**Gates:**
- `:shared:compileDebugKotlinAndroid` ✅
- `:androidApp:compileDebugKotlin` ✅
- `:shared:compileKotlinOhosArm64` (OHOS) ✅
- `:shared:testDebugUnitTest --tests RealtimeEventMapperTest` ✅

### Still Missing (unchanged from original audit)
- `daemon:status` — React still listens as a legacy transition event; current server source only emits `machine:status`, so this is now documented as a compatibility decision gap rather than an active known server emit.
- OHOS refreshed-token reconnect handoff
- Real-device proof for Android and OHOS
- Conservative refresh intents for agent activity/machine status/reminders/announcements
- OHOS Socket.IO parser tests

### 2026-06-11 Re-Audit Notes

The 2026-06-11 re-audit found one stale contradiction in this document: `server:plan-updated` was listed both as missing and as completed. The current source has it in `RealtimeSocketEvents`, `RealtimeEventMapper`, Android raw forwarding, and the OHOS `WIRE_EVENTS` allowlist, so it should no longer be treated as missing.

`daemon:status` was different. React's `MainLayout.tsx` still registers it with the comment "Legacy: handle old daemon:status events during transition", but the checked server source under `third_party/slock-source/packages/server` has no `daemon:status` emit; machine connectivity now emits `machine:status`. That means the remaining work is either:

- explicitly decide not to support the legacy alias and remove it from parity requirements, or
- add a small compatibility mapping from `daemon:status { daemonId, status, statusVersion }` into the existing machine status refresh path.

---

## Executive Summary (original)

The OHOS bridge is not missing the basic Engine.IO ping/pong. `OhosSocketIoClient.ets` handles Engine.IO ping packet `"2"` and replies with pong packet `"3"` (`compose/ohosApp/entry/src/main/ets/kuikly/OhosSocketIoClient.ets:181-187`). So if the question is strictly "does OHOS answer the Socket.IO transport heartbeat?", the source says yes.

At audit time the mobile stack was not truly aligned with React. The highest-risk gaps were above the low-level ping/pong layer:

1. KMP decoded `message:new` / `message:updated` as `{ message: ... }`, while server and React use the raw enriched message object directly.
2. Mobile advanced resume `lastSeq` from `heartbeat.seq`, which could move the applied cursor past messages that were never decoded.
3. Mobile lacked React's app-layer heartbeat-silence forced reconnect.
4. Mobile listened to fewer refresh events than React: channel updates, channel member updates, machine status/capabilities, agent create/delete, server membership removal, announcements, and reminders.
5. Home merge state counted realtime/resume unread too simply and did not account for dedupe, self-authored messages, or the currently visible conversation.

Task #65 now closes those product-risk paths in source: raw message rows decode, heartbeat server max is separate from applied resume cursor, resume waits for `rooms:joined`, Android/OHOS share the same heartbeat watchdog thresholds, Android refreshes Socket.IO auth during reconnect/auth failures, the missing React refresh events are wired to typed refresh intents, server membership removal exits the current server, and Home preview/unread merge now dedupes by message id and suppresses self/current-conversation unread increments.

Remaining audit caveats after Task #65 and the 2026-06-11 follow-up: no real-device OHOS proof yet, OHOS still does not have a true refreshed-token handoff before reconnect, React's legacy `daemon:status` compatibility listener has no mobile mapping, and reminder/announcement handling is a conservative refresh/reload path rather than full surface-specific local mutation.

## 2026-06-09 OHOS Socket.IO Pitfall Record

This section records the concrete detour taken while investigating whether OHOS should replace the hand-written bridge with the third-party `@ohos/socketio` package.

### What We Tried

We first switched OHOS to `@ohos/socketio@2.1.4` in commit `61771eb9 fix: use ohos socketio client`.

That change:

- added `@ohos/socketio` to `compose/ohosApp/entry/oh-package.json5` and `oh-package-lock.json5`;
- rewrote `OhosSocketIoClient.ets` to use the package's `client_socket`;
- kept the existing shared bridge API stable (`connect`, `disconnect`, `suspend`, `resume`, `emit`, `onEvent`);
- registered the same Slock wire events as the self-written bridge;
- passed `ohpm install --all`, hvigor sync, OHOS `assembleHap`, and `git diff --check`;
- produced a signed HAP that included `libs/arm64-v8a/libclient_socket_c.so`.

The initial attraction was valid: the package provides event registration, emit, reconnect settings, namespace support, header/options APIs, and native Socket.IO framing. It would reduce maintenance cost if our server accepted its available auth paths.

### The Blocking Pitfall

Slock server Socket.IO middleware reads credentials from:

```ts
socket.handshake.auth.token
socket.handshake.auth.serverId
```

Source: `third_party/slock-source/packages/server/src/socket/index.ts:46-75`.

React sends exactly that shape with `auth: { token, serverId }`.

Source: `third_party/slock-source/packages/web/src/api/socket.ts:38-49`.

`@ohos/socketio@2.1.4` does not expose an equivalent public auth API. The ArkTS wrapper only exposes:

- `set_headers(headers: string)`
- `set_option(opt: string)`
- `connect(uri: string)`

It has no `auth`, `set_auth`, or `connectWithAuth`.

The upstream OHOS NAPI wrapper also does not pass auth. Its `SocketIOClient::connect` calls the lower-level client as:

```cpp
client->clientInstance.connect(uri, client->g_optionMap, client->g_headerMap);
```

The lower-level `sio::client` binary does contain overloads that accept an auth `sio::message`, but the OHOS wrapper neither exposes nor calls those overloads. So the package can send headers/query/options, but not the Socket.IO CONNECT packet auth payload that current Slock server requires.

### Secondary Parameter-Format Pitfall

While checking the package source, we also found our first wrapper usage formatted headers/options incorrectly for this package.

The package parser is a lenient `key:value,key:value` / `{key:value}` map parser, not an HTTP-style header block or URL query parser. The first integration passed:

- headers joined by `\r\n`
- options as `token=...&serverId=...`

That would not populate `g_headerMap` / `g_optionMap` correctly. Commit `b53149c4 fix: format ohos socketio options` corrected that to:

- headers as `Authorization:Bearer ...,X-Server-Id:...`
- options as `{token:...,serverId:...}`

This made headers/query reach the native client, but it still did not create `handshake.auth`.

### Why We Reverted

Because current Slock server only authenticates Socket.IO from `handshake.auth`, switching to the package would likely produce a clean build but fail real login/realtime auth on device.

We therefore reverted the package route:

- `f581d376 Revert "fix: format ohos socketio options"`
- `34067bd9 Revert "fix: use ohos socketio client"`

After the revert:

- `@ohos/socketio` is removed from OHOS package files;
- `OhosSocketIoClient.ets` is back to `@ohos.net.webSocket`;
- the bridge sends Socket.IO CONNECT as `40${authJson}` after Engine.IO open;
- token/serverId therefore land in the same Socket.IO auth payload the server already reads;
- the signed HAP no longer contains `libs/arm64-v8a/libclient_socket_c.so`.

Validation after reverting:

- `ohpm install --all` passed and removed stale local `@ohos/socketio` folders;
- OHOS `assembleHap` passed;
- `git diff --check HEAD~2 HEAD` passed;
- signed HAP SHA256: `43fdd92ee918abfa21c8807bb5835943a566fd20f0c8e905bd1c88566bf89537`.

### If We Ever Revisit `@ohos/socketio`

There are only two clean ways to use it with current Slock auth:

1. Extend/fork the package wrapper to expose auth.
   - Add an ArkTS API such as `connectWithAuth(uri, authJson)`.
   - In NAPI, build an `sio::object_message` containing `token` and `serverId`.
   - Call the `sio::client::connect(...)` overload that accepts auth.
   - Vendor/build the modified native library/HAR so the HAP carries our fixed binary.

2. Change the server middleware to accept fallback auth from headers/query.
   - Example inputs: `Authorization`, `X-Server-Id`, query `token`, query `serverId`.
   - This would make the third-party package usable without native fork.
   - It is a server contract change and must be intentional because it expands auth surface area.

Until one of those is done, self-maintained OHOS bridge is the safer route.

### Fork Patch Prepared

After confirming the gap, we prepared a fork-side upstream candidate:

- fork: `git@gitcode.com:gcw_wXOEWo30/openharmony_tpc_samples.git`
- branch: `fix/socketio-auth-payload`
- current commit: `909e6ede docs: simplify socketio auth sample`
- merge request URL from GitCode: `https://gitcode.com/CPF-ApplicationTPC/openharmony_tpc_samples/merge_requests/3003`

The fork patch adds an additive `set_auth(auth)` API, but the final public contract is intentionally object-only:

- ArkTS `client_socket.set_auth(auth: ESObject)`;
- NAPI `SocketIOClient::set_auth`;
- native optional `g_authMessage`;
- `connect(uri)` still preserves old behavior when auth is not set;
- if auth is set, native connect calls `clientInstance.connect(uri, g_optionMap, g_headerMap, g_authMessage)`;
- README/README_zh/CHANGELOG and Demo 009 were updated to show that `set_headers`, `set_option`, and `set_auth` are distinct;
- Demo 009 now passes a generic object payload such as `{ token }`, not a Slock-specific `serverId` example;
- the earlier string-auth parser path was removed because the package's `parseConfigString`-style parsing coerces values through strings and is not equivalent to Socket.IO JS `auth: { ... }` for numbers, booleans, or nested objects.

This makes the package capable of sending the Socket.IO CONNECT auth payload read by Node.js as `socket.handshake.auth`, without requiring Slock server to accept header/query fallback.

## What We Actually Need To Build Ourselves

The self-maintained OHOS bridge is not just "a WebSocket wrapper." It is responsible for the specific subset of Socket.IO behavior Slock depends on.

It must own:

- Engine.IO websocket URL construction: `/socket.io/?EIO=4&transport=websocket`;
- Engine.IO open packet handling (`0...`);
- Socket.IO auth connect packet (`40{ token, serverId }`);
- Engine.IO ping/pong (`2` -> `3`);
- Socket.IO event packet decoding (`42[...]`);
- Socket.IO emit packet encoding (`42[...]`);
- connect error handling (`44...`);
- reconnect scheduling after close/error;
- forwarding Slock wire events into shared KMP;
- resume after room setup;
- diagnostics that distinguish transport health from app-event health.

It does not yet need full generic Socket.IO coverage if Slock does not use those features, but the remaining missing pieces must be explicit:

- no binary event packets;
- no ack packet support;
- no namespace support beyond default namespace;
- no full parser/fixture test suite around Engine.IO close / Socket.IO disconnect / reconnect;
- no OHOS-side refreshed-token handoff before reconnect;
- no real-device proof that the newly wired refresh events and watchdog path behave on device.

That is acceptable only if we treat this bridge as a Slock-specific client and add tests/diagnostics around the supported subset.

## What We Are Still Missing

There are two different categories of gaps. The third-party package detour only concerned category A.

### A. OHOS Transport / Bridge Gaps

The hand-written OHOS bridge sends the right Socket.IO auth payload and has basic ping/pong. Task #65 also added field diagnostics and the app heartbeat watchdog. What it still lacks:

- parser coverage for Engine.IO close and Socket.IO disconnect;
- parser/fixture tests for open, ping, pong, connect error, event, reconnect;
- refreshed-token reconnect handoff from shared session state;
- real-device proof that `rooms:joined`, `heartbeat`, `message:new`, and `sync:resume:response` all traverse the bridge after login.

### B. Shared Realtime / React Parity Gaps

Task #65 fixed the shared/mobile parity gaps that could lose messages or leave Home stale: raw message payloads decode, heartbeat no longer advances the applied cursor, resume waits for `rooms:joined`, heartbeat silence forces reconnect, Android refreshes auth on reconnect/auth failure, missing refresh events map to typed refresh intents, server membership removal exits the current server, and Home unread/preview merge dedupes and suppresses self/current-conversation increments.

The most dangerous original combination was:

1. Mobile receives raw `message:new`.
2. Mapper drops it because it expects `{ message: ... }`.
3. Later heartbeat advances mobile `lastSeq`.
4. Reconnect emits `sync:resume` from the heartbeat seq.
5. Server thinks mobile already has the skipped message and does not backfill it.

That is why fixing payload shape and heartbeat cursor semantics has higher product impact than adding more low-level Socket.IO features.

Remaining shared parity caveats: React's legacy `daemon:status` listener is still not represented in the mobile event model, reminder and announcement events currently trigger conservative reloads rather than fine-grained local mutation, and durable agent activity / machine status recovery still uses refresh intents rather than the full React local merge policy.

## Implementation Update: Task #65

Implementation on 2026-06-09 closed the highest-risk loss/skip and stale-Home paths:

- KMP now accepts raw `message:new` / `message:updated` enriched message rows, while keeping the previous `{ message: ... }` envelope as a compatibility fallback.
- Shared cursor policy now distinguishes applied resume cursor from server max seq. `heartbeat.seq` updates server max only; it no longer advances applied `lastSeq`.
- Android and shared app-shell cursor updates use the same shared policy, so Android/KMP and OHOS-fed KMP events do not diverge on heartbeat handling.
- Shared realtime diagnostics now expose `serverMaxSeq`, emit heartbeat-gap backfill intents, and emit `sync:resume` only from a `rooms:joined` transition.
- Android Socket.IO now replays rooms on connect/reconnect and gates `sync:resume` on `rooms:joined`.
- OHOS bridge diagnostics now log Engine.IO open ping parameters, ping/pong, Socket.IO auth connect, Engine.IO close, Socket.IO disconnect, `rooms:joined`, app heartbeat, and `sync:resume`; OHOS room replay also moved to socket connect, with resume after `rooms:joined`.
- Shared `RealtimeHeartbeatWatchdog` now mirrors React's conservative thresholds: heartbeat silence over 90s plus all inbound event silence over 120s while connected forces reconnect. Android and OHOS both use that policy.
- Android refreshes/rebuilds Socket.IO auth on Manager `reconnect_attempt` and auth-related `connect_error`, so retry handshakes do not keep using a stale access token.
- Mobile now subscribes/maps channel updates, channel-member updates, machine status/capabilities/updates, agent created/deleted, server member changes, server membership removal, announcement new, and reminder fired/scheduled/canceled into typed refresh intents.
- Server membership removal for the current server disconnects realtime, clears selected server, and returns the app shell to auth/server restore flow instead of staying in stale server UI.
- Home realtime merge now dedupes resume/live messages by message id, counts multiple missed non-self messages, avoids unread increments for self-authored messages, suppresses unread when the affected conversation is currently visible, and still updates the preview to the latest message.

Remaining gaps from this audit after Task #65 and the 2026-06-11 typed-event follow-up: OHOS refreshed-token reconnect handoff, real-device proof, legacy `daemon:status` compatibility decision, and finer local mutation for announcement/reminder/agent activity/machine status surfaces.

## React / Server Contract

### Connection Setup

React creates a single Socket.IO client with:

- `autoConnect: false`
- `forceNew: true`
- websocket-only transport
- `auth: { token, serverId }`

Source: `third_party/slock-source/packages/web/src/api/socket.ts:38-49`.

React also refreshes auth in two places:

- `connect_error` auth failure recovery, including retrying with a token that REST already rotated (`socketAuthRecovery`) before spending another refresh token.
- `Manager` `reconnect_attempt` refresh/update so engine.io retries do not reuse stale `Socket.auth`.

Source: `third_party/slock-source/packages/web/src/api/socket.ts:59-105`, `:129-184`; `packages/web/src/utils/socketAuthRecovery.ts:13-35`; `packages/web/src/utils/socketReconnectAuthRefresh.ts:59-87`.

### Server Connect, Rooms, Resume, Heartbeat

The server Socket.IO middleware reads `socket.handshake.auth.token` and `socket.handshake.auth.serverId`, verifies JWT and server membership, then joins:

- `user:{userId}`
- `server:{serverId}`
- all accessible channel and DM rooms

Only after room setup does the server emit `rooms:joined`.

Source: `third_party/slock-source/packages/server/src/socket/index.ts:46-75`, `:100-123`.

`sync:resume` accepts `{ lastSeq }`, backfills missed messages across all channels up to 500, and emits `sync:resume:response`.

Source: `third_party/slock-source/packages/server/src/socket/index.ts:126-148`.

The server emits app-layer `heartbeat` every 15s with `{ seq: maxSeq, ts }`.

Source: `third_party/slock-source/packages/server/src/socket/index.ts:154-158`.

### React Socket.IO Source Inventory

React Socket.IO behavior is not isolated to one file. The current web contract is spread across these client-side sources:

| Source | Socket.IO responsibility | Mobile parity implication |
| --- | --- | --- |
| `packages/web/src/api/socket.ts` | Creates the singleton socket, uses websocket-only `forceNew`, builds `auth: { token, serverId }`, handles `connect_error`, destroys/recreates sockets after login/server switch, and refreshes auth on `reconnect_attempt`. | Android has equivalent websocket/auth basics but lacks reconnect-attempt token refresh; OHOS has only the token passed at bridge connect time. |
| `packages/web/src/components/layout/MainLayout.tsx` | Owns the global listener set for messages, tasks, agents, machines, channels, members, server membership, announcements, rooms, resume, heartbeat, and browser live-session recovery. | This is the canonical event coverage list mobile should compare against. |
| `packages/web/src/App.tsx` + `utils/browserRecoveryPolicy.ts` | On browser `online` / visible signals, retries auth restore and reloads live state when a stored session exists. | Android/OHOS need foreground/online recovery semantics at the shared shell level, not only socket reconnect. |
| `packages/web/src/store/channelStore.ts` | Emits `join:channel` as a raw channel id after channel create/open/join/DM open. | Android's transport matches this by special-casing `join:channel` to a raw string; OHOS also sends a string. |
| `packages/web/src/components/message/ThreadPanel.tsx` | Joins/leaves thread rooms, re-joins on socket `connect`, listens to raw `message:new` / `message:updated`, and keeps thread-local new-message state. | Mobile should treat thread room joins as a per-thread lifecycle, and should not rely only on server auto-joined channel rooms. |
| `packages/web/src/hooks/useChannelMembers.ts` | Listens to `channel:members-updated` for the currently open channel and refetches channel members. | Mobile has no typed event/refresh path for this event today. |
| `packages/web/src/components/agent/AgentDetailPanel.tsx` | Agent detail listens to `reminder:fired`, `reminder:scheduled`, `reminder:canceled`, and reloads reminders on reconnect. | Mobile reminder surfaces will stay stale unless this scoped listener pattern is added. |
| `packages/web/src/utils/activityTrajectoryRecovery.ts` | Reloads durable agent activity logs after socket reconnect while an agent activity tab is open. | Mobile `agent:activity` should distinguish live presence from durable activity history and refetch on reconnect where a detail screen is open. |
| `packages/web/src/utils/machineStatusRecovery.ts` | Applies machine offline quickly, forces attached agents offline, and reloads machines/agents on machine online. | Mobile cannot simply map `machine:status` to a local field; online needs authoritative reload to unstick agents. |
| `packages/web/src/utils/socketAuthRecovery.ts`, `socketReconnectAuthRefresh.ts`, `socketSessionPolicy.ts` | Encodes auth-error and Manager-reconnect token-refresh policy in tested pure helpers. | Mobile should port the policy shape rather than only checking token expiry before explicit connect. |

React also has tests around these decisions (`socketAuthRecovery.test.ts`, `socketReconnectAuthRefresh.test.ts`, `socketSessionPolicy.test.ts`, `browserRecoveryPolicy.test.ts`, `mainLayoutSocketEffectDeps.test.ts`, `messageWindowGapRecovery.test.ts`, `channelRealtimeSync.test.ts`, `agentStatusRecovery.test.ts`, `machineStatusMerge.test.ts`). Mobile needs equivalent shared tests for the same contract edges, not just transport connection tests.

### React Listeners And Recovery Semantics

React listens for the main message/task/member/activity events in `MainLayout`:

- `message:new`
- `message:updated`
- `agent:activity`
- `dm:new`
- `machine:status`
- `machine:capabilities`
- `machine:updated`
- `daemon:status`
- `agent:created`
- `agent:deleted`
- `channel:updated`
- `channel:members-updated`
- `server:plan-updated`
- `server:member-added`
- `server:member:left`
- `server:member-removed`
- `server:member-updated`
- `server:membership-removed`
- `thread:updated`
- `announcement:new`
- `task:created`
- `task:updated`
- `task:deleted`

Source: `third_party/slock-source/packages/web/src/components/layout/MainLayout.tsx:829-1006`.

On `connect`, React reloads durable state: unread counts, sidebar order, machines, agents, servers, followed threads, inbox.

Source: `third_party/slock-source/packages/web/src/components/layout/MainLayout.tsx:1045-1067`.

React waits for `rooms:joined`, then emits `sync:resume` using `useMessageStore.lastSeq`, syncs visible scopes, reloads unread, and refreshes inbox.

Source: `third_party/slock-source/packages/web/src/components/layout/MainLayout.tsx:1069-1083`.

React handles `heartbeat` differently from mobile: it does not advance message `lastSeq`; it compares `heartbeat.seq` to the applied message `lastSeq` and calls `syncVisibleScopes()` if the server is ahead.

Source: `third_party/slock-source/packages/web/src/components/layout/MainLayout.tsx:1113-1119`.

React also has a client-side circuit breaker: if heartbeat is silent for more than 90s and all inbound server events are silent for more than 120s while connected, it disconnects and reconnects.

Source: `third_party/slock-source/packages/web/src/components/layout/MainLayout.tsx:1121-1133`.

### Message Payload Shape

Server emits `message:new` as the enriched message object itself, not `{ message: enriched }`.

Source: `third_party/slock-source/packages/server/src/services/messageService.ts:393-400`.

React receives `message:new` as `msg` and passes it directly to `addMessage(msg)`.

Source: `third_party/slock-source/packages/web/src/components/layout/MainLayout.tsx:829-841`.

Server emits `message:updated` as the enriched message object itself, including task mutation paths.

Source examples:

- `third_party/slock-source/packages/server/src/routes/tasks.ts:256-264`
- `third_party/slock-source/packages/server/src/routes/messages.ts:420-478`
- `third_party/slock-source/packages/server/src/routes/internalAgentApi.ts:475-482`

React receives `message:updated` as `msg` and merges directly.

Source: `third_party/slock-source/packages/web/src/components/layout/MainLayout.tsx:843-847`.

### Server Event Inventory And Mobile Status

| Event | Server emit source | React behavior | Android/OHOS current status |
| --- | --- | --- | --- |
| `rooms:joined` | `packages/server/src/socket/index.ts` after room setup | Starts global `sync:resume`, visible-scope sync, unread/inbox refresh | Android/OHOS now gate global resume on `rooms:joined`; room replay happens on socket connect/reconnect. |
| `sync:resume:response` | `packages/server/src/socket/index.ts` | Batch-adds raw missed messages and advances `lastSeq` to `currentSeq` | Subscribed and decoded, but raw message DTO must be kept aligned with server shape. |
| `heartbeat` | `packages/server/src/socket/index.ts` every 15s | Does not advance applied message cursor; triggers visible gap sync and silence watchdog | Subscribed; now updates server max only, emits heartbeat-gap backfill intent, and shares React watchdog thresholds. |
| `message:new` | `messageService.ts`, `routes/tasks.ts`, `actionCardsService.ts`, internal routes | Adds raw message row, reorders channel/DM, schedules inbox refresh | Subscribed; mapper now accepts raw server rows with `{ message: ... }` fallback. |
| `message:updated` | `routes/messages.ts`, `routes/tasks.ts`, `internalAgentApi.ts`, action-card/internal routes | Merge-only update of raw message row | Subscribed; mapper now accepts raw server rows with `{ message: ... }` fallback. |
| `dm:new` | `messageService.ts` | Joins DM room, refreshes DM channel list/inbox | Subscribed and maps to `DirectMessageCreated`; Android joins the channel after dispatch. |
| `thread:updated` | `messageService.ts` | Updates thread summaries, followed-thread unread, inbox | Subscribed and decoded; Home/thread unread semantics are simpler than React. |
| `task:created/updated/deleted` | `taskRealtimeEvents.ts`, `routes/tasks.ts`, internal task routes | Upserts/removes task-board rows; paired message events update chat flow | Subscribed and decoded for task store writes, but message update envelope bug can leave chat row stale. |
| `agent:activity` | `agentOrchestrator.ts` | Updates status, live activity, trajectory, with `serverSeq` dedupe | Still maps to member presence; durable activity trajectory recovery is not fully React-equivalent. |
| `machine:status` | `agentOrchestrator.ts` | Updates machine status, forces attached agents offline on offline, reloads machines/agents on online | Subscribed/mapped to Machines refresh intent. |
| `machine:capabilities` | `agentOrchestrator.ts` | Updates runtimes/host/os/daemon version | Subscribed/mapped to Machines refresh intent. |
| `machine:updated` | `routes/servers.ts` | Reloads machines | Subscribed/mapped to Machines refresh intent. |
| `daemon:status` | React-only legacy transition listener; current checked server source has no emit | Updates machine status by `daemonId` alias | Still not subscribed/mapped; decide whether to drop as obsolete or add alias compatibility. |
| `agent:created/deleted` | `routes/agents.ts` | Reloads agents | Subscribed/mapped to Agents refresh intent. |
| `channel:updated` | `routes/channels.ts`, onboarding/action-card services | Reloads channel list; joined/private projections refresh via user/server rooms | Subscribed/mapped to Home refresh intent. |
| `channel:members-updated` | `routes/channels.ts`, internal membership routes | Reloads channel list globally and channel members in open member hooks | Subscribed/mapped to ChannelMembers refresh intent. |
| `server:member-added/left/removed/updated` | `routes/auth.ts`, `routes/servers.ts` | Reloads member list for active server | Subscribed/mapped to ServerMembers refresh intent. |
| `server:membership-removed` | `routes/servers.ts` | Reloads servers and exits the removed current server | Subscribed/mapped; current-server removal disconnects realtime and clears selected server. |
| `server:deleted` | `routes/servers.ts` | N/A (server emits this as non-authoritative domain event, React may not handle separately) | ✅ Added 2026-06-11: typed event, mapper, handler, Android/OHOS whitelist. |
| `server:plan-updated` | `billingService.ts` | Reloads servers | ✅ Added 2026-06-11: typed event, mapper, handler, Android/OHOS whitelist. |
| `announcement:new` | `routes/announcements.ts` | Inserts/dedupes account announcement | Subscribed/mapped to Announcements refresh/load path. |
| `reminder:fired/scheduled/canceled` | `reminderScheduler.ts`, `agentOrchestrator.ts`, internal reminder routes | Agent detail updates/removes/schedules reminder rows and reloads on reconnect | Subscribed/mapped to Reminders refresh intent; still conservative reload rather than fine-grained Agent detail local mutation. |

## Android / KMP Implementation

### Android Native Transport

Android uses `io.socket:socket.io-client:2.1.2`, websocket-only transport, reconnection enabled, and auth `{ token, serverId }`.

Source: `compose/gradle/libs.versions.toml:23`, `:55`; `compose/androidApp/src/main/java/ai/slock/android/core/data/realtime/SocketIoSocketTransport.kt:27-33`.

Android bridges Manager-level reconnect events (`reconnect`, `reconnect_attempt`, `reconnect_error`, `reconnect_failed`) by binding those names on `socket.io()`.

Source: `compose/androidApp/src/main/java/ai/slock/android/core/data/realtime/SocketIoSocketTransport.kt:87-101`.

Android emits `join:channel` as the raw channel id string, which matches server's `socket.on("join:channel", async (channelId: string) => ...)`.

Source: `compose/androidApp/src/main/java/ai/slock/android/core/data/realtime/SocketIoSocketTransport.kt:105-110`; server source `third_party/slock-source/packages/server/src/socket/index.ts:160-168`.

Risk: Android does not set React's `forceNew: true` equivalent in `IO.Options`. Because this transport constructs a new Socket on every connect, it may be fine, but it is not source-aligned with React's explicit "new manager/session" behavior.

### Android Token Refresh

Android refreshes token before realtime connect if the token is expired/near expiry/missing exp, and preserves session on unauthorized when called from realtime.

Source: `compose/androidApp/src/main/java/ai/slock/android/core/data/realtime/SessionRealtimeRepository.kt:20-25`; `compose/androidApp/src/main/java/ai/slock/android/core/data/auth/AuthTokenRefresher.kt:26-35`, `:57-90`.

Task #65 added React-style reconnect auth refresh: Android updates Socket.IO auth on Manager `reconnect_attempt`, and retries with refreshed auth on auth-related `connect_error`.

### KMP Event Names

KMP event constants include the core events:

- `rooms:joined`
- `sync:resume`
- `sync:resume:response`
- `join:channel`
- `heartbeat`
- `message:new`
- `message:updated`
- `dm:new`
- `thread:updated`
- `task:*`
- `agent:activity`
- `server:member-*`
- `channel:updated`
- `channel:members-updated`
- `agent:created`
- `agent:deleted`
- `machine:status`
- `machine:capabilities`
- `machine:updated`
- `server:membership-removed`
- `server:deleted`
- `server:plan-updated`
- `announcement:new`
- `reminder:fired`
- `reminder:scheduled`
- `reminder:canceled`

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/realtime/RealtimeSocketEvents.kt`.

Android and OHOS now subscribe to the same expanded Slock event set above. `server:plan-updated` and `server:deleted` were added 2026-06-11. `daemon:status` removed from the gap list — server no longer emits this event.

### KMP Message Decode Mismatch

Original audit finding: KMP decoded `message:new` and `message:updated` through `MessageEnvelopeDto`, requiring a top-level `message` property, while server and React use raw message rows.

Task #65 fixed this by decoding raw `MessageWireDto` first and keeping `{ message: ... }` as compatibility fallback.

The old tests reinforced the wrong shape by sending:

```kotlin
mapOf(
    "message" to mapOf(
        "id" to "message-1",
        "threadId" to "channel-1",
        "channelId" to "channel-1",
        "seq" to 89,
        "content" to "Realtime body"
    )
)
```

Source: `compose/androidApp/src/test/java/ai/slock/android/core/data/realtime/RealtimeRepositoryTest.kt:285-306`.

Canonical tests now use the raw message row:

```json
{
  "id": "message-1",
  "threadId": "channel-1",
  "channelId": "channel-1",
  "seq": 89,
  "content": "Realtime body"
}
```

Original impact before Task #65:

- Live `message:new` can be logged as received but map to null.
- Home preview/unread is not updated by live message events.
- Thread panels miss live message events.
- `lastSeq` is not advanced by message events, then heartbeat may incorrectly advance it instead.

### KMP Heartbeat Cursor Mismatch

Original audit finding: KMP updated its resume cursor from heartbeat:

```kotlin
if (event is Heartbeat) lastSeq = maxOf(lastSeq, event.seq)
```

Source: `compose/androidApp/src/main/java/ai/slock/android/core/data/realtime/SocketIoRealtimeClient.kt:176-180`.

The app shell also updates `realtimeLastSeq` from heartbeat:

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsHomePage.kt:6958-6964`.

React does not do this. React compares heartbeat server max seq to applied message seq and syncs visible scopes if the server is ahead.

Task #65 fixed this with shared `RealtimeCursorPolicy`: heartbeat updates `serverMaxSeq` only, never the applied resume cursor.

Impact:

If mobile drops `message:new` because of the payload mismatch, a later `heartbeat.seq` can move `lastSeq` forward to the server max. On reconnect, mobile emits `sync:resume` using that advanced cursor and can skip the exact missing message forever.

### Resume Gating Difference

React only emits global `sync:resume` after `rooms:joined`.

Source: `third_party/slock-source/packages/web/src/components/layout/MainLayout.tsx:1069-1079`.

Original audit finding: KMP emitted `sync:resume` on raw `connect`, on `rooms:joined`, and on `reconnect`:

Source: `compose/androidApp/src/main/java/ai/slock/android/core/data/realtime/SocketIoRealtimeClient.kt:87-99`, `:116-122`, `:170-174`.

Task #65 aligned mobile with React: global resume is now emitted from the `rooms:joined` transition.

### Home Merge Path

Home applies realtime by writing the event into the local store and then reading the cached Home snapshot back if the event is considered Home-affecting.

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/HomeDataSource.kt:104-111`, `:131-141`; `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SharedHomeStore.kt:128-156`.

`Heartbeat` intentionally does not rewrite Home.

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ComposeLocalWriteThrough.kt:141-153`.

Live messages and resume messages flow through `writeRealtimeMessages(...)`.

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ComposeLocalWriteThrough.kt:253-286`.

Task #65 hardened Home preview/unread update:

- it still updates preview from the latest message by seq;
- it dedupes unread candidates against cached thread messages by message id;
- it counts multiple missed `sync:resume` messages per stream;
- it excludes self-authored messages from unread increments;
- it suppresses unread increments for the currently visible conversation;
- it maps `server:member-*` to a refresh intent instead of a silent no-op.

Remaining caveat: mobile still does not have every React near-bottom/read-receipt nuance. Current-conversation suppression is a conservative Home-level guard, not a full Thread scroll-position/read-state model.

## OHOS Implementation

### Low-Level Socket.IO / Engine.IO

OHOS uses a custom ArkTS Socket.IO client over `@ohos.net.webSocket`, websocket-only:

`wss://.../socket.io/?EIO=4&transport=websocket`

Source: `compose/ohosApp/entry/src/main/ets/kuikly/OhosSocketIoClient.ets:236-245`.

It handles:

- Engine.IO open packet `"0"` -> sends Socket.IO connect packet `"40{auth}"`
- Engine.IO ping packet `"2"` -> sends pong packet `"3"`
- Socket.IO connect packet `"40"` -> marks connected
- Socket.IO connect error packet `"44"` -> reports connect error
- Socket.IO event packet `"42"` -> parses `[eventName, payload]`

Source: `compose/ohosApp/entry/src/main/ets/kuikly/OhosSocketIoClient.ets:177-204`, `:206-220`.

So OHOS has basic ping/pong. Missing low-level pieces:

- no handling for Engine.IO close packet `"1"`
- no Socket.IO ack packet `"43"`
- no Socket.IO disconnect packet `"41"`
- no binary packets
- no ping/pong diagnostics logging, so field logs cannot currently prove the pong path fired

Those are not necessarily blockers for current Slock events, but diagnostics should log open ping interval/timeout, ping received, pong sent, and heartbeat received.

### OHOS Bridge Semantics

OHOS bridge subscribes to:

- `message:new`
- `dm:new`
- `message:updated`
- `thread:updated`
- `channel:updated`
- `channel:members-updated`
- `task:*`
- `agent:activity`
- `agent:created`
- `agent:deleted`
- `machine:status`
- `machine:capabilities`
- `machine:updated`
- `server:member-*`
- `server:membership-removed`
- `server:deleted`
- `server:plan-updated`
- `announcement:new`
- `reminder:fired`
- `reminder:scheduled`
- `reminder:canceled`
- `sync:resume:response`
- `heartbeat`

Source: `compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosRealtimeBridge.ets` `WIRE_EVENTS`.

It handles `rooms:joined` specially: rejoin locally tracked channels and emit `sync:resume` when `lastSeq > 0`.

Source: `compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosRealtimeBridge.ets:124-145`.

It sends raw `join:channel` with a string payload and `sync:resume` with `{ lastSeq }`.

Source: `compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosRealtimeBridge.ets:148-170`.

Current caveats vs Android/React:

- OHOS does not forward `connect`, `disconnect`, `reconnect_attempt`, `reconnect_error`, or `reconnect_failed` to shared code. It logs only.
- OHOS uses the access token passed from shared state; no source-visible reconnect-attempt token refresh equivalent exists in the ArkTS bridge.
- OHOS now uses the shared app heartbeat-silence policy, but this still needs real-device proof.

Source for raw fallback and deprecated legacy payload branch: `compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsHomePage.kt` `decodeSharedShellRealtimeEvent()`.

## Gap Matrix

| Area | React behavior | Android/KMP/OHOS behavior | Risk |
| --- | --- | --- | --- |
| Engine.IO ping/pong | Socket.IO client handles internally | Android library handles internally; OHOS replies `"2"` -> `"3"` | Basic transport heartbeat likely OK |
| App heartbeat | `heartbeat.seq > messageLastSeq` triggers visible gap sync; silence watchdog reconnects | Heartbeat updates server max only; Android/OHOS share watchdog thresholds | Needs real-device proof, source path aligned |
| Message payload | Raw enriched message object | Mapper accepts raw row first, envelope fallback second | Source path aligned |
| `message:updated` | Raw enriched message object | Mapper accepts raw row first, envelope fallback second | Source path aligned |
| Resume timing | Waits for `rooms:joined` | Android/OHOS now gate global resume on `rooms:joined` | Source path aligned |
| Auth on reconnect | Updates/refreshes auth before Manager retry | Android refreshes on reconnect/auth failure; OHOS still uses current token only | OHOS stale-token reconnect still possible |
| Channel updates | `channel:updated`, `channel:members-updated` reload channels | Subscribed/mapped to refresh intents | Source path aligned |
| Server membership removal | Clears current server and navigates out | Subscribed/mapped; current server is cleared/exited | Source path aligned |
| Machine/agent status | Machine/agent events reload/update stores | Machine/agent create/update events map to refresh intents; `agent:activity` remains presence-only | Durable activity/local machine merge still not full React parity |
| Announcements/reminders | Announcement and Agent detail reminder events handled | Subscribed/mapped to conservative refresh/load paths | Fine-grained local mutation still missing |
| Member events | React reloads members on `server:member-*` | Mapped to ServerMembers refresh intent | Source path aligned |
| Home unread merge | React counts deduped missed messages and knows current channel state | KMP counts deduped non-self messages and suppresses active conversation increments | Near-bottom/read-receipt nuance still not full React parity |

## Dual-End Missing Work

This is the actionable split between Android-native, OHOS-native, and shared KMP. Most product-visible fixes belong in shared KMP; transport-only work is not enough.

### Shared KMP Remaining Work

Task #65 completed the shared payload/cursor/resume/refresh-intent/Home-unread work. Remaining shared items:

- Decide `daemon:status` compatibility explicitly. Current server source does not emit it, but React still has a legacy listener; if we keep React parity literally, map it to the existing machine-status refresh path.
- Replace conservative refresh intents with finer local mutation where the product needs it, especially durable agent activity trajectory, machine online/offline merge, and Agent detail reminders.
- Add broader shared contract tests based on pinned server payload fixtures beyond the focused raw-message/watchdog/Home tests already added.
- Add real read-position / near-bottom awareness if Thread unread parity needs to go beyond current visible-conversation suppression.
- Remove the now-unreachable `parseSharedShellRealtimeEvent()` legacy helper once no test/tool still depends on old `message_new` payloads.

### Android Remaining Work

Task #65 completed the duplicate-resume, reconnect-auth, heartbeat-watchdog, event-subscription, and diagnostics work. Remaining Android items:

- Device/network proof for stale-token reconnect and heartbeat-silence reconnect.
- Add legacy `daemon:status` subscription only if the shared model keeps that React compatibility alias.

### OHOS Remaining Work

- Add parser fixtures for Engine.IO open, ping, close; Socket.IO connect, connect error, event, disconnect; and reconnect-after-close.
- Forward connection lifecycle events (`connect`, `disconnect`, reconnect attempt/error/failed or equivalent bridge states) into shared diagnostics.
- Add token-refresh/reconnect handoff. The bridge currently uses the token it was given; it needs a path to refresh or request a new access token before reconnect if the access token is near expiry/expired.
- Add real-device proof for field diagnostics and heartbeat watchdog.
- Add legacy `daemon:status` forwarding only if the shared model keeps that React compatibility alias.

## Confirmation Plan

Use logs to separate "socket dead" from "events decode/merge wrong".

### 1. Transport Liveness

Add temporary OHOS logs in `OhosSocketIoClient`:

- Engine.IO open payload: sid, `pingInterval`, `pingTimeout`
- `engine_ping_received`
- `engine_pong_sent`
- Socket.IO `connect`
- WebSocket `close/error`

Expected: after connect, OHOS should log pings roughly on server schedule and pong sent. If no pong path fires, low-level transport is broken.

### 2. App Heartbeat

On OHOS app shell, expect:

- `rooms:joined`
- `sync:resume` when lastSeq > 0
- `heartbeat` every about 15s
- shared event type `heartbeat`
- `write_realtime_skip type=heartbeat seq=...`

If Engine.IO ping/pong works but app `heartbeat` does not arrive, the issue is Socket.IO event parsing/bridge forwarding, not network ping/pong.

### 3. Live Message Decode

Send one message from React or CLI while OHOS Home is open. Log raw event keys and mapper result.

Expected before Task #65 if the mismatch was active:

- OHOS/Android receives event name `message:new`
- raw payload top-level keys include `id`, `channelId`, `seq`, `content`
- mapper result is null or no `message_new` shared event is forwarded

That confirms the payload-shape bug.

### 4. Resume Cursor Safety

Reproduce with:

1. Open OHOS Home.
2. Record applied message `lastSeq`.
3. Cause a live message to be missed/dropped.
4. Wait for heartbeat with higher `seq`.
5. Reconnect.

If mobile emits `sync:resume` with the heartbeat max seq rather than the last applied message seq, resume will skip the missed message. Task #65 fixed the source path; this test is now a regression check.

### 5. Home Merge

After fixing decode/cursor first, retest Home:

- new channel message preview updates
- unread increments by deduped missed message count
- current visible thread/channel does not over-increment unread
- DM creation reloads Home and joins room
- thread reply updates parent reply count

## Recommended Next Fix Order After Task #65

### P0

1. Real-device proof for Android and OHOS realtime reconnect/resume.
   - Prove `rooms:joined`, `heartbeat`, raw `message:new`, `sync:resume:response`, and heartbeat-silence reconnect on device.
   - Prove Android stale-token reconnect updates Socket.IO auth before retry.

2. OHOS refreshed-token reconnect handoff.
   - The bridge currently reconnects with the token it was given.
   - It needs a way to request or receive a refreshed session before reconnect when the access token is near expiry/expired.

### P1

3. Add remaining legacy event coverage if still used.
   - `daemon:status`

4. Replace conservative refresh intents where fine-grained local parity matters.
   - durable agent activity trajectory
   - machine online/offline merge
   - Agent detail reminder rows
   - announcement dedupe/local insertion

5. Add OHOS Socket.IO parser tests.
   - Engine.IO open/ping/pong/close
   - Socket.IO connect/connect error/event/disconnect
   - reconnect after close

### P2

6. Expand live contract tests against pinned server payload fixtures.
   - raw `message:new`
   - raw `message:updated`
   - raw messages in `sync:resume:response`
   - heartbeat must not advance applied resume cursor
   - Home unread active/self/dedupe behavior

## Current Answer To The Original Question

After Task #65, mobile source is aligned with the React Socket.IO semantics that were most likely to cause lost messages or stale Home state.

The OHOS implementation did already include the low-level Engine.IO ping/pong path; the original likely defects were at the app Socket.IO layer: raw message decoding, heartbeat cursor handling, resume timing, app heartbeat silence recovery, missing refresh events, server removal handling, and Home unread merge. Those source paths are now fixed. Remaining work is proof and narrower parity: OHOS token refresh on reconnect, real-device verification, the React-only legacy `daemon:status` compatibility decision, deprecated parser cleanup, and finer local mutation for some refresh-driven surfaces.
