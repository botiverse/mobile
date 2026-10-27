package build.raft.app.data

import build.raft.app.model.ConversationSummary
import build.raft.app.model.ServerId

/**
 * The `GET channels/{id}` detail behind the conversation header as an offline resource: one value per
 * account + server + channel (artin: everything fetched is stored locally).
 */
internal val ConversationDetailResourceSpec: RaftOfflineResourceSpec<String, ConversationSummary> =
    RaftOfflineResourceSpec(
        name = "conversation_detail",
        version = 1,
        serializer = ConversationSummary.serializer(),
        paramsId = { channelId -> channelId },
        maxEntriesPerAccount = 256,
        maxBytesPerEntry = 64 * 1024,
    )

/** Who a stored conversation detail belongs to: environment + account + server (+ the hub's generations). */
internal data class StoredConversationDetailOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val authGeneration: Long = 0L,
    val selectionGeneration: Long = 0L,
)

/**
 * The header's stored conversation details: the thread load's canonical detail is written through
 * ([record]); a page opened on a cold start or offline publishes the stored copy into the header's identity
 * hub ([restore]) until the network's detail arrives (which then replaces it). Confirmed channel setting
 * changes write through ([update]); a channel the user lost goes ([remove]).
 */
internal class StoredConversationDetails(
    private val resource: RaftOfflineResource<String, ConversationSummary>,
) {
    /** Now, on the offline cache's clock: call when the request that will answer [record] starts. */
    fun fetchStarted(): Long = resource.fetchStarted()

    /**
     * The network's canonical detail of [channelId], already accepted by the header hub. [startedAt]: when its
     * request started ([fetchStarted]); a wipe (lost server) or confirmed change after that wins, so an answer
     * in flight across them is not stored. Null = now.
     */
    suspend fun record(
        owner: StoredConversationDetailOwner,
        channelId: String,
        detail: ConversationSummary,
        startedAt: Long? = null,
    ): Boolean {
        val key = key(owner, channelId, startedAt) ?: return false
        return resource.write(key, detail)
    }

    /** [record] under the detail's canonical channel id (the id the header hub keys it by). */
    suspend fun recordCanonical(
        owner: StoredConversationDetailOwner,
        detail: ConversationSummary,
        startedAt: Long? = null,
    ): Boolean {
        val projection = detail.toCanonicalConversationDirectoryProjection(ServerId(owner.serverId)) ?: return false
        return record(owner, projection.canonicalChannelId, detail, startedAt)
    }

    suspend fun read(owner: StoredConversationDetailOwner, channelId: String): ConversationSummary? {
        val key = key(owner, channelId) ?: return null
        return resource.read(key)
    }

    /**
     * Publishes [detail] (the stored detail of [channelId], from [read]) into [hub] as the header's canonical
     * row, unless the network's detail is already there. False when the hub refused it.
     */
    fun restore(
        owner: StoredConversationDetailOwner,
        channelId: String,
        hub: SharedConversationIdentityHub,
        detail: ConversationSummary,
    ): Boolean {
        val projection = detail.toCanonicalConversationDirectoryProjection(ServerId(owner.serverId)) ?: return false
        if (projection.canonicalChannelId != channelId.trim()) return false
        return hub.publishCanonical(
            authority =
                SharedConversationIdentityAuthority(
                    environmentId = owner.environmentId,
                    accountId = owner.accountId,
                    serverId = owner.serverId,
                    authGeneration = owner.authGeneration,
                    selectionGeneration = owner.selectionGeneration,
                ),
            projection = projection,
            onlyIfAbsent = true,
        )
    }

    /** A confirmed change of the channel (the server answered): the stored copy follows; none stored = no-op. */
    suspend fun update(owner: StoredConversationDetailOwner, channelId: String, transform: (ConversationSummary) -> ConversationSummary?) {
        val key = key(owner, channelId) ?: return
        resource.update(key, transform)
    }

    suspend fun remove(owner: StoredConversationDetailOwner, channelId: String) {
        val key = key(owner, channelId) ?: return
        resource.remove(key)
    }

    // Under the cache's current environment only: a detail of another environment is never mixed in.
    private fun key(
        owner: StoredConversationDetailOwner,
        channelId: String,
        mintedAt: Long? = null,
    ): RaftOfflineResourceKey<String>? {
        val channel = channelId.trim().takeIf { it.isNotEmpty() } ?: return null
        return resource.key(owner.accountId, owner.serverId, channel, mintedAt)
            ?.takeIf { it.environmentId == owner.environmentId.normalizedEnvironmentId() }
    }
}
