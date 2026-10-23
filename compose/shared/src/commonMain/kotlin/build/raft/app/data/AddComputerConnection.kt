package build.raft.app.data

/*
 * Which machine row the Add Computer dialog is connecting: a port of the web's
 * `resolveAddMachineConnectedMachine` (botiverse/slock staging,
 * packages/web/src/utils/addMachineConnection.ts). The `raft-computer setup` command creates
 * (or resumes) its own machine row rather than coming online on the placeholder the dialog
 * registered, so the dialog compares the rows against the ones that existed when it showed
 * the command.
 */

/** One machine row as it stood when the dialog showed the connect command. */
data class AddComputerBaselineEntry(
    val id: String,
    val online: Boolean,
    val isComputer: Boolean,
    val attachedByCurrentUser: Boolean,
)

internal fun SettingsComputerSnapshot.toAddComputerBaselineEntry(): AddComputerBaselineEntry =
    AddComputerBaselineEntry(id = id, online = online, isComputer = isComputer, attachedByCurrentUser = attachedByCurrentUser)

/** The row that connected; [requiresConfirmation] when it may not be the computer just set up. */
internal data class AddComputerConnectionMatch(
    val machine: SettingsComputerSnapshot,
    val requiresConfirmation: Boolean,
)

private fun SettingsComputerSnapshot.isEligibleComputer(): Boolean = isComputer && online && attachedByCurrentUser

private fun AddComputerBaselineEntry.wasEligibleOnline(): Boolean = isComputer && attachedByCurrentUser && online

/**
 * The registered placeholder once it is online; otherwise a Computer this user attached that is
 * online now but was not (or did not exist) in [baseline]. A single fresh Computer, with none of
 * the user's Computers online before, is unambiguous; anything else asks the user to confirm.
 */
internal fun resolveAddComputerConnectedMachine(
    machines: List<SettingsComputerSnapshot>,
    pendingMachineId: String?,
    baseline: List<AddComputerBaselineEntry>,
): AddComputerConnectionMatch? {
    val pending = pendingMachineId?.takeIf { it.isNotBlank() }?.let { id -> machines.firstOrNull { it.id == id } }
    if (pending?.online == true) return AddComputerConnectionMatch(pending, requiresConfirmation = false)

    val baselineById = baseline.associateBy { it.id }
    val newOrResumed =
        machines.filter { machine ->
            machine.isEligibleComputer() && baselineById[machine.id]?.online != true
        }
    val selected = newOrResumed.firstOrNull() ?: return null
    val unambiguous =
        baseline.none { it.wasEligibleOnline() } && newOrResumed.size == 1 && baselineById[selected.id] == null
    return AddComputerConnectionMatch(selected, requiresConfirmation = !unambiguous)
}
