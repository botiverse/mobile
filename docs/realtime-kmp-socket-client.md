# Realtime unification: one KMP Socket.IO client

Owner: artin · Status: approved with corrections (CTO Tenny, artin scope change 2026-11-14); phase 0 in progress · Base: mobile-https `origin/main` 6af3676b6d, slock `origin/staging` 409605bdd · 2026-11-14

## 中文摘要

- **现状**：实时连接在三处各写了一遍。Android 和桌面端各有一份几乎一样的 `SocketIoRealtimeClient` 加 `SocketIoSocketTransport`（约 1,260 行 ×2，都用 socket.io-client-java 2.1.2 over OkHttp）。iOS 是 1,150 行 Swift（`RaftIosRealtimeHost.swift`，用 Socket.IO-Client-Swift 16.1），而且自己手写了一份事件列表。光标、sync horizon 和事件映射这些策略已经在 `shared/commonMain` 里，传输层和连接生命周期还没有。
- **已确认的问题**：
  - iOS 列表比共享的 `ServerWireEvents` 少了 `agent:seen` 和 `joint-invites:changed`，iOS 永远收不到这两个事件。
  - 桌面端握手时报的是 `clientKind: "mobile"`，平台定向投递会发错。桌面端也不发 `presence:activity`，没有网络变化处理，也没有心跳看门狗。
  - iOS 自动重连会继续用旧的 token，也没有应用层心跳看门狗和网络监听。
  - Android 判断鉴权失败靠字符串关键字匹配；`sync:horizon` 的 ack 没有超时。
- **服务端**：socket.io 4.8.3（Engine.IO v4、Socket.IO 协议 v5），只有 `/` 命名空间，用 Redis adapter。鉴权只看 `handshake.auth {token, serverId, clientKind, capabilities}`。ping 参数是默认值（25 s / 20 s），另有应用层 `heartbeat`，每 15 s 一次。三端都只用 websocket，没有二进制帧。ack 有两个方向：客户端发 `sync:horizon` 要服务端回 ack；服务端发 `notification:desktop`（`emitDesktopNotificationWithAck`）要每个桌面端 socket 回 ack，内容是它的 socket id。
- **方案**：新建 KMP 模块 `:realtime-socket`。commonMain 里实现 EIO4/SIO5 的最小子集，传输层用 Ktor WebSockets（Android 和桌面用 OkHttp 引擎，iOS 用 Darwin 引擎）。每个服务器一条连接，跑在一个串行协程状态机里。每次连接前通过 suspend 函数取 token。重连用带抖动的指数退避。现有的 `SocketIoRealtimeClient` 移到 commonMain。事件列表只从 `realtime_contract.json` 生成 Kotlin。任何被丢弃的帧都要计数并记日志，不允许静默丢弃。平台只保留很薄的生命周期适配层（前后台、网络、Android 保活服务）。
- **迁移**：先 iOS，然后桌面，最后 Android。每端用开关 `realtime_kmp_client_v1` 加开发者覆盖项控制，新建 socket 时生效，关掉开关即回滚。确认稳定两个版本后，再删除旧客户端、pod 和 iOS 列表。
- **工作量**：约 9–11 人周；分阶段灰度，日历时间约 3–4 个月。

## 0. Decisions and corrections (2026-11-14)

These override anything below that disagrees.

1. **Acks run in both directions.** The server asks clients for acks: `notification:desktop`
   (`emitDesktopNotificationWithAck`, `packages/server/src/services/notifications/transports/desktopSocket.ts`)
   is sent with `io.timeout(ms).to(desktopRooms).emit(...)` and the server waits for each desktop socket to
   answer `{ socketId }`. An EVENT packet with an ack id (`2<id>[...]`) is a normal packet; the listener gets
   a `RaftSocketIncomingAck` and answers with an ACK packet (`3<id>[...]`), once, on the same connection only.
   It is never treated as an invalid frame. (Tenny, correction 1.)
2. **`realtime_contract.json` stays the single source** for event names and handshake error strings. Its
   slock-derived sections are written by `compose/realtime-contract/sync_from_slock.py` from slock
   `packages/shared` (plus the four handshake errors that are still literals in
   `packages/server/src/socket/index.ts`); `generate_realtime_contract.py` turns them into Kotlin
   (`GeneratedSocketHandshakeErrors` in `:realtime-socket`); `.github/workflows/realtime-contract-drift.yml`
   fails on drift (daily and on PRs touching the contract). (Tenny, correction 2.)
3. **Desktop `clientKind: "desktop"` is phase 2**, not part of the phase 0/1 PRs. (Tenny, correction 3.)
4. **Heartbeat and max payload always come from the Engine.IO open packet** (`pingInterval`, `pingTimeout`,
   `maxPayload`), never from client constants. A missing field falls back to the socket.io server default and
   is logged (`realtime_socket_open_fallback fields=...`). Nothing depends on `perMessageDeflate` or
   `allowUpgrades`. The only server guarantees relied on: the default JSON parser, WebSocket transport
   available, and acks as in item 1. (Tenny, constraint.)
5. **A complete Socket.IO v5 client, not a subset** (artin scope change): every packet type including
   BINARY_EVENT / BINARY_ACK with attachments, acks both ways, any number of namespaces each with its own
   `auth`, CONNECT_ERROR, heartbeat and maxPayload from the handshake, connection state recovery
   (`pid` / `offset`), per-message deflate as negotiated by the WebSocket engine. So no server-side contract
   beyond the Socket.IO protocol itself is needed.
   - **Deferred**: the HTTP long-polling transport and the polling to WebSocket upgrade. The Raft clients have
     been WebSocket-only since launch; polling is a separate transport implementation (XHR framing with
     `\u001e` batching, sid-bound requests, upgrade probe `2probe`/`3probe`/`5`). The engine is behind the
     `RaftWebSocketConnector` seam so a polling transport can be added without touching the protocol layers.
   - Per-message deflate: OkHttp offers `permessage-deflate` and uses it when the server accepts;
     NSURLSessionWebSocketTask negotiates on its own. Covered by the interop server B (deflate on).

6. **Recovery replays before the CONNECT answer** (found by the interop suite): a socket.io server with
   `connectionStateRecovery` sends the missed packets from the `Socket` constructor, before `0{sid,pid}`.
   socket.io-client buffers events that arrive while a CONNECT is pending (`receiveBuffer`) and delivers them
   right after `connect`; `RaftSocketIoNamespace` does the same (dropped with `buffered_<reason>` if the CONNECT
   fails or the connection ends).

### Phase 0 status (PR "realtime-socket: Socket.IO v5 client core")

- Module `compose/realtime-socket` (`build.raft.realtime.socket`), commonMain + Ktor (OkHttp / Darwin).
  `EngineIoCodec`, `SocketIoCodec` (+ `SocketIoBinaryReconstructor`), `RaftSocketIoClient` (the Manager:
  one Engine.IO connection, reconnect) and `RaftSocketIoNamespace` (the Socket), `RaftReconnectPolicy`
  (1 s to 30 s, ±50 % jitter), `RaftSocketHandshakeError`, `raftHandshakeAuth`.
- Per namespace: fresh `auth` before every CONNECT attempt; a token rejection retries once immediately with
  `forceRefresh`, then backs off; SSO / not-a-member are terminal until `kick()`. Engine loss reconnects with
  backoff and re-CONNECTs every namespace. `kick()` and `forceReconnect()` are the immediate-retry hooks.
- Serial dispatcher per client (one client per server). Unlisted events: `projection_value_unknown
  source=realtime_socket field=event_name value=<name>` once per name plus `realtime_event_dropped
  reason=unlisted`; every other drop is `realtime_event_dropped reason=<token>` and counted.
- Tests: `:realtime-socket:testAndroidHostTest` runs the common suite (codecs, scripted fake server on virtual
  time: handshake, open-packet heartbeat/maxPayload, ping timeout, acks both ways incl. timeout and wrap-around,
  binary, namespaces, token refresh, terminal errors, backoff, kick, force reconnect, recovery) and
  `RaftSocketIoInteropTest` against a real socket.io 4.8.3 (`scripts/socketio-interop/server.mjs`, started by
  the Android CI job). The iOS simulator runs the common suite on the full tier.
- Not yet: the shared adapter and the iOS host behind `realtime_kmp_client_v1` (phase 1 PR), moving
  `SocketIoRealtimeClient` to common.

### Flag resolution, build default, and the Android switch (artin 2026-11-14: replace directly, smoke QA builds)

`realtime_kmp_client_v1` is read by `RaftRealtimeKmpClientFlag.decision()` each time a host creates a socket,
never mid-connection. First match wins:

1. **Host support**: only hosts with an adapter (`hostSupported`: Android `RaftApplication`, iOS
   `RaftIosRealtimeHost`) can be on. Desktop reads off.
2. **Developer switch** (Settings › Developer › "New realtime connection"): on or off wins.
3. **Explicit server decision**: the last evaluation of the key on any server, if it was a decision: any
   `true`, or `false` from a rule (`server_rule`, `client_rule`, `platform_rule`) or the `kill_switch`.
   A "no rule" evaluation (`default`, `missing_flag`, `missing_server_unit` evaluating `false`) clears the
   stored value instead, so it never overrides the build default.
4. **Build default** (`buildDefaultOn`), set by the host at launch from its build configuration:

| Platform | Build setting | QA builds (on) | Release builds (off) |
|---|---|---|---|
| Android | `BuildConfig.REALTIME_KMP_CLIENT_DEFAULT_ON` (`compose/androidApp/build.gradle.kts`) | `debug`, `alpha` (and `connectedTest`, which inherits debug) | `release`, `play` |
| iOS | `RAFT_REALTIME_KMP_CLIENT_DEFAULT_ON` build setting, read from Info.plist `RaftRealtimeKmpClientDefaultOn` | `Debug` (simulator / QA builds, `ios-simulator-app.yml`) | `Release` (TestFlight / App Store, `ios-release.yml`) |

So a QA build runs the common client until the server explicitly turns it off (a rule or the kill switch for
the key); a release build stays on the platform library until the server turns it on. Rollback in either
case: an explicit `false` on the server (or the developer switch) and the next connection uses the platform
library.

**Android** (`createAndroidRealtimeSocketTransport`): `SocketIoRealtimeClient` keeps all of its logic
(cursor, sync horizon, resume, rejoin, presence, heartbeat watchdog) and talks to a
`RealtimeSocketTransportSelector`, which picks `RaftKmpRealtimeSocketTransport` (common client, OkHttp engine)
or `SocketIoSocketTransport` (socket.io-client-java) at every `connect` and disconnects the other one when the
choice changes. Both run under the generation fence's lock (`RealtimeTransportLinearizer`); callbacks are
checked against the current connection under that lock and delivered outside it, as before. `clientKind`
stays `mobile`; the common client adds `clientImpl: "kmp"` to the handshake.

Diagnostics (always kept): `realtime_kmp_transport selected=kmp|legacy source=local|server|build_default
serverId=… switched=…` once per connection, and `realtime_kmp transport_connect_start|transport_connected|
transport_disconnected|transport_reconnect_scheduled|transport_connect_error …` from the common client. Its
other lines (`realtime_kmp …`, e.g. drops) need verbose diagnostics.

Tests for both implementations: `SocketIoRealtimeClientTransportParityTest` (androidApp unit tests) runs the
client through the production wiring with the flag off and on against a real socket.io 4.8.3 server with
Raft's realtime behaviour (`scripts/socketio-interop/server.mjs` PORT_C): handshake auth, `rooms:joined`,
the `sync:horizon` ack, `join:channel` / `channel:joined`, a server-side transport drop, reconnect + rejoin +
`sync:resume {lastSeq, sinceHorizon}`, and `horizonReset` catch-up. The fake-transport suites
(`SocketIoRealtimeClientReconnectResumeTest`, `…SyncHorizonTest`) and the scripted-frame port
(`SocketIoRealtimeClientOverKmpTransportTest`) stay.

## 1. Inventory of current implementations

| Aspect | Android (`androidApp/.../core/data/realtime`, `shared/Android*Realtime*`) | Desktop (`desktopApp/.../realtime`, `RaftDesktopRealtimeHost`) | iOS (`RaftIosRealtimeHost.swift`, `RaftIosRealtimeLifecyclePolicy.swift`) |
|---|---|---|---|
| Library | socket.io-client-java 2.1.2 on a shared OkHttp client (ping 15 s, connect 10 s, read timeout 0) | Byte-for-byte copy of the Android client and transport | Socket.IO-Client-Swift ~>16.1 (Starscream), `FoundationSecurity` TLS evaluation |
| Auth | `auth {token, serverId, clientKind:"mobile", capabilities:[agent_activity_subscriptions_v1]}`. The capabilities array is passed through an unchecked `Map<String,String>` cast. | Same, including `clientKind:"mobile"` (**bug**: should be `desktop`) | `connect(withPayload:)` with the same fields, plus `Authorization`/`X-Server-Id` headers that the server ignores |
| Token refresh | On `connect_error` whose text matches a keyword ("auth/token/invalid/expired"), with `SSO_REQUIRED` excluded, it calls `refreshSession` on a dedicated thread. The ANR fix (9222ba03) keeps this off the lock. `reconnect_attempt` re-reads the committed session only, because refresh tokens rotate. A changed token builds a new socket. | Same | `error` posts `authRefreshRequired` to shared, with one-in-flight gating. Shared re-reconciles with the new token. Swift's built-in reconnect keeps sending the **old payload**. |
| Transports | websocket only | websocket only | `forceWebsockets(true)`, `.compress` |
| Backoff | Library default: 1 s up to 5 s, randomization 0.5, unlimited | Same | 1 s up to 30 s, unlimited |
| App-level health | `RealtimeHeartbeatWatchdog`: forces a reconnect after 90 s with no `heartbeat` and 120 s with no inbound frame (checked every 30 s) | Same code. The TODO says there are no network or health checks beyond this. | None. It relies on the Engine.IO ping timeout of about 45 s. |
| Resume / catch-up | On `rooms:joined`, sends `sync:resume {lastSeq, sinceHorizon}`. Sends `sync:horizon` (ack) on connect while no horizon is held. A `horizonReset` or a missing cursor triggers `RealtimeRefreshRequired(Home)` plus a local `raft:realtime:catch_up_required` frame. A projection rejection retries once, then escalates. Cursor epochs rotate on reconnect. | Same | Equivalent logic, re-implemented in Swift. The horizon ack times out after 10 s; Android has no timeout. |
| Rooms | Keeps a `joinedChannels` set and re-sends `join:channel` with the bare id on every (re)connect. Handles `channel:joined`/`join_denied` acks. | Same | Same. Emits made while disconnected are dropped and replaced by the rejoin. |
| Presence | `presence:activity` on foreground, on connect, and as a heartbeat | **Missing** | Sent with a timer. `false` is sent once before the socket closes in background. |
| Background | Reconnect is suspended in background unless keep-alive is on. Devices without Play services (Huawei etc.) run a foreground `RaftBackgroundConnectionService` to keep the socket open. Returning after more than 5 min in background forces a reconnect. | The window is always treated as visible | Closes on `didEnterBackground` and reopens on `didBecomeActive`. `willResignActive` hides the shell only. |
| Network change | `registerDefaultNetworkCallback` triggers a health check, DNS-change and capability signals, and a prewarm | None | None |
| Multi-server / window | One socket per server (`AndroidRealtimeSessionRegistry`). Multi-window can keep up to 3 sessions; only the primary may reconnect in background. | Exactly one server | One runtime per server. All shells are foregrounded or backgrounded together. |
| Generation fencing | `RealtimeSocketGenerationFence` plus the connection-context identity check, holding a global lock (`linearize`) | Same | A generation counter on a serial `DispatchQueue` |
| Event list | `RealtimeSocketEvents.ServerWireEvents` (generated list plus 9 hard-coded strings) | Same | Hand-maintained `serverWireEvents`, which **lacks `agent:seen` and `joint-invites:changed`** |
| Server-id gate | Drops a payload whose `serverId` differs from the connection's, except `UserScopedServerEvents` | Same | Same gate, with its own copy of the set |
| Diagnostics | `RaftAppLog` lines, `RealtimeSuccessDiagnostics` summaries, `RealtimeClientDiagnostics` | `RaftDesktopLog` | `record()` into the diagnostics bundle |
| Tests | Reconnect/resume, sync-horizon, OkHttp, repository, owner, keep-alive, registry; a fake transport | None | Lifecycle policy only |

Other known defects:
- Desktop `closeSession` always passes `ServerRemoved`, whatever the actual reason.
- socket.io-client-java pulls in OkHttp 3.12, which is forced up to 5.5 at resolution (ABI risk).
- Payloads use org.json on one side and Gson on the other.
- Auth-failure detection depends on server error strings. The constants are in slock `packages/shared/src/socketHandshakeErrors.ts`.

## 2. Server protocol facts (slock `packages/server/src/socket/index.ts`)

- `socket.io ^4.8.3` with `@socket.io/redis-adapter`. That means **Engine.IO v4 (`EIO=4`) and Socket.IO protocol v5**. Web uses `socket.io-client ^4.8.4` with `transports:["websocket"]`.
- `new Server(server, {cors})`. No other options are set, so the defaults apply:
  - path `/socket.io`
  - transports polling and websocket (clients only use websocket)
  - `pingInterval` 25 s, `pingTimeout` 20 s
  - `maxHttpBufferSize` 1 MB
  - no `connectionStateRecovery`
  - per-message deflate off by default
- Only the default namespace `/` exists.
- **Auth**: `io.use` reads only `handshake.auth`. `token` is required and must be an access token. `serverId` is optional; when present, membership is checked. `clientKind` is required, from `web|mobile|desktop|cli`. `capabilities` is a string array. Extra keys are ignored.
- Handshake errors (`connect_error` message text):
  - `Authentication required`
  - `Invalid token type`
  - `Invalid or expired token`
  - `Not a member of this server`
  - `SSO_REQUIRED`
  - `Authentication changed; reconnect required`
- The server closes sockets on SSO expiry and access revocation, and emits `server:membership-removed` first.
- Server-driven frames:
  - `rooms:joined` once member rooms are joined (retried with backoff from 1 s to 60 s)
  - `heartbeat {seq, ts}` every 15 s
  - `sync:resume` → `sync:resume:response {messages, currentSeq, hasMore, horizon, horizonReset}`
  - `sync:horizon` (client ack) → `{horizon}` or `{error}`
- Client-emitted events:
  - `join:channel` / `leave:channel` (bare channel id string)
  - `agent:activity:subscribe` / `agent:activity:unsubscribe`
  - `read_progress` watch / unwatch
  - `presence:activity`
- **The server does request acks**: `notification:desktop` to desktop sockets (see §0 item 1). No binary payloads are sent today. `_trace` keys are stripped from inbound payloads.
- **Source of truth for event names**: slock `packages/shared` and the emit sites. The mobile mirror is `compose/realtime-contract/realtime_contract.json`, processed by `generate_realtime_contract.py`. Some names still live as literals in `RealtimeSocketEvents.kt`.

## 3. Proposed architecture

New KMP module **`:realtime-socket`** (commonMain; targets androidTarget, jvm (desktop), iosArm64 and iosSimulatorArm64). `:shared` depends on it. All parts below are in commonMain.

```
RealtimeProcessHost         reconcile/connect/cursorAdvance/disconnect commands (today duplicated as desktop report() + iOS handleCommand)
 └─ RealtimeSessionRegistry  one RealtimeConnection per serverId, max N, primary/background policy (from Android registry)
     └─ RealtimeConnection   = today's SocketIoRealtimeClient moved to common: cursor, horizon, resume, rejoin,
         │                     presence, heartbeat watchdog, server-id gate, auth refresh, RealtimeEvent dispatch
         └─ SocketIoClient   SIO v5 subset: CONNECT(auth)/CONNECT_ERROR/EVENT/ACK/DISCONNECT, ack registry + timeouts
             └─ EngineIoWebSocket  EIO4 over Ktor WebSocketSession: open packet, ping→pong, ping-timeout, close, message
                 └─ WebSocketFactory  (interface; Ktor impl in prod, scripted fake in tests)
```

- **Engines**: Android and desktop use `ktor-client-okhttp`, which is already in the build, keeps system proxy and TLS behaviour, and supports `pingInterval`. iOS uses `ktor-client-darwin`, built on `NSURLSessionWebSocketTask` with system trust; it drops Starscream and `FoundationSecurity`. CIO is not used because it does not pick up the system proxy. WebSockets are in `ktor-client-core`, and Ktor is already 3.6.1.
- **Protocol** (originally a subset; now the complete client, §0 item 5):
  - Connect to `wss://host/socket.io/?EIO=4&transport=websocket`.
  - Parse `0{sid,pingInterval,pingTimeout,maxPayload}` and reply `3` to each server `2`.
  - A dead link is declared after `pingInterval + pingTimeout` with no ping.
  - Send `40{auth}` and expect `40{sid}` or `44{message}`.
  - Event packets are `42[name,arg]`; ack packets are `42<id>[…]` going out and `43<id>[…]` coming back.
  - Superseded by §0 item 5: binary packets and namespaces are supported; frames for a namespace with no socket are dropped with a diagnostic.
  - JSON is handled with kotlinx.serialization `JsonElement`. The mapper already accepts `Map` and JSON payloads.
- **Concurrency**: each connection is an actor on `Dispatchers.Default.limitedParallelism(1)`. A generation id is stamped on every inbound frame, and stale generations are counted and dropped. This replaces the `linearize` lock and the generation fence. No lock is held while listeners or the auth provider run, which removes the ANR and deadlock class of bugs.
- **Auth**: an `authProvider: suspend (attempt, lastError) -> RealtimeAuth?` is called before **every** connect attempt, so a rotated token is always used.
  - A typed `HandshakeError` is parsed from the exact server strings, with the constants generated into the contract. Keyword matching goes away.
  - `Invalid or expired token` triggers one forced refresh and then the normal backoff.
  - `SSO_REQUIRED` and `Not a member` are terminal: no retry, and a surface signal is raised.
  - `clientKind` is a platform parameter (`mobile`/`desktop`). The desktop switch to `desktop` is phase 2 (§0 item 3).
- **Reconnect policy** (`ReconnectPolicy`, pure and tested with virtual time):
  - Exponential backoff from 1 s to 30 s with ±50 % jitter, unlimited attempts, reset after `rooms:joined`.
  - `kick()` on network-available or foreground resets the backoff and retries at once.
  - `suspend()`/`resume()` cover background without keep-alive.
  - The heartbeat watchdog (90 s/120 s) and the EIO ping timeout both feed the same `forceReconnect(reason)`.
  - Resume and catch-up stay exactly as today (`RealtimeCursorPolicy`, `RealtimeSyncHorizon`). The `sync:horizon` ack gets a 10 s timeout on all platforms.
- **Platform adapters** (thin, about 100–300 lines each). Each one only calls `onForeground/onBackground/onNetworkAvailable/onNetworkLost/setKeepAlive`:
  - **iOS**: a Swift `RaftIosRealtimeLifecyclePolicy` that keeps today's resign, background and active semantics, plus an `NWPathMonitor`.
  - **Android**: the existing process-lifecycle and `ConnectivityManager` callbacks, plus keep-alive and Huawei service logic. That logic stays in `androidMain` because it is OS policy.
  - **Desktop**: window focus and visibility, plus a JVM network probe.
- **Event list single-sourced**:
  - Move the 9 literal names into `realtime_contract.json` and stop generating socket event names into Swift.
  - The client accepts every inbound event. A name in `ServerWireEvents` is mapped. A name outside it is counted and logged once per name as `event_unlisted` (and fanned out raw behind a debug flag). An event can no longer silently never arrive.
  - Add a CI check that diffs the contract against slock `packages/shared` event constants (via `$SLOCK_SOURCE_REPO`).
- **Diagnostics**:
  - A typed `RealtimeDiagnostic` stream (state transitions with reason; handshake error; backoff delay; drop reason ∈ {stale_generation, unlisted, parse_error, server_mismatch, binary, ack_timeout, oversize}; RTT from ping) feeds `RaftAppLog`, the Hands bundle and `realtimeSessionDiagnosticsText()`.
  - Drop counters are always on and contain no tokens.
  - The auth payload gets `clientImpl:"kmp"` for server-side log correlation. The server ignores unknown keys.

## 4. Migration plan

The flag is `realtime_kmp_client_v1`, read per platform from `ServerFeatureFlagLastKnownStore` at socket creation, with a Settings › Developer override that wins. Switching takes effect at the next connection or process start; there is no hot swap mid-socket. **Rollback**: turn the flag off and the next connect uses the legacy stack. Legacy code stays for 2 releases after 100 %.

| Phase | Scope | Exit criteria |
|---|---|---|
| 0. Core | `:realtime-socket` with EIO/SIO codec (all packet types), `RaftSocketIoClient` + namespaces, `RaftReconnectPolicy`, fake connector, interop CI, contract sync + drift job. | Codec and state-machine tests green on JVM and the iOS simulator. Interop suite green against a real Node `socket.io@4.8.3`. |
| 1. iOS first | Move `SocketIoRealtimeClient` to common as `RealtimeConnection`, with org.json and Gson replaced. Add `RealtimeProcessHost` and `SessionRegistry` in common. The Swift adapter keeps lifecycle only. `RaftIosRealtimeHost.handleCommand` routes to the Kotlin host when the flag is on. | Internal TestFlight, then 5 %, 25 % and 100 %. Metrics (below) are at or better than legacy, and `agent:seen` and `joint-invites:changed` now arrive. |
| 2. Desktop | Replace desktop `realtime/*` with the common host and the OkHttp engine. Adds `clientKind:"desktop"`, presence, network kick. | One release on the flag, then default on. |
| 3. Android | `AndroidAppScopeRealtimeHostContext` builds the common connection. The keep-alive, Huawei service, multi-window registry and network callbacks become adapter inputs. | Staged Play rollout from 5 % to 100 %, including no-GMS devices. |
| 4. Cleanup | Delete the Android and desktop `SocketIo*` files, the `socket.io-client` dependency (which also removes the OkHttp 3.12 transitive), the `Socket.IO-Client-Swift` pod, the iOS event list and the Swift event generation. Update the `raft-realtime-event` skill. | 2 releases after 100 % with no rollback. |

**Tests**
1. *Protocol units* (commonTest, all targets): golden packets, ack id wrap-around, open-packet timers, ping timeout, `44` error parsing, binary rejection.
2. *Fake server* (scripted `WebSocketFactory`, virtual time):
   - handshake, auth error → refresh → rotated token
   - SSO terminal
   - server close and EIO close
   - backoff schedule
   - stale-generation drop
   - `rooms:joined` → `sync:resume`
   - horizon ack late, missing or timed out
   - `horizonReset` → catch-up
   - unlisted-event diagnostic

   Port the existing `SocketIoRealtimeClientReconnectResumeTest` and `SyncHorizonTest` to common as parity tests (same frame script in, same `RealtimeEvent` and cursor sequence out).
3. *Interop*: CI starts a 30-line Node server with `socket.io@4.8.3` and runs the suite through Ktor OkHttp (JVM).
4. *Staging integration* (nightly, and on PRs touching realtime): a test account connects from the JVM, then asserts `rooms:joined`, a `heartbeat` within 20 s, the `sync:horizon` ack, a REST post producing `message:new`, and that a forced drop resumes without a gap. Extend `LiveRealtimeServerScopeTest`.
5. *Soak*: 24 h on iPhone, Pixel, Huawei without GMS, macOS and Windows desktop, with network flapping (Link Conditioner, `adb svc wifi`) and background/foreground cycles. Pass criteria: no FD or memory growth, reconnect-to-`rooms:joined` p95 < 3 s, zero unexplained drops, battery at or better than legacy.

**Rollout metrics** (client diagnostics, compared with legacy per platform): connect success %, time to `rooms:joined`, reconnects per hour, catch-up and `horizonReset` rate, drop counters, `heartbeat_silence` reconnects.

**Risks and mitigations**
- **Darwin WebSocket quirks** (close codes, suspension in background, no ping control): EIO ping/pong and our own watchdog cover liveness, and the iOS soak runs early.
- **Subtle semantic regressions in fencing, refresh-token rotation and keep-alive**: mitigated by the parity tests, by keeping the policies unchanged, and by the per-platform flag.
- **Proxies or networks that block WebSocket**: no change, since all clients are WebSocket-only today. Polling stays out of scope.
- **Kotlin/Native threading and memory**: the single-dispatcher actor and the existing iOS Kotlin runtime in `shared` contain it.
- **Server upgrade to Socket.IO 5 / EIO 5**: version constants live in one place, and the interop CI pins and alerts.
- **Delivery-target change from the desktop `clientKind` fix**: announce it to the server team, and verify desktop notification transport on staging.

## 5. Effort estimate (one engineer familiar with the code)

| Phase | Effort | Calendar incl. rollout |
|---|---|---|
| 0 Core + fake server + interop CI | 2 wk | 2 wk |
| 1 Common connection/host port + iOS adapter + flag + staging test | 3 wk | +3–4 wk staged |
| 2 Desktop | 1 wk | +2 wk |
| 3 Android (adapters, keep-alive, multi-window) | 2–3 wk | +3–4 wk staged |
| 4 Cleanup + docs/skill | 0.5–1 wk | after 2 releases |
| **Total** | **≈ 9–11 engineer-weeks** | **≈ 3–4 months** |
