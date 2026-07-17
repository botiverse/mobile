# Raft UI / KMP Visual Similarity Priority

Date: 2026-07-03
Owner: Android-Developer-3
Task: #visual-testing task #276

## Scope

This note reorders the Raft UI / KMP adoption plan by actual React-vs-Android visual similarity instead of by source-scan coverage alone.

Input report:

- `visual-testing-results/diff/react__android.json`
- `visual-testing-results/report.html`
- local site: `artifacts/visual-testing-site/latest/report.html`

Current report summary:

- 82 compared React/Android cases
- 78 different
- 3 basic-pass
- 1 missing

The ranking below uses `pixelMismatchRatio` first, then manual side-by-side image review. Cases that are invalid providers, fixture-only, or mostly content-data mismatches are called out separately so they do not distort product component priority.

## Lowest Similarity Components

### High-Signal UI Primitives

| Rank | Case | Pixel mismatch | RGB similarity | Readout | Action |
|---:|---|---:|---:|---|---|
| 1 | `components.ui.surface-list-item.states` | 45.65% | 72.9% | Selected row background is much darker on Android than React; selected text becomes low contrast. | Fix selected surface token/chrome before broad row adoption. |
| 2 | `components.ui.avatar-list-row.states` | 41.90% | 71.4% | Same selected-state darkness issue; avatar source/data differs, but the dominant problem is selected row token and row typography/spacing. | Fold into avatar row consolidation and selected-surface token fix. |
| 3 | `components.ui.progress-bar.states` | 36.52% | 75.6% | Android bars are too long for the same percentages and omit/right-hide the percentage labels. | Add a focused progress-bar parity task before using it in usage meters. |
| 4 | `components.ui.textarea.states` | 42.53% | 87.8% | Difference is mostly text wrapping, height, padding, and font metrics rather than gross layout. | Include in form-field/input adoption; tune textarea min-height, padding, and line-height. |
| 5 | `components.ui.slug-input.states` | 25.39% | 88.1% | Height/shadow/disabled border and prefix-cell proportions differ. | Include in form-field/input adoption. |
| 6 | `components.ui.button.states` | 23.29% | 87.9% | Android buttons are slightly taller/wider; disabled action state should not be resolved by a generic gray foreground or by dimming the active fill. The accepted KMP direction is composer-style explicit disabled tokens: disabled surface, border, shadow, and foreground are configured separately. | Keep `SlockBrutalActionButton` on explicit disabled action tokens; do not regress to active-color alpha or generic gray disabled foreground. |
| 7 | `components.ui.select.states` | 22.09% | 87.2% | Trigger height/shadow/disabled chrome differ; likely same root as button/input trigger tokens. | Keep in controlled select/menu task. |
| 8 | `components.ui.selection-popover.states` | 20.04% | 86.5% | Popover is close enough structurally; remaining differences are trigger/input/list-row selected token. | Fix after selected row/input token work. |
| 9 | `components.ui.form-field.states` | 17.53% | 90.7% | Mostly spacing and helper/error text metrics. | Lower priority than row/progress/input chrome. |
| 10 | `components.ui.segmented-control.states` | 11.96% | 93.2% | Already comparatively close; still worth Settings adoption, but not the largest visual gap. | Keep task #277, but do not treat it as the only first priority. |

Excluded from component priority:

- `components.ui.skeleton.states` has 61.93% mismatch, but side-by-side review shows the Android capture is vertically offset/cropped relative to React. Re-capture or fixture correction should happen before using it as style evidence.
- `components.navigation.tabbar.states` is low mismatch at 4.55%; no urgent component-style work from this report.

### Disabled Action Button Decision Update

2026-07-05 follow-up: disabled submit/action buttons should follow the Thread
Composer empty-send implementation model rather than the old
`activeColor.copy(alpha = 0.50f)` pattern or a generic gray foreground. The KMP
visual contract is:

- Disabled action surface, border, shadow, and foreground are explicit tokens.
- Ordinary unavailable submit actions use the explicit disabled-action surface
  plus white disabled foreground.
- Destructive unavailable actions use a destructive disabled-action surface, not
  a red active-color alpha.
- Busy/in-flight and brand/social states may preserve active or brand visuals
  because they communicate progress or provider identity rather than ordinary
  unavailability.

This means visual-testing reviews should treat the explicit-token KMP fix as the
appropriate direction even if the web/React source expresses comparable states as
Tailwind `disabled:opacity-*` modifiers.

## Lowest Similarity Product Components

| Rank | Case | Pixel mismatch | RGB similarity | Readout | Root cause |
|---:|---|---:|---:|---|---|
| 1 | `components.channel.members.add-panel` | 71.97% | 71.9% | Real panel exists; modal is lower/larger on Android, input/list rows differ, member data differs. | Shared modal + form field + avatar/list row tokens. |
| 2 | `components.home.create-channel.dialog` | 55.58% | 77.2% | Structure is close, but modal placement/size, field heights, member rows, and fixture data differ. | Unified dialog + form field + selected row/avatar row. |
| 3 | `components.home.search.channel-dropdown` | 60.42% | 88.5% | Search data/content differs heavily, but dropdown/list row chrome and filter chips also differ. | Selection popover/list row/filter chip tokens. |
| 4 | `components.thread.composer.channel-suggestions` | 39.78% | 80.2% | Suggestion popup is above composer on both sides; Android vertical placement and row/input chrome differ. | Selection popover/list row/composer chrome. |
| 5 | `components.thread.composer.file-previews` | 27.95% | 84.2% | Android includes upload progress/error states not present in React baseline, plus preview sizing differences. | Fixture state alignment first; then file preview card tokens. |
| 6 | `components.settings.connected-apps.page` | 36.94% | 82.9% | Page uses repeated surface/list rows; row token fixes should improve this without a page-only rewrite. | Surface/list item selected and disabled states. |
| 7 | `components.settings.administration.*` | 31.46% | 85.5% | Repeated same page shell/content pattern across tabs. | Counted segmented controls plus section/list row tokens. |

Excluded or low-confidence product cases:

- `components.channel.members.panel` and `.joint-channel` were retired on 2026-07-18 because the modal has no production caller after channel members moved to the standalone `compose_thread_members` page. Keep `components.channel.members.add-panel`, which renders the production Add Members dialog.
- `components.channel.settings.panel` is invalid for this priority pass. React captured composer chrome while Android captured the edit-channel modal.
- `components.members.agent-lifecycle-actions` has 96.69% pixel mismatch, but the side-by-side shows a scroll/vertical-position mismatch and different active tab/dropdown placement. It should be stabilized before using it to drive component token work.
- Full `screens.*` cases are useful for final acceptance, but too broad for first component-style prioritization.

## Revised Implementation Order

1. **Selected row and surface token parity**
   - Targets: `SlockSurfaceListItem`, `SlockAvatarListRow`, rows inside `SlockSelectionPopover`, member add/list rows.
   - Evidence: `surface-list-item` 45.65% mismatch, `avatar-list-row` 41.90%, plus product panel drift.
   - Existing task fit: task #283.

2. **Progress bar parity**
   - Targets: `SlockProgressBar` and pages that hand-roll usage/progress meters.
   - Evidence: `components.ui.progress-bar.states` 36.52% mismatch; Android appears to render incorrect filled width and omits percentage text.
   - Existing task fit: task #284.

3. **Form/input/textarea/select trigger chrome**
   - Targets: `SlockFormField`, `SlockBrutalInputField`, `SlockBrutalTextarea`, `SlockSlugInput`, `SlockSelectTrigger`.
   - Evidence: textarea 42.53%, slug input 25.39%, select 22.09%, form field 17.53%.
   - Existing task fit: task #279 plus the select part of #278.

4. **Controlled popover/menu/list-row adoption**
   - Targets: `SlockSelectionPopover`, `SlockMenuItemRow`, suggestion pickers, search dropdowns.
   - Evidence: product dropdown/suggestion cases are low similarity, but much of the fix comes from row/input tokens first.
   - Existing task fit: task #278.

5. **Settings counted segmented controls**
   - Targets: Settings wrappers and repeated counted filters.
   - Evidence: `components.ui.segmented-control.states` is already closer than most primitives at 11.96% mismatch, but Settings product uses this repeatedly.
   - Existing task fit: task #277 remains valid, but it is no longer the top visual-similarity fix.

6. **Provider/case cleanup before ranking**
   - Targets: invalid or unstable captures such as `channel.settings.panel`, `skeleton`, and `members.agent-lifecycle-actions`.
   - Reason: these can create false "largest diff" signals.
   - Existing task fit: visual-testing maintenance, not product component adoption.

## Recommended Task Board Adjustment

Keep the current tasks, but reorder execution by visual evidence:

- Start with task #283 for selected surface/list row visual parity across surface, avatar, and popover rows.
- Run task #284 for progress-bar parity because it is not covered by #277-#280 and has a high-confidence primitive mismatch.
- Then run task #279 for form/input/textarea/slug-input parity.
- Then task #278 for controlled menu/popover/select API polish.
- Run task #277 after the higher-mismatch token work unless Settings segmented controls become a blocking product issue.

## Validation Performed

- Parsed `visual-testing-results/diff/react__android.json`.
- Ranked all compared cases by `pixelMismatchRatio`.
- Separately ranked `components.ui.*`, `components.navigation.*`, and product `components.*`.
- Manually reviewed side-by-side images for the highest-mismatch component and product cases.

No new visual capture or publish was run for this note. This is an analysis pass over the latest local visual report.
