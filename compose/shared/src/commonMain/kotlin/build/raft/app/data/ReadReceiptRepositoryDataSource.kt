package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val readReceiptRepositoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private suspend fun RaftNetworkClient.channelUnreadCounts(
    priority: DataRequestPriority,
    serverId: ServerId
): HomeNetworkResponse =
    request(priority, "channels/unread", query = CHANNEL_UNREAD_SUMMARY_QUERY, serverIdOverride = serverId)

private suspend fun RaftNetworkClient.markChannelReadToSeq(
    priority: DataRequestPriority,
    channelId: ChannelId,
    bodyText: String,
    serverId: ServerId? = null
): HomeNetworkResponse =
    request(
        priority,
        "channels/${channelId.value}/read",
        method = "POST",
        serverIdOverride = serverId,
        bodyText = bodyText
    )

private suspend fun RaftNetworkClient.markChannelReadAll(
    priority: DataRequestPriority,
    channelId: ChannelId,
    serverId: ServerId? = null
): HomeNetworkResponse =
    request(
        priority,
        "channels/${channelId.value}/read-all",
        method = "POST",
        serverIdOverride = serverId
    )

private suspend fun RaftNetworkClient.markChannelUnread(
    priority: DataRequestPriority,
    channelId: ChannelId
): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/unread", method = "POST")

private suspend fun RaftNetworkClient.markInboxReadAll(
    priority: DataRequestPriority,
    serverId: ServerId
): HomeNetworkResponse =
    request(priority, "channels/inbox/read-all", method = "POST", serverIdOverride = serverId)

class RealReadReceiptRepository(
    private val sessionStore: RaftAuthSessionStore,
    private val networkClient: RaftNetworkClient,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) : ReadReceiptRepository {
    override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> {
        val context = serverContext(serverId) ?: return authRequired()
        return executeReadReceipt(
            emptyReason = EmptyReason.NoInboxItems,
            block = { networkClient.channelUnreadCounts(DataRequestPriority.Visible, serverId) },
            source = "read_unread_counts",
            mapper = { bodyText ->
                parseChannelUnreadSnapshot(bodyText, context.server)
                    ?: throw IllegalStateException("Unread summary response missing channels/threadUnread")
            }
        )
    }

    override suspend fun markChannelReadToSeq(
        channelId: ChannelId,
        seq: Long
    ): DataResult<ReadReceiptResult> {
        val context = currentContext() ?: return authRequired()
        return markChannelReadToSeq(context.server.id, channelId, seq)
    }

    internal suspend fun markChannelReadToSeq(
        serverId: ServerId,
        channelId: ChannelId,
        seq: Long
    ): DataResult<ReadReceiptResult> {
        serverContext(serverId) ?: return authRequired()
        if (seq <= 0L) return DataResult.Empty(EmptyReason.NoMessages)
        return executeReadReceipt(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.markChannelReadToSeq(
                    DataRequestPriority.Visible,
                    channelId,
                    buildSharedReadSeqBody(seq),
                    serverId
                )
            },
            source = "read_channel_seq",
            mapper = { bodyText ->
                parseReadReceiptResult(
                    bodyText,
                    fallbackChannelId = channelId,
                    fallbackSeq = seq
                ) ?: throw IllegalStateException("Read receipt response missing ok")
            }
        )
    }

    override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> {
        val context = currentContext() ?: return authRequired()
        return markChannelReadAll(context.server.id, channelId)
    }

    internal suspend fun markChannelReadAll(
        serverId: ServerId,
        channelId: ChannelId
    ): DataResult<ReadReceiptResult> {
        serverContext(serverId) ?: return authRequired()
        return executeReadReceipt(
            emptyReason = EmptyReason.NoMessages,
            block = { networkClient.markChannelReadAll(DataRequestPriority.Visible, channelId, serverId) },
            source = "read_channel_all",
            mapper = { bodyText ->
                parseReadReceiptResult(bodyText, fallbackChannelId = channelId)
                    ?: throw IllegalStateException("Read receipt response missing ok")
            }
        )
    }

    override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> {
        currentContext() ?: return authRequired()
        return executeReadReceipt(
            emptyReason = EmptyReason.NoMessages,
            block = { networkClient.markChannelUnread(DataRequestPriority.Visible, channelId) },
            source = "read_channel_unread",
            mapper = { bodyText ->
                parseReadReceiptResult(bodyText, fallbackChannelId = channelId)
                    ?: throw IllegalStateException("Unread response missing ok")
            }
        )
    }

    override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> {
        serverContext(serverId) ?: return authRequired()
        return executeReadReceipt(
            emptyReason = EmptyReason.NoInboxItems,
            block = { networkClient.markInboxReadAll(DataRequestPriority.Visible, serverId) },
            source = "read_inbox_all",
            mapper = { bodyText ->
                parseReadReceiptResult(bodyText)
                    ?: throw IllegalStateException("Inbox read-all response missing ok")
            }
        )
    }

    private suspend fun serverContext(serverId: ServerId): ReadReceiptServerContext? {
        val session = sessionStore.readSession() ?: return null
        val requestedId = serverId.value.trim().takeIf { it.isNotBlank() } ?: return null
        val server = session.servers.firstOrNull { it.id == requestedId }?.toServerSummary() ?: return null
        return ReadReceiptServerContext(server)
    }

    private suspend fun currentContext(): ReadReceiptServerContext? {
        val session = sessionStore.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return serverContext(ServerId(selectedId))
    }

    private suspend fun <T> executeReadReceipt(
        emptyReason: EmptyReason,
        block: suspend () -> HomeNetworkResponse,
        source: String,
        mapper: (String) -> T
    ): DataResult<T> {
        var statusCode: Int? = null
        return try {
            val response = block()
            statusCode = response.code
            if (!response.isSuccessful) return response.toReadReceiptDataError()
            val body = response.bodyText.orEmpty()
            if (body.isBlank()) return DataResult.Empty(emptyReason)
            DataResult.Content(
                parseResponseBodyWithTrace(
                    source = source,
                    markerPrefix = "read_receipt",
                    body = body,
                    code = response.code,
                    hasBody = response.bodyText != null,
                    diagnosticsLogger = logger,
                    parser = { mapper(body) }
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            warningLogger(
                "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} exception=${exception::class.simpleName}"
            )
            DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
        }
    }
}

/**
 * Auth view used by a process read-receipt owner.
 *
 * The network middleware re-reads this store immediately before signing HTTP, so an account
 * switch between coordinator validation and transport fails closed instead of borrowing the new
 * account's token for an old owner. The selected server is similarly fixed to the owner bucket.
 */
internal class ReadReceiptOwnerAuthSessionStore(
    private val delegate: RaftAuthSessionStore,
    private val owner: ReadReceiptOwner
) : RaftAuthSessionStore by delegate {
    override suspend fun readSession(): StoredAuthSession? {
        val session = delegate.readSession() ?: return null
        if (session.user.id != owner.accountId) return null
        val server = session.servers.firstOrNull { it.id == owner.serverId } ?: return null
        return session.copy(
            selectedServerId = server.id,
            selectedServerSlug = server.slug,
            allowImplicitServerFallback = false
        )
    }

    override suspend fun saveSession(session: StoredAuthSession) = Unit

    override suspend fun replaceAuthenticatedSession(session: StoredAuthSession) = Unit

    override suspend fun replaceAuthenticatedSessionPreservingSelection(session: StoredAuthSession) = Unit

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val current = readSession() ?: return
        delegate.rotateTokensIfMatch(
            expectedAccessToken = current.accessToken,
            expectedRefreshToken = current.refreshToken,
            newAccessToken = accessToken,
            newRefreshToken = refreshToken
        )
    }

    override suspend fun rotateTokensIfMatch(
        expectedAccessToken: String,
        expectedRefreshToken: String,
        newAccessToken: String,
        newRefreshToken: String
    ): StoredAuthSession? {
        if (readSession() == null) return null
        return delegate.rotateTokensIfMatch(
            expectedAccessToken = expectedAccessToken,
            expectedRefreshToken = expectedRefreshToken,
            newAccessToken = newAccessToken,
            newRefreshToken = newRefreshToken
        )?.takeIf { it.user.id == owner.accountId && it.servers.any { server -> server.id == owner.serverId } }
    }

    override suspend fun updateMetadata(
        expectedAccountId: String?,
        transform: (StoredAuthSession) -> StoredAuthSession
    ) = Unit

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) = Unit

    override suspend fun clearSession(reason: String, source: String) {
        val current = readSession() ?: return
        delegate.clearSessionIfMatches(
            expectedAccountId = owner.accountId,
            expectedAccessToken = current.accessToken,
            expectedRefreshToken = current.refreshToken,
            reason = reason,
            source = source
        )
    }

    override suspend fun clearSessionIfMatches(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        reason: String,
        source: String
    ): Boolean {
        if (expectedAccountId != owner.accountId || readSession() == null) return false
        return delegate.clearSessionIfMatches(
            expectedAccountId = expectedAccountId,
            expectedAccessToken = expectedAccessToken,
            expectedRefreshToken = expectedRefreshToken,
            reason = reason,
            source = source
        )
    }

    override suspend fun clearSessionIfMatchesOutcome(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        reason: String,
        source: String,
    ): AuthSessionClearOutcome {
        if (expectedAccountId != owner.accountId) return AuthSessionClearOutcome.AuthorityChanged
        return delegate.clearSessionIfMatchesOutcome(
            expectedAccountId = expectedAccountId,
            expectedAccessToken = expectedAccessToken,
            expectedRefreshToken = expectedRefreshToken,
            reason = reason,
            source = source,
        )
    }
}

private data class ReadReceiptServerContext(val server: ServerSummary)

@Serializable
data class ReadSeqRequestBody(val seq: Long)

fun buildSharedReadSeqBody(seq: Long): String =
    readReceiptRepositoryJson.encodeToString(ReadSeqRequestBody(seq))

private fun authRequired(): DataResult.NoPermission =
    authSessionRequiredResult()

private fun authRequired(serverMessage: String?): DataResult.NoPermission =
    authSessionRequiredResult(serverMessage)

private fun <T> HomeNetworkResponse.toReadReceiptDataError(): DataResult<T> {
    val serverMessage = readReceiptRouteErrorTextOrNull()
    return when (code) {
        401 -> authRequired(serverMessage)
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "server_forbidden",
                    requiredCapability = "server_access",
                    serverMessage = serverMessage
                )
            )
        in 500..599 ->
            DataResult.Error(ScreenError(code = "server_unavailable", retryable = true, serverMessage = serverMessage))
        else ->
            DataResult.Error(ScreenError(code = "http_$code", retryable = code != 404, serverMessage = serverMessage))
    }
}

@Serializable
private data class ReadReceiptRouteErrorDto(val error: String? = null)

private fun HomeNetworkResponse.readReceiptRouteErrorTextOrNull(): String? {
    val payload = bodyText?.takeIf { it.isNotBlank() }
        ?: errorBodyText?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        readReceiptRepositoryJson.decodeFromString(ReadReceiptRouteErrorDto.serializer(), payload)
            .error
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
