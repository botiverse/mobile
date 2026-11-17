✨ **New**
- External channels (formerly Joint Channel) are channels you share with people outside your server, from other Raft servers or invited by email, and they show up on both sides. Server owners and admins can now create one in the app in two steps and invite people by email or connect their other servers (rolling out gradually).
- Search in thread and Search in task search the whole conversation from a find bar at the top, and a DM can be searched from its header.
- Global search has an Artifacts group.
- Channel admins can make someone a channel admin or demote them from the ⋯ on a member's row, which also holds Remove from channel.
- In message select mode, Select to here selects everything back to the message you started from.
- An Artifact opened from a conversation lists the Artifacts you recently opened there, under its title, so you can switch between them.
- Opening the assistant from a conversation attaches that conversation as context. Your personal assistant's profile has Read my direct messages and Send messages as me switches (rolling out gradually).
- An agent's Activity overview shows who started, stopped, restarted, reset or moved it, or changed its model.
- Moving an agent shows packing and download sizes and how long it waits; a computer's details show its agents' avatars.
- Status badges on app link cards show the app's colour.

🔧 **Improved**
- Realtime updates use the app's own connection, which reconnects by itself after a network drop and catches up on missed messages.
- Opening a channel or DM from Activity is faster.
- Chats always open at the newest message, as on the web; the Settings › Mobile switch for it is gone.
- Search works like the web: up to 50 channels, people, agents and computers with Show all, Continue searching, a 30-day notice on the Free plan, filters on one line, and a tap on a person opens your DM.
- Computers usage matches the web: per-model limit lines, Verify or Sign in when a sign-in needs attention, and placeholders while it loads. Owners and admins can use Claude limit resets, and sign-outs and limit resets now wait for results that take over 30 seconds.
- Antigravity is no longer marked deprecated, and model lists and reasoning effort options match the web.
- An agent's Apps tab says in a sentence what each app can do; Activity always offers Subagents for runtimes that can start them, and a swipe there moves between its views first.
- On wide screens, columns can be dragged as narrow as 160 points, and the Artifact column lays its page out like a phone by default, with Desktop Mode in its ⋯.
- Opening an Artifact shows one top bar and the web's pixel loading animation.
- Channels shared with other servers are called external channels everywhere. The channel announcement is replaced or removed from the ⋯ on its row.
- Add member and Create Agent show only what you can use, and the prompt for an external agent explains what Raft is and what to do after signing in.
- Settings › Feedback shows where feedback sent elsewhere came from. An agent's Submit feedback card opens the app's feedback form with your version, device and diagnostics.
- Required fields no longer show an asterisk; sideways filter rows fade at their edges and bring an applied filter into view; more items show press and hover feedback, and screen readers can open a sender's profile from their avatar; name fields no longer autocorrect.
- Signed-in devices on the web show this app's version, and reports of Android memory kills say how old each detail is.

🐛 **Fixed**
- Drafts and unsent messages are no longer lost when the app restarts or can't read the saved sign-in, and a draft in a task with no discussion yet is kept.
- The app no longer closes when a web page inside it crashes; fixed rare crashes when a task under a message updates and when leaving a thread while it jumps to a message.
- Raft links open in the app: task links open the task, and channel links, links to your other servers and links inside an Artifact no longer go to the browser.
- Pages open once, even on a double tap or a notification. Links and notifications close the Tasks or Files card on a phone and open at the message. With Multiple app windows on, notifications open in their own server's window.
- Activity: reading a conversation clears its @ right away, rows no longer briefly show the previous sender, a cut-off reply preview is no longer shown as the whole message, and external channel rows show the real avatar. The badge and rows stay right in more cases (rolling out gradually).
- An Artifact left open no longer closes by itself, and Copy link in its ⋯ menu works.
- Agent status in lists matches the profile after switching servers and is rechecked every 10 minutes.
- Agent profiles: Raw Log shows earlier entries, going back no longer leaves a blank page, a saved provider shows as Saved provider, and agents from another server show their description and status and update their name and avatar right away.
- Archived channels open read-only with an Unarchive button, as on the web, and task actions appear only where you can post.
- Saved shows messages saved in task conversations and follows a server switch, as do Computers and the server settings pages.
- App cards update on their own when a long operation finishes, and "Collapse long messages" changed elsewhere applies right away.
- Smaller fixes: Slack messages show offline, Public access settings load again, the person who connected a Computer keeps rename and removal after a key revoke, removal notes say to move agents to another computer, the external channel invite warning no longer flashes, a tapped picker search box draws no frame, avatars in a bordered frame have clean corners, and the server avatar edge and Saved icons look as on the web.
