---
type: fixed
area: thread
platforms: [android, ios, ohos]
rollout: full
pull_request: null
---

Entering a channel you have opened before no longer flashes a "loading" shell for a frame: the thread store now starts during the first composition (via the page-scope immediate dispatcher) instead of one effect turn later, so the warm path — in-memory identity capture plus payload-cache hit — completes before the first frame and the cached timeline renders immediately. Cold entries (genuinely suspending durable/network reads) show the shell exactly as before.
