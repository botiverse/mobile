package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentMigrationLeaveOutCandidate
import build.raft.app.data.AgentMigrationLeaveOutProblem
import build.raft.app.data.AgentMigrationTypedLeaveOutResult
import build.raft.app.data.readTypedAgentMigrationLeaveOutPath
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftChoiceListSearch
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldDescriptionPosition
import build.raft.designsystem.components.RaftMultiChoiceList
import build.raft.designsystem.components.RaftTextField

internal object AgentMigrationLeaveOutTestTags {
    const val Root = "agent-migration-leave-out"
    const val Input = "agent-migration-leave-out-input"
    const val AddButton = "agent-migration-leave-out-add"
}

/**
 * Workspace paths to leave out when retrying a computer migration that failed for being over the
 * size limit (web `LeaveOutPicker.tsx`, botiverse/slock #17048):
 * - Measured `topEntries` candidates from the failed bundle render as checkboxes with their
 *   pre-compression size.
 * - Custom paths typed below are validated via [readTypedAgentMigrationLeaveOutPath], added to the
 *   checkbox list (checked), and stay visible when unchecked (and across Cloud target switches
 *   when [typed] is hoisted by the caller).
 * - `MEMORY.md`, `notes/`, `.raft/`, and ignore files are rejected with an inline field error.
 */
@Composable
internal fun AgentMigrationLeaveOutPicker(
    candidates: List<AgentMigrationLeaveOutCandidate>,
    sourceName: String,
    value: List<String>,
    onValueChange: (List<String>) -> Unit,
    disabled: Boolean,
    modifier: Modifier = Modifier,
    typed: List<String> = emptyList(),
    onTypedChange: ((List<String>) -> Unit)? = null,
) {
    var query by remember(candidates) { mutableStateOf("") }
    var draft by remember(candidates) { mutableStateOf("") }
    var localTyped by remember(candidates) {
        mutableStateOf(value.filter { path -> candidates.none { it.path == path } })
    }
    var problem by remember(candidates) { mutableStateOf<AgentMigrationLeaveOutProblem?>(null) }

    val candidatePaths = remember(candidates) { candidates.map { it.path }.toSet() }
    val effectiveTyped =
        remember(typed, localTyped, value, candidatePaths, onTypedChange) {
            val base = if (onTypedChange != null) typed else localTyped
            (base + value).distinct().filter { it !in candidatePaths }
        }
    val selectedSet = remember(value) { value.toSet() }
    val sizeItems =
        candidates.map { candidate ->
            RaftChoiceListItem(
                value = candidate.path,
                label = candidate.path,
                searchText = candidate.path,
                descriptions =
                    listOf(
                        AnnotatedString(
                            stringResource(
                                Res.string.agent_migration_leave_out_size,
                                formatAgentMigrationSize(candidate.sizeBytes),
                            ),
                        ),
                    ),
            )
        }
    val typedItems =
        effectiveTyped.map { path ->
            RaftChoiceListItem(
                value = path,
                label = path,
                searchText = path,
            )
        }
    val items = sizeItems + typedItems

    fun addTypedPath() {
        if (disabled || draft.isBlank()) return
        when (val result = readTypedAgentMigrationLeaveOutPath(draft)) {
            is AgentMigrationTypedLeaveOutResult.Invalid -> {
                problem = result.problem
            }
            is AgentMigrationTypedLeaveOutResult.Valid -> {
                val path = result.path
                problem = null
                if (path !in candidatePaths && path !in effectiveTyped) {
                    val nextTyped = effectiveTyped + path
                    if (onTypedChange != null) {
                        onTypedChange(nextTyped)
                    } else {
                        localTyped = nextTyped
                    }
                }
                if (path !in value) {
                    onValueChange(value + path)
                }
                draft = ""
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag(AgentMigrationLeaveOutTestTags.Root),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RaftField(
            label = stringResource(Res.string.agent_migration_leave_out_label),
            description = stringResource(Res.string.agent_migration_leave_out_description, sourceName),
            descriptionPosition = RaftFieldDescriptionPosition.BelowLabel,
            enabled = !disabled,
        ) {
            if (items.isNotEmpty()) {
                RaftMultiChoiceList(
                    items = items,
                    values = selectedSet,
                    onValuesChange = { nextSet ->
                        val ordered = items.map { it.value }.filter { it in nextSet }
                        val extra = nextSet.filter { it !in ordered }
                        onValueChange(ordered + extra)
                    },
                    query = query,
                    onQueryChange = { query = it },
                    searchPlaceholder = stringResource(Res.string.agent_migration_leave_out_search),
                    clearSearchLabel = stringResource(Res.string.agent_migration_target_clear_search),
                    noMatchLabel = stringResource(Res.string.agent_migration_target_no_matches),
                    emptyLabel = "",
                    enabled = !disabled,
                    search = RaftChoiceListSearch.Auto,
                    lazy = false,
                )
            }
        }
        val errorText =
            when (problem) {
                AgentMigrationLeaveOutProblem.AlwaysMoved ->
                    stringResource(Res.string.agent_migration_leave_out_always_moved)
                AgentMigrationLeaveOutProblem.Invalid ->
                    stringResource(Res.string.agent_migration_leave_out_invalid)
                null -> null
            }
        RaftField(
            label = stringResource(Res.string.agent_migration_leave_out_typed_label),
            error = errorText,
            enabled = !disabled,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftTextField(
                    value = draft,
                    onValueChange = { next ->
                        draft = next
                        if (problem != null) problem = null
                    },
                    modifier = Modifier.weight(1f).testTag(AgentMigrationLeaveOutTestTags.Input),
                    placeholder = stringResource(Res.string.agent_migration_leave_out_typed_placeholder),
                    enabled = !disabled,
                    invalid = problem != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { addTypedPath() }),
                )
                RaftButton(
                    text = stringResource(Res.string.agent_migration_leave_out_add),
                    onClick = ::addTypedPath,
                    modifier = Modifier.testTag(AgentMigrationLeaveOutTestTags.AddButton),
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg,
                    enabled = !disabled && draft.trim().isNotEmpty(),
                )
            }
        }
    }
}
