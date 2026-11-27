package build.raft.app

import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.thread.PendingMentionActionBatchRequestGate
import build.raft.app.thread.PendingMentionActionBatchStrings
import build.raft.app.thread.eligiblePendingMentionActionBatch
import build.raft.app.thread.localizedNotice
import build.raft.app.thread.pendingMentionActionBatchOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingMentionActionBatchTest {
    @Test
    fun requestGateRejectsSameFrameDuplicateUntilCompletion() {
        val gate = PendingMentionActionBatchRequestGate()

        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        gate.finish()
        assertTrue(gate.tryBegin())
    }

    @Test
    fun eligibilityKeepsStableDistinctAddCapableRowsOnly() {
        val eligibleFirst = action(" first ", listOf("add", "notify_only"))
        val duplicate = action("first", listOf("add"))
        val notifyOnly = action("notify", listOf("notify_only"))
        val resolved = action("resolved", listOf("add"))
        val removing = action("removing", listOf("invite"))
        val executing = action("executing", listOf("add"))
        val blank = action("  ", listOf("add"))
        val eligibleLast = action("last", listOf("invite"))

        val eligible =
            eligiblePendingMentionActionBatch(
                actions =
                    listOf(
                        eligibleFirst,
                        duplicate,
                        notifyOnly,
                        resolved,
                        removing,
                        executing,
                        blank,
                        eligibleLast
                    ),
                actionStates = mapOf("resolved" to "added"),
                removingIds = setOf("removing"),
                executingIds = setOf("executing")
            )

        assertEquals(listOf(eligibleFirst, eligibleLast), eligible)
    }

    @Test
    fun allDeliveredHasNoFailureOrNotice() {
        val outcome =
            pendingMentionActionBatchOutcome(
                requestedIds = listOf("one", "two"),
                results = listOf(result("one", "delivered"), result("two", "delivered"))
            )

        assertEquals(2, outcome.deliveredCount)
        assertNull(outcome.firstFailure)
        assertNull(outcome.localizedNotice(strings()))
    }

    @Test
    fun missingResponseStaysFailedAndRetryable() {
        val outcome =
            pendingMentionActionBatchOutcome(
                requestedIds = listOf("one", "two"),
                results = listOf(result("one", "delivered"))
            )

        assertTrue(outcome.missingFirstFailure)
        assertEquals(setOf("one"), outcome.deliveredIds)
        assertEquals("Added 1 of 2. This mention action is no longer available.", outcome.localizedNotice(strings()))
    }

    private fun action(
        resolutionId: String,
        availableActions: List<String>
    ) = RaftPendingMentionActionItem(
        resolutionId = resolutionId,
        targetType = "user",
        targetHandle = resolutionId.trim(),
        availableActions = availableActions
    )

    private fun result(
        resolutionId: String,
        status: String,
        reason: String? = null
    ) = PendingMentionActionExecuteResult(resolutionId, status, reason)

    private fun strings() = pendingMentionActionTestStrings()
}

internal fun pendingMentionActionTestStrings() =
    PendingMentionActionBatchStrings(
        resultTemplate = "Added __RAFT_ADDED__ of __RAFT_TOTAL__. __RAFT_ERROR__",
        resultSummaryTemplate = "Added __RAFT_ADDED__ of __RAFT_TOTAL__",
        missing = "This mention action is no longer available.",
        deliveryUnavailable = "Delivery is temporarily unavailable.",
        dropped = "The action was not delivered.",
        notifyDropped = "The target was not notified.",
        senderLacksChannelAccess = "You no longer have channel access.",
        targetLacksReadAccess = "The target cannot read this channel.",
        addNotAllowedForSurface = "Use channel member controls.",
        addRequiresHumanAuthority = "Ask a human member.",
        addRequiresMemberManagementAuthority = "Only channel admins can add members.",
        noPermission = "You do not have permission.",
        targetAlreadyMember = "The target is already a member.",
        targetUnavailable = "The target is unavailable.",
        stale = "This action is no longer pending.",
        expired = "This action expired.",
        ambiguous = "This action matched multiple targets.",
        generic = "The action failed.",
        requestConnection = "Check your connection.",
        requestAuthentication = "Sign in again.",
        requestPermission = "You do not have request permission.",
        requestRateLimited = "Wait and try again.",
        requestServer = "The server failed.",
        requestUnexpectedResponse = "Unexpected response.",
        requestGeneric = "The request failed."
    )
