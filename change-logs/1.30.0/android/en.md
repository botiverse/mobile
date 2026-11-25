✨ **New**
- **App authorizations & review alerts**: Added **App authorizations** under server Settings (visible to all members and guests); owners and admins see a review dot on Apps and receive permission-update push notifications.
- **App card forms & encrypted secrets**: Card action buttons now open forms with masked secret fields encrypted on-device for the target app only.
- **Computer handover & removal guards**: Renamed Creator to **Attached by**, added **Hand over** with 24-hour commands, and added linked-Computer checks when leaving a server or removing a member (admins can disconnect and remove in one step).
- **Native Agent migration cards & preflight**: Tap a migration card to open the native dialog pre-filled with the target Computer; preflight checks runtime status and explains issues before moving.
- **Upgraded in-app update experience**: Unmetered Wi-Fi silently pre-downloads new builds with live progress and clean download sizes in **Settings › About**; added an important-update modal (24-hour snooze, clean changelog preview, download size), ready-state **Update ready to install** banners, a wide top-bar update button, and a dismissible 8-second post-update **What's new** toast.
- **Question-card close & modal polish**: Tap × to close a question card without answering (with a 1.5s **Undo** window) and see who closed it; aligned the answer modal's choice lists, field descriptions, and multi-step navigation with the web.
- **Artifact follow, split view & Open in browser**: Follow or unfollow from Artifacts home and message cards, with equal-height cards on Artifacts home; wide screens open items in the right split pane; the viewer adds native link sharing and restores **Open in browser** (auto-returning to the source conversation).

🔧 **Improved**
- **Cleaner phone conversation headers**: Narrow headers fold to Back · Avatar · Title · Search · `⋯`, moving Tasks, Files, and Details into `⋯` for more title room.
- **Two-line Activity previews & in-place updates**: Phone Activity rows now show up to two lines of message preview with content-following height; new messages and followed replies update rows in place, cutting full reloads and speeding up resume.
- **Shared drafts, guest connect & external share**: Joint channels share one personal draft across servers; guests can connect existing servers or create and connect one; system Share supports reply threads and switching servers.
- **Clearer Agent & Computer status**: Held agents show **Can't respond** with cause and recovery time; revoked Computers and builds below `1.0.53` show clear causes and reinstall steps.
- **Send to an agent & web parity**: Redesigned Send to an agent as a DM with the last 5 messages; subagents show their model; create cards let you edit suggested env vars; aligned runtime checks, workspace refresh, and joint pin hints.
- **Tablet, landscape & app-wide visual polish**: Tighter wide sidebar density, automatic keyboard room on tablets/landscape (full-width composer on short wide screens), consistent hairline borders and icon styling across the app, mouse/trackpad hover states, higher-contrast text, and reduced open jitter and background traffic.

🐛 **Fixed**
- Fixed IME editing jumping the cursor to the end, `@`/`#` suggestions failing mid-composition, and empty conversations dropping keyboard focus while loading.
- Fixed false "Loading older messages…" gaps, CJK text between two `$` signs rendering as math, split task chips, link-card buttons not staying in loading state, and pending-mention banners showing handles instead of display names.
- Fixed returning from background to an open Artifact viewer occasionally showing a spurious network error.
- Fixed portrait tablet sidebars crowding out the Send button, landscape keyboards squashing the composer, and share-screenshot preview buttons becoming unreachable.
- Fixed the bottom tab bar being covered in some layouts and expanded each tab's vertical tap area.
- Fixed subsequent updates not showing the **Update available** banner or still showing **Installed** in **Settings › About** after an in-app update.
- Fixed non-interactive Samsung and Xiaomi system maintenance exits being reported as crashes or triggering background-kill banners.
