package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import kotlinx.coroutines.CompletableDeferred

/**
 * Shared `RaftNetworkClient` fake for [RealConversationRepository] suites: answers `"<METHOD> <path>"`
 * keys from [responses] and records every request (extracted from ConversationRepositoryDataSourceTest).
 */
internal class FakeConversationNetworkClient(
    private val responses: Map<String, String> = emptyMap(),
    private val failures: Map<String, String> = emptyMap(),
    private val uploadBody: String = """{"attachments":[]}""",
    private val uploadResponse: HomeNetworkResponse? = null,
    private val requestStarted: Map<String, CompletableDeferred<Unit>> = emptyMap(),
    private val requestRelease: Map<String, CompletableDeferred<Unit>> = emptyMap(),
    private val responseForRequest: (RecordedConversationRequest) -> String? = { null },
) : RaftNetworkClient {
    val requests = mutableListOf<RecordedConversationRequest>()
    val serverOverrides = mutableListOf<String?>()
    val bodies = mutableListOf<String>()
    val uploadedFileNames = mutableListOf<String>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        val normalized = path.trim('/')
        val requestKey = "$method $normalized"
        requests += RecordedConversationRequest(priority, method, normalized, query)
        serverOverrides += serverIdOverride?.value
        bodyText?.let { bodies += it }
        requestStarted[requestKey]?.complete(Unit)
        requestRelease[requestKey]?.await()
        failures[requestKey]?.let { message ->
            return HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = message)
        }
        return HomeNetworkResponse(
            code = 200,
            bodyText = responseForRequest(requests.last()) ?: responses[requestKey] ?: """{"threads":[]}""",
        )
    }

    override suspend fun uploadAttachments(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>
    ): HomeNetworkResponse {
        requests += RecordedConversationRequest(priority, "POST", "messages/channels/${channelId.value}/attachments", emptyList())
        uploadedFileNames += files.map { it.fileName }
        return uploadResponse ?: HomeNetworkResponse(code = 200, bodyText = uploadBody)
    }
}

internal data class RecordedConversationRequest(
    val priority: DataRequestPriority,
    val method: String,
    val path: String,
    val query: List<Pair<String, String>> = emptyList()
)

/** The signed-in session the conversation repository suites read (server-1, user-1). */
internal fun conversationRepositoryTestSessionStore(): RaftAuthSessionStore =
    InMemoryRaftAuthSessionStore(
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock Android", slug = "android")),
        )
    )
