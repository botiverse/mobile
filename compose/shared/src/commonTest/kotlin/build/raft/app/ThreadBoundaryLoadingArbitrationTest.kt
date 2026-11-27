package build.raft.app

import build.raft.app.resources.Res
import build.raft.app.resources.desc
import build.raft.app.resources.thread_offline_older_unavailable
import build.raft.app.resources.thread_retry_new
import build.raft.app.resources.thread_state_beginning_conversation
import build.raft.app.resources.thread_state_beginning_replies
import build.raft.app.resources.thread_state_loading_new
import build.raft.app.resources.thread_state_loading_older
import build.raft.app.resources.thread_state_loading_older_replies
import build.raft.app.thread.ThreadBoundaryLoadingSlot
import build.raft.app.thread.resolveVisibleThreadBoundaryLoadingSlot
import build.raft.app.thread.shouldSuppressThreadNewerBoundaryLoading
import build.raft.app.thread.threadNewerMessagesActionSpec
import build.raft.app.thread.threadOlderMessagesStatusSpec
import build.raft.app.thread.visibleForBoundaryLoadingSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure arbitration and boundary-spec interaction tests for #proj-mobile:#390:
 * at most one visible boundary loading indicator across a thread timeline while older and newer
 * paging remain parallel in [SharedThreadStore].
 */
class ThreadBoundaryLoadingArbitrationTest {

    @Test
    fun resolveVisibleSlotCoversAllCombinationsWithoutFlicker() {
        // Neither boundary loading -> null regardless of prior activeSlot.
        assertNull(resolveVisibleThreadBoundaryLoadingSlot(olderLoading = false, newerLoading = false, activeSlot = null))
        assertNull(resolveVisibleThreadBoundaryLoadingSlot(olderLoading = false, newerLoading = false, activeSlot = ThreadBoundaryLoadingSlot.Older))
        assertNull(resolveVisibleThreadBoundaryLoadingSlot(olderLoading = false, newerLoading = false, activeSlot = ThreadBoundaryLoadingSlot.Newer))

        // Only older loading -> Older regardless of prior activeSlot.
        assertEquals(
            ThreadBoundaryLoadingSlot.Older,
            resolveVisibleThreadBoundaryLoadingSlot(olderLoading = true, newerLoading = false, activeSlot = null),
        )
        assertEquals(
            ThreadBoundaryLoadingSlot.Older,
            resolveVisibleThreadBoundaryLoadingSlot(olderLoading = true, newerLoading = false, activeSlot = ThreadBoundaryLoadingSlot.Older),
        )
        assertEquals(
            ThreadBoundaryLoadingSlot.Older,
            resolveVisibleThreadBoundaryLoadingSlot(olderLoading = true, newerLoading = false, activeSlot = ThreadBoundaryLoadingSlot.Newer),
        )

        // Only newer loading -> Newer regardless of prior activeSlot.
        assertEquals(
            ThreadBoundaryLoadingSlot.Newer,
            resolveVisibleThreadBoundaryLoadingSlot(olderLoading = false, newerLoading = true, activeSlot = null),
        )
        assertEquals(
            ThreadBoundaryLoadingSlot.Newer,
            resolveVisibleThreadBoundaryLoadingSlot(olderLoading = false, newerLoading = true, activeSlot = ThreadBoundaryLoadingSlot.Older),
        )
        assertEquals(
            ThreadBoundaryLoadingSlot.Newer,
            resolveVisibleThreadBoundaryLoadingSlot(olderLoading = false, newerLoading = true, activeSlot = ThreadBoundaryLoadingSlot.Newer),
        )

        // Both loading simultaneously -> deterministic Older when starting cold, sticky activeSlot when staggered.
        assertEquals(
            ThreadBoundaryLoadingSlot.Older,
            resolveVisibleThreadBoundaryLoadingSlot(olderLoading = true, newerLoading = true, activeSlot = null),
        )
        assertEquals(
            ThreadBoundaryLoadingSlot.Older,
            resolveVisibleThreadBoundaryLoadingSlot(olderLoading = true, newerLoading = true, activeSlot = ThreadBoundaryLoadingSlot.Older),
        )
        assertEquals(
            ThreadBoundaryLoadingSlot.Newer,
            resolveVisibleThreadBoundaryLoadingSlot(olderLoading = true, newerLoading = true, activeSlot = ThreadBoundaryLoadingSlot.Newer),
        )
    }

    @Test
    fun olderLoadingSpecsAndStandInLoadingRespectActiveSlotWhileNonLoadingLabelsNeverSuppress() {
        // Older paging loading (channel + reply thread) suppresses when Newer owns the slot.
        val olderPagingSpec =
            assertNotNull(
                threadOlderMessagesStatusSpec(
                    canLoadMoreBefore = true,
                    olderMessagesStatus = "loading",
                    replyThread = false,
                )
            )
        assertTrue(olderPagingSpec.loading)
        assertEquals(Res.string.thread_state_loading_older.desc(), olderPagingSpec.label)
        assertEquals(olderPagingSpec, olderPagingSpec.visibleForBoundaryLoadingSlot(ThreadBoundaryLoadingSlot.Older))
        assertNull(olderPagingSpec.visibleForBoundaryLoadingSlot(ThreadBoundaryLoadingSlot.Newer))
        assertNull(olderPagingSpec.visibleForBoundaryLoadingSlot(null))

        // Durable stand-in older loading (canLoadMoreBefore == false, spec.loading == true) also arbitrates.
        val standInSpec =
            assertNotNull(
                threadOlderMessagesStatusSpec(
                    canLoadMoreBefore = false,
                    olderMessagesStatus = "idle",
                    replyThread = true,
                    durableLastKnown = true,
                    durableReachedOldest = false,
                    mainLoadFailed = false,
                )
            )
        assertTrue(standInSpec.loading)
        assertEquals(Res.string.thread_state_loading_older_replies.desc(), standInSpec.label)
        assertEquals(standInSpec, standInSpec.visibleForBoundaryLoadingSlot(ThreadBoundaryLoadingSlot.Older))
        assertNull(standInSpec.visibleForBoundaryLoadingSlot(ThreadBoundaryLoadingSlot.Newer))

        // Non-loading labels are NEVER suppressed, even when Newer owns the loading slot.
        val beginningConversation =
            assertNotNull(threadOlderMessagesStatusSpec(canLoadMoreBefore = false, replyThread = false))
        assertFalse(beginningConversation.loading)
        assertEquals(Res.string.thread_state_beginning_conversation.desc(), beginningConversation.label)
        assertEquals(beginningConversation, beginningConversation.visibleForBoundaryLoadingSlot(ThreadBoundaryLoadingSlot.Newer))
        assertEquals(beginningConversation, beginningConversation.visibleForBoundaryLoadingSlot(null))

        val beginningReplies =
            assertNotNull(threadOlderMessagesStatusSpec(canLoadMoreBefore = false, replyThread = true))
        assertFalse(beginningReplies.loading)
        assertEquals(Res.string.thread_state_beginning_replies.desc(), beginningReplies.label)
        assertEquals(beginningReplies, beginningReplies.visibleForBoundaryLoadingSlot(ThreadBoundaryLoadingSlot.Newer))

        val offlineUnavailable =
            assertNotNull(
                threadOlderMessagesStatusSpec(
                    canLoadMoreBefore = false,
                    durableLastKnown = true,
                    durableReachedOldest = false,
                    mainLoadFailed = true,
                )
            )
        assertFalse(offlineUnavailable.loading)
        assertEquals(Res.string.thread_offline_older_unavailable.desc(), offlineUnavailable.label)
        assertEquals(offlineUnavailable, offlineUnavailable.visibleForBoundaryLoadingSlot(ThreadBoundaryLoadingSlot.Newer))
        assertEquals(offlineUnavailable, offlineUnavailable.visibleForBoundaryLoadingSlot(null))
    }

    @Test
    fun newerBoundarySuppressionMatchesActiveSlotAndKeepsErrorActionSpecIntact() {
        assertTrue(shouldSuppressThreadNewerBoundaryLoading(visibleLoadingSlot = ThreadBoundaryLoadingSlot.Older))
        assertTrue(shouldSuppressThreadNewerBoundaryLoading(visibleLoadingSlot = null))
        assertFalse(shouldSuppressThreadNewerBoundaryLoading(visibleLoadingSlot = ThreadBoundaryLoadingSlot.Newer))
        assertTrue(
            shouldSuppressThreadNewerBoundaryLoading(
                visibleLoadingSlot = ThreadBoundaryLoadingSlot.Newer,
                replyLoadingSkeletonVisible = true,
            )
        )

        val loadingSpec = threadNewerMessagesActionSpec("loading")
        assertEquals(Res.string.thread_state_loading_new.desc(), loadingSpec.label)
        assertNull(loadingSpec.event)

        val errorSpec = threadNewerMessagesActionSpec("error")
        assertEquals(Res.string.thread_retry_new.desc(), errorSpec.label)
        assertEquals("retryLoadNewer", errorSpec.event)
    }
}
