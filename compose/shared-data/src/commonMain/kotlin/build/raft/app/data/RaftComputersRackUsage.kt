package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 * The Computers page's runtime usage reads (web `useRackRuntimeUsage`, botiverse/slock
 * packages/web/src/components/machine/rack/RackRuntimeUsage.tsx), under Tenny's request rules:
 *
 * - read only when the page opens or on pull-to-refresh; nothing polls;
 * - the usage sheet's own store and its 60 s cache ([SharedRuntimeAccountUsageStore]);
 * - no read for an offline computer or one the viewer may not see the usage of;
 * - at most 4 reads in flight ([RUNTIME_ACCOUNT_USAGE_MAX_CONCURRENT_READS], in the store);
 * - `DataRequestPriority.Visible` ([RaftComputersNetworkApi.runtimeAccountUsage]).
 */

/**
 * One read per usage provider of each online computer the viewer may see the usage of
 * ([SettingsComputerSnapshot.canViewRuntimeUsage]), providers in their catalog order. Nothing
 * while the signed-in owner or the server is unknown.
 */
@RaftCoreInternalApi fun raftComputersRackUsageKeys(
    owner: SharedComputersOwner?,
    serverId: String?,
    computers: List<SettingsComputerSnapshot>,
): List<RuntimeAccountUsageKey> =
    raftComputersRackPeekUsageKeys(
        owner = owner,
        serverId = serverId,
        computers = computers,
        includeUnpermissionedCached = false,
    )

/**
 * Keys eligible for synchronous Frame-1 cache peeks ([RaftComputersRackUsageSession.peekSync]).
 * When [includeUnpermissionedCached] is true (while the page's authoritative permission-gated
 * snapshot is still loading behind the first-frame machine cache), every online computer's
 * usage providers are peeked against the owner/server/generation-fenced in-memory store without
 * issuing any network request; the store only returns entries previously authorized for that
 * exact owner.
 */
@RaftCoreInternalApi fun raftComputersRackPeekUsageKeys(
    owner: SharedComputersOwner?,
    serverId: String?,
    computers: List<SettingsComputerSnapshot>,
    includeUnpermissionedCached: Boolean = false,
): List<RuntimeAccountUsageKey> {
    val readOwner = owner ?: return emptyList()
    val server = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return computers
        .filter { it.online && (includeUnpermissionedCached || it.canViewRuntimeUsage) }
        .flatMap { computer ->
            val machineId = computer.id.trim().takeIf { it.isNotEmpty() } ?: return@flatMap emptyList()
            val providers = computer.runtimes.mapNotNull(RuntimeAccountUsageProvider::forRuntime).toSet()
            RuntimeAccountUsageProvider.entries
                .filter { it in providers }
                .map { provider -> RuntimeAccountUsageKey(owner = readOwner, serverId = server, machineId = machineId, provider = provider) }
        }
        .distinct()
}

/**
 * Combines synchronously peeked cached usage loads with live visit reads for [owner] and
 * [serverId], letting fresher [live] reads win while strictly filtering out any other owner,
 * session generation, or server.
 */
@RaftCoreInternalApi fun raftComputersRackUsageLoadsForOwner(
    peeked: Map<RuntimeAccountUsageKey, RuntimeAccountUsageLoad>,
    live: Map<RuntimeAccountUsageKey, RuntimeAccountUsageLoad>,
    owner: SharedComputersOwner?,
    serverId: String?,
): Map<String, Map<RuntimeAccountUsageProvider, RuntimeAccountUsageLoad>> {
    val activeOwner = owner ?: return emptyMap()
    val activeServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
    if (peeked.isEmpty() && live.isEmpty()) return emptyMap()
    val merged = LinkedHashMap<RuntimeAccountUsageKey, RuntimeAccountUsageLoad>(peeked.size + live.size)
    peeked.forEach { (key, load) ->
        if (key.owner == activeOwner && key.serverId == activeServerId && load !is RuntimeAccountUsageLoad.Superseded) {
            merged[key] = load
        }
    }
    live.forEach { (key, load) ->
        if (key.owner == activeOwner && key.serverId == activeServerId && load !is RuntimeAccountUsageLoad.Superseded) {
            merged[key] = load
        }
    }
    return merged.entries
        .groupBy({ it.key.machineId }, { it.key.provider to it.value })
        .mapValues { (_, loads) -> loads.toMap() }
}

/**
 * One Computers page visit's usage reads. [open] reads each key at most once per visit (a
 * computer that comes online while the page is open is read once, as web reads a card when it
 * shows); a key read in the last 60 s is answered by the store's cache without a request.
 * [pullToRefresh] reads every key again past the cache. Each result is handed to `onResult` as
 * it lands.
 */
@RaftCoreInternalApi class RaftComputersRackUsageSession(private val store: SharedRuntimeAccountUsageStore) {
    private val requested = HashSet<RuntimeAccountUsageKey>()

    /** Synchronous Frame-1 peek of already-loaded owner-scoped usage snapshots; never starts a request. */
    fun peekSync(
        keys: Collection<RuntimeAccountUsageKey>,
        allowExpiredLoaded: Boolean = true,
    ): Map<RuntimeAccountUsageKey, RuntimeAccountUsageLoad> =
        store.peekAllSync(keys, allowExpiredLoaded = allowExpiredLoaded)

    suspend fun open(keys: List<RuntimeAccountUsageKey>, onResult: (RuntimeAccountUsageKey, RuntimeAccountUsageLoad) -> Unit) {
        val unread = keys.filter { requested.add(it) }
        read(unread, force = false, onResult)
    }

    suspend fun pullToRefresh(keys: List<RuntimeAccountUsageKey>, onResult: (RuntimeAccountUsageKey, RuntimeAccountUsageLoad) -> Unit) {
        requested.addAll(keys)
        read(keys, force = true, onResult)
    }

    /** What the store holds for [key] now (after a usage sheet refreshed it); never a request. */
    suspend fun cached(key: RuntimeAccountUsageKey): RuntimeAccountUsageLoad? = store.peek(key)

    private suspend fun read(
        keys: List<RuntimeAccountUsageKey>,
        force: Boolean,
        onResult: (RuntimeAccountUsageKey, RuntimeAccountUsageLoad) -> Unit,
    ) = coroutineScope {
        keys.forEach { key -> launch { onResult(key, store.read(key, force = force)) } }
    }
}
