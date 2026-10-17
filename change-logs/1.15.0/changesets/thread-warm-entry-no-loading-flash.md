---
type: fixed
area: thread
platforms: [android, ios, ohos]
rollout: full
pull_request: null
---

Entering a channel you have opened before no longer flashes a "loading" shell for a frame: the thread ViewModel now starts its store at creation, and the page-scope dispatcher runs the warm path (identity capture + in-memory payload cache hit) inline during composition, so the first frame already renders the cached timeline. Cold entries (genuinely suspending durable/network reads) show the shell exactly as before, and duplicate start dispatches are now no-ops.
