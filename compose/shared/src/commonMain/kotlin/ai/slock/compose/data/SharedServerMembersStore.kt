package ai.slock.compose.data

import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
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
    val entriesByServerId: Map<String, SharedServerMembersEntry> = emptyMap(),
    val presenceByMemberId: Map<String, MemberPresenceUpdated> = emptyMap()
) {
    fun entryFor(serverId: ServerId): SharedServerMembersEntry? =
        entriesByServerId[serverId.value.trim()]

    fun presenceFor(memberId: String?): MemberPresenceUpdated? =
        memberId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { presenceByMemberId[it] }
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
        val presenceFacts = _state.value.presenceByMemberId
        putEntry(
            key,
            SharedServerMembersEntry(
                serverId = key,
                server = snapshot.server,
                members = snapshot.members.withServerMemberPresenceFacts(presenceFacts),
                status = SharedServerMembersLoadStatus.Loaded,
                errorCode = null
            )
        )
    }

    fun applyPresence(event: MemberPresenceUpdated) {
        val memberId = event.memberId.value.trim()
        if (memberId.isBlank()) return
        val current = _state.value
        val previousFact = current.presenceByMemberId[memberId]
        val nextPresenceFacts =
            if (event.shouldReplacePresenceFact(previousFact)) {
                current.presenceByMemberId + (memberId to event)
            } else {
                current.presenceByMemberId
            }
        var changed = false
        val nextEntries =
            current.entriesByServerId.mapValues { (_, entry) ->
                var entryChanged = false
                val nextMembers =
                    entry.members.map { member ->
                        if (member.kind == MemberKind.Agent && member.id.value == memberId) {
                            entryChanged = true
                            member.copy(
                                presence = event.presence,
                                agentStatus = event.agentStatus ?: member.agentStatus
                            )
                        } else {
                            member
                        }
                    }
                if (entryChanged) {
                    changed = true
                    entry.copy(members = nextMembers)
                } else {
                    entry
                }
            }
        if (!changed && nextPresenceFacts == current.presenceByMemberId) return
        _state.value =
            current.copy(
                entriesByServerId = nextEntries,
                presenceByMemberId = nextPresenceFacts
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
                    val snapshot =
                        result.data.copy(
                            members = result.data.members.withServerMemberPresenceFacts(_state.value.presenceByMemberId)
                        )
                    putEntry(
                        normalizedKey,
                        SharedServerMembersEntry(
                            serverId = normalizedKey,
                            server = snapshot.server,
                            members = snapshot.members,
                            status = SharedServerMembersLoadStatus.Loaded,
                            errorCode = null
                        )
                    )
                    if (normalizedKey != key) removeEntry(key)
                    logger(
                        "shared_server_members_load_result serverId=$normalizedKey result=content members=${snapshot.members.size}"
                    )
                    return@withLock DataResult.Content(snapshot)
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

private fun MemberPresenceUpdated.shouldReplacePresenceFact(previous: MemberPresenceUpdated?): Boolean =
    when {
        previous == null -> true
        serverSeq != null && previous.serverSeq != null -> serverSeq >= previous.serverSeq
        serverSeq != null && previous.serverSeq == null -> true
        else -> true
    }

private fun List<MemberProfile>.withServerMemberPresenceFacts(
    presenceFacts: Map<String, MemberPresenceUpdated>
): List<MemberProfile> =
    map { member ->
        val event = presenceFacts[member.id.value.trim()]
            ?.takeIf { member.kind == MemberKind.Agent }
            ?: return@map member
        member.copy(
            presence = event.presence,
            agentStatus = event.agentStatus ?: member.agentStatus
        )
    }
