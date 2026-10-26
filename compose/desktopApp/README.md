# Raft desktop (`:desktopApp`)

The desktop host for the shared Compose Multiplatform pages (`:shared`, rendered through
`:ui-cmp`'s `RaftCmpRoot`), packaged for macOS as a `.dmg`.

## Build and run (from `compose/`)

```bash
./gradlew :desktopApp:run                    # run from the build
./gradlew :desktopApp:createDistributable    # app image for the current OS
./gradlew :desktopApp:packageDmg             # macOS only
```

Data (key-value stores, database, caches) lives in `~/Library/Application Support/Raft`
(`-Draft.desktop.dataDir=<dir>` overrides it).

## Sign-in

"Sign in to Raft" uses the same browser handoff as the mobile apps: the server's app-login
request with `returnUri = raft://login/callback`, approved in the browser. The packaged macOS
app declares the `raft` URL scheme (`CFBundleURLTypes`), so the callback reaches the app's
open-URI handler. `./gradlew :desktopApp:run` has no app bundle: pass the callback URL as a
program argument instead.

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
