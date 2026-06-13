package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.components.SlockBrutalActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val logoutDetail: String = "Sign out of this device. You can sign back in anytime.",
    val logoutLabel: String = "Log out",
    val changePasswordTitle: String = "Change password",
    val changePasswordSubtitle: String = "Requires current password before saving a new one",
    val canEditProfile: Boolean = true,
    val saveInProgress: Boolean = false,
    val saveErrorCode: String? = null
)

@Serializable
internal data class KuiklySettingsAccountProfile(
    val displayName: String = "",
    val handle: String = "",
    val email: String = "",
    val avatarLabel: String = "",
    val verified: Boolean = false,
    val roleLabel: String = ""
)

@Serializable
internal data class KuiklySettingsConnectedAccount(
    val provider: String = "",
    val identifier: String = "",
    val status: String = "notConnected"
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
        KuiklySettingsConnectedAccount("Google", "artin@raft.build", "connected"),
        KuiklySettingsConnectedAccount("GitHub", "github.com/artin", "connected"),
        KuiklySettingsConnectedAccount("Email password", "Password last changed 3 days ago", "notConnected")
    )
)

@Composable
private fun SettingsAccountScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = rememberAccountPayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.optString("instanceId")
    )
    LaunchedEffect(payload) {
        bridge.log("settings_account_render instanceId=${payload.instanceId} connected=${payload.connectedAccounts.size}")
    }
    SettingsAccountContent(payload) { action, value ->
        bridge.settingsEvent(payload.instanceId, action, value)
    }
}

@Composable
private fun rememberAccountPayload(payload: String, instanceId: String): KuiklySettingsAccountPayload =
    remember(payload, instanceId) {
        runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(KuiklySettingsAccountPayload.serializer(), payload)
                .let { if (it.instanceId.isBlank()) it.copy(instanceId = instanceId) else it }
        }.getOrElse { settingsAccountSmokePayload.copy(instanceId = instanceId) }
    }

@Composable
internal fun SettingsAccountContent(
    payload: KuiklySettingsAccountPayload,
    applyTopSafeArea: Boolean = true,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onEvent: (String, String?) -> Unit
) {
    val topSafeArea = if (applyTopSafeArea) slockTopSafeAreaPadding() else 0.dp
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
        item { SettingsAccountProfileCard(payload, onEvent) }
        if (payload.connectedAccounts.isNotEmpty()) {
            item { SettingsConnectedAccounts(payload) }
        }
        item { SettingsLogoutAction(payload.logoutDetail, payload.logoutLabel) { onEvent("logout", null) } }
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
        SlockLucideIcon("user", color = "#FF737373", modifier = Modifier.size(14.dp))
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
    onEvent: (String, String?) -> Unit
) {
    val profile = payload.profile
    val displayNameState = remember(profile.displayName) { mutableStateOf(profile.displayName) }
    val displayName = displayNameState.value
    SettingsAccountHardShadowCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier.size(56.dp).background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon("user", modifier = Modifier.size(24.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    profile.displayName.ifBlank { "Account" },
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 15.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                profile.handle.takeIf { it.isNotBlank() }?.let {
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
        SettingsProfileTextField(
            label = "Name",
            value = profile.handle.removePrefix("@"),
            placeholder = payload.handlePlaceholder.removePrefix("@"),
            muted = true
        )
        SettingsProfileTextField(
            label = "Display Name",
            value = displayName,
            placeholder = payload.displayNamePlaceholder,
            muted = !payload.canEditProfile || payload.saveInProgress,
            editable = payload.canEditProfile && !payload.saveInProgress,
            onValueChange = { displayNameState.value = it }
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
            Text(
                "Profile save failed: $code",
                style = SlockType.Caption.copy(color = SlockColor.AccentRed),
                color = SlockColor.AccentRed
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        SettingsPasswordDisclosure(payload.changePasswordTitle) { onEvent("changePassword", null) }
    }
}

@Composable
private fun SettingsProfileTextField(
    label: String,
    value: String,
    placeholder: String,
    muted: Boolean,
    editable: Boolean = false,
    onValueChange: (String) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = SlockType.SectionLabel.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(if (muted) SlockColor.EmptyStateGray else SlockColor.SurfaceWhite)
                    .border(if (muted) SlockStroke.Thin else SlockStroke.Default, if (muted) SlockColor.TextMutedGray else SlockColor.BorderBlack)
                    .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (editable) {
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = value,
                    placeholder = placeholder,
                    autoFocus = false,
                    onValueChange = onValueChange,
                    textStyle = SlockType.Input.copy(fontSize = 15.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                    placeholderColor = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            } else {
                Text(
                    value.ifBlank { placeholder },
                    style = SlockType.Input.copy(fontSize = 15.sp, lineHeight = 18.sp, color = if (muted || value.isBlank()) SlockColor.TextMutedGray else SlockColor.TextBlack),
                    color = if (muted || value.isBlank()) SlockColor.TextMutedGray else SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                SettingsStatusBadge(verifiedLabel.uppercase(), SlockColor.AccentLime, "shield")
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
        shadowOffset = 2.dp,
        textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextBlack)
    )
}

@Composable
private fun SettingsAccountHardShadowCard(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(end = 3.dp, bottom = 3.dp)) {
        Box(Modifier.matchParentSize().offset(x = 3.dp, y = 3.dp).background(SlockColor.ShadowBlack))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsPasswordDisclosure(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockLucideIcon("chevron-right", modifier = Modifier.size(14.dp))
        Text(title, style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsConnectedAccounts(payload: KuiklySettingsAccountPayload) {
    val accounts = payload.connectedAccounts
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsAccountSectionTitle(payload.connectedAccountsTitle.uppercase())
        if (accounts.isEmpty()) {
            SettingsAccountEmptyMessage("No connected accounts", "External login providers will show up here.")
        } else {
            accounts.forEachIndexed { index, account ->
                SettingsConnectedAccountRow(account, index, payload)
            }
        }
    }
}

@Composable
private fun SettingsConnectedAccountRow(
    account: KuiklySettingsConnectedAccount,
    index: Int,
    payload: KuiklySettingsAccountPayload
) {
    val connected = account.status == "connected"
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconBox(providerIcon(account.provider), if (connected) SlockColor.TextBlack else SlockColor.SurfaceWhite, invert = connected)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(account.provider.ifBlank { "Account ${index + 1}" }, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(account.identifier, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val (label, color, icon) = connectedStatus(account.status, payload)
        SettingsStatusBadge(label, color, icon)
    }
}

@Composable
private fun SettingsDisclosureRow(iconName: String, title: String, subtitle: String) {
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
        SlockLucideIcon("chevron-right", modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SettingsLogoutAction(detail: String, label: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(detail, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        SlockBrutalActionButton(
            label = label,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = SlockColor.AccentRed,
            minWidth = 0.dp,
            minHeight = 48.dp,
            shadowOffset = 3.dp,
            textStyle = SlockType.Button.copy(color = SlockColor.TextBlack)
        )
    }
}

@Composable
private fun SettingsMetaRow(iconName: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockLucideIcon(iconName, color = "#FF737373", modifier = Modifier.size(14.dp))
        Text(text, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsAccountSectionTitle(text: String) {
    Text(text, style = SlockType.SectionLabel.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
}

@Composable
private fun SettingsStatusBadge(text: String, color: Color, iconName: String? = null) {
    Row(
        modifier = Modifier.background(color).border(1.dp, SlockColor.BorderBlack).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        iconName?.let { SlockLucideIcon(it, modifier = Modifier.size(12.dp)) }
        Text(text, style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsInlineButton(text: String, color: Color, enabled: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.height(44.dp).background(if (enabled) color else SlockColor.EmptyStateGray).border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = SlockType.Button.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IconBox(iconName: String, color: Color, invert: Boolean = false) {
    Box(
        modifier = Modifier.size(38.dp).background(color).border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(iconName, color = if (invert) "#FFFFFFFF" else "#FF000000", modifier = Modifier.size(18.dp))
    }
}

private fun providerIcon(provider: String): String =
    when {
        provider.lowercase().contains("github") -> "key"
        provider.lowercase().contains("google") -> "mail"
        else -> "key"
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

private fun connectedStatus(status: String, payload: KuiklySettingsAccountPayload): Triple<String, Color, String?> =
    when (status) {
        "connected" -> Triple(payload.connectedLabel, SlockColor.AccentCyan, "link")
        "loading" -> Triple(payload.connectingLabel, SlockColor.BrandYellow, "link")
        "failed" -> Triple(payload.failedLabel, SlockColor.AccentPink, null)
        else -> Triple(payload.connectLabel, SlockColor.SurfaceWhite, null)
    }
