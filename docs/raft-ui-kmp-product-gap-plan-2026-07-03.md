# Raft UI / KMP Product Adoption Gap Plan

Date: 2026-07-03
Owner: Android-Developer-3
Task: #visual-testing task #276

## Scope

This plan maps Slock product surfaces to the reusable KMP Raft UI/component library. It deliberately excludes visual-test-only fixture composition from "adoption"; a product page only counts as adopted when its real route uses a product-safe component from `compose/shared/src/commonMain/kotlin/build/raft/app/components/` or an established shared product component such as `SlockTopTabBar` / `SlockUnifiedDialog`.

Companion audit: `docs/raft-ui-kmp-component-library-audit-2026-07-03.md`.

Visual-priority follow-up: `docs/raft-ui-kmp-visual-similarity-priority-2026-07-03.md` reorders the implementation sequence by actual React-vs-Android image similarity. The source scan below remains valid, but the visual pass moves selected row/surface tokens, progress bars, and form/input/select chrome ahead of broad segmented-control adoption.

## Current React Raft UI Usage

From the current `third_party/slock-source` checkout, production React imports `raft-ui` directly in these areas:

- App providers: `ThemeProvider`, `TooltipProvider` in `packages/web/src/main.tsx`.
- Agent workspace: segmented controls in `packages/web/src/components/agent/AgentWorkspace.tsx` and `ExternalSetupTabSegmentedControl.tsx`.
- Create channel dialog: segmented control in `packages/web/src/components/channel/CreateChannelDialog.tsx`.
- Settings: segmented controls in `SettingsSegmentedControls.tsx` and `SettingsPanel.tsx`.
- Tasks: segmented controls in `TaskBoard.tsx`, `TaskFilterSegmentedControl.tsx`, and `TasksPanel.tsx`.
- Inbox/thread inbox: segmented controls in `ThreadsInbox.tsx`.

The React `raft-ui` package exports many more primitives, but this checkout's product call sites are dominated by segmented controls. KMP should therefore prioritize matching segmented controls and shared chrome first; broader package parity can remain visual-library/WIP until product uses those exports.

## Gap List By Area

| Area | React raft-ui / wrapper in use | KMP implementation sites | Current KMP state | Recommended action |
|---|---|---|---|---|
| Home | No direct React `raft-ui` import found in Home product sources in this checkout; Home still uses shared cards, badges, buttons, empty/loading rows | `SlockHomeComponents.kt` | Uses `SlockBrutalActionButton` and local row/card composition | Keep page-specific for now. Extract only repeated row/card patterns after Home and Inbox share the same contract. |
| Thread | React thread surfaces mostly use local UI wrappers and menus, not direct `raft-ui` except inbox filtering | `ThreadPage.kt`, `ThreadComposerPage.kt`, `SlockFilePreview.kt`, `ThreadFilesContent.kt` | Uses `SlockUnifiedDialog`, `SlockTopTabBar`, `SlockBrutalActionButton`, `SlockBrutalIconButton`, local context-menu/composer chrome | Reuse existing product-safe components. Do not adopt static `SlockRaftUiMenuRow`/`PopupSurface` for message menu yet; first add controlled overlay/menu APIs if needed. |
| Thread inbox / Inbox | React `ThreadsInbox.tsx` uses `SegmentedControl` for filters | `SharedInboxPanel.kt` | Uses `SegmentedControl` directly | Already aligned enough. Add a small wrapper only if Settings/Tasks/Inbox need the same API naming. |
| Members | React product usage not direct `raft-ui`; existing design asks for reusable section headers and tabs | `MembersPage.kt` | Uses `SlockTopPageHeader`, `SlockCollapsibleSectionHeader`, `SlockTopTabBar`, `SlockBrutalActionButton`, `SlockUnifiedDialog`, local detail/profile cards | Adopt `SlockSectionHeader`/`SlockSectionEyebrow` consistently for section labels. Keep profile/detail cards page-specific until repeated across Settings/Agent surfaces. |
| Settings | React settings uses `SegmentedControl`, `SegmentedControlCount`, local wrappers in `SettingsSegmentedControls.tsx` | `SettingsHomePage.kt`, `SettingsBillingPage.kt`, `SettingsComputersPage.kt`, `SettingsAccountPage.kt`, `SettingsStoragePage.kt` | Many direct `SegmentedControl` calls; buttons/dialogs use existing KMP primitives | Concrete adoption gap: consolidate repeated settings segmented controls behind KMP wrappers equivalent to React settings wrappers. |
| Tasks | React tasks use `SegmentedControl` and counts for status filters | `SharedTasksPage.kt` | Has `SharedTasksSegmentedControl` wrapping direct `SegmentedControl` | Mostly aligned. Consider moving generic counted segmented filter wrapper out of tasks if Inbox/Settings reuse it. |
| Composer | React composer uses local message-input primitives; no direct `raft-ui` product import in checked files | `ThreadComposerPage.kt`, `ThreadPage.kt` | Uses `SlockBrutalActionButton`, `SlockBrutalIconButton`, `SlockSelectionPopover`, `SlockAttachmentPreviewCard`, local composer state | Keep composer-specific. Only extract attachment preview and suggestion popover when another page needs the same behavior. |
| Files | React files/workspace surfaces use local wrappers plus segmented controls in Agent workspace | `ThreadFilesContent.kt`, `SlockFilePreview.kt`, `SettingsComputersPage.kt` | File rows/actions are local; action buttons are shared | Extract only reusable file rows/actions if Thread files and Agent workspace converge. Do not force Raft UI package wrappers yet. |
| Dialogs / overlays | React package exports popover, dropdown, context menu, tooltip, combobox, select; product usage is mixed local wrappers plus providers | `SlockModalComponents.kt`, `SlockFilterPopover.kt`, `SlockBrutalSelect.kt`, `SlockRaftUiMenuPrimitives.kt`, Thread/Members/Settings local overlays | Product-ready KMP dialog exists; select/popover/menu pieces exist; visual `SlockRaftUiPopupSurface` is static | Concrete gap: normalize menu/popover/select APIs before broad adoption. Avoid replacing product overlay logic with visual-only shell components. |
| Visual fixtures | React/KMP library cases are visual scaffolding | `RaftUiLibraryVisualFixtures.kt`, `VisualFixturePages.kt`, Android visual test | 22 KMP library cases, including 4 explicit WIP cases | Keep separate from product adoption accounting. WIP cases should become product tasks only when a real page needs that component. |

## Concrete Implementation Subtasks

Create implementation work only for gaps where a real product route should adopt the component library:

1. `Settings segmented controls`: extract a KMP settings segmented-control wrapper that matches React `SettingsSegmentedControls.tsx` patterns, then replace repeated `SettingsHomePage.kt` direct calls where the same count/disabled/label behavior appears.
2. `Counted segmented filters`: review `SharedTasksSegmentedControl`, `SharedInboxPanel` filters, and Settings connected-app tabs; extract one generic counted segmented-filter wrapper only if it removes duplicated options/count/chrome code without reducing page-specific semantics.
3. `Menu/popover/select API polish`: promote `SlockSelectionPopover`, `SlockMenuItemRow`, and `SlockSelectTrigger` into a documented controlled overlay contract. Keep `SlockRaftUiPopupSurface` and `SlockRaftUiMenuRow` fixture-only until this exists.
4. `Form field/input-group adoption`: use `SlockFormField` consistently for Settings/Members/Create-Agent style labels, hints, and errors; do not use `SlockRaftUiInputGroup` in product until it accepts editable input and callbacks.
5. `Avatar/package wrapper gap`: leave `SlockRaftUiAvatar` fixture-only for now; product avatar adoption should continue through `SlockAvatar` and `SlockAvatarListRow` because those accept real avatar wire values and deleted/fallback semantics.

## Do Not Count As Product Gaps

- `library.raft-ui.wip-*` visual cases. They are intentionally fixture/WIP compositions.
- Static `SlockRaftUiTabs`, `SlockRaftUiSegmentedControl`, and `SlockRaftUiButton` wrappers missing callbacks. Their existence is not a product migration requirement.
- React package exports that product React does not currently use. KMP can keep visual coverage for them, but product adoption should be driven by real Slock screens.

## Suggested Task Split

The following tasks are concrete enough to implement without re-auditing and have been created in `#visual-testing`. After the visual-similarity pass, execute them in the revised order documented in `docs/raft-ui-kmp-visual-similarity-priority-2026-07-03.md` rather than treating this original split as strict priority order:

- Phase 1: task #277, Settings counted segmented controls KMP wrapper/adoption.
- Phase 1: task #278, menu/popover/select controlled API polish and docs.
- Phase 2: task #279, form field/input-group adoption in Settings and Members after the wrapper API is stable.
- Phase 2: task #280, product avatar row/component consolidation around `SlockAvatarListRow`, not `SlockRaftUiAvatar`.

The split is intentionally parallel by surface/API family. It avoids a serial "migrate everything to Raft UI" task, which would mix fixture-only components with product-safe components and create churn.

## Validation Notes

- Static source scan completed for KMP `components/`, visual fixture pages, Android visual test case IDs, React product `raft-ui` imports, and React `raft-ui` package exports.
- `node scripts/slock-visual-testing.mjs validate --manifest shared` is blocked in this checkout because `third_party/slock-source/packages/visual-testing/src/cli.mjs` is absent. Do not treat this as a component audit failure; it is a checkout/tooling integrity issue to resolve before the next visual publish.
