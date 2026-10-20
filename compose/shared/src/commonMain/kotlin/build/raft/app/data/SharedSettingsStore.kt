package build.raft.app.data

import build.raft.app.KmmResourceLocaleRuntime
import build.raft.app.RaftLucideIconName
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerNotificationPreferencesChanged
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.network.RaftCurlSocketIoPolicy
import build.raft.app.network.currentRaftNetworkTransportController
import build.raft.app.network.updateRaftNetworkKmmRecoveryEnvironment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
    /**
     * The key a rotation just returned, for the one-time dialog of the detail page that rotated it.
     * In-memory only (the page's own store), never cached or persisted, and cleared when the dialog
     * closes ([SharedSettingsIntent.DismissRotatedComputerKey]), the page leaves the machine, the
     * account changes or the store is cleared.
     */
    val rotatedComputerKey: SettingsComputerRotatedKey? = null,
    val about: ScreenLoadState<SharedSettingsAboutSnapshot> = ScreenLoadState.Loading,
    val openSourceLicenses: ScreenLoadState<SharedSettingsOpenSourceLicensesSnapshot> = ScreenLoadState.Loading,
    val developer: ScreenLoadState<SharedSettingsDeveloperSnapshot> = ScreenLoadState.Loading,
    val diagnostics: ScreenLoadState<SharedSettingsDiagnosticsSnapshot> = ScreenLoadState.Loading,
    val refreshingDestinations: Set<SharedSettingsDestination> = emptySet(),
    val routeErrorCode: String? = null,
    val actionState: SharedSettingsActionState? = null,
    val effect: SharedSettingsEffect? = null
)

/** A rotated machine key on its way to the one-time dialog; [apiKey] redacts itself in [toString]. */
data class SettingsComputerRotatedKey(
    val machineId: String,
    val apiKey: SettingsComputerApiKey
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
    val screenError: ScreenError? = null,
    val permissionBlocker: PermissionBlocker? = null,
    val emptyReason: EmptyReason? = null,
    val registering: Boolean = false,
    val saving: Boolean = false,
    val canManage: Boolean = true,
    val atLimit: Boolean = false
)

internal fun AddComputerDialogState.errorPresentationOrNull(): ErrorPresentation? =
    screenError?.errorPresentation()
        ?: permissionBlocker?.errorPresentation()
        ?: emptyReason?.errorPresentation()

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
    val iconName: RaftLucideIconName,
    val value: String? = null,
    val productVisible: Boolean = true
)

data class SharedSettingsStorageSnapshot(
    val totalSizeLabel: String,
    val rows: List<SharedSettingsStorageRow>,
    val feedback: String? = null,
    val feedbackError: ErrorPresentation? = null
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
    val transactionState: SharedSettingsUpdateTransactionState = SharedSettingsUpdateTransactionState.Unknown,
    val transactionTargetVersionCode: Long = 0L,
    val transactionRetryable: Boolean = false,
    val statusLabel: String = "",
    val detail: String = "",
    val actionLabel: String = "",
    val updateAvailable: Boolean = false,
    val currentVersionLabel: String = "",
    val currentVersionCode: Long = 0L,
    val latestVersionLabel: String = "",
    val latestVersionCode: Long = 0L,
    val latestChangelog: String = "",
    val downloadUrl: String = "",
    val downloadProgressToken: String = "",
    val downloadSizesResolved: Boolean = false,
    val fullDownloadSizeBytes: Long = 0L,
    val patchDownloadSizeBytes: Long = 0L,
    val checkedAtLabel: String = "",
    val errorCode: String = "",
    val screenError: ScreenError? = null
)

enum class SharedSettingsUpdateTransactionState(val wireValue: String) {
    Unknown(""),
    Idle("idle"),
    Checking("checking"),
    Patching("patching"),
    Downloading("downloading"),
    ReadyToInstall("ready_to_install"),
    InstallerOpened("installer_opened"),
    Failed("failed"),
    Stale("stale"),
    Installed("installed");

    companion object {
        fun fromWire(value: String?): SharedSettingsUpdateTransactionState =
            entries.firstOrNull { state ->
                state != Unknown && state.wireValue == value?.trim()?.lowercase()
            } ?: Unknown
    }
}

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
    val readStateV1: SharedSettingsReadStateV1DiagnosticSnapshot = SharedSettingsReadStateV1DiagnosticSnapshot(),
    val featureFlagOverrides: List<SharedSettingsDiagnosticFeatureFlagSnapshot> = emptyList(),
    /** Signed-out diagnostics toggle; see [SharedAuthRuntimeCapabilityStore]. */
    val appleSignInLocalOverride: Boolean = false,
    val appleSignInLocalOverrideAvailable: Boolean = false,
    val loadErrorMessage: String? = null,
    val loadErrors: List<SharedSettingsDiagnosticsLoadError> = emptyList()
)

data class SharedSettingsDiagnosticsHostSnapshot(
    val entries: List<SharedSettingsDiagnosticsEntry> = emptyList(),
    val readStateV1: SharedSettingsReadStateV1DiagnosticSnapshot = SharedSettingsReadStateV1DiagnosticSnapshot(),
    val featureFlagOverrides: List<SharedSettingsDiagnosticFeatureFlagSnapshot> = emptyList(),
)

data class SharedSettingsDiagnosticsLoadError(
    val label: String,
    val presentation: ErrorPresentation
)

data class SharedSettingsDiagnosticFeatureFlagSnapshot(
    val key: String = "",
    val supported: Boolean = false,
    val scopeKind: String = "unsupported",
    val entitled: Boolean = false,
    val clientOverride: String = "Server",
    val observedScope: String = "",
    val boundScope: String = "",
    val expiresAtMillis: Long? = null,
    val serverEffective: Boolean? = null,
    val finalEffective: Boolean? = null,
    val precedence: String = "not_observed",
    val exactScopeMatched: Boolean = false,
    val clearReason: String = "",
    val evidence: String = "",
)

data class SharedSettingsReadStateV1DiagnosticSnapshot(
    val entitled: Boolean = false,
    val clientOverride: String = "Server",
    val observedScope: String = "",
    val boundScope: String = "",
    val expiresAtMillis: Long? = null,
    val serverEffective: Boolean? = null,
    val finalEffective: Boolean? = null,
    val precedence: String = "not_observed",
    val exactScopeMatched: Boolean = false,
    val clearReason: String = "",
    val selectedScope: String = "",
    val witnessGeneration: Long? = null,
    val durableReadStateVersion: Long? = null,
    val durableMaxReadSeq: Long? = null,
    val latestAdjudication: String = "",
    val destinationPublication: String = "",
    val barrierState: String = "",
    val stalePublicationRejectedCount: Long = 0L,
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
    val errorCode: String? = null,
    val screenError: ScreenError? = null,
    val permissionBlocker: PermissionBlocker? = null,
    val emptyReason: EmptyReason? = null
)

internal fun SharedSettingsActionState.errorPresentationOrNull(): ErrorPresentation? =
    screenError?.errorPresentation()
        ?: permissionBlocker?.errorPresentation()
        ?: emptyReason?.errorPresentation()
        ?: errorCode?.let { ScreenError(it, retryable = false).errorPresentation() }

private data class ServerNotificationModeMutation(
    val generation: Long,
    val ownerKey: String?,
    val ownerRoute: SharedSettingsRoute,
    val previousMode: SettingsMobileSelectionSnapshot,
    val previousMute: SettingsMobileToggleSnapshot
)

sealed interface SharedSettingsIntent {
    data object Start : SharedSettingsIntent
    /**
     * Loads content for the Pager that already owns this store; it never performs navigation.
     * [itemId] names the item of an item page (the machine of [SharedSettingsDestination.ComputerDetail]).
     */
    data class LoadPageContent(
        val page: SharedSettingsDestination,
        val itemId: String? = null
    ) : SharedSettingsIntent
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
    /** The one-time key dialog closed: drop the key for good. */
    data object DismissRotatedComputerKey : SharedSettingsIntent
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
    data class SetOfflineComputerAlertsPreference(val enabled: Boolean) : SharedSettingsIntent
    data class SetServerMute(val enabled: Boolean) : SharedSettingsIntent
    data class SetServerNotificationMode(val mode: String) : SharedSettingsIntent
    data class SetPushPreviewMode(val mode: String) : SharedSettingsIntent
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
    data class SetFontPreference(val fontKey: String) : SharedSettingsIntent
    data class SetMessageAlignment(val alignment: String) : SharedSettingsIntent
    data class SetLinkOpenMode(val mode: String) : SharedSettingsIntent
    data class SetCompactConsecutiveMessages(val enabled: Boolean) : SharedSettingsIntent
    data class SetLongMessageCollapseEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetSingleTapOpensThreadEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetThreadUnreadJumpEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetNewMessageMenuEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetConversationForwardGestureEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetForegroundMessageBanners(val enabled: Boolean) : SharedSettingsIntent
    data class SetActivityTaskStatusBadges(val enabled: Boolean) : SharedSettingsIntent
    data class SetComposerSendKey(val enabled: Boolean) : SharedSettingsIntent
    data class SetMultiShellHostEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetDesktopModeOnLargeScreen(val enabled: Boolean) : SharedSettingsIntent
    data class SetServerDrawerEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetNewDesignSettingsEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetHomeServerAvatarEnabled(val enabled: Boolean) : SharedSettingsIntent
    /** Task #154: stores the user's explicit realtime curl Socket.IO choice (no longer follows the server). */
    data class SetRealtimeCurlSocketIoEnabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetHttp3Enabled(val enabled: Boolean) : SharedSettingsIntent
    data class SetThreadReverseLayoutEnabled(val enabled: Boolean) : SharedSettingsIntent
    data object RefreshStorage : SharedSettingsIntent
    data class ClearStorage(val target: String) : SharedSettingsIntent
    data class SetApiEnvironment(val environmentKey: String) : SharedSettingsIntent
    data class SaveApiEnvironment(val request: SharedSettingsApiEnvironmentRequest) : SharedSettingsIntent
    data object CheckHandsUpdate : SharedSettingsIntent
    data class CompleteStorageClear(
        val target: String,
        val ok: Boolean,
        val code: String? = null,
        val screenError: ScreenError? = null,
        val permissionBlocker: PermissionBlocker? = null,
        val emptyReason: EmptyReason? = null,
        val snapshot: SharedSettingsStorageSnapshot? = null
    ) : SharedSettingsIntent
    data object CopyDiagnostics : SharedSettingsIntent
    data object ShareDiagnostics : SharedSettingsIntent
    data object ClearDiagnostics : SharedSettingsIntent
    data class SetDiagnosticFeatureFlagOverride(val key: String, val mode: String) : SharedSettingsIntent
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
    val supportsConversationForwardGesture: Boolean
        get() = false

    suspend fun pickAccountAvatarFile(): DataResult<PlatformFile> =
        DataResult.Error(ScreenError("settings_account_avatar_picker_unavailable", retryable = false))

    suspend fun pickConnectedAppLogoFile(clientRecordId: String): DataResult<PlatformFile> =
        DataResult.Error(ScreenError("settings_connected_app_logo_picker_unavailable", retryable = false))

    /**
     * Starts the platform-owned mobile OAuth account-link flow.
     *
     * [DataResult.Empty] is the only signal that the host does not implement the
     * mobile flow and that the repository Web flow may be used as a fallback.
     * A real platform error must stay an error: opening the Web flow after a
     * failed native start loses the app-owned auth/PKCE callback authority.
     */
    suspend fun startMobileOAuthAccountLink(providerId: String): DataResult<Unit> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

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

    suspend fun checkHandsUpdate(): DataResult<SharedSettingsDeveloperSnapshot> =
        DataResult.Error(ScreenError("settings_hands_update_check_unavailable", retryable = false))

    suspend fun checkHandsUpdateStatus(): DataResult<SharedSettingsUpdateCheckSnapshot> =
        DataResult.Error(ScreenError("settings_hands_update_status_unavailable", retryable = false))

    /** Local download observation only; never checks the remote update offer. */
    suspend fun readHandsDownloadProgress(token: String): SharedUpdateDownloadProgress? = null

    suspend fun installHandsUpdate(): DataResult<SharedSettingsUpdateCheckSnapshot> =
        DataResult.Error(ScreenError("settings_hands_update_install_unavailable", retryable = false))

    suspend fun installHandsUpdateForToken(token: String): DataResult<SharedSettingsUpdateCheckSnapshot> =
        installHandsUpdate()

    suspend fun prefetchReleaseNotes(url: String): DataResult<Unit> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun onApiEnvironmentChanged(
        before: SharedSettingsApiEnvironmentOption,
        after: SharedSettingsApiEnvironmentOption
    ): DataResult<Unit> =
        DataResult.Content(Unit)

    suspend fun loadDiagnosticsHost(): DataResult<SharedSettingsDiagnosticsHostSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)

    suspend fun setDiagnosticFeatureFlagOverride(key: String, mode: String): DataResult<Unit> =
        DataResult.Error(ScreenError("diagnostic_feature_flag_override_unavailable", retryable = false))

    suspend fun loadSystemNotificationPermission(): SettingsMobileActionSnapshot? = null
}

private data class AccountAvatarRetirementCapture(
    val userId: String,
    val capture: RaftAvatarSubjectRetirementCapture
)

class SharedSettingsDataSource(
    private val sessionStore: RaftAuthSessionStore,
    private val settingsRepository: SettingsRepository? = null,
    private val computersSource: SharedSettingsComputersSource? = null,
    private val serverMembersStore: SharedServerMembersStore? = null,
    private val notificationPreferenceRepository: NotificationPreferenceRepository? = null,
    private val localeRepository: LocaleRepository? = null,
    private val fontPreferenceRepository: FontPreferenceRepository? = null,
    private val chatLayoutPreferenceRepository: ChatLayoutPreferenceRepository? = null,
    private val apiEnvironmentStore: SharedApiEnvironmentStore? = null,
    private val platformCapabilities: SharedSettingsPlatformCapabilities = object : SharedSettingsPlatformCapabilities {},
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    private val linkOpenPreferenceRepository: LinkOpenPreferenceRepository? = null,
    private val authRuntimeCapabilityStore: SharedAuthRuntimeCapabilityStore? = null,
    private val realtimeTransportPreferenceStore: RealtimeTransportPreferenceStore? = null,
    private val http3PreferenceStore: Http3PreferenceStore? = null,
    private val onHttp3UserChoiceChanged: (() -> String?)? = null,
    private val threadReverseLayoutPreferenceStore: ThreadReverseLayoutPreferenceStore? = null,
    /** What the switch follows until the user picks: the flag for the selected server (alpha builds: on). */
    private val threadReverseLayoutDefault: (suspend () -> Boolean)? = null,
    private val serverVersionProbe: suspend (String) -> ServerVersionProbe = { SharedServerVersionGate.probe(it) }
) {
    private var avatarCacheRuntime: RaftAvatarCacheRuntime? = null
    private var avatarCacheNativeCache: RaftAvatarNativeCache = UnsupportedRaftAvatarNativeCache

    internal fun withAvatarCacheRuntime(
        runtime: RaftAvatarCacheRuntime?,
        nativeCache: RaftAvatarNativeCache
    ): SharedSettingsDataSource = apply {
        avatarCacheRuntime = runtime
        avatarCacheNativeCache = nativeCache
    }

    val authSessionEvents: SharedFlow<AuthSessionEvent>?
        get() = (sessionStore as? RaftAuthSessionEventSource)?.authSessionEvents

    suspend fun currentMobileSettingsOwnerKey(): String? =
        sessionStore.readSession()?.let { session ->
            "${session.user.id}\u001F${session.currentServerId.orEmpty()}"
        }

    suspend fun loadHome(): DataResult<SharedSettingsHomeSnapshot> {
        val session = sessionStore.readSession()
        return DataResult.Content(session.toSharedSettingsHomeSnapshot())
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
        val avatarRetirement = captureCurrentAccountAvatarRetirement()
        return when (val picked = platformCapabilities.pickAccountAvatarFile()) {
            is DataResult.Content -> {
                val result =
                    withContext(Dispatchers.Default) {
                        repository.uploadAccountAvatar(AccountAvatarUploadRequest(picked.data))
                    }
                if (
                    result is DataResult.Content &&
                    avatarRetirement != null &&
                    result.data.profile.userId.trim() == avatarRetirement.userId
                ) {
                    retireCapturedAvatarSubject(
                        avatarRetirement.capture,
                        source = "account_avatar_upload"
                    )
                }
                result
            }
            is DataResult.Empty -> DataResult.Empty(picked.reason)
            is DataResult.Error -> DataResult.Error(picked.error)
            is DataResult.NoPermission -> DataResult.NoPermission(picked.blocker)
        }
    }

    suspend fun startAccountProviderLink(request: AccountProviderLinkStartRequest): DataResult<AccountProviderLinkStartResult> =
        when (val mobile = platformCapabilities.startMobileOAuthAccountLink(request.providerId)) {
            is DataResult.Content ->
                DataResult.Content(
                    AccountProviderLinkStartResult(
                        providerId = request.providerId,
                        url = null
                    )
                )
            is DataResult.Empty ->
                settingsRepository?.startAccountProviderLink(request)
                    ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)
            is DataResult.Error -> DataResult.Error(mobile.error)
            is DataResult.NoPermission -> DataResult.NoPermission(mobile.blocker)
        }

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
            }.withComputerManageGate(session)
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

    suspend fun deleteServer(request: SettingsServerDeleteRequest): DataResult<SettingsServerActionResult> {
        val capture = captureCurrentAvatarOwnerRetirement()
        val result = settingsRepository?.deleteServer(request) ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        if (result is DataResult.Content && result.data.success) {
            retireCapturedAvatarOwners(capture, "delete_server")
        }
        return result
    }

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
        val cleanMemberId = memberId.trim()
        val session = sessionStore.readSession()
        val serverId = session?.currentServerId?.trim().orEmpty()
        val memberKind =
            serverId.takeIf(String::isNotBlank)
                ?.let(::ServerId)
                ?.let { ownerServerId ->
                    serverMembersStore?.currentKnownMemberProfilesById(ownerServerId)?.get(cleanMemberId)?.kind
                }
                ?.takeIf { it == MemberKind.Human || it == MemberKind.Agent }
        val avatarRetirement =
            if (serverId.isNotBlank() && cleanMemberId.isNotBlank() && memberKind != null) {
                avatarCacheRuntime?.captureCurrentSubjectRetirement(
                    subject = RaftAvatarCacheSubject.Member(memberKind, cleanMemberId),
                    serverId = serverId
                )
            } else {
                null
            }
        val result =
            (settingsRepository as? SettingsServerManagementRepository)?.removeServerMember(memberId)
                ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        if (result is DataResult.Content && result.data.success) {
            if (serverId.isNotBlank() && cleanMemberId.isNotBlank()) {
                serverMembersStore?.removeMember(
                    serverId = ServerId(serverId),
                    memberId = MemberId(cleanMemberId)
                )
            }
            avatarRetirement?.let { retireCapturedAvatarSubject(it, "remove_server_member") }
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

    suspend fun leaveServer(): DataResult<SettingsServerActionResult> {
        val capture = captureCurrentAvatarOwnerRetirement()
        val result =
            (settingsRepository as? SettingsServerManagementRepository)?.leaveServer()
                ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        if (result is DataResult.Content && result.data.success) {
            retireCapturedAvatarOwners(capture, "leave_server")
        }
        return result
    }

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

    suspend fun setOfflineComputerAlertsPreference(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            notificationPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_notification_preference_unavailable", retryable = false))
        repository.setOfflineComputerAlertsEnabled(enabled)
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

    suspend fun setPushPreviewMode(mode: String): DataResult<SettingsMobileSnapshot> {
        val selectedMode =
            PushPreviewMode.fromWire(mode)
                ?: return DataResult.Error(ScreenError("settings_push_preview_mode_unknown", retryable = false))
        val repository =
            settingsRepository as? SettingsServerActionRepository
                ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return when (val result = repository.setPushPreviewMode(selectedMode)) {
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

    /**
     * Task #179: persist the chosen font face and publish it to the observable face state so the
     * running UI repaints without a restart.
     */
    suspend fun setFontPreference(fontKey: String): DataResult<SettingsMobileSnapshot> {
        val repository =
            fontPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_font_unavailable", retryable = false))
        val font = FontPreference.fromStorageKey(fontKey)
        repository.selectFontPreference(font)
        // RaftDesignTokens already imports from this package, so reference the state by its
        // fully-qualified name instead of adding another import edge.
        build.raft.app.RaftFontFaceState.apply(font)
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

    suspend fun setThreadUnreadJumpEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_thread_unread_jump_unavailable", retryable = false))
        repository.setThreadUnreadJumpEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setNewMessageMenuEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_new_message_menu_unavailable", retryable = false))
        repository.setNewMessageMenuEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setConversationForwardGestureEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        if (!platformCapabilities.supportsConversationForwardGesture) {
            return DataResult.Error(
                ScreenError("settings_conversation_forward_gesture_unavailable", retryable = false)
            )
        }
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_conversation_forward_gesture_unavailable", retryable = false))
        repository.setConversationForwardGestureEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setForegroundMessageBanners(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_foreground_message_banners_unavailable", retryable = false))
        repository.setForegroundMessageBannersEnabled(enabled)
        // This control lives on the Notifications page beside server-owned
        // push mode. A local-only snapshot defaults that mode to unavailable
        // All and would overwrite the canonical server selection after the
        // foreground preference changes.
        return loadMobile()
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

    fun hasChatLayoutPreferenceRepositoryForDiagnostics(): Boolean =
        chatLayoutPreferenceRepository != null

    suspend fun setDesktopModeOnLargeScreen(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_desktop_mode_unavailable", retryable = false))
        repository.setDesktopModeOnLargeScreen(enabled)
        return loadMobilePreferences()
    }

    suspend fun setServerDrawerEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_server_drawer_unavailable", retryable = false))
        repository.setServerDrawerEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setNewDesignSettingsEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_new_design_settings_unavailable", retryable = false))
        repository.setNewDesignSettingsEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setHomeServerAvatarEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val repository =
            chatLayoutPreferenceRepository
                ?: return DataResult.Error(ScreenError("settings_home_server_avatar_unavailable", retryable = false))
        repository.setHomeServerAvatarEnabled(enabled)
        return loadMobilePreferences()
    }

    suspend fun setRealtimeCurlSocketIoEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val store =
            realtimeTransportPreferenceStore
                ?: return DataResult.Error(ScreenError("settings_realtime_curl_socketio_unavailable", retryable = false))
        store.saveCurlSocketIoUserChoice(enabled)
        diagnosticsLogger?.invoke("realtime_curl_socketio_user_choice enabled=$enabled")
        return loadMobilePreferences()
    }

    suspend fun setHttp3Enabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val store =
            http3PreferenceStore
                ?: return DataResult.Error(ScreenError("settings_http3_unavailable", retryable = false))
        store.saveHttp3UserChoice(enabled)
        val applied = onHttp3UserChoiceChanged?.invoke()
        diagnosticsLogger?.invoke("http3_user_choice enabled=$enabled ${applied ?: "controller=unbound"}")
        return loadMobilePreferences()
    }

    suspend fun setThreadReverseLayoutEnabled(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val store =
            threadReverseLayoutPreferenceStore
                ?: return DataResult.Error(ScreenError("settings_thread_reverse_layout_unavailable", retryable = false))
        store.saveThreadReverseLayoutUserChoice(enabled)
        diagnosticsLogger?.invoke("thread_reverse_layout_user_choice enabled=$enabled")
        return loadMobilePreferences()
    }

    /** The switch shows the user's choice when there is one, otherwise the default it follows. Applies to the next thread open. */
    private suspend fun SettingsMobileSnapshot.withThreadReverseLayoutPreference(): SettingsMobileSnapshot {
        val store = threadReverseLayoutPreferenceStore ?: return this
        val enabled = store.readThreadReverseLayoutUserChoiceSync() ?: (threadReverseLayoutDefault?.invoke() ?: false)
        return copy(
            threadReverseLayout =
                SettingsMobileToggleSnapshot(
                    enabled = enabled,
                    available = true,
                    statusLabel = if (enabled) "Enabled" else "Off",
                    detail = "Open chats at the newest message, laid out from the bottom. Until changed, follows the server default."
                )
        )
    }

    /**
     * Task #3: the switch shows the effective value: the user's explicit choice when there is one,
     * otherwise what the server-sent transport variant implies (curl_h3 = on). Read from this data
     * source's own store so the row reflects a save immediately.
     */
    private fun SettingsMobileSnapshot.withHttp3Preference(): SettingsMobileSnapshot {
        val store = http3PreferenceStore ?: return this
        val controller = currentRaftNetworkTransportController() ?: return this
        val transport = controller.getPreferenceSync()
        if (transport.availability == NetworkTransportPreferenceAvailability.Http3Unsupported) return this
        val enabled = store.readHttp3UserChoiceSync() ?: transport.requestedHttp3Enabled
        return copy(
            http3 =
                SettingsMobileToggleSnapshot(
                    enabled = enabled,
                    available = true,
                    statusLabel = if (enabled) "Enabled" else "Off",
                    detail = "New requests try HTTP/3 and fall back to HTTP/2 when the network cannot. Until changed, follows the server default."
                )
        )
    }

    /**
     * Task #154: the switch shows the effective value, i.e. RaftCurlSocketIoPolicy.isEnabledFor(currentServerId):
     * the user's explicit choice when there is one, otherwise the server flag for the selected server.
     * The choice is read from this data source's own store so the row reflects a save immediately.
     */
    private suspend fun SettingsMobileSnapshot.withRealtimeCurlSocketIoPreference(): SettingsMobileSnapshot {
        val store = realtimeTransportPreferenceStore ?: return this
        if (!RaftCurlSocketIoPolicy.platformSupported) return this
        val serverId = sessionStore.readSession()?.currentServerId?.trim()?.takeIf(String::isNotEmpty)
        val enabled = store.readCurlSocketIoUserChoiceSync() ?: RaftCurlSocketIoPolicy.serverDefaultFor(serverId)
        return copy(
            realtimeCurlSocketIo =
                SettingsMobileToggleSnapshot(
                    enabled = enabled,
                    available = true,
                    statusLabel = if (enabled) "Enabled" else "Off",
                    detail = "Takes effect on the next connection or reconnect. Until changed, follows the server default."
                )
        )
    }


    suspend fun loadStorage(): DataResult<SharedSettingsStorageSnapshot> =
        platformCapabilities.loadStorage()

    suspend fun clearStorage(target: String): DataResult<SharedSettingsStorageSnapshot> {
        val runtime = avatarCacheRuntime
        if (target != "imageCache" || runtime == null) return platformCapabilities.clearStorage(target)
        var nativeResult: DataResult<SharedSettingsStorageSnapshot>? = null
        val receipt =
            runtime.clearAll {
                val result = platformCapabilities.clearStorage(target)
                nativeResult = result
                result is DataResult.Content
            }
        val result = nativeResult
            ?: return DataResult.Error(ScreenError("settings_storage_clear_failed", retryable = true))
        if (receipt is RaftAvatarCacheClearReceipt.Success) return result
        diagnosticsLogger?.invoke(
            "avatar_cache_clear result=failure stages=" +
                (receipt as RaftAvatarCacheClearReceipt.Failed).stages.joinToString(",") { it.name.lowercase() }
        )
        return when (result) {
            is DataResult.Content ->
                DataResult.Error(ScreenError("settings_avatar_cache_clear_incomplete", retryable = true))
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }
    }

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
            if (SharedApiEnvironmentStore.predefined.none { it.key == request.key }) {
                when (serverVersionProbe(request.apiBaseUrl)) {
                    is ServerVersionProbe.Compatible, ServerVersionProbe.Unversioned -> Unit
                    is ServerVersionProbe.Incompatible, ServerVersionProbe.MissingVersion ->
                        return DataResult.Error(ScreenError("settings_environment_server_outdated", retryable = false))
                    ServerVersionProbe.Unreachable ->
                        return DataResult.Error(ScreenError("settings_environment_server_unreachable", retryable = true))
                }
            }
            val before = store.current()
            val after =
                store.save(request)
                    ?: return DataResult.Error(ScreenError("settings_developer_environment_unknown", retryable = false))
            return applyApiEnvironmentChange(store, before, after)
        } ?: platformCapabilities.saveApiEnvironment(request)

    suspend fun checkHandsUpdate(): DataResult<SharedSettingsDeveloperSnapshot> =
        platformCapabilities.checkHandsUpdate()

    suspend fun checkHandsUpdateStatus(): DataResult<SharedSettingsUpdateCheckSnapshot> =
        platformCapabilities.checkHandsUpdateStatus()

    suspend fun installHandsUpdate(): DataResult<SharedSettingsUpdateCheckSnapshot> =
        platformCapabilities.installHandsUpdate()

    private suspend fun applyApiEnvironmentChange(
        store: SharedApiEnvironmentStore,
        before: SharedSettingsApiEnvironmentOption,
        after: SharedSettingsApiEnvironmentOption
    ): DataResult<SharedSettingsDeveloperSnapshot> {
        val changed = before != after
        if (changed) {
            val avatarRetirement =
                avatarCacheRuntime?.captureSessionRetirement(
                    environmentId = before.apiBaseUrl,
                    session = sessionStore.readSession()
                )
            runCatching {
                updateRaftNetworkKmmRecoveryEnvironment(
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
            retireCapturedAvatarOwners(avatarRetirement, "api_environment_changed")
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

    private suspend fun captureCurrentAvatarOwnerRetirement(): RaftAvatarOwnerRetirementCapture? {
        val environmentId = apiEnvironmentStore?.current()?.apiBaseUrl ?: return null
        val session = sessionStore.readSession() ?: return null
        val currentServerId = session.currentServerId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val owner =
            raftCurrentAvatarCacheOwner(
                environmentId = environmentId,
                session = session,
                requestedServerId = currentServerId
            ) ?: return null
        return RaftAvatarOwnerRetirementCapture(setOf(owner))
    }

    private suspend fun captureCurrentAccountAvatarRetirement(): AccountAvatarRetirementCapture? {
        val runtime = avatarCacheRuntime ?: return null
        val session = sessionStore.readSession() ?: return null
        val userId = session.user.id.trim().takeIf(String::isNotEmpty) ?: return null
        val serverId = session.currentServerId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val capture =
            runtime.captureCurrentSubjectRetirement(
                subject = RaftAvatarCacheSubject.Member(MemberKind.Human, userId),
                serverId = serverId
            ) ?: return null
        return AccountAvatarRetirementCapture(userId = userId, capture = capture)
    }

    private suspend fun retireCapturedAvatarOwners(
        capture: RaftAvatarOwnerRetirementCapture?,
        source: String
    ) {
        val runtime = avatarCacheRuntime ?: return
        val captured = capture ?: return
        when (val receipt = runtime.retireOwners(captured, avatarCacheNativeCache)) {
            RaftAvatarCacheClearReceipt.Success -> Unit
            is RaftAvatarCacheClearReceipt.Failed ->
                diagnosticsLogger?.invoke(
                    "avatar_cache_retire result=failure source=$source stages=" +
                        receipt.stages.joinToString(",") { it.name.lowercase() }
                )
        }
    }

    private suspend fun retireCapturedAvatarSubject(
        capture: RaftAvatarSubjectRetirementCapture,
        source: String
    ) {
        val runtime = avatarCacheRuntime ?: return
        when (val receipt = runtime.retireSubject(capture, avatarCacheNativeCache)) {
            RaftAvatarCacheClearReceipt.Success -> Unit
            is RaftAvatarCacheClearReceipt.Failed ->
                diagnosticsLogger?.invoke(
                    "avatar_cache_retire result=failure source=$source stages=" +
                        receipt.stages.joinToString(",") { it.name.lowercase() }
                )
        }
    }

    suspend fun loadDiagnostics(): DataResult<SharedSettingsDiagnosticsSnapshot> =
        loadDiagnosticsSnapshot()

    suspend fun setDiagnosticFeatureFlagOverride(
        key: String,
        mode: String,
    ): DataResult<SharedSettingsDiagnosticsSnapshot> {
        return when (val result = platformCapabilities.setDiagnosticFeatureFlagOverride(key, mode)) {
            is DataResult.Content -> loadDiagnosticsSnapshot()
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }
    }

    /**
     * Signed-out diagnostics Apple-login toggle. This only changes local capability
     * projection (which provider buttons the login surface renders); the backend
     * `platform=mobile` gate still decides whether an Apple start call succeeds.
     */
    suspend fun setAppleSignInLocalOverride(enabled: Boolean): DataResult<SharedSettingsDiagnosticsSnapshot> {
        val store = authRuntimeCapabilityStore
            ?: return DataResult.Error(ScreenError("apple_signin_override_unavailable", retryable = false))
        store.setLocalAppleSignInOverride(enabled)
        diagnosticsLogger?.invoke("apple_signin_local_override enabled=$enabled")
        return loadDiagnosticsSnapshot()
    }

    private suspend fun SettingsMobileSnapshot.withLocalMobilePreferences(): SettingsMobileSnapshot {
        val notificationPreference = notificationPreferenceRepository?.getNotificationPreference()
        val locale = localeRepository?.getLocale()
        // Task #179: cold start. Publish the stored face to the observable state so the very
        // first composition renders the chosen font, not the default and then a swap.
        fontPreferenceRepository?.getFontPreference()?.current?.let { stored ->
            build.raft.app.RaftFontFaceState.apply(stored)
        }
        val chatLayout = chatLayoutPreferenceRepository?.getChatLayoutPreference()
        val linkOpenMode = linkOpenPreferenceRepository?.getLinkOpenMode()
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
            threadUnreadJump =
                chatLayout?.toSettingsMobileThreadUnreadJumpSnapshot()
                    ?: threadUnreadJump.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Thread unread jumping is not available on this platform."
                    ),
            newMessageMenu =
                chatLayout?.toSettingsMobileNewMessageMenuSnapshot()
                    ?: newMessageMenu.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "The compact message menu is not available on this platform."
                    ),
            conversationForwardGesture =
                chatLayout?.toSettingsMobileConversationForwardGestureSnapshot(
                    available = platformCapabilities.supportsConversationForwardGesture
                )
                    ?: conversationForwardGesture.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Conversation Forward navigation is not available on this platform."
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
            desktopModeOnLargeScreen =
                chatLayout?.toSettingsMobileDesktopModeSnapshot()
                    ?: desktopModeOnLargeScreen.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "The large-screen desktop mode preference is not available on this platform."
                    ),
            newDesignSettings =
                chatLayout?.toSettingsMobileNewDesignSettingsSnapshot()
                    ?: newDesignSettings.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "The new channel settings design is not available on this platform."
                    ),
            homeServerAvatar =
                chatLayout?.toSettingsMobileHomeServerAvatarSnapshot()
                    ?: homeServerAvatar.copy(
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "The Home workspace avatar preference is not available on this platform."
                    ),
        ).withRealtimeCurlSocketIoPreference().withHttp3Preference().withThreadReverseLayoutPreference()
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

    private suspend fun loadDiagnosticsSnapshot(): DataResult<SharedSettingsDiagnosticsSnapshot> {
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
        val loadErrors =
            listOfNotNull(
                aboutResult.settingsDiagnosticsLoadError("about"),
                hostResult.settingsDiagnosticsLoadError("logs")
            )
        return DataResult.Content(
            SharedSettingsDiagnosticsSnapshot(
                about = about,
                entries = entries,
                readStateV1 = (hostResult as? DataResult.Content)?.data?.readStateV1
                    ?: SharedSettingsReadStateV1DiagnosticSnapshot(),
                featureFlagOverrides =
                    (hostResult as? DataResult.Content)?.data?.featureFlagOverrides.orEmpty(),
                appleSignInLocalOverride = authRuntimeCapabilityStore?.current()?.localAppleSignInOverride == true,
                // Apple is always offered on iOS; the old local test toggle has no effect.
                appleSignInLocalOverrideAvailable = false,
                loadErrors = loadErrors
            )
        )
    }

    private fun <T> DataResult<T>.settingsDiagnosticsLoadError(
        label: String
    ): SharedSettingsDiagnosticsLoadError? =
        when (this) {
            is DataResult.Content -> null
            is DataResult.Empty -> SharedSettingsDiagnosticsLoadError(label, reason.errorPresentation())
            is DataResult.Error -> SharedSettingsDiagnosticsLoadError(label, error.errorPresentation())
            is DataResult.NoPermission -> SharedSettingsDiagnosticsLoadError(label, blocker.errorPresentation())
        }
}

internal class SharedSettingsPageContentOwner {
    private var page: SharedSettingsDestination? = null

    fun admits(candidate: SharedSettingsDestination): Boolean {
        val current = page
        if (current != null) return current == candidate
        page = candidate
        return true
    }
}

class SharedSettingsViewModel(
    dataSource: SharedSettingsDataSource,
    private val diagnosticsLogger: (String) -> Unit = {}
) : ViewModel() {
    private val pageContentOwner = SharedSettingsPageContentOwner()
    private val storeDelegate =
        lazy {
            SharedSettingsStore(
                dataSource = dataSource,
                scope = viewModelScope,
                diagnosticsLogger = diagnosticsLogger,
                standaloneNavigation = true
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
        if (!admitsPageContent(intent)) return
        if (contractDelegate.isInitialized()) {
            contractDelegate.value.dispatch(intent)
        } else {
            storeDelegate.value.dispatch(intent)
        }
    }

    suspend fun accept(intent: SharedSettingsIntent) {
        if (!admitsPageContent(intent)) return
        storeDelegate.value.accept(intent)
    }

    private fun admitsPageContent(intent: SharedSettingsIntent): Boolean {
        val page =
            when (intent) {
                SharedSettingsIntent.Start -> SharedSettingsDestination.Home
                is SharedSettingsIntent.LoadPageContent -> intent.page
                is SharedSettingsIntent.OpenComputer -> {
                    diagnosticsLogger("settings_viewmodel_navigation_intent_rejected intent=OpenComputer")
                    return false
                }
                SharedSettingsIntent.Back -> {
                    diagnosticsLogger("settings_viewmodel_navigation_intent_rejected intent=Back")
                    return false
                }
                else -> return true
            }
        val admitted = pageContentOwner.admits(page)
        if (!admitted) {
            diagnosticsLogger("settings_page_content_rebind_rejected page=${page.name}")
        }
        return admitted
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
    private val diagnosticsLogger: (String) -> Unit = {},
    private val nowMillis: () -> Long = ::defaultNowMillis,
    private val diagnosticsRefreshDelay: suspend (Long) -> Unit = { delay(it) },
    /**
     * Each standalone settings page owns one destination; navigating is the host's job. A store in
     * this mode never switches its own route to another page, it only emits the navigation effect.
     */
    private val standaloneNavigation: Boolean = false,
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<SharedSettingsState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private val pendingMachineRealtimeIds = LinkedHashSet<String>()
    private var machineRealtimeDrainActive = false
    private var diagnosticsRefreshJob: Job? = null
    private var diagnosticsRefreshToken: Long = 0L
    private var authOwnerObservationJob: Job? = null
    private var serverNotificationModeMutationGeneration: Long = 0L
    private var serverNotificationModeMutation: ServerNotificationModeMutation? = null

    init {
        authOwnerObservationJob =
            dataSource.authSessionEvents?.let { events ->
                scope.launch {
                    events.collect(::onAuthSessionEvent)
                }
            }
    }

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
        if (_state.value.rotatedComputerKey != null) {
            _state.value = _state.value.copy(rotatedComputerKey = null)
        }
        cancelDiagnosticsExpiryRefresh()
        authOwnerObservationJob?.cancel()
        authOwnerObservationJob = null
        retireServerNotificationModeMutation()
    }

    suspend fun accept(intent: SharedSettingsIntent) {
        when (intent) {
            SharedSettingsIntent.Start -> loadHome()
            is SharedSettingsIntent.LoadPageContent -> {
                cancelDiagnosticsExpiryRefresh()
                if (intent.page != SharedSettingsDestination.Notifications) {
                    retireServerNotificationModeMutation()
                }
                val route = SharedSettingsRoute(intent.page, intent.itemId?.trim()?.takeIf { it.isNotBlank() })
                _state.value =
                    _state.value.copy(
                        route = route,
                        routeErrorCode = null,
                        rotatedComputerKey = _state.value.rotatedComputerKey?.takeIf { it.machineId == route.itemId }
                    )
                loadCurrentRoute()
            }
            is SharedSettingsIntent.OpenComputer -> {
                cancelDiagnosticsExpiryRefresh()
                _state.value =
                    _state.value.copy(
                        route = SharedSettingsRoute(SharedSettingsDestination.ComputerDetail, intent.machineId),
                        routeErrorCode = null
                    )
                loadComputerDetail(intent.machineId)
            }
            SharedSettingsIntent.Back -> {
                cancelDiagnosticsExpiryRefresh()
                retireServerNotificationModeMutation()
                _state.value =
                    _state.value.copy(
                        route = SharedSettingsRoute(SharedSettingsDestination.Home),
                        routeErrorCode = null,
                        actionState = null,
                        rotatedComputerKey = null
                    )
                loadHome()
            }
            // An explicit refresh bypasses the computers cache (the in-memory computers slice).
            SharedSettingsIntent.Refresh -> loadCurrentRoute(forceComputers = true)
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
                    dataSource.rotateComputerKey(intent.machineId).applyComputerRotateKeyActionResult(intent.machineId)
                }
            }
            SharedSettingsIntent.DismissRotatedComputerKey ->
                if (_state.value.rotatedComputerKey != null) {
                    _state.value = _state.value.copy(rotatedComputerKey = null)
                }
            is SharedSettingsIntent.RegisterComputer -> {
                runAction(COMPUTER_ACTION_REGISTER) {
                    _state.value = _state.value.copy(
                        addComputerDialog = _state.value.addComputerDialog?.copy(
                            registering = true,
                            errorMessage = null,
                            screenError = null,
                            permissionBlocker = null,
                            emptyReason = null,
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
                                    errorMessage = null,
                                    screenError = null,
                                    permissionBlocker = null,
                                    emptyReason = null
                                )
                            )
                            dataSource.loadComputers(forceComputers = true).applyToComputersListState()
                            result.applyComputerRegisterActionResult(result.data.machine.id)
                        }
                        is DataResult.Empty -> {
                            _state.value = _state.value.copy(
                                addComputerDialog = _state.value.addComputerDialog?.copy(
                                    registering = false,
                                    errorMessage = null,
                                    screenError = null,
                                    permissionBlocker = null,
                                    emptyReason = result.reason
                                )
                            )
                            result.applyComputerRegisterActionResult()
                        }
                        is DataResult.Error -> {
                            _state.value = _state.value.copy(
                                addComputerDialog = _state.value.addComputerDialog?.copy(
                                    registering = false,
                                    errorMessage = null,
                                    screenError = result.error,
                                    permissionBlocker = null,
                                    emptyReason = null
                                )
                            )
                            result.applyComputerRegisterActionResult()
                        }
                        is DataResult.NoPermission -> {
                            _state.value = _state.value.copy(
                                addComputerDialog = _state.value.addComputerDialog?.copy(
                                    registering = false,
                                    errorMessage = null,
                                    screenError = null,
                                    permissionBlocker = result.blocker,
                                    emptyReason = null
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
                    addComputerDialog =
                        _state.value.addComputerDialog?.copy(
                            errorMessage = intent.message,
                            screenError = null,
                            permissionBlocker = null,
                            emptyReason = null
                        )
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
                if (serverNotificationModeMutation != null) return
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_NOTIFICATION_PREFERENCE, inProgress = true)
                    )
                dataSource.setNotificationPreference(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_NOTIFICATION_PREFERENCE)
            }
            is SharedSettingsIntent.SetForegroundKeepAlivePreference -> {
                if (serverNotificationModeMutation != null) return
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_FOREGROUND_KEEP_ALIVE, inProgress = true)
                    )
                dataSource.setForegroundKeepAlivePreference(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_FOREGROUND_KEEP_ALIVE)
            }
            is SharedSettingsIntent.SetOfflineComputerAlertsPreference -> {
                if (serverNotificationModeMutation != null) return
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_OFFLINE_COMPUTER_ALERTS, inProgress = true)
                    )
                dataSource.setOfflineComputerAlertsPreference(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_OFFLINE_COMPUTER_ALERTS)
            }
            is SharedSettingsIntent.SetServerMute -> {
                if (serverNotificationModeMutation != null) return
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_SERVER_MUTE, inProgress = true)
                    )
                dataSource.setServerMute(intent.enabled).applyMobileActionResult(MOBILE_ACTION_SERVER_MUTE)
            }
            is SharedSettingsIntent.SetServerNotificationMode -> {
                setServerNotificationMode(intent.mode)
            }
            is SharedSettingsIntent.SetPushPreviewMode -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_PUSH_PREVIEW_MODE, inProgress = true)
                    )
                dataSource.setPushPreviewMode(intent.mode).applyMobileActionResult(MOBILE_ACTION_PUSH_PREVIEW_MODE)
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
            is SharedSettingsIntent.SetFontPreference -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_FONT_PREFERENCE, inProgress = true)
                    )
                // Task #179: local preference. Applying it to the observable face state is what
                // repaints the app; the store write is what survives a cold start.
                dataSource.setFontPreference(intent.fontKey)
                    .applyMobileActionResult(MOBILE_ACTION_FONT_PREFERENCE)
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
            is SharedSettingsIntent.SetThreadUnreadJumpEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_THREAD_UNREAD_JUMP, inProgress = true)
                    )
                dataSource.setThreadUnreadJumpEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_THREAD_UNREAD_JUMP)
            }
            is SharedSettingsIntent.SetNewMessageMenuEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_NEW_MESSAGE_MENU, inProgress = true)
                    )
                dataSource.setNewMessageMenuEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_NEW_MESSAGE_MENU)
            }
            is SharedSettingsIntent.SetConversationForwardGestureEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE, inProgress = true)
                    )
                dataSource.setConversationForwardGestureEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE)
            }
            is SharedSettingsIntent.SetForegroundMessageBanners -> {
                if (serverNotificationModeMutation != null) return
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
            is SharedSettingsIntent.SetDesktopModeOnLargeScreen -> {
                diagnosticsLogger(
                    "settings_desktop_mode_dispatch enabled=${intent.enabled} " +
                        "hasRepository=${dataSource.hasChatLayoutPreferenceRepositoryForDiagnostics()}"
                )
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_DESKTOP_MODE, inProgress = true)
                    )
                val result = dataSource.setDesktopModeOnLargeScreen(intent.enabled)
                diagnosticsLogger(
                    "settings_desktop_mode_result result=${result.settingsStoreDiagnosticsResult()}"
                )
                result.applyMobileActionResult(MOBILE_ACTION_DESKTOP_MODE)
            }
            is SharedSettingsIntent.SetServerDrawerEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_SERVER_DRAWER, inProgress = true)
                    )
                dataSource.setServerDrawerEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_SERVER_DRAWER)
            }
            is SharedSettingsIntent.SetNewDesignSettingsEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_NEW_DESIGN_SETTINGS, inProgress = true)
                    )
                dataSource.setNewDesignSettingsEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_NEW_DESIGN_SETTINGS)
            }
            is SharedSettingsIntent.SetHomeServerAvatarEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_HOME_SERVER_AVATAR, inProgress = true)
                    )
                dataSource.setHomeServerAvatarEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_HOME_SERVER_AVATAR)
            }
            is SharedSettingsIntent.SetHttp3Enabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_HTTP3, inProgress = true)
                    )
                dataSource.setHttp3Enabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_HTTP3)
            }
            is SharedSettingsIntent.SetThreadReverseLayoutEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_THREAD_REVERSE_LAYOUT, inProgress = true)
                    )
                dataSource.setThreadReverseLayoutEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_THREAD_REVERSE_LAYOUT)
            }
            is SharedSettingsIntent.SetRealtimeCurlSocketIoEnabled -> {
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState(MOBILE_ACTION_REALTIME_CURL_SOCKETIO, inProgress = true)
                    )
                dataSource.setRealtimeCurlSocketIoEnabled(intent.enabled)
                    .applyMobileActionResult(MOBILE_ACTION_REALTIME_CURL_SOCKETIO)
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
            SharedSettingsIntent.CheckHandsUpdate -> {
                runAction(DEVELOPER_ACTION_HANDS_UPDATE_CHECK) {
                    dataSource.checkHandsUpdate()
                        .applyDeveloperActionResult(DEVELOPER_ACTION_HANDS_UPDATE_CHECK)
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
            is SharedSettingsIntent.SetDiagnosticFeatureFlagOverride -> {
                cancelDiagnosticsExpiryRefresh()
                _state.value =
                    _state.value.copy(
                        actionState = SharedSettingsActionState("diagnosticFeatureFlagOverride:${intent.key}", inProgress = true)
                    )
                dataSource.setDiagnosticFeatureFlagOverride(intent.key, intent.mode)
                    .applyDiagnosticsActionResult("diagnosticFeatureFlagOverride:${intent.key}")
            }
            SharedSettingsIntent.SendFeedback ->
                _state.value = _state.value.copy(effect = SharedSettingsEffect.SendFeedback)
            SharedSettingsIntent.ConsumeEffect ->
                _state.value = _state.value.copy(effect = null)
        }
    }

    private suspend fun loadHome() {
        dataSource.loadHome().applyToState(SharedSettingsDestination.Home) { copy(home = it) }
    }

    private suspend fun loadCurrentRoute(forceComputers: Boolean = false) {
        when (val destination = _state.value.route.destination) {
            SharedSettingsDestination.Home -> loadHome()
            SharedSettingsDestination.Account ->
                dataSource.loadAccount().applyToState(destination) { copy(account = it) }
            SharedSettingsDestination.Computers ->
                dataSource.loadComputers(forceComputers = forceComputers).applyToState(destination) { copy(computers = it) }
            SharedSettingsDestination.ComputerDetail ->
                loadComputerDetail(_state.value.route.itemId.orEmpty(), forceComputers = forceComputers)
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
                dataSource.loadMobile().preservingPendingServerNotificationMode()
                    .applyToState(destination) { copy(mobile = it) }
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
                loadDiagnostics()
            SharedSettingsDestination.Router -> loadHome()
        }
    }

    private suspend fun refreshComputers() {
        dataSource.loadComputers(forceComputers = true)
            .applyToState(SharedSettingsDestination.Computers) { copy(computers = it) }
    }

    private suspend fun loadComputerDetail(machineId: String, forceComputers: Boolean = false) {
        if (machineId.isBlank()) {
            _state.value =
                _state.value.copy(
                    computerDetail = ScreenLoadState.Error(ScreenError("settings_computer_required", retryable = false)),
                    routeErrorCode = "settings_computer_required"
                )
            return
        }
        dataSource.loadComputerDetail(machineId, forceComputers = forceComputers).applyToState(SharedSettingsDestination.ComputerDetail) {
            copy(computerDetail = it)
        }
    }

    private suspend fun loadAboutWithUpdateStatus() {
        dataSource.loadAbout().applyToState(SharedSettingsDestination.About) { copy(about = it) }
        val updateResult = dataSource.checkHandsUpdateStatus()
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
                                        detail = "",
                                        errorCode =
                                            updateResult.error.code.ifBlank {
                                                "update_check_failed"
                                            },
                                        screenError = updateResult.error
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
                    dataSource.loadMobile().preservingPendingServerNotificationMode()
                        .applyToState(SharedSettingsDestination.Notifications) { copy(mobile = it) }
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
                if (openId.isBlank() || openId != machineId) return
            }
            SharedSettingsDestination.Computers -> Unit
            else -> return
        }
        // Single-flight: a burst of machine:status / machine:updated frames coalesces into one
        // in-flight reload plus at most one follow-up that covers every machine seen meanwhile.
        pendingMachineRealtimeIds += machineId
        if (machineRealtimeDrainActive) {
            diagnosticsLogger("settings_machines_realtime_coalesce machineId=$machineId")
            return
        }
        machineRealtimeDrainActive = true
        try {
            while (pendingMachineRealtimeIds.isNotEmpty()) {
                val machineIds = pendingMachineRealtimeIds.toList()
                pendingMachineRealtimeIds.clear()
                when (_state.value.route.destination) {
                    SharedSettingsDestination.ComputerDetail -> {
                        val openId = _state.value.route.itemId.orEmpty()
                        if (openId.isNotBlank() && openId in machineIds) {
                            dataSource
                                .loadComputerDetail(openId, forceComputers = true)
                                .applyToState(SharedSettingsDestination.ComputerDetail) { copy(computerDetail = it) }
                        }
                    }
                    SharedSettingsDestination.Computers -> {
                        dataSource.loadComputers(forceComputers = true).applyToComputersListState()
                        machineIds.forEach(::maybeAdvanceAddComputerDialogFromMachines)
                    }
                    else -> Unit
                }
            }
        } finally {
            machineRealtimeDrainActive = false
            pendingMachineRealtimeIds.clear()
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
        _state.value =
            _state.value.copy(
                addComputerDialog =
                    dialog.copy(
                        saving = true,
                        errorMessage = null,
                        screenError = null,
                        permissionBlocker = null,
                        emptyReason = null
                    )
            )
        val finalName = dialog.name.trim()
        if (finalName.isNotBlank() && finalName != "my-computer") {
            dataSource.renameComputer(SettingsComputerRenameRequest(machineId = machineId, name = finalName))
        }
        dataSource.loadComputers(forceComputers = true).applyToComputersListState()
        if (standaloneNavigation) {
            // The list page stays the list; the host opens the new machine's own detail page.
            _state.value =
                _state.value.copy(
                    addComputerDialog = null,
                    effect = SharedSettingsEffect.OpenComputer(machineId)
                )
            return
        }
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
        val actionState =
            SharedSettingsActionState(
                action = "clearStorage",
                itemId = intent.target,
                inProgress = false,
                errorCode = intent.code.takeUnless { intent.ok },
                screenError = intent.screenError.takeUnless { intent.ok },
                permissionBlocker = intent.permissionBlocker.takeUnless { intent.ok },
                emptyReason = intent.emptyReason.takeUnless { intent.ok }
            )
        val completionMessage = "Storage cleared.".takeIf { intent.ok }
        val completionError =
            if (intent.ok) {
                null
            } else {
                actionState.errorPresentationOrNull()
                    ?: ScreenError("settings_storage_clear_failed", retryable = false).errorPresentation()
            }
        val currentStorage = _state.value.storage
        val updatedStorage =
            if (intent.snapshot != null) {
                ScreenLoadState.Content(
                    intent.snapshot.copy(
                        feedback =
                            intent.snapshot.feedback?.takeIf { it.isNotBlank() }
                                ?: completionMessage,
                        feedbackError =
                            completionError.takeUnless { !intent.snapshot.feedback.isNullOrBlank() }
                    )
                )
            } else if (currentStorage is ScreenLoadState.Content) {
                ScreenLoadState.Content(
                    currentStorage.data.copy(
                        feedback =
                            completionMessage,
                        feedbackError = completionError
                    )
                )
            } else {
                currentStorage
            }
        _state.value =
            _state.value.copy(
                storage = updatedStorage,
                actionState = actionState,
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
                            },
                        screenError =
                            result.takeIf { it.status == SystemNotificationActionStatus.Error }
                                ?.let {
                                    ScreenError(
                                        code = it.code,
                                        retryable = true,
                                        serverMessage = it.message.takeIf { message -> message.isNotBlank() }
                                    )
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

    private suspend fun setServerNotificationMode(mode: String) {
        val selectedMode = ServerPushMode.fromWire(mode) ?: run {
            _state.value =
                _state.value.copy(
                    actionState =
                        SharedSettingsActionState(
                            action = MOBILE_ACTION_SERVER_MUTE,
                            inProgress = false,
                            errorCode = "settings_server_notification_mode_unknown"
                        ),
                    routeErrorCode = "settings_server_notification_mode_unknown"
                )
            return
        }
        val current = (_state.value.mobile as? ScreenLoadState.Content)?.data ?: return
        if (current.serverNotificationMode.selectedKey == selectedMode.wireName) return
        if (_state.value.actionState?.let { it.action == MOBILE_ACTION_SERVER_MUTE && it.inProgress } == true) return

        val ownerResult = runCatchingPreservingCancellation { dataSource.currentMobileSettingsOwnerKey() }
        if (ownerResult.isFailure) {
            _state.value =
                _state.value.copy(
                    actionState =
                        SharedSettingsActionState(
                            action = MOBILE_ACTION_SERVER_MUTE,
                            inProgress = false,
                            errorCode = "settings_server_notification_mode_owner_unavailable"
                        ),
                    routeErrorCode = "settings_server_notification_mode_owner_unavailable"
                )
            return
        }
        val ownerKey = ownerResult.getOrNull()

        val generation = ++serverNotificationModeMutationGeneration
        val ownerRoute = _state.value.route
        val previousMode = current.serverNotificationMode
        val previousMute = current.muteServer
        serverNotificationModeMutation =
            ServerNotificationModeMutation(
                generation = generation,
                ownerKey = ownerKey,
                ownerRoute = ownerRoute,
                previousMode = previousMode,
                previousMute = previousMute
            )
        _state.value =
            _state.value.copy(
                mobile =
                    ScreenLoadState.Content(
                        current.withServerNotificationModeProjection(selectedMode)
                    ),
                actionState =
                    SharedSettingsActionState(
                        action = MOBILE_ACTION_SERVER_MUTE,
                        itemId = selectedMode.wireName,
                        inProgress = true
                    ),
                routeErrorCode = null
            )
        val result =
            runCatchingPreservingCancellation {
                dataSource.setServerNotificationMode(selectedMode.wireName)
            }.getOrElse {
                DataResult.Error(ScreenError("settings_server_notification_mode_failed"))
            }
        applyServerNotificationModeMutationResult(
            result = result,
            generation = generation
        )
    }

    private suspend fun applyServerNotificationModeMutationResult(
        result: DataResult<SettingsMobileSnapshot>,
        generation: Long
    ) {
        val mutation = serverNotificationModeMutation?.takeIf { it.generation == generation } ?: return
        if (_state.value.route != mutation.ownerRoute) {
            retireServerNotificationModeMutation()
            return
        }
        val currentOwnerResult =
            runCatchingPreservingCancellation { dataSource.currentMobileSettingsOwnerKey() }
        if (serverNotificationModeMutation?.generation != generation ||
            _state.value.route != mutation.ownerRoute
        ) {
            retireServerNotificationModeMutation()
            return
        }
        val currentOwner =
            currentOwnerResult.getOrElse {
                retireServerNotificationModeMutation()
                return
            }
        if (currentOwner != mutation.ownerKey) {
            retireServerNotificationModeMutation()
            return
        }

        when (result) {
            is DataResult.Content -> {
                serverNotificationModeMutation = null
                _state.value =
                    _state.value.copy(
                        mobile = ScreenLoadState.Content(result.data),
                        actionState =
                            SharedSettingsActionState(
                                action = MOBILE_ACTION_SERVER_MUTE,
                                itemId = result.data.serverNotificationMode.selectedKey,
                                inProgress = false
                            ),
                        routeErrorCode = null
                    )
            }
            is DataResult.Empty,
            is DataResult.Error,
            is DataResult.NoPermission -> {
                val errorCode = result.toScreenLoadState().routeErrorCode()
                val visible = (_state.value.mobile as? ScreenLoadState.Content)?.data
                serverNotificationModeMutation = null
                _state.value =
                    _state.value.copy(
                        mobile =
                            visible?.copy(
                                serverNotificationMode = mutation.previousMode,
                                muteServer = mutation.previousMute
                            )?.let { ScreenLoadState.Content(it) } ?: _state.value.mobile,
                        actionState =
                            result.toSettingsActionState(
                                action = MOBILE_ACTION_SERVER_MUTE,
                                itemId = mutation.previousMode.selectedKey
                            ),
                        routeErrorCode = errorCode
                    )
            }
        }
    }

    private suspend fun onAuthSessionEvent(event: AuthSessionEvent) {
        if (event is AuthSessionEvent.TokensRotated) return
        // Sign-out or another account: a machine key shown for the previous one must not survive.
        if (
            (event is AuthSessionEvent.Cleared || event is AuthSessionEvent.Authenticated) &&
            _state.value.rotatedComputerKey != null
        ) {
            _state.value = _state.value.copy(rotatedComputerKey = null)
        }
        val mutation = serverNotificationModeMutation ?: return
        val currentOwnerResult =
            runCatchingPreservingCancellation { dataSource.currentMobileSettingsOwnerKey() }
        val activeMutation =
            serverNotificationModeMutation?.takeIf { it.generation == mutation.generation }
                ?: return
        if (currentOwnerResult.isFailure || currentOwnerResult.getOrNull() != activeMutation.ownerKey) {
            retireServerNotificationModeMutation(invalidateServerOwnedProjection = true)
        }
    }

    private fun retireServerNotificationModeMutation(
        invalidateServerOwnedProjection: Boolean = false
    ) {
        if (serverNotificationModeMutation == null) return
        serverNotificationModeMutation = null
        val currentMobile = (_state.value.mobile as? ScreenLoadState.Content)?.data
        _state.value =
            _state.value.copy(
                mobile =
                    if (invalidateServerOwnedProjection && currentMobile != null) {
                        ScreenLoadState.Content(
                            currentMobile.copy(
                                muteServer =
                                    currentMobile.muteServer.copy(
                                        enabled = false,
                                        available = false,
                                        statusLabel = "Unavailable"
                                    ),
                                serverNotificationMode =
                                    ServerPushMode.All.toSettingsMobileSelectionSnapshot(available = false)
                            )
                        )
                    } else {
                        _state.value.mobile
                    },
                actionState = _state.value.actionState.takeUnless { it?.action == MOBILE_ACTION_SERVER_MUTE }
            )
    }

    private fun DataResult<SettingsMobileSnapshot>.applyMobileActionResult(
        action: String,
        itemId: String? = null
    ) {
        val nextMobile = preservingPendingServerNotificationMode().toScreenLoadState()
        val errorCode = nextMobile.routeErrorCode()
        _state.value =
            _state.value.copy(
                mobile =
                    when (nextMobile) {
                        is ScreenLoadState.Content -> nextMobile
                        else -> _state.value.mobile
                    },
                actionState = nextMobile.toSettingsActionState(action, itemId),
                routeErrorCode = errorCode
            )
    }

    private fun DataResult<SettingsMobileSnapshot>.preservingPendingServerNotificationMode(): DataResult<SettingsMobileSnapshot> {
        if (serverNotificationModeMutation == null || this !is DataResult.Content) return this
        val visible = (_state.value.mobile as? ScreenLoadState.Content)?.data ?: return this
        return DataResult.Content(
            data.copy(
                serverNotificationMode = visible.serverNotificationMode,
                muteServer = visible.muteServer
            )
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
                actionState = nextLanguageRegion.toSettingsActionState(action),
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
                actionState = nextAppearance.toSettingsActionState(action),
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
                actionState = nextAccount.toSettingsActionState(action),
                routeErrorCode = nextAccount.routeErrorCode()
            )
    }

    private fun DataResult<AccountProviderLinkStartResult>.applyAccountProviderLinkResult(providerId: String) {
        val resultState = toScreenLoadState()
        _state.value =
            _state.value.copy(
                actionState = resultState.toSettingsActionState(ACCOUNT_ACTION_PROVIDER_CONNECT, providerId),
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
                actionState = nextConnectedApps.toSettingsActionState(action, itemId),
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
                actionState = nextServer.toSettingsActionState(action, itemId),
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
     * typed error (e.g. `computer_has_agents` for 409) on failure without
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
                    actionState = toSettingsActionState(COMPUTER_ACTION_REGISTER)
                )
            is DataResult.Error ->
                _state.value = _state.value.copy(
                    actionState = toSettingsActionState(COMPUTER_ACTION_REGISTER)
                )
            is DataResult.NoPermission ->
                _state.value = _state.value.copy(
                    actionState = toSettingsActionState(COMPUTER_ACTION_REGISTER)
                )
        }
    }

    /**
     * A rotation changes only the machine's key: the detail slice takes the new prefix in place (no
     * reload), and the full key goes to [SharedSettingsState.rotatedComputerKey] for the one-time
     * dialog. A failure keeps the page and records the action error like the other computer actions.
     */
    private fun DataResult<SettingsComputerKeyRotationResult>.applyComputerRotateKeyActionResult(machineId: String) {
        when (this) {
            is DataResult.Content -> {
                val current = _state.value
                val detail = (current.computerDetail as? ScreenLoadState.Content)?.data
                _state.value =
                    current.copy(
                        computerDetail =
                            if (detail != null && detail.computer.id == data.machineId) {
                                ScreenLoadState.Content(
                                    detail.copy(computer = detail.computer.copy(apiKeyPrefix = data.apiKeyPrefix))
                                )
                            } else {
                                current.computerDetail
                            },
                        rotatedComputerKey = SettingsComputerRotatedKey(machineId = data.machineId, apiKey = data.apiKey),
                        actionState = SharedSettingsActionState(COMPUTER_ACTION_ROTATE_KEY, machineId, inProgress = false)
                    )
            }
            is DataResult.Empty ->
                DataResult.Empty(reason).applyComputerDetailActionResult(COMPUTER_ACTION_ROTATE_KEY, machineId)
            is DataResult.Error ->
                DataResult.Error(error).applyComputerDetailActionResult(COMPUTER_ACTION_ROTATE_KEY, machineId)
            is DataResult.NoPermission ->
                DataResult.NoPermission(blocker).applyComputerDetailActionResult(COMPUTER_ACTION_ROTATE_KEY, machineId)
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
                actionState = next.toSettingsActionState(action, machineId),
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
                        workspacesError =
                            SettingsSectionErrorSnapshot(
                                code = error.code,
                                retryable = error.retryable,
                                screenError = error
                            )
                    )
                is DataResult.NoPermission ->
                    current.copy(
                        workspacesScanned = true,
                        workspacesError =
                            SettingsSectionErrorSnapshot(
                                code = blocker.code,
                                retryable = false,
                                permissionBlocker = blocker
                            )
                    )
            }
        _state.value =
            _state.value.copy(
                computerDetail = ScreenLoadState.Content(nextDetail),
                actionState =
                    if (this is DataResult.Empty) {
                        SharedSettingsActionState(
                            action = COMPUTER_ACTION_SCAN_WORKSPACES,
                            itemId = machineId,
                            inProgress = false
                        )
                    } else {
                        toSettingsActionState(COMPUTER_ACTION_SCAN_WORKSPACES, machineId)
                    }
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
                actionState = nextDiagnostics.toSettingsActionState(action),
                routeErrorCode = nextDiagnostics.routeErrorCode(),
                effect = null
            )
        (_state.value.diagnostics as? ScreenLoadState.Content)?.data?.let(::scheduleDiagnosticsExpiryRefresh)
    }

    private suspend fun loadDiagnostics(expectedRefreshToken: Long? = null) {
        if (expectedRefreshToken == null) cancelDiagnosticsExpiryRefresh()
        val result = dataSource.loadDiagnostics()
        if (expectedRefreshToken != null && expectedRefreshToken != diagnosticsRefreshToken) return
        result.applyToState(SharedSettingsDestination.Diagnostics) { copy(diagnostics = it) }
        if (expectedRefreshToken != null && expectedRefreshToken != diagnosticsRefreshToken) return
        (_state.value.diagnostics as? ScreenLoadState.Content)?.data?.let(::scheduleDiagnosticsExpiryRefresh)
    }

    private fun scheduleDiagnosticsExpiryRefresh(snapshot: SharedSettingsDiagnosticsSnapshot) {
        val nextExpiry = snapshot.featureFlagOverrides.mapNotNull { it.expiresAtMillis }.minOrNull()
        cancelDiagnosticsExpiryRefresh()
        if (nextExpiry == null || _state.value.route.destination != SharedSettingsDestination.Diagnostics) return
        val token = diagnosticsRefreshToken
        diagnosticsRefreshJob =
            scope.launch {
                diagnosticsRefreshDelay((nextExpiry - nowMillis()).coerceAtLeast(0L))
                if (token != diagnosticsRefreshToken || _state.value.route.destination != SharedSettingsDestination.Diagnostics) return@launch
                loadDiagnostics(expectedRefreshToken = token)
            }
    }

    private fun cancelDiagnosticsExpiryRefresh() {
        diagnosticsRefreshToken += 1L
        diagnosticsRefreshJob?.cancel()
        diagnosticsRefreshJob = null
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
                actionState = nextDeveloper.toSettingsActionState(action, itemId),
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
            is SharedSettingsIntent.LoadPageContent,
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
            is SharedSettingsIntent.SetOfflineComputerAlertsPreference,
            is SharedSettingsIntent.SetServerMute,
            is SharedSettingsIntent.SetServerNotificationMode,
            is SharedSettingsIntent.SetPushPreviewMode,
            is SharedSettingsIntent.SetLanguageRegionServerTranslation,
            is SharedSettingsIntent.SetTranslationMode,
            is SharedSettingsIntent.SetTranslationTargetLanguage,
            is SharedSettingsIntent.UpdateLanguageRegionPreference,
            is SharedSettingsIntent.UpdateAppearance,
            is SharedSettingsIntent.CompleteSystemNotificationAction,
            is SharedSettingsIntent.SetLocale,
            is SharedSettingsIntent.SetFontPreference,
            is SharedSettingsIntent.SetMessageAlignment,
            is SharedSettingsIntent.SetLinkOpenMode,
            is SharedSettingsIntent.SetCompactConsecutiveMessages,
            is SharedSettingsIntent.SetLongMessageCollapseEnabled,
            is SharedSettingsIntent.SetSingleTapOpensThreadEnabled,
            is SharedSettingsIntent.SetThreadUnreadJumpEnabled,
            is SharedSettingsIntent.SetNewMessageMenuEnabled,
            is SharedSettingsIntent.SetConversationForwardGestureEnabled,
            is SharedSettingsIntent.SetForegroundMessageBanners,
            is SharedSettingsIntent.SetActivityTaskStatusBadges,
            is SharedSettingsIntent.SetComposerSendKey,
            is SharedSettingsIntent.SetMultiShellHostEnabled,
            is SharedSettingsIntent.SetDesktopModeOnLargeScreen,
            is SharedSettingsIntent.SetServerDrawerEnabled,
            is SharedSettingsIntent.SetNewDesignSettingsEnabled,
            is SharedSettingsIntent.SetHomeServerAvatarEnabled,
            is SharedSettingsIntent.SetRealtimeCurlSocketIoEnabled,
            is SharedSettingsIntent.SetHttp3Enabled,
            is SharedSettingsIntent.SetThreadReverseLayoutEnabled,
            is SharedSettingsIntent.ClearStorage,
            is SharedSettingsIntent.SetApiEnvironment,
            is SharedSettingsIntent.SaveApiEnvironment,
            SharedSettingsIntent.CheckHandsUpdate,
            is SharedSettingsIntent.CompleteStorageClear,
            SharedSettingsIntent.CopyDiagnostics,
            SharedSettingsIntent.ShareDiagnostics,
            SharedSettingsIntent.ClearDiagnostics,
            is SharedSettingsIntent.SetDiagnosticFeatureFlagOverride,
            SharedSettingsIntent.SendFeedback,
            is SharedSettingsIntent.ScanComputerWorkspaces,
            is SharedSettingsIntent.RenameComputer,
            is SharedSettingsIntent.UpdateComputerDescription,
            is SharedSettingsIntent.DeleteComputer,
            is SharedSettingsIntent.RotateComputerKey,
            SharedSettingsIntent.DismissRotatedComputerKey,
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
    session: StoredAuthSession?
): DataResult<SettingsComputerDetailSnapshot> =
    when (this) {
        is DataResult.Content -> {
            val permissions =
                settingsComputerPermissions(
                    serverRole = session.settingsCurrentServer()?.role,
                    currentUserId = session?.user?.id,
                    computer = data.computer
                )
            DataResult.Content(
                data.copy(
                    canManageMachines = permissions.canEdit || permissions.canRemove || permissions.canRotateKey,
                    canEditMachine = permissions.canEdit,
                    canRemoveMachine = permissions.canRemove,
                    canRotateMachineKey = permissions.canRotateKey
                )
            )
        }
        is DataResult.Empty -> this
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
    }

internal data class SettingsComputerPermissions(val canEdit: Boolean, val canRemove: Boolean, val canRotateKey: Boolean)

/**
 * Mirrors the server gate `canManageMachineResource` (slock packages/server/src/routes/servers.ts):
 * the machine creator (`machines.userId`) or a role holding `editMachines` (PATCH) /
 * `removeMachines` (DELETE). Per packages/shared/src/serverPermissions.ts only owner and admin hold
 * those capabilities. Like the web detail panel, attaching the backing Computer also counts for
 * edit/remove. Key rotation (`POST .../rotate-key`) follows the server alone: the machine creator or
 * `rotateMachineKeys` (owner/admin); attaching the Computer does not grant it.
 */
internal fun settingsComputerPermissions(
    serverRole: String?,
    currentUserId: String?,
    computer: SettingsComputerSnapshot
): SettingsComputerPermissions {
    val role = serverRole?.trim()
    val roleGrantsMachineCapabilities = role == "owner" || role == "admin"
    val userId = currentUserId?.trim()?.takeIf { it.isNotBlank() }
    val isCreator = userId != null && computer.creatorUserId?.trim() == userId
    val ownsMachine = isCreator || computer.attachedByCurrentUser
    return SettingsComputerPermissions(
        canEdit = roleGrantsMachineCapabilities || ownsMachine,
        canRemove = roleGrantsMachineCapabilities || ownsMachine,
        canRotateKey = roleGrantsMachineCapabilities || isCreator
    )
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
        permissionBlocker =
            if (canManage) {
                null
            } else {
                PermissionBlocker(
                    code = snapshot?.addComputerUnavailableReason ?: "server_admin_required",
                    requiredCapability = "manage_machines"
                )
            }
    )
}

/**
 * The server the settings route is scoped to. Never another server: without a current server id
 * (or with an id the session no longer lists) there is no server, so no role and no add/manage
 * authority is borrowed from whichever server happens to be first.
 */
private fun StoredAuthSession?.settingsCurrentServer(): StoredAuthServer? {
    val session = this ?: return null
    val selectedId = session.currentServerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return session.servers.firstOrNull { it.id == selectedId }
}

private fun StoredAuthSession?.canManageCurrentServer(): Boolean =
    settingsCurrentServer()?.role.let { it == "owner" || it == "admin" }

private fun StoredAuthSession?.currentServerSlugForSettings(): String? {
    val session = this ?: return null
    return session.selectedServerSlug?.takeIf { it.isNotBlank() }
        ?: session.settingsCurrentServer()?.slug?.takeIf { it.isNotBlank() }
}

private fun ScreenLoadState<*>.routeErrorCode(): String? =
    when (this) {
        is ScreenLoadState.Empty -> reason.name
        is ScreenLoadState.Error -> error.code
        is ScreenLoadState.NoPermission -> blocker.code
        is ScreenLoadState.Content,
        ScreenLoadState.Loading -> null
    }

private fun ScreenLoadState<*>.toSettingsActionState(
    action: String,
    itemId: String? = null
): SharedSettingsActionState =
    SharedSettingsActionState(
        action = action,
        itemId = itemId,
        inProgress = false,
        errorCode = routeErrorCode(),
        screenError = (this as? ScreenLoadState.Error)?.error,
        permissionBlocker = (this as? ScreenLoadState.NoPermission)?.blocker,
        emptyReason = (this as? ScreenLoadState.Empty)?.reason
    )

private fun DataResult<*>.toSettingsActionState(
    action: String,
    itemId: String? = null
): SharedSettingsActionState =
    toScreenLoadState().toSettingsActionState(action, itemId)

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
internal const val MOBILE_ACTION_OFFLINE_COMPUTER_ALERTS = "offlineComputerAlerts"
internal const val MOBILE_ACTION_SERVER_MUTE = "serverMute"
internal const val MOBILE_ACTION_PUSH_PREVIEW_MODE = "pushPreviewMode"
internal const val MOBILE_ACTION_PERMISSION = "notificationPermission"
internal const val MOBILE_ACTION_SETTINGS = "openNotificationSettings"
internal const val DEVELOPER_ACTION_API_ENVIRONMENT = "apiEnvironment"
internal const val DEVELOPER_ACTION_HANDS_UPDATE_CHECK = "handsUpdateCheck"
internal const val MOBILE_ACTION_TEST_NOTIFICATION = "sendTestNotification"
internal const val MOBILE_ACTION_LANGUAGE = "language"
internal const val MOBILE_ACTION_MESSAGE_ALIGNMENT = "messageAlignment"
internal const val MOBILE_ACTION_FONT_PREFERENCE = "fontPreference"
internal const val MOBILE_ACTION_LINK_OPEN_MODE = "linkOpenMode"
internal const val MOBILE_ACTION_COMPACT_MESSAGES = "compactMessages"
internal const val MOBILE_ACTION_LONG_MESSAGE_COLLAPSE = "longMessageCollapse"
internal const val MOBILE_ACTION_SINGLE_TAP_OPENS_THREAD = "singleTapOpensThread"
internal const val MOBILE_ACTION_THREAD_UNREAD_JUMP = "threadUnreadJump"
internal const val MOBILE_ACTION_NEW_MESSAGE_MENU = "newMessageMenu"
internal const val MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE = "conversationForwardGesture"
internal const val MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS = "foregroundMessageBanners"
internal const val MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES = "activityTaskStatusBadges"
internal const val MOBILE_ACTION_COMPOSER_SEND_KEY = "composerSendKey"
internal const val MOBILE_ACTION_MULTI_SHELL_HOST = "multiShellHost"
internal const val MOBILE_ACTION_SERVER_DRAWER = "serverDrawer"
internal const val MOBILE_ACTION_DESKTOP_MODE = "desktopModeOnLargeScreen"
internal const val MOBILE_ACTION_NEW_DESIGN_SETTINGS = "newDesignSettings"
internal const val MOBILE_ACTION_HOME_SERVER_AVATAR = "homeServerAvatar"
internal const val MOBILE_ACTION_REALTIME_CURL_SOCKETIO = "realtimeCurlSocketIo"
internal const val MOBILE_ACTION_HTTP3 = "http3"
internal const val MOBILE_ACTION_THREAD_REVERSE_LAYOUT = "threadReverseLayout"
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
internal const val DIAGNOSTICS_ACTION_APPLE_SIGNIN_LOCAL_OVERRIDE = "diagnosticsAppleSignInLocalOverride"

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

private fun SettingsMobileSnapshot.withServerNotificationModeProjection(
    mode: ServerPushMode
): SettingsMobileSnapshot {
    val label =
        serverNotificationMode.options
            .firstOrNull { it.key == mode.wireName }
            ?.label
            ?.takeIf { it.isNotBlank() }
            ?: mode.wireName
    return copy(
        muteServer =
            muteServer.copy(
                enabled = mode == ServerPushMode.None,
                statusLabel = if (mode == ServerPushMode.None) "Muted" else "Not muted"
            ),
        serverNotificationMode =
            serverNotificationMode.copy(
                selectedKey = mode.wireName,
                selectedLabel = label,
                statusLabel = label
            )
    )
}

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

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileThreadUnreadJumpSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = threadUnreadJumpEnabled,
        available = true,
        statusLabel = if (threadUnreadJumpEnabled) "Enabled" else "Off",
        detail = "Show the floating unread-message jump control in Thread. Keep this off while the reading-window behavior is experimental."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileNewMessageMenuSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = newMessageMenuEnabled,
        available = true,
        statusLabel = if (newMessageMenuEnabled) "Enabled" else "Off",
        detail = "Long-press a message to show quick reactions and common actions in a compact two-row menu. Turn it off to use the classic message menu."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileConversationForwardGestureSnapshot(
    available: Boolean
): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = available && conversationForwardGestureEnabled,
        available = available,
        statusLabel =
            when {
                !available -> "Unsupported"
                conversationForwardGestureEnabled -> "Enabled"
                else -> "Off"
            },
        detail =
            if (available) {
                "Swipe left from the right edge of Home to reopen the most recent channel or direct message after going back."
            } else {
                "Conversation Forward navigation is available on iOS."
            }
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

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileDesktopModeSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = desktopModeOnLargeScreen,
        available = true,
        statusLabel = if (desktopModeOnLargeScreen) "Enabled" else "Off",
        detail = "On large screens, show the left rail with the list and detail side by side. Turn off to keep the single-pane mobile layout with the bottom tab bar."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileServerDrawerSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = serverDrawerEnabled,
        available = true,
        statusLabel = if (serverDrawerEnabled) "Enabled" else "Off",
        detail = "Use the new multi-server left drawer on Home. When off, keep the current server switcher popover."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileNewDesignSettingsSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = newDesignSettingsEnabled,
        available = true,
        statusLabel = if (newDesignSettingsEnabled) "Enabled" else "Off",
        detail = "Open the new standalone channel settings page. When off, keep the current Thread settings dialog."
    )

private fun ChatLayoutPreferenceSnapshot.toSettingsMobileHomeServerAvatarSnapshot(): SettingsMobileToggleSnapshot =
    SettingsMobileToggleSnapshot(
        enabled = homeServerAvatarEnabled,
        available = true,
        statusLabel = if (homeServerAvatarEnabled) "Enabled" else "Off",
        detail = "Show the current workspace avatar in the Home header. When off, keep the existing compact title badge."
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

private fun StoredAuthSession?.toSharedSettingsHomeSnapshot(): SharedSettingsHomeSnapshot {
    val server = this?.currentServer()
    return SharedSettingsHomeSnapshot(
        subtitle = "",
        items =
            listOf(
                nav(SharedSettingsDestination.Account, "Account", "Profile, email, password, connected accounts", RaftLucideIconName.User, this?.user?.bestDisplayName),
                nav(SharedSettingsDestination.LanguageRegion, "Language & Region", "Language, timezone, translation", RaftLucideIconName.Languages, null),
                nav(SharedSettingsDestination.Appearance, "Appearance", "Message font size and reading density", RaftLucideIconName.Type, null),
                nav(SharedSettingsDestination.Notifications, "Notifications", "Push status and workspace mute", RaftLucideIconName.Bell, null),
                nav(SharedSettingsDestination.Mobile, "Mobile", "Links, conversations, and composer", RaftLucideIconName.Smartphone, null),
                nav(SharedSettingsDestination.Storage, "Storage", "Local cache and temporary files", RaftLucideIconName.Archive, null),
                nav(SharedSettingsDestination.Server, "Server Profile", "Workspace name, slug, role", RaftLucideIconName.Building2, server?.role?.titleLabel()),
                nav(SharedSettingsDestination.Billing, "Billing", "Plan and usage", RaftLucideIconName.CreditCard, server?.plan?.titleLabel()),
                nav(SharedSettingsDestination.Administration, "Administration", "Admins, invites, join links, agreements", RaftLucideIconName.Shield, null),
                nav(SharedSettingsDestination.ConnectedApps, "Connected Apps", "Marketplace, installed apps, OAuth clients", RaftLucideIconName.Link2, null),
                nav(SharedSettingsDestination.Computers, "Computers", "Connected machines and workspaces", RaftLucideIconName.Monitor, null),
                nav(SharedSettingsDestination.About, "About", "Version and build information", RaftLucideIconName.BadgeInfo, null)
            )
    )
}

private fun nav(
    destination: SharedSettingsDestination,
    title: String,
    subtitle: String,
    iconName: RaftLucideIconName,
    value: String?,
): SharedSettingsNavItem =
    SharedSettingsNavItem(
        destination = destination,
        title = title,
        subtitle = subtitle,
        iconName = iconName,
        value = value,
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
