package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadUnreadJumpProvenanceTest {
    private fun message(id: String, seq: Long?) =
        KuiklyThreadMessageItem(
            id = id,
            authorId = "author-$id",
            authorName = "Author $id",
            seq = seq,
        )

    private fun payload(
        vararg messages: KuiklyThreadMessageItem,
        gapRefillMessageIds: Set<String> = emptySet(),
    ) = KuiklyThreadScreenPayload(
        status = "content",
        messages = messages.toList(),
        gapRefillMessageIds = gapRefillMessageIds,
    )

    @Test
    fun gapRefillTagRecordsOnlyNewlyMergedIds() {
        val base =
            payload(
                message("m10", 10L),
                message("m11", 11L),
            )
        val merged =
            base
                .mergePagedMessagesForTest(
                    listOf(
                        message("m12", 12L),
                        message("m13", 13L),
                        message("m11", 11L),
                    )
                )
                .withGapRefillTagFrom(base)

        assertEquals(setOf("m12", "m13"), merged.gapRefillMessageIds)
    }

    @Test
    fun gapRefillTagAccumulatesAcrossRefillsAndSurvivesProjection() {
        val base =
            payload(
                message("m10", 10L),
                gapRefillMessageIds = setOf("m11"),
            )
        val merged =
            base
                .mergePagedMessagesForTest(listOf(message("m12", 12L)))
                .withGapRefillTagFrom(base)
        val slice = merged.toThreadTimelineSlice()

        assertEquals(setOf("m11", "m12"), slice.gapRefillMessageIds)
        assertTrue(merged.messages.any { it.id == "m12" })
    }

    @Test
    fun tagWithNoNewMessagesIsIdentity() {
        val base = payload(message("m10", 10L))
        val merged = base.withGapRefillTagFrom(base)

        assertEquals(emptySet(), merged.gapRefillMessageIds)
    }

    @Test
    fun liveRealtimeArrivalPathDoesNotTagAndKeepsPriorRefills() {
        val base =
            payload(
                message("m10", 10L),
                gapRefillMessageIds = setOf("m9"),
            )
        // Live realtime merges go through copy-style merges (mergePagedMessages),
        // never withGapRefillTagFrom; prior refills must survive untouched.
        val merged =
            base.mergePagedMessagesForTest(listOf(message("m11", 11L)))

        assertEquals(setOf("m9"), merged.gapRefillMessageIds)
    }
}

/** Test seam mirroring the store's internal merge call shape. */
private fun KuiklyThreadScreenPayload.mergePagedMessagesForTest(
    pageMessages: List<KuiklyThreadMessageItem>
): KuiklyThreadScreenPayload =
    mergePagedMessages(
        pageMessages = pageMessages,
        before = false,
        pageSize = 20,
    )
