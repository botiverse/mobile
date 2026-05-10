package ai.slock.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.slock.android.R
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.system.DeclareStatusBarColor
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

private val SettingsDetailHeaderButtonSize = 38.dp
private val SettingsDetailHeaderIconSize = 18.dp

data class SettingsServerProfileState(
    val name: String,
    val slug: String,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val error: Boolean = false
)

data class SettingsServerAdminCandidate(
    val id: String,
    val name: String,
    val handle: String
)

data class SettingsServerAdminState(
    val candidates: List<SettingsServerAdminCandidate>,
    val selectedCandidateId: String? = null,
    val admins: List<SettingsServerAdminCandidate> = emptyList(),
    val adding: Boolean = false
)

@Composable
fun SettingsServerDetailScreen(
    profile: SettingsServerProfileState,
    admins: SettingsServerAdminState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onSaveProfile: () -> Unit = {},
    onAddAdmin: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_server_title),
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            SettingsSectionHeader(
                iconBackground = SlockColor.EmptyStateGray,
                label = stringResource(R.string.settings_server_profile_section)
            )
            ServerProfileCard(
                profile = profile,
                onSaveProfile = onSaveProfile
            )
            SettingsSectionHeader(
                iconBackground = SlockColor.EmptyStateGray,
                label = stringResource(R.string.settings_server_admins_section, admins.admins.size)
            )
            ServerAdminsCard(
                state = admins,
                onAddAdmin = onAddAdmin
            )
        }
    }
}

@Composable
fun SettingsDetailHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val backInteractionSource = remember { MutableInteractionSource() }
    val backPressed by backInteractionSource.collectIsPressedAsState()
    val backShadow = if (backPressed) SlockShadow.None else SlockShadow.Small
    DeclareStatusBarColor(SlockColor.SurfaceWhite)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .drawBehind {
                val stroke = SlockStroke.Default.toPx()
                drawLine(
                    color = SlockColor.BorderBlack,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke
                )
            }
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Box(
            modifier = Modifier
                .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
                .slockHardShadow(backShadow)
                .size(SettingsDetailHeaderButtonSize)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(
                    interactionSource = backInteractionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onBack
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = SlockIcons.ArrowLeft,
                contentDescription = null,
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(SettingsDetailHeaderIconSize)
            )
        }
        Text(
            text = title,
            style = SlockType.PageTitle,
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun SettingsSectionHeader(
    label: String,
    iconBackground: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(iconBackground)
                .border(SlockStroke.Thin, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = SlockIcons.Settings,
                contentDescription = null,
                tint = SlockColor.TextMutedGray,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = label,
            style = SlockType.SectionLabel,
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ServerProfileCard(
    profile: SettingsServerProfileState,
    modifier: Modifier = Modifier,
    onSaveProfile: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockTextField(
            value = profile.name,
            onValueChange = {},
            label = stringResource(R.string.settings_server_name_label),
            placeholder = stringResource(R.string.settings_server_name_placeholder),
            enabled = !profile.saving,
            isError = profile.error,
            errorText = if (profile.error) stringResource(R.string.settings_server_name_required) else null,
            forceFocused = profile.dirty
        )
        SlockTextField(
            value = profile.slug,
            onValueChange = {},
            label = stringResource(R.string.settings_server_slug_label),
            placeholder = stringResource(R.string.settings_server_slug_placeholder),
            enabled = false,
            reserveErrorSpace = false
        )
        Text(
            text = stringResource(R.string.settings_server_slug_locked),
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
        SlockButton(
            text = if (profile.saving) {
                stringResource(R.string.settings_server_saving_profile)
            } else {
                stringResource(R.string.settings_server_save_profile)
            },
            onClick = onSaveProfile,
            modifier = Modifier.fillMaxWidth(),
            variant = SlockButtonVariant.Pink,
            enabled = profile.dirty && !profile.saving,
            loading = profile.saving
        )
    }
}

@Composable
fun ServerAdminsCard(
    state: SettingsServerAdminState,
    modifier: Modifier = Modifier,
    onAddAdmin: () -> Unit = {}
) {
    val selectedCandidate = state.candidates.firstOrNull { it.id == state.selectedCandidateId }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = stringResource(R.string.settings_server_add_admin_label),
            style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack
        )
        ServerAdminSelectRow(candidate = selectedCandidate)
        SlockButton(
            text = if (state.adding) {
                stringResource(R.string.settings_server_add_admin)
            } else {
                stringResource(R.string.settings_server_add_admin)
            },
            onClick = onAddAdmin,
            modifier = Modifier.fillMaxWidth(),
            variant = SlockButtonVariant.Yellow,
            enabled = selectedCandidate != null && !state.adding,
            loading = state.adding
        )
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Default)
                .background(SlockColor.BorderBlack)
        )
        if (state.admins.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_server_no_admins),
                style = SlockType.Body,
                color = SlockColor.TextMutedGray
            )
        } else {
            state.admins.forEach { admin ->
                ServerAdminRow(admin)
            }
        }
    }
}

@Composable
private fun ServerAdminSelectRow(candidate: SettingsServerAdminCandidate?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate?.name ?: stringResource(R.string.settings_server_add_admin_placeholder),
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = if (candidate == null) SlockColor.TextMutedGray else SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (candidate != null) {
                Text(
                    text = candidate.handle,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = SlockIcons.ChevronDown,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ServerAdminRow(admin: SettingsServerAdminCandidate) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(SlockColor.EmptyStateGray)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(SlockColor.AccentCyan)
                .border(SlockStroke.Thin, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = admin.name.take(1).uppercase(),
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = admin.name,
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = admin.handle,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val serverProfileSample = SettingsServerProfileState(
    name = "Slock Android",
    slug = "slock-android",
    dirty = true
)

private val adminCandidatesSample = listOf(
    SettingsServerAdminCandidate("1", "Artin", "@artin"),
    SettingsServerAdminCandidate("2", "Operations Lead With 中文 Name", "@operations")
)

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun SettingsServerDetailPreview() {
    SlockTheme {
        SettingsServerDetailScreen(
            profile = serverProfileSample,
            admins = SettingsServerAdminState(
                candidates = adminCandidatesSample,
                selectedCandidateId = "1"
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 1200)
@Composable
private fun SettingsServerDetailStatesPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            ServerProfileCard(
                profile = serverProfileSample.copy(saving = true)
            )
            ServerProfileCard(
                profile = serverProfileSample.copy(name = "", error = true)
            )
            ServerAdminsCard(
                state = SettingsServerAdminState(
                    candidates = adminCandidatesSample,
                    selectedCandidateId = "2",
                    admins = adminCandidatesSample
                )
            )
            ServerAdminsCard(
                state = SettingsServerAdminState(
                    candidates = adminCandidatesSample
                )
            )
            ServerProfileCard(
                profile = SettingsServerProfileState(
                    name = "Very Long Server Name With 中文 Characters And Lots Of Team Context",
                    slug = "very-long-server-slug-with-many-segments",
                    dirty = true
                )
            )
        }
    }
}
