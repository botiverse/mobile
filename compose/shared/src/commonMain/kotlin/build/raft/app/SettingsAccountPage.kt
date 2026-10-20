package build.raft.app

import build.raft.app.RaftCachedImage
import build.raft.app.base.Utils
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.raftCardSurface
import build.raft.app.components.raftSoftWash
import build.raft.app.components.RaftBrutalCard
import build.raft.app.components.RaftBrutalReadOnlyField
import build.raft.app.components.RaftBrutalTextField
import build.raft.app.components.RaftFormFieldSize
import build.raft.app.data.AccountPasswordChangeRequest
import build.raft.app.data.AccountProfileUpdateRequest
import build.raft.app.data.AccountProviderLinkStartRequest
import build.raft.app.data.AccountSettingsSnapshot
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.RAFT_MOBILE_OAUTH_COMPLETION_EVENT
import build.raft.app.data.RaftEventBuses
import build.raft.app.data.StoredAuthSession
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tencent.tmm.kmmresource.compose.stringResource
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
    val logoutTitle: String = "Log out",
    // Mobile-appropriate wording: react web says "this browser", but on the
    // app the equivalent scope is the device — matches the app's existing
    // "on this device" copy (SettingsStoragePage / AuthDataSource). Do NOT
    // align this to react's "browser" (intentional platform copy divergence).
    val logoutDetail: String = "Sign out on this device. Your account and data stay; you can sign back in any time.",
    val logoutLabel: String = "Log out",
    val logoutConfirmMessage: String = "Sign out on this device? Your account and data are kept; you can log back in any time.",
    val logoutConfirmLabel: String = "Log out",
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
    val canEditProfile: Boolean = true,
    val canUploadAvatar: Boolean = true,
    val canChangePassword: Boolean = true,
    val saveInProgress: Boolean = false,
    val saveErrorCode: String? = null
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
        profileSectionTitle = stringResource(MR.strings.settings_account_profile_section),
        displayNameLabel = stringResource(MR.strings.settings_account_display_name),
        displayNamePlaceholder = stringResource(MR.strings.settings_account_display_name_placeholder),
        handleLabel = stringResource(MR.strings.settings_account_name),
        handlePlaceholder = stringResource(MR.strings.settings_account_handle_placeholder),
        emailLabel = stringResource(MR.strings.settings_account_email),
        emailPlaceholder = stringResource(MR.strings.settings_account_email_placeholder),
        verifiedLabel = stringResource(MR.strings.settings_account_verified),
        saveChangesLabel = stringResource(MR.strings.settings_account_save_profile),
        cancelLabel = stringResource(MR.strings.common_cancel),
        connectedAccountsTitle = stringResource(MR.strings.settings_account_connected_accounts),
        connectedLabel = stringResource(MR.strings.settings_account_connected),
        connectLabel = stringResource(MR.strings.settings_account_connect),
        connectingLabel = stringResource(MR.strings.settings_account_connecting),
        failedLabel = stringResource(MR.strings.settings_account_failed),
        logoutTitle = stringResource(MR.strings.settings_account_logout),
        logoutDetail = stringResource(MR.strings.settings_account_logout_detail),
        logoutLabel = stringResource(MR.strings.settings_account_logout),
        logoutConfirmMessage = stringResource(MR.strings.settings_account_logout_confirm),
        logoutConfirmLabel = stringResource(MR.strings.settings_account_logout),
        logoutCancelLabel = stringResource(MR.strings.common_cancel),
        changePasswordTitle = stringResource(MR.strings.settings_account_change_password),
        changePasswordSubtitle = stringResource(MR.strings.settings_account_change_password_detail),
        currentPasswordLabel = stringResource(MR.strings.settings_account_current_password),
        newPasswordLabel = stringResource(MR.strings.settings_account_new_password),
        confirmPasswordLabel = stringResource(MR.strings.settings_account_confirm_password),
        newPasswordPlaceholder = stringResource(MR.strings.settings_account_password_minimum),
        changePasswordButtonLabel = stringResource(MR.strings.settings_account_change_password),
        passwordUpdatingLabel = stringResource(MR.strings.settings_account_updating),
        passwordUpdatedLabel = stringResource(MR.strings.settings_account_password_updated)
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
    SettingsDetailShell(
        title = stringResource(MR.strings.settings_account_title),
        subtitle = "",
        onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    ) {
        SettingsAccountContent(
            payload =
                settingsState.account.toAccountPayload(
                    session = null,
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
        "connectProvider" ->
            value?.takeIf { it.isNotBlank() }?.let { providerId ->
                dispatch(SharedSettingsIntent.ConnectAccountProvider(AccountProviderLinkStartRequest(providerId = providerId)))
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
    onEvent: (String, String?) -> Unit
) {
    val localizedPayload = payload.localizedPresentationCopy()
    val topSafeArea = if (applyTopSafeArea) raftTopSafeAreaPadding() else 0.dp
    val visibleConnectedAccounts = localizedPayload.connectedAccounts.visibleSettingsConnectedAccounts()
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .dismissInputFocusOnScrollableChrome()
                .background(RaftColor.SurfaceWhite)
                .padding(top = topSafeArea)
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
                onPasswordChange
            )
        }
        item { SettingsLogoutAction(localizedPayload) { onEvent("logout", null) } }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsAccountSectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RaftLucideIcon(RaftLucideIconName.User, color = "#FF737373", modifier = Modifier.size(14.dp))
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
    onPasswordChange: (currentPassword: String, newPassword: String) -> Unit
) {
    val profile = payload.profile
    val displayNameState = remember(profile.displayName) { mutableStateOf(profile.displayName) }
    val passwordExpandedState = remember(payload.passwordExpanded) { mutableStateOf(payload.passwordExpanded) }
    val currentPasswordState = remember { mutableStateOf(TextFieldValue("")) }
    val newPasswordState = remember { mutableStateOf(TextFieldValue("")) }
    val confirmPasswordState = remember { mutableStateOf(TextFieldValue("")) }
    val passwordDraftError = remember { mutableStateOf<String?>(null) }
    val passwordMinimumError = stringResource(MR.strings.settings_account_password_too_short)
    val passwordMismatchError = stringResource(MR.strings.settings_account_password_mismatch)
    val displayName = displayNameState.value
    SettingsAccountHardShadowCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsProfileAvatar(profile, payload, avatarCacheIdentity, onEvent)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    profile.displayName.ifBlank { stringResource(MR.strings.settings_account_title) },
                    style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 15.sp, lineHeight = 20.sp, color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                settingsAccountHeaderHandleLabel(profile.handle)?.let {
                    Text(
                        it,
                        style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        payload.avatarErrorCode?.takeIf { it.isNotBlank() }?.let { code ->
            SettingsAccountWarning(stringResource(MR.strings.settings_account_avatar_upload_failed, code))
        }
        RaftBrutalReadOnlyField(
            label = payload.handleLabel,
            value = profile.handle.removePrefix("@").ifBlank { payload.handlePlaceholder.removePrefix("@") },
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
            enabled = payload.canEditProfile && !payload.saveInProgress,
            onClick = { onEvent("saveProfile", displayName.trim()) }
        )
        payload.saveErrorCode?.takeIf { it.isNotBlank() }?.let { code ->
            SettingsAccountWarning(stringResource(MR.strings.settings_account_profile_save_failed, code))
        }
        Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Default).background(RaftColor.BorderBlack))
        if (connectedAccounts.isNotEmpty()) {
            SettingsConnectedAccounts(payload, connectedAccounts, onEvent)
            Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Default).background(RaftColor.BorderBlack))
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
                        .background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Control))
                        .border(RaftAppStroke.Thin, RaftColor.BorderHairline, RoundedCornerShape(RaftAppRadius.Control)),
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(RaftLucideIconName.LoaderCircle, modifier = Modifier.size(16.dp), spinning = true)
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
                style = settingsAccountInputTextStyle().copy(fontSize = 15.sp),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (verified) {
                SettingsStatusBadge(verifiedLabel.raftDisplayUppercase(), RaftColor.AccentLime, RaftLucideIconName.Shield)
            }
        }
    }
}

@Composable
private fun SettingsProfileSaveButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    RaftBrutalActionButton(
        label = text,
        onClick = onClick,
        enabled = enabled,
        backgroundColor = RaftColor.AccentPink,
        disabledBackgroundColor = RaftColor.EmptyStateGray,
        minWidth = 96.dp,
        minHeight = 32.dp,
        textStyle = RaftType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 14.sp, color = RaftColor.TextBlack)
    )
}

@Composable
private fun RaftSettingsAccountPayload.profileSaveButtonLabel(): String =
    when {
        saveInProgress -> stringResource(MR.strings.settings_account_saving)
        saveErrorCode != null -> stringResource(MR.strings.common_retry)
        else -> saveChangesLabel
    }

@Composable
private fun SettingsAccountHardShadowCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    RaftBrutalCard(
        shadowOffset = 3.dp,
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
        RaftLucideIcon(
            if (expanded) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
            modifier = Modifier.size(14.dp)
        )
        Text(
            title,
            style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
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
            SettingsAccountWarning(stringResource(MR.strings.settings_account_password_update_failed, it))
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
    onEvent: (String, String?) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            payload.connectedAccountsTitle,
            style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (accounts.isEmpty()) {
            SettingsAccountEmptyMessage(
                stringResource(MR.strings.settings_account_no_connected_accounts),
                stringResource(MR.strings.settings_account_no_connected_accounts_detail)
            )
        } else {
            accounts.forEachIndexed { index, account ->
                SettingsConnectedAccountRow(account, index, payload, onEvent)
            }
        }
    }
}

@Composable
private fun SettingsConnectedAccountRow(
    account: RaftSettingsConnectedAccount,
    index: Int,
    payload: RaftSettingsAccountPayload,
    onEvent: (String, String?) -> Unit
) {
    val providerId = account.providerId.ifBlank { account.provider.lowercase() }
    val connecting = payload.providerConnectingId == providerId
    val failed = payload.providerErrorId == providerId
    val status =
        when {
            connecting -> "loading"
            failed -> "failed"
            else -> account.status
        }
    val connected = status == "connected"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .raftCardSurface()
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RaftCachedImage(
                source = providerIconAssetSource(providerId),
                contentDescription = account.provider,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    account.provider.ifBlank { stringResource(MR.strings.settings_account_provider_fallback, index + 1) },
                    style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    connectedAccountStatusDetail(account, connected),
                    style = RaftType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (account.canConnect && !connected) {
                SettingsProviderConnectButton(
                    text = if (connecting) payload.connectingLabel else payload.connectLabel,
                    enabled = !connecting,
                    onClick = { onEvent("connectProvider", providerId) }
                )
            } else {
                val (label, color, icon) = connectedStatus(status, payload)
                SettingsStatusBadge(label, color, icon)
            }
        }
        if (failed) {
            SettingsAccountWarning(
                stringResource(
                    MR.strings.settings_account_provider_link_failed,
                    payload.providerErrorCode ?: payload.failedLabel
                )
            )
        }
    }
}

@Composable
private fun SettingsDisclosureRow(iconName: RaftLucideIconName, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp).raftCardSurface().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconBox(iconName, RaftColor.SurfaceWhite)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = RaftType.Body.copy(color = RaftColor.TextBlack), color = RaftColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = RaftType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        RaftLucideIcon(RaftLucideIconName.ChevronRight, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SettingsLogoutAction(payload: RaftSettingsAccountPayload, onConfirmLogout: () -> Unit) {
    val confirming = remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsAccountSectionTitle(stringResource(MR.strings.settings_account_session), RaftLucideIconName.LogOut)
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
                    RaftBrutalActionButton(
                        label = payload.logoutCancelLabel,
                        onClick = { confirming.value = false },
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        backgroundColor = RaftColor.SurfaceWhite,
                        minWidth = 0.dp,
                        minHeight = 44.dp,
                        shadowOffset = 0.dp
                    )
                    RaftBrutalActionButton(
                        label = payload.logoutConfirmLabel,
                        onClick = onConfirmLogout,
                        modifier = Modifier.weight(1f),
                        backgroundColor = RaftColor.AccentRed,
                        minWidth = 0.dp,
                        minHeight = 44.dp,
                        textStyle = RaftType.ButtonBold.copy(color = RaftColor.TextBlack)
                    )
                }
            } else {
                RaftBrutalActionButton(
                    label = payload.logoutLabel,
                    onClick = { confirming.value = true },
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = RaftColor.AccentRed,
                    minWidth = 0.dp,
                    minHeight = 48.dp,
                    textStyle = RaftType.ButtonBold.copy(color = RaftColor.TextBlack)
                )
            }
        }
    }
}

@Composable
private fun SettingsMetaRow(iconName: RaftLucideIconName, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftLucideIcon(iconName, color = "#FF737373", modifier = Modifier.size(14.dp))
        Text(text, style = RaftType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            RaftLucideIcon(iconName, color = "#FF737373", modifier = Modifier.size(14.dp))
        }
        Text(text, style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
    }
}

@Composable
private fun SettingsStatusBadge(text: String, color: Color, iconName: RaftLucideIconName? = null) {
    Row(
        modifier = Modifier.background(raftSoftWash(color), RoundedCornerShape(RaftAppRadius.Chip)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        iconName?.let { RaftLucideIcon(it, modifier = Modifier.size(12.dp)) }
        Text(
            text,
            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsProviderConnectButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    RaftBrutalActionButton(
        label = text,
        onClick = onClick,
        enabled = enabled,
        backgroundColor = RaftColor.SurfaceWhite,
        disabledBackgroundColor = RaftColor.EmptyStateGray,
        minWidth = 84.dp,
        minHeight = 32.dp,
        textStyle = RaftType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 14.sp, color = RaftColor.TextBlack)
    )
}

@Composable
private fun SettingsAccountWarning(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().raftCardSurface(fill = raftSoftWash(RaftColor.SurfaceCream)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RaftLucideIcon(RaftLucideIconName.TriangleAlert, modifier = Modifier.size(14.dp))
        Text(text, style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextBlack), color = RaftColor.TextBlack, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsAccountSuccess(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().raftCardSurface(fill = raftSoftWash(RaftColor.AccentLime)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RaftLucideIcon(RaftLucideIconName.Check, modifier = Modifier.size(14.dp))
        Text(text, style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextBlack), color = RaftColor.TextBlack, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IconBox(iconName: RaftLucideIconName, color: Color, invert: Boolean = false) {
    Box(
        modifier = Modifier.size(38.dp).background(raftSoftWash(color), RoundedCornerShape(RaftAppRadius.Control)).border(RaftAppStroke.Thin, RaftColor.BorderHairline, RoundedCornerShape(RaftAppRadius.Control)),
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(iconName, color = if (invert) "#FFFFFFFF" else "#FF000000", modifier = Modifier.size(18.dp))
    }
}

// Known providers keep their own marks. Unknown social identities retain the
// existing GitHub fallback rather than borrowing another provider's logo.
// task #60: every platform loads the MR-bundled SVG asset (see ProviderIconAsset.kt).
private fun providerIconAssetSource(provider: String): String? =
    when {
        provider.lowercase().contains("google") -> providerIconAssetSource(ProviderIconKey.GoogleColor)
        provider.lowercase().contains("apple") -> providerIconAssetSource(ProviderIconKey.AppleBlack)
        else -> providerIconAssetSource(ProviderIconKey.GitHubBlack)
    }

@Composable
private fun SettingsAccountEmptyMessage(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth().raftCardSurface(fill = raftSoftWash(RaftColor.SurfaceCream)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = RaftType.Body.copy(color = RaftColor.TextBlack), color = RaftColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = RaftType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp, color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
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
            stringResource(MR.strings.settings_account_connected_as, it)
        } ?: stringResource(MR.strings.settings_account_connected)
    } else {
        stringResource(MR.strings.settings_account_not_connected)
    }

private fun connectedStatus(status: String, payload: RaftSettingsAccountPayload): Triple<String, Color, RaftLucideIconName?> =
    when (status) {
        "connected" -> Triple(payload.connectedLabel, RaftColor.AccentCyan, RaftLucideIconName.Link)
        "loading" -> Triple(payload.connectingLabel, RaftColor.BrandYellow, RaftLucideIconName.Link)
        "failed" -> Triple(payload.failedLabel, RaftColor.AccentPink, null)
        else -> Triple(payload.connectLabel, RaftColor.SurfaceWhite, null)
    }
