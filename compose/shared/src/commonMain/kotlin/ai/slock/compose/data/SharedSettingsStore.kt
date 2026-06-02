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
    val entries: List<SharedSettingsDiagnosticsEntry> = emptyList(),
    val profiler: SharedSettingsRecompositionProfilerSnapshot = SharedSettingsRecompositionProfilerSnapshot()
)

data class SharedSettingsDiagnosticsEntry(
    val timestampLabel: String,
    val tag: String,
    val message: String
)

data class SharedSettingsRecompositionProfilerSnapshot(
    val supported: Boolean = false,
    val gateLabel: String = "Unavailable",
    val statusLabel: String = "Unavailable",
    val detail: String = "Recomposition profiler is unavailable on this host.",
    val actionFeedback: String? = null,
    val defaults: SharedSettingsRecompositionProfilerDefaults = SharedSettingsRecompositionProfilerDefaults(),
    val report: SharedSettingsRecompositionProfilerReport = SharedSettingsRecompositionProfilerReport(),
    val files: SharedSettingsRecompositionProfilerFiles = SharedSettingsRecompositionProfilerFiles(),
    val analyzerHint: String = "",
    val extensionNote: String = ""
)

data class SharedSettingsRecompositionProfilerDefaults(
    val enableFile: Boolean = false,
    val enableLog: Boolean = false,
    val enableOverlay: Boolean = false,
    val sampleRateLabel: String = "",
    val hotspotThreshold: Int = 0,
    val maxEventBufferSize: Int = 0,
    val overlayGuide: String = "",
    val filterStrategy: String = "",
    val resetStrategy: String = ""
)

data class SharedSettingsRecompositionProfilerReport(
    val sessionId: String = "",
    val durationLabel: String = "",
    val totalFrames: Long = 0L,
    val totalRecompositions: Int = 0,
    val hotspotCount: Int = 0,
    val stateChangeCount: Int = 0,
    val generatedLabel: String = "",
    val reportAvailable: Boolean = false,
    val topHotspots: List<SharedSettingsRecompositionProfilerHotspot> = emptyList()
)

data class SharedSettingsRecompositionProfilerHotspot(
    val name: String,
    val sourceLocation: String? = null,
    val recompositionCount: Int = 0,
    val avgDurationLabel: String = ""
)

data class SharedSettingsRecompositionProfilerFiles(
    val reportFileName: String = "",
    val framesFileName: String = "",
    val locationHint: String = ""
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
    data class UpdateProfile(val request: AccountProfileUpdateRequest) : SharedSettingsIntent
    data class UpdateServerProfile(val request: SettingsServerProfileUpdateRequest) : SharedSettingsIntent
    data class CreateServerInvite(val request: SettingsServerInviteCreateRequest) : SharedSettingsIntent
    data class RevokeServerInvite(val inviteId: String) : SharedSettingsIntent
    data class CreateServerJoinLink(val request: SettingsServerJoinLinkCreateRequest = SettingsServerJoinLinkCreateRequest()) : SharedSettingsIntent
    data class RevokeServerJoinLink(val linkId: String) : SharedSettingsIntent
    data class UpdateServerOnboarding(val request: SettingsServerOnboardingUpdateRequest) : SharedSettingsIntent
    data class UpdateServerTranslation(val request: SettingsServerTranslationUpdateRequest) : SharedSettingsIntent
    data class UpdateServerSidebarOrder(val request: SettingsServerSidebarOrderUpdateRequest) : SharedSettingsIntent
    data class UpdateServerMemberRole(val request: SettingsServerMemberRoleUpdateRequest) : SharedSettingsIntent
    data class RemoveServerMember(val memberId: String) : SharedSettingsIntent
    data object LeaveServer : SharedSettingsIntent
    data class DeleteServer(val request: SettingsServerDeleteRequest) : SharedSettingsIntent
    data class RequestServerConfirmation(val action: String, val itemId: String? = null) : SharedSettingsIntent
    data class SetNotificationPreference(val enabled: Boolean) : SharedSettingsIntent
    data class SetServerMute(val enabled: Boolean) : SharedSettingsIntent
    data class CompleteSystemNotificationAction(
        val action: String,
        val result: SystemNotificationActionResult
    ) : SharedSettingsIntent
    data class SetLocale(val localeTag: String) : SharedSettingsIntent
    data class SetMessageAlignment(val alignment: String) : SharedSettingsIntent
    data object RefreshStorage : SharedSettingsIntent
    data class ClearStorage(val target: String) : SharedSettingsIntent
    data class CompleteStorageClear(
        val target: String,
        val ok: Boolean,
        val code: String? = null,
        val snapshot: SharedSettingsStorageSnapshot? = null
    ) : SharedSettingsIntent
    data object CopyDiagnostics : SharedSettingsIntent
    data object ShareDiagnostics : SharedSettingsIntent
    data object ClearDiagnostics : SharedSettingsIntent
    data object StartRecompositionProfiler : SharedSettingsIntent
    data object StopRecompositionProfiler : SharedSettingsIntent
    data object ResetRecompositionProfiler : SharedSettingsIntent
    data object RefreshRecompositionProfilerReport : SharedSettingsIntent
    data object ExportRecompositionProfilerReport : SharedSettingsIntent
    data object SendFeedback : SharedSettingsIntent
    data class RunBuglyCrashMonitorAction(val action: String) : SharedSettingsIntent
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
    data class RunBuglyCrashMonitorAction(val action: String) : SharedSettingsEffect
}

interface SharedSettingsPlatformCapabilities {
    suspend fun loadStorage(): DataResult<SharedSettingsStorageSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun clearStorage(target: String): DataResult<SharedSettingsStorageSnapshot> =
        DataResult.Error(ScreenError("settings_storage_clear_unavailable", retryable = false))

    suspend fun loadAbout(): DataResult<SharedSettingsAboutSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadDiagnosticsHost(): DataResult<SharedSettingsDiagnosticsHostSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)
}

class SharedSettingsDataSource(
    private val sessionStore: SlockAuthSessionStore,
    private val settingsRepository: SettingsRepository? = null,
    private val notificationPreferenceRepository: NotificationPreferenceRepository? = null,
    private val localeRepository: LocaleRepository? = null,
    private val chatLayoutPreferenceRepository: ChatLayoutPreferenceRepository? = null,
    private val platformCapabilities: SharedSettingsPlatformCapabilities = object : SharedSettingsPlatformCapabilities {},
    private val recompositionProfilerController: SharedSettingsRecompositionProfilerController =
        SharedSettingsRecompositionProfilerController()
) {
    suspend fun loadHome(selectedDestination: SharedSettingsDestination): DataResult<SharedSettingsHomeSnapshot> {
        val session = sessionStore.readSession()
        return DataResult.Content(session.toSharedSettingsHomeSnapshot(selectedDestination))
    }

    suspend fun loadAccount(): DataResult<AccountSettingsSnapshot> =
        settingsRepository?.getAccount()
            ?: sessionStore.readSession()?.toAccountSettingsSnapshot()?.let { DataResult.Content(it) }
            ?: DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))

    suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot> =
        settingsRepository?.updateProfile(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadComputers(): DataResult<SettingsComputersSnapshot> =
        settingsRepository?.getComputers() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadComputerDetail(machineId: String): DataResult<SettingsComputerDetailSnapshot> =
        settingsRepository?.getComputerDetail(machineId) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadServer(): DataResult<SettingsServerSnapshot> =
        settingsRepository?.getServerSettings() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun updateServerProfile(request: SettingsServerProfileUpdateRequest): DataResult<SettingsServerSnapshot> =
        settingsRepository?.updateServerProfile(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun createServerInvite(request: SettingsServerInviteCreateRequest): DataResult<SettingsServerActionResult> =
        settingsRepository?.createServerInvite(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun revokeServerInvite(inviteId: String): DataResult<SettingsServerActionResult> =
        (settingsRepository as? SettingsServerActionRepository)?.revokeServerInvite(inviteId)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun createServerJoinLink(request: SettingsServerJoinLinkCreateRequest): DataResult<SettingsServerJoinLinkSnapshot> =
        (settingsRepository as? SettingsServerActionRepository)?.createServerJoinLink(request)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun revokeServerJoinLink(linkId: String): DataResult<SettingsServerActionResult> =
        (settingsRepository as? SettingsServerActionRepository)?.revokeServerJoinLink(linkId)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun deleteServer(request: SettingsServerDeleteRequest): DataResult<SettingsServerActionResult> =
        settingsRepository?.deleteServer(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun updateServerOnboarding(
        request: SettingsServerOnboardingUpdateRequest
    ): DataResult<SettingsServerOnboardingSnapshot> =
        (settingsRepository as? SettingsServerManagementRepository)?.updateServerOnboarding(request)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun updateServerTranslation(
        request: SettingsServerTranslationUpdateRequest
    ): DataResult<SettingsServerTranslationSnapshot> =
        (settingsRepository as? SettingsServerManagementRepository)?.updateServerTranslationSettings(request)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun updateServerSidebarOrder(
        request: SettingsServerSidebarOrderUpdateRequest
    ): DataResult<SettingsServerSidebarOrderSnapshot> =
        (settingsRepository as? SettingsServerManagementRepository)?.updateServerSidebarOrder(request)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun updateServerMemberRole(
        request: SettingsServerMemberRoleUpdateRequest
    ): DataResult<SettingsServerActionResult> =
        (settingsRepository as? SettingsServerManagementRepository)?.updateServerMemberRole(request)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun removeServerMember(memberId: String): DataResult<SettingsServerActionResult> =
        (settingsRepository as? SettingsServerManagementRepository)?.removeServerMember(memberId)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun leaveServer(): DataResult<SettingsServerActionResult> =
        (settingsRepository as? SettingsServerManagementRepository)?.leaveServer()
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadBilling(): DataResult<SettingsBillingSnapshot> =
        settingsRepository?.getBilling() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadMobile(): DataResult<SettingsMobileSnapshot> =
        when (val result = settingsRepository?.getMobileSettings() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)) {
            is DataResult.Content -> DataResult.Content(result.data.withLocalMobilePreferences())
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }

    suspend fun setNotificationPreference(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            notificationPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_notification_preference_unavailable", retryable = false))
        repository.setNotificationEnabled(enabled)
        return loadMobile()
    }

    suspend fun setServerMute(enabled: Boolean): DataResult<SettingsMobileSnapshot> =
        when (val result =
            (settingsRepository as? SettingsServerActionRepository)?.setServerMute(enabled)
                ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)) {
            is DataResult.Content -> DataResult.Content(result.data.withLocalMobilePreferences())
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }

    suspend fun setLocale(localeTag: String): DataResult<SettingsMobileSnapshot> {
        val repository =
            localeRepository
                ?: return DataResult.Error(ScreenError("settings_locale_unavailable", retryable = false))
        val locale =
            AppLocale.entries.firstOrNull { it.languageTag == localeTag || it.storageKey == localeTag }
                ?: return DataResult.Error(ScreenError("settings_locale_unknown", retryable = false))
        repository.selectLocale(locale)
        return loadMobile()
    }

    suspend fun setMessageAlignment(alignmentKey: String): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_message_alignment_unavailable", retryable = false))
        val alignment =
            MyMessageAlignmentPreference.entries.firstOrNull {
                it.storageKey == alignmentKey || it.name.equals(alignmentKey, ignoreCase = true)
            } ?: return DataResult.Error(ScreenError("settings_message_alignment_unknown", retryable = false))
        repository.selectMyMessageAlignment(alignment)
        return loadMobile()
    }

    suspend fun loadStorage(): DataResult<SharedSettingsStorageSnapshot> =
        platformCapabilities.loadStorage()

    suspend fun clearStorage(target: String): DataResult<SharedSettingsStorageSnapshot> =
        platformCapabilities.clearStorage(target)

    suspend fun loadAbout(): DataResult<SharedSettingsAboutSnapshot> =
        platformCapabilities.loadAbout()

    suspend fun loadDiagnostics(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        loadDiagnosticsSnapshot()

    suspend fun startRecompositionProfiler(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        performProfilerAction {
            recompositionProfilerController.start()
        }

    suspend fun stopRecompositionProfiler(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        performProfilerAction {
            recompositionProfilerController.stop()
        }

    suspend fun resetRecompositionProfiler(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        performProfilerAction {
            recompositionProfilerController.reset()
        }

    suspend fun refreshRecompositionProfilerReport(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        performProfilerAction {
            recompositionProfilerController.refreshReport()
        }

    suspend fun exportRecompositionProfilerReport(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        performProfilerAction {
            recompositionProfilerController.exportReport()
        }

    private suspend fun SettingsMobileSnapshot.withLocalMobilePreferences(): SettingsMobileSnapshot {
        val notificationPreference = notificationPreferenceRepository?.getNotificationPreference()
        val locale = localeRepository?.getLocale()
        val chatLayout = chatLayoutPreferenceRepository?.getChatLayoutPreference()
        return copy(
            pushNotifications =
                pushNotifications.copy(
                    enabled = notificationPreference?.enabled ?: pushNotifications.enabled,
                    available = notificationPreferenceRepository != null && pushNotifications.available,
                    statusLabel =
                        when {
                            notificationPreferenceRepository == null -> "Unsupported"
                            notificationPreference?.enabled == true -> "Enabled"
                            else -> "Off"
                        },
                    detail =
                        if (notificationPreferenceRepository == null) {
                            "Local notification preference is not available on this platform."
                        } else {
                            pushNotifications.detail
                        }
                ),
            notificationPermission =
                notificationPermission.takeIf { it.available }
                    ?: SettingsMobileActionSnapshot(
                        available = true,
                        statusLabel = "Request",
                        detail = "Ask the platform for notification permission."
                    ),
            notificationSettings =
                notificationSettings.takeIf { it.available }
                    ?: SettingsMobileActionSnapshot(
                        available = true,
                        statusLabel = "Open",
                        detail = "Open system notification settings for this app."
                    ),
            testNotification =
                testNotification.copy(
                    available = sendTestEnabled,
                    statusLabel = if (sendTestEnabled) "Send" else "Disabled",
                    detail =
                        if (sendTestEnabled) {
                            "Post a local system notification to verify delivery."
                        } else {
                            testNotification.detail
                        }
                ),
            localePreference =
                locale?.toSettingsMobileSelectionSnapshot()
                    ?: localePreference.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Language preference is not available on this platform."
                    ),
            messageAlignmentPreference =
                chatLayout?.toSettingsMobileSelectionSnapshot()
                    ?: messageAlignmentPreference.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Message alignment preference is not available on this platform."
                    )
        )
    }

    private suspend fun loadDiagnosticsSnapshot(
        feedback: String? = null
    ): DataResult<SharedSettingsDiagnosticsSnapshot> {
        val entries =
            when (val result = platformCapabilities.loadDiagnosticsHost()) {
                is DataResult.Content -> result.data.entries
                is DataResult.Empty -> emptyList()
                is DataResult.Error -> emptyList()
                is DataResult.NoPermission -> emptyList()
            }
        return DataResult.Content(
            SharedSettingsDiagnosticsSnapshot(
                entries = entries,
                profiler = recompositionProfilerController.buildSnapshot(feedback = feedback)
            )
        )
    }

    private suspend fun performProfilerAction(
        action: () -> SharedSettingsRecompositionProfilerActionResult
    ): DataResult<SharedSettingsDiagnosticsSnapshot> {
        val actionResult = action()
        actionResult.errorCode?.let { code ->
            return DataResult.Error(ScreenError(code, retryable = false))
        }
        return loadDiagnosticsSnapshot(feedback = actionResult.feedback)
    }
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
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_PERMISSION, inProgress = true),
                        effect = SharedSettingsEffect.RequestNotificationPermission
                    )
            SharedSettingsIntent.OpenNotificationSettings ->
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_SETTINGS, inProgress = true),
                        effect = SharedSettingsEffect.OpenNotificationSettings
                    )
            SharedSettingsIntent.SendTestNotification ->
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_TEST_NOTIFICATION, inProgress = true),
                        effect = SharedSettingsEffect.SendTestNotification
                    )
            is SharedSettingsIntent.UpdateProfile -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState("saveProfile", inProgress = true)
                    )
                dataSource.updateProfile(intent.request).applyAccountActionResult("saveProfile")
            }
            is SharedSettingsIntent.UpdateServerProfile -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_PROFILE_SAVE, inProgress = true))
                dataSource.updateServerProfile(intent.request).applyServerSnapshotActionResult(SERVER_ACTION_PROFILE_SAVE)
            }
            is SharedSettingsIntent.CreateServerInvite -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_INVITE_CREATE, inProgress = true))
                dataSource.createServerInvite(intent.request).applyServerActionResult(SERVER_ACTION_INVITE_CREATE)
            }
            is SharedSettingsIntent.RevokeServerInvite -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_INVITE_REVOKE, intent.inviteId, inProgress = true))
                dataSource.revokeServerInvite(intent.inviteId).applyServerActionResult(SERVER_ACTION_INVITE_REVOKE, intent.inviteId)
            }
            is SharedSettingsIntent.CreateServerJoinLink -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_JOIN_LINK_CREATE, inProgress = true))
                dataSource.createServerJoinLink(intent.request).applyServerSnapshotReloadResult(SERVER_ACTION_JOIN_LINK_CREATE)
            }
            is SharedSettingsIntent.RevokeServerJoinLink -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_JOIN_LINK_REVOKE, intent.linkId, inProgress = true))
                dataSource.revokeServerJoinLink(intent.linkId).applyServerActionResult(SERVER_ACTION_JOIN_LINK_REVOKE, intent.linkId)
            }
            is SharedSettingsIntent.UpdateServerOnboarding -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_ONBOARDING_TOGGLE, inProgress = true))
                dataSource.updateServerOnboarding(intent.request).applyServerSnapshotReloadResult(SERVER_ACTION_ONBOARDING_TOGGLE)
            }
            is SharedSettingsIntent.UpdateServerTranslation -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_TRANSLATION_TOGGLE, inProgress = true))
                dataSource.updateServerTranslation(intent.request).applyServerSnapshotReloadResult(SERVER_ACTION_TRANSLATION_TOGGLE)
            }
            is SharedSettingsIntent.UpdateServerSidebarOrder -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_SIDEBAR_ORDER, inProgress = true))
                dataSource.updateServerSidebarOrder(intent.request).applyServerSnapshotReloadResult(SERVER_ACTION_SIDEBAR_ORDER)
            }
            is SharedSettingsIntent.UpdateServerMemberRole -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_MEMBER_ROLE, intent.request.memberId, inProgress = true))
                dataSource.updateServerMemberRole(intent.request).applyServerActionResult(SERVER_ACTION_MEMBER_ROLE, intent.request.memberId)
            }
            is SharedSettingsIntent.RemoveServerMember -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_MEMBER_REMOVE, intent.memberId, inProgress = true))
                dataSource.removeServerMember(intent.memberId).applyServerActionResult(SERVER_ACTION_MEMBER_REMOVE, intent.memberId)
            }
            SharedSettingsIntent.LeaveServer -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_LEAVE, inProgress = true))
                dataSource.leaveServer().applyServerActionResult(SERVER_ACTION_LEAVE)
            }
            is SharedSettingsIntent.DeleteServer -> {
                _state.value = _state.value.copy(actionState = SharedSettingsActionState(SERVER_ACTION_DELETE, inProgress = true))
                dataSource.deleteServer(intent.request).applyServerActionResult(SERVER_ACTION_DELETE)
            }
            is SharedSettingsIntent.RequestServerConfirmation ->
                _state.value =
                    _state.value.copy(
                        actionState =
                            SharedSettingsActionState(
                                SERVER_ACTION_CONFIRM,
                                itemId = serverConfirmationItemId(intent.action, intent.itemId),
                                inProgress = false
                            )
                    )
            is SharedSettingsIntent.CompleteSystemNotificationAction ->
                completeSystemNotificationAction(intent.action, intent.result)
            is SharedSettingsIntent.SetNotificationPreference -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_NOTIFICATION_PREFERENCE, inProgress = true)
                    )
                dataSource.setNotificationPreference(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_NOTIFICATION_PREFERENCE)
            }
            is SharedSettingsIntent.SetServerMute -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_SERVER_MUTE, inProgress = true)
                    )
                dataSource.setServerMute(intent.enabled).applyMobileActionResult(MOBILE_ACTION_SERVER_MUTE)
            }
            is SharedSettingsIntent.SetLocale -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_LANGUAGE, inProgress = true)
                    )
                dataSource.setLocale(intent.localeTag).applyMobileActionResult(MOBILE_ACTION_LANGUAGE)
            }
            is SharedSettingsIntent.SetMessageAlignment -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_MESSAGE_ALIGNMENT, inProgress = true)
                    )
                dataSource.setMessageAlignment(intent.alignment)
                    .applyMobileActionResult(MOBILE_ACTION_MESSAGE_ALIGNMENT)
            }
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
            is SharedSettingsIntent.CompleteStorageClear ->
                completeStorageClear(intent)
            SharedSettingsIntent.CopyDiagnostics ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.CopyDiagnostics)
            SharedSettingsIntent.ShareDiagnostics ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.ShareDiagnostics)
            SharedSettingsIntent.ClearDiagnostics ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.ClearDiagnostics)
            SharedSettingsIntent.StartRecompositionProfiler -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(DIAGNOSTICS_ACTION_PROFILER_START, inProgress = true)
                    )
                dataSource.startRecompositionProfiler().applyDiagnosticsActionResult(DIAGNOSTICS_ACTION_PROFILER_START)
            }
            SharedSettingsIntent.StopRecompositionProfiler -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(DIAGNOSTICS_ACTION_PROFILER_STOP, inProgress = true)
                    )
                dataSource.stopRecompositionProfiler().applyDiagnosticsActionResult(DIAGNOSTICS_ACTION_PROFILER_STOP)
            }
            SharedSettingsIntent.ResetRecompositionProfiler -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(DIAGNOSTICS_ACTION_PROFILER_RESET, inProgress = true)
                    )
                dataSource.resetRecompositionProfiler().applyDiagnosticsActionResult(DIAGNOSTICS_ACTION_PROFILER_RESET)
            }
            SharedSettingsIntent.RefreshRecompositionProfilerReport -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(DIAGNOSTICS_ACTION_PROFILER_REPORT, inProgress = true)
                    )
                dataSource.refreshRecompositionProfilerReport().applyDiagnosticsActionResult(DIAGNOSTICS_ACTION_PROFILER_REPORT)
            }
            SharedSettingsIntent.ExportRecompositionProfilerReport -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(DIAGNOSTICS_ACTION_PROFILER_EXPORT, inProgress = true)
                    )
                dataSource.exportRecompositionProfilerReport().applyDiagnosticsActionResult(DIAGNOSTICS_ACTION_PROFILER_EXPORT)
            }
            SharedSettingsIntent.SendFeedback ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.SendFeedback)
            is SharedSettingsIntent.RunBuglyCrashMonitorAction ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.RunBuglyCrashMonitorAction(intent.action))
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

    private fun completeStorageClear(intent: SharedSettingsIntent.CompleteStorageClear) {
        val currentStorage = _state.value.storage
        val updatedStorage =
            if (intent.snapshot != null) {
                ScreenLoadState.Content(
                    intent.snapshot.copy(
                        feedback =
                            intent.snapshot.feedback?.takeIf { it.isNotBlank() }
                                ?: if (intent.ok) {
                                    "Storage cleared."
                                } else {
                                    intent.code ?: "settings_storage_clear_failed"
                                }
                    )
                )
            } else if (currentStorage is ScreenLoadState.Content) {
                ScreenLoadState.Content(
                    currentStorage.data.copy(
                        feedback =
                            if (intent.ok) {
                                "Storage cleared."
                            } else {
                                intent.code ?: "settings_storage_clear_failed"
                            }
                    )
                )
            } else {
                currentStorage
            }
        _state.value =
            _state.value.copy(
                storage = updatedStorage,
                actionState =
                    SharedSettingsActionState(
                        action = "clearStorage",
                        itemId = intent.target,
                        inProgress = false,
                        errorCode = intent.code.takeUnless { intent.ok }
                    ),
                routeErrorCode = intent.code.takeUnless { intent.ok },
                effect = null
            )
    }

    private fun completeSystemNotificationAction(action: String, result: SystemNotificationActionResult) {
        val current = _state.value.mobile
        val updatedMobile =
            if (current is ScreenLoadState.Content) {
                ScreenLoadState.Content(current.data.withSystemNotificationResult(action, result))
            } else {
                current
            }
        _state.value =
            _state.value.copy(
                mobile = updatedMobile,
                actionState =
                    SharedSettingsActionState(
                        action = action,
                        inProgress = false,
                        errorCode = result.code.takeUnless { result.ok }
                    ),
                effect = null
            )
    }

    private fun DataResult<SettingsMobileSnapshot>.applyMobileActionResult(action: String) {
        val nextMobile = toScreenLoadState()
        _state.value =
            _state.value.copy(
                mobile =
                    when (nextMobile) {
                        is ScreenLoadState.Content -> nextMobile
                        else -> _state.value.mobile
                    },
                actionState =
                    SharedSettingsActionState(
                        action = action,
                        inProgress = false,
                        errorCode = nextMobile.routeErrorCode()
                    ),
                routeErrorCode = nextMobile.routeErrorCode()
            )
    }

    private fun DataResult<AccountSettingsSnapshot>.applyAccountActionResult(action: String) {
        val nextAccount = toScreenLoadState()
        _state.value =
            _state.value.copy(
                account =
                    when (nextAccount) {
                        is ScreenLoadState.Content -> nextAccount
                        else -> _state.value.account
                    },
                actionState =
                    SharedSettingsActionState(
                        action = action,
                        inProgress = false,
                        errorCode = nextAccount.routeErrorCode()
                    ),
                routeErrorCode = nextAccount.routeErrorCode()
            )
    }

    private fun DataResult<SettingsServerSnapshot>.applyServerSnapshotActionResult(action: String, itemId: String? = null) {
        val nextServer = toScreenLoadState()
        _state.value =
            _state.value.copy(
                server =
                    when (nextServer) {
                        is ScreenLoadState.Content -> nextServer
                        else -> _state.value.server
                    },
                actionState =
                    SharedSettingsActionState(
                        action = action,
                        itemId = itemId,
                        inProgress = false,
                        errorCode = nextServer.routeErrorCode()
                    ),
                routeErrorCode = nextServer.routeErrorCode()
            )
    }

    private suspend fun DataResult<SettingsServerActionResult>.applyServerActionResult(action: String, itemId: String? = null) {
        when (this) {
            is DataResult.Content ->
                dataSource.loadServer().applyServerSnapshotActionResult(action, itemId)
            is DataResult.Empty ->
                DataResult.Empty(reason).applyServerSnapshotActionResult(action, itemId)
            is DataResult.Error ->
                DataResult.Error(error).applyServerSnapshotActionResult(action, itemId)
            is DataResult.NoPermission ->
                DataResult.NoPermission(blocker).applyServerSnapshotActionResult(action, itemId)
        }
    }

    private suspend fun <T> DataResult<T>.applyServerSnapshotReloadResult(action: String, itemId: String? = null) {
        when (this) {
            is DataResult.Content ->
                dataSource.loadServer().applyServerSnapshotActionResult(action, itemId)
            is DataResult.Empty ->
                DataResult.Empty(reason).applyServerSnapshotActionResult(action, itemId)
            is DataResult.Error ->
                DataResult.Error(error).applyServerSnapshotActionResult(action, itemId)
            is DataResult.NoPermission ->
                DataResult.NoPermission(blocker).applyServerSnapshotActionResult(action, itemId)
        }
    }

    private fun DataResult<SharedSettingsDiagnosticsSnapshot>.applyDiagnosticsActionResult(action: String) {
        val nextDiagnostics = toScreenLoadState()
        _state.value =
            _state.value.copy(
                diagnostics =
                    when (nextDiagnostics) {
                        is ScreenLoadState.Content -> nextDiagnostics
                        else -> _state.value.diagnostics
                    },
                actionState =
                    SharedSettingsActionState(
                        action = action,
                        inProgress = false,
                        errorCode = nextDiagnostics.routeErrorCode()
                    ),
                routeErrorCode = nextDiagnostics.routeErrorCode(),
                effect = null
            )
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
            is SharedSettingsIntent.UpdateProfile,
            is SharedSettingsIntent.UpdateServerProfile,
            is SharedSettingsIntent.CreateServerInvite,
            is SharedSettingsIntent.RevokeServerInvite,
            is SharedSettingsIntent.CreateServerJoinLink,
            is SharedSettingsIntent.RevokeServerJoinLink,
            is SharedSettingsIntent.UpdateServerOnboarding,
            is SharedSettingsIntent.UpdateServerTranslation,
            is SharedSettingsIntent.UpdateServerSidebarOrder,
            is SharedSettingsIntent.UpdateServerMemberRole,
            is SharedSettingsIntent.RemoveServerMember,
            SharedSettingsIntent.LeaveServer,
            is SharedSettingsIntent.DeleteServer,
            is SharedSettingsIntent.RequestServerConfirmation,
            is SharedSettingsIntent.SetNotificationPreference,
            is SharedSettingsIntent.SetServerMute,
            is SharedSettingsIntent.CompleteSystemNotificationAction,
            is SharedSettingsIntent.SetLocale,
            is SharedSettingsIntent.SetMessageAlignment,
            is SharedSettingsIntent.ClearStorage,
            is SharedSettingsIntent.CompleteStorageClear,
            SharedSettingsIntent.CopyDiagnostics,
            SharedSettingsIntent.ShareDiagnostics,
            SharedSettingsIntent.ClearDiagnostics,
            SharedSettingsIntent.StartRecompositionProfiler,
            SharedSettingsIntent.StopRecompositionProfiler,
            SharedSettingsIntent.ResetRecompositionProfiler,
            SharedSettingsIntent.RefreshRecompositionProfilerReport,
            SharedSettingsIntent.ExportRecompositionProfilerReport,
            SharedSettingsIntent.SendFeedback,
            is SharedSettingsIntent.RunBuglyCrashMonitorAction,
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

fun serverConfirmationItemId(action: String, itemId: String? = null): String =
    if (itemId.isNullOrBlank()) action else "$action:$itemId"

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

internal const val MOBILE_ACTION_NOTIFICATION_PREFERENCE = "notificationPreference"
internal const val MOBILE_ACTION_SERVER_MUTE = "serverMute"
internal const val MOBILE_ACTION_PERMISSION = "notificationPermission"
internal const val MOBILE_ACTION_SETTINGS = "openNotificationSettings"
internal const val MOBILE_ACTION_TEST_NOTIFICATION = "sendTestNotification"
internal const val MOBILE_ACTION_LANGUAGE = "language"
internal const val MOBILE_ACTION_MESSAGE_ALIGNMENT = "messageAlignment"
internal const val SERVER_ACTION_PROFILE_SAVE = "serverProfileSave"
internal const val SERVER_ACTION_INVITE_CREATE = "serverInviteCreate"
internal const val SERVER_ACTION_INVITE_REVOKE = "serverInviteRevoke"
internal const val SERVER_ACTION_JOIN_LINK_CREATE = "serverJoinLinkCreate"
internal const val SERVER_ACTION_JOIN_LINK_REVOKE = "serverJoinLinkRevoke"
internal const val SERVER_ACTION_ONBOARDING_TOGGLE = "serverOnboardingToggle"
internal const val SERVER_ACTION_TRANSLATION_TOGGLE = "serverTranslationToggle"
internal const val SERVER_ACTION_SIDEBAR_ORDER = "serverSidebarOrder"
internal const val SERVER_ACTION_MEMBER_ROLE = "serverMemberRole"
internal const val SERVER_ACTION_MEMBER_REMOVE = "serverMemberRemove"
internal const val SERVER_ACTION_LEAVE = "serverLeave"
internal const val SERVER_ACTION_DELETE = "serverDelete"
internal const val SERVER_ACTION_CONFIRM = "serverConfirm"
internal const val DIAGNOSTICS_ACTION_PROFILER_START = "diagnosticsProfilerStart"
internal const val DIAGNOSTICS_ACTION_PROFILER_STOP = "diagnosticsProfilerStop"
internal const val DIAGNOSTICS_ACTION_PROFILER_RESET = "diagnosticsProfilerReset"
internal const val DIAGNOSTICS_ACTION_PROFILER_REPORT = "diagnosticsProfilerReport"
internal const val DIAGNOSTICS_ACTION_PROFILER_EXPORT = "diagnosticsProfilerExport"

private fun LocaleSnapshot.toSettingsMobileSelectionSnapshot(): SettingsMobileSelectionSnapshot =
    SettingsMobileSelectionSnapshot(
        selectedKey = current.languageTag,
        selectedLabel = current.mobileLabel(),
        statusLabel = current.mobileLabel(),
        detail = "Tap to switch language on this device.",
        options =
            supported.map {
                SettingsMobileSelectionOptionSnapshot(
                    key = it.languageTag,
                    label = it.mobileLabel()
                )
            }
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileSelectionSnapshot(): SettingsMobileSelectionSnapshot =
    SettingsMobileSelectionSnapshot(
        selectedKey = myMessageAlignment.storageKey,
        selectedLabel = myMessageAlignment.mobileLabel(),
        statusLabel = myMessageAlignment.mobileLabel(),
        detail = "Tap to switch how your own messages align in conversations.",
        options =
            supportedAlignments.map {
                SettingsMobileSelectionOptionSnapshot(
                    key = it.storageKey,
                    label = it.mobileLabel()
                )
            }
    )

private fun AppLocale.mobileLabel(): String =
    if (nativeLabel == englishLabel) {
        englishLabel
    } else {
        "$englishLabel / $nativeLabel"
    }

private fun MyMessageAlignmentPreference.mobileLabel(): String =
    when (this) {
        MyMessageAlignmentPreference.Left -> "Left"
        MyMessageAlignmentPreference.Right -> "Right"
    }

private fun SettingsMobileSnapshot.withSystemNotificationResult(
    action: String,
    result: SystemNotificationActionResult
): SettingsMobileSnapshot {
    val statusLabel = result.mobileStatusLabel(action)
    val detail = result.message.ifBlank { result.mobileDetail(action) }
    val available = result.status != SystemNotificationActionStatus.Unsupported
    val snapshot = SettingsMobileActionSnapshot(available = available, statusLabel = statusLabel, detail = detail)
    return when (action) {
        MOBILE_ACTION_PERMISSION -> copy(notificationPermission = snapshot)
        MOBILE_ACTION_SETTINGS -> copy(notificationSettings = snapshot)
        MOBILE_ACTION_TEST_NOTIFICATION -> copy(testNotification = snapshot)
        else -> this
    }
}

private fun SystemNotificationActionResult.mobileStatusLabel(action: String): String =
    when (status) {
        SystemNotificationActionStatus.Ok ->
            when (action) {
                MOBILE_ACTION_SETTINGS -> "Opened"
                MOBILE_ACTION_TEST_NOTIFICATION -> "Sent"
                else -> "Done"
            }
        SystemNotificationActionStatus.Requested -> "Requested"
        SystemNotificationActionStatus.PermissionDenied -> "Denied"
        SystemNotificationActionStatus.NotificationsDisabled -> "Disabled"
        SystemNotificationActionStatus.ChannelBlocked -> "Blocked"
        SystemNotificationActionStatus.Unsupported -> "Unsupported"
        SystemNotificationActionStatus.Error -> "Error"
    }

private fun SystemNotificationActionResult.mobileDetail(action: String): String =
    when (status) {
        SystemNotificationActionStatus.Ok ->
            when (action) {
                MOBILE_ACTION_SETTINGS -> "System notification settings were opened."
                MOBILE_ACTION_TEST_NOTIFICATION -> "A test notification was posted."
                else -> "Notification permission request completed."
            }
        SystemNotificationActionStatus.Requested -> "System notification permission dialog was opened. Recheck after responding."
        SystemNotificationActionStatus.PermissionDenied -> "Notification permission is denied or still disabled."
        SystemNotificationActionStatus.NotificationsDisabled -> "System notifications are disabled for Slock."
        SystemNotificationActionStatus.ChannelBlocked -> "The notification channel is blocked in system settings."
        SystemNotificationActionStatus.Unsupported -> "This notification action is not supported on this platform."
        SystemNotificationActionStatus.Error -> code.ifBlank { "Notification action failed." }
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
