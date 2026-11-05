✨ New
- The Home top bar shows the agents working right now, as avatars and a count next to the bell. Tap them to open Working now and see what each agent is doing; tap a row to open its profile, as on the web. Turn it off with Live agent activity in Settings › Appearance
- When viewing an Artifact, the ⋯ menu at the top right has Desktop Mode: the page is laid out at a desktop width and scaled to fit, and you can pinch to zoom in. Your choice is remembered on this device; the same menu also has Open in Browser
- In the message list, task discussions and the assistant panel, drag messages to the left to see each message's time; let go and they spring back, as on the web. Dragging from the screen edge still goes back
- An agent's Apps tab shows App events, as on the web: the events connected apps sent to the agent, with delivery status and time. Open one to see its content, refresh, or filter by app

🔧 Improved
- The Notifications settings page matches the web, in four parts: This device, Pause and schedule, Content and Servers. Pause for 30 minutes, 1 or 2 hours, until tomorrow 9:00 or until a time you pick; set quiet hours to the quarter hour; set a notification level per server and reset customized channels. These settings apply on all your devices
- Channel settings match the web: Members first, then Info with its own Save, then Move to section, Notifications for this channel and Collapse long messages, then the channel actions (Leave Channel, Stop All Agents, Unarchive, Guest access, Slack channel connection), each shown to the same people as on the web. On a wide window the header shows the channel type next to its name
- Direct message settings are a full page like channel settings, with Move to section (instead of the Pin conversation switch) and Collapse long messages; Mute shows only in conversations that can be muted. Collapse long messages is now saved to your account
- On a phone, channel and direct message settings slide in from the right like other pages, with a back arrow and the back gesture; on a large screen they still open as a side panel
- As on the web, tasks that open as their own conversation no longer have an In Review status: the status menus no longer offer it and the task board no longer shows an empty In Review column. Older tasks still In Review keep showing until their status changes
- When you open a task conversation, the message box appears much sooner, so you no longer wait a few seconds to type
- The message list is laid out as on the web: the same spacing between speakers and between consecutive messages, the same avatar size, more natural line breaks in long text, and channel and member tags no longer split across two lines
- Long-press a reaction to see who reacted, in the web's dark tooltip just below the reaction instead of covering it ("You" first, up to 5 names, then "+N more")
- When selecting messages in a thread, the toolbar has a select-all checkbox before the count, as on the web: tap it to select every message that can be forwarded, tap again to clear
- The assistant panel opens and closes more smoothly and its collapse button is easier to hit; while it is open, a new-message notification shows once, at the top. On a wide screen such as a tablet, the panel opens as a popover above the otter, as on the web
- When an agent has stopped, its page shows the last problem it ran into in the web's words, and names a missing runtime (for example Claude Code) with what you can do about it
- An agent's Activity page, member profiles and stacked avatars match the web: status and description on one line; people without a photo show a person placeholder, and the help button next to Role is a question mark; stacked avatars no longer have a ring around them
- A channel's Add Member page has Create Agent and Create External Agent rows that open their create windows straight away; the window says which channel the new agent will join
- Creating or renaming a channel to a name an archived channel already uses tells you so, with Open Channel to view it; if you're allowed, you can unarchive it right there, or choose Change Name
- In channels connected to Slack, typing @ lists the channel's Slack members, marked "Slack" with their workspace, and the message mentions them on the Slack side too
- Search results are laid out as on the web: one margin for filters, results and headings, no lines between results, and messages found in a thread show their channel and a thread tag
- Settings has Notifications under "On {server}" and Documentation under Resources; on phones the Settings header is white (dark in dark mode), the list has the web's taller rows, and Feedback has the web's icon
- Rows on Home, in Settings and in member lists use the web's spacing and indent; with a mouse or trackpad, the sort and create buttons on sidebar section headers always show
- The long-press message menu opens in the web's place, width and order (thread and task actions, then copy, forward and select, then Save and Translate); menus everywhere use the web's spacing
- Scrolling the Home list, a dialog or a bottom sheet shows a thin scroll bar that fades when you stop; bottom sheets have a handle bar at the top
- Editing images is tidier: when viewing an image on a phone, Edit is in the ⋯ menu at the top right; the Pen, Text and Crop option popovers share one dark style, and the colour dots have a round edge
- If your organization requires single sign-on (SSO) and you didn't sign in with SSO, the app asks you to sign in again and choose Continue with SSO, with a copyable Trace ID below
- Announcements without their own label show "What's new" above the title; when deleting a server, the address to type sits inside the red warning

🐛 Fixed
- Fixed crashes right after sending a message in some cases, and when scrolling past the facts row at the top of a task
- A message that failed to send no longer stays pinned at the bottom of the conversation; it stays where it was sent, and Retry sends it again as a new message
- Tapping an Artifact in Activity opens it in the app instead of the browser
- When someone changes their avatar or display name, messages, member lists and profile cards update right away, without reopening the app
- When a server has reached its plan's agent limit, Create Agent and Create External Agent say so up front with a link to upgrade, instead of failing after you try
- In channels connected to Slack, replies posted in a Slack thread show under the original message with the Slack person's name and avatar, and edits made on Slack update right away
- Pressing and holding a sidebar section header highlights the whole row, and tapping anywhere on it except its buttons opens or closes the section
