package build.raft.app

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.model.appAvatarApiFallbackUrl
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import build.raft.app.image.raftNetworkImageCacheKey
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * An http(s) avatar, loaded entirely through the Raft Coil image loader.
 *
 * Coil owns the whole pipeline: the memory cache, the disk cache (which survives a cold start) and
 * single-flight downloads. Both caches are keyed by the avatar's CONTENT
 * ([build.raft.app.image.raftNetworkImageCacheKey]: the content-addressed `/avatars/<ns>/<hash>.webp`
 * path, the Gravatar hash, or the fragment-less URL), never by server, account, host or endpoint.
 * The same person's avatar is therefore one cache entry across every server, and the CDN URL and
 * its API fallback hit the same disk entry.
 *
 * This replaces the process-local HTTP load coordinator and the managed (owner/server-scoped)
 * avatar binding, whose lease gate kept every row on its placeholder until a LaunchedEffect round
 * trip had granted the request, even when the bytes were already on disk (Hands dd79da45).
 *
 * Rendering: [placeholder] until the image is decoded, the image on success, [fallback] once every
 * candidate has failed (also while its process-wide failure cooldown runs). [cacheSubject] / [cacheServerId]
 * are accepted for call-site compatibility; they are deliberately not part of the cache identity.
 */
@Composable
internal fun RaftAvatarHttpImage(
    source: String,
    modifier: Modifier = Modifier,
    diagnostics: RaftAvatarRowDiagnostics? = null,
    @Suppress("UNUSED_PARAMETER") cacheSubject: RaftAvatarCacheSubject? = null,
    @Suppress("UNUSED_PARAMETER") cacheServerId: String? = null,
    fallback: @Composable () -> Unit = {},
    serverDiagnosticsId: String? = null,
    // "visible" or "prewarm" in server avatar logs.
    diagnosticsRole: String = "visible",
    placeholder: @Composable () -> Unit = {}
) {
    val apiBaseUrl =
        RaftSharedStoreRegistry.currentOrNull()
            ?.apiEnvironmentStore
            ?.current()
            ?.apiBaseUrl
            ?.trimEnd('/')
    val candidates = remember(source, apiBaseUrl) { raftAvatarImageCandidates(source, apiBaseUrl) }
    val cooldownKey = remember(candidates) { raftNetworkImageCacheKey(candidates.first()) }
    // A full round = every candidate tried once. Failed rounds are remembered process-wide (keyed by
    // content), so a failed avatar scrolled back into view shows its fallback without refetching.
    var round by remember(candidates) { mutableStateOf(0) }
    var candidateIndex by remember(candidates) { mutableStateOf(0) }
    val coolingDownMillis = remember(cooldownKey, round) { raftAvatarFailureCooldown.retryDelayMillis(cooldownKey) }
    if (coolingDownMillis > 0L) {
        fallback()
        LaunchedEffect(cooldownKey, round) {
            delay(coolingDownMillis)
            candidateIndex = 0
            round += 1
        }
        return
    }
    val model = candidates.getOrElse(candidateIndex) { candidates.first() }
    key(model, round) {
        val painter = rememberAsyncImagePainter(model = model)
        val imageState by painter.state.collectAsState()
        val failed = imageState is AsyncImagePainter.State.Error
        LaunchedEffect(imageState) {
            val outcome = imageState.raftAvatarImageOutcome()
            val message =
                "source=${source.raftAvatarDiagnosticsSource()} candidate=$candidateIndex " +
                    "round=$round painter=${outcome.painter} dataSource=${outcome.dataSource}"
            diagnostics?.let { row ->
                row.logger("thread_avatar_painter messageHash=${row.messageHash} authorHash=${row.authorHash} $message")
            }
            serverDiagnosticsId?.let { serverId ->
                raftLogInfo(
                    "SlockServerAvatar",
                    "server_avatar_painter serverHash=${serverId.raftAvatarDiagnosticsHash()} role=$diagnosticsRole $message"
                )
            }
            when (imageState) {
                is AsyncImagePainter.State.Success -> raftAvatarFailureCooldown.recordSuccess(cooldownKey)
                is AsyncImagePainter.State.Error ->
                    if (candidateIndex + 1 < candidates.size) {
                        candidateIndex += 1
                    } else {
                        raftAvatarFailureCooldown.recordFailure(cooldownKey)
                        candidateIndex = 0
                        round += 1
                    }
                else -> Unit
            }
        }
        when {
            imageState is AsyncImagePainter.State.Success ->
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = modifier,
                    contentScale = ContentScale.FillBounds
                )
            failed -> fallback()
            else -> {
                placeholder()
                // Keep the painter attached so it starts (and finishes) its request.
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = modifier,
                    contentScale = ContentScale.FillBounds
                )
            }
        }
    }
}

/**
 * Process-wide failure cooldown of http avatars, keyed by content ([raftNetworkImageCacheKey]).
 * Coil does not cache failures, so without it every recomposition of a failed row (scrolling,
 * server switch) would fetch again. Consecutive failures back off exponentially up to
 * [maxCooldownMillis]; a success clears the key.
 */
internal class RaftAvatarFailureCooldown(
    private val maxEntries: Int = 512,
    private val baseCooldownMillis: Long = 10_000L,
    private val maxCooldownMillis: Long = 5 * 60 * 1_000L,
    private val nowMillis: () -> Long = { raftAvatarCooldownOrigin.elapsedNow().inWholeMilliseconds },
) {
    private class Entry(val failures: Int, val retryAtMillis: Long)

    private val lock = SynchronizedObject()
    private val entries = linkedMapOf<String, Entry>()

    /** Remaining cooldown of [key] in ms; 0 when it may load now. */
    fun retryDelayMillis(key: String): Long =
        synchronized(lock) {
            val entry = entries[key] ?: return@synchronized 0L
            (entry.retryAtMillis - nowMillis()).coerceAtLeast(0L)
        }

    fun recordFailure(key: String) {
        synchronized(lock) {
            val failures = (entries.remove(key)?.failures ?: 0) + 1
            val cooldown =
                (baseCooldownMillis shl (failures - 1).coerceIn(0, 16)).coerceAtMost(maxCooldownMillis)
            entries[key] = Entry(failures, nowMillis() + cooldown)
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
    }

    fun recordSuccess(key: String) {
        synchronized(lock) { entries.remove(key) }
    }
}

private val raftAvatarCooldownOrigin = TimeSource.Monotonic.markNow()

internal val raftAvatarFailureCooldown = RaftAvatarFailureCooldown()

/**
 * The URLs to try for [source], in order: the source itself, then (for a raft CDN avatar) the same
 * path on the API host, which serves the identical content-addressed bytes when the CDN is
 * unreachable. Both share one Coil cache entry.
 */
internal fun raftAvatarImageCandidates(source: String, apiBaseUrl: String?): List<String> {
    val primary = source.trim()
    val apiFallback =
        primary.appAvatarApiFallbackUrl(apiBaseUrl)?.takeUnless { it == primary }
    return listOfNotNull(primary, apiFallback)
}

private data class RaftAvatarImageOutcome(val painter: String, val dataSource: String)

private fun AsyncImagePainter.State.raftAvatarImageOutcome(): RaftAvatarImageOutcome =
    when (this) {
        is AsyncImagePainter.State.Empty -> RaftAvatarImageOutcome("empty", "none")
        is AsyncImagePainter.State.Loading -> RaftAvatarImageOutcome("loading", "none")
        is AsyncImagePainter.State.Success -> RaftAvatarImageOutcome("success", result.dataSource.name.lowercase())
        is AsyncImagePainter.State.Error -> RaftAvatarImageOutcome("error", "none")
    }
