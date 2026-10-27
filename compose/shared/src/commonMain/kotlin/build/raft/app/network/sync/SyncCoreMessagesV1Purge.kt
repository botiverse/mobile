package build.raft.app.network.sync

import build.raft.app.data.RaftSqlDatabase

/**
 * Deletes every `sync-core.messages.v1` row (facts, cursors, viewer overlays) in every partition, in one
 * transaction. The V1 shadow writers were deleted and nothing ever read these rows (message storage design
 * 2026-10-27, Phase 0a); the provider runs this at start. Idempotent: a second run deletes nothing, and it
 * never touches another consumer (`sync-core.messages.v2`, read-state).
 */
internal suspend fun purgeSyncCoreMessagesV1Rows(database: RaftSqlDatabase) {
    database.transaction {
        queries.purgeCanonicalFactsForConsumer(SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID)
        queries.purgeSyncCursorsForConsumer(SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID)
        queries.purgeMessageViewerOverlaysForConsumer(SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID)
    }
}
