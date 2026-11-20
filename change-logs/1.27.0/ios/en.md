✨ **New**
- **Personal assistant** (rolling out gradually):
  - Home has an Assistant entry under Saved and Drafts: it opens your DM with your assistant, or a page where you can Create assistant. On wide screens it is on the rail too.
  - Your assistant's profile can turn Show Ottie on every page on or off, show and disconnect WeChat, and delete the assistant. On wide screens you can Connect WeChat right in the app by scanning a QR code.
  - Language & Region, Theme and Notifications list what your assistant changed under Changed by your assistant, and its profile has Recent changes.
- **Settings** gains what the web already has:
  - Account › Signed-in devices lets you sign out one device or all others, and Apps you signed in to with Raft lets you revoke them.
  - Server › Archived channels and Archived agents open when tapped (channels read-only, agents with View workspace). Owners can delete a server from Server Profile.
  - Appearance › Keyboard shortcuts on tablets, and a Labs page when Labs is on for a server (rolling out gradually).
- Verify it's you takes a passkey right in the app with Use Passkey, and when it has to send you to the web, Open on the web opens the page for the same action. With only an older passkey, Use Passkey opens a web page to confirm it and comes straight back to finish the action (rolling out gradually).
- Reorder attachments in the composer by pressing and dragging them. Long-press a sender's name to copy their name or handle.
- An agent's profile has a Recent activity card: what it is doing now and its last few turns.
- Install or upgrade a runtime from a Computer's page, always after you confirm (rolling out gradually). The experimental DeepSeek Harness runtime is supported.
- When you invite people outside a server, members of the server can also get Invite to connect their server.

🔧 **Improved**
- Search finds every channel and person on the server you can see, including archived and not-joined channels, in Channels, People and Computers groups with Show all and Load more. A public channel you haven't joined opens on a preview you can join from, and typing # in the composer offers archived channels too.
- Settings are easier to find: the Mobile page is now part of Appearance, several options have clearer names (such as Enter to send and Phone notification delay), Sign out asks in a dialog, and Plan & Billing and Administration show only to owners and admins. About is simpler: Version shows the build number and copies full details on a long press, self-hosted servers show Connected server, the Open Raft row is gone, and copied environment info no longer includes logs.
- Activity: new @mentions appear and conversations marked done elsewhere disappear without coming back to the app, and only changed conversations reload after a reconnect (rolling out gradually). In Unread, a conversation you read moves back to the top when a new message arrives; older rows show their time on touch screens; the "may be out of date" notice is gone.
- Threads: once the original message scrolls away, the title shows its sender and first line; tap the title to go back, and View in Channel opens the channel at the original. Conversations you've seen before open without flashing Loading, and Back to Bottom works with one tap, showing Loading Messages… while it loads.
- The selection bar shows Forward, Generate image, Copy Link and Copy MD as icon buttons, as on the web.
- Runtime settings match the web: Claude Code and Codex are marked Recommended and some runtimes Limited; on/off settings are option cards; long lists can be searched by id or provider; reasoning effort stays visible with the reason when it can't be changed; and the form says when a runtime isn't signed in or is too old to list models. Codex speed options follow the model you pick, where the server supports it.
- Computers: Codex usage shows Used up · using credits and the credits left, where the server supports it. The Claude Code card has one Use a limit reset (rolling out gradually). Add Computer says up front what agents can do there; a Computer running as a system service explains how to upgrade it; usage reads only the computer's default account.
- Agent profiles: the actions at the end are a card of rows, red only for what can't be undone; Raw Log status explanations sit on their own line, in your language; descriptions are limited to 300 characters and warn when a link in them isn't open to everyone; owners and admins can attach the runner log when reporting an issue.
- On tablets and wide windows: Activity uses compact rows and a one-line head, a message's sender line no longer runs to the right edge, Small and Large text sizes are a full step from Medium, and a task's details scroll away with its conversation.
- Mermaid flowcharts in messages lay out more clearly, with lines back to earlier steps curving around the side.
- Move to section in the sidebar menu starts with the conversation's own section and replaces Remove from section.
- If restoring your sign-in at launch is slow, you can Try again or Sign out.
- Links inside a sentence wrap with the text, links that leave Raft end in ↗, the @ list shows the server logo on people from other servers, channel action icons are the usual size, a channel's Members button shows a plain count instead of a red badge, and external channel, connect and agent migration messages are clearer.
- More screens follow your language, including sign-in, account linking, attachment, forwarding and agent activity log messages, and Traditional Chinese, Korean, Spanish, Portuguese and Russian no longer show English on Computers and upgrade screens; photo access prompts follow your language too.
- On a server in privacy mode, Report issue and New Feedback aren't offered, and diagnostics and crash reports aren't uploaded.

🐛 **Fixed**
- Fixed crashes after the Members page or a message list changes size, when opening a message with certain Markdown, and when saving an edited image with text on it.
- What you type is saved to the draft as you type, so a crash loses at most the last moment.
- While you read earlier messages, the conversation no longer jumps when a message changes size; Artifact cards and messages being translated keep their size while loading; and expanding a message scrolls what opened into view.
- Messages show at your chosen text size right after launch, and text size no longer changes a few seconds after opening on wide screens.
- Activity: a conversation you read no longer turns unread again, and one marked unread on another device stays unread (rolling out gradually). An Artifact opens at its latest version, Back closes a conversation when the list is collapsed, and people from other servers in external channels show their own avatar and name.
- Artifacts: Download saves the file in the app instead of sending you to the browser, the ⋯ and version menus show while the page loads, the ↑ ↓ ✓ bar above the keyboard no longer covers the comment box, and Artifacts in a shared channel your server left read Shared channel.
- Channels: archiving or unarchiving swaps the composer right away; Guests are no longer offered in Add members and appear in search only for owners and admins; a channel too large to share shows the server's explanation.
- Computers: Add Computer works with two-step verification, the runtime sign-in dialog no longer loses what you typed, and on a phone a tap opens runtime usage as a sheet while scrolling never does.
- Agent profiles: an agent whose runtime isn't signed in shows Sign-in needed for it and opens its computer, Back shows the previous profile right away, a swipe on Overview / Raw Log switches between them first, and the remaining English labels follow your language.
- Raft links with an 8-character message ID open, links and mentions in a task hand-off reason are tappable, and images in older messages keep loading.
- With Conversation forward gesture on, swiping in from the right edge on Home reopens the chat you just left.
- App cards show their latest content when you come back to the app, and their buttons no longer fail first after a while.
- A message whose attachment expired says to upload it again instead of offering a Retry that can't work.
- Smaller fixes: open menus close when their button scrolls away, columns on wide screens stop cleanly at the edge and their headers fit, Check for update shows its status in your language, the floating server label no longer stays on screen on self-hosted servers, Cindy's Pro trial offer is one line on a phone, and the app does less in the background.
