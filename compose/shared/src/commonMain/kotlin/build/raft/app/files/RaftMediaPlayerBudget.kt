package build.raft.app

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Distinguishes inline chat media cards from the dedicated full-screen file preview page so
 * inline cards share a strict process-wide decoder ceiling and lighter buffer policy without
 * competing with or evicting the active file preview.
 */
internal enum class RaftMediaPlayerRole {
    InlineVideo,
    InlineAudio,
    FilePreview,
}

/**
 * Structural playback surface role for [RaftVideoPlayer]. Defaults to [RaftMediaPlayerRole.FilePreview]
 * so standalone preview callers keep full buffer headroom; inline thread cards provide
 * [RaftMediaPlayerRole.InlineVideo] or [RaftMediaPlayerRole.InlineAudio].
 */
internal val LocalRaftVideoPlayerRole = staticCompositionLocalOf { RaftMediaPlayerRole.FilePreview }

/**
 * Maximum number of live inline native media players (ExoPlayer on Android, AVPlayer/VLC on iOS)
 * allowed simultaneously across the entire process. Keeps peak MediaCodec / AVPlayer decode
 * pipelines and heap buffers tightly bounded even in video-heavy threads or tall viewports
 * (fixing the 32-concurrent-ExoPlayer OOM/ANR in Hands `a4f86aed`, #proj-mobile:#389).
 */
internal const val RAFT_MAX_CONCURRENT_INLINE_MEDIA_PLAYERS = 2

/**
 * Inline chat media cards only mount their underlying platform player while their enclosing page
 * is active (top of navigation stack / selected shell tab), the card is laid out inside the
 * visible viewport, and the message is not being captured by "Generate image".
 */
internal fun threadInlineMediaShouldMountPlayer(
    pageActive: Boolean,
    onScreen: Boolean,
    shareCaptureActive: Boolean,
): Boolean = pageActive && onScreen && !shareCaptureActive

/**
 * Inline audio cards draw their own Compose controls and need zero native decoders until the user
 * taps Play or scrubs; video cards and the dedicated file preview prepare on attach so the first
 * frame is visible immediately (bounded by [RaftProcessMediaPlayerBudget]).
 */
internal fun raftMediaPlayerShouldPrepareOnAttach(
    role: RaftMediaPlayerRole,
    audioOnly: Boolean,
): Boolean =
    when (role) {
        RaftMediaPlayerRole.InlineAudio -> false
        RaftMediaPlayerRole.InlineVideo -> !audioOnly
        RaftMediaPlayerRole.FilePreview -> true
    }

/**
 * Process-wide concurrency cap and LRU eviction coordinator for native media players.
 *
 * Callers register **before** constructing the underlying ExoPlayer / AVPlayer / libVLC instance
 * so evicted victims release their decoders and buffers before the incoming player allocates its
 * own. All `onEvict` callbacks are invoked outside the internal lock so re-entrant `unregister()`
 * calls are safe.
 */
internal class RaftMediaPlayerBudget(
    private val maxInlinePlayers: Int = RAFT_MAX_CONCURRENT_INLINE_MEDIA_PLAYERS,
) {
    init {
        require(maxInlinePlayers >= 1) { "maxInlinePlayers must be >= 1" }
    }

    private data class Entry(
        val id: Long,
        val role: RaftMediaPlayerRole,
        var playing: Boolean,
        var fullscreen: Boolean,
        var lastUsedOrder: Long,
        val onEvict: () -> Unit,
    )

    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<Long, Entry>()
    private var nextId = 0L
    private var nextOrder = 0L

    fun register(
        role: RaftMediaPlayerRole,
        playing: Boolean = false,
        fullscreen: Boolean = false,
        onEvict: () -> Unit,
    ): Long {
        val evictedCallbacks = mutableListOf<() -> Unit>()
        val id =
            synchronized(lock) {
                val assignedId = ++nextId
                val order = ++nextOrder
                if (role == RaftMediaPlayerRole.FilePreview) {
                    // Opening a dedicated file preview immediately reclaims idle inline decoders
                    // so hardware MediaCodec / AVFoundation pipelines are freed up front.
                    val idleInlineIds =
                        entries.values
                            .filter { it.role != RaftMediaPlayerRole.FilePreview && !it.playing && !it.fullscreen }
                            .map { it.id }
                    for (victimId in idleInlineIds) {
                        entries.remove(victimId)?.let { evictedCallbacks += it.onEvict }
                    }
                } else {
                    while (inlineCountLocked() >= maxInlinePlayers) {
                        val victim = selectInlineEvictionCandidateLocked(excludeId = assignedId) ?: break
                        entries.remove(victim.id)?.let { evictedCallbacks += it.onEvict }
                    }
                }
                entries[assignedId] =
                    Entry(
                        id = assignedId,
                        role = role,
                        playing = playing,
                        fullscreen = fullscreen,
                        lastUsedOrder = order,
                        onEvict = onEvict,
                    )
                assignedId
            }
        evictedCallbacks.forEach { callback -> runCatching { callback() } }
        return id
    }

    fun markActivity(
        id: Long,
        playing: Boolean? = null,
        fullscreen: Boolean? = null,
        touch: Boolean = true,
    ) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            if (playing != null) entry.playing = playing
            if (fullscreen != null) entry.fullscreen = fullscreen
            if (touch) entry.lastUsedOrder = ++nextOrder
        }
    }

    fun unregister(id: Long) {
        synchronized(lock) {
            entries.remove(id)
        }
    }

    /** Releases every idle, non-fullscreen inline player (e.g. when the app goes to background). */
    fun evictAllIdleInline(): Int {
        val evictedCallbacks =
            synchronized(lock) {
                val victims =
                    entries.values
                        .filter { it.role != RaftMediaPlayerRole.FilePreview && !it.playing && !it.fullscreen }
                        .map { it.id }
                victims.mapNotNull { victimId -> entries.remove(victimId)?.onEvict }
            }
        evictedCallbacks.forEach { callback -> runCatching { callback() } }
        return evictedCallbacks.size
    }

    fun activeCount(): Int = synchronized(lock) { entries.size }

    fun activeInlineCount(): Int = synchronized(lock) { inlineCountLocked() }

    internal fun clearForTest() {
        synchronized(lock) {
            entries.clear()
        }
    }

    private fun inlineCountLocked(): Int = entries.values.count { it.role != RaftMediaPlayerRole.FilePreview }

    private fun selectInlineEvictionCandidateLocked(excludeId: Long): Entry? {
        val evictable =
            entries.values.filter {
                it.id != excludeId && it.role != RaftMediaPlayerRole.FilePreview && !it.fullscreen
            }
        if (evictable.isEmpty()) return null
        return evictable
            .filter { !it.playing }
            .minByOrNull { it.lastUsedOrder }
            ?: evictable.minByOrNull { it.lastUsedOrder }
    }
}

internal val RaftProcessMediaPlayerBudget = RaftMediaPlayerBudget()
