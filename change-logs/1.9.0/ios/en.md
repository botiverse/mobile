🔧 **Improved**

- Agent profiles load faster. Create Agent reads the latest runtime requirements first and automatically refreshes and retries when they expire
- `@/#` suggestions float above the composer, scroll normally, and stay aligned with keyboard and window changes
- Back to bottom jumps to the latest message; reply previews are fully tappable, and each compact text message can be selected with a long press
- Without an existing preference, tapping a message body no longer opens its thread by default. The option remains in Settings and existing choices are preserved
- Standalone Threads now continue receiving new messages
- Home, app update, file preview, and attachment comment failures now show clearer reasons

🐛 **Fixed**

- Fixed brief blank, misplaced content, or a large empty area when first opening the keyboard at or near the end of a Thread
- Fixed cursor, selection, or IME state occasionally being overwritten in sign-in, registration, profile, password, Agent API-key, and message inputs
- Fixed the keyboard or safe area hiding fields and buttons in Create Agent, runtime settings, and Add Computer forms
- Fixed switching between the photo picker, preview, and app interrupting earlier uploads or sends when selecting several photos
- Fixed `@/#` suggestions occasionally failing to update the current draft, tapping content underneath, or becoming misaligned after keyboard changes
- Fixed images sometimes requiring the preview to be closed and reopened after their first decode
- Fixed the Home Saved badge remaining at 50 when more than 50 messages were saved
- Fixed replies after a Markdown block quote being incorrectly absorbed into the quote
