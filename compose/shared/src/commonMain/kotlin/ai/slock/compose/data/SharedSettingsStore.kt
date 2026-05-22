package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SharedSettingsDestination {
    Home,
    Account,
    Computers,
    ComputerDetail,
    Server,
    Billing,
    Mobile,
    Storage,
    About,
    Diagnostics,
    Router
}

data class SharedSettingsRoute(
    val destination: SharedSettingsDestination = SharedSettingsDestination.Home,
    val itemId: String? = null
)

data class SharedSettingsState(
    val route: SharedSettingsRoute = SharedSettingsRoute(),
    val home: ScreenLoadState<SharedSettingsHomeSnapshot> = ScreenLoadState.Loading,
    val account: ScreenLoadState<AccountSettingsSnapshot> = ScreenLoadState.Loading,
    val computers: ScreenLoadState<SettingsComputersSnapshot> = ScreenLoadState.Loading,
    val computerDetail: ScreenLoadState<SettingsComputerDetailSnapshot> = ScreenLoadState.Loading,
    val server: ScreenLoadState<SettingsServerSnapshot> = ScreenLoadState.Loading,
    val billing: ScreenLoadState<SettingsBillingSnapshot> = ScreenLoadState.Loading,
    val mobile: ScreenLoadState<SettingsMobileSnapshot> = ScreenLoadState.Loading,
    val storage: ScreenLoadState<SharedSettingsStorageSnapshot> = ScreenLoadState.Loading,
    val about: ScreenLoadState<SharedSettingsAboutSnapshot> = ScreenLoadState.Loading,
    val diagnostics: ScreenLoadState<SharedSettingsDiagnosticsSnapshot> = ScreenLoadState.Loading,
    val refreshingDestinations: Set<SharedSettingsDestination> = emptySet(),
    val routeErrorCode: String? = null,
    val actionState: SharedSettingsActionState? = null,
    val effect: SharedSettingsEffect? = null
)

data class SharedSettingsHomeSnapshot(
    val title: String = "Settings",
    val subtitle: String = "",
    val items: List<SharedSettingsNavItem> = emptyList()
)

data class SharedSettingsNavItem(
    val destination: SharedSettingsDestination,
    val title: String,
    val subtitle: String,
    val iconName: String,
    val value: String? = null,
    val selected: Boolean = false,
    val productVisible: Boolean = true
)

data class SharedSettingsStorageSnapshot(
    val totalSizeLabel: String,
    val rows: List<SharedSettingsStorageRow>,
    val feedback: String? = null
)

data class SharedSettingsStorageRow(
    val target: String,
    val title: String,
    val detail: String,
    val sizeLabel: String,
    val clearEnabled: Boolean = false,
    val clearInProgress: Boolean = false
)

data class SharedSettingsAboutSnapshot(
    val appName: String = "Slock",
    val versionLabel: String = "",
    val buildLabel: String = "",
    val packageName: String = "",
    val signedInUserLabel: String = "",
    val selectedServerLabel: String = "",
    val sessionStatusLabel: String = "",
    val socketStatusLabel: String = "",
    val notificationStatusLabel: String = "",
    val deviceLabel: String = "",
    val platformLabel: String = ""
)

data class SharedSettingsDiagnosticsSnapshot(
    val entries: List<SharedSettingsDiagnosticsEntry> = emptyList()
)

data class SharedSettingsDiagnosticsEntry(
    val timestampLabel: String,
    val tag: String,
    val message: String
)

data class SharedSettingsActionState(
    val action: String,
    val itemId: String? = null,
    val inProgress: Boolean = false,
    val errorCode: String? = null
)

sealed interface SharedSettingsIntent {
    data object Start : SharedSettingsIntent
    data class OpenDestination(val destination: SharedSettingsDestination) : SharedSettingsIntent
    data class OpenComputer(val machineId: String) : SharedSettingsIntent
    data object Back : SharedSettingsIntent
    data object Refresh : SharedSettingsIntent
    data object Logout : SharedSettingsIntent
    data object RequestNotificationPermission : SharedSettingsIntent
    data object OpenNotificationSettings : SharedSettingsIntent
    data object SendTestNotification : SharedSettingsIntent
    data class SetLocale(val localeTag: String) : SharedSettingsIntent
    data class SetMessageAlignment(val alignment: String) : SharedSettingsIntent
    data object RefreshStorage : SharedSettingsIntent
    data class ClearStorage(val target: String) : SharedSettingsIntent
    data object CopyDiagnostics : SharedSettingsIntent
    data object ShareDiagnostics : SharedSettingsIntent
    data object ClearDiagnostics : SharedSettingsIntent
    data object SendFeedback : SharedSettingsIntent
    data object ConsumeEffect : SharedSettingsIntent
}

sealed interface SharedSettingsEffect {
    data object Logout : SharedSettingsEffect
    data object RequestNotificationPermission : SharedSettingsEffect
    data object OpenNotificationSettings : SharedSettingsEffect
    data object SendTestNotification : SharedSettingsEffect
    data class SetLocale(val localeTag: String) : SharedSettingsEffect
    data class SetMessageAlignment(val alignment: String) : SharedSettingsEffect
    data class ClearStorage(val target: String) : SharedSettingsEffect
    data object CopyDiagnostics : SharedSettingsEffect
    data object ShareDiagnostics : SharedSettingsEffect
    data object ClearDiagnostics : SharedSettingsEffect
    data object SendFeedback : SharedSettingsEffect
}

interface SharedSettingsPlatformCapabilities {
    suspend fun loadStorage(): DataResult<SharedSettingsStorageSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadAbout(): DataResult<SharedSettingsAboutSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadDiagnostics(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)
}

class SharedSettingsDataSource(
    private val sessionStore: SlockAuthSessionStore,
    private val settingsRepository: SettingsRepository? = null,
    private val platformCapabilities: SharedSettingsPlatformCapabilities = object : SharedSettingsPlatformCapabilities {}
) {
    suspend fun loadHome(selectedDestination: SharedSettingsDestination): DataResult<SharedSettingsHomeSnapshot> {
        val session = sessionStore.readSession()
        return DataResult.Content(session.toSharedSettingsHomeSnapshot(selectedDestination))
    }

    suspend fun loadAccount(): DataResult<AccountSettingsSnapshot> =
        settingsRepository?.getAccount()
            ?: sessionStore.readSession()?.toAccountSettingsSnapshot()?.let { DataResult.Content(it) }
            ?: DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))

    suspend fun loadComputers(): DataResult<SettingsComputersSnapshot> =
        settingsRepository?.getComputers() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadComputerDetail(machineId: String): DataResult<SettingsComputerDetailSnapshot> =
        settingsRepository?.getComputerDetail(machineId) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadServer(): DataResult<SettingsServerSnapshot> =
        settingsRepository?.getServerSettings() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadBilling(): DataResult<SettingsBillingSnapshot> =
        settingsRepository?.getBilling() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadMobile(): DataResult<SettingsMobileSnapshot> =
        settingsRepository?.getMobileSettings() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadStorage(): DataResult<SharedSettingsStorageSnapshot> =
        platformCapabilities.loadStorage()

    suspend fun loadAbout(): DataResult<SharedSettingsAboutSnapshot> =
        platformCapabilities.loadAbout()

    suspend fun loadDiagnostics(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        platformCapabilities.loadDiagnostics()
}

class SharedSettingsViewModel(
    dataSource: SharedSettingsDataSource
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedSettingsStore(dataSource = dataSource, scope = viewModelScope)
        }
    private val store: SharedSettingsStore by storeDelegate
    val state: StateFlow<SharedSettingsState>
        get() = store.state

    fun dispatch(intent: SharedSettingsIntent) {
        store.dispatch(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) {
            store.clear()
        }
        super.onCleared()
    }
}

class SharedSettingsStore(
    private val dataSource: SharedSettingsDataSource,
    private val scope: CoroutineScope,
    initialState: SharedSettingsState = SharedSettingsState()
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<SharedSettingsState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun dispatch(intent: SharedSettingsIntent) {
        if (intent.loadsSettingsContent) {
            loadJob?.cancel()
            loadJob =
                scope.launch {
                    accept(intent)
                }
        } else {
            scope.launch {
                accept(intent)
            }
        }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
    }

    suspend fun accept(intent: SharedSettingsIntent) {
        when (intent) {
            SharedSettingsIntent.Start -> loadHome()
            is SharedSettingsIntent.OpenDestination -> {
                _state.value = _state.value.copy(route = SharedSettingsRoute(intent.destination), routeErrorCode = null)
                loadCurrentRoute()
            }
            is SharedSettingsIntent.OpenComputer -> {
                _state.value =
                    _state.value.copy(
                        route = SharedSettingsRoute(SharedSettingsDestination.ComputerDetail, intent.machineId),
                        routeErrorCode = null
                    )
                loadComputerDetail(intent.machineId)
            }
            SharedSettingsIntent.Back -> {
                _state.value =
                    _state.value.copy(
                        route = SharedSettingsRoute(SharedSettingsDestination.Home),
                        routeErrorCode = null,
                        actionState = null
                    )
                loadHome()
            }
            SharedSettingsIntent.Refresh -> loadCurrentRoute()
            SharedSettingsIntent.Logout ->
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState("logout", inProgress = true),
                        effect = SharedSettingsEffect.Logout
                    )
            SharedSettingsIntent.RequestNotificationPermission ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.RequestNotificationPermission)
            SharedSettingsIntent.OpenNotificationSettings ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.OpenNotificationSettings)
            SharedSettingsIntent.SendTestNotification ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.SendTestNotification)
            is SharedSettingsIntent.SetLocale ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.SetLocale(intent.localeTag))
            is SharedSettingsIntent.SetMessageAlignment ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.SetMessageAlignment(intent.alignment))
            SharedSettingsIntent.RefreshStorage -> {
                _state.value = _state.value.copy(route = SharedSettingsRoute(SharedSettingsDestination.Storage))
                loadStorage()
            }
            is SharedSettingsIntent.ClearStorage ->
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState("clearStorage", itemId = intent.target, inProgress = true),
                        effect = SharedSettingsEffect.ClearStorage(intent.target)
                    )
            SharedSettingsIntent.CopyDiagnostics ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.CopyDiagnostics)
            SharedSettingsIntent.ShareDiagnostics ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.ShareDiagnostics)
            SharedSettingsIntent.ClearDiagnostics ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.ClearDiagnostics)
            SharedSettingsIntent.SendFeedback ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.SendFeedback)
            SharedSettingsIntent.ConsumeEffect ->
                _state.value = _state.value.copy(effect = null)
        }
    }

    private suspend fun loadHome() {
        val destination = _state.value.route.destination
        dataSource.loadHome(destination).applyToState(SharedSettingsDestination.Home) { copy(home = it) }
    }

    private suspend fun loadCurrentRoute() {
        when (val destination = _state.value.route.destination) {
            SharedSettingsDestination.Home -> loadHome()
            SharedSettingsDestination.Account ->
                dataSource.loadAccount().applyToState(destination) { copy(account = it) }
            SharedSettingsDestination.Computers ->
                dataSource.loadComputers().applyToState(destination) { copy(computers = it) }
            SharedSettingsDestination.ComputerDetail ->
                loadComputerDetail(_state.value.route.itemId.orEmpty())
            SharedSettingsDestination.Server ->
                dataSource.loadServer().applyToState(destination) { copy(server = it) }
            SharedSettingsDestination.Billing ->
                dataSource.loadBilling().applyToState(destination) { copy(billing = it) }
            SharedSettingsDestination.Mobile ->
                dataSource.loadMobile().applyToState(destination) { copy(mobile = it) }
            SharedSettingsDestination.Storage -> loadStorage()
            SharedSettingsDestination.About ->
                dataSource.loadAbout().applyToState(destination) { copy(about = it) }
            SharedSettingsDestination.Diagnostics ->
                dataSource.loadDiagnostics().applyToState(destination) { copy(diagnostics = it) }
            SharedSettingsDestination.Router -> loadHome()
        }
    }

    private suspend fun loadComputerDetail(machineId: String) {
        if (machineId.isBlank()) {
            _state.value =
                _state.value.copy(
                    computerDetail = ScreenLoadState.Error(ScreenError("settings_computer_required", retryable = false)),
                    routeErrorCode = "settings_computer_required"
                )
            return
        }
        dataSource.loadComputerDetail(machineId).applyToState(SharedSettingsDestination.ComputerDetail) {
            copy(computerDetail = it)
        }
    }

    private suspend fun loadStorage() {
        dataSource.loadStorage().applyToState(SharedSettingsDestination.Storage) { copy(storage = it) }
    }

    private suspend fun <T> DataResult<T>.applyToState(
        destination: SharedSettingsDestination,
        update: SharedSettingsState.(ScreenLoadState<T>) -> SharedSettingsState
    ) {
        _state.value = _state.value.markRefreshing(destination)
        try {
            val nextState = toScreenLoadState()
            _state.value =
                when (nextState) {
                    is ScreenLoadState.Content -> _state.value.update(nextState).finishRefreshing(destination)
                    is ScreenLoadState.Empty,
                    is ScreenLoadState.Error,
                    is ScreenLoadState.NoPermission ->
                        if (_state.value.hasContent(destination)) {
                            _state.value.finishRefreshing(destination).copy(routeErrorCode = nextState.routeErrorCode())
                        } else {
                            _state.value.update(nextState).finishRefreshing(destination).copy(routeErrorCode = nextState.routeErrorCode())
                        }
                    ScreenLoadState.Loading -> _state.value.update(nextState)
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            val error = ScreenLoadState.Error(ScreenError(code = "settings_network", retryable = true))
            _state.value =
                if (_state.value.hasContent(destination)) {
                    _state.value.finishRefreshing(destination).copy(routeErrorCode = "settings_network")
                } else {
                    _state.value.update(error).finishRefreshing(destination).copy(routeErrorCode = "settings_network")
                }
        }
    }
}

private val SharedSettingsIntent.loadsSettingsContent: Boolean
    get() =
        when (this) {
            SharedSettingsIntent.Start,
            is SharedSettingsIntent.OpenDestination,
            is SharedSettingsIntent.OpenComputer,
            SharedSettingsIntent.Back,
            SharedSettingsIntent.Refresh,
            SharedSettingsIntent.RefreshStorage -> true
            SharedSettingsIntent.Logout,
            SharedSettingsIntent.RequestNotificationPermission,
            SharedSettingsIntent.OpenNotificationSettings,
            SharedSettingsIntent.SendTestNotification,
            is SharedSettingsIntent.SetLocale,
            is SharedSettingsIntent.SetMessageAlignment,
            is SharedSettingsIntent.ClearStorage,
            SharedSettingsIntent.CopyDiagnostics,
            SharedSettingsIntent.ShareDiagnostics,
            SharedSettingsIntent.ClearDiagnostics,
            SharedSettingsIntent.SendFeedback,
            SharedSettingsIntent.ConsumeEffect -> false
        }

private fun <T> DataResult<T>.toScreenLoadState(): ScreenLoadState<T> =
    when (this) {
        is DataResult.Content -> ScreenLoadState.Content(data)
        is DataResult.Empty -> ScreenLoadState.Empty(reason)
        is DataResult.Error -> ScreenLoadState.Error(error)
        is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
    }

private fun ScreenLoadState<*>.routeErrorCode(): String? =
    when (this) {
        is ScreenLoadState.Empty -> reason.name
        is ScreenLoadState.Error -> error.code
        is ScreenLoadState.NoPermission -> blocker.code
        is ScreenLoadState.Content,
        ScreenLoadState.Loading -> null
    }

private fun SharedSettingsState.markRefreshing(destination: SharedSettingsDestination): SharedSettingsState =
    if (hasContent(destination)) {
        copy(refreshingDestinations = refreshingDestinations + destination, routeErrorCode = null)
    } else {
        copy(refreshingDestinations = refreshingDestinations - destination, routeErrorCode = null)
    }

private fun SharedSettingsState.finishRefreshing(destination: SharedSettingsDestination): SharedSettingsState =
    copy(refreshingDestinations = refreshingDestinations - destination)

fun SharedSettingsState.isRefreshing(destination: SharedSettingsDestination): Boolean =
    destination in refreshingDestinations

private fun SharedSettingsState.hasContent(destination: SharedSettingsDestination): Boolean =
    when (destination) {
        SharedSettingsDestination.Home -> home is ScreenLoadState.Content
        SharedSettingsDestination.Account -> account is ScreenLoadState.Content
        SharedSettingsDestination.Computers -> computers is ScreenLoadState.Content
        SharedSettingsDestination.ComputerDetail -> computerDetail is ScreenLoadState.Content
        SharedSettingsDestination.Server -> server is ScreenLoadState.Content
        SharedSettingsDestination.Billing -> billing is ScreenLoadState.Content
        SharedSettingsDestination.Mobile -> mobile is ScreenLoadState.Content
        SharedSettingsDestination.Storage -> storage is ScreenLoadState.Content
        SharedSettingsDestination.About -> about is ScreenLoadState.Content
        SharedSettingsDestination.Diagnostics -> diagnostics is ScreenLoadState.Content
        SharedSettingsDestination.Router -> home is ScreenLoadState.Content
    }

private fun StoredAuthSession?.toSharedSettingsHomeSnapshot(
    selectedDestination: SharedSettingsDestination
): SharedSettingsHomeSnapshot {
    val server = this?.currentServer()
    return SharedSettingsHomeSnapshot(
        subtitle = server?.name ?: "Shared settings",
        items =
            listOf(
                nav(SharedSettingsDestination.Account, "Account", "Profile, email, password, connected accounts", "user", this?.user?.bestDisplayName, selectedDestination),
                nav(SharedSettingsDestination.Computers, "Computers", "Connected machines and workspaces", "monitor", null, selectedDestination),
                nav(SharedSettingsDestination.Server, "Server", "Workspace profile, admins, invites, onboarding", "users", server?.role?.titleLabel(), selectedDestination),
                nav(SharedSettingsDestination.Billing, "Billing", "Plan and usage", "shield-check", server?.plan?.titleLabel(), selectedDestination),
                nav(SharedSettingsDestination.Mobile, "Mobile", "Push notifications, language, layout", "smartphone", null, selectedDestination),
                nav(SharedSettingsDestination.Storage, "Storage", "Local cache and diagnostics", "archive", null, selectedDestination),
                nav(SharedSettingsDestination.About, "About", "Version, diagnostics, feedback", "bug", null, selectedDestination)
            )
    )
}

private fun nav(
    destination: SharedSettingsDestination,
    title: String,
    subtitle: String,
    iconName: String,
    value: String?,
    selectedDestination: SharedSettingsDestination
): SharedSettingsNavItem =
    SharedSettingsNavItem(
        destination = destination,
        title = title,
        subtitle = subtitle,
        iconName = iconName,
        value = value,
        selected = selectedDestination == destination
    )

private fun StoredAuthSession.toAccountSettingsSnapshot(): AccountSettingsSnapshot =
    AccountSettingsSnapshot(
        profile =
            AccountProfileSnapshot(
                userId = user.id,
                displayName = user.bestDisplayName,
                handle = user.email,
                email = user.email,
                avatarLabel = user.bestDisplayName.take(2).uppercase(),
                verified = user.emailVerified == true,
                roleLabel = currentServer()?.role
            ),
        connectedAccounts = emptyList(),
        canEditProfile = false
    )

private fun String.titleLabel(): String =
    replaceFirstChar { it.uppercase() }

private fun StoredAuthSession.currentServer(): StoredAuthServer? =
    currentServerId?.let { serverId -> servers.firstOrNull { it.id == serverId } }
        ?: servers.firstOrNull()
