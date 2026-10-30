# raft-ui 0.50.0 → 0.52.2: mobile follow

The Web client (slock `packages/web`, slock `d3761292`) ships `@botiverse/raft-ui` 0.52.2
with `tailwindcss` 4.3.3. Mobile's colour tokens were pinned at 0.50.0. Values are taken from
the raft-design tags `v0.50.0` to `v0.52.2` (recipes, component `.md`, `design/`).

Since #3947 mobile has mouse and trackpad hover (`raftPointerHover`, `raftIsHovered`,
`RaftStates`), so hover items apply to a mouse only. Touch never hovers.

## Verdicts

| Version | Item | Verdict | Mobile |
| --- | --- | --- | --- |
| 0.51.0 | DropdownMenuRadioItem / ContextMenuRadioItem close the menu on pick (`closeOnClick` default true, #883) | **done** | Mobile menus are closed by the row's `onClick`. The app's value menus already close on pick: the Activity filter (`SharedInboxPanel`), the task status menu (`TaskConversationHead`) and `RaftBrutalSelect`. The `RaftDropdownMenu` KDoc now says a `checked` row closes too (docs commit). |
| 0.51.0 | ModeBar stable (#882; first shipped in 0.45 from `/wip`, #867) | **change** | New `RaftModeBar`, plus `mode_bar` screenshot test, semantics test and a README row. The HUD strip uses `RaftPanelNotice`'s edges: 16dp sides, 10dp above and below, at least 40dp. Bold title, then the muted description on one wrapping `text-callout` line, as a polite live region. Done is a `hudTextAction`: underlined under a mouse only, a 15% `foreground-on-hud` veil while pressed, a 2dp HUD-ink keyboard outline and a 44dp touch target. Goldens are new. |
| 0.51.0 | Design rule "When a change takes effect" (#881, forms.md) | **n.a. (note)** | Pages are not changed here. Findings: Settings → Language & Region puts the time zone select and the 12h/24h segmented control behind one Save, though the rule says standalone choices apply at once. The translation mode segmented control is also behind Save, together with the preferred language. Channel settings save the Slack bridge select with the Info form's Save, which matches the Web today. Ask the owner before changing pages. |
| 0.52.0 | PanelSplitAction (wip, #885) | **n.a.** | Waiting until it is stable. |
| 0.52.0 | TextSelectionToolbar generic rename (#885) | **n.a.** | Mobile has no `MessageTextSelectionToolbar*` / `TextSelectionToolbar*` component. Text selection uses the platform toolbar. |
| 0.52.0 | Tree-shakable build (#886) | **n.a.** | Web bundling only. |
| 0.52.0 | Joined parts hover as one control: the hovered part gets `fill-medium`, its joined neighbour `fill-subtle` (#885, states.md) | **done** | Already in #3947. `RaftButtonBox` + `RaftJoinedButtons` (split button, the message-list jump pill and its dismiss): the hovered or pressed part gets the medium tier and its neighbour `fill-subtle`. raft-ui itself applies it only to PanelSplitAction (wip). Mobile has no other touching parts: segmented segments sit in a track and aren't joined. |
| 0.52.0 | Descriptive links take sentence case (#887, writing.md) | **n.a. (note)** | A copy rule for pages and strings, not a design-system value. |
| 0.52.0 | Entry points on an edge take a band (#887, elevation.md) | **n.a. (note)** | A layout rule for pages (e.g. the Assistant entry on the composer). No design-system component is affected. |
| 0.52.1 | PanelAction hit area reaches 44px (#889) | **n.a.** | The Web bug was its `::after` being placed inside the 1.5px border (41–42px). Mobile panel and thread header actions are 44dp boxes on touch (`ThreadHeaderActionSize = 44.dp` in `ThreadPage.kt`; `RaftIconButton` widens smaller sizes to a 44dp target with `raftTouchTarget`). There is no overhang geometry to fix. |
| 0.52.2 | Badge rendered as a button keeps a 44px touch target (#892) | **change** | `RaftBadge` takes `onClick` / `onClickLabel` / `copyText`. A badge button gets a 44dp touch target (44 tall, at least 44 wide) with no change to its look. It also gets the medium tier on press and mouse hover (`sticker-medium` solid, `fill-medium` soft and outline, as `badge.tsx`) and the focus ring. The runtime-usage chip (`ComputerRuntimeUsageChip`) is the only tappable badge in the app. Its tap area used to be the 22dp chip; it now uses this. `RaftTaskStatusBadge` (the status editor trigger) already had a 44dp target. |
| — | Colour tokens 0.50.0 → 0.52.2 | **change (no colour change)** | raft-ui `styles.css`, `foundation.css` and `themes.css` are byte-identical in 0.50.0 and 0.52.2. The Web `index.css` is unchanged at slock `d3761292`. Only the manifest (paths, origins, `slockCommit`) and the generated headers move. `gen_tokens.py --check` passes against the existing Chromium oracle, so the oracle was not regenerated (nothing to repaint). |

## Differences and follow-ups

- **Toast action drift (not in this range).** raft-ui's Toast uses the same `hudTextAction` as ModeBar (since 0.45, #867): a pressed veil and underline on hover only. `RaftToast`'s action still underlines while pressed and has no veil. `RaftHudTextAction` (internal, in `RaftModeBar.kt`) is ready to share. Switching the toast over changes toast pressed goldens, so it should be its own PR.
- **ModeBar side padding** is 16dp at every width, the same as `RaftPanelNotice`. raft-ui uses 20px from `md` (a window media query) and 16px below. Mobile keeps the two strips one size.
- **ModeBar text** is 14/20 (`text-callout` on a phone). The web desktop is 14/22.
- The `raft-ui-follow` skill still says mobile is touch-only. Since #3947, hover items apply to a mouse or trackpad.

## Goldens

- New: `compose/designsystem/screenshots/mode_bar_light.png`, `mode_bar_dark.png`. Record through CI (`test.yml -f record_screenshots=true`).
- No existing golden should change. Tokens are identical, a display `RaftBadge` draws exactly as before, and the runtime chip at rest is unchanged (only its press and hover state is new).
