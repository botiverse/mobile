package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.toSharedConversationDirectoryProjection
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ServerId

private val HeaderProjectionTestServerId = ServerId("header-projection-test-server")

internal fun ConversationSummary.toConversationHeaderProjection(): SharedConversationDirectoryProjection? =
    toSharedConversationDirectoryProjection(HeaderProjectionTestServerId)

internal fun ConversationSummary.toConversationAccessCandidate(): ConversationAccessCandidate? {
    val projection = toConversationHeaderProjection() ?: return null
    return projection.toConversationAccessCandidate()
}
