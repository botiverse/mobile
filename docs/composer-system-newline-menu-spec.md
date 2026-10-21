# Composer System Newline Menu Spec

> **Status (2026-07-05, task #206 closed):** Phase 1 (shared prop +
> Android host adapter in `SlockKuiklyTextFieldViews.kt`) is live. The
> planned Phase 2 (an OHOS render hook) was dropped with the HarmonyOS app
> (#3181).

## Goal

The mobile composer uses `ImeAction.Send`, so touch-only users need a newline entry that does not compete with the platform text editing affordances. Long pressing the composer input should show the normal platform edit menu and include one additional action: `换行`.

## Contract

- Shared code marks only composer text fields that want the action with the private native prop `slockSystemNewlineAction`.
- Native text field render adapters own the system menu integration.
- The action preserves the platform menu surface, focus, cursor handles, paste, select-all, and copy/cut behavior.
- When invoked with a selected range, `换行` replaces the selection with `\n`.
- When invoked with only a caret, `换行` inserts `\n` at the caret.
- The action is enabled only for multiline text fields.

## Implementation Plan

### Shared

- Add `systemNewlineAction: Boolean = false` to the shared `TextField` wrapper.
- When true, pass `slockSystemNewlineAction = 1` via Kuikly Compose `Modifier.setProp`.
- Enable the prop only on `ThreadComposerContent`'s composer field.

### Android

- Extend the existing `SlockKuiklyTextFieldView` adapter.
- Handle `slockSystemNewlineAction`.
- Attach custom insertion and selection action-mode callbacks to append a `换行` menu item while leaving platform default menu items intact.
- Insert/replace newline through `Editable.replace(...)` so Kuikly's existing text watcher emits the normal value-change event.

## Validation

- `git diff --check`
- Android shared compile
- Android app compile / APK assemble
