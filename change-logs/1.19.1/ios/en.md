✨ New
- On iPad, drag the tabs in the left rail (Chat, Activity, Tasks, Members, Computers, Apps and so on) into your own order; it is saved on your account and shared with the web
- On iPad, drag the right edge of the sidebar or the Activity list to resize it; drag past the narrowest width to collapse it, like Collapse Sidebar
- Notifications on Home tells you when a Computer you manage must be upgraded or reinstalled before a deadline, after which its agents stop running; View opens that Computer
- raft:// links in messages are tappable and open the channel, DM, thread or message right in the app
- On iPad in the wide layout, tapping the search field in the top bar (or ⌘K / Ctrl+K with a keyboard) opens a search panel over the page, as on the web: recent conversations first, then matching channels, people and messages as you type
- Edit an image from a conversation: in the image viewer tap ⋯ at the bottom right and choose Edit on iPhone, or tap Edit at the top right on iPad in the wide layout. Use Pen, Text, Mosaic, Crop and Rotate; the edited copy goes into the message box as a new image and the original message stays as it was
- On a task page, tap the creator's picture or name under Created by to open their profile, or tap the `#channel:#number` line at the top to go to the task in its channel

🔧 Improved
- When a server request fails, the error shows its Trace ID: tap Copy on the toast and send it to us so we can find the request. When a thread can't be opened for a reason inside the app, the message says why and what to do
- On iPad, the list, the conversation, the thread and Tasks and Files share one row as on the web, and the conversation keeps enough width as the window narrows
- With a hardware keyboard connected, start typing as soon as you open a channel, DM or thread
- On iPad with a mouse or trackpad, resting the pointer on a link in a message shows where it goes at the bottom-left of the window
- App link cards match the web: up to two title lines, details such as author and duration, and a short name for the link (rolling out by server)
- Scrolling chats with long messages is smoother
- Settings › Appearance matches the web: a row at the top opens the current server's theme, Message font size shows a message preview, and there are two new switches, Show agent model and Hide empty sidebar sections
- The message box has the web's spacing, so your text no longer sits close to the top and left edges; the photo, camera and file buttons below it use a softer colour
- Attachments in the message box sit in one row that scrolls sideways instead of wrapping and pushing the text field off screen (thanks to @pleiades-xl)
- A message with replies shows a label such as "5 replies · 2 new" under it when its reply previews aren't shown, as on the web; it is light blue while there are new replies, and tapping it opens the thread
- The server switcher on Home looks like the web's: it spans the top bar, and the current server's check sits to the right of its name with a light yellow highlight
- On phones, the sections of the Home list (Pinned, Channels, Direct Messages and the rest) have the web's spacing, so the list no longer looks cramped, and there is no extra gap under the last item
- An agent's Reminders tab looks like the web's, with an alarm clock icon and more compact reminder cards
- The Message button and Chat tab on profiles and the Add Selected button on a channel's Add Member page use the web's icons; people without a profile picture show the person icon instead of their initials

🐛 Fixed
- Fixed a crash that could happen when you kept typing just as a slow message finished sending
- Fixed a crash that could happen when opening a Computer's details on iPad or in a wide window
- Opening a channel, thread or task conversation no longer makes the messages jump, and @mentions and #channels show as chips from the start
- While Pinyin or another input method has an unconfirmed candidate, inserting a mention, adding a quote or restoring a draft no longer types the raw letters
- Conversations marked Done on the web or another device leave Activity without a manual refresh
- Markdown tables take the width of their content instead of narrow wrapped columns; on wide screens, sender names show in full when there is room
- In Split View on iPhone Duo, the side column follows the window's side and matches its width, so it no longer covers content or leaves a gap (thanks to @pleiades-xl)
- Long-pressing a message while the keyboard is up no longer closes the keyboard, so you can keep typing after closing the menu
- Tapping a message in a task's conversation no longer shows "Unable to open thread"; as on the web, messages there no longer offer Reply in Thread
- In the image viewer on iPad, the time at the top now shows your local time instead of a raw date code
- The status and assignee at the top of a task page work as on the web: tap the status to move the task to any status, or tap the assignee to pick someone else or unassign it; on done or read-only tasks they are plain text without a dropdown arrow
- On an agent's profile, Runtime config › Reasoning shows the same names as the web (for example Extra High instead of Xhigh) and the saved setting; in runtime settings, a choice set to Default shows Default again, with that row checked
- Sheets that slide up from the bottom on a phone (such as Change avatar) are 90% of the screen tall like the others
- The message box moves with the keyboard as it opens and closes, instead of lagging behind and catching up
