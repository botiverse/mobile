✨ **New**

- Added an in-app browser, with a setting to use the system browser or open web links directly inside Raft
- Added generic action cards so Agent requests can be confirmed or executed directly in the app
- Tap a message body to open its reply thread with the parent pinned at the top; inline videos now load only after a tap
- Added an optional HTTP/3 transport with safe fallback to HTTP/2 or HTTP/1.1

⚡ **Improved**

- Improved Home and thread first-frame loading, prewarming, and background scheduling to reduce waits and jank when opening replies, sending messages, and switching bottom tabs for the first time
- Moved Android loading spinners to native animation to reduce rendering overhead in conversations, members, and settings
- Return now defaults to newline for new users, while joint-channel @ suggestions prioritize the current server and still surface members from the connected server
- Improved visual consistency for member rows, selected and unread channel titles, Create Agent capacity warnings, form labels, and the session-restore landing screen
- Improved dialog, popover, and accessibility nodes so actions are discovered and tapped more reliably

🐛 **Fixes**

- Fixed agent replies or concurrently arriving messages temporarily disappearing during consecutive sends
- Fixed the keyboard not reopening when inserting another avatar mention after manually dismissing it
- Fixed click-through and placement issues affecting the back-to-bottom button, long-press menus, and popovers in threads
- Fixed stale member presence overwriting newer state and occasional drift between task and message status
- Fixed the brand icon disappearing during the Android 12+ cold-start hold
- Fixed additional suggestion ranking, selected-image capture, channel-list, input, performance, and display issues
