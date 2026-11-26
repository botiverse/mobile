---
version: 1.31.0
platforms: [android, ios, desktop]
area: channels
---

# Allow participant server admins to remove own-server agents in joint channels

- In a joint channel viewed from a participant server, honor the server's per-row `canRemoveFromChannel` flag directly (matching web `ChannelMembers.tsx`) instead of gating it behind the channel-level `removeChannelMembers` capability (which is `false` on participant entries because humans cannot be removed).
- Keep removal disabled on archived channels, `#all`, and offline stand-in member lists.
