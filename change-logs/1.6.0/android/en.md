✨ **New**

- Added a web link opening preference: use the system browser or browse inside Raft with back, forward, and reload controls
- Conversations now support generic action cards, letting you confirm or execute agent-requested actions directly in the app
- Added an opt-in setting to open reply threads by tapping message bodies, while long-press, link, avatar, and scrolling interactions keep working
- Added a device-local HTTP/3 preference, off by default, with safe fallback to HTTP/2 or HTTP/1.1 when HTTP/3 is unavailable

⚡ **Improved**

- Faster first frames and first switches across Home and reply threads, with foreground interaction taking priority over background prewarm work
- Lower loading-animation overhead for smoother conversation, member, and Settings loading states
- More consistent realtime sync, reconnects, message updates, and member presence, reducing duplicates, ordering issues, and stale state overwrites
- More stable composing: Return defaults to newline, mention suggestions no longer jump while ranking, and repeated avatar mentions reopen the keyboard
- More consistent Members row density, subtitles, and presence indicators; selected and unread channel names are easier to spot
- Clearer Create Agent capacity limits, upgrade actions, and form labels aligned with the web experience

🐛 **Fixed**

- Fixed task state occasionally drifting from the source message after converting a message into a task
- Fixed agent replies or other messages received between consecutive sends temporarily disappearing until the conversation was reopened
- Fixed reply-thread opens and sends being delayed by background prewarm work, and the parent message briefly appearing blank on first frame
- Fixed consecutive Activity or notification opens into different messages in the same reply thread sometimes returning to the previously focused message
- Fixed Activity refreshes after loading multiple pages returning to loading, truncating later entries, or losing the loaded position
- Fixed live message or read-state updates repeatedly reshuffling the top of Activity or restarting the refresh indicator while the page is open
- Fixed Thread history continuing to load more pages without a fresh drag, which could cause extra work and unstable first-screen rendering
- Fixed the Back to Bottom button, popover menus, and dialog actions sometimes activating content underneath
- Fixed long-press message menu position flicker, confusing thread-follow icons, and incomplete image capture from selected messages
- Fixed inline videos loading automatically when entering a conversation, which could cause unnecessary data use and stutter
- Fixed initial mention suggestions in joint channels omitting members from the connected server
- Fixed the Raft icon disappearing too early while the Home screen loads during cold start
- Fixed soft branding and awkward Simplified Chinese tagline wrapping on the session-restore landing screen
- Fixed Settings feedback rejecting supported large video or file attachments before upload
- Fixed additional composer, member presence, list rendering, and conversation stability issues
