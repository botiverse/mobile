# Realtime Event Contract Audit (2026-06-23)

Scope: KMP/shared and Android Socket.IO listener parity with the Slock server. Server source of truth is `third_party/slock-source/packages/server/src`; React listener parity was checked in `third_party/slock-source/packages/web/src/components/layout/MainLayout.tsx`.

## Contract Rule

- Service-side `io.emit(...)`, `io.to(...).emit(...)`, and `socket.emit(...)` event names are authoritative.
- Platform transports must subscribe to every user-visible server Socket.IO event, then pass raw payload to `RealtimeEventMapper`. Android uses `RealtimeSocketEvents.ServerWireEvents`; OHOS currently mirrors the same event names in `SlockOhosRealtimeBridge.ets`.
- Shared should prefer typed domain events for message/task/thread mutations and `RealtimeRefreshRequired` for broad server snapshots. Do not add speculative field aliases; add fields only when the server emits them.
- Transport lifecycle events (`connect`, `disconnect`, reconnect variants, `rooms:joined`) remain client/transport state and do not enter shared MVI as domain events.

## Event Matrix

| Server event | Server emit source | Payload shape | Shared mapping | Shared consumer |
| --- | --- | --- | --- | --- |
| `message:new` | `services/messageService.ts`, `routes/tasks.ts`, `routes/internal.ts`, `services/actionCardsService.ts` | enriched message row | `MessageNew` | Home, Thread, Activity/cache |
| `message:updated` | `routes/messages.ts`, `routes/internal*.ts`, `routes/tasks.ts`, `services/actionCardsService.ts` | enriched message row | `MessageUpdated` | Home, Thread, Activity/cache |
| `dm:new` | `services/messageService.ts` | `{ channelId }` | `DirectMessageCreated` | Home refresh / join path |
| `thread:updated` | `services/messageService.ts` | `{ parentMessageId, threadChannelId, replyCount, lastReplyAt, participantIds, unreadCount?, firstUnreadMessageId? }` | `ThreadUpdated` | Home, Thread |
| `channel:updated` | `routes/channels.ts`, `services/onboardingService.ts`, `services/actionCardsService.ts` | `{ channel }` or `{ channelId }` | `RealtimeRefreshRequired(Home)` | Home |
| `channel:members-updated` | `routes/channels.ts`, `routes/internal*.ts` | `{ channelId }` | `RealtimeRefreshRequired(ChannelMembers)` | Home / member suggestions |
| `task:created` | `services/taskRealtimeEvents.ts` | `{ channelId, tasks }` | `TaskCreated` | Home, Thread, Activity/cache |
| `task:updated` | `services/taskRealtimeEvents.ts` | `{ channelId, task }` | `TaskUpdated` | Home, Thread, Activity/cache |
| `task:deleted` | `services/taskRealtimeEvents.ts` | `{ channelId, taskId }` | `TaskDeleted` | Thread/cache |
| `agent:activity` | `services/agentOrchestrator.ts` | `{ agentId, activity, detail?, timestamp?, entries?, serverSeq? }` | `MemberPresenceUpdated` | Home presence |
| `agent:session` | `services/agentOrchestrator.ts` | `{ agentId, sessionId }` | `RealtimeRefreshRequired(Agents)` | Home snapshot refresh |
| `agent:created` | `routes/agents.ts` | `{ agent }` | `RealtimeRefreshRequired(Agents)` | Home snapshot refresh |
| `agent:deleted` | `routes/agents.ts` | `{ agentId }` | `RealtimeRefreshRequired(Agents)` | Home snapshot refresh |
| `machine:status` | `services/agentOrchestrator.ts` | `{ machineId, status, statusVersion? }` | `RealtimeRefreshRequired(Machines)` | Home / Settings computers |
| `machine:capabilities` | `services/agentOrchestrator.ts` | `{ machineId, runtimes, hostname?, os?, daemonVersion?, computerVersion? }` | `RealtimeRefreshRequired(Machines)` | Home / Settings computers |
| `machine:updated` | `routes/servers.ts` | `{ serverId, machineId }` | `RealtimeRefreshRequired(Machines)` | Home / Settings computers |
| `computer:upgrade:progress` | `services/agentOrchestrator.ts` | `{ machineId, requestId, phase, message?, percent? }` | `RealtimeRefreshRequired(Machines)` | Home / Settings computers |
| `computer:upgrade:done` | `services/agentOrchestrator.ts` | `{ machineId, requestId, ok, newVersion?, rolledBack?, error? }` | `RealtimeRefreshRequired(Machines)` | Home / Settings computers |
| `daemon:status` | legacy React listener parity | `{ daemonId, status, statusVersion? }` | `RealtimeRefreshRequired(Machines)` | Home / Settings computers |
| `server:member-added` | `routes/auth.ts`, `routes/servers.ts` | `{ serverId, userId }` | `RealtimeRefreshRequired(ServerMembers)` | Home |
| `server:member-updated` | `routes/servers.ts` | `{ serverId, userId }` | `RealtimeRefreshRequired(ServerMembers)` | Home |
| `server:member:left` | `routes/servers.ts` | `{ serverId, userId }` | `RealtimeRefreshRequired(ServerMembers)` | Home |
| `server:member-removed` | `routes/servers.ts` | `{ serverId, userId }` | `RealtimeRefreshRequired(ServerMembers)` | Home |
| `server:membership-removed` | `routes/servers.ts` | `{ serverId }` | `ServerMembershipRemoved` | Home / route guard |
| `server:deleted` | `routes/servers.ts` | `{ serverId }` | `ServerDeleted` | Home / route guard |
| `server:plan-updated` | `services/billingService.ts` | `{ serverId, plan }` | `ServerPlanUpdated` | Home / Settings |
| `announcement:new` | `routes/announcements.ts` | announcement object | `RealtimeRefreshRequired(Announcements)` | Home notifications |
| `reminder:fired` | `services/reminderScheduler.ts`, `services/agentOrchestrator.ts` | `{ reminderId, ownerAgentId, firedAt, firedBy, catchup?, nextFireAt? }` | `RealtimeRefreshRequired(Reminders)` | Home / agent detail refresh |
| `reminder:scheduled` | `routes/internal.ts` | `{ reminder }` | `RealtimeRefreshRequired(Reminders)` | Home / agent detail refresh |
| `reminder:canceled` | `routes/internal.ts` | `{ reminder }` or `{ reminderId }` | `RealtimeRefreshRequired(Reminders)` | Home / agent detail refresh |
| `sync:resume:response` | `socket/index.ts` | `{ messages, currentSeq, hasMore }` | `SyncResumeResponse` | Realtime cursor / cache |
| `heartbeat` | `socket/index.ts` | `{ seq, ts }` | `Heartbeat` | Realtime cursor / watchdog |

## Non-shared Events

- `connect`, `connect_error`, `disconnect`, `reconnect`, `reconnect_attempt`, `reconnect_error`, `reconnect_failed`, `rooms:joined`: transport state only.
- `join:channel` and `sync:resume`: client-emitted commands.
- `connection` in `routes/daemon.ts`, `agent:lifecycle`, and `external-inbox-delivered` in `agentOrchestrator.ts`: not Socket.IO UI events consumed by shared/KMP.

## 2026-06-23 Alignment Changes

- Added `agent:session`, `computer:upgrade:progress`, `computer:upgrade:done`, and legacy `daemon:status` to the KMP event contract.
- Moved Android listener white-listing to common `RealtimeSocketEvents.ServerWireEvents` so native listener coverage uses the same contract as shared mapper tests; mirrored the new events in the OHOS bridge white-list.
- Mapped `agent:session` to `RealtimeRefreshRequired(Agents)`.
- Mapped `computer:upgrade:progress`, `computer:upgrade:done`, and `daemon:status` to `RealtimeRefreshRequired(Machines)`.
