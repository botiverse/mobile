# Android Socket.IO Boundary

Task: #118

## Contract

- Socket base URL: `https://api.slock.ai`
- Socket auth payload: `{ token, serverId }`
- Token and selected server id come from `AuthSessionStore` through `SessionRealtimeRepository`.
- Compose/UI must consume mapped `RealtimeEvent` values only. It must not hold raw socket events, bearer tokens, or `X-Server-Id`.

## Client Boundary

- `RealtimeRepository` is the data-layer entry point for app wiring:
  - `connect(serverId, lastSeq)`
  - `disconnect()`
  - `joinChannel(channelId)`
  - `observeEvents(listener)`
- `SessionRealtimeRepository` validates auth/session/selected-server state and injects auth into the realtime client.
- `SocketIoRealtimeClient` owns room join/resume behavior and maps socket events through `RealtimeEventMapper`.
- `RealtimeSocketTransport` is the adapter boundary for the actual Socket.IO Android library. The recommended adapter is `io.socket:socket.io-client` configured with websocket transport, reconnection enabled, and auth `{ token, serverId }`.

## Events

Mapped now:

- `message:new` -> `RealtimeEvent.MessageNew`
- `message:updated` -> `RealtimeEvent.MessageUpdated`
- `thread:updated` -> `RealtimeEvent.ThreadUpdated`
- `task:created` -> `RealtimeEvent.TaskCreated`
- `task:updated` -> `RealtimeEvent.TaskUpdated`
- `task:deleted` -> `RealtimeEvent.TaskDeleted`
- `sync:resume:response` -> `RealtimeEvent.SyncResumeResponse`
- `heartbeat` -> `RealtimeEvent.Heartbeat`

Boundary-only / lifecycle events:

- `connect`
- `disconnect`
- `connect_error`
- `rooms:joined`

## Resume And Gap Sync

- On `rooms:joined`, the client emits `sync:resume` with `{ lastSeq }` when `lastSeq > 0`.
- Heartbeat and resume response update the client's latest sequence tracking.
- Thread/channel gap sync remains a repository/app lifecycle follow-up: app wiring should request REST page gaps for the active channel/thread after reconnect, then de-dupe by message id/seq before updating UI state.

## Non-goals In This Phase

- No MainActivity or foreground/background lifecycle wiring.
- No Compose direct socket consumption.
- No real Socket.IO dependency adapter yet; `RealtimeSocketTransport` is the compile-safe adapter boundary for that follow-up.
