# Slock -> Raft rename status

> First analysis: 2026-06-13 by Pi-Test
> Current status refresh: 2026-07-01 by Android-Developer-2
> Scope: client repository, excluding `third_party/slock-source/`

The original rename note was a point-in-time migration plan. As of 2026-07-01, most external rename work has landed and this document should be used as a status matrix, not as a raw grep-to-zero checklist.

## Rename policy

Do not try to remove every `slock` / `Slock` string from the repo. The safe boundary is:

- New user-visible output should say Raft and use `raft.build`.
- Old input paths should remain compatible where users, links, persisted state, or wire contracts may still reference Slock names.
- Internal class/file/resource names can stay as technical debt unless they produce visible output or block correctness.
- Persistent storage keys should not be renamed without a read-old/write-new migration plan.

## Current status

| Area | Status | Notes |
|------|--------|-------|
| Shared Kotlin package path | Done | Shared sources are now under `build/raft/app`, not `ai/slock/compose`. A focused scan found 363 shared Kotlin files under `build/raft/app` and 0 under `ai/slock/compose`. |
| Android source package path | Done | Android sources are under `build/raft/app`. A focused scan found 47 files under `compose/androidApp/src/main/java/build/raft/app` and 0 under `ai/slock/android`. |
| Canonical package namespace | Done | Current canonical package family is `build.raft.app`, with shared namespace `build.raft.app.shared` and SQLDelight package `build.raft.app.shared.data.local`. Do not migrate to `build.raft.compose`; that was an obsolete draft direction. |
| Android application id | Done / release-owned | `compose/androidApp/build.gradle.kts` currently uses `namespace = "build.raft.app"` and `applicationId = "build.raft.app"`. Future application identity changes must be a release/product decision, not a package-refactor side effect. |
| API endpoint defaults | Done | `SlockApiEndpoints.kt` defaults to `https://api.raft.build/api/` and `https://api.raft.build`. |
| OHOS API/socket/web defaults | Done | OHOS defaults use `api.raft.build` and `app.raft.build`. |
| Android deep links | Done with legacy compatibility | AndroidManifest and MainActivity recognize both `app.slock.ai` and `app.raft.build`. Keep `app.slock.ai` as legacy input compatibility for at least the agreed compatibility window. |
| Thread web-link parsing | Done with legacy compatibility | Thread link parsing recognizes `app.slock.ai`, `staging.slock.ai`, and `app.raft.build`. Do not remove old/staging hosts unless product/backend explicitly ends compatibility. |
| Visible Android strings | Mostly done | App name, auth placeholder, notification title, Home default title, and About/open-web copy use Raft. Continue to fix any newly found user-visible "Slock" output case-by-case. |
| iOS realtime socket fallback | Fixed in this refresh | `SlockIosRealtimeHost.swift` now falls back to `https://api.raft.build` when no socket URL is supplied. |
| Test/mock email domains | Mixed by design | Do not batch replace all `@slock.ai`. Tests that validate legacy links or historical compatibility should keep old hosts/domains. Change only examples that are new user-visible output. |
| Internal `Slock*` symbols/files | Intentional technical debt | Examples: `SlockAppShell`, `SlockKuiklyActivity`, `SlockApiEndpoints`, `SlockSharedStoreProvider`. These are internal names and should not be renamed in a broad correctness PR. |
| Persistent storage keys | Intentional compatibility | Current session storage uses MMKV namespace/key `slockAuthSession` / `slockAuthSession:session`; API environment uses `slock_api_server_environment`; SQL cache uses `slock_cache.db`. Keep these unless a migration explicitly reads old and writes new. |
| `third_party/slock-source/` | Excluded | Upstream/server source should not be modified by this client rename sweep. |

## Obsolete guidance from the first draft

The 2026-06-13 draft had several recommendations that are no longer correct:

- The package migration is not pending as `ai.slock -> build.raft`; the client already uses `build.raft.app`.
- The suggested destination `build.raft.compose` is not the current canonical package.
- The Gradle examples that mapped `build.raft.app` to `build.raft.app` were self-contradictory and should be ignored.
- The SharedPreferences section referenced `slock_auth_session.xml`; current auth/session persistence is MMKV-based.
- The blanket `sed -i '' 's/@slock\.ai/@raft.build/g'` recommendation is unsafe because some old-domain fixtures are compatibility tests.
- The validation commands `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest` are stale module names.

## Remaining checks

Use focused scans and classify each hit:

```bash
rg -n "api\\.slock\\.ai|app\\.slock\\.ai|staging\\.slock\\.ai|@slock\\.ai|Open Slock|Slock Android|Slock notification test" compose docs -g '!**/build/**'
```

Classification rules:

- Must fix: new default endpoint, new visible UI/share/notification/diagnostic output, new generated links, or new fallback URLs that point at `slock.ai`.
- Must keep: legacy deep-link hosts, staging hosts, server wire contracts, persisted cache/session/environment keys, historical migrations, and compatibility fixtures.
- Optional cleanup: internal `Slock*` class names, filenames, XML resource names, test names, scripts, and diagnostics field names.

## Validation commands

Pick validation based on the changed surface:

```bash
./gradlew :androidApp:compileDebugKotlin
./gradlew :shared:testDebugUnitTest
./gradlew :shared:compileKotlinIosSimulatorArm64
```

For documentation-only changes, `git diff --check` is usually enough. For runtime default changes, run the closest compile target for that platform when the local tree is clean enough.
