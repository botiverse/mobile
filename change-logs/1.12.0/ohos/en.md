✨ **New**

- Mermaid code blocks in threads now render as diagrams instead of source text; zoom controls in the bottom-right corner (50%–300%), tap the percentage to reset; unparseable diagrams fall back to the code block
- Audio attachments (MP3, WAV, OGG, M4A, AAC, FLAC, etc.) now play inline in the message card — no external app needed
- XLSX spreadsheet previews: .xlsx/.xlsm attachments show worksheet tabs and table content in the file viewer (up to 20 sheets, 200×20 cells each); new "Open in browser" action in the file viewer
- Image editor gains a crop tool with draggable rule-of-thirds handles; existing annotations remap automatically and exports have no letterboxing; toolbar buttons now have text labels and the mosaic icon is refreshed
- Thread pages (Files / Tasks / Activity tabs) support swipe switching and keep each tab's scroll position

🔧 **Improved**

- Tasks panel now loads in pages: first screen and later pages fetch summary data only, so it opens faster and uses less memory; the board view pages each status column independently
- Adding multiple channel members is now atomic: either everyone is added or no one is — no more partial adds or duplicates
- Attachment save failures now distinguish "download failed" from "can't write to storage" with matching messages
- Large attachment direct uploads pin HTTP/1.1 and allow more connect time, so slower networks no longer break uploads
- In "My messages: right-aligned" mode, other people's messages are also capped to a maximum width instead of filling the row

🐛 **Fixed**

- Fixed blank Google/GitHub icons on the sign-in page and Account › Connected accounts; icons now load from bundled assets
- Fixed image previews flickering and reloading
- Fixed the Activity feed not refreshing when returning from background
- Fixed the "N replies" preview row taking a long time to appear when entering a channel from Activity, notifications, or search
- Fixed the composer briefly missing then appearing when entering a thread
- Attachment cards no longer show a "Comments" button; comments are still available from the preview page's action bar
