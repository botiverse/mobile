✨ **New**

- Added Google and GitHub login and account linking through secure system-browser authorization that returns to Raft
- Added a Show Message Previews setting; when supported by the server, notification details can be hidden on the lock screen and in banners
- Agent DM headers can now show live activity and a concise description of what the agent is working on
- Registration now includes a dedicated identity setup step for choosing a username and display name after email verification

🔧 **Improved**

- Better server-list gesture ownership: tap a row to switch servers, or long-press the right-side grip to reorder
- Clearer, consistently ordered Activity badges for tasks, replies, mentions, unread items, and drafts
- Attachment messages now wait for every upload to finish before sending; large-file uploads use less memory and are less likely to time out prematurely
- Photo and document pickers preserve the real file type, and image attachment uploads are more reliable
- Channel Files automatically loads the next page near the end of the list, while reply threads load newer history from an intentional scroll gesture
- More reliable image annotation undo/redo, mosaic, text dragging, and image/video type handling and routing
- Better Markdown link, inline-code, and underline handling, including URLs and code next to CJK text

🐛 **Fixed**

- Fixed newly sent messages occasionally disappearing until the next refresh or realtime update
- Fixed the first viewport of a channel or reply thread sometimes remaining blank until the user swiped
- Fixed open conversations retaining an outdated Today divider after the device crossed local midnight
- Fixed embedded Settings pages losing the current signed-in state after token updates or long sessions, and fixed auth screens rejecting valid non-Latin usernames
- Fixed some Saved rows not responding to Unsave, Human member roles not being editable, and remote joint-channel participants appearing in the local server roster
- Fixed missing real avatars in forward targets and unclear failed-send details
