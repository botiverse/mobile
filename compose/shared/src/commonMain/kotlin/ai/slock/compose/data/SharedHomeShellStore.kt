package ai.slock.compose.data

import ai.slock.compose.model.RealtimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val SharedHomeShellDestinationSearch = "search"
const val SharedHomeShellDestinationActivity = "activity"
const val SharedHomeShellDestinationSaved = "saved"

data class SharedHomeShellState(
    val home: SharedHomeState = SharedHomeState(),
    val activity: SharedActivityState = SharedActivityState(),
    val searchSaved: SharedHomeSearchSavedState = SharedHomeSearchSavedState(),
    val destination: String? = null,
    val selectedConversationId: String? = null,
    val primarySurfaceVisible: Boolean = false
) {
    val notifications: List<SharedHomeNotification>
        get() = home.notifications
}

sealed interface SharedHomeShellIntent {
    data object Start : SharedHomeShellIntent
    data object Refresh : SharedHomeShellIntent
    data object ForegroundRefresh : SharedHomeShellIntent
    data class SetSurfaceVisible(val visible: Boolean) : SharedHomeShellIntent
    data object OpenActivity : SharedHomeShellIntent
    data object OpenSaved : SharedHomeShellIntent
    data object OpenSearch : SharedHomeShellIntent
    data object CloseSecondaryDestination : SharedHomeShellIntent
    data class SetSelectedConversation(val conversationId: String?) : SharedHomeShellIntent
    data class SelectTopAction(val action: SharedHomeTopAction) : SharedHomeShellIntent
    data class DismissNotification(val id: String) : SharedHomeShellIntent
    data class ApplyRealtimeEvent(val serverId: String, val event: RealtimeEvent) : SharedHomeShellIntent
    data class Home(val intent: SharedHomeIntent) : SharedHomeShellIntent
    data class Activity(val intent: SharedActivityIntent) : SharedHomeShellIntent
    data class SearchSaved(val intent: SharedHomeSearchSavedIntent) : SharedHomeShellIntent
}

class SharedHomeShellStore(
    homeState: StateFlow<SharedHomeState>,
    activityState: StateFlow<SharedActivityState>,
    searchSavedState: StateFlow<SharedHomeSearchSavedState>,
    private val dispatchHome: (SharedHomeIntent) -> Unit,
    private val dispatchActivity: (SharedActivityIntent) -> Unit,
    private val dispatchSearchSaved: (SharedHomeSearchSavedIntent) -> Unit,
    private val resetActivity: () -> Unit = {},
    private val scope: CoroutineScope
) {
    private var observedHomeServerId: String? = homeState.value.activeHomeServerId()

    private val _state =
        MutableStateFlow(
            SharedHomeShellState(
                home = homeState.value,
                activity = activityState.value,
                searchSaved = searchSavedState.value
            )
        )
    val state: StateFlow<SharedHomeShellState> = _state.asStateFlow()

    private val jobs =
        listOf(
            scope.launch {
                homeState.collect { home ->
                    val previousServerId = observedHomeServerId
                    val nextServerId = home.activeHomeServerId()
                    observedHomeServerId = nextServerId ?: previousServerId
                    _state.value = _state.value.copy(home = home)
                    if (nextServerId != null && previousServerId != nextServerId) {
                        refreshBadgeSummaries(clearBeforeRefresh = previousServerId != null)
                    }
                }
            },
            scope.launch {
                activityState.collect { activity ->
                    _state.value = _state.value.copy(activity = activity)
                }
            },
            scope.launch {
                searchSavedState.collect { searchSaved ->
                    _state.value = _state.value.copy(searchSaved = searchSaved)
                }
            }
        )

    fun dispatch(intent: SharedHomeShellIntent) {
        when (intent) {
            SharedHomeShellIntent.Start -> dispatchHome(SharedHomeIntent.Start)
            SharedHomeShellIntent.Refresh -> dispatchHome(SharedHomeIntent.Refresh)
            SharedHomeShellIntent.ForegroundRefresh -> refreshVisibleHomeDestination()
            is SharedHomeShellIntent.SetSurfaceVisible -> setSurfaceVisible(intent.visible)
            SharedHomeShellIntent.OpenActivity -> openDestination(SharedHomeShellDestinationActivity)
            SharedHomeShellIntent.OpenSaved -> openDestination(SharedHomeShellDestinationSaved)
            SharedHomeShellIntent.OpenSearch -> openDestination(SharedHomeShellDestinationSearch)
            SharedHomeShellIntent.CloseSecondaryDestination ->
                _state.value = _state.value.copy(destination = null)
            is SharedHomeShellIntent.SetSelectedConversation ->
                _state.value = _state.value.copy(selectedConversationId = intent.conversationId)
            is SharedHomeShellIntent.SelectTopAction ->
                dispatchHome(SharedHomeIntent.SelectTopAction(intent.action))
            is SharedHomeShellIntent.DismissNotification ->
                dispatchHome(SharedHomeIntent.DismissNotification(intent.id))
            is SharedHomeShellIntent.ApplyRealtimeEvent -> applyRealtimeEvent(intent.serverId, intent.event)
            is SharedHomeShellIntent.Home -> dispatchHomeIntent(intent.intent)
            is SharedHomeShellIntent.Activity -> dispatchActivity(intent.intent)
            is SharedHomeShellIntent.SearchSaved -> dispatchSearchSaved(intent.intent)
        }
    }

    fun reset() {
        resetActivity()
        _state.value =
            _state.value.copy(
                destination = null,
                selectedConversationId = null,
                primarySurfaceVisible = false
            )
    }

    fun clear() {
        jobs.forEach(Job::cancel)
    }

    private fun setSurfaceVisible(visible: Boolean) {
        _state.value = _state.value.copy(primarySurfaceVisible = visible)
        dispatchHome(SharedHomeIntent.SetSurfaceVisible(visible))
    }

    private fun openDestination(destination: String) {
        _state.value =
            _state.value.copy(
                destination = destination,
                selectedConversationId = null
            )
        when (destination) {
            SharedHomeShellDestinationActivity -> dispatchActivity(SharedActivityIntent.Refresh)
            SharedHomeShellDestinationSaved -> dispatchSearchSaved(SharedHomeSearchSavedIntent.OpenSaved)
            SharedHomeShellDestinationSearch -> dispatchSearchSaved(SharedHomeSearchSavedIntent.OpenSearch)
        }
    }

    private fun refreshVisibleHomeDestination() {
        when (_state.value.destination) {
            null -> dispatchHome(SharedHomeIntent.ForegroundRefresh)
            SharedHomeShellDestinationActivity -> dispatchActivity(SharedActivityIntent.Refresh)
            SharedHomeShellDestinationSaved -> dispatchSearchSaved(SharedHomeSearchSavedIntent.RefreshSaved)
            SharedHomeShellDestinationSearch -> Unit
        }
    }

    private fun applyRealtimeEvent(serverId: String, event: RealtimeEvent) {
        dispatchHome(SharedHomeIntent.ApplyRealtimeEvent(serverId, event))
        val current = _state.value
        if (current.primarySurfaceVisible || current.destination == SharedHomeShellDestinationActivity) {
            dispatchActivity(SharedActivityIntent.ApplyRealtimeEvent(event))
        }
    }

    private fun refreshBadgeSummaries(clearBeforeRefresh: Boolean = false) {
        if (clearBeforeRefresh) {
            dispatchActivity(SharedActivityIntent.ClearSummary)
            dispatchSearchSaved(SharedHomeSearchSavedIntent.ClearSavedSummary)
        }
        dispatchActivity(SharedActivityIntent.Refresh)
        dispatchSearchSaved(SharedHomeSearchSavedIntent.RefreshSaved)
    }

    private fun dispatchHomeIntent(intent: SharedHomeIntent) {
        dispatchHome(intent)
        if (intent == SharedHomeIntent.Refresh) {
            refreshBadgeSummaries()
        }
    }
}

private fun SharedHomeState.activeHomeServerId(): String? =
    when (val current = content) {
        is SharedHomeContentState.Content -> current.snapshot.server.id.value
        is SharedHomeContentState.Loading -> current.session?.currentServerId
        is SharedHomeContentState.Empty -> current.session.currentServerId
        is SharedHomeContentState.Error -> null
    }
