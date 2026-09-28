package build.raft.app.data

/**
 * Task #409: per-machine collapse state for the members-page agent groups.
 *
 * Mirrors web's `sidebarCollapsedSections.ts`
 * `sidebarAgentMachineGroupCollapsedStorageKey`, which stores one
 * `slock:sidebarCollapsed:<userId>:agent-machine-group:<machineKey>` entry per
 * machine group, read as `"true" === collapsed`. The members page keys groups
 * by the agent's `computer-id:` badge (stable across machine renames, same
 * reason web uses `machineId`); agents without a machine share
 * [MembersAgentMachineGroupNoMachineKey], matching web's `"__no_machine__"`.
 *
 * Reads are synchronous: [SharedPreferenceStore.getString] is the same
 * MMKV-backed access the other `KeyValue*PreferenceStore`s use from
 * composables, so the first frame already reflects the remembered state.
 */
internal interface MembersAgentMachineGroupCollapseStore {
    fun isCollapsed(userId: String, machineKey: String): Boolean
    fun setCollapsed(userId: String, machineKey: String, collapsed: Boolean)
}

internal const val MembersAgentMachineGroupNoMachineKey = "__no_machine__"

internal class KeyValueMembersAgentMachineGroupCollapseStore(
    private val store: SharedPreferenceStore
) : MembersAgentMachineGroupCollapseStore {

    override fun isCollapsed(userId: String, machineKey: String): Boolean {
        val key = storageKey(userId, machineKey) ?: return false
        return runCatching { store.getString(key) == "true" }.getOrDefault(false)
    }

    override fun setCollapsed(userId: String, machineKey: String, collapsed: Boolean) {
        val key = storageKey(userId, machineKey) ?: return
        runCatching { store.putString(key, collapsed.toString()) }
    }

    private fun storageKey(userId: String, machineKey: String): String? {
        val user = userId.trim().takeIf { it.isNotBlank() } ?: return null
        val machine = machineKey.trim().takeIf { it.isNotBlank() } ?: return null
        return "$StoragePrefix:$user:$MachineGroupSuffix:$machine"
    }

    private companion object {
        const val StoragePrefix = "slock:sidebarCollapsed"
        const val MachineGroupSuffix = "agent-machine-group"
    }
}
