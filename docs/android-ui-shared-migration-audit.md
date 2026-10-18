# Android UI/Theme Shared Migration Audit

Date: 2026-05-26

## Goal

Android should stop carrying product visual theme/tokens/components that belong to
the shared Kuikly shell. New KMP work should move reusable UI and business state
to `compose/shared/commonMain`; Android should keep only platform host
capabilities, Activity/system adapters, native render adapters, and tests that
exercise real product paths.

The target end state is explicit: Android should not own Slock product theme.
There may still be an Android host wrapper theme to satisfy Compose/Activity
runtime, but Slock colors, typography, shadows, buttons, badges, tab bars, and
route chrome should be driven from shared tokens/components.

## 2026-05-26 Re-Audit Summary

The Android product theme is **not yet fully removable**. The repo still has
runtime product callers from `MainActivity.kt` into Android-owned UI/theme:

- `MainActivity.kt` imports `build.raft.app.ui.theme.*`,
  `build.raft.app.ui.components.*`, and `build.raft.app.ui.icons.LucideIcons`.
- `MainActivity.kt` still directly renders native product screens:
  `ThreadScreen`, `InboxScreen`, `SettingsHomeScreen`, and several Settings
  detail screens.
- Those native screens in turn import Android `SlockColor`, `SlockType`,
  `SlockSpacing`, `SlockShadow`, and Android components such as `SlockButton`,
  `SlockTextField`, `SlockModal`, `SlockKeySurface`, and `SlockPageHeader`.

So the current answer is: Android should not own final theme, but deleting
`compose/androidApp/src/main/java/build/raft/app/ui/theme` today would break runtime
screens and connected tests. The remaining work is to remove those runtime
callers, not to blindly delete token files.

Use this command to reproduce the current dependency inventory:

```bash
scripts/audit_android_theme_ownership.sh
```

## 2026-05-27 Cleanup Slice

Removed one Android-only component visual baseline that no longer represented a
runtime product path:

- Deleted `ui/components/SlockMemberComponents.kt`.
- Removed the old Android native component screenshot harness:
  `VisualScreenshotCaptureTest.kt` and `VisualScreenshotCases.kt`.

Reason: `SlockMemberRow` / `SlockMemberKind` / `SlockPresence` had no runtime
callers; the only remaining usage was the old Android component screenshot
harness. That harness also kept native Android token/button/input baselines alive
after visual validation moved to shared route captures such as
`KmpVisualScreenshotCaptureTest`. Real Members and avatar/status parity now
belong to shared routes and shared visual providers, so keeping these
Android-only component cases would preserve a second product implementation
without a runtime owner.

The broader Android theme tree is still not removable until `MainActivity` and
the remaining native Thread/Inbox/Settings routes stop importing Android
product components/tokens.

## 2026-05-27 Icon Ownership Check

Product UI icons should use the shared Lucide path. Current shared/KMP routes do
that through `SlockLucideIcon` and named shared icons, with Android/OHOS renderers
providing the platform drawing bridge.

The remaining Android `ui/icons/LucideIcons.kt` file is still generated from the
same Lucide catalog and is kept only as an Android `ImageVector` bridge for
legacy native Compose routes and the shared `SlockLucideIconRenderView`. It is
not a separate product icon style, but it should disappear once native
Thread/Inbox/Settings fallback screens no longer need Android `ImageVector`
icons.

Non-Lucide drawable resources that still exist are intentionally outside the
shared product icon set:

- `ic_github.xml` is a third-party brand mark kept for the remaining native
  account screen. `ic_google.xml` was the same, but nothing references it any
  more since the provider marks moved to the shared MR asset rendered through
  `providerIconAssetSource`, so it was deleted (it also still carried the
  pre-fix, wrong palette).
- launcher and splash assets are app branding, not route/action icons.
- benchmark/local assets used by `ImageAdapterBenchmarks.kt` are not product UI.

Deleted unused `ic_chevron_down.xml`; there were no remaining `R.drawable`
callers. Future chevrons should use Lucide `chevron-down` through shared route
components, or through the temporary generated Android Lucide bridge only while
a legacy native route still exists.

## 2026-07-27 Lucide Migration Closure

The Android `ImageVector` bridge described in the dated sections above is now
retired. Those sections remain as migration history, but their instructions to
keep `ui/icons/LucideIcons.kt` are superseded.

Current Android product rendering uses:

- the shared `SlockLucideIcon` contract and generated icon-name inventory;
- the pinned generated Android path registry under `shared/generated`;
- one native Canvas render view that parses each path and paint once into a
  first-use cache, then draws without Compose, `ImageVector`, or per-frame path
  and paint parsing.

`compose/androidApp/src/main/java/build/raft/app/ui/icons/LucideIcons.kt` has
been deleted. Android production inventory must remain free of `ComposeView`,
`ImageVector`, `LucideIcons`, and `androidx.compose.ui.graphics.vector` icon
runtime dependencies. Do not restore the old bridge for legacy routes or tests;
route all product Lucide rendering through the shared contract and native
Canvas adapter.

### Current Runtime Callers To Remove Or Replace

These are the main remaining runtime roots that keep Android product theme alive:

| Root | Current reason it keeps Android theme | Required exit |
| --- | --- | --- |
| `SlockAdaptiveAppScaffold` / bottom bar in `MainActivity.kt` | Android still owns app-level scaffold/background/tab shell in the native host | Let shared app shell own tab/scaffold chrome; Android host only embeds it and passes safe-area/system capability |
| `ThreadScreen` | Legacy Android Thread route still used for some route/fallback/test paths | Finish shared Thread route parity and remove native Thread screen from product runtime; keep only tests until replaced |
| `InboxScreen` | Inbox route remains native Compose UI | Move Inbox list/filter/actions to shared store/page, then remove Android screen/theme usage |
| Settings home/detail native screens | Some Settings sections still render native rows/cards/actions | Continue Settings #35-#39 shared MVI/page migration; delete native Settings screens once shared sections replace them |
| `SlockTestBuildFrame`, restore/error/empty host fragments in `MainActivity.kt` | Host still paints small branded fragments during startup/error | Replace with shared splash/error components or make them plain host-only wrappers with no product token ownership |
| Android visual tests under `compose/androidApp/src/androidTest/java/build/raft/app/visual` | Some visual cases still import Android components/tokens directly | Keep only cases that render true shared product routes; move component baselines to shared providers or mark pending |

### Keep Android-Owned

These should stay Android-specific even after product theme exits:

- Activity/window/system-bar effects and permission launchers.
- Android Kuikly render adapters, image adapters, Google sign-in/credential
  capability, file picker, share sheet, notification/system settings, and other
  host capabilities.
- `ui/system/SystemBarColor.kt` or its eventual host-only equivalent.
- Minimal Android host theme needed for Activity/Compose runtime scaffolding,
  as long as it does not define product colors/spacing/typography.

### Move Out Of Android

These should not remain Android-owned in the final architecture:

- `SlockColor`, `SlockType`, `SlockSpacing`, `SlockStroke`, `SlockRadius`,
  `SlockShadow`, `slockHardShadow`, and other product visual tokens.
- Product components: buttons, inputs, modals, badges, page headers, bottom tab
  bars, avatars/status indicators, menus/popovers, empty/error/loading blocks.
- Route UI for Home, Thread, Inbox, Tasks, Members, Settings, Search/Saved, and
  auth.

### Safe Cleanup Rule

Only delete an Android UI/theme file after both checks are true:

1. `scripts/audit_android_theme_ownership.sh` shows no runtime product caller.
2. The equivalent shared route/component exists in `compose/shared/commonMain`
   and is used by Android and OHOS.

If a file is used only by connected visual tests, either retarget the test to a
real shared route or mark that visual case pending before deleting the native
component.


## Shared ViewModel Lifecycle Rule

Shared Kuikly Compose ViewModels must not touch Pager-backed runtime state during construction. Constructor/property initializers and `init {}` blocks may store pure dependencies only; they must not access `viewModelScope`, `pagerId`, `Utils.bridgeModule(pagerId)`, host bridge modules, or any object that indirectly needs Pager context.

Stores, effects, and coroutine jobs that need `viewModelScope` should be created lazily or after an explicit route start/attach intent. `onCleared()` must check whether lazy stores were initialized before clearing them.

This rule prevents startup white screens caused by reading Kuikly `viewModelScope` before Pager initialization (`lateinit property pagerId has not been initialized`). It is now part of the local `kuikly-coroutines-threading` skill and applies to Home, Tasks, Thread, Settings, and future shared MVI routes.

## Current State

The Android `compose/androidApp/src/main/java/build/raft/app/ui` tree still contains native Compose design-system code from the pre-shared-shell migration:

- `ui/theme`: native Material theme, Android font resources, hard-shadow helpers, colors, spacing, stroke, radius, size.
- `ui/components`: native buttons, text fields, bottom tab/scaffold, feedback states, modals, member/task/thread widgets, pull refresh, previews.
- `ui/icons`: the generated Android `ImageVector` set is retired; Lucide product
  icons use the shared contract plus generated Android paths/native Canvas.
- `ui/system`: Android status-bar color helper.

Shared already has equivalents for the long-term product path:

- `compose/shared/.../SlockDesignTokens.kt`: shared colors, spacing, stroke, radius, size, typography.
- `compose/shared/.../SlockTabBar.kt`: shared bottom tab contract/renderer seam.
- `compose/shared/.../SlockLucideIcon.kt`: shared icon bridge for Kuikly/OHOS/native renderers.
- `compose/shared/.../AuthComponents.kt`, `ThreadComposerPage.kt`, `ThreadPage.kt`, `SlockHomeComponents.kt`, Settings pages: migrated page-specific UI.

## Classification

### Delete Now

These were Android Studio preview/harness only and do not represent the real product path or visual baseline:

- `ui/components/SlockBasicComponentsPreview.kt`
- `ui/components/SlockTaskMemberComponentsPreview.kt`
- `ui/theme/SlockThemePreview.kt`

These legacy Android auth screens are no longer product paths after `compose_slock_app_shell`
became the auth/server gate owner:

- `feature/auth/LoginScreen.kt`
- `feature/auth/ForgotPasswordScreen.kt`
- `feature/auth/CreateAccountScreen.kt`

Android `MainActivity` should only show the short restore splash while it hands off to the
shared shell; login, reset-password, and create-account state belong in shared auth/MVI.

These unused native component primitives had no product callers after Thread/Tasks/Composer moved
to shared routes and should not be revived as Android-only UI. The few native controls still used
by legacy Thread/Settings fallback were split into smaller temporary files:

- `ui/components/SlockComposer.kt`
- `ui/components/SlockMessageItem.kt`
- `ui/components/SlockCheckbox.kt`

### Migrated To Shared MVI

The Android legacy Tasks route still renders native UI, but its load/update reducer no longer
owns business state transitions directly. `compose/shared/commonMain` now owns:

- `ScreenLoadState`
- `TasksUiState` / `TasksContent`
- `SharedTasksIntent`
- `SharedTasksStore`
- optimistic task status update / success / failure reducers
- task summary count derivation

Android `feature/tasks/TasksUiState.kt` remains only as a temporary compatibility adapter while
the native Tasks route is removed or replaced by a shared Tasks page. New task logic should target
the shared store/reducer first.

### Migrate To Shared

These are reusable product UI concepts and should be moved to shared variants instead of remaining Android-only:

- Buttons/text fields/check controls: `SlockButton`, `SlockTextField`, plus temporary `SlockLabeledCheckbox` and `SlockSegmentOption` used by remaining native Thread/Settings fallbacks. Toggle/radio variants should be reintroduced only as shared components when a real shared route needs them.
- Feedback states: `SlockEmptyState`, `SlockLoadingState`, `SlockInlineLoading`, `SlockErrorState`.
- Modal/menu/key-surface primitives: `SlockModal`, `SlockMenuOverlay`, `SlockKeySurface`.
- Avatar/member/task/file-tree primitives if their product routes remain in KMP.
- Pull-to-refresh wordmark should be owned by shared Home/Inbox routes, not Android `SlockPullRefreshContainer`.

Migration rule: create shared components in `compose/shared/commonMain`, wire the real shared route to them, then remove Android usage. Do not copy Android `androidx.compose.*` implementation directly into shared; use Kuikly Compose APIs and existing shared tokens.

### Keep Android Host

These are platform/host-specific and should not move into common UI:

- `ui/system/SystemBarColor.kt`: Android status-bar side effect. Shared should request safe area/status treatment through host capability/pageData, not own Android window APIs.
- Android `SlockTheme`: can remain only for legacy/native fallback screens and connected Android component tests. Product shared shell should not depend on MaterialTheme.
- Android font resource binding in `Type.kt`: shared uses Kuikly font declarations in `SlockDesignTokens.kt`; Android resources are platform fallback.
- `ui/icons/LucideIcons.kt`: retired and deleted; do not restore it. Native
  Android legacy/test paths must use `SlockLucideIcon` and the native Canvas
  adapter.

### Still Blocking Full Removal

The old `MainActivity` native fallback still references multiple `ui/components` classes through legacy routes such as Activity/Inbox, Saved, Search, Tasks, Members, Thread, and Settings native wrappers. Tasks reducer state has started moving to shared MVI, but the route UI is still native. As each route moves fully into shared shell/MVI, delete the corresponding Android native UI and tests instead of keeping a second implementation.

## Recommended Order

1. Delete preview-only files now.
2. Move generic shared primitives needed by the active KMP routes first: feedback states, button, text field, checkbox/toggle/segment, modal/menu.
3. Replace Android native route callers with shared route/component usage.
4. Delete Android route-specific components once `rg` shows no product callers.
5. Keep only Android host adapters, connected visual tests that render true product paths, and platform helpers.
