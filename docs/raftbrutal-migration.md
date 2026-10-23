# RaftBrutal migration inventory

The old "Brutal" layer (`RaftBrutal*` composables in `compose/shared/.../app/components/` and
`RaftBrutalDropdownMenu.kt`) is an adapter over `compose/designsystem` that page code still
calls, and the brutal accent tokens `RaftColor.BrandYellow`, `RaftColor.AccentPink` and
`RaftColor.AccentCyan` still colour page chrome. Both are now deprecated
(`@Deprecated(level = WARNING)`), and `scripts/check_raftbrutal_usage.sh` (CI, `Test` workflow)
fails when any file has more uses than `scripts/raftbrutal_usage_baseline.tsv` records. A file
that is not in the baseline may have none.

When you migrate a file, lower its count and re-record the baseline:

```bash
scripts/check_raftbrutal_usage.sh --update-baseline
```

Never raise a count by hand. Counts below are from `origin/main` at `098d263ac` (2026-10-23).
A "use" is one `RaftBrutal<Name>` identifier, or one `RaftColor.BrandYellow/AccentPink/AccentCyan`,
outside imports and comments.

`RaftColor.StickerInk` is not deprecated: raft-ui keeps it as the ink on fixed sticker fills
(see `.agents/skills/raft-designsystem-ui/SKILL.md` §4). Its wrong uses (hand-drawn
`.border(RaftAppStroke.Default, RaftColor.StickerInk)` frames) go away with the component they
frame.

## Replacements

All targets are in `build.raft.designsystem.components` unless noted.

| Deprecated | Replacement |
|---|---|
| `RaftBrutalActionButton` | `RaftButton` (variant / size from the raft-ui recipe; no `backgroundColor`, shadow or border args) |
| `RaftBrutalIconButton` | `RaftIconButton` (`RaftBackButton` for back / close) |
| `RaftBrutalTextField`, `RaftBrutalInputField` | `RaftTextField` inside a `RaftField` (label, description, error) |
| `RaftBrutalReadOnlyField` | `RaftTextField(readOnly = true)` inside a `RaftField` |
| `RaftBrutalFieldFrame` | `RaftField` |
| `RaftBrutalSearchField` | `RaftTextField` with a search `leading` icon; `RaftMenuSearchField` inside a menu; `RaftSearchTrigger` for a search entry point |
| `RaftBrutalTextarea` | `RaftTextarea` inside a `RaftField` |
| `RaftBrutalSelectField`, `RaftBrutalSelectOption` | `RaftCombobox` + `RaftComboboxOption` (raft-ui ComboboxSelect: anchored list up to 10 options, searchable sheet above), or `RaftChoicePicker` (radio up to 5, sheet up to 10, searchable sheet above). Both apply the picker-count rule the adapter ignores |
| `RaftBrutalDropdownMenu`, `RaftBrutalDropdownMenuItem` | `RaftDropdownMenu` + `RaftMenuItem` |
| `RaftBrutalDropdownMenuSurface`, `RaftBrutalDropdownMenuRow` | `RaftMenuSurface` + `RaftMenuItem` (`RaftMenuLabel`, `RaftMenuSeparator`) |
| `RaftBrutalCard` | `RaftCard` (`RaftCardSection`, `RaftCardFooterText`) |
| `RaftBrutalListRow` | `RaftListRow` in a `RaftListRowGroup` |
| `RaftBrutalBadge`, `RaftBrutalTone` | `RaftBadge` (and `RaftBadgePresets`) |
| `RaftBrutalModalLayer` | `RaftDialog`, `RaftSheet` or `RaftDialogSheet` (all open through `RaftWindowDialog`) |
| `RaftBrutalActionSheetRow` | `RaftMenuItem` in a `RaftSheet`, or `RaftListRow` |
| `RaftColor.BrandYellow` | `RaftColor.Brand` (sticker solid), `RaftColor.Frame` (the yellow frame), `RaftColor.BrandSoft` (wash) |
| `RaftColor.AccentPink` | `RaftColor.Attention` (sticker solid), `RaftColor.AttentionSoft` (wash); a selected state uses the component's own selected look |
| `RaftColor.AccentCyan` | `RaftColor.Info` (sticker solid), `RaftColor.InfoSoft` (wash) |

## Page batches

Largest win first. One batch is one PR: migrate, add or re-record the Robolectric fixture for
the page (`compose/shared/src/androidUnitTest`), lower the baseline. "Brutal" is `RaftBrutal*`
uses, "Tokens" is legacy accent uses. Paths are under
`compose/shared/src/commonMain/kotlin/build/raft/app/`.

### 1. Settings sub-pages — 35 (24 brutal, 11 tokens)

| File | Brutal | Tokens | Uses |
|---|---:|---:|---|
| `SettingsLanguageRegionPage.kt` | 7 | 2 | SelectField ×2 (+4 options), ActionButton, AccentPink |
| `SettingsServerProfilePage.kt` | 6 | 2 | ActionButton ×4, TextField, ReadOnlyField, AccentPink ×2 |
| `SettingsFormParts.kt` | 3 | 2 | ActionButton ×2, Card, AccentCyan, BrandYellow |
| `SettingsDeveloperPage.kt` | 4 | 0 | TextField ×4 |
| `SettingsMobilePage.kt` | 0 | 4 | BrandYellow ×4 |
| `SettingsHandsFeedbackContent.kt` | 2 | 0 | ActionButton ×2 |
| `SettingsStandaloneWebViewHost.kt` | 1 | 1 | ActionButton, BrandYellow |
| `SettingsNotificationsPage.kt` | 1 | 0 | ActionButton |

No open PR touches these files. Fixtures: none yet; add `settings.<page>` captures.

### 2. Thread — 35 (20 brutal, 15 tokens)

| File | Brutal | Tokens | Uses |
|---|---:|---:|---|
| `ThreadPage.kt` | 17 | 10 | DropdownMenuItem ×5, ActionButton ×5, TextField ×4, DropdownMenu, DropdownMenuSurface, SearchField; AccentCyan ×4, BrandYellow ×3, AccentPink ×3 (attachment tabs and comments, pin, message search, menus) |
| `ThreadComposerPage.kt` | 3 | 5 | IconButton ×2 (send), ActionButton (text chip), AccentPink ×5 |

`ThreadComposerPage.kt` waits for #3626 (composer attachment tray). The attachment-comment row
(`ThreadAttachmentCommentRow`) also shows the raw ISO `createdAt` (audit class 6); fix both together.

### 3. Members — 35 (22 brutal, 13 tokens)

| File | Brutal | Tokens | Uses |
|---|---:|---:|---|
| `MembersPage.kt` | 20 | 12 | ActionButton ×17, InputField ×2, TextField; AccentPink ×10 (Admin role colour, avatar picker selection, save buttons), AccentCyan ×2 (member kind) |
| `MembersAgentRuntimeFormV2Dialog.kt` | 2 | 1 | ActionButton ×2, AccentPink |

#3629 and #3615 touch three lines of `MembersPage.kt` each; rebase after them.

### 4. Shell, modals and system pages — 29 (18 brutal, 11 tokens)

| File | Brutal | Tokens | Uses |
|---|---:|---:|---|
| `RaftModalComponents.kt` | 5 | 2 | ActionButton ×2, ModalLayer, ActionSheetRow, IconButton, BrandYellow, AccentCyan |
| `InAppBrowserPage.kt` | 3 | 2 | IconButton ×3, AccentCyan ×2 |
| `AppUpdatePage.kt` | 1 | 4 | ActionButton, BrandYellow ×3, AccentPink |
| `AnnouncementModal.kt` | 3 | 1 | ActionButton ×3, BrandYellow |
| `RouterPage.kt` | 3 | 1 | ListRow, InputField, ActionButton, BrandYellow |
| `RaftEnvironmentIndicator.kt` | 2 | 0 | Badge, Tone |
| `RaftDatabaseLaunchRecoveryContent.kt` | 1 | 1 | Card, BrandYellow |

### 5. Home, auth and search — 29 (12 brutal, 17 tokens)

| File | Brutal | Tokens | Uses |
|---|---:|---:|---|
| `RaftHomeComponents.kt` | 4 | 7 | InputField ×2, ActionButton ×2, AccentPink ×4, BrandYellow ×3 |
| `AuthComponents.kt` | 2 | 8 | InputField, IconButton, AccentPink ×5, BrandYellow ×2, AccentCyan |
| `RaftMenu.kt` | 3 | 2 | DropdownMenuRow, DropdownMenuItem, SearchField, BrandYellow ×2 |
| `components/SharedHomeSearchInput.kt` | 2 | 0 | InputField ×2 |
| `RaftFilterPopover.kt` | 1 | 0 | InputField |

### 6. Inbox — 23 (23 brutal)

| File | Brutal | Tokens | Uses |
|---|---:|---:|---|
| `SharedInboxPanel.kt` | 12 | 0 | Badge, Tone ×11 |
| `commonTest/.../SharedInboxPanelTest.kt` | 11 | 0 | Tone ×11 (assertions follow the panel) |

#3615 touches `SharedInboxPanel.kt`; rebase after it.

### 7. Status colours and shared chrome — tokens only (33, plus tests)

`RaftAgentStatusIndicator.kt` 5, `RaftAgentStatusDot.kt` 2, `components/RaftChrome.kt` 5,
`components/RaftUiFeedbackPrimitives.kt` 5, `markdown/RaftMarkdown.kt` 5,
`TokenPaletteVisualPage.kt` 3, and one each in `RaftTabBar.kt`, `RaftSectionHeader.kt`,
`SharedTaskStatusChipMeta.kt`, `SharedTasksPage.kt`, `RaftServerChoice.kt`,
`RaftMemberPicker.kt`, `RaftChildPage.kt`, `RaftAppTheme.kt`. Status dots and chips must map to
the `presence-*` / state tokens web uses, not a hue for hue swap; the matching `commonTest`
assertions (`RaftMarkdownTest.kt` 29, `ExternalAgentDetailIndicatorTest.kt` 9,
`RaftAgentStatusIndicatorTest.kt` 5, `RaftAgentStatusLabelTest.kt` 4, ...) move with them.

### 8. Create agent and runtime forms — 40 (39 brutal, 1 token), on hold

| File | Brutal | Tokens | Uses |
|---|---:|---:|---|
| `SharedCreateAgentDialog.kt` | 31 | 1 | TextField ×12, SelectField ×7 (+6 options), ActionButton ×5, IconButton, AccentPink |
| `RuntimeFormV2Fields.kt` | 8 | 0 | SelectOption ×4, TextField ×3, SelectField |

Largest by count, but over half of these uses sit inside `BuiltInRuntimeSection` /
`AdvancedRuntimeSection`, the runtime pickers #3604 just moved to `RaftChoicePicker`, and the
remaining selects (provider, model, reasoning) are the picker-count work of audit class 6 fix 2
(`RaftBrutalSelectField` over `raftComboboxPresentation`). Do it with that change, with the
Mobile DRI's go-ahead.

### Not in a batch (owned elsewhere)

| File | Brutal | Tokens | Owner |
|---|---:|---:|---|
| `SettingsHomePage.kt` | 10 | 6 | Lagoon (being split / changed) |
| `RaftFilePreview.kt` | 6 | 4 | Lagoon |
| `SettingsAccountPage.kt` | 6 | 0 | another agent |
| `SettingsStoragePage.kt` | 0 | 0 | Lagoon (`agent/lagoon/storage-page-ds`); its hand-drawn `RaftAppStroke.Default` blocks are not counted here |

### Last: delete the layer

When the pages above are done, the remaining uses are the adapters themselves and their
fixtures and tests: `components/RaftBrutalComponents.kt`, `RaftBrutalDropdownMenu.kt`,
`components/RaftBrutalSelect.kt`, `components/RaftBrutalCard.kt`, `components/BrutalListRow.kt`,
`components/RaftUiPrimitives.kt` (Tone, Badge), `components/RaftUiFormPrimitives.kt` (Textarea),
`components/RaftUiLibraryPrimitives.kt`, the visual fixtures (`UiPrimitiveVisualFixtures.kt`,
`RaftModalPointerBarrierVisualPage.kt`, ...) and the `RaftBrutal*Test.kt` / `RaftDesignTokensTest.kt`
cases. Delete them together and drop their rows from the baseline.

The full per-file list is `scripts/raftbrutal_usage_baseline.tsv`.
