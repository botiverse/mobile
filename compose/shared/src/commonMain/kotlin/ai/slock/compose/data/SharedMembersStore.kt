package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.avatarSlotResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class SharedMembersLoadStatus {
    Idle,
    InFlight,
    Loaded,
    Error
}

data class SharedMembersMemberSuggestion(
    val displayName: String,
    val handle: String,
    val inMentionChannel: Boolean = true,
    val sourceServerLabel: String? = null,
    val kind: MemberKind = MemberKind.Human,
    val description: String? = null,
    val avatarWireValue: String? = null,
    val gravatarHash: String? = null,
    val avatarLabel: String = displayName.take(1).uppercase()
)

data class SharedMembersChannelSuggestion(
    val name: String,
    val subtitle: String? = null
)

data class SharedMembersMentionScope(
    val serverId: String,
    val mentionChannelId: String? = null,
    val memberScopedMentions: Boolean = false
)

data class SharedMembersMemberSuggestionSlice(
    val suggestions: List<SharedMembersMemberSuggestion> = emptyList(),
    val status: SharedMembersLoadStatus = SharedMembersLoadStatus.Idle,
    val errorCode: String? = null
)

data class SharedMembersChannelSuggestionSlice(
    val suggestions: List<SharedMembersChannelSuggestion> = emptyList(),
    val status: SharedMembersLoadStatus = SharedMembersLoadStatus.Idle,
    val errorCode: String? = null
)

data class SharedMembersState(
    val serverId: String? = null,
    val mentionChannelId: String? = null,
    val memberScopedMentions: Boolean = false,
    val memberSuggestions: List<SharedMembersMemberSuggestion> = emptyList(),
    val channelSuggestions: List<SharedMembersChannelSuggestion> = emptyList(),
    val memberStatus: SharedMembersLoadStatus = SharedMembersLoadStatus.Idle,
    val channelStatus: SharedMembersLoadStatus = SharedMembersLoadStatus.Idle,
    val memberErrorCode: String? = null,
    val channelErrorCode: String? = null,
    val memberSuggestionSlices: Map<SharedMembersMentionScope, SharedMembersMemberSuggestionSlice> = emptyMap()
)

fun SharedMembersState.selectMemberSuggestionSlice(
    mentionChannelId: String? = null,
    memberScopedMentions: Boolean = false
): SharedMembersMemberSuggestionSlice {
    val normalizedServerId = serverId.normalizedId() ?: return SharedMembersMemberSuggestionSlice()
    return memberSuggestionSlices[
        SharedMembersMentionScope(
            serverId = normalizedServerId,
            mentionChannelId = mentionChannelId.normalizedId(),
            memberScopedMentions = memberScopedMentions
        )
    ] ?: SharedMembersMemberSuggestionSlice()
}

fun SharedMembersState.selectChannelSuggestionSlice(): SharedMembersChannelSuggestionSlice =
    SharedMembersChannelSuggestionSlice(
        suggestions = channelSuggestions,
        status = channelStatus,
        errorCode = channelErrorCode
    )

sealed interface SharedMembersIntent {
    data class EnsureSuggestions(
        val mentionChannelId: String? = null,
        val memberScopedMentions: Boolean = false
    ) : SharedMembersIntent

    data class RefreshSuggestions(
        val mentionChannelId: String? = null,
        val memberScopedMentions: Boolean = false
    ) : SharedMembersIntent

    data object Clear : SharedMembersIntent
}

class SharedMembersStore(
    private val authStore: SlockAuthSessionStore,
    private val memberRepository: MemberRepository,
    private val channelRepository: ChannelRepository,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow(SharedMembersState())
    val state: StateFlow<SharedMembersState> = _state.asStateFlow()

    fun memberSuggestionSliceFlow(
        mentionChannelId: String? = null,
        memberScopedMentions: Boolean = false
    ): Flow<SharedMembersMemberSuggestionSlice> =
        state
            .map { state ->
                state.selectMemberSuggestionSlice(
                    mentionChannelId = mentionChannelId,
                    memberScopedMentions = memberScopedMentions
                )
            }
            .distinctUntilChanged()

    fun channelSuggestionSliceFlow(): Flow<SharedMembersChannelSuggestionSlice> =
        state
            .map { state -> state.selectChannelSuggestionSlice() }
            .distinctUntilChanged()

    fun dispatch(intent: SharedMembersIntent) {
        when (intent) {
            is SharedMembersIntent.EnsureSuggestions ->
                loadSuggestions(
                    force = false,
                    mentionChannelId = intent.mentionChannelId,
                    memberScopedMentions = intent.memberScopedMentions
                )
            is SharedMembersIntent.RefreshSuggestions ->
                loadSuggestions(
                    force = true,
                    mentionChannelId = intent.mentionChannelId,
                    memberScopedMentions = intent.memberScopedMentions
                )
            SharedMembersIntent.Clear -> _state.value = SharedMembersState()
        }
    }

    private fun loadSuggestions(
        force: Boolean,
        mentionChannelId: String?,
        memberScopedMentions: Boolean
    ) {
        scope.launch {
            val serverId = authStore.readSession()?.currentServerId?.trim().orEmpty()
            if (serverId.isBlank()) {
                _state.value = SharedMembersState()
                return@launch
            }
            ensureSuggestionsForServer(
                serverId = serverId,
                force = force,
                mentionChannelId = mentionChannelId,
                memberScopedMentions = memberScopedMentions
            )
        }
    }

    internal suspend fun ensureSuggestionsForServer(
        serverId: String,
        force: Boolean = false,
        mentionChannelId: String? = null,
        memberScopedMentions: Boolean = false
    ) {
        val normalizedServerId = serverId.trim()
        if (normalizedServerId.isBlank()) {
            _state.value = SharedMembersState()
            return
        }
        val normalizedMentionChannelId = mentionChannelId.normalizedId()
        val memberScope =
            SharedMembersMentionScope(
                serverId = normalizedServerId,
                mentionChannelId = normalizedMentionChannelId,
                memberScopedMentions = memberScopedMentions
            )
        bindScope(
            serverId = normalizedServerId,
            mentionChannelId = normalizedMentionChannelId,
            memberScopedMentions = memberScopedMentions
        )
        val current = _state.value
        val memberSlice = current.selectMemberSuggestionSlice(
            mentionChannelId = normalizedMentionChannelId,
            memberScopedMentions = memberScopedMentions
        )
        val shouldLoadMembers =
            force ||
                (memberSlice.status != SharedMembersLoadStatus.Loaded &&
                    memberSlice.status != SharedMembersLoadStatus.InFlight)
        val shouldLoadChannels =
            force ||
                (current.channelStatus != SharedMembersLoadStatus.Loaded &&
                    current.channelStatus != SharedMembersLoadStatus.InFlight)
        if (!shouldLoadMembers && !shouldLoadChannels) {
            logger("shared_members_suggestions_skip serverId=$normalizedServerId reason=loaded_or_inflight")
            return
        }
        if (shouldLoadMembers) {
            setMemberSuggestionSlice(
                scope = memberScope,
                slice =
                    memberSlice.copy(
                        status = SharedMembersLoadStatus.InFlight,
                        errorCode = null
                    )
            )
        }
        if (shouldLoadChannels) {
            _state.value =
                _state.value.copy(
                    channelStatus = SharedMembersLoadStatus.InFlight,
                    channelErrorCode = null
                )
        }
        if (shouldLoadMembers) {
            loadMemberSuggestions(
                serverId = normalizedServerId,
                mentionChannelId = normalizedMentionChannelId,
                memberScopedMentions = memberScopedMentions
            )
        }
        if (shouldLoadChannels) {
            loadChannelSuggestions(normalizedServerId)
        }
    }

    private fun bindScope(
        serverId: String,
        mentionChannelId: String?,
        memberScopedMentions: Boolean
    ) {
        val current = _state.value
        val memberScope =
            SharedMembersMentionScope(
                serverId = serverId,
                mentionChannelId = mentionChannelId,
                memberScopedMentions = memberScopedMentions
            )
        val memberSlice =
            if (current.serverId == serverId) {
                current.memberSuggestionSlices[memberScope] ?: SharedMembersMemberSuggestionSlice()
            } else {
                SharedMembersMemberSuggestionSlice()
            }
        when {
            current.serverId != serverId ->
                _state.value =
                    SharedMembersState(
                        serverId = serverId,
                        mentionChannelId = mentionChannelId,
                        memberScopedMentions = memberScopedMentions,
                        memberSuggestions = memberSlice.suggestions,
                        memberStatus = memberSlice.status,
                        memberErrorCode = memberSlice.errorCode
                    )
            current.mentionChannelId != mentionChannelId ||
                current.memberScopedMentions != memberScopedMentions ->
                _state.value =
                    current.copy(
                        mentionChannelId = mentionChannelId,
                        memberScopedMentions = memberScopedMentions,
                        memberSuggestions = memberSlice.suggestions,
                        memberStatus = memberSlice.status,
                        memberErrorCode = memberSlice.errorCode
                    )
        }
    }

    private fun setMemberSuggestionSlice(
        scope: SharedMembersMentionScope,
        slice: SharedMembersMemberSuggestionSlice
    ) {
        val current = _state.value
        if (current.serverId != scope.serverId) return
        val nextSlices = current.memberSuggestionSlices + (scope to slice)
        val next =
            if (current.activeMemberScope() == scope) {
                current.copy(
                    memberSuggestions = slice.suggestions,
                    memberStatus = slice.status,
                    memberErrorCode = slice.errorCode,
                    memberSuggestionSlices = nextSlices
                )
            } else {
                current.copy(memberSuggestionSlices = nextSlices)
            }
        _state.value = next
    }

    private suspend fun loadMemberSuggestions(
        serverId: String,
        mentionChannelId: String?,
        memberScopedMentions: Boolean
    ) {
        val memberScope =
            SharedMembersMentionScope(
                serverId = serverId,
                mentionChannelId = mentionChannelId,
                memberScopedMentions = memberScopedMentions
            )
        if (memberScopedMentions) {
            loadScopedMemberSuggestions(
                serverId = serverId,
                mentionChannelId = mentionChannelId,
                memberScopedMentions = memberScopedMentions
            )
            return
        }
        val result =
            runCatchingPreservingCancellation {
                memberRepository.getMembers(DataRequestPriority.Visible, ServerId(serverId))
            }.getOrElse { throwable ->
                logger("shared_members_suggestions_members_result serverId=$serverId result=exception message=${throwable.message.orEmpty()}")
                setMemberSuggestionSlice(
                    scope = memberScope,
                    slice =
                        SharedMembersMemberSuggestionSlice(
                            status = SharedMembersLoadStatus.Error,
                            errorCode = "members_exception"
                        )
                )
                return
            }
        if (_state.value.serverId != serverId) return
        when (result) {
            is DataResult.Content -> {
                val channelMembers =
                    mentionChannelId?.let { channelId ->
                        loadChannelMembersBestEffort(
                            serverId = serverId,
                            mentionChannelId = channelId
                        )
                    }.orEmpty()
                if (_state.value.serverId != serverId) return
                setMemberSuggestionSlice(
                    scope = memberScope,
                    slice =
                        SharedMembersMemberSuggestionSlice(
                            suggestions =
                                if (mentionChannelId == null) {
                                    result.data.members.toSharedMembersMemberSuggestions()
                                } else {
                                    result.data.members.toSharedMembersMemberSuggestions(
                                        localServerId = serverId,
                                        inMentionChannelIds = channelMembers.map { it.id.value }.toSet()
                                    ).mergeMentionChannelSuggestions(
                                        channelMembers.toSharedMembersMemberSuggestions(
                                            localServerId = serverId,
                                            inMentionChannel = true
                                        )
                                    )
                                },
                            status = SharedMembersLoadStatus.Loaded
                        )
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
                if (_state.value.serverId != serverId) return
                setMemberSuggestionSlice(
                    scope = memberScope,
                    slice =
                        SharedMembersMemberSuggestionSlice(
                            suggestions = channelMembers.toSharedMembersMemberSuggestions(localServerId = serverId),
                            status = SharedMembersLoadStatus.Loaded
                        )
                    )
            }
            is DataResult.Error -> {
                setMemberSuggestionSlice(
                    scope = memberScope,
                    slice =
                        SharedMembersMemberSuggestionSlice(
                            status = SharedMembersLoadStatus.Error,
                            errorCode = result.error.code
                        )
                    )
            }
            is DataResult.NoPermission -> {
                setMemberSuggestionSlice(
                    scope = memberScope,
                    slice =
                        SharedMembersMemberSuggestionSlice(
                            status = SharedMembersLoadStatus.Error,
                            errorCode = result.blocker.code
                        )
                    )
            }
        }
    }

    private suspend fun loadScopedMemberSuggestions(
        serverId: String,
        mentionChannelId: String?,
        memberScopedMentions: Boolean
    ) {
        val memberScope =
            SharedMembersMentionScope(
                serverId = serverId,
                mentionChannelId = mentionChannelId,
                memberScopedMentions = memberScopedMentions
            )
        if (mentionChannelId == null) {
            setMemberSuggestionSlice(
                scope = memberScope,
                slice = SharedMembersMemberSuggestionSlice(status = SharedMembersLoadStatus.Loaded)
            )
            return
        }
        val result =
            runCatchingPreservingCancellation {
                memberRepository.getChannelMembers(DataRequestPriority.Visible, ChannelId(mentionChannelId))
            }.getOrElse { throwable ->
                logger(
                    "shared_members_suggestions_channel_members_result serverId=$serverId " +
                        "channelId=$mentionChannelId result=exception message=${throwable.message.orEmpty()}"
                )
                setMemberSuggestionSlice(
                    scope = memberScope,
                    slice =
                        SharedMembersMemberSuggestionSlice(
                            status = SharedMembersLoadStatus.Error,
                            errorCode = "channel_members_exception"
                        )
                )
                return
            }
        if (_state.value.serverId != serverId) return
        when (result) {
            is DataResult.Content -> {
                setMemberSuggestionSlice(
                    scope = memberScope,
                    slice =
                        SharedMembersMemberSuggestionSlice(
                            suggestions = result.data.members.toSharedMembersMemberSuggestions(localServerId = serverId),
                            status = SharedMembersLoadStatus.Loaded
                        )
                    )
            }
            is DataResult.Empty -> {
                setMemberSuggestionSlice(
                    scope = memberScope,
                    slice = SharedMembersMemberSuggestionSlice(status = SharedMembersLoadStatus.Loaded)
                )
            }
            is DataResult.Error -> {
                setMemberSuggestionSlice(
                    scope = memberScope,
                    slice =
                        SharedMembersMemberSuggestionSlice(
                            status = SharedMembersLoadStatus.Error,
                            errorCode = result.error.code
                        )
                    )
            }
            is DataResult.NoPermission -> {
                setMemberSuggestionSlice(
                    scope = memberScope,
                    slice =
                        SharedMembersMemberSuggestionSlice(
                            status = SharedMembersLoadStatus.Error,
                            errorCode = result.blocker.code
                        )
                    )
            }
        }
    }

    private suspend fun loadChannelMembersBestEffort(
        serverId: String,
        mentionChannelId: String
    ): List<MemberProfile> {
        val result =
            runCatchingPreservingCancellation {
                memberRepository.getChannelMembers(DataRequestPriority.Visible, ChannelId(mentionChannelId))
            }.getOrElse { throwable ->
                logger(
                    "shared_members_suggestions_channel_members_result serverId=$serverId " +
                        "channelId=$mentionChannelId result=exception message=${throwable.message.orEmpty()}"
                )
                return emptyList()
            }
        if (_state.value.serverId != serverId) return emptyList()
        return when (result) {
            is DataResult.Content -> result.data.members
            is DataResult.Empty -> emptyList()
            is DataResult.Error -> {
                logger(
                    "shared_members_suggestions_channel_members_result serverId=$serverId " +
                        "channelId=$mentionChannelId result=error code=${result.error.code}"
                )
                emptyList()
            }
            is DataResult.NoPermission -> {
                logger(
                    "shared_members_suggestions_channel_members_result serverId=$serverId " +
                        "channelId=$mentionChannelId result=no_permission code=${result.blocker.code}"
                )
                emptyList()
            }
        }
    }

    private suspend fun loadChannelSuggestions(serverId: String) {
        val result =
            runCatchingPreservingCancellation {
                channelRepository.getChannels(ServerId(serverId))
            }.getOrElse { throwable ->
                logger("shared_members_suggestions_channels_result serverId=$serverId result=exception message=${throwable.message.orEmpty()}")
                if (_state.value.serverId == serverId) {
                    _state.value =
                        _state.value.copy(
                            channelStatus = SharedMembersLoadStatus.Error,
                            channelErrorCode = "channels_exception"
                        )
                }
                return
            }
        if (_state.value.serverId != serverId) return
        when (result) {
            is DataResult.Content -> {
                _state.value =
                    _state.value.copy(
                        channelSuggestions = result.data.channels.toSharedMembersChannelSuggestions(),
                        channelStatus = SharedMembersLoadStatus.Loaded,
                        channelErrorCode = null
                    )
            }
            is DataResult.Empty -> {
                _state.value =
                    _state.value.copy(
                        channelSuggestions = emptyList(),
                        channelStatus = SharedMembersLoadStatus.Loaded,
                        channelErrorCode = null
                    )
            }
            is DataResult.Error -> {
                _state.value =
                    _state.value.copy(
                        channelStatus = SharedMembersLoadStatus.Error,
                        channelErrorCode = result.error.code
                    )
            }
            is DataResult.NoPermission -> {
                _state.value =
                    _state.value.copy(
                        channelStatus = SharedMembersLoadStatus.Error,
                        channelErrorCode = result.blocker.code
                    )
            }
        }
    }
}

private fun SharedMembersState.activeMemberScope(): SharedMembersMentionScope? {
    val normalizedServerId = serverId.normalizedId() ?: return null
    return SharedMembersMentionScope(
        serverId = normalizedServerId,
        mentionChannelId = mentionChannelId.normalizedId(),
        memberScopedMentions = memberScopedMentions
    )
}

private fun String?.normalizedId(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

private fun List<MemberProfile>.toSharedMembersMemberSuggestions(
    localServerId: String? = null
): List<SharedMembersMemberSuggestion> {
    return toSharedMembersMemberSuggestions(localServerId = localServerId, inMentionChannel = true)
}

private fun List<MemberProfile>.toSharedMembersMemberSuggestions(
    localServerId: String? = null,
    inMentionChannel: Boolean
): List<SharedMembersMemberSuggestion> =
    toSharedMembersMemberSuggestions(
        localServerId = localServerId,
        inMentionChannelIds = null,
        fallbackInMentionChannel = inMentionChannel
    )

private fun List<MemberProfile>.toSharedMembersMemberSuggestions(
    localServerId: String? = null,
    inMentionChannelIds: Set<String>?,
    fallbackInMentionChannel: Boolean = false
): List<SharedMembersMemberSuggestion> {
    val deduped = LinkedHashMap<String, SharedMembersMemberSuggestion>()
    for (member in this) {
        val handle = member.handle.trim().removePrefix("@").takeIf { it.isNotBlank() }
            ?: member.displayName.toSuggestionToken()
            ?: continue
        val key = handle.lowercase()
        if (!deduped.containsKey(key)) {
            val avatar = member.avatarSlotResolution()
            deduped[key] =
                SharedMembersMemberSuggestion(
                    displayName = member.displayName.trim().ifBlank { handle },
                    handle = handle,
                    inMentionChannel = inMentionChannelIds?.contains(member.id.value) ?: fallbackInMentionChannel,
                    sourceServerLabel = member.remoteSourceServerLabel(localServerId),
                    kind = member.kind,
                    description = member.description?.trim()?.takeIf { it.isNotBlank() },
                    avatarWireValue = avatar.effectiveAvatarWireValue,
                    gravatarHash = avatar.gravatarHash,
                    avatarLabel = member.avatarLabel.trim().takeIf { it.isNotBlank() }
                        ?: member.displayName.trim().take(1).uppercase().ifBlank { handle.take(1).uppercase() }
                )
        }
    }
    return deduped.values.toList()
}

private fun List<SharedMembersMemberSuggestion>.mergeMentionChannelSuggestions(
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
                        avatarWireValue = existing.avatarWireValue.preferConcreteSuggestionAvatar(suggestion.avatarWireValue),
                        gravatarHash = existing.gravatarHash ?: suggestion.gravatarHash
                    )
                else -> existing
            }
    }
    return deduped.values.toList()
}

private fun String?.preferConcreteSuggestionAvatar(candidate: String?): String? =
    when {
        isNullOrBlank() -> candidate
        isAgentFallbackSuggestionAvatar() && !candidate.isNullOrBlank() &&
            !candidate.isAgentFallbackSuggestionAvatar() -> candidate
        else -> this
    }

private fun String?.isAgentFallbackSuggestionAvatar(): Boolean =
    this?.equals("pixel:robot", ignoreCase = true) == true || this?.startsWith("random:", ignoreCase = true) == true

private fun MemberProfile.remoteSourceServerLabel(localServerId: String?): String? {
    val sourceId = serverId?.value?.takeIf { it.isNotBlank() } ?: return null
    if (localServerId != null && sourceId == localServerId) return null
    return serverName?.takeIf { it.isNotBlank() } ?: serverSlug?.takeIf { it.isNotBlank() }
}

private fun List<ConversationSummary>.toSharedMembersChannelSuggestions(): List<SharedMembersChannelSuggestion> {
    val deduped = LinkedHashMap<String, SharedMembersChannelSuggestion>()
    for (channel in this) {
        if (channel.archived || channel.kind == ConversationKind.DirectMessage) continue
        val name = channel.name.trim().removePrefix("#").takeIf { it.isNotBlank() } ?: continue
        val key = name.lowercase()
        if (!deduped.containsKey(key)) {
            deduped[key] =
                SharedMembersChannelSuggestion(
                    name = name,
                    subtitle = channel.subtitle?.trim()?.takeIf { it.isNotBlank() }
                )
        }
    }
    return deduped.values.toList()
}

private fun String.toSuggestionToken(): String? =
    trim()
        .lowercase()
        .replace(Regex("""\s+"""), "-")
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        .takeIf { it.isNotBlank() }
