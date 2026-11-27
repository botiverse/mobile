package build.raft.app.shell

import build.raft.app.RaftAdaptiveHomeLanding
import build.raft.app.model.ConversationKind
import build.raft.app.resolveRaftAdaptiveHomeLanding

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
