package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #990 — focus → composer ownership transfer at the keyboard onset.
 *
 * The two samples below are the real ones from log 778ee087, and they are the
 * reason this file exists: their final geometry is identical (target 17:770+235,
 * viewport 1059, gap 0) and one rendered while the other was blank. Geometry
 * therefore cannot be the pin — the only thing that separated them was who still
 * owned the viewport when the keyboard began.
 *
 * Every assertion here is paired with the opposite input. A guard that returned
 * a constant would satisfy the "no reanchor" half on its own, which is exactly
 * the shape of check that let an earlier gate through.
 */
class ThreadImeOnsetOwnershipTest {

    private val target = "thread-1:message-17"

    // 02:26:38 — the white frame. The initial focus command had settled 38ms
    // earlier, so the session and the origin correction were both still live.
    private fun whiteSampleAction(retiredIdentity: String? = null) =
        threadImeOnsetOwnershipAction(
            keyboardRising = true,
            targetIdentity = target,
            focusedAnchorOwnsViewport = true,
            focusedOriginCorrectionPending = true,
            retiredIdentity = retiredIdentity
        )

    // 02:26:14 — the healthy frame. focused_anchor_session=consumed_quiet.
    private fun healthySampleAction(retiredIdentity: String? = null) =
        threadImeOnsetOwnershipAction(
            keyboardRising = true,
            targetIdentity = target,
            focusedAnchorOwnsViewport = false,
            focusedOriginCorrectionPending = false,
            retiredIdentity = retiredIdentity
        )

    @Test
    fun liveFocusedTransactionAtOnsetIsRetiredAndSettledOneIsNot() {
        assertEquals(
            ThreadImeOnsetOwnershipAction.RetireFocusedSession,
            whiteSampleAction(),
            "a focused transaction still running at the keyboard onset must be retired"
        )
        assertEquals(
            ThreadImeOnsetOwnershipAction.KeepConsumedPath,
            healthySampleAction(),
            "the already-consumed path must be left exactly as it was"
        )
    }

    @Test
    fun aPendingOriginCorrectionAloneIsEnoughToRetire() {
        // Ownership can already have lapsed while the correction is still in
        // flight; that correction is one of the two superseded commands, so it
        // must be able to trigger the handoff by itself.
        assertEquals(
            ThreadImeOnsetOwnershipAction.RetireFocusedSession,
            threadImeOnsetOwnershipAction(
                keyboardRising = true,
                targetIdentity = target,
                focusedAnchorOwnsViewport = false,
                focusedOriginCorrectionPending = true,
                retiredIdentity = null
            )
        )
    }

    @Test
    fun retirementHappensOncePerTarget() {
        assertEquals(
            ThreadImeOnsetOwnershipAction.None,
            whiteSampleAction(retiredIdentity = target),
            "a second inset change inside the same rise must not re-run the handoff"
        )
        assertEquals(
            ThreadImeOnsetOwnershipAction.RetireFocusedSession,
            whiteSampleAction(retiredIdentity = "thread-1:message-9"),
            "a retirement recorded for another target must not suppress this one"
        )
    }

    @Test
    fun nothingHappensWithoutARiseOrAFocusTarget() {
        assertEquals(
            ThreadImeOnsetOwnershipAction.None,
            threadImeOnsetOwnershipAction(
                keyboardRising = false,
                targetIdentity = target,
                focusedAnchorOwnsViewport = true,
                focusedOriginCorrectionPending = true,
                retiredIdentity = null
            ),
            "the keyboard falling must not retire anything"
        )
        assertEquals(
            ThreadImeOnsetOwnershipAction.None,
            threadImeOnsetOwnershipAction(
                keyboardRising = true,
                targetIdentity = null,
                focusedAnchorOwnsViewport = true,
                focusedOriginCorrectionPending = true,
                retiredIdentity = null
            ),
            "a thread with no focus target has no transaction to retire"
        )
    }

    @Test
    fun retiredSessionYieldsInsteadOfReadingShrinkDisplacementAsDrift() {
        // The 3:3 command in the white sample: the viewport shrinking moved the
        // target by the expected 1033px and the session called it drift.
        assertEquals(
            FocusedAnchorSessionAction.Reanchor,
            focusedAnchorSessionAction(
                userInteracting = false,
                drifted = true,
                quietMs = 0L,
                elapsedMs = 200L,
                viewportResizing = false
            ),
            "without a resize, drift must still reanchor — the guard, not the arm, is what changed"
        )
        assertEquals(
            FocusedAnchorSessionAction.Consume,
            focusedAnchorSessionAction(
                userInteracting = false,
                drifted = true,
                quietMs = 0L,
                elapsedMs = 200L,
                viewportResizing = true
            ),
            "a retired session must end rather than reanchor a resize displacement"
        )
    }

    @Test
    fun aViewportResizeIsNotDriftButRealMovementStillIs() {
        // The white sample's own numbers: target 1803 -> 770 while the viewport
        // went 2092 -> 1059. Identical magnitudes, so nothing moved relative to
        // the resize. This is the tooth that closes the window between the
        // viewport shrinking and the onset handoff being recorded.
        val resizeDelta =
            focusedAnchorViewportResizeDelta(
                anchoredViewportExtentPx = 2092,
                currentViewportExtentPx = 1059
            )
        assertEquals(-1033, resizeDelta)
        assertFalse(
            focusedAnchorTargetDrifted(
                anchoredOffset = 1803,
                currentOffset = 770,
                viewportResizeDeltaPx = resizeDelta
            ),
            "displacement equal to the resize must not read as drift"
        )
        assertTrue(
            focusedAnchorTargetDrifted(anchoredOffset = 1803, currentOffset = 770),
            "the raw comparison did read it as drift — that is the defect being fixed"
        )
        // A real scroll during the same resize must still be caught, otherwise
        // the subtraction has simply disabled the invariant.
        assertTrue(
            focusedAnchorTargetDrifted(
                anchoredOffset = 1803,
                currentOffset = 370,
                viewportResizeDeltaPx = resizeDelta
            ),
            "movement beyond the resize must still reanchor"
        )
        assertTrue(
            focusedAnchorTargetDrifted(
                anchoredOffset = 1803,
                currentOffset = null,
                viewportResizeDeltaPx = resizeDelta
            ),
            "leaving the viewport is always drift, resize or not"
        )
    }

    @Test
    fun anUnmeasuredViewportFallsBackToTheRawComparison() {
        // Degrading to 0 keeps the old behaviour; degrading to "excuse anything"
        // would silently retire the invariant on every unmeasured frame.
        assertEquals(0, focusedAnchorViewportResizeDelta(null, 1059))
        assertEquals(0, focusedAnchorViewportResizeDelta(2092, null))
        assertEquals(0, focusedAnchorViewportResizeDelta(0, 1059))
        assertTrue(
            focusedAnchorTargetDrifted(
                anchoredOffset = 1803,
                currentOffset = 770,
                viewportResizeDeltaPx = focusedAnchorViewportResizeDelta(null, 1059)
            )
        )
    }

    @Test
    fun aSupersededCorrectionMayNotReportItselfCorrected() {
        // The white sample settled `superseded_epoch` and still published a
        // settled identity because the target happened to be visible, which
        // unlocked the composer on a correction that never applied.
        fun mayPublish(outcome: String, retired: String? = null, visible: Boolean = true) =
            focusedOriginCorrectionMayPublishCompletion(
                settlementOutcome = outcome,
                targetVisible = visible,
                targetIdentity = target,
                retiredIdentity = retired
            )

        assertTrue(mayPublish("consumed_original"), "a command that landed must publish")
        assertFalse(mayPublish("superseded_epoch"), "a superseded command must not publish")
        assertFalse(mayPublish("replaced_same_epoch"), "a replaced command must not publish")
        assertFalse(
            mayPublish("consumed_original", retired = target),
            "a retired owner must not publish even when its command landed"
        )
        assertFalse(
            mayPublish("consumed_original", visible = false),
            "visibility remains necessary — it has only stopped being sufficient"
        )
        assertTrue(
            mayPublish("consumed_original", retired = "thread-1:message-9"),
            "another target's retirement must not block this publication"
        )
    }

    @Test
    fun userInteractionStillOutranksRetirement() {
        // Retiring must not swallow a real user scroll: an interrupt has to stay
        // distinguishable from a handoff, or the session records the wrong reason
        // and the interrupt fence is never set.
        assertEquals(
            FocusedAnchorSessionAction.Interrupt,
            focusedAnchorSessionAction(
                userInteracting = true,
                drifted = true,
                quietMs = 0L,
                elapsedMs = 200L,
                viewportResizing = true
            )
        )
    }

    @Test
    fun retirementInvalidatesTheLateOriginCorrection() {
        // The 2:2 command: this effect awaits layout frames, so it can arrive
        // after the keyboard already owns the viewport.
        fun correct(retired: String?) =
            shouldCorrectFocusedAnchorAfterOriginHydration(
                originStatus = "loaded",
                targetIdentity = target,
                anchoredIdentity = target,
                correctedIdentity = null,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 3,
                currentUserScrollGeneration = 3,
                retiredIdentity = retired
            )

        assertTrue(correct(retired = null), "an un-retired correction must still run")
        assertTrue(
            correct(retired = "thread-1:message-9"),
            "another target's retirement must not cancel this correction"
        )
        assertFalse(correct(retired = target), "a retired correction must not dispatch")
    }

    @Test
    fun retirementHandsTheShrinkToExactlyOneComposerCommand() {
        // Ownership after the handoff, computed the way the effect computes it.
        val ownsAfterRetire =
            focusedMessageAnchorOwnsViewport(
                pendingIdentity = target,
                consumedIdentity = target,
                scrollInterruptedIdentity = null,
                yieldElapsedMs = 0L
            )
        assertFalse(ownsAfterRetire, "retirement must release the focused fence immediately")

        assertFalse(
            threadFocusedOriginCorrectionBlocksComposerLayout(
                messageCount = 40,
                viewportHeightPx = 1059,
                originStatus = "loaded",
                targetIdentity = target,
                focusedAnchorOwnsViewport = ownsAfterRetire,
                settledCorrectionIdentity = null
            ),
            "with the fence released the composer must no longer be debounced"
        )

        // The active-tail path is the one that belongs to a *running* session; it
        // must go quiet, and the consumed-tail path — the healthy sample's own
        // path — must be what carries the shrink.
        fun activeTail(consumed: String?) =
            focusedActiveTailMessageComposerLayoutOwnsViewport(
                targetIdentity = target,
                targetItemIndex = 17,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = target,
                consumedIdentity = consumed,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 3,
                currentUserScrollGeneration = 3
            )

        fun consumedTail(consumed: String?) =
            focusedTailMessageComposerLayoutOwnsViewport(
                targetIdentity = target,
                targetItemIndex = 17,
                latestMessageItemIndex = 17,
                targetToTailFitsViewport = true,
                canLoadMoreAfter = false,
                anchoredIdentity = target,
                consumedIdentity = consumed,
                scrollInterruptedIdentity = null,
                startUserScrollGeneration = 3,
                currentUserScrollGeneration = 3
            )

        assertTrue(activeTail(consumed = null), "before retirement the active-tail path owns the layout")
        assertFalse(activeTail(consumed = target), "after retirement the active-tail path must go quiet")
        assertFalse(consumedTail(consumed = null), "before retirement the consumed path is not eligible")
        assertTrue(consumedTail(consumed = target), "after retirement the consumed path carries the shrink")

        // Exactly one of the two focused paths is eligible on either side of the
        // handoff — the shrink never has two composer owners.
        listOf<String?>(null, target).forEach { consumed ->
            assertEquals(
                1,
                listOf(activeTail(consumed), consumedTail(consumed)).count { it },
                "exactly one focused composer path may own the shrink (consumed=$consumed)"
            )
        }

        assertTrue(
            shouldAnchorThreadComposerLayoutChange(
                ThreadComposerLayoutBottomAnchorState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 336f,
                    previousViewportHeightDp = 700f,
                    nextViewportHeightDp = 364f,
                    pendingLayoutChange = false,
                    initialBottomAnchorReady = true,
                    latestItemIndex = 18,
                    // The focused route disables the bottom auto anchor, so this
                    // must pass on the focused path alone.
                    allowBottomAutoAnchor = false,
                    viewportEpochCoherent = true,
                    focusedAnchorOwnsViewport = ownsAfterRetire,
                    focusedOriginCorrectionPending = false,
                    focusedActiveTailAnchorCanCorrectLayout = false,
                    focusedTailAnchorCanCorrectLayout = consumedTail(consumed = target)
                )
            ),
            "the retired shrink must still be corrected — retiring is a handoff, not a freeze"
        )
    }

    @Test
    fun theHealthyPathStillCorrectsItsOwnShrink() {
        // 02:26:14 unchanged: consumed before the keyboard, one composer command.
        assertTrue(
            shouldAnchorThreadComposerLayoutChange(
                ThreadComposerLayoutBottomAnchorState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 336f,
                    previousViewportHeightDp = 700f,
                    nextViewportHeightDp = 364f,
                    pendingLayoutChange = false,
                    initialBottomAnchorReady = true,
                    latestItemIndex = 18,
                    allowBottomAutoAnchor = false,
                    viewportEpochCoherent = true,
                    focusedAnchorOwnsViewport = false,
                    focusedOriginCorrectionPending = false,
                    focusedActiveTailAnchorCanCorrectLayout = false,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        )
    }

    @Test
    fun anIncoherentViewportEpochStillDefersTheCommand() {
        // Requirement 3 of the handoff: one pending intent, dispatched only once
        // the measured viewport and the Lazy viewport agree. Retirement must not
        // become a way to dispatch against a half-applied shrink.
        assertFalse(
            shouldAnchorThreadComposerLayoutChange(
                ThreadComposerLayoutBottomAnchorState(
                    previousKeyboardInsetDp = 0f,
                    nextKeyboardInsetDp = 336f,
                    previousViewportHeightDp = 700f,
                    nextViewportHeightDp = 364f,
                    pendingLayoutChange = false,
                    initialBottomAnchorReady = true,
                    latestItemIndex = 18,
                    allowBottomAutoAnchor = false,
                    viewportEpochCoherent = false,
                    focusedAnchorOwnsViewport = false,
                    focusedOriginCorrectionPending = false,
                    focusedActiveTailAnchorCanCorrectLayout = false,
                    focusedTailAnchorCanCorrectLayout = true
                )
            )
        )
    }
}
