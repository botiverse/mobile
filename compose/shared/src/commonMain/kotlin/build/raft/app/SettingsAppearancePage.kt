package build.raft.app

import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.app.data.RaftAppAppearanceRuntime
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.raftCardSurface
import build.raft.app.data.APPEARANCE_ACTION_MESSAGE_FONT_SIZE
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsAppearanceSnapshot
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import build.raft.app.components.RaftDesignSystemTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.flow.map

@Composable
internal fun SettingsAppearanceContent(
    state: ScreenLoadState<SettingsAppearanceSnapshot>,
    actionState: SharedSettingsActionState?,
    fontSizeDraft: String,
    activitySenderAvatarsEnabled: Boolean,
    bottomContentPadding: Dp,
    onFontSizeDraftChange: (String) -> Unit,
    onActivitySenderAvatarsChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsAppearanceThemeCard() }
        item {
            SettingsActivitySenderAvatarsCard(
                enabled = activitySenderAvatarsEnabled,
                onEnabledChange = onActivitySenderAvatarsChange
            )
        }
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.LoaderCircle,
                        stringResource(MR.strings.common_loading),
                        stringResource(MR.strings.settings_appearance_loading),
                        null,
                        onRetry
                    )
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Type,
                        stringResource(MR.strings.settings_state_unavailable),
                        state.reason.localizedUserFacingMessage(),
                        stringResource(MR.strings.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.TriangleAlert,
                        stringResource(MR.strings.settings_error_appearance),
                        state.error.localizedUserFacingMessage(),
                        stringResource(MR.strings.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Lock,
                        stringResource(MR.strings.settings_state_permission_required),
                        state.blocker.localizedUserFacingMessage(),
                        null,
                        onRetry
                    )
                }
            is ScreenLoadState.Content ->
                item {
                    SettingsAppearanceFontSizeCard(
                        snapshot = state.data,
                        fontSizeDraft = fontSizeDraft,
                        actionState = actionState,
                        onFontSizeDraftChange = onFontSizeDraftChange,
                        onSubmit = onSave
                    )
                }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

/**
 * Settings > Appearance > Theme, the Web's AppearanceThemePicker: Light, Dark or System,
 * applied at once and saved on this device ([RaftAppAppearanceRuntime]).
 */
@Composable
private fun SettingsAppearanceThemeCard() {
    val selected = RaftAppAppearanceRuntime.appearance
    SettingsFormSectionCard(iconName = RaftLucideIconName.Eye, title = stringResource(MR.strings.settings_appearance_theme_title)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_appearance_theme_description))
        RaftDesignSystemTheme {
            RaftRadioGroup {
                RaftAppAppearanceOptions.forEach { (appearance, label) ->
                    RaftRadioRow(
                        label = stringResource(label),
                        selected = selected == appearance,
                        onClick = { RaftAppAppearanceRuntime.select(appearance) }
                    )
                }
            }
        }
    }
}

/** The Web's order (`APP_APPEARANCE_MODES`): light, dark, system. */
private val RaftAppAppearanceOptions =
    listOf(
        RaftAppAppearance.Light to MR.strings.settings_appearance_mode_light,
        RaftAppAppearance.Dark to MR.strings.settings_appearance_mode_dark,
        RaftAppAppearance.System to MR.strings.settings_appearance_mode_system,
    )

@Composable
private fun SettingsActivitySenderAvatarsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.CircleUser,
        title = stringResource(MR.strings.settings_appearance_activity_sender_avatars)
    ) {
        SettingsNotificationToggleRow(
            title =
                stringResource(
                    if (enabled) {
                        MR.strings.settings_appearance_activity_sender_avatars_enabled
                    } else {
                        MR.strings.settings_appearance_activity_sender_avatars_disabled
                    }
                ),
            detail = stringResource(MR.strings.settings_appearance_activity_sender_avatars_detail),
            checked = enabled,
            enabled = true,
            onCheckedChange = onEnabledChange
        )
    }
}

@Composable
private fun SettingsAppearanceFontSizeCard(
    snapshot: SettingsAppearanceSnapshot,
    fontSizeDraft: String,
    actionState: SharedSettingsActionState?,
    onFontSizeDraftChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val busy = actionState?.action == APPEARANCE_ACTION_MESSAGE_FONT_SIZE && actionState.inProgress
    val selected = fontSizeDraft.ifBlank { snapshot.messageBodyFontSize.selectedKey }
    val dirty = selected != snapshot.messageBodyFontSize.selectedKey
    // Auto-save immediately on font selection change.
    LaunchedEffect(fontSizeDraft) {
        if (!dirty) return@LaunchedEffect
        onSubmit()
    }
    SettingsFormSectionCard(iconName = RaftLucideIconName.Type, title = stringResource(MR.strings.settings_appearance_title)) {
        SettingsSegmentedControlField(
            detail = snapshot.messageBodyFontSize.detail,
            selectedValue = selected,
            options =
                snapshot.messageBodyFontSize.options.map {
                    SegmentedControlOption(it.key, it.label)
                },
            onValueSelected = onFontSizeDraftChange,
            enabled = snapshot.messageBodyFontSize.available && !busy
        )
        if (actionState?.action == APPEARANCE_ACTION_MESSAGE_FONT_SIZE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
        SettingsFormDivider()
        SettingsAppearancePreview(fontSizeKey = selected)
    }
}

@Composable
private fun SettingsAppearancePreview(fontSizeKey: String) {
    val bodyStyle = raftMessageBodyTextStyle(fontSizeKey)
    Column(
        modifier = Modifier.fillMaxWidth().raftCardSurface(fill = RaftColor.FillMuted, radius = RaftAppRadius.Control).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(MR.strings.settings_appearance_preview),
            style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            RaftAppAvatar(avatarWireValue = "letter:AI", label = "AI", avatarSize = 28.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(MR.strings.settings_appearance_preview_sender), style = RaftType.ButtonBold.copy(color = RaftColor.TextBlack), color = RaftColor.TextBlack)
                Text(
                    stringResource(MR.strings.settings_appearance_preview_message),
                    style = bodyStyle,
                    color = RaftColor.TextBlack
                )
            }
        }
    }
}
