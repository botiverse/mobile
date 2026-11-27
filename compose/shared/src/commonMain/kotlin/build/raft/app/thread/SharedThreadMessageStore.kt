package build.raft.app.thread

import build.raft.app.ReplyThreadResolveKey
import build.raft.app.data.map
import build.raft.app.get
import build.raft.app.messages
import build.raft.app.model.ChannelId
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.resolve
import build.raft.app.thread.withKnownDelegatedByFrom
import build.raft.app.withKnownDelegatedByFrom
import build.raft.app.withKnownExternalMentionSpansFrom
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SharedThreadMessageKey(
    val scopeId: String,
    val channelId: ChannelId,
    val messageId: MessageId
)

internal sealed interface SharedThreadMessageResolution {
    data class Loaded(val message: Message) : SharedThreadMessageResolution
    data object Missing : SharedThreadMessageResolution
    /** [hiddenTarget]: the parent behind the plan's history window, as the server shows it (thread title). */
    data class Error(
        val code: String,
        val hiddenTarget: build.raft.app.data.MessageContextHiddenTarget? = null,
    ) : SharedThreadMessageResolution
}

internal sealed interface SharedThreadOriginResolution {
    data class Loaded(val message: RaftThreadMessageItem) : SharedThreadOriginResolution
    data object Missing : SharedThreadOriginResolution
    data class Error(
        val code: String,
        val hiddenTarget: build.raft.app.data.MessageContextHiddenTarget? = null,
    ) : SharedThreadOriginResolution
}

internal class SharedThreadMessageStore(
    private val limit: Int = 256,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())
) {
    private val messageLock = SynchronizedObject()
    private val messages = LinkedHashMap<SharedThreadMessageKey, Message>()
    private val replyThreadStreams = LinkedHashMap<ReplyThreadResolveKey, String>()
    private val inFlight = mutableMapOf<SharedThreadMessageKey, Deferred<SharedThreadMessageResolution>>()
    private val inFlightMutex = Mutex()

    fun get(serverId: ServerId, channelId: ChannelId, messageId: MessageId): Message? =
        getScoped(serverId.value, channelId, messageId)

    fun getScoped(scopeId: String, channelId: ChannelId, messageId: MessageId): Message? =
        synchronized(messageLock) {
            messages[SharedThreadMessageKey(scopeId, channelId, messageId)]
        }

    fun upsert(serverId: ServerId, channelId: ChannelId, message: Message): Message =
        upsertScoped(serverId.value, channelId, message)

    fun upsertScoped(scopeId: String, channelId: ChannelId, message: Message): Message =
        synchronized(messageLock) {
            upsertLocked(scopeId, channelId, message)
        }

    private fun upsertLocked(scopeId: String, channelId: ChannelId, message: Message): Message {
        val key = SharedThreadMessageKey(scopeId, channelId, message.id)
        val existing = messages[key]
        val selected =
            if (existing != null && !existing.needsSharedThreadMessageStoreHydration() && message.needsSharedThreadMessageStoreHydration()) {
                existing
            } else {
                // RFC 077: a copy without `delegatedBy` never clears the known label, and an older
                // copy never brings back a retracted message's words or attachments.
                message
                    .withKnownDelegatedByFrom(existing)
                    .withKnownExternalMentionSpansFrom(existing)
                    .withKnownRetractionFrom(existing)
            }
        messages.remove(key)
        messages[key] = selected
        trimToLimit()
        return selected
    }

    /** The loaded messages of [channelId] whose id starts with [idPrefix]. */
    fun findScopedByIdPrefix(scopeId: String, channelId: ChannelId, idPrefix: String): List<Message> {
        val prefix = idPrefix.trim().lowercase()
        if (prefix.isBlank()) return emptyList()
        return synchronized(messageLock) {
            messages.entries
                .filter { (key, _) ->
                    key.scopeId == scopeId &&
                        key.channelId == channelId &&
                        key.messageId.value.lowercase().startsWith(prefix)
                }
                .map { it.value }
                .distinctBy { it.id }
        }
    }

    /** Reply-thread resolutions of [parentChannelId] whose parent id starts with [parentIdPrefix]. */
    fun findReplyThreadStreams(
        namespaceId: String,
        parentChannelId: String,
        parentIdPrefix: String
    ): List<Pair<String, String>> {
        val prefix = parentIdPrefix.trim().lowercase()
        if (prefix.isBlank()) return emptyList()
        return synchronized(messageLock) {
            replyThreadStreams.entries
                .filter { (key, _) ->
                    key.namespaceId == namespaceId &&
                        key.parentChannelId == parentChannelId &&
                        key.parentMessageId.lowercase().startsWith(prefix)
                }
                .map { (key, threadChannelId) -> key.parentMessageId to threadChannelId }
        }
    }

    fun getReplyThreadStream(
        namespaceId: String,
        parentChannelId: String,
        parentMessageId: String
    ): String? =
        synchronized(messageLock) {
            replyThreadStreams[ReplyThreadResolveKey(namespaceId, parentChannelId, parentMessageId)]
        }

    fun upsertReplyThreadStream(
        namespaceId: String,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ) {
        if (
            namespaceId.isBlank() ||
            parentChannelId.isBlank() ||
            parentMessageId.isBlank() ||
            threadChannelId.isBlank()
        ) {
            return
        }
        synchronized(messageLock) {
            val key = ReplyThreadResolveKey(namespaceId, parentChannelId, parentMessageId)
            replyThreadStreams.remove(key)
            replyThreadStreams[key] = threadChannelId
            while (replyThreadStreams.size > limit) {
                val firstKey = replyThreadStreams.keys.firstOrNull() ?: return@synchronized
                replyThreadStreams.remove(firstKey)
            }
        }
    }

    suspend fun resolve(
        serverId: ServerId,
        channelId: ChannelId,
        messageId: MessageId,
        fetch: suspend () -> SharedThreadMessageResolution
    ): SharedThreadMessageResolution =
        resolveScoped(serverId.value, channelId, messageId, fetch)

    suspend fun resolveScoped(
        scopeId: String,
        channelId: ChannelId,
        messageId: MessageId,
        fetch: suspend () -> SharedThreadMessageResolution
    ): SharedThreadMessageResolution {
        getScoped(scopeId, channelId, messageId)
            ?.takeIf { message -> message.matchesSharedThreadMessageStoreKey(channelId, messageId) }
            ?.let { message -> return SharedThreadMessageResolution.Loaded(message) }
        val key = SharedThreadMessageKey(scopeId, channelId, messageId)
        val deferred =
            inFlightMutex.withLock {
                getScoped(scopeId, channelId, messageId)
                    ?.takeIf { message -> message.matchesSharedThreadMessageStoreKey(channelId, messageId) }
                    ?.let { message -> return SharedThreadMessageResolution.Loaded(message) }
                // A completed producer stays in [inFlight] until its cleanup coroutine runs on
                // [scope]; joining it then would hand a new request the old Missing/Error.
                inFlight[key]?.takeUnless { producer -> producer.isCompleted } ?: scope.async {
                    val fetched = fetch()
                    synchronized(messageLock) {
                        val current =
                            messages[SharedThreadMessageKey(scopeId, channelId, messageId)]
                                ?.takeIf { message -> message.matchesSharedThreadMessageStoreKey(channelId, messageId) }
                        if (current != null) {
                            SharedThreadMessageResolution.Loaded(current)
                        } else {
                            when (fetched) {
                                is SharedThreadMessageResolution.Loaded -> {
                                    val message =
                                        fetched.message.takeIf { candidate ->
                                            candidate.matchesSharedThreadMessageStoreKey(channelId, messageId)
                                        }
                                    if (message == null) {
                                        SharedThreadMessageResolution.Error("thread_parent_context_identity_mismatch")
                                    } else {
                                        SharedThreadMessageResolution.Loaded(upsertLocked(scopeId, channelId, message))
                                    }
                                }
                                SharedThreadMessageResolution.Missing -> fetched
                                is SharedThreadMessageResolution.Error -> fetched
                            }
                        }
                    }
                }.also { producer ->
                    inFlight[key] = producer
                    producer.invokeOnCompletion {
                        scope.launch {
                            inFlightMutex.withLock {
                                if (inFlight[key] === producer) {
                                    inFlight.remove(key)
                                }
                            }
                        }
                    }
                }
            }
        return deferred.await()
    }

    internal fun clearForTest() {
        synchronized(messageLock) {
            messages.clear()
            replyThreadStreams.clear()
        }
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    private fun trimToLimit() {
        while (messages.size > limit) {
            val firstKey = messages.keys.firstOrNull() ?: return
            messages.remove(firstKey)
        }
    }
}

private fun Message.needsSharedThreadMessageStoreHydration(): Boolean =
    body.isBlank() && attachments.isEmpty() && forwardBundle == null

private fun Message.matchesSharedThreadMessageStoreKey(
    channelId: ChannelId,
    messageId: MessageId
): Boolean =
    id == messageId &&
        !needsSharedThreadMessageStoreHydration() &&
        (this.channelId == channelId || threadId == ThreadId(channelId.value))
