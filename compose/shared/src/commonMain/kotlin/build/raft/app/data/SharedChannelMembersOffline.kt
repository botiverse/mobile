package build.raft.app.data

import build.raft.app.model.ChannelExternalMemberProfile
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * A channel's (or a reply thread's, which is its own channel) member list as stored offline (artin:
 * everything fetched is stored locally): the rows only. Never stored: the slice's permission flags
 * (`canAddMembers` / `canRemoveMembers`), a member's `canChangeChannelRole`, presence and agent liveness
 * (live facts), created-agent lists. A stand-in built from it grants no management action.
 */
@Serializable
internal data class StoredChannelMembers(
    val members: List<MemberProfile> = emptyList(),
    val externalMembers: List<ChannelExternalMemberProfile> = emptyList(),
)

internal const val ChannelMembersOfflineMaxMembers = 500

internal val ChannelMembersResourceSpec: RaftOfflineResourceSpec<String, StoredChannelMembers> =
    RaftOfflineResourceSpec(
        name = "channel_members",
        version = 1,
        serializer = StoredChannelMembers.serializer(),
        paramsId = { channelId -> channelId },
        // The channels / threads opened recently, per account (every server).
        maxEntriesPerAccount = 64,
        maxBytesPerEntry = 1024 * 1024,
        fit = { record ->
            record.copy(
                members = record.members.take(ChannelMembersOfflineMaxMembers),
                externalMembers = record.externalMembers.take(ChannelMembersOfflineMaxMembers),
            )
        },
    )

/** The stored rows of [slice] (see [StoredChannelMembers] for what never leaves memory). */
internal fun SharedServerChannelMembersSlice.toStoredChannelMembers(): StoredChannelMembers =
    StoredChannelMembers(
        members =
            members.map { member ->
                member.copy(
                    presence = Presence.Offline,
                    agentStatus = null,
                    canChangeChannelRole = null,
                    createdAgents = emptyList(),
                )
            },
        externalMembers = externalMembers,
    )

/**
 * The stored channel member lists: a mirror of the members store's loaded channel slices (after a load, a
 * confirmed add / remove, a realtime removal), and the read-only stand-in a channel with nothing in memory
 * shows while its members load. Lost access to a channel drops its copy ([forget]); lost access to a server
 * wipes every offline resource of it (AccountLocalData).
 */
internal class SharedChannelMembersOffline(
    private val resource: RaftOfflineResource<String, StoredChannelMembers>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    /** At most one mirror pass per this period (a burst of patches is one write per channel). */
    private val mirrorPeriodMillis: Long = 1_000L,
    private val logger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()

    // Hash of each channel's rows as last stored (not the rows: memory stays small).
    private val storedHashes = mutableMapOf<SharedServerChannelMembersScope, Int>()

    suspend fun read(scope: SharedServerChannelMembersScope): StoredChannelMembers? {
        val key = key(scope, mintedAt = null) ?: return null
        return resource.read(key)
    }

    /** The channel is gone for this account (lost permission / not found): its copy goes, and is not remembered. */
    suspend fun forget(scope: SharedServerChannelMembersScope) {
        synchronized(lock) { storedHashes -= scope }
        val key = key(scope, mintedAt = null) ?: return
        resource.remove(key)
    }

    /** Lost access to [serverId]: its rows go with the offline wipe; what was stored is forgotten. */
    fun forgetServer(serverId: String) {
        synchronized(lock) { storedHashes.keys.removeAll { it.serverId == serverId } }
    }

    /**
     * Mirrors [slices] (read through [current] when [changes] signals): every Loaded slice whose rows
     * changed is stored; an empty one drops its copy. Slices that are loading / failed are not touched.
     */
    fun follow(
        changes: Flow<*>,
        current: () -> Map<SharedServerChannelMembersScope, SharedServerChannelMembersSlice>,
    ) {
        scope.launch {
            changes.conflate().collect {
                try {
                    // The instant first, then the state: a wipe after this instant wins over what is read now.
                    val observedAt = resource.fetchStarted()
                    mirrorOnce(current(), observedAt)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    logger("channel_members_offline_mirror result=failure type=${error::class.simpleName}")
                }
                if (mirrorPeriodMillis > 0) delay(mirrorPeriodMillis)
            }
        }
    }

    private suspend fun mirrorOnce(
        slices: Map<SharedServerChannelMembersScope, SharedServerChannelMembersSlice>,
        observedAt: Long,
    ) {
        // Slices that left memory (eviction, owner change) are forgotten: a later identical load stores again.
        synchronized(lock) { storedHashes.keys.retainAll(slices.keys) }
        for ((sliceScope, slice) in slices) {
            if (slice.status != SharedServerMembersLoadStatus.Loaded) continue
            val record = slice.toStoredChannelMembers()
            val hash = record.hashCode()
            if (synchronized(lock) { storedHashes[sliceScope] } == hash) continue
            val key = key(sliceScope, mintedAt = observedAt) ?: continue
            if (record.members.isEmpty() && record.externalMembers.isEmpty()) {
                // No members is no stand-in: the copy goes.
                resource.remove(key)
            } else {
                resource.write(key, record)
            }
            synchronized(lock) { storedHashes[sliceScope] = hash }
        }
    }

    // Under the offline cache's current environment only: another environment's list is never mixed in.
    private fun key(scope: SharedServerChannelMembersScope, mintedAt: Long?): RaftOfflineResourceKey<String>? =
        resource.key(scope.accountId, scope.serverId, scope.channelId, mintedAt)
            ?.takeIf { it.environmentId == scope.environmentId.trim().trimEnd('/') }
}
