# Raft UI / KMP Component Library Audit

Date: 2026-07-03
Owner: Android-Developer-3
Task: #visual-testing task #275

## Scope

This audit covers the KMP-side Raft UI component layer added around the Raft UI visual work. It separates reusable product components from visual-fixture shells so product pages can adopt stable pieces without mistaking visual-only compositions for a component library.

Source surfaces audited:

- `compose/shared/src/commonMain/kotlin/build/raft/app/components/SlockRaftUiPrimitives.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/components/SlockRaftUiLibraryPrimitives.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/components/SlockRaftUiFormPrimitives.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/components/SlockRaftUiMenuPrimitives.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/components/SlockRaftUiFeedbackPrimitives.kt`
- `compose/shared/src/visualFixtures/kotlin/build/raft/app/RaftUiLibraryVisualFixtures.kt`
- `compose/shared/src/visualFixtures/kotlin/build/raft/app/VisualFixturePages.kt`
- `compose/androidApp/src/androidTest/java/build/raft/app/visual/KmpVisualScreenshotCaptureTest.kt`

React reference checked from the current `third_party/slock-source` checkout:

- `third_party/slock-source/packages/web/node_modules/raft-ui/src/index.ts`
- `third_party/slock-source/packages/web/src/main.tsx`
- Product imports from `third_party/slock-source/packages/web/src/components/**`

## Status Legend

- `ready to adopt`: product-safe API already exists and can be used directly.
- `needs API polish`: visual or partial API exists, but product code needs callbacks, state, sizing, accessibility, or domain-friendly parameters before broad adoption.
- `fixture-only`: intentionally present for visual capture or package parity exploration only.
- `do not extract`: page-specific composition should stay local unless repeated elsewhere.

## Current Coverage

Android/KMP has 22 Raft UI library visual captures wired in `KmpVisualScreenshotCaptureTest.kt`:

- Stable-ish library cases: `library.raft-ui.banner.states`, `empty-state.states`, `typography.states`, `field-input.states`, `avatar.states`, `button-badge.states`, `checkbox-status.states`, `textarea.states`, `tabs.states`, `segmented-control.states`, `label-radio.states`, `select.states`, `menu-surfaces.states`, `popover-surface.states`, `combobox-surface.states`, `notification-center.states`, `interaction-states`, `controlled-open-overlays`.
- Explicit WIP cases: `library.raft-ui.wip-toggle-group.states`, `wip-list-panels.states`, `wip-message-cards.states`, `wip-overlay-shells.states`.

The visual fixture source is properly outside production `commonMain` under `compose/shared/src/visualFixtures`, so fixture pages are not production page registrations. The reusable KMP primitives themselves are in `commonMain`.

Validation caveat: `node scripts/slock-visual-testing.mjs validate --manifest shared` currently fails before reading a manifest because this checkout's `third_party/slock-source/packages/visual-testing` directory is missing `src/cli.mjs`. The audit therefore uses direct source/test references rather than CLI manifest output.

## Component Status

| Component / family | Source | Status | Existing coverage | Product readiness note |
|---|---|---:|---|---|
| `SlockBrutalActionButton`, `SlockBrutalIconButton` | `SlockBrutalComponents.kt` | ready to adopt | Covered indirectly by `button-badge`, `interaction-states`, many product visuals | Already used throughout Auth, Home, Thread, Members, Settings, Files. This is the real KMP button primitive today; `SlockRaftUiButton` wraps it but removes `onClick`. |
| `SlockBrutalInputField` | `SlockBrutalComponents.kt` | ready to adopt | `field-input`, `textarea`, `select`, product composer/auth/settings cases | Product-ready input surface already used broadly. Keep as base for Raft UI input wrappers. |
| `SlockBrutalModalLayer` + `SlockUnifiedDialog` | `SlockBrutalComponents.kt`, `SlockModalComponents.kt` | ready to adopt | Existing modal/product visual cases, not in `library.raft-ui.*` directly | Product-safe modal/dialog engine. Raft UI overlay work should compose this instead of inventing a separate modal shell. |
| `SegmentedControl` | `SlockBrutalComponents.kt` | ready to adopt | `library.raft-ui.segmented-control.states`; product settings/tasks/inbox cases | Closest KMP equivalent to React `raft-ui` segmented control. Already supports counts via `SegmentedControlOption.count`, callbacks, and disabled options. |
| `SlockTopTabBar` | `SlockTopTabBar.kt` | ready to adopt | Product members/thread/settings visual cases; `library.raft-ui.tabs.states` is not this component | Product tab bar is stable and supports shared tab specs. Do not replace it with `SlockRaftUiTabs` yet. |
| `SlockSectionHeader` / `SlockSectionEyebrow` | `SlockRaftUiPrimitives.kt`, `SlockSectionEyebrow.kt` | ready to adopt | `components.ui.section-header.states` | This should be the canonical section header/eyebrow route for letter-spaced section labels. Already used in product areas such as Members. |
| `SlockSurfaceListItem` | `SlockRaftUiPrimitives.kt` | ready to adopt | `components.ui.surface-list-item.states` | Generic brutal bordered list row with selected state and optional click. Product-safe for list rows if data-specific layout stays in caller. |
| `SlockAvatarListRow` | `SlockRaftUiPrimitives.kt` | ready to adopt | `components.ui.avatar-list-row.states` | Product-safe row abstraction when the caller can provide real avatar wire value and resolver output. |
| `SlockFormField` | `SlockRaftUiFormPrimitives.kt` | ready to adopt | `field-input`, `textarea`; used by wrappers | Canonical label/hint/error wrapper. Supports uppercase/plain labels and compact/default size. |
| `SlockBrutalTextarea` | `SlockRaftUiFormPrimitives.kt` | ready to adopt | `library.raft-ui.textarea.states` | Product-safe text area with counter/error support. Not yet broadly adopted. |
| `SlockSlugInput` | `SlockRaftUiFormPrimitives.kt` | ready to adopt | `field-input`/`select` adjacent fixture coverage | Product-safe for create-channel/server slug style fields, but current product code mostly uses direct inputs. |
| `SlockCheckboxLabelRow` | `SlockRaftUiFormPrimitives.kt` | ready to adopt | `library.raft-ui.checkbox-status.states` | Product-safe simple checkbox row. Needs tri-state only if product later needs partial selection. |
| `SlockSelectionPopover` | `SlockRaftUiMenuPrimitives.kt` | ready to adopt | `select`, `combobox-surface`, controlled-open overlays | Product-safe option popover with header/search/empty/clear support. Already used by composer/member/channel pickers. |
| `SlockMenuItemRow` | `SlockRaftUiMenuPrimitives.kt` | ready to adopt | `menu-surfaces`, controlled-open overlays | Product-safe menu row with icon/trailing/divider/click state. Should be used for popup menus instead of ad hoc rows. |
| `SlockSelectTrigger` | `SlockRaftUiMenuPrimitives.kt` | ready to adopt | `library.raft-ui.select.states` | Product-safe trigger chrome. Full select still needs caller-owned popup/open state. |
| `SlockAttentionDot`, `SlockStatusDot` | `SlockRaftUiFeedbackPrimitives.kt` | ready to adopt | `checkbox-status`, WIP library panels | Product-safe status dots. `pulsing` is already available through `SlockStatusIndicator`. |
| `SlockCheckMarker` | `SlockRaftUiFeedbackPrimitives.kt` | ready to adopt | `checkbox-status`, `label-radio` | Product-safe primitive for checkbox/radio markers. |
| `SlockProgressBar` | `SlockRaftUiFeedbackPrimitives.kt` | ready to adopt | WIP list/panel fixtures; product billing/storage visuals use similar state | Product-safe for usage meters. Adopt where pages currently hand-roll percentage bars. |
| `SlockSkeleton`, `SlockSkeletonRow` | `SlockRaftUiFeedbackPrimitives.kt` | ready to adopt | WIP library fixtures; product loading states | Product-safe for loading rows; current pages also have local skeletons. |
| `SlockSpinner` | `SlockRaftUiFeedbackPrimitives.kt` | ready to adopt | `checkbox-status`, WIP fixtures | Product-safe static spinner glyph; does not animate in visual captures by design. |
| `SlockRaftUiBadge` | `SlockRaftUiLibraryPrimitives.kt` | needs API polish | `library.raft-ui.button-badge.states`, WIP panels | Good mapping to React `Badge`, but currently wraps only text/tone. Needs neutral/default variant naming and optional icon/uppercase policy before general product adoption. |
| `SlockRaftUiBanner` | `SlockRaftUiLibraryPrimitives.kt` | needs API polish | `library.raft-ui.banner.states` | Visual parity shell for React `Banner`. Needs stable status naming, single/multiline policy, action callback pattern, and product usage before marking ready. |
| `SlockRaftUiEmptyState` | `SlockRaftUiLibraryPrimitives.kt` | needs API polish | `library.raft-ui.empty-state.states` | Useful shell, but product already has `SlockEmptyState`. Decide whether to unify or keep product empty states as domain-specific. |
| `SlockRaftUiLabel`, `SlockRaftUiField`, `SlockRaftUiInputGroup` | `SlockRaftUiLibraryPrimitives.kt` | needs API polish | `field-input`, `label-radio` | Mirrors React label/field/input-group exports, but `InputGroup` is display-only text, not editable. Use `SlockFormField` + `SlockBrutalInputField` for product until editable input-group API exists. |
| `SlockRaftUiAvatar`, `SlockRaftUiAvatarGroup` | `SlockRaftUiLibraryPrimitives.kt` | needs API polish | `library.raft-ui.avatar.states` | Fixture implementation always uses fallback label and does not accept `avatarWireValue`, image URL, deleted state, or count item. Product should keep `SlockAvatar` / `SlockAvatarListRow` for now. |
| `SlockRaftUiSegmentedControl` | `SlockRaftUiLibraryPrimitives.kt` | needs API polish | `library.raft-ui.segmented-control.states` | Wrapper is static: no `onValueSelected` parameter. Product should use `SegmentedControl` directly until wrapper forwards callbacks/disabled/options. |
| `SlockRaftUiTabs` | `SlockRaftUiLibraryPrimitives.kt` | needs API polish | `library.raft-ui.tabs.states` | Static library display; no callback and not wired to `SlockTabSpec`. Product should keep `SlockTopTabBar`. |
| `SlockRaftUiPopupSurface`, `SlockRaftUiSurfaceLabel`, `SlockRaftUiMenuRow` | `SlockRaftUiLibraryPrimitives.kt` | fixture-only | `menu-surfaces`, `popover-surface`, `combobox-surface`, `controlled-open-overlays` | These are static visual shells for raft-ui open-state captures. Product should use `SlockSelectionPopover`, `SlockMenuItemRow`, `SlockBrutalDropdownMenu`, or page-specific controlled overlay plumbing. |
| `SlockRaftUiNotificationItem` | `SlockRaftUiLibraryPrimitives.kt` | fixture-only | `library.raft-ui.notification-center.states` | It models package surface visuals, not the product notification center data contract. Keep fixture-only until a real notification-center abstraction is requested. |
| `SlockRaftUiTextHeading`, `SlockRaftUiTextSans`, `SlockRaftUiTextMono`, `SlockRaftUiInlineCode`, `SlockRaftUiKbd` | `SlockRaftUiLibraryPrimitives.kt` | fixture-only | `library.raft-ui.typography.states`, overlays/WIP | These are package-display helpers. Product should use `SlockType` styles or domain components unless repeated product patterns justify extraction. |
| `RaftUiWip*VisualContent` compositions | `RaftUiLibraryVisualFixtures.kt` | do not extract | `library.raft-ui.wip-*` | These are checklist/preview compositions, not components. They should remain visual-test-only until a specific product surface asks for them. |

## React Raft UI Mapping

The current React package exports include:

- Already used in product: `ThemeProvider`, `TooltipProvider`, `SegmentedControl`, `SegmentedControlItem`, `SegmentedControlLabel`, `SegmentedControlCount`.
- Exported but not broadly used from product sources in this checkout: `Button`, `Checkbox`, context/dropdown menu, tooltip/popover/select/combobox families, `Label`, `Input`, `Field`, `InputGroup`, `RadioGroup`, `Textarea`, `Badge`, `Avatar`, `InlineCode`, `Kbd`, text primitives, `Status`, `Spinner`, `Banner`, `EmptyState`, `NotificationCenter`, and `Tabs`.

KMP currently mirrors more of the exported package than product React actually consumes. That is fine for visual exploration, but adoption should follow product call sites, not package export breadth.

## Decisions

1. Treat `SlockBrutal*`, `SegmentedControl`, `SlockTopTabBar`, `SlockSectionHeader`, `SlockSurfaceListItem`, `SlockAvatarListRow`, `SlockFormField`, `SlockSelectionPopover`, `SlockMenuItemRow`, `SlockSelectTrigger`, and feedback primitives as the product-safe KMP library layer.
2. Treat `SlockRaftUi*` package-shaped wrappers as visual parity shells unless their API forwards product state and callbacks.
3. Do not migrate product pages to `SlockRaftUiButton`, `SlockRaftUiSegmentedControl`, `SlockRaftUiTabs`, `SlockRaftUiAvatar`, or `SlockRaftUiInputGroup` until those wrappers stop being static display components.
4. Keep WIP visual compositions out of product and do not count them as product adoption.
5. Create follow-up implementation work from concrete product call-site gaps, not from visual fixture existence alone.

## Follow-Up Candidates

- Add product-ready callback/state APIs to the `SlockRaftUi*` wrappers only where they map to actual React raft-ui product usage.
- Add a source/test guard that fails if product code imports WIP fixture-only `RaftUiWip*` content or visual fixture pages.
- Restore or initialize `third_party/slock-source/packages/visual-testing/src/cli.mjs` before relying on the mobile wrapper `scripts/slock-visual-testing.mjs validate --manifest shared`.
