package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.AgentSummary
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.model.MemberKind
import build.raft.app.resources.*
import build.raft.app.thread.key
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftMenuLabel
import build.raft.designsystem.components.RaftMenuSearchField
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

private const val COPY_ENV_VARS_NAMES_SENTINEL = "\u0001"

/**
 * Mirrors web `useSameComputerEnvVarCopySource` (botiverse/slock #16606, `#proj-runtime:#99`):
 * loads live agents for [serverId] from [formStore] and derives the same-Computer copy source for
 * Advanced → Environment Variables. Kept outside [RuntimeFormV2Fields] so the field renderer stays
 * store-free for tests and fixtures.
 */
@Composable
internal fun rememberSameComputerEnvVarCopySource(
    formStore: SharedCreateAgentFormStore?,
    serverId: String?,
    machineId: String?,
    machineName: String?,
    targetRuntimeId: String,
    excludeAgentId: String? = null,
    enabled: Boolean = true,
): RuntimeEnvVarsCopySource? {
    val cleanServerId = serverId?.trim().orEmpty()
    val cleanMachineId = machineId?.trim()?.takeIf { it.isNotEmpty() }
    val fallbackComputerName = stringResource(Res.string.settings_computers_runtime_auth_this_computer)
    val resolvedComputerName = machineName?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackComputerName
    var agents by remember(formStore, cleanServerId) { mutableStateOf<List<AgentSummary>>(emptyList()) }
    var viewerUserId by remember(formStore, cleanServerId) { mutableStateOf<String?>(null) }
    var viewerCanEditAgents by remember(formStore, cleanServerId) { mutableStateOf(false) }

    LaunchedEffect(formStore, cleanServerId, enabled, cleanMachineId) {
        if (!enabled || formStore == null || cleanServerId.isBlank() || cleanMachineId == null) return@LaunchedEffect
        viewerUserId = formStore.currentUserId()
        viewerCanEditAgents = formStore.canEditAgents(cleanServerId)
        agents = formStore.loadCopyableAgents(cleanServerId)
    }

    if (!enabled || cleanMachineId == null) return null
    val cleanRuntime = targetRuntimeId.trim()
    val candidates =
        remember(agents, cleanMachineId, cleanRuntime, excludeAgentId, viewerUserId, viewerCanEditAgents) {
            copyableSameComputerEnvVarAgents(
                agents = agents,
                machineId = cleanMachineId,
                targetRuntimeId = cleanRuntime,
                excludeAgentId = excludeAgentId,
                currentUserId = viewerUserId,
                canEditAgents = viewerCanEditAgents,
            )
        }
    return remember(candidates, resolvedComputerName, cleanMachineId, cleanRuntime) {
        RuntimeEnvVarsCopySource(
            candidates = candidates,
            computerName = resolvedComputerName,
            resetKey = "$cleanMachineId:$cleanRuntime",
        )
    }
}

/**
 * Mirrors web `CopyEnvVarsFromAgentMenu` (botiverse/slock #16606, `#proj-runtime:#99`):
 * "Copy from an agent" ghost md button with 16dp Copy icon beside "+ Add env var", opening a
 * 288dp end-aligned menu with Computer heading, 20dp agent avatar, and two-line summary with
 * monospace variable names. Switches to a searchable menu past 8 candidates. Renders nothing when
 * [candidates] is empty.
 */
@Composable
internal fun CopyEnvVarsFromAgentMenu(
    candidates: List<CopyableAgentEnvVars>,
    computerName: String,
    enabled: Boolean,
    onPick: (CopyableAgentEnvVars) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchable = candidates.size > COPY_ENV_VARS_SEARCHABLE_THRESHOLD
    val heading = stringResource(Res.string.agent_runtime_config_copy_from_agent_heading, computerName)
    val menuAria = stringResource(Res.string.agent_runtime_config_copy_from_agent_aria)
    val searchPlaceholder = stringResource(Res.string.agent_runtime_config_copy_from_agent_search_placeholder)
    val noMatchLabel = stringResource(Res.string.agent_runtime_config_copy_from_agent_no_match)

    Box(modifier = modifier) {
        RaftButton(
            text = stringResource(Res.string.agent_runtime_config_copy_from_agent),
            onClick = { expanded = true },
            modifier = Modifier.testTag("env-vars-copy-from-agent"),
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Md,
            enabled = enabled,
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.Copy, size = 16.dp) },
        )
        RaftDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = {
                expanded = false
                query = ""
            },
            modifier =
                Modifier
                    .testTag("env-vars-copy-menu")
                    .semantics { contentDescription = menuAria },
            width = 288.dp,
            offset = 4.dp,
            alignEnd = true,
            typing = searchable && query.isNotEmpty(),
            header =
                if (searchable) {
                    {
                        Column(Modifier.fillMaxWidth()) {
                            RaftMenuLabel(heading)
                            RaftMenuSeparator()
                            RaftMenuSearchField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = searchPlaceholder,
                                modifier = Modifier.testTag("env-vars-copy-search"),
                            )
                        }
                    }
                } else {
                    null
                },
        ) {
            if (!searchable) {
                RaftMenuLabel(heading)
            }
            val visible = if (searchable) filterCopyableAgentEnvVars(candidates, query) else candidates
            if (visible.isEmpty()) {
                RaftText(
                    text = noMatchLabel,
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundHint,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            } else {
                visible.forEach { candidate ->
                    CopyEnvVarsCandidateRow(
                        candidate = candidate,
                        onPick = {
                            expanded = false
                            query = ""
                            onPick(candidate)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyEnvVarsCandidateRow(
    candidate: CopyableAgentEnvVars,
    onPick: () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val summary = copyEnvVarsCandidateSummaryText(candidate.entries)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("env-vars-copy-agent-${candidate.agentId}")
                .raftClickable(
                    onClick = onPick,
                    shape = RoundedCornerShape(RaftRadius.Chip),
                    tier = RaftHoverTier.Highlight,
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftAppAvatar(
            kind = raftAvatarKind(MemberKind.Agent),
            avatarWireValue = candidate.avatarUrl,
            label = candidate.name,
            avatarSize = 20.dp,
            cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, candidate.agentId),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RaftText(
                text = candidate.name,
                style = typography.callout.copy(fontWeight = FontWeight.Medium),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RaftText(
                text = summary,
                style = typography.footnote,
                color = colors.foregroundMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun copyEnvVarsCandidateSummaryText(entries: List<CopyableEnvVarEntry>): AnnotatedString {
    val count = entries.size
    val template =
        stringResource(
            if (count == 1) Res.string.agent_runtime_config_copy_from_agent_summary_one
            else Res.string.agent_runtime_config_copy_from_agent_summary_other,
            count,
            COPY_ENV_VARS_NAMES_SENTINEL,
        )
    return formatWithMonoToken(template, entries.joinToString(", ") { it.key })
}

@Composable
internal fun copyEnvVarsFeedbackText(
    agentName: String,
    addedCount: Int,
    keptKeys: List<String>,
): AnnotatedString {
    val names = keptKeys.joinToString(", ")
    if (addedCount > 0) {
        if (keptKeys.isNotEmpty()) {
            val template =
                stringResource(
                    if (addedCount == 1) Res.string.agent_runtime_config_copy_from_agent_copied_kept_one
                    else Res.string.agent_runtime_config_copy_from_agent_copied_kept_other,
                    addedCount,
                    agentName,
                    COPY_ENV_VARS_NAMES_SENTINEL,
                )
            return formatWithMonoToken(template, names)
        }
        val plain =
            stringResource(
                if (addedCount == 1) Res.string.agent_runtime_config_copy_from_agent_copied_one
                else Res.string.agent_runtime_config_copy_from_agent_copied_other,
                addedCount,
                agentName,
            )
        return AnnotatedString(plain)
    }
    if (keptKeys.isNotEmpty()) {
        val template =
            stringResource(
                Res.string.agent_runtime_config_copy_from_agent_nothing_new_kept,
                agentName,
                COPY_ENV_VARS_NAMES_SENTINEL,
            )
        return formatWithMonoToken(template, names)
    }
    return AnnotatedString(stringResource(Res.string.agent_runtime_config_copy_from_agent_nothing_new, agentName))
}

@Composable
private fun formatWithMonoToken(template: String, monoText: String): AnnotatedString {
    val monoStyle = SpanStyle(fontFamily = RaftTheme.typography.monoMeta.fontFamily)
    val index = template.indexOf(COPY_ENV_VARS_NAMES_SENTINEL)
    if (index < 0) return AnnotatedString(template)
    return buildAnnotatedString {
        append(template.substring(0, index))
        withStyle(monoStyle) { append(monoText) }
        append(template.substring(index + COPY_ENV_VARS_NAMES_SENTINEL.length))
    }
}
