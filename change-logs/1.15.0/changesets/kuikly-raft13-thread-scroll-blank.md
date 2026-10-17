---
type: fixed
area: thread
platforms: [android]
rollout: full
pull_request: null
---

Fix blank rows flashing in while fast-scrolling a long thread (task #178): the Kuikly renderer bump to 2.28.0-raft.13 delays the reverse-layout scroll re-anchor until the scroll settles, so rows are no longer unbound mid-fling. Root cause was confirmed on device via the bottom-up prefetch A/B (baseline ~804px blank for ~3.3s per fling, near-zero with prefetch disabled); runtime verification of this carrier is tracked in task #178.
