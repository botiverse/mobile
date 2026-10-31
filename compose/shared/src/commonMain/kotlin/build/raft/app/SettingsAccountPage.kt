package build.raft.app

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftIsDark
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import build.raft.app.RaftCachedImage
import build.raft.app.base.Utils
import build.raft.app.components.RaftBrutalCard
import build.raft.app.components.RaftBrutalReadOnlyField
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftBrutalTextField
import build.raft.app.components.RaftFormFieldSize
import build.raft.app.data.AccountPasswordChangeRequest
import build.raft.app.data.AccountProfileUpdateRequest
import build.raft.app.data.AccountProviderLinkStartRequest
import build.raft.app.data.AccountSettingsSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.RAFT_MOBILE_OAUTH_COMPLETION_EVENT
import build.raft.app.data.RaftEventBuses
import build.raft.app.data.StoredAuthSession
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.raftPlatformName
import kotlin.time.Clock
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.Serializable

const val SETTINGS_ACCOUNT_PAGE = "compose_settings_account"

internal fun settingsAccountInputTextStyle(
    color: Color = RaftColor.TextBlack
): TextStyle =
    RaftType.Input.copy(
        fontFamily = RaftMonoFamily,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = color
    )

@Page(SETTINGS_ACCOUNT_PAGE, supportInLocal = true)
internal class ComposeSettingsAccountPage : AuthenticatedProductPager(AuthenticatedProductRoute.SettingsAccount) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { SettingsAccountScreen() }
    }
}

@Serializable
internal data class RaftSettingsAccountPayload(
    val instanceId: String = "",
    val profile: RaftSettingsAccountProfile = RaftSettingsAccountProfile(),
    val connectedAccounts: List<RaftSettingsConnectedAccount> = emptyList(),
    val profileSectionTitle: String = "Profile",
    val displayNameLabel: String = "Display Name",
    val displayNamePlaceholder: String = "Your public name",
    val handleLabel: String = "Handle",
    val handlePlaceholder: String = "@handle",
    val emailLabel: String = "Email",
    val emailPlaceholder: String = "you@example.com",
    val verifiedLabel: String = "Verified",
    val saveChangesLabel: String = "Save Profile",
    val cancelLabel: String = "Cancel",
    val connectedAccountsTitle: String = "Connected accounts",
    val connectedLabel: String = "Connected",
    val connectLabel: String = "Connect",
    val connectingLabel: String = "Connecting",
    val failedLabel: String = "Failed",
    val avatarInProgress: Boolean = false,
    val avatarErrorCode: String? = null,
    val logoutTitle: String = "Sign out",
    // Mobile-appropriate wording: react web says "this browser", but on the
    // app the equivalent scope is the device — matches the app's existing
    // "on this device" copy (SettingsStoragePage / AuthDataSource). Do NOT
    // align this to react's "browser" (intentional platform copy divergence).
    val logoutDetail: String = "Sign out on this device. Your account and data stay; you can sign back in any time.",
    val logoutLabel: String = "Sign Out",
    val logoutConfirmMessage: String = "Sign out on this device? Your account and data are kept; you can sign back in any time.",
    val logoutConfirmLabel: String = "Sign Out",
    val logoutCancelLabel: String = "Cancel",
    val changePasswordTitle: String = "Change password",
    val changePasswordSubtitle: String = "Requires current password before saving a new one",
    val currentPasswordLabel: String = "Current Password",
    val newPasswordLabel: String = "New Password",
    val confirmPasswordLabel: String = "Confirm Password",
    val newPasswordPlaceholder: String = "Min 8 characters",
    val changePasswordButtonLabel: String = "Change Password",
    val passwordUpdatingLabel: String = "Updating",
    val passwordUpdatedLabel: String = "Password updated!",
    val passwordExpanded: Boolean = false,
    val passwordInProgress: Boolean = false,
    val passwordErrorCode: String? = null,
    val providerConnectingId: String? = null,
    val providerErrorId: String? = null,
    val providerErrorCode: String? = null,
    /** The provider whose Disconnect is running, and the provider / message of a failed one. */
    val providerDisconnectingId: String? = null,
    val providerDisconnectErrorId: String? = null,
    val providerDisconnectError: String? = null,
    val canEditProfile: Boolean = true,
    val canUploadAvatar: Boolean = true,
    val canChangePassword: Boolean = true,
    val saveInProgress: Boolean = false,
    val saveErrorCode: String? = null,
    /** "Share usage data" (Settings › Account on web); hidden while false. */
    val usageDataVisible: Boolean = false,
    val usageDataSharing: Boolean = true,
    val usageDataSaving: Boolean = false
)

@Serializable
internal data class RaftSettingsAccountProfile(
    val displayName: String = "",
    val handle: String = "",
    val email: String = "",
    val avatarLabel: String = "",
    val avatarUrl: String? = null,
    val verified: Boolean = false,
    val roleLabel: String = ""
)

@Serializable
internal data class RaftSettingsConnectedAccount(
    val providerId: String = "",
    val provider: String = "",
    val identifier: String = "",
    val status: String = "notConnected",
    val canConnect: Boolean = status == "notConnected"
)

@Composable
private fun RaftSettingsAccountPayload.localizedPresentationCopy(): RaftSettingsAccountPayload =
    copy(
        profileSectionTitle = stringResource(Res.string.settings_account_profile_section),
        displayNameLabel = stringResource(Res.string.settings_account_display_name),
        displayNamePlaceholder = stringResource(Res.string.settings_account_display_name_placeholder),
        handleLabel = stringResource(Res.string.settings_account_name),
        handlePlaceholder = stringResource(Res.string.settings_account_handle_placeholder),
        emailLabel = stringResource(Res.string.settings_account_email),
        emailPlaceholder = stringResource(Res.string.settings_account_email_placeholder),
        verifiedLabel = stringResource(Res.string.settings_account_verified),
        saveChangesLabel = stringResource(Res.string.settings_account_save_profile),
        cancelLabel = stringResource(Res.string.common_cancel),
        connectedAccountsTitle = stringResource(Res.string.settings_account_connected_accounts),
        connectedLabel = stringResource(Res.string.settings_account_connected),
        connectLabel = stringResource(Res.string.settings_account_connect),
        connectingLabel = stringResource(Res.string.settings_account_connecting),
        failedLabel = stringResource(Res.string.settings_account_failed),
        logoutTitle = stringResource(Res.string.settings_account_logout),
        logoutDetail = stringResource(Res.string.settings_account_logout_detail),
        logoutLabel = stringResource(Res.string.settings_account_logout_action),
        logoutConfirmMessage = stringResource(Res.string.settings_account_logout_confirm),
        logoutConfirmLabel = stringResource(Res.string.settings_account_logout_action),
        logoutCancelLabel = stringResource(Res.string.common_cancel),
        changePasswordTitle = stringResource(Res.string.settings_account_change_password),
        changePasswordSubtitle = stringResource(Res.string.settings_account_change_password_detail),
        currentPasswordLabel = stringResource(Res.string.settings_account_current_password),
        newPasswordLabel = stringResource(Res.string.settings_account_new_password),
        confirmPasswordLabel = stringResource(Res.string.settings_account_confirm_password),
        newPasswordPlaceholder = stringResource(Res.string.settings_account_password_minimum),
        changePasswordButtonLabel = stringResource(Res.string.settings_account_change_password),
        passwordUpdatingLabel = stringResource(Res.string.settings_account_updating),
        passwordUpdatedLabel = stringResource(Res.string.settings_account_password_updated)
    )

internal val settingsAccountSmokePayload: RaftSettingsAccountPayload
    get() {
        val fixture = SettingsVisualFixtureBridge.values
        return RaftSettingsAccountPayload(
            instanceId = "settings-account-smoke",
            profile = RaftSettingsAccountProfile(
                displayName = fixture.ownerDisplayName,
                handle = fixture.ownerHandle,
                email = fixture.ownerEmail,
                avatarLabel = fixture.ownerAvatarLabel,
                verified = fixture.ownerEmailVerified,
                roleLabel = fixture.ownerRoleLabel
            ),
            connectedAccounts = listOf(
                RaftSettingsConnectedAccount("password", "Email password", fixture.ownerEmail, "connected", canConnect = false),
                RaftSettingsConnectedAccount("google", "Google", fixture.ownerEmail, "connected"),
                RaftSettingsConnectedAccount("github", "GitHub", "Not connected", "notConnected")
            )
        )
    }

@Composable
private fun SettingsAccountScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val launchPayload = rememberAccountPayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.toString(),
        pager.pageData.params.optString("instanceId")
    )
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-account") {
            SharedSettingsViewModel(environment.dataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    val session = remember(environment.authStore) { mutableStateOf<StoredAuthSession?>(null) }
    LaunchedEffect(environment.authStore) {
        session.value = environment.authStore.readSession()
    }
    LaunchedEffect(Unit) {
        bridge.log("settings_account_open instanceId=${launchPayload.instanceId}")
        settingsViewModel.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Account))
    }
    DisposableEffect(settingsViewModel) {
        val subscription =
            RaftEventBuses.inProcess().observe(RAFT_MOBILE_OAUTH_COMPLETION_EVENT) { event ->
                if (shouldReloadSettingsAccountAfterMobileOAuth(event)) {
                    settingsViewModel.dispatch(
                        SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Account)
                    )
                }
            }
        onDispose(subscription::close)
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    val accountDeletionStore = rememberAccountDeletionRequestStore(environment)
    val accountDeletionState by accountDeletionStore.state.collectAsState()
    SettingsDetailShell(
        title = stringResource(Res.string.settings_account_title),
        subtitle = "",
        onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    ) {
        SettingsAccountContent(
            payload =
                settingsState.account.toAccountPayload(
                    // The signed-in session stands in until the account loads (as the in-shell page does),
                    // instead of the empty placeholders (you@example.com) on every open.
                    session = session.value,
                    actionState = settingsState.actionState,
                    errorStrings = localizedErrorPresentationStrings()
                ),
            avatarCacheIdentity =
                settingsState.account.settingsAccountAvatarCacheIdentity(session.value),
            applyTopSafeArea = false,
            // This page is hosted outside the app shell, so the account list must
            // reserve the native navigation bar itself. Without this, the inline
            // logout confirmation row can end under three-button navigation.
            bottomContentPadding =
                settingsAccountStandaloneBottomContentPadding(raftBottomSafeAreaPadding()),
            // Hidden where the host can't send the ticket (the Compose desktop app).
            accountDeletion =
                if (raftPlatformSupportsHandsFeedbackSubmit()) {
                    SettingsAccountDeletionUi(
                        state = accountDeletionState,
                        onOpen = accountDeletionStore::open,
                        onConfirm = { accountDeletionStore.request() },
                        onDismiss = accountDeletionStore::dismiss
                    )
                } else {
                    null
                },
            onPasswordChange = { currentPassword, newPassword ->
                settingsViewModel.dispatch(
                    SharedSettingsIntent.ChangeAccountPassword(
                        AccountPasswordChangeRequest(
                            currentPassword = currentPassword,
                            newPassword = newPassword
                        )
                    )
                )
            },
            onEvent = { action, value ->
                handleStandaloneAccountAction(
                    action = action,
                    value = value,
                    settingsState = settingsState,
                    dispatch = settingsViewModel::dispatch,
                    fallbackEvent = { fallbackAction, fallbackValue ->
                        bridge.log("settings_account_unsupported_action action=$fallbackAction value=${fallbackValue.orEmpty()}")
                    }
                )
            }
        )
    }
}

/**
 * Delete account sends its ticket through the Hands feedback channel (no server endpoint yet).
 * The session and the host's About facts are read when the user confirms; no network call
 * besides the ticket itself.
 */
@Composable
private fun rememberAccountDeletionRequestStore(environment: StandaloneSettingsEnvironment): RaftAccountDeletionRequestStore {
    val scope = rememberCoroutineScope()
    val requestedMessage by rememberUpdatedState(
        stringResource(Res.string.settings_account_delete_requested, RAFT_ACCOUNT_DELETION_DAYS)
    )
    val capabilities = environment.settingsCapabilities
    val authStore = environment.authStore
    return remember(scope, capabilities, authStore) {
        RaftAccountDeletionRequestStore(
            scope = scope,
            loadDetails = {
                authStore.readSession()?.let { session ->
                    raftAccountDeletionRequestDetails(
                        user = session.user,
                        about = (capabilities.loadAbout() as? DataResult.Content)?.data,
                        platform = raftPlatformName(),
                        requestedAt = Clock.System.now().toString()
                    )
                }
            },
            // A deletion request must not wait on (or fail with) the diagnostics flush.
            submit = { message ->
                capabilities.submitHandsFeedback(message, attachments = emptyList(), flushDiagnostics = false)
            },
            onSubmitted = { capabilities.bridgeToast(requestedMessage) },
            log = capabilities::log
        )
    }
}

internal fun shouldReloadSettingsAccountAfterMobileOAuth(
    event: kotlinx.serialization.json.JsonObject?,
): Boolean = event?.optString("status") == "settings_account"

/**
 * Standalone account pages are outside the app-shell content inset contract.
 * Keep their final list item above the native navigation bar while retaining the
 * normal visual breathing room used by settings lists.
 */
internal fun settingsAccountStandaloneBottomContentPadding(bottomSafeArea: Dp): Dp =
    bottomSafeArea + RaftAppSpacing.Lg

@Composable
private fun rememberAccountPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): RaftSettingsAccountPayload =
    remember(payload, fallbackPageData, instanceId) {
        parseSettingsAccountPayload(payload, fallbackPageData, instanceId)
    }

private fun handleStandaloneAccountAction(
    action: String,
    value: String?,
    settingsState: SharedSettingsState,
    dispatch: (SharedSettingsIntent) -> Unit,
    fallbackEvent: (String, String?) -> Unit
) {
    when (action) {
        "logout" -> dispatch(SharedSettingsIntent.Logout)
        "uploadAvatar" -> dispatch(SharedSettingsIntent.RequestAccountAvatarUpload)
        SETTINGS_ACCOUNT_ACTION_SET_SHARE_USAGE_DATA ->
            value?.toBooleanStrictOrNull()?.let { enabled ->
                dispatch(SharedSettingsIntent.SetShareUsageData(enabled))
            }
        SETTINGS_ACCOUNT_ACTION_OPEN_USAGE_DATA_PRIVACY -> dispatch(SharedSettingsIntent.OpenShareUsageDataPrivacy)
        "connectProvider" ->
            value?.takeIf { it.isNotBlank() }?.let { providerId ->
                dispatch(SharedSettingsIntent.ConnectAccountProvider(AccountProviderLinkStartRequest(providerId = providerId)))
            }
        SETTINGS_ACCOUNT_ACTION_DISCONNECT_PROVIDER ->
            value?.takeIf { it.isNotBlank() }?.let { providerId ->
                dispatch(SharedSettingsIntent.DisconnectAccountProvider(providerId))
            }
        "saveProfile" -> {
            val account = (settingsState.account as? ScreenLoadState.Content<AccountSettingsSnapshot>)?.data
            val displayName = value.orEmpty().trim()
            if (account != null) {
                dispatch(
                    SharedSettingsIntent.UpdateProfile(
                        AccountProfileUpdateRequest(
                            displayName = displayName.ifBlank { account.profile.displayName },
                            handle = account.profile.handle,
                            email = account.profile.email
                        )
                    )
                )
            }
        }
        else -> fallbackEvent(action, value)
    }
}

@Composable
internal fun SettingsAccountContent(
    payload: RaftSettingsAccountPayload,
    avatarCacheIdentity: RaftMemberAvatarCacheIdentity? = null,
    applyTopSafeArea: Boolean = true,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onPasswordChange: (currentPassword: String, newPassword: String) -> Unit = { _, _ -> },
    /** Delete account; null hides the row (web has no such row, so visual-parity fixtures pass null). */
    accountDeletion: SettingsAccountDeletionUi? = null,
    onEvent: (String, String?) -> Unit
) {
    val localizedPayload = payload.localizedPresentationCopy()
    val topSafeArea = if (applyTopSafeArea) raftTopSafeAreaPadding() else 0.dp
    val visibleConnectedAccounts = localizedPayload.connectedAccounts.visibleSettingsConnectedAccounts()
    // Web SettingsPanel `pendingDisconnectProvider`: the provider whose confirm dialog is open.
    val pendingDisconnect = remember { mutableStateOf<String?>(null) }
    val disconnectSubmitted = remember { mutableStateOf(false) }
    RaftDesignSystemTheme {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .dismissInputFocusOnScrollableChrome()
                    .background(RaftColor.SurfaceWhite)
                    .padding(top = topSafeArea)
                    .raftSafeAreaHorizontalPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SettingsAccountSectionHeader(localizedPayload.profileSectionTitle) }
            item {
                SettingsAccountProfileCard(
                    localizedPayload,
                    visibleConnectedAccounts,
                    avatarCacheIdentity,
                    onEvent,
                    onPasswordChange,
                    onRequestDisconnect = { providerId ->
                        disconnectSubmitted.value = false
                        pendingDisconnect.value = providerId
                    }
                )
            }
            if (localizedPayload.usageDataVisible) {
                item {
                    SettingsAccountUsageDataRow(
                        sharing = localizedPayload.usageDataSharing,
                        saving = localizedPayload.usageDataSaving,
                        onEvent = onEvent
                    )
                }
            }
            item { SettingsLogoutAction(localizedPayload) { onEvent("logout", null) } }
            if (accountDeletion != null) {
                item { SettingsDeleteAccountRow(onClick = accountDeletion.onOpen) }
            }
            item { Spacer(Modifier.height(bottomContentPadding)) }
        }
        // The dialog closes once the account reads back with the provider no longer connected.
        val pendingAccount =
            visibleConnectedAccounts.firstOrNull { it.resolvedProviderId() == pendingDisconnect.value && it.status == "connected" }
        LaunchedEffect(pendingDisconnect.value, pendingAccount == null) {
            if (pendingDisconnect.value != null && pendingAccount == null) pendingDisconnect.value = null
        }
        if (pendingAccount != null) {
            val providerId = pendingAccount.resolvedProviderId()
            val busy = localizedPayload.providerDisconnectingId == providerId
            SettingsDisconnectProviderDialog(
                label = pendingAccount.provider.ifBlank { providerId },
                busy = busy,
                error =
                    localizedPayload.providerDisconnectError
                        .takeIf { disconnectSubmitted.value && !busy && localizedPayload.providerDisconnectErrorId == providerId },
                onConfirm = {
                    disconnectSubmitted.value = true
                    onEvent(SETTINGS_ACCOUNT_ACTION_DISCONNECT_PROVIDER, providerId)
                },
                onDismiss = { if (!busy) pendingDisconnect.value = null }
            )
        }
        if (accountDeletion != null && accountDeletion.state != RaftAccountDeletionRequestState.Idle) {
            SettingsDeleteAccountDialog(
                busy = accountDeletion.state == RaftAccountDeletionRequestState.Submitting,
                failed = accountDeletion.state is RaftAccountDeletionRequestState.Failed,
                onConfirm = accountDeletion.onConfirm,
                onDismiss = accountDeletion.onDismiss
            )
        }
    }
}

/** The Delete account row's state and actions (see [RaftAccountDeletionRequestStore]). */
internal class SettingsAccountDeletionUi(
    val state: RaftAccountDeletionRequestState,
    val onOpen: () -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit
)

/**
 * Delete account (App Store 5.1.1(v)): a plain setting row in its own inset group, the red
 * button its target. Mobile-only; the web has no equivalent row yet.
 */
@Composable
private fun SettingsDeleteAccountRow(onClick: () -> Unit) {
    val title = stringResource(Res.string.settings_account_delete)
    RaftListRowGroup(Modifier.fillMaxWidth()) {
        RaftListRow(
            title = title,
            variant = RaftListRowVariant.Plain,
            description = stringResource(Res.string.settings_account_delete_detail),
            wrapDescription = true,
            trailing = {
                RaftButton(
                    text = stringResource(Res.string.settings_account_delete_action),
                    onClick = onClick,
                    variant = RaftButtonVariant.Danger,
                    size = RaftButtonSize.Md
                )
            }
        )
    }
}

/**
 * Confirm dialog for Delete account, built like [SettingsDisconnectProviderDialog]: the
 * consequences in a destructive banner, a failure in a warning banner under it, ghost Cancel
 * and the red Request deletion ("Requesting…" while it runs; tapping it again retries).
 * A scrim tap does not close it; Cancel, the close button and Back do (not while it runs).
 */
@Composable
internal fun SettingsDeleteAccountDialog(
    busy: Boolean,
    failed: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_account_delete_title),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !busy
            )
            RaftButton(
                text = stringResource(Res.string.settings_account_delete_confirm),
                onClick = onConfirm,
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg,
                loading = busy,
                loadingLabel = stringResource(Res.string.settings_account_delete_requesting)
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftBanner(
                description = stringResource(Res.string.settings_account_delete_message, RAFT_ACCOUNT_DELETION_DAYS),
                status = RaftBannerStatus.Destructive
            )
            if (failed) {
                RaftBanner(
                    description = stringResource(Res.string.settings_account_delete_failed),
                    status = RaftBannerStatus.Warning
                )
            }
        }
    }
}

internal const val SETTINGS_ACCOUNT_ACTION_DISCONNECT_PROVIDER = "disconnectProvider"

internal const val SETTINGS_ACCOUNT_ACTION_SET_SHARE_USAGE_DATA = "setShareUsageData"
internal const val SETTINGS_ACCOUNT_ACTION_OPEN_USAGE_DATA_PRIVACY = "openUsageDataPrivacy"

/**
 * Web `UsageDataSettingsCard` (slock `packages/web/src/components/settings/UsageDataSettings.tsx`): a
 * plain setting row in an inset group, the switch its target; the description ends in "Learn more"
 * (the privacy policy). The switch is disabled while a save runs; a failed save is a toast.
 */
@Composable
private fun SettingsAccountUsageDataRow(
    sharing: Boolean,
    saving: Boolean,
    onEvent: (String, String?) -> Unit
) {
    val title = stringResource(Res.string.settings_usage_data_title)
    RaftListRowGroup(Modifier.fillMaxWidth()) {
        RaftListRow(
            title = title,
            variant = RaftListRowVariant.Plain,
            description = stringResource(Res.string.settings_usage_data_description),
            wrapDescription = true,
            trailing = {
                RaftSwitch(
                    checked = sharing,
                    onCheckedChange = { checked ->
                        onEvent(SETTINGS_ACCOUNT_ACTION_SET_SHARE_USAGE_DATA, checked.toString())
                    },
                    enabled = !saving,
                    label = title
                )
            }
        )
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            RaftTextLink(
                text = stringResource(Res.string.settings_usage_data_learn_more),
                onClick = { onEvent(SETTINGS_ACCOUNT_ACTION_OPEN_USAGE_DATA_PRIVACY, null) },
                variant = RaftTextLinkVariant.Strong,
                style = RaftTheme.typography.callout
            )
        }
    }
}

@Composable
private fun SettingsAccountSectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RaftLucideIcon(RaftLucideIconName.User, color = RaftColor.TextMutedGrayArgb, modifier = Modifier.size(14.dp))
        Text(
            title,
            style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsAccountProfileCard(
    payload: RaftSettingsAccountPayload,
    connectedAccounts: List<RaftSettingsConnectedAccount>,
    avatarCacheIdentity: RaftMemberAvatarCacheIdentity?,
    onEvent: (String, String?) -> Unit,
    onPasswordChange: (currentPassword: String, newPassword: String) -> Unit,
    onRequestDisconnect: (providerId: String) -> Unit
) {
    val profile = payload.profile
    val displayNameState = remember(profile.displayName) { mutableStateOf(profile.displayName) }
    val passwordExpandedState = remember(payload.passwordExpanded) { mutableStateOf(payload.passwordExpanded) }
    val currentPasswordState = remember { mutableStateOf(TextFieldValue("")) }
    val newPasswordState = remember { mutableStateOf(TextFieldValue("")) }
    val confirmPasswordState = remember { mutableStateOf(TextFieldValue("")) }
    val passwordDraftError = remember { mutableStateOf<String?>(null) }
    val passwordMinimumError = stringResource(Res.string.settings_account_password_too_short)
    val passwordMismatchError = stringResource(Res.string.settings_account_password_mismatch)
    val displayName = displayNameState.value
    SettingsAccountHardShadowCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsProfileAvatar(profile, payload, avatarCacheIdentity, onEvent)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    profile.displayName.ifBlank { stringResource(Res.string.settings_account_title) },
                    modifier = Modifier.raftCopyable(profile.displayName),
                    style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 15.sp, lineHeight = 20.sp, color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                settingsAccountHeaderHandleLabel(profile.handle)?.let {
                    Text(
                        it,
                        // The handle copies with its "@", as shown.
                        modifier = Modifier.raftCopyable(it),
                        style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        payload.avatarErrorCode?.takeIf { it.isNotBlank() }?.let { code ->
            SettingsAccountWarning(stringResource(Res.string.settings_account_avatar_upload_failed, code))
        }
        RaftBrutalReadOnlyField(
            label = payload.handleLabel,
            value = profile.handle.removePrefix("@").ifBlank { payload.handlePlaceholder.removePrefix("@") },
            // Copies the handle as shown here (no "@"); nothing for the placeholder.
            copyText = profile.handle.removePrefix("@").ifBlank { null },
            textStyle = settingsAccountInputTextStyle(RaftColor.TextMutedGray),
            textColor = RaftColor.TextMutedGray
        )
        RaftBrutalTextField(
            label = payload.displayNameLabel,
            labelSize = RaftFormFieldSize.Compact,
            value = displayName,
            placeholder = payload.displayNamePlaceholder,
            onValueChange = { displayNameState.value = it },
            enabled = payload.canEditProfile && !payload.saveInProgress,
            textStyle = settingsAccountInputTextStyle()
        )
        SettingsEmailField(payload.emailLabel, profile.email, payload.emailPlaceholder, profile.verified, payload.verifiedLabel)
        SettingsProfileSaveButton(
            text = payload.profileSaveButtonLabel(),
            // Web `disabled={!profileDirty || saving}`.
            enabled = payload.canEditProfile && !payload.saveInProgress && displayName.trim() != profile.displayName,
            onClick = { onEvent("saveProfile", displayName.trim()) }
        )
        payload.saveErrorCode?.takeIf { it.isNotBlank() }?.let { code ->
            SettingsAccountWarning(stringResource(Res.string.settings_account_profile_save_failed, code))
        }
        RaftSeparator()
        if (connectedAccounts.isNotEmpty()) {
            SettingsConnectedAccounts(payload, connectedAccounts, onEvent, onRequestDisconnect)
            RaftSeparator()
        }
        SettingsPasswordDisclosure(
            title = payload.changePasswordTitle,
            expanded = passwordExpandedState.value,
            enabled = payload.canChangePassword && !payload.passwordInProgress
        ) {
            passwordExpandedState.value = !passwordExpandedState.value
            passwordDraftError.value = null
        }
        if (passwordExpandedState.value) {
            SettingsPasswordForm(
                payload = payload,
                currentPassword = currentPasswordState.value,
                newPassword = newPasswordState.value,
                confirmPassword = confirmPasswordState.value,
                draftError = passwordDraftError.value,
                onCurrentPasswordChange = {
                    currentPasswordState.value = it
                    passwordDraftError.value = null
                },
                onNewPasswordChange = {
                    newPasswordState.value = it
                    passwordDraftError.value = null
                },
                onConfirmPasswordChange = {
                    confirmPasswordState.value = it
                    passwordDraftError.value = null
                },
                onSubmit = {
                    val current = currentPasswordState.value.text
                    val next = newPasswordState.value.text
                    val confirm = confirmPasswordState.value.text
                    when {
                        next.length < 8 -> passwordDraftError.value = passwordMinimumError
                        next != confirm -> passwordDraftError.value = passwordMismatchError
                        else -> {
                            passwordDraftError.value = null
                            onPasswordChange(current, next)
                        }
                    }
                }
            )
        }
    }
}

internal fun settingsAccountHeaderHandleLabel(handle: String): String? {
    val trimmed = handle.trim()
    if (trimmed.isBlank()) {
        return null
    }
    return when {
        trimmed.startsWith("@") -> trimmed
        trimmed.contains("@") -> trimmed
        else -> "@$trimmed"
    }
}

@Composable
private fun SettingsProfileAvatar(
    profile: RaftSettingsAccountProfile,
    payload: RaftSettingsAccountPayload,
    avatarCacheIdentity: RaftMemberAvatarCacheIdentity?,
    onEvent: (String, String?) -> Unit
) {
    Box(
        modifier =
            Modifier
                .size(56.dp)
                .clickable(enabled = payload.canUploadAvatar && !payload.avatarInProgress) { onEvent("uploadAvatar", null) },
        contentAlignment = Alignment.Center
    ) {
        RaftAppAvatar(
            avatarWireValue = profile.avatarUrl,
            label = profile.avatarLabel,
            avatarSize = 56.dp,
            cacheSubject = avatarCacheIdentity?.subject,
            cacheServerId = avatarCacheIdentity?.serverId
        )
        if (payload.avatarInProgress) {
            Box(
                modifier =
                    Modifier
                        .size(30.dp)
                        .background(RaftTheme.colors.layerCard, RaftTheme.shapes.control)
                        .border(RaftStroke.Hairline, RaftTheme.colors.lineHairline, RaftTheme.shapes.control),
                contentAlignment = Alignment.Center
            ) {
                RaftSpinner(size = RaftSpinnerSize.Md)
            }
        }
    }
}

@Composable
private fun SettingsEmailField(
    label: String,
    value: String,
    placeholder: String,
    verified: Boolean,
    verifiedLabel: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = RaftType.SectionLabel.copy(fontSize = 11.sp, lineHeight = 14.sp, color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                value.ifBlank { placeholder },
                modifier = Modifier.raftCopyable(value),
                style = settingsAccountInputTextStyle().copy(fontSize = 15.sp),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Web: a soft success "Verified" badge with a 10px shield, or a soft warning "Unverified".
            if (verified) {
                RaftBadge(
                    text = verifiedLabel,
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Success,
                    icon = {
                        RaftLucideIcon(
                            RaftLucideIconName.Shield,
                            modifier = Modifier.size(10.dp),
                            color = raftArgbHex(LocalRaftContentColor.current)
                        )
                    }
                )
            } else if (value.isNotBlank()) {
                RaftBadge(
                    text = stringResource(Res.string.settings_account_unverified),
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Warning
                )
            }
        }
    }
}

@Composable
private fun SettingsProfileSaveButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    // Web `<Button variant="accent" size="md">` (Save Profile, Change Password).
    RaftButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        variant = RaftButtonVariant.Primary,
        size = RaftButtonSize.Md
    )
}

@Composable
private fun RaftSettingsAccountPayload.profileSaveButtonLabel(): String =
    when {
        saveInProgress -> stringResource(Res.string.settings_account_saving)
        saveErrorCode != null -> stringResource(Res.string.common_retry)
        else -> saveChangesLabel
    }

@Composable
private fun SettingsAccountHardShadowCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    RaftBrutalCard(
        contentPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SettingsPasswordDisclosure(
    title: String,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Web: a 16px `foreground-hint` chevron that turns 90° open, the title in subhead semibold.
        RaftLucideIcon(
            RaftLucideIconName.ChevronRight,
            modifier = Modifier.size(16.dp),
            color = raftArgbHex(RaftTheme.colors.foregroundHint),
            rotationDegrees = if (expanded) 90f else 0f
        )
        RaftText(
            title,
            style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsPasswordForm(
    payload: RaftSettingsAccountPayload,
    currentPassword: TextFieldValue,
    newPassword: TextFieldValue,
    confirmPassword: TextFieldValue,
    draftError: String?,
    onCurrentPasswordChange: (TextFieldValue) -> Unit,
    onNewPasswordChange: (TextFieldValue) -> Unit,
    onConfirmPasswordChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RaftBrutalTextField(
            label = payload.currentPasswordLabel,
            labelSize = RaftFormFieldSize.Compact,
            value = currentPassword,
            placeholder = "",
            onValueChange = onCurrentPasswordChange,
            enabled = !payload.passwordInProgress,
            textStyle = settingsAccountInputTextStyle(),
            password = true
        )
        RaftBrutalTextField(
            label = payload.newPasswordLabel,
            labelSize = RaftFormFieldSize.Compact,
            value = newPassword,
            placeholder = payload.newPasswordPlaceholder,
            onValueChange = onNewPasswordChange,
            enabled = !payload.passwordInProgress,
            textStyle = settingsAccountInputTextStyle(),
            password = true
        )
        RaftBrutalTextField(
            label = payload.confirmPasswordLabel,
            labelSize = RaftFormFieldSize.Compact,
            value = confirmPassword,
            placeholder = "",
            onValueChange = onConfirmPasswordChange,
            enabled = !payload.passwordInProgress,
            textStyle = settingsAccountInputTextStyle(),
            password = true
        )
        draftError?.let { SettingsAccountWarning(it) }
        payload.passwordErrorCode?.takeIf { it.isNotBlank() }?.let {
            SettingsAccountWarning(stringResource(Res.string.settings_account_password_update_failed, it))
        }
        SettingsProfileSaveButton(
            text = if (payload.passwordInProgress) payload.passwordUpdatingLabel else payload.changePasswordButtonLabel,
            enabled = payload.canChangePassword && !payload.passwordInProgress,
            onClick = onSubmit
        )
    }
}

@Composable
private fun SettingsConnectedAccounts(
    payload: RaftSettingsAccountPayload,
    accounts: List<RaftSettingsConnectedAccount>,
    onEvent: (String, String?) -> Unit,
    onRequestDisconnect: (providerId: String) -> Unit
) {
    // Web: the title (`text-subhead font-semibold`, mb-2) over the rows, 8dp apart.
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftText(
            payload.connectedAccountsTitle,
            style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (accounts.isEmpty()) {
            SettingsAccountEmptyMessage(
                stringResource(Res.string.settings_account_no_connected_accounts),
                stringResource(Res.string.settings_account_no_connected_accounts_detail)
            )
        } else {
            accounts.forEachIndexed { index, account ->
                SettingsConnectedAccountRow(account, index, payload, onEvent, onRequestDisconnect)
            }
        }
    }
}

private fun RaftSettingsConnectedAccount.resolvedProviderId(): String = providerId.ifBlank { provider.lowercase() }

/**
 * Web `ProviderAccountRow`: a raft-ui Card (min-h-11, px-4 py-3, gap-3) holding the provider mark in
 * an IconTile, the label (subhead semibold) over "Connected as …" / "Not connected" (footnote,
 * `foreground-muted`), and an outline md button: Connect ("Connecting…" while it starts) or
 * Disconnect, which opens the confirm dialog. The card is the Inset variant only for its unpadded
 * body; the row pads itself to the web's 16 x 12.
 */
@Composable
private fun SettingsConnectedAccountRow(
    account: RaftSettingsConnectedAccount,
    index: Int,
    payload: RaftSettingsAccountPayload,
    onEvent: (String, String?) -> Unit,
    onRequestDisconnect: (providerId: String) -> Unit
) {
    val providerId = account.resolvedProviderId()
    val connecting = payload.providerConnectingId == providerId
    val failed = payload.providerErrorId == providerId
    val connected = account.status == "connected"
    val label = account.provider.ifBlank { stringResource(Res.string.settings_account_provider_fallback, index + 1) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftCard(modifier = Modifier.fillMaxWidth(), variant = RaftCardVariant.Inset) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RaftIconTile {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        RaftCachedImage(
                            source = providerIconAssetSource(providerId, dark = LocalRaftIsDark.current),
                            contentDescription = label,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    RaftText(
                        label,
                        style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                        color = RaftTheme.colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val statusDetail = connectedAccountStatusDetail(account, connected)
                    RaftText(
                        statusDetail,
                        // "Connected as x" copies as shown; the bare "Connected" / "Not connected" status doesn't.
                        modifier = Modifier.raftCopyable(statusDetail.takeIf { connected && account.identifier.isNotBlank() }),
                        style = RaftTheme.typography.footnote,
                        color = RaftTheme.colors.foregroundMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (connected) {
                    RaftButton(
                        text = stringResource(Res.string.settings_account_disconnect),
                        onClick = { onRequestDisconnect(providerId) },
                        enabled = payload.providerDisconnectingId != providerId,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md
                    )
                } else if (account.canConnect) {
                    RaftButton(
                        text = if (connecting) payload.connectingLabel else payload.connectLabel,
                        onClick = { onEvent("connectProvider", providerId) },
                        enabled = !connecting,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md
                    )
                }
            }
        }
        if (failed) {
            RaftBanner(
                description =
                    stringResource(
                        Res.string.settings_account_provider_link_failed,
                        payload.providerErrorCode ?: payload.failedLabel
                    ),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm
            )
        }
    }
}

/**
 * Web ConfirmDialog for Disconnect: "Disconnect {label}", the explanation in the destructive
 * banner, a failure in a warning banner under it, ghost Cancel and the red Disconnect
 * ("Disconnecting…" while it runs). A scrim tap does not close it (web `disablePointerDismissal`).
 */
@Composable
private fun SettingsDisconnectProviderDialog(
    label: String,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_account_disconnect_title, label),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !busy
            )
            RaftButton(
                text = stringResource(Res.string.settings_account_disconnect),
                onClick = onConfirm,
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg,
                loading = busy,
                loadingLabel = stringResource(Res.string.settings_account_disconnecting)
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftBanner(
                description = stringResource(Res.string.settings_account_disconnect_message, label),
                status = RaftBannerStatus.Destructive
            )
            error?.takeIf { it.isNotBlank() }?.let {
                RaftBanner(description = it, status = RaftBannerStatus.Warning)
            }
        }
    }
}

@Composable
private fun SettingsLogoutAction(payload: RaftSettingsAccountPayload, onConfirmLogout: () -> Unit) {
    val confirming = remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsAccountSectionTitle(stringResource(Res.string.settings_account_session), RaftLucideIconName.LogOut)
        SettingsAccountHardShadowCard {
            Text(
                payload.logoutTitle,
                style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.TextBlack),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(payload.logoutDetail, style = RaftType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
            if (confirming.value) {
                SettingsAccountWarning(payload.logoutConfirmMessage)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftButton(
                        text = payload.logoutCancelLabel,
                        onClick = { confirming.value = false },
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        size = RaftButtonSize.Lg
                    )
                    RaftButton(
                        text = payload.logoutConfirmLabel,
                        onClick = onConfirmLogout,
                        modifier = Modifier.weight(1f),
                        variant = RaftButtonVariant.Danger,
                        size = RaftButtonSize.Lg
                    )
                }
            } else {
                RaftButton(
                    text = payload.logoutLabel,
                    onClick = { confirming.value = true },
                    modifier = Modifier.fillMaxWidth(),
                    variant = RaftButtonVariant.Danger,
                    size = RaftButtonSize.Lg
                )
            }
        }
    }
}

@Composable
private fun SettingsAccountSectionTitle(text: String, iconName: RaftLucideIconName? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (iconName != null) {
            RaftLucideIcon(iconName, color = RaftColor.TextMutedGrayArgb, modifier = Modifier.size(14.dp))
        }
        Text(text, style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
    }
}

@Composable
private fun SettingsAccountWarning(text: String) {
    // Web `<Banner intent="warning" density="sm">` (a rounded design-system banner).
    RaftBanner(description = text, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm, modifier = Modifier.fillMaxWidth())
}

// Known providers keep their own marks. Unknown social identities retain the
// existing GitHub fallback rather than borrowing another provider's logo.
// task #60: every platform loads the bundled SVG file (see ProviderIconAsset.kt).
// The GitHub and Apple marks are ink (web `text-foreground`): the white files in dark.
private fun providerIconAssetSource(provider: String, dark: Boolean): String? =
    when {
        provider.lowercase().contains("google") -> providerIconAssetSource(ProviderIconKey.GoogleColor)
        provider.lowercase().contains("apple") ->
            providerIconAssetSource(if (dark) ProviderIconKey.AppleWhite else ProviderIconKey.AppleBlack)
        else -> providerIconAssetSource(if (dark) ProviderIconKey.GitHubWhite else ProviderIconKey.GitHubBlack)
    }

@Composable
private fun SettingsAccountEmptyMessage(title: String, detail: String) {
    RaftCard(modifier = Modifier.fillMaxWidth(), title = title, description = detail)
}

// React parity: the web Connected accounts section lists only social auth
// providers (enabledProviders = google/github). The email+password identity
// is already represented by the Email field and the Change password row, so
// a "password" provider row must never render here.
internal fun List<RaftSettingsConnectedAccount>.visibleSettingsConnectedAccounts(
    appleSignInSupported: Boolean = raftPlatformSupportsAppleSignIn()
): List<RaftSettingsConnectedAccount> =
    filterNot { account ->
        val resolvedId = account.providerId.ifBlank { account.provider }.lowercase()
        val identityRow = resolvedId.startsWith("password") || resolvedId.startsWith("email")
        // Sign in with Apple is an iOS-host capability. The sign-in page already gates its
        // button on `raftPlatformSupportsAppleSignIn()`, but this list had no equivalent
        // gate and renders whatever the server advertises, so enabling Apple for mobile
        // surfaced a Connect entry on Android that can never complete.
        //
        // Only the *offer* is suppressed. An Apple identity already linked from an iOS host
        // keeps its row, because hiding an existing connection reads to the user as "this
        // account was unlinked" rather than "you cannot link it from this device".
        val unusableAppleOffer =
            !appleSignInSupported && resolvedId.contains("apple") && account.canConnect
        identityRow || unusableAppleOffer
    }

@Composable
private fun connectedAccountStatusDetail(account: RaftSettingsConnectedAccount, connected: Boolean): String =
    if (connected) {
        account.identifier.takeIf { it.isNotBlank() }?.let {
            stringResource(Res.string.settings_account_connected_as, it)
        } ?: stringResource(Res.string.settings_account_connected)
    } else {
        stringResource(Res.string.settings_account_not_connected)
    }
