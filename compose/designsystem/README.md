# Raft design system (Compose Multiplatform)

`:designsystem` is the Raft design system (raft-ui 0.8.0 docs and components), written on official
JetBrains Compose Multiplatform (1.12.2, Kotlin 2.4.21). It is the base for screens that
move off Kuikly (see the CMP migration assessment, §3.2 `:ui-cmp`).

- Depends only on `compose.runtime`, `compose.foundation`, `compose.ui`,
  `compose.components.resources` and `compose.material3`. Material 3 is used for
  `TooltipBox` / `PlainTooltip` only (positioning and gestures of `RaftTooltip`); nothing
  is drawn in Material's colours, type or ripple. No Kuikly, no `:shared`.
- Targets: `androidTarget`, `iosArm64` / `iosSimulatorArm64` (no `iosX64`: CMP 1.11+ dropped Apple x86_64), and
  `jvm("desktop")`. The desktop target exists only to render the screenshot tests
  offscreen; nothing ships on it.
- Package: `build.raft.designsystem` (`.tokens`, `.theme`, `.text`, `.components`).

Sources of truth:

- Colour tokens: generated from the one vendored raft-ui source pinned in
  `tools/design-tokens/manifest.json` (currently 0.7.1), the same source as the Kuikly
  tokens in compose/shared. Bumping raft-ui is a source + manifest change that
  regenerates both outputs together; 0.7.1 and 0.8.0 ship byte-identical CSS.
- Component specs and state rules: the raft-design docs site
  (https://raft-design.botiverse.build, raft-ui 0.8.0; `design/` in the raft-design repo),
  cross-checked with the component source
  (`git -C <raft-design> show v0.8.0:packages/ui/src/components/...`). States follow the
  States page: navigation selection is a yellow sticker with an ink edge, content
  selection `info-soft`, value selection ink plus a check mark, focus the `focus-ring`.
- Page specs: where the mobile DRI's component-gap hand-off
  (`handoff/designsystem-component-gaps.md`, measured from the web pages) is more specific
  than raft-ui, it wins; the differences are listed at the end.

## Tokens

| Kind | Where | How |
| --- | --- | --- |
| Colours | `tokens/RaftUiColorTokens.g.kt` | Generated. `RaftUiPalette` (mode-invariant) and `RaftUiThemeColors.RaftLight` / `.RaftDark` |
| Radii, strokes, spacing, sizes, elevation | `tokens/RaftDimensions.kt` | Hand-written, ported from compose/shared `RaftDesignTokens.kt` |
| Type scale | `theme/RaftTypography.kt` | raft-ui `--text-*` and `--field-*` / `--card-title-*`, 1:1 with the Typography page (see Typography below) |

The colours come from the same generator and vendored CSS as the Kuikly tokens in
compose/shared, so both stay in lockstep:

```sh
python3 tools/design-tokens/gen_tokens.py            # regenerate both Kotlin files
python3 tools/design-tokens/gen_tokens.py --check    # CI: sources, both files current, Chrome oracle
python3 tools/design-tokens/gen_tokens.py --self-test
```

See `tools/design-tokens/README.md` for bumping raft-ui.

### Typography

`RaftTypography` is raft-design's type scale and nothing else: one style per token, with
the size, line height, tracking and default weight of raft-ui 0.8.0 `styles.css` (the
[Typography](https://raft-design.botiverse.build/foundations/typography.md) page). Message
text is `body`, 16/24, as on the web; there are no mobile-only roles.

| Style | Token | Size / line | Weight | Tracking | Use for |
| --- | --- | --- | --- | --- | --- |
| `hero` | `text-hero` | 40 / 44 | 700 | -0.03em | Service name on Login with Raft |
| `display` | `text-display` | 34 / 40 | 700 | -0.02em | Create-server title, large page titles |
| `title` | `text-title` | 22 / 28 | 700 | -0.01em | Sign-in and setup-step titles |
| `headline` | `text-headline` | 17 / 22 | 700 | 0 | Top bar, panel, dialog and sheet titles |
| `body` | `text-body` | 16 / 24 | 400 | 0 | Message text; list and task titles at 600; xl buttons at 700 |
| `subhead` | `text-subhead` | 15 / 20 | 400 | 0 | Buttons (44dp) at 700, names, radio labels, dialog descriptions |
| `callout` | `text-callout` | 14 / 20 | 400 | 0 | Previews, menu items (500, selected 700), banners, toasts, tabs (600, selected 700), description-list values |
| `footnote` | `text-footnote` | 13 / 18 | 400 | 0 | Field labels (600), description-list terms (500), group headings (700), 32dp buttons (700) |
| `caption` | `text-caption` | 12 / 16 | 500 | 0 | Subtitles, help text, badges (600, solid 700), menu group labels (700) |
| `label` | `text-label` | 11 / 13 | 600 | 0 | Tab bar and labeled-rail labels |
| `monoMeta` | `text-mono-meta` | 12 / 16 | 400 (Space Mono) | 0 | IDs, times, counts, shortcuts |
| `monoCode` | `text-mono-code` | 13 / 20 | 400 (Space Mono) | 0 | Code and commands |
| `field` | `--field-*` | 16 / 24 | 400 | 0 | Input, textarea, menu search row, a field Select's trigger and options |
| `cardTitle` | `--card-title-*` | 16 / 24 | 600 | 0 | Card titles |

Pick other weights by purpose with `.copy(fontWeight = ...)` (page "Weights by use").
`RaftText` defaults to `body`.

## Theme

```kotlin
// App root: follows RaftThemeState.App (Light by default; the app pins light until
// every screen supports dark).
RaftTheme {
    RaftText("Loading messages", style = RaftTheme.typography.body)
}

// Settings > Appearance
RaftThemeState.App.apply(RaftAppearance.Dark)   // true when the resolved theme changed

// Fixtures and previews pin one appearance.
RaftTheme(appearance = RaftAppearance.Light) { ... }
```

- `RaftThemeState` is an observable Compose state holder modelled on `RaftAppThemeState`
  (`Light` / `Dark` / `System`, default `Light`). As there, `apply()` and
  `onSystemDarkChanged()` return whether the resolved theme changed, not whether the
  stored choice did. Unlike Kuikly, hosts don't report the OS setting: `RaftTheme` reads
  `isSystemInDarkTheme()` and records it on the state.
- `RaftTheme { }` provides `LocalRaftColors`, `LocalRaftTypography`, `LocalRaftShapes`,
  `LocalRaftIsDark` and `LocalRaftContentColor` (CSS `currentColor`, `--foreground` at the
  root). Read them through `RaftTheme.colors` / `.typography` / `.shapes` / `.isDark`.
- `RaftTheme` also provides `LocalTextSelectionColors`: a selection is `brand-soft`
  (raft-web `::selection`), the handles `foreground`.
- `LocalRaftSurfaceColor` (`RaftTheme.surfaceColor`) is the opaque surface under the
  content: `layer-canvas` at the root, `layer-card` inside `RaftCard` and
  `RaftListRowGroup`, a selected row's `info-soft` over the card, a banner's fill for its
  action. Cut-outs read it: the avatar's presence light and the ring of an avatar stack.
  A screen that paints its own surface (a `layer-panel` side panel) provides it too. The
  focus ring's gap stays `layer-canvas` (raft's `--focus-ring` is a fixed colour).
- Fonts: Space Grotesk 300–700 and Space Mono 400/700 from `composeResources/font`
  (copies of `androidApp/src/main/res/font`, SIL OFL 1.1, see `THIRD_PARTY_NOTICES.md`),
  loaded with `Font(Res.font.…)`, so every platform measures the same TTFs.
  **iOS:** `compose.ios.resources.sync=false` in compose/gradle.properties applies to this
  module too, so the CMP plugin copies nothing into the app bundle. The iOS app links this
  module through `:ui-cmp` (exported by the `shared` framework), and the Podfile script phase
  runs `:ui-cmp:syncComposeResourcesForXcodeApp`, which copies these fonts to
  `<App>.app/compose-resources/composeResources/build.raft.designsystem.resources/`, where the
  CMP resource reader looks. A module that adds compose resources must be listed in that task.
- `RaftText` is built on `BasicText`, not Material `Text`, so no Material
  `LocalTextStyle` / `LocalContentColor` can shift colour or line height. Every style
  centres the line box like CSS half-leading (`LineHeightStyle.Alignment.Center`, no trim).

## Screenshot tests

Each component has one golden per appearance holding its full state matrix, rendered
offscreen with skiko (`ImageComposeScene`, no window or GPU) at density 2 and sized to
the content. Goldens: `compose/designsystem/screenshots/<component>_<light|dark>.png`.

```sh
cd compose
./gradlew --no-daemon :designsystem:desktopTest                                # verify
./gradlew --no-daemon :designsystem:desktopTest -Praft.screenshot.record=true  # record
```

- Verify fails when more than 0.1% of the pixels differ by more than 8/255 in any
  channel, or the size changed. The actual image and a diff (mismatches in red) go to
  `compose/designsystem/build/reports/screenshots/`.
- Record after an intended visual change, then review the PNG diff in the PR.
- Goldens are recorded on Linux (the CI runner). Skia rasterises text slightly
  differently on macOS, so a macOS run may need a Linux re-record rather than a looser
  tolerance.
- The same task runs the unit tests (`src/commonTest`).

Other checks:

```sh
./gradlew --no-daemon :designsystem:compileDebugKotlinAndroid :designsystem:compileCommonMainKotlinMetadata
./gradlew --no-daemon :designsystem:compileKotlinIosSimulatorArm64   # macOS only
```

## Components

| Component | raft-ui component | Status | Notes |
| --- | --- | --- | --- |
| `RaftSeparator` | Separator | Done | Horizontal, vertical, with a word |
| `RaftBadge` | Badge | Done | Solid / Soft / Outline x 8 variants, disabled look, icon slot. Not clickable |
| `RaftUnreadCount`, `RaftMentionMarker`, `RaftMutedCount`, `RaftAgentTag`, `RaftAgentStateChip` | Badge, Status | Done | Count (99+, read as "N unread" with the full number), with a thread segment (`RaftUnreadCount(count, threadCount)`: `5 · [thread] 2`, raft-ui `SidebarItemThreadCount`), @ marker, gray muted count, `agent` tag, agent state chips |
| `RaftSpinner` | Spinner | Done | Pixel spinner, xs–lg, inverse, `stillStep` for stills and reduced motion |
| `RaftButton`, `RaftIconButton` | Button | Done | 6 variants, md / lg / xl and icon xs (24) / sm (28) / md / lg; pressed and mouse hover (the press overlay, as the web's `hover:`; no ripple), focus ring, disabled (no overlay), busy (focusable, semantics disabled, taps ignored). No underlined-text variant: that is `RaftTextLink`. `RaftIconButton(onClick, tooltip, …)`: `tooltip` names the action in Title Case, is the content description, and is shown as a `RaftTooltip` on a long press or mouse hover (a long press that opens it does not click). The tooltip adds no screen-reader stop: the button is the one node. `tooltip = null` shows none and names the button by `contentDescription`; `onLongClick` (named `longClickLabel`) takes the long press, leaving the tooltip to the mouse; `Xs` / `Sm` widen their touch target to 44dp |
| `RaftTooltip` | Tooltip | Done | The dark HUD bubble (`layer-hud`, `foreground-on-hud`, `radius-chip`, 10 x 4dp padding, footnote 13/18 medium, `shadow-float`) with a 10 x 5.25dp caret, 8dp above the anchor (below when there is no room). A mouse / trackpad hover keeps it open until the pointer leaves; a long press opens it for 1.5s (and does not click). Not focusable, no semantics node of its own. Material3 `TooltipBox` + `PlainTooltip` (positioning and popup; the gestures are the module's), so its width caps at 200dp (raft-ui: 320dp) |
| `RaftTextLink`, `appendRaftTextLink` | TextLink | Done | Muted (default, on its own) and strong (ink 600, inside a sentence); no height, padding or border, takes the text's size; 1.5dp underline 3dp under the baseline; pressed (the web's hover look), focus ring on a 2dp radius, disabled. Inside a sentence: `appendRaftTextLink` in the `AnnotatedString` of a `RaftText` (or a `RaftBanner` description) |
| `RaftCard`, `RaftCardSection` | Card | Done | Default, inset, option; header (leading / trailing), body, footer. An option card with `onClick` is one target: press, focus ring, radio / checkbox role and `selected` |
| `RaftAvatar`, `RaftAvatarGroup`, `RaftAvatarGroupCount` | Avatar | Done | 7 sizes, agent / human, initials (first grapheme of each word), placeholder, presence light cut out of the current surface, die-cut edge, stack |
| `RaftStatusDot` | Status | Done | 8 variants, 5 presence lights, xs–lg |
| `RaftSkeleton` | Skeleton | Done | Line, block, square; pulse (all in phase, one shared frame loop per theme), or still for screenshots / reduced motion |
| `RaftBanner`, `RaftPanelNotice` | Banner, PanelNotice | Done | 4 intents + default, sm / md / lg, title, action; a text action goes inside the description as a strong `appendRaftTextLink` |
| `RaftSidebarItem`, `RaftSidebarChannelItem`, `RaftSidebarItemIcon`, `RaftSidebarItemMarks`, `RaftSidebarItemMeta`, `RaftSidebarSectionHeader`, `RaftSidebarSectionAction` | Sidebar (SidebarItem, SidebarSection*) | Done | Phone size (raft-ui 0.10, below `md`): rows at least 44dp, `text-body` 600 (700 unread / selected), 19dp icon slot with a 16dp `foreground-hint` icon, `#` / lock for channels; tones; selected is the yellow sticker with the ink edge (`selected` in semantics); pressed / hovered, focus ring; display-only row. Marks: pink count with thread segment, yellow @ (replaces the count; threads still show), muted gray pixel. Group header: chevron, `text-footnote` 700 title, mono count, attention pixel when collapsed, ghost icon controls overhanging the 18dp line; one button with expand / collapse in semantics. The page keeps the rows 8dp from its edge |
| `RaftListRowGroup`, `RaftListRow` | ListRow | Done | Default / plain / entry, tones, selected (`info-soft`, `selected` semantics), pressed, inset focus, disabled, subtitle (AvatarListRow) |
| `RaftDescriptionList` | DescriptionList | Done | Vertical; horizontal grid at 640dp+, one column below, two columns at content width when unbounded; values wrap; each fact is one screen-reader item |
| `RaftIconTile`, `RaftStickerTile` | IconTile | Done | Tile variants and sizes; the sticker tile of the pages |
| `RaftCodeBlock`, `RaftReadOnlyField` | CopyableCode | Done | Read-only command (wraps), the disabled-field value |
| `RaftSection`, `RaftSectionEyebrow` | — | Done | Profile / settings section heading |
| `RaftTextField` | Input, InputGroup | Done | Placeholder, value, focus ring on any focus (tap included), invalid, disabled, read-only (`fill-muted`, hairline, muted text); icon and text addons (`/` prefix, suffix) ring the whole group |
| `RaftTextarea`, `RaftTextareaCounter` | Textarea | Done | Grows to `maxHeight`; counter turns bold red past the limit or when invalid |
| `RaftField`, `RaftFieldLabel`, `RaftFieldItem`, `RaftFieldDescription`, `RaftFieldError`, `RaftFormSection` | Field, Label, FieldItem, FormSection | Done | Label (required `*`, "(optional)", sm / md, at its style's full line height); `labelTrailing`: a switch or text link at the end of the label row (centred, 8dp, own name; the label takes one line); `RaftFieldItem` (sm / md, matching its label) puts a checkbox, radio or switch 8dp before its label, centred on the label's first line; the label names the control and a tap on it works the control; help or error line; through `LocalRaftFieldState` the control is invalid, named by the label and carries the error in semantics; the error line is a polite live region |
| `RaftCheckbox`, `RaftCheckboxRow`, `RaftCheckboxIndicator` | Checkbox, CheckboxIndicator, FieldItem | Done | Unchecked, checked (ink block), indeterminate, focused, disabled; the row toggles as a whole, the box on the label's first line even when it wraps. `RaftCheckboxIndicator` is the box alone (no role or focus) for a row that owns the semantics, square or `Circle` (an ink disc with the check, not a radio dot) |
| `RaftRadio`, `RaftRadioGroup`, `RaftRadioRow` | RadioGroup | Done | Ring with an ink dot; rows with descriptions (reset-agent list) |
| `RaftSwitch` | Switch | Done | Off, on (brand sticker, die-cut in dark), focused, disabled; the knob slides; `label` names it (pass the row's title) |
| `RaftTabs`, `RaftTab`, `RaftTabStrip` | Tabs | Done | Default (sticker, 44dp touch target) and underline; the panel strip scrolls without clipping the sticker or ring, scrolls the selected tab into view (its own scroll only, never the page around it) and fades an edge with more tabs past it. No drag-reorder |
| `RaftSegmentedControl`, `RaftSegment` | SegmentedControl | Done | `fill-muted` track, sticker segment, counts, full width with weights (on its own line); never shrinks below its segments (`shrink-0`): in a narrow row it runs past the end |
| `RaftToggleGroup`, `RaftToggleGroupItem` | ToggleGroup | Done | Same item as a segment; a toggle button (on / off), not a switch |
| `RaftSelectTrigger`, `RaftSelect` | Select | Done | Value / placeholder, pressed, focused, invalid, disabled; field type inside a `RaftField`; read as a drop-down list with its label, value (or placeholder) and expanded state; the list is a dropdown menu exactly as wide as the trigger, and focus returns to the trigger after a keyboard pick |
| `RaftCombobox`, `RaftMultiCombobox`, `RaftComboboxChipsTrigger`, `RaftComboboxOption`, `raftComboboxFilter`, `RaftComboboxSheetThreshold` | Combobox, ComboboxSelect (0.10.3) | Done | One or a few of many values, with search. Single: the field Select's trigger (`RaftSelectTrigger`, field type, label, error and invalid inside a `RaftField`); multiple: the same frame holding 28dp `fill-muted` chips (footnote semibold) that wrap, each with a 22dp remove button ("Remove Bugen"). Where the list opens (raft-ui team): a pick of at most `RaftComboboxSheetThreshold` (10, raft-design Select "one of up to 10 values") opens the Select's anchored list (no search, no keyboard; keyboard navigation and focus return as `RaftSelect`); more opens a bottom sheet (`RaftSheet`'s window and surface, titled by the label, full height) with the menu's search row at its top, not focused, so the keyboard comes up only on a tap; `create` (a value typed that is not an option) and `RaftMultiCombobox` always take the sheet, and with `create` the search row is focused on open and the first row offers "Use “text”" (+ glyph) unless the text is exactly an option's label. Rows keep the menu's metrics (36dp, callout medium; chosen bold + check; `brand-soft` highlight; optional leading icon / avatar and a caption description line), `text-caption` bold group labels, the caller's empty line ("No models match \"zz\"", a polite live region), Done for multiple, an optional Clear (28dp text button) at the end of the title. Search is `raftComboboxFilter`: every word of the query, ignoring case, in the label or a keyword. A single pick or a create closes the list; multiple stays open; Back and a tap outside close. Trigger: a drop-down list with label, value(s) and expanded state; each option one focus stop, selectable, with its selected state |
| `RaftPickerTriggerButton` | PickerTriggerButton | Done | Rest (seam ring), applied filter (`info-soft`, no border), pressed, focused, invalid, disabled; 44dp touch target |
| `RaftDropdownMenu`, `RaftMenuSurface`, `RaftMenuItem`, `RaftMenuLabel`, `RaftMenuSeparator`, `RaftMenuSearchField` | DropdownMenu, Select popup | Done | Hugs its rows (`IntrinsicSize.Max`, at least 240dp: so rows must answer intrinsic measurement, no `LazyColumn`, `RaftDescriptionList` or other subcomposed content unless a fixed `width` is passed); highlight `brand-soft`, current value bold + check, shortcut, disabled, an in-row top hairline (`divider`, CSS `border-t` inside the 36dp); the search row (16/24) takes the inset focus outline; keyboard: first row focused on open, arrows, Home / End, type-ahead; kept inside the safe area and above the keyboard |
| `RaftDialog`, `RaftDialogSurface` | Dialog | Done | sm / md / lg, title, ×, description, body, footer; dialog radius 16. Cancel or a minor action in the footer (an announcement's "Not Now" too) is a `Ghost` button, never an underlined link |
| `RaftSheet`, `RaftSheetSurface` | Drawer (bottom sheet) | Done | Top radius 16, stacked actions, slides up |
| `RaftToast` | Toast | Done | HUD surface (title only, icon, action with a 44dp touch target, description) and the pages' foreground banner on the popover surface |
| `RaftGlyphIcon` | — | Done | The few Lucide icons the components draw |
| `RaftText` | — | Done | Text primitive |
| Tabs drag-reorder, file list row, avatar picker, task card / board lane, toast host | | Not done | Later PRs |

**TODO before migrating a real page:**

- Dialog and Sheet open / close animation (dialogs appear and vanish at once; the sheet
  slides up but not down) and swipe-to-dismiss on the sheet. Both are needed before any
  production screen moves to these overlays.
- **Device check: popup insets.** `Popup` / `Dialog` default to `usePlatformInsets = true`,
  and `RaftDropdownMenu` / `RaftDialog` / `RaftSheet` also subtract `WindowInsets.safeDrawing`
  themselves, so the insets may be applied twice (a menu flipped above its anchor, or
  anything open with the keyboard up, sitting too low or too far in). Verify on an Android
  and an iOS device.
- **Device check: menu height cap.** `raftMenuHeightCap` and `raftMenuPosition` assume the
  popup's constraints and window size are the whole window and subtract the
  `safeDrawing` insets (bars, keyboard) themselves. On Android, `PopupLayout` may already
  measure against the visible display frame (without the status bar and the IME), so a
  long menu could be capped too short, or placed too low, by the insets counted twice.
  Open a long menu with the keyboard up on an Android and an iOS device before changing
  the math.
- **Device check: labelled inputs.** An editable `BasicTextField` carrying the field's
  label as `contentDescription` (`RaftTextField`, `RaftTextarea` in a `RaftField`): TalkBack
  may read the description instead of the typed text, or the label twice. Verify with
  TalkBack and VoiceOver.

**Option card or choice rows.** Both make the whole surface the target and draw the
radio or checkbox without a handler of its own. `RaftCard(variant = Option, onClick = …)`
is the larger, framed choice with a title and a description (where an agent runs, a
plan); `RaftRadioRow` / `RaftCheckboxRow` are the plain list of choices inside a form
or a dialog (reset-agent options). Pick one per set; don't mix them.

**Field type.** Inputs, input groups, textareas and the menu's search row use raft-ui
`--field-*` (16/24 regular, `RaftTypography.field`). That is not a deviation: the design system's
Typography page sets field text at 16px because iOS zooms into inputs under 16px. The
15/20 (`text-subhead`) of the page hand-off applies to select triggers and their values,
which keep it, except a select inside a `RaftField` (select.tsx `chrome: field`): its
trigger and its options take the field type, 16/24 at 400, to line up with the inputs
beside it, so a value doesn't change size when the list opens.

**Large text.** Control heights are minimums (`heightIn`): at a 200% font scale the input,
select trigger, tabs, picker trigger and choice rows grow instead of clipping
(`large_font_*` golden). The choice row centres its box on the label's first line in sp.

**Safe area and keyboard.** `RaftDialog` keeps 16dp inside `WindowInsets.safeDrawing`
(bars, cutout and the keyboard), the sheet's content clears the home indicator and the
keyboard while its fill runs under them, and `RaftDropdownMenu` positions itself inside
the same safe area, so it flips above an anchor the keyboard would cover. Checked on
desktop only: **verify on an Android and an iOS device** before migrating a page.

### State primitives (`components/RaftStates.kt`)

Internal modifiers every component draws its states with; none takes layout room.

- `raftActiveStickerLook` + `raftSticker`: navigation selection. `brand` fill, 1.5dp
  `sticker-ink` edge and ink type; the edge is always drawn (transparent at rest).
- `raftDieCutEdge`: 1.5dp `sticker-edge` outside the box (transparent in light).
- `raftFocusRing`: 2dp `layer-canvas` gap plus 2dp `line-strong`, outside the box and
  under the die-cut edge. Keyboard focus only (Compose never focuses a clickable on touch).
- `raftInsetFocusOutline`: 2dp `foreground` inside the box, for rows touching an edge.
- `raftPressOverlay` / `raftPressOverlayColor`: `fill-pressed`, `sticker-pressed` or
  `fill-frame-pressed` over the fill; press never changes the fill or moves anything.
  Buttons also draw it while a mouse or trackpad hovers them (`raftIsHovered`; the
  `clickable` emits the hover interactions), as raft-ui does on `hover:`.
- `raftFloatShadow` / `raftOverlaySurface`: `shadow-float` drawn as a stack of rounded
  rects that add up to the CSS Gaussian (no platform blur, so Android below API 31 matches),
  plus the `line-seam` ring and fill of every overlay (menus, dialogs, sheets, toasts). The
  paths are built once per size (`drawWithCache`), not per frame. Overlays provide their
  fill as `LocalRaftSurfaceColor`.
- `raftTouchTarget`: widens a small control's touch target to 44dp without changing its
  layout or drawing (a pointer node with `touchBoundsExpansion`, raft-ui's overhanging
  `::after`). Tabs, picker triggers and the toast action use it; the space round the
  control must be free, as a neighbour hit first wins.

Screenshot fixtures force these states with `LocalRaftPressedPreview`,
`LocalRaftHoveredPreview`, `LocalRaftFocusedPreview`, `LocalRaftTooltipPreview` (opens
every `RaftTooltip`, persistent; `icon_button_tooltip_*`) and `LocalRaftSpinnerStillStep`. `avatar_list_row_states_*`
reproduces the visual-testing case `components.ui.avatar-list-row.states`; the
interactive fixtures name their case ids in `InteractiveComponentScreenshotTest`.
`menu_popup_*` renders a real menu and select list in their popups in a wide window. `combobox_trigger_*`,
`combobox_list_*` (the anchored list) and `combobox_sheet_*` (the sheet, create row included) hold the combobox as
surfaces (no window); `RaftComboboxSemanticsTest` opens the real list and sheet, searches, creates and picks. A real tap (not a
forced state) is checked by `RaftInteractionTest`: tapping an input draws the focus ring,
a checkbox row toggles from its label, and a mouse over an icon button draws the press
fill (and leaving removes it). `RaftDisplaySemanticsTest` and `RaftInteractiveSemanticsTest`
(compose ui-test) check semantics, keyboard navigation, popup widths, the widened touch
target and the tab strip's scroll, and that an icon button's tooltip opens on a long press
without clicking, stays open while a mouse hovers and adds no focus stop of its own.

## raft-ui 0.7.1 vs 0.8.0

Checked with `git diff v0.7.1 v0.8.0` in raft-design:

- No change to `foundation.css`, `tokens.json`, `styles.css`, `fonts.css`, or to the
  Badge, Separator and Spinner sources. The generated colours and the three components
  are the same in both versions.
- Docs only: Typography adds "Why message text is 16px" (the module follows it: `body` is 16/24); Alignment
  adds "A control and its label" (centre a checkbox, radio or switch on the first line of
  its label); Spacing gives the hover toolbar 28px buttons; the
  rest renames "workspace" to "server" in copy.
- Components (CHANGELOG 0.8.0), and where the module has them:
  - `TextLink` replaces Button `variant="link"` / `size="inline"`: `RaftTextLink` and
    `appendRaftTextLink`. `RaftButton` never had a link variant.
  - `AnnouncementDialogSecondaryAction` is a ghost button, not an underlined link. The
    module has no announcement dialog; its dialogs' minor actions are `Ghost` buttons.
  - `FieldLabel` drops `leading-none`: `RaftFieldLabel` always had its style's line height
    (footnote 18, caption 16). `FieldItem` takes `size`: `RaftFieldItem(size = …)`.
  - Checkbox, radio and switch centre on the first line of their label
    (`(1lh - control) / 2`): `RaftFieldItem`, `RaftCheckboxRow`, `RaftRadioRow`. Where the
    control is taller than the line (a 30dp switch beside an 18dp label) the label moves
    down instead of the control hanging above the row. A switch in a `RaftListRow`'s
    trailing slot stays centred on the row, as raft-ui ListRow (`items-center`) does.
  - `SegmentedControl` is `shrink-0`: `RaftSegmentedControl` measures at least its
    segments' width. `ToggleGroup` keeps `max-w-full` in 0.8.0 and is unchanged.
  - The presence light on 19px avatars (`xs`, `2xs`) is 7px with a 1px edge: `RaftAvatar`
    already drew it so (7dp, 1dp edge, 2dp overhang, 1dp cut-out).

## Differences from raft-ui and its docs

- **Typography page vs component recipes.** Where the Typography page's "Sizes by use"
  and a raft-ui component recipe disagree, the recipe wins for that component: menu and
  select group labels are `text-caption` bold (dropdown-menu.tsx, select.tsx), not the
  page's footnote group heading; the sheet description is `text-callout` (drawer.recipe.ts),
  where the page gives dialog descriptions `text-subhead` (the centred dialog keeps
  subhead); an option card's title is `text-callout` semibold (card.recipe.ts), not the
  16/24 card title; tabs are `text-callout` (tabs.tsx; the page sets only their weight).
- **Disabled solid badge keeps its die-cut edge in dark.** badge.tsx (0.7.1 and 0.8.0) removes only
  the inset ink edge when disabled and keeps `ring-sticker-edge`; the Dark mode doc says
  a disabled button is no longer a sticker. The module follows the component source.
- **Hover only from a mouse.** Touch has no hover; pressed draws the web's hover look.
  Buttons and icon buttons also take it under a mouse or trackpad pointer. A
  `RaftTextLink` has no fill to lay an overlay on, so pressed takes its hover look (muted
  turns ink; strong's underline turns pink).
- **Tooltip on a long press.** The web opens an icon button's tooltip on hover; touch
  opens it on a long press for 1.5s (a mouse still on hover, open until it leaves). Every
  icon-only action has a name: the tooltip, or `contentDescription` when `tooltip = null`.
  The dialog's close button shows a "Close" tooltip; the web dialog's close button has
  none. An icon button with `onLongClick` gives the long press to that action and opens its
  tooltip only on hover.
- **Small icon buttons.** raft-ui 0.10.3 renders `icon-xs` / `icon-sm` as `icon-md` (32dp)
  and its Button doc lists no smaller size. `RaftIconButtonSize.Sm` (28dp, 16dp icon) and
  `Xs` (24dp, 16dp icon) are the square buttons raft-ui draws inside components
  (MessageItemToolbarButton `size-7`, MessageAttachmentAction `size-6`), with the Button
  variants' looks and a 44dp touch target.
- **TextLink inside a sentence** is a span of the sentence's text (`appendRaftTextLink`),
  with a click target over its words on each line it takes (one node for focus and
  screen readers, on its first line), and its underline and focus ring drawn under the
  text. The text is measured with the constraints it is given, so `fillMaxWidth` with
  `TextAlign.Center` / `End` aligns as plain `RaftText` does. Its spaces are no-break spaces, as raft-ui's inline-block button
  doesn't wrap. A `RaftText` with such links drops any other link or string
  annotations; don't mix them.
- **Busy button keeps its colour.** button.tsx sets `disabled` while `loading`, which
  greys it; the Button doc ("keeps the button's width and color") and the States rule
  (busy: unchanged) win. The busy button stays focusable (raft-ui `aria-disabled`), is
  disabled in semantics and ignores taps.
- **32dp hit area.** raft-ui widens a 32dp button to a 44px target with an overhanging
  `::after`. Tabs, picker triggers and the toast action do the same with
  `raftTouchTarget`; 32dp buttons don't yet, so leave room around them.
- **Skeleton line** uses the chip radius (DRI page spec); raft-ui uses the sticker radius.
- **Code block wraps** by default (the pages' `break-all`); raft-ui CopyableCode keeps
  one line (`wrap = false`).
- **Sidebar.** Only the phone size: the desktop 36dp / `text-subhead` row and the
  hover-only section controls are not drawn. A group header's disclosure is a target as
  tall as the header (40dp; the web's is its 18dp line) and its controls are 32dp ghost
  icon buttons (raft-ui `h-7`, 28px). A muted place with something unread shows the gray
  status pixel the web Home draws; raft-ui's quiet number is `RaftSidebarItemMeta`. The
  chevron leads the group title, as on the web Home (the docs composition puts it after).
- **ListRow** follows the pages' AvatarListRow too: an optional mono `subtitle` line and a
  trailing slot 6dp apart. Rows don't know their siblings: pass `divider = true` from the
  second row on. A selected or disabled row hides its badge.
- **DescriptionList horizontal** switches on the list's own width (640dp), not the window's.
  It measures its labels first (a subcomposition) so each fact can be one node for screen
  readers; it has no intrinsic size.
- **Option card** is a target of its own (`selectable`, or `toggleable` with
  `Role.Checkbox`) so the whole card picks, as the pages' option cards do; its radio or
  checkbox is drawn without a handler. The card never shows the pick itself.
- **Icons**: components draw a handful of Lucide outlines (`RaftGlyph`, ISC); app icons
  come through slots.
- **Choice rows** (`RaftCheckboxRow`, `RaftRadioRow`) follow the pages' radio list
  (44dp min, 12dp padding and gap, `text-subhead` semibold label, `text-footnote` muted
  description), not raft-ui FieldItem's 8dp gap and footnote label.
- **Hit areas.** A checkbox, radio or switch is exactly its box (raft-ui overhangs a
  44 x 36dp `::after`); the larger target comes from the row around it (a choice row, or
  a list row's trailing slot), which is how the pages use them.
- **Menu focus.** Keyboard focus on a menu row is the `brand-soft` highlight (Base UI
  moves the highlight with focus), not a ring. The search row at the popup's edge takes
  the inset outline. Opened from the keyboard (`InputMode.Keyboard`) the first row takes
  focus, and on closing focus returns to `returnFocusTo` (`RaftSelect` passes its
  trigger); opened by touch nothing is focused or highlighted. Type-ahead jumps on the
  first letter only (no multi-letter buffer).
- **Popups.** `RaftDropdownMenu` is a platform popup with transparent room round the
  surface for its shadow (16dp sides, 8dp top, 24dp bottom); a tap in that room dismisses
  like a tap outside. Collision handling is the simple flip / clamp of `raftMenuPosition`.
- **Scrim.** `RaftDialog` and `RaftSheet` use the platform dialog window, whose own scrim
  (about 60% black) stands in for `layer-backdrop` (65% light / 60% dark). Dialogs appear
  without animation; the sheet slides up but closes without the slide, and has no swipe
  to dismiss or snap points (see the TODO above).
- **Combobox on a phone.** raft-ui opens ComboboxSelect's list in a popup under the field;
  on a phone the module opens a pick of more than `RaftComboboxSheetThreshold` values, a
  `create` combobox and every multiple pick in a bottom sheet with the search row at its
  top (raft-ui team, #proj-raft-ui; raft-design #617, overlays on a phone), and only
  `create` focuses the search on open. The create row ("Use “text”", not raft-ui's
  "Create “text”") comes first, where raft-ui appends it after the options. The sheet's
  rows keep the menu's metrics (36dp, callout), not the Drawer page's 52dp place-to-go
  rows, and a multiple pick marks rows with the check as Combobox does (not Select
  `multiple`'s checkbox). The trigger is not an input: typing happens in the sheet, so
  there is no type-to-filter in the field, no `maxTagCount` and no Left Arrow into the
  chips. A chip's remove button is its 22dp box (no widened target); removing from the
  sheet is the larger target. The sheet has no grab handle yet (as `RaftSheet`).
- **Toast.** Only the surface: no host (stacking, timeout, swipe). The action's underline
  uses the font's default thickness and offset (CSS: 1.5px, 3px). `RaftToastSurface.Popover`
  is the pages' foreground in-app banner (hand-off §11); raft-ui itself puts every toast on
  the HUD.
- **Focus-ring gap** is `layer-canvas` everywhere (white in light, as raft's
  `--focus-ring`), even on a track or popover of another colour, as CSS draws it.
