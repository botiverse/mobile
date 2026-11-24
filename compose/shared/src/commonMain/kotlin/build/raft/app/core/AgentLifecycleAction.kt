package build.raft.app

import build.raft.app.data.AgentResetMode

internal enum class MembersAgentLifecycleAction(
    val actionKey: String,
    val label: String,
    val successLabel: String,
    val destructive: Boolean = false
) {
    Start("start", "Start", "Started"),
    Stop("stop", "Stop", "Stopped"),
    Restart("restart", "Restart", "Restarted"),
    ResetSession("reset_session", "Reset session", "Session reset"),
    ResetFull("reset_full", "Full reset", "Full reset"),
    // task #515: the action is the web "archive" (former delete). Enum labels are
    // non-localized fallbacks; user-visible text comes from MR strings.
    Delete("delete", "Archive Agent", "Archived", destructive = true),
    UnassignMachine("assign_machine", "Unassign machine", "Machine unassigned");

    val resetMode: AgentResetMode?
        get() =
            when (this) {
                Restart -> AgentResetMode.Restart
                ResetSession -> AgentResetMode.Session
                ResetFull -> AgentResetMode.Full
                Start,
                Stop,
                Delete,
                UnassignMachine -> null
            }

    val requiresConfirmation: Boolean
        get() = this != Start
}
