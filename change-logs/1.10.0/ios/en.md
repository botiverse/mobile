✨ **New**

- Added a non-intrusive notification center on Home, plus an optional multi-server home drawer (off by default; enable it in Settings), with clearer server-row actions and a stable workspace switcher position
- Added computer discovery and management: find computers in Search, open their profiles, and manage direct-message settings; the new Computer Offline Alerts option can show a Home alert when a registered computer goes offline and is off by default; saving an agent's runtime model now applies to the detail page immediately
- The same task no longer appears more than once across joint channels, and claims and status changes apply to the currently selected server
- Personalized Search Home: recent searches are kept per account (up to 15) and frequently used channels, DMs, and contacts from the last 90 days are recommended (up to 10); recents can be edited individually or cleared without affecting recommendations, and archived, unjoined, unreadable, or hidden targets never appear

💪 **Improved**

- Avatars now use a durable cache: previously cached avatars appear immediately after restarting the app or re-entering a page instead of flashing a placeholder; the cache is partitioned by content with size limits, failed or interrupted writes never leave partial entries, and account-scoped entries are retired on sign-out
- Thread reading: adjustable message font size, clearer timestamps and unread anchors, the keyboard no longer covers messages, more stable text selection and long-press toolbar (copy, share), and Markdown tables now render bold headers correctly
- Attachments and previews: image annotation is now a standard capability, oversize files are clearly flagged against server limits, long-pressing an image can capture a photo, MOV video preview is supported, attachments keep their canonical names, and preview identity is more stable
- Direct messages and link navigation: more accurate DM and parent-message headers, quoted previews for Raft permalinks, direct thread links in conversations, and more reliable back navigation
- Improved Activity navigation, mute settings, and system-event anchors; the server switcher shows numeric unread badges again — an exact count wins when available, otherwise it falls back to the legacy broader unread number (which can differ from the Activity total), and an authoritative zero stays hidden

🐛 **Fixed**

- Composer reliability: sending now uses the native text snapshot, @/# suggestions support pointer and Enter acceptance, batched mention actions are more stable, and send failures give actionable feedback; when a stale text callback reports an untrusted cursor position, the insertion cursor and mention panel are preserved instead of being reset
- Safer sending and replies: transient messages handle timeouts, reconnects, and duplicate sends more robustly; reply-origin recovery and reply permission now follow the currently selected reply thread instead of stale page state
- Local data and input state: unsupported database downgrades are blocked, and search and other short forms preserve text selection and IME composition while typing
- Settings, copy, and network: removed the dead Reactions toggle from the message menu, fixed Settings pages that could fail to appear, made Simplified Chinese terminology consistent, kept registration terms reachable, and network requests now follow system proxy changes
- Fixed a race in automatic session refresh: when a refresh ended ambiguously, a follow-up request could suppress the "session expired" signal — expiry now arrives reliably and the app prompts for sign-in when it should
