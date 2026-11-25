package build.raft.app

import build.raft.app.components.RaftDesignSystemEditor
import build.raft.app.components.rememberRaftDesignSystemEditor
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldLabelSize
import build.raft.designsystem.components.RaftTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.components.appendRaftTextLink
import androidx.compose.ui.text.buildAnnotatedString
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftIsDark
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import build.raft.app.RaftCachedImage
import build.raft.app.base.Utils
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.AccountPasswordChangeRequest
import build.raft.app.data.AccountProfileUpdateRequest
import build.raft.app.data.AccountProviderLinkStartRequest
import build.raft.app.data.AccountSettingsSnapshot
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SecondFactorStepUpDialogState
import build.raft.app.data.SecondFactorStepUpIntent
import build.raft.app.data.SharedAccountLoginSecurityIntent
import build.raft.app.data.SharedSignedInDevicesIntent
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.RAFT_MOBILE_OAUTH_COMPLETION_EVENT
import build.raft.app.data.RaftEventBuses
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.secondFactorStepUpWebUrlResolver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.Pager
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.Serializable

const val SETTINGS_ACCOUNT_PAGE = "compose_settings_account"

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
    val displayNameLabel: String = "Display name",
    val displayNamePlaceholder: String = "Your public name",
    val handleLabel: String = "Handle",
    val handlePlaceholder: String = "@handle",
    val emailLabel: String = "Email",
    val emailPlaceholder: String = "you@example.com",
    val verifiedLabel: String = "Verified",
    val saveChangesLabel: String = "Save profile",
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
    val logoutLabel: String = "Sign out",
    val logoutConfirmTitle: String = "Sign out?",
    val logoutConfirmMessage: String = "Sign out of this device? Your account and data are kept; you can sign back in any time.",
    val logoutConfirmLabel: String = "Sign out",
    val logoutSigningOutLabel: String = "Signing out…",
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
    /** The provider whose Disconnect is running, and the provider / message / trace ID of a failed one. */
    val providerDisconnectingId: String? = null,
    val providerDisconnectErrorId: String? = null,
    val providerDisconnectError: String? = null,
    val providerDisconnectErrorTraceId: String? = null,
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
        logoutConfirmTitle = stringResource(Res.string.settings_account_logout_confirm_title),
        logoutConfirmMessage = stringResource(Res.string.settings_account_logout_confirm),
        logoutConfirmLabel = stringResource(Res.string.settings_account_logout_action),
        logoutSigningOutLabel = stringResource(Res.string.settings_account_logout_signing_out),
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
    val loginSecurityNetworkClient =
        remember(bridge, environment.storeScope) { RaftSharedStoreRegistry.get(bridge).networkClient(environment.storeScope) }
    val loginSecurityStore = rememberSettingsAccountLoginSecurityStore(loginSecurityNetworkClient)
    val loginSecurityState by loginSecurityStore.state.collectAsState()
    val signedInDevicesStore =
        rememberSettingsSignedInDevicesStore(
            loginSecurityNetworkClient,
            stepUpWebUrl =
                remember(environment) {
                    secondFactorStepUpWebUrlResolver({ environment.webBaseUrl }) { environment.authStore.readSession() }
                },
        )
    val signedInDevicesState by signedInDevicesStore.state.collectAsState()
    val appLoginsStore = rememberSettingsAppLoginsStore(loginSecurityNetworkClient)
    val appLoginsState by appLoginsStore.state.collectAsState()
    val passwordSetupStore = rememberSettingsAccountPasswordSetupStore(loginSecurityNetworkClient)
    val passwordSetupState by passwordSetupStore.state.collectAsState()
    // The proof dialogs need the account's password state (GET /auth/identities) and its address.
    val accountSnapshot = (settingsState.account as? ScreenLoadState.Content<AccountSettingsSnapshot>)?.data
    val accountPasswordConfigured = accountSnapshot?.passwordConfigured
    val accountEmail = accountSnapshot?.profile?.email ?: session.value?.user?.email
    LaunchedEffect(signedInDevicesStore, accountPasswordConfigured, accountEmail) {
        signedInDevicesStore.dispatch(SharedSignedInDevicesIntent.AccountKnown(accountPasswordConfigured, accountEmail))
    }
    // Web AccountSection `handleDisconnectProvider`: Disconnect refused for the last sign-in of an
    // account without a password reads the sign-in methods again, so the confirm turns into
    // "Set a password first".
    val disconnectRefusedForPassword = settingsAccountDisconnectRefusedForPassword(settingsState.actionState)
    LaunchedEffect(disconnectRefusedForPassword) {
        if (disconnectRefusedForPassword) {
            bridge.log("settings_account_disconnect_refused reason=password_credential_required reload=account")
            settingsViewModel.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Account))
        }
    }
    val linkNavigator = LocalRaftLinkNavigator.current
    // A Settings search result opened this page at a setting: its row is brought into view and located.
    ProvideSettingsSearchLocator(pager.pageData.params) {
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
            // Sign out row can end under three-button navigation.
            bottomContentPadding =
                settingsAccountStandaloneBottomContentPadding(raftBottomSafeAreaPadding()),
            // The web deletion page, on every platform.
            accountDeletion =
                SettingsAccountDeletionUi(
                    onOpen = { pager.openSettingsAccountDeletionPage(environment.storeScope) }
                ),
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
            secondFactorStepUp =
                settingsState.secondFactorStepUp?.let { dialog ->
                    SettingsSecondFactorStepUpUi(dialog) { intent ->
                        settingsViewModel.dispatch(SharedSettingsIntent.SecondFactorStepUp(intent))
                    }
                },
            loginSecurity =
                SettingsAccountLoginSecurityUi(
                    state = loginSecurityState,
                    onOpen =
                        settingsAccountLoginSecurityOpener(
                            webBaseUrl = environment.webBaseUrl,
                            serverSlug = { standaloneSettingsServerSlug(session.value) },
                            log = { line -> bridge.log(line) },
                            openInBrowser = { url ->
                                // Passkeys / two-step verification require WebAuthn, which the in-app WebView lacks.
                                if (linkNavigator != null) {
                                    linkNavigator.open(url, RaftLinkOpenIntent.RaftWebPageInBrowser)
                                } else {
                                    bridge.openUrl(url, inBrowser = true)
                                }
                            },
                        ),
                    onRetry = { loginSecurityStore.dispatch(SharedAccountLoginSecurityIntent.Refresh) },
                ),
            signedInDevices =
                SettingsSignedInDevicesUi(
                    state = signedInDevicesState,
                    dispatch = signedInDevicesStore::dispatch,
                    onOpenComputers = { pager.openSettingsComputersPage(environment.storeScope) },
                ),
            appLogins = SettingsAppLoginsUi(state = appLoginsState, dispatch = appLoginsStore::dispatch),
            passwordSetup =
                SettingsAccountPasswordSetupUi(
                    passwordConfigured = accountPasswordConfigured,
                    state = passwordSetupState,
                    dispatch = passwordSetupStore::dispatch,
                ),
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
    /** The open "Verify it's you" of a refused password or sign-in method change; null: none. */
    secondFactorStepUp: SettingsSecondFactorStepUpUi? = null,
    /** Login & security (passkeys, two-step verification); null hides the group (visual fixtures). */
    loginSecurity: SettingsAccountLoginSecurityUi? = null,
    /** Signed-in devices (web SignedInDevicesSection); null hides it (visual fixtures). */
    signedInDevices: SettingsSignedInDevicesUi? = null,
    /** Apps you signed in to with Raft (web AppLoginsSection); null hides it. */
    appLogins: SettingsAppLoginsUi? = null,
    /**
     * An account without a password: "Set a password" and "Set a password first" (web
     * AccountSection); null (visual fixtures): Change password and the plain Disconnect confirm.
     */
    passwordSetup: SettingsAccountPasswordSetupUi? = null,
    onEvent: (String, String?) -> Unit
) {
    val localizedPayload = payload.localizedPresentationCopy()
    val topSafeArea = if (applyTopSafeArea) raftTopSafeAreaPadding() else 0.dp
    val visibleConnectedAccounts = localizedPayload.connectedAccounts.visibleSettingsConnectedAccounts()
    // Web SettingsPanel `pendingDisconnectProvider`: the provider whose confirm dialog is open.
    val pendingDisconnect = remember { mutableStateOf<String?>(null) }
    val disconnectSubmitted = remember { mutableStateOf(false) }
    // "Set a password first"'s Send setup email was pressed since the dialog opened.
    val passwordSetupSubmitted = remember { mutableStateOf(false) }
    RaftDesignSystemTheme {
        LazyColumn(
            state = rememberSettingsSearchListState(),
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(RaftColor.Canvas)
                    .padding(top = topSafeArea)
                    .raftSafeAreaHorizontalPadding()
                    .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp + bottomContentPadding),
            // Web: each section is `mb-6`, 24dp apart.
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                // Web AccountSection: Profile card followed 12dp below by Change password (or Set a password).
                SettingsFormSection(iconName = RaftLucideIconName.User, title = localizedPayload.profileSectionTitle) {
                    SettingsAccountProfileCard(
                        payload = localizedPayload,
                        avatarCacheIdentity = avatarCacheIdentity,
                        onEvent = onEvent,
                    )
                    SettingsAccountPasswordCard(
                        payload = localizedPayload,
                        onPasswordChange = onPasswordChange,
                        passwordSetup = passwordSetup,
                    )
                }
            }
            if (visibleConnectedAccounts.isNotEmpty()) {
                item(key = SettingsAccountConnectedAccountsSectionTag) {
                    SettingsConnectedAccountsSection(
                        payload = localizedPayload,
                        accounts = visibleConnectedAccounts,
                        onEvent = onEvent,
                        onRequestDisconnect = { providerId ->
                            // Web `handleRequestDisconnectProvider`: the last setup failure and the provider's success line go.
                            passwordSetup?.dispatch(SettingsAccountPasswordSetupIntent.DisconnectRequested)
                            disconnectSubmitted.value = false
                            passwordSetupSubmitted.value = false
                            pendingDisconnect.value = providerId
                        },
                        passwordSetupSentFor = passwordSetup?.state?.sentForProvider,
                    )
                }
            }
            // Web: Passkeys and Two-step verification right after Connected accounts (nothing while
            // auth_second_factor_v1 is off for the person, or before the first read answers).
            if (loginSecurity != null && loginSecurity.state.visible) {
                item(key = SettingsAccountLoginSecurityGroupTag) { SettingsAccountLoginSecurityGroup(loginSecurity) }
            }
            // Web: Signed-in devices after the ways the person signs in, then the Apps they signed in to
            // (each only while it has something to show; their dialogs are composed after the list).
            if (signedInDevices != null && signedInDevices.state.visible) {
                item(key = SettingsSignedInDevicesSectionTag) { SettingsSignedInDevicesSection(signedInDevices) }
            }
            if (appLogins != null && appLogins.state.logins.isNotEmpty()) {
                item(key = SettingsAppLoginsSectionTag) { SettingsAppLoginsSection(appLogins) }
            }
            // Web order (AccountSection.tsx): Product analytics before Session (Sign out).
            if (localizedPayload.usageDataVisible) {
                item(key = SettingsAccountUsageDataSectionTag) {
                    Box(Modifier.fillMaxWidth().testTag(SettingsAccountUsageDataSectionTag)) {
                        SettingsFormSection(
                            iconName = RaftLucideIconName.ChartColumn,
                            title = stringResource(Res.string.settings_usage_data_section_title),
                        ) {
                            SettingsAccountUsageDataRow(
                                sharing = localizedPayload.usageDataSharing,
                                saving = localizedPayload.usageDataSaving,
                                onEvent = onEvent
                            )
                        }
                    }
                }
            }
            item(key = SettingsAccountSessionSectionTag) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .testTag(SettingsAccountSessionSectionTag)
                        .settingsSearchAnchor("sign-out", SettingsSearchSectionShape)
                ) {
                    SettingsLogoutAction(localizedPayload) { onEvent("logout", null) }
                }
            }
            // Web DeleteAccountSection: its own Danger zone group, the last thing on the page,
            // apart from Session's Sign out.
            if (accountDeletion != null) {
                item {
                    SettingsFormSection(
                        iconName = RaftLucideIconName.TriangleAlert,
                        title = stringResource(Res.string.settings_section_danger_zone_title)
                    ) {
                        SettingsDeleteAccountRow(onClick = accountDeletion.onOpen)
                    }
                }
            }
        }
        // The dialog closes once the account reads back with the provider no longer connected.
        val pendingAccount =
            visibleConnectedAccounts.firstOrNull { it.resolvedProviderId() == pendingDisconnect.value && it.status == "connected" }
        LaunchedEffect(pendingDisconnect.value, pendingAccount == null) {
            if (pendingDisconnect.value != null && pendingAccount == null) pendingDisconnect.value = null
        }
        // "Set a password first" closes once its email went out (web: `setPendingDisconnectProvider(null)`).
        val passwordSetupSentFor = passwordSetup?.state?.sentForProvider?.id
        LaunchedEffect(passwordSetupSentFor) {
            if (passwordSetupSentFor != null && passwordSetupSubmitted.value && pendingDisconnect.value == passwordSetupSentFor) {
                pendingDisconnect.value = null
            }
        }
        if (pendingAccount != null) {
            val providerId = pendingAccount.resolvedProviderId()
            val label = pendingAccount.provider.ifBlank { providerId }
            // Web `pendingDisconnectRequiresPassword`: the one linked sign-in, and no password.
            val setup = passwordSetup?.takeIf { settingsAccountDisconnectRequiresPassword(visibleConnectedAccounts, it.passwordConfigured) }
            val disconnecting = localizedPayload.providerDisconnectingId == providerId
            val sending = setup?.state?.sending == true
            val busy = disconnecting || sending
            // The last confirm's failure, of this dialog only (web ConfirmDialog `error`): the send's once
            // one was tried here, else the Disconnect's (a refused one turns the dialog into this one).
            val sendError = setup?.state?.error?.takeIf { passwordSetupSubmitted.value && !sending }
            val disconnectFailed =
                !passwordSetupSubmitted.value && disconnectSubmitted.value && !disconnecting &&
                    localizedPayload.providerDisconnectErrorId == providerId
            val sendFailedFallback = stringResource(Res.string.settings_account_password_setup_failed)
            SettingsDisconnectProviderDialog(
                label = label,
                requiresPassword = setup != null,
                busy = busy,
                error =
                    if (sendError != null) {
                        settingsAccountPasswordSetupErrorText(sendError, sendFailedFallback)
                    } else {
                        localizedPayload.providerDisconnectError.takeIf { disconnectFailed }
                    },
                errorTraceId = if (sendError != null) sendError.traceId else localizedPayload.providerDisconnectErrorTraceId.takeIf { disconnectFailed },
                onConfirm = {
                    if (setup != null) {
                        passwordSetupSubmitted.value = true
                        setup.dispatch(
                            SettingsAccountPasswordSetupIntent.Send(
                                email = localizedPayload.profile.email,
                                provider = SettingsAccountPasswordSetupProvider(id = providerId, label = label)
                            )
                        )
                    } else {
                        disconnectSubmitted.value = true
                        onEvent(SETTINGS_ACCOUNT_ACTION_DISCONNECT_PROVIDER, providerId)
                    }
                },
                onDismiss = { if (!busy) pendingDisconnect.value = null }
            )
        }
        signedInDevices?.let { SettingsSignedInDevicesOverlays(it) }
        appLogins?.let { SettingsAppLoginsOverlays(it) }
        // Last, so it opens over the Disconnect confirm that asked for it.
        if (secondFactorStepUp != null) {
            SecondFactorStepUpDialog(secondFactorStepUp.state, secondFactorStepUp.onIntent)
        }
    }
}

/** The open step-up dialog and where its intents go (SharedSettingsIntent.SecondFactorStepUp). */
internal class SettingsSecondFactorStepUpUi(
    val state: SecondFactorStepUpDialogState,
    val onIntent: (SecondFactorStepUpIntent) -> Unit
)

/** The Delete account row's action: open the web deletion page. */
internal class SettingsAccountDeletionUi(
    val onOpen: () -> Unit
)

/**
 * Delete account (App Store 5.1.1(v)): a plain setting row in its own inset group, the red
 * button its target, under the Danger zone header (web DeleteAccountSection).
 */
@Composable
private fun SettingsDeleteAccountRow(onClick: () -> Unit) {
    val title = stringResource(Res.string.settings_account_delete)
    RaftListRowGroup(Modifier.fillMaxWidth().settingsSearchAnchor("delete-account", SettingsSearchCardShape)) {
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
                    size = RaftButtonSize.Md,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Trash2) }
                )
            }
        )
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
    val description = stringResource(Res.string.settings_usage_data_description)
    val learnMore = stringResource(Res.string.settings_usage_data_learn_more)
    // Web: the description ends in the strong "Learn more" TextLink, inside the same wrapping line.
    val richDescription =
        remember(description, learnMore, onEvent) {
            buildAnnotatedString {
                append(description)
                append(' ')
                appendRaftTextLink(learnMore, onClick = { onEvent(SETTINGS_ACCOUNT_ACTION_OPEN_USAGE_DATA_PRIVACY, null) }, leavesRaft = true)
            }
        }
    RaftListRowGroup(Modifier.fillMaxWidth()) {
        RaftListRow(
            title = title,
            variant = RaftListRowVariant.Plain,
            richDescription = richDescription,
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
    }
}

internal const val SettingsAccountChangePasswordToggleTag = "settings-account-change-password-toggle"
internal const val SettingsAccountConnectedAccountsSectionTag = "settings-account-connected-accounts"
internal const val SettingsAccountUsageDataSectionTag = "settings-account-usage-data"
internal const val SettingsAccountSessionSectionTag = "settings-account-session"

@Composable
private fun SettingsAccountProfileCard(
    payload: RaftSettingsAccountPayload,
    avatarCacheIdentity: RaftMemberAvatarCacheIdentity?,
    onEvent: (String, String?) -> Unit,
) {
    val profile = payload.profile
    val displayNameState = remember(profile.displayName) { mutableStateOf(profile.displayName) }
    val displayName = displayNameState.value
    // Web SettingsProfileCard: the avatar beside the name over "@username", then Display Name,
    // the read-only "@" Username, Email, the form's error and Save Profile.
    SettingsProfileIdentityCard(
        title = profile.displayName.ifBlank { stringResource(Res.string.settings_account_title) },
        subtitle = settingsAccountHeaderHandleLabel(profile.handle).orEmpty(),
        avatar = { SettingsProfileAvatar(profile, payload, avatarCacheIdentity, onEvent) }
    ) {
        RaftField(
            label = payload.displayNameLabel,
            modifier = Modifier.fillMaxWidth().settingsSearchAnchor("display-name"),
            enabled = payload.canEditProfile && !payload.saveInProgress,
            labelSize = RaftFieldLabelSize.Sm
        ) {
            val editor =
                rememberRaftDesignSystemEditor(
                    keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                    keyboardActions = KeyboardActions.Default,
                    keyboardHeightChange = {},
                    focusRequester = remember { FocusRequester() },
                    autoFocus = false,
                    onFocus = {},
                    onBlur = {}
                )
            RaftTextField(
                value = displayName,
                onValueChange = { displayNameState.value = it },
                modifier = Modifier.fillMaxWidth().then(editor.modifier),
                placeholder = payload.displayNamePlaceholder.ifEmpty { null },
                keyboardOptions = editor.keyboardOptions
            )
        }
        RaftField(
            label = payload.handleLabel.takeIf { it.isNotBlank() },
            modifier = Modifier.fillMaxWidth().settingsSearchAnchor("username"),
            labelSize = RaftFieldLabelSize.Sm
        ) {
            SettingsReadOnlyPrefixedField(
                prefix = "@",
                text = profile.handle.removePrefix("@").ifBlank { payload.handlePlaceholder.removePrefix("@") },
                // Copies the handle as shown here (no "@"); nothing for the placeholder.
                copyText = profile.handle.removePrefix("@").ifBlank { null }
            )
        }
        Box(Modifier.fillMaxWidth().settingsSearchAnchor("email")) {
            SettingsEmailField(payload.emailLabel, profile.email, payload.emailPlaceholder, profile.verified, payload.verifiedLabel)
        }
        // Web: the form's one error banner, between Email and Save Profile.
        payload.avatarErrorCode?.takeIf { it.isNotBlank() }?.let { code ->
            SettingsAccountWarning(stringResource(Res.string.settings_account_avatar_upload_failed, code))
        }
        payload.saveErrorCode?.takeIf { it.isNotBlank() }?.let { code ->
            SettingsAccountWarning(stringResource(Res.string.settings_account_profile_save_failed, code))
        }
        SettingsProfileSaveButton(
            text = payload.profileSaveButtonLabel(),
            // Web `disabled={!profileDirty || saving}`.
            enabled = payload.canEditProfile && !payload.saveInProgress && displayName.trim() != profile.displayName,
            onClick = { onEvent("saveProfile", displayName.trim()) }
        )
    }
}

/**
 * Web AccountSection lines 441-559 (`<ListRowGroup id="password" className="mt-3">`): sits directly
 * below `SettingsProfileCard` inside the Account section and above Connected accounts.
 */
@Composable
private fun SettingsAccountPasswordCard(
    payload: RaftSettingsAccountPayload,
    onPasswordChange: (currentPassword: String, newPassword: String) -> Unit,
    passwordSetup: SettingsAccountPasswordSetupUi?,
) {
    val passwordExpandedState = remember(payload.passwordExpanded) { mutableStateOf(payload.passwordExpanded) }
    val currentPasswordState = remember { mutableStateOf(TextFieldValue("")) }
    val newPasswordState = remember { mutableStateOf(TextFieldValue("")) }
    val confirmPasswordState = remember { mutableStateOf(TextFieldValue("")) }
    val passwordDraftError = remember { mutableStateOf<String?>(null) }
    val passwordMinimumError = stringResource(Res.string.settings_account_password_too_short)
    val passwordMismatchError = stringResource(Res.string.settings_account_password_mismatch)
    // Web AccountSection: an account without a password has none to change, so "Set a password"
    // stands in for Change password and its form.
    val setPasswordUi =
        passwordSetup?.takeIf { settingsAccountPasswordRow(it.passwordConfigured) == SettingsAccountPasswordRow.SetPassword }
    RaftListRowGroup(Modifier.fillMaxWidth().settingsSearchAnchor("password", SettingsSearchCardShape)) {
        if (setPasswordUi != null) {
            SettingsAccountSetPasswordRow(
                state = setPasswordUi.state,
                onSend = { setPasswordUi.dispatch(SettingsAccountPasswordSetupIntent.Send(email = payload.profile.email)) },
            )
        } else {
            RaftListRow(
                title = payload.changePasswordTitle,
                modifier = Modifier.testTag(SettingsAccountChangePasswordToggleTag),
                variant = RaftListRowVariant.Plain,
                onClick = {
                    passwordExpandedState.value = !passwordExpandedState.value
                    passwordDraftError.value = null
                },
                enabled = payload.canChangePassword && !payload.passwordInProgress,
                trailing = {
                    RaftLucideIcon(
                        RaftLucideIconName.ChevronDown,
                        modifier = Modifier.size(20.dp),
                        color = raftArgbHex(LocalRaftContentColor.current),
                        rotationDegrees = if (passwordExpandedState.value) 180f else 0f,
                    )
                },
            )
            if (passwordExpandedState.value) {
                RaftSeparator(Modifier.padding(start = 16.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
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
                // raft-ui Avatar lg (web AvatarSlot "profile-tile"): 51dp.
                .size(51.dp)
                // raw-click: an avatar face; the app's press wash draws over it, where raftClickable's
                // overlay would sit behind the opaque avatar.
                .clickable(enabled = payload.canUploadAvatar && !payload.avatarInProgress) { onEvent("uploadAvatar", null) },
        contentAlignment = Alignment.Center
    ) {
        RaftAppAvatar(
            // web AvatarSlot `account-tile type="human"`: your stored avatar, else the placeholder person.
            kind = RaftAvatarKind.Human,
            avatarWireValue = profile.avatarUrl,
            label = profile.avatarLabel,
            avatarSize = 51.dp,
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
        // Web FormField compact → FieldLabel sm: text-caption font-semibold (12/16 600).
        Text(label, style = RaftType.Caption.withRaftFontWeight(FontWeight.SemiBold).copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                value.ifBlank { placeholder },
                modifier = Modifier.raftCopyable(value),
                // Web AccountSection: font-mono text-mono-code (13/20).
                style = RaftTheme.typography.monoCode,
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RaftField(
            label = payload.currentPasswordLabel,
            modifier = Modifier.fillMaxWidth(),
            enabled = !payload.passwordInProgress,
            labelSize = RaftFieldLabelSize.Sm
        ) {
            val editor = rememberSettingsAccountPasswordEditor()
            RaftTextField(
                value = currentPassword,
                onValueChange = onCurrentPasswordChange,
                modifier = Modifier.fillMaxWidth().then(editor.modifier),
                placeholder = null,
                keyboardOptions = editor.keyboardOptions,
                visualTransformation = PasswordVisualTransformation()
            )
        }
        RaftField(
            label = payload.newPasswordLabel,
            modifier = Modifier.fillMaxWidth(),
            enabled = !payload.passwordInProgress,
            labelSize = RaftFieldLabelSize.Sm
        ) {
            val editor = rememberSettingsAccountPasswordEditor()
            RaftTextField(
                value = newPassword,
                onValueChange = onNewPasswordChange,
                modifier = Modifier.fillMaxWidth().then(editor.modifier),
                placeholder = payload.newPasswordPlaceholder.ifEmpty { null },
                keyboardOptions = editor.keyboardOptions,
                visualTransformation = PasswordVisualTransformation()
            )
        }
        RaftField(
            label = payload.confirmPasswordLabel,
            modifier = Modifier.fillMaxWidth(),
            enabled = !payload.passwordInProgress,
            labelSize = RaftFieldLabelSize.Sm
        ) {
            val editor = rememberSettingsAccountPasswordEditor()
            RaftTextField(
                value = confirmPassword,
                onValueChange = onConfirmPasswordChange,
                modifier = Modifier.fillMaxWidth().then(editor.modifier),
                placeholder = null,
                keyboardOptions = editor.keyboardOptions,
                visualTransformation = PasswordVisualTransformation()
            )
        }
        RaftBanner(
            description = stringResource(Res.string.settings_account_device_login_notice),
            status = RaftBannerStatus.Info,
            size = RaftBannerSize.Sm,
            modifier = Modifier.fillMaxWidth(),
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

/**
 * Web `AccountSection.tsx` lines 562-592: its own section (`Plug` header, 24dp below Account) with
 * every visible provider inside one `ListRowGroup` separated by 16dp-inset dividers.
 */
@Composable
private fun SettingsConnectedAccountsSection(
    payload: RaftSettingsAccountPayload,
    accounts: List<RaftSettingsConnectedAccount>,
    onEvent: (String, String?) -> Unit,
    onRequestDisconnect: (providerId: String) -> Unit,
    /** "Set a password first" sent the setup email for this provider (web `passwordSetupSentForProvider`). */
    passwordSetupSentFor: SettingsAccountPasswordSetupProvider? = null
) {
    Box(Modifier.fillMaxWidth().testTag(SettingsAccountConnectedAccountsSectionTag)) {
        SettingsFormSection(iconName = RaftLucideIconName.Plug, title = payload.connectedAccountsTitle) {
            RaftListRowGroup(Modifier.fillMaxWidth()) {
                accounts.forEachIndexed { index, account ->
                    SettingsConnectedAccountRow(
                        account = account,
                        index = index,
                        divider = index > 0,
                        payload = payload,
                        onEvent = onEvent,
                        onRequestDisconnect = onRequestDisconnect,
                    )
                }
            }
            if (accounts.any { payload.providerErrorId == it.resolvedProviderId() }) {
                RaftBanner(
                    description =
                        stringResource(
                            Res.string.settings_account_provider_link_failed,
                            payload.providerErrorCode ?: payload.failedLabel
                        ),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            passwordSetupSentFor?.let { SettingsAccountPasswordSetupSentForProvider(it) }
        }
    }
}

private fun RaftSettingsConnectedAccount.resolvedProviderId(): String = providerId.ifBlank { provider.lowercase() }

/**
 * Web `ProviderAccountRow`: a plain `ListRow` inside Connected accounts' `ListRowGroup`, with the
 * provider mark in an `IconTile`, label (`ListRowTitle`), "Connected as …" / "Not connected"
 * (`ListRowDescription`), and an outline md Connect / Disconnect button.
 */
@Composable
private fun SettingsConnectedAccountRow(
    account: RaftSettingsConnectedAccount,
    index: Int,
    divider: Boolean,
    payload: RaftSettingsAccountPayload,
    onEvent: (String, String?) -> Unit,
    onRequestDisconnect: (providerId: String) -> Unit
) {
    val providerId = account.resolvedProviderId()
    val connecting = payload.providerConnectingId == providerId
    val connected = account.status == "connected"
    val label = account.provider.ifBlank { stringResource(Res.string.settings_account_provider_fallback, index + 1) }
    val statusDetail = connectedAccountStatusDetail(account, connected)
    RaftListRow(
        title = label,
        variant = RaftListRowVariant.Plain,
        divider = divider,
        description = statusDetail,
        leading = {
            RaftIconTile {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    RaftCachedImage(
                        source = providerIconAssetSource(providerId, dark = LocalRaftIsDark.current),
                        contentDescription = label,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        trailing =
            if (connected || account.canConnect) {
                {
                    if (connected) {
                        RaftButton(
                            text = stringResource(Res.string.settings_account_disconnect),
                            onClick = { onRequestDisconnect(providerId) },
                            enabled = payload.providerDisconnectingId != providerId,
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md
                        )
                    } else {
                        RaftButton(
                            text = if (connecting) payload.connectingLabel else payload.connectLabel,
                            onClick = { onEvent("connectProvider", providerId) },
                            enabled = !connecting,
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md
                        )
                    }
                }
            } else {
                null
            },
    )
}

internal const val SettingsDisconnectProviderDialogTag = "settings-account-disconnect-dialog"
internal const val SettingsDisconnectProviderConfirmTag = "settings-account-disconnect-confirm"

/**
 * Web ConfirmDialog for Disconnect: "Disconnect {label}", the explanation in the destructive
 * banner, a failure in a warning banner under it (with its trace ID), ghost Cancel and the red
 * Disconnect ("Disconnecting…" while it runs). For the one linked sign-in of an account without a
 * password ([requiresPassword], web `pendingDisconnectRequiresPassword`) it is "Set a password
 * first": the sentence as plain text (an accent confirm has no danger banner) and the accent Send
 * setup email ("Sending setup email…"). A scrim tap does not close it (web `disablePointerDismissal`).
 */
@Composable
private fun SettingsDisconnectProviderDialog(
    label: String,
    requiresPassword: Boolean,
    busy: Boolean,
    error: String?,
    errorTraceId: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title =
            if (requiresPassword) {
                stringResource(Res.string.settings_account_password_required_title)
            } else {
                stringResource(Res.string.settings_account_disconnect_title, label)
            },
        modifier = Modifier.testTag(SettingsDisconnectProviderDialogTag),
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
            if (requiresPassword) {
                RaftButton(
                    text = stringResource(Res.string.settings_account_send_setup_email),
                    onClick = onConfirm,
                    modifier = Modifier.testTag(SettingsDisconnectProviderConfirmTag),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    loading = busy,
                    loadingLabel = stringResource(Res.string.settings_account_sending_setup_email)
                )
            } else {
                RaftButton(
                    text = stringResource(Res.string.settings_account_disconnect),
                    onClick = onConfirm,
                    modifier = Modifier.testTag(SettingsDisconnectProviderConfirmTag),
                    variant = RaftButtonVariant.Danger,
                    size = RaftButtonSize.Lg,
                    loading = busy,
                    loadingLabel = stringResource(Res.string.settings_account_disconnecting)
                )
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (requiresPassword) {
                RaftText(
                    stringResource(Res.string.settings_account_password_required_message, label),
                    style = RaftTheme.typography.subhead,
                    color = RaftTheme.colors.foreground
                )
            } else {
                RaftBanner(
                    description = stringResource(Res.string.settings_account_disconnect_message, label),
                    status = RaftBannerStatus.Destructive
                )
            }
            error?.takeIf { it.isNotBlank() }?.let {
                SettingsAccountFailureBanner(message = it, traceId = errorTraceId, size = RaftBannerSize.Md)
            }
        }
    }
}

internal const val SettingsLogoutButtonTag = "settings-account-logout"
internal const val SettingsLogoutDialogTag = "settings-account-logout-dialog"
internal const val SettingsLogoutConfirmTag = "settings-account-logout-confirm"
internal const val SettingsLogoutCancelTag = "settings-account-logout-cancel"

/**
 * Web `AccountSignOutSection`: the Session header over a `ListRowGroup` whose plain `ListRow` is
 * the title over the description beside the warning Sign out button. The button opens the web's
 * confirm dialog ([SettingsLogoutConfirmDialog]); the same on Android and iOS.
 */
@Composable
internal fun SettingsLogoutAction(payload: RaftSettingsAccountPayload, onConfirmLogout: () -> Unit) {
    val confirming = remember { mutableStateOf(false) }
    SettingsFormSection(iconName = RaftLucideIconName.LogOut, title = stringResource(Res.string.settings_account_session)) {
        RaftListRowGroup(Modifier.fillMaxWidth()) {
            RaftListRow(
                title = payload.logoutTitle,
                variant = RaftListRowVariant.Plain,
                description = payload.logoutDetail,
                wrapDescription = true,
                trailing = {
                    RaftButton(
                        text = payload.logoutLabel,
                        onClick = { confirming.value = true },
                        modifier = Modifier.testTag(SettingsLogoutButtonTag),
                        variant = RaftButtonVariant.Warning,
                        size = RaftButtonSize.Md
                    )
                }
            )
        }
    }
    if (confirming.value) {
        SettingsLogoutConfirmDialog(
            payload = payload,
            onConfirm = onConfirmLogout,
            onDismiss = { confirming.value = false }
        )
    }
}

/**
 * Web `AccountSignOutSection`'s `ConfirmDialog` (`settings.session.confirm*`): "Sign out?" over
 * the one sentence (plain body text: a warning-variant confirm carries no danger banner), Cancel
 * (ghost) then the orange Sign out. Confirm signs out and shows "Signing out…" until the page
 * leaves; while it runs, Cancel, × and Back do nothing. A tap on the scrim never closes it
 * (web `disablePointerDismissal`).
 */
@Composable
internal fun SettingsLogoutConfirmDialog(
    payload: RaftSettingsAccountPayload,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    initiallySigningOut: Boolean = false
) {
    var signingOut by remember { mutableStateOf(initiallySigningOut) }
    val dismiss = { if (!signingOut) onDismiss() }
    RaftDialog(
        onDismissRequest = dismiss,
        title = payload.logoutConfirmTitle,
        modifier = Modifier.testTag(SettingsLogoutDialogTag),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = payload.logoutCancelLabel,
                onClick = dismiss,
                modifier = Modifier.testTag(SettingsLogoutCancelTag),
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !signingOut
            )
            RaftButton(
                text = payload.logoutConfirmLabel,
                onClick = {
                    if (!signingOut) {
                        signingOut = true
                        onConfirm()
                    }
                },
                modifier = Modifier.testTag(SettingsLogoutConfirmTag),
                variant = RaftButtonVariant.Warning,
                size = RaftButtonSize.Lg,
                loading = signingOut,
                loadingLabel = payload.logoutSigningOutLabel
            )
        }
    ) {
        RaftText(payload.logoutConfirmMessage, style = RaftTheme.typography.subhead, color = RaftTheme.colors.foreground)
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

/** The app editor behaviour for a masked password field (password keyboard, tap-outside dismiss). */
@Composable
private fun rememberSettingsAccountPasswordEditor(): RaftDesignSystemEditor =
    rememberRaftDesignSystemEditor(
        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Password),
        keyboardActions = KeyboardActions.Default,
        keyboardHeightChange = {},
        focusRequester = remember { FocusRequester() },
        autoFocus = false,
        onFocus = {},
        onBlur = {}
    )
