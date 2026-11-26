package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.data.ServerRuntimeCatalog
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerWorkerRuntimesState
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSaveStatusState
import build.raft.designsystem.components.RaftSection
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * Worker v1 step 2 (botiverse/slock #17011 + #17012, web MachineWorkerRuntimesSection.tsx):
 * Computer Detail > Runtimes for Workers.
 *
 * - Shown only while `worker_v1` is on for this server AND the server sent `workerRuntimesTurnedOn`
 *   (non-null; absent on older servers keeps the section hidden).
 * - Lists the distinct union of reported `computer.runtimes` first, then any runtime still turned
 *   on that the Computer no longer reports.
 * - Each switch applies immediately (optimistic), rolls back on server refusal, and renders the
 *   refusal sentence (or localized fallback) under its row until the next change.
 * - Footer names who can change it (owner/admin vs Public any member) and offers "Make it mine"
 *   for a Public Computer the viewer connected.
 */

internal const val RAFT_COMPUTER_WORKER_RUNTIMES_TAG = "computer-worker-runtimes"
internal const val RAFT_COMPUTER_WORKER_RUNTIMES_NONE_TAG = "computer-worker-runtimes-none"
internal const val RAFT_COMPUTER_WORKER_RUNTIMES_CHANGE_BY_TAG = "computer-worker-runtimes-change-by"
internal const val RAFT_COMPUTER_WORKER_RUNTIMES_MAKE_MINE_TAG = "computer-worker-runtimes-make-mine"

internal fun raftWorkerRuntimeRowTag(runtime: String): String = "worker-runtime-row-$runtime"

internal fun raftWorkerRuntimeSwitchTag(runtime: String): String = "worker-runtime-switch-$runtime"

/**
 * Web `workerRuntimeRows` (`packages/web/src/features/computer/workerRuntimeRows.ts`):
 * the runtimes the Computer reports first, then any runtime still turned on for Workers that the
 * Computer no longer reports, in that order (de-duplicated).
 */
internal fun raftComputerWorkerRuntimeRows(
    reportedRuntimes: List<String>,
    turnedOnRuntimes: List<String>?,
): List<String> {
    val reported = reportedRuntimes.map(String::trim).filter(String::isNotEmpty)
    val turnedOn = turnedOnRuntimes.orEmpty().map(String::trim).filter(String::isNotEmpty)
    return (reported + turnedOn.filter { it !in reported }).distinct()
}

/** Shown only while `worker_v1` is enabled for the server and the server sent `workerRuntimesTurnedOn`. */
internal fun shouldShowComputerWorkerRuntimesSection(
    workerV1Enabled: Boolean,
    computer: SettingsComputerSnapshot,
): Boolean = workerV1Enabled && computer.workerRuntimesTurnedOn != null

@Composable
internal fun RaftComputerWorkerRuntimesSection(
    computer: SettingsComputerSnapshot,
    canClaim: Boolean,
    claiming: Boolean,
    runtimeCatalog: ServerRuntimeCatalog,
    workerRuntimesState: SettingsComputerWorkerRuntimesState,
    onToggleRuntime: (runtime: String, enabled: Boolean) -> Unit,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val turnedOn = computer.workerRuntimesTurnedOn ?: return
    val rows = remember(computer.runtimes, turnedOn) { raftComputerWorkerRuntimeRows(computer.runtimes, turnedOn) }
    val canChange = computer.canChangeWorkerRuntimes
    val ownerName = computer.owner?.name?.trim()?.takeIf { it.isNotEmpty() }
    val publicComputer = computer.workerRuntimesChangeBy != "owner_or_admin" || ownerName == null
    val changeByText =
        if (publicComputer) {
            stringResource(Res.string.settings_computers_worker_runtimes_change_by_any_member)
        } else {
            stringResource(Res.string.settings_computers_worker_runtimes_change_by_owner, ownerName)
        }

    RaftSection(
        title = stringResource(Res.string.settings_computers_worker_runtimes_title),
        modifier = modifier.testTag(RAFT_COMPUTER_WORKER_RUNTIMES_TAG),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RaftText(
                text = stringResource(Res.string.settings_computers_worker_runtimes_own_runtime),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundHint,
            )
            RaftListRowGroup(Modifier.fillMaxWidth()) {
                if (rows.isNotEmpty()) {
                    rows.forEachIndexed { index, runtime ->
                        val runtimeLabel =
                            runtimeCatalog.entry(runtime)?.displayName?.takeIf { it.isNotBlank() }
                                ?: raftAgentRuntimeDisplayName(runtime)
                        val checked = runtime in turnedOn
                        val saving = workerRuntimesState.isSaving(computer.id, runtime)
                        val error = workerRuntimesState.errorFor(computer.id, runtime)
                        val fallbackError =
                            stringResource(Res.string.settings_computers_worker_runtimes_save_failed, runtimeLabel)
                        val errorText = error?.serverMessage?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackError
                        RaftListRow(
                            title = runtimeLabel,
                            modifier = Modifier.testTag(raftWorkerRuntimeRowTag(runtime)),
                            variant = RaftListRowVariant.Plain,
                            titleMaxLines = 2,
                            divider = index > 0,
                            saveStatus =
                                if (error != null) {
                                    RaftSaveStatusState.Failed(errorText)
                                } else {
                                    RaftSaveStatusState.Idle
                                },
                            trailing = {
                                RaftSwitch(
                                    checked = checked,
                                    onCheckedChange = { next -> onToggleRuntime(runtime, next) },
                                    modifier = Modifier.testTag(raftWorkerRuntimeSwitchTag(runtime)),
                                    enabled = canChange && !saving,
                                    label = runtimeLabel,
                                )
                            },
                        )
                        if (error != null) {
                            RaftTraceIdNote(
                                error.traceId,
                                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            )
                        }
                    }
                } else {
                    WorkerRuntimesNoteRow(
                        text = stringResource(Res.string.settings_computers_worker_runtimes_none),
                        divider = false,
                        modifier = Modifier.testTag(RAFT_COMPUTER_WORKER_RUNTIMES_NONE_TAG),
                    )
                }
                WorkerRuntimesNoteRow(
                    text = changeByText,
                    divider = true,
                    textModifier = Modifier.testTag(RAFT_COMPUTER_WORKER_RUNTIMES_CHANGE_BY_TAG),
                    trailing =
                        if (publicComputer && canClaim) {
                            {
                                RaftButton(
                                    text = stringResource(Res.string.settings_computers_owner_make_mine),
                                    onClick = onClaim,
                                    variant = RaftButtonVariant.Default,
                                    size = RaftButtonSize.Md,
                                    loading = claiming,
                                    modifier = Modifier.testTag(RAFT_COMPUTER_WORKER_RUNTIMES_MAKE_MINE_TAG),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun WorkerRuntimesNoteRow(
    text: String,
    divider: Boolean,
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        if (divider) {
            RaftSeparator(Modifier.padding(start = 16.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftText(
                text = text,
                modifier = textModifier.weight(1f),
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundMuted,
            )
            trailing?.invoke()
        }
    }
}
