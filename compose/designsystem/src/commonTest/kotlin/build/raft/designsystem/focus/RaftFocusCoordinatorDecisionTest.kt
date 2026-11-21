package build.raft.designsystem.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Table-driven contract tests for [RaftFocusCoordinator] covering all five rules of raft-design
 * "Moving focus" (#1118 / #1119, audit step 4 / #triage:#465).
 */
class RaftFocusCoordinatorDecisionTest {
    @Test
    fun rule1ModalStackPopoversAndOtherTextFieldDecisionMatrix() {
        data class Case(
            val label: String,
            val openModal: Boolean = false,
            val nestedModal: Boolean = false,
            val openPopover: Boolean = false,
            val otherTextField: Boolean = false,
            val targetRegion: RaftFocusRegion?,
            val targetIsBottomModal: Boolean = false,
            val targetIsTopModal: Boolean = false,
            val source: RaftFocusRequestSource,
            val expected: RaftFocusDecision,
        )

        val cases =
            listOf(
                Case(
                    label = "system request outside open modal is dropped",
                    openModal = true,
                    targetRegion = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("open"),
                    expected = RaftFocusDecision.DropOpenSurface,
                ),
                Case(
                    label = "user request outside open modal is dropped behind modal",
                    openModal = true,
                    targetRegion = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.User("mention"),
                    expected = RaftFocusDecision.DropOpenSurface,
                ),
                Case(
                    label = "request inside the topmost modal is allowed",
                    openModal = true,
                    targetRegion = RaftFocusRegion.Modal,
                    targetIsTopModal = true,
                    source = RaftFocusRequestSource.System("modal_open"),
                    expected = RaftFocusDecision.Allow,
                ),
                Case(
                    label = "buried modal below a nested modal cannot steal focus from the top modal",
                    openModal = true,
                    nestedModal = true,
                    targetRegion = RaftFocusRegion.Modal,
                    targetIsBottomModal = true,
                    source = RaftFocusRequestSource.System("buried_modal"),
                    expected = RaftFocusDecision.DropOpenSurface,
                ),
                Case(
                    label = "topmost nested modal is allowed",
                    openModal = true,
                    nestedModal = true,
                    targetRegion = RaftFocusRegion.Modal,
                    targetIsTopModal = true,
                    source = RaftFocusRequestSource.System("top_modal"),
                    expected = RaftFocusDecision.Allow,
                ),
                Case(
                    label = "non-modal menu/popover drops system requests (Jasper ruling)",
                    openPopover = true,
                    targetRegion = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("open"),
                    expected = RaftFocusDecision.DropOpenSurface,
                ),
                Case(
                    label = "non-modal menu/popover allows explicit user requests (e.g. Quote/Mention from closing menu)",
                    openPopover = true,
                    targetRegion = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.User("quote"),
                    expected = RaftFocusDecision.Allow,
                ),
                Case(
                    label = "system request is dropped while another text field holds focus",
                    otherTextField = true,
                    targetRegion = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("open"),
                    expected = RaftFocusDecision.DropOtherTextField,
                ),
                Case(
                    label = "user-initiated conversation open still yields to another focused text field",
                    otherTextField = true,
                    targetRegion = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("conversation_open", allowCrossRegionFromNonText = true),
                    expected = RaftFocusDecision.DropOtherTextField,
                ),
                Case(
                    label = "explicit user request may move focus from another text field",
                    otherTextField = true,
                    targetRegion = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.User("explicit_click"),
                    expected = RaftFocusDecision.Allow,
                ),
                Case(
                    label = "system request allowed when nothing blocks",
                    targetRegion = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("open"),
                    expected = RaftFocusDecision.Allow,
                ),
            )

        cases.forEach { case ->
            val coordinator = RaftFocusCoordinator()
            val bottomModal = Any()
            val topModal = Any()
            if (case.openModal) coordinator.acquireModal(bottomModal)
            if (case.nestedModal) coordinator.acquireModal(topModal)
            if (case.openPopover) coordinator.acquireOpenSurface()
            if (case.otherTextField) {
                coordinator.setTextFieldFocused(owner = "search", focused = true, region = RaftFocusRegion.SupportingPane)
            }
            val token =
                when {
                    case.targetIsTopModal -> if (case.nestedModal) topModal else bottomModal
                    case.targetIsBottomModal -> bottomModal
                    else -> null
                }
            val ticket =
                coordinator.request(
                    targetKey = "composer",
                    region = case.targetRegion,
                    source = case.source,
                    modalToken = token,
                )
            assertEquals(case.expected, coordinator.evaluate(ticket), case.label)
        }
    }

    @Test
    fun rule2OtherRegionNonTextFocusBlocksPassiveSystemButAllowsUserAndNavigatedOpens() {
        val nonTextRegions =
            listOf(
                RaftFocusRegion.TopBar,
                RaftFocusRegion.Navigation,
                RaftFocusRegion.ListPane,
                RaftFocusRegion.SupportingPane,
            )
        nonTextRegions.forEach { region ->
            val coordinator = RaftFocusCoordinator()
            coordinator.setRegionFocused(owner = "pane-$region", region = region, focused = true)

            val passiveTicket =
                coordinator.request(
                    targetKey = "composer",
                    region = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("default_landing"),
                )
            assertEquals(
                RaftFocusDecision.DropOtherRegionFocus,
                coordinator.evaluate(passiveTicket),
                "non-text focus in $region must block passive system focus in Conversation (S6)",
            )

            val navigatedTicket =
                coordinator.request(
                    targetKey = "composer",
                    region = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("conversation_open", allowCrossRegionFromNonText = true),
                )
            assertEquals(
                RaftFocusDecision.Allow,
                coordinator.evaluate(navigatedTicket),
                "user-initiated navigation from non-text control in $region may focus Conversation",
            )

            val userTicket =
                coordinator.request(
                    targetKey = "composer",
                    region = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.User("quote"),
                )
            assertEquals(
                RaftFocusDecision.Allow,
                coordinator.evaluate(userTicket),
                "explicit user request may move focus out of $region",
            )
        }

        // Same-region system move is allowed when epochs did not advance.
        val sameRegion = RaftFocusCoordinator()
        sameRegion.setRegionFocused(owner = "conv-pane", region = RaftFocusRegion.Conversation, focused = true)
        val sameRegionTicket =
            sameRegion.request(
                targetKey = "composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("conversation_open"),
            )
        assertEquals(RaftFocusDecision.Allow, sameRegion.evaluate(sameRegionTicket))

        // Side thread opening may take focus from a yielding Conversation composer, but NOT from
        // a non-composer control inside Conversation on a passive request.
        val handoff = RaftFocusCoordinator()
        handoff.setRegionFocused(owner = "conv-pane", region = RaftFocusRegion.Conversation, focused = true)
        handoff.setComposerFocused(owner = "main-composer", focused = true, region = RaftFocusRegion.Conversation)
        val sideFromComposer =
            handoff.request(
                targetKey = "side-composer",
                region = RaftFocusRegion.SideThread,
                source = RaftFocusRequestSource.System("conversation_open"),
            )
        assertEquals(RaftFocusDecision.Allow, handoff.evaluate(sideFromComposer))

        handoff.setComposerFocused(owner = "main-composer", focused = false, region = RaftFocusRegion.Conversation)
        val sideFromNonComposerControl =
            handoff.request(
                targetKey = "side-composer",
                region = RaftFocusRegion.SideThread,
                source = RaftFocusRequestSource.System("conversation_open"),
            )
        assertEquals(RaftFocusDecision.DropOtherRegionFocus, handoff.evaluate(sideFromNonComposerControl))

        // Conversation must NOT steal focus back from a focused SideThread.
        val sideFocused = RaftFocusCoordinator()
        sideFocused.setRegionFocused(owner = "side-pane", region = RaftFocusRegion.SideThread, focused = true)
        sideFocused.setComposerFocused(owner = "side-composer", focused = true, region = RaftFocusRegion.SideThread)
        val convSteal =
            sideFocused.request(
                targetKey = "main-composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("conversation_open"),
            )
        assertEquals(RaftFocusDecision.DropOtherRegionFocus, sideFocused.evaluate(convSteal))

        // Untagged window-level non-text focus also blocks passive system requests.
        val untagged = RaftFocusCoordinator()
        untagged.setWindowHasFocus(owner = "window-root", hasFocus = true)
        val untaggedTicket =
            untagged.request(
                targetKey = "composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("conversation_open"),
            )
        assertEquals(RaftFocusDecision.DropOtherRegionFocus, untagged.evaluate(untaggedTicket))
    }

    @Test
    fun rule3DelayedTicketEpochsCancelPendingConsumeAndPreModalReturn() {
        // User pointer/key interaction while waiting cancels the ticket.
        val interactionCoord = RaftFocusCoordinator()
        val interactionTicket =
            interactionCoord.request(
                targetKey = "composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("deferred_upload"),
            )
        interactionCoord.recordUserInteraction()
        assertTrue(interactionCoord.isStale(interactionTicket))
        assertEquals(RaftFocusDecision.DropStaleInteraction, interactionCoord.evaluate(interactionTicket))
        // checkEpochs = false bypasses epoch checks when a caller only queries live surface/region rules.
        assertEquals(RaftFocusDecision.Allow, interactionCoord.evaluate(interactionTicket, checkEpochs = false))

        // Explicit User.interactionEpoch is honored as the ticket's captured interaction epoch.
        val explicitEpochCoord = RaftFocusCoordinator()
        val capturedAtClick = explicitEpochCoord.interactionEpoch
        explicitEpochCoord.recordUserInteraction()
        val lateUserTicket =
            explicitEpochCoord.request(
                targetKey = "composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.User("delayed_click", interactionEpoch = capturedAtClick),
            )
        assertEquals(RaftFocusDecision.DropStaleInteraction, explicitEpochCoord.evaluate(lateUserTicket))

        // Focus moving to another owner while waiting cancels the ticket.
        val focusMoveCoord = RaftFocusCoordinator()
        val focusMoveTicket =
            focusMoveCoord.request(
                targetKey = "composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("deferred_upload"),
            )
        focusMoveCoord.setRegionFocused(owner = "nav", region = RaftFocusRegion.Navigation, focused = true)
        assertTrue(focusMoveCoord.isStale(focusMoveTicket))
        assertEquals(RaftFocusDecision.DropStaleFocus, focusMoveCoord.evaluate(focusMoveTicket))

        // Newer ticket for the same target supersedes the older one; cancelPending & consume retire tracking.
        val supersedeCoord = RaftFocusCoordinator()
        val older =
            supersedeCoord.request(
                targetKey = "composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("first"),
            )
        val newer =
            supersedeCoord.request(
                targetKey = "composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("second"),
            )
        assertEquals(RaftFocusDecision.DropSuperseded, supersedeCoord.evaluate(older))
        assertEquals(RaftFocusDecision.Allow, supersedeCoord.evaluate(newer, consume = true))
        assertFalse(supersedeCoord.isLatest(newer), "consume = true removes completed ticket from tracking")

        val cancelled =
            supersedeCoord.request(
                targetKey = "composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("third"),
            )
        supersedeCoord.cancelPending("composer")
        assertEquals(RaftFocusDecision.DropSuperseded, supersedeCoord.evaluate(cancelled))

        // Closing a modal returns OS window focus to the pre-modal host region without advancing
        // focusEpoch, so a search-result navigation ticket survives settle.
        val modalReturnCoord = RaftFocusCoordinator()
        val topBarOwner = "top-bar"
        val searchModal = Any()
        modalReturnCoord.setRegionFocused(owner = topBarOwner, region = RaftFocusRegion.TopBar, focused = true)
        modalReturnCoord.acquireModal(searchModal)
        modalReturnCoord.setRegionFocused(owner = topBarOwner, region = RaftFocusRegion.TopBar, focused = false)
        val searchPickTicket =
            modalReturnCoord.request(
                targetKey = "composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("conversation_open", allowCrossRegionFromNonText = true),
            )
        modalReturnCoord.releaseModal(searchModal)
        modalReturnCoord.setWindowHasFocus(owner = "window-root", hasFocus = true)
        modalReturnCoord.setRegionFocused(owner = topBarOwner, region = RaftFocusRegion.TopBar, focused = true)
        assertFalse(modalReturnCoord.isStale(searchPickTicket))
        assertEquals(RaftFocusDecision.Allow, modalReturnCoord.evaluate(searchPickTicket))

        // By contrast, focus moving to a *different* region after modal release still bumps focusEpoch.
        val movedAfterModal =
            modalReturnCoord.request(
                targetKey = "composer-2",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("conversation_open", allowCrossRegionFromNonText = true),
            )
        modalReturnCoord.setRegionFocused(owner = "supporting", region = RaftFocusRegion.SupportingPane, focused = true)
        assertEquals(RaftFocusDecision.DropStaleFocus, modalReturnCoord.evaluate(movedAfterModal))
    }

    @Test
    fun rule4RestoreIfLostChecksLostFocusEpochsSurfacesAndTouchPrimary() {
        // Focus fell to nowhere after closing element disposed -> Allow.
        val lostCoord = RaftFocusCoordinator()
        lostCoord.setComposerFocused(owner = "side", focused = true, region = RaftFocusRegion.SideThread)
        val lostTicket =
            lostCoord.request(
                targetKey = "main",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("side_thread_close_restore"),
            )
        lostCoord.setComposerFocused(owner = "side", focused = false, region = RaftFocusRegion.SideThread)
        assertEquals(RaftFocusDecision.Allow, lostCoord.evaluateRestoreIfLost(lostTicket))
        assertEquals(RaftFocusDecision.DropTouchPrimary, lostCoord.evaluateRestoreIfLost(lostTicket, touchPrimary = true))

        // Another control (non-text region, window focus, text field, or composer) still holds focus -> DropFocusNotLost.
        val keptRegionCoord = RaftFocusCoordinator()
        keptRegionCoord.setRegionFocused(owner = "nav", region = RaftFocusRegion.Navigation, focused = true)
        val keptRegionTicket =
            keptRegionCoord.request(
                targetKey = "main",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("side_thread_close_restore"),
            )
        assertEquals(RaftFocusDecision.DropFocusNotLost, keptRegionCoord.evaluateRestoreIfLost(keptRegionTicket))

        // An open surface blocks restore -> DropOpenSurface.
        val surfaceCoord = RaftFocusCoordinator()
        surfaceCoord.acquireOpenSurface()
        val surfaceTicket =
            surfaceCoord.request(
                targetKey = "main",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("side_thread_close_restore"),
            )
        assertEquals(RaftFocusDecision.DropOpenSurface, surfaceCoord.evaluateRestoreIfLost(surfaceTicket))

        // Focus moved during settle -> DropStaleFocus.
        val movedCoord = RaftFocusCoordinator()
        val movedTicket =
            movedCoord.request(
                targetKey = "main",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("side_thread_close_restore"),
            )
        movedCoord.setRegionFocused(owner = "supporting", region = RaftFocusRegion.SupportingPane, focused = true)
        assertEquals(RaftFocusDecision.DropStaleFocus, movedCoord.evaluateRestoreIfLost(movedTicket))

        // User interacted during settle -> DropStaleInteraction.
        val actedCoord = RaftFocusCoordinator()
        val actedTicket =
            actedCoord.request(
                targetKey = "main",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("side_thread_close_restore"),
            )
        actedCoord.recordUserInteraction()
        assertEquals(RaftFocusDecision.DropStaleInteraction, actedCoord.evaluateRestoreIfLost(actedTicket))
    }

    @Test
    fun rule5TouchPrimaryDropsSystemTextRequestsOnly() {
        val coordinator = RaftFocusCoordinator()

        val systemText =
            coordinator.request(
                targetKey = "composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.System("mount"),
                isText = true,
            )
        assertEquals(RaftFocusDecision.DropTouchPrimary, coordinator.evaluate(systemText, touchPrimary = true))
        assertEquals(RaftFocusDecision.Allow, coordinator.evaluate(systemText, touchPrimary = false))

        val systemNonText =
            coordinator.request(
                targetKey = "lightbox",
                region = RaftFocusRegion.Modal,
                source = RaftFocusRequestSource.System("mount"),
                isText = false,
            )
        assertEquals(RaftFocusDecision.Allow, coordinator.evaluate(systemNonText, touchPrimary = true))

        val userText =
            coordinator.request(
                targetKey = "composer",
                region = RaftFocusRegion.Conversation,
                source = RaftFocusRequestSource.User("tap"),
                isText = true,
            )
        assertEquals(RaftFocusDecision.Allow, coordinator.evaluate(userText, touchPrimary = true))
    }

    @Test
    fun autofocusGuardDelegatesToCoordinatorAndKeepsExistingSemantics() {
        val guard = RaftAutofocusGuard()
        assertFalse(guard.blocksAutofocus)
        assertFalse(guard.surfaceOpen)
        assertFalse(guard.otherFieldFocused)

        guard.setFieldFocused(owner = "members-search", focused = true, region = RaftFocusRegion.SupportingPane)
        assertTrue(guard.blocksAutofocus)
        assertTrue(guard.otherFieldFocused)
        assertTrue(guard.coordinator.otherTextFieldFocused)

        guard.setFieldFocused(owner = "members-search", focused = false, region = RaftFocusRegion.SupportingPane)
        assertFalse(guard.blocksAutofocus)
        assertFalse(guard.otherFieldFocused)
    }
}
