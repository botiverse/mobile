package build.raft.app.network.sync

/** Stable durable consumer identity for the canonical messages ledger. Rollout flags are not identities. */
const val SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID: String = "sync-core.messages.v1"

/**
 * Durable storage partition identity (realtime consistency contract v1.2 section 17).
 *
 * Every segment is opaque and REQUIRED. Callers must provide the exact canonical bytes; this type rejects
 * leading or trailing whitespace instead of silently normalizing two distinct raw identities into one
 * partition. There is deliberately no default for any segment: `environmentId` used to default to a constant,
 * which made staging and production rows of the same principal share one partition (task #1109).
 */
data class DurablePartitionKey(
    val serverId: String,
    val principalId: String,
    val consumerId: String,
    val environmentId: String,
) {
    init {
        requireOpaqueDurableIdentity("serverId", serverId)
        requireOpaqueDurableIdentity("principalId", principalId)
        requireOpaqueDurableIdentity("consumerId", consumerId)
        requireOpaqueDurableIdentity("environmentId", environmentId)
    }
}

internal fun requireOpaqueDurableIdentity(label: String, value: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value == value.trim()) { "$label must already be canonical (no surrounding whitespace)" }
}
