package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The viewer bar's store: counts read on open and when the comments list closes (the page's
 * `#panel=` going from comments to closed), never blocking; following optimistic with roll back.
 */
class SharedArtifactViewerBarStoreTest {
    private val summary =
        ArtifactSummary(
            id = "pt_1",
            title = "q4-launch-plan.html",
            description = null,
            kind = ArtifactKind.Html,
            version = 3,
            pinned = false,
            latestVersion = 3,
            home = ArtifactHome.Conversation("q4-launch", dm = false),
            commentCount = 3,
        )

    private fun details(comments: Int = 3, following: Boolean = false) =
        ArtifactViewerDetails(summary.copy(commentCount = comments), listOf(ArtifactViewerVersion(3, "Kirby", "2026-11-01T08:00:00Z")), following, viewerKey = "user:me")

    /** [readers] others read the current version. */
    private fun readers(readers: Int) = DataResult.Content(ArtifactPresence(readCurrentVersion = (1..readers).map { "agent:a$it" }))

    private class FakeRepository : ArtifactViewerRepository {
        val detailsAnswers = ArrayDeque<DataResult<ArtifactViewerDetails>>()
        val readAnswers = ArrayDeque<DataResult<ArtifactPresence>>()
        var followGate: CompletableDeferred<DataResult<Boolean>>? = null
        val detailsCalls = mutableListOf<Int?>()
        var readCalls = 0
        val followCalls = mutableListOf<Boolean>()

        override suspend fun details(serverId: String, artifactId: String, version: Int?): DataResult<ArtifactViewerDetails> {
            detailsCalls += version
            return detailsAnswers.removeFirstOrNull() ?: DataResult.Error(ScreenError("network"))
        }

        override suspend fun presence(serverId: String, artifactId: String): DataResult<ArtifactPresence> {
            readCalls += 1
            return readAnswers.removeFirstOrNull() ?: DataResult.Error(ScreenError("network"))
        }

        override suspend fun setFollowing(serverId: String, artifactId: String, following: Boolean): DataResult<Boolean> {
            followCalls += following
            return followGate!!.await()
        }
    }

    private fun TestScope.store(repository: FakeRepository) = SharedArtifactViewerBarStore(repository, this, "srv-1", "pt_1")

    @Test
    fun opensWithTheCountsAndReadsThemAgainOnlyWhenCommentsClose() = runTest {
        val repository = FakeRepository()
        repository.detailsAnswers += DataResult.Content(details(comments = 3))
        repository.readAnswers += readers(4)
        val store = store(repository)

        store.dispatch(ArtifactViewerBarIntent.Load(version = null))
        runCurrent()
        assertEquals(3, store.state.value.details?.summary?.commentCount)
        // Four others and "You" (on the current version), as the web counts it.
        assertEquals(5, store.state.value.readCount)
        assertEquals(false, store.state.value.following)
        assertEquals(listOf<Int?>(null), repository.detailsCalls)

        // Opening comments, or the versions list, reads nothing.
        store.dispatch(ArtifactViewerBarIntent.PanelObserved(ArtifactViewerPanel.Comments))
        store.dispatch(ArtifactViewerBarIntent.PanelObserved(ArtifactViewerPanel.Comments))
        runCurrent()
        assertEquals(1, repository.detailsCalls.size)
        assertEquals(ArtifactViewerPanel.Comments, store.state.value.panel)

        // Comments close (the page writes no hash): both counts are read again, at the version shown.
        repository.detailsAnswers += DataResult.Content(details(comments = 4))
        repository.readAnswers += readers(5)
        store.dispatch(ArtifactViewerBarIntent.VersionShown(2))
        store.dispatch(ArtifactViewerBarIntent.PanelObserved(ArtifactViewerPanel.None))
        runCurrent()
        assertEquals(listOf(null, 2), repository.detailsCalls)
        assertEquals(2, repository.readCalls)
        assertEquals(4, store.state.value.details?.summary?.commentCount)
        assertEquals(6, store.state.value.readCount)

        // The versions list opening and closing reads nothing.
        store.dispatch(ArtifactViewerBarIntent.PanelObserved(ArtifactViewerPanel.Versions))
        store.dispatch(ArtifactViewerBarIntent.PanelObserved(ArtifactViewerPanel.None))
        runCurrent()
        assertEquals(2, repository.detailsCalls.size)
    }

    @Test
    fun aFailedReadHidesOnlyWhatItWouldShowAndKeepsWhatWasShown() = runTest {
        val repository = FakeRepository()
        repository.readAnswers += readers(4)
        val store = store(repository)
        store.dispatch(ArtifactViewerBarIntent.Load(null))
        runCurrent()
        assertNull(store.state.value.details)
        assertNull(store.state.value.following)
        assertTrue(store.state.value.detailsFailed)
        // Without the viewer answer, the others only (no "You").
        assertEquals(4, store.state.value.readCount)

        // A later failure keeps the counts already shown.
        repository.detailsAnswers += DataResult.Content(details())
        store.dispatch(ArtifactViewerBarIntent.Load(null))
        runCurrent()
        assertFalse(store.state.value.detailsFailed)
        store.dispatch(ArtifactViewerBarIntent.Load(null))
        runCurrent()
        assertEquals(3, store.state.value.details?.summary?.commentCount)
        assertEquals(5, store.state.value.readCount)
        assertFalse(store.state.value.detailsFailed)
    }

    @Test
    fun followIsShownAtOnceAndTakenBackWhenTheWriteFails() = runTest {
        val repository = FakeRepository()
        repository.detailsAnswers += DataResult.Content(details(following = false))
        val store = store(repository)
        store.dispatch(ArtifactViewerBarIntent.Load(null))
        runCurrent()

        repository.followGate = CompletableDeferred()
        store.dispatch(ArtifactViewerBarIntent.ToggleFollow)
        runCurrent()
        assertEquals(true, store.state.value.following)
        assertTrue(store.state.value.followInFlight)
        // A second toggle while the first is out is ignored.
        store.dispatch(ArtifactViewerBarIntent.ToggleFollow)
        repository.followGate!!.complete(DataResult.Error(ScreenError("http_429")))
        runCurrent()
        assertEquals(listOf(true), repository.followCalls)
        assertEquals(false, store.state.value.following)
        assertFalse(store.state.value.followInFlight)

        // Succeeds: the server's answer stays.
        repository.followGate = CompletableDeferred(DataResult.Content(true))
        store.dispatch(ArtifactViewerBarIntent.ToggleFollow)
        runCurrent()
        assertEquals(true, store.state.value.following)
        assertEquals(listOf(true, true), repository.followCalls)
    }

    @Test
    fun aReadLandingWhileAToggleIsOutDoesNotUndoIt() = runTest {
        val repository = FakeRepository()
        repository.detailsAnswers += DataResult.Content(details(following = false))
        val store = store(repository)
        store.dispatch(ArtifactViewerBarIntent.Load(null))
        runCurrent()
        repository.followGate = CompletableDeferred()
        store.dispatch(ArtifactViewerBarIntent.ToggleFollow)
        repository.detailsAnswers += DataResult.Content(details(following = false))
        store.dispatch(ArtifactViewerBarIntent.Load(null))
        runCurrent()
        assertEquals(true, store.state.value.following)
        repository.followGate!!.complete(DataResult.Content(true))
        runCurrent()
        assertEquals(true, store.state.value.following)
    }

    @Test
    fun noFollowBeforeFollowingIsKnown() = runTest {
        val repository = FakeRepository()
        val store = store(repository)
        store.dispatch(ArtifactViewerBarIntent.ToggleFollow)
        runCurrent()
        assertTrue(repository.followCalls.isEmpty())
        assertNull(store.state.value.following)
    }
}
