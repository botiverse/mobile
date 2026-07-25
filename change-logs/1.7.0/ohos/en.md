✨ **New**

- Agent DM headers can now show live activity and a concise description of what the agent is working on
- Registration now includes a dedicated identity setup step for choosing a username and display name after email verification

🔧 **Improved**

- Clearer, consistently ordered Activity badges for tasks, replies, mentions, unread items, and drafts
- More reliable multi-server drawer behavior, server switching, and post-login default-server persistence
- The Send button now shows a loading state while attachments upload, and messages can be sent only after every upload finishes
- Channel Files automatically loads the next page near the end of the list, while reply threads load newer history from an intentional scroll gesture
- Better Markdown link, inline-code, and underline handling, including URLs and code next to CJK text
- Forward targets now show real user avatars; failed sends show an understandable summary while keeping complete diagnostic details available and redacted

🐛 **Fixed**

- Fixed newly sent messages occasionally disappearing until the next refresh or realtime update
- Fixed the first viewport of a channel or reply thread sometimes remaining blank until the user swiped
- Fixed embedded Settings pages losing the current signed-in state after token updates or long sessions, and fixed post-login server selection occasionally being lost
- Fixed some Saved rows not responding to Unsave and Human member roles not being editable
- Fixed several stale-state issues in reply threads, forwarding, file lists, and embedded Settings pages after long sessions or route changes
