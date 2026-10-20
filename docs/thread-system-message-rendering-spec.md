# Thread System Message Rendering Spec

## Scope

This spec defines how Android/KMP should render persisted system messages in thread timelines, with special attention to task lifecycle messages such as "moved #N ... to Done".

There are two similarly named flows and they must stay separate:

- Task status Done: changing a task to `done` posts a persisted system message into that task's own thread. The thread UI must show that message in chronological order, including when it is the last message.
- Thread inbox Done: `POST /channels/threads/done` only marks the current user's thread follow row as done/read. It does not create a chat message and should not render a system row.

## React/Web Behavior

React renders system messages in both main chat timelines and thread timelines.

- `ChatPanel.tsx` calls `buildSystemMessageRenderStates(messages)` and applies the returned render states while rendering channel/DM messages. This covers normal channel chat UI.
- `ThreadPanel.tsx` applies the same `buildSystemMessageRenderStates(messages)` to thread messages. It accepts `message:new` / `message:updated` only when `msg.channelId === threadChannelId`, fetches `/messages/channel/{threadChannelId}`, and merges only messages from that thread channel.
- `systemMessageGrouping.ts` treats adjacent `messageType === "system"` rows as render-only groups. One system message renders as a normal system row; two or more adjacent system rows render as a disclosure summary, hiding later rows until expanded. The underlying message list is not changed.
- `MessageItem.tsx` renders a system message as a compact centered row with timestamp plus muted single-line text and no avatar.

The consequence for parity is simple: if the server has persisted a `messageType=system` row in a thread channel, React's thread panel displays it inside that thread timeline.

## Server Contract

System messages are real message rows.

- `messageService.broadcastSystemMessage(...)` calls `createMessage(channelId, "user", "system", content, "system")`, enriches it with `senderName: "System"`, and emits `message:new`.
- For thread channels, the server joins human followers and emits `message:new` to each follower's user room. This is a delivery detail only; the row still lives in the thread channel.

Task lifecycle events:

- Claim posts a system message to the task's own thread: `claimed #N "title"`.
- Release posts to the task's own thread: `released #N "title"`.
- Status update posts to the task's own thread: `<status emoji> <actor> moved #N "title" to <Status Label>`.
- For status `done`, the label is `Done`, so the content is the persisted row the user expects to see after the task is marked done.
- Convert-message-to-task posts a system message in the parent channel, not in the task thread.
- Task delete currently posts a system message in the parent channel, not in the task thread.

Thread inbox done:

- `POST /channels/threads/done` validates the thread and calls `channelService.markThreadDone(...)`.
- `markThreadDone(...)` sets `threadFollows.doneAt` and marks the thread read.
- No system message is created for this personal inbox action.

## Android/KMP Requirements

R1. Preserve system messages from every source.

Thread REST fetches, realtime `message:new`, realtime recovery/sync, and any message-store merge path must preserve `messageType=system` / `MessageType.System`. Do not filter these rows out because they have `senderId=system`, empty `authorKind`, no avatar, no task fields, or no normal sender membership.

R2. Render system messages inside the thread message list.

For the currently open thread, if a received or fetched message has `channelId == currentThreadChannelId` and `messageType=system`, render it at its sequence/created-at position among replies. If it is the newest message and the user is at bottom, it must be appended as the last visible row.

R3. Do not substitute `task:updated` for the system message.

When a task moves to Done, Android may receive both:

- `task:updated`, which updates task status/badge/state.
- `message:new`, whose `messageType=system` row is the visible lifecycle notice.

Both paths are required. Updating task state alone does not satisfy this spec; the visible Done notice must come from the system message row.

R4. Match React row semantics.

A system message row should be compact, muted, timestamped, and not show sender avatar/name, reactions, task action menus, reply actions, or thread-open affordances. If KMP already supports expand/collapse for long text, that is acceptable as long as the collapsed state is compact and the row remains visible.

R5. Group adjacent system messages like React or document a phased exception.

Parity target: adjacent system messages collapse into one summary disclosure while preserving expandable details. If implementation is phased, the minimum acceptable first step is that every system row is visible and not dropped; grouping can follow as a separate task only if explicitly accepted.

R6. Personal thread inbox Done must not create a row.

Calling the thread inbox Done action should remove/hide the thread from active inbox state according to follow metadata. It should not inject a local fake "done" message. If a new real message later arrives, normal server behavior clears `doneAt` and the new message is rendered normally.

R7. Gap/newer handling must not drop system messages.

If the user is not at bottom or the thread has a newer gap, a system `message:new` should follow the same unread/newer-count behavior as chat messages. When the user loads newer messages or refreshes, the system row must appear. Do not silently ignore it because it is a system row.

R8. Keep source references in tests.

Parser and reducer tests should include representative thread `message:new` and REST rows with `messageType="system"`, including a Done lifecycle content string. Tests should assert that the row survives mapping and becomes a visible thread item.

## Acceptance Matrix

| Case | Expected behavior |
| --- | --- |
| Task thread open at bottom, task moved to Done | Task status updates and a system row appears at the bottom: `<actor> moved #N "title" to Done` with the status emoji prefix if present. |
| Task thread open away from bottom | System row is counted as new/newer work, not dropped; loading newer or returning to bottom shows it in order. |
| App restart or thread reopen after task Done | Initial `/messages/channel/{threadChannelId}` fetch includes and renders the persisted Done system row. |
| Realtime reconnect/sync recovery includes Done row | Recovery mapper preserves `MessageType.System`; reducer merges it into the thread timeline. |
| Task claimed/released/status changed to non-Done | Lifecycle system row appears in the task's own thread with the server content. |
| Message converted to task | Parent channel shows the convert system message; the new task thread is not expected to show a convert row unless server changes. |
| Task deleted | Parent channel shows the delete system message under the current server contract; task thread delete-row parity is not required by this spec. |
| Thread inbox marked Done | No chat row appears; only thread follow/read state changes. |
| Adjacent system messages | React parity target is one summary disclosure with expandable rows. First implementation may render visible compact rows only if accepted as phased. |

## Current KMP Notes

The current KMP code already has partial system-message support:

- `ThreadMessagesParser.kt` maps wire `messageType=system` to thread item `messageType="System"` and blank `authorKind`.
- `RealtimeEventMapper.kt` maps wire `messageType=="system"` to `MessageType.System`.
- `ThreadPage.kt` detects `RaftThreadMessageItem.isSystemMessage()` and renders `ThreadSystemMessageRow`.

The remaining risk to verify/fix is end-to-end retention: thread REST, realtime `message:new`, sync/recovery, store reducers, gap handling, and list rendering must all keep the server's system row and append/render it in the current thread. A local status update without the parallel `message:new` row is not enough.

## Source References

- Web grouping: `<slock-source>/packages/web/src/components/message/systemMessageGrouping.ts`
- Web channel rendering: `<slock-source>/packages/web/src/components/message/ChatPanel.tsx`
- Web thread rendering and realtime merge: `<slock-source>/packages/web/src/components/message/ThreadPanel.tsx`
- Web system row UI: `<slock-source>/packages/web/src/components/message/MessageItem.tsx`
- Server system-message creation/broadcast: `<slock-source>/packages/server/src/services/messageService.ts`
- Server task lifecycle-to-thread contract: `<slock-source>/packages/server/src/routes/tasks.ts`
- Server thread inbox Done endpoint: `<slock-source>/packages/server/src/routes/channels.ts`
- Server thread follow Done state: `<slock-source>/packages/server/src/services/channelService.ts`
- KMP thread parser/render notes: `compose/shared/src/commonMain/kotlin/build/raft/app/data/ThreadMessagesParser.kt`, `compose/shared/src/commonMain/kotlin/build/raft/app/data/realtime/RealtimeEventMapper.kt`, `compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt`

## Non-Goals

- Do not invent a local-only system message for personal thread inbox Done.
- Do not change server task lifecycle placement in this spec.
- Do not render system rows in the task board as a substitute for the thread timeline.
- Do not modify the external `botiverse/slock` checkout from this Android repo;
  pin and record its SHA when using it as evidence.
