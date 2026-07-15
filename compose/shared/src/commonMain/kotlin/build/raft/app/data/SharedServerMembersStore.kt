package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.avatarSlotResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

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

data class SharedServerChannelMembersScope(
    val serverId: String,
    val channelId: String
)

data class SharedServerChannelMembersSlice(
    val serverId: String? = null,
    val channelId: String,
    val members: List<MemberProfile> = emptyList(),
    val canAddMembers: Boolean = true,
    val status: SharedServerMembersLoadStatus = SharedServerMembersLoadStatus.Idle,
    val errorCode: String? = null
) {
    fun snapshotOrNull(): ChannelMemberSnapshot? =
        channelId.takeIf { it.isNotBlank() }?.let {
            ChannelMemberSnapshot(
                channelId = ChannelId(it),
                members = members,
                canAddMembers = canAddMembers
            )
        }
}

data class SharedServerMembersState(
    val entriesByServerId: Map<String, SharedServerMembersEntry> = emptyMap(),
    val presenceByMemberId: Map<String, MemberPresenceUpdated> = emptyMap(),
    val memberSuggestionSlices: Map<SharedMembersMentionScope, SharedMembersMemberSuggestionSlice> = emptyMap(),
    val channelMemberSlices: Map<SharedServerChannelMembersScope, SharedServerChannelMembersSlice> = emptyMap()
) {
    fun entryFor(serverId: ServerId): SharedServerMembersEntry? =
        entriesByServerId[serverId.value.trim()]

    fun presenceFor(memberId: String?): MemberPresenceUpdated? =
        memberId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { presenceByMemberId[it] }

    fun memberSuggestionSlice(
        serverId: ServerId,
        mentionChannelId: String? = null,
        memberScopedMentions: Boolean = false
    ): SharedMembersMemberSuggestionSlice =
        memberSuggestionSlices[
            SharedMembersMentionScope(
                serverId = serverId.value.trim(),
                mentionChannelId = mentionChannelId.normalizedServerMembersId(),
                memberScopedMentions = memberScopedMentions
            )
        ] ?: SharedMembersMemberSuggestionSlice()

    fun channelMemberSlice(
        serverId: ServerId,
        channelId: ChannelId
    ): SharedServerChannelMembersSlice =
        channelMemberSlices[
            SharedServerChannelMembersScope(
                serverId = serverId.value.trim(),
                channelId = channelId.value.trim()
            )
        ] ?: SharedServerChannelMembersSlice(
            serverId = serverId.value.trim(),
            channelId = channelId.value.trim()
        )

    fun memberStatus(
        serverId: ServerId?,
        memberId: String?
    ): SharedServerMemberStatus? {
        val cleanMemberId = memberId.normalizedServerMembersId() ?: return null
        val cleanServerId = serverId?.value.normalizedServerMembersId()
        val entries =
            if (cleanServerId != null) {
                listOfNotNull(entriesByServerId[cleanServerId])
            } else {
                entriesByServerId.values.toList()
            }
        val member =
            entries
                .asSequence()
                .flatMap { it.members.asSequence() }
                .firstOrNull { it.kind == MemberKind.Agent && it.id.value.trim() == cleanMemberId }
                ?: channelMemberSlices.values
                    .asSequence()
                    .filter { cleanServerId == null || it.serverId?.trim() == cleanServerId }
                    .flatMap { it.members.asSequence() }
                    .firstOrNull { it.kind == MemberKind.Agent && it.id.value.trim() == cleanMemberId }
        return member.toSharedServerMemberStatus(presenceByMemberId[cleanMemberId])
    }

    fun memberProfile(
        serverId: ServerId?,
        memberId: String?
    ): MemberProfile? {
        val cleanMemberId = memberId.normalizedServerMembersId() ?: return null
        val cleanServerId = serverId?.value.normalizedServerMembersId()
        val entries =
            if (cleanServerId != null) {
                listOfNotNull(entriesByServerId[cleanServerId])
            } else {
                entriesByServerId.values.toList()
            }
        return entries
            .asSequence()
            .flatMap { it.members.asSequence() }
            .firstOrNull { it.id.value.trim() == cleanMemberId }
            ?: channelMemberSlices.values
                .asSequence()
                .filter { cleanServerId == null || it.serverId?.trim() == cleanServerId }
                .flatMap { it.members.asSequence() }
                    .firstOrNull { it.id.value.trim() == cleanMemberId }
    }

    fun canonicalMentionHandle(
        serverId: ServerId,
        mentionChannelId: String?,
        memberId: String?
    ): String? {
        val cleanServerId = serverId.value.normalizedServerMembersId() ?: return null
        val cleanMemberId = memberId.normalizedServerMembersId() ?: return null
        val channelHandle =
            mentionChannelId
                .normalizedServerMembersId()
                ?.let { channelId ->
                    channelMemberSlices[
                        SharedServerChannelMembersScope(
                            serverId = cleanServerId,
                            channelId = channelId
                        )
                    ]
                }
                ?.members
                ?.asSequence()
                ?.filter { member -> member.id.value.trim() == cleanMemberId }
                ?.mapNotNull { member -> member.handle.canonicalServerMembersMentionHandle(member.id.value) }
                ?.firstOrNull()
        if (channelHandle != null) return channelHandle
        return entriesByServerId[cleanServerId]
            ?.members
            ?.asSequence()
            ?.filter { member -> member.id.value.trim() == cleanMemberId }
            ?.mapNotNull { member -> member.handle.canonicalServerMembersMentionHandle(member.id.value) }
            ?.firstOrNull()
    }
}

data class SharedServerMemberStatus(
    val status: AgentStatus?,
    val presence: Presence,
    val statusLabel: String? = null
)

private fun SharedServerMembersState.channelMemberProfile(
    serverId: ServerId,
    memberId: String
): MemberProfile? =
    channelMemberSlices.values
        .asSequence()
        .filter { it.serverId?.trim() == serverId.value }
        .flatMap { it.members.asSequence() }
        .firstOrNull { it.id.value.trim() == memberId }

private fun SharedServerMembersState.scopedMemberProfile(
    serverId: ServerId,
    memberId: String
): MemberProfile? =
    entryFor(serverId)
        ?.members
        ?.firstOrNull { it.id.value.trim() == memberId }
        ?: channelMemberProfile(serverId, memberId)

private fun MemberProfile?.toSharedServerMemberStatus(
    presenceFact: MemberPresenceUpdated?
): SharedServerMemberStatus? {
    if (
        presenceFact != null &&
        (
            presenceFact.kind == null ||
                presenceFact.kind == MemberKind.Agent ||
                this?.kind == MemberKind.Agent
        )
    ) {
        return SharedServerMemberStatus(
            status = presenceFact.agentStatus ?: this?.agentStatus,
            presence = presenceFact.presence,
            statusLabel = presenceFact.statusLabel
        )
    }
    if (this?.kind != MemberKind.Agent) return null
    return SharedServerMemberStatus(
        status = agentStatus,
        presence = presence
    )
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
    private val deletedAgentIdsByServerId = mutableMapOf<String, Set<String>>()
    private val _state = MutableStateFlow(SharedServerMembersState())
    val state: StateFlow<SharedServerMembersState> = _state.asStateFlow()

    fun serverMembersEntryFlow(serverId: ServerId): Flow<SharedServerMembersEntry> =
        state
            .map { current ->
                current.entryFor(serverId)
                    ?: SharedServerMembersEntry(serverId = serverId.value.trim())
            }
            .distinctUntilChanged()

    fun currentServerMembers(serverId: ServerId): List<MemberProfile> =
        _state.value.entryFor(serverId)?.members.orEmpty()

    fun currentServerMemberCount(serverId: ServerId): Int =
        _state.value.entryFor(serverId)?.members.orEmpty().size

    fun currentServerMemberProfilesById(serverId: ServerId): Map<String, MemberProfile> =
        currentServerMembers(serverId)
            .filter { it.id.value.trim().isNotBlank() }
            .associateBy { it.id.value.trim() }

    fun currentChannelMembers(
        serverId: ServerId,
        channelId: ChannelId
    ): List<MemberProfile> =
        _state.value.channelMemberSlice(serverId, channelId).members

    fun currentCanonicalMentionHandle(
        serverId: ServerId,
        mentionChannelId: String?,
        memberId: String?
    ): String? =
        _state.value.canonicalMentionHandle(
            serverId = serverId,
            mentionChannelId = mentionChannelId,
            memberId = memberId
        )

    fun currentServerMembersContent(
        serverId: ServerId,
        fallbackServer: ServerSummary? = null
    ): MemberSnapshot? {
        val entry = _state.value.entryFor(serverId)
        val server = entry?.server ?: fallbackServer ?: return null
        return MemberSnapshot(server = server, members = entry?.members.orEmpty())
    }

    fun memberSuggestionSliceFlow(
        serverId: ServerId,
        mentionChannelId: String? = null,
        memberScopedMentions: Boolean = false
    ): Flow<SharedMembersMemberSuggestionSlice> =
        state
            .map { current ->
                current.memberSuggestionSlice(
                    serverId = serverId,
                    mentionChannelId = mentionChannelId,
                    memberScopedMentions = memberScopedMentions
                )
            }
            .distinctUntilChanged()

    fun channelMemberSliceFlow(
        serverId: ServerId,
        channelId: ChannelId
    ): Flow<SharedServerChannelMembersSlice> =
        state
            .map { current ->
                current.channelMemberSlice(
                    serverId = serverId,
                    channelId = channelId
                )
            }
            .distinctUntilChanged()

    fun memberStatusFlow(
        serverId: ServerId?,
        memberId: String?
    ): Flow<SharedServerMemberStatus?> {
        val cleanServerId = serverId?.value.normalizedServerMembersId()
        val cleanMemberId = memberId.normalizedServerMembersId()
        if (cleanServerId == null || cleanMemberId == null) {
            return state
                .map { current -> current.memberStatus(serverId, memberId) }
                .distinctUntilChanged()
        }
        val scopedServerId = ServerId(cleanServerId)
        return combine(
            scopedMemberProfileFlow(scopedServerId, cleanMemberId),
            state
                .map { current -> current.presenceFor(cleanMemberId) }
                .distinctUntilChanged()
        ) { member, presenceFact ->
            member.toSharedServerMemberStatus(presenceFact)
        }.distinctUntilChanged()
    }

    fun memberProfileFlow(
        serverId: ServerId?,
        memberId: String?
    ): Flow<MemberProfile?> {
        val cleanServerId = serverId?.value.normalizedServerMembersId()
        val cleanMemberId = memberId.normalizedServerMembersId()
        if (cleanServerId == null || cleanMemberId == null) {
            return state
                .map { current -> current.memberProfile(serverId, memberId) }
                .distinctUntilChanged()
        }
        return scopedMemberProfileFlow(ServerId(cleanServerId), cleanMemberId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun scopedMemberProfileFlow(
        serverId: ServerId,
        memberId: String
    ): Flow<MemberProfile?> =
        flow {
            // `flatMapLatest` starts its inner collection asynchronously. Emit the
            // current cached profile first so a freshly opened Members row never
            // waits a frame (or loses its initial value) before the scoped flow is
            // attached.
            emit(_state.value.scopedMemberProfile(serverId, memberId))
            emitAll(
                serverMembersEntryFlow(serverId)
                    .flatMapLatest { entry ->
                        val rosterMember = entry.members.firstOrNull { it.id.value.trim() == memberId }
                        if (rosterMember != null) {
                            flowOf(rosterMember)
                        } else {
                            // A channel-local slice can arrive before the full server roster.
                            // Keep that fallback until the roster contains this member, while
                            // keeping normal Members rows off the whole-state hot path.
                            state
                                .map { current -> current.channelMemberProfile(serverId, memberId) }
                                .distinctUntilChanged()
                        }
                    }
            )
        }.distinctUntilChanged()

    fun seed(snapshot: MemberSnapshot) {
        val key = snapshot.server.id.value.trim()
        if (key.isBlank()) return
        val presenceFacts = _state.value.presenceByMemberId
        putEntry(
            key,
            SharedServerMembersEntry(
                serverId = key,
                server = snapshot.server,
                members =
                    snapshot.members
                        .filterDeletedAgents(key)
                        .withServerMemberPresenceFacts(presenceFacts),
                status = SharedServerMembersLoadStatus.Loaded,
                errorCode = null
            )
        )
    }

    fun seedProfiles(
        serverId: ServerId,
        profiles: List<MemberProfile>,
        server: ServerSummary? = null
    ) {
        val key = serverId.value.trim()
        if (key.isBlank() || profiles.isEmpty()) return
        val current = _state.value
        val previous = current.entriesByServerId[key]
        val merged = LinkedHashMap<String, MemberProfile>()
        previous?.members.orEmpty().forEach { member ->
            merged[member.id.value.trim()] = member
        }
        profiles
            .filter { it.id.value.trim().isNotBlank() }
            .filterNot { it.isDeletedAgentProfile(key) }
            .forEach { profile ->
                val profileKey = profile.id.value.trim()
                merged[profileKey] = merged[profileKey]?.mergeSeedProfile(profile) ?: profile
            }
        putEntry(
            key,
            (previous ?: SharedServerMembersEntry(serverId = key)).copy(
                server = previous?.server ?: server,
                members = merged.values.toList().withServerMemberPresenceFacts(current.presenceByMemberId)
            )
        )
    }

    fun removeMember(
        serverId: ServerId,
        memberId: MemberId,
        memberKind: MemberKind? = null
    ): Boolean {
        val serverKey = serverId.value.trim()
        val memberKey = memberId.value.trim()
        if (serverKey.isBlank() || memberKey.isBlank()) return false
        val current = _state.value
        var changed = false
        val nextEntry = current.entriesByServerId[serverKey]?.let { entry ->
            val nextMembers = entry.members.removeServerMember(memberKey, memberKind)
            if (nextMembers.size != entry.members.size) {
                changed = true
                entry.copy(members = nextMembers)
            } else {
                entry
            }
        }
        val nextEntries =
            if (nextEntry != null) {
                current.entriesByServerId + (serverKey to nextEntry)
            } else {
                current.entriesByServerId
            }
        val nextChannelSlices =
            current.channelMemberSlices.mapValues { (scope, slice) ->
                if (scope.serverId != serverKey && slice.serverId?.trim() != serverKey) {
                    slice
                } else {
                    val nextMembers = slice.members.removeServerMember(memberKey, memberKind)
                    if (nextMembers.size != slice.members.size) {
                        changed = true
                        slice.copy(members = nextMembers)
                    } else {
                        slice
                    }
                }
            }
        val nextSuggestionSlices =
            current.memberSuggestionSlices.mapValues { (scope, slice) ->
                if (scope.serverId != serverKey) {
                    slice
                } else {
                    val nextSuggestions =
                        slice.suggestions.removeOrMarkMemberSuggestionOutsideMentionChannel(
                            memberId = memberKey,
                            memberKind = memberKind,
                            remove = true
                        )
                    if (nextSuggestions.size != slice.suggestions.size) {
                        changed = true
                        slice.copy(suggestions = nextSuggestions)
                    } else {
                        slice
                    }
                }
            }
        val nextPresenceFacts =
            if (current.presenceByMemberId.containsKey(memberKey)) {
                changed = true
                current.presenceByMemberId - memberKey
            } else {
                current.presenceByMemberId
            }
        if (!changed) return false
        _state.value =
            current.copy(
                entriesByServerId = nextEntries,
                presenceByMemberId = nextPresenceFacts,
                memberSuggestionSlices = nextSuggestionSlices,
                channelMemberSlices = nextChannelSlices
            )
        return true
    }

    fun markAgentDeleted(
        serverId: ServerId,
        agentId: MemberId
    ): Boolean {
        val serverKey = serverId.value.trim()
        val agentKey = agentId.value.trim()
        if (serverKey.isBlank() || agentKey.isBlank()) return false
        val currentDeleted = deletedAgentIdsByServerId[serverKey].orEmpty()
        deletedAgentIdsByServerId[serverKey] = currentDeleted + agentKey
        return removeMember(serverId = ServerId(serverKey), memberId = MemberId(agentKey), memberKind = MemberKind.Agent)
    }

    suspend fun deleteAgent(
        serverId: ServerId,
        agentId: MemberId,
        priority: DataRequestPriority = DataRequestPriority.Foreground
    ): DataResult<AgentLifecycleActionResult> {
        val result =
            try {
                memberRepository.deleteAgent(priority, agentId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logger("shared_server_members_delete_agent_result agentId=${agentId.value} result=exception type=${throwable::class.simpleName}")
                return DataResult.Error(ScreenError(code = "agent_delete_exception", retryable = true))
            }
        if (result is DataResult.Content && result.data.success) {
            markAgentDeleted(serverId = serverId, agentId = agentId)
        }
        return result
    }

    suspend fun updateAgentProfile(
        serverId: ServerId,
        request: AgentProfileUpdateRequest,
        priority: DataRequestPriority = DataRequestPriority.Foreground
    ): DataResult<MemberProfile> {
        val result =
            try {
                memberRepository.updateAgentProfile(priority, request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logger("shared_server_members_update_agent_profile_result agentId=${request.agentId.value} result=exception type=${throwable::class.simpleName}")
                return DataResult.Error(ScreenError(code = "agent_profile_update_exception", retryable = true))
            }
        if (result is DataResult.Content) {
            seedProfiles(serverId, listOf(result.data))
        }
        return result
    }

    suspend fun uploadAgentAvatar(
        serverId: ServerId,
        request: AgentAvatarUploadRequest,
        priority: DataRequestPriority = DataRequestPriority.Foreground
    ): DataResult<MemberProfile> {
        val result =
            try {
                memberRepository.uploadAgentAvatar(priority, request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logger("shared_server_members_upload_agent_avatar_result agentId=${request.agentId.value} result=exception type=${throwable::class.simpleName}")
                return DataResult.Error(ScreenError(code = "agent_avatar_upload_exception", retryable = true))
            }
        if (result is DataResult.Content) {
            seedProfiles(serverId, listOf(result.data))
        }
        return result
    }

    suspend fun addChannelMember(
        serverId: ServerId,
        request: AddChannelMemberRequest,
        priority: DataRequestPriority = DataRequestPriority.Foreground
    ): DataResult<ChannelMemberActionResult> {
        val serverKey = serverId.value.trim()
        val channelKey = request.channelId.value.trim()
        val result =
            try {
                memberRepository.addChannelMember(priority, request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logger(
                    "shared_server_channel_member_add_result serverId=$serverKey channelId=$channelKey " +
                        "memberId=${request.memberId.value} result=exception type=${throwable::class.simpleName}"
                )
                return DataResult.Error(ScreenError(code = "channel_member_add_exception", retryable = true))
            }
        if (result is DataResult.Content) {
            val member =
                result.data.member
                    ?: _state.value.memberProfile(serverId, request.memberId.value)
            if (member != null) {
                patchChannelMemberSlice(serverKey, channelKey, member, inMentionChannel = true)
                patchMentionChannelMemberSuggestionSlices(serverKey, channelKey, member, inMentionChannel = true)
            }
        }
        return result
    }

    suspend fun removeChannelMember(
        serverId: ServerId,
        request: RemoveChannelMemberRequest,
        priority: DataRequestPriority = DataRequestPriority.Foreground
    ): DataResult<ChannelMemberActionResult> {
        val serverKey = serverId.value.trim()
        val channelKey = request.channelId.value.trim()
        val result =
            try {
                memberRepository.removeChannelMember(priority, request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logger(
                    "shared_server_channel_member_remove_result serverId=$serverKey channelId=$channelKey " +
                        "memberId=${request.memberId.value} result=exception type=${throwable::class.simpleName}"
                )
                return DataResult.Error(ScreenError(code = "channel_member_remove_exception", retryable = true))
            }
        if (result is DataResult.Content) {
            patchChannelMemberSlice(
                serverKey = serverKey,
                channelKey = channelKey,
                memberId = request.memberId.value,
                memberKind = request.kind,
                inMentionChannel = false
            )
            removeMentionChannelMemberSuggestionSlices(
                serverKey = serverKey,
                channelKey = channelKey,
                memberId = request.memberId.value,
                memberKind = request.kind
            )
        }
        return result
    }

    fun applyPresence(event: MemberPresenceUpdated) {
        val memberId = event.memberId.value.trim()
        if (memberId.isBlank()) return
        val current = _state.value
        val previousFact = current.presenceByMemberId[memberId]
        if (!event.shouldReplacePresenceFact(previousFact)) return
        if (previousFact != null && event.hasSameVisiblePresenceAs(previousFact)) return
        val nextPresenceFacts =
            current.presenceByMemberId + (memberId to event)
        _state.value =
            current.copy(
                presenceByMemberId = nextPresenceFacts
            )
    }

    fun applyAgentActivitySnapshot(agentId: MemberId, activity: List<AgentActivityEntry>) {
        val projection = activity.latestAgentActivityStatusProjection() ?: return
        applyAgentActivityProjection(agentId, projection)
    }

    fun applyAgentDetailSnapshot(detail: AgentDetailSnapshot) {
        val projection =
            detail.activity.latestAgentActivityStatusProjection()
                ?: detail.summary.agentActivityStatusFallbackProjection()
                ?: return
        applyAgentActivityProjection(detail.agentId, projection)
    }

    private fun applyAgentActivityProjection(
        agentId: MemberId,
        projection: AgentActivityStatusProjection
    ) {
        val memberId = agentId.value.trim()
        if (memberId.isBlank()) return
        val current = _state.value
        val previousFact = current.presenceByMemberId[memberId]
        if (previousFact?.serverSeq != null) return
        val event = projection.toMemberPresenceUpdated(agentId)
        if (!event.shouldReplacePresenceFact(previousFact)) return
        if (previousFact != null && event.hasSameVisiblePresenceAs(previousFact)) return
        _state.value =
            current.copy(
                presenceByMemberId = current.presenceByMemberId + (memberId to event)
            )
    }

    suspend fun refreshMembers(
        serverId: ServerId,
        priority: DataRequestPriority = DataRequestPriority.Visible,
        force: Boolean = false
    ): DataResult<Unit> {
        return loadMembersIntoStore(serverId, priority, force)
    }

    private suspend fun loadMembersIntoStore(
        serverId: ServerId,
        priority: DataRequestPriority,
        force: Boolean
    ): DataResult<Unit> {
        val startedAt = TimeSource.Monotonic.markNow()
        val key = serverId.value.trim()
        if (key.isBlank()) {
            logger(
                "store_load domain=server_members serverId= result=error code=server_missing " +
                    "force=$force priority=${priority.name} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return DataResult.Error(ScreenError(code = "server_missing", retryable = false))
        }
        cachedLoadedSnapshot(key, force)?.let {
            logger(
                "store_load domain=server_members serverId=$key result=cache_hit members=${it.members.size} " +
                    "force=$force priority=${priority.name} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return DataResult.Content(Unit)
        }
        return mutex.withLock {
            cachedLoadedSnapshot(key, force)?.let {
                logger(
                    "store_load domain=server_members serverId=$key result=cache_hit_locked members=${it.members.size} " +
                        "force=$force priority=${priority.name} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
                return@withLock DataResult.Content(Unit)
            }
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
                    logger(
                        "store_load domain=server_members serverId=$key result=exception code=members_exception " +
                            "force=$force priority=${priority.name} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                    )
                    val error = DataResult.Error(ScreenError(code = "members_exception", retryable = true))
                    markFailure(key, previous, error.error.code)
                    return@withLock error
                }
            when (result) {
                is DataResult.Content -> {
                    val normalizedKey = result.data.server.id.value.trim().ifBlank { key }
                    val snapshot =
                        result.data.copy(
                            members =
                                result.data.members
                                    .filterDeletedAgents(normalizedKey)
                                    .withServerMemberPresenceFacts(_state.value.presenceByMemberId)
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
                    logger(
                        "store_load domain=server_members serverId=$normalizedKey result=content members=${snapshot.members.size} " +
                            "force=$force priority=${priority.name} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                    )
                    return@withLock DataResult.Content(Unit)
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
                    logger(
                        "store_load domain=server_members serverId=$key result=empty code=${result.reason.name} " +
                            "force=$force priority=${priority.name} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                    )
                }
                is DataResult.Error -> {
                    markFailure(key, previous, result.error.code)
                    logger("shared_server_members_load_result serverId=$key result=error code=${result.error.code}")
                    logger(
                        "store_load domain=server_members serverId=$key result=error code=${result.error.code} " +
                            "force=$force priority=${priority.name} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                    )
                }
                is DataResult.NoPermission -> {
                    markFailure(key, previous, result.blocker.code)
                    logger("shared_server_members_load_result serverId=$key result=no_permission code=${result.blocker.code}")
                    logger(
                        "store_load domain=server_members serverId=$key result=no_permission code=${result.blocker.code} " +
                            "force=$force priority=${priority.name} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                    )
                }
            }
            result.map { Unit }
        }
    }

    suspend fun loadChannelMembers(
        serverId: ServerId,
        channelId: ChannelId,
        priority: DataRequestPriority = DataRequestPriority.Visible,
        force: Boolean = false
    ): DataResult<ChannelMemberSnapshot> {
        val serverKey = serverId.value.trim()
        val channelKey = channelId.value.trim()
        if (serverKey.isBlank() || channelKey.isBlank()) {
            return DataResult.Error(ScreenError(code = "channel_members_scope_missing", retryable = false))
        }
        val scope = SharedServerChannelMembersScope(serverId = serverKey, channelId = channelKey)
        cachedLoadedChannelSnapshot(scope, force)?.let { return DataResult.Content(it) }
        return mutex.withLock {
            cachedLoadedChannelSnapshot(scope, force)?.let { return@withLock DataResult.Content(it) }
            val previous = _state.value.channelMemberSlices[scope]
            putChannelSlice(
                scope,
                (previous ?: SharedServerChannelMembersSlice(serverId = serverKey, channelId = channelKey)).copy(
                    status = SharedServerMembersLoadStatus.InFlight,
                    errorCode = null
                )
            )
            val result =
                try {
                    memberRepository.getChannelMembers(priority, ChannelId(channelKey))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    logger(
                        "shared_server_channel_members_load_result serverId=$serverKey " +
                            "channelId=$channelKey result=exception type=${throwable::class.simpleName}"
                    )
                    val error = DataResult.Error(ScreenError(code = "channel_members_exception", retryable = true))
                    markChannelFailure(scope, previous, error.error.code)
                    return@withLock error
                }
            when (result) {
                is DataResult.Content -> {
                    val normalizedChannelId = result.data.channelId.value.trim().ifBlank { channelKey }
                    val normalizedScope =
                        SharedServerChannelMembersScope(
                            serverId = serverKey,
                            channelId = normalizedChannelId
                        )
                    val members =
                        result.data.members
                            .distinctBy { "${it.kind.name}:${it.id.value.trim()}" }
                            .filterDeletedAgents(serverKey)
                            .withServerMemberPresenceFacts(_state.value.presenceByMemberId)
                    putChannelSlice(
                        normalizedScope,
                        SharedServerChannelMembersSlice(
                            serverId = serverKey,
                            channelId = normalizedChannelId,
                            members = members,
                            canAddMembers = result.data.canAddMembers,
                            status = SharedServerMembersLoadStatus.Loaded,
                            errorCode = null
                        )
                    )
                    if (normalizedScope != scope) removeChannelSlice(scope)
                    seedProfiles(ServerId(serverKey), members)
                    logger(
                        "shared_server_channel_members_load_result serverId=$serverKey " +
                            "channelId=$normalizedChannelId result=content members=${members.size}"
                    )
                    return@withLock DataResult.Content(
                        ChannelMemberSnapshot(
                            channelId = ChannelId(normalizedChannelId),
                            members = members,
                            canAddMembers = result.data.canAddMembers
                        )
                    )
                }
                is DataResult.Empty -> {
                    putChannelSlice(
                        scope,
                        SharedServerChannelMembersSlice(
                            serverId = serverKey,
                            channelId = channelKey,
                            members = emptyList(),
                            canAddMembers = previous?.canAddMembers ?: true,
                            status = SharedServerMembersLoadStatus.Loaded,
                            errorCode = null
                        )
                    )
                    logger("shared_server_channel_members_load_result serverId=$serverKey channelId=$channelKey result=empty")
                }
                is DataResult.Error -> {
                    markChannelFailure(scope, previous, result.error.code)
                    logger(
                        "shared_server_channel_members_load_result serverId=$serverKey " +
                            "channelId=$channelKey result=error code=${result.error.code}"
                    )
                }
                is DataResult.NoPermission -> {
                    markChannelFailure(scope, previous, result.blocker.code)
                    logger(
                        "shared_server_channel_members_load_result serverId=$serverKey " +
                            "channelId=$channelKey result=no_permission code=${result.blocker.code}"
                    )
                }
            }
            result
        }
    }

    suspend fun ensureMemberSuggestionsForServer(
        serverId: ServerId,
        mentionChannelId: String? = null,
        memberScopedMentions: Boolean = false,
        force: Boolean = false
    ) {
        val key = serverId.value.trim()
        if (key.isBlank()) return
        val normalizedMentionChannelId = mentionChannelId.normalizedServerMembersId()
        val suggestionScope =
            SharedMembersMentionScope(
                serverId = key,
                mentionChannelId = normalizedMentionChannelId,
                memberScopedMentions = memberScopedMentions
            )
        val currentSlice = _state.value.memberSuggestionSlices[suggestionScope] ?: SharedMembersMemberSuggestionSlice()
        if (
            !force &&
            (currentSlice.status == SharedMembersLoadStatus.Loaded ||
                currentSlice.status == SharedMembersLoadStatus.InFlight)
        ) {
            logger("shared_server_members_suggestions_skip serverId=$key reason=loaded_or_inflight")
            return
        }
        setMemberSuggestionSlice(
            scope = suggestionScope,
            slice = currentSlice.copy(status = SharedMembersLoadStatus.InFlight, errorCode = null)
        )
        if (memberScopedMentions) {
            loadScopedMemberSuggestions(
                serverId = key,
                mentionChannelId = normalizedMentionChannelId,
                suggestionScope = suggestionScope,
                preserveExistingOnEmpty = force
            )
        } else {
            loadMemberSuggestions(
                serverId = key,
                mentionChannelId = normalizedMentionChannelId,
                suggestionScope = suggestionScope,
                preserveExistingOnEmpty = force
            )
        }
    }

    fun patchMentionChannelMemberSuggestion(
        serverId: ServerId,
        mentionChannelId: String?,
        memberScopedMentions: Boolean,
        member: MemberProfile,
        inMentionChannel: Boolean
    ): Boolean {
        val serverKey = serverId.value.trim()
        val channelKey = mentionChannelId.normalizedServerMembersId()
        val memberKey = member.id.value.trim()
        if (serverKey.isBlank() || memberKey.isBlank()) return false
        val scope =
            SharedMembersMentionScope(
                serverId = serverKey,
                mentionChannelId = channelKey,
                memberScopedMentions = memberScopedMentions
            )
        val current = _state.value.memberSuggestionSlices[scope] ?: return false
        val suggestion =
            listOf(member)
                .filterDeletedAgents(serverKey)
                .toServerMembersMemberSuggestions(localServerId = serverKey, inMentionChannel = inMentionChannel)
                .firstOrNull()
                ?: return false
        val nextSuggestions =
            current.suggestions.patchMemberSuggestion(
                suggestion = suggestion,
                memberScopedMentions = memberScopedMentions,
                inMentionChannel = inMentionChannel
            )
        setMemberSuggestionSlice(
            scope = scope,
            slice =
                current.copy(
                    suggestions = nextSuggestions,
                    status = SharedMembersLoadStatus.Loaded,
                    errorCode = null
                )
        )
        patchChannelMemberSlice(serverKey, channelKey, member, inMentionChannel)
        seedProfiles(ServerId(serverKey), listOf(member))
        return true
    }

    fun removeMentionChannelMemberSuggestion(
        serverId: ServerId,
        mentionChannelId: String?,
        memberScopedMentions: Boolean,
        memberId: String,
        memberKind: MemberKind? = null
    ): Boolean {
        val serverKey = serverId.value.trim()
        val channelKey = mentionChannelId.normalizedServerMembersId()
        val memberKey = memberId.trim()
        if (serverKey.isBlank() || memberKey.isBlank()) return false
        val scope =
            SharedMembersMentionScope(
                serverId = serverKey,
                mentionChannelId = channelKey,
                memberScopedMentions = memberScopedMentions
            )
        val current = _state.value.memberSuggestionSlices[scope] ?: return false
        val nextSuggestions =
            current.suggestions.removeOrMarkMemberSuggestionOutsideMentionChannel(
                memberId = memberKey,
                memberKind = memberKind,
                remove = memberScopedMentions || channelKey == null
            )
        setMemberSuggestionSlice(
            scope = scope,
            slice =
                current.copy(
                    suggestions = nextSuggestions,
                    status = SharedMembersLoadStatus.Loaded,
                    errorCode = null
                )
        )
        patchChannelMemberSlice(serverKey, channelKey, memberId = memberKey, memberKind = memberKind, inMentionChannel = false)
        return true
    }

    private fun cachedLoadedSnapshot(key: String, force: Boolean): MemberSnapshot? {
        if (force) return null
        val entry = _state.value.entriesByServerId[key] ?: return null
        if (entry.status != SharedServerMembersLoadStatus.Loaded) return null
        return entry.snapshotOrNull()
    }

    private fun cachedLoadedChannelSnapshot(
        scope: SharedServerChannelMembersScope,
        force: Boolean
    ): ChannelMemberSnapshot? {
        if (force) return null
        val slice = _state.value.channelMemberSlices[scope] ?: return null
        if (slice.status != SharedServerMembersLoadStatus.Loaded) return null
        return slice.snapshotOrNull()
    }

    private suspend fun loadMemberSuggestions(
        serverId: String,
        mentionChannelId: String?,
        suggestionScope: SharedMembersMentionScope,
        preserveExistingOnEmpty: Boolean
    ) {
        val result =
            runCatchingPreservingCancellation {
                refreshMembers(ServerId(serverId), DataRequestPriority.Visible)
            }.getOrElse { throwable ->
                logger("shared_server_members_suggestions_members_result serverId=$serverId result=exception message=${throwable.message.orEmpty()}")
                setMemberSuggestionFailure(suggestionScope, "members_exception")
                return
            }
        when (result) {
            is DataResult.Content -> {
                val serverMembers = currentServerMembers(ServerId(serverId))
                val channelMembers =
                    mentionChannelId?.let { channelId ->
                        loadChannelMembersBestEffort(
                            serverId = serverId,
                            mentionChannelId = channelId
                        )
                    }.orEmpty()
                val suggestions =
                    if (mentionChannelId == null) {
                        serverMembers.toServerMembersMemberSuggestions()
                    } else {
                        serverMembers.toServerMembersMemberSuggestions(
                            localServerId = serverId,
                            inMentionChannelIds = channelMembers.map { it.id.value }.toSet()
                        ).mergeServerMembersMentionChannelSuggestions(
                            channelMembers.toServerMembersMemberSuggestions(
                                localServerId = serverId,
                                inMentionChannel = true
                            )
                        )
                    }
                setMemberSuggestionSlice(
                    scope = suggestionScope,
                    slice = loadedMemberSuggestionSlice(suggestionScope, suggestions, preserveExistingOnEmpty)
                )
            }
            is DataResult.Empty -> {
                val channelMembers =
                    mentionChannelId?.let { channelId ->
                        loadChannelMembersBestEffort(
                            serverId = serverId,
                            mentionChannelId = channelId
                        )
                    }.orEmpty()
                setMemberSuggestionSlice(
                    scope = suggestionScope,
                    slice =
                        loadedMemberSuggestionSlice(
                            scope = suggestionScope,
                            suggestions = channelMembers.toServerMembersMemberSuggestions(localServerId = serverId),
                            preserveExistingOnEmpty = preserveExistingOnEmpty
                        )
                )
            }
            is DataResult.Error -> setMemberSuggestionFailure(suggestionScope, result.error.code)
            is DataResult.NoPermission -> setMemberSuggestionFailure(suggestionScope, result.blocker.code)
        }
    }

    private suspend fun loadScopedMemberSuggestions(
        serverId: String,
        mentionChannelId: String?,
        suggestionScope: SharedMembersMentionScope,
        preserveExistingOnEmpty: Boolean
    ) {
        if (mentionChannelId == null) {
            setMemberSuggestionSlice(
                scope = suggestionScope,
                slice = SharedMembersMemberSuggestionSlice(status = SharedMembersLoadStatus.Loaded)
            )
            return
        }
        val result =
            runCatchingPreservingCancellation {
                loadChannelMembers(
                    serverId = ServerId(serverId),
                    channelId = ChannelId(mentionChannelId),
                    priority = DataRequestPriority.Visible,
                    force = preserveExistingOnEmpty
                )
            }.getOrElse { throwable ->
                logger(
                    "shared_server_members_suggestions_channel_members_result serverId=$serverId " +
                        "channelId=$mentionChannelId result=exception message=${throwable.message.orEmpty()}"
                )
                setMemberSuggestionFailure(suggestionScope, "channel_members_exception")
                return
            }
        when (result) {
            is DataResult.Content ->
                setMemberSuggestionSlice(
                    scope = suggestionScope,
                    slice =
                        loadedMemberSuggestionSlice(
                            scope = suggestionScope,
                            suggestions = result.data.members.toServerMembersMemberSuggestions(localServerId = serverId),
                            preserveExistingOnEmpty = preserveExistingOnEmpty
                        )
                )
            is DataResult.Empty ->
                setMemberSuggestionSlice(
                    scope = suggestionScope,
                    slice =
                        loadedMemberSuggestionSlice(
                            scope = suggestionScope,
                            suggestions = emptyList(),
                            preserveExistingOnEmpty = preserveExistingOnEmpty
                        )
                )
            is DataResult.Error -> setMemberSuggestionFailure(suggestionScope, result.error.code)
            is DataResult.NoPermission -> setMemberSuggestionFailure(suggestionScope, result.blocker.code)
        }
    }

    private suspend fun loadChannelMembersBestEffort(
        serverId: String,
        mentionChannelId: String
    ): List<MemberProfile> {
        val result =
            runCatchingPreservingCancellation {
                loadChannelMembers(
                    serverId = ServerId(serverId),
                    channelId = ChannelId(mentionChannelId),
                    priority = DataRequestPriority.Visible
                )
            }.getOrElse { throwable ->
                logger(
                    "shared_server_members_suggestions_channel_members_result serverId=$serverId " +
                        "channelId=$mentionChannelId result=exception message=${throwable.message.orEmpty()}"
                )
                return emptyList()
            }
        return when (result) {
            is DataResult.Content -> result.data.members
            is DataResult.Empty -> emptyList()
            is DataResult.Error -> {
                logger(
                    "shared_server_members_suggestions_channel_members_result serverId=$serverId " +
                        "channelId=$mentionChannelId result=error code=${result.error.code}"
                )
                emptyList()
            }
            is DataResult.NoPermission -> {
                logger(
                    "shared_server_members_suggestions_channel_members_result serverId=$serverId " +
                        "channelId=$mentionChannelId result=no_permission code=${result.blocker.code}"
                )
                emptyList()
            }
        }
    }

    private suspend fun refreshMembersBestEffort(
        serverId: ServerId,
        reason: String
    ) {
        val serverKey = serverId.value.trim()
        if (serverKey.isBlank()) return
        val result =
            runCatchingPreservingCancellation {
                refreshMembers(serverId, DataRequestPriority.Background, force = true)
            }.getOrElse { throwable ->
                logger("shared_server_members_refresh_after_mutation reason=$reason serverId=$serverKey result=exception type=${throwable::class.simpleName}")
                return
            }
        when (result) {
            is DataResult.Content,
            is DataResult.Empty -> Unit
            is DataResult.Error ->
                logger("shared_server_members_refresh_after_mutation reason=$reason serverId=$serverKey result=error code=${result.error.code}")
            is DataResult.NoPermission ->
                logger("shared_server_members_refresh_after_mutation reason=$reason serverId=$serverKey result=no_permission code=${result.blocker.code}")
        }
    }

    private suspend fun refreshChannelMembersBestEffort(
        serverId: ServerId,
        channelId: ChannelId,
        reason: String
    ) {
        val serverKey = serverId.value.trim()
        val channelKey = channelId.value.trim()
        if (serverKey.isBlank() || channelKey.isBlank()) return
        val result =
            runCatchingPreservingCancellation {
                loadChannelMembers(serverId, channelId, DataRequestPriority.Background, force = true)
            }.getOrElse { throwable ->
                logger(
                    "shared_server_channel_members_refresh_after_mutation reason=$reason serverId=$serverKey " +
                        "channelId=$channelKey result=exception type=${throwable::class.simpleName}"
                )
                return
            }
        when (result) {
            is DataResult.Content,
            is DataResult.Empty -> Unit
            is DataResult.Error ->
                logger(
                    "shared_server_channel_members_refresh_after_mutation reason=$reason serverId=$serverKey " +
                        "channelId=$channelKey result=error code=${result.error.code}"
                )
            is DataResult.NoPermission ->
                logger(
                    "shared_server_channel_members_refresh_after_mutation reason=$reason serverId=$serverKey " +
                        "channelId=$channelKey result=no_permission code=${result.blocker.code}"
                )
        }
    }

    private fun loadedMemberSuggestionSlice(
        scope: SharedMembersMentionScope,
        suggestions: List<SharedMembersMemberSuggestion>,
        preserveExistingOnEmpty: Boolean
    ): SharedMembersMemberSuggestionSlice {
        val retainedSuggestions =
            suggestions.ifEmpty {
                if (preserveExistingOnEmpty) {
                    _state.value.memberSuggestionSlices[scope]?.suggestions.orEmpty()
                } else {
                    emptyList()
                }
            }
        return SharedMembersMemberSuggestionSlice(
            suggestions = retainedSuggestions,
            status = SharedMembersLoadStatus.Loaded,
            errorCode = null
        )
    }

    private fun patchMentionChannelMemberSuggestionSlices(
        serverKey: String,
        channelKey: String,
        member: MemberProfile,
        inMentionChannel: Boolean
    ) {
        val memberKey = member.id.value.trim()
        if (serverKey.isBlank() || channelKey.isBlank() || memberKey.isBlank()) return
        val suggestion =
            listOf(member)
                .filterDeletedAgents(serverKey)
                .toServerMembersMemberSuggestions(localServerId = serverKey, inMentionChannel = inMentionChannel)
                .firstOrNull()
                ?: return
        val current = _state.value
        var changed = false
        val nextSlices =
            current.memberSuggestionSlices.mapValues { (scope, slice) ->
                if (scope.serverId != serverKey || scope.mentionChannelId != channelKey) {
                    slice
                } else {
                    val nextSuggestions =
                        slice.suggestions.patchMemberSuggestion(
                            suggestion = suggestion,
                            memberScopedMentions = scope.memberScopedMentions,
                            inMentionChannel = inMentionChannel
                        )
                    if (nextSuggestions != slice.suggestions) {
                        changed = true
                        slice.copy(
                            suggestions = nextSuggestions,
                            status = SharedMembersLoadStatus.Loaded,
                            errorCode = null
                        )
                    } else {
                        slice
                    }
                }
            }
        if (changed) {
            _state.value = current.copy(memberSuggestionSlices = nextSlices)
        }
    }

    private fun removeMentionChannelMemberSuggestionSlices(
        serverKey: String,
        channelKey: String,
        memberId: String,
        memberKind: MemberKind?
    ) {
        val memberKey = memberId.trim()
        if (serverKey.isBlank() || channelKey.isBlank() || memberKey.isBlank()) return
        val current = _state.value
        var changed = false
        val nextSlices =
            current.memberSuggestionSlices.mapValues { (scope, slice) ->
                if (scope.serverId != serverKey || scope.mentionChannelId != channelKey) {
                    slice
                } else {
                    val nextSuggestions =
                        slice.suggestions.removeOrMarkMemberSuggestionOutsideMentionChannel(
                            memberId = memberKey,
                            memberKind = memberKind,
                            remove = scope.memberScopedMentions
                        )
                    if (nextSuggestions != slice.suggestions) {
                        changed = true
                        slice.copy(
                            suggestions = nextSuggestions,
                            status = SharedMembersLoadStatus.Loaded,
                            errorCode = null
                        )
                    } else {
                        slice
                    }
                }
            }
        if (changed) {
            _state.value = current.copy(memberSuggestionSlices = nextSlices)
        }
    }

    private fun setMemberSuggestionFailure(
        scope: SharedMembersMentionScope,
        errorCode: String?
    ) {
        val currentSlice = _state.value.memberSuggestionSlices[scope] ?: SharedMembersMemberSuggestionSlice()
        setMemberSuggestionSlice(
            scope = scope,
            slice =
                currentSlice.copy(
                    status = SharedMembersLoadStatus.Error,
                    errorCode = errorCode
                )
        )
    }

    private fun setMemberSuggestionSlice(
        scope: SharedMembersMentionScope,
        slice: SharedMembersMemberSuggestionSlice
    ) {
        _state.value =
            _state.value.copy(
                memberSuggestionSlices = _state.value.memberSuggestionSlices + (scope to slice)
            )
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

    private fun markChannelFailure(
        scope: SharedServerChannelMembersScope,
        previous: SharedServerChannelMembersSlice?,
        code: String
    ) {
        putChannelSlice(
            scope,
            (previous ?: SharedServerChannelMembersSlice(serverId = scope.serverId, channelId = scope.channelId)).copy(
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

    private fun putChannelSlice(
        scope: SharedServerChannelMembersScope,
        slice: SharedServerChannelMembersSlice
    ) {
        _state.value =
            _state.value.copy(
                channelMemberSlices = _state.value.channelMemberSlices + (scope to slice)
            )
    }

    private fun removeEntry(key: String) {
        _state.value =
            _state.value.copy(
                entriesByServerId = _state.value.entriesByServerId - key
            )
    }

    private fun removeChannelSlice(scope: SharedServerChannelMembersScope) {
        _state.value =
            _state.value.copy(
                channelMemberSlices = _state.value.channelMemberSlices - scope
            )
    }

    private fun patchChannelMemberSlice(
        serverKey: String,
        channelKey: String?,
        member: MemberProfile,
        inMentionChannel: Boolean
    ) {
        val memberKey = member.id.value.trim()
        if (member.isDeletedAgentProfile(serverKey)) return
        if (channelKey == null || memberKey.isBlank()) return
        val scope = SharedServerChannelMembersScope(serverId = serverKey, channelId = channelKey)
        val current = _state.value.channelMemberSlices[scope] ?: return
        val nextMembers =
            if (inMentionChannel) {
                current.members.upsertServerMember(member.withServerMemberPresenceFacts(_state.value.presenceByMemberId))
            } else {
                current.members.removeServerMember(memberKey, member.kind)
            }
        putChannelSlice(
            scope,
            current.copy(
                members = nextMembers,
                status = SharedServerMembersLoadStatus.Loaded,
                errorCode = null
            )
        )
    }

    private fun patchChannelMemberSlice(
        serverKey: String,
        channelKey: String?,
        memberId: String,
        memberKind: MemberKind?,
        inMentionChannel: Boolean
    ) {
        if (channelKey == null || inMentionChannel) return
        val scope = SharedServerChannelMembersScope(serverId = serverKey, channelId = channelKey)
        val current = _state.value.channelMemberSlices[scope] ?: return
        putChannelSlice(
            scope,
            current.copy(
                members = current.members.removeServerMember(memberId, memberKind),
                status = SharedServerMembersLoadStatus.Loaded,
                errorCode = null
            )
        )
    }

    private fun List<MemberProfile>.filterDeletedAgents(serverKey: String): List<MemberProfile> {
        val deletedAgentIds = deletedAgentIdsByServerId[serverKey].orEmpty()
        if (deletedAgentIds.isEmpty()) return this
        return filterNot { it.kind == MemberKind.Agent && it.id.value.trim() in deletedAgentIds }
    }

    private fun MemberProfile.isDeletedAgentProfile(serverKey: String): Boolean =
        kind == MemberKind.Agent && id.value.trim() in deletedAgentIdsByServerId[serverKey].orEmpty()
}

private fun MemberPresenceUpdated.shouldReplacePresenceFact(previous: MemberPresenceUpdated?): Boolean =
    when {
        previous == null -> true
        serverSeq != null && previous.serverSeq != null -> serverSeq >= previous.serverSeq
        serverSeq != null && previous.serverSeq == null -> true
        else -> true
    }

private fun MemberPresenceUpdated.hasSameVisiblePresenceAs(previous: MemberPresenceUpdated): Boolean =
    presence == previous.presence &&
        agentStatus == previous.agentStatus &&
        statusLabel == previous.statusLabel &&
        kind == previous.kind

private fun String?.normalizedServerMembersId(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

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

private fun MemberProfile.withServerMemberPresenceFacts(
    presenceFacts: Map<String, MemberPresenceUpdated>
): MemberProfile {
    val event = presenceFacts[id.value.trim()]?.takeIf { kind == MemberKind.Agent } ?: return this
    return copy(
        presence = event.presence,
        agentStatus = event.agentStatus ?: agentStatus
    )
}

private fun List<MemberProfile>.upsertServerMember(member: MemberProfile): List<MemberProfile> {
    val key = member.id.value.trim()
    if (key.isBlank()) return this
    var replaced = false
    val next =
        map { existing ->
            if (existing.id.value.trim() == key && existing.kind == member.kind) {
                replaced = true
                existing.mergeSeedProfile(member)
            } else {
                existing
            }
        }
    return if (replaced) next else next + member
}

private fun List<MemberProfile>.removeServerMember(memberId: String, memberKind: MemberKind?): List<MemberProfile> {
    val key = memberId.trim()
    if (key.isBlank()) return this
    return filterNot { member ->
        member.id.value.trim() == key && (memberKind == null || member.kind == memberKind)
    }
}

private fun MemberProfile.mergeSeedProfile(seed: MemberProfile): MemberProfile {
    val agentProfile = kind == MemberKind.Agent || seed.kind == MemberKind.Agent
    return copy(
        displayName = displayName.takeIf { it.isNotBlank() } ?: seed.displayName,
        handle = handle.takeIf { it.isNotBlank() } ?: seed.handle,
        description = description ?: seed.description,
        roleDescription = roleDescription ?: seed.roleDescription,
        serverRole = serverRole ?: seed.serverRole,
        email = email ?: seed.email,
        avatarUrl = avatarUrl ?: seed.avatarUrl,
        avatar = avatar ?: seed.avatar,
        avatarLabel = avatarLabel.takeIf { it.isNotBlank() } ?: seed.avatarLabel,
        serverId = serverId ?: seed.serverId,
        serverName = serverName ?: seed.serverName,
        serverSlug = serverSlug ?: seed.serverSlug,
        presence = if (agentProfile) seed.presence else presence,
        agentStatus = if (agentProfile) seed.agentStatus ?: agentStatus else agentStatus
    )
}

private fun List<MemberProfile>.toServerMembersMemberSuggestions(
    localServerId: String? = null
): List<SharedMembersMemberSuggestion> =
    toServerMembersMemberSuggestions(localServerId = localServerId, inMentionChannel = true)

private fun List<MemberProfile>.toServerMembersMemberSuggestions(
    localServerId: String? = null,
    inMentionChannel: Boolean
): List<SharedMembersMemberSuggestion> =
    toServerMembersMemberSuggestions(
        localServerId = localServerId,
        inMentionChannelIds = null,
        fallbackInMentionChannel = inMentionChannel
    )

private fun List<MemberProfile>.toServerMembersMemberSuggestions(
    localServerId: String? = null,
    inMentionChannelIds: Set<String>?,
    fallbackInMentionChannel: Boolean = false
): List<SharedMembersMemberSuggestion> {
    val deduped = LinkedHashMap<String, SharedMembersMemberSuggestion>()
    for (member in this) {
        val handle = member.serverMembersSuggestionHandle() ?: continue
        val key = handle.lowercase()
        if (!deduped.containsKey(key)) {
            val avatar = member.avatarSlotResolution()
            deduped[key] =
                SharedMembersMemberSuggestion(
                    displayName = member.displayName.trim().ifBlank { handle },
                    handle = handle,
                    inMentionChannel = inMentionChannelIds?.contains(member.id.value) ?: fallbackInMentionChannel,
                    sourceServerLabel = member.serverMembersSourceServerLabel(localServerId),
                    kind = member.kind,
                    description = member.description?.trim()?.takeIf { it.isNotBlank() },
                    avatarWireValue = avatar.effectiveAvatarWireValue,
                    avatarLabel = member.avatarLabel.trim().takeIf { it.isNotBlank() }
                        ?: member.displayName.trim().take(1).uppercase().ifBlank { handle.take(1).uppercase() },
                    memberId = member.id.value
                )
        }
    }
    return deduped.values.toList()
}

private fun MemberProfile.serverMembersSuggestionHandle(): String? {
    return handle.canonicalServerMembersMentionHandle(id.value)
}

internal fun String?.canonicalServerMembersMentionHandle(memberId: String?): String? {
    val canonicalHandle = this?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() } ?: return null
    return canonicalHandle.takeUnless { it.isOpaqueServerMembersIdentityHandle(memberId.orEmpty()) }
}

private fun String.isOpaqueServerMembersIdentityHandle(memberId: String): Boolean {
    val value = trim().removePrefix("@")
    if (value.equals(memberId.trim().removePrefix("@"), ignoreCase = true)) return true
    if (value.length != 36) return false
    val dashPositions = setOf(8, 13, 18, 23)
    return value.withIndex().all { (index, char) ->
        if (index in dashPositions) {
            char == '-'
        } else {
            char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        }
    }
}

private fun List<SharedMembersMemberSuggestion>.mergeServerMembersMentionChannelSuggestions(
    channelSuggestions: List<SharedMembersMemberSuggestion>
): List<SharedMembersMemberSuggestion> {
    if (channelSuggestions.isEmpty()) return this
    val deduped = LinkedHashMap<String, SharedMembersMemberSuggestion>()
    for (suggestion in this + channelSuggestions) {
        val key = suggestion.memberSuggestionIdentityKey()
        val existing = deduped[key]
        deduped[key] =
            when {
                existing == null -> suggestion
                suggestion.inMentionChannel -> existing.mergePatchedSuggestion(suggestion)
                else -> existing
            }
    }
    return deduped.values.toList()
}

private fun SharedMembersMemberSuggestion.memberSuggestionIdentityKey(): String =
    memberId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { "${kind.name}:$it" }
        ?: "${kind.name}:${handle.trim().lowercase()}"

private fun List<SharedMembersMemberSuggestion>.patchMemberSuggestion(
    suggestion: SharedMembersMemberSuggestion,
    memberScopedMentions: Boolean,
    inMentionChannel: Boolean
): List<SharedMembersMemberSuggestion> {
    if (memberScopedMentions && !inMentionChannel) {
        return removeOrMarkMemberSuggestionOutsideMentionChannel(
            memberId = suggestion.memberId.orEmpty(),
            memberKind = suggestion.kind,
            remove = true
        )
    }
    val key = suggestion.memberSuggestionPatchKey()
    if (key.isBlank()) return this
    var replaced = false
    val next =
        map { existing ->
            if (existing.memberSuggestionPatchKey() == key) {
                replaced = true
                existing.mergePatchedSuggestion(suggestion)
            } else {
                existing
            }
        }
    return if (replaced) next else next + suggestion
}

private fun List<SharedMembersMemberSuggestion>.removeOrMarkMemberSuggestionOutsideMentionChannel(
    memberId: String,
    memberKind: MemberKind?,
    remove: Boolean
): List<SharedMembersMemberSuggestion> {
    val key = memberId.trim()
    if (key.isBlank()) return this
    return mapNotNull { suggestion ->
        val matches =
            suggestion.memberId?.trim() == key &&
                (memberKind == null || suggestion.kind == memberKind)
        when {
            !matches -> suggestion
            remove -> null
            else -> suggestion.copy(inMentionChannel = false)
        }
    }
}

private fun SharedMembersMemberSuggestion.memberSuggestionPatchKey(): String =
    memberId?.trim()?.takeIf { it.isNotBlank() }
        ?: handle.trim().lowercase().takeIf { it.isNotBlank() }
        ?: displayName.trim().lowercase()

private fun SharedMembersMemberSuggestion.mergePatchedSuggestion(
    patch: SharedMembersMemberSuggestion
): SharedMembersMemberSuggestion =
    copy(
        displayName = patch.displayName.ifBlank { displayName },
        handle = patch.handle.ifBlank { handle },
        inMentionChannel = patch.inMentionChannel,
        sourceServerLabel = sourceServerLabel ?: patch.sourceServerLabel,
        kind = patch.kind,
        description = description ?: patch.description,
        avatarWireValue = avatarWireValue.preferServerMembersConcreteSuggestionAvatar(patch.avatarWireValue),
        avatarLabel = avatarLabel.ifBlank { patch.avatarLabel },
        memberId = memberId ?: patch.memberId
    )

private fun String?.preferServerMembersConcreteSuggestionAvatar(candidate: String?): String? =
    when {
        isNullOrBlank() -> candidate
        isServerMembersAgentFallbackSuggestionAvatar() && !candidate.isNullOrBlank() &&
            !candidate.isServerMembersAgentFallbackSuggestionAvatar() -> candidate
        else -> this
    }

private fun String?.isServerMembersAgentFallbackSuggestionAvatar(): Boolean =
    this?.equals("pixel:robot", ignoreCase = true) == true ||
        this?.startsWith("random:", ignoreCase = true) == true

private fun MemberProfile.serverMembersSourceServerLabel(localServerId: String?): String? {
    val sourceId = serverId?.value?.takeIf { it.isNotBlank() } ?: return null
    if (localServerId != null && sourceId == localServerId) return null
    return serverName?.takeIf { it.isNotBlank() } ?: serverSlug?.takeIf { it.isNotBlank() }
}
