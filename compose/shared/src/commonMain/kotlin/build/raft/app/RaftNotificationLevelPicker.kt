package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.data.DataResult
import build.raft.app.data.RaftNotificationLevel
import build.raft.app.data.RaftNotificationLevelScope
import build.raft.app.data.RaftNotificationLevelSetting
import build.raft.app.data.RaftNotificationLevelStore
import build.raft.app.data.RaftServerNotificationLevels
import build.raft.app.data.inheritedNotificationLevel
import build.raft.app.data.resolveEffectiveNotificationLevel
import build.raft.app.resources.*
import kotlinx.coroutines.launch

/**
 * What a level picker is about (slock docs/contracts/notifications.md §1.1): a channel or DM
 * ([RaftNotificationLevelScope.Channel], [id] = its channel id) or a thread / task conversation
 * ([RaftNotificationLevelScope.Thread], [id] = its own channel id, [parentChannelId] = the channel it
 * belongs to). [muted]: the channel (or DM) is muted in Activity, which caps the level at mentions.
 */
data class RaftNotificationLevelTarget(
    val serverId: String,
    val scope: RaftNotificationLevelScope,
    val id: String,
    val parentChannelId: String? = null,
    val isDirectMessage: Boolean = false,
    val muted: Boolean = false
) {
    /** The stored setting of this target in [levels]; `default` when none is stored. */
    fun setting(levels: RaftServerNotificationLevels): RaftNotificationLevelSetting =
        when (scope) {
            RaftNotificationLevelScope.Thread -> levels.threadSetting(id)
            RaftNotificationLevelScope.Channel -> levels.channelSetting(id)
            RaftNotificationLevelScope.Server -> RaftNotificationLevelSetting.of(levels.server)
        }

    /** The channel setting a thread inherits from (null for a channel). */
    fun parentSetting(levels: RaftServerNotificationLevels): RaftNotificationLevelSetting? =
        if (scope == RaftNotificationLevelScope.Thread) parentChannelId?.let(levels::channelSetting) else null

    /** What `default` means here: the parent channel's explicit level, else the server's. */
    fun inherited(levels: RaftServerNotificationLevels): RaftNotificationLevel =
        inheritedNotificationLevel(scope = scope, server = levels.server, channel = parentSetting(levels))

    /** The level that applies (most specific explicit wins, then the mute cap). */
    fun effective(levels: RaftServerNotificationLevels): RaftNotificationLevel =
        when (scope) {
            RaftNotificationLevelScope.Thread ->
                resolveEffectiveNotificationLevel(levels.server, parentSetting(levels), setting(levels), muted)
            RaftNotificationLevelScope.Channel ->
                resolveEffectiveNotificationLevel(levels.server, setting(levels), null, muted)
            RaftNotificationLevelScope.Server -> levels.server
        }
}

@Composable
internal fun RaftNotificationLevel.localizedNotificationLevelLabel(): String =
    when (this) {
        RaftNotificationLevel.All -> stringResource(Res.string.notification_level_all)
        RaftNotificationLevel.Mentions -> stringResource(Res.string.notification_level_mentions)
        RaftNotificationLevel.None -> stringResource(Res.string.notification_level_none)
    }

/** The label of a stored setting: `Default (<inherited>)` for default. */
@Composable
internal fun RaftNotificationLevelSetting.localizedNotificationLevelSettingLabel(
    inherited: RaftNotificationLevel,
    scope: RaftNotificationLevelScope
): String =
    when (this) {
        RaftNotificationLevelSetting.Default ->
            stringResource(Res.string.notification_level_default, inherited.localizedNotificationLevelLabel())
        RaftNotificationLevelSetting.All ->
            if (scope == RaftNotificationLevelScope.Thread) {
                stringResource(Res.string.notification_level_thread_all)
            } else {
                stringResource(Res.string.notification_level_all)
            }
        RaftNotificationLevelSetting.Mentions -> stringResource(Res.string.notification_level_mentions)
        RaftNotificationLevelSetting.None -> stringResource(Res.string.notification_level_none)
    }

/** The current setting's label ("Default (All new messages)", "Nothing", ...), from the process level store. */
@Composable
internal fun rememberRaftNotificationLevelCurrentLabel(target: RaftNotificationLevelTarget): String {
    val all by RaftNotificationLevelStore.state.collectAsState()
    val levels = all[target.serverId] ?: RaftServerNotificationLevels(target.serverId)
    return target.setting(levels).localizedNotificationLevelSettingLabel(target.inherited(levels), target.scope)
}

/** "Notifications: <current>" for a menu row. */
@Composable
internal fun rememberRaftNotificationLevelMenuLabel(target: RaftNotificationLevelTarget?): String {
    if (target == null) return stringResource(Res.string.notification_level_title)
    return stringResource(Res.string.notification_level_menu_label, rememberRaftNotificationLevelCurrentLabel(target))
}

/**
 * The level picker of a channel, DM or thread, the same four choices as web (design §4.3): Default
 * (<inherited>), All, Mentions, Nothing. Writes through `PUT notification-levels`; the store applies
 * it at once and rolls back when the server refuses.
 */
@Composable
internal fun RaftNotificationLevelDialog(
    target: RaftNotificationLevelTarget,
    onDismiss: () -> Unit
) {
    val all by RaftNotificationLevelStore.state.collectAsState()
    val levels = all[target.serverId] ?: RaftServerNotificationLevels(target.serverId)
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(target.serverId) { RaftNotificationLevelStore.refresh(target.serverId) }
    val selected = target.setting(levels)
    val inherited = target.inherited(levels)
    RaftUnifiedDialog(
        title = stringResource(Res.string.notification_level_title),
        onDismiss = onDismiss,
        contentSpacing = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RaftNotificationLevelSetting.entries.forEach { option ->
                SettingsServerSelectRow(
                    title = option.localizedNotificationLevelSettingLabel(inherited, target.scope),
                    detail = "",
                    selected = option == selected,
                    enabled = !saving,
                    onClick = {
                        if (option == selected || saving) return@SettingsServerSelectRow
                        saving = true
                        failed = false
                        scope.launch {
                            val result = RaftNotificationLevelStore.setLevel(target.serverId, target.scope, target.id, option)
                            saving = false
                            failed = result !is DataResult.Content
                        }
                    }
                )
            }
            val notes =
                buildList {
                    if (target.muted) add(Res.string.notification_level_muted_note)
                    if (target.isDirectMessage) add(Res.string.notification_level_dm_note)
                    if (target.scope == RaftNotificationLevelScope.Thread) add(Res.string.notification_level_thread_note)
                }
            notes.forEach { note -> SettingsFormDetailText(stringResource(note)) }
            if (failed) SettingsFormErrorText(stringResource(Res.string.notification_level_save_failed))
        }
    }
}
