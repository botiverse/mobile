package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.avatarSlotResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        val presenceFact = presenceByMemberId[cleanMemberId]
        if (
            presenceFact != null &&
            (
                presenceFact.kind == null ||
                    presenceFact.kind == MemberKind.Agent ||
                    member?.kind == MemberKind.Agent
            )
        ) {
            return SharedServerMemberStatus(
                status = presenceFact.agentStatus ?: member?.agentStatus,
                presence = presenceFact.presence
            )
        }
        if (member?.kind != MemberKind.Agent) return null
        return SharedServerMemberStatus(
            status = member.agentStatus,
            presence = member.presence
        )
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
}

data class SharedServerMemberStatus(
    val status: AgentStatus?,
    val presence: Presence
)

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
    ): Flow<SharedServerMemberStatus?> =
        state
            .map { current -> current.memberStatus(serverId, memberId) }
            .distinctUntilChanged()

    fun memberProfileFlow(
        serverId: ServerId?,
        memberId: String?
    ): Flow<MemberProfile?> =
        state
            .map { current -> current.memberProfile(serverId, memberId) }
            .distinctUntilChanged()

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
        var channelChanged = false
        val nextChannelSlices =
            current.channelMemberSlices.mapValues { (_, slice) ->
                var sliceChanged = false
                val nextMembers =
                    slice.members.map { member ->
                        if (member.kind == MemberKind.Agent && member.id.value == memberId) {
                            sliceChanged = true
                            member.copy(
                                presence = event.presence,
                                agentStatus = event.agentStatus ?: member.agentStatus
                            )
                        } else {
                            member
                        }
                    }
                if (sliceChanged) {
                    channelChanged = true
                    slice.copy(members = nextMembers)
                } else {
                    slice
                }
            }
        if (!changed && !channelChanged && nextPresenceFacts == current.presenceByMemberId) return
        _state.value =
            current.copy(
                entriesByServerId = nextEntries,
                presenceByMemberId = nextPresenceFacts,
                channelMemberSlices = nextChannelSlices
            )
    }

    suspend fun loadMembers(
        serverId: ServerId,
        priority: DataRequestPriority = DataRequestPriority.Visible,
        force: Boolean = false
    ): DataResult<Unit> {
        val key = serverId.value.trim()
        if (key.isBlank()) {
            return DataResult.Error(ScreenError(code = "server_missing", retryable = false))
        }
        cachedLoadedSnapshot(key, force)?.let { return DataResult.Content(Unit) }
        return mutex.withLock {
            cachedLoadedSnapshot(key, force)?.let { return@withLock DataResult.Content(Unit) }
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
                            members =
                                result.data.members
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
                loadMembers(ServerId(serverId), DataRequestPriority.Visible)
            }.getOrElse { throwable ->
                logger("shared_server_members_suggestions_members_result serverId=$serverId result=exception message=${throwable.message.orEmpty()}")
                setMemberSuggestionFailure(suggestionScope, "members_exception")
                return
            }
        when (result) {
            is DataResult.Content -> {
                val serverMembers = cachedSnapshot(ServerId(serverId))?.members.orEmpty()
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
}

private fun MemberPresenceUpdated.shouldReplacePresenceFact(previous: MemberPresenceUpdated?): Boolean =
    when {
        previous == null -> true
        serverSeq != null && previous.serverSeq != null -> serverSeq >= previous.serverSeq
        serverSeq != null && previous.serverSeq == null -> true
        else -> true
    }

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

private fun MemberProfile.mergeSeedProfile(seed: MemberProfile): MemberProfile {
    val agentProfile = kind == MemberKind.Agent || seed.kind == MemberKind.Agent
    return copy(
        displayName = displayName.takeIf { it.isNotBlank() } ?: seed.displayName,
        handle = handle.takeIf { it.isNotBlank() } ?: seed.handle,
        description = description ?: seed.description,
        roleDescription = roleDescription ?: seed.roleDescription,
        email = email ?: seed.email,
        avatarUrl = avatarUrl ?: seed.avatarUrl,
        gravatarHash = gravatarHash ?: seed.gravatarHash,
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
        val handle = member.handle.trim().removePrefix("@").takeIf { it.isNotBlank() }
            ?: member.displayName.toServerMembersSuggestionToken()
            ?: continue
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
                    gravatarHash = avatar.gravatarHash,
                    avatarLabel = member.avatarLabel.trim().takeIf { it.isNotBlank() }
                        ?: member.displayName.trim().take(1).uppercase().ifBlank { handle.take(1).uppercase() },
                    memberId = member.id.value
                )
        }
    }
    return deduped.values.toList()
}

private fun List<SharedMembersMemberSuggestion>.mergeServerMembersMentionChannelSuggestions(
    channelSuggestions: List<SharedMembersMemberSuggestion>
): List<SharedMembersMemberSuggestion> {
    if (channelSuggestions.isEmpty()) return this
    val deduped = LinkedHashMap<String, SharedMembersMemberSuggestion>()
    for (suggestion in this + channelSuggestions) {
        val key = suggestion.handle.lowercase()
        val existing = deduped[key]
        deduped[key] =
            when {
                existing == null -> suggestion
                suggestion.inMentionChannel && !existing.inMentionChannel ->
                    existing.copy(
                        inMentionChannel = true,
                        sourceServerLabel = existing.sourceServerLabel ?: suggestion.sourceServerLabel,
                        description = existing.description ?: suggestion.description,
                        avatarWireValue = existing.avatarWireValue.preferServerMembersConcreteSuggestionAvatar(suggestion.avatarWireValue),
                        gravatarHash = existing.gravatarHash ?: suggestion.gravatarHash,
                        memberId = existing.memberId ?: suggestion.memberId
                    )
                else -> existing
            }
    }
    return deduped.values.toList()
}

private fun String?.preferServerMembersConcreteSuggestionAvatar(candidate: String?): String? =
    when {
        isNullOrBlank() -> candidate
        isServerMembersAgentFallbackSuggestionAvatar() && !candidate.isNullOrBlank() &&
            !candidate.isServerMembersAgentFallbackSuggestionAvatar() -> candidate
        else -> this
    }

private fun String?.isServerMembersAgentFallbackSuggestionAvatar(): Boolean =
    this?.equals("pixel:robot", ignoreCase = true) == true || this?.startsWith("random:", ignoreCase = true) == true

private fun MemberProfile.serverMembersSourceServerLabel(localServerId: String?): String? {
    val sourceId = serverId?.value?.takeIf { it.isNotBlank() } ?: return null
    if (localServerId != null && sourceId == localServerId) return null
    return serverName?.takeIf { it.isNotBlank() } ?: serverSlug?.takeIf { it.isNotBlank() }
}

private fun String.toServerMembersSuggestionToken(): String? =
    trim()
        .lowercase()
        .replace(Regex("""\s+"""), "-")
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        .takeIf { it.isNotBlank() }
