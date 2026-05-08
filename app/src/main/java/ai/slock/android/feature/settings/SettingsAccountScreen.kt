package ai.slock.android.feature.settings

import ai.slock.android.R
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

data class SettingsAccountProfile(
        val displayName: String,
        val handle: String,
        val email: String,
        val avatarLabel: String,
        val verified: Boolean,
        val roleLabel: String
)

data class SettingsConnectedAccount(
        val provider: String,
        val identifier: String,
        val status: SettingsConnectedAccountStatus
)

enum class SettingsConnectedAccountStatus {
    Connected,
    NotConnected,
    Loading,
    Failed
}

@Composable
fun SettingsAccountScreen(
        profile: SettingsAccountProfile,
        connectedAccounts: List<SettingsConnectedAccount>,
        modifier: Modifier = Modifier,
        showOuterBorder: Boolean = true,
        onLogout: () -> Unit = {}
) {
    Column(
            modifier =
                    modifier.fillMaxSize()
                            .background(SlockColor.SurfaceWhite)
                            .imePadding()
                            .then(
                                    if (showOuterBorder) {
                                        Modifier.border(SlockStroke.Default, SlockColor.BorderBlack)
                                    } else {
                                        Modifier
                                    }
                            )
                            .verticalScroll(rememberScrollState())
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SettingsAccountHeader(profile = profile)
        SettingsProfileFields(profile = profile)
        SettingsConnectedAccounts(accounts = connectedAccounts)
        SettingsDisclosureRow(
                icon = SlockIcons.Key,
                title = stringResource(R.string.settings_account_change_password),
                subtitle = stringResource(R.string.settings_account_change_password_subtitle)
        )
        SettingsLogoutAction(onLogout = onLogout)
    }
}

@Composable
private fun SettingsLogoutAction(onLogout: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shadow = if (pressed) SlockShadow.None else SlockShadow.Small
    val background = if (pressed) SlockColor.AccentOrange else SlockColor.AccentRed

    Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
                text = stringResource(R.string.settings_account_logout_detail),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
        )
        Box(
                modifier =
                        Modifier.fillMaxWidth()
                                .padding(
                                        end = SlockShadow.Small.offsetX,
                                        bottom = SlockShadow.Small.offsetY
                                )
                                .slockHardShadow(shadow)
                                .background(background, RoundedCornerShape(SlockRadius.Xs))
                                .border(
                                        SlockStroke.Default,
                                        SlockColor.BorderBlack,
                                        RoundedCornerShape(SlockRadius.Xs)
                                )
                                .clickable(
                                        interactionSource = interactionSource,
                                        indication = null,
                                        role = Role.Button,
                                        onClick = onLogout
                                )
                                .heightIn(min = 48.dp)
                                .padding(horizontal = SlockSpacing.Md, vertical = 12.dp),
                contentAlignment = Alignment.Center
        ) {
            Text(
                    text = stringResource(R.string.settings_account_logout),
                    color = SlockColor.TextBlack,
                    style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SettingsAccountHeader(profile: SettingsAccountProfile, modifier: Modifier = Modifier) {
    Row(
            modifier =
                    modifier.fillMaxWidth()
                            .padding(
                                    end = SlockShadow.Small.offsetX,
                                    bottom = SlockShadow.Small.offsetY
                            )
                            .slockHardShadow(SlockShadow.Small)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SettingsAvatar(label = profile.avatarLabel)
        Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Text(
                        text = profile.displayName,
                        style = SlockType.PageTitle,
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                )
                if (profile.verified) {
                    SettingsStatusBadge(
                            text = stringResource(R.string.settings_account_verified),
                            color = SlockColor.AccentLime,
                            icon = SlockIcons.ShieldCheck
                    )
                }
            }
            Text(
                    text = profile.handle,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            SettingsMetaRow(icon = SlockIcons.Mail, text = profile.email)
            SettingsStatusBadge(text = profile.roleLabel, color = SlockColor.AppBgLightBlue)
        }
    }
}

@Composable
private fun SettingsAvatar(label: String) {
    Box(
            modifier =
                    Modifier.size(58.dp)
                            .background(SlockColor.AccentPink)
                            .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
    ) {
        Text(
                text = label.take(2).uppercase(),
                style = SlockType.PageTitle,
                color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun SettingsMetaRow(icon: ImageVector, text: String) {
    Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SlockColor.TextMutedGray,
                modifier = Modifier.size(14.dp)
        )
        Text(
                text = text,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SettingsStatusBadge(
        text: String,
        color: Color,
        modifier: Modifier = Modifier,
        icon: ImageVector? = null
) {
    Row(
            modifier =
                    modifier.background(color)
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .padding(horizontal = SlockSpacing.Sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        if (icon != null) {
            Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(12.dp)
            )
        }
        Text(
                text = text,
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SettingsProfileFields(
        profile: SettingsAccountProfile,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        saving: Boolean = false,
        focusedField: String? = null,
        errorField: String? = null
) {
    val fieldEnabled = enabled && !saving

    Column(
            modifier =
                    modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SettingsSectionLabel(stringResource(R.string.settings_account_profile_section))
        SlockTextField(
                value = profile.displayName,
                onValueChange = {},
                label = stringResource(R.string.settings_account_display_name_label),
                placeholder = stringResource(R.string.settings_account_display_name_placeholder),
                enabled = fieldEnabled,
                forceFocused = focusedField == "displayName",
                isError = errorField == "displayName",
                errorText =
                        if (errorField == "displayName") {
                            stringResource(R.string.settings_account_display_name_required)
                        } else {
                            null
                        }
        )
        SlockTextField(
                value = profile.handle,
                onValueChange = {},
                label = stringResource(R.string.settings_account_handle_label),
                placeholder = "@handle",
                enabled = fieldEnabled,
                forceFocused = focusedField == "handle",
                isError = errorField == "handle",
                errorText =
                        if (errorField == "handle") {
                            stringResource(R.string.settings_account_handle_taken)
                        } else {
                            null
                        }
        )
        SlockTextField(
                value = profile.email,
                onValueChange = {},
                label = stringResource(R.string.settings_account_email_label),
                placeholder = "you@example.com",
                enabled = fieldEnabled,
                forceFocused = focusedField == "email",
                isError = errorField == "email",
                errorText =
                        if (errorField == "email") {
                            stringResource(R.string.settings_account_email_invalid)
                        } else {
                            null
                        },
                trailingContent = {
                    SettingsStatusBadge(
                            stringResource(R.string.settings_account_verified),
                            SlockColor.AccentLime,
                            icon = SlockIcons.Check
                    )
                }
        )
        Row(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
        ) {
            SlockButton(
                    text =
                            if (saving) {
                                stringResource(R.string.settings_account_saving)
                            } else {
                                stringResource(R.string.settings_account_save_changes)
                            },
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    variant = SlockButtonVariant.Pink,
                    enabled = enabled,
                    loading = saving
            )
            SlockButton(
                    text = stringResource(R.string.settings_account_cancel),
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    variant = SlockButtonVariant.Outline,
                    enabled = fieldEnabled
            )
        }
    }
}

@Composable
fun SettingsConnectedAccounts(
        accounts: List<SettingsConnectedAccount>,
        modifier: Modifier = Modifier
) {
    Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SettingsSectionLabel(stringResource(R.string.settings_account_connected_accounts))
        accounts.forEach { account -> SettingsConnectedAccountRow(account = account) }
    }
}

@Composable
fun SettingsConnectedAccountRow(account: SettingsConnectedAccount, modifier: Modifier = Modifier) {
    val connected = account.status == SettingsConnectedAccountStatus.Connected

    Row(
            modifier =
                    modifier.fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Box(
                modifier =
                        Modifier.size(38.dp)
                                .background(
                                        if (connected) SlockColor.TextBlack
                                        else SlockColor.SurfaceWhite
                                )
                                .border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
        ) { SettingsConnectedAccountIcon(provider = account.provider, connected = connected) }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                    text = account.provider,
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            Text(
                    text = account.identifier,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
        SettingsConnectedStatusBadge(status = account.status)
    }
}

@Composable
private fun SettingsConnectedAccountIcon(provider: String, connected: Boolean) {
    val providerKey = provider.lowercase()
    when {
        providerKey.contains("google") ->
                Image(
                        painter = painterResource(R.drawable.ic_google),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                )
        providerKey.contains("github") ->
                Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = null,
                        tint = if (connected) SlockColor.SurfaceWhite else SlockColor.TextBlack,
                        modifier = Modifier.size(20.dp)
                )
        else ->
                Icon(
                        imageVector = SlockIcons.Key,
                        contentDescription = null,
                        tint = if (connected) SlockColor.SurfaceWhite else SlockColor.TextBlack,
                        modifier = Modifier.size(18.dp)
                )
    }
}

@Composable
private fun SettingsConnectedStatusBadge(status: SettingsConnectedAccountStatus) {
    val (text, color, icon) =
            when (status) {
                SettingsConnectedAccountStatus.Connected ->
                        Triple(
                                stringResource(R.string.settings_account_connected),
                                SlockColor.AccentCyan,
                                SlockIcons.Link
                        )
                SettingsConnectedAccountStatus.NotConnected ->
                        Triple(
                                stringResource(R.string.settings_account_connect),
                                SlockColor.SurfaceWhite,
                                null
                        )
                SettingsConnectedAccountStatus.Loading ->
                        Triple(
                                stringResource(R.string.settings_account_connecting),
                                SlockColor.BrandYellow,
                                SlockIcons.Link
                        )
                SettingsConnectedAccountStatus.Failed ->
                        Triple(
                                stringResource(R.string.settings_account_failed),
                                SlockColor.AccentPink,
                                null
                        )
            }

    SettingsStatusBadge(text = text, color = color, icon = icon)
}

@Composable
fun SettingsDisclosureRow(
        icon: ImageVector,
        title: String,
        subtitle: String,
        modifier: Modifier = Modifier
) {
    Row(
            modifier =
                    modifier.fillMaxWidth()
                            .heightIn(min = 62.dp)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Box(
                modifier =
                        Modifier.size(38.dp)
                                .background(SlockColor.SurfaceWhite)
                                .border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
        ) {
            Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                    text = title,
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            Text(
                    text = subtitle,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
                imageVector = SlockIcons.ChevronRight,
                contentDescription = null,
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(text = text.uppercase(), style = SlockType.SectionLabel, color = SlockColor.TextBlack)
}

private val accountProfileSample =
        SettingsAccountProfile(
                displayName = "Artin",
                handle = "@artin",
                email = "artin@slock.ai",
                avatarLabel = "AR",
                verified = true,
                roleLabel = "Workspace owner"
        )

private val connectedAccountSamples =
        listOf(
                SettingsConnectedAccount(
                        provider = "Google",
                        identifier = "artin@slock.ai",
                        status = SettingsConnectedAccountStatus.Connected
                ),
                SettingsConnectedAccount(
                        provider = "GitHub",
                        identifier = "github.com/artin",
                        status = SettingsConnectedAccountStatus.Connected
                ),
                SettingsConnectedAccount(
                        provider = "Email password",
                        identifier = "Password last changed 3 days ago",
                        status = SettingsConnectedAccountStatus.NotConnected
                )
        )

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SettingsAccountPreview() {
    SlockTheme {
        SettingsAccountScreen(
                profile = accountProfileSample,
                connectedAccounts = connectedAccountSamples
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsAccountStatesPreview() {
    SlockTheme {
        Column(
                modifier = Modifier.background(SlockColor.AppBgLightBlue).padding(SlockSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            SettingsAccountHeader(
                    profile =
                            accountProfileSample.copy(
                                    displayName = "Very Long Display Name With 中文 Mixed Text",
                                    handle = "@very-long-profile-handle-for-preview"
                            )
            )
            SettingsConnectedAccountRow(
                    account =
                            connectedAccountSamples[2].copy(
                                    provider = "Unconnected password provider with long label",
                                    identifier =
                                            "Use this row to verify long text does not crush the status badge",
                                    status = SettingsConnectedAccountStatus.Failed
                            )
            )
            SettingsProfileFields(profile = accountProfileSample, focusedField = "displayName")
            SettingsProfileFields(
                    profile = accountProfileSample.copy(displayName = ""),
                    errorField = "displayName"
            )
            SettingsProfileFields(profile = accountProfileSample, saving = true)
            SettingsProfileFields(profile = accountProfileSample, enabled = false)
            SettingsConnectedAccounts(
                    accounts =
                            listOf(
                                    connectedAccountSamples[0],
                                    connectedAccountSamples[1].copy(
                                            status = SettingsConnectedAccountStatus.Loading
                                    ),
                                    connectedAccountSamples[2],
                                    connectedAccountSamples[2].copy(
                                            provider = "Google backup account",
                                            identifier = "Connection failed. Try again.",
                                            status = SettingsConnectedAccountStatus.Failed
                                    )
                            )
            )
            SettingsDisclosureRow(
                    icon = SlockIcons.Key,
                    title = "Change password",
                    subtitle = "Requires current password before saving a new one"
            )
        }
    }
}
