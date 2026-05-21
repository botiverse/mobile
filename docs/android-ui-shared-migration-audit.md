# Android UI/Theme Shared Migration Audit

Date: 2026-05-21

## Goal

Android should stop carrying product UI that already belongs to the shared Kuikly shell. New KMP work should move reusable UI and business state to `compose/shared/commonMain`; Android should keep only platform host capabilities, temporary native fallback, and real Android test harness.

## Current State

The Android `app/src/main/java/ai/slock/android/ui` tree still contains native Compose design-system code from the pre-shared-shell migration:

- `ui/theme`: native Material theme, Android font resources, hard-shadow helpers, colors, spacing, stroke, radius, size.
- `ui/components`: native buttons, text fields, bottom tab/scaffold, feedback states, modals, member/task/thread widgets, pull refresh, previews.
- `ui/icons`: generated Android `ImageVector` Lucide icon set.
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

### Migrate To Shared

These are reusable product UI concepts and should be moved to shared variants instead of remaining Android-only:

- Buttons/text fields/check controls: `SlockButton`, `SlockTextField`, `SlockCheckbox`, `SlockToggle`, `SlockRadio`, `SlockSegmentOption`.
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
- `ui/icons/LucideIcons.kt`: keep only while native Android legacy/test UI still uses `ImageVector`; product shared path should use `SlockLucideIcon`.

### Still Blocking Full Removal

The old `MainActivity` native fallback still references multiple `ui/components` classes through legacy routes such as Activity/Inbox, Saved, Search, Tasks, Members, Thread, and Settings native wrappers. As each route moves fully into shared shell/MVI, delete the corresponding Android native UI and tests instead of keeping a second implementation.

## Recommended Order

1. Delete preview-only files now.
2. Move generic shared primitives needed by the active KMP routes first: feedback states, button, text field, checkbox/toggle/segment, modal/menu.
3. Replace Android native route callers with shared route/component usage.
4. Delete Android route-specific components once `rg` shows no product callers.
5. Keep only Android host adapters, connected visual tests that render true product paths, and platform helpers.
