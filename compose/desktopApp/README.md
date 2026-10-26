# Raft desktop (`:desktopApp`)

The desktop host for the shared Compose Multiplatform pages (`:shared`, rendered through
`:ui-cmp`'s `RaftCmpRoot`), packaged for macOS as a `.dmg`.

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
open-URI handler. The bundle identifier is `build.raft.app.desktop` (not the iOS app's
`build.raft.app`), but both apps register the `raft` scheme: on an Apple Silicon Mac that also
has the iOS Raft app installed, macOS may hand the callback to either app (the first launched /
registered wins), and sign-in in the other one then never completes. Elsewhere (and for `./gradlew :desktopApp:run`) start the app again with the
callback URL as its argument: the second launch hands it to the running app and exits.

## Realtime

`reportRealTime` drives a Socket.IO client (`realtime/`, copied from androidApp's
SocketIoRealtimeClient) through the shared realtime runtime: one socket for the current server.

## Local development server (raftdev)

Point the app at a local stack by seeding `<dataDir>/kv/slock_api_server_environment.json`:

```json
{"environment":"custom","custom_label":"Local raftdev","custom_api_base_url":"http://localhost:13081/api/",
 "custom_socket_base_url":"http://localhost:13081","custom_web_url":"http://localhost:15253"}
```

(ports from `./raftdev start <name>`). The browser login page may then be plain http on localhost.

## CI

`.github/workflows/desktop-macos-dmg.yml` (workflow_dispatch) runs `:desktopApp:packageDmg`
on a macOS runner and uploads the unsigned `.dmg` as the `raft-desktop-macos-dmg` artifact.

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

PDF, video and embedded web views, animated images, attachment pickers / uploads, settings
host actions (storage, diagnostics, notifications, developer environment, account linking),
notifications and the app icon (`.icns`) are not implemented yet; they show a notice or do
nothing.
