package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SharedMembersLoadStatus {
    Idle,
    InFlight,
    Loaded,
    Error
}

data class SharedMembersMemberSuggestion(
    val displayName: String,
    val handle: String
)

data class SharedMembersChannelSuggestion(
    val name: String,
    val subtitle: String? = null
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
    val channelErrorCode: String? = null
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
        val normalizedMentionChannelId = mentionChannelId?.trim()?.takeIf { it.isNotBlank() }
        bindScope(
            serverId = normalizedServerId,
            mentionChannelId = normalizedMentionChannelId,
            memberScopedMentions = memberScopedMentions
        )
        val current = _state.value
        val shouldLoadMembers =
            force ||
                (current.memberStatus != SharedMembersLoadStatus.Loaded &&
                    current.memberStatus != SharedMembersLoadStatus.InFlight)
        val shouldLoadChannels =
            force ||
                (current.channelStatus != SharedMembersLoadStatus.Loaded &&
                    current.channelStatus != SharedMembersLoadStatus.InFlight)
        if (!shouldLoadMembers && !shouldLoadChannels) {
            logger("shared_members_suggestions_skip serverId=$normalizedServerId reason=loaded_or_inflight")
            return
        }
        _state.value =
            _state.value.copy(
                memberStatus =
                    if (shouldLoadMembers) SharedMembersLoadStatus.InFlight else _state.value.memberStatus,
                channelStatus =
                    if (shouldLoadChannels) SharedMembersLoadStatus.InFlight else _state.value.channelStatus,
                memberErrorCode = if (shouldLoadMembers) null else _state.value.memberErrorCode,
                channelErrorCode = if (shouldLoadChannels) null else _state.value.channelErrorCode
            )
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
        when {
            current.serverId != serverId ->
                _state.value =
                    SharedMembersState(
                        serverId = serverId,
                        mentionChannelId = mentionChannelId,
                        memberScopedMentions = memberScopedMentions
                    )
            current.mentionChannelId != mentionChannelId ||
                current.memberScopedMentions != memberScopedMentions ->
                _state.value =
                    current.copy(
                        mentionChannelId = mentionChannelId,
                        memberScopedMentions = memberScopedMentions,
                        memberSuggestions = emptyList(),
                        memberStatus = SharedMembersLoadStatus.Idle,
                        memberErrorCode = null
                    )
        }
    }

    private suspend fun loadMemberSuggestions(
        serverId: String,
        mentionChannelId: String?,
        memberScopedMentions: Boolean
    ) {
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
                if (_state.value.matchesScope(serverId, mentionChannelId, memberScopedMentions)) {
                    _state.value =
                        _state.value.copy(
                            memberStatus = SharedMembersLoadStatus.Error,
                            memberErrorCode = "members_exception"
                        )
                }
                return
            }
        if (!_state.value.matchesScope(serverId, mentionChannelId, memberScopedMentions)) return
        when (result) {
            is DataResult.Content -> {
                val channelMembers =
                    mentionChannelId?.let { channelId ->
                        loadChannelMembersBestEffort(
                            serverId = serverId,
                            mentionChannelId = channelId,
                            memberScopedMentions = memberScopedMentions
                        )
                    }.orEmpty()
                if (!_state.value.matchesScope(serverId, mentionChannelId, memberScopedMentions)) return
                _state.value =
                    _state.value.copy(
                        memberSuggestions = (result.data.members + channelMembers).toSharedMembersMemberSuggestions(),
                        memberStatus = SharedMembersLoadStatus.Loaded,
                        memberErrorCode = null
                    )
            }
            is DataResult.Empty -> {
                val channelMembers =
                    mentionChannelId?.let { channelId ->
                        loadChannelMembersBestEffort(
                            serverId = serverId,
                            mentionChannelId = channelId,
                            memberScopedMentions = memberScopedMentions
                        )
                    }.orEmpty()
                if (!_state.value.matchesScope(serverId, mentionChannelId, memberScopedMentions)) return
                _state.value =
                    _state.value.copy(
                        memberSuggestions = channelMembers.toSharedMembersMemberSuggestions(),
                        memberStatus = SharedMembersLoadStatus.Loaded,
                        memberErrorCode = null
                    )
            }
            is DataResult.Error -> {
                _state.value =
                    _state.value.copy(
                        memberStatus = SharedMembersLoadStatus.Error,
                        memberErrorCode = result.error.code
                    )
            }
            is DataResult.NoPermission -> {
                _state.value =
                    _state.value.copy(
                        memberStatus = SharedMembersLoadStatus.Error,
                        memberErrorCode = result.blocker.code
                    )
            }
        }
    }

    private suspend fun loadScopedMemberSuggestions(
        serverId: String,
        mentionChannelId: String?,
        memberScopedMentions: Boolean
    ) {
        if (mentionChannelId == null) {
            if (_state.value.matchesScope(serverId, mentionChannelId, memberScopedMentions)) {
                _state.value =
                    _state.value.copy(
                        memberSuggestions = emptyList(),
                        memberStatus = SharedMembersLoadStatus.Loaded,
                        memberErrorCode = null
                    )
            }
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
                if (_state.value.matchesScope(serverId, mentionChannelId, memberScopedMentions)) {
                    _state.value =
                        _state.value.copy(
                            memberStatus = SharedMembersLoadStatus.Error,
                            memberErrorCode = "channel_members_exception"
                        )
                }
                return
            }
        if (!_state.value.matchesScope(serverId, mentionChannelId, memberScopedMentions)) return
        when (result) {
            is DataResult.Content -> {
                _state.value =
                    _state.value.copy(
                        memberSuggestions = result.data.members.toSharedMembersMemberSuggestions(),
                        memberStatus = SharedMembersLoadStatus.Loaded,
                        memberErrorCode = null
                    )
            }
            is DataResult.Empty -> {
                _state.value =
                    _state.value.copy(
                        memberSuggestions = emptyList(),
                        memberStatus = SharedMembersLoadStatus.Loaded,
                        memberErrorCode = null
                    )
            }
            is DataResult.Error -> {
                _state.value =
                    _state.value.copy(
                        memberStatus = SharedMembersLoadStatus.Error,
                        memberErrorCode = result.error.code
                    )
            }
            is DataResult.NoPermission -> {
                _state.value =
                    _state.value.copy(
                        memberStatus = SharedMembersLoadStatus.Error,
                        memberErrorCode = result.blocker.code
                    )
            }
        }
    }

    private suspend fun loadChannelMembersBestEffort(
        serverId: String,
        mentionChannelId: String,
        memberScopedMentions: Boolean
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
        if (!_state.value.matchesScope(serverId, mentionChannelId, memberScopedMentions)) return emptyList()
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

private fun SharedMembersState.matchesScope(
    serverId: String,
    mentionChannelId: String?,
    memberScopedMentions: Boolean
): Boolean =
    this.serverId == serverId &&
        this.mentionChannelId == mentionChannelId &&
        this.memberScopedMentions == memberScopedMentions

private fun List<MemberProfile>.toSharedMembersMemberSuggestions(): List<SharedMembersMemberSuggestion> {
    val deduped = LinkedHashMap<String, SharedMembersMemberSuggestion>()
    for (member in this) {
        val handle = member.handle.trim().removePrefix("@").takeIf { it.isNotBlank() }
            ?: member.displayName.toSuggestionToken()
            ?: continue
        val key = handle.lowercase()
        if (!deduped.containsKey(key)) {
            deduped[key] =
                SharedMembersMemberSuggestion(
                    displayName = member.displayName.trim().ifBlank { handle },
                    handle = handle
                )
        }
    }
    return deduped.values.toList()
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
