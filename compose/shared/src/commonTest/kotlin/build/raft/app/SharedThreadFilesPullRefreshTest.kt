package build.raft.app

import build.raft.app.data.ChannelFileItem
import build.raft.app.data.ChannelFilesSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
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

/** Pull-to-refresh of the channel / thread Files tab ([SharedThreadFilesIntent.PullRefresh]). */
class SharedThreadFilesPullRefreshTest {
    private val route = RaftThreadScreenPayload(instanceId = "files-route", sourceThreadId = "channel-1")

    @Test
    fun pullRefreshKeepsTheListWhileItRunsAndAfterAFailure() = runTest {
        // Each case: the reload's answer, then the rows, status and error left on screen.
        data class Case(
            val label: String,
            val answer: DataResult<ChannelFilesSnapshot>,
            val ids: List<String>,
            val status: String,
            val errorCode: String?,
        )
        val cases =
            listOf(
                Case("content", files("a-2", "a-1"), listOf("a-2", "a-1"), "content", null),
                Case("error", DataResult.Error(ScreenError("thread_files_network", retryable = true)), listOf("a-1"), "content", "thread_files_network"),
            )
        cases.forEach { case ->
            val repository = recorder(files("a-1"))
            val store = loadedStore(repository)
            val release = CompletableDeferred<Unit>()
            repository.releaseChannelFiles = release
            repository.channelFilesResults += case.answer

            store.dispatch(SharedThreadFilesIntent.PullRefresh)
            runCurrent()
            store.dispatch(SharedThreadFilesIntent.PullRefresh)
            runCurrent()
            assertEquals(true, store.state.value.refreshing, case.label)
            assertEquals("content", store.state.value.payload.status, "${case.label}: no loading state over the list")
            assertEquals(listOf("a-1"), ids(store), case.label)
            assertEquals(2, repository.channelFilesRequests.size, "${case.label}: single-flight")
            assertEquals(ChannelId("channel-1") to null, repository.channelFilesRequests.last(), "${case.label}: first page")

            release.complete(Unit)
            runCurrent()
            assertEquals(false, store.state.value.refreshing, case.label)
            assertEquals(case.ids, ids(store), case.label)
            assertEquals(case.status, store.state.value.payload.status, case.label)
            assertEquals(case.errorCode, store.state.value.payload.errorCode, case.label)
            store.clear()
        }
    }

    private fun TestScope.loadedStore(repository: ReplyThreadAccessConversationRepositoryRecorder): SharedThreadFilesStore {
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
            )
        store.dispatch(SharedThreadFilesIntent.Start(route))
        store.dispatch(SharedThreadFilesIntent.Refresh("initial_tab"))
        runCurrent()
        assertEquals(listOf("a-1"), ids(store))
        return store
    }

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

    private fun ids(store: SharedThreadFilesStore) = store.state.value.payload.items.map { it.attachmentId }
}
