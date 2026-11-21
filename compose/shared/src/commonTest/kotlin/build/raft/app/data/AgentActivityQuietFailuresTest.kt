package build.raft.app.data

import build.raft.app.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Web packages/web/tests/activityQuietFailures.test.ts at botiverse/slock 4be606f7fd (#13050), its
 * three cases one to one, plus what ActivityQuietFailureRuns.tsx reads off a run: whether it is a
 * usage-limit wait and the latest reset a retry reported.
 */
class AgentActivityQuietFailuresTest {
    private val hour = 3_600_000L

    /** `Date.parse("2026-11-08T07:00:00.000Z")`, the web test's reset. */
    private val reset = 1_794_121_200_000L

    /** The web test's `turn(at, overrides)`: a failed turn that did nothing, a second long. */
    private fun turn(
        at: Long,
        outcome: AgentActivityTurnOutcome = AgentActivityTurnOutcome.Failed,
        toolCalls: Int = 0,
        messagesSent: Int = 0,
        problemCount: Int = 0,
        lastReplyMessageId: MessageId? = null,
        sentMessageIds: List<MessageId>? = null,
        hiddenSentCount: Int = 0,
        usageLimit: AgentActivityTurnUsageLimit? = null,
    ) = AgentActivityTurn(
        id = "t$at",
        startedAtEpochMillis = at,
        endedAtEpochMillis = at + 1_000L,
        outcome = outcome,
        trigger = null,
        actions = emptyList(),
        toolCalls = toolCalls,
        problems = emptyList(),
        problemCount = problemCount,
        messagesSent = messagesSent,
        lastReplyMessageId = lastReplyMessageId,
        sentMessageIds = sentMessageIds,
        hiddenSentCount = hiddenSentCount,
        usageLimit = usageLimit,
    )

    private fun limited(at: Long, resetsAt: Long? = reset) = turn(at, usageLimit = AgentActivityTurnUsageLimit(resetsAt))

    private fun turns(vararg turns: AgentActivityTurn) = turns.map { AgentActivityQuietFailureItem.Turn(it) }

    @Test
    fun aQuietFailureDidNothingFailedNoToolCallNoMessageNothingGoneWrong() {
        assertTrue(turn(0).isQuietFailure)
        // Anything done, sent or gone wrong, or any ending but a failure, is not quiet.
        listOf(
            turn(0, outcome = AgentActivityTurnOutcome.Completed),
            turn(0, toolCalls = 1),
            turn(0, messagesSent = 1),
            turn(0, lastReplyMessageId = MessageId("m1")),
            turn(0, sentMessageIds = listOf(MessageId("m1"))),
            turn(0, hiddenSentCount = 1),
            turn(0, problemCount = 1),
        ).forEach { assertFalse(it.isQuietFailure, "$it") }
        // An empty sent list (a newer server's) is nothing sent.
        assertTrue(turn(0, sentMessageIds = emptyList()).isQuietFailure)
    }

    @Test
    fun hourlyUsageLimitRetriesBetweenRealTurnsFoldIntoOneRunInOrder() {
        val work = turn(0, outcome = AgentActivityTurnOutcome.Completed, toolCalls = 4, messagesSent = 1)
        val retries = listOf(limited(hour), limited(2 * hour), limited(3 * hour))
        val after = turn(10 * hour, outcome = AgentActivityTurnOutcome.Completed, toolCalls = 2)
        assertEquals(
            listOf(
                AgentActivityQuietFailureItem.Turn(work),
                AgentActivityQuietFailureItem.Run(retries),
                AgentActivityQuietFailureItem.Turn(after),
            ),
            groupAgentActivityQuietFailures(listOf(work) + retries + after),
        )
    }

    @Test
    fun oneQuietFailureAloneStaysATurnUsageLimitAndOtherFailuresAreSeparateRuns() {
        val lone = turn(0)
        assertEquals(turns(lone), groupAgentActivityQuietFailures(listOf(lone)))
        // Turns without a usage limit (other failures, and every turn from an older Computer) fold too.
        val plain = listOf(turn(hour), turn(2 * hour))
        val quota = listOf(limited(3 * hour), limited(4 * hour))
        assertEquals(
            listOf(AgentActivityQuietFailureItem.Run(plain), AgentActivityQuietFailureItem.Run(quota)),
            groupAgentActivityQuietFailures(plain + quota),
        )
        // A limit turn between plain failures does not join them.
        val mixed = listOf(turn(hour), limited(2 * hour), turn(3 * hour))
        assertEquals(turns(*mixed.toTypedArray()), groupAgentActivityQuietFailures(mixed))
    }

    @Test
    fun aRunSaysWhetherItWaitsForTheLimitAndTheLatestResetReported() {
        val later = reset + 24 * hour
        // The latest report wins: a later retry may have heard a newer reset; one that said none does not erase it.
        val run = AgentActivityQuietFailureItem.Run(listOf(limited(hour), limited(2 * hour, later), limited(3 * hour, resetsAt = null)))
        assertTrue(run.endedOnUsageLimit)
        assertEquals(later, run.resetsAtEpochMillis)
        // No retry said when the limit resets: "Usage limit reached · N attempts".
        val unknownReset = AgentActivityQuietFailureItem.Run(listOf(limited(hour, resetsAt = null), limited(2 * hour, resetsAt = null)))
        assertTrue(unknownReset.endedOnUsageLimit)
        assertNull(unknownReset.resetsAtEpochMillis)
        // Other failures: "N turns in a row ended with an error ...".
        val plain = AgentActivityQuietFailureItem.Run(listOf(turn(hour), turn(2 * hour)))
        assertFalse(plain.endedOnUsageLimit)
        assertNull(plain.resetsAtEpochMillis)
    }
}
