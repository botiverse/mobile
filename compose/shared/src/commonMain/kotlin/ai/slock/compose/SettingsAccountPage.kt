package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.GitHubIconDataUri
import ai.slock.compose.GoogleIconDataUri
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalCard
import ai.slock.compose.components.SlockBrutalReadOnlyField
import ai.slock.compose.components.SlockBrutalTextField
import ai.slock.compose.SlockCachedImage
import ai.slock.compose.data.AccountPasswordChangeRequest
import ai.slock.compose.data.AccountProfileUpdateRequest
import ai.slock.compose.data.AccountProviderLinkStartRequest
import ai.slock.compose.data.AccountSettingsSnapshot
import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.SharedSettingsDestination
import ai.slock.compose.data.SharedSettingsIntent
import ai.slock.compose.data.SharedSettingsState
import ai.slock.compose.data.SharedSettingsViewModel
import ai.slock.compose.model.toGravatarAvatarUrl
import androidx.compose.runtime.Composable
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
import com.tencent.kuikly.compose.ui.text.input.KeyboardType
import com.tencent.kuikly.compose.ui.text.input.PasswordVisualTransformation
import com.tencent.kuikly.compose.ui.text.input.VisualTransformation
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.Serializable

const val SETTINGS_ACCOUNT_PAGE = "compose_settings_account"

@Page(SETTINGS_ACCOUNT_PAGE, supportInLocal = true)
internal class ComposeSettingsAccountPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsAccountScreen() }
    }
}

@Serializable
internal data class KuiklySettingsAccountPayload(
    val instanceId: String = "",
    val profile: KuiklySettingsAccountProfile = KuiklySettingsAccountProfile(),
    val connectedAccounts: List<KuiklySettingsConnectedAccount> = emptyList(),
    val profileSectionTitle: String = "Profile",
    val displayNameLabel: String = "Display name",
    val displayNamePlaceholder: String = "Your public name",
    val handleLabel: String = "Handle",
    val handlePlaceholder: String = "@handle",
    val emailLabel: String = "Email",
    val emailPlaceholder: String = "you@example.com",
    val verifiedLabel: String = "Verified",
    val saveChangesLabel: String = "Save changes",
    val cancelLabel: String = "Cancel",
    val connectedAccountsTitle: String = "Connected accounts",
    val connectedLabel: String = "Connected",
    val connectLabel: String = "Connect",
    val connectingLabel: String = "Connecting",
    val failedLabel: String = "Failed",
    val avatarInProgress: Boolean = false,
    val avatarErrorCode: String? = null,
    val logoutTitle: String = "Log out",
    val logoutDetail: String = "Sign out of this device. Your account and data stay; you can sign back in any time.",
    val logoutLabel: String = "Log out",
    val logoutConfirmMessage: String = "Sign out of this device? Your account and data are kept; you can log back in any time.",
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
    val gravatarHash: String? = null,
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

internal val settingsAccountSmokePayload = KuiklySettingsAccountPayload(
    instanceId = "settings-account-smoke",
    profile = KuiklySettingsAccountProfile(
        displayName = "Artin",
        handle = "@artin",
        email = "artin@raft.build",
        avatarLabel = "AR",
        verified = true,
        roleLabel = "Workspace owner"
    ),
    connectedAccounts = listOf(
        KuiklySettingsConnectedAccount("password", "Email password", "artin@raft.build", "connected", canConnect = false),
        KuiklySettingsConnectedAccount("google", "Google", "artin@raft.build", "connected"),
        KuiklySettingsConnectedAccount("github", "GitHub", "Not connected", "notConnected")
    )
)

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
    LaunchedEffect(Unit) {
        bridge.log("settings_account_open instanceId=${launchPayload.instanceId}")
        settingsViewModel.dispatch(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Account))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    SettingsAccountContent(
        settingsState.account.toAccountPayload(session = null, actionState = settingsState.actionState),
        onPasswordChange = { currentPassword, newPassword ->
            settingsViewModel.dispatch(
                SharedSettingsIntent.ChangeAccountPassword(
                    AccountPasswordChangeRequest(
                        currentPassword = currentPassword,
                        newPassword = newPassword
                    )
                )
            )
        }
    ) { action, value ->
        handleStandaloneAccountAction(
            action = action,
            value = value,
            settingsState = settingsState,
            dispatch = settingsViewModel::dispatch,
            fallbackEvent = { fallbackAction, fallbackValue ->
                bridge.settingsEvent(launchPayload.instanceId, fallbackAction, fallbackValue)
            }
        )
    }
}

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
    applyTopSafeArea: Boolean = true,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onPasswordChange: (currentPassword: String, newPassword: String) -> Unit = { _, _ -> },
    onEvent: (String, String?) -> Unit
) {
    val topSafeArea = if (applyTopSafeArea) slockTopSafeAreaPadding() else 0.dp
    val visibleConnectedAccounts = payload.connectedAccounts.visibleSettingsConnectedAccounts()
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
                .padding(top = topSafeArea)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { SettingsAccountSectionHeader(payload.profileSectionTitle) }
        item { SettingsAccountProfileCard(payload, visibleConnectedAccounts, onEvent, onPasswordChange) }
        item { SettingsLogoutAction(payload) { onEvent("logout", null) } }
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
            title.uppercase(),
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
    onEvent: (String, String?) -> Unit,
    onPasswordChange: (currentPassword: String, newPassword: String) -> Unit
) {
    val profile = payload.profile
    val displayNameState = remember(profile.displayName) { mutableStateOf(profile.displayName) }
    val passwordExpandedState = remember(payload.passwordExpanded) { mutableStateOf(payload.passwordExpanded) }
    val currentPasswordState = remember { mutableStateOf("") }
    val newPasswordState = remember { mutableStateOf("") }
    val confirmPasswordState = remember { mutableStateOf("") }
    val passwordDraftError = remember { mutableStateOf<String?>(null) }
    val displayName = displayNameState.value
    SettingsAccountHardShadowCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsProfileAvatar(profile, payload, onEvent)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    profile.displayName.ifBlank { "Account" },
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
            SettingsAccountWarning("Avatar upload failed: $code")
        }
        SlockBrutalReadOnlyField(
            label = "Name",
            value = profile.handle.removePrefix("@").ifBlank { payload.handlePlaceholder.removePrefix("@") }
        )
        SlockBrutalTextField(
            label = "Display Name",
            value = displayName,
            placeholder = payload.displayNamePlaceholder,
            onValueChange = { displayNameState.value = it },
            enabled = payload.canEditProfile && !payload.saveInProgress
        )
        SettingsEmailField(payload.emailLabel, profile.email, payload.emailPlaceholder, profile.verified, payload.verifiedLabel)
        SettingsProfileSaveButton(
            text =
                when {
                    payload.saveInProgress -> "Saving"
                    payload.saveErrorCode != null -> "Retry"
                    else -> payload.saveChangesLabel
                },
            enabled = payload.canEditProfile && !payload.saveInProgress,
            onClick = { onEvent("saveProfile", displayName.trim()) }
        )
        payload.saveErrorCode?.takeIf { it.isNotBlank() }?.let { code ->
            SettingsAccountWarning("Profile save failed: $code")
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
                    val current = currentPasswordState.value
                    val next = newPasswordState.value
                    val confirm = confirmPasswordState.value
                    when {
                        next.length < 8 -> passwordDraftError.value = "New password must be at least 8 characters"
                        next != confirm -> passwordDraftError.value = "Passwords do not match"
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
    onEvent: (String, String?) -> Unit
) {
    Box(
        modifier =
            Modifier
                .size(56.dp)
                .clickable(enabled = payload.canUploadAvatar && !payload.avatarInProgress) { onEvent("uploadAvatar", null) },
        contentAlignment = Alignment.Center
    ) {
        val avatarWireValue =
            profile.avatarUrl?.takeIf { it.isNotBlank() }
                ?: profile.gravatarHash?.takeIf { it.isNotBlank() }?.toGravatarAvatarUrl()
        SlockAvatar(
            avatarWireValue = avatarWireValue,
            label = profile.avatarLabel,
            avatarSize = 56.dp,
            borderWidth = SlockStroke.Default
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
                style = SlockType.Input.copy(fontSize = 15.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (verified) {
                SettingsStatusBadge(verifiedLabel.uppercase(), SlockColor.AccentLime, SlockLucideIconName.Shield)
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
    currentPassword: String,
    newPassword: String,
    confirmPassword: String,
    draftError: String?,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SlockBrutalTextField(
            label = payload.currentPasswordLabel,
            value = currentPassword,
            placeholder = "",
            onValueChange = onCurrentPasswordChange,
            enabled = !payload.passwordInProgress,
            password = true
        )
        SlockBrutalTextField(
            label = payload.newPasswordLabel,
            value = newPassword,
            placeholder = payload.newPasswordPlaceholder,
            onValueChange = onNewPasswordChange,
            enabled = !payload.passwordInProgress,
            password = true
        )
        SlockBrutalTextField(
            label = payload.confirmPasswordLabel,
            value = confirmPassword,
            placeholder = "",
            onValueChange = onConfirmPasswordChange,
            enabled = !payload.passwordInProgress,
            password = true
        )
        draftError?.let { SettingsAccountWarning(it) }
        payload.passwordErrorCode?.takeIf { it.isNotBlank() }?.let { SettingsAccountWarning("Password update failed: $it") }
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
            SettingsAccountEmptyMessage("No connected accounts", "External login providers will show up here.")
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
                source = providerIconDataUri(account.provider),
                contentDescription = account.provider,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    account.provider.ifBlank { "Account ${index + 1}" },
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(account.identifier, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            SettingsAccountWarning("Provider link failed: ${payload.providerErrorCode ?: payload.failedLabel}")
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
        SettingsAccountSectionTitle("SESSION", SlockLucideIconName.LogOut)
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
                        textStyle = SlockType.Button.copy(color = SlockColor.TextBlack)
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
                    textStyle = SlockType.Button.copy(color = SlockColor.TextBlack)
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

private fun providerIconDataUri(provider: String): String =
    when {
        provider.lowercase().contains("github") -> GitHubIconDataUri
        provider.lowercase().contains("google") -> GoogleIconDataUri
        else -> GoogleIconDataUri
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

internal fun List<KuiklySettingsConnectedAccount>.visibleSettingsConnectedAccounts(): List<KuiklySettingsConnectedAccount> =
    filterNot { it.isPasswordCredentialProvider() || it.isHiddenMobileAccountProvider() }

private fun KuiklySettingsConnectedAccount.isHiddenMobileAccountProvider(): Boolean {
    val providerIdValue = providerId.trim().lowercase()
    val providerValue = provider.trim().lowercase()
    return providerIdValue == "google" ||
        providerIdValue == "github" ||
        providerValue == "google" ||
        providerValue == "github"
}

private fun KuiklySettingsConnectedAccount.isPasswordCredentialProvider(): Boolean {
    val providerIdValue = providerId.trim().lowercase()
    val providerValue = provider.trim().lowercase()
    return providerIdValue == "password" ||
        providerIdValue == "email_password" ||
        providerValue == "email password" ||
        providerValue.contains("password")
}

private fun connectedStatus(status: String, payload: KuiklySettingsAccountPayload): Triple<String, Color, SlockLucideIconName?> =
    when (status) {
        "connected" -> Triple(payload.connectedLabel, SlockColor.AccentCyan, SlockLucideIconName.Link)
        "loading" -> Triple(payload.connectingLabel, SlockColor.BrandYellow, SlockLucideIconName.Link)
        "failed" -> Triple(payload.failedLabel, SlockColor.AccentPink, null)
        else -> Triple(payload.connectLabel, SlockColor.SurfaceWhite, null)
    }
