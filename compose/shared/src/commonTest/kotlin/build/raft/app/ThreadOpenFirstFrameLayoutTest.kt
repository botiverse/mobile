package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * artin 11-03 (Compose 版, 「对话流出现之后 抖动」): after a conversation's messages appeared, the
 * bottom-pinned list jumped up by the composer's height a few frames later. A cached timeline is
 * shown while access is still refreshing, but the composer mounts only once the footer is ready,
 * so the first content frames had no composer. The dock keeps the composer's resting height while
 * a composer is still expected, and the first frame reads per-server flags with the shell's
 * server, so the old Chat / Tasks / Files tab row does not show for a frame and go.
 */
class ThreadOpenFirstFrameLayoutTest {
    private val cachedContent =
        RaftThreadScreenPayload(
            status = "content",
            messages = listOf(RaftThreadMessageItem(id = "m1", authorId = "u1", authorName = "Artin", body = "cached"))
        )

    @Test
    fun aCachedTimelineShownBeforeAccessResolvesKeepsTheComposerSlot() {
        val slice = cachedContent.toThreadTimelineSlice()
        // The state the recording shows: the timeline is mounted, the footer is not ready yet.
        assertEquals(
            ThreadRouteMountPlan.FeatureSurfaces,
            cachedContent.threadRouteMountPlan(timelineSlice = slice, accessResolution = ThreadAccessResolution.Loading)
        )
        assertFalse(cachedContent.isThreadPrimaryFooterReady(slice, ThreadAccessResolution.Loading))
        assertTrue(reserves(cachedContent, ThreadAccessResolution.Loading))
    }

    @Test
    fun noSlotWhereNoComposerIsComing() {
        assertFalse(reserves(cachedContent, ThreadAccessResolution.Error))
        assertFalse(reserves(cachedContent, ThreadAccessResolution.NoPermission))
        assertFalse(reserves(cachedContent.copy(status = "error"), ThreadAccessResolution.Loading))
        assertFalse(reserves(cachedContent.copy(joined = false), ThreadAccessResolution.Loading))
        assertFalse(reserves(cachedContent.copy(showComposer = false), ThreadAccessResolution.Loading))
        assertFalse(reserves(cachedContent.copy(selectedTab = "tasks"), ThreadAccessResolution.Loading))
        assertFalse(reserves(cachedContent, ThreadAccessResolution.Loading, composerLocked = true))
    }

    @Test
    fun flagsReadTheShellServerUntilTheSessionIsCaptured() {
        assertEquals("srv-shell", threadFeatureFlagServerId(sessionServerId = null, shellServerId = "srv-shell"))
        assertEquals("srv-session", threadFeatureFlagServerId(sessionServerId = " srv-session ", shellServerId = "srv-shell"))
        assertEquals("srv-shell", threadFeatureFlagServerId(sessionServerId = " ", shellServerId = "srv-shell"))
        assertNull(threadFeatureFlagServerId(sessionServerId = null, shellServerId = null))
    }

    private fun reserves(
        payload: RaftThreadScreenPayload,
        accessResolution: ThreadAccessResolution,
        composerLocked: Boolean = false
    ): Boolean =
        threadReservesPendingComposerSlot(
            accessResolution = accessResolution,
            payloadStatus = payload.status,
            joined = payload.joined,
            showComposer = payload.showComposer,
            selectedTab = payload.selectedTab,
            composerLocked = composerLocked
        )
}
