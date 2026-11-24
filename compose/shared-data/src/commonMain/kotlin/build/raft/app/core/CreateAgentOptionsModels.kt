package build.raft.app

@RaftCoreInternalApi data class SharedCreateAgentMachineOption(
    val id: String,
    val name: String,
    val status: String,
    val runtimes: List<String> = emptyList(),
    /** For the quota lines ([build.raft.app.data.canViewMachineRuntimeAccountUsage]). */
    val attachedByCurrentUser: Boolean = false,
    val creatorId: String? = null,
    /** False when the option came without the machine list (route params): the server decides. */
    val usageFactsKnown: Boolean = false,
    /** The viewer's own Computer (attached or owned, [build.raft.app.data.raftIsMyComputer]): the default prefers it. */
    val mine: Boolean = false
)

@RaftCoreInternalApi data class SharedCreateAgentRuntimeModelOption(
    val id: String,
    val label: String,
    val verified: String? = null,
    /** Null: not reported; empty: the model takes no reasoning effort ([build.raft.app.data.RuntimeModelInfo]). */
    val supportedReasoningEfforts: List<String>? = null,
    val defaultReasoningEffort: String? = null
)

@RaftCoreInternalApi data class SharedCreateAgentRuntimeModelCatalog(
    val models: List<SharedCreateAgentRuntimeModelOption>,
    val defaultModel: String? = null
)
