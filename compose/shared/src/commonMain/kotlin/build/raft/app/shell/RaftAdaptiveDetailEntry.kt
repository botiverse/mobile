package build.raft.app

import build.raft.app.model.ConversationKind

internal data class RaftAdaptiveHomeLandingCandidate(
    val threadId: String,
    val name: String,
    val kind: ConversationKind? = null,
)

internal fun resolveRaftAdaptiveHomeLandingId(
    selectedThreadId: String?,
    candidates: List<RaftAdaptiveHomeLandingCandidate>,
): String? =
    (resolveRaftAdaptiveHomeLanding(selectedThreadId, remembered = null, candidates).landing as? RaftAdaptiveHomeLanding.Row)
        ?.threadId
