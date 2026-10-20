package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedThreadTimelineComparatorTest {
    @Test
    fun mixedSequenceAndNullPairsRemainTransitive() {
        val firstCanonical = message(id = "canonical-a", seq = 1L, createdAtEpochMillis = 3_000L, label = "z")
        val secondCanonical = message(id = "canonical-b", seq = 2L, createdAtEpochMillis = 1_000L, label = "a")
        val sequencePending = message(id = "sequence-pending", seq = null, createdAtEpochMillis = 2_000L, label = "m")
        val values = listOf(firstCanonical, secondCanonical, sequencePending)

        assertTrue(SharedThreadTimelineComparator.compare(firstCanonical, secondCanonical) < 0)
        assertTrue(SharedThreadTimelineComparator.compare(secondCanonical, sequencePending) < 0)
        assertTrue(SharedThreadTimelineComparator.compare(firstCanonical, sequencePending) < 0)
        assertComparatorContract(values)
        assertEquals(
            listOf("canonical-a", "canonical-b", "sequence-pending"),
            values.shuffledForSortProbe().sortedWith(SharedThreadTimelineComparator).map(RaftThreadMessageItem::id),
        )
    }

    @Test
    fun canonicalSequenceWinsEvenWhenCreationInstantsDisagree() {
        val laterClockEarlierSequence =
            message(id = "seq-4", seq = 4L, createdAtEpochMillis = 9_000L, label = "午後 9:00")
        val earlierClockLaterSequence =
            message(id = "seq-5", seq = 5L, createdAtEpochMillis = 1_000L, label = "上午 1:00")

        assertEquals(
            listOf("seq-4", "seq-5"),
            listOf(earlierClockLaterSequence, laterClockEarlierSequence)
                .sortedWith(SharedThreadTimelineComparator)
                .map(RaftThreadMessageItem::id),
        )
    }

    @Test
    fun sequenceNullMessagesUseNumericCreationTimeNotLocalizedOrDstLabels() {
        val firstInstant =
            message(
                id = "first-instant",
                seq = null,
                createdAtEpochMillis = 1_730_613_000_000L,
                label = "2026-11-01 01:30 EST",
            )
        val secondInstant =
            message(
                id = "second-instant",
                seq = null,
                createdAtEpochMillis = 1_730_616_600_000L,
                label = "2026年11月1日 01:30",
            )

        val expected = listOf("first-instant", "second-instant")
        assertEquals(
            expected,
            listOf(secondInstant, firstInstant)
                .sortedWith(SharedThreadTimelineComparator)
                .map(RaftThreadMessageItem::id),
        )
        assertEquals(
            expected,
            listOf(
                secondInstant.copy(timestampLabel = "a"),
                firstInstant.copy(timestampLabel = "z"),
            ).sortedWith(SharedThreadTimelineComparator).map(RaftThreadMessageItem::id),
        )
    }

    @Test
    fun optimisticMessagesUseNumericLocalSendOrderForSameInstant() {
        val second =
            message(
                id = "local-send-thread-2",
                seq = null,
                createdAtEpochMillis = 5_000L,
                label = "Now",
                localSendOrder = 2L,
                local = true,
            )
        val tenth =
            message(
                id = "local-send-thread-10",
                seq = null,
                createdAtEpochMillis = 5_000L,
                label = "此刻",
                localSendOrder = 10L,
                local = true,
            )

        assertEquals(
            listOf("local-send-thread-2", "local-send-thread-10"),
            listOf(tenth, second).sortedWith(SharedThreadTimelineComparator).map(RaftThreadMessageItem::id),
        )
    }

    @Test
    fun allTimelineLanesHaveOneDeterministicTotalOrder() {
        val values =
            listOf(
                message(id = "local-10", createdAtEpochMillis = 100L, localSendOrder = 10L, local = true),
                message(id = "pending-b", createdAtEpochMillis = 300L, label = "a"),
                message(id = "canonical-2", seq = 2L, createdAtEpochMillis = 50L),
                message(id = "pending-a", createdAtEpochMillis = 200L, label = "z"),
                message(id = "canonical-1", seq = 1L, createdAtEpochMillis = 900L),
                message(id = "local-2", createdAtEpochMillis = 100L, localSendOrder = 2L, local = true),
            )

        assertComparatorContract(values)
        assertEquals(
            listOf("canonical-1", "canonical-2", "pending-a", "pending-b", "local-2", "local-10"),
            values.shuffledForSortProbe().sortedWith(SharedThreadTimelineComparator).map(RaftThreadMessageItem::id),
        )
    }

    private fun assertComparatorContract(values: List<RaftThreadMessageItem>) {
        values.forEach { left ->
            values.forEach { right ->
                val forward = SharedThreadTimelineComparator.compare(left, right).normalizedSign()
                val backward = SharedThreadTimelineComparator.compare(right, left).normalizedSign()
                assertEquals(-forward, backward, "anti-symmetry failed for ${left.id} / ${right.id}")
            }
        }
        values.forEach { first ->
            values.forEach { second ->
                values.forEach { third ->
                    val firstBeforeSecond = SharedThreadTimelineComparator.compare(first, second) <= 0
                    val secondBeforeThird = SharedThreadTimelineComparator.compare(second, third) <= 0
                    if (firstBeforeSecond && secondBeforeThird) {
                        assertTrue(
                            SharedThreadTimelineComparator.compare(first, third) <= 0,
                            "transitivity failed for ${first.id} <= ${second.id} <= ${third.id}",
                        )
                    }
                }
            }
        }
    }

    private fun message(
        id: String,
        seq: Long? = null,
        createdAtEpochMillis: Long? = null,
        label: String = "localized-display-only",
        localSendOrder: Long? = null,
        local: Boolean = false,
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "user-1",
            authorName = "User",
            timestampLabel = label,
            createdAtEpochMillis = createdAtEpochMillis,
            seq = seq,
            body = id,
            deliveryState = if (local) ThreadMessageDeliverySending else ThreadMessageDeliverySent,
            localSendOrder = localSendOrder,
        )

    private fun List<RaftThreadMessageItem>.shuffledForSortProbe(): List<RaftThreadMessageItem> =
        indices.reversed().map { index -> get(index) }

    private fun Int.normalizedSign(): Int =
        when {
            this < 0 -> -1
            this > 0 -> 1
            else -> 0
        }
}
