---
version: 1.31.0
platforms: [android, ios, desktop]
area: channels
---

# Show info toast when a removed joint-channel member remains via another server

- Parse the optional `stillInChannel` payload (`through` servers and fallback `text`) returned by `DELETE /api/channels/:id/members/user/:memberId` in joint channels.
- Keep the person in the channel roster and `@`-mention suggestions when `stillInChannel` is present, refreshing the channel member slice from the server so their remaining server badges and permissions update accurately.
- Show an 8-second localized info toast across all 8 UI locales (falling back to the server-provided `stillInChannel.text` when needed) explaining that only the viewed server’s side of their membership was removed.
