package build.raft.app.thread

import build.raft.app.resources.*
import build.raft.app.withRaftFontWeight
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.components.RaftFormSection
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSwitch
import build.raft.app.data.map
import build.raft.app.get
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.ChannelSectionMove
import build.raft.app.ChannelSectionPlacement
import build.raft.app.ChannelSectionSelectRow
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppStroke
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftColor
import build.raft.app.RaftDialogFooter
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftModalActionButton
import build.raft.app.RaftNotificationLevelSelect
import build.raft.app.RaftType
import build.raft.app.RaftUnifiedDialog
import build.raft.app.ThreadChannelSettingsPanel
import build.raft.app.available
import kotlinx.coroutines.flow.map

// Legacy pure-black icon strings on themed surfaces: unchanged in light, foreground ink in dark.
private val ThreadLegacyBlackIconArgb: String
    get() = if (RaftAppThemeState.isDark) RaftColor.TextBlackArgb else "#FF000000"

private enum class ThreadSettingsDangerAction(
    val id: String,
    val title: String,
    val icon: RaftLucideIconName,
    val buttonLabel: String,
    val color: Color
) {
    Visibility("visibility", "MAKE CHANNEL PRIVATE", RaftLucideIconName.Lock, "Make Private", RaftColor.AccentOrange),
    Archive("archive", "ARCHIVE CHANNEL", RaftLucideIconName.Archive, "Archive Channel", RaftColor.AccentOrange),
    Delete("delete", "DELETE CHANNEL", RaftLucideIconName.Trash2, "Delete Channel", RaftColor.AccentRed)
}

internal enum class ThreadSettingsDangerConfirmationMode {
    Explanation,
    SingleConfirm,
    Unsupported
}

internal fun threadSettingsDangerConfirmationMode(actionId: String): ThreadSettingsDangerConfirmationMode =
    when (actionId) {
        "visibility" -> ThreadSettingsDangerConfirmationMode.Explanation
        "archive",
        "delete" -> ThreadSettingsDangerConfirmationMode.SingleConfirm
        else -> ThreadSettingsDangerConfirmationMode.Unsupported
    }

internal fun defaultThreadSettingsChannelActionCapabilities(): List<RaftThreadSettingsAction> =
    listOf(
        RaftThreadSettingsAction(id = "visibility"),
        RaftThreadSettingsAction(id = "archive"),
        RaftThreadSettingsAction(id = "delete")
    )

private fun defaultThreadSettingsChannelActions(): List<ThreadSettingsActionUi> =
    listOf(
        ThreadSettingsActionUi(
            id = "visibility",
            title = "Make Private",
            subtitle = "Restrict this channel to selected members",
            icon = RaftLucideIconName.Lock,
            destructive = true
        ),
        ThreadSettingsActionUi(
            id = "archive",
            title = "Archive Channel",
            subtitle = "Freeze writes and hide from sidebars",
            icon = RaftLucideIconName.Archive,
            destructive = true
        ),
        ThreadSettingsActionUi(
            id = "delete",
            title = "Delete Channel",
            subtitle = "Permanently remove this channel",
            icon = RaftLucideIconName.Trash2,
            destructive = true
        )
    )

internal fun List<RaftThreadSettingsAction>.normalizedThreadSettingsChannelActions(
    useDefaultWhenEmpty: Boolean = true
): List<ThreadSettingsActionUi> {
    val defaults = defaultThreadSettingsChannelActions()
    val defaultById = defaults.associateBy { it.id }
    val canonicalActions =
        mapNotNull { action ->
            action.canonicalThreadSettingsActionId()?.let { id -> id to action.enabled }
        }
    if (canonicalActions.isEmpty()) {
        return if (useDefaultWhenEmpty) defaults else emptyList()
    }
    return canonicalActions
        .filter { (_, enabled) -> enabled }
        .distinctBy { (id, _) -> id }
        .map { (id, _) -> defaultById.getValue(id) }
}

@Composable
private fun ThreadSettingsActionUi.localizedThreadSettingsAction(): ThreadSettingsActionUi =
    when (id) {
        "visibility" ->
            copy(
                title = stringResource(Res.string.thread_settings_make_private),
                subtitle = stringResource(Res.string.thread_settings_make_private_detail)
            )
        "archive" ->
            copy(
                title = stringResource(Res.string.thread_settings_archive),
                subtitle = stringResource(Res.string.thread_settings_archive_detail)
            )
        "delete" ->
            copy(
                title = stringResource(Res.string.thread_settings_delete),
                subtitle = stringResource(Res.string.thread_settings_delete_detail)
            )
        else -> this
    }

@Composable
private fun ThreadSettingsDangerAction.localizedTitle(): String =
    when (this) {
        ThreadSettingsDangerAction.Visibility -> stringResource(Res.string.thread_settings_make_private_title)
        ThreadSettingsDangerAction.Archive -> stringResource(Res.string.thread_settings_archive_title)
        ThreadSettingsDangerAction.Delete -> stringResource(Res.string.thread_settings_delete_title)
    }

@Composable
private fun ThreadSettingsDangerAction.localizedButtonLabel(): String =
    when (this) {
        ThreadSettingsDangerAction.Visibility -> stringResource(Res.string.thread_settings_make_private)
        ThreadSettingsDangerAction.Archive -> stringResource(Res.string.thread_settings_archive_confirm)
        ThreadSettingsDangerAction.Delete -> stringResource(Res.string.thread_settings_delete)
    }

private fun RaftThreadSettingsAction.canonicalThreadSettingsActionId(): String? =
    when (id) {
        "visibility",
        "make-private",
        "make_public" -> "visibility"
        "archive" -> "archive"
        "delete" -> "delete"
        else -> null
    }

/**
 * The DM settings: the channel settings drawer in its DM mode (web ChannelOverflowMenu for a DM,
 * task #703 / #proj-chat:#47: the same sheet as a channel). The head is the person's or agent's
 * name with an outline "Direct message" tag at every width (web ChannelDrawerTitle); the body has
 * Pinned content ([announcement], DMs only, never Agent Lounge) right above the shared Preferences:
 * Move to section, Mute where the server lets this DM be muted (a DM with an App bot) or a mute is
 * stored, then Collapse long messages, worded for a conversation. No members, no Info form, no
 * actions; Close chat stays in the Home row menu as on the web sidebar.
 */
@Composable
internal fun ThreadDirectMessageSettingsDrawer(
    name: String,
    activityMuteState: ThreadActivityMuteUiState?,
    section: ChannelSectionPlacement?,
    sectionEnabled: Boolean,
    longMessageCollapseSetting: RaftLongMessageCollapseConversationSetting? = null,
    onDismiss: () -> Unit,
    onToggleActivityMute: (() -> Unit)?,
    onMoveSection: (List<ChannelSectionMove>) -> Unit,
    onCreateSection: (name: String, emoji: String?) -> Unit,
    // A phone: a pushed page with Back in place of the drawer's × (ThreadSettingsPage.kt).
    asPage: Boolean = false,
    /** False for an agent's Lounge, which uses this DM-mode drawer but is not a DM. */
    directMessageTag: Boolean = true,
    /** DM pinned content (channel_announcement_v0); omitted for Agent Lounge. */
    announcement: (@Composable () -> Unit)? = null,
) {
    ThreadSettingsSurface(
        asPage = asPage,
        title = name,
        titleTrailing = if (directMessageTag) threadSettingsDirectMessageTag() else null,
        closeLabel = stringResource(Res.string.thread_dm_settings_close),
        onDismiss = onDismiss
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                // web `px-4 pb-2` under the sheet body's `py-2`, on the channel drawer's margin.
                .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 16.dp),
            // web `stack-y-6`: 24dp between Pinned content and Your preferences when both draw.
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (directMessageTag) {
                announcement?.invoke()
            }
            ThreadSettingsPreferencesSection(
                panel =
                    ThreadChannelSettingsPanel(
                        section = section,
                        longMessageCollapse = longMessageCollapseSetting,
                        writeActionsEnabled = sectionEnabled,
                        onMoveSection = onMoveSection,
                        onCreateSection = onCreateSection,
                    ),
                activityMuteState = activityMuteState,
                onToggleActivityMute = onToggleActivityMute,
                separator = false,
                directMessage = true,
            )
        }
    }
}

/**
 * web ChannelPreferencesSection (`isPanel`): Move to section; Notifications, the channel's level
 * and its mute as one select (a Mute switch where the server has no levels); Collapse long
 * messages. Rows divided by hairlines, no borders.
 */
@Composable
private fun ThreadSettingsPreferencesSection(
    panel: ThreadChannelSettingsPanel,
    activityMuteState: ThreadActivityMuteUiState?,
    onToggleActivityMute: (() -> Unit)?,
    separator: Boolean,
    // A DM's rows speak of "this conversation" (web `conversationKind="dm"`).
    directMessage: Boolean = false,
) {
    RaftFormSection(title = stringResource(Res.string.thread_settings_preferences), separator = separator) {
        Column(modifier = Modifier.fillMaxWidth()) {
            var rowAfterFirst = false
            panel.section?.let { section ->
                ChannelSectionSelectRow(
                    state = section,
                    enabled = panel.writeActionsEnabled,
                    onMove = panel.onMoveSection,
                    onCreate = panel.onCreateSection,
                )
                rowAfterFirst = true
            }
            val levelTarget = panel.notificationLevelTarget
            if (levelTarget != null) {
                if (rowAfterFirst) RaftSeparator()
                val title = stringResource(Res.string.notification_level_title)
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftText(
                        title,
                        style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Medium),
                        color = RaftTheme.colors.foreground
                    )
                    RaftNotificationLevelSelect(
                        target = levelTarget,
                        enabled = panel.writeActionsEnabled,
                        refresh = panel.refreshNotificationLevels,
                        label = title,
                        onSetMuted =
                            onToggleActivityMute?.let { toggle ->
                                { next: Boolean -> if (next != levelTarget.muted) toggle() }
                            }
                    )
                }
                rowAfterFirst = true
            } else if (activityMuteState != null && onToggleActivityMute != null) {
                if (rowAfterFirst) RaftSeparator()
                ThreadSettingsPreferenceRow(
                    title = stringResource(Res.string.channel_settings_mute),
                    detail = stringResource(Res.string.channel_settings_mute_detail),
                    checked = activityMuteState.muted,
                    onToggle = onToggleActivityMute
                )
                rowAfterFirst = true
            }
            panel.longMessageCollapse?.takeIf { it.available }?.let { setting ->
                if (rowAfterFirst) RaftSeparator()
                ThreadSettingsPreferenceRow(
                    title = stringResource(Res.string.conversation_long_message_collapse),
                    detail =
                        stringResource(
                            if (directMessage) Res.string.channel_settings_collapse_detail_dm else Res.string.channel_settings_collapse_detail
                        ),
                    checked = setting.effective,
                    onToggle = { setting.onSetEnabled(!setting.effective) }
                )
            }
        }
    }
}

/**
 * A preference row of the settings drawer (web ChannelPreferencesSection): the label in
 * `text-subhead` medium, its consequence in `text-footnote` hint 2dp under it, and the switch at
 * the end, which is the row's one control. `py-3`.
 */
@Composable
private fun ThreadSettingsPreferenceRow(
    title: String,
    detail: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val colors = RaftTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RaftText(
                title,
                style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Medium),
                color = colors.foreground
            )
            RaftText(detail, style = RaftTheme.typography.footnote, color = colors.foregroundHint)
        }
        RaftSwitch(checked = checked, onCheckedChange = { onToggle() }, label = title)
    }
}

/**
 * The confirm a channel action asks before it runs, centred over the settings drawer (web
 * ConfirmDialog `layer={1}`): Make Private explains what changes, Archive and Delete warn. Back
 * (or Cancel) returns to the drawer; the confirm runs the action.
 */
@Composable
private fun ThreadSettingsDangerConfirm(
    action: ThreadSettingsDangerAction,
    channelName: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    RaftUnifiedDialog(
        title = action.localizedTitle(),
        dismissOnOutsideTap = false,
        onDismiss = onBack
    ) {
        when (action) {
            ThreadSettingsDangerAction.Visibility -> {
                Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
                    Text(
                        text = stringResource(Res.string.thread_settings_visibility_question, channelName),
                        style = RaftType.Body,
                        color = RaftColor.TextBlack
                    )
                    Text(
                        text = stringResource(Res.string.thread_settings_visibility_detail),
                        style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
                        color = RaftColor.TextMutedGray
                    )
                }
            }
            ThreadSettingsDangerAction.Archive,
            ThreadSettingsDangerAction.Delete -> ThreadChannelActionWarning(action, channelName)
        }
        RaftDialogFooter {
            val isSingleConfirm =
                threadSettingsDangerConfirmationMode(action.id) ==
                    ThreadSettingsDangerConfirmationMode.SingleConfirm
            RaftModalActionButton(
                label =
                    if (isSingleConfirm) {
                        stringResource(Res.string.common_cancel)
                    } else {
                        stringResource(Res.string.common_back)
                    },
                icon = if (isSingleConfirm) RaftLucideIconName.X else RaftLucideIconName.ArrowLeft,
                onClick = onBack
            )
            RaftModalActionButton(
                label = action.localizedButtonLabel(),
                icon = action.icon,
                color = action.color,
                onClick = onConfirm
            )
        }
    }
}

@Composable
private fun ThreadChannelActionWarning(
    action: ThreadSettingsDangerAction,
    channelName: String
) {
    val warningText =
        when (action) {
            ThreadSettingsDangerAction.Archive -> stringResource(Res.string.thread_settings_archive_question, channelName)
            ThreadSettingsDangerAction.Delete -> stringResource(Res.string.thread_settings_delete_warning, channelName)
            ThreadSettingsDangerAction.Visibility -> return
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1041
                .background(action.color.copy(alpha = 0.22f), RoundedCornerShape(RaftAppRadius.Container))
                .border(RaftAppStroke.Thin, RaftColor.BorderHairline, RoundedCornerShape(RaftAppRadius.Container))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(
            name = RaftLucideIconName.TriangleAlert,
            modifier = Modifier.size(26.dp),
            color = ThreadLegacyBlackIconArgb
        )
        Text(
            text = warningText,
            style = RaftType.Body,
            color = RaftColor.TextBlack,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun ThreadModalEmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = RaftType.Body.withRaftFontWeight(FontWeight.W600).copy(color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        Text(
            text = detail,
            style = RaftType.Caption,
            color = RaftColor.TextMutedGray
        )
    }
}
