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

## Batch Scan

The audit scanned shared Compose files for three patterns:

- named functions such as `*Button`, `*IconButton`, `*Chip`, and `*Action`;
- direct `Button(` usage;
- clickable surfaces with border/background chrome.

The scan is limited to Android/KMP app source. `third_party/slock-source` is read-only React reference material and must not be modified as part of this migration.

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

## Migration Done

This task moved the high-priority product action controls listed above onto shared button primitives. The goal is not to make every clickable surface look like a button; it is to remove duplicated action-button chrome from real app surfaces and keep future visual changes centralized.

## Follow-up Rule

When adding a new KMP action control:

1. Use a shared primitive first.
2. Only write local chrome when the control is not a button semantically or when the shared primitive cannot express the behavior.
3. If a local exception is necessary, name the reason in code review or task notes so future audits do not treat it as accidental drift.
