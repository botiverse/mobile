package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where local sends (sending / failed) sit among server messages: where they were sent, not pinned
 * under every newer message (feedback 11-04: a failed send from 11-02 stayed at the bottom of a
 * DM for days). Web `compareMessagesForDisplay` places a local row where its server seq would land.
 */
class SharedThreadTimelineLocalSendPlacementTest {
    @Test
    fun localSendsSitByTheirCreationTimeAmongServerMessages() {
        data class Case(val name: String, val input: List<RaftThreadMessageItem>, val expected: List<String>)
        val s1 = server("s1", seq = 1L, createdAt = 100L)
        val s2 = server("s2", seq = 2L, createdAt = 200L)
        val s3 = server("s3", seq = 3L, createdAt = 300L)
        val cases =
            listOf(
                Case(
                    "an old failed send stays above the messages that came after it",
                    listOf(local("failed-old", createdAt = 150L, failed = true), s3, s1, s2),
                    listOf("s1", "failed-old", "s2", "s3"),
                ),
                Case(
                    "a send that just failed is the newest row: bottom",
                    listOf(s2, local("failed-new", createdAt = 400L, failed = true), s1, s3),
                    listOf("s1", "s2", "s3", "failed-new"),
                ),
                Case(
                    "a sending row and a failed row keep their own order; equal instants follow the local ordinal",
                    listOf(
                        local("local-b-10", createdAt = 250L, order = 10L),
                        local("local-a-2", createdAt = 250L, order = 2L, failed = true),
                        s3,
                        s1,
                        s2,
                    ),
                    listOf("s1", "s2", "local-a-2", "local-b-10", "s3"),
                ),
                Case(
                    "a local row with no creation time stays last",
                    listOf(local("no-time", createdAt = null), s1, s2),
                    listOf("s1", "s2", "no-time"),
                ),
                Case(
                    "server rows keep seq order even when their clocks disagree",
                    listOf(
                        server("seq-5", seq = 5L, createdAt = 100L),
                        local("failed", createdAt = 500L, failed = true),
                        server("seq-4", seq = 4L, createdAt = 900L),
                    ),
                    listOf("seq-4", "seq-5", "failed"),
                ),
            )
        cases.forEach { case ->
            assertEquals(case.expected, case.input.sortedByThreadTimeline().map { it.id }, case.name)
            assertEquals(
                case.expected,
                case.input.reversed().sortedByThreadTimeline().map { it.id },
                "${case.name} (input order must not matter)",
            )
        }
    }

    @Test
    fun retryTakesTheRetryTimeSoTheResentMessageIsTheBottomRow() {
        val failed = local("failed", createdAt = 150L, failed = true).copy(timestampLabel = "2026-11-02T09:20:00Z")
        val retried = failed.restampedForLocalSendRetry("2026-11-04T02:20:00Z")
        val retriedAt = build.raft.app.model.parseRaftTimestampEpochMillisOrNull("2026-11-04T02:20:00Z")

        assertEquals(retriedAt, retried.createdAtEpochMillis)
        assertEquals("2026-11-04T02:20:00Z", retried.timestampLabel)
        assertEquals(
            listOf("s1", "s2", "failed"),
            listOf(retried, server("s1", 1L, 100L), server("s2", 2L, 200L)).sortedByThreadTimeline().map { it.id },
        )
        // No clock on this device ("Now"): the row keeps its time; the store's send admission
        // still starts it after the newest message.
        assertEquals(failed, failed.restampedForLocalSendRetry("Now"))
    }

    private fun server(id: String, seq: Long, createdAt: Long): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "agent-pm",
            authorName = "PM",
            timestampLabel = "label-$id",
            createdAtEpochMillis = createdAt,
            seq = seq,
            body = id,
        )

    private fun local(id: String, createdAt: Long?, order: Long? = null, failed: Boolean = false): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "user-1",
            authorName = "Me",
            isOwn = true,
            timestampLabel = "label-$id",
            createdAtEpochMillis = createdAt,
            body = id,
            deliveryState = if (failed) ThreadMessageDeliveryFailed else ThreadMessageDeliverySending,
            localSendOrder = order,
        )
}
