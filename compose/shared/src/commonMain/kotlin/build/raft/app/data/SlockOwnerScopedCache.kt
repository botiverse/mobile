package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Token-free process-cache partition for one authenticated server owner.
 *
 * Construction normalizes and validates every identity component, so cache APIs cannot be called
 * with an ownerless or partially specified partition.
 */
internal class SlockOwnerScopedCacheOwner private constructor(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
) {
    companion object {
        fun createOrNull(
            environmentId: String,
            accountId: String,
            serverId: String,
        ): SlockOwnerScopedCacheOwner? {
            val environment =
                environmentId.normalizedEnvironmentId().takeIf(String::isNotBlank) ?: return null
            val account = accountId.trim().takeIf(String::isNotBlank) ?: return null
            val server = serverId.trim().takeIf(String::isNotBlank) ?: return null
            return SlockOwnerScopedCacheOwner(environment, account, server)
        }

        fun from(owner: AuthenticatedRouteOwner): SlockOwnerScopedCacheOwner? =
            createOrNull(owner.environmentId, owner.accountId, owner.serverId)
    }

    override fun equals(other: Any?): Boolean =
        other is SlockOwnerScopedCacheOwner &&
            environmentId == other.environmentId &&
            accountId == other.accountId &&
            serverId == other.serverId

    override fun hashCode(): Int {
        var result = environmentId.hashCode()
        result = 31 * result + accountId.hashCode()
        result = 31 * result + serverId.hashCode()
        return result
    }

    override fun toString(): String = buildString {
        append("SlockOwnerScopedCacheOwner(environmentId=")
        append(environmentId)
        append(", accountId=")
        append(accountId)
        append(", serverId=")
        append(serverId)
        append(')')
    }
}

/**
 * Synchronized access-order LRU whose smallest addressable unit is an exact owner/resource pair.
 *
 * [acceptsResource] lets a specialized resource key reject owner mismatches such as a channel key
 * carrying a different server. Rejected access is a miss/no-op and never aliases another owner.
 */
internal class SlockOwnerScopedBoundedCache<ResourceKey : Any, Value : Any>(
    maxEntries: Int,
    private val acceptsResource: (SlockOwnerScopedCacheOwner, ResourceKey) -> Boolean =
        { _, _ -> true },
    private val acceptsValue: (SlockOwnerScopedCacheOwner, ResourceKey, Value) -> Boolean =
        { _, _, _ -> true },
) {
    private data class EntryKey<ResourceKey : Any>(
        val owner: SlockOwnerScopedCacheOwner,
        val resource: ResourceKey,
    )

    private val capacity =
        maxEntries.also { require(it > 0) { "owner-scoped cache capacity must be positive" } }
    private val lock = SynchronizedObject()
    private val entries = linkedMapOf<EntryKey<ResourceKey>, Value>()

    fun get(owner: SlockOwnerScopedCacheOwner, resource: ResourceKey): Value? {
        if (!acceptsResource(owner, resource)) return null
        return synchronized(lock) {
            val key = EntryKey(owner, resource)
            val value = entries.remove(key) ?: return@synchronized null
            entries[key] = value
            value
        }
    }

    fun put(owner: SlockOwnerScopedCacheOwner, resource: ResourceKey, value: Value): Boolean {
        if (!acceptsResource(owner, resource)) return false
        val valueAccepted = acceptsValue(owner, resource, value)
        return synchronized(lock) {
            val key = EntryKey(owner, resource)
            if (!valueAccepted) {
                entries.remove(key)
                return@synchronized false
            }
            entries.remove(key)
            entries[key] = value
            while (entries.size > capacity) {
                entries.remove(entries.keys.firstOrNull() ?: break)
            }
            true
        }
    }

    fun remove(owner: SlockOwnerScopedCacheOwner, resource: ResourceKey): Value? {
        if (!acceptsResource(owner, resource)) return null
        return synchronized(lock) { entries.remove(EntryKey(owner, resource)) }
    }

    fun removeOwner(owner: SlockOwnerScopedCacheOwner): List<Value> =
        synchronized(lock) {
            val removed = mutableListOf<Value>()
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.owner == owner) {
                    removed += entry.value
                    iterator.remove()
                }
            }
            removed
        }

    val size: Int
        get() = synchronized(lock) { entries.size }
}
