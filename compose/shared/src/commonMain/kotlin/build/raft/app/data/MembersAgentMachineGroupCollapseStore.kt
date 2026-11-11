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

    /** The Agents list's grouping the reader chose ([MembersAgentsGrouping]); the default until they choose. */
    fun agentsGrouping(userId: String): MembersAgentsGrouping = MembersAgentsGrouping.Default

    fun setAgentsGrouping(userId: String, grouping: MembersAgentsGrouping) {}
}

/**
 * How the Members page groups its agents (web `AgentsGrouping`, `agentsGroupingPreference.ts`):
 * not at all (the default since slock #14068) or by computer. The web's By creator needs each
 * agent's creator, which the app's member list does not carry yet.
 */
internal enum class MembersAgentsGrouping(val storageValue: String) {
    None("none"),
    Computer("computer"),
    ;

    companion object {
        /** slock #14068 (`DEFAULT_AGENTS_GROUPING = "none"`): one list until the reader picks a grouping. */
        val Default: MembersAgentsGrouping = None

        /** A stored value from this or a later build; anything unknown reads as the default. */
        fun fromStorageValue(value: String?): MembersAgentsGrouping =
            entries.firstOrNull { it.storageValue == value?.trim() } ?: Default
    }
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

    override fun agentsGrouping(userId: String): MembersAgentsGrouping {
        val key = groupingKey(userId) ?: return MembersAgentsGrouping.Default
        return MembersAgentsGrouping.fromStorageValue(runCatching { store.getString(key) }.getOrNull())
    }

    override fun setAgentsGrouping(userId: String, grouping: MembersAgentsGrouping) {
        val key = groupingKey(userId) ?: return
        runCatching { store.putString(key, grouping.storageValue) }
    }

    /** The web keeps the choice per browser (`raft_members_agents_grouping`); the app per signed-in user. */
    private fun groupingKey(userId: String): String? =
        userId.trim().takeIf { it.isNotBlank() }?.let { "$GroupingKey:$it" }

    private fun storageKey(userId: String, machineKey: String): String? {
        val user = userId.trim().takeIf { it.isNotBlank() } ?: return null
        val machine = machineKey.trim().takeIf { it.isNotBlank() } ?: return null
        return "$StoragePrefix:$user:$MachineGroupSuffix:$machine"
    }

    private companion object {
        const val StoragePrefix = "slock:sidebarCollapsed"
        const val MachineGroupSuffix = "agent-machine-group"
        const val GroupingKey = "raft_members_agents_grouping"
    }
}
