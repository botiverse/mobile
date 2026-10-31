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
import org.jetbrains.compose.resources.StringResource

/**
 * What a level picker is about (slock docs/contracts/notifications.md §1.1): a channel or DM
 * ([RaftNotificationLevelScope.Channel], [id] = its channel id) or a thread / task conversation
 * ([RaftNotificationLevelScope.Thread], [id] = its own channel id, [parentChannelId] = the channel it
 * belongs to). [muted]: the channel (or DM) is muted, which silences it entirely (slock #12231, as in
 * Slack). [muteSupported]: the person can mute / unmute it here, so a channel's picker offers Mute as
 * its third choice.
 */
data class RaftNotificationLevelTarget(
    val serverId: String,
    val scope: RaftNotificationLevelScope,
    val id: String,
    val parentChannelId: String? = null,
    val isDirectMessage: Boolean = false,
    val muted: Boolean = false,
    val muteSupported: Boolean = false
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

    /** The level that applies: the most specific explicit setting; a muted channel pushes nothing. */
    fun effective(levels: RaftServerNotificationLevels): RaftNotificationLevel =
        when (scope) {
            RaftNotificationLevelScope.Thread ->
                if (muted) RaftNotificationLevel.None
                else resolveEffectiveNotificationLevel(levels.server, parentSetting(levels), setting(levels), false)
            RaftNotificationLevelScope.Channel ->
                if (muted) RaftNotificationLevel.None
                else resolveEffectiveNotificationLevel(levels.server, setting(levels), null, false)
            RaftNotificationLevelScope.Server -> levels.server
        }

    /** A channel whose picker shows Mute as a choice (web `NotificationLevelTarget.mute`). */
    val hasMuteChoice: Boolean
        get() = scope == RaftNotificationLevelScope.Channel && (muteSupported || muted)
}

/**
 * One row of a channel's Notifications choice (slock #12231): a level, or Mute. A muted channel shows
 * Mute; picking Mute sets the conversation's mute (the same state as Activity's Mute), not a level.
 */
sealed interface RaftNotificationChoice {
    data class Level(val setting: RaftNotificationLevelSetting) : RaftNotificationChoice
    data object Mute : RaftNotificationChoice
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
        RaftNotificationLevelSetting.Mentions ->
            if (scope == RaftNotificationLevelScope.Channel) {
                stringResource(Res.string.notification_level_channel_mentions)
            } else {
                stringResource(Res.string.notification_level_mentions)
            }
        RaftNotificationLevelSetting.None -> stringResource(Res.string.notification_level_none)
    }

/**
 * Whether [serverId] runs the unified notification API ([RaftNotificationCapability]): the level
 * rows show only then, so an older server shows no menu entry that could only fail.
 */
@Composable
internal fun rememberRaftNotificationLevelsSupported(serverId: String?): Boolean {
    val supported by build.raft.app.data.RaftNotificationCapability.supportedByServer.collectAsState()
    return serverId != null && supported[serverId] == true
}

/**
 * [target] when its server runs the unified API and its scope still has an entry, else null.
 *
 * Threads and DMs have no level (stdrc + Wug, #proj-raft-ui:f603e418, web slock #12214, after
 * Slack): a thread is only followed or not, a DM is only muted or not. Channels keep Default / All /
 * Only what involves me / Nothing, with no "Default" (the inherited level shows as
 * selected); the server default lives in Settings › Notifications. Every entry point goes through here.
 */
@Composable
internal fun RaftNotificationLevelTarget?.whenLevelsSupported(): RaftNotificationLevelTarget? {
    val supported = rememberRaftNotificationLevelsSupported(this?.serverId)
    return this?.takeIf { supported && raftNotificationLevelEntryShown(it.scope, it.isDirectMessage) }
}

/**
 * Threads have no level entry (follow / unfollow is their only switch) and DMs have none either
 * (only mute): web slock #12214.
 */
internal fun raftNotificationLevelEntryShown(scope: RaftNotificationLevelScope, isDirectMessage: Boolean = false): Boolean =
    scope != RaftNotificationLevelScope.Thread && !isDirectMessage

/**
 * The level choices of a scope with no Mute: a channel lists only concrete levels, All / Only what
 * involves me / Nothing (stdrc + Wug, #proj-raft-ui:f603e418: a channel has no "Default"); the
 * server keeps every value.
 */
internal fun raftNotificationLevelChoices(scope: RaftNotificationLevelScope): List<RaftNotificationLevelSetting> =
    when (scope) {
        RaftNotificationLevelScope.Server -> RaftNotificationLevelSetting.entries
        else -> RaftNotificationLevelSetting.entries.filter { it != RaftNotificationLevelSetting.Default }
    }

/**
 * What a picker offers (web `useNotificationLevelModel` options, slock #12231). A channel with a mute
 * control: All / Only what involves me / Mute, loud to quiet; a channel set to Nothing before
 * Mute took its place keeps showing Nothing (before Mute) until it changes. Without a mute control
 * the plain levels of [raftNotificationLevelChoices].
 */
internal fun raftNotificationChoices(
    scope: RaftNotificationLevelScope,
    hasMuteChoice: Boolean,
    shownLevel: RaftNotificationLevelSetting,
    muted: Boolean
): List<RaftNotificationChoice> {
    if (!hasMuteChoice) return raftNotificationLevelChoices(scope).map(RaftNotificationChoice::Level)
    return buildList {
        add(RaftNotificationChoice.Level(RaftNotificationLevelSetting.All))
        add(RaftNotificationChoice.Level(RaftNotificationLevelSetting.Mentions))
        if (shownLevel == RaftNotificationLevelSetting.None && !muted) {
            add(RaftNotificationChoice.Level(RaftNotificationLevelSetting.None))
        }
        add(RaftNotificationChoice.Mute)
    }
}

/** The concrete level a channel shows: its own level, else the one it inherits. */
internal fun raftChannelLevelShown(
    setting: RaftNotificationLevelSetting,
    inherited: RaftNotificationLevel
): RaftNotificationLevelSetting = RaftNotificationLevelSetting.of(setting.explicitLevel ?: inherited)

/** The choice a picker shows as selected: Mute while muted (channels), else the level shown. */
internal fun raftNotificationChoiceShown(
    target: RaftNotificationLevelTarget,
    levels: RaftServerNotificationLevels
): RaftNotificationChoice {
    if (target.hasMuteChoice && target.muted) return RaftNotificationChoice.Mute
    val inherited = target.inherited(levels)
    val level =
        if (target.scope == RaftNotificationLevelScope.Channel) raftChannelLevelShown(target.setting(levels), inherited) else target.setting(levels)
    return RaftNotificationChoice.Level(level)
}

/** What picking [choice] writes for a channel: the server's own level means following the server again. */
internal fun raftChannelLevelWrite(
    choice: RaftNotificationLevelSetting,
    serverLevel: RaftNotificationLevel
): RaftNotificationLevelSetting =
    if (choice.explicitLevel == serverLevel) RaftNotificationLevelSetting.Default else choice

/**
 * What picking a choice does (web `useNotificationLevelModel.set`, slock #12231). [setMuted]: the
 * mute to set first, if any; [writeLevel]: the level to store after, if any.
 */
internal data class RaftNotificationChoicePlan(
    val setMuted: Boolean? = null,
    val writeLevel: RaftNotificationLevelSetting? = null
)

/**
 * Mute only mutes (the level stays as it is). A level on a muted channel unmutes first, and stores the
 * level only when it differs from the one shown (so picking the shown level while muted only unmutes).
 */
internal fun raftNotificationChoicePlan(
    choice: RaftNotificationChoice,
    target: RaftNotificationLevelTarget,
    levels: RaftServerNotificationLevels
): RaftNotificationChoicePlan {
    if (choice == RaftNotificationChoice.Mute) return RaftNotificationChoicePlan(setMuted = true)
    val setting = (choice as RaftNotificationChoice.Level).setting
    val unmute = if (target.hasMuteChoice && target.muted) false else null
    val channel = target.scope == RaftNotificationLevelScope.Channel
    val current =
        if (channel) raftChannelLevelShown(target.setting(levels), target.inherited(levels)) else target.setting(levels)
    val write =
        when {
            setting == current -> null
            channel -> raftChannelLevelWrite(setting, levels.server)
            else -> setting
        }
    return RaftNotificationChoicePlan(setMuted = unmute, writeLevel = write)
}

@Composable
private fun RaftNotificationChoice.localizedLabel(target: RaftNotificationLevelTarget, inherited: RaftNotificationLevel): String =
    when (this) {
        RaftNotificationChoice.Mute -> stringResource(Res.string.notification_level_mute)
        is RaftNotificationChoice.Level -> setting.localizedNotificationLevelSettingLabel(inherited, target.scope)
    }

/** The current choice's label ("Mute", "Nothing", "Default (All new messages)", ...), from the process level store. */
@Composable
internal fun rememberRaftNotificationLevelCurrentLabel(target: RaftNotificationLevelTarget): String {
    val all by RaftNotificationLevelStore.state.collectAsState()
    val levels = all[target.serverId] ?: RaftServerNotificationLevels(target.serverId)
    return raftNotificationChoiceShown(target, levels).localizedLabel(target, target.inherited(levels))
}

/** "Notifications: <current>" for a menu row. */
@Composable
internal fun rememberRaftNotificationLevelMenuLabel(target: RaftNotificationLevelTarget?): String {
    if (target == null) return stringResource(Res.string.notification_level_title)
    return stringResource(Res.string.notification_level_menu_label, rememberRaftNotificationLevelCurrentLabel(target))
}

/**
 * The Notifications picker of a channel (slock #12231, as in Slack): All new messages, Only what
 * involves me, Mute ([raftNotificationChoices]); the selected one is Mute while muted, else the
 * level in effect, inherited or chosen. Levels write through `PUT notification-levels` (the store
 * applies it at once and rolls back when the server refuses); Mute goes through [onSetMuted], the
 * conversation's existing mute, which reports its own failure.
 */
@Composable
internal fun RaftNotificationLevelDialog(
    target: RaftNotificationLevelTarget,
    onDismiss: () -> Unit,
    onSetMuted: ((Boolean) -> Unit)? = null
) {
    val all by RaftNotificationLevelStore.state.collectAsState()
    val levels = all[target.serverId] ?: RaftServerNotificationLevels(target.serverId)
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(target.serverId) { RaftNotificationLevelStore.refresh(target.serverId) }
    val inherited = target.inherited(levels)
    // Mute is offered only where something can set it.
    val shownTarget = if (onSetMuted == null) target.copy(muteSupported = false, muted = false) else target
    val selected = raftNotificationChoiceShown(shownTarget, levels)
    val shownLevel = (raftNotificationChoiceShown(shownTarget.copy(muted = false), levels) as RaftNotificationChoice.Level).setting
    RaftUnifiedDialog(
        title = stringResource(Res.string.notification_level_title),
        onDismiss = onDismiss,
        contentSpacing = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            raftNotificationChoices(shownTarget.scope, shownTarget.hasMuteChoice, shownLevel, shownTarget.muted).forEach { option ->
                SettingsServerSelectRow(
                    title = option.localizedLabel(target, inherited),
                    detail = "",
                    selected = option == selected,
                    enabled = !saving,
                    onClick = {
                        if (option == selected || saving) return@SettingsServerSelectRow
                        val plan = raftNotificationChoicePlan(option, shownTarget, levels)
                        plan.setMuted?.let { muted -> onSetMuted?.invoke(muted) }
                        val write = plan.writeLevel ?: return@SettingsServerSelectRow
                        saving = true
                        failed = false
                        scope.launch {
                            val result = RaftNotificationLevelStore.setLevel(target.serverId, target.scope, target.id, write)
                            saving = false
                            failed = result !is DataResult.Content
                        }
                    }
                )
            }
            raftNotificationLevelNotes(shownTarget).forEach { note -> SettingsFormDetailText(stringResource(note)) }
            if (failed) SettingsFormErrorText(stringResource(Res.string.notification_level_save_failed))
        }
    }
}

/**
 * The notes under the choices: what Mute means while it is the choice (nothing notifies, not even
 * @mentions: slock #12231); in a DM All and Mentions behave the same; a thread's All is following it.
 */
internal fun raftNotificationLevelNotes(target: RaftNotificationLevelTarget): List<StringResource> =
    buildList {
        when {
            target.muted -> add(Res.string.notification_level_muted_note)
            target.isDirectMessage -> add(Res.string.notification_level_dm_note)
        }
        if (target.scope == RaftNotificationLevelScope.Thread) add(Res.string.notification_level_thread_note)
    }
