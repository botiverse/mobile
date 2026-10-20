package build.raft.app.data

/**
 * Raft DS › Attention dots (raft-design #620, design/products/raft-chat "Attention dots"): a
 * computer's attention dot marks a change you have not seen, not a lasting state. Mirrors web
 * `packages/web/src/utils/computerAttentionSeen.ts`.
 *
 * The model is a "seen" set of (computerId, attentionState) pairs, per user and server:
 * - every computer named by a machine Home notice contributes one pair per state
 *   (`offline` from `machine-offline:`, `outdated` from `machine-outdated:`);
 * - the dot is lit while any current pair is not in the seen set;
 * - opening Computers marks every current pair seen;
 * - a pair observed to leave (current before, gone now) is dropped from the seen set, so a
 *   computer that comes back online and later goes offline again lights the dot again.
 *
 * Seeing only hides the dot; the Computers page and the Home notices do not read this.
 */
internal object RaftComputerAttentionState {
    const val Offline = "offline"
    const val Outdated = "outdated"
}

private const val MachineOfflinePrefix = "machine-offline:"
private const val MachineOutdatedPrefix = "machine-outdated:"
private const val PairSeparator = "\t"

internal fun raftComputerAttentionPair(computerId: String, state: String): String =
    "$computerId$PairSeparator$state"

/**
 * The (computerId, state) pairs the machine Home notices name now. Notice ids are
 * `machine-offline:<ids>` and `machine-outdated:<latestVersion>:<ids>`, ids comma-joined.
 */
internal fun raftComputerAttentionPairs(homeNotificationIds: List<String>): Set<String> =
    buildSet {
        for (id in homeNotificationIds) {
            val (state, ids) =
                when {
                    id.startsWith(MachineOfflinePrefix) ->
                        RaftComputerAttentionState.Offline to id.removePrefix(MachineOfflinePrefix)
                    id.startsWith(MachineOutdatedPrefix) ->
                        RaftComputerAttentionState.Outdated to id.removePrefix(MachineOutdatedPrefix).substringAfterLast(':')
                    else -> continue
                }
            ids.split(',').map(String::trim).filter(String::isNotEmpty).forEach { computerId ->
                add(raftComputerAttentionPair(computerId, state))
            }
        }
    }

/** The dot: any current pair not yet seen. */
internal fun raftComputerAttentionUnseen(current: Set<String>, seen: Set<String>): Boolean =
    current.any { it !in seen }

/** Opening Computers: everything in view now is seen. */
internal fun raftComputerAttentionMarkSeen(seen: Set<String>, current: Set<String>): Set<String> =
    seen + current

/**
 * One observation of the notices: pairs that were current at the [previous] observation and are
 * gone now left their state, so they are forgotten and re-entering lights the dot again. With no
 * previous observation this session (cold start: notices not loaded yet) nothing is forgotten.
 */
internal fun raftComputerAttentionObserve(
    seen: Set<String>,
    previous: Set<String>?,
    current: Set<String>,
): Set<String> {
    if (previous == null) return seen
    val left = previous - current
    return if (left.isEmpty()) seen else seen - left
}

/** Per user, per server, per device seen set. */
internal interface RaftComputerAttentionSeenStore {
    fun read(userId: String, serverId: String): Set<String>
    fun write(userId: String, serverId: String, seen: Set<String>)
}

internal class KeyValueRaftComputerAttentionSeenStore(
    private val store: SharedPreferenceStore
) : RaftComputerAttentionSeenStore {

    override fun read(userId: String, serverId: String): Set<String> {
        val key = storageKey(userId, serverId) ?: return emptySet()
        return runCatching {
            store.getString(key)?.split('\n')?.filter(String::isNotBlank)?.toSet()
        }.getOrNull() ?: emptySet()
    }

    override fun write(userId: String, serverId: String, seen: Set<String>) {
        val key = storageKey(userId, serverId) ?: return
        runCatching { store.putString(key, seen.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("\n")) }
    }

    private fun storageKey(userId: String, serverId: String): String? {
        val user = userId.trim().takeIf { it.isNotBlank() } ?: return null
        val server = serverId.trim().takeIf { it.isNotBlank() } ?: return null
        return "$StoragePrefix:$user:$server"
    }

    private companion object {
        // Same spelling as web's localStorage key.
        const val StoragePrefix = "slock:computers-attention:seen"
    }
}
