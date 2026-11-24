package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Cross-surface own Follow/Unfollow store (web `artifactOwnFollows.ts` + `setArtifactFollowingHere`,
 * botiverse/slock #16357).
 */
class SharedArtifactFollowStoreTest {
    @Test
    fun setFollowingIsSingleFlightRecordsAnswerPatchesSummariesAndEmitsEvent() = runTest {
        val gate = CompletableDeferred<ArtifactsHomeLoad<Boolean>>()
        var calls = 0
        val patched = mutableListOf<Triple<String, String, Boolean>>()
        val store =
            SharedArtifactFollowStore(
                setFollowingRequest = { _, _, _ ->
                    calls++
                    gate.await()
                },
                onFollowUpdated = { serverId, artifactId, following ->
                    patched += Triple(serverId, artifactId, following)
                },
            )
        val events = mutableListOf<ArtifactOwnFollowEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.toList(events) }

        val first = launch { store.setFollowing("s-1", "pt_a", false) }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(store.state.value.isPending("s-1", "pt_a"))
        // Duplicate while in flight returns null without starting a second request.
        assertNull(store.setFollowing("s-1", "pt_a", false))
        assertEquals(1, calls)

        gate.complete(ArtifactsHomeLoad.Ok(false))
        first.join()
        advanceUntilIdle()

        assertFalse(store.state.value.isPending("s-1", "pt_a"))
        assertEquals(false, store.state.value.followingOf("s-1", "pt_a"))
        assertEquals(listOf(Triple("s-1", "pt_a", false)), patched)
        assertEquals(1, events.size)
        assertEquals("s-1", events.single().serverId)
        assertEquals("pt_a", events.single().artifactId)
        assertEquals(false, events.single().following)
    }

    @Test
    fun failedWriteClearsPendingWithoutOverwritingState() = runTest {
        val store =
            SharedArtifactFollowStore(
                setFollowingRequest = { _, _, _ -> ArtifactsHomeLoad.Failed(ScreenError("boom", traceId = "tr-1")) },
            )
        val res = store.setFollowing("s-1", "pt_a", false)
        assertIs<ArtifactsHomeLoad.Failed>(res)
        assertFalse(store.state.value.isPending("s-1", "pt_a"))
        assertNull(store.state.value.followingOf("s-1", "pt_a"))
    }

    @Test
    fun dropServerAndClearAllFenceInFlightWritesAndIsolateServers() = runTest {
        val gate = CompletableDeferred<ArtifactsHomeLoad<Boolean>>()
        val store = SharedArtifactFollowStore(setFollowingRequest = { _, _, _ -> gate.await() })
        store.reportOwnFollow("s-1", "pt_a", true)
        store.reportOwnFollow("s-2", "pt_a", false)

        val job = launch { store.setFollowing("s-1", "pt_b", false) }
        runCurrent()
        store.dropServer("s-1")
        gate.complete(ArtifactsHomeLoad.Ok(false))
        job.join()

        assertNull(store.state.value.followingOf("s-1", "pt_a"))
        assertNull(store.state.value.followingOf("s-1", "pt_b"), "in-flight write from dropped server is fenced")
        assertEquals(false, store.state.value.followingOf("s-2", "pt_a"), "other server preserved")

        store.clearAll()
        assertNull(store.state.value.followingOf("s-2", "pt_a"))
    }
}
