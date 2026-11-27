package build.raft.app.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.AppLocale
import build.raft.app.data.AssistantChangedSetting
import build.raft.app.data.AssistantSettingChange
import build.raft.app.data.AssistantSettingValue
import build.raft.app.data.RaftAssistantSettingChangesApi
import build.raft.app.data.ofSettings
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.raftLocalizedMediumDateTime
import build.raft.app.resources.*
import build.raft.app.settings.settingsDisplayName
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftThemeId

// "Changed by your assistant" (web components/settings/AssistantSettingChanges.tsx): what the
// owner's personal assistant changed of a page's settings, newest first, one muted footnote line
// per change. The theme is this server's; the display language and time zone are the account's,
// so a change made from another server shows too, labelled with it. Nothing shows when it changed
// none of them.

/**
 * Where a settings page reads the changes from: the current server's audit through [api]. Provided
 * by the logged-in Settings pages; absent (fixtures, no current server) nothing shows.
 */
internal class AssistantSettingChangesSource(
    val serverId: String,
    val api: RaftAssistantSettingChangesApi,
)

internal val LocalAssistantSettingChangesSource = compositionLocalOf<AssistantSettingChangesSource?> { null }

/**
 * The recorded changes of [settings] in [source]'s server, newest first; empty until read, on a
 * failure, or without a source. Read each time the page opens and when the server changes (web
 * `useAssistantSettingChanges`).
 */
@Composable
internal fun rememberAssistantSettingChanges(
    settings: Set<AssistantChangedSetting>,
    source: AssistantSettingChangesSource? = LocalAssistantSettingChangesSource.current,
): List<AssistantSettingChange> {
    var changes by remember(source?.serverId, source?.api) { mutableStateOf<List<AssistantSettingChange>>(emptyList()) }
    LaunchedEffect(source?.serverId, source?.api) {
        val current = source ?: return@LaunchedEffect
        val serverId = current.serverId.trim().takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        current.api.fetch(ServerId(serverId))?.let { changes = it }
    }
    return remember(changes, settings) { changes.ofSettings(settings) }
}

/**
 * The section (web `AssistantSettingChanges`): "Changed by your assistant" over the lines. Hidden
 * when [changes] is empty. [currentServerId] labels a change made in another server.
 */
@Composable
internal fun AssistantSettingChangesSection(
    changes: List<AssistantSettingChange>,
    currentServerId: String?,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext? = null,
) {
    if (changes.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag("assistant-setting-changes")) {
        // Web `mb-2 text-subhead font-semibold text-foreground`.
        RaftText(
            text = stringResource(Res.string.assistant_changes_title),
            style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        AssistantSettingChangesList(changes, currentServerId, timeContext = timeContext)
    }
}

/** The lines of [AssistantSettingChangesSection], one per change (web `AssistantSettingChangesList`). */
@Composable
internal fun AssistantSettingChangesList(
    changes: List<AssistantSettingChange>,
    currentServerId: String?,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext? = null,
) {
    // Web `stack-y-1`.
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        changes.forEach { change ->
            val line = assistantSettingChangeLine(change, timeContext)
            val server =
                if (change.inOtherServer(currentServerId)) {
                    val name = change.serverName?.takeIf { it.isNotEmpty() }
                    if (name != null) {
                        stringResource(Res.string.assistant_changes_in_server, name)
                    } else {
                        stringResource(Res.string.assistant_changes_another_server)
                    }
                } else {
                    null
                }
            RaftText(
                // Web `<span className="ms-1">` after the line.
                text = if (server != null) "$line $server" else line,
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
                modifier = Modifier.fillMaxWidth().testTag("assistant-setting-change"),
            )
        }
    }
}

/** One change as a sentence (web `lineOf`). */
@Composable
internal fun assistantSettingChangeLine(change: AssistantSettingChange, timeContext: RaftTimeContext? = null): String {
    val assistant = change.assistantName?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.assistant_changes_assistant_fallback)
    val time = assistantSettingChangeTime(change.createdAt, timeContext)
    val target = assistantSettingChangeTarget(change)
    val nextFlag = (change.nextValue as? AssistantSettingValue.Flag)?.value == true
    val nextText = (change.nextValue as? AssistantSettingValue.Text)?.value
    return when (change.setting) {
        AssistantChangedSetting.ConversationMute ->
            stringResource(
                if (nextFlag) Res.string.assistant_changes_action_muted else Res.string.assistant_changes_action_unmuted,
                assistant,
                target,
                time,
            )
        AssistantChangedSetting.SidebarMove -> {
            // Where it went: "top", or "after #general" (named as they saw it then).
            val next = nextText.orEmpty()
            val where =
                if (next.startsWith("after ")) {
                    stringResource(Res.string.assistant_changes_where_after, next.removePrefix("after "))
                } else {
                    stringResource(Res.string.assistant_changes_where_top)
                }
            stringResource(Res.string.assistant_changes_action_moved, assistant, target, where, time)
        }
        AssistantChangedSetting.SidebarArrange -> stringResource(Res.string.assistant_changes_action_arranged, assistant, time)
        AssistantChangedSetting.AgentMove ->
            stringResource(
                Res.string.assistant_changes_action_agent_moved,
                assistant,
                change.targetRef?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.assistant_changes_target_gone_agent),
                nextText.orEmpty(),
                time,
            )
        AssistantChangedSetting.MessageSave ->
            stringResource(
                if (nextFlag) Res.string.assistant_changes_action_message_saved else Res.string.assistant_changes_action_message_unsaved,
                assistant,
                change.targetRef?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.assistant_changes_target_gone_message),
                time,
            )
        AssistantChangedSetting.SidebarPin ->
            stringResource(
                if (nextFlag) Res.string.assistant_changes_action_pinned else Res.string.assistant_changes_action_unpinned,
                assistant,
                target,
                time,
            )
        AssistantChangedSetting.NotificationLevel ->
            stringResource(
                Res.string.assistant_changes_action_level,
                assistant,
                target,
                assistantSettingChangeLevel(change.nextValue),
                assistantSettingChangeLevel(change.previousValue),
                time,
            )
        AssistantChangedSetting.Theme,
        AssistantChangedSetting.ThemeDecor,
        AssistantChangedSetting.DisplayLanguage,
        AssistantChangedSetting.PreferredTimezone ->
            stringResource(
                Res.string.assistant_changes_line,
                assistant,
                stringResource(
                    when (change.setting) {
                        AssistantChangedSetting.Theme -> Res.string.assistant_changes_setting_theme
                        AssistantChangedSetting.ThemeDecor -> Res.string.assistant_changes_setting_theme_decor
                        AssistantChangedSetting.DisplayLanguage -> Res.string.assistant_changes_setting_display_language
                        else -> Res.string.assistant_changes_setting_preferred_timezone
                    }
                ),
                assistantSettingChangeValue(change.setting, change.previousValue),
                assistantSettingChangeValue(change.setting, change.nextValue),
                time,
            )
    }
}

/** The conversation as the owner names it, "this server", or one that's gone (web `targetOf`). */
@Composable
private fun assistantSettingChangeTarget(change: AssistantSettingChange): String {
    val ref = change.targetRef
    return when {
        ref == "server" -> stringResource(Res.string.assistant_changes_target_server)
        ref != null && ref.isNotEmpty() -> ref
        else -> stringResource(Res.string.assistant_changes_target_gone)
    }
}

/** A notification level by its name (web `levelOf`); anything else as it was recorded. */
@Composable
private fun assistantSettingChangeLevel(value: AssistantSettingValue?): String =
    when (value) {
        is AssistantSettingValue.Text ->
            when (value.value) {
                "all" -> stringResource(Res.string.notification_level_all)
                "mentions" -> stringResource(Res.string.notification_level_mentions)
                "none" -> stringResource(Res.string.notification_level_none)
                "default" -> stringResource(Res.string.assistant_changes_level_default)
                else -> value.value
            }
        is AssistantSettingValue.Flag -> value.value.toString()
        null -> ""
    }

/** A setting's value (web `valueOf`): not set, on / off, a theme's name, a language's own name, else as recorded. */
@Composable
private fun assistantSettingChangeValue(setting: AssistantChangedSetting, value: AssistantSettingValue?): String =
    when (value) {
        null -> stringResource(Res.string.assistant_changes_not_set)
        is AssistantSettingValue.Flag ->
            stringResource(if (value.value) Res.string.assistant_changes_on else Res.string.assistant_changes_off)
        is AssistantSettingValue.Text -> {
            val text = value.value
            val theme = if (setting == AssistantChangedSetting.Theme) RaftThemeId.entries.firstOrNull { it.wire == text } else null
            val locale =
                if (setting == AssistantChangedSetting.DisplayLanguage) AppLocale.entries.firstOrNull { it.webDisplayLocale == text } else null
            when {
                text.isEmpty() -> stringResource(Res.string.assistant_changes_not_set)
                theme != null -> stringResource(theme.settingsDisplayName())
                locale != null -> locale.nativeLabel
                else -> text
            }
        }
    }

/**
 * When it changed, medium date and time ("Nov 17, 2026, 09:14", web `formatDate` with
 * `dateStyle: "medium", timeStyle: "short"`): in the page's time context, else the device's zone;
 * the recorded value when it is not a timestamp.
 */
@Composable
private fun assistantSettingChangeTime(createdAt: String, timeContext: RaftTimeContext?): String =
    raftLocalizedMediumDateTime(createdAt, timeContext)
