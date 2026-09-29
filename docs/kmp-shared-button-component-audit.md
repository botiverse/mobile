# KMP Shared Button Component Audit

Date: 2026-06-08
Task: #product task #189

## Source of Truth

Shared/KMP action controls should use the common brutal primitives instead of page-local `Button` or `Box + clickable + border` chrome:

- `SlockBrutalActionButton` for text actions, CTA buttons, full-width settings actions, small action chips, and modal action rows that need the standard brutal border/shadow treatment.
- `SlockBrutalIconButton` for square icon-only actions.
- `SlockToolbarChip` / `SlockAnchoredToolbarChip` for toolbar filters, sort chips, and anchored menu triggers.
- `SlockModalActionButton`, `SlockModalFullWidthActionButton`, and `SlockModalSquareActionButton` for modal-specific actions.
- `SlockHeaderIconAction` for compact page/header icon controls where the header chrome is intentional.

Low-level `Button` remains allowed inside the component implementations themselves and in deliberately custom controls whose behavior is not a normal action button: tabs, toggles, selectable rows/cards, text input chrome, reaction chips, image preview controls, and demo/sample pages.

## Shared Component Inventory

These are the common KMP components that should be treated as existing source-of-truth before adding local chrome:

### Action Buttons

- `SlockBrutalActionButton`
- `SlockBrutalIconButton`
- `SlockModalActionButton`
- `SlockModalFullWidthActionButton`
- `SlockModalSquareActionButton`
- `SlockModalIconButton`

Current product usage includes Settings account/storage/billing/computers actions, Home notification actions, Members detail/add actions, Activity small actions, Thread header/back-to-bottom/inline actions, Thread composer icon/send actions, and modal footer actions.

### Toolbar Chips / Filters

- `SlockToolbarChip`
- `SlockAnchoredToolbarChip`
- `SlockFilterPopover`
- `SlockBaseMenuOverlay`

Current product usage includes Home Search filters/sort/channel dropdown, Tasks filter/status menus, and Thread overflow/status menus.

### Modal / Sheet Chrome

- `SlockUnifiedDialog`
- `SlockModalLayer`
- `SlockModalListShell`
- `SlockModalSectionHeader`
- `SlockModalCompactMemberRow`
- lower-level `SlockBrutalModalLayer` / `SlockBrutalModalHeader`

Current product usage includes Home create-channel, Thread settings, Thread members/add-member, and Thread danger/action flows.

### Shell / Navigation / Layout

- `SlockAppShellSurface`
- `SlockAppShellRootBackHandler`
- `SlockAppBottomTabBarHost`
- `SlockBottomTabBar`
- `SlockVerticalTabBar`
- `SlockFloatingDockTabBar`
- `SlockNavigationRailTabBar`

Current product usage includes the shared Home app shell, Home/Activity/Search/Saved/Settings bottom tabbar, and root Back/safe-area handling.

### Rows / Sections / Content Rendering

- `SlockCollapsibleSectionHeader`
- `SlockBrutalListSectionHeader`
- `SlockBrutalListShell`
- `SlockBrutalPanelShell`
- `SlockMarkdownBody`
- markdown inline chips / mention text / inline code chips inside `SlockMarkdown.kt`

Current product usage includes Members section headers, Settings/Tasks list sectioning, Thread markdown message bodies, and shared Home/Search/Saved panels.

### Input / Field Chrome

- `SlockBrutalTextField`
- `SlockModalTextField`
- `SlockModalTextFieldComponent`
- `SlockModalFieldLabel` / `SlockModalFieldLabelComponent`
- Auth-specific `AuthTextField` / `AuthActionButton`

Current product usage includes Auth, Home create-channel, Thread modals, and Settings forms. Auth remains its own shared component family because its visual and focus behavior is a product-level surface, not a one-off local input.

## Batch Scan

The audit scanned shared Compose files for three patterns:

- named functions such as `*Button`, `*IconButton`, `*Chip`, and `*Action`;
- direct `Button(` usage;
- clickable surfaces with border/background chrome.

The scan is limited to Android/KMP app source. React reference material comes
from an external `botiverse/slock` checkout (`SLOCK_SOURCE_REPO`) and must not
be modified as part of this migration.

High-priority manual chrome found in product surfaces:

- Settings: `SettingsProfileSaveButton`, `SettingsLogoutAction`, `SettingsStorageButton`, `PlanActionButton`, and `SettingsPrimaryButton`.
- Home: `HomeNotificationActionButton`.
- Members: `MembersDetailActionButton` and `MembersAddButton`.
- Thread: `ThreadHeaderAction`, `ThreadBackToBottomButton`, `ThreadInlineActionRow`, `ComposerIconButton`, and `ComposerTextChip`.

Controls intentionally left for separate work or as allowed exceptions:

- Row/card click targets such as Home channel rows, Members rows, Thread file cards, and Settings rows. These are navigation rows, not standalone action buttons.
- Toggle or selector controls such as composer “As Task”, reaction chips, tab bars, and segmented/search toolbar controls that already have their own shared primitive or interaction model.
- Image preview navigation and overlay actions, where hit-area/overlay behavior is specific to the preview surface.
- Demo pages such as `ReverseLazyColumnDemoPage` and router smoke buttons.

## Remaining Local Usage Map

This is the current follow-up scan for common primitives that exist but still have local usage patterns elsewhere.

### Already Unified, Continue Using

- Settings CTA/action buttons use `SlockBrutalActionButton`.
- Home notification actions use `SlockBrutalActionButton`.
- Members add/detail actions use `SlockBrutalIconButton` / `SlockBrutalActionButton`.
- Thread composer icon/send actions use `SlockBrutalIconButton` / `SlockBrutalActionButton`.
- Thread modal actions use `SlockModalActionButton` / `SlockModalFullWidthActionButton`.
- Home Search and Tasks filters use `SlockToolbarChip`, `SlockAnchoredToolbarChip`, `SlockFilterPopover`, and `SlockBaseMenuOverlay`.
- Shared Home shell/tabbar/safe-area uses `SlockAppShellSurface` and `SlockAppBottomTabBarHost`.

### Local Usage That Is Allowed

- `BasicWidget.Button`: the low-level pressable primitive used by shared components.
- `SlockBrutalComponents.kt`: implementation of the shared brutal primitives.
- `SlockModalComponents.kt`: implementation of modal action wrappers.
- `SlockMenu.kt` / `SlockFilterPopover.kt`: menu row implementation, not product-page ad hoc button chrome.
- `SlockTabBar.kt`: tab and navigation rail implementation.
- `SlockSectionHeader.kt`: collapsible header toggle implementation.
- `RouterPage.kt`, `ReverseLazyColumnDemoPage.kt`, visual token pages, and smoke/demo pages.
- Home channel/DM rows, Settings rows, Members rows, Thread file cards, and Tasks rows: row/card navigation targets, not standalone buttons.
- Thread image preview controls: overlay/hit-area-specific preview controls.
- Composer “As Task” checkbox/reaction chip style controls: selector/toggle semantics.

### Still Worth Watching

These are not immediate refactor targets, but they should be checked when the surrounding feature next changes:

- `SettingsDetailBackButton` in `SettingsHomePage.kt`: local back button chrome. It is a compact page-header control and can move to a shared header icon primitive if Settings detail headers expand.
- `SharedHomePanelButton` / `SharedHomeSearchSortSegmentButton` in `SettingsHomePage.kt`: local segmented/panel controls. They are acceptable because they are not generic action buttons, but should not be copied to other pages.
- `SharedTasksPillButton` in `SharedTasksPage.kt`: local status/action pill. It should eventually converge with toolbar/pill primitives if Tasks gets more pill variants.
- `AuthActionButton`: auth-specific shared button. Keep it local to Auth unless Product decides Auth and app action buttons should share one visual primitive.
- `SettingsInlineButton` in `SettingsAccountPage.kt`: currently a small inline text action helper. If it gains border/shadow or appears outside Settings account, migrate it to a shared small action primitive.

## Migration Done

This task moved the high-priority product action controls listed above onto shared button primitives. The goal is not to make every clickable surface look like a button; it is to remove duplicated action-button chrome from real app surfaces and keep future visual changes centralized.

## Follow-up Rule

When adding a new KMP action control:

1. Use a shared primitive first.
2. Only write local chrome when the control is not a button semantically or when the shared primitive cannot express the behavior.
3. If a local exception is necessary, name the reason in code review or task notes so future audits do not treat it as accidental drift.
