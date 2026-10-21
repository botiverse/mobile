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
    /**
     * Task #168: told of every `leave:channel` transition this registry emits (called under its lock; must
     * not block). The offline cache stops appending that room's frames: the socket no longer receives them.
     * Declared before [leaveAllowed] so existing trailing-lambda callers keep binding to [leaveAllowed].
     */
    private val onLeave: (serverId: String, roomId: String) -> Unit = { _, _ -> },
    /**
     * Whether the last release of a room may send `leave:channel`. The server subscribes every socket to
     * all channels and DMs the user belongs to when it connects (that is how unread counts arrive), so a
     * leave for one of those silently stops its realtime messages until the next reconnect. Only rooms the
     * user is known not to belong to (a public channel opened to look at) may be left; unknown = keep.
     */
    private val leaveAllowed: (serverId: String, roomId: String) -> Boolean = { _, _ -> true },
) {
    private val lock = SynchronizedObject()
    private val ownersByRoom = mutableMapOf<RoomKey, MutableSet<RealtimeRoomLeaseOwner>>()
    private val roomsByOwner = mutableMapOf<RealtimeRoomLeaseOwner, MutableSet<RoomKey>>()
    // Rooms the user joined from a page in this shell; the directory may not know about it yet.
    private val joinedByUser = mutableSetOf<RoomKey>()

    /**
     * Task #14c: reply-thread rooms a page asked to keep ([keepJoinedUntilClear]). Their last release sends no
     * `leave:channel` while they are among the [MAX_RETAINED_THREAD_ROOMS] most recently released; [clearServer] /
     * [clear] (shell scope release) leave them like any other room.
     */
    private val keptUntilClear = mutableSetOf<RoomKey>()

    /**
     * Task #14c: [keptUntilClear] rooms whose last lease was released: still joined, owned by nobody. Oldest
     * release first. Bounded: the hosts rejoin every joined room on reconnect and never drop one on their own, so
     * without a bound every thread opened in the shell would stay joined (and its replies keep arriving) for its
     * whole life. The oldest one is left when a newer one is retained (that thread's acks are void from then on).
     */
    private val retainedAfterRelease = LinkedHashSet<RoomKey>()

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
                // Still joined, but the join is sent again as before (idempotent on the server, which acks it).
                retainedAfterRelease -= key
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
        releaseOwnerLocked(owner, teardown = false)
    }

    fun clearServer(serverId: String): List<RealtimeRoomTransition> = synchronized(lock) {
        val owners =
            roomsByOwner.keys.filter { owner ->
                owner.shellInstanceId == shellInstanceId && owner.serverId == serverId
            }
        val transitions = owners.flatMap { releaseOwnerLocked(it, teardown = true) }.toMutableList()
        teardownKeptLocked(transitions) { it.serverId == serverId }
        transitions.distinct()
    }

    fun clear(): List<RealtimeRoomTransition> = synchronized(lock) {
        val transitions = roomsByOwner.keys.toList().flatMap { releaseOwnerLocked(it, teardown = true) }.toMutableList()
        teardownKeptLocked(transitions) { true }
        transitions.distinct()
    }

    /**
     * Task #14c: the page of reply thread [roomId] keeps that room joined after its last lease is released, until
     * it is no longer among the [MAX_RETAINED_THREAD_ROOMS] most recently released kept rooms, or [clearServer] /
     * [clear]. (A reconnect does not end it: the hosts rejoin every joined room.) A thread room is never in the
     * connect-time join, and the conversation directory may wrongly read it as non-member, so leaving it on page
     * close stopped its replies and voided its join acks for the realtime offline cache
     * ([build.raft.app.network.sync.CanonicalMessageV2ProductionCanary.noteRoomLeft]) for the rest of the process.
     * Only the thread room itself is marked: the parent channel's room keeps the [leaveAllowed] rule.
     */
    fun keepJoinedUntilClear(serverId: String, roomId: String) = synchronized(lock) {
        val cleanRoomId = roomId.trim().takeIf(String::isNotBlank) ?: return@synchronized
        keptUntilClear += RoomKey(serverId, cleanRoomId)
    }

    /** The user joined [roomId] (e.g. the Join button): never leave it on page close. */
    fun markJoinedByUser(serverId: String, roomId: String) = synchronized(lock) {
        val cleanRoomId = roomId.trim().takeIf(String::isNotBlank) ?: return@synchronized
        joinedByUser += RoomKey(serverId, cleanRoomId)
    }

    /** Whether the user joined [roomId] from a page in this shell (task #168 membership lookup). */
    fun isJoinedByUser(serverId: String, roomId: String): Boolean = synchronized(lock) {
        RoomKey(serverId, roomId.trim()) in joinedByUser
    }

    fun ownerCount(serverId: String, roomId: String): Int = synchronized(lock) {
        ownersByRoom[RoomKey(serverId, roomId)]?.size ?: 0
    }

    /** [teardown]: [clearServer] / [clear], where a [keptUntilClear] room is left like any other. */
    private fun releaseOwnerLocked(owner: RealtimeRoomLeaseOwner, teardown: Boolean): List<RealtimeRoomTransition> {
        val keys = roomsByOwner.remove(owner) ?: return emptyList()
        val transitions = mutableListOf<RealtimeRoomTransition>()
        keys.forEach { key ->
            val owners = ownersByRoom[key] ?: return@forEach
            owners -= owner
            if (owners.isEmpty()) {
                ownersByRoom -= key
                if (!teardown && key in keptUntilClear) {
                    retainedAfterRelease -= key
                    retainedAfterRelease += key
                    while (retainedAfterRelease.size > MAX_RETAINED_THREAD_ROOMS) {
                        val oldest = retainedAfterRelease.first()
                        retainedAfterRelease -= oldest
                        keptUntilClear -= oldest
                        leaveIfAllowedLocked(oldest, transitions)
                    }
                } else {
                    leaveIfAllowedLocked(key, transitions)
                }
            }
        }
        return transitions
    }

    /** Holding [lock]: the teardown of the kept rooms [matches] selects (after their owners were released). */
    private fun teardownKeptLocked(
        transitions: MutableList<RealtimeRoomTransition>,
        matches: (RoomKey) -> Boolean,
    ) {
        retainedAfterRelease.filter(matches).forEach { key ->
            retainedAfterRelease -= key
            leaveIfAllowedLocked(key, transitions)
        }
        keptUntilClear.removeAll(matches)
    }

    private fun leaveIfAllowedLocked(key: RoomKey, transitions: MutableList<RealtimeRoomTransition>) {
        if (key in joinedByUser || !leaveAllowed(key.serverId, key.roomId)) return
        onLeave(key.serverId, key.roomId)
        transitions +=
            RealtimeRoomTransition(
                action = RealtimeRoomTransitionAction.Leave,
                serverId = key.serverId,
                roomId = key.roomId,
            )
    }

    private data class RoomKey(
        val serverId: String,
        val roomId: String,
    )
}

/**
 * Leave a room on page close only when the conversation directory says the user is not a member.
 * A missing or ambiguous row counts as "maybe a member": receiving extra messages is harmless, missing a
 * member channel's messages is the bug this guards against.
 */
internal fun realtimeRoomLeaveAllowed(
    identity: SharedConversationIdentityProvider?,
    serverId: String,
    roomId: String,
): Boolean {
    val projection = identity?.resolveProjection(ServerId(serverId), roomId) ?: return false
    return !projection.joined
}

/** Task #14c: how many released reply-thread rooms [SharedRealtimeRoomLeaseRegistry] keeps joined. */
internal const val MAX_RETAINED_THREAD_ROOMS: Int = 8
