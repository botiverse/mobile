# Android Tasks API Contract

Source: `/Users/artin/Downloads/index-EpAFmlk5.js` Web bundle inspection on 2026-05-07.

## Confirmed Web Requests

- `GET /tasks/server`
  - Loads server-level task list for the Tasks page.
  - Web stores response as `serverTasks` and expects a `tasks` collection.
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
- `SlockApi.claimTask(taskId)` -> `PATCH /tasks/{taskId}/claim`
- `RealTaskRepository.getTasks(serverId, filter)` maps server tasks into `TaskSnapshot`.
- `RealTaskRepository.claimTask(taskId)` maps `{ task }` into `TaskActionResult`.
- Mapper accepts top-level array, `{ tasks: [...] }`, `{ items: [...] }`, and nested `data` shapes via existing JSON helpers.
- Status strings map to `TaskStatus.Todo`, `InProgress`, `InReview`, and `Done`.
- Assignee mapping accepts object shapes (`assignee`, `claimedBy`, `assignedTo`, etc.) and scalar fallback fields (`claimedById`, `claimedByName`, etc.).

## Android Boundaries

- UI must consume `TaskRepository`; Compose should not call task endpoints or read token/header/raw response.
- Auth and selected server remain owned by `AuthSessionStore` and the shared API client/interceptors.
- #182 can use `TaskRepository.getTasks(selectedServerId, TaskFilter(...))` for Board/List data and `claimTask(taskId)` for claim action.
- Create task, unclaim, status update, delete, and convert-message are confirmed Web endpoints but are intentionally not wired in #181 unless a UI task requests them.

## Error and Security

- No session or selected-server mismatch returns `auth_required` / no-permission style `DataResult`.
- Empty task list maps to `EmptyReason.NoTasks`.
- Parse diagnostics use `source=tasks_server` / `source=tasks_claim`; logs must not include raw body, task content dumps, Authorization, or token values.
