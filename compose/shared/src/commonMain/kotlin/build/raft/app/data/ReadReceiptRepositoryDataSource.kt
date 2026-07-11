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

private suspend fun SlockNetworkClient.channelUnreadCounts(
    priority: DataRequestPriority,
    serverId: ServerId
): HomeNetworkResponse =
    request(priority, "channels/unread", serverIdOverride = serverId)

private suspend fun SlockNetworkClient.markChannelReadToSeq(
    priority: DataRequestPriority,
    channelId: ChannelId,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/read", method = "POST", bodyText = bodyText)

private suspend fun SlockNetworkClient.markChannelReadAll(
    priority: DataRequestPriority,
    channelId: ChannelId
): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/read-all", method = "POST")

private suspend fun SlockNetworkClient.markChannelUnread(
    priority: DataRequestPriority,
    channelId: ChannelId
): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/unread", method = "POST")

private suspend fun SlockNetworkClient.markInboxReadAll(
    priority: DataRequestPriority,
    serverId: ServerId
): HomeNetworkResponse =
    request(priority, "channels/inbox/read-all", method = "POST", serverIdOverride = serverId)

class RealReadReceiptRepository(
    private val sessionStore: SlockAuthSessionStore,
    private val networkClient: SlockNetworkClient,
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
                    ?: throw IllegalStateException("Unread counts response missing channel map")
            }
        )
    }

    override suspend fun markChannelReadToSeq(
        channelId: ChannelId,
        seq: Long
    ): DataResult<ReadReceiptResult> {
        currentContext() ?: return authRequired()
        if (seq <= 0L) return DataResult.Empty(EmptyReason.NoMessages)
        return executeReadReceipt(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.markChannelReadToSeq(
                    DataRequestPriority.Visible,
                    channelId,
                    buildSharedReadSeqBody(seq)
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
        currentContext() ?: return authRequired()
        return executeReadReceipt(
            emptyReason = EmptyReason.NoMessages,
            block = { networkClient.markChannelReadAll(DataRequestPriority.Visible, channelId) },
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

private data class ReadReceiptServerContext(val server: ServerSummary)

@Serializable
data class ReadSeqRequestBody(val seq: Long)

fun buildSharedReadSeqBody(seq: Long): String =
    readReceiptRepositoryJson.encodeToString(ReadSeqRequestBody(seq))

private fun <T> authRequired(): DataResult<T> =
    DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))

private fun <T> HomeNetworkResponse.toReadReceiptDataError(): DataResult<T> =
    when (code) {
        401 -> authRequired()
        403 -> DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access"))
        in 500..599 -> DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
        else -> DataResult.Error(ScreenError(code = "http_$code", retryable = code != 404))
    }
