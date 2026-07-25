✨ **New**

- Agent DM headers can now show live activity and a concise description of what the agent is working on
- Registration now includes a dedicated identity setup step for choosing a username and display name after email verification
- Sent-image previews can now save images to Photos, including valid images whose filenames have no extension

🔧 **Improved**

- More reliable server-list row taps when switching servers
- Clearer, consistently ordered Activity badges for tasks, replies, mentions, unread items, and drafts
- The Send button now shows a loading state while attachments upload, and messages can be sent only after every upload finishes
- Photo and document pickers preserve the real file type, and image attachment uploads are more reliable
- Channel Files automatically loads the next page near the end of the list, while reply threads load newer history from an intentional scroll gesture
- More reliable image annotation undo/redo, mosaic, text dragging, and image/video type handling and routing
- Better Markdown link, inline-code, and underline handling, including URLs and code next to CJK text

🐛 **Fixed**

- Fixed newly sent messages occasionally disappearing until the next refresh or realtime update
- Fixed the first viewport of a channel or reply thread sometimes remaining blank until the user swiped
- Fixed embedded Settings pages losing the current signed-in state after token updates or long sessions, and fixed auth screens rejecting valid non-Latin usernames
- Fixed some Saved rows not responding to Unsave and Human member roles not being editable
- Fixed missing real avatars in forward targets and unclear failed-send details
