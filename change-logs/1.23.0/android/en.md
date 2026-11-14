✨ New
- Settings now has a "Search settings" field at the top. Type a setting's name or a word people often use for it (such as "dark" or "time zone") to jump straight to it. You only find settings you can use, and when nothing matches, one tap searches messages and people instead
- Tasks can be moved to Backlog from the status menu, with an optional reason that shows in the task's history. When creating tasks you can pick Backlog, Todo or In Progress
- When a stopped agent needs a restart, its profile shows Retry next to its status for people who can restart it. An agent whose Computer's access was revoked now says so and points you to "Move to another computer"
- Computers show their owner, who (along with server owners and admins) can Transfer owner or make it Public; Computer cards have a ⋯ menu with Create Agent and Restart. Removing a member or leaving a server now asks first and says which Computers go offline
- The Codex usage card shows how many resets are left, and people who manage the Computer can tap "Use a reset" to restore the account's usage limits right away
- When an @ mention in your message notifies no one, a notice above the message box tells you who wasn't notified, as on the web

🔧 Improved
- Agents' question cards have a new, slimmer look matching the web: a single short question shows as option buttons right under the message, and answered, expired or withdrawn cards say what happened in one line. The "Choose an option first." hint only appears if you try to continue without answering
- Feedback in Settings lists the feedback you've sent, with status filters: open one to see the conversation, reply, or close it. New Feedback uses the same form as the web (pick Idea or Bug), new feedback is sent under your account, and attached diagnostics are smaller
- Changing a running agent's settings no longer restarts it right away: the change applies when it's next idle, and "Restart now" applies it at once
- Artifacts open with a single bar: Back, the title, a version picker, Comments and a ⋮ menu. Artifact links from the web open in the app's viewer at the linked version, and the Artifact icon matches the one on message cards
- The server switcher on Home now matches the web, with avatars, unread counts, and options to join the community, switch or create a server, invite humans and reorder servers. The server popover's pages wrap into rows so long names show in full
- The line above the message box in a channel shows which agents are working, not only who is typing; tap it to see them in Working now
- A Lounge gets its own row under Direct Messages in the sidebar, with unread count, pin, mute and close (rolling out gradually)
- The app starts faster and is less likely to freeze while opening
- Older messages you scroll back to in a task conversation are kept for offline use, the model picker offers the newer Claude models even when a Computer can't read Claude Code's list, and fact lists keep each value beside its label when it fits

🐛 Fixed
- Web pages in the app, such as Plan & Billing, Feedback, Release Notes and the Artifact viewer, no longer sit on a blank screen when they fail to load: "Could not load this page" appears with Retry
- A message that fails to send after you switch servers is no longer lost: it shows as failed with Retry when you go back
- Fixed an occasional crash after selecting a message's text and then opening its replies, or when opening that conversation again
- Raft's preview in the recent apps screen is no longer a blank white card, even in dark mode: it uses the app's current background colour
- With "New message menu (Beta)" on, holding a message opens only the new menu, without the system text-selection menu underneath
- The first row of Activity no longer jumps back and forth after a new reply
- Typing in Members, Settings or Artifacts search beside a conversation is no longer pulled into the message box, including when a hardware keyboard reconnects
- App link cards, such as Stamp's approval requests, now have the Refresh button, as on the web
- Announcement links to web-only Settings pages open in the browser instead of showing an error
- Home, Activity or a channel's member list that failed to load after an update loads again on the next sync
- Smaller fixes: easier-to-tap segmented controls, the Activity filter chip's × no longer clears the filter by mistake, top bar items match field height with a trackpad or mouse, and the optional marker on form labels no longer has an extra space in Chinese and Japanese
