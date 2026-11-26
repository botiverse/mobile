package build.raft.app.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.RaftTraceIdNote
import build.raft.app.data.AGENT_VOICE_FEATURE_FLAG_KEY
import build.raft.app.data.AgentVoiceQuestion
import build.raft.app.data.AgentVoiceSettings
import build.raft.app.data.AgentVoiceSettingsDataSource
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.raftLocalizedShortDateTime
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSaveStatusState
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Raft Agent Voice section in the Agent Profile tab (`#wg-mobile-sync:#95`), mirroring web
// `packages/web/src/features/agent/AgentVoiceSection.tsx`:
// - Gated on `agent_voice_v1` for the current server and `canManage` (server also enforces via 404).
// - Two immediate-save switches ("Can be asked", "Can be called") with optimistic toggle, per-field
//   rollback, and inline failure + trace ID on error.
// - "Who asked" audit list (at most 10 entries, newest first, showing asker + app/time).

/** Maximum number of recent side-question entries shown in the Voice section (mirrors web `SHOWN_QUESTIONS`). */
internal const val AGENT_VOICE_SHOWN_QUESTIONS: Int = 10

internal enum class AgentVoiceSettingField(val wireName: String) {
    Askable("askable"),
    Callable("callable"),
}

internal class AgentVoiceSectionScope(
    val api: AgentVoiceSettingsDataSource,
    val featureFlags: SharedServerFeatureFlags,
    val serverId: String,
    val canManage: Boolean,
    val timeContext: RaftTimeContext? = null,
)

internal val LocalAgentVoiceSectionScope = compositionLocalOf<AgentVoiceSectionScope?> { null }

/**
 * Stateful host for the Agent Profile's Voice section. Renders nothing when `agent_voice_v1` is off,
 * the viewer cannot manage the agent, or `GET /agents/:id/voice-settings` returns 404.
 */
@Composable
internal fun AgentVoiceSection(
    agentId: String,
    frame: @Composable (content: @Composable ColumnScope.() -> Unit) -> Unit,
) {
    val sectionScope = LocalAgentVoiceSectionScope.current ?: return
    if (!sectionScope.canManage) return
    val serverId = sectionScope.serverId.trim()
    if (serverId.isEmpty()) return
    val enabled = rememberServerFeatureLatched(sectionScope.featureFlags, ServerId(serverId), AGENT_VOICE_FEATURE_FLAG_KEY)
    if (!enabled) return

    var settings by remember(agentId, serverId) { mutableStateOf<AgentVoiceSettings?>(null) }
    var questions by remember(agentId, serverId) { mutableStateOf<List<AgentVoiceQuestion>?>(null) }
    var savingFields by remember(agentId, serverId) { mutableStateOf<Set<AgentVoiceSettingField>>(emptySet()) }
    var failures by remember(agentId, serverId) { mutableStateOf<Map<AgentVoiceSettingField, ScreenError>>(emptyMap()) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(agentId, serverId, sectionScope.api) {
        launch {
            val result = sectionScope.api.readSettings(agentId = agentId, serverId = serverId)
            if (result is DataResult.Content) {
                settings = result.data
            }
        }
        launch {
            val result = sectionScope.api.readQuestions(agentId = agentId, serverId = serverId)
            if (result is DataResult.Content) {
                questions = result.data
            }
        }
    }

    val currentSettings = settings ?: return
    frame {
        MembersAgentVoiceSectionContent(
            settings = currentSettings,
            questions = questions,
            savingFields = savingFields,
            failures = failures,
            timeContext = sectionScope.timeContext,
            onToggleField = { field, checked ->
                if (field !in savingFields) {
                    val before =
                        when (field) {
                            AgentVoiceSettingField.Askable -> currentSettings.askable
                            AgentVoiceSettingField.Callable -> currentSettings.callable
                        }
                    fun applyField(value: Boolean) {
                        settings =
                            settings?.let { current ->
                                when (field) {
                                    AgentVoiceSettingField.Askable -> current.copy(askable = value)
                                    AgentVoiceSettingField.Callable -> current.copy(callable = value)
                                }
                            }
                    }
                    applyField(checked)
                    savingFields = savingFields + field
                    failures = failures - field
                    coroutineScope.launch {
                        try {
                            val result =
                                try {
                                    when (field) {
                                        AgentVoiceSettingField.Askable ->
                                            sectionScope.api.updateSettings(
                                                agentId = agentId,
                                                askable = checked,
                                                serverId = serverId,
                                            )
                                        AgentVoiceSettingField.Callable ->
                                            sectionScope.api.updateSettings(
                                                agentId = agentId,
                                                callable = checked,
                                                serverId = serverId,
                                            )
                                    }
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Throwable) {
                                    DataResult.Error(ScreenError("agent_voice_save_failed"))
                                }
                            when (result) {
                                is DataResult.Content -> {
                                    val savedValue =
                                        when (field) {
                                            AgentVoiceSettingField.Askable -> result.data.askable
                                            AgentVoiceSettingField.Callable -> result.data.callable
                                        }
                                    applyField(savedValue)
                                }
                                is DataResult.Error -> {
                                    applyField(before)
                                    failures = failures + (field to result.error)
                                }
                                is DataResult.NoPermission -> {
                                    applyField(before)
                                    failures =
                                        failures + (
                                            field to
                                                ScreenError(
                                                    code = result.blocker.code,
                                                    retryable = false,
                                                    serverMessage = result.blocker.serverMessage,
                                                )
                                            )
                                }
                                is DataResult.Empty -> {
                                    applyField(before)
                                    failures = failures + (field to ScreenError("agent_voice_save_failed"))
                                }
                            }
                        } finally {
                            savingFields = savingFields - field
                        }
                    }
                }
            },
        )
    }
}

/**
 * Pure presentation for the Agent Profile's Voice section: eyebrow, immediate switches for
 * "Can be asked" and "Can be called", and the "Who asked" recent questions list.
 */
@Composable
internal fun MembersAgentVoiceSectionContent(
    settings: AgentVoiceSettings,
    questions: List<AgentVoiceQuestion>?,
    onToggleField: (AgentVoiceSettingField, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    savingFields: Set<AgentVoiceSettingField> = emptySet(),
    failures: Map<AgentVoiceSettingField, ScreenError> = emptyMap(),
    timeContext: RaftTimeContext? = null,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("agent-voice-section")) {
        RaftSectionEyebrow(
            text = stringResource(Res.string.agent_voice_section),
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RaftListRowGroup(Modifier.fillMaxWidth()) {
                AgentVoiceSwitchRow(
                    field = AgentVoiceSettingField.Askable,
                    checked = settings.askable,
                    title = stringResource(Res.string.agent_voice_askable_title),
                    description = stringResource(Res.string.agent_voice_askable_description),
                    saving = AgentVoiceSettingField.Askable in savingFields,
                    failure = failures[AgentVoiceSettingField.Askable],
                    divider = false,
                    onCheckedChange = { onToggleField(AgentVoiceSettingField.Askable, it) },
                )
                AgentVoiceSwitchRow(
                    field = AgentVoiceSettingField.Callable,
                    checked = settings.callable,
                    title = stringResource(Res.string.agent_voice_callable_title),
                    description = stringResource(Res.string.agent_voice_callable_description),
                    saving = AgentVoiceSettingField.Callable in savingFields,
                    failure = failures[AgentVoiceSettingField.Callable],
                    divider = true,
                    onCheckedChange = { onToggleField(AgentVoiceSettingField.Callable, it) },
                )
            }
            if (questions != null) {
                AgentVoiceQuestionsList(
                    questions = questions,
                    timeContext = timeContext,
                )
            }
        }
    }
}

@Composable
private fun AgentVoiceSwitchRow(
    field: AgentVoiceSettingField,
    checked: Boolean,
    title: String,
    description: String,
    saving: Boolean,
    failure: ScreenError?,
    divider: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val failedFallback = stringResource(Res.string.agent_voice_save_failed)
    val failureText = failure?.serverMessage?.takeIf { it.isNotBlank() } ?: failedFallback
    RaftListRow(
        title = title,
        modifier = Modifier.testTag("agent-voice-${field.wireName}-row"),
        variant = RaftListRowVariant.Plain,
        titleMaxLines = 2,
        description = description,
        wrapDescription = true,
        divider = divider,
        saveStatus = if (failure != null) RaftSaveStatusState.Failed(failureText) else RaftSaveStatusState.Idle,
        trailing = {
            RaftSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag("agent-voice-${field.wireName}"),
                enabled = !saving,
                label = title,
            )
        },
    )
    if (failure != null) {
        RaftTraceIdNote(failure.traceId, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
    }
}

@Composable
private fun AgentVoiceQuestionsList(
    questions: List<AgentVoiceQuestion>,
    timeContext: RaftTimeContext?,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().testTag("agent-voice-questions"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RaftText(
            text = stringResource(Res.string.agent_voice_questions_title),
            style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foregroundMuted,
        )
        if (questions.isEmpty()) {
            RaftText(
                text = stringResource(Res.string.agent_voice_questions_empty),
                style = typography.footnote,
                color = colors.foregroundMuted,
            )
        } else {
            val formerMemberLabel = stringResource(Res.string.agent_voice_questions_former_member)
            val visible = questions.take(AGENT_VOICE_SHOWN_QUESTIONS)
            RaftListRowGroup(Modifier.fillMaxWidth()) {
                visible.forEachIndexed { index, question ->
                    val timeLabel = raftLocalizedShortDateTime(question.at, timeContext)
                    val metaLabel =
                        question.app?.let { app ->
                            stringResource(Res.string.agent_voice_questions_meta, app.name, timeLabel)
                        } ?: timeLabel
                    RaftListRow(
                        title = question.asker?.name ?: formerMemberLabel,
                        description = metaLabel,
                        variant = RaftListRowVariant.Plain,
                        divider = index > 0,
                        modifier = Modifier.testTag("agent-voice-question-row-$index"),
                    )
                }
            }
        }
    }
}
