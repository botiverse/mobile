package build.raft.app.network

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser

/**
 * PROTOTYPE: the capture's in-process server. Answers the app's requests from fabricated data;
 * anything it does not know is a logged 404 (`RAFT_CAPTURE_HTTP`), so a capture run lists what
 * the signed-in shell asked for.
 */
internal object RaftCaptureFakeServer {
    const val BaseUrl = "https://capture.raft.invalid"

    const val UserId = "usr_capture_artin"
    const val DesignServerId = "srv_capture_design"
    const val BotsServerId = "srv_capture_bots"

    fun session(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = UserId, email = "artin@capture.invalid", name = "artin", displayName = "artin", emailVerified = true),
            accessToken = "capture-access-token",
            refreshToken = "capture-refresh-token",
            selectedServerId = DesignServerId,
            selectedServerSlug = "raft-design",
            servers =
                listOf(
                    StoredAuthServer(id = DesignServerId, name = "Raft Design", slug = "raft-design", role = "owner", plan = "pro"),
                    StoredAuthServer(id = BotsServerId, name = "Botiverse", slug = "botiverse", role = "member", plan = "free"),
                ),
        )

    val executor: RaftHttpExecutor =
        object : RaftHttpExecutor {
            override suspend fun execute(
                priority: RaftRequestPriority,
                url: String,
                method: String,
                headers: Map<String, String>,
                bodyText: String?,
                jsonBody: Boolean,
            ): RaftHttpExecutorResponse = respond(method, url, bodyText, headers)

            override suspend fun executeMultipart(
                priority: RaftRequestPriority,
                url: String,
                fields: Map<String, String>,
                files: List<MultipartFile>,
                headers: Map<String, String>,
                onUploadProgress: ((RaftWireTransferProgress) -> Unit)?,
            ): RaftHttpExecutorResponse = respond("POST", url, null, headers)
        }

    private fun respond(method: String, url: String, bodyText: String?, headers: Map<String, String>): RaftHttpExecutorResponse {
        val pathAndQuery = url.removePrefix(BaseUrl)
        val path = pathAndQuery.substringBefore('?')
        val query = pathAndQuery.substringAfter('?', "")
        val serverId =
            headers.entries.firstOrNull { it.key.equals(HEADER_SERVER_ID, ignoreCase = true) }?.value
                ?: DesignServerId
        val body = runCatching { RaftCaptureFakeRoutes.answer(method, path, query, bodyText, serverId) }
            .onFailure { println("RAFT_CAPTURE_ERROR $method $path ${it.message}") }
            .getOrNull()
        println("RAFT_CAPTURE_HTTP ${body?.first ?: 404} $method $pathAndQuery server=$serverId")
        return if (body == null) {
            RaftHttpExecutorResponse(code = 404, bodyText = null, errorBodyText = """{"error":"capture_not_found"}""")
        } else {
            RaftHttpExecutorResponse(code = body.first, bodyText = body.second, errorBodyText = null)
        }
    }
}

/** The routes the capture answers: (status, body) or null (404). */
internal object RaftCaptureFakeRoutes {
    private val json = kotlinx.serialization.json.Json { encodeDefaults = false }

    private fun ok(body: String) = 200 to body

    fun answer(method: String, path: String, query: String, bodyText: String?, serverId: String): Pair<Int, String>? {
        val session = RaftCaptureFakeServer.session()
        val data = RaftCaptureFakeData.forServer(serverId)
        return when {
            method == "GET" && path == "/channels" ->
                ok(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(build.raft.app.data.HomeChannelWireDto.serializer()), data.channels))
            method == "GET" && path.startsWith("/messages/channel/") ->
                ok(RaftCaptureFakeWire.messages(path.removePrefix("/messages/channel/")))
            method == "GET" && path.matches(Regex("/channels/[^/]+/members")) -> ok(RaftCaptureFakeWire.channelMembers(serverId))
            method == "GET" && path.matches(Regex("/servers/[^/]+/members")) -> ok(RaftCaptureFakeWire.serverMembers(serverId))
            method == "GET" && path == "/agents" -> ok(RaftCaptureFakeWire.agents(serverId))
            method == "GET" && path.matches(Regex("/channels/(ch|dm)_capture_[a-z]+")) -> {
                val id = path.removePrefix("/channels/")
                data.channels.firstOrNull { it.id == id }?.let { ok(json.encodeToString(build.raft.app.data.HomeChannelWireDto.serializer(), it)) }
                    ?: data.directMessages.firstOrNull { it.id == id }?.let { ok(json.encodeToString(build.raft.app.data.HomeDirectMessageWireDto.serializer(), it)) }
            }
            method == "GET" && path == "/channels/dm" ->
                ok(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(build.raft.app.data.HomeDirectMessageWireDto.serializer()), data.directMessages))
            method == "GET" && path == "/auth/me" -> ok(json.encodeToString(StoredAuthUser.serializer(), session.user))
            method == "GET" && path == "/servers" ->
                ok(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(StoredAuthServer.serializer()), session.servers))
            method == "GET" && path == "/servers/order" -> ok("""{"serverOrder":["${RaftCaptureFakeServer.DesignServerId}","${RaftCaptureFakeServer.BotsServerId}"]}""")
            method == "POST" && path == "/push/registrations" -> ok("{}")
            else -> null
        }
    }
}
