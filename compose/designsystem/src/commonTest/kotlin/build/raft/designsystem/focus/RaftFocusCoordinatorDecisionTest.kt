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
    fun topModalSystemRequestIgnoresHostRegionAndHostTextFieldWhileRespectingInModalTextField() {
        val coordinator = RaftFocusCoordinator()
        val bottomModal = Any()
        val topModal = Any()

        // Host window Conversation region and TopBar search box still report focus when modal opens.
        coordinator.setRegionFocused(owner = "host-conv", region = RaftFocusRegion.Conversation, focused = true)
        coordinator.setTextFieldFocused(owner = "host-search", focused = true, region = RaftFocusRegion.TopBar)
        coordinator.acquireModal(bottomModal)

        // System request targeting the top modal succeeds despite lingering host focus.
        assertFalse(
            coordinator.blocksSystemFocus(
                targetRegion = RaftFocusRegion.Modal,
                modalToken = bottomModal,
            ),
        )
        val topModalTicket =
            coordinator.request(
                targetKey = "modal-field",
                region = RaftFocusRegion.Modal,
                source = RaftFocusRequestSource.System("modal_open"),
                modalToken = bottomModal,
            )
        assertEquals(RaftFocusDecision.Allow, coordinator.evaluate(topModalTicket))

        // Single-modal callers without an explicit modalToken also succeed when region == Modal.
        val implicitSingleModalTicket =
            coordinator.request(
                targetKey = "modal-field-implicit",
                region = RaftFocusRegion.Modal,
                source = RaftFocusRequestSource.System("modal_open"),
            )
        assertEquals(RaftFocusDecision.Allow, coordinator.evaluate(implicitSingleModalTicket))

        // A text field already focused INSIDE the modal still blocks another System request in the modal.
        coordinator.setTextFieldFocused(owner = "modal-existing", focused = true, region = RaftFocusRegion.Modal)
        val blockedByInModalField =
            coordinator.request(
                targetKey = "modal-second-field",
                region = RaftFocusRegion.Modal,
                source = RaftFocusRequestSource.System("modal_open"),
                modalToken = bottomModal,
            )
        assertEquals(RaftFocusDecision.DropOtherTextField, coordinator.evaluate(blockedByInModalField))
        coordinator.setTextFieldFocused(owner = "modal-existing", focused = false, region = RaftFocusRegion.Modal)

        // Opening a nested modal blocks the buried modal while allowing the new top modal.
        coordinator.acquireModal(topModal)
        val buriedTicket =
            coordinator.request(
                targetKey = "buried-field",
                region = RaftFocusRegion.Modal,
                source = RaftFocusRequestSource.System("buried_modal"),
                modalToken = bottomModal,
            )
        assertEquals(RaftFocusDecision.DropOpenSurface, coordinator.evaluate(buriedTicket))

        val nestedTopTicket =
            coordinator.request(
                targetKey = "nested-field",
                region = RaftFocusRegion.Modal,
                source = RaftFocusRequestSource.System("nested_modal"),
                modalToken = topModal,
            )
        assertEquals(RaftFocusDecision.Allow, coordinator.evaluate(nestedTopTicket))
    }
}
