package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.ThreadDurableEnteredPage
import build.raft.app.network.sync.ThreadDurableReplyParent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Message storage Phase 2b test support: the loader no longer fetches its own page — the thread store hands its page
 * to [CanonicalMessageV2EnteredScopeLoader.persistEnteredPage]. These helpers play the thread store's part for the
 * writer tests: take the ticket, fetch `messages/channel/{id}?limit=200` through the rig's network client (as the
 * thread store's own load would), and write it through. A failed fetch abandons the ticket.
 */
internal suspend fun CanonicalMessageV2EnteredScopeLoader.loadOnEnter(
    partition: DurablePartitionKey,
    channelId: SyncScopeId,
    stillCurrent: () -> Boolean,
) = enterThroughNetwork(partition, channelId, replyParent = null, stillCurrent)

internal suspend fun CanonicalMessageV2EnteredScopeLoader.loadReplyThreadOnEnter(
    partition: DurablePartitionKey,
    threadChannelId: SyncScopeId,
    replyParent: ThreadDurableReplyParent,
    stillCurrent: () -> Boolean,
) = enterThroughNetwork(partition, threadChannelId, replyParent, stillCurrent)

/** The thread store's page request for [channelId], as [ThreadDurableEnteredPage]; `null` = failed. */
internal suspend fun CanonicalMessageV2EnteredScopeLoader.fetchEnteredPageForTest(
    partition: DurablePartitionKey,
    channelId: SyncScopeId,
    limit: Int = ENTER_PAGE_LIMIT_FOR_TEST,
): ThreadDurableEnteredPage? {
    val response = try {
        fetcher.networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "messages/channel/$channelId",
            query = listOf("limit" to limit.toString()),
            serverIdOverride = ServerId(partition.serverId),
        )
    } catch (failure: Exception) {
        return null
    }
    if (!response.isSuccessful) return null
    return enteredPageFromBody(channelId, response.bodyText ?: return null, limit)
}

/** A page body (`{messages, historyLimited, messageWindow?, threadSummariesByParentMessageId?}`) as an entered page. */
internal fun enteredPageFromBody(
    channelId: SyncScopeId,
    body: String,
    limit: Int = ENTER_PAGE_LIMIT_FOR_TEST,
    channelType: String? = null,
): ThreadDurableEnteredPage? {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val messages = root["messages"] as? JsonArray ?: return null
    val counts = embeddedReplyCountsOrNull(root["threadSummariesByParentMessageId"])
    return ThreadDurableEnteredPage(
        channelId = channelId,
        rawMessages = messages,
        requestedLimit = limit,
        messageWindow = decodeChannelMessageWindow(root["messageWindow"]),
        historyLimited = (root["historyLimited"] as? JsonPrimitive)?.booleanOrNull ?: false,
        channelType = channelType,
        replyCountByParentId = counts,
    )
}

private suspend fun CanonicalMessageV2EnteredScopeLoader.enterThroughNetwork(
    partition: DurablePartitionKey,
    channelId: SyncScopeId,
    replyParent: ThreadDurableReplyParent?,
    stillCurrent: () -> Boolean,
) {
    val ticket = beginEnter(partition, channelId, replyParent) ?: return
    val page = fetchEnteredPageForTest(partition, channelId)
    if (page == null) {
        abandonEnter(ticket)
        return
    }
    persistEnteredPage(ticket, page, stillCurrent)
}

internal const val ENTER_PAGE_LIMIT_FOR_TEST = 200
