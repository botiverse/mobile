✨ New
- On tablets, drag the tabs in the left rail (Chat, Activity, Tasks, Members, Computers, Apps and so on) into your own order; it is saved on your account and shared with the web
- On tablets, drag the right edge of the sidebar or the Activity list to resize it; drag past the narrowest width to collapse it, like Collapse Sidebar
- Drag files onto the message composer to attach them (from another app in split screen, desktop windowing or on a tablet); it shows "Drop files to attach"
- Notifications on Home tells you when a Computer you manage must be upgraded or reinstalled before a deadline, after which its agents stop running; View opens that Computer
- raft:// links in messages are tappable and open the channel, DM, thread or message right in the app
- On tablets in the wide layout, tapping the search field in the top bar (or ⌘K / Ctrl+K with a keyboard) opens a search panel over the page, as on the web: recent conversations first, then matching channels, people and messages as you type
- Edit an image from a conversation: in the image viewer tap ⋯ at the bottom right and choose Edit on a phone, or tap Edit at the top right on a tablet in the wide layout. Use Pen, Text, Mosaic, Crop and Rotate; the edited copy goes into the message box as a new image and the original message stays as it was

🔧 Improved
- When a server request fails, the error shows its Trace ID: tap Copy on the toast and send it to us so we can find the request. When a thread can't be opened for a reason inside the app, the message says why and what to do
- On tablets, the list, the conversation, the thread and Tasks and Files share one row as on the web, and the conversation keeps enough width as the window narrows
- With a hardware keyboard connected, start typing as soon as you open a channel, DM or thread
- With a mouse or trackpad, resting the pointer on a link in a message shows where it goes at the bottom-left of the window
- App link cards match the web: up to two title lines, details such as author and duration, and a short name for the link (rolling out by server)
- Scrolling chats with long messages is smoother
- The new page navigation (Navigation 3) is now on for everyone: opening a conversation, going back and moving between pages animate more smoothly, with the conversation sliding in together with its header
- Opening a conversation from Home or Activity no longer stalls halfway through the slide; the messages appear as the slide ends
- Settings › Appearance matches the web: a row at the top opens the current server's theme, Message font size shows a message preview, and there are two new switches, Show agent model and Hide empty sidebar sections

🐛 Fixed
- Fixed a crash that could happen when you kept typing just as a slow message finished sending
- Opening a channel, thread or task conversation no longer makes the messages jump, and @mentions and #channels show as chips from the start
- While Pinyin or another input method has an unconfirmed candidate, inserting a mention, adding a quote or restoring a draft no longer types the raw letters
- Conversations marked Done on the web or another device leave Activity without a manual refresh
- Markdown tables take the width of their content instead of narrow wrapped columns; on wide screens, sender names show in full when there is room
- Back after switching bottom tabs returns to the previous tab instead of Home
- Long-pressing a message while the keyboard is up no longer closes the keyboard, so you can keep typing after closing the menu
- Tapping a message in a task's conversation no longer shows "Unable to open thread"; as on the web, messages there no longer offer Reply in Thread
- In the image viewer on tablets, the time at the top now shows your local time instead of a raw date code
- The status and assignee at the top of a task page work as on the web: tap the status to move the task to any status, or tap the assignee to pick someone else or unassign it; on done or read-only tasks they are plain text without a dropdown arrow
- Cancelling a text selection no longer flashes the Cut / Copy / Paste bar in the top-left corner of the screen
