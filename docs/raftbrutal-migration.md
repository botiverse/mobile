# RaftBrutal migration

## Components: removed

The old "Brutal" component layer (`RaftBrutal*` composables in `compose/shared/.../app/components/`
and `RaftBrutalDropdownMenu.kt`) is gone (2026-11-02). Every call site now draws the
`compose/designsystem` component directly, and the adapters, their helpers and their tests were
deleted. Do not reintroduce app-level wrappers that restyle design-system controls.

What replaced what (all in `build.raft.designsystem.components` unless noted):

| Removed | Replacement |
|---|---|
| `RaftBrutalActionButton` | `RaftButton` |
| `RaftBrutalIconButton` | `RaftIconButton` (`RaftBackButton` for back / close) |
| `RaftBrutalTextField`, `RaftBrutalInputField` | `RaftTextField` inside a `RaftField`; `RaftTextarea` for more than one line |
| `RaftBrutalReadOnlyField` | `RaftReadOnlyField` inside a `RaftField` |
| `RaftBrutalFieldFrame` | `RaftField` |
| `RaftBrutalSearchField` | `RaftTextField` with a search `leading` icon; `RaftMenuSearchField` inside a menu or popover |
| `RaftBrutalTextarea` | `RaftTextarea` inside a `RaftField` |
| `RaftBrutalSelectField`, `RaftBrutalSelectOption` | `RaftCombobox` + `RaftComboboxOption`, or `RaftChoicePicker` |
| `RaftBrutalDropdownMenu*` | `RaftDropdownMenu` / `RaftMenuSurface` + `RaftMenuItem` (app positioning: `RaftUiControlledMenu`, `RaftUiMenuSurface`) |
| `RaftBrutalCard` | `RaftCard` |
| `RaftBrutalListRow` | `RaftListRow` in a `RaftListRowGroup` |
| `RaftBrutalBadge`, `RaftBrutalTone` | `RaftBadge` + `RaftBadgeVariant` |
| `RaftBrutalModalLayer` | `RaftDialog`, `RaftSheet`, `RaftDialogSheet` (app dialogs: `RaftUnifiedDialog`) |
| `RaftBrutalActionSheetRow` | `RaftMenuItem` in a `RaftSheet`, or `RaftListRow` |

A design-system field that needs the app editor behaviour (IME action, keyboard-height events,
tap-outside focus dismiss, delayed auto-focus) takes it from
`build.raft.app.components.rememberRaftDesignSystemEditor` (single line: put `editor.modifier` on
the field, pass `editor.keyboardOptions`) or `rememberRaftTextareaEditorModifier` (a textarea's
`inputModifier`). Secrets stay on the `TextFieldValue` overload with
`PasswordVisualTransformation()` and `KeyboardType.Password`.

## Tokens: still deprecated

The brutal accent tokens `RaftColor.BrandYellow`, `RaftColor.AccentPink` and
`RaftColor.AccentCyan` still colour page chrome and stay `@Deprecated(level = WARNING)`: add no
new uses, and lower a file's count when you touch it.

| Deprecated | Replacement |
|---|---|
| `RaftColor.BrandYellow` | `RaftColor.Brand` (sticker solid), `RaftColor.Frame` (the yellow frame), `RaftColor.BrandSoft` (wash) |
| `RaftColor.AccentPink` | `RaftColor.Attention` (sticker solid), `RaftColor.AttentionSoft` (wash); a selected state uses the component's own selected look |
| `RaftColor.AccentCyan` | `RaftColor.Info` (sticker solid), `RaftColor.InfoSoft` (wash) |

`RaftColor.StickerInk` is not deprecated: raft-ui keeps it as the ink on fixed sticker fills
(see `.agents/skills/raft-designsystem-ui/SKILL.md` §4).

Status dots and chips (`RaftAgentStatusIndicator.kt`, `RaftAgentStatusDot.kt`,
`SharedTaskStatusChipMeta.kt`, ...) must map to the `presence-*` / state tokens web uses, not a
hue-for-hue swap; their `commonTest` assertions (`ExternalAgentDetailIndicatorTest.kt`,
`RaftAgentStatusIndicatorTest.kt`, `RaftAgentStatusLabelTest.kt`, ...) move with them.

Largest remaining users (2026-11-02): `ThreadPage.kt` 9, `RaftHomeComponents.kt` 7,
`AuthComponents.kt` 6, `components/RaftUiFeedbackPrimitives.kt` 5, `components/RaftChrome.kt` 5,
`RaftAgentStatusIndicator.kt` 5, `components/RaftUiLibraryPrimitives.kt` 4, then 1 to 3 each in
about twenty more files. `RaftFilePreview.kt` and `SettingsHomePage.kt` are Lagoon's.
