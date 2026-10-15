---
type: fixed
area: composer
platforms: [ios]
rollout: full
pull_request: null
---

Fix Korean IME input on iOS: sending a message mid-composition no longer leaves the last syllable in the composer (it previously resurfaced as the first character of the next message). Shipped via the Kuikly renderer bump to 2.28.0-raft.12, which ends the IME composition before programmatic text reads and notifies the keyboard when programmatic replacement changes content. The same bump also stops OHOS frame monitoring while paused.
