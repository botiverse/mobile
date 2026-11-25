package build.raft.app

import build.raft.app.data.ComputerSizeStatus

/**
 * A computer the migration can name or target (from the server's machine list), with what the
 * target list says about it (web AgentMigrationDialog `TargetComputerList`): its free memory and
 * disk (reported only while online), how many agents it has, and the facts deciding whether the
 * viewer may read its runtime usage.
 */
@RaftCoreInternalApi
data class AgentMigrationComputer(
    val id: String,
    val name: String,
    val isComputer: Boolean,
    val online: Boolean = false,
    val offline: Boolean = false,
    val lastHeartbeat: String? = null,
    /** Server `agentCount`: active agents on it; null when not known. */
    val agentCount: Int? = null,
    val diskStatus: ComputerSizeStatus? = null,
    val memoryStatus: ComputerSizeStatus? = null,
    val attachedByCurrentUser: Boolean = false,
    val creatorUserId: String? = null,
    /** The viewer's own Computer (attached or owned, [build.raft.app.data.raftIsMyComputer]): listed first. */
    val mine: Boolean = false,
    /** The note the Computer's owner wrote about it (botiverse/slock #16578). */
    val description: String? = null
)
