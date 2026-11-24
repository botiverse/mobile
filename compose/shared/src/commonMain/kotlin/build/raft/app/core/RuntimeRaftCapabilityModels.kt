package build.raft.app

/**
 * What a runtime can do in Raft on the host it runs on by default (slock shared
 * `RuntimeRaftCapabilities`): [midTurnSteering], a message sent while it works reaches it in the
 * same turn; [subagents], it can split work into parallel sub-agents; [compactionReported], it
 * tells Raft when it compacts its context.
 */
internal data class RuntimeRaftCapabilities(
    val midTurnSteering: Boolean,
    val subagents: Boolean,
    val compactionReported: Boolean
)

/** Whether resolved capabilities came from the Computer's own report or the server default table (slock `RuntimeCapabilitySource`). */
internal enum class RuntimeCapabilitySource {
    Machine,
    Default,
    /** A future source value this app does not know; treated like [Default] (never triggers Recommended hints). */
    Unknown
}

/**
 * Server/Computer-resolved Raft capabilities (slock `ResolvedRuntimeRaftCapabilities`, #16178):
 * the 3 user-facing picker flags plus [source] (`Machine` when the Computer reported them in
 * `ready.runtimeCapabilities`, `Default` when resolved from the server's host-route default table).
 * Internal operational capabilities (`internal`) are ignored here and never shown as picker hints.
 */
internal data class ResolvedRuntimeRaftCapabilities(
    val midTurnSteering: Boolean,
    val subagents: Boolean,
    val compactionReported: Boolean,
    val source: RuntimeCapabilitySource
) {
    fun toRuntimeRaftCapabilities(): RuntimeRaftCapabilities =
        RuntimeRaftCapabilities(
            midTurnSteering = midTurnSteering,
            subagents = subagents,
            compactionReported = compactionReported
        )
}
