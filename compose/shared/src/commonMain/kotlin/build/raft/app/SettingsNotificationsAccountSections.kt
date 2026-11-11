package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftNotificationAccountSettings
import build.raft.app.data.RaftNotificationAccountSettingsPatch
import build.raft.app.data.RaftNotificationChannelNames
import build.raft.app.data.RaftNotificationCustomPause
import build.raft.app.data.RaftNotificationLevel
import build.raft.app.data.RaftNotificationLevelScope
import build.raft.app.data.RaftNotificationLevelSetting
import build.raft.app.data.RaftNotificationLevelStore
import build.raft.app.data.RaftNotificationMobileDelay
import build.raft.app.data.RaftNotificationPauseOption
import build.raft.app.data.RaftServerNotificationLevels
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.raftMinuteOfDayLabel
import build.raft.app.data.raftPausedUntilLabel
import build.raft.app.data.raftQuietHoursPatch
import build.raft.app.data.raftQuietHoursTimeOptions
import build.raft.app.data.DataResult
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowValue
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSelect
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.launch

// Settings › Notifications, the account half (web packages/web/src/components/settings/notifications:
// PauseAndScheduleCard, ContentCard, ServersCard). Everything here is account-wide and saved on the
// server (`me/notification-settings`, `notification-levels`), which applies it to every device.

/** The width of the web's `w-full max-w-sm` selects. */
private val SettingsNotificationSelectMaxWidth = 384.dp

/** One server row of the Servers card; [current] is the server open now. */
internal data class SettingsNotificationServer(val id: String, val name: String, val current: Boolean)

/** The signed-in account's servers, the current one first (web `ServersCard` order). */
internal fun settingsNotificationServers(session: StoredAuthSession?): List<SettingsNotificationServer> {
    session ?: return emptyList()
    val currentId = session.currentServerId
    val rows =
        session.servers
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { SettingsNotificationServer(id = it.id, name = it.name, current = it.id == currentId) }
    return rows.filter { it.current } + rows.filterNot { it.current }
}

/** The "Pause notifications" choices (web `PAUSE_PRESETS` plus Custom). */
internal enum class SettingsNotificationPauseChoice(val preset: RaftNotificationPauseOption?) {
    Minutes30(RaftNotificationPauseOption.Minutes30),
    Hour1(RaftNotificationPauseOption.Hour1),
    Hours2(RaftNotificationPauseOption.Hours2),
    TomorrowMorning(RaftNotificationPauseOption.TomorrowMorning),
    Custom(null)
}

/** Web `SectionHeader` with its 16dp icon, over each section's card. */
@Composable
internal fun SettingsNotificationsSectionHeader(icon: RaftLucideIconName, title: String) {
    RaftSectionEyebrow(
        text = title,
        icon = { RaftLucideIcon(icon, modifier = Modifier.size(16.dp), color = raftArgbHex(RaftTheme.colors.foregroundHint)) }
    )
}

@Composable
private fun SettingsNotificationsSection(
    icon: RaftLucideIconName,
    title: String,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsNotificationsSectionHeader(icon, title)
        content()
    }
}

/** A control's heading inside a card: `text-subhead font-semibold`. */
@Composable
private fun SettingsNotificationsControlTitle(text: String) {
    RaftText(
        text,
        style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
        color = RaftTheme.colors.foreground
    )
}

@Composable
private fun SettingsNotificationsNote(text: String, hint: Boolean = false) {
    RaftText(
        text,
        style = RaftTheme.typography.footnote,
        color = if (hint) RaftTheme.colors.foregroundHint else RaftTheme.colors.foregroundMuted
    )
}

@Composable
private fun SettingsNotificationsSaveFailed() {
    RaftBanner(
        description = stringResource(Res.string.settings_notifications_account_save_failed),
        status = RaftBannerStatus.Warning,
        size = RaftBannerSize.Sm
    )
}

/**
 * Pause and schedule (web `PauseAndScheduleCard`): pause for a while, or until a chosen time, and
 * daily quiet hours in this device's time zone. [onSave] sends a patch and answers whether it saved.
 */
@Composable
internal fun SettingsNotificationPauseSection(
    settings: RaftNotificationAccountSettings,
    saving: Boolean,
    saveFailed: Boolean,
    nowEpochMillis: Long,
    timeZoneId: String,
    onSave: (RaftNotificationAccountSettingsPatch, (Boolean) -> Unit) -> Unit
) {
    var choice by remember { mutableStateOf<SettingsNotificationPauseChoice?>(null) }
    var custom by remember { mutableStateOf(RaftNotificationCustomPause.initial(nowEpochMillis, timeZoneId)) }
    val pausedUntil = settings.pausedUntilEpochMillis()?.takeIf { it > nowEpochMillis }
    val quiet = settings.quietHours
    val pauseTitle = stringResource(Res.string.settings_notifications_pause_title)
    SettingsNotificationsSection(RaftLucideIconName.Moon, stringResource(Res.string.settings_notifications_pause_section)) {
        RaftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (saveFailed) SettingsNotificationsSaveFailed()
                Column(Modifier.settingsSearchAnchor("pause-notifications"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsNotificationsControlTitle(pauseTitle)
                    if (pausedUntil != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                SettingsNotificationsNote(
                                    stringResource(
                                        Res.string.settings_notifications_paused_until,
                                        raftPausedUntilLabel(pausedUntil, nowEpochMillis)
                                    )
                                )
                            }
                            RaftButton(
                                text = stringResource(Res.string.settings_notifications_resume),
                                onClick = { onSave(RaftNotificationAccountSettingsPatch(resume = true)) {} },
                                size = RaftButtonSize.Md,
                                enabled = !saving
                            )
                        }
                    } else {
                        val labels = SettingsNotificationPauseChoice.entries.associateWith { it.localizedLabel() }
                        RaftSelect(
                            value = choice,
                            options = SettingsNotificationPauseChoice.entries,
                            onValueChange = { next ->
                                val preset = next.preset
                                if (preset == null) {
                                    choice = next
                                } else {
                                    choice = null
                                    onSave(RaftNotificationAccountSettingsPatch(pausedUntil = preset.pausedUntil(raftNowEpochMillisForSettings(), timeZoneId))) {}
                                }
                            },
                            optionLabel = { labels.getValue(it) },
                            modifier = Modifier.widthIn(max = SettingsNotificationSelectMaxWidth).fillMaxWidth(),
                            placeholder = stringResource(Res.string.settings_notifications_pause_off),
                            enabled = !saving,
                            label = pauseTitle
                        )
                        if (choice == SettingsNotificationPauseChoice.Custom) {
                            SettingsNotificationCustomPause(
                                value = custom,
                                onValueChange = { custom = it },
                                nowEpochMillis = nowEpochMillis,
                                timeZoneId = timeZoneId,
                                enabled = !saving,
                                onApply = {
                                    onSave(RaftNotificationAccountSettingsPatch(pausedUntil = custom.pausedUntil(timeZoneId))) { saved ->
                                        if (saved) choice = null
                                    }
                                }
                            )
                        }
                    }
                }
                val quietTitle = stringResource(Res.string.settings_notifications_quiet_hours_title)
                RaftListRowGroup(Modifier.settingsSearchAnchor("quiet-hours", SettingsSearchCardShape)) {
                    RaftListRow(
                        title = quietTitle,
                        variant = RaftListRowVariant.Plain,
                        titleMaxLines = 2,
                        description = stringResource(Res.string.settings_notifications_quiet_hours_detail, timeZoneId),
                        wrapDescription = true,
                        trailing = {
                            RaftSwitch(
                                checked = quiet.enabled,
                                onCheckedChange = { enabled -> onSave(raftQuietHoursPatch(quiet, timeZoneId, enabled = enabled)) {} },
                                enabled = !saving,
                                label = quietTitle
                            )
                        }
                    )
                }
                if (quiet.enabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.widthIn(max = SettingsNotificationSelectMaxWidth).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SettingsNotificationQuietTime(
                                label = stringResource(Res.string.settings_notifications_quiet_hours_start),
                                minute = quiet.startMinute,
                                modifier = Modifier.weight(1f),
                                onPick = { onSave(raftQuietHoursPatch(quiet, timeZoneId, startMinute = it)) {} }
                            )
                            SettingsNotificationQuietTime(
                                label = stringResource(Res.string.settings_notifications_quiet_hours_end),
                                minute = quiet.endMinute,
                                modifier = Modifier.weight(1f),
                                onPick = { onSave(raftQuietHoursPatch(quiet, timeZoneId, endMinute = it)) {} }
                            )
                        }
                        if (quiet.timeZone != timeZoneId) {
                            SettingsNotificationsNote(
                                stringResource(Res.string.settings_notifications_quiet_hours_stored_zone, quiet.timeZone),
                                hint = true
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The clock now; a function so a screenshot fixture's fixed time only feeds what is drawn. */
private fun raftNowEpochMillisForSettings(): Long = build.raft.app.data.raftNowEpochMillis()

@Composable
private fun SettingsNotificationQuietTime(label: String, minute: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsNotificationsNote(label)
        RaftSelect(
            value = minute,
            options = raftQuietHoursTimeOptions(minute),
            onValueChange = { if (it != minute) onPick(it) },
            optionLabel = ::raftMinuteOfDayLabel,
            modifier = Modifier.fillMaxWidth(),
            // Stays enabled while a save is in flight (web QuietTimeInput): a pick never waits.
            enabled = true,
            label = label
        )
    }
}

/** Custom pause (web `datetime-local` plus Pause): a day and a time, then Pause. */
@Composable
private fun SettingsNotificationCustomPause(
    value: RaftNotificationCustomPause,
    onValueChange: (RaftNotificationCustomPause) -> Unit,
    nowEpochMillis: Long,
    timeZoneId: String,
    enabled: Boolean,
    onApply: () -> Unit
) {
    val label = stringResource(Res.string.settings_notifications_pause_custom_label)
    val days = remember(nowEpochMillis, timeZoneId) { RaftNotificationCustomPause.days(nowEpochMillis, timeZoneId) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsNotificationsNote(label)
        Row(
            Modifier.widthIn(max = SettingsNotificationSelectMaxWidth).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RaftSelect(
                value = value.date,
                options = days,
                onValueChange = { onValueChange(value.copy(date = it)) },
                optionLabel = { it.toString() },
                modifier = Modifier.weight(1.4f),
                enabled = enabled,
                label = label
            )
            RaftSelect(
                value = value.minuteOfDay,
                options = raftQuietHoursTimeOptions(value.minuteOfDay),
                onValueChange = { onValueChange(value.copy(minuteOfDay = it)) },
                optionLabel = ::raftMinuteOfDayLabel,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                label = label
            )
        }
        RaftButton(
            text = stringResource(Res.string.settings_notifications_pause_apply),
            onClick = onApply,
            size = RaftButtonSize.Md,
            enabled = enabled && value.accepted(nowEpochMillis, timeZoneId)
        )
    }
}

@Composable
private fun SettingsNotificationPauseChoice.localizedLabel(): String =
    when (this) {
        SettingsNotificationPauseChoice.Minutes30 -> stringResource(Res.string.settings_notifications_pause_30m)
        SettingsNotificationPauseChoice.Hour1 -> stringResource(Res.string.settings_notifications_pause_1h)
        SettingsNotificationPauseChoice.Hours2 -> stringResource(Res.string.settings_notifications_pause_2h)
        SettingsNotificationPauseChoice.TomorrowMorning -> stringResource(Res.string.settings_notifications_pause_tomorrow)
        SettingsNotificationPauseChoice.Custom -> stringResource(Res.string.settings_notifications_pause_custom)
    }

/**
 * Content (web `ContentCard`): what notifications show, when the phone alerts while a computer is
 * in use, and sound. [previewMode] / [onPreviewChange] are the preview row's value and writer;
 * [mobileDelay] is null on a server without the setting (the row is then not shown).
 */
@Composable
internal fun SettingsNotificationContentSection(
    settings: RaftNotificationAccountSettings,
    previewMode: PushPreviewMode,
    saving: Boolean,
    saveFailed: Boolean,
    onPreviewChange: (PushPreviewMode) -> Unit,
    onSave: (RaftNotificationAccountSettingsPatch) -> Unit
) {
    val mobileDelay = settings.mobileDelaySetting
    SettingsNotificationsSection(RaftLucideIconName.Eye, stringResource(Res.string.settings_notifications_content_section)) {
        RaftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (saveFailed) SettingsNotificationsSaveFailed()
                val previewTitle = stringResource(Res.string.settings_notifications_preview_mode_title)
                Column(Modifier.settingsSearchAnchor("notification-preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsNotificationsControlTitle(previewTitle)
                    SettingsNotificationsNote(stringResource(Res.string.settings_notifications_preview_mode_detail))
                    val labels = PushPreviewMode.entries.associateWith { stringResource(it.settingsPreviewModeLabel()) }
                    RaftSelect(
                        value = previewMode,
                        options = PushPreviewMode.entries,
                        onValueChange = { if (it != previewMode) onPreviewChange(it) },
                        optionLabel = { labels.getValue(it) },
                        modifier = Modifier.widthIn(max = SettingsNotificationSelectMaxWidth).fillMaxWidth(),
                        enabled = !saving,
                        label = previewTitle
                    )
                }
                if (mobileDelay != null) {
                    val delayTitle = stringResource(Res.string.settings_notifications_mobile_delay_title)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsNotificationsControlTitle(delayTitle)
                        SettingsNotificationsNote(stringResource(Res.string.settings_notifications_mobile_delay_detail))
                        val labels = RaftNotificationMobileDelay.entries.associateWith { it.localizedMobileDelayLabel() }
                        RaftSelect(
                            value = mobileDelay,
                            options = RaftNotificationMobileDelay.entries,
                            onValueChange = { option -> settingsMobileDelayPatch(settings, option)?.let(onSave) },
                            optionLabel = { labels.getValue(it) },
                            modifier = Modifier.widthIn(max = SettingsNotificationSelectMaxWidth).fillMaxWidth(),
                            enabled = !saving,
                            label = delayTitle
                        )
                        if (mobileDelay == RaftNotificationMobileDelay.Always) {
                            SettingsNotificationsNote(stringResource(Res.string.settings_notifications_mobile_delay_always_detail))
                        }
                    }
                }
                val soundTitle = stringResource(Res.string.settings_notifications_sound_title)
                RaftListRowGroup(Modifier.settingsSearchAnchor("notification-sound", SettingsSearchCardShape)) {
                    RaftListRow(
                        title = soundTitle,
                        variant = RaftListRowVariant.Plain,
                        titleMaxLines = 2,
                        description = stringResource(Res.string.settings_notifications_sound_detail),
                        wrapDescription = true,
                        trailing = {
                            RaftSwitch(
                                checked = settings.sound,
                                onCheckedChange = { onSave(RaftNotificationAccountSettingsPatch(sound = it)) },
                                enabled = !saving,
                                label = soundTitle
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * Servers (web `ServersCard`): every server's level, the current server first, and per server the
 * channels with a level of their own ("N channels customized"), each one resettable to the default.
 */
@Composable
internal fun SettingsNotificationServersSection(servers: List<SettingsNotificationServer>) {
    if (servers.isEmpty()) return
    val levels by RaftNotificationLevelStore.state.collectAsState()
    val ids = servers.map { it.id }
    LaunchedEffect(ids) { ids.forEach { RaftNotificationLevelStore.refresh(it) } }
    SettingsNotificationsSection(RaftLucideIconName.Building2, stringResource(Res.string.settings_notifications_servers_section)) {
        RaftCard(Modifier.fillMaxWidth(), variant = RaftCardVariant.Inset) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                servers.forEachIndexed { index, server ->
                    if (index > 0) RaftSeparator()
                    SettingsNotificationServerRow(server, levels[server.id] ?: RaftServerNotificationLevels(server.id))
                }
            }
        }
    }
}

/**
 * Settings › On {server} › Notifications (web `CurrentServerNotificationsCard`): the current
 * server's level and its customized channels, the same row as in the Servers card.
 */
@Composable
internal fun SettingsNotificationCurrentServerCard(server: SettingsNotificationServer) {
    val levels by RaftNotificationLevelStore.state.collectAsState()
    LaunchedEffect(server.id) { RaftNotificationLevelStore.refresh(server.id) }
    RaftCard(Modifier.fillMaxWidth(), variant = RaftCardVariant.Inset) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SettingsNotificationServerRow(server, levels[server.id] ?: RaftServerNotificationLevels(server.id))
        }
    }
}

/**
 * The personal page's way to the current server's own page (web `ServerNotificationsLink`):
 * "Notifications on {server}", its level, and a chevron.
 */
@Composable
internal fun SettingsNotificationServerLink(server: SettingsNotificationServer, onClick: () -> Unit) {
    val levels by RaftNotificationLevelStore.state.collectAsState()
    val level = (levels[server.id] ?: RaftServerNotificationLevels(server.id)).server
    RaftListRowGroup {
        RaftListRow(
            title = stringResource(Res.string.settings_server_notifications_subtitle, server.name),
            variant = RaftListRowVariant.Plain,
            titleMaxLines = 2,
            onClick = onClick,
            trailing = {
                RaftListRowValue(level.localizedNotificationLevelLabel())
                RaftListRowChevron()
            }
        )
    }
}

@Composable
private fun SettingsNotificationServerRow(server: SettingsNotificationServer, levels: RaftServerNotificationLevels) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val names by RaftNotificationChannelNames.state.collectAsState()
    LaunchedEffect(open, server.id) { if (open) RaftNotificationChannelNames.load(server.id) }
    val title =
        if (server.current) stringResource(Res.string.settings_notifications_servers_current, server.name) else server.name
    val levelLabels = RaftNotificationLevel.entries.associateWith { it.localizedNotificationLevelLabel() }
    fun setLevel(scopeKind: RaftNotificationLevelScope, id: String, setting: RaftNotificationLevelSetting) {
        failed = false
        scope.launch {
            failed = RaftNotificationLevelStore.setLevel(server.id, scopeKind, id, setting) !is DataResult.Content
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val select: @Composable (Modifier) -> Unit = { modifier ->
                RaftSelect(
                    value = levels.server,
                    options = RaftNotificationLevel.entries,
                    onValueChange = { level ->
                        if (level != levels.server) setLevel(RaftNotificationLevelScope.Server, server.id, RaftNotificationLevelSetting.of(level))
                    },
                    optionLabel = { levelLabels.getValue(it) },
                    modifier = modifier,
                    label = title
                )
            }
            // Web `flex flex-wrap justify-between`: the name and the select share a line when they fit.
            if (maxWidth >= SettingsNotificationSelectMaxWidth + 160.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsNotificationServerName(title, Modifier.weight(1f))
                    select(Modifier.widthIn(max = SettingsNotificationSelectMaxWidth).fillMaxWidth())
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsNotificationServerName(title, Modifier.fillMaxWidth())
                    select(Modifier.fillMaxWidth())
                }
            }
        }
        val customized = levels.channels.entries.mapNotNull { (id, setting) -> setting.explicitLevel?.let { id to it } }
        if (customized.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftTextLink(
                    text =
                        stringResource(
                            if (customized.size == 1) {
                                Res.string.settings_notifications_servers_customized_one
                            } else {
                                Res.string.settings_notifications_servers_customized_other
                            },
                            customized.size
                        ),
                    onClick = { open = !open },
                    style = RaftTheme.typography.footnote
                )
                if (open) {
                    val serverNames = names[server.id].orEmpty()
                    customized.forEach { (channelId, level) ->
                        SettingsNotificationCustomizedChannel(
                            name = serverNames[channelId] ?: stringResource(Res.string.settings_notifications_servers_unknown_channel),
                            levelLabel = levelLabels.getValue(level),
                            onReset = { setLevel(RaftNotificationLevelScope.Channel, channelId, RaftNotificationLevelSetting.Default) }
                        )
                    }
                }
            }
        }
        if (failed) SettingsFormErrorText(stringResource(Res.string.notification_level_save_failed))
    }
}

@Composable
private fun SettingsNotificationServerName(text: String, modifier: Modifier) {
    RaftText(
        text,
        modifier = modifier,
        style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
        color = RaftTheme.colors.foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SettingsNotificationCustomizedChannel(name: String, levelLabel: String, onReset: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            RaftText(
                name,
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SettingsNotificationsNote(levelLabel)
        }
        RaftButton(
            text = stringResource(Res.string.settings_notifications_servers_reset),
            onClick = onReset,
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Md
        )
    }
}
