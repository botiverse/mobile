package build.raft.app

import build.raft.app.data.ServerPublicMutationResult
import build.raft.app.data.ServerPublicVisibilityLoadResult
import build.raft.app.data.ServerPublicVisibilityReadback
import build.raft.app.data.ServerPublicVisibilityRepository
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * MVI store for the owner-only "Public access" section of Server settings. Mirrors web
 * PublicVisibilitySection + publicVisibilityUiReducer (botiverse/slock@9df0d1b3f
 * packages/web/src/components/settings/SettingsPanel.tsx:2069-2436).
 *
 * Visibility decision: the section exists only for role == "owner", and only once
 * `GET /servers/:id/public-visibility` answers. A 404 (the `public_server_v0` gate is off; the
 * server evaluates it with platform "web") or 403 hides it. The mobile flag store is
 * intentionally not consulted because it evaluates with platform "mobile".
 */

internal enum class ServerPublicVisibilityAvailability {
    /** Not owner, no server, or not asked yet. */
    Hidden,
    Loading,
    Available,
    /** GET returned 404/403: feature unavailable for this server/caller. */
    Unavailable,
    /** GET failed for another reason: the section shows with the failedLoad error (web parity). */
    LoadFailed,
}

internal enum class ServerPublicVisibilityErrorKind { Load, Update, GuestJoinUpdate, SearchUpdate }

internal data class ServerPublicVisibilityError(
    val kind: ServerPublicVisibilityErrorKind,
    /** Server `{ error }` text; web shows it in preference to the localized fallback. */
    val serverMessage: String? = null,
)

internal data class ServerPublicVisibilityState(
    val serverId: String? = null,
    val availability: ServerPublicVisibilityAvailability = ServerPublicVisibilityAvailability.Hidden,
    val readback: ServerPublicVisibilityReadback? = null,
    /** `server_guest_v0` evaluated with platform "web" for this server (web: serverGuestEnabled). */
    val guestJoinApplicable: Boolean = false,
    val saving: Boolean = false,
    val confirmOpen: Boolean = false,
    val error: ServerPublicVisibilityError? = null,
) {
    val loading: Boolean
        get() = availability == ServerPublicVisibilityAvailability.Loading

    val sectionVisible: Boolean
        get() =
            availability == ServerPublicVisibilityAvailability.Available ||
                availability == ServerPublicVisibilityAvailability.LoadFailed

    /** Main switch: `checked={readback?.publiclyVisible === true}`. */
    val visibilityChecked: Boolean
        get() = readback?.publiclyVisible == true

    /** Main switch: `disabled={loading || saving || !readback}`. */
    val visibilitySwitchEnabled: Boolean
        get() = !loading && !saving && readback != null

    /** Sub-switches additionally require a public server: `|| !readback.publiclyVisible`. */
    val subSwitchesEnabled: Boolean
        get() = visibilitySwitchEnabled && readback?.publiclyVisible == true

    val guestJoinChecked: Boolean
        get() = readback?.publicGuestJoinEnabled == true

    /** `checked={readback?.publiclyVisible === true && readback.publicSearchEnabled}`. */
    val searchChecked: Boolean
        get() = readback?.publiclyVisible == true && readback.publicSearchEnabled

    /**
     * `${shareableWebOrigin()}/s/${slug}` while public. The caller passes the active Developer
     * environment web URL (AGENTS.md Environment Web Link URL Rule) and reuses the same value for
     * display and copy.
     */
    fun publicUrl(webBaseUrl: String): String? {
        val current = readback?.takeIf { it.publiclyVisible } ?: return null
        val base = webBaseUrl.trim().trimEnd('/')
        if (base.isEmpty() || current.slug.isBlank()) return null
        return "$base/s/${current.slug}"
    }
}

internal sealed interface ServerPublicVisibilityIntent {
    /** Bind to the current server + caller role; reloads when the server changes. */
    data class Start(val serverId: String?, val role: String?) : ServerPublicVisibilityIntent

    /** Main switch flipped. ON opens the confirm dialog; OFF applies directly. */
    data class VisibilityToggled(val checked: Boolean) : ServerPublicVisibilityIntent

    data object ConfirmMakePublic : ServerPublicVisibilityIntent

    data object DismissConfirm : ServerPublicVisibilityIntent

    data class GuestJoinToggled(val checked: Boolean) : ServerPublicVisibilityIntent

    data class SearchToggled(val checked: Boolean) : ServerPublicVisibilityIntent
}

internal class ServerPublicVisibilityStore(
    private val repository: ServerPublicVisibilityRepository,
    private val scope: CoroutineScope,
    /**
     * Refresh the server list after a successful visibility change so the owner's own
     * `publicPageLive` globe updates without waiting for the next refresh (web: loadServers fresh).
     */
    private val refreshServerList: suspend () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ServerPublicVisibilityState())
    val state: StateFlow<ServerPublicVisibilityState> = mutableState.asStateFlow()

    private var generation = 0L
    private var loadJob: Job? = null

    fun dispatch(intent: ServerPublicVisibilityIntent) {
        when (intent) {
            is ServerPublicVisibilityIntent.Start -> start(intent.serverId, intent.role)
            is ServerPublicVisibilityIntent.VisibilityToggled -> {
                val current = mutableState.value
                if (!current.visibilitySwitchEnabled) return
                if (intent.checked) {
                    if (!current.confirmOpen) mutableState.value = current.copy(confirmOpen = true)
                } else {
                    updateVisibility(false)
                }
            }
            ServerPublicVisibilityIntent.ConfirmMakePublic -> {
                val current = mutableState.value
                if (!current.confirmOpen || !current.visibilitySwitchEnabled) return
                updateVisibility(true)
            }
            ServerPublicVisibilityIntent.DismissConfirm -> {
                val current = mutableState.value
                if (current.confirmOpen && !current.saving) mutableState.value = current.copy(confirmOpen = false)
            }
            is ServerPublicVisibilityIntent.GuestJoinToggled -> {
                val current = mutableState.value
                if (!current.guestJoinApplicable || !current.subSwitchesEnabled) return
                if (current.guestJoinChecked == intent.checked) return
                updateGuestJoin(intent.checked)
            }
            is ServerPublicVisibilityIntent.SearchToggled -> {
                val current = mutableState.value
                if (!current.subSwitchesEnabled || current.searchChecked == intent.checked) return
                updateSearch(intent.checked)
            }
        }
    }

    private fun start(serverId: String?, role: String?) {
        val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        val current = mutableState.value
        if (normalizedServerId == null || role != "owner") {
            if (current != ServerPublicVisibilityState()) {
                generation += 1L
                loadJob?.cancel()
                mutableState.value = ServerPublicVisibilityState()
            }
            return
        }
        if (current.serverId == normalizedServerId && current.availability != ServerPublicVisibilityAvailability.Hidden) {
            return
        }
        generation += 1L
        val requestGeneration = generation
        loadJob?.cancel()
        // web `load`: readback cleared, loading, error cleared, confirm closed.
        mutableState.value =
            ServerPublicVisibilityState(
                serverId = normalizedServerId,
                availability = ServerPublicVisibilityAvailability.Loading,
            )
        loadJob =
            scope.launch {
                val result =
                    runCatchingPreservingCancellation { repository.load(ServerId(normalizedServerId)) }
                        .getOrElse { ServerPublicVisibilityLoadResult.Failed(serverMessage = null) }
                if (requestGeneration != generation) return@launch
                mutableState.value =
                    when (result) {
                        is ServerPublicVisibilityLoadResult.Loaded ->
                            mutableState.value.copy(
                                availability = ServerPublicVisibilityAvailability.Available,
                                readback = result.readback,
                                guestJoinApplicable = result.guestJoinApplicable,
                            )
                        ServerPublicVisibilityLoadResult.Unavailable ->
                            mutableState.value.copy(availability = ServerPublicVisibilityAvailability.Unavailable)
                        is ServerPublicVisibilityLoadResult.Failed ->
                            mutableState.value.copy(
                                availability = ServerPublicVisibilityAvailability.LoadFailed,
                                error = ServerPublicVisibilityError(ServerPublicVisibilityErrorKind.Load, result.serverMessage),
                            )
                    }
            }
    }

    private fun updateVisibility(publiclyVisible: Boolean) {
        launchMutation(
            errorKind = ServerPublicVisibilityErrorKind.Update,
            request = { serverId -> repository.setPubliclyVisible(serverId, publiclyVisible) },
            onSuccess = { state, update ->
                state.copy(
                    confirmOpen = false,
                    readback =
                        state.readback?.copy(
                            publiclyVisible = update.publiclyVisible,
                            publicGuestJoinEnabled = update.publicGuestJoinEnabled,
                        ),
                )
            },
            afterSuccess = { runCatchingPreservingCancellation { refreshServerList() } },
        )
    }

    private fun updateGuestJoin(enabled: Boolean) {
        launchMutation(
            errorKind = ServerPublicVisibilityErrorKind.GuestJoinUpdate,
            request = { serverId -> repository.setPublicGuestJoinEnabled(serverId, enabled) },
            onSuccess = { state, value -> state.copy(readback = state.readback?.copy(publicGuestJoinEnabled = value)) },
        )
    }

    private fun updateSearch(enabled: Boolean) {
        launchMutation(
            errorKind = ServerPublicVisibilityErrorKind.SearchUpdate,
            request = { serverId -> repository.setPublicSearchEnabled(serverId, enabled) },
            onSuccess = { state, value -> state.copy(readback = state.readback?.copy(publicSearchEnabled = value)) },
        )
    }

    private fun <T> launchMutation(
        errorKind: ServerPublicVisibilityErrorKind,
        request: suspend (ServerId) -> ServerPublicMutationResult<T>,
        onSuccess: (ServerPublicVisibilityState, T) -> ServerPublicVisibilityState,
        afterSuccess: suspend () -> Unit = {},
    ) {
        val current = mutableState.value
        val serverId = current.serverId ?: return
        if (current.saving) return
        val requestGeneration = generation
        // web `saving`: saving=true, error cleared.
        mutableState.value = current.copy(saving = true, error = null)
        scope.launch {
            val result =
                runCatchingPreservingCancellation { request(ServerId(serverId)) }
                    .getOrElse { ServerPublicMutationResult.Failed(code = 0, serverMessage = null) }
            // A server switch mid-request drops the late answer (web checks current server id).
            if (requestGeneration != generation) return@launch
            when (result) {
                is ServerPublicMutationResult.Success -> {
                    mutableState.value = onSuccess(mutableState.value.copy(saving = false), result.value)
                    afterSuccess()
                }
                is ServerPublicMutationResult.Failed ->
                    mutableState.value =
                        mutableState.value.copy(
                            saving = false,
                            confirmOpen = false,
                            error = ServerPublicVisibilityError(errorKind, result.serverMessage),
                        )
            }
        }
    }
}
