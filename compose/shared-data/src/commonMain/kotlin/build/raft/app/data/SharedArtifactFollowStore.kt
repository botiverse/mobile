package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The reader's own Follow and Unfollow answers (web `artifactOwnFollows.ts` +
 * `setArtifactFollowingHere`, botiverse/slock #16357), shared across Artifacts Home lists and
 * message Artifact cards for the active signed-in viewer.
 */
@RaftCoreInternalApi data class ArtifactOwnFollowEvent(
    val serverId: String,
    val artifactId: String,
    val following: Boolean,
    val seq: Long,
)

@RaftCoreInternalApi data class ArtifactOwnFollowEntry(
    val following: Boolean,
    val seq: Long,
)

@RaftCoreInternalApi data class SharedArtifactFollowState(
    val entries: Map<String, ArtifactOwnFollowEntry> = emptyMap(),
    val pendingKeys: Set<String> = emptySet(),
    val generation: Int = 0,
) {
    fun followingOf(serverId: String, artifactId: String, afterSeq: Long = 0L): Boolean? {
        val entry = entries[artifactFollowKey(serverId, artifactId)] ?: return null
        return entry.following.takeIf { entry.seq > afterSeq }
    }

    fun isPending(serverId: String, artifactId: String): Boolean =
        artifactFollowKey(serverId, artifactId) in pendingKeys
}

@RaftCoreInternalApi fun artifactFollowKey(serverId: String, artifactId: String): String =
    "${serverId.trim()}\u0000${artifactCanonicalId(artifactId)}"

@RaftCoreInternalApi class SharedArtifactFollowStore(
    private val setFollowingRequest: suspend (serverId: String, artifactId: String, following: Boolean) -> ArtifactsHomeLoad<Boolean>,
    private val onFollowUpdated: (serverId: String, artifactId: String, following: Boolean) -> Unit = { _, _, _ -> },
) {
    private val lock = SynchronizedObject()
    private var lastSeq = 0L
    private val serverGenerations = HashMap<String, Long>()

    private val mutableState = MutableStateFlow(SharedArtifactFollowState())
    val state: StateFlow<SharedArtifactFollowState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<ArtifactOwnFollowEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ArtifactOwnFollowEvent> = mutableEvents.asSharedFlow()

    fun currentSeq(): Long = synchronized(lock) { lastSeq }

    /**
     * Follows or unfollows [artifactId] in [serverId], single-flight per `(serverId, canonicalId)`.
     * Returns null when a follow write for that Artifact is already in flight.
     */
    suspend fun setFollowing(serverId: String, artifactId: String, following: Boolean): ArtifactsHomeLoad<Boolean>? {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
        val key = artifactFollowKey(cleanServer, artifactId)
        val tokens =
            synchronized(lock) {
                if (key in mutableState.value.pendingKeys) return null
                val gen = mutableState.value.generation
                val serverGen = serverGenerations[cleanServer] ?: 0L
                mutableState.update { it.copy(pendingKeys = it.pendingKeys + key) }
                gen to serverGen
            }
        val (generation, serverGeneration) = tokens
        return try {
            val result = setFollowingRequest(cleanServer, artifactId, following)
            if (result is ArtifactsHomeLoad.Ok) {
                recordLocked(cleanServer, artifactId, result.value, generation, serverGeneration)
            }
            result
        } finally {
            synchronized(lock) {
                mutableState.update { state ->
                    if (key in state.pendingKeys) state.copy(pendingKeys = state.pendingKeys - key) else state
                }
            }
        }
    }

    /** Records an own Follow/Unfollow answer made outside [setFollowing] (e.g. from Artifacts Home). */
    fun reportOwnFollow(serverId: String, artifactId: String, following: Boolean) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val (generation, serverGeneration) =
            synchronized(lock) {
                mutableState.value.generation to (serverGenerations[cleanServer] ?: 0L)
            }
        recordLocked(cleanServer, artifactId, following, generation, serverGeneration)
    }

    fun dropServer(serverId: String) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val prefix = "$cleanServer\u0000"
        synchronized(lock) {
            serverGenerations[cleanServer] = (serverGenerations[cleanServer] ?: 0L) + 1L
            mutableState.update { state ->
                val nextEntries = state.entries.filterKeys { !it.startsWith(prefix) }
                val nextPending = state.pendingKeys.filterNotTo(LinkedHashSet()) { it.startsWith(prefix) }
                if (nextEntries.size == state.entries.size && nextPending.size == state.pendingKeys.size) {
                    state
                } else {
                    state.copy(entries = nextEntries, pendingKeys = nextPending)
                }
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            serverGenerations.clear()
            mutableState.update { SharedArtifactFollowState(generation = it.generation + 1) }
        }
    }

    private fun recordLocked(
        serverId: String,
        artifactId: String,
        following: Boolean,
        expectedGeneration: Int,
        expectedServerGeneration: Long,
    ) {
        val key = artifactFollowKey(serverId, artifactId)
        val emitted =
            synchronized(lock) {
                if (mutableState.value.generation != expectedGeneration) return
                if ((serverGenerations[serverId] ?: 0L) != expectedServerGeneration) return
                val seq = ++lastSeq
                mutableState.update { state ->
                    if (state.generation != expectedGeneration) state
                    else state.copy(entries = state.entries + (key to ArtifactOwnFollowEntry(following = following, seq = seq)))
                }
                ArtifactOwnFollowEvent(serverId = serverId, artifactId = artifactId, following = following, seq = seq)
            }
        onFollowUpdated(serverId, artifactId, following)
        mutableEvents.tryEmit(emitted)
    }
}
