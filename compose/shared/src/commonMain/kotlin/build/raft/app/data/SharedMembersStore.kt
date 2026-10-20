package build.raft.app.data

import build.raft.app.toRaftAvatarLabel
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
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
    val sourceServerId: String? = null,
    val isLocalServer: Boolean = true,
    val sourceServerLabel: String? = null,
    val kind: MemberKind = MemberKind.Human,
    val description: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String = displayName.toRaftAvatarLabel(),
    val memberId: String? = null
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
    val channelSuggestions: List<SharedMembersChannelSuggestion> = emptyList(),
    val channelStatus: SharedMembersLoadStatus = SharedMembersLoadStatus.Idle,
    val channelErrorCode: String? = null
)

fun SharedMembersState.selectChannelSuggestionSlice(): SharedMembersChannelSuggestionSlice =
    SharedMembersChannelSuggestionSlice(
        suggestions = channelSuggestions,
        status = channelStatus,
        errorCode = channelErrorCode
    )

sealed interface SharedMembersIntent {
    data object EnsureChannelSuggestions : SharedMembersIntent

    data object RefreshChannelSuggestions : SharedMembersIntent

    data object Clear : SharedMembersIntent
}

class SharedMembersStore(
    private val authStore: RaftAuthSessionStore,
    private val channelRepository: ChannelRepository,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow(SharedMembersState())
    val state: StateFlow<SharedMembersState> = _state.asStateFlow()

    fun channelSuggestionSliceFlow(): Flow<SharedMembersChannelSuggestionSlice> =
        state
            .map { state -> state.selectChannelSuggestionSlice() }
            .distinctUntilChanged()

    fun dispatch(intent: SharedMembersIntent) {
        when (intent) {
            SharedMembersIntent.EnsureChannelSuggestions -> launchChannelSuggestionsLoad(force = false)
            SharedMembersIntent.RefreshChannelSuggestions -> launchChannelSuggestionsLoad(force = true)
            SharedMembersIntent.Clear -> _state.value = SharedMembersState()
        }
    }

    suspend fun acceptRealtimeChannelSuggestionsRefresh() {
        loadChannelSuggestionsForCurrentSession(force = true)
    }

    private fun launchChannelSuggestionsLoad(force: Boolean) {
        scope.launch {
            loadChannelSuggestionsForCurrentSession(force)
        }
    }

    private suspend fun loadChannelSuggestionsForCurrentSession(force: Boolean) {
        val serverId = authStore.readSession()?.currentServerId?.trim().orEmpty()
        if (serverId.isBlank()) {
            _state.value = SharedMembersState()
            return
        }
        bindServer(serverId)
        val current = _state.value
        val shouldLoad =
            force ||
                (current.channelStatus != SharedMembersLoadStatus.Loaded &&
                    current.channelStatus != SharedMembersLoadStatus.InFlight)
        if (!shouldLoad) {
            logger("shared_members_channel_suggestions_skip serverId=$serverId reason=loaded_or_inflight")
            return
        }
        _state.value =
            _state.value.copy(
                channelStatus = SharedMembersLoadStatus.InFlight,
                channelErrorCode = null
            )
        loadChannelSuggestions(serverId)
    }

    private fun bindServer(serverId: String) {
        if (_state.value.serverId != serverId) {
            _state.value = SharedMembersState(serverId = serverId)
        }
    }

    private suspend fun loadChannelSuggestions(serverId: String) {
        val result =
            runCatchingPreservingCancellation {
                channelRepository.getChannels(ServerId(serverId))
            }.getOrElse { throwable ->
                logger("shared_members_channels_result serverId=$serverId result=exception message=${throwable.message.orEmpty()}")
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
            is DataResult.Content ->
                _state.value =
                    _state.value.copy(
                        channelSuggestions = result.data.channels.map { it.toConversationSummary() }.toSharedMembersChannelSuggestions(),
                        channelStatus = SharedMembersLoadStatus.Loaded,
                        channelErrorCode = null
                    )
            is DataResult.Empty ->
                _state.value =
                    _state.value.copy(
                        channelSuggestions = emptyList(),
                        channelStatus = SharedMembersLoadStatus.Loaded,
                        channelErrorCode = null
                    )
            is DataResult.Error ->
                _state.value =
                    _state.value.copy(
                        channelStatus = SharedMembersLoadStatus.Error,
                        channelErrorCode = result.error.code
                    )
            is DataResult.NoPermission ->
                _state.value =
                    _state.value.copy(
                        channelStatus = SharedMembersLoadStatus.Error,
                        channelErrorCode = result.blocker.code
                    )
        }
    }
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
