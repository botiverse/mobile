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

🐛 **Fixed**

- Fixed reply threads occasionally freezing or turning blank on Android while sending attachments, scrolling, or repeatedly showing the keyboard
- Fixed saved and task states briefly returning to defaults when reopening or refreshing a reply thread; reply headers now always show the accurate total count
- Fixed cached APK installs flashing back to a download state and the update page sometimes remaining on "Installing" after returning from the system installer
- Fixed human users sometimes reappearing in the member list, search, or mention suggestions on servers configured to hide the human directory
