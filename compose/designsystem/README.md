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
- **Strings.** The module has no string resources. The few words the components write
  themselves (the "(optional)" label marker, the dialog's "Close", a text field's "Clear",
  a busy button's and spinner's "Loading") come from `LocalRaftStrings` (`RaftStrings`,
  read as `RaftTheme.strings`), English by default. The app provides its translations once
  round its design-system screens (`LocalRaftStrings provides RaftStrings(optional =
  stringResource(MR.strings.common_optional_suffix), …)`); `closeLabel` / `loadingLabel`
  parameters default to it and still take a per-call value.
- `RaftTheme` also provides `LocalTextSelectionColors`: a selection is `brand-soft`
  (raft-web `::selection`), the handles `foreground`.
- `RaftTheme(localeList = ...)` puts the app's UI language on every `RaftTypography` style
  (`RaftTypography.from(fonts, localeList)`), so Android and iOS pick the CJK fallback
  glyphs of that language (`ja`, `ko`, `zh-Hant`) for Han characters. The default, null,
  sets none (the device locale decides). A span's own `localeList` still wins: pass a
  user's name as an `AnnotatedString` to keep its own.
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
| `RaftBadge` | Badge | Done | Solid / Soft / Outline x 8 variants, disabled look, icon slot, a trailing slot 4dp past the label (a runtime label's usage `RaftStatusDot`). Not clickable (the caller adds the tap) |
| `RaftUnreadCount`, `RaftMentionMarker`, `RaftMutedCount`, `RaftAgentTag`, `RaftAgentStateChip` | Badge, Status | Done | Count (99+, read as "N unread" with the full number), with a thread segment (`RaftUnreadCount(count, threadCount)`: `5 · [thread] 2`, raft-ui `SidebarItemThreadCount`), @ marker, gray muted count, `agent` tag, agent state chips |
| `RaftSpinner` | Spinner | Done | Pixel spinner, xs–lg, inverse, `stillStep` for stills and reduced motion |
| `RaftButton`, `RaftIconButton` | Button | Done | 6 variants, md / lg / xl and icon xs (24) / sm (28) / md / lg; pressed and mouse hover (the press overlay, as the web's `hover:`; no ripple), focus ring, disabled (no overlay), busy (focusable, semantics disabled, taps ignored). No underlined-text variant: that is `RaftTextLink`. `RaftIconButton(onClick, tooltip, …)`: `tooltip` names the action in Title Case, is the content description, and is shown as a `RaftTooltip` on a long press or mouse hover (a long press that opens it does not click). The tooltip adds no screen-reader stop: the button is the one node. `tooltip = null` shows none and names the button by `contentDescription`; `onLongClick` (named `longClickLabel`) takes the long press, leaving the tooltip to the mouse; `Xs` / `Sm` widen their touch target to 44dp |
| `RaftTooltip` | Tooltip | Done | The dark HUD bubble (`layer-hud`, `foreground-on-hud`, `radius-chip`, 10 x 4dp padding, footnote 13/18 medium, `shadow-float`) with a 10 x 5.25dp caret, 8dp above the anchor (below when there is no room). A mouse / trackpad hover keeps it open until the pointer leaves; a long press opens it for 1.5s (and does not click). Not focusable, no semantics node of its own. Material3 `TooltipBox` + `PlainTooltip` (positioning and popup; the gestures are the module's), so its width caps at 200dp (raft-ui: 320dp) |
| `RaftTextLink`, `appendRaftTextLink` | TextLink | Done | Muted (default, on its own) and strong (ink 600, inside a sentence); no height, padding or border, takes the text's size; 1.5dp underline 3dp under the baseline; pressed (the web's hover look), focus ring on a 2dp radius, disabled. Inside a sentence: `appendRaftTextLink` in the `AnnotatedString` of a `RaftText` (or a `RaftBanner` description) |
| `RaftCard`, `RaftCardSection` | Card | Done | Default, inset, option; header (leading / trailing), body, footer. An option card with `onClick` is one target: press, focus ring, radio / checkbox role and `selected` |
| `RaftAvatar`, `RaftAvatarGroup`, `RaftAvatarGroupCount` | Avatar | Done | 7 sizes, agent / human, initials (first grapheme of each word), placeholder, presence light cut out of the current surface, die-cut edge, stack |
| `RaftAvatarEmpty` | Avatar (`rf-avatar--empty`) | Done | The dashed "add" identity at any avatar size: dashed `foreground-hint` edge and plus, press `fill-medium`, disabled; screenshot `avatar_empty` |
| `RaftAvatarBadgeBox`, `RaftMessageDelegatedAvatar` | AvatarBadge (`render`), MessageItemDelegatedAvatar | Done | A sticker of the caller's in the presence light's corner and overhang; the assistant's 19dp (`2xs`) face on its owner's `md` avatar for a message the assistant sent (RFC 077): ringed and backed by the row surface (1.5dp past the edge in light, 3dp in dark so 1.5dp shows past the die-cut), deleted = grayscale at 60%, hidden from accessibility |
| `RaftStatusDot` | Status | Done | 8 variants, 5 presence lights, xs–lg; a light beside text is `md`, 10dp (raft-design #722: even, so it centres in even rows); `sm` (8dp) is the dot on an icon's corner; a light on an avatar follows the avatar |
| `RaftMessageReferenceReadMark` | MessageReferenceReadMark | Done | A bare Lucide check, 10dp, stroke 2.5 (raft-design #794, raft-ui 0.34.0), `palette-lime-700` in light and the online lime in dark; no light, no box. Hand-matched `--read-mark` (the generated tokens are pinned to raft-ui 0.22.1) |
| `RaftSkeleton`, `RaftSkeletonList`, `rememberRaftSkeletonDelayPassed` | Skeleton, InboxItemSkeletonList `delay` | Done | Line, block, square; pulse (all in phase, one shared frame loop per theme), or still for screenshots / reduced motion. A list skeleton waits `RaftListSkeletonDelayMillis` (1s, raft-ui 0.22.1 #720) before it shows, so a fast load doesn't flash it: `RaftSkeletonList` wraps the rows, `rememberRaftSkeletonDelayPassed(loading)` gates lazy rows. `delayMillis = 0` or `LocalRaftListSkeletonDelayMillis provides 0L` shows them at once (screenshot fixtures). A single `RaftSkeleton` is not delayed |
| `RaftLocatedState`, `Modifier.raftLocated`, `raftLocatedStrength` | located (`useLocated`, `LOCATED_MS`) | Done | raft-ui 0.22.1 #719: the item a jump took someone to shows `info-soft` at once, held 1.2s, then an ease-out fade, gone at 2s (`RaftLocatedMillis`); reduced motion holds it still for 2s, then off. `locate(key)` once the item is on screen (a new call restarts it); `raftLocated` paints it over the item's fill in its shape (draw phase only); `raftLocatedStrength(active)` for a row that lays it in its own fill (a message). A stronger mark (selected, menu open, search match, checked) wins: don't paint it there. Used by the thread jump, the search return row and Activity's unread jump |
| `RaftProgressBar` | Progress | Done | `fill-muted` track (sticker radius), `info` fill with the 1.5dp `sticker-ink` inset edge; sm / md / lg (6 / 8 / 10dp); primary and warning; complete turns `success`; optional header (label, mono value); indeterminate half-width sweep (`animated = false` freezes it); progress-bar semantics |
| `RaftSteps`, `RaftStep`, `RaftStepStatus` | Steps (0.24, `vertical`) | Done | A short flow of 3 to 5 steps in a column: 20dp square markers (sticker radius) with the number in Space Mono 12 bold, the title `text-footnote` semibold 8dp after it, an optional `text-caption` description, 16dp between steps and a 1dp `line-seam` from 4dp under each marker to 4dp above the next. Ahead (`wait`): seam outline, hint ink; current (`process`): the yellow `brand-400` sticker with a 1.5dp ink edge; done (`finish`): solid ink with a paper check; failed (`error`): the orange sticker with a cross. A step's `icon` replaces its number while waiting or in progress (a `RaftSpinner` for the step running now). Each step is one screen-reader item, its title then the finish / error label. Not ported: the row layout and clickable steps |
| `RaftMeter` | Meter (`wip`) | Done | What is left of an allowance (a usage limit that resets): takes `used` of `max`, draws `max - used`, full to start and draining; `fill-muted` track (sticker radius), `info` fill with the 1.5dp `sticker-ink` inset edge; `low` or less left (20% of `max` unless set, at or below) turns the fill `warning` and the value `warning-strong`; used up is an empty `warning-muted` track with a 1dp `warning` edge; never `success` or `danger`; sm / md (6 / 8dp); optional header (label footnote semibold, value footnote tabular `foreground-muted`); the value text is the spoken value. The header is one row (label truncates), where raft-ui wraps the value under a long label |
| `RaftBanner`, `RaftPanelNotice` | Banner, PanelNotice | Done | 4 intents + default, sm / md / lg (callout 14/20 at every size), title, action, `pending` (a spinner in the icon's place); a text action goes inside the description as a strong `appendRaftTextLink`. PanelNotice leads with its intent's 16dp icon, never a status light, or with `pending` (Info) the spinner in its place |
| `RaftSidebarItem`, `RaftSidebarChannelItem`, `RaftSidebarItemIcon`, `RaftSidebarItemMarks`, `RaftSidebarItemMeta`, `RaftSidebarSectionHeader`, `RaftSidebarSectionAction`, `RaftSidebarGroupLabel` | Sidebar (SidebarItem, SidebarSection*, SidebarGroupLabel) | Done | Phone size (raft-ui 0.10, below `md`): rows at least 44dp and 1dp apart, `text-body` 600 (700 unread / selected), 19dp icon slot with a 16dp `foreground-hint` icon, `#` / lock for channels; `RaftSidebarItemVariant.Entry` for Home's Search / Activity / Saved (web `SIDEBAR_ENTRY_ROW_CLASS`: a 20dp icon, 12dp to the name; `raftSidebarItemIconSize()` for app icons) and `RaftSidebarItemVariant.Peer` for DMs (the aside's parts 6dp apart, web `SIDEBAR_ROW_TRAILING_CLASS` `gap-1.5`; other rows `gap-1`); the title as a `String` or an `AnnotatedString` (spans and their `localeList` kept, for CJK names); a `titleTrailing` slot right after the name (activity-muted bell, bridge badge; the name truncates, the slot doesn't); tones; selected is the yellow sticker with the ink edge (`selected` in semantics); pressed / hovered, focus ring; display-only row. Press and hold (`onLongClick`, named by `onLongClickLabel` in semantics) opens the row's or header's menu without clicking; `menuOpen` keeps the press overlay while that menu is open (not on the selected row, raft-ui `not-data-active:data-popup-open`) and hides a header's attention pixel. Marks: pink count with thread segment, yellow @ (replaces the count; threads still show), muted gray pixel. Group header: 14dp chevron, `text-footnote` 700 title, mono count, attention pixel when collapsed, `countSeparator` (the members page's "Humans · 11": the separator inside the count, no gap after the title), 28dp ghost icon controls (16dp icon, 4dp apart, 44dp touch target) overhanging the 18dp line (a `RaftGlyph`, e.g. the sort arrows `ArrowUpDown` / `ArrowDownUp`, or any icon through the `icon` slot); one button with expand / collapse in semantics. `RaftSidebarGroupLabel`: a plain group heading that doesn't collapse (the settings hub's groups), `px-2 pt-4 pb-1.5` `text-footnote` 700 hint. The page keeps the rows 8dp from its edge |
| `RaftListRowGroup`, `RaftListRow` | ListRow | Done | Default / plain / entry, tones, selected (`info-soft`, `selected` semantics), pressed, inset focus, disabled, subtitle (AvatarListRow), meta kept whole while the title gives way, then truncated with an ellipsis; `text = RaftListRowText.Member`: the web members page row's 15/20 title and sans 12/16 sub-line (`member_list_row_*`) |
| `RaftDescriptionList` | DescriptionList | Done | Vertical; horizontal grid from 384dp of its own width, one column below (value 2dp under its label with no 32dp minimum, 10dp between facts), two columns at content width when unbounded; values wrap; each fact is one screen-reader item; text values copy on a long press (`raftCopyable`) |
| `Modifier.raftCopyable`, `LocalRaftCopyHandler` | (mobile only) | Done | Long-press to copy an identifier value: `LongPress` haptic, copies the text as shown (an ellipsized value copies in full; a placeholder copies nothing) through `LocalRaftCopyHandler` (the app: host pasteboard + "Copied" toast; none: the Compose clipboard), a "Copy" custom screen-reader action (`RaftStrings.copy`). With `onClick` a tap still taps (ambient ink), without it the value keeps no press look or click action. `RaftDescriptionValue` / `Item(term, value)` are copyable by default (`copyable = false` opts out); `RaftReadOnlyField` copies its value by default (`copyText`); `RaftListRow` copies its title / subtitle when given `titleCopyText` / `subtitleCopyText` |
| `RaftIconTile`, `RaftStickerTile` | IconTile | Done | Tile variants and sizes; the sticker tile of the pages |
| `RaftInboxItemLeading`, `RaftInboxItemLeadingBadge` | InboxItemLeading, InboxItemLeadingBadge (`wip`) | Done | The 36dp leading slot of an inbox row (a 35dp avatar or IconTile) and the thread badge in its bottom-right corner, the same on a tile and an avatar: 14dp, 3dp radius, 1dp `sticker-ink` edge, `info-soft` over the canvas, a 2dp `layer-canvas` ring, 2dp past the slot (2.5dp past the avatar, as a presence light), `ThreadIcon` at 10dp in `foreground`; hidden from accessibility. One mark per corner: with the badge, leave the presence light out |
| `RaftCodeBlock`, `RaftReadOnlyField` | CopyableCode | Done | Read-only command (wraps), the disabled-field value (one 44dp line, or `maxLines` > 1: wraps and grows from 44dp, 12dp above and below); the field's value copies on a long press (`copyText`, default the shown text; null for a placeholder) |
| `RaftSection`, `RaftSectionEyebrow`, `RaftSectionEyebrowIcon` | — (web `SectionEyebrow`, `SectionHeader`) | Done | Profile / settings section heading: `text-footnote` 700 `foreground-hint`, sentence case as written; `count` after it in Space Mono 12 hint, 6dp on; `icon`: a 16dp `foreground-hint` icon 8dp before the label (`RaftSectionEyebrowIcon` draws a `RaftGlyph` there); a section adds `titleAction` and `action` |
| `RaftTextField`, `RaftTextFieldAction`, `RaftTextFieldClearAction` | Input, InputGroup | Done | Placeholder, value, focus ring on any focus (tap included), invalid, disabled, read-only (`fill-muted`, hairline, muted text); icon and text addons (`/` prefix, suffix) ring the whole group; `trailingAction`: the button addon at the end (28dp, `foreground-muted`, pressed `fill-pressed`, named; clear with the `LocalRaftStrings` "Clear", show password, copy) |
| `RaftTextarea`, `RaftTextareaCounter` | Textarea | Done | Grows to `maxHeight`; counter turns bold red past the limit or when invalid; `String` and `TextFieldValue` overloads; `inputModifier` reaches the editor (focus requester, focus and IME hooks) |
| `RaftField`, `RaftFieldLabel`, `RaftFieldItem`, `RaftFieldDescription`, `RaftFieldError`, `RaftFormSection` | Field, Label, FieldItem, FormSection | Done | Label (required `*`, "(optional)" from `LocalRaftStrings`, sm / md, at its style's full line height); `labelTrailing`: a switch or text link at the end of the label row (centred, 8dp, own name; the label takes one line); `RaftFieldItem` (sm / md, matching its label) puts a checkbox, radio or switch 8dp before its label, centred on the label's first line; the label names the control and a tap on it works the control; help or error line; through `LocalRaftFieldState` the control is invalid, named by the label and carries the error in semantics; the error line is a polite live region |
| `RaftCheckbox`, `RaftCheckboxRow`, `RaftCheckboxIndicator` | Checkbox, CheckboxIndicator, FieldItem | Done | Unchecked, checked (ink block), indeterminate, focused, disabled; the row toggles as a whole, the box on the label's first line even when it wraps. `RaftCheckboxIndicator` is the box alone (no role or focus) for a row that owns the semantics, square or `Circle` (an ink disc with the check, not a radio dot) |
| `RaftRadio`, `RaftRadioGroup`, `RaftRadioRow` | RadioGroup | Done | Ring with an ink dot; rows with descriptions (reset-agent list) |
| `RaftSwitch` | Switch | Done | Off, on (brand sticker, die-cut in dark), focused, disabled; the knob slides; `label` names it (pass the row's title) |
| `RaftTabs`, `RaftTab`, `RaftTabStrip` | Tabs | Done | Default (sticker, 44dp touch target) and underline; the panel strip scrolls without clipping the sticker or ring, scrolls the selected tab into view (its own scroll only, never the page around it) and fades an edge with more tabs past it. No drag-reorder |
| `RaftSegmentedControl`, `RaftSegment` | SegmentedControl | Done | `fill-muted` track, sticker segment, counts, full width with weights (on its own line); segments never shrink, but the track shrinks to its row and the segments scroll sideways inside it, the selected one kept in view (raft-ui 0.23) |
| `RaftToggleGroup`, `RaftToggleGroupItem` | ToggleGroup | Done | Same item as a segment; a toggle button (on / off), not a switch |
| `RaftSelectTrigger`, `RaftSelect` | Select | Done | Value / placeholder, pressed, focused, invalid, disabled; field type inside a `RaftField`; read as a drop-down list with its label, value (or placeholder) and expanded state; the list is a dropdown menu exactly as wide as the trigger, and focus returns to the trigger after a keyboard pick; `optionEnabled` greys out options that cannot be picked (raft-ui `SelectItem disabled`) |
| `RaftCombobox`, `RaftMultiCombobox`, `RaftComboboxSheet`, `RaftMultiComboboxSheet`, `RaftComboboxChipsTrigger`, `RaftComboboxOption`, `raftComboboxFilter`, `RaftComboboxSheetThreshold` | Combobox, ComboboxSelect (0.10.3) | Done | One or a few of many values, with search. Single: the field Select's trigger (`RaftSelectTrigger`, field type, label, error and invalid inside a `RaftField`); multiple: the same frame holding 28dp `fill-muted` chips (footnote semibold) that wrap, each with a 22dp remove button ("Remove Bugen"). Where the list opens (raft-ui team): a pick of at most `RaftComboboxSheetThreshold` (10, raft-design Select "one of up to 10 values") opens the Select's anchored list (no search, no keyboard; keyboard navigation and focus return as `RaftSelect`); more opens a bottom sheet (`RaftSheet`'s window and surface, titled by the label, full height) with the menu's search row at its top, not focused, so the keyboard comes up only on a tap; `create` (a value typed that is not an option) and `RaftMultiCombobox` always take the sheet, and with `create` the search row is focused on open and the first row offers "Use “text”" (+ glyph) unless the text is exactly an option's label. Rows keep the menu's metrics (36dp, callout medium; chosen bold + check; `brand-soft` highlight; optional leading icon / avatar and a caption description line), `text-caption` bold group labels, the caller's empty line ("No models match \"zz\"", a polite live region), Done for multiple, an optional Clear (28dp text button) at the end of the title. Search is `raftComboboxFilter`: every word of the query, ignoring case, in the label or a keyword. A single pick or a create closes the list; multiple stays open; Back and a tap outside close. Trigger: a drop-down list with label, value(s) and expanded state; each option one focus stop, selectable, with its selected state. `RaftComboboxSheet` / `RaftMultiComboboxSheet`: the same sheet for a trigger of the caller's own (a filter chip), shown while composed, with the caller's query |
| `RaftChoicePicker`, `RaftChoicePickerSheetSurface`, `raftChoicePickerVariant`, `RaftChoicePickerRadioMax` | RadioGroup / Select / ComboboxSelect by count (raft-design picker rule) | Done | One value, drawn by how many options there are, counting every option (a "Hosted" choice too): at most `RaftChoicePickerRadioMax` (5) a `RaftRadioGroup` of `RaftRadioRow`s with each option's description under its label; at most `RaftComboboxSheetThreshold` (10) a Select whose trigger (`RaftSelectTrigger`) opens a bottom sheet listing the options with their descriptions and no search row (on a phone a Select is a sheet); more the sheet with the search row (ComboboxSelect). Disabled options grey out in every variant. `RaftChoicePickerSheetSurface` is the sheet without its window, for fixtures. Goldens `choice_picker_*` |
| `RaftChoiceList`, `RaftChoiceListItem`, `RaftChoiceListDefaults` | ChoiceList (`wip`, raft-design #767 / #772) | Done (single) | An always-open list to choose one of any number of items when choosing is the dialog's main content (where to move an agent): a `layer-card` frame with a hairline border, rows with the radio circle, the name (`text-subhead` 600) and up to two description lines (`text-footnote` `foreground-muted`, each cut off on its own line, colour inside a line is the caller's), and an optional `action` at the row's end (a 32dp ghost icon button) outside the row's selectable node. Above 5 items a search field shows and the rows scroll inside the frame past 288dp; the chosen row stays first while searching, with the caller's no-match line under it; no items show the caller's empty line. Goldens `choice_list_*` |
| `RaftPickerTriggerButton` | PickerTriggerButton | Done | Rest (seam ring), applied filter (`info-soft`, no border), pressed, focused, invalid, disabled; 44dp touch target |
| `RaftDropdownMenu`, `RaftMenuSurface`, `RaftMenuItem`, `RaftMenuLabel`, `RaftMenuSeparator`, `RaftMenuSearchField` | DropdownMenu, Select popup | Done | Hugs its rows (`IntrinsicSize.Max`, at least 240dp: so rows must answer intrinsic measurement, no `LazyColumn`, `RaftDescriptionList` or other subcomposed content unless a fixed `width` is passed); highlight `brand-soft`, current value bold + check, shortcut, disabled, an in-row top hairline (`divider`, CSS `border-t` inside the 36dp); the search row (16/24) takes the inset focus outline; keyboard: first row focused on open, arrows, Home / End, type-ahead; kept inside the safe area and above the keyboard |
| `RaftPopoverSurface` | Popover (`PopoverPopup`) | Done | The popover layer of the menus (`layer-popover`, container radius, `line-seam` ring, `shadow-float`): 300dp and 16dp padding by default (Popover rules); `width = null` takes the room it is given (at least 220dp, no intrinsics, so lazy content may go in); `contentPadding`, flush `header`. Surface only: positioning is the caller's |
| `RaftDialog`, `RaftDialogSurface`, `RaftDialogHeader` | Dialog | Done | sm / md / lg, title, ×, description, body, footer (one row at the end; stacks full width, the confirm on top, only when it doesn't fit, raft-ui #673; stacked, a ghost Cancel takes the `default` frame, #727, `LocalRaftButtonsStacked`, also in a sheet's actions); dialog radius 16. `RaftDialogHeader` is the title row (title, description 6dp under it, × named by `LocalRaftStrings`) on its own, for a surface whose body the caller lays out. Cancel or a minor action in the footer (an announcement's "Not Now" too) is a `Ghost` button, never an underlined link |
| `RaftSheet`, `RaftSheetSurface` | Drawer (bottom sheet) | Done | Top radius 16, stacked actions, slides up |
| `RaftWindowDialog`, `RaftWindowDialogProperties`, `RaftTheme.scrim` | (window) | Done | The one platform window under every dialog, sheet and modal: back-swipe block, edge to edge, the theme scrim (or none, for content that draws its own), system bars that follow `RaftTheme.isDark` |
| `RaftDialogSheet`, `RaftDialogSheetSurface` | Dialog, "On a phone" (2026-10-21) | Done | A phone dialog with a long list, several fields or steps: 90% of the window tall, top radius 16, slides up; 56dp head with × at the start, the title and the one commit button (32dp, primary) at the end (none for a null `commitLabel`, a view that commits nothing; `commitIcon` leads its label, as the web keeps its icon there), a seam under it, no footer; the content fills the rest and scrolls itself. A tap on the scrim does not close it; × and Back do |
| `RaftToast` | Toast | Done | HUD surface (title only, `intent` icon 16dp: Info / CircleCheck / TriangleAlert, an avatar in its place, action with a 44dp touch target, description) and the pages' foreground banner on the popover surface |
| `RaftMessageListJumpControls`, `RaftMessageListJumpPill`, `RaftMessageListJumpPillGroup`, `RaftMessageListJumpPillDismiss` | MessageList > Jump controls (wip) | Done | One pill for every jump (Back to Bottom, N New Messages, Next / Earliest Unread Thread (N)): Button `outline` / `md` (32dp, 1.5dp `line-strong` on `layer-card`, control radius, 13/18 bold, 16dp arrow, 12dp padding both sides), pressed `fill-pressed`, 44dp touch target. The group joins a dismiss (32dp square, no start edge, 16dp ✕ in `foreground-muted`, `foreground` pressed, its touch target reaching past its end only) on the pill's end edge. Controls: centred, 12dp from the list's bottom (or top) edge and sides; one row 8dp apart while it fits, else stacked 12dp apart with the first pill nearest the edge. No shadow, no animation |
| `RaftComposerAttachment`, `RaftComposerAttachmentFile`, `RaftComposerAttachmentImage`, `RaftComposerAttachmentOverlay` (+ `RaftComposerAttachmentPhase`), `RaftComposerAttachmentRemove` | Composer > attachments (ComposerAttachment*) | Done | The tray's tiles: file 176 x 64dp and image 64dp, `fill-muted`, hairline edge, control radius; file title footnote 13/18 semibold on one line over a Space Mono meta line in `foreground-hint`, 16dp icon in `foreground-muted` 10dp before them. The overlay goes over any tile, file or image, as on the web (MessageInput): checking, uploading and finishing take `layer-card` at 80% with a small spinner centred over the label 4dp under it in Space Mono meta bold `foreground` ("Checking file…", "Uploading", "42%", "Finishing upload…"); a known percent runs a 4dp bar inset 6dp from the sides and bottom (2dp radius, `layer-card` track, `info` fill, 4% floor). Failed: `layer-card` at 90% inside a 1dp `danger-strong` edge with "Upload failed" centred in caption semibold `danger-strong`, and on mobile the underlined action ("Retry") under it, which a tap on the tile does. Remove: 20dp sticker-radius square overhanging the top-end corner 6dp, 1.5dp `line-strong` on `layer-card`, 12dp ✕, pressed `fill-pressed`, focus ring, disabled, 44dp touch target, always shown (no hover on a phone). The tray keeps 6dp of room above and after its tiles (`RaftComposerAttachmentRemoveOverhang`) |
| `RaftMessageActionCard`, `RaftMessageActionCardHeaderDescription`, `RaftMessageActionCardNotice`, `RaftMessageActionCardProperties` / `RaftMessageActionCardProperty`, `RaftMessageActionCardHint`, `RaftMessageActionCardScopeList` / `RaftMessageActionCardScope`, `RaftMessageActionCardActions` | MessageActionCard (+ Header, HeaderIcon / Title / Description, Status, CollapseTrigger, Content, Notice, Properties, Hint, ScopeList, Actions) | Done | At most 440dp, `layer-card`, hairline edge, container radius. Header top-aligned: the icon in a 27dp `fill-muted` tile (`zap` by default), the title in subhead bold with an optional line under it (what happened, led by an ink check on a done card, or why the reader can't act; visible while folded), the status word led by its tone's 9dp pixel, the 27dp chevron toggle (only with `onExpandedChange`). The content folds (200ms); a folded card closes 12dp under its header. Notice: `fill-muted` block, never a warning colour. Properties switch on their own width (the card's content): label over value below 384dp (no value minimum or padding, 10dp between pairs), a 88..160dp label column from 384dp, so a card at its 440dp cap has two columns (`twoColumns` overrides). Hint: the agent's reason as a 2dp `line-seam` quote under its label. Permissions scroll past 164dp. Actions bottom-right, 8dp apart, wrapping, an optional note on their left; the confirm is a default (ink-outline) `RaftButton`, never pink |
| `RaftMessageImageGallery`, `RaftMessageImageFrame` (+ `buildRaftMessageImageGalleryRows`, `raftMessageImageSingleSize`) | MessageImageGallery (+ Item, Overlay, Preview; `message-image-gallery-rows`) | Done | The inline images of a message within `min(360dp, available)`: a lone image keeps its proportions (at most 416 x 288dp, 176dp at 4 / 3 when its size is unknown) with the container radius; otherwise rows of one or two (128dp, 144dp at a full 360dp) and of three (two across, 112 / 128dp), 4dp apart, control radius. Every cell: `fill-muted`, 1dp `line-hairline`, cover or contain fit; press dims 5%, focus inset ring; `pending` (an image still sending) dims to 70% under an `md` inverse spinner. The hover download action is not drawn (touch). The host draws the image and its failed state; screenshot `message_image_gallery` |
| `RaftMessageReaction`, `RaftMessageReactionAdd`, `RaftMessageSavedIndicator` | MessageReactionChip (+ Glyph, Count), MessageReactionAddButton, MessageItemSavedIndicator | Done | Reaction chip: 24dp, `rounded-chip`, 7dp each side, a 16dp glyph slot (the app's emoji sprite) 4dp before the count in Space Mono `mono-meta` bold tabular ink; rest `fill-muted`, yours the solid `attention-soft`, failed `warning-soft`; press or mouse hover `overlay-medium`; focus ring; the count bumps for 180ms when it changes (`reactionCountKeyframes`); count 0 shows the emoji alone. With `onClick` a toggle (on while yours); without, it only draws and the host owns tap and long press. Add chip: 24dp, 6dp each side of a 16dp `plus` in `foreground-muted` on `fill-muted`, named. Saved marker: 22dp, `rounded-chip`, `fill-muted`, a filled 14dp bookmark in ink before `caption` semibold `foreground-muted`; screenshot `message_reaction` |
| `RaftMessageReplies`, `RaftMessageRepliesItem`, `RaftMessageRepliesItemTime`, `RaftMessageRepliesDefaults` | MessageReplies (+ Summary, SummaryItem, Item, ItemSender / Content / Time) | Done | The thread under a message, as the web draws it: full width up to 480dp, 8dp under the body, `fill-muted`, no border, `rounded-container`, 10 x 8dp padding, rows 4dp apart; hover `overlay-subtle`, press `overlay-medium`; one button. Summary in `footnote` bold, `foreground-muted` (ink while there are new replies): "4 replies", the new count as the pink count sticker (Badge solid accent, read as "1 new"), then "· [pencil 12dp] Draft"; no chevron. Item: avatar slot, sender `footnote` semibold `foreground-muted`, content `footnote` `foreground-hint`, time at the end in Space Mono `mono-meta` `foreground-hint`; content truncates first, then the name; screenshot `message_replies` |
| `RaftMessageQuotedPreview`, `RaftMessageQuotedPreviewAttachment`, `RaftMessageQuotedPreviewLoading`, `RaftMessageQuotedPreviewUnavailable`, `RaftMessageQuotedPreviewDefaults` | MessageQuotedPreview (+ Header, Channel, Thread, Author, AuthorName / Meta / DelegatedBy, Timestamp, Content, Text, Attachments, Loading, Unavailable) | Done | As the web composes it (QuotedMessageCard in its preview card, not raft-ui's bare seam bar): full width, `layer-card`, hairline, container radius, 12 x 10dp, press `overlay-medium`. Header wraps 6 x 4dp, top-aligned, the time at the end in Space Mono `mono-meta` `foreground-hint`: channel (14dp `hash` + name, or "@name" for a DM) in `footnote` semibold `foreground-hint`, a dot, the `xs` face, author `footnote` bold `foreground-muted` (truncates first), "(sent by …)" slot, role in `caption` hint, then "· Thread" (`message-square` 14) and "· Archived" (Badge soft muted). Text `callout` `foreground-muted`, 2 lines; attachment chips 22dp `fill-muted` `rounded-chip`, Space Mono, no edge. Loading: the web's skeleton card (34dp strip over a hairline, a 28dp block and three lines). Unavailable: the muted card (`fill-muted`) with one `callout` hint line; screenshot `message_quoted_preview` |
| `RaftMessageForwardedBundle` (+ `RaftMessageForwardedBundleFooterMode`), `RaftMessageForwardedBundleItem`, `RaftMessageForwardedBundleItemTime`, `RaftMessageForwardedBundleAttachments`, `RaftMessageForwardedBundleAttachment`, `RaftMessageForwardedBundleGallery`, `RaftMessageForwardedBundleDefaults` | MessageForwardedBundle (+ Header, HeaderLabel / Count, Source / SourceLabel, Items, Item, ItemMeta / Author / Content, Attachments, Attachment, Gallery, GalleryTile, Footer, FooterAction) | Done | At most 544dp, `layer-card`, hairline, container radius. Header 12 x 8dp, wraps: 14dp `forward` + "Forwarded" `footnote` bold `foreground-muted`, the count in Space Mono hint, the source at the end in `caption` hint (semibold, a control, underlined on press when it opens). Items 12 x 10dp: author `footnote` bold, " · " and time in Space Mono `mono-meta` tabular hint, 2dp over the text; attachments 6dp under it. The list folds by itself past `foldHeight` (8 lines of text; 160dp by default) and fades into `layer-card` over 40dp (20dp under a 60dp fold). Footer (12 x 8dp, `footnote` semibold, 12dp chevron) shows while folded, or always (`Always`, mobile's open-the-bundle entry). Chip: 22dp, `fill-muted`, `rounded-chip`, 5.5 / 6.5dp sides, 14dp `file`, name `caption` semibold, type in Space Mono hint, no edge. Gallery: rows of three (four as two and two) of `RaftMessageImageFrame`s 8dp apart; below 360dp of its own width 128dp rows and a row of three two across at 112dp, from 360dp 144dp and three across at 128dp; screenshot `message_forwarded_bundle` |
| `RaftAppRail`, `RaftAppRailItem` | AppRail (Root, Header, Nav, Item, ItemAttention, Footer; icons-only variant) | Done | The wide layout's leftmost column on `layer-frame`: 56dp plus a full-height `line-seam`; the optional header is a 56dp square (its seam lines up with list and page headers); 40dp items on the control radius, 6dp apart and 8dp from both ends, 20dp icons; selected = the sticker on the frame (`layer-frame-active`, 1.5dp `line-frame-active` edge, `sticker-edge` ring), press `fill-frame-medium`; attention = the 8dp `Status sm` pixel at the icon's corner, never on the selected item and never a count; 44dp touch target over the gaps |
| `RaftTopBar` (+ `RaftTopBarDefaults`) | The web top bar (web `AppTopBar`, `BAR_FRAME`) | Done | The wide layout's bar above the rail and the content: 48dp on `layer-frame` with a 1dp `line-seam` underneath, content in `foreground-on-frame`, the status bar inset added above it. Three regions: `leading` (9dp / 8dp padding) and `trailing` (8dp / 12dp, laid out from the end) each take half of the room the `center` leaves, so the centre stays centred on the bar; screenshot `top_bar` |
| `RaftWorkspaceButton` (+ `RaftWorkspaceButtonDefaults`) | WorkspaceButton, WorkspaceButtonName | Done | The on-frame `md` button: 36dp, 10dp each side, `text-subhead` bold, `fill-frame-pressed` on press and while `open`; the avatar slot (27dp, `sm`), 8dp to the name (at most 200dp, `NameMaxWidthBesideStrip` 120dp beside the strip, then an ellipsis), an optional mark 4dp after it, the 16dp chevron 4dp after; shrinks from the name. Home's head and the wide top bar; screenshot `workspace_button` |
| `RaftWorkspaceStrip`, `RaftWorkspaceStripItem`, `RaftWorkspaceStripEntry` (+ `RaftWorkspaceStripDefaults`) | The web top bar's workspace strip (web `WorkspaceStrip`, `WorkspaceStripButton`, `WorkspaceStripCurrentMark`, `workspaceStripLayout`) | Done | Every server's `xs` avatar in a 32dp ghost on-frame button (`fill-frame-pressed` on press), 4dp apart, 8dp after the workspace button; as many as fit the width given (`capacity`: no cap, at least 2, otherwise hidden; `onShownChange` reports it), and a current server beyond them takes the last slot after a 16dp `line-seam` hairline (`items`). The current mark is a 14 x 3dp `foreground` bar, its bottom 6dp under the button; the attention dot (`RaftAvatarAttentionLight`) sits on the avatar's top-right corner of a server with unread, and on the current one when a server with unread has no avatar in the strip. Each avatar is a button named by the server (plus the unread description), the current one selected. No drag to reorder (the web has it); screenshots `top_bar`, `workspace_strip_item` |
| `RaftSearchTrigger` (+ `RaftSearchTriggerDefaults`), `RaftKbd` | Search: SearchTrigger, SearchTriggerLabel, SearchTriggerShortcut; Kbd | Done | A button drawn as a field: 36dp, 12dp each side, the 1.5dp `line-field` edge on `layer-card`, the 18dp search glyph and the placeholder (`text-body`) in `foreground-hint`, an optional shortcut (`RaftKbd`: `text-mono-meta` hint in a 1dp `line-hairline` ring, 3dp radius) at its end; no hover, a press lays `fill-pressed`, focus ring. Width `clamp(360, 32% of the window, 520)` (`width(windowWidth)`), never more than the room given; screenshot `search_trigger` |
| `RaftMobileNavItem`, `RaftMobileNavServerPopover` (+ `RaftMobileNavServerPopoverState`), `RaftMobileNavServerPopoverItem`, `RaftMobileNavServerPopoverContent`, `RaftMobileNavServerPopoverList`, `RaftMobileNavServerPopoverGroup`, `RaftMobileNavServerPopoverRow`, `RaftMobileNavServerPopoverDestinations` / `RaftMobileNavServerPopoverDestination`, `RaftMobileNavServerPopoverSeparator` | MobileNav: MobileNavItem, MobileNavServerPopover, MobileNavServerItem, MobileNavServerPopoverContent / List / Group / Row / Destinations / Destination (wip, raft-design #712) | Done | `RaftMobileNavItem`: a phone-bar tab, 56x32 slot (9dp radius; selected = 2dp-ink brand sticker; attention dot (`Status sm`, 8dp) on the slot's corner, never on the selected tab; 11.5/14 label, bold when selected, fixed at any font scale). `RaftMobileNavServerPopoverItem`: the current-server tab, never selected: the server avatar at 22dp in the icon's place, its name truncated, the attention dot centred on the avatar's top-right corner (only "another server has unread"); `fill-pressed` on press, `fill-medium` while the popover is open. A press toggles the popover; a 500ms hold opens it (a move past 10dp cancels; a hold while open does nothing); letting a held press go over a popover row picks it (`raftMobileNavServerPopoverTarget`). One focusable button with a long-press action; `compact` is the wide rail's 48dp cell (27dp avatar). `RaftMobileNavServerPopover`: the layer, 8dp from each side and 8dp above the bar, at most 60% of the screen and below the top safe area; tap-outside and Back are the host's. `RaftMobileNavServerPopoverContent`: the popover surface (a dialog named by `paneTitle`): the list scrolls, the group and the destinations stay in view. Rows sit at the option height (`RaftOptionHeight`, the token `RaftMenuItem` uses; 36dp), a 20dp avatar or 16dp icon, the label, an optional muted mono second line (the `/slug`), a trailing check or count, bold when current; destinations are equal cells, a 20dp icon over a footnote label, with their own attention dot |
| `RaftReorderState` (+ `rememberRaftReorderState`), `Modifier.raftReorderableItem`, `Modifier.raftReorderableViewport` | touch-drag (`useHoldThenMoveTouchSensor`, foundations/states.md "Dragging on a touch screen") | Done | A keyed `Column` whose rows reorder by touch: a 500ms hold (a move past 10dp first is a scroll) lifts the row with a haptic tick (`shadow-float` card on `layer-popover`, chip radius); dragging moves it, a neighbour gives up its slot once the row's leading edge passes its middle; letting go commits a changed order once (a hold let go without moving commits nothing and clicks nothing; a tap stays the row's click). Orders that arrive while a row is lifted wait for the drop. In a `verticalScroll`, `raftReorderableViewport` scrolls the list while the lifted row is within 40dp of its edge. Screen readers get Move up / Move down actions. `RaftMobileNavServerPopoverList(reorderState = …)` uses it. A `LazyColumn` (the Home switcher) keeps its own grip-and-overlay sort |
| `RaftGlyphIcon` | — | Done | The few Lucide icons the components draw (sort: `arrow-up-down`, `arrow-down-up`; jump: `arrow-down`, `arrow-up`; thread draft: `pencil`; saved: `bookmark`) |
| `RaftText` | — | Done | Text primitive |
| Tabs drag-reorder, file list row, avatar picker, task card / board lane, toast host | | Not done | Later PRs |

**TODO before migrating a real page:**

- Dialog and Sheet open / close animation (dialogs appear and vanish at once; the sheet
  slides up but not down) and swipe-to-dismiss on the sheet. Both are needed before any
  production screen moves to these overlays.
- **Device check: popup insets.** `Popup` defaults to `usePlatformInsets = true`, and
  `RaftDropdownMenu` also subtracts `WindowInsets.safeDrawing` itself, so a menu's insets
  may be applied twice (flipped above its anchor, or open with the keyboard up, sitting too
  low or too far in). Dialogs and sheets don't have this: `RaftWindowDialog` opens them edge
  to edge (Android `decorFitsSystemWindows = false`, iOS `usePlatformInsets = false`) and
  they pad for `safeDrawing` once. On iOS their window still shrinks for the keyboard
  (`useSoftwareKeyboardInset`, except `Modal`); check a form in a `RaftDialog` with the
  keyboard up there. Verify on an Android and an iOS device.
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
    segments' width (until raft-ui 0.23: the track now shrinks and its segments scroll). `ToggleGroup` keeps `max-w-full` in 0.8.0 and is unchanged.
  - The presence light on 19px avatars (`xs`, `2xs`) is 7px with a 1px edge: `RaftAvatar`
    already drew it so (7dp, 1dp edge, 2dp overhang, 1dp cut-out).

## Differences from raft-ui and its docs

- `RaftChoiceList` always uses the touch row sizes (a one-line row is 44dp, `pointer-coarse`), and has only the single-choice variant: `multiple`, `groups`, `footer` and `fill` are not built yet (no caller).
- **The top bar's search keeps raft-ui's width.** The web's `useElasticSearch` widens the
  search field into room the side regions leave (up to 520px); `RaftSearchTrigger` keeps
  `clamp(360, 32% of the window, 520)`. The workspace strip has no drag to reorder and no
  tooltips (the web's dnd-kit strip, hover tooltips), and the button's name slot is not
  reserved at the longest server name's width, so the strip starts after the current name.
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
  A nameless one doesn't crash: it tells `LocalRaftMisuse` (silent by default; debug builds
  and tests provide one that fails).
  The dialog's close button shows a "Close" tooltip; the web dialog's close button has
  none. An icon button with `onLongClick` gives the long press to that action and opens its
  tooltip only on hover.
- **Composer attachment Retry.** A failed tile, file or image, carries the web's "Upload failed"
  overlay plus an underlined "Retry" under it, which a tap on the tile does; the web retries
  from a banner. Otherwise the tiles and their overlays follow raft-ui.
- **Jump pill focus ring on the joined edge.** A pill and its dismiss joined in a
  `RaftMessageListJumpPillGroup` square their shared corners, but the keyboard focus ring
  keeps the control radius on all four corners (the web's `shadow-focus` follows the
  squared box). Touch never shows it.
- **Small icon buttons.** raft-ui 0.21 has two icon-button sizes, `icon-md` (32dp) and
  `icon-lg` (44dp); `icon-xs` / `icon-sm` are old names of `icon-md`, and "28px is not a
  Button size". `RaftIconButtonSize` is `Md` and `Lg`. The one 28dp button, the sidebar
  section control, sizes itself (`RaftSizedIconButton`, internal) with a 44dp touch target.
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
  The disclosure lights up its hit area with `fill-pressed` while pressed or while its
  press-and-hold menu is open (States: a text button lights up its hit area); raft-ui's
  disclosure has no press look, as a mouse right-clicks it.
- **ListRow** follows the pages' AvatarListRow too: an optional mono `subtitle` line and a
  trailing slot 6dp apart. Rows don't know their siblings: pass `divider = true` from the
  second row on. A selected or disabled row hides its badge.
- **DescriptionList horizontal** switches on the list's own width (384dp), not the window's.
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
- **Windows and scrim.** `RaftDialog`, `RaftSheet`, `RaftDialogSheet`, the combobox's sheet
  and the app's `Modal` all open `RaftWindowDialog`, the only caller of Compose's `Dialog`
  (do not open a raw `Dialog` / `ModalBottomSheet` anywhere else).
  It blocks the back swipe, makes the window edge to edge with no platform dim, draws
  `RaftTheme.scrim` (`layer-backdrop`, 65% light / 60% dark), and sets the window's system bar
  icons from `RaftTheme.isDark` (`RaftDialogWindowPolicy`, the module's one platform hook:
  `androidMain` / `iosMain` / `desktopMain`; the Android host activity applies the same
  `raftSystemBarsAppearance` rule to its own bars). Dialogs appear
  without animation; the sheet slides up but closes without the slide, and has no swipe
  to dismiss or snap points (see the TODO above).
- **Choice picker on a phone.** raft-ui's Select opens its list in a popup under the
  trigger; `RaftChoicePicker` (machine and runtime pickers, Jasper's picker rule in
  #proj-mobile) opens a Select of 6 to 10 options in a bottom sheet without a search row,
  so each option's description (a quota line) has room. `RaftCombobox` keeps the anchored
  list for up to 10 values.
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
