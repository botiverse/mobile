package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import build.raft.app.model.ServerId

internal data class RealtimeRoomLeaseOwner(
    val shellInstanceId: String,
    val serverId: String,
    val consumerId: String,
    val generation: Long,
) {
    init {
        require(shellInstanceId.isNotBlank()) { "Room lease requires a shell id" }
        require(serverId.isNotBlank()) { "Room lease requires a server id" }
        require(consumerId.isNotBlank()) { "Room lease requires a consumer id" }
        require(generation > 0L) { "Room lease requires a positive generation" }
    }
}

internal enum class RealtimeRoomTransitionAction {
    Join,
    Leave,
}

internal data class RealtimeRoomTransition(
    val action: RealtimeRoomTransitionAction,
    val serverId: String,
    val roomId: String,
)

/**
 * Shell-scoped room membership authority.
 *
 * Consumers hold generation-qualified leases. A room is joined only for the first consumer and
 * left only after the final consumer releases it, so a stale child cannot evict a newer visible
 * detail from the same room.
 */
internal class SharedRealtimeRoomLeaseRegistry(
    private val shellInstanceId: String,
    @Suppress("unused") private val leaveAllowed: (serverId: String, roomId: String) -> Boolean = { _, _ -> true },
) {
    private val lock = SynchronizedObject()
    private val ownersByRoom = mutableMapOf<RoomKey, MutableSet<RealtimeRoomLeaseOwner>>()
    private val roomsByOwner = mutableMapOf<RealtimeRoomLeaseOwner, MutableSet<RoomKey>>()

    init {
        require(shellInstanceId.isNotBlank()) { "Room lease registry requires a shell id" }
    }

    fun acquire(
        owner: RealtimeRoomLeaseOwner,
        roomIds: Iterable<String>,
    ): List<RealtimeRoomTransition> = synchronized(lock) {
        require(owner.shellInstanceId == shellInstanceId) {
            "Room lease owner does not belong to this shell"
        }
        val transitions = mutableListOf<RealtimeRoomTransition>()
        roomIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { roomId ->
                val key = RoomKey(owner.serverId, roomId)
                val owners = ownersByRoom.getOrPut(key) { mutableSetOf() }
                if (owner in owners) return@forEach
                if (owners.isEmpty()) {
                    transitions +=
                        RealtimeRoomTransition(
                            action = RealtimeRoomTransitionAction.Join,
                            serverId = owner.serverId,
                            roomId = roomId,
                        )
                }
                owners += owner
                roomsByOwner.getOrPut(owner) { mutableSetOf() } += key
            }
        transitions
    }

    fun release(owner: RealtimeRoomLeaseOwner): List<RealtimeRoomTransition> = synchronized(lock) {
        releaseOwnerLocked(owner)
    }

    fun clearServer(serverId: String): List<RealtimeRoomTransition> = synchronized(lock) {
        val owners =
            roomsByOwner.keys.filter { owner ->
                owner.shellInstanceId == shellInstanceId && owner.serverId == serverId
            }
        owners.flatMap(::releaseOwnerLocked).distinct()
    }

    fun clear(): List<RealtimeRoomTransition> = synchronized(lock) {
        roomsByOwner.keys.toList().flatMap(::releaseOwnerLocked).distinct()
    }

    fun markJoinedByUser(serverId: String, roomId: String) = Unit

    fun ownerCount(serverId: String, roomId: String): Int = synchronized(lock) {
        ownersByRoom[RoomKey(serverId, roomId)]?.size ?: 0
    }

    private fun releaseOwnerLocked(owner: RealtimeRoomLeaseOwner): List<RealtimeRoomTransition> {
        val keys = roomsByOwner.remove(owner) ?: return emptyList()
        val transitions = mutableListOf<RealtimeRoomTransition>()
        keys.forEach { key ->
            val owners = ownersByRoom[key] ?: return@forEach
            owners -= owner
            if (owners.isEmpty()) {
                ownersByRoom -= key
                transitions +=
                    RealtimeRoomTransition(
                        action = RealtimeRoomTransitionAction.Leave,
                        serverId = key.serverId,
                        roomId = key.roomId,
                    )
            }
        }
        return transitions
    }

    private data class RoomKey(
        val serverId: String,
        val roomId: String,
    )
}

// Red evidence: old behaviour, every final release leaves.
internal fun realtimeRoomLeaveAllowed(
    identity: SharedConversationIdentityProvider?,
    serverId: String,
    roomId: String,
): Boolean = true
