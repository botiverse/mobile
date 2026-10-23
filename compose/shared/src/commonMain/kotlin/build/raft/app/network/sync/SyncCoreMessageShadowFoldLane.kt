package build.raft.app.network.sync

internal enum class PartitionDenialReason(val wireValue: String) {
    PrincipalUnavailable("principal_unavailable"),
}

internal data class PartitionDenied(
    val reason: PartitionDenialReason,
    val deniedFrameCount: Int,
)

/**
 * The sync-core messages legacy-overload diagnostic lane for message-canonical frames.
 *
 * It used to fold each frame through the pure SyncCore messages domain and persist a V1
 * (`sync-core.messages.v1`) shadow window. Those rows were never read, so the persisting half was deleted
 * (message storage design 2026-10-27, Phase 0a) and the provider purges any surviving V1 rows at start.
 * What remains is the bounded partition-denial diagnostic for the principal-less legacy overload: it is
 * invisible to the legacy realtime sinks and fail-open (a logger failure is swallowed).
 */
internal class SyncCoreMessageShadowFoldLane(
    private val logger: (String) -> Unit,
) {
    /** Fail closed when a legacy/unowned caller cannot supply the durable principal partition. */
    fun partitionDenied(
        serverId: String,
        reason: PartitionDenialReason,
    ): PartitionDenied =
        partitionDenied(serverId, reason, deniedFrameCount = 1)

    /** Emit one bounded diagnostic for a denied eligible batch without logging identity or content. */
    fun partitionDenied(
        serverId: String,
        reason: PartitionDenialReason,
        deniedFrameCount: Int,
    ): PartitionDenied =
        PartitionDenied(
            reason = reason,
            deniedFrameCount = deniedFrameCount.also { count ->
                require(count > 0) { "deniedFrameCount must be positive" }
            },
        ).also {
            runCatching {
                logger(
                    "sync_core_partition_persist_denied reason=${reason.wireValue} " +
                        "serverId=$serverId consumer=$SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID " +
                        "deniedFrameCount=$deniedFrameCount",
                )
            }
        }
}
