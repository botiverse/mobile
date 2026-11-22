package build.raft.app

import androidx.compose.runtime.compositionLocalOf
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.data.normalizedOrNull
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val ThreadInlineAttachmentUrlBatchMaxSize = 50
internal const val ThreadInlineAttachmentUrlBatchDebounceMillis = 30L

internal data class ThreadInlineImageFallbackState(
    val requestedAttachmentIds: Set<String> = emptySet(),
    val resolvedUrls: Map<String, String> = emptyMap()
) {
    fun beginRequest(attachmentId: String): Pair<ThreadInlineImageFallbackState, Boolean> {
        if (attachmentId.isBlank() || attachmentId in requestedAttachmentIds) return this to false
        return copy(requestedAttachmentIds = requestedAttachmentIds + attachmentId) to true
    }

    fun resolve(attachmentId: String, url: String): ThreadInlineImageFallbackState {
        val cleanUrl = url.trim()
        if (attachmentId.isBlank() || cleanUrl.isBlank()) return this
        return copy(resolvedUrls = resolvedUrls + (attachmentId to cleanUrl))
    }

    fun resolveAll(urlsByAttachmentId: Map<String, String>): ThreadInlineImageFallbackState {
        if (urlsByAttachmentId.isEmpty()) return this
        val next = LinkedHashMap(resolvedUrls)
        var changed = false
        urlsByAttachmentId.forEach { (id, rawUrl) ->
            val cleanId = id.trim()
            val cleanUrl = rawUrl.trim()
            if (cleanId.isNotEmpty() && cleanUrl.isNotEmpty() && next[cleanId] != cleanUrl) {
                next[cleanId] = cleanUrl
                changed = true
            }
        }
        return if (changed) copy(resolvedUrls = next) else this
    }

    fun invalidate(attachmentId: String, expectedUrl: String? = null): ThreadInlineImageFallbackState {
        val cleanId = attachmentId.trim()
        if (cleanId.isEmpty()) return this
        val current = resolvedUrls[cleanId]
        if (expectedUrl != null && current != null && current != expectedUrl.trim()) return this
        return copy(
            requestedAttachmentIds = requestedAttachmentIds - cleanId,
            resolvedUrls = resolvedUrls - cleanId
        )
    }
}

/**
 * #proj-mobile:#293: coalesces missing-thumbnail inline image URL requests on one screen into a
 * single `POST /api/attachments/urls` batch (web `inlineAttachmentUrlCache.ts` parity) and shares
 * the resolved valid URLs with full-screen preview via [urlCache].
 */
internal class ThreadInlineAttachmentUrlBatcher(
    private val coroutineScope: CoroutineScope,
    private val ownerProvider: () -> AuthenticatedRouteOwner?,
    private val urlCache: RaftFilePreviewAttachmentUrlCache = raftFilePreviewAttachmentUrlCache(),
    private val fetchBatch: suspend (List<String>) -> ThreadApiResult<Map<String, ThreadAttachmentOpenTarget>>,
    private val readState: () -> ThreadInlineImageFallbackState,
    private val writeState: (ThreadInlineImageFallbackState) -> Unit,
    private val debounceMillis: Long = ThreadInlineAttachmentUrlBatchDebounceMillis,
    private val maxBatchSize: Int = ThreadInlineAttachmentUrlBatchMaxSize,
    private val logger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private val pendingIds = LinkedHashSet<String>()
    private val retriedIds = mutableSetOf<String>()
    private var flight: Job? = null

    fun request(attachmentId: String) {
        val cleanId = attachmentId.trim()
        if (cleanId.isEmpty()) return
        val owner = ownerProvider()?.normalizedOrNull()
        if (owner != null) {
            val cacheKey = RaftFilePreviewAttachmentUrlCacheKey(owner, cleanId, "inline")
            val cachedUrl = urlCache.get(cacheKey)
            if (cachedUrl != null) {
                val (begun, _) = readState().beginRequest(cleanId)
                writeState(begun.resolve(cleanId, cachedUrl))
                logger("thread_inline_image_fallback status=cache_hit attachmentId=${cleanId.take(8)}")
                return
            }
        }
        val (nextState, shouldRequest) = readState().beginRequest(cleanId)
        writeState(nextState)
        if (!shouldRequest) return
        enqueue(cleanId)
    }

    /** Drops a cached inline URL that failed to load (e.g. expired signature) and re-requests once. */
    fun invalidateAndRetry(attachmentId: String, failedUrl: String) {
        val cleanId = attachmentId.trim()
        val cleanFailed = failedUrl.trim()
        if (cleanId.isEmpty() || cleanFailed.isEmpty()) return
        val canRetry = synchronized(lock) { retriedIds.add(cleanId) }
        if (!canRetry) return
        ownerProvider()?.normalizedOrNull()?.let { owner ->
            urlCache.invalidate(RaftFilePreviewAttachmentUrlCacheKey(owner, cleanId, "inline"), expectedUrl = cleanFailed)
        }
        writeState(readState().invalidate(cleanId, expectedUrl = cleanFailed))
        request(cleanId)
    }

    private fun enqueue(attachmentId: String) {
        val jobToStart =
            synchronized(lock) {
                pendingIds += attachmentId
                if (flight != null) return
                coroutineScope.launch(start = CoroutineStart.LAZY) { runBatchLoop() }.also { flight = it }
            }
        if (!jobToStart.start()) {
            synchronized(lock) {
                if (flight === jobToStart) flight = null
            }
        }
    }

    private suspend fun runBatchLoop() {
        val self = currentCoroutineContext()[Job]
        try {
            while (true) {
                if (debounceMillis > 0L) delay(debounceMillis)
                val batch =
                    synchronized(lock) {
                        if (pendingIds.isEmpty()) {
                            if (flight === self) flight = null
                            return
                        }
                        val next = pendingIds.toList()
                        pendingIds.clear()
                        next
                    }
                for (chunk in batch.chunked(maxBatchSize.coerceAtLeast(1))) {
                    val owner = ownerProvider()?.normalizedOrNull()
                    val cacheHits = LinkedHashMap<String, String>()
                    val missing = ArrayList<String>(chunk.size)
                    chunk.forEach { id ->
                        val cached = owner?.let { urlCache.get(RaftFilePreviewAttachmentUrlCacheKey(it, id, "inline")) }
                        if (cached != null) cacheHits[id] = cached else missing += id
                    }
                    if (cacheHits.isNotEmpty()) {
                        writeState(readState().resolveAll(cacheHits))
                    }
                    if (missing.isEmpty()) continue
                    logger("thread_inline_image_fallback status=batch_start count=${missing.size}")
                    val response =
                        try {
                            fetchBatch(missing)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            continue
                        }
                    val currentOwner = ownerProvider()?.normalizedOrNull()
                    if (currentOwner != owner) {
                        logger("thread_inline_image_fallback status=batch_dropped reason=owner_changed")
                        continue
                    }
                    when (response) {
                        is ThreadApiResult.Success -> {
                            val resolvedUrls = LinkedHashMap<String, String>()
                            response.data.forEach { (id, target) ->
                                val cleanId = id.trim()
                                val cleanUrl = target.url.trim()
                                if (cleanId.isNotEmpty() && cleanUrl.isNotEmpty()) {
                                    resolvedUrls[cleanId] = cleanUrl
                                    if (currentOwner != null) {
                                        urlCache.put(
                                            key = RaftFilePreviewAttachmentUrlCacheKey(currentOwner, cleanId, "inline"),
                                            url = cleanUrl,
                                            expiresAtEpochMillis = parseRaftTimestampEpochMillisOrNull(target.expiresAt),
                                            fallbackTtlMillis = RaftFilePreviewAttachmentUrlCache.DefaultInlineFallbackUrlTtlMillis,
                                        )
                                    }
                                }
                            }
                            if (resolvedUrls.isNotEmpty()) {
                                writeState(readState().resolveAll(resolvedUrls))
                            }
                            logger(
                                "thread_inline_image_fallback status=batch_resolved " +
                                    "requested=${missing.size} resolved=${resolvedUrls.size}"
                            )
                        }
                        is ThreadApiResult.Failure -> {
                            logger(
                                "thread_inline_image_fallback status=batch_failed count=${missing.size} " +
                                    "code=${response.code} contractCode=${response.contractCode.orEmpty()}"
                            )
                        }
                    }
                }
                synchronized(lock) {
                    if (pendingIds.isEmpty()) {
                        if (flight === self) flight = null
                        return
                    }
                }
            }
        } finally {
            synchronized(lock) {
                if (flight === self) flight = null
            }
        }
    }
}

internal fun shouldRequestThreadInlineImageFallbackForMissingThumbnail(
    thumbnailUrl: String?,
    fallbackUrl: String?
): Boolean = thumbnailUrl.isNullOrBlank() && fallbackUrl.isNullOrBlank()

/**
 * What a thread inline image loads. An image this device just uploaded ([localModel], see
 * `RaftSentAttachmentLocalImages`) renders from the local copy and never asks for the remote
 * inline URL; the remote thumbnail / fallback stays as the error fallback.
 *
 * One that may animate (`mayAnimate`: GIF / WebP) moves to its server thumbnail once the message
 * has one: that bounded animated WebP (slock #16159) is the only source a tile plays. The
 * full-size local copy shows a still until then, and stays the fallback (and the underlay while
 * the thumbnail loads).
 */
internal data class ThreadInlineImageSourcePlan(
    val source: String?,
    val fallbackSource: String?,
    val requestRemoteFallback: Boolean,
    /** [source] is the server thumbnail: the only source an animated image plays from. */
    val sourceIsThumbnail: Boolean = false
)

internal fun threadInlineImageSourcePlan(
    thumbnailUrl: String?,
    fallbackUrl: String?,
    localModel: String?,
    mayAnimate: Boolean = false
): ThreadInlineImageSourcePlan {
    val thumbnail = thumbnailUrl?.takeIf { it.isNotBlank() }
    val fallback = fallbackUrl?.takeIf { it.isNotBlank() }
    val local = localModel?.takeIf { it.isNotBlank() }
    if (local != null) {
        if (mayAnimate && thumbnail != null) {
            return ThreadInlineImageSourcePlan(
                source = thumbnail,
                fallbackSource = local,
                requestRemoteFallback = false,
                sourceIsThumbnail = true
            )
        }
        return ThreadInlineImageSourcePlan(
            source = local,
            fallbackSource = thumbnail ?: fallback,
            requestRemoteFallback = false
        )
    }
    return ThreadInlineImageSourcePlan(
        source = thumbnail,
        fallbackSource = fallback,
        requestRemoteFallback = shouldRequestThreadInlineImageFallbackForMissingThumbnail(thumbnail, fallback),
        sourceIsThumbnail = thumbnail != null
    )
}

internal data class ThreadInlineImageFallbackHost(
    val state: ThreadInlineImageFallbackState,
    val requestFallback: (attachmentId: String) -> Unit,
    val onFallbackError: ((attachmentId: String, failedUrl: String) -> Unit)? = null,
)

internal val LocalThreadInlineImageFallbackHost =
    compositionLocalOf<ThreadInlineImageFallbackHost?> { null }
