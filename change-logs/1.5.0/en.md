# Raft Android 1.5.0

> First release-notes draft. Final wording is still subject to Alpha smoke results and product review.

✨ **New**

- Added message forwarding with destination selection, content previews, and consistent rendering for forwarded messages, Markdown, and attachments
- Added image annotation and editing before upload; the media picker now supports video, with refined image-editor and preview controls
- Added export of downloaded attachments to user files; image and file previews retain thumbnails while full content loads
- Added a persistent copy action for code blocks, with more accurate rendering of code, email addresses, and inline Markdown
- Added in-app entry points for Administration, Computers, and Marketplace surfaces

🔧 **Improved**

- Improved conversation loading, reply-thread handling, and long-list state retention to reduce duplicate refreshes and lost positions
- Improved image request coalescing, decoding, caching, gallery retention, and failure recovery to reduce repeated network work and jank
- Moved member-roster reads to a process-shared data owner so windows and pages reuse one roster instead of repeatedly refreshing it
- Reduced main-thread pressure by serializing background Markdown and JSON parsing and optimizing suggestion ranking and page prewarming
- Made realtime messages, unread state, read receipts, and task status more consistent across Home, Activity, Thread, Tasks, and notifications
- Added and expanded Simplified Chinese translations across more core screens, actions, and status messages
- Double-tapping the current bottom tab now returns its list to the top; pagination, pull-to-refresh, and server-switch viewport retention are more stable

🐛 **Fixed**

- Fixed reply notifications and Activity rows opening the wrong channel, server, or parent message
- Fixed joint-channel reply mention candidates, parent-channel identity, and notification titles
- Fixed read state drifting across pages or windows, stale requests overwriting newer state, and Activity failing to recover after navigation
- Fixed missing Thread task chips, stale task snapshots hiding updates, and recycled status-menu anchors
- Fixed channel profile edits not persisting, missing channel-name validation, lost feedback drafts, and stale Settings state
- Fixed pointer input passing through dialogs, narrow message long-press targets, suggestion-panel flicker, and attachment or thumbnail display issues
- Fixed Android first-login, notification-tap, and app-link flows creating incorrect task stacks or duplicate pages
- Fixed visual inconsistencies in code blocks, blockquotes, badges, icons, fonts, timestamps, and spacing compared with the web app
