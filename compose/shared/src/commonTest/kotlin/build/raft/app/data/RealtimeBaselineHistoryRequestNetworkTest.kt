package build.raft.app.data

import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sync horizon (botiverse/slock #10982): the shared network client records which servers had a baseline
 * history read sent (a starting horizon is usable only before that), and the per-channel
 * `/messages/sync` gap catch-up stays exactly as before: no `since_horizon`, so the global horizon is
 * never touched by a read that sees one channel only.
 */
class RealtimeBaselineHistoryRequestNetworkTest {
    @Test
    fun historyReadsAreRecordedForTheirServerBeforeTheyLeave() = runBlocking {
        val executor = RecordingExecutor()
        val recorded = mutableListOf<String>()
        val client =
            SharedRaftNetworkClient(
                sessionStore = authenticatedSessionStore(),
                baseUrlProvider = { SharedApiEnvironmentStore.Production.apiBaseUrl },
                executor = executor,
                // "@n": how many requests had reached the HTTP stack when this one was recorded.
                onBaselineHistoryRequestSent = { serverId -> recorded += "$serverId@${executor.urls.size}" },
            )

        client.request(DataRequestPriority.Visible, "messages/channel/channel-1")
        client.request(DataRequestPriority.Visible, "messages", method = "POST", bodyText = "{}")
        client.request(DataRequestPriority.Visible, "agents")
        client.request(DataRequestPriority.Visible, "servers/unread-summary", serverIdOverride = build.raft.app.model.ServerId("server-2"))

        assertEquals(listOf("server-1@0", "server-2@3"), recorded, "server and number of requests already sent")
    }

    @Test
    fun perChannelMessageSyncNeverSendsSinceHorizon() = runBlocking {
        val executor = RecordingExecutor()
        val client =
            SharedRaftNetworkClient(
                sessionStore = authenticatedSessionStore(),
                baseUrlProvider = { SharedApiEnvironmentStore.Production.apiBaseUrl },
                executor = executor,
                onBaselineHistoryRequestSent = {},
            )

        NetworkRaftThreadApi(client).messageSync(channelId = "channel-1", sinceSeq = 89L, limit = 50)

        val url = executor.urls.single()
        assertTrue("/messages/sync?" in url, url)
        val queryKeys = url.substringAfter('?').split('&').map { it.substringBefore('=') }
        assertEquals(listOf("since_seq", "limit", "channel_id"), queryKeys)
        assertFalse("since_horizon" in url)
    }

    private class RecordingExecutor : RaftHttpExecutor {
        val urls = mutableListOf<String>()

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): RaftHttpExecutorResponse {
            urls += url
            return RaftHttpExecutorResponse(code = 200, bodyText = "[]", errorBodyText = null)
        }

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?
        ): RaftHttpExecutorResponse = RaftHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
    }

    private fun authenticatedSessionStore(): InMemoryRaftAuthSessionStore =
        InMemoryRaftAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                accessToken = "access-token",
                refreshToken = "refresh-token",
                selectedServerId = "server-1",
                servers =
                    listOf(
                        StoredAuthServer(id = "server-1", name = "Raft"),
                        StoredAuthServer(id = "server-2", name = "Two"),
                    ),
            )
        )
}
