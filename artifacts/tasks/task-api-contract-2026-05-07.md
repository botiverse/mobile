# Android Tasks API Contract

Source: `/Users/artin/Downloads/index-EpAFmlk5.js` Web bundle inspection on 2026-05-07, plus a sanitized live `GET /tasks/server` shape check on 2026-05-09. No bearer token, cookie, server id value, or real task body is recorded here.

## Confirmed Web Requests

- `GET /tasks/server`
  - Loads server-level task list for the Tasks page.
  - Web stores response as `serverTasks` and expects a `tasks` collection.
  - Live response shape observed on 2026-05-09: `{ "tasks": [Task] }`.
- `GET /tasks/channel/{channelId}`
  - Loads tasks scoped to a channel/thread message area.
  - Web stores response as channel `tasks`.
- `POST /tasks/channel/{channelId}`
  - Creates one or more tasks from titles with payload `{ tasks: [{ title }] }`.
- `PATCH /tasks/{taskId}/claim`
  - Claims a task and returns `{ task }`.
- `PATCH /tasks/{taskId}/unclaim`
  - Unclaims a task and returns `{ task }`.
- `PATCH /tasks/{taskId}/status`
  - Updates status with payload `{ status }`, returning `{ task }`.
- `DELETE /tasks/{taskId}`
  - Deletes a task.
- `POST /tasks/convert-message`
  - Converts a message to a task with payload `{ messageId }`, returning `{ task }`.

Realtime events observed in the Web app:

- `task:created` with `{ tasks }`
- `task:updated` with `{ task }`
- `task:deleted` with `{ taskId }`

## Android #181 Scope

Implemented the minimum real data boundary required for the Android Tasks tab:

- `SlockApi.serverTasks()` -> `GET /tasks/server`
- `SlockApi.channelTasks(channelId)` -> `GET /tasks/channel/{channelId}`
- `SlockApi.claimTask(taskId)` -> `PATCH /tasks/{taskId}/claim`
- `SlockApi.updateTaskStatus(taskId, body)` -> `PATCH /tasks/{taskId}/status` with JSON body `{ "status": "todo" | "in_progress" | "in_review" | "done" | "closed" }`.
- `RealTaskRepository.getTasks(serverId, filter)` maps server tasks into `TaskSnapshot`.
- `RealTaskRepository.getChannelTasks(serverId, channelId, filter)` maps channel-scoped tasks into the same `TaskSnapshot` model so channel/thread surfaces can reuse the same Tasks panel interactions.
- `RealTaskRepository.claimTask(taskId)` maps `{ task }` into `TaskActionResult`.
- `RealTaskRepository.updateTaskStatus(taskId, status)` maps `{ task }` into `TaskActionResult`.
- Mapper accepts top-level array, `{ tasks: [...] }`, `{ items: [...] }`, and nested `data` shapes via existing JSON helpers.
- Live task items currently use flat fields:
  - `id`, `taskNumber`, `title`, `content`, `description`
  - `status` and duplicate `taskStatus`
  - `channelType` (`channel` observed for server task list)
  - `channelId`, `channelName`
  - `messageId` for the source task message
  - `threadId` for the task message's reply thread
  - `claimedById`, `claimedByName`, `claimedByType`
  - `createdById`, `createdByName`, `createdByType`
  - `createdAt`, `updatedAt`, plus claim/completion timestamps
- For task navigation, Android must treat `messageId + threadId` as a real replies/thread target even when `parentMessageId` is missing. `parentMessageId` is synthesized from `messageId` only for the local `TaskSummary` navigation model; docs must keep the wire shape explicit.
- Status strings map to `TaskStatus.Todo`, `InProgress`, `InReview`, `Done`, and `Closed`; incoming `done`/`complete`/`completed` map to `Done`, while `closed` maps to `Closed`.
- Assignee mapping accepts object shapes (`assignee`, `claimedBy`, `assignedTo`, etc.) and scalar fallback fields (`claimedById`, `claimedByName`, `claimedByType`, `taskAssigneeId`, `taskAssigneeType`, etc.).

## Sanitized Live Shape Check 2026-05-09

- `GET /tasks/server` returned HTTP 200.
- Root object had a `tasks` array.
- The sampled response contained hundreds of server task items; no nested task/message/channel objects were needed for the fields Android consumes.
- Observed status values: `todo`, `in_progress`, `in_review`, `done`, `closed`.
- Observed `channelType`: `channel`.
- Observed navigation-critical pair: `messageId` points to the task/source message, and `threadId` points to that task message's thread. `parentMessageId` was not present in the sampled items.

## Android Boundaries

- UI must consume `TaskRepository`; Compose should not call task endpoints or read token/header/raw response.
- Auth and selected server remain owned by `AuthSessionStore` and the shared API client/interceptors.
- #182 can use `TaskRepository.getTasks(selectedServerId, TaskFilter(...))` for Board/List data and `claimTask(taskId)` for claim action.
- Task status changes are now wired for the Android Tasks page through `TaskRepository.updateTaskStatus`; Compose must not call the endpoint directly or provide raw auth/server headers.
- Channel/thread task panels should call `TaskRepository.getChannelTasks(...)` and feed the same `TasksUiState` / reusable Tasks panel UI. They should hide redundant channel filtering when the API is already channel-scoped.
- Create task, unclaim, delete, and convert-message are confirmed Web endpoints but are intentionally not wired in #181 unless a UI task requests them.

## Error and Security

- No session or selected-server mismatch returns `auth_required` / no-permission style `DataResult`.
- Empty task list maps to `EmptyReason.NoTasks`.
- Parse diagnostics use `source=tasks_server` / `source=tasks_channel` / `source=tasks_claim` / `source=tasks_status`; logs must not include raw body, task content dumps, Authorization, X-Server-Id, or token values.
