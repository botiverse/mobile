---
version: 1.31.0
platforms: [android, ios, desktop]
---

- Evaluate `trace_sample.*` observability flags in an independent background request isolated from product feature flags, and retire unused/graduated mobile flag keys (`sync_core_notification_prefs_v0`, `mobile_inbox_inplace_v0`, `mobile_raft_sign_in_v0`).
