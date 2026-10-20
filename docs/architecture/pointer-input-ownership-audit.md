# Pointer-input ownership audit

This census is bound to mobile `main@e29fb6f587743827fae32111319ad6a899eaf0be`.
That tree contains 38 `commonMain` raw or ownership-wrapper `pointerInput` callsites. Kuikly compares a
`SuspendPointerInputElement` only by keys; an equal element reuses its node without updating the
handler. Consequently, an old handler also retains old `rememberUpdatedState` holders, anchors,
mutable presentation state, and composition coroutine scopes.

## Required ownership contract

- A lifecycle-sensitive handler captures one `RaftGestureOwner`, not composition-local authority.
- The pointer-input key contains the owner's opaque composition token. Replacing a composition with
  the same semantic value therefore cannot leave an equal element attached to the old handler.
- Ordinary recomposition updates the owner's callback slot without cancelling an in-flight gesture.
- DOWN (or drag start) creates one immutable `RaftGestureSession`. Callback changes apply to the
  next gesture, not halfway through the current gesture.
- A newer gesture or owner disposal invalidates the old session. Terminal callbacks and visual-state
  writes use `currentCallbacksOrNull()`, so removed rows produce neither dispatch nor stuck press UI.
- While Compose retains the same `remember` slot, the same owner receives the current callback slot;
  a structural branch change alone is not proof of disposal. If the old composition is actually
  disposed and the same semantic identity later mounts again, it gets a different opaque token and the
  old owner cannot resurrect. This composition-local API does not preserve an in-flight gesture across
  actual disposal. PR #1960's Thread owner store and lease provide that stronger continuity contract
  for the two full-row handlers that explicitly need it.

## Census

`Migrated` means this change applies the owner/session contract and the source-constraint gate.
`Thread landed owner` belongs to the landed active-lease implementation and is intentionally not
duplicated here. `Owner-stable` means the raw handler is attached to one non-recycled root or carries
all mutable authority in restart keys; it is audited but not migrated in Phase A. `Phase B` is a real
same-key replacement hazard outside the repeated/lazy surfaces selected for this phase.

| # | Source anchor on audited base | Classification | Reason |
|---:|---|---|---|
| 1 | `BasicWidget.kt:322` zoomable image | Phase B | Same-source replacement can retain old scale/pan/viewport state. Static preview surface, not recycled. |
| 2 | `BasicWidget.kt:602` outside-focus host | Owner-stable | One registry-owned app root; registry is the pointer key and owns the focus-clear epoch. |
| 3 | `BasicWidget.kt:945` scroll focus observer | Owner-stable | One scroll-root observer per screen; registry owns the focus-clear epoch. |
| 4 | `BasicWidget.kt:1041` touch listener | Migrated | Thread viewport callback holder could outlive a same-semantic root replacement. |
| 5 | `BasicWidget.kt:1231` Button | Migrated | Shared by repeated rows; old enabled/callback/pressed holders were retained. |
| 6 | `MembersPage.kt:5976` tab reorder | Owner-stable | Tab data, tab identity, and callback are all restart keys; callback changes cancel by design. |
| 7 | `SettingsHomePage.kt:11173` settings copy row | Migrated | Repeated row captured action/copy authority outside its keys. |
| 8 | `SettingsHomePage.kt:19056` saved card | Migrated | Lazy result captured open/unsave/menu authority, anchor, and gesture generation. |
| 9 | `SettingsHomePage.kt:19374` search entity | Migrated | Repeated result captured open/menu authority and anchor. |
| 10 | `SharedInboxPanel.kt:1593` Activity row | Migrated | Lazy row must freeze the DOWN owner while rejecting a recycled row. |
| 11 | `SharedTasksPage.kt:1249` task card | Migrated | Lazy card captured open/menu callbacks and anchor. |
| 12 | `RaftAppShell.kt:220` drawer-open observer | Owner-stable | Single shell-root detector; no lazy/recycled node. |
| 13 | `RaftBrutalDropdownMenu.kt:236` dropdown row | Migrated | Indexed row replacement retained pressed state and selection callback. |
| 14 | `RaftEnvironmentIndicator.kt:133` badge drag | Phase B | Static diagnostic overlay, but equal bounds can retain old drag state. |
| 15 | `RaftFilePreview.kt:2304` editor draw surface | Phase B | Static editor; tool keys are complete but editor state remains composition-local. |
| 16 | `RaftFilePreview.kt:2334` editor gesture surface | Phase B | Static editor; same-key replacement can retain history/canvas state. |
| 17 | `RaftFilePreview.kt:2541` text annotation drag | Phase B | Annotation identity is keyed, but history/size maps remain composition-local. |
| 18 | `RaftFilePreview.kt:4271` image gallery swipe | Phase B | Static gallery captures payload callback and drag presentation state. |
| 19 | `RaftFilePreview.kt:4540` video seek | Owner-stable | Total time, callback, and measured width are all restart keys. |
| 20 | `RaftFloatingOverlay.kt:220` dismiss barrier | Phase B | Static overlay captures an updated-state holder not represented by the active key. |
| 21 | `RaftForegroundBannerHost.kt:246` banner swipe | Migrated | Lifecycle replacement retained dismiss callback and swipe state. |
| 22 | `RaftHomeComponents.kt:3843` drawer swipe | Owner-stable | Dismiss callback itself is a restart key on one drawer instance. |
| 23 | `RaftHomeComponents.kt:4459` server-order drag | Phase B | Dialog-level stable container, but callbacks and drag state are not all keyed. |
| 24 | `RaftHomeComponents.kt:4806` sidebar action | Migrated | Lazy sidebar item retained pressed state and click holder. |
| 25 | `RaftHomeComponents.kt:4949` Channel row drag | Migrated | Lazy row retained drag callbacks and presentation state. |
| 26 | `RaftHomeComponents.kt:4964` Channel drag handle | Migrated | Same row identity as the row drag, but a separate owner for this independent behavior role. |
| 27 | `RaftHomeComponents.kt:4982` Channel tap/long press | Migrated | Lazy row retained click/menu callbacks, press state, and live anchor. |
| 28 | `RaftHomeComponents.kt:5003` Channel tap | Migrated | Lazy row retained click and press callbacks. |
| 29 | `RaftHomeComponents.kt:5280` channel-order drag | Phase B | Dialog-level stable container, but callbacks and drag state are not all keyed. |
| 30 | `RaftHomeComponents.kt:5500` DM tap/long press | Migrated | Lazy row retained click/menu callbacks, press state, and live anchor. |
| 31 | `RaftHomeComponents.kt:5521` DM tap | Migrated | Lazy row retained click and press callbacks. |
| 32 | `RaftModalComponents.kt:71` content barrier | Owner-stable | Pure pointer observation; captures no callback, state, anchor, or scope. |
| 33 | `RaftModalComponents.kt:94` dismiss barrier | Phase B | Enabled is keyed, but replacement can retain an old dismiss callback. The landed keyboard helper is runtime-ineffective and is not a pattern for this phase. |
| 34 | `ThreadPage.kt:10195` image-preview swipe | Phase B | Static viewer uses updated-state holders that can be retained by equal index/size keys. |
| 35 | `ThreadPage.kt:20393` inline selection | Migrated | Lazy message selection captured controller, gate, anchor, logger, and message authority. |
| 36 | `ThreadPage.kt:21765` reaction chip | Migrated | Lazy reaction captured toggle/details callbacks and anchor. |
| 37 | `ThreadPage.kt:22053` shared message gesture wrapper | Thread landed owner | The landed route/semantic owner and active composition lease atomically govern movement and message actions. |
| 38 | `ThreadPage.kt:24408` message avatar | Migrated | Lazy message avatar retained open/long-press callbacks. |

Phase A therefore migrates 19 callsites, leaves 1 wrapper atomically owned by the landed Thread owner, records 7
owner-stable callsites, and defers 11 non-recycled hazards to Phase B. The gate protects every
Phase-A region against reintroducing raw `pointerInput`; its self-test mutates every protected region
back to raw input and separately removes session creation/current-generation admission.

Hands `7ba7240c` is not evidence for this family. On VC1000160, background-to-foreground immediate
clicks do not reproduce, and the behavior had already recovered in an earlier version. It is closed
as “no longer reproducible”, not attributed to task #963 or this ownership migration.
