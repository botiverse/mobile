package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftMediaPlayerBudgetTest {
    @Test
    fun inlineConcurrencyCapEvictsOldestIdlePlayerFirstAcrossBurst() {
        val budget = RaftMediaPlayerBudget(maxInlinePlayers = 2)
        val evicted = mutableListOf<String>()

        // Simulate 32 inline video cards registering sequentially (the Hands a4f86aed failure mode).
        val ids = mutableListOf<Long>()
        repeat(32) { idx ->
            val label = "v$idx"
            ids += budget.register(role = RaftMediaPlayerRole.InlineVideo) { evicted += label }
            assertTrue(
                budget.activeInlineCount() <= 2,
                "active inline count must never exceed cap at idx=$idx: ${budget.activeInlineCount()}",
            )
        }

        assertEquals(2, budget.activeInlineCount())
        assertEquals(30, evicted.size)
        assertEquals((0 until 30).map { "v$it" }, evicted)

        ids.forEach(budget::unregister)
        assertEquals(0, budget.activeCount())
    }

    @Test
    fun playingAndRecentlyTouchedInlinePlayersSurviveAheadOfIdleLru() {
        val budget = RaftMediaPlayerBudget(maxInlinePlayers = 2)
        val evicted = mutableListOf<String>()

        val first = budget.register(role = RaftMediaPlayerRole.InlineVideo) { evicted += "first" }
        val second = budget.register(role = RaftMediaPlayerRole.InlineVideo) { evicted += "second" }
        // Mark `first` as playing so `second` (idle) is evicted when `third` arrives.
        budget.markActivity(first, playing = true)

        val third = budget.register(role = RaftMediaPlayerRole.InlineAudio) { evicted += "third" }
        assertEquals(listOf("second"), evicted)
        assertEquals(2, budget.activeInlineCount())

        // Touch `third` and pause `first`, then open `fourth`: `first` is now oldest idle LRU.
        budget.markActivity(first, playing = false, touch = false)
        budget.markActivity(third, playing = false, touch = true)
        val fourth = budget.register(role = RaftMediaPlayerRole.InlineVideo) { evicted += "fourth" }
        assertEquals(listOf("second", "first"), evicted)
        assertEquals(2, budget.activeInlineCount())

        budget.unregister(third)
        budget.unregister(fourth)
        assertEquals(0, budget.activeCount())
    }

    @Test
    fun fullscreenAndFilePreviewAreProtectedAndFilePreviewReclaimsIdleInlinePlayers() {
        val budget = RaftMediaPlayerBudget(maxInlinePlayers = 2)
        val evicted = mutableListOf<String>()

        val fullscreenInline =
            budget.register(role = RaftMediaPlayerRole.InlineVideo, playing = true, fullscreen = true) {
                evicted += "fullscreen"
            }
        val idleInline =
            budget.register(role = RaftMediaPlayerRole.InlineVideo, playing = false) {
                evicted += "idle"
            }

        // Opening FilePreview evicts idle inline players but preserves fullscreen and itself.
        val preview =
            budget.register(role = RaftMediaPlayerRole.FilePreview) {
                evicted += "preview"
            }
        assertEquals(listOf("idle"), evicted)
        assertEquals(1, budget.activeInlineCount())
        assertEquals(2, budget.activeCount())

        // New inline cards cannot evict either FilePreview or the fullscreen inline player.
        val inlineA = budget.register(role = RaftMediaPlayerRole.InlineVideo) { evicted += "inlineA" }
        val inlineB = budget.register(role = RaftMediaPlayerRole.InlineVideo) { evicted += "inlineB" }
        assertEquals(listOf("idle", "inlineA"), evicted)
        assertFalse("fullscreen" in evicted)
        assertFalse("preview" in evicted)

        budget.unregister(fullscreenInline)
        budget.unregister(idleInline)
        budget.unregister(preview)
        budget.unregister(inlineA)
        budget.unregister(inlineB)
        assertEquals(0, budget.activeCount())
    }

    @Test
    fun reentrantUnregisterInsideOnEvictAndEvictAllIdleInlineStayConsistent() {
        val budget = RaftMediaPlayerBudget(maxInlinePlayers = 1)
        var firstId = -1L
        var evictedCount = 0
        firstId =
            budget.register(role = RaftMediaPlayerRole.InlineVideo) {
                evictedCount += 1
                // Simulate a player releasing and calling unregister() synchronously inside onEvict.
                budget.unregister(firstId)
            }
        val secondId = budget.register(role = RaftMediaPlayerRole.InlineVideo) { evictedCount += 1 }
        assertEquals(1, evictedCount)
        assertEquals(1, budget.activeInlineCount())

        // Backgrounding evicts remaining idle inline player without touching FilePreview.
        val previewId = budget.register(role = RaftMediaPlayerRole.FilePreview) { evictedCount += 10 }
        assertEquals(2, evictedCount)
        assertEquals(0, budget.activeInlineCount())
        assertEquals(1, budget.activeCount())

        // Re-register an idle inline card and verify evictAllIdleInline clears it cleanly.
        val thirdId = budget.register(role = RaftMediaPlayerRole.InlineAudio) { evictedCount += 1 }
        assertEquals(1, budget.evictAllIdleInline())
        assertEquals(3, evictedCount)
        assertEquals(0, budget.activeInlineCount())
        assertEquals(1, budget.activeCount())

        budget.unregister(secondId)
        budget.unregister(thirdId)
        budget.unregister(previewId)
        assertEquals(0, budget.activeCount())
    }

    @Test
    fun visibilityAndPrepareOnAttachPoliciesGateInlineDecodersCleanly() {
        assertTrue(threadInlineMediaShouldMountPlayer(pageActive = true, onScreen = true, shareCaptureActive = false))
        assertFalse(threadInlineMediaShouldMountPlayer(pageActive = false, onScreen = true, shareCaptureActive = false))
        assertFalse(threadInlineMediaShouldMountPlayer(pageActive = true, onScreen = false, shareCaptureActive = false))
        assertFalse(threadInlineMediaShouldMountPlayer(pageActive = true, onScreen = true, shareCaptureActive = true))

        assertFalse(raftMediaPlayerShouldPrepareOnAttach(RaftMediaPlayerRole.InlineAudio, audioOnly = true))
        assertFalse(raftMediaPlayerShouldPrepareOnAttach(RaftMediaPlayerRole.InlineAudio, audioOnly = false))
        assertTrue(raftMediaPlayerShouldPrepareOnAttach(RaftMediaPlayerRole.InlineVideo, audioOnly = false))
        assertFalse(raftMediaPlayerShouldPrepareOnAttach(RaftMediaPlayerRole.InlineVideo, audioOnly = true))
        assertTrue(raftMediaPlayerShouldPrepareOnAttach(RaftMediaPlayerRole.FilePreview, audioOnly = false))
        assertTrue(raftMediaPlayerShouldPrepareOnAttach(RaftMediaPlayerRole.FilePreview, audioOnly = true))
    }
}
