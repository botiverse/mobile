# KMP Capability Parity Release Checklist

Date: 2026-05-22

Scope: release/build validation for any KMP migration that replaces or bypasses a native Android product path. This checklist is intentionally about product capability parity, not only visual parity or whether a route opens.

## Release/Build Validation Command

For shared KMP changes that affect Android packaging, run the executable shared
release gate:

```bash
cd compose
./gradlew validateKmpSharedRelease
```

This generates SQLDelight interfaces, runs focused Thread SQLDelight/write-through
tests, and compiles `:androidApp` against `:shared` as a direct project
dependency.

For OHOS SQLDelight/native-driver validation, run the script form with `--ohos`.
The OHOS Gradle settings initialize the SQLDelight fork submodules and publish
the required Stately, SQLiter, SQLDelight plugin/compiler/dialect/runtime/native
driver artifacts to MavenLocal when they are missing, then compile shared OHOS
Kotlin:

```bash
cd compose
../scripts/validate_kmp_shared_release.sh --ohos
```

## Required Checklist

Every KMP migration task that replaces native behavior must answer these items in its delivery note or task thread:

| Item | Required answer |
| --- | --- |
| Native behavior replaced | Name the old Android native route/state/repository behavior being replaced. |
| Shared owner | Name the `commonMain` MVI store/data source/effect owner. UI-only migration is not enough. |
| Status | Mark each affected capability as `migrated`, `partial`, `missing`, or `intentionally unsupported`. |
| Android capability | Name the Android host adapter/repository/native database capability used, or state why none is required. |
| OHOS capability | Name the OHOS adapter/native database/platform capability, or provide an explicit disabled/no-cache/no-upload state. |
| Cache | Cover cold start, offline start, refresh, and server/thread switch behavior. Existing content must not disappear during refresh. |
| Loading/error | State whether loading preserves current content and how no-permission/network-error appears. |
| Realtime/read receipt | State whether realtime event merge, unread reconciliation, and read receipt behavior are migrated, partial, missing, or intentionally unsupported. |
| Pagination/gap recovery | Required for Thread/conversation flows; state older/newer/gap-sync behavior. |
| Attachments/menu/actions | Required for Thread/Settings mutations and any route with native actions; list disabled actions explicitly. |
| Verification | Include focused store/reducer tests, live-device logs, adb verification, or OHOS compile/device verification. |
| Visual support | Include visual-testing run only when appearance is relevant. Visual pass cannot replace capability verification. |

## Blocker Rules

- A route that renders but leaves cache, realtime, pagination, read receipts, attachments, or native mutations missing is `partial`, not complete.
- A route that drops previous content during refresh or network error fails the gate unless the identity/server/thread changed and no cache exists.
- A platform capability that is not implemented on OHOS must be represented as an explicit disabled/error state, not a silent no-op.
- Visual-testing output is supporting evidence only. It cannot mark cache, realtime, persistence, or mutation behavior as migrated.
- If a KMP task is blocked by another owner dirtying main, keep the ready commit in its worktree and report it as "implemented, waiting for clean main"; do not stash or overwrite unrelated work.

## Route-Specific Gate References

- Global migration status: `docs/kmp-migration-capability-parity-audit.md`
- Home cache/warm loading: `docs/kmp-home-capability-parity-audit.md`
- Thread/conversation flow: `docs/kmp-thread-capability-parity-audit.md`
- Settings data/action parity: `docs/kmp-settings-capability-parity-audit.md`
- Android UI/shared migration cleanup: `docs/android-ui-shared-migration-audit.md`

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
- OHOS capability:
- Verification:
- Visual support:
- Remaining partial/missing items:
```
