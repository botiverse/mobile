package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/**
 * Web `rack/rackUsageLines.ts` (botiverse/slock #14650, #14662): whether a Computers usage line
 * last showed two lines (an account that reports a model's own window: "60% used" over
 * "Fable · 40%"), per server, computer and provider, so while its usage is read again its
 * placeholder is that tall and the card or row does not grow when the answer lands. Kept on this
 * device, like the rack's view and sort ([raftSharedPreferenceStore]):
 * `computers:rackUsageLines:{serverId}:{machineId}:{provider}` = "2" | "1".
 */
@RaftCoreInternalApi class RaftRackUsageLinesMemory(private val store: SharedPreferenceStore) {
    /** Whether the line last showed two lines; false when never seen on this device (or unreadable). */
    fun twoLines(serverId: String, machineId: String, provider: RuntimeAccountUsageProvider): Boolean =
        runCatching { store.getString(key(serverId, machineId, provider)) == "2" }.getOrDefault(false)

    fun remember(serverId: String, machineId: String, provider: RuntimeAccountUsageProvider, twoLines: Boolean) {
        val key = key(serverId, machineId, provider)
        val value = if (twoLines) "2" else "1"
        runCatching { if (store.getString(key) != value) store.putString(key, value) }
    }

    companion object {
        fun key(serverId: String, machineId: String, provider: RuntimeAccountUsageProvider): String =
            "computers:rackUsageLines:$serverId:$machineId:${provider.wire}"
    }
}
