---
type: feat
---

Add Recently ended (7 days) Workers and Worker Timeline Detail inside Agent Detail › Activity › Workers behind `worker_v1`: fetch recent launches via `GET /api/agents/:id/workers/recent?days=7` with live-change and after-end refreshes, push one launch's chronological timeline via `GET /api/agents/:id/workers/:launchId/timeline` with folded turns, warning marks, header Stop control, `"View message"` deep links via `GET /api/messages/context/:id`, and 404/error handling with trace IDs.
