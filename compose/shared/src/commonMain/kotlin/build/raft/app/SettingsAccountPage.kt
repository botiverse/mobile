package build.raft.app

import build.raft.app.SlockCachedImage
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalCard
import build.raft.app.components.SlockBrutalReadOnlyField
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.components.SlockFormFieldSize
import build.raft.app.data.AccountPasswordChangeRequest
import build.raft.app.data.AccountProfileUpdateRequest
import build.raft.app.data.AccountProviderLinkStartRequest
import build.raft.app.data.AccountSettingsSnapshot
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.SLOCK_MOBILE_OAUTH_COMPLETION_EVENT
import build.raft.app.data.SlockEventBuses
import build.raft.app.data.StoredAuthSession
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.serialization.Serializable

const val SETTINGS_ACCOUNT_PAGE = "compose_settings_account"

internal fun settingsAccountInputTextStyle(
    color: Color = SlockColor.TextBlack
): TextStyle =
    SlockType.Input.copy(
        fontFamily = SlockMonoFamily,
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
internal data class KuiklySettingsAccountPayload(
    val instanceId: String = "",
    val profile: KuiklySettingsAccountProfile = KuiklySettingsAccountProfile(),
    val connectedAccounts: List<KuiklySettingsConnectedAccount> = emptyList(),
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
internal data class KuiklySettingsAccountProfile(
    val displayName: String = "",
    val handle: String = "",
    val email: String = "",
    val avatarLabel: String = "",
    val avatarUrl: String? = null,
    val verified: Boolean = false,
    val roleLabel: String = ""
)

@Serializable
internal data class KuiklySettingsConnectedAccount(
    val providerId: String = "",
    val provider: String = "",
    val identifier: String = "",
    val status: String = "notConnected",
    val canConnect: Boolean = status == "notConnected"
)

@Composable
private fun KuiklySettingsAccountPayload.localizedPresentationCopy(): KuiklySettingsAccountPayload =
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

internal val settingsAccountSmokePayload: KuiklySettingsAccountPayload
    get() {
        val fixture = SettingsVisualFixtureBridge.values
        return KuiklySettingsAccountPayload(
            instanceId = "settings-account-smoke",
            profile = KuiklySettingsAccountProfile(
                displayName = fixture.ownerDisplayName,
                handle = fixture.ownerHandle,
                email = fixture.ownerEmail,
                avatarLabel = fixture.ownerAvatarLabel,
                verified = fixture.ownerEmailVerified,
                roleLabel = fixture.ownerRoleLabel
            ),
            connectedAccounts = listOf(
                KuiklySettingsConnectedAccount("password", "Email password", fixture.ownerEmail, "connected", canConnect = false),
                KuiklySettingsConnectedAccount("google", "Google", fixture.ownerEmail, "connected"),
                KuiklySettingsConnectedAccount("github", "GitHub", "Not connected", "notConnected")
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
            SlockEventBuses.inProcess().observe(SLOCK_MOBILE_OAUTH_COMPLETION_EVENT) { event ->
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
                settingsAccountStandaloneBottomContentPadding(slockBottomSafeAreaPadding()),
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
    event: com.tencent.kuikly.core.nvi.serialization.json.JSONObject?,
): Boolean = event?.optString("status") == "settings_account"

/**
 * Standalone account pages are outside the app-shell content inset contract.
 * Keep their final list item above the native navigation bar while retaining the
 * normal visual breathing room used by settings lists.
 */
internal fun settingsAccountStandaloneBottomContentPadding(bottomSafeArea: Dp): Dp =
    bottomSafeArea + SlockSpacing.Lg

@Composable
private fun rememberAccountPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsAccountPayload =
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
    payload: KuiklySettingsAccountPayload,
    avatarCacheIdentity: SlockMemberAvatarCacheIdentity? = null,
    applyTopSafeArea: Boolean = true,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onPasswordChange: (currentPassword: String, newPassword: String) -> Unit = { _, _ -> },
    onEvent: (String, String?) -> Unit
) {
    val localizedPayload = payload.localizedPresentationCopy()
    val topSafeArea = if (applyTopSafeArea) slockTopSafeAreaPadding() else 0.dp
    val visibleConnectedAccounts = localizedPayload.connectedAccounts.visibleSettingsConnectedAccounts()
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .dismissInputFocusOnScrollableChrome()
                .background(SlockColor.SurfaceWhite)
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
        SlockLucideIcon(SlockLucideIconName.User, color = "#FF737373", modifier = Modifier.size(14.dp))
        Text(
            title,
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsAccountProfileCard(
    payload: KuiklySettingsAccountPayload,
    connectedAccounts: List<KuiklySettingsConnectedAccount>,
    avatarCacheIdentity: SlockMemberAvatarCacheIdentity?,
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
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 15.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                settingsAccountHeaderHandleLabel(profile.handle)?.let {
                    Text(
                        it,
                        style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        payload.avatarErrorCode?.takeIf { it.isNotBlank() }?.let { code ->
            SettingsAccountWarning(stringResource(MR.strings.settings_account_avatar_upload_failed, code))
        }
        SlockBrutalReadOnlyField(
            label = payload.handleLabel,
            value = profile.handle.removePrefix("@").ifBlank { payload.handlePlaceholder.removePrefix("@") },
            textStyle = settingsAccountInputTextStyle(SlockColor.TextMutedGray),
            textColor = SlockColor.TextMutedGray
        )
        SlockBrutalTextField(
            label = payload.displayNameLabel,
            labelSize = SlockFormFieldSize.Compact,
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
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        if (connectedAccounts.isNotEmpty()) {
            SettingsConnectedAccounts(payload, connectedAccounts, onEvent)
            Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
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
    profile: KuiklySettingsAccountProfile,
    payload: KuiklySettingsAccountPayload,
    avatarCacheIdentity: SlockMemberAvatarCacheIdentity?,
    onEvent: (String, String?) -> Unit
) {
    Box(
        modifier =
            Modifier
                .size(56.dp)
                .clickable(enabled = payload.canUploadAvatar && !payload.avatarInProgress) { onEvent("uploadAvatar", null) },
        contentAlignment = Alignment.Center
    ) {
        SlockAvatar(
            avatarWireValue = profile.avatarUrl,
            label = profile.avatarLabel,
            avatarSize = 56.dp,
            borderWidth = SlockStroke.Default,
            cacheSubject = avatarCacheIdentity?.subject,
            cacheServerId = avatarCacheIdentity?.serverId
        )
        if (payload.avatarInProgress) {
            Box(
                modifier =
                    Modifier
                        .size(30.dp)
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(SlockLucideIconName.LoaderCircle, modifier = Modifier.size(16.dp), spinning = true)
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
        Text(label, style = SlockType.SectionLabel.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                value.ifBlank { placeholder },
                style = settingsAccountInputTextStyle().copy(fontSize = 15.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (verified) {
                SettingsStatusBadge(verifiedLabel.slockDisplayUppercase(), SlockColor.AccentLime, SlockLucideIconName.Shield)
            }
        }
    }
}

@Composable
private fun SettingsProfileSaveButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    SlockBrutalActionButton(
        label = text,
        onClick = onClick,
        enabled = enabled,
        backgroundColor = SlockColor.AccentPink,
        disabledBackgroundColor = SlockColor.EmptyStateGray,
        minWidth = 96.dp,
        minHeight = 32.dp,
        textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextBlack)
    )
}

@Composable
private fun KuiklySettingsAccountPayload.profileSaveButtonLabel(): String =
    when {
        saveInProgress -> stringResource(MR.strings.settings_account_saving)
        saveErrorCode != null -> stringResource(MR.strings.common_retry)
        else -> saveChangesLabel
    }

@Composable
private fun SettingsAccountHardShadowCard(content: @Composable com.tencent.kuikly.compose.foundation.layout.ColumnScope.() -> Unit) {
    SlockBrutalCard(
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
        SlockLucideIcon(
            if (expanded) SlockLucideIconName.ChevronDown else SlockLucideIconName.ChevronRight,
            modifier = Modifier.size(14.dp)
        )
        Text(
            title,
            style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsPasswordForm(
    payload: KuiklySettingsAccountPayload,
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
        SlockBrutalTextField(
            label = payload.currentPasswordLabel,
            labelSize = SlockFormFieldSize.Compact,
            value = currentPassword,
            placeholder = "",
            onValueChange = onCurrentPasswordChange,
            enabled = !payload.passwordInProgress,
            textStyle = settingsAccountInputTextStyle(),
            password = true
        )
        SlockBrutalTextField(
            label = payload.newPasswordLabel,
            labelSize = SlockFormFieldSize.Compact,
            value = newPassword,
            placeholder = payload.newPasswordPlaceholder,
            onValueChange = onNewPasswordChange,
            enabled = !payload.passwordInProgress,
            textStyle = settingsAccountInputTextStyle(),
            password = true
        )
        SlockBrutalTextField(
            label = payload.confirmPasswordLabel,
            labelSize = SlockFormFieldSize.Compact,
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
    payload: KuiklySettingsAccountPayload,
    accounts: List<KuiklySettingsConnectedAccount>,
    onEvent: (String, String?) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            payload.connectedAccountsTitle,
            style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
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
    account: KuiklySettingsConnectedAccount,
    index: Int,
    payload: KuiklySettingsAccountPayload,
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
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.30f))
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SlockCachedImage(
                source = providerIconAssetSource(providerId),
                contentDescription = account.provider,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    account.provider.ifBlank { stringResource(MR.strings.settings_account_provider_fallback, index + 1) },
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    connectedAccountStatusDetail(account, connected),
                    style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
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
private fun SettingsDisclosureRow(iconName: SlockLucideIconName, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp).background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconBox(iconName, SlockColor.SurfaceWhite)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SlockLucideIcon(SlockLucideIconName.ChevronRight, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SettingsLogoutAction(payload: KuiklySettingsAccountPayload, onConfirmLogout: () -> Unit) {
    val confirming = remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsAccountSectionTitle(stringResource(MR.strings.settings_account_session), SlockLucideIconName.LogOut)
        SettingsAccountHardShadowCard {
            Text(
                payload.logoutTitle,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(payload.logoutDetail, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
            if (confirming.value) {
                SettingsAccountWarning(payload.logoutConfirmMessage)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlockBrutalActionButton(
                        label = payload.logoutCancelLabel,
                        onClick = { confirming.value = false },
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        backgroundColor = SlockColor.SurfaceWhite,
                        minWidth = 0.dp,
                        minHeight = 44.dp,
                        shadowOffset = 0.dp
                    )
                    SlockBrutalActionButton(
                        label = payload.logoutConfirmLabel,
                        onClick = onConfirmLogout,
                        modifier = Modifier.weight(1f),
                        backgroundColor = SlockColor.AccentRed,
                        minWidth = 0.dp,
                        minHeight = 44.dp,
                        textStyle = SlockType.ButtonBold.copy(color = SlockColor.TextBlack)
                    )
                }
            } else {
                SlockBrutalActionButton(
                    label = payload.logoutLabel,
                    onClick = { confirming.value = true },
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = SlockColor.AccentRed,
                    minWidth = 0.dp,
                    minHeight = 48.dp,
                    textStyle = SlockType.ButtonBold.copy(color = SlockColor.TextBlack)
                )
            }
        }
    }
}

@Composable
private fun SettingsMetaRow(iconName: SlockLucideIconName, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockLucideIcon(iconName, color = "#FF737373", modifier = Modifier.size(14.dp))
        Text(text, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsAccountSectionTitle(text: String, iconName: SlockLucideIconName? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (iconName != null) {
            SlockLucideIcon(iconName, color = "#FF737373", modifier = Modifier.size(14.dp))
        }
        Text(text, style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
    }
}

@Composable
private fun SettingsStatusBadge(text: String, color: Color, iconName: SlockLucideIconName? = null) {
    Row(
        modifier = Modifier.background(color).border(1.dp, SlockColor.BorderBlack).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        iconName?.let { SlockLucideIcon(it, modifier = Modifier.size(12.dp)) }
        Text(
            text,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsProviderConnectButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    SlockBrutalActionButton(
        label = text,
        onClick = onClick,
        enabled = enabled,
        backgroundColor = SlockColor.SurfaceWhite,
        disabledBackgroundColor = SlockColor.EmptyStateGray,
        minWidth = 84.dp,
        minHeight = 32.dp,
        textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextBlack)
    )
}

@Composable
private fun SettingsAccountWarning(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockLucideIcon(SlockLucideIconName.TriangleAlert, modifier = Modifier.size(14.dp))
        Text(text, style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsAccountSuccess(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(SlockColor.AccentLime).border(SlockStroke.Default, SlockColor.BorderBlack).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(14.dp))
        Text(text, style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IconBox(iconName: SlockLucideIconName, color: Color, invert: Boolean = false) {
    Box(
        modifier = Modifier.size(38.dp).background(color).border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(iconName, color = if (invert) "#FFFFFFFF" else "#FF000000", modifier = Modifier.size(18.dp))
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
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// React parity: the web Connected accounts section lists only social auth
// providers (enabledProviders = google/github). The email+password identity
// is already represented by the Email field and the Change password row, so
// a "password" provider row must never render here.
internal fun List<KuiklySettingsConnectedAccount>.visibleSettingsConnectedAccounts(
    appleSignInSupported: Boolean = slockPlatformSupportsAppleSignIn()
): List<KuiklySettingsConnectedAccount> =
    filterNot { account ->
        val resolvedId = account.providerId.ifBlank { account.provider }.lowercase()
        val identityRow = resolvedId.startsWith("password") || resolvedId.startsWith("email")
        // Sign in with Apple is an iOS-host capability. The sign-in page already gates its
        // button on `slockPlatformSupportsAppleSignIn()`, but this list had no equivalent
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
private fun connectedAccountStatusDetail(account: KuiklySettingsConnectedAccount, connected: Boolean): String =
    if (connected) {
        account.identifier.takeIf { it.isNotBlank() }?.let {
            stringResource(MR.strings.settings_account_connected_as, it)
        } ?: stringResource(MR.strings.settings_account_connected)
    } else {
        stringResource(MR.strings.settings_account_not_connected)
    }

private fun connectedStatus(status: String, payload: KuiklySettingsAccountPayload): Triple<String, Color, SlockLucideIconName?> =
    when (status) {
        "connected" -> Triple(payload.connectedLabel, SlockColor.AccentCyan, SlockLucideIconName.Link)
        "loading" -> Triple(payload.connectingLabel, SlockColor.BrandYellow, SlockLucideIconName.Link)
        "failed" -> Triple(payload.failedLabel, SlockColor.AccentPink, null)
        else -> Triple(payload.connectLabel, SlockColor.SurfaceWhite, null)
    }
