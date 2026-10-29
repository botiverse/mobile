package build.raft.app

import build.raft.app.data.ChannelFileItem
import build.raft.app.data.ChannelFilesSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftConversationArtifactsApi
import build.raft.app.data.ScreenError
import build.raft.app.data.authSessionFixture
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Files tab's Artifacts beside its files ([SharedThreadFilesStore], web #11580). */
class SharedThreadFilesArtifactsTest {
    private val route = RaftThreadScreenPayload(instanceId = "files-route", sourceThreadId = "channel-1")
    private val scope = ThreadFilesArtifactsScope(serverId = "server-1")

    @Test
    fun theArtifactsAndTheFilesLoadTogether() = runTest {
        val artifacts = FakeArtifactsApi(page(listOf("pt_b", "pt_a"), next = "c1"))
        val store = started(files("f-1"), artifacts, scope)

        assertEquals(listOf("f-1"), fileIds(store))
        assertEquals("content", store.state.value.payload.status)
        assertEquals(listOf("pt_b", "pt_a"), artifactIds(store))
        assertEquals("c1", store.state.value.artifacts.nextCursor)
        assertEquals(listOf(Asked("server-1", "channel-1", null)), artifacts.asked)

        // Show More: the next page after them, and no more.
        artifacts.answers += page(listOf("pt_z"), next = null)
        store.dispatch(SharedThreadFilesIntent.LoadMoreArtifacts)
        runCurrent()
        assertEquals(listOf("pt_b", "pt_a", "pt_z"), artifactIds(store))
        assertEquals(null, store.state.value.artifacts.nextCursor)
        assertEquals(Asked("server-1", "channel-1", "c1"), artifacts.asked.last())
        store.clear()
    }

    @Test
    fun aFailedArtifactsListKeepsTheFilesAndShowsNone() = runTest {
        // 403 ARTIFACTS_NOT_ENABLED, 404 TARGET_NOT_FOUND, 429, a network failure: all null from the API.
        val artifacts = FakeArtifactsApi(failure())
        val store = started(files("f-1", "f-2"), artifacts, scope)

        assertEquals(listOf("f-1", "f-2"), fileIds(store))
        assertEquals("content", store.state.value.payload.status)
        assertEquals(null, store.state.value.payload.errorCode)
        assertEquals(emptyList(), artifactIds(store))
        assertEquals(false, store.state.value.artifacts.loading)
        store.clear()
    }

    @Test
    fun noFilesButArtifactsStillListAndPullToRefreshReadsBothAgain() = runTest {
        val artifacts = FakeArtifactsApi(page(listOf("pt_a"), next = null))
        val repository = recorder(DataResult.Empty(EmptyReason.NoMessages))
        val store = started(repository, artifacts, scope)
        assertEquals("empty", store.state.value.payload.status)
        assertEquals(listOf("pt_a"), artifactIds(store))

        // A pull reads both again; a failed reload of the Artifacts keeps the rows shown.
        repository.channelFilesResults += files("f-1")
        artifacts.answers += failure()
        store.dispatch(SharedThreadFilesIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("f-1"), fileIds(store))
        assertEquals(listOf("pt_a"), artifactIds(store))
        assertEquals(2, artifacts.asked.size)

        repository.channelFilesResults += files("f-1")
        artifacts.answers += page(listOf("pt_c", "pt_a"), next = null)
        store.dispatch(SharedThreadFilesIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("pt_c", "pt_a"), artifactIds(store))
        store.clear()
    }

    @Test
    fun theFlagOffReadsNoArtifacts() = runTest {
        val artifacts = FakeArtifactsApi(page(listOf("pt_a"), next = null))
        val store = started(files("f-1"), artifacts, artifactsScope = null)
        assertEquals(listOf("f-1"), fileIds(store))
        assertEquals(emptyList(), artifactIds(store))
        assertEquals(emptyList(), artifacts.asked)

        // The flag resolves on while the tab shows: its Artifacts now, the files untouched.
        store.dispatch(SharedThreadFilesIntent.Start(route, scope))
        runCurrent()
        assertEquals(listOf("pt_a"), artifactIds(store))
        assertEquals(listOf("f-1"), fileIds(store))
        store.clear()
    }

    @Test
    fun anAnswerForAnotherConversationIsDropped() = runTest {
        val artifacts = FakeArtifactsApi(page(listOf("pt_a"), next = null))
        val repository = recorder(files("f-1"))
        val store = started(repository, artifacts, scope)
        val release = CompletableDeferred<Unit>()
        artifacts.release = release
        repository.channelFilesResults += files("f-1")
        artifacts.answers += page(listOf("pt_old"), next = null)
        store.dispatch(SharedThreadFilesIntent.PullRefresh)
        runCurrent()

        val other = RaftThreadScreenPayload(instanceId = "files-route-2", sourceThreadId = "channel-2")
        store.dispatch(SharedThreadFilesIntent.Start(other, scope))
        release.complete(Unit)
        runCurrent()
        assertEquals(emptyList(), artifactIds(store))
        store.clear()
    }

    private fun TestScope.started(
        first: DataResult<ChannelFilesSnapshot>,
        artifacts: FakeArtifactsApi,
        artifactsScope: ThreadFilesArtifactsScope?,
    ): SharedThreadFilesStore = started(recorder(first), artifacts, artifactsScope)

    private fun TestScope.started(
        repository: ReplyThreadAccessConversationRepositoryRecorder,
        artifacts: FakeArtifactsApi,
        artifactsScope: ThreadFilesArtifactsScope?,
    ): SharedThreadFilesStore {
        val store =
            SharedThreadFilesStore(
                dataSource =
                    testSharedThreadDataSource(
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = backgroundScope,
                artifactsApi = artifacts,
            )
        store.dispatch(SharedThreadFilesIntent.Start(route, artifactsScope))
        store.dispatch(SharedThreadFilesIntent.Refresh("initial_tab"))
        runCurrent()
        return store
    }

    private data class Asked(val serverId: String, val conversationId: String, val cursor: String?)

    /** Answers in order, then the last one again. */
    private class FakeArtifactsApi(first: HomeNetworkResponse) : RaftConversationArtifactsApi {
        val answers = ArrayDeque(listOf(first))
        val asked = mutableListOf<Asked>()
        var release: CompletableDeferred<Unit>? = null
        private var last = first

        override suspend fun conversationArtifacts(serverId: String, conversationId: String, cursor: String?): HomeNetworkResponse {
            asked += Asked(serverId, conversationId, cursor)
            val answer = answers.removeFirstOrNull() ?: last
            last = answer
            release?.await()
            return answer
        }
    }

    private fun page(ids: List<String>, next: String?): HomeNetworkResponse {
        val rows =
            ids.joinToString(",") { id ->
                """{"card":{"id":"$id","url":"https://app.raft.build/artifacts/$id","title":"Title $id","description":null,"kind":"html",""" +
                    """"facts":{"sizeBytes":1,"fileCount":1},"preview":{"kind":"none"},"version":1,"pinned":false,"latestVersion":1,""" +
                    """"author":{"type":"user","id":"u1","name":"u","displayName":"U"},"createdAt":"2026-10-28T00:00:00.000Z",""" +
                    """"updatedAt":"2026-10-28T00:00:00.000Z","commentCount":0,""" +
                    """"home":{"kind":"conversation","conversation":{"id":"channel-1","name":"general","type":"channel"}}}}"""
            }
        val cursor = next?.let { "\"$it\"" } ?: "null"
        return HomeNetworkResponse(200, """{"rows":[$rows],"nextCursor":$cursor}""")
    }

    private fun failure() = HomeNetworkResponse(429, null, errorBodyText = """{"error":"rate limited","code":"RATE_LIMITED"}""")

    private fun recorder(first: DataResult<ChannelFilesSnapshot>) =
        ReplyThreadAccessConversationRepositoryRecorder(
            replyResult = DataResult.Error(ScreenError("unused", retryable = false))
        ).apply { channelFilesResults += first }

    private fun files(vararg ids: String): DataResult<ChannelFilesSnapshot> =
        DataResult.Content(
            ChannelFilesSnapshot(
                channelId = ChannelId("channel-1"),
                items =
                    ids.map { id ->
                        ChannelFileItem(
                            attachment = Attachment(id = AttachmentId(id), name = "$id.png", mimeType = "image/png", sizeLabel = "1 KB"),
                        )
                    },
            )
        )

    private fun fileIds(store: SharedThreadFilesStore) = store.state.value.payload.items.map { it.attachmentId }

    private fun artifactIds(store: SharedThreadFilesStore) = store.state.value.artifacts.items.map { it.summary.id }
}

