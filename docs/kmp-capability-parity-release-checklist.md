# KMP Capability Parity Release Checklist

Date: 2026-05-22

Scope: release/build validation for any KMP migration that replaces or bypasses a native Android product path. This checklist is intentionally about product capability parity, not only visual parity or whether a route opens.

## Release/Build Validation Command

For shared KMP changes that affect Android packaging, run the direct shared
release gate:

```bash
cd compose
./gradlew --no-daemon --console=plain \
  :shared:generateCommonMainSlockDatabaseInterface \
  :shared:testDebugUnitTest \
  --tests build.raft.app.data.SqlDelightComposeLocalDataStoreTest \
  --tests build.raft.app.SharedThreadStoreTest

./gradlew --no-daemon --console=plain :androidApp:compileDebugKotlin
```

This generates SQLDelight interfaces, runs focused shared local-store / Thread
tests, and compiles `:androidApp` against `:shared` as a direct project
dependency. The old shared-release wrapper task/script has been removed.

HarmonyOS (OHOS) validation is no longer part of this gate: OHOS was frozen
at 1.14.x and removed from `main` (it lives on `release/1.14.x`).

## Required Checklist

Every KMP migration task that replaces native behavior must answer these items in its delivery note or task thread:

| Item | Required answer |
| --- | --- |
| Native behavior replaced | Name the old Android native route/state/repository behavior being replaced. |
| Shared owner | Name the `commonMain` MVI store/data source/effect owner. UI-only migration is not enough. |
| Status | Mark each affected capability as `migrated`, `partial`, `missing`, or `intentionally unsupported`. |
| Android capability | Name the Android host adapter/repository/native database capability used, or state why none is required. |
| iOS capability | Name the iOS adapter/native database/platform capability, or provide an explicit disabled/no-cache/no-upload state. |
| Cache | Cover cold start, offline start, refresh, and server/thread switch behavior. Existing content must not disappear during refresh. |
| Loading/error | State whether loading preserves current content and how no-permission/network-error appears. |
| Realtime/read receipt | State whether realtime event merge, unread reconciliation, and read receipt behavior are migrated, partial, missing, or intentionally unsupported. |
| Pagination/gap recovery | Required for Thread/conversation flows; state older/newer/gap-sync behavior. |
| Attachments/menu/actions | Required for Thread/Settings mutations and any route with native actions; list disabled actions explicitly. |
| Verification | Include focused store/reducer tests, live-device logs, adb verification, or iOS compile/device verification. |
| Visual support | Include visual-testing run only when appearance is relevant. Visual pass cannot replace capability verification. |

## Blocker Rules

- A route that renders but leaves cache, realtime, pagination, read receipts, attachments, or native mutations missing is `partial`, not complete.
- A route that drops previous content during refresh or network error fails the gate unless the identity/server/thread changed and no cache exists.
- A platform capability that is not implemented on iOS must be represented as an explicit disabled/error state, not a silent no-op.
- Visual-testing output is supporting evidence only. It cannot mark cache, realtime, persistence, or mutation behavior as migrated.
- If a KMP task is blocked by another owner dirtying main, keep the ready commit in its worktree and report it as "implemented, waiting for clean main"; do not stash or overwrite unrelated work.

## Current Gate References

- Current React/KMP product map:
  `docs/react-kmp-feature-map-2026-06-26.md`
- App-wide shared state contract:
  `docs/slock-app-state-first-principles.md`
- Current shared store map and MVI runtime usage:
  `docs/shared-mvi-foundation.md`
- Current realtime alignment snapshot:
  `docs/react-kmp-realtime-alignment-2026-06-26.md`
- Android UI/shared migration cleanup:
  `docs/android-ui-shared-migration-audit.md`

Older route-specific audits may remain in `docs/` as historical snapshots, but
they are not source of truth for current capability status. Before citing one
as current, refresh it against code and the task board or delete it.

## Copy/Paste Release Note Template

```text
KMP capability gate:
- Native behavior replaced:
- commonMain owner:
- Status by capability:
  - Cache:
  - Loading/error:
  - Realtime/read receipt:
  - Pagination/gap recovery:
  - Attachments/menu/actions:
- Android capability:
- iOS capability:
- Verification:
- Visual support:
- Remaining partial/missing items:
```
