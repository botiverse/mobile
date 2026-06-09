# Socket.IO React Parity Audit

Date: 2026-06-09
Owner: Cindy
Scope: React/web Socket.IO source, server Socket.IO contract, Android/KMP realtime client, OHOS ArkTS Socket.IO bridge, and Home merge effects.

## Executive Summary

The OHOS bridge is not missing the basic Engine.IO ping/pong. `OhosSocketIoClient.ets` handles Engine.IO ping packet `"2"` and replies with pong packet `"3"` (`compose/ohosApp/entry/src/main/ets/kuikly/OhosSocketIoClient.ets:181-187`). So if the question is strictly "does OHOS answer the Socket.IO transport heartbeat?", the source says yes.

The mobile stack is still not truly aligned with React. The highest-risk gaps are above the low-level ping/pong layer:

1. KMP decodes `message:new` / `message:updated` as `{ message: ... }`, but server and React use the raw enriched message object directly. This can make live messages silently drop on Android/OHOS while heartbeat still looks healthy.
2. Mobile advances resume `lastSeq` from `heartbeat.seq`. React does not. React treats heartbeat seq as "server has newer data" and triggers gap sync; mobile can move the resume cursor past missed messages, making later resume skip the missing rows.
3. React has a heartbeat-silence circuit breaker; Android/KMP and OHOS do not currently have equivalent forced reconnect when app-layer heartbeat stops but the socket remains logically connected.
4. React listens to more realtime events than mobile: channel updates, channel member updates, machine status/capabilities, agent created/deleted, server membership removal, announcements, reminders, and server plan changes are missing or mapped to no-op on mobile.
5. Home merge state can be wrong even when the socket itself is alive, because `SyncResumeResponse`/live message writes increment unread and previews in a simplified way, and because live `message:new` may not decode at all.

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

It does not yet need full generic Socket.IO coverage if Slock does not use those features, but the missing pieces must be explicit:

- no binary event packets;
- no ack packet support;
- no namespace support beyond default namespace;
- no Engine.IO close packet handling;
- no app-layer heartbeat watchdog;
- no reconnect token refresh parity;
- limited diagnostics for ping/pong and auth connect payload.

That is acceptable only if we treat this bridge as a Slock-specific client and add tests/diagnostics around the supported subset.

## What We Are Still Missing

There are two different categories of gaps. The third-party package detour only concerned category A.

### A. OHOS Transport / Bridge Gaps

The hand-written OHOS bridge currently sends the right Socket.IO auth payload, and it has basic ping/pong. What it still lacks:

- parser coverage for Engine.IO close and Socket.IO disconnect;
- parser/fixture tests for open, ping, pong, connect error, event, reconnect;
- field logs for ping interval/timeout, ping received, pong sent, auth connect sent, app heartbeat received;
- heartbeat-silence reconnect watchdog;
- refreshed-token reconnect path;
- real-device proof that `rooms:joined`, `heartbeat`, `message:new`, and `sync:resume:response` all traverse the bridge after login.

### B. Shared Realtime / React Parity Gaps

Even with a perfect OHOS transport, mobile remains behind React until these are fixed:

- `message:new` / `message:updated` must decode raw enriched message rows, not only `{ message: ... }`;
- heartbeat `seq` must not advance applied resume `lastSeq`;
- `sync:resume` should be gated on `rooms:joined` rather than raw socket connect;
- mobile needs React-style app heartbeat silence recovery;
- Android reconnect attempts need stale-token auth refresh parity;
- missing React events need subscriptions and typed refresh behavior;
- server membership removal must clear/navigate out like React;
- `server:member-*` cannot stay silent no-op if member list/Home must refresh;
- Home unread/preview merge must count deduped missed messages and account for current visible scope/self-authored messages.

The most dangerous current combination is:

1. Mobile receives raw `message:new`.
2. Mapper drops it because it expects `{ message: ... }`.
3. Later heartbeat advances mobile `lastSeq`.
4. Reconnect emits `sync:resume` from the heartbeat seq.
5. Server thinks mobile already has the skipped message and does not backfill it.

That is why fixing payload shape and heartbeat cursor semantics has higher product impact than adding more low-level Socket.IO features.

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
| `rooms:joined` | `packages/server/src/socket/index.ts` after room setup | Starts global `sync:resume`, visible-scope sync, unread/inbox refresh | Android/OHOS receive it, but Android also resumes on raw `connect` / `reconnect`; OHOS resumes only when `lastSeq > 0`. |
| `sync:resume:response` | `packages/server/src/socket/index.ts` | Batch-adds raw missed messages and advances `lastSeq` to `currentSeq` | Subscribed and decoded, but raw message DTO must be kept aligned with server shape. |
| `heartbeat` | `packages/server/src/socket/index.ts` every 15s | Does not advance applied message cursor; triggers visible gap sync and silence watchdog | Subscribed, but advances mobile resume cursor and has no silence watchdog. |
| `message:new` | `messageService.ts`, `routes/tasks.ts`, `actionCardsService.ts`, internal routes | Adds raw message row, reorders channel/DM, schedules inbox refresh | Subscribed, but mapper expects `{ message: ... }`; raw server rows can drop. |
| `message:updated` | `routes/messages.ts`, `routes/tasks.ts`, `internalAgentApi.ts`, action-card/internal routes | Merge-only update of raw message row | Same envelope mismatch as `message:new`. |
| `dm:new` | `messageService.ts` | Joins DM room, refreshes DM channel list/inbox | Subscribed and maps to `DirectMessageCreated`; Android joins the channel after dispatch. |
| `thread:updated` | `messageService.ts` | Updates thread summaries, followed-thread unread, inbox | Subscribed and decoded; Home/thread unread semantics are simpler than React. |
| `task:created/updated/deleted` | `taskRealtimeEvents.ts`, `routes/tasks.ts`, internal task routes | Upserts/removes task-board rows; paired message events update chat flow | Subscribed and decoded for task store writes, but message update envelope bug can leave chat row stale. |
| `agent:activity` | `agentOrchestrator.ts` | Updates status, live activity, trajectory, with `serverSeq` dedupe | Subscribed as member presence only; no durable activity trajectory recovery. |
| `machine:status` | `agentOrchestrator.ts` | Updates machine status, forces attached agents offline on offline, reloads machines/agents on online | Not subscribed/mapped. |
| `machine:capabilities` | `agentOrchestrator.ts` | Updates runtimes/host/os/daemon version | Not subscribed/mapped. |
| `machine:updated` | `routes/servers.ts` | Reloads machines | Not subscribed/mapped. |
| `daemon:status` | legacy transition event | Updates machine status | Not subscribed/mapped. |
| `agent:created/deleted` | `routes/agents.ts` | Reloads agents | Not subscribed/mapped. |
| `channel:updated` | `routes/channels.ts`, onboarding/action-card services | Reloads channel list; joined/private projections refresh via user/server rooms | Not subscribed/mapped. |
| `channel:members-updated` | `routes/channels.ts`, internal membership routes | Reloads channel list globally and channel members in open member hooks | Not subscribed/mapped. |
| `server:plan-updated` | `billingService.ts` | Reloads servers | Not subscribed/mapped. |
| `server:member-added/left/removed/updated` | `routes/auth.ts`, `routes/servers.ts` | Reloads member list for active server | Android subscribes but maps to null; OHOS forwards raw fallback to mapper, which also returns null. |
| `server:membership-removed` | `routes/servers.ts` | Reloads servers and exits the removed current server | Not subscribed/mapped. |
| `announcement:new` | `routes/announcements.ts` | Inserts/dedupes account announcement | Not subscribed/mapped. |
| `reminder:fired/scheduled/canceled` | `reminderScheduler.ts`, `agentOrchestrator.ts`, internal reminder routes | Agent detail updates/removes/schedules reminder rows and reloads on reconnect | Not subscribed globally; no scoped mobile Agent detail parity. |

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

Gap vs React: Android does not mirror React's `reconnect_attempt` stale-token recovery. `SocketIoRealtimeClient` only logs reconnect attempts; it does not refresh auth before the Manager retry handshake.

Source: `compose/androidApp/src/main/java/ai/slock/android/core/data/realtime/SocketIoRealtimeClient.kt:123-139`.

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

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/realtime/RealtimeSocketEvents.kt:9-34`.

Android client subscribes only to:

- message/direct-message/thread/task/activity/server-member/resume/heartbeat events

Source: `compose/androidApp/src/main/java/ai/slock/android/core/data/realtime/SocketIoRealtimeClient.kt:197-212`.

Missing vs React:

- `machine:status`
- `machine:capabilities`
- `machine:updated`
- `daemon:status`
- `agent:created`
- `agent:deleted`
- `channel:updated`
- `channel:members-updated`
- `server:plan-updated`
- `server:membership-removed`
- `announcement:new`
- `reminder:fired`
- `reminder:scheduled`
- `reminder:canceled`

This means mobile can stay stale for machine status, channel membership/name/join state, agent list, server removal, announcements, and reminder changes unless another REST refresh happens.

### KMP Message Decode Mismatch

KMP currently decodes `message:new` and `message:updated` through `MessageEnvelopeDto`, requiring a top-level `message` property.

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/realtime/RealtimeEventMapper.kt:97-103`, `:183-186`.

But the server emits raw message rows and React consumes raw message rows. Therefore current mobile live message decoding is likely wrong.

The tests currently reinforce the wrong shape by sending:

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

Expected canonical test shape should be the raw message row:

```json
{
  "id": "message-1",
  "threadId": "channel-1",
  "channelId": "channel-1",
  "seq": 89,
  "content": "Realtime body"
}
```

Impact:

- Live `message:new` can be logged as received but map to null.
- Home preview/unread is not updated by live message events.
- Thread panels miss live message events.
- `lastSeq` is not advanced by message events, then heartbeat may incorrectly advance it instead.

### KMP Heartbeat Cursor Mismatch

KMP updates its resume cursor from heartbeat:

```kotlin
if (event is Heartbeat) lastSeq = maxOf(lastSeq, event.seq)
```

Source: `compose/androidApp/src/main/java/ai/slock/android/core/data/realtime/SocketIoRealtimeClient.kt:176-180`.

The app shell also updates `realtimeLastSeq` from heartbeat:

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsHomePage.kt:6958-6964`.

React does not do this. React compares heartbeat server max seq to applied message seq and syncs visible scopes if the server is ahead.

Impact:

If mobile drops `message:new` because of the payload mismatch, a later `heartbeat.seq` can move `lastSeq` forward to the server max. On reconnect, mobile emits `sync:resume` using that advanced cursor and can skip the exact missing message forever.

### Resume Gating Difference

React only emits global `sync:resume` after `rooms:joined`.

Source: `third_party/slock-source/packages/web/src/components/layout/MainLayout.tsx:1069-1079`.

KMP emits `sync:resume` on raw `connect`, on `rooms:joined`, and on `reconnect`:

Source: `compose/androidApp/src/main/java/ai/slock/android/core/data/realtime/SocketIoRealtimeClient.kt:87-99`, `:116-122`, `:170-174`.

This can cause duplicate resume responses and differs from the server comment that room setup completion is the safe point for gap sync.

### Home Merge Path

Home applies realtime by writing the event into the local store and then reading the cached Home snapshot back if the event is considered Home-affecting.

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/HomeDataSource.kt:104-111`, `:131-141`; `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SharedHomeStore.kt:128-156`.

`Heartbeat` intentionally does not rewrite Home.

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ComposeLocalWriteThrough.kt:141-153`.

Live messages and resume messages flow through `writeRealtimeMessages(...)`.

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ComposeLocalWriteThrough.kt:253-286`.

Home preview/unread update is simplified:

- it takes the latest message by seq
- sets preview fields
- increments unread by 1 if `incrementUnread = true`
- increments Home unread total by 1

Source: `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ComposeLocalWriteThrough.kt:368-395`.

Risks:

- `SyncResumeResponse` can contain many messages, but Home unread increments once per stream group, not by deduped message count.
- There is no React-equivalent current-channel / near-bottom / visible-thread awareness in the Home write path.
- If live `message:new` fails to decode, Home relies on later resume; but heartbeat can advance resume seq and skip the missing message.
- `server:member-*` events are subscribed on Android but mapped to null, so membership changes do not trigger React-style member refresh.

Source for server-member no-op: `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/realtime/RealtimeEventMapper.kt:114-121`.

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
- `task:*`
- `agent:activity`
- `server:member-*`
- `sync:resume:response`
- `heartbeat`

Source: `compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosRealtimeBridge.ets:7-22`.

It handles `rooms:joined` specially: rejoin locally tracked channels and emit `sync:resume` when `lastSeq > 0`.

Source: `compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosRealtimeBridge.ets:124-145`.

It sends raw `join:channel` with a string payload and `sync:resume` with `{ lastSeq }`.

Source: `compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosRealtimeBridge.ets:148-170`.

Gaps vs Android/React:

- OHOS does not forward `connect`, `disconnect`, `reconnect_attempt`, `reconnect_error`, or `reconnect_failed` to shared code. It logs only.
- OHOS has custom exponential reconnect on WebSocket close/error, but no app-layer heartbeat-silence watchdog.
- OHOS uses the access token passed from shared state; no source-visible reconnect-attempt token refresh equivalent exists in the ArkTS bridge.
- OHOS inherits the same raw message payload mismatch when `decodeSharedShellRealtimeEvent` falls back to `RealtimeEventMapper.map(eventName, rawPayload)`.

Source for raw fallback: `compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsHomePage.kt:6883-6903`.

## Gap Matrix

| Area | React behavior | Android/KMP/OHOS behavior | Risk |
| --- | --- | --- | --- |
| Engine.IO ping/pong | Socket.IO client handles internally | Android library handles internally; OHOS replies `"2"` -> `"3"` | Basic transport heartbeat likely OK |
| App heartbeat | `heartbeat.seq > messageLastSeq` triggers visible gap sync; silence watchdog reconnects | Heartbeat advances `lastSeq`; no silence watchdog | Missed messages can be skipped; wedged socket not self-healed |
| Message payload | Raw enriched message object | Mapper expects `{ message: ... }` | Live messages dropped |
| `message:updated` | Raw enriched message object | Mapper expects `{ message: ... }` | Task/reaction/action updates dropped |
| Resume timing | Waits for `rooms:joined` | Emits on `connect`, `rooms:joined`, `reconnect` | Duplicate/out-of-order resume, not aligned with server safe point |
| Auth on reconnect | Updates/refreshes auth before Manager retry | Android refreshes before explicit connect only; OHOS bridge has no visible refresh path | Stale-token reconnect wedges possible |
| Channel updates | `channel:updated`, `channel:members-updated` reload channels | Not subscribed | Home/sidebar stale after rename/join/member changes |
| Server membership removal | Clears current server and navigates out | Not subscribed | Removed user can remain in stale server UI |
| Machine/agent status | Machine/agent events reload/update stores | Mostly absent except `agent:activity` -> member presence | Settings/Members status stale |
| Announcements/reminders | Announcement and Agent detail reminder events handled | Not subscribed globally | Notifications/reminder surfaces stale |
| Member events | React reloads members on `server:member-*` | Android subscribes but mapper returns null | Member list not refreshed |
| Home unread merge | React counts deduped missed messages and knows current channel state | KMP increments simplified +1 per stream group and lacks visible-thread awareness | Unread/count drift |

## Dual-End Missing Work

This is the actionable split between Android-native, OHOS-native, and shared KMP. Most product-visible fixes belong in shared KMP; transport-only work is not enough.

### Shared KMP Missing Work

- Accept canonical raw `message:new` / `message:updated` payloads and keep only a compatibility fallback for `{ message: ... }` if required by older tests.
- Track applied message cursor separately from heartbeat server max seq; `heartbeat.seq` must never advance the resume cursor.
- Add a shared "server ahead" signal so heartbeat can trigger gap sync/backfill for visible scopes without pretending messages were applied.
- Expand `RealtimeSocketEvents` and the mapper/repository model for channel updates, channel member updates, server membership removal, machine status/capabilities/updated, agent created/deleted, server plan, announcements, and reminders.
- Add refresh intents for events that are not self-contained (`server:member-*`, `channel:members-updated`, `machine:updated`, `agent:created/deleted`) instead of fabricating partial domain objects.
- Make Home unread/preview merge dedupe by message id/seq and count missed messages per channel; include visible channel/thread and self-authored-message rules.
- Add shared contract tests based on raw server payloads and React recovery semantics.

### Android Missing Work

- Remove duplicate global `sync:resume` from raw `connect`; wait for `rooms:joined` or prove room setup before resume.
- Refresh/update socket auth before Manager `reconnect_attempt`, using the same decision policy React has in `socketReconnectAuthRefresh.ts`.
- Add an app-layer heartbeat watchdog using React's conservative thresholds: heartbeat silence over 90s plus all inbound event silence over 120s while connected should force reconnect.
- Subscribe to the missing React events after the shared mapper/refresh-intent model exists.
- Add diagnostics that log `rooms:joined`, resume reason, heartbeat server seq vs applied cursor, and dropped/unknown event names.

### OHOS Missing Work

- Add parser fixtures for Engine.IO open, ping, close; Socket.IO connect, connect error, event, disconnect; and reconnect-after-close.
- Add field diagnostics for Engine.IO `pingInterval` / `pingTimeout`, ping received, pong sent, Socket.IO auth connect sent, app heartbeat received, and event parse failures.
- Forward connection lifecycle events (`connect`, `disconnect`, reconnect attempt/error/failed or equivalent bridge states) into shared diagnostics.
- Add app-layer heartbeat watchdog in ArkTS or shared shell, not just Engine.IO pong handling.
- Add token-refresh/reconnect handoff. The bridge currently uses the token it was given; it needs a path to refresh or request a new access token before reconnect if the access token is near expiry/expired.
- Once shared event coverage exists, subscribe/forward the same React event set as Android.

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

Expected today if the mismatch is active:

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

If mobile emits `sync:resume` with the heartbeat max seq rather than the last applied message seq, resume will skip the missed message. That is a cursor bug.

### 5. Home Merge

After fixing decode/cursor first, retest Home:

- new channel message preview updates
- unread increments by deduped missed message count
- current visible thread/channel does not over-increment unread
- DM creation reloads Home and joins room
- thread reply updates parent reply count

## Recommended Fix Order

### P0

1. Fix `RealtimeEventMapper` for canonical raw `message:new` and `message:updated`.
   - Decode raw `MessageWireDto` first.
   - Keep `{ message: ... }` fallback only if old local tests require it.
   - Update tests to use server/React raw payload shape.

2. Split heartbeat max seq from applied message resume seq.
   - Track `serverMaxSeq` from heartbeat separately.
   - Do not update resume `lastSeq` from heartbeat.
   - If `heartbeat.seq > appliedLastSeq`, trigger a gap sync/backfill intent instead.

3. Align resume timing to React.
   - Emit global `sync:resume` after `rooms:joined`.
   - Avoid duplicate `sync:resume` on raw `connect` before room setup.
   - On reconnect, wait for the post-reconnect `rooms:joined` or explicitly prove rooms are joined before resume.

4. Add app-layer heartbeat watchdog.
   - Mirror React threshold: heartbeat silence > 90s and all inbound event silence > 120s while connected -> disconnect/connect.
   - Add this for Android native transport and OHOS bridge.

### P1

5. Add reconnect-attempt token refresh parity.
   - Android: refresh/update auth before Manager retry handshakes.
   - OHOS: refresh or request refreshed session before reconnect when token is near expiry/expired, or reconnect through shared session refresh flow.

6. Wire missing React events.
   - At minimum: `channel:updated`, `channel:members-updated`, `server:membership-removed`, `machine:status`, `machine:capabilities`, `machine:updated`, `agent:created`, `agent:deleted`.
   - For `server:member-*`, do not map to null silently; emit a refresh-members/reload-home signal.

7. Harden Home unread/preview merge.
   - Count deduped resume messages per channel, not just +1.
   - Avoid unread increments for current visible thread/channel and self-authored messages where appropriate.
   - Keep message seq cursor based on applied/deduped messages only.

### P2

8. Add OHOS Socket.IO parser tests/diagnostics.
   - Engine.IO open/ping/pong
   - Socket.IO event packet
   - connect error packet
   - reconnect after close

9. Add live contract tests against the pinned server source shape.
   - Raw `message:new`
   - Raw `message:updated`
   - `sync:resume:response` raw messages list
   - heartbeat must not advance applied resume cursor

## Current Answer To The Original Question

No, mobile is not fully aligned with React Socket.IO semantics.

The OHOS implementation does include the low-level Engine.IO ping/pong path, so the likely defect is not simply "ping/pong missing." The more likely causes of the observed OHOS realtime/Home problems are:

1. live message events are dropped because the KMP mapper expects the wrong payload envelope;
2. heartbeat seq advances mobile resume cursor and can hide the dropped messages from later `sync:resume`;
3. mobile lacks React's heartbeat-silence reconnect guard;
4. mobile does not subscribe/respond to several React realtime refresh events, so Home/Members/Settings can remain stale even when the socket is connected.
