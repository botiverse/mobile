package ai.slock.compose.data

import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SharedServerMembersLoadStatus {
    Idle,
    InFlight,
    Loaded,
    Error
}

data class SharedServerMembersEntry(
    val serverId: String,
    val server: ServerSummary? = null,
    val members: List<MemberProfile> = emptyList(),
    val status: SharedServerMembersLoadStatus = SharedServerMembersLoadStatus.Idle,
    val errorCode: String? = null
) {
    fun snapshotOrNull(): MemberSnapshot? =
        server?.let { MemberSnapshot(server = it, members = members) }
}

data class SharedServerMembersState(
    val entriesByServerId: Map<String, SharedServerMembersEntry> = emptyMap()
) {
    fun entryFor(serverId: ServerId): SharedServerMembersEntry? =
        entriesByServerId[serverId.value.trim()]
}

/**
 * Server-scoped members directory shared by surfaces that need server members.
 *
 * Channel/thread member lists remain channel-scoped. This store owns the full
 * server roster request so Thread, Search, Activity, Members, and add-member
 * flows do not each issue independent `getMembers(serverId)` calls.
 */
class SharedServerMembersStore(
    private val memberRepository: MemberRepository,
    private val logger: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SharedServerMembersState())
    val state: StateFlow<SharedServerMembersState> = _state.asStateFlow()

    fun cachedSnapshot(serverId: ServerId): MemberSnapshot? =
        _state.value.entryFor(serverId)?.snapshotOrNull()

    fun seed(snapshot: MemberSnapshot) {
        val key = snapshot.server.id.value.trim()
        if (key.isBlank()) return
        putEntry(
            key,
            SharedServerMembersEntry(
                serverId = key,
                server = snapshot.server,
                members = snapshot.members,
                status = SharedServerMembersLoadStatus.Loaded,
                errorCode = null
            )
        )
    }

    suspend fun loadMembers(
        serverId: ServerId,
        priority: DataRequestPriority = DataRequestPriority.Visible,
        force: Boolean = false
    ): DataResult<MemberSnapshot> {
        val key = serverId.value.trim()
        if (key.isBlank()) {
            return DataResult.Error(ScreenError(code = "server_missing", retryable = false))
        }
        cachedLoadedSnapshot(key, force)?.let { return DataResult.Content(it) }
        return mutex.withLock {
            cachedLoadedSnapshot(key, force)?.let { return@withLock DataResult.Content(it) }
            val previous = _state.value.entriesByServerId[key]
            putEntry(
                key,
                (previous ?: SharedServerMembersEntry(serverId = key)).copy(
                    status = SharedServerMembersLoadStatus.InFlight,
                    errorCode = null
                )
            )
            val result =
                try {
                    memberRepository.getMembers(priority, ServerId(key))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    logger("shared_server_members_load_result serverId=$key result=exception type=${throwable::class.simpleName}")
                    val error = DataResult.Error(ScreenError(code = "members_exception", retryable = true))
                    markFailure(key, previous, error.error.code)
                    return@withLock error
                }
            when (result) {
                is DataResult.Content -> {
                    val normalizedKey = result.data.server.id.value.trim().ifBlank { key }
                    putEntry(
                        normalizedKey,
                        SharedServerMembersEntry(
                            serverId = normalizedKey,
                            server = result.data.server,
                            members = result.data.members,
                            status = SharedServerMembersLoadStatus.Loaded,
                            errorCode = null
                        )
                    )
                    if (normalizedKey != key) removeEntry(key)
                    logger(
                        "shared_server_members_load_result serverId=$normalizedKey result=content members=${result.data.members.size}"
                    )
                }
                is DataResult.Empty -> {
                    putEntry(
                        key,
                        SharedServerMembersEntry(
                            serverId = key,
                            server = previous?.server,
                            members = emptyList(),
                            status = SharedServerMembersLoadStatus.Loaded,
                            errorCode = null
                        )
                    )
                    logger("shared_server_members_load_result serverId=$key result=empty")
                }
                is DataResult.Error -> {
                    markFailure(key, previous, result.error.code)
                    logger("shared_server_members_load_result serverId=$key result=error code=${result.error.code}")
                }
                is DataResult.NoPermission -> {
                    markFailure(key, previous, result.blocker.code)
                    logger("shared_server_members_load_result serverId=$key result=no_permission code=${result.blocker.code}")
                }
            }
            result
        }
    }

    private fun cachedLoadedSnapshot(key: String, force: Boolean): MemberSnapshot? {
        if (force) return null
        val entry = _state.value.entriesByServerId[key] ?: return null
        if (entry.status != SharedServerMembersLoadStatus.Loaded) return null
        return entry.snapshotOrNull()
    }

    private fun markFailure(
        key: String,
        previous: SharedServerMembersEntry?,
        code: String
    ) {
        putEntry(
            key,
            (previous ?: SharedServerMembersEntry(serverId = key)).copy(
                status = SharedServerMembersLoadStatus.Error,
                errorCode = code
            )
        )
    }

    private fun putEntry(
        key: String,
        entry: SharedServerMembersEntry
    ) {
        _state.value =
            _state.value.copy(
                entriesByServerId = _state.value.entriesByServerId + (key to entry)
            )
    }

    private fun removeEntry(key: String) {
        _state.value =
            _state.value.copy(
                entriesByServerId = _state.value.entriesByServerId - key
            )
    }
}
