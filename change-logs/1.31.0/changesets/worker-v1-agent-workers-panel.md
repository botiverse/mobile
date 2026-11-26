---
type: feat
---

Add the Agent Detail › Activity › Workers panel behind `worker_v1`: subscribe to and map realtime `agent:workers:changed` nudges, fetch live Worker snapshots via `GET /api/agents/:id/workers` with digest deduplication, staleness handling, and live status marks, support confirmed `POST /api/agents/:id/workers/:worker/stop` with typed refusal handling, rename the Activity segment to `Workers` when enabled, and tag runtime sub-agents alongside live Workers.
