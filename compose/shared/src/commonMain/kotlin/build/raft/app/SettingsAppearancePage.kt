package build.raft.app

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.app.data.RaftAppAppearanceRuntime
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.raftCardSurface
import build.raft.app.data.APPEARANCE_ACTION_MESSAGE_FONT_SIZE
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsAppearanceSnapshot
import build.raft.app.data.StoredAuthSession
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
import androidx.compose.foundation.lazy.LazyListScope
import build.raft.app.components.RaftDesignSystemTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftDisplayPreferenceRuntime
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The preview message's time. Null: the current time, like the Web's preview (`new Date()`); the
 * screenshot host pins it.
 */
internal val LocalSettingsAppearancePreviewTimeLabel = staticCompositionLocalOf<String?> { null }

/**
 * Settings > Appearance, in the Web's order (`AppearanceSection`): the way to this server's theme
 * ("Theme on <server>"), the "Appearance" header over the message font size card (title,
 * description, size, "Saved on this device." and a real message row as the preview), then the
 * device display switches. The Web's "Live agent activity" switch is left out: the app has no
 * live agent activity bar. "Activity sender avatars" is the app's own and comes last.
 */
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
    onRetry: () -> Unit,
    /**
     * Light, Dark or System. Web (botiverse/slock#11355) keeps it on the Theme page; Appearance shows
     * it only where there is no Theme page, so it has one place: [settingsAppearanceShowsModeCard].
     */
    showModeCard: Boolean,
    /**
     * On a fine pointer, the app preference cards ([settingsAppPreferenceItems]) after the
     * appearance ones: the web keeps its device preferences in AppearanceSection, and the app has
     * no "Mobile" page there.
     */
    appPreferenceItems: (LazyListScope.() -> Unit)? = null,
    /** The current server's name for the "Theme on <server>" row (Web `ServerThemeLink`); null hides it. */
    themeServerName: String? = null,
    onOpenTheme: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).raftSafeAreaHorizontalPadding().padding(16.dp),
    ) {
        if (showModeCard) {
            item {
                SettingsAppearanceThemeCard(title = stringResource(Res.string.settings_appearance_theme_title))
                Spacer(Modifier.height(24.dp))
            }
        }
        if (themeServerName != null) {
            item {
                SettingsAppearanceServerThemeLink(serverName = themeServerName, onClick = onOpenTheme)
                Spacer(Modifier.height(24.dp))
            }
        }
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Type,
                        stringResource(Res.string.common_loading),
                        stringResource(Res.string.settings_appearance_loading),
                        null,
                        onRetry,
                        loading = true
                    )
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Type,
                        stringResource(Res.string.settings_state_unavailable),
                        state.reason.localizedUserFacingMessage(),
                        stringResource(Res.string.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.TriangleAlert,
                        stringResource(Res.string.settings_error_appearance),
                        state.error.localizedUserFacingMessage(),
                        stringResource(Res.string.common_retry),
                        onRetry,
                        traceId = state.error.traceId
                    )
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Lock,
                        stringResource(Res.string.settings_state_permission_required),
                        state.blocker.localizedUserFacingMessage(),
                        null,
                        onRetry,
                        traceId = state.blocker.traceId
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
        item {
            Spacer(Modifier.height(16.dp))
            SettingsAppearanceDisplaySwitches(
                activitySenderAvatarsEnabled = activitySenderAvatarsEnabled,
                onActivitySenderAvatarsChange = onActivitySenderAvatarsChange
            )
        }
        appPreferenceItems?.let { preferenceItems ->
            item { Spacer(Modifier.height(12.dp)) }
            preferenceItems()
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

/** Web `ServerThemeLink`: one plain row in an inset group, to Settings > Theme. */
@Composable
private fun SettingsAppearanceServerThemeLink(serverName: String, onClick: () -> Unit) {
    RaftDesignSystemTheme {
        RaftListRowGroup(Modifier.fillMaxWidth()) {
            RaftListRow(
                title = stringResource(Res.string.settings_appearance_server_theme_link_title, serverName),
                variant = RaftListRowVariant.Plain,
                description = stringResource(Res.string.settings_appearance_server_theme_link_description),
                wrapDescription = true,
                onClick = onClick,
                trailing = { RaftListRowChevron() }
            )
        }
    }
}

/**
 * The Web's device switches (`ListRowGroup` of plain rows: title, description, "Saved on this
 * device.", the switch its target), plus the app's Activity sender avatars last.
 */
@Composable
private fun SettingsAppearanceDisplaySwitches(
    activitySenderAvatarsEnabled: Boolean,
    onActivitySenderAvatarsChange: (Boolean) -> Unit
) {
    RaftDesignSystemTheme {
        RaftListRowGroup(Modifier.fillMaxWidth()) {
            SettingsAppearanceSwitchRow(
                title = stringResource(Res.string.settings_appearance_show_agent_model_title),
                description = stringResource(Res.string.settings_appearance_show_agent_model_description),
                checked = RaftDisplayPreferenceRuntime.showAgentModelName,
                onCheckedChange = RaftDisplayPreferenceRuntime::setShowAgentModelName,
                divider = false
            )
            SettingsAppearanceSwitchRow(
                title = stringResource(Res.string.settings_appearance_hide_empty_sidebar_sections_title),
                description = stringResource(Res.string.settings_appearance_hide_empty_sidebar_sections_description),
                checked = RaftDisplayPreferenceRuntime.hideEmptySidebarSections,
                onCheckedChange = RaftDisplayPreferenceRuntime::setHideEmptySidebarSections,
                divider = true
            )
            SettingsAppearanceSwitchRow(
                title = stringResource(Res.string.settings_appearance_activity_sender_avatars),
                description = stringResource(Res.string.settings_appearance_activity_sender_avatars_detail),
                checked = activitySenderAvatarsEnabled,
                onCheckedChange = onActivitySenderAvatarsChange,
                divider = true
            )
        }
    }
}

/** raft-ui ListRow `plain` (px 16, py 12, gap 12), its body with the "Saved on this device." line. */
@Composable
private fun SettingsAppearanceSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    divider: Boolean
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(Modifier.fillMaxWidth()) {
        if (divider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 16.dp).height(RaftAppStroke.Thin).background(colors.lineHairline)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftText(title, style = typography.body.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground)
                RaftText(description, style = typography.callout, color = colors.foregroundMuted)
                RaftText(
                    stringResource(Res.string.settings_appearance_saved_on_device),
                    style = typography.caption,
                    color = colors.foregroundHint
                )
            }
            RaftSwitch(checked = checked, onCheckedChange = onCheckedChange, label = title)
        }
    }
}

/**
 * Whether Appearance shows Light, Dark or System (web AppearanceSection `themePageShown`): only
 * when there is no Theme page, i.e. no current server. Unknown until the session is read, so a
 * page with a server never flashes it.
 */
internal fun settingsAppearanceShowsModeCard(session: StoredAuthSession?): Boolean =
    session != null && session.settingsThemeServer() == null

/**
 * Light, Dark or System, the Web's AppearanceThemePicker, applied at once and saved on this device
 * for every server ([RaftAppAppearanceRuntime]). Settings > Theme's "Light and dark"; Appearance's
 * "Theme" where there is no Theme page.
 */
@Composable
internal fun SettingsAppearanceThemeCard(title: String) {
    val selected = RaftAppAppearanceRuntime.appearance
    SettingsFormSectionCard(iconName = RaftLucideIconName.Eye, title = title) {
        SettingsFormDetailText(stringResource(Res.string.settings_appearance_theme_description))
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
        RaftAppAppearance.Light to Res.string.settings_appearance_mode_light,
        RaftAppAppearance.Dark to Res.string.settings_appearance_mode_dark,
        RaftAppAppearance.System to Res.string.settings_appearance_mode_system,
    )

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
    SettingsFormSection(iconName = RaftLucideIconName.Type, title = stringResource(Res.string.settings_appearance_title)) {
        RaftDesignSystemTheme {
            val colors = RaftTheme.colors
            val typography = RaftTheme.typography
            RaftCard(modifier = Modifier.fillMaxWidth()) {
                // Web `Card className="stack-y-4 p-4"`.
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RaftText(
                            stringResource(Res.string.settings_appearance_message_font_size_title),
                            style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.foreground
                        )
                        RaftText(
                            stringResource(Res.string.settings_appearance_message_font_size_description),
                            style = typography.footnote,
                            color = colors.foregroundMuted
                        )
                    }
                    SettingsSegmentedControlField(
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
                    RaftText(
                        stringResource(Res.string.settings_appearance_saved_on_device),
                        style = typography.caption,
                        color = colors.foregroundHint
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(Res.string.settings_appearance_preview),
                            style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray),
                            color = RaftColor.TextMutedGray
                        )
                        SettingsAppearancePreview(fontSizeKey = selected)
                    }
                }
            }
        }
    }
}

private const val APPEARANCE_PREVIEW_AGENT_ID = "appearance-preview-agent"

/** The Web's preview agent (`APPEARANCE_PREVIEW_AGENT`): Cindy, model "preview". */
private val AppearancePreviewModelLabelResolver =
    RaftMemberModelLabelResolver(
        currentLabel = { "preview" },
        labelFlow = { flowOf("preview") }
    )

/**
 * The Web's preview: a real message row (`MessageItem`) from the agent Cindy in the reader's
 * message size, with a mention, a channel and inline code. It follows "Show agent model" too.
 */
@Composable
private fun SettingsAppearancePreview(fontSizeKey: String) {
    val bodyStyle = raftMessageBodyTextStyle(fontSizeKey)
    val body = stringResource(Res.string.settings_appearance_preview_message)
    val pinnedTime = LocalSettingsAppearancePreviewTimeLabel.current
    val timeLabel = pinnedTime ?: remember { settingsAppearancePreviewClock() }
    val message =
        remember(body, timeLabel) {
            RaftThreadMessageItem(
                id = "appearance-preview-message",
                authorId = APPEARANCE_PREVIEW_AGENT_ID,
                authorName = "Cindy",
                authorHandle = "cindy-preview",
                authorKind = "agent",
                avatarLabel = "Cindy",
                avatarWireValue = "pixel:random:cindy-preview",
                timestampLabel = timeLabel,
                body = body
            )
        }
    val knownChannels = remember { setOf("proj-uiux") }
    val knownMentions = remember { setOf("joy", "Joy") }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(RaftAppRadius.Control)).background(RaftColor.Canvas)) {
        ThreadMessageCard(
            message = message,
            messageBodyTextStyle = bodyStyle,
            showReplyAction = false,
            showSaveAction = false,
            taskOpenEnabled = false,
            taskStatusActionsEnabled = false,
            isMarkdownTagValid = { tag -> tag.isKnownThreadMarkdownTag(knownChannels, knownMentions, emptySet()) },
            markdownTagContextSignature = "appearance-preview",
            onShowActions = {},
            onOpenMember = {},
            onOpenThread = {},
            onOpenTask = {},
            onOpenTaskStatusMenu = {},
            onToggleReaction = {},
            onOpenAttachment = {},
            onOpenMarkdownTag = { _, _ -> },
            onOpenMarkdownUrl = {},
            memberModelLabelResolver = AppearancePreviewModelLabelResolver,
            outerPadding = 0.dp
        )
    }
}

/** `HH:mm` now, in this device's time zone. */
private fun settingsAppearancePreviewClock(): String {
    val local = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}
