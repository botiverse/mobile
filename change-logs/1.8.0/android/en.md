✨ **New**

- Expanded Japanese support across the main signed-in interface; select Japanese from Settings after signing in
- Text, images, and files can now be shared to Raft from the Android system share sheet; choose a server and conversation, then review the content in the composer before sending
- Sent images can now be saved directly to the system photo library
- Messages now show recent replies, the total reply count, unread state, and draft state, with direct access to the reply thread

🔧 **Improved**

- Release Notes and update details now use native Markdown, with faster and more accurate download, install, and return-to-app status updates
- Improved Markdown tables, links, inline code, and task and channel references; references now show a pending state while opening
- Uploaded images, videos, and files can be previewed from the composer; videos in reply threads preload their first frame and use a single playback control
- Attachments keep their selection order, edited images retain their original aspect ratio, and composer interactions are less likely to lose focus
- Transient network send failures receive more automatic retries, and the manual Retry action is easier to find below the error details
- Sign-in conflicts now clearly distinguish an existing password account from a third-party identity linked to another account, with the appropriate next step
- Reaction and task-status chips now show actions immediately; pending chips no longer reserve blank space or change width
- The preferred translation target in Settings is now System language, following the device language and falling back to English when unsupported
- Long-pressing to select message text no longer opens the keyboard, while partial selection, Select all, and Copy remain available
- Android packages now compress DEX and verify every 64-bit native library for 16 KB page-size compatibility, reducing download size and improving compatibility on newer devices

🐛 **Fixed**

- Fixed reply threads occasionally freezing or turning blank on Android while sending attachments, scrolling, or repeatedly showing the keyboard
- Fixed saved and task states briefly returning to defaults when reopening or refreshing a reply thread; reply headers now always show the accurate total count
- Fixed cached APK installs flashing back to a download state and the update page sometimes remaining on "Installing" after returning from the system installer
- Fixed human users sometimes reappearing in the member list, search, or mention suggestions on servers configured to hide the human directory
- Fixed the disabled Tasks Assignee filter; it now supports members, Unassigned, multiple selections, and Clear
- Fixed Home channels, direct messages, or Thread content remaining blocked when unread counts or reply summaries are delayed
- Fixed loaded replies being blocked by delayed parent context and empty reply threads returning to "Loading..." after showing "No replies yet"
- Fixed search results for yourself or another member opening the wrong profile, including after switching accounts or servers
- Fixed Android attachment state across Save, re-download, and Open with; same-named files are not adopted, and deleted downloads return to an actionable download state
