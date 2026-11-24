package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.components.rememberRaftDesignSystemEditor
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.RaftFieldDescription
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `agent:create`'s `suggestedEnvVars` (botiverse/slock #16341):
 * - shared `actionCardSuggestedEnvVars.ts` (`isConfigDirectoryEnvVarName`)
 * - web `features/agent/proposedEnvVars.ts` (`proposedEnvVarEntries`, `proposedEnvVarsForSubmit`)
 * - web `features/agent/ProposedEnvVarsField.tsx` (`ProposedEnvVarsField`, `ProposedConfigDirectoryRow`)
 */

internal const val CreateAgentProposedEnvVarsTestTag = "create-agent-proposed-env-vars"
internal const val CreateAgentProposedConfigDirTestTag = "create-agent-proposed-config-dir"

/** Runtime config directories: allowed on a card, shown unticked with a warning in the dialog. */
private val ConfigDirectoryEnvVarNames: Set<String> =
    setOf("CODEX_HOME", "CLAUDE_CONFIG_DIR", "PI_CODING_AGENT_DIR", "XDG_CONFIG_HOME")

/**
 * Whether a proposed name points the new agent at a config directory, whose configured MCP servers
 * and hooks run as commands: the dialog leaves it unticked until the person ticks it (slock #16341).
 */
internal fun isConfigDirectoryEnvVarName(name: String): Boolean =
    name.uppercase() in ConfigDirectoryEnvVarNames

/** A proposed config directory (`CODEX_HOME` and the like): sent only once the person ticks it. */
internal data class ProposedConfigDirectory(
    val key: String,
    val value: String,
    val ticked: Boolean = false,
)

/** A card's proposed variables as the Create agent dialog edits them. */
internal data class ProposedEnvVars(
    /** Ordinary variables: sent as they stand unless the person removes them. */
    val rows: List<Pair<String, String>> = emptyList(),
    /** Config directories: shown unticked with a warning. */
    val configDirectories: List<ProposedConfigDirectory> = emptyList(),
)

/** Splits a card's `suggestedEnvVars` into ordinary editable rows and unticked config directories. */
internal fun proposedEnvVarEntries(envVars: Map<String, String>?): ProposedEnvVars {
    if (envVars.isNullOrEmpty()) return ProposedEnvVars()
    val rows = mutableListOf<Pair<String, String>>()
    val configDirectories = mutableListOf<ProposedConfigDirectory>()
    for ((key, value) in envVars) {
        if (isConfigDirectoryEnvVarName(key)) {
            configDirectories += ProposedConfigDirectory(key = key, value = value, ticked = false)
        } else {
            rows += key to value
        }
    }
    return ProposedEnvVars(rows = rows, configDirectories = configDirectories)
}

/**
 * The create request's `formValues.envVars`: the proposed rows the person kept (blank names
 * dropped) and the config directories they ticked, under what they typed into the runtime form's
 * own Environment variables field, which wins on the same name.
 */
internal fun proposedEnvVarsForSubmit(
    proposal: ProposedEnvVars,
    formEnvVars: JsonElement?,
): JsonObject {
    val merged = linkedMapOf<String, String>()
    for ((rawKey, value) in proposal.rows) {
        val key = rawKey.trim()
        if (key.isNotEmpty()) merged[key] = value
    }
    for (directory in proposal.configDirectories) {
        if (directory.ticked) merged[directory.key] = directory.value
    }
    if (formEnvVars is JsonObject) {
        for ((key, element) in formEnvVars) {
            val primitive = element as? JsonPrimitive ?: continue
            if (primitive.isString) merged[key] = primitive.content
        }
    }
    return JsonObject(merged.mapValues { (_, v) -> JsonPrimitive(v) })
}

/**
 * A Create agent card's `suggestedEnvVars`, shown open in the dialog (never folded under Advanced)
 * with every name and value, labelled as the sending agent's proposal. Ordinary rows reuse
 * [EnvVarsEditor]; each config directory gets its own unticked row with a warning naming the
 * proposer.
 */
@Composable
internal fun CreateAgentProposedEnvVarsField(
    proposal: ProposedEnvVars,
    onChange: (ProposedEnvVars) -> Unit,
    proposer: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val cleanProposer = proposer?.trim()?.takeIf { it.isNotEmpty() }
    val hintText =
        if (cleanProposer != null) {
            stringResource(Res.string.agent_create_proposed_env_vars_by, cleanProposer)
        } else {
            stringResource(Res.string.agent_create_proposed_env_vars)
        }
    Column(
        modifier = modifier.fillMaxWidth().testTag(CreateAgentProposedEnvVarsTestTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EnvVarsEditor(
            entries = proposal.rows,
            onChange = { nextRows -> onChange(proposal.copy(rows = nextRows)) },
            enabled = enabled,
            label = stringResource(Res.string.agent_create_label_environment_variables),
            hint = {
                RaftFieldDescription(text = hintText, enabled = enabled)
            },
        )
        proposal.configDirectories.forEachIndexed { index, directory ->
            ProposedConfigDirectoryRow(
                directory = directory,
                proposer = cleanProposer,
                enabled = enabled,
                onChange = { updated ->
                    val next = proposal.configDirectories.toMutableList()
                    next[index] = updated
                    onChange(proposal.copy(configDirectories = next))
                },
            )
        }
    }
}

@Composable
private fun ProposedConfigDirectoryRow(
    directory: ProposedConfigDirectory,
    proposer: String?,
    enabled: Boolean,
    onChange: (ProposedConfigDirectory) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val valueLabel = stringResource(Res.string.agent_create_proposed_config_dir_value, directory.key)
    val warningText =
        if (proposer != null) {
            stringResource(Res.string.agent_create_proposed_config_dir_by, proposer)
        } else {
            stringResource(Res.string.agent_create_proposed_config_dir)
        }
    val editor =
        rememberRaftDesignSystemEditor(
            keyboardOptions = KeyboardOptions.Default,
            keyboardActions = KeyboardActions.Default,
            keyboardHeightChange = {},
            focusRequester = remember { FocusRequester() },
            autoFocus = false,
            onFocus = {},
            onBlur = {},
        )
    Row(
        modifier = Modifier.fillMaxWidth().testTag(CreateAgentProposedConfigDirTestTag),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RaftCheckbox(
            checked = directory.ticked,
            onCheckedChange = { ticked -> onChange(directory.copy(ticked = ticked)) },
            enabled = enabled,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RaftText(
                text = directory.key,
                style = typography.monoMeta.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) colors.foreground else colors.foregroundDisabled,
                modifier =
                    Modifier.raftClickable(
                        enabled = enabled,
                        role = Role.Checkbox,
                        shape = RoundedCornerShape(RaftRadius.Control),
                        onClick = { onChange(directory.copy(ticked = !directory.ticked)) },
                    ),
            )
            RaftTextField(
                value = directory.value,
                onValueChange = { nextValue -> onChange(directory.copy(value = nextValue)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(editor.modifier)
                        .semantics { contentDescription = valueLabel },
                enabled = enabled,
                keyboardOptions = editor.keyboardOptions,
            )
            RaftFieldDescription(text = warningText, enabled = enabled)
        }
    }
}
