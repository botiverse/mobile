package ai.slock.android.core.data.network

import android.util.Log
import ai.slock.android.core.data.auth.AuthHeaderInterceptor
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.RetrofitAuthRepository
import ai.slock.android.core.data.repository.ChannelRepository
import ai.slock.android.core.data.repository.ChannelSnapshot
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.DirectMessageSnapshot
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.InboxFilter
import ai.slock.android.core.data.repository.InboxRepository
import ai.slock.android.core.data.repository.InboxSnapshot
import ai.slock.android.core.data.repository.SavedRepository
import ai.slock.android.core.data.repository.SavedSnapshot
import ai.slock.android.core.data.repository.SearchRepository
import ai.slock.android.core.data.repository.SearchSnapshot
import ai.slock.android.core.data.repository.ServerUnreadRepository
import ai.slock.android.core.data.repository.ServerUnreadSummary
import ai.slock.android.core.data.repository.ServerUnreadSummarySnapshot
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.state.EmptyReason
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RealChannelRepository(
    private val store: AuthSessionStore,
    private val api: SlockApi
) : ChannelRepository {
    override suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot> {
        val context = serverContext(serverId) ?: return authRequired()
        val channels = fetchChannels(context)
        val directMessages = fetchDirectMessages(context)

        if (channels is DataResult.Error || channels is DataResult.NoPermission) return channels.mapError()
        if (directMessages is DataResult.Error || directMessages is DataResult.NoPermission) return directMessages.mapError()
        val channelItems = (channels as? DataResult.Content)?.data?.channels.orEmpty()
        val directMessageItems = (directMessages as? DataResult.Content)?.data?.directMessages.orEmpty()
        if (channelItems.isEmpty() && directMessageItems.isEmpty()) return DataResult.Empty(EmptyReason.NoChannels)

        val allUnread = channelItems.sumOf { it.unreadCount } + directMessageItems.sumOf { it.unreadCount }
        return DataResult.Content(
            HomeSnapshot(
                server = context.server,
                currentMember = context.session.user.toMemberProfile(),
                channels = channelItems,
                directMessages = directMessageItems,
                unreadTotal = allUnread
            )
        )
    }

    override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> {
        val context = serverContext(serverId) ?: return authRequired()
        return fetchChannels(context)
    }

    override suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot> {
        val context = serverContext(serverId) ?: return authRequired()
        return fetchDirectMessages(context)
    }

    private suspend fun fetchChannels(context: NetworkServerContext): DataResult<ChannelSnapshot> =
        execute(
            emptyReason = EmptyReason.NoChannels,
            block = { api.channels() },
            emptyWhen = { it.channels.isEmpty() },
            mapper = { json ->
                ChannelSnapshot(
                    server = context.server,
                    channels = json.items("channels").map { it.asObject().toConversation(ConversationKind.Channel) }
                )
            }
        )

    private suspend fun fetchDirectMessages(context: NetworkServerContext): DataResult<DirectMessageSnapshot> =
        execute(
            emptyReason = EmptyReason.NoChannels,
            block = { api.directMessages() },
            emptyWhen = { it.directMessages.isEmpty() },
            mapper = { json ->
                DirectMessageSnapshot(
                    server = context.server,
                    directMessages = json.items("directMessages", "dmChannels", "channels")
                        .map { it.asObject().toConversation(ConversationKind.DirectMessage) }
                )
            }
        )

    private suspend fun serverContext(serverId: ServerId): NetworkServerContext? =
        store.readSession()?.toNetworkServerContext(serverId)
}

class RealInboxRepository(
    private val store: AuthSessionStore,
    private val api: SlockApi
) : InboxRepository {
    override suspend fun loadInitial(
        serverId: ServerId,
        filter: InboxFilter,
        limit: Int
    ): DataResult<InboxSnapshot> = load(serverId, filter, limit, offset = 0)

    override suspend fun loadMore(
        serverId: ServerId,
        filter: InboxFilter,
        offset: Int,
        limit: Int
    ): DataResult<InboxSnapshot> = load(serverId, filter, limit, offset)

    private suspend fun load(
        serverId: ServerId,
        filter: InboxFilter,
        limit: Int,
        offset: Int
    ): DataResult<InboxSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoInboxItems,
            block = { api.inbox(filter.wireValue, limit, offset) },
            emptyWhen = { it.items.isEmpty() },
            mapper = { json ->
                val items = json.items("items").map { it.asObject().toInboxItem() }.distinctBy { it.key }
                InboxSnapshot(
                    server = context.server,
                    items = items,
                    totalCount = json.int("totalCount") ?: items.size,
                    totalUnreadCount = json.int("totalUnreadCount") ?: items.sumOf { it.unreadCount },
                    hasMore = json.boolean("hasMore") ?: false,
                    nextOffset = offset + items.size
                )
            }
        )
    }
}

class RealSavedRepository(
    private val store: AuthSessionStore,
    private val api: SlockApi
) : SavedRepository {
    override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> =
        load(serverId, limit, offset = 0)

    override suspend fun loadMore(serverId: ServerId, offset: Int, limit: Int): DataResult<SavedSnapshot> =
        load(serverId, limit, offset)

    private suspend fun load(serverId: ServerId, limit: Int, offset: Int): DataResult<SavedSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSearchResults,
            block = { api.saved(limit, offset) },
            emptyWhen = { it.items.isEmpty() },
            mapper = { json ->
                val items = json.items("items", "messages", "saved")
                    .map { it.asObject().toInboxItem(defaultKind = InboxItemKind.Channel) }
                    .distinctBy { it.key }
                SavedSnapshot(
                    server = context.server,
                    items = items,
                    totalCount = json.int("totalCount") ?: items.size,
                    hasMore = json.boolean("hasMore") ?: false,
                    nextOffset = offset + items.size
                )
            }
        )
    }
}

class RealSearchRepository(
    private val store: AuthSessionStore,
    private val api: SlockApi
) : SearchRepository {
    override suspend fun search(
        serverId: ServerId,
        query: String,
        offset: Int,
        limit: Int
    ): DataResult<SearchSnapshot> {
        if (query.isBlank()) return DataResult.Empty(EmptyReason.NoSearchResults)
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSearchResults,
            block = { api.searchMessages(query.trim(), limit, offset) },
            emptyWhen = { it.items.isEmpty() },
            mapper = { json ->
                val items = json.items("items", "results", "messages")
                    .map { it.asObject().toInboxItem(defaultKind = InboxItemKind.Channel) }
                    .distinctBy { it.key }
                SearchSnapshot(
                    server = context.server,
                    query = query,
                    items = items,
                    totalCount = json.int("totalCount") ?: items.size,
                    hasMore = json.boolean("hasMore") ?: false,
                    nextOffset = offset + items.size
                )
            }
        )
    }
}

class RealServerUnreadRepository(
    private val store: AuthSessionStore,
    private val api: SlockApi,
    private val throttleWindowMillis: Long = 5_000L,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val logger: (String) -> Unit = { message -> Log.d("SlockUnread", message) },
    private val warningLogger: (String) -> Unit = { message -> Log.w("SlockUnread", message) }
) : ServerUnreadRepository {
    private val throttleMutex = Mutex()
    private var cachedSummary: ThrottledUnreadSummary? = null

    override suspend fun getUnreadSummary(serverId: ServerId): DataResult<ServerUnreadSummarySnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId)
        if (context == null) {
            warningLogger("repository_skip reason=auth_or_server_mismatch serverId=${serverId.value}")
            return authRequired()
        }
        return throttleMutex.withLock {
            val now = clockMillis()
            val cached = cachedSummary
            if (
                cached != null &&
                cached.serverId == serverId &&
                now - cached.loadedAtMillis < throttleWindowMillis
            ) {
                logger(
                    "repository_throttle_hit serverId=${serverId.value} ageMs=${now - cached.loadedAtMillis}"
                )
                return@withLock cached.result
            }

            logger("repository_fetch_start serverId=${serverId.value}")
            val result = execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.serverUnreadSummary() },
                mapper = { json -> json.toServerUnreadSummarySnapshot(context.server) }
            )
            when (result) {
                is DataResult.Content -> {
                    logger(
                        "repository_fetch_success serverId=${serverId.value} total=${result.data.totalUnreadCount} inbox=${result.data.inboxUnreadCount} saved=${result.data.savedUnreadCount}"
                    )
                }
                is DataResult.Empty -> {
                    logger("repository_fetch_empty serverId=${serverId.value} empty=${result.reason}")
                }
                is DataResult.Error -> {
                    warningLogger("repository_fetch_error serverId=${serverId.value} code=${result.error.code}")
                }
                is DataResult.NoPermission -> {
                    warningLogger("repository_fetch_no_permission serverId=${serverId.value} code=${result.blocker.code}")
                }
            }
            cachedSummary = ThrottledUnreadSummary(
                serverId = serverId,
                loadedAtMillis = now,
                result = result
            )
            result
        }
    }

    private data class ThrottledUnreadSummary(
        val serverId: ServerId,
        val loadedAtMillis: Long,
        val result: DataResult<ServerUnreadSummarySnapshot>
    )
}

object RealSlockApiFactory {
    fun create(
        sessionProvider: () -> AuthSession?,
        baseUrl: String = RetrofitAuthRepository.BASE_URL
    ): SlockApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(AuthHeaderInterceptor(sessionProvider))
                    .build()
            )
            .build()
            .create(SlockApi::class.java)
}

private data class NetworkServerContext(
    val session: AuthSession,
    val server: ServerSummary
)

private suspend fun AuthSessionStore.toNetworkServerContext(serverId: ServerId): NetworkServerContext? =
    readSession()?.toNetworkServerContext(serverId)

private fun AuthSession.toNetworkServerContext(serverId: ServerId): NetworkServerContext? {
    val selectedId = currentServerId ?: return null
    if (selectedId != serverId.value) return null
    val server = servers.firstOrNull { it.id == selectedId }?.toServerSummary() ?: return null
    return NetworkServerContext(session = this, server = server)
}

private fun <T> DataResult<*>.mapError(): DataResult<T> =
    when (this) {
        is DataResult.Empty -> this
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
        is DataResult.Content -> error("Cannot map content as error.")
    }

private fun JsonObject.toConversation(kind: ConversationKind): ConversationSummary {
    val id = string("id", "channelId", "threadChannelId") ?: "unknown-channel"
    val name = string("name", "channelName", "peerDisplayName", "peerName", "title")
        ?: if (kind == ConversationKind.DirectMessage) "Direct message" else "channel"
    return ConversationSummary(
        threadId = ThreadId(string("threadId", "threadChannelId", "id") ?: id),
        channelId = ChannelId(id),
        kind = kind,
        name = name,
        subtitle = string("description", "topic", "purpose", "peerDescription"),
        unreadCount = int("unreadCount", "unread") ?: 0,
        mentionCount = int("mentionCount", "mentions") ?: 0,
        lastMessageId = string("lastMessageId", "latestMessageId")?.let(::MessageId),
        firstUnreadMessageId = string("firstUnreadMessageId")?.let(::MessageId),
        lastMessagePreview = string("lastMessagePreview", "preview", "latestActivityPreview", "content"),
        lastMessageAt = string("lastMessageAt", "updatedAt", "latestActivityAt", "createdAt"),
        lastMessageSenderName = string("lastMessageSenderName", "senderName", "authorName"),
        archived = boolean("archived", "isArchived") ?: false
    )
}

private fun JsonObject.toInboxItem(defaultKind: InboxItemKind? = null): InboxItem {
    val kind = defaultKind ?: when (string("kind", "type")) {
        "dm", "direct_message", "directMessage" -> InboxItemKind.DirectMessage
        "thread" -> InboxItemKind.Thread
        else -> InboxItemKind.Channel
    }
    val channelId = string("channelId", "id", "threadChannelId") ?: "unknown-channel"
    val threadChannelId = string("threadChannelId")
    val key = when {
        kind == InboxItemKind.Thread && threadChannelId != null -> "thread:$threadChannelId"
        kind == InboxItemKind.DirectMessage -> "dm:$channelId"
        else -> "channel:$channelId"
    }
    return InboxItem(
        key = string("key") ?: key,
        kind = kind,
        channelId = ChannelId(channelId),
        channelName = string("channelName", "name", "peerDisplayName", "peerName", "parentChannelName") ?: "channel",
        unreadCount = int("unreadCount", "unread") ?: 0,
        firstUnreadMessageId = string("firstUnreadMessageId")?.let(::MessageId),
        lastMessageId = string("lastMessageId", "latestMessageId", "messageId", "id")?.let(::MessageId),
        lastMessagePreview = string("lastMessagePreview", "preview", "content", "body"),
        lastMessageAt = string("lastMessageAt", "updatedAt", "createdAt"),
        lastMessageSenderName = string("lastMessageSenderName", "senderName", "authorName"),
        threadChannelId = threadChannelId?.let(::ChannelId),
        parentChannelId = string("parentChannelId")?.let(::ChannelId),
        parentChannelName = string("parentChannelName"),
        parentChannelKind = string("parentChannelKind")?.let {
            if (it == "dm") ConversationKind.DirectMessage else ConversationKind.Channel
        },
        parentMessageId = string("parentMessageId")?.let(::MessageId),
        parentMessagePreview = string("parentMessagePreview"),
        latestActivityMessageId = string("latestActivityMessageId")?.let(::MessageId),
        latestActivityPreview = string("latestActivityPreview"),
        latestActivityAt = string("latestActivityAt"),
        latestActivitySenderName = string("latestActivitySenderName"),
        replyCount = int("replyCount", "replies") ?: 0,
        participantIds = stringList("participantIds", "participants").map(::MemberId),
        taskNumber = int("taskNumber"),
        taskStatus = string("taskStatus")?.toTaskStatus(),
        taskClaimedByName = string("taskClaimedByName", "claimedByName")
    )
}

private fun JsonObject.toServerUnreadSummarySnapshot(selectedServer: ServerSummary): ServerUnreadSummarySnapshot {
    val summaries = items("servers", "items", "summaries").mapNotNull { it.asObject().toServerUnreadSummary() }
    val selectedSummary = summaries.firstOrNull { it.serverId == selectedServer.id }
        ?: toServerUnreadSummary(defaultServerId = selectedServer.id)
    val totalUnread = int("totalUnreadCount", "totalUnread", "unreadTotal", "unreadCount")
        ?: selectedSummary?.totalUnreadCount
        ?: summaries.sumOf { it.totalUnreadCount }
    val inboxUnread = int("totalInboxUnreadCount", "inboxUnreadCount", "inboxUnread")
        ?: selectedSummary?.inboxUnreadCount
        ?: 0
    val savedUnread = int("totalSavedUnreadCount", "savedUnreadCount", "savedUnread")
        ?: selectedSummary?.savedUnreadCount
        ?: 0
    return ServerUnreadSummarySnapshot(
        server = selectedServer.copy(unreadCount = selectedSummary?.totalUnreadCount ?: totalUnread),
        summaries = summaries.ifEmpty { selectedSummary?.let(::listOf).orEmpty() },
        selectedServerSummary = selectedSummary,
        totalUnreadCount = totalUnread,
        inboxUnreadCount = inboxUnread,
        savedUnreadCount = savedUnread,
        mentionCount = int("mentionCount", "mentions") ?: selectedSummary?.mentionCount ?: 0
    )
}

private fun JsonObject.toServerUnreadSummary(defaultServerId: ServerId? = null): ServerUnreadSummary? {
    val serverId = string("serverId", "id")?.let(::ServerId) ?: defaultServerId ?: return null
    val inboxUnread = int("inboxUnreadCount", "inboxUnread", "inbox") ?: 0
    val channelUnread = int("channelUnreadCount", "channelsUnread", "channels") ?: 0
    val dmUnread = int("directMessageUnreadCount", "dmUnreadCount", "directMessagesUnread", "dms") ?: 0
    val savedUnread = int("savedUnreadCount", "savedUnread", "saved") ?: 0
    val mentionCount = int("mentionCount", "mentions") ?: 0
    val taskUnread = int("taskUnreadCount", "tasksUnread", "tasks") ?: 0
    val total = int("totalUnreadCount", "totalUnread", "unreadTotal", "unreadCount")
        ?: (inboxUnread + channelUnread + dmUnread + savedUnread + mentionCount + taskUnread)
    return ServerUnreadSummary(
        serverId = serverId,
        totalUnreadCount = total,
        inboxUnreadCount = inboxUnread,
        channelUnreadCount = channelUnread,
        directMessageUnreadCount = dmUnread,
        savedUnreadCount = savedUnread,
        mentionCount = mentionCount,
        taskUnreadCount = taskUnread,
        updatedAt = string("updatedAt", "lastActivityAt")
    )
}

private fun String.toTaskStatus(): TaskStatus? =
    when (lowercase()) {
        "todo", "to_do", "open" -> TaskStatus.Todo
        "in_progress", "inprogress", "progress" -> TaskStatus.InProgress
        "in_review", "inreview", "review" -> TaskStatus.InReview
        "done", "closed", "complete", "completed" -> TaskStatus.Done
        else -> null
    }
