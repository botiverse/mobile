package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Set / Replace picker's list (web `AnnouncementPicker`: `useConversationArtifacts`, the
 * channel's Files list): the channel's own Artifacts, newest first, a page at a time
 * (`GET artifacts/conversations/:channelId`, ConversationArtifactsApi.kt), the current
 * announcement left out.
 */
internal data class ChannelAnnouncementPickerState(
    val status: Status = Status.Loading,
    val artifacts: List<ConversationArtifact> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
) {
    enum class Status { Loading, Failed, Ready }

    /** What the picker lists: every Artifact but [currentId], the announcement now. */
    fun choicesExcept(currentId: String?): List<ConversationArtifact> =
        artifacts.filter { currentId == null || artifactCanonicalId(it.summary.id) != artifactCanonicalId(currentId) }
}

internal sealed interface ChannelAnnouncementPickerIntent {
    /** The picker opened, or Try again. */
    data object Load : ChannelAnnouncementPickerIntent

    /** The next page. Ignored while one is loading or there is none. */
    data object ShowMore : ChannelAnnouncementPickerIntent
}

internal class ChannelAnnouncementPickerStore(
    private val api: RaftConversationArtifactsApi,
    private val serverId: String,
    private val channelId: String,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(ChannelAnnouncementPickerState())
    val state: StateFlow<ChannelAnnouncementPickerState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun dispatch(intent: ChannelAnnouncementPickerIntent) {
        when (intent) {
            ChannelAnnouncementPickerIntent.Load -> {
                job?.cancel()
                mutableState.value = ChannelAnnouncementPickerState()
                job =
                    scope.launch {
                        val page = api.loadConversationArtifacts(serverId, channelId, cursor = null)
                        mutableState.value =
                            if (page == null) {
                                ChannelAnnouncementPickerState(status = ChannelAnnouncementPickerState.Status.Failed)
                            } else {
                                ChannelAnnouncementPickerState(ChannelAnnouncementPickerState.Status.Ready, page.artifacts, page.nextCursor)
                            }
                    }
            }
            ChannelAnnouncementPickerIntent.ShowMore -> {
                val current = mutableState.value
                val cursor = current.nextCursor ?: return
                if (current.status != ChannelAnnouncementPickerState.Status.Ready || current.loadingMore) return
                mutableState.update { it.copy(loadingMore = true) }
                job =
                    scope.launch {
                        val page = api.loadConversationArtifacts(serverId, channelId, cursor)
                        mutableState.update { state ->
                            if (page == null) {
                                // The rows shown stay; Show more can be pressed again.
                                state.copy(loadingMore = false)
                            } else {
                                val known = state.artifacts.map { it.summary.id }.toSet()
                                state.copy(
                                    artifacts = state.artifacts + page.artifacts.filter { it.summary.id !in known },
                                    nextCursor = page.nextCursor,
                                    loadingMore = false,
                                )
                            }
                        }
                    }
            }
        }
    }
}
