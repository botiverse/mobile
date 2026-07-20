package build.raft.app.data

import build.raft.app.KmmResourceLocaleRuntime
import build.raft.app.SlockLucideIconName
import build.raft.app.model.MemberId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerNotificationPreferencesChanged
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.network.updateSlockNetworkKmmRecoveryEnvironment
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

enum class SharedSettingsDestination {
    Home,
    Account,
    Computers,
    ComputerDetail,
    Server,
    Administration,
    ConnectedApps,
    Billing,
    LanguageRegion,
    Appearance,
    Notifications,
    Mobile,
    Storage,
    About,
    OpenSourceLicenses,
    Developer,
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
    val connectedApps: ScreenLoadState<SettingsConnectedAppsSnapshot> = ScreenLoadState.Loading,
    val billing: ScreenLoadState<SettingsBillingSnapshot> = ScreenLoadState.Loading,
    val languageRegion: ScreenLoadState<SettingsLanguageRegionSnapshot> = ScreenLoadState.Loading,
    val appearance: ScreenLoadState<SettingsAppearanceSnapshot> = ScreenLoadState.Loading,
    val mobile: ScreenLoadState<SettingsMobileSnapshot> = ScreenLoadState.Loading,
    val storage: ScreenLoadState<SharedSettingsStorageSnapshot> = ScreenLoadState.Loading,
    val addComputerDialog: AddComputerDialogState? = null,
    val about: ScreenLoadState<SharedSettingsAboutSnapshot> = ScreenLoadState.Loading,
    val openSourceLicenses: ScreenLoadState<SharedSettingsOpenSourceLicensesSnapshot> = ScreenLoadState.Loading,
    val developer: ScreenLoadState<SharedSettingsDeveloperSnapshot> = ScreenLoadState.Loading,
    val diagnostics: ScreenLoadState<SharedSettingsDiagnosticsSnapshot> = ScreenLoadState.Loading,
    val refreshingDestinations: Set<SharedSettingsDestination> = emptySet(),
    val routeErrorCode: String? = null,
    val actionState: SharedSettingsActionState? = null,
    val effect: SharedSettingsEffect? = null
)

enum class AddComputerStep { Type, Waiting, Connected }

enum class AddComputerType { Local, Cloud }

enum class AddComputerCommandTarget { Daemon, Computer }

data class AddComputerDialogState(
    val step: AddComputerStep = AddComputerStep.Type,
    val machineType: AddComputerType = AddComputerType.Local,
    val name: String = "",
    val machineId: String? = null,
    val apiKeyPrefix: String? = null,
    val daemonRunCommand: String? = null,
    val computerSetupCommand: String? = null,
    val copiedCommand: AddComputerCommandTarget? = null,
    val connectedDetail: String? = null,
    val errorMessage: String? = null,
    val registering: Boolean = false,
    val saving: Boolean = false,
    val canManage: Boolean = true,
    val atLimit: Boolean = false
)

data class AddComputerSetupCommands(
    val daemonRunCommand: String? = null,
    val computerSetupCommand: String? = null
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
    val iconName: SlockLucideIconName,
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
    val appName: String = "Raft",
    val versionLabel: String = "",
    val versionCodeLabel: String = "",
    val buildLabel: String = "",
    val sourceLabel: String = "",
    val packageName: String = "",
    val signedInUserLabel: String = "",
    val selectedServerLabel: String = "",
    val sessionStatusLabel: String = "",
    val socketStatusLabel: String = "",
    val notificationStatusLabel: String = "",
    val deviceLabel: String = "",
    val platformLabel: String = "",
    val sdkLabel: String = "",
    val installTimeLabel: String = "",
    val screenLabel: String = "",
    val densityLabel: String = "",
    val fontScaleLabel: String = "",
    val modelLabel: String = "",
    val buildTime: String = "",
    val gitCommit: String = ""
)

data class SharedSettingsOpenSourceLicensesSnapshot(
    val schemaVersion: Int = 1,
    val variant: String = "",
    val sourceConfiguration: String = "",
    val items: List<SharedSettingsOpenSourceLicenseItem> = emptyList()
)

data class SharedSettingsOpenSourceLicenseItem(
    val id: String,
    val group: String = "",
    val name: String,
    val version: String = "",
    val displayName: String = "",
    val licenseName: String,
    val licenseUrl: String = "",
    val sourceUrl: String = ""
)

data class SharedSettingsDeveloperSnapshot(
    val environmentKey: String = "",
    val environmentLabel: String = "",
    val apiBaseUrl: String = "",
    val socketBaseUrl: String = "",
    val webUrl: String = "",
    val updateCheck: SharedSettingsUpdateCheckSnapshot = SharedSettingsUpdateCheckSnapshot(),
    val options: List<SharedSettingsApiEnvironmentOption> = emptyList(),
    val feedback: String? = null
)

data class SharedSettingsUpdateCheckSnapshot(
    val supported: Boolean = false,
    val statusLabel: String = "",
    val detail: String = "",
    val actionLabel: String = "",
    val updateAvailable: Boolean = false,
    val currentVersionLabel: String = "",
    val currentVersionCode: Long = 0L,
    val latestVersionLabel: String = "",
    val latestVersionCode: Long = 0L,
    val downloadUrl: String = "",
    val checkedAtLabel: String = "",
    val errorCode: String = ""
)

data class SharedSettingsApiEnvironmentOption(
    val key: String,
    val label: String,
    val apiBaseUrl: String,
    val socketBaseUrl: String,
    val webUrl: String
)

data class SharedSettingsApiEnvironmentRequest(
    val key: String,
    val label: String,
    val apiBaseUrl: String,
    val socketBaseUrl: String,
    val webUrl: String
)

internal fun sharedSettingsDefaultApiEnvironmentOptions(): List<SharedSettingsApiEnvironmentOption> =
    SharedApiEnvironmentStore.predefined

data class SharedSettingsDiagnosticsSnapshot(
    val about: SharedSettingsAboutSnapshot? = null,
    val entries: List<SharedSettingsDiagnosticsEntry> = emptyList(),
    val profiler: SharedSettingsRecompositionProfilerSnapshot = SharedSettingsRecompositionProfilerSnapshot(),
    val loadErrorMessage: String? = null
)

data class SharedSettingsDiagnosticsEntry(
    val timestampLabel: String,
    val tag: String,
    val message: String
)

data class SharedSettingsRecompositionProfilerSnapshot(
    val supported: Boolean = false,
    val enabled: Boolean = false,
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
    data object RefreshComputers : SharedSettingsIntent
    data class ScanComputerWorkspaces(val machineId: String) : SharedSettingsIntent
    data object Logout : SharedSettingsIntent
    data object RequestNotificationPermission : SharedSettingsIntent
    data object OpenNotificationSettings : SharedSettingsIntent
    data object SendTestNotification : SharedSettingsIntent
    data class UpdateProfile(val request: AccountProfileUpdateRequest) : SharedSettingsIntent
    data object RequestAccountAvatarUpload : SharedSettingsIntent
    data class ChangeAccountPassword(val request: AccountPasswordChangeRequest) : SharedSettingsIntent
    data class ConnectAccountProvider(val request: AccountProviderLinkStartRequest) : SharedSettingsIntent
    data class RegisterConnectedApp(val request: SettingsConnectedAppRegisterRequest) : SharedSettingsIntent
    data class UpdateConnectedApp(val request: SettingsConnectedAppUpdateRequest) : SharedSettingsIntent
    data class RequestConnectedAppLogoUpload(val clientRecordId: String) : SharedSettingsIntent
    data class ClearConnectedAppLogo(val clientRecordId: String) : SharedSettingsIntent
    data class DeleteConnectedApp(val clientRecordId: String) : SharedSettingsIntent
    data class RequestConnectedAppPublish(val request: SettingsConnectedAppPublishRequest) : SharedSettingsIntent
    data class InstallMarketplaceApp(val clientRecordId: String) : SharedSettingsIntent
    data class UninstallMarketplaceApp(val clientRecordId: String) : SharedSettingsIntent
    data class UpdateServerProfile(val request: SettingsServerProfileUpdateRequest) : SharedSettingsIntent
    data class CreateServerInvite(val request: SettingsServerInviteCreateRequest) : SharedSettingsIntent
    data class RevokeServerInvite(val inviteId: String) : SharedSettingsIntent
    data class CreateServerJoinLink(val request: SettingsServerJoinLinkCreateRequest = SettingsServerJoinLinkCreateRequest()) : SharedSettingsIntent
    data class RevokeServerJoinLink(val linkId: String) : SharedSettingsIntent
    data class RenameComputer(val request: SettingsComputerRenameRequest) : SharedSettingsIntent
    data class UpdateComputerDescription(val request: SettingsComputerDescriptionUpdateRequest) : SharedSettingsIntent
    data class DeleteComputer(val machineId: String) : SharedSettingsIntent
    data class RotateComputerKey(val machineId: String) : SharedSettingsIntent
    data class RegisterComputer(val request: SettingsComputerRegisterRequest) : SharedSettingsIntent
    data object ShowAddComputerDialog : SharedSettingsIntent
    data object DismissAddComputerDialog : SharedSettingsIntent
    data class SetAddComputerStep(val step: AddComputerStep) : SharedSettingsIntent
    data class SetAddComputerType(val machineType: AddComputerType) : SharedSettingsIntent
    data class SetAddComputerName(val name: String) : SharedSettingsIntent
    data class SetAddComputerRegistration(val machineId: String, val apiKeyPrefix: String) : SharedSettingsIntent
    data class SetAddComputerError(val message: String) : SharedSettingsIntent
    data class PollAddComputerMachineStatus(val machineId: String) : SharedSettingsIntent
    data class CopyAddComputerCommand(val target: AddComputerCommandTarget) : SharedSettingsIntent
    data object ClearAddComputerCopiedCommand : SharedSettingsIntent
    data object CompleteAddComputerDialog : SharedSettingsIntent
    /**
     * #79: realtime event bridge for the Settings surface. Only used for
     * `RealtimeRefreshScope.Machines` events today. Listens to the same
     * `slockRealtimeEvent` flow that SharedHomeStore already drains from,
     * but routes the relevant subset into settings state so the
     * ComputerDetail page reflects machine:updated server events without a
     * full app refresh.
     */
    data class ApplyRealtimeEvent(
        val serverId: String,
        val event: RealtimeEvent
    ) : SharedSettingsIntent
    data class UpdateServerAgreement(val request: SettingsServerAgreementUpdateRequest) : SharedSettingsIntent
    data class UpdateServerOnboarding(val request: SettingsServerOnboardingUpdateRequest) : SharedSettingsIntent
    data class UpdateServerTranslation(val request: SettingsServerTranslationUpdateRequest) : SharedSettingsIntent
    data class UpdateServerSidebarOrder(val request: SettingsServerSidebarOrderUpdateRequest) : SharedSettingsIntent
    data class UpdateSystemChannelVisibility(val allChannelId: String, val hide: Boolean) : SharedSettingsIntent
    data object RestoreSystemChannel : SharedSettingsIntent
    data class UpdateServerMemberRole(val request: SettingsServerMemberRoleUpdateRequest) : SharedSettingsIntent
    data class RemoveServerMember(val memberId: String) : SharedSettingsIntent
    data object LeaveServer : SharedSettingsIntent
    data class DeleteServer(val request: SettingsServerDeleteRequest) : SharedSettingsIntent
    data class RequestServerConfirmation(val action: String, val itemId: String? = null) : SharedSettingsIntent
    data class SetNotificationPreference(val enabled: Boolean) : SharedSettingsIntent
    data class SetForegroundKeepAlivePreference(val enabled: Boolean) : SharedSettingsIntent
    data class SetServerMute(val enabled: Boolean) : SharedSettingsIntent
    data class SetServerNotificationMode(val mode: String) : SharedSettingsIntent
    data class SetLanguageRegionServerTranslation(val enabled: Boolean) : SharedSettingsIntent
    data class SetTranslationMode(val mode: String) : SharedSettingsIntent
    data class SetTranslationTargetLanguage(val language: String) : SharedSettingsIntent
    data class UpdateLanguageRegionPreference(
        val request: SettingsLanguageRegionPreferenceUpdateRequest
    ) : SharedSettingsIntent
    data class UpdateAppearance(
        val request: SettingsAppearanceUpdateRequest
    ) : SharedSettingsIntent
    data class CompleteSystemNotificationAction(
        val action: String,
        val result: SystemNotificationActionResult
    ) : SharedSettingsIntent
    data class SetLocale(val localeTag: String) : SharedSettingsIntent
    data class SetMessageAlignment(val alignment: String) : SharedSettingsIntent
    data class SetLinkOpenMode(val mode: String) : SharedSettingsIntent
    data class SetHttp3Enabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetCompactConsecutiveMessages(val enabled: Boolean) : SharedSettingsIntent
    data class SetLongMessageCollapseEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetSingleTapOpensThreadEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetImageAnnotationEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetForegroundMessageBanners(val enabled: Boolean) : SharedSettingsIntent
    data class SetActivityTaskStatusBadges(val enabled: Boolean) : SharedSettingsIntent
    data class SetComposerSendKey(val enabled: Boolean) : SharedSettingsIntent
    data class SetMultiShellHostEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetServerDrawerEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetThreadRowAffordance(val affordance: String, val enabled: Boolean) : SharedSettingsIntent
    data object RefreshStorage : SharedSettingsIntent
    data class ClearStorage(val target: String) : SharedSettingsIntent
    data class SetApiEnvironment(val environmentKey: String) : SharedSettingsIntent
    data class SaveApiEnvironment(val request: SharedSettingsApiEnvironmentRequest) : SharedSettingsIntent
    data object CheckQuiverUpdate : SharedSettingsIntent
    data class CompleteStorageClear(
        val target: String,
        val ok: Boolean,
        val code: String? = null,
        val snapshot: SharedSettingsStorageSnapshot? = null
    ) : SharedSettingsIntent
    data object CopyDiagnostics : SharedSettingsIntent
    data object ShareDiagnostics : SharedSettingsIntent
    data object ClearDiagnostics : SharedSettingsIntent
    data class SetRecompositionProfilerEnabled(val enabled: Boolean) : SharedSettingsIntent
    data object StartRecompositionProfiler : SharedSettingsIntent
    data object StopRecompositionProfiler : SharedSettingsIntent
    data object ResetRecompositionProfiler : SharedSettingsIntent
    data object RefreshRecompositionProfilerReport : SharedSettingsIntent
    data object ExportRecompositionProfilerReport : SharedSettingsIntent
    data object SendFeedback : SharedSettingsIntent
    data object ConsumeEffect : SharedSettingsIntent
}

sealed interface SharedSettingsEffect {
    data object Logout : SharedSettingsEffect
    data class ApiEnvironmentChanged(val message: String) : SharedSettingsEffect
    data class OpenUrl(val url: String) : SharedSettingsEffect
    data object RequestNotificationPermission : SharedSettingsEffect
    data object OpenNotificationSettings : SharedSettingsEffect
    data object SendTestNotification : SharedSettingsEffect
    data class SetLocale(val localeTag: String) : SharedSettingsEffect
    data class SetMessageAlignment(val alignment: String) : SharedSettingsEffect
    data class ClearStorage(val target: String) : SharedSettingsEffect
    data object CopyDiagnostics : SharedSettingsEffect
    data class CopyText(val text: String) : SharedSettingsEffect
    data class OpenComputer(val machineId: String) : SharedSettingsEffect
    data object ShareDiagnostics : SharedSettingsEffect
    data object ClearDiagnostics : SharedSettingsEffect
    data object SendFeedback : SharedSettingsEffect
}

interface SharedSettingsPlatformCapabilities {
    suspend fun pickAccountAvatarFile(): DataResult<PlatformFile> =
        DataResult.Error(ScreenError("settings_account_avatar_picker_unavailable", retryable = false))

    suspend fun pickConnectedAppLogoFile(clientRecordId: String): DataResult<PlatformFile> =
        DataResult.Error(ScreenError("settings_connected_app_logo_picker_unavailable", retryable = false))

    suspend fun loadStorage(): DataResult<SharedSettingsStorageSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun clearStorage(target: String): DataResult<SharedSettingsStorageSnapshot> =
        DataResult.Error(ScreenError("settings_storage_clear_unavailable", retryable = false))

    suspend fun loadAbout(): DataResult<SharedSettingsAboutSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadOpenSourceLicenses(): DataResult<SharedSettingsOpenSourceLicensesSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadDeveloper(): DataResult<SharedSettingsDeveloperSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun setApiEnvironment(environmentKey: String): DataResult<SharedSettingsDeveloperSnapshot> =
        DataResult.Error(ScreenError("settings_developer_environment_unavailable", retryable = false))

    suspend fun saveApiEnvironment(request: SharedSettingsApiEnvironmentRequest): DataResult<SharedSettingsDeveloperSnapshot> =
        setApiEnvironment(request.key)

    suspend fun checkQuiverUpdate(): DataResult<SharedSettingsDeveloperSnapshot> =
        DataResult.Error(ScreenError("settings_quiver_update_check_unavailable", retryable = false))

    suspend fun checkQuiverUpdateStatus(): DataResult<SharedSettingsUpdateCheckSnapshot> =
        DataResult.Error(ScreenError("settings_quiver_update_status_unavailable", retryable = false))

    suspend fun installQuiverUpdate(): DataResult<SharedSettingsUpdateCheckSnapshot> =
        DataResult.Error(ScreenError("settings_quiver_update_install_unavailable", retryable = false))

    suspend fun prefetchReleaseNotes(url: String): DataResult<Unit> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun onApiEnvironmentChanged(
        before: SharedSettingsApiEnvironmentOption,
        after: SharedSettingsApiEnvironmentOption
    ): DataResult<Unit> =
        DataResult.Content(Unit)

    suspend fun loadDiagnosticsHost(): DataResult<SharedSettingsDiagnosticsHostSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun setRecompositionProfilerEnabled(enabled: Boolean): DataResult<Unit> =
        DataResult.Content(Unit)

    suspend fun loadSystemNotificationPermission(): SettingsMobileActionSnapshot? = null
}

class SharedSettingsDataSource(
    private val sessionStore: SlockAuthSessionStore,
    private val settingsRepository: SettingsRepository? = null,
    private val computersSource: SharedSettingsComputersSource? = null,
    private val serverMembersStore: SharedServerMembersStore? = null,
    private val notificationPreferenceRepository: NotificationPreferenceRepository? = null,
    private val localeRepository: LocaleRepository? = null,
    private val chatLayoutPreferenceRepository: ChatLayoutPreferenceRepository? = null,
    private val recompositionProfilerPreferenceRepository: RecompositionProfilerPreferenceRepository? = null,
    private val apiEnvironmentStore: SharedApiEnvironmentStore? = null,
    private val platformCapabilities: SharedSettingsPlatformCapabilities = object : SharedSettingsPlatformCapabilities {},
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    private val recompositionProfilerController: SharedSettingsRecompositionProfilerController =
        SharedSettingsRecompositionProfilerController(),
    private val linkOpenPreferenceRepository: LinkOpenPreferenceRepository? = null,
    private val networkTransportPreferenceRepository: NetworkTransportPreferenceRepository? = null
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

    suspend fun changeAccountPassword(request: AccountPasswordChangeRequest): DataResult<AccountSettingsSnapshot> =
        settingsRepository?.changeAccountPassword(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun uploadAccountAvatar(): DataResult<AccountSettingsSnapshot> {
        val repository = settingsRepository ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return when (val picked = platformCapabilities.pickAccountAvatarFile()) {
            is DataResult.Content ->
                withContext(Dispatchers.Default) {
                    repository.uploadAccountAvatar(AccountAvatarUploadRequest(picked.data))
                }
            is DataResult.Empty -> DataResult.Empty(picked.reason)
            is DataResult.Error -> DataResult.Error(picked.error)
            is DataResult.NoPermission -> DataResult.NoPermission(picked.blocker)
        }
    }

    suspend fun startAccountProviderLink(request: AccountProviderLinkStartRequest): DataResult<AccountProviderLinkStartResult> =
        settingsRepository?.startAccountProviderLink(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadConnectedApps(): DataResult<SettingsConnectedAppsSnapshot> =
        settingsRepository?.getConnectedApps() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun registerConnectedApp(request: SettingsConnectedAppRegisterRequest): DataResult<SettingsConnectedAppsSnapshot> =
        settingsRepository?.registerConnectedApp(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun updateConnectedApp(request: SettingsConnectedAppUpdateRequest): DataResult<SettingsConnectedAppsSnapshot> =
        settingsRepository?.updateConnectedApp(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun uploadConnectedAppLogo(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> {
        val repository = settingsRepository ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val id = clientRecordId.trim()
        if (id.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return when (val picked = platformCapabilities.pickConnectedAppLogoFile(id)) {
            is DataResult.Content ->
                withContext(Dispatchers.Default) {
                    repository.uploadConnectedAppLogo(SettingsConnectedAppLogoUploadRequest(id, picked.data))
                }
            is DataResult.Empty -> DataResult.Empty(picked.reason)
            is DataResult.Error -> DataResult.Error(picked.error)
            is DataResult.NoPermission -> DataResult.NoPermission(picked.blocker)
        }
    }

    suspend fun clearConnectedAppLogo(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> =
        settingsRepository?.clearConnectedAppLogo(clientRecordId) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun deleteConnectedApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> =
        settingsRepository?.deleteConnectedApp(clientRecordId) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun requestConnectedAppPublish(request: SettingsConnectedAppPublishRequest): DataResult<SettingsConnectedAppsSnapshot> =
        settingsRepository?.requestConnectedAppPublish(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun installMarketplaceApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> =
        settingsRepository?.installMarketplaceApp(clientRecordId) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun uninstallMarketplaceApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> =
        settingsRepository?.uninstallMarketplaceApp(clientRecordId) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadComputers(forceComputers: Boolean = false): DataResult<SettingsComputersSnapshot> {
        val session = sessionStore.readSession()
        val serverId = session?.currentServerId
        val sharedResult =
            if (serverId != null && computersSource != null) {
                computersSource.loadSettingsComputersForServer(serverId, force = forceComputers)
            } else {
                null
            }
        val result = sharedResult ?: settingsRepository?.getComputers() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return result.withAddComputerGate(session.canManageCurrentServer())
    }

    suspend fun loadComputerDetail(machineId: String, forceComputers: Boolean = false): DataResult<SettingsComputerDetailSnapshot> {
        val session = sessionStore.readSession()
        val serverId = session?.currentServerId
        val sharedComputer =
            if (serverId != null && computersSource != null) {
                computersSource.loadSettingsComputerSnapshotForServer(
                    serverId = serverId,
                    machineId = machineId,
                    force = forceComputers
                )
            } else {
                null
        }
        val result =
            when (sharedComputer) {
                is DataResult.Content ->
                    settingsRepository?.getComputerDetailForComputer(sharedComputer.data)
                        ?: DataResult.Content(
                            SettingsComputerDetailSnapshot(
                                computer = sharedComputer.data,
                                workspaces = emptyList(),
                                agents = emptyList(),
                                sections = emptyList()
                            )
                        )
                is DataResult.Empty -> DataResult.Empty(sharedComputer.reason)
                is DataResult.Error -> DataResult.Error(sharedComputer.error)
                is DataResult.NoPermission -> DataResult.NoPermission(sharedComputer.blocker)
                null -> settingsRepository?.getComputerDetail(machineId) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)
            }.withComputerManageGate(session.canManageCurrentServer())
        if (result is DataResult.Content && serverId != null) {
            refreshServerMembersForSettingsStatus(serverId, force = forceComputers)
        }
        return result
    }

    suspend fun scanComputerWorkspaces(machineId: String): DataResult<List<SettingsComputerWorkspaceSnapshot>> =
        settingsRepository?.scanComputerWorkspaces(machineId)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    private suspend fun refreshServerMembersForSettingsStatus(serverId: String, force: Boolean) {
        val store = serverMembersStore ?: return
        val cleanServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return
        when (val result = store.refreshMembers(ServerId(cleanServerId), priority = DataRequestPriority.Background, force = force)) {
            is DataResult.Content ->
                diagnosticsLogger?.invoke(
                    "settings_member_status_refresh_result serverId=$cleanServerId result=content members=${store.currentServerMemberCount(ServerId(cleanServerId))}"
                )
            is DataResult.Empty ->
                diagnosticsLogger?.invoke(
                    "settings_member_status_refresh_result serverId=$cleanServerId result=empty reason=${result.reason}"
                )
            is DataResult.Error ->
                diagnosticsLogger?.invoke(
                    "settings_member_status_refresh_result serverId=$cleanServerId result=error code=${result.error.code}"
                )
            is DataResult.NoPermission ->
                diagnosticsLogger?.invoke(
                    "settings_member_status_refresh_result serverId=$cleanServerId result=no_permission code=${result.blocker.code}"
                )
        }
    }

    suspend fun renameComputer(request: SettingsComputerRenameRequest): DataResult<SettingsComputerSnapshot> =
        settingsRepository?.renameComputer(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun updateComputerDescription(request: SettingsComputerDescriptionUpdateRequest): DataResult<SettingsComputerSnapshot> =
        settingsRepository?.updateComputerDescription(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun deleteComputer(machineId: String): DataResult<SettingsComputerActionResult> =
        settingsRepository?.deleteComputer(machineId) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun rotateComputerKey(machineId: String): DataResult<SettingsComputerKeyRotationResult> =
        settingsRepository?.rotateComputerKey(machineId) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun registerComputer(request: SettingsComputerRegisterRequest): DataResult<SettingsComputerRegistrationResult> =
        settingsRepository?.registerComputer(request) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun buildAddComputerSetupCommands(apiKey: String): AddComputerSetupCommands {
        val cleanApiKey = apiKey.trim()
        if (cleanApiKey.isBlank()) return AddComputerSetupCommands()
        val session = sessionStore.readSession()
        val environment = apiEnvironmentStore?.current() ?: SharedApiEnvironmentStore.Production
        val server = session.settingsCurrentServer()
        val serverName = server?.name?.takeIf { it.isNotBlank() } ?: session.currentServerSlugForSettings() ?: "server"
        val serverUrl = environment.socketBaseUrl.trimEnd('/')
        val daemonTag = if (environment.key == SharedApiEnvironmentStore.Staging.key) "staging" else "latest"
        val daemonRunCommand =
            "npx @botiverse/raft-daemon@$daemonTag --server-url $serverUrl --api-key $cleanApiKey # $serverName"
        val slug = session.currentServerSlugForSettings()?.trim()?.trimStart('/')
        val computerSetupCommand =
            if (!slug.isNullOrBlank() && (slug == "botiverse" || environment.key == SharedApiEnvironmentStore.CustomKey)) {
                val serverUrlArg =
                    when (environment.key) {
                        SharedApiEnvironmentStore.Staging.key -> " --server-url https://slock-server-staging.fly.dev"
                        SharedApiEnvironmentStore.CustomKey -> " --server-url $serverUrl"
                        else -> ""
                    }
                "npm install -g @botiverse/raft-computer@latest && raft-computer setup /$slug$serverUrlArg"
            } else {
                null
            }
        return AddComputerSetupCommands(
            daemonRunCommand = daemonRunCommand,
            computerSetupCommand = computerSetupCommand
        )
    }

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

    suspend fun updateServerAgreement(
        request: SettingsServerAgreementUpdateRequest
    ): DataResult<SettingsServerAgreementSnapshot> =
        (settingsRepository as? SettingsServerManagementRepository)?.updateServerAgreement(request)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

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

    suspend fun removeServerMember(memberId: String): DataResult<SettingsServerActionResult> {
        val result =
            (settingsRepository as? SettingsServerManagementRepository)?.removeServerMember(memberId)
                ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        if (result is DataResult.Content && result.data.success) {
            val serverId = sessionStore.readSession()?.currentServerId?.trim().orEmpty()
            val cleanMemberId = memberId.trim()
            if (serverId.isNotBlank() && cleanMemberId.isNotBlank()) {
                serverMembersStore?.removeMember(
                    serverId = ServerId(serverId),
                    memberId = MemberId(cleanMemberId)
                )
            }
        }
        return result
    }

    suspend fun updateSystemChannelVisibility(
        allChannelId: String,
        hide: Boolean
    ): DataResult<SettingsServerActionResult> =
        (settingsRepository as? SettingsServerManagementRepository)?.updateSystemChannelVisibility(allChannelId, hide)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun restoreSystemChannel(): DataResult<SettingsServerActionResult> =
        (settingsRepository as? SettingsServerManagementRepository)?.restoreSystemChannel()
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun leaveServer(): DataResult<SettingsServerActionResult> =
        (settingsRepository as? SettingsServerManagementRepository)?.leaveServer()
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadBilling(): DataResult<SettingsBillingSnapshot> =
        settingsRepository?.getBilling() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun loadLanguageRegion(): DataResult<SettingsLanguageRegionSnapshot> =
        settingsRepository?.getLanguageRegionSettings()?.withLocalTranslationPreferences()
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun updateLanguageRegionPreference(
        request: SettingsLanguageRegionPreferenceUpdateRequest
    ): DataResult<SettingsLanguageRegionSnapshot> =
        settingsRepository?.updateLanguageRegionPreference(request)?.withLocalTranslationPreferences()
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun setTranslationMode(modeKey: String): DataResult<SettingsLanguageRegionSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_translation_mode_unavailable", retryable = false))
        val mode =
            TranslationModePreference.entries.firstOrNull { it.storageKey == modeKey }
                ?: return DataResult.Error(ScreenError("settings_translation_mode_unknown", retryable = false))
        repository.setTranslationMode(mode)
        return loadLanguageRegion()
    }

    suspend fun setTranslationTargetLanguage(language: String): DataResult<SettingsLanguageRegionSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_translation_target_unavailable", retryable = false))
        val normalized = TranslationTargetLanguages.normalize(language)
        if (normalized == null && language != TranslationTargetLanguages.Browser) {
            return DataResult.Error(ScreenError("settings_translation_target_unknown", retryable = false))
        }
        repository.setTranslationTargetLanguage(normalized ?: TranslationTargetLanguages.Browser)
        return loadLanguageRegion()
    }

    suspend fun loadAppearance(): DataResult<SettingsAppearanceSnapshot> =
        settingsRepository?.getAppearanceSettings() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun updateAppearance(
        request: SettingsAppearanceUpdateRequest
    ): DataResult<SettingsAppearanceSnapshot> =
        settingsRepository?.updateAppearanceSettings(request)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun setLanguageRegionServerTranslation(enabled: Boolean): DataResult<SettingsLanguageRegionSnapshot> =
        when (val result =
            (settingsRepository as? SettingsServerManagementRepository)?.updateServerTranslationSettings(
                SettingsServerTranslationUpdateRequest(enabled)
            ) ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)) {
            is DataResult.Content -> loadLanguageRegion()
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }

    suspend fun loadMobile(): DataResult<SettingsMobileSnapshot> {
        val systemNotificationPermission = platformCapabilities.loadSystemNotificationPermission()
        return when (val result = settingsRepository?.getMobileSettings() ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)) {
            is DataResult.Content ->
                DataResult.Content(
                    result.data
                        .withLocalMobilePreferences()
                        .withSystemNotificationPermission(systemNotificationPermission)
                )
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun loadMobilePreferences(): DataResult<SettingsMobileSnapshot> =
        DataResult.Content(
            localOnlyMobileSnapshot()
                .withLocalMobilePreferences()
                .withSystemNotificationPermission(platformCapabilities.loadSystemNotificationPermission())
        )

    suspend fun setNotificationPreference(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            notificationPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_notification_preference_unavailable", retryable = false))
        repository.setNotificationEnabled(enabled)
        return loadMobile()
    }

    suspend fun setForegroundKeepAlivePreference(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            notificationPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_notification_preference_unavailable", retryable = false))
        repository.setForegroundKeepAliveEnabled(enabled)
        return loadMobile()
    }

    suspend fun setServerMute(enabled: Boolean): DataResult<SettingsMobileSnapshot> =
        setServerNotificationMode(if (enabled) ServerPushMode.None.wireName else ServerPushMode.All.wireName)

    suspend fun setServerNotificationMode(mode: String): DataResult<SettingsMobileSnapshot> {
        val selectedMode =
            ServerPushMode.fromWire(mode)
                ?: return DataResult.Error(ScreenError("settings_server_notification_mode_unknown", retryable = false))
        val repository =
            settingsRepository as? SettingsServerActionRepository
                ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return when (val result = repository.setServerNotificationMode(selectedMode)) {
            is DataResult.Content -> DataResult.Content(result.data.withLocalMobilePreferences())
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun setLocale(localeTag: String): DataResult<SettingsMobileSnapshot> {
        val repository =
            localeRepository
                ?: return DataResult.Error(ScreenError("settings_locale_unavailable", retryable = false))
        val locale =
            AppLocale.entries.firstOrNull { it.languageTag == localeTag || it.storageKey == localeTag }
                ?: return DataResult.Error(ScreenError("settings_locale_unknown", retryable = false))
        repository.selectLocale(locale)
        KmmResourceLocaleRuntime.publish(locale)
        return loadMobilePreferences()
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
        return loadMobilePreferences()
    }

    suspend fun setLinkOpenMode(modeKey: String): DataResult<SettingsMobileSnapshot> {
        val repository =
            linkOpenPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_link_open_mode_unavailable", retryable = false))
        val mode =
            LinkOpenMode.entries.firstOrNull {
                it.storageKey == modeKey || it.name.equals(modeKey, ignoreCase = true)
            } ?: return DataResult.Error(ScreenError("settings_link_open_mode_unknown", retryable = false))
        repository.selectLinkOpenMode(mode)
        return loadMobilePreferences()
    }

    suspend fun setHttp3Enabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            networkTransportPreferenceRepository
                ?: return DataResult.Error(
                    ScreenError("settings_network_transport_unavailable", retryable = false)
                )
        return when (val result = repository.setHttp3Enabled(enabled)) {
            is NetworkTransportPreferenceMutationResult.Applied -> loadMobilePreferences()
            is NetworkTransportPreferenceMutationResult.Rejected ->
                DataResult.Error(ScreenError(result.errorCode, retryable = false))
        }
    }

    suspend fun setCompactConsecutiveMessages(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_compact_messages_unavailable", retryable = false))
        repository.setCompactConsecutiveMessages(enabled)
        return loadMobilePreferences()
    }

    suspend fun setLongMessageCollapseEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_long_message_collapse_unavailable", retryable = false))
        repository.setLongMessageCollapseEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setSingleTapOpensThreadEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_single_tap_thread_unavailable", retryable = false))
        repository.setSingleTapOpensThreadEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setImageAnnotationEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_image_annotation_unavailable", retryable = false))
        repository.setImageAnnotationEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setForegroundMessageBanners(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_foreground_message_banners_unavailable", retryable = false))
        repository.setForegroundMessageBannersEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setActivityTaskStatusBadges(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_activity_task_status_badges_unavailable", retryable = false))
        repository.setActivityTaskStatusBadgesEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setComposerSendKey(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_composer_send_key_unavailable", retryable = false))
        repository.setComposerSendKeyEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setMultiShellHostEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_multi_shell_host_unavailable", retryable = false))
        repository.setMultiShellHostEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setServerDrawerEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_server_drawer_unavailable", retryable = false))
        repository.setServerDrawerEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setThreadRowAffordance(
        affordanceKey: String,
        enabled: Boolean
    ): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_thread_row_affordances_unavailable", retryable = false))
        val affordance =
            ThreadRowAffordancePreference.entries.firstOrNull {
                it.storageKey == affordanceKey || it.name.equals(affordanceKey, ignoreCase = true)
            } ?: return DataResult.Error(ScreenError("settings_thread_row_affordance_unknown", retryable = false))
        repository.setThreadRowAffordance(affordance, enabled)
        return loadMobilePreferences()
    }

    suspend fun loadStorage(): DataResult<SharedSettingsStorageSnapshot> =
        platformCapabilities.loadStorage()

    suspend fun clearStorage(target: String): DataResult<SharedSettingsStorageSnapshot> =
        platformCapabilities.clearStorage(target)

    suspend fun loadAbout(): DataResult<SharedSettingsAboutSnapshot> {
        val sessionResult = runCatchingPreservingCancellation { sessionStore.readSession() }
        sessionResult.exceptionOrNull()?.let { throwable ->
            val diagnostics = throwable.toPersistenceFailureDiagnostics()
            diagnosticsLogger?.invoke(
                "about_session result=failure stage=session_read " +
                    "errorType=${diagnostics.errorType} sqliteCategory=${diagnostics.category} sqliteCode=${diagnostics.code ?: "none"}"
            )
        }
        return when (val result = platformCapabilities.loadAbout()) {
            is DataResult.Content -> DataResult.Content(result.data.withCanonicalSession(sessionResult))
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun loadOpenSourceLicenses(): DataResult<SharedSettingsOpenSourceLicensesSnapshot> =
        platformCapabilities.loadOpenSourceLicenses()

    suspend fun loadDeveloper(): DataResult<SharedSettingsDeveloperSnapshot> =
        apiEnvironmentStore?.developerSnapshot()?.let { DataResult.Content(it) }
            ?: platformCapabilities.loadDeveloper()

    suspend fun setApiEnvironment(environmentKey: String): DataResult<SharedSettingsDeveloperSnapshot> =
        apiEnvironmentStore?.let { store ->
            val before = store.current()
            val after =
                store.select(environmentKey)
                    ?: return DataResult.Error(ScreenError("settings_developer_environment_unknown", retryable = false))
            return applyApiEnvironmentChange(store, before, after)
        } ?: platformCapabilities.setApiEnvironment(environmentKey)

    suspend fun saveApiEnvironment(request: SharedSettingsApiEnvironmentRequest): DataResult<SharedSettingsDeveloperSnapshot> =
        apiEnvironmentStore?.let { store ->
            val before = store.current()
            val after =
                store.save(request)
                    ?: return DataResult.Error(ScreenError("settings_developer_environment_unknown", retryable = false))
            return applyApiEnvironmentChange(store, before, after)
        } ?: platformCapabilities.saveApiEnvironment(request)

    suspend fun checkQuiverUpdate(): DataResult<SharedSettingsDeveloperSnapshot> =
        platformCapabilities.checkQuiverUpdate()

    suspend fun checkQuiverUpdateStatus(): DataResult<SharedSettingsUpdateCheckSnapshot> =
        platformCapabilities.checkQuiverUpdateStatus()

    suspend fun installQuiverUpdate(): DataResult<SharedSettingsUpdateCheckSnapshot> =
        platformCapabilities.installQuiverUpdate()

    private suspend fun applyApiEnvironmentChange(
        store: SharedApiEnvironmentStore,
        before: SharedSettingsApiEnvironmentOption,
        after: SharedSettingsApiEnvironmentOption
    ): DataResult<SharedSettingsDeveloperSnapshot> {
        val changed = before != after
        if (changed) {
            runCatching {
                updateSlockNetworkKmmRecoveryEnvironment(
                    environmentKey = after.key,
                    apiBaseUrl = after.apiBaseUrl
                )
            }.onSuccess { diagnostics ->
                diagnostics?.let { diagnosticsLogger?.invoke(it) }
            }.onFailure { error ->
                diagnosticsLogger?.invoke(
                    "networkkmm_environment_update_failed reason=${error::class.simpleName ?: "unknown"}"
                )
            }
            sessionStore.clearSession(reason = "api_environment_changed", source = "settings_store")
            when (val result = platformCapabilities.onApiEnvironmentChanged(before, after)) {
                is DataResult.Content -> Unit
                is DataResult.Empty -> Unit
                is DataResult.Error -> diagnosticsLogger?.invoke("api_environment_changed_hook_error code=${result.error.code}")
                is DataResult.NoPermission -> diagnosticsLogger?.invoke("api_environment_changed_hook_no_permission code=${result.blocker.code}")
            }
        }
        return DataResult.Content(
            store.developerSnapshot(
                feedback =
                    if (changed) {
                        "API server switched to ${after.label}. Sign in again to use this environment."
                    } else {
                        "${after.label} is already selected."
                    }
            )
        )
    }

    suspend fun loadDiagnostics(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        loadDiagnosticsSnapshot()

    suspend fun setRecompositionProfilerEnabled(enabled: Boolean): DataResult<SharedSettingsDiagnosticsSnapshot> {
        val hostResult = platformCapabilities.setRecompositionProfilerEnabled(enabled)
        when (hostResult) {
            is DataResult.Content -> Unit
            is DataResult.Empty ->
                return DataResult.Error(ScreenError("settings_profiler_bridge_unavailable", retryable = false))
            is DataResult.Error ->
                return DataResult.Error(hostResult.error)
            is DataResult.NoPermission ->
                return DataResult.NoPermission(hostResult.blocker)
        }
        val result =
            if (enabled) {
                recompositionProfilerController.start()
            } else {
                recompositionProfilerController.stop()
            }
        result.errorCode?.let { code ->
            if (enabled) {
                recompositionProfilerPreferenceRepository?.setEnabled(false)
            }
            return DataResult.Error(ScreenError(code, retryable = false))
        }
        recompositionProfilerPreferenceRepository?.setEnabled(false)
        return loadDiagnosticsSnapshot(feedback = result.feedback)
    }

    suspend fun startRecompositionProfiler(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        setRecompositionProfilerEnabled(true)

    suspend fun stopRecompositionProfiler(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        setRecompositionProfilerEnabled(false)

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
        val linkOpenMode = linkOpenPreferenceRepository?.getLinkOpenMode()
        val networkTransport = networkTransportPreferenceRepository?.getPreference()
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
                    ),
            linkOpenMode =
                linkOpenMode?.toSettingsMobileLinkOpenModeSnapshot()
                    ?: this.linkOpenMode.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Link opening preference is not available on this platform."
                    ),
            http3 =
                networkTransport?.toSettingsMobileHttp3Snapshot()
                    ?: SettingsHttp3Snapshot(
                        requestedEnabled = false,
                        effectiveEnabled = false,
                        curlRequestedForNewCalls = false,
                        canEnable = false,
                        statusLabel = "Unavailable",
                        detail = "The curl/HTTP/3 runtime is not available on this platform."
                    ),
            compactConsecutiveMessages =
                chatLayout?.toSettingsMobileCompactMessagesSnapshot()
                    ?: compactConsecutiveMessages.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Compact message layout preference is not available on this platform."
                    ),
            longMessageCollapse =
                chatLayout?.toSettingsMobileLongMessageCollapseSnapshot()
                    ?: longMessageCollapse.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Long message collapse preference is not available on this platform."
                    ),
            singleTapOpensThread =
                chatLayout?.toSettingsMobileSingleTapOpensThreadSnapshot()
                    ?: singleTapOpensThread.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Single-tap Thread opening is not available on this platform."
                    ),
            imageAnnotation =
                chatLayout?.toSettingsMobileImageAnnotationSnapshot()
                    ?: imageAnnotation.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Image annotation preference is not available on this platform."
                    ),
            foregroundMessageBanners =
                chatLayout?.toSettingsMobileForegroundMessageBannersSnapshot()
                    ?: foregroundMessageBanners.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Foreground message banner preference is not available on this platform."
                    ),
            activityTaskStatusBadges =
                chatLayout?.toSettingsMobileActivityTaskStatusBadgesSnapshot()
                    ?: activityTaskStatusBadges.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Activity task status badge preference is not available on this platform."
                    ),
            composerSendKey =
                chatLayout?.toSettingsMobileComposerSendKeySnapshot()
                    ?: composerSendKey.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Composer keyboard action preference is not available on this platform."
                    ),
            multiShellHost =
                chatLayout?.toSettingsMobileMultiShellHostSnapshot()
                    ?: multiShellHost.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Multiple app windows are not available on this platform."
                    ),
            serverDrawer =
                chatLayout?.toSettingsMobileServerDrawerSnapshot()
                    ?: serverDrawer.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "The multi-server Home drawer is not available on this platform."
                    ),
            threadRowAffordances =
                chatLayout?.toSettingsMobileThreadRowAffordancesSnapshot()
                    ?: threadRowAffordances.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Message row actions preference is not available on this platform."
                    )
        )
    }

    private fun SettingsMobileSnapshot.withSystemNotificationPermission(
        permission: SettingsMobileActionSnapshot?
    ): SettingsMobileSnapshot =
        permission?.let { copy(notificationPermission = it) } ?: this

    private suspend fun DataResult<SettingsLanguageRegionSnapshot>.withLocalTranslationPreferences(): DataResult<SettingsLanguageRegionSnapshot> =
        when (this) {
            is DataResult.Content -> {
                val preferences = chatLayoutPreferenceRepository?.getChatLayoutPreference()
                DataResult.Content(
                    data.withLocalTranslationPreferences(
                        mode = preferences?.translationMode ?: TranslationModePreference.Default,
                        targetLanguage = preferences?.translationTargetLanguage ?: TranslationTargetLanguages.Browser
                    )
                )
            }
            is DataResult.Empty -> this
            is DataResult.Error -> this
            is DataResult.NoPermission -> this
        }

    private fun localOnlyMobileSnapshot(): SettingsMobileSnapshot =
        SettingsMobileSnapshot(
            pushNotifications =
                SettingsMobileToggleSnapshot(
                    enabled = false,
                    available = false,
                    statusLabel = "Moved",
                    detail = "Notification controls are available from Settings > Notifications."
                ),
            muteServer =
                SettingsMobileToggleSnapshot(
                    enabled = false,
                    available = false,
                    statusLabel = "Moved",
                    detail = "Workspace mute is available from Settings > Notifications."
                ),
            sendTestEnabled = false
        )

    private suspend fun loadDiagnosticsSnapshot(
        feedback: String? = null
    ): DataResult<SharedSettingsDiagnosticsSnapshot> {
        val aboutResult = loadAbout()
        val hostResult = platformCapabilities.loadDiagnosticsHost()
        val about =
            when (aboutResult) {
                is DataResult.Content -> aboutResult.data
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> null
            }
        val entries =
            when (hostResult) {
                is DataResult.Content -> hostResult.data.entries
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> emptyList()
            }
        val loadErrorMessage =
            listOfNotNull(
                aboutResult.settingsDiagnosticsErrorLabel("about"),
                hostResult.settingsDiagnosticsErrorLabel("logs")
            ).joinToString("; ").takeIf { it.isNotBlank() }
        val persistedProfilerEnabled = recompositionProfilerPreferenceRepository?.getPreference()?.enabled == true
        val profilerEnabled =
            if (recompositionProfilerController.isRunning) {
                true
            } else {
                if (persistedProfilerEnabled) {
                    recompositionProfilerPreferenceRepository.setEnabled(false)
                }
                false
            }
        return DataResult.Content(
            SharedSettingsDiagnosticsSnapshot(
                about = about,
                entries = entries,
                profiler = recompositionProfilerController.buildSnapshot(enabled = profilerEnabled, feedback = feedback),
                loadErrorMessage = loadErrorMessage
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

    private fun <T> DataResult<T>.settingsDiagnosticsErrorLabel(label: String): String? =
        when (this) {
            is DataResult.Content -> null
            is DataResult.Empty -> "$label empty: ${reason.name}"
            is DataResult.Error -> "$label error: ${error.code}"
            is DataResult.NoPermission -> "$label blocked: ${blocker.code}"
        }
}

class SharedSettingsViewModel(
    dataSource: SharedSettingsDataSource,
    diagnosticsLogger: (String) -> Unit = {}
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedSettingsStore(
                dataSource = dataSource,
                scope = viewModelScope,
                diagnosticsLogger = diagnosticsLogger
            )
        }
    private val contractDelegate =
        lazy {
            SharedSettingsStoreContract(
                store = storeDelegate.value,
                scope = viewModelScope
            )
        }
    val state: StateFlow<SharedSettingsState>
        get() =
            if (contractDelegate.isInitialized()) contractDelegate.value.state
            else storeDelegate.value.state

    fun dispatch(intent: SharedSettingsIntent) {
        if (contractDelegate.isInitialized()) {
            contractDelegate.value.dispatch(intent)
        } else {
            storeDelegate.value.dispatch(intent)
        }
    }

    suspend fun accept(intent: SharedSettingsIntent) {
        storeDelegate.value.accept(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) {
            storeDelegate.value.clear()
        }
        super.onCleared()
    }
}

private fun SharedSettingsAboutSnapshot.withCanonicalSession(
    sessionResult: Result<StoredAuthSession?>
): SharedSettingsAboutSnapshot =
    sessionResult.fold(
        onSuccess = { session ->
            if (session != null) {
                copy(
                    signedInUserLabel = session.user.bestDisplayName,
                    selectedServerLabel = session.aboutServerLabel(),
                    sessionStatusLabel = "Signed in"
                )
            } else {
                copy(
                    signedInUserLabel = "Signed out",
                    selectedServerLabel = "No selected server",
                    sessionStatusLabel = "Signed out"
                )
            }
        },
        onFailure = {
            copy(
                signedInUserLabel = "Local storage unavailable",
                selectedServerLabel = "Local storage unavailable",
                sessionStatusLabel = "Local storage unavailable"
            )
        }
    )

private fun StoredAuthSession.aboutServerLabel(): String =
    selectedServerId
        ?.let { serverId -> servers.firstOrNull { it.id == serverId }?.name?.takeIf { it.isNotBlank() } ?: serverId }
        ?: selectedServerSlug
            ?.let { serverSlug -> servers.firstOrNull { it.slug == serverSlug }?.name?.takeIf { it.isNotBlank() } ?: serverSlug }
        ?: servers.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        ?: "No selected server"

class SharedSettingsStore(
    private val dataSource: SharedSettingsDataSource,
    private val scope: CoroutineScope,
    initialState: SharedSettingsState = SharedSettingsState(),
    private val diagnosticsLogger: (String) -> Unit = {}
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
            SharedSettingsIntent.RefreshComputers -> refreshComputers()
            is SharedSettingsIntent.ScanComputerWorkspaces -> {
                runAction(COMPUTER_ACTION_SCAN_WORKSPACES, intent.machineId) {
                    dataSource.scanComputerWorkspaces(intent.machineId)
                        .applyComputerWorkspacesActionResult(intent.machineId)
                }
            }
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
                runAction(ACCOUNT_ACTION_PROFILE_SAVE) {
                    dataSource.updateProfile(intent.request).applyAccountActionResult(ACCOUNT_ACTION_PROFILE_SAVE)
                }
            }
            SharedSettingsIntent.RequestAccountAvatarUpload -> {
                runAction(ACCOUNT_ACTION_AVATAR_UPLOAD) {
                    dataSource.uploadAccountAvatar().applyAccountActionResult(ACCOUNT_ACTION_AVATAR_UPLOAD)
                }
            }
            is SharedSettingsIntent.ChangeAccountPassword -> {
                runAction(ACCOUNT_ACTION_PASSWORD_CHANGE) {
                    dataSource.changeAccountPassword(intent.request).applyAccountActionResult(ACCOUNT_ACTION_PASSWORD_CHANGE)
                }
            }
            is SharedSettingsIntent.ConnectAccountProvider -> {
                runAction(ACCOUNT_ACTION_PROVIDER_CONNECT, intent.request.providerId) {
                    dataSource.startAccountProviderLink(intent.request)
                        .applyAccountProviderLinkResult(intent.request.providerId)
                }
            }
            is SharedSettingsIntent.RegisterConnectedApp -> {
                runAction(CONNECTED_APPS_ACTION_REGISTER) {
                    dataSource.registerConnectedApp(intent.request)
                        .applyConnectedAppsActionResult(CONNECTED_APPS_ACTION_REGISTER)
                }
            }
            is SharedSettingsIntent.UpdateConnectedApp -> {
                runAction(CONNECTED_APPS_ACTION_UPDATE, intent.request.id) {
                    dataSource.updateConnectedApp(intent.request)
                        .applyConnectedAppsActionResult(CONNECTED_APPS_ACTION_UPDATE, intent.request.id)
                }
            }
            is SharedSettingsIntent.RequestConnectedAppLogoUpload -> {
                runAction(CONNECTED_APPS_ACTION_LOGO_UPLOAD, intent.clientRecordId) {
                    dataSource.uploadConnectedAppLogo(intent.clientRecordId)
                        .applyConnectedAppsActionResult(CONNECTED_APPS_ACTION_LOGO_UPLOAD, intent.clientRecordId)
                }
            }
            is SharedSettingsIntent.ClearConnectedAppLogo -> {
                runAction(CONNECTED_APPS_ACTION_LOGO_CLEAR, intent.clientRecordId) {
                    dataSource.clearConnectedAppLogo(intent.clientRecordId)
                        .applyConnectedAppsActionResult(CONNECTED_APPS_ACTION_LOGO_CLEAR, intent.clientRecordId)
                }
            }
            is SharedSettingsIntent.DeleteConnectedApp -> {
                runAction(CONNECTED_APPS_ACTION_DELETE, intent.clientRecordId) {
                    dataSource.deleteConnectedApp(intent.clientRecordId)
                        .applyConnectedAppsActionResult(CONNECTED_APPS_ACTION_DELETE, intent.clientRecordId)
                }
            }
            is SharedSettingsIntent.RequestConnectedAppPublish -> {
                runAction(CONNECTED_APPS_ACTION_PUBLISH, intent.request.id) {
                    dataSource.requestConnectedAppPublish(intent.request)
                        .applyConnectedAppsActionResult(CONNECTED_APPS_ACTION_PUBLISH, intent.request.id)
                }
            }
            is SharedSettingsIntent.InstallMarketplaceApp -> {
                runAction(CONNECTED_APPS_ACTION_INSTALL, intent.clientRecordId) {
                    dataSource.installMarketplaceApp(intent.clientRecordId)
                        .applyConnectedAppsActionResult(CONNECTED_APPS_ACTION_INSTALL, intent.clientRecordId)
                }
            }
            is SharedSettingsIntent.UninstallMarketplaceApp -> {
                runAction(CONNECTED_APPS_ACTION_UNINSTALL, intent.clientRecordId) {
                    dataSource.uninstallMarketplaceApp(intent.clientRecordId)
                        .applyConnectedAppsActionResult(CONNECTED_APPS_ACTION_UNINSTALL, intent.clientRecordId)
                }
            }
            is SharedSettingsIntent.UpdateServerProfile -> {
                val action = intent.request.serverProfileActionKey
                runAction(action) {
                    dataSource.updateServerProfile(intent.request).applyServerSnapshotActionResult(action)
                }
            }
            is SharedSettingsIntent.CreateServerInvite -> {
                runAction(SERVER_ACTION_INVITE_CREATE) {
                    dataSource.createServerInvite(intent.request).applyServerActionResult(SERVER_ACTION_INVITE_CREATE)
                }
            }
            is SharedSettingsIntent.RevokeServerInvite -> {
                runAction(SERVER_ACTION_INVITE_REVOKE, intent.inviteId) {
                    dataSource.revokeServerInvite(intent.inviteId).applyServerActionResult(SERVER_ACTION_INVITE_REVOKE, intent.inviteId)
                }
            }
            is SharedSettingsIntent.CreateServerJoinLink -> {
                runAction(SERVER_ACTION_JOIN_LINK_CREATE) {
                    dataSource.createServerJoinLink(intent.request).applyServerSnapshotReloadResult(SERVER_ACTION_JOIN_LINK_CREATE)
                }
            }
            is SharedSettingsIntent.RevokeServerJoinLink -> {
                runAction(SERVER_ACTION_JOIN_LINK_REVOKE, intent.linkId) {
                    dataSource.revokeServerJoinLink(intent.linkId).applyServerActionResult(SERVER_ACTION_JOIN_LINK_REVOKE, intent.linkId)
                }
            }
            is SharedSettingsIntent.RenameComputer -> {
                runAction(COMPUTER_ACTION_RENAME, intent.request.machineId) {
                    dataSource.renameComputer(intent.request).applyComputerSnapshotActionResult(COMPUTER_ACTION_RENAME, intent.request.machineId)
                }
            }
            is SharedSettingsIntent.UpdateComputerDescription -> {
                runAction(COMPUTER_ACTION_DESCRIPTION, intent.request.machineId) {
                    dataSource.updateComputerDescription(intent.request).applyComputerSnapshotActionResult(COMPUTER_ACTION_DESCRIPTION, intent.request.machineId)
                }
            }
            is SharedSettingsIntent.DeleteComputer -> {
                runAction(COMPUTER_ACTION_DELETE, intent.machineId) {
                    dataSource.deleteComputer(intent.machineId).applyComputerDeleteActionResult(COMPUTER_ACTION_DELETE, intent.machineId)
                }
            }
            is SharedSettingsIntent.RotateComputerKey -> {
                runAction(COMPUTER_ACTION_ROTATE_KEY, intent.machineId) {
                    dataSource.rotateComputerKey(intent.machineId).applyComputerRotateKeyActionResult(COMPUTER_ACTION_ROTATE_KEY, intent.machineId)
                }
            }
            is SharedSettingsIntent.RegisterComputer -> {
                runAction(COMPUTER_ACTION_REGISTER) {
                    _state.value = _state.value.copy(
                        addComputerDialog = _state.value.addComputerDialog?.copy(
                            registering = true,
                            errorMessage = null,
                            copiedCommand = null
                        )
                    )
                    val result = dataSource.registerComputer(intent.request)
                    when (result) {
                        is DataResult.Content -> {
                            val state = _state.value.addComputerDialog
                            val commands = dataSource.buildAddComputerSetupCommands(result.data.apiKey.orEmpty())
                            _state.value = _state.value.copy(
                                addComputerDialog = state?.copy(
                                    step = AddComputerStep.Waiting,
                                    machineId = result.data.machine.id,
                                    apiKeyPrefix = result.data.apiKeyPrefix,
                                    daemonRunCommand = commands.daemonRunCommand,
                                    computerSetupCommand = commands.computerSetupCommand,
                                    registering = false,
                                    errorMessage = null
                                )
                            )
                            dataSource.loadComputers(forceComputers = true).applyToComputersListState()
                            result.applyComputerRegisterActionResult(result.data.machine.id)
                        }
                        is DataResult.Empty -> {
                            _state.value = _state.value.copy(
                                addComputerDialog = _state.value.addComputerDialog?.copy(
                                    registering = false,
                                    errorMessage = result.reason.name
                                )
                            )
                            result.applyComputerRegisterActionResult()
                        }
                        is DataResult.Error -> {
                            _state.value = _state.value.copy(
                                addComputerDialog = _state.value.addComputerDialog?.copy(
                                    registering = false,
                                    errorMessage = result.error.code
                                )
                            )
                            result.applyComputerRegisterActionResult()
                        }
                        is DataResult.NoPermission -> {
                            _state.value = _state.value.copy(
                                addComputerDialog = _state.value.addComputerDialog?.copy(
                                    registering = false,
                                    errorMessage = result.blocker.code
                                )
                            )
                            result.applyComputerRegisterActionResult()
                        }
                    }
                }
            }
            is SharedSettingsIntent.ShowAddComputerDialog ->
                _state.value = _state.value.copy(
                    addComputerDialog = _state.value.addComputerDialog ?: _state.value.computers.toAddComputerDialogState()
                )
            is SharedSettingsIntent.DismissAddComputerDialog -> dismissAddComputerDialog(cleanupPendingMachine = true)
            is SharedSettingsIntent.SetAddComputerStep ->
                _state.value = _state.value.copy(
                    addComputerDialog = _state.value.addComputerDialog?.copy(step = intent.step)
                )
            is SharedSettingsIntent.SetAddComputerType ->
                _state.value = _state.value.copy(
                    addComputerDialog = _state.value.addComputerDialog?.copy(machineType = intent.machineType)
                )
            is SharedSettingsIntent.SetAddComputerName ->
                _state.value = _state.value.copy(
                    addComputerDialog = _state.value.addComputerDialog?.copy(name = intent.name)
                )
            is SharedSettingsIntent.SetAddComputerRegistration ->
                _state.value = _state.value.copy(
                    addComputerDialog = _state.value.addComputerDialog?.copy(
                        machineId = intent.machineId,
                        apiKeyPrefix = intent.apiKeyPrefix,
                        step = AddComputerStep.Waiting
                    )
                )
            is SharedSettingsIntent.SetAddComputerError ->
                _state.value = _state.value.copy(
                    addComputerDialog = _state.value.addComputerDialog?.copy(errorMessage = intent.message)
                )
            is SharedSettingsIntent.PollAddComputerMachineStatus ->
                scope.launch {
                    dataSource.loadComputers(forceComputers = true).applyToComputersListState()
                    maybeAdvanceAddComputerDialogFromMachines(intent.machineId)
                }
            is SharedSettingsIntent.CopyAddComputerCommand -> {
                val dialog = _state.value.addComputerDialog ?: return
                val command =
                    when (intent.target) {
                        AddComputerCommandTarget.Daemon -> dialog.daemonRunCommand
                        AddComputerCommandTarget.Computer -> dialog.computerSetupCommand
                    }?.takeIf { it.isNotBlank() } ?: return
                _state.value = _state.value.copy(
                    addComputerDialog = dialog.copy(copiedCommand = intent.target),
                    effect = SharedSettingsEffect.CopyText(command)
                )
            }
            SharedSettingsIntent.ClearAddComputerCopiedCommand ->
                _state.value = _state.value.copy(
                    addComputerDialog = _state.value.addComputerDialog?.copy(copiedCommand = null)
                )
            SharedSettingsIntent.CompleteAddComputerDialog ->
                scope.launch { completeAddComputerDialog() }
            is SharedSettingsIntent.ApplyRealtimeEvent -> applyRealtimeEvent(intent.serverId, intent.event)
            is SharedSettingsIntent.UpdateServerAgreement -> {
                runAction(SERVER_ACTION_AGREEMENT_SAVE) {
                    dataSource.updateServerAgreement(intent.request).applyServerSnapshotReloadResult(SERVER_ACTION_AGREEMENT_SAVE)
                }
            }
            is SharedSettingsIntent.UpdateServerOnboarding -> {
                runAction(SERVER_ACTION_ONBOARDING_SAVE) {
                    dataSource.updateServerOnboarding(intent.request).applyServerSnapshotReloadResult(SERVER_ACTION_ONBOARDING_SAVE)
                }
            }
            is SharedSettingsIntent.UpdateServerTranslation -> {
                runAction(SERVER_ACTION_TRANSLATION_TOGGLE) {
                    dataSource.updateServerTranslation(intent.request).applyServerSnapshotReloadResult(SERVER_ACTION_TRANSLATION_TOGGLE)
                }
            }
            is SharedSettingsIntent.UpdateServerSidebarOrder -> {
                runAction(SERVER_ACTION_SIDEBAR_ORDER) {
                    dataSource.updateServerSidebarOrder(intent.request).applyServerSnapshotReloadResult(SERVER_ACTION_SIDEBAR_ORDER)
                }
            }
            is SharedSettingsIntent.UpdateServerMemberRole -> {
                runAction(SERVER_ACTION_MEMBER_ROLE, intent.request.memberId) {
                    dataSource.updateServerMemberRole(intent.request).applyServerActionResult(SERVER_ACTION_MEMBER_ROLE, intent.request.memberId)
                }
            }
            is SharedSettingsIntent.UpdateSystemChannelVisibility -> {
                runAction(SERVER_ACTION_SYSTEM_CHANNEL_HIDE) {
                    dataSource.updateSystemChannelVisibility(intent.allChannelId, intent.hide).applyServerSnapshotReloadResult(SERVER_ACTION_SYSTEM_CHANNEL_HIDE)
                }
            }
            is SharedSettingsIntent.RestoreSystemChannel -> {
                runAction(SERVER_ACTION_SYSTEM_CHANNEL_RESTORE) {
                    dataSource.restoreSystemChannel().applyServerSnapshotReloadResult(SERVER_ACTION_SYSTEM_CHANNEL_RESTORE)
                }
            }
            is SharedSettingsIntent.RemoveServerMember -> {
                runAction(SERVER_ACTION_MEMBER_REMOVE, intent.memberId) {
                    dataSource.removeServerMember(intent.memberId).applyServerActionResult(SERVER_ACTION_MEMBER_REMOVE, intent.memberId)
                }
            }
            SharedSettingsIntent.LeaveServer -> {
                runAction(SERVER_ACTION_LEAVE) {
                    dataSource.leaveServer().applyServerActionResult(SERVER_ACTION_LEAVE)
                }
            }
            is SharedSettingsIntent.DeleteServer -> {
                runAction(SERVER_ACTION_DELETE) {
                    dataSource.deleteServer(intent.request).applyServerActionResult(SERVER_ACTION_DELETE)
                }
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
            is SharedSettingsIntent.SetForegroundKeepAlivePreference -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_FOREGROUND_KEEP_ALIVE, inProgress = true)
                    )
                dataSource.setForegroundKeepAlivePreference(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_FOREGROUND_KEEP_ALIVE)
            }
            is SharedSettingsIntent.SetServerMute -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_SERVER_MUTE, inProgress = true)
                    )
                dataSource.setServerMute(intent.enabled).applyMobileActionResult(MOBILE_ACTION_SERVER_MUTE)
            }
            is SharedSettingsIntent.SetServerNotificationMode -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_SERVER_MUTE, inProgress = true)
                    )
                dataSource.setServerNotificationMode(intent.mode).applyMobileActionResult(MOBILE_ACTION_SERVER_MUTE)
            }
            is SharedSettingsIntent.SetLanguageRegionServerTranslation -> {
                runAction(LANGUAGE_REGION_ACTION_SERVER_TRANSLATION) {
                    dataSource.setLanguageRegionServerTranslation(intent.enabled)
                        .applyLanguageRegionActionResult(LANGUAGE_REGION_ACTION_SERVER_TRANSLATION)
                }
            }
            is SharedSettingsIntent.SetTranslationMode -> {
                runAction(LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY) {
                    dataSource.setTranslationMode(intent.mode)
                        .applyLanguageRegionActionResult(LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY)
                }
            }
            is SharedSettingsIntent.SetTranslationTargetLanguage -> {
                runAction(LANGUAGE_REGION_ACTION_LANGUAGE) {
                    dataSource.setTranslationTargetLanguage(intent.language)
                        .applyLanguageRegionActionResult(LANGUAGE_REGION_ACTION_LANGUAGE)
                }
            }
            is SharedSettingsIntent.UpdateLanguageRegionPreference -> {
                val action = intent.request.toLanguageRegionAction()
                runAction(action) {
                    dataSource.updateLanguageRegionPreference(intent.request).applyLanguageRegionActionResult(action)
                }
            }
            is SharedSettingsIntent.UpdateAppearance -> {
                runAction(APPEARANCE_ACTION_MESSAGE_FONT_SIZE) {
                    dataSource.updateAppearance(intent.request).applyAppearanceActionResult(APPEARANCE_ACTION_MESSAGE_FONT_SIZE)
                }
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
            is SharedSettingsIntent.SetLinkOpenMode -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_LINK_OPEN_MODE, inProgress = true)
                    )
                dataSource.setLinkOpenMode(intent.mode)
                    .applyMobileActionResult(MOBILE_ACTION_LINK_OPEN_MODE)
            }
            is SharedSettingsIntent.SetHttp3Enabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_HTTP3, inProgress = true)
                    )
                val result = dataSource.setHttp3Enabled(intent.enabled)
                if (result is DataResult.Error) {
                    // A rejection carries the controller's current readiness.
                    // Refresh the local snapshot so a stale-ready row cannot
                    // keep offering an action the runtime now rejects.
                    dataSource.loadMobilePreferences().applyMobileActionResult(
                        action = MOBILE_ACTION_HTTP3,
                        errorCodeOverride = result.error.code
                    )
                } else {
                    result.applyMobileActionResult(MOBILE_ACTION_HTTP3)
                }
            }
            is SharedSettingsIntent.SetCompactConsecutiveMessages -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_COMPACT_MESSAGES, inProgress = true)
                    )
                dataSource.setCompactConsecutiveMessages(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_COMPACT_MESSAGES)
            }
            is SharedSettingsIntent.SetLongMessageCollapseEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_LONG_MESSAGE_COLLAPSE, inProgress = true)
                    )
                dataSource.setLongMessageCollapseEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_LONG_MESSAGE_COLLAPSE)
            }
            is SharedSettingsIntent.SetSingleTapOpensThreadEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_SINGLE_TAP_OPENS_THREAD, inProgress = true)
                    )
                dataSource.setSingleTapOpensThreadEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_SINGLE_TAP_OPENS_THREAD)
            }
            is SharedSettingsIntent.SetImageAnnotationEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_IMAGE_ANNOTATION, inProgress = true)
                    )
                dataSource.setImageAnnotationEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_IMAGE_ANNOTATION)
            }
            is SharedSettingsIntent.SetForegroundMessageBanners -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS, inProgress = true)
                    )
                dataSource.setForegroundMessageBanners(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS)
            }
            is SharedSettingsIntent.SetActivityTaskStatusBadges -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES, inProgress = true)
                    )
                dataSource.setActivityTaskStatusBadges(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES)
            }
            is SharedSettingsIntent.SetComposerSendKey -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_COMPOSER_SEND_KEY, inProgress = true)
                    )
                dataSource.setComposerSendKey(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_COMPOSER_SEND_KEY)
            }
            is SharedSettingsIntent.SetMultiShellHostEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_MULTI_SHELL_HOST, inProgress = true)
                    )
                dataSource.setMultiShellHostEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_MULTI_SHELL_HOST)
            }
            is SharedSettingsIntent.SetServerDrawerEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_SERVER_DRAWER, inProgress = true)
                    )
                dataSource.setServerDrawerEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_SERVER_DRAWER)
            }
            is SharedSettingsIntent.SetThreadRowAffordance -> {
                _state.value =
                    _state.value.copy(
                        actionState =
                            SharedSettingsActionState(
                                MOBILE_ACTION_THREAD_ROW_AFFORDANCES,
                                itemId = intent.affordance,
                                inProgress = true
                            )
                    )
                dataSource.setThreadRowAffordance(intent.affordance, intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_THREAD_ROW_AFFORDANCES, intent.affordance)
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
            is SharedSettingsIntent.SetApiEnvironment -> {
                val changedMessage = apiEnvironmentChangedMessage(intent.environmentKey)
                runAction(DEVELOPER_ACTION_API_ENVIRONMENT, intent.environmentKey) {
                    dataSource.setApiEnvironment(intent.environmentKey)
                        .applyDeveloperActionResult(
                            DEVELOPER_ACTION_API_ENVIRONMENT,
                            intent.environmentKey,
                            changedMessage
                        )
                }
            }
            is SharedSettingsIntent.SaveApiEnvironment -> {
                val changedMessage = apiEnvironmentChangedMessage(intent.request)
                runAction(DEVELOPER_ACTION_API_ENVIRONMENT, intent.request.key) {
                    dataSource.saveApiEnvironment(intent.request)
                        .applyDeveloperActionResult(
                            DEVELOPER_ACTION_API_ENVIRONMENT,
                            intent.request.key,
                            changedMessage
                        )
                }
            }
            SharedSettingsIntent.CheckQuiverUpdate -> {
                runAction(DEVELOPER_ACTION_QUIVER_UPDATE_CHECK) {
                    dataSource.checkQuiverUpdate()
                        .applyDeveloperActionResult(DEVELOPER_ACTION_QUIVER_UPDATE_CHECK)
                }
            }
            is SharedSettingsIntent.CompleteStorageClear ->
                completeStorageClear(intent)
            SharedSettingsIntent.CopyDiagnostics ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.CopyDiagnostics)
            SharedSettingsIntent.ShareDiagnostics ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.ShareDiagnostics)
            SharedSettingsIntent.ClearDiagnostics ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.ClearDiagnostics)
            is SharedSettingsIntent.SetRecompositionProfilerEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(DIAGNOSTICS_ACTION_PROFILER_ENABLED, inProgress = true)
                    )
                dataSource.setRecompositionProfilerEnabled(intent.enabled)
                    .applyDiagnosticsActionResult(DIAGNOSTICS_ACTION_PROFILER_ENABLED)
            }
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
            SharedSettingsDestination.Administration ->
                dataSource.loadServer().applyToState(destination) { copy(server = it) }
            SharedSettingsDestination.ConnectedApps ->
                dataSource.loadConnectedApps().applyToState(destination) { copy(connectedApps = it) }
            SharedSettingsDestination.Billing ->
                dataSource.loadBilling().applyToState(destination) { copy(billing = it) }
            SharedSettingsDestination.LanguageRegion -> {
                dataSource.loadMobilePreferences().applyToState(destination) { copy(mobile = it) }
                dataSource.loadLanguageRegion().applyToState(destination) { copy(languageRegion = it) }
            }
            SharedSettingsDestination.Appearance ->
                dataSource.loadAppearance().applyToState(destination) { copy(appearance = it) }
            SharedSettingsDestination.Notifications ->
                dataSource.loadMobile().applyToState(destination) { copy(mobile = it) }
            SharedSettingsDestination.Mobile ->
                dataSource.loadMobilePreferences().applyToState(destination) { copy(mobile = it) }
            SharedSettingsDestination.Storage -> loadStorage()
            SharedSettingsDestination.About ->
                loadAboutWithUpdateStatus()
            SharedSettingsDestination.OpenSourceLicenses ->
                dataSource.loadOpenSourceLicenses().applyToState(destination) { copy(openSourceLicenses = it) }
            SharedSettingsDestination.Developer ->
                dataSource.loadDeveloper().applyToState(destination) { copy(developer = it) }
            SharedSettingsDestination.Diagnostics ->
                dataSource.loadDiagnostics().applyToState(destination) { copy(diagnostics = it) }
            SharedSettingsDestination.Router -> loadHome()
        }
    }

    private suspend fun refreshComputers() {
        dataSource.loadComputers(forceComputers = true)
            .applyToState(SharedSettingsDestination.Computers) { copy(computers = it) }
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

    private suspend fun loadAboutWithUpdateStatus() {
        dataSource.loadAbout().applyToState(SharedSettingsDestination.About) { copy(about = it) }
        val updateResult = dataSource.checkQuiverUpdateStatus()
        when (val developerResult = dataSource.loadDeveloper()) {
            is DataResult.Content -> {
                val developer =
                    when (updateResult) {
                        is DataResult.Content ->
                            developerResult.data.copy(updateCheck = updateResult.data)
                        is DataResult.Empty ->
                            developerResult.data.copy(
                                updateCheck =
                                    SharedSettingsUpdateCheckSnapshot(
                                        supported = developerResult.data.updateCheck.supported,
                                        statusLabel = "Check failed",
                                        detail = "Update check returned no data. Try again later.",
                                        errorCode = "update_check_empty"
                                    )
                            )
                        is DataResult.Error ->
                            developerResult.data.copy(
                                updateCheck =
                                    SharedSettingsUpdateCheckSnapshot(
                                        supported = developerResult.data.updateCheck.supported,
                                        statusLabel = "Check failed",
                                        detail =
                                            "Could not reach the update server (" +
                                                updateResult.error.code.ifBlank { "unknown" } +
                                                "). Check your network and try again.",
                                        errorCode =
                                            updateResult.error.code.ifBlank {
                                                "update_check_failed"
                                            }
                                    )
                            )
                        is DataResult.NoPermission -> developerResult.data
                    }
                _state.value = _state.value.copy(developer = ScreenLoadState.Content(developer))
            }
            is DataResult.Empty,
            is DataResult.Error,
            is DataResult.NoPermission -> Unit
        }
    }

    /**
     * #79: realtime event bridge for the Settings surface. Only handles
     * `RealtimeRefreshScope.Machines` events; other scopes are ignored here
     * because SharedHomeStore already drains them for the home / members /
     * agent detail surfaces. Behavior:
     *
     * - Active route is ComputerDetail and the event's subjectId matches the
     *   open machineId: reload the detail snapshot so status / daemonVersion /
     *   runtimes / lastHeartbeat reflect the latest server state.
     * - Active route is Computers (list): reload the list so card status dots
     *   flip live.
     * - Any other route: ignored (no excessive refresh).
     */
    private suspend fun applyRealtimeEvent(serverId: String, event: RealtimeEvent) {
        if (event is ServerNotificationPreferencesChanged) {
            if (event.serverId.value != serverId) return
            when (_state.value.route.destination) {
                SharedSettingsDestination.Notifications ->
                    dataSource.loadMobile().applyToState(SharedSettingsDestination.Notifications) { copy(mobile = it) }
                else -> Unit
            }
            return
        }
        if (event !is RealtimeRefreshRequired) return
        if (event.scope != RealtimeRefreshScope.Machines) return
        if (event.serverId?.value != null && event.serverId.value != serverId) return
        val machineId = event.subjectId?.takeIf { it.isNotBlank() } ?: return
        when (_state.value.route.destination) {
            SharedSettingsDestination.ComputerDetail -> {
                val openId = _state.value.route.itemId.orEmpty()
                if (openId.isNotBlank() && openId == machineId) {
                    dataSource
                        .loadComputerDetail(machineId, forceComputers = true)
                        .applyToState(SharedSettingsDestination.ComputerDetail) { copy(computerDetail = it) }
                }
            }
            SharedSettingsDestination.Computers -> {
                dataSource.loadComputers(forceComputers = true).applyToComputersListState()
                maybeAdvanceAddComputerDialogFromMachines(machineId)
            }
            else -> Unit
        }
    }

    private fun dismissAddComputerDialog(cleanupPendingMachine: Boolean) {
        val dialog = _state.value.addComputerDialog
        _state.value = _state.value.copy(addComputerDialog = null)
        val machineId = dialog?.machineId?.takeIf { it.isNotBlank() } ?: return
        if (!cleanupPendingMachine || dialog.step == AddComputerStep.Connected) return
        scope.launch {
            dataSource.deleteComputer(machineId)
            dataSource.loadComputers(forceComputers = true).applyToComputersListState()
        }
    }

    private suspend fun completeAddComputerDialog() {
        val dialog = _state.value.addComputerDialog ?: return
        val machineId = dialog.machineId?.takeIf { it.isNotBlank() } ?: run {
            dismissAddComputerDialog(cleanupPendingMachine = false)
            return
        }
        _state.value = _state.value.copy(addComputerDialog = dialog.copy(saving = true, errorMessage = null))
        val finalName = dialog.name.trim()
        if (finalName.isNotBlank() && finalName != "my-computer") {
            dataSource.renameComputer(SettingsComputerRenameRequest(machineId = machineId, name = finalName))
        }
        dataSource.loadComputers(forceComputers = true).applyToComputersListState()
        _state.value =
            _state.value.copy(
                addComputerDialog = null,
                route = SharedSettingsRoute(SharedSettingsDestination.ComputerDetail, machineId),
                effect = SharedSettingsEffect.OpenComputer(machineId)
            )
        loadComputerDetail(machineId)
    }

    private fun maybeAdvanceAddComputerDialogFromMachines(machineId: String) {
        val dialog = _state.value.addComputerDialog ?: return
        if (dialog.step != AddComputerStep.Waiting) return
        if (dialog.machineId != null && dialog.machineId != machineId) return
        val machines = (_state.value.computers as? ScreenLoadState.Content)?.data ?: return
        val machine = machines.computers.firstOrNull { it.id == machineId } ?: return
        if (!machine.online) return
        val connectedDetail = listOfNotNull(
            machine.hostname?.takeIf { it.isNotBlank() },
            machine.platform?.takeIf { it.isNotBlank() } ?: "Unknown OS"
        ).joinToString(" - ").takeIf { it.isNotBlank() }
        _state.value = _state.value.copy(
            addComputerDialog = dialog.copy(
                step = AddComputerStep.Connected,
                name = machine.hostname?.takeIf { it.isNotBlank() } ?: machine.name.takeIf { it.isNotBlank() } ?: dialog.name,
                connectedDetail = connectedDetail
            )
        )
    }

    private fun DataResult<SettingsComputersSnapshot>.applyToComputersListState() {
        val next = toScreenLoadState()
        _state.value =
            _state.value.copy(
                computers =
                    when (next) {
                        is ScreenLoadState.Content -> next
                        else -> _state.value.computers
                    },
                routeErrorCode = next.routeErrorCode()
            )
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
                        errorCode =
                            result.code.takeIf {
                                result.status == SystemNotificationActionStatus.Error
                            }
                    ),
                effect = null
            )
    }

    private suspend fun runAction(
        action: String,
        itemId: String? = null,
        block: suspend () -> Unit
    ) {
        val currentAction = _state.value.actionState
        if (currentAction?.action == action && currentAction.itemId == itemId && currentAction.inProgress) {
            return
        }
        _state.value = _state.value.copy(actionState = SharedSettingsActionState(action, itemId, inProgress = true))
        block()
    }

    private fun DataResult<SettingsMobileSnapshot>.applyMobileActionResult(
        action: String,
        itemId: String? = null,
        errorCodeOverride: String? = null
    ) {
        val nextMobile = toScreenLoadState()
        val errorCode = errorCodeOverride ?: nextMobile.routeErrorCode()
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
                        itemId = itemId,
                        inProgress = false,
                        errorCode = errorCode
                    ),
                routeErrorCode = errorCode
            )
    }

    private fun DataResult<SettingsLanguageRegionSnapshot>.applyLanguageRegionActionResult(action: String) {
        val nextLanguageRegion = toScreenLoadState()
        _state.value =
            _state.value.copy(
                languageRegion =
                    when (nextLanguageRegion) {
                        is ScreenLoadState.Content -> nextLanguageRegion
                        else -> _state.value.languageRegion
                    },
                actionState =
                    SharedSettingsActionState(
                        action = action,
                        inProgress = false,
                        errorCode = nextLanguageRegion.routeErrorCode()
                    ),
                routeErrorCode = nextLanguageRegion.routeErrorCode()
            )
    }

    private fun DataResult<SettingsAppearanceSnapshot>.applyAppearanceActionResult(action: String) {
        val nextAppearance = toScreenLoadState()
        _state.value =
            _state.value.copy(
                appearance =
                    when (nextAppearance) {
                        is ScreenLoadState.Content -> nextAppearance
                        else -> _state.value.appearance
                    },
                actionState =
                    SharedSettingsActionState(
                        action = action,
                        inProgress = false,
                        errorCode = nextAppearance.routeErrorCode()
                    ),
                routeErrorCode = nextAppearance.routeErrorCode()
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

    private fun DataResult<AccountProviderLinkStartResult>.applyAccountProviderLinkResult(providerId: String) {
        val resultState = toScreenLoadState()
        _state.value =
            _state.value.copy(
                actionState =
                    SharedSettingsActionState(
                        action = ACCOUNT_ACTION_PROVIDER_CONNECT,
                        itemId = providerId,
                        inProgress = false,
                        errorCode = resultState.routeErrorCode()
                    ),
                routeErrorCode = resultState.routeErrorCode(),
                effect = (this as? DataResult.Content)?.data?.url?.let(SharedSettingsEffect::OpenUrl)
            )
    }

    private fun DataResult<SettingsConnectedAppsSnapshot>.applyConnectedAppsActionResult(
        action: String,
        itemId: String? = null
    ) {
        val nextConnectedApps = toScreenLoadState()
        _state.value =
            _state.value.copy(
                connectedApps =
                    when (nextConnectedApps) {
                        is ScreenLoadState.Content -> nextConnectedApps
                        else -> _state.value.connectedApps
                    },
                actionState =
                    SharedSettingsActionState(
                        action = action,
                        itemId = itemId,
                        inProgress = false,
                        errorCode = nextConnectedApps.routeErrorCode()
                    ),
                routeErrorCode = nextConnectedApps.routeErrorCode()
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

    /**
     * After a successful computer snapshot-affecting action (rename, rotate-key),
     * reload the computer detail snapshot so the page reflects the new server state
     * without a full app refresh. The new snapshot replaces [computerDetail] when
     * it is content; otherwise the existing computerDetail state is kept so a
     * partial reload failure doesn't wipe the user's view of the machine.
     */
    private suspend fun DataResult<SettingsComputerSnapshot>.applyComputerSnapshotActionResult(
        action: String,
        machineId: String
    ) {
        when (this) {
            is DataResult.Content ->
                dataSource.loadComputerDetail(machineId, forceComputers = true).applyComputerDetailActionResult(action, machineId)
            is DataResult.Empty ->
                DataResult.Empty(reason).applyComputerDetailActionResult(action, machineId)
            is DataResult.Error ->
                DataResult.Error(error).applyComputerDetailActionResult(action, machineId)
            is DataResult.NoPermission ->
                DataResult.NoPermission(blocker).applyComputerDetailActionResult(action, machineId)
        }
    }

    /**
     * After a successful computer delete action, clear the page route + reload the
     * list so the deleted machine disappears from the list, and surface the
     * typed error code (e.g. `computer_has_agents` for 409) on failure without
     * dropping the existing detail view.
     */
    private suspend fun DataResult<SettingsComputerActionResult>.applyComputerDeleteActionResult(
        action: String,
        machineId: String
    ) {
        when (this) {
            is DataResult.Content -> {
                val computers = dataSource.loadComputers(forceComputers = true).toScreenLoadState()
                _state.value =
                    _state.value.copy(
                        computers =
                            when (computers) {
                                is ScreenLoadState.Content -> computers
                                else -> _state.value.computers
                            },
                        computerDetail = ScreenLoadState.Loading,
                        route = SharedSettingsRoute(SharedSettingsDestination.Computers),
                        actionState = SharedSettingsActionState(action, itemId = machineId, inProgress = false),
                        routeErrorCode = computers.routeErrorCode()
                    )
            }
            is DataResult.Empty ->
                DataResult.Empty(reason).applyComputerDetailActionResult(action, machineId)
            is DataResult.Error ->
                DataResult.Error(error).applyComputerDetailActionResult(action, machineId)
            is DataResult.NoPermission ->
                DataResult.NoPermission(blocker).applyComputerDetailActionResult(action, machineId)
        }
    }

    private suspend fun DataResult<SettingsComputerRegistrationResult>.applyComputerRegisterActionResult(
        machineId: String? = null
    ) {
        when (this) {
            is DataResult.Content -> {
                _state.value = _state.value.copy(
                    actionState = SharedSettingsActionState(
                        action = COMPUTER_ACTION_REGISTER,
                        itemId = machineId ?: data.machine.id,
                        inProgress = false
                    )
                )
            }
            is DataResult.Empty ->
                _state.value = _state.value.copy(
                    actionState = SharedSettingsActionState(
                        action = COMPUTER_ACTION_REGISTER,
                        inProgress = false,
                        errorCode = reason.name
                    )
                )
            is DataResult.Error ->
                _state.value = _state.value.copy(
                    actionState = SharedSettingsActionState(
                        action = COMPUTER_ACTION_REGISTER,
                        inProgress = false,
                        errorCode = error.code
                    )
                )
            is DataResult.NoPermission ->
                _state.value = _state.value.copy(
                    actionState = SharedSettingsActionState(
                        action = COMPUTER_ACTION_REGISTER,
                        inProgress = false,
                        errorCode = blocker.code
                    )
                )
        }
    }

    private suspend fun DataResult<SettingsComputerKeyRotationResult>.applyComputerRotateKeyActionResult(
        action: String,
        machineId: String
    ) {
        when (this) {
            is DataResult.Content ->
                dataSource.loadComputerDetail(machineId, forceComputers = true).applyComputerDetailActionResult(action, machineId)
            is DataResult.Empty ->
                DataResult.Empty(reason).applyComputerDetailActionResult(action, machineId)
            is DataResult.Error ->
                DataResult.Error(error).applyComputerDetailActionResult(action, machineId)
            is DataResult.NoPermission ->
                DataResult.NoPermission(blocker).applyComputerDetailActionResult(action, machineId)
        }
    }

    private fun DataResult<SettingsComputerDetailSnapshot>.applyComputerDetailActionResult(
        action: String,
        machineId: String
    ) {
        val currentDetail = (_state.value.computerDetail as? ScreenLoadState.Content)?.data
        val next =
            when (this) {
                is DataResult.Content -> {
                    val detail =
                        if (
                            currentDetail?.computer?.id == data.computer.id &&
                            currentDetail.workspacesScanned &&
                            !data.workspacesScanned
                        ) {
                            data.copy(
                                workspaces = currentDetail.workspaces,
                                workspacesScanned = true,
                                workspacesError = currentDetail.workspacesError
                            )
                        } else {
                            data
                        }
                    ScreenLoadState.Content(detail)
                }
                else -> toScreenLoadState()
            }
        _state.value =
            _state.value.copy(
                computerDetail =
                    when (next) {
                        is ScreenLoadState.Content -> next
                        else -> _state.value.computerDetail
                    },
                actionState =
                    SharedSettingsActionState(
                        action = action,
                        itemId = machineId,
                        inProgress = false,
                        errorCode = next.routeErrorCode()
                    ),
                routeErrorCode = next.routeErrorCode()
            )
    }

    private fun DataResult<List<SettingsComputerWorkspaceSnapshot>>.applyComputerWorkspacesActionResult(machineId: String) {
        val current = (_state.value.computerDetail as? ScreenLoadState.Content)?.data
        if (current == null || current.computer.id != machineId) {
            _state.value =
                _state.value.copy(
                    actionState = SharedSettingsActionState(
                        action = COMPUTER_ACTION_SCAN_WORKSPACES,
                        itemId = machineId,
                        inProgress = false,
                        errorCode = "settings_computer_required"
                    )
                )
            return
        }
        val nextDetail =
            when (this) {
                is DataResult.Content ->
                    current.copy(
                        workspaces = data,
                        workspacesScanned = true,
                        workspacesError = null
                    )
                is DataResult.Empty ->
                    current.copy(
                        workspaces = emptyList(),
                        workspacesScanned = true,
                        workspacesError = null
                    )
                is DataResult.Error ->
                    current.copy(
                        workspacesScanned = true,
                        workspacesError = SettingsSectionErrorSnapshot(error.code, error.retryable)
                    )
                is DataResult.NoPermission ->
                    current.copy(
                        workspacesScanned = true,
                        workspacesError = SettingsSectionErrorSnapshot(blocker.code, retryable = false)
                    )
            }
        val errorCode =
            when (this) {
                is DataResult.Error -> error.code
                is DataResult.NoPermission -> blocker.code
                else -> null
            }
        _state.value =
            _state.value.copy(
                computerDetail = ScreenLoadState.Content(nextDetail),
                actionState =
                    SharedSettingsActionState(
                        action = COMPUTER_ACTION_SCAN_WORKSPACES,
                        itemId = machineId,
                        inProgress = false,
                        errorCode = errorCode
                    )
            )
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

    private fun DataResult<SharedSettingsDeveloperSnapshot>.applyDeveloperActionResult(
        action: String,
        itemId: String? = null,
        apiEnvironmentChangedMessage: String? = null
    ) {
        val nextDeveloper = toScreenLoadState()
        _state.value =
            _state.value.copy(
                developer =
                    when (nextDeveloper) {
                        is ScreenLoadState.Content -> nextDeveloper
                        else -> _state.value.developer
                    },
                actionState =
                    SharedSettingsActionState(
                        action = action,
                        itemId = itemId,
                        inProgress = false,
                        errorCode = nextDeveloper.routeErrorCode()
                    ),
                routeErrorCode = nextDeveloper.routeErrorCode(),
                effect =
                    apiEnvironmentChangedMessage
                        ?.takeIf { nextDeveloper is ScreenLoadState.Content }
                        ?.let(SharedSettingsEffect::ApiEnvironmentChanged)
            )
    }

    private fun apiEnvironmentChangedMessage(environmentKey: String): String? {
        val developer = (_state.value.developer as? ScreenLoadState.Content)?.data ?: return null
        if (developer.environmentKey == environmentKey) return null
        val label =
            developer.options.firstOrNull { it.key == environmentKey }?.label
                ?: environmentKey
        return apiEnvironmentChangedMessageForLabel(label)
    }

    private fun apiEnvironmentChangedMessage(request: SharedSettingsApiEnvironmentRequest): String? {
        val developer = (_state.value.developer as? ScreenLoadState.Content)?.data ?: return apiEnvironmentChangedMessageForLabel(request.label)
        val changed =
            developer.environmentKey != request.key ||
                developer.environmentLabel != request.label ||
                developer.apiBaseUrl != request.apiBaseUrl ||
                developer.socketBaseUrl != request.socketBaseUrl ||
                developer.webUrl != request.webUrl
        if (!changed) return null
        return apiEnvironmentChangedMessageForLabel(request.label)
    }

    private fun apiEnvironmentChangedMessageForLabel(label: String): String =
        "API server switched to ${label.ifBlank { "selected environment" }}. Sign in again to continue."

    private suspend fun <T> DataResult<T>.applyToState(
        destination: SharedSettingsDestination,
        update: SharedSettingsState.(ScreenLoadState<T>) -> SharedSettingsState
    ) {
        val startedAt = TimeSource.Monotonic.markNow()
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
            diagnosticsLogger(
                "store_load domain=settings destination=${destination.name} result=${this.settingsStoreDiagnosticsResult()} " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
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
            diagnosticsLogger(
                "store_load domain=settings destination=${destination.name} result=exception code=settings_network " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
        }
    }
}

private fun DataResult<*>.settingsStoreDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty code=${reason.name}"
        is DataResult.Error -> "error code=${error.code}"
        is DataResult.NoPermission -> "no_permission code=${blocker.code}"
    }

private val SharedSettingsIntent.loadsSettingsContent: Boolean
    get() =
        when (this) {
            SharedSettingsIntent.Start,
            is SharedSettingsIntent.OpenDestination,
            is SharedSettingsIntent.OpenComputer,
            SharedSettingsIntent.Back,
            SharedSettingsIntent.Refresh,
            SharedSettingsIntent.RefreshComputers,
            SharedSettingsIntent.RefreshStorage -> true
            SharedSettingsIntent.Logout,
            SharedSettingsIntent.RequestNotificationPermission,
            SharedSettingsIntent.OpenNotificationSettings,
            SharedSettingsIntent.SendTestNotification,
            is SharedSettingsIntent.UpdateProfile,
            SharedSettingsIntent.RequestAccountAvatarUpload,
            is SharedSettingsIntent.ChangeAccountPassword,
            is SharedSettingsIntent.ConnectAccountProvider,
            is SharedSettingsIntent.RegisterConnectedApp,
            is SharedSettingsIntent.UpdateConnectedApp,
            is SharedSettingsIntent.RequestConnectedAppLogoUpload,
            is SharedSettingsIntent.ClearConnectedAppLogo,
            is SharedSettingsIntent.DeleteConnectedApp,
            is SharedSettingsIntent.RequestConnectedAppPublish,
            is SharedSettingsIntent.InstallMarketplaceApp,
            is SharedSettingsIntent.UninstallMarketplaceApp,
            is SharedSettingsIntent.UpdateServerProfile,
            is SharedSettingsIntent.CreateServerInvite,
            is SharedSettingsIntent.RevokeServerInvite,
            is SharedSettingsIntent.CreateServerJoinLink,
            is SharedSettingsIntent.RevokeServerJoinLink,
            is SharedSettingsIntent.UpdateServerAgreement,
            is SharedSettingsIntent.UpdateServerOnboarding,
            is SharedSettingsIntent.UpdateServerTranslation,
            is SharedSettingsIntent.UpdateServerSidebarOrder,
            is SharedSettingsIntent.UpdateServerMemberRole,
            is SharedSettingsIntent.UpdateSystemChannelVisibility,
            SharedSettingsIntent.RestoreSystemChannel,
            is SharedSettingsIntent.RemoveServerMember,
            SharedSettingsIntent.LeaveServer,
            is SharedSettingsIntent.DeleteServer,
            is SharedSettingsIntent.RequestServerConfirmation,
            is SharedSettingsIntent.SetNotificationPreference,
            is SharedSettingsIntent.SetForegroundKeepAlivePreference,
            is SharedSettingsIntent.SetServerMute,
            is SharedSettingsIntent.SetServerNotificationMode,
            is SharedSettingsIntent.SetLanguageRegionServerTranslation,
            is SharedSettingsIntent.SetTranslationMode,
            is SharedSettingsIntent.SetTranslationTargetLanguage,
            is SharedSettingsIntent.UpdateLanguageRegionPreference,
            is SharedSettingsIntent.UpdateAppearance,
            is SharedSettingsIntent.CompleteSystemNotificationAction,
            is SharedSettingsIntent.SetLocale,
            is SharedSettingsIntent.SetMessageAlignment,
            is SharedSettingsIntent.SetLinkOpenMode,
            is SharedSettingsIntent.SetHttp3Enabled,
            is SharedSettingsIntent.SetCompactConsecutiveMessages,
            is SharedSettingsIntent.SetLongMessageCollapseEnabled,
            is SharedSettingsIntent.SetSingleTapOpensThreadEnabled,
            is SharedSettingsIntent.SetImageAnnotationEnabled,
            is SharedSettingsIntent.SetForegroundMessageBanners,
            is SharedSettingsIntent.SetActivityTaskStatusBadges,
            is SharedSettingsIntent.SetComposerSendKey,
            is SharedSettingsIntent.SetMultiShellHostEnabled,
            is SharedSettingsIntent.SetServerDrawerEnabled,
            is SharedSettingsIntent.SetThreadRowAffordance,
            is SharedSettingsIntent.ClearStorage,
            is SharedSettingsIntent.SetApiEnvironment,
            is SharedSettingsIntent.SaveApiEnvironment,
            SharedSettingsIntent.CheckQuiverUpdate,
            is SharedSettingsIntent.CompleteStorageClear,
            SharedSettingsIntent.CopyDiagnostics,
            SharedSettingsIntent.ShareDiagnostics,
            SharedSettingsIntent.ClearDiagnostics,
            is SharedSettingsIntent.SetRecompositionProfilerEnabled,
            SharedSettingsIntent.StartRecompositionProfiler,
            SharedSettingsIntent.StopRecompositionProfiler,
            SharedSettingsIntent.ResetRecompositionProfiler,
            SharedSettingsIntent.RefreshRecompositionProfilerReport,
            SharedSettingsIntent.ExportRecompositionProfilerReport,
            SharedSettingsIntent.SendFeedback,
            is SharedSettingsIntent.ScanComputerWorkspaces,
            is SharedSettingsIntent.RenameComputer,
            is SharedSettingsIntent.UpdateComputerDescription,
            is SharedSettingsIntent.DeleteComputer,
            is SharedSettingsIntent.RotateComputerKey,
            is SharedSettingsIntent.RegisterComputer,
            SharedSettingsIntent.ShowAddComputerDialog,
            SharedSettingsIntent.DismissAddComputerDialog,
            is SharedSettingsIntent.SetAddComputerStep,
            is SharedSettingsIntent.SetAddComputerType,
            is SharedSettingsIntent.SetAddComputerName,
            is SharedSettingsIntent.SetAddComputerRegistration,
            is SharedSettingsIntent.SetAddComputerError,
            is SharedSettingsIntent.PollAddComputerMachineStatus,
            is SharedSettingsIntent.CopyAddComputerCommand,
            SharedSettingsIntent.ClearAddComputerCopiedCommand,
            SharedSettingsIntent.CompleteAddComputerDialog,
            is SharedSettingsIntent.DeleteComputer,
            is SharedSettingsIntent.RotateComputerKey,
            is SharedSettingsIntent.ApplyRealtimeEvent,
            SharedSettingsIntent.ConsumeEffect -> false
        }

private fun <T> DataResult<T>.toScreenLoadState(): ScreenLoadState<T> =
    when (this) {
        is DataResult.Content -> ScreenLoadState.Content(data)
        is DataResult.Empty -> ScreenLoadState.Empty(reason)
        is DataResult.Error -> ScreenLoadState.Error(error)
        is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
    }

private fun DataResult<SettingsComputersSnapshot>.withAddComputerGate(
    canManageServer: Boolean
): DataResult<SettingsComputersSnapshot> =
    when (this) {
        is DataResult.Content ->
            DataResult.Content(
                data.copy(
                    canAddComputer = canManageServer,
                    addComputerUnavailableReason =
                        if (canManageServer) null else data.addComputerUnavailableReason ?: "server_admin_required"
                )
            )
        is DataResult.Empty -> this
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
    }

private fun DataResult<SettingsComputerDetailSnapshot>.withComputerManageGate(
    canManageServer: Boolean
): DataResult<SettingsComputerDetailSnapshot> =
    when (this) {
        is DataResult.Content -> DataResult.Content(data.copy(canManageMachines = canManageServer))
        is DataResult.Empty -> this
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
    }

private fun ScreenLoadState<SettingsComputersSnapshot>.toAddComputerDialogState(): AddComputerDialogState {
    val snapshot = (this as? ScreenLoadState.Content)?.data
    val canManage = snapshot?.canAddComputer ?: false
    return AddComputerDialogState(
        step = AddComputerStep.Type,
        machineType = AddComputerType.Local,
        name = "my-computer",
        canManage = canManage,
        atLimit = false,
        errorMessage = if (canManage) null else snapshot?.addComputerUnavailableReason ?: "server_admin_required"
    )
}

private fun StoredAuthSession?.settingsCurrentServer(): StoredAuthServer? {
    val session = this ?: return null
    val selectedId = session.currentServerId
    return selectedId?.let { id -> session.servers.firstOrNull { it.id == id } }
        ?: session.servers.firstOrNull()
}

private fun StoredAuthSession?.canManageCurrentServer(): Boolean =
    settingsCurrentServer()?.role.let { it == "owner" || it == "admin" }

private fun StoredAuthSession?.currentServerSlugForSettings(): String? {
    val session = this ?: return null
    return session.selectedServerSlug?.takeIf { it.isNotBlank() }
        ?: session.settingsCurrentServer()?.slug?.takeIf { it.isNotBlank() }
        ?: session.servers.firstOrNull()?.slug?.takeIf { it.isNotBlank() }
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
        SharedSettingsDestination.Administration -> server is ScreenLoadState.Content
        SharedSettingsDestination.ConnectedApps -> connectedApps is ScreenLoadState.Content
        SharedSettingsDestination.Billing -> billing is ScreenLoadState.Content
        SharedSettingsDestination.LanguageRegion -> languageRegion is ScreenLoadState.Content
        SharedSettingsDestination.Appearance -> appearance is ScreenLoadState.Content
        SharedSettingsDestination.Notifications -> mobile is ScreenLoadState.Content
        SharedSettingsDestination.Mobile -> mobile is ScreenLoadState.Content
        SharedSettingsDestination.Storage -> storage is ScreenLoadState.Content
        SharedSettingsDestination.About -> about is ScreenLoadState.Content
        SharedSettingsDestination.OpenSourceLicenses -> openSourceLicenses is ScreenLoadState.Content
        SharedSettingsDestination.Developer -> developer is ScreenLoadState.Content
        SharedSettingsDestination.Diagnostics -> diagnostics is ScreenLoadState.Content
        SharedSettingsDestination.Router -> home is ScreenLoadState.Content
    }

internal const val MOBILE_ACTION_NOTIFICATION_PREFERENCE = "notificationPreference"
internal const val MOBILE_ACTION_FOREGROUND_KEEP_ALIVE = "foregroundKeepAlive"
internal const val MOBILE_ACTION_SERVER_MUTE = "serverMute"
internal const val MOBILE_ACTION_PERMISSION = "notificationPermission"
internal const val MOBILE_ACTION_SETTINGS = "openNotificationSettings"
internal const val DEVELOPER_ACTION_API_ENVIRONMENT = "apiEnvironment"
internal const val DEVELOPER_ACTION_QUIVER_UPDATE_CHECK = "quiverUpdateCheck"
internal const val MOBILE_ACTION_TEST_NOTIFICATION = "sendTestNotification"
internal const val MOBILE_ACTION_LANGUAGE = "language"
internal const val MOBILE_ACTION_MESSAGE_ALIGNMENT = "messageAlignment"
internal const val MOBILE_ACTION_LINK_OPEN_MODE = "linkOpenMode"
internal const val MOBILE_ACTION_HTTP3 = "http3"
internal const val MOBILE_ACTION_COMPACT_MESSAGES = "compactMessages"
internal const val MOBILE_ACTION_LONG_MESSAGE_COLLAPSE = "longMessageCollapse"
internal const val MOBILE_ACTION_SINGLE_TAP_OPENS_THREAD = "singleTapOpensThread"
internal const val MOBILE_ACTION_IMAGE_ANNOTATION = "imageAnnotation"
internal const val MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS = "foregroundMessageBanners"
internal const val MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES = "activityTaskStatusBadges"
internal const val MOBILE_ACTION_COMPOSER_SEND_KEY = "composerSendKey"
internal const val MOBILE_ACTION_MULTI_SHELL_HOST = "multiShellHost"
internal const val MOBILE_ACTION_SERVER_DRAWER = "serverDrawer"
internal const val MOBILE_ACTION_THREAD_ROW_AFFORDANCES = "threadRowAffordances"
internal const val LANGUAGE_REGION_ACTION_SERVER_TRANSLATION = "languageRegionServerTranslation"
internal const val COMPUTER_ACTION_RENAME = "renameComputer"
internal const val COMPUTER_ACTION_DESCRIPTION = "updateComputerDescription"
internal const val COMPUTER_ACTION_DELETE = "deleteComputer"
internal const val COMPUTER_ACTION_ROTATE_KEY = "rotateComputerKey"
internal const val COMPUTER_ACTION_REGISTER = "registerComputer"
internal const val COMPUTER_ACTION_SCAN_WORKSPACES = "scanComputerWorkspaces"
internal const val COMPUTER_ACTION_AT_LIMIT = "atLimit"
internal const val LANGUAGE_REGION_ACTION_AUTO_TRANSLATION = "languageRegionAutoTranslation"
internal const val LANGUAGE_REGION_ACTION_LANGUAGE = "languageRegionLanguage"
internal const val LANGUAGE_REGION_ACTION_TIMEZONE = "languageRegionTimezone"
internal const val LANGUAGE_REGION_ACTION_TIME_FORMAT = "languageRegionTimeFormat"
internal const val LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY = "languageRegionTranslationDisplay"
internal const val APPEARANCE_ACTION_MESSAGE_FONT_SIZE = "appearanceMessageFontSize"
internal const val CONNECTED_APPS_ACTION_REGISTER = "connectedAppsRegister"
internal const val CONNECTED_APPS_ACTION_UPDATE = "connectedAppsUpdate"
internal const val CONNECTED_APPS_ACTION_LOGO_UPLOAD = "connectedAppsLogoUpload"
internal const val CONNECTED_APPS_ACTION_LOGO_CLEAR = "connectedAppsLogoClear"
internal const val CONNECTED_APPS_ACTION_DELETE = "connectedAppsDelete"
internal const val CONNECTED_APPS_ACTION_PUBLISH = "connectedAppsPublish"
internal const val CONNECTED_APPS_ACTION_INSTALL = "connectedAppsInstall"
internal const val CONNECTED_APPS_ACTION_UNINSTALL = "connectedAppsUninstall"
internal const val ACCOUNT_ACTION_PROFILE_SAVE = "saveProfile"
internal const val ACCOUNT_ACTION_AVATAR_UPLOAD = "accountAvatarUpload"
internal const val ACCOUNT_ACTION_PASSWORD_CHANGE = "accountPasswordChange"
internal const val ACCOUNT_ACTION_PROVIDER_CONNECT = "accountProviderConnect"
internal const val SERVER_ACTION_PROFILE_SAVE = "serverProfileSave"
internal const val SERVER_ACTION_INVITE_CREATE = "serverInviteCreate"
internal const val SERVER_ACTION_INVITE_REVOKE = "serverInviteRevoke"
internal const val SERVER_ACTION_JOIN_LINK_CREATE = "serverJoinLinkCreate"
internal const val SERVER_ACTION_JOIN_LINK_REVOKE = "serverJoinLinkRevoke"
internal const val SERVER_ACTION_ONBOARDING_TOGGLE = "serverOnboardingToggle"
internal const val SERVER_ACTION_ONBOARDING_SAVE = "serverOnboardingSave"
internal const val SERVER_ACTION_AGREEMENT_SAVE = "serverAgreementSave"
internal const val SERVER_ACTION_MEMBER_PERMISSIONS_SAVE = "serverMemberPermissionsSave"
internal const val SERVER_ACTION_TRANSLATION_TOGGLE = "serverTranslationToggle"
internal const val SERVER_ACTION_SIDEBAR_ORDER = "serverSidebarOrder"
internal const val SERVER_ACTION_MEMBER_ROLE = "serverMemberRole"
internal const val SERVER_ACTION_MEMBER_REMOVE = "serverMemberRemove"
internal const val SERVER_ACTION_LEAVE = "serverLeave"
internal const val SERVER_ACTION_DELETE = "serverDelete"
internal const val SERVER_ACTION_CONFIRM = "serverConfirm"
internal const val SERVER_ACTION_SYSTEM_CHANNEL_HIDE = "serverSystemChannelHide"
internal const val SERVER_ACTION_SYSTEM_CHANNEL_RESTORE = "serverSystemChannelRestore"
internal const val DIAGNOSTICS_ACTION_PROFILER_START = "diagnosticsProfilerStart"
internal const val DIAGNOSTICS_ACTION_PROFILER_STOP = "diagnosticsProfilerStop"
internal const val DIAGNOSTICS_ACTION_PROFILER_ENABLED = "diagnosticsProfilerEnabled"
internal const val DIAGNOSTICS_ACTION_PROFILER_RESET = "diagnosticsProfilerReset"
internal const val DIAGNOSTICS_ACTION_PROFILER_REPORT = "diagnosticsProfilerReport"
internal const val DIAGNOSTICS_ACTION_PROFILER_EXPORT = "diagnosticsProfilerExport"

private fun SettingsLanguageRegionPreferenceUpdateRequest.toLanguageRegionAction(): String =
    when {
        preferredLanguage != null || clearPreferredLanguage -> LANGUAGE_REGION_ACTION_LANGUAGE
        preferredTimezone != null || clearPreferredTimezone -> LANGUAGE_REGION_ACTION_TIMEZONE
        preferredTimeFormat != null || clearPreferredTimeFormat -> LANGUAGE_REGION_ACTION_TIME_FORMAT
        autoTranslationEnabled != null -> LANGUAGE_REGION_ACTION_AUTO_TRANSLATION
        preferredTranslationDisplay != null -> LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY
        else -> "languageRegionPreference"
    }

internal val SettingsServerProfileUpdateRequest.serverProfileActionKey: String
    get() =
        if (hideHumansFromMembers != null && name == null) {
            SERVER_ACTION_MEMBER_PERMISSIONS_SAVE
        } else {
            SERVER_ACTION_PROFILE_SAVE
        }

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

private fun LinkOpenMode.toSettingsMobileLinkOpenModeSnapshot(): SettingsMobileSelectionSnapshot =
    SettingsMobileSelectionSnapshot(
        selectedKey = storageKey,
        selectedLabel = mobileLabel(),
        statusLabel = mobileLabel(),
        detail = "Choose whether web links open in the default browser or inside Raft.",
        options =
            LinkOpenMode.entries.map {
                SettingsMobileSelectionOptionSnapshot(
                    key = it.storageKey,
                    label = it.mobileLabel()
                )
            }
    )

private fun SettingsLanguageRegionSnapshot.withLocalTranslationPreferences(
    mode: TranslationModePreference,
    targetLanguage: String
): SettingsLanguageRegionSnapshot =
    copy(
        browserLanguage =
            SettingsMobileSelectionSnapshot(
                selectedKey = targetLanguage,
                selectedLabel =
                    TranslationTargetLanguages.options.firstOrNull { it.key == targetLanguage }?.label
                        ?: TranslationTargetLanguages.options.first().label,
                statusLabel =
                    TranslationTargetLanguages.options.firstOrNull { it.key == targetLanguage }?.label
                        ?: TranslationTargetLanguages.options.first().label,
                detail = "Stored locally on this device and used as the target for message translation.",
                options =
                    TranslationTargetLanguages.options.map {
                        SettingsMobileSelectionOptionSnapshot(it.key, it.label)
                    },
                available = true
            ),
        translationDisplay =
            SettingsMobileSelectionSnapshot(
                selectedKey = mode.storageKey,
                selectedLabel = mode.mobileLabel(),
                statusLabel = mode.mobileLabel(),
                detail = "Stored locally on this device. Controls whether Thread requests translations automatically, manually, or keeps messages original.",
                options =
                    TranslationModePreference.entries.map {
                        SettingsMobileSelectionOptionSnapshot(
                            key = it.storageKey,
                            label = it.mobileLabel()
                        )
                    },
                available = true
            )
    )

private fun TranslationModePreference.mobileLabel(): String =
    when (this) {
        TranslationModePreference.Automatic -> "Automatic"
        TranslationModePreference.Manual -> "Manual"
        TranslationModePreference.Original -> "Original"
    }

private fun LinkOpenMode.mobileLabel(): String =
    when (this) {
        LinkOpenMode.SystemBrowser -> "Default browser"
        LinkOpenMode.InAppBrowser -> "In-app browser"
    }

private fun NetworkTransportPreferenceSnapshot.toSettingsMobileHttp3Snapshot(): SettingsHttp3Snapshot =
    SettingsHttp3Snapshot(
        requestedEnabled = requestedHttp3Enabled,
        effectiveEnabled = effectiveHttp3Enabled,
        curlRequestedForNewCalls = curlRequestedForNewCalls,
        canEnable = available,
        statusLabel =
            when {
                requestedHttp3Enabled && effectiveHttp3Enabled -> "Preferred"
                requestedHttp3Enabled -> "Unavailable"
                available -> "Off"
                availability == NetworkTransportPreferenceAvailability.Http3Unsupported -> "Unsupported"
                else -> "Unavailable"
            },
        detail = detail,
        generation = generation
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileCompactMessagesSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = compactConsecutiveMessages,
        available = true,
        statusLabel = if (compactConsecutiveMessages) "Enabled" else "Off",
        detail = "Hide repeated avatars for consecutive messages from the same sender. Message times appear while pressing a row."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileLongMessageCollapseSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = longMessageCollapseEnabled,
        available = true,
        statusLabel = if (longMessageCollapseEnabled) "Enabled" else "Off",
        detail = "Collapse long messages to an 8-line preview. Expand each message when you want to read the full content."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileSingleTapOpensThreadSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = singleTapOpensThreadEnabled,
        available = true,
        statusLabel = if (singleTapOpensThreadEnabled) "Enabled" else "Off",
        detail = "Open a message Thread by tapping its body once. When off, use the replies action or message menu."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileImageAnnotationSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = imageAnnotationEnabled,
        available = true,
        statusLabel = if (imageAnnotationEnabled) "Enabled" else "Off",
        detail = "Enable the composer image preview and annotation transfer page. When off, selected images upload directly."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileForegroundMessageBannersSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = foregroundMessageBannersEnabled,
        available = true,
        statusLabel = if (foregroundMessageBannersEnabled) "Enabled" else "Off",
        detail = "Show an in-app banner for new messages while Raft is open. When off, foreground messages stay quiet."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileActivityTaskStatusBadgesSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = activityTaskStatusBadgesEnabled,
        available = true,
        statusLabel = if (activityTaskStatusBadgesEnabled) "Enabled" else "Off",
        detail = "Show task status badges at the bottom of Activity rows. Keep this off for a quieter Activity list."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileComposerSendKeySnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = composerSendKeyEnabled,
        available = true,
        statusLabel = if (composerSendKeyEnabled) "Send" else "New line",
        detail = "Choose what the keyboard action key does in the message composer. Long-pressing the input still offers Insert newline."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileMultiShellHostSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = multiShellHostEnabled,
        available = true,
        statusLabel = if (multiShellHostEnabled) "Enabled" else "Off",
        detail = "Open different workspaces in separate Android app windows. Existing windows remain open until you close them."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileServerDrawerSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = serverDrawerEnabled,
        available = true,
        statusLabel = if (serverDrawerEnabled) "Enabled" else "Off",
        detail = "Use the new multi-server left drawer on Home. When off, keep the current server switcher popover."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileThreadRowAffordancesSnapshot(): SettingsMobileMultiSelectionSnapshot =
    SettingsMobileMultiSelectionSnapshot(
        selectedKeys = threadRowAffordances.map { it.storageKey }.toSet(),
        statusLabel =
            if (threadRowAffordances.isEmpty()) {
                "Off"
            } else {
                supportedThreadRowAffordances
                    .filter { it in threadRowAffordances }
                    .joinToString(", ") { it.mobileLabel() }
            },
        detail = "Choose which message row actions are visible in conversations.",
        options =
            supportedThreadRowAffordances.map {
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

private fun ThreadRowAffordancePreference.mobileLabel(): String =
    when (this) {
        ThreadRowAffordancePreference.Reactions -> "Reactions"
        ThreadRowAffordancePreference.Saved -> "Saved"
    }

private fun SettingsMobileSnapshot.withSystemNotificationResult(
    action: String,
    result: SystemNotificationActionResult
): SettingsMobileSnapshot {
    val statusLabel = result.mobileStatusLabel(action)
    val detail = result.message.ifBlank { result.mobileDetail(action) }
    val available =
        result.status != SystemNotificationActionStatus.Unsupported &&
            !(action == MOBILE_ACTION_PERMISSION &&
                result.status in setOf(
                    SystemNotificationActionStatus.PermissionDenied,
                    SystemNotificationActionStatus.NotificationsDisabled
                ))
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
                MOBILE_ACTION_PERMISSION ->
                    when (code) {
                        "permission_authorized", "permission_granted", "permission_ephemeral" -> "Allowed"
                        "permission_provisional" -> "Provisional"
                        else -> "Done"
                    }
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
        SystemNotificationActionStatus.NotificationsDisabled -> "System notifications are disabled for Raft."
        SystemNotificationActionStatus.ChannelBlocked -> "The notification channel is blocked in system settings."
        SystemNotificationActionStatus.Unsupported -> "This notification action is not supported on this platform."
        SystemNotificationActionStatus.Error -> code.ifBlank { "Notification action failed." }
    }

private fun StoredAuthSession?.toSharedSettingsHomeSnapshot(
    selectedDestination: SharedSettingsDestination
): SharedSettingsHomeSnapshot {
    val server = this?.currentServer()
    return SharedSettingsHomeSnapshot(
        subtitle = "",
        items =
            listOf(
                nav(SharedSettingsDestination.Account, "Account", "Profile, email, password, connected accounts", SlockLucideIconName.User, this?.user?.bestDisplayName, selectedDestination),
                nav(SharedSettingsDestination.LanguageRegion, "Language & Region", "Language, timezone, translation", SlockLucideIconName.Languages, null, selectedDestination),
                nav(SharedSettingsDestination.Appearance, "Appearance", "Message font size and reading density", SlockLucideIconName.Type, null, selectedDestination),
                nav(SharedSettingsDestination.Notifications, "Notifications", "Push status and workspace mute", SlockLucideIconName.Bell, null, selectedDestination),
                nav(SharedSettingsDestination.Mobile, "Mobile", "Links, conversations, and composer", SlockLucideIconName.Smartphone, null, selectedDestination),
                nav(SharedSettingsDestination.Storage, "Storage", "Local cache and temporary files", SlockLucideIconName.Archive, null, selectedDestination),
                nav(SharedSettingsDestination.Server, "Server Profile", "Workspace name, slug, role", SlockLucideIconName.Building2, server?.role?.titleLabel(), selectedDestination),
                nav(SharedSettingsDestination.Billing, "Billing", "Plan and usage", SlockLucideIconName.CreditCard, server?.plan?.titleLabel(), selectedDestination),
                nav(SharedSettingsDestination.Administration, "Administration", "Admins, invites, join links, agreements", SlockLucideIconName.Shield, null, selectedDestination),
                nav(SharedSettingsDestination.ConnectedApps, "Connected Apps", "Marketplace, installed apps, OAuth clients", SlockLucideIconName.Link2, null, selectedDestination),
                nav(SharedSettingsDestination.Computers, "Computers", "Connected machines and workspaces", SlockLucideIconName.Monitor, null, selectedDestination),
                nav(SharedSettingsDestination.About, "About", "Version and build information", SlockLucideIconName.BadgeInfo, null, selectedDestination)
            )
    )
}

private fun nav(
    destination: SharedSettingsDestination,
    title: String,
    subtitle: String,
    iconName: SlockLucideIconName,
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
                avatarUrl = user.avatarUrl,
                verified = user.emailVerified == true,
                roleLabel = currentServer()?.role
        ),
        connectedAccounts =
            listOf(
                ConnectedAccountSnapshot(
                    providerId = "password",
                    provider = "Email password",
                    identifier = user.email,
                    status = ConnectedAccountStatus.Connected,
                    canConnect = false
                )
            ),
        canEditProfile = false
    )

private fun String.titleLabel(): String =
    replaceFirstChar { it.uppercase() }

private fun StoredAuthSession.currentServer(): StoredAuthServer? =
    currentServerId?.let { serverId -> servers.firstOrNull { it.id == serverId } }
        ?: servers.firstOrNull()
