# Raft desktop (`:desktopApp`)

The desktop host for the shared Compose Multiplatform pages (`:shared`, rendered through
`:ui-cmp`'s `RaftCmpRoot`), packaged for macOS as a `.dmg`.

## Window

The window is [Nucleus](https://github.com/NucleusFramework/Nucleus)'s Tao backend
(`nucleusApplication` + `DecoratedWindow`, 2.5.x), not Compose Desktop's AWT window: the Compose
UI thread is the macOS main thread, which AppKit (text input, window resizing) and the embedded web
views run on too. Nothing may start AWT before the window (`java.awt.Desktop`, `Toolkit`): AWT and Tao
would each set up the macOS application object. Links, files and folders open through the system's
`open` command (`RaftDesktopExternalOpen`); the clipboard stays Compose's own.

On macOS the content runs up under the title bar: a transparent band as tall as the top bar
(`RaftDesktopTitleBar.kt`) moves the window, the traffic lights sit at the start of the top bar.
Embedded web pages (Artifacts, Settings web pages) are the system WKWebView through
ComposeNativeWebview, a Tao `NativeView` under the Compose surface (`RaftSandboxWebView.desktop.kt`).

## Build and run (from `compose/`)

```bash
./gradlew :desktopApp:run                    # run from the build
./gradlew :desktopApp:createDistributable    # app image for the current OS (smoke tests)
./gradlew :desktopApp:packageDmg             # macOS only
```

Data (key-value stores, database, caches) lives in `~/Library/Application Support/Raft`
(`-Draft.desktop.dataDir=<dir>` overrides it).

## Sign-in

"Sign in to Raft" uses the same browser handoff as the mobile apps: the server's app-login
request with `returnUri = raft://login/callback`, approved in the browser. The packaged macOS
app declares the `raft` URL scheme (`CFBundleURLTypes`), so the callback reaches the app's
open-URI handler. The Electron "Raft Desktop" app registers `raft` too, and macOS hands a scheme
to only one app: with both installed, sign-in may land in Raft Desktop (uninstall it). The
server allows `raft://login/callback` (slock `appLoginService.ts`). Elsewhere (and for
`./gradlew :desktopApp:run`) start the app again with the callback URL as its argument: the second
launch hands it to the running app and exits.

## Layout

The host publishes the window size with a `flat` posture (`slockAdaptivePaneChanged`, as the
Android and iOS hosts do) on start and on every resize, so a window at least 768dp wide gets the
wide layout (navigation rail, split panes) and a narrower one the phone layout.

## Realtime

`reportRealTime` drives the shared `SocketIoRealtimeClient` (`:shared` jvmCommonMain, the same one as
Android) over the common Kotlin Socket.IO client (`:realtime-socket`, OkHttp engine) through the shared
realtime runtime: one socket for the current server. The handshake still declares `clientKind: "mobile"`.

## Local development server (raftdev)

Point the app at a local stack by seeding `<dataDir>/kv/slock_api_server_environment.json`:

```json
{"environment":"custom","custom_label":"Local raftdev","custom_api_base_url":"http://localhost:13081/api/",
 "custom_socket_base_url":"http://localhost:13081","custom_web_url":"http://localhost:15253"}
```

(ports from `./raftdev start <name>`). The browser login page may then be plain http on localhost.

## CI

`.github/workflows/desktop-macos-dmg.yml` (workflow_dispatch) first runs the desktop app's tests
(`:desktopApp:test`; no other CI lane runs them, so dispatch it for any desktop change), then
`:desktopApp:createDistributable` on a macOS runner, ad-hoc signs `Raft.app` (`codesign --sign -`, no certificate; without it Apple
silicon reports the downloaded app as "damaged"), wraps it in a `.dmg` with `hdiutil` and uploads
it as the `raft-desktop-macos-dmg` artifact. It has no Developer ID signature and is not notarized.
It is also a reusable workflow: `github-release.yml` calls it for each `vX.Y.Z` tag (tag
commit, version `X.Y.Z`, Blacksmith Mac) and attaches the dmg to the GitHub Release as
`raft-macos-X.Y.Z.dmg`, marked unsigned / not notarized with first-launch steps.

## Signing and notarization (DevOps, macOS CI)

Not configured here. Compose Desktop reads them from Gradle properties, e.g.:

```bash
./gradlew :desktopApp:packageDmg \
  -Pcompose.desktop.mac.sign=true \
  -Pcompose.desktop.mac.signing.identity="Developer ID Application: ..." \
  -Pcompose.desktop.mac.notarization.appleID=... \
  -Pcompose.desktop.mac.notarization.password=... \
  -Pcompose.desktop.mac.notarization.teamID=...
./gradlew :desktopApp:notarizeDmg ...
```

`-Praft.desktop.version=X.Y.Z` sets the bundle version (macOS needs MAJOR > 0).

## Desktop gaps (TODO(desktop))

PDF, animated images, settings host actions (notifications, developer environment, account
linking), notifications, the app menu bar (Nucleus `menu-macos`), the dock badge and the app icon
(`.icns`) are not implemented yet; they show a notice or do nothing. Video plays on macOS 14+ only
(ComposeMediaPlayer's AVFoundation bridge), without full screen (the library's full screen is an AWT
window); elsewhere video attachments stay download cards. Audio-only media does not play yet and
keeps the "not available" notice. A mouse's side buttons do nothing (the Tao window gives back and
forward one code, so `RaftDesktopSideButtons` drops them instead of letting them click); ⌘[ / ⌘] step
through the history.
