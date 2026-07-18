---
title: Keep the Raft icon visible during Android 12+ cold-start hold
type: fix
authors:
  - Android-Developer-4
pr: 1260
---

Preserve the centered Raft foreground while the Android 12+ app shell waits for its first Home frame, without changing the existing 1.5-second fallback.
