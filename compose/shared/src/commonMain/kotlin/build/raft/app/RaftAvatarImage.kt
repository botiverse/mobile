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
import kotlinx.coroutines.delay

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
 * candidate has failed (also while a backoff retry is pending). [cacheSubject] / [cacheServerId]
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
    var attempt by remember(candidates) { mutableStateOf(RaftAvatarImageAttempt()) }
    val model = candidates.getOrElse(attempt.candidateIndex) { candidates.first() }
    key(model, attempt.retry) {
        val painter = rememberAsyncImagePainter(model = model)
        val imageState by painter.state.collectAsState()
        val failed = imageState is AsyncImagePainter.State.Error
        LaunchedEffect(imageState) {
            val outcome = imageState.raftAvatarImageOutcome()
            val message =
                "source=${source.raftAvatarDiagnosticsSource()} candidate=${attempt.candidateIndex} " +
                    "retry=${attempt.retry} painter=${outcome.painter} dataSource=${outcome.dataSource}"
            diagnostics?.let { row ->
                row.logger("thread_avatar_painter messageHash=${row.messageHash} authorHash=${row.authorHash} $message")
            }
            serverDiagnosticsId?.let { serverId ->
                raftLogInfo(
                    "SlockServerAvatar",
                    "server_avatar_painter serverHash=${serverId.raftAvatarDiagnosticsHash()} role=$diagnosticsRole $message"
                )
            }
            if (imageState is AsyncImagePainter.State.Error) {
                val next = attempt.next(candidates.size) ?: return@LaunchedEffect
                if (next.retry != attempt.retry) delay(raftAvatarImageRetryDelayMillis(attempt.retry))
                attempt = next
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

/** Which avatar URL is being tried and how many full rounds have failed. */
internal data class RaftAvatarImageAttempt(
    val candidateIndex: Int = 0,
    val retry: Int = 0,
) {
    /** The next attempt after a failure, or null when the retry budget is spent. */
    fun next(candidateCount: Int): RaftAvatarImageAttempt? =
        when {
            candidateIndex + 1 < candidateCount -> copy(candidateIndex = candidateIndex + 1)
            retry + 1 <= RaftAvatarImageMaxRetries -> RaftAvatarImageAttempt(candidateIndex = 0, retry = retry + 1)
            else -> null
        }
}

internal const val RaftAvatarImageMaxRetries = 2

/** Backoff before full retry round `retry + 1` (failures are not cached by Coil). */
internal fun raftAvatarImageRetryDelayMillis(retry: Int): Long = 5_000L shl retry.coerceIn(0, 4)

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
