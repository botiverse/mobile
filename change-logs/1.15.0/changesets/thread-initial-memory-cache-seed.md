---
type: fixed
area: thread
platforms: [android, ios, ohos]
rollout: full
pull_request: null
---

Entering a channel you have opened before no longer flashes a "loading" shell for a frame: the thread store now seeds its first frame synchronously from the in-memory payload cache at construction, instead of waiting for the async start intent to reach the same cache. Cold entries (no cached payload) are unchanged, and reply threads keep the existing start path since their parent-message enrichment needs the session.
