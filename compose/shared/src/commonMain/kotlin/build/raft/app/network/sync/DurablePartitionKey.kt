package build.raft.app.network.sync

/** Stable durable consumer identity for the canonical messages ledger. Rollout flags are not identities. */
const val SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID: String = "sync-core.messages.v1"

/**
 * Durable storage partition identity (realtime consistency contract v1.2 section 17).
 *
 * Every segment is opaque. Callers must provide the exact canonical bytes; this type rejects leading or
 * trailing whitespace instead of silently normalizing two distinct raw identities into one partition.
 */
data class DurablePartitionKey(
    val serverId: String,
    val principalId: String,
    val consumerId: String,
) {
    init {
        requireOpaqueDurableIdentity("serverId", serverId)
        requireOpaqueDurableIdentity("principalId", principalId)
        requireOpaqueDurableIdentity("consumerId", consumerId)
    }
}

internal fun requireOpaqueDurableIdentity(label: String, value: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value == value.trim()) { "$label must already be canonical (no surrounding whitespace)" }
}
