# Composer System Newline Menu Spec

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

### OHOS

- Desired platform API is the system edit menu extension (`TextInput` / `TextArea` `editMenuOptions`) rather than a custom shared popup.
- Current Slock OHOS host consumes a vendored Kuikly render HAR, and Kuikly's input view is implemented in the C++ ArkUI render (`KRTextFieldView`) rather than app-level ArkTS `TextArea(...)`.
- The local HarmonyOS SDK exposes ArkTS `TextArea.editMenuOptions(EditMenuOptions)` and native ArkUI `NODE_TEXT_EDIT_MENU_OPTIONS` for text. The current Kuikly C++ input nodes expose text-input/text-area selection-menu visibility attributes, but not a wired `slockSystemNewlineAction` prop.
- Phase 2 should add the same prop to the Kuikly OHOS render/fork and republish the HAR. If the C API cannot attach `ArkUI_TextEditMenuOptions` to `ARKUI_NODE_TEXT_INPUT` / `ARKUI_NODE_TEXT_AREA` for the current SDK level, keep the prop as a no-op on OHOS and document the platform limitation.

## Validation

- `git diff --check`
- Android shared compile
- Android app compile / APK assemble
- OHOS shared compile after shared prop changes
- OHOS HAR rebuild after Phase 2 render changes
