package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val whitespaceRegex = Regex("\\s+")

internal fun normalizeMessagePreview(body: String): String? {
    val collapsed = body.replace(whitespaceRegex, " ").trim()
    if (collapsed.isBlank()) return null
    return if (collapsed.length > 140) {
        collapsed.take(140) + "…"
    } else {
        collapsed
    }
}

private data class PrefetchScope(val serverId: String, val accountId: String)

/**
 * Coordinates non-blocking background prefetching of latest message previews for
 * preview-less DM sessions upon opening the DM table.
 *
 * Enforces:
 * - Bounded concurrency via [Semaphore] (default 3).
 * - Per-session / server / principal isolation.
 * - Non-clobbering: skips DMs that already have a non-blank preview.
 * - Deduplication via in-flight and attempted tracking.
 * - Fail-open: network errors or missing messages result in empty second-line previews
 *   without disrupting or blocking list rendering.
 */
class SharedDirectMessagePreviewPrefetchCoordinator(
    private val fetchLatestMessage: suspend (ChannelId) -> DataResult<Message>,
    private val dispatchHome: (SharedHomeIntent) -> Unit,
    maxConcurrency: Int = 3,
    private val diagnostics: (String) -> Unit = {},
) {
    constructor(
        conversationRepository: ConversationRepository,
        dispatchHome: (SharedHomeIntent) -> Unit,
        maxConcurrency: Int = 3,
        diagnostics: (String) -> Unit = {},
    ) : this(
        fetchLatestMessage = { channelId ->
            conversationRepository.getLatestChannelMessage(channelId, DataRequestPriority.Background)
        },
        dispatchHome = dispatchHome,
        maxConcurrency = maxConcurrency,
        diagnostics = diagnostics,
    )

    private val lock = SynchronizedObject()
    private val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
    private val attemptedChannelIds = mutableMapOf<PrefetchScope, MutableSet<String>>()
    private val inFlightChannelIds = mutableMapOf<PrefetchScope, MutableSet<String>>()

    fun enqueue(
        serverId: ServerId,
        accountId: String,
        directMessages: List<HomeConversationRow>,
        scope: CoroutineScope,
    ) {
        val scopeKey = PrefetchScope(serverId = serverId.value, accountId = accountId)
        val candidates: List<HomeConversationRow> = synchronized(lock) {
            val inFlight = inFlightChannelIds.getOrPut(scopeKey) { mutableSetOf() }
            val attempted = attemptedChannelIds.getOrPut(scopeKey) { mutableSetOf() }

            directMessages
                .distinctBy { it.channelId?.value }
                .filter { dm ->
                    val cid = dm.channelId?.value ?: return@filter false
                    dm.lastMessagePreview.isNullOrBlank() &&
                        cid !in inFlight &&
                        cid !in attempted
                }
                .take(50)
                .also { list ->
                    list.forEach { dm ->
                        dm.channelId?.value?.let { inFlight.add(it) }
                    }
                }
        }

        if (candidates.isEmpty()) return

        diagnostics("dm_preview_prefetch_enqueue serverId=${serverId.value} count=${candidates.size}")

        candidates.forEach { candidate ->
            val channelId = candidate.channelId ?: return@forEach
            scope.launch {
                try {
                    semaphore.withPermit {
                        coroutineContext.ensureActive()
                        val result = fetchLatestMessage(channelId)
                        when (result) {
                            is DataResult.Content -> {
                                val message = result.data
                                val preview = normalizeMessagePreview(message.body)
                                val senderName = message.author.displayName.trim().takeIf { it.isNotBlank() }
                                    ?: message.author.handle.trim().takeIf { it.isNotBlank() }
                                dispatchHome(
                                    SharedHomeIntent.HydrateDirectMessagePreview(
                                        serverId = serverId,
                                        channelId = channelId,
                                        preview = preview,
                                        senderName = senderName,
                                        lastMessageAt = message.timestampLabel,
                                        lastMessageId = message.id,
                                        accountId = accountId,
                                    )
                                )
                            }
                            is DataResult.Empty -> {
                                diagnostics("dm_preview_prefetch_empty channelId=${channelId.value}")
                            }
                            is DataResult.Error -> {
                                diagnostics("dm_preview_prefetch_error channelId=${channelId.value} code=${result.error.code}")
                            }
                            is DataResult.NoPermission -> {
                                diagnostics("dm_preview_prefetch_no_permission channelId=${channelId.value} code=${result.blocker.code}")
                            }
                        }
                        synchronized(lock) {
                            attemptedChannelIds.getOrPut(scopeKey) { mutableSetOf() }.add(channelId.value)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    diagnostics("dm_preview_prefetch_failed channelId=${channelId.value} error=${throwable.message}")
                    synchronized(lock) {
                        attemptedChannelIds.getOrPut(scopeKey) { mutableSetOf() }.add(channelId.value)
                    }
                } finally {
                    synchronized(lock) {
                        inFlightChannelIds[scopeKey]?.remove(channelId.value)
                    }
                }
            }
        }
    }

    internal fun isAttempted(serverId: ServerId, accountId: String, channelId: String): Boolean =
        synchronized(lock) {
            attemptedChannelIds[PrefetchScope(serverId.value, accountId)]?.contains(channelId) == true
        }

    internal fun isInFlight(serverId: ServerId, accountId: String, channelId: String): Boolean =
        synchronized(lock) {
            inFlightChannelIds[PrefetchScope(serverId.value, accountId)]?.contains(channelId) == true
        }

    internal fun clear(serverId: ServerId? = null, accountId: String? = null) {
        synchronized(lock) {
            if (serverId != null && accountId != null) {
                val key = PrefetchScope(serverId.value, accountId)
                attemptedChannelIds.remove(key)
                inFlightChannelIds.remove(key)
            } else {
                attemptedChannelIds.clear()
                inFlightChannelIds.clear()
            }
        }
    }
}
