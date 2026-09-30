package build.raft.app.data

import build.raft.app.slockLogInfo
import build.raft.app.slockLogWarn

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
    private val store: SharedPreferenceStore,
    // task #409 Alpha85 forensics: optional diagnostic sink. Callers pass the
    // shared platform logger so write/read pairs can be correlated on-device;
    // tests keep the default no-op.
    private val logger: (String) -> Unit = {}
) : MembersAgentMachineGroupCollapseStore {

    override fun isCollapsed(userId: String, machineKey: String): Boolean {
        val key = storageKey(userId, machineKey)
        if (key == null) {
            slockLogWarn(
                LogTag,
                "members_agent_group_collapse_read skipped " +
                    "reason=blank_scope userIdBlank=${userId.isBlank()} machineKeyBlank=${machineKey.isBlank()}"
            )
            return false
        }
        val result =
            runCatching { store.getString(key) == "true" }
                .onFailure { error ->
                    slockLogWarn(
                        LogTag,
                        "members_agent_group_collapse_read failed " +
                            "scope=${scopeDigest(userId)} machineKey=$machineKey error=${error::class.simpleName}"
                    )
                }
                .getOrDefault(false)
        logger(
            "members_agent_group_collapse_read " +
                "scope=${scopeDigest(userId)} machineKey=$machineKey collapsed=$result"
        )
        return result
    }

    override fun setCollapsed(userId: String, machineKey: String, collapsed: Boolean) {
        val key = storageKey(userId, machineKey)
        if (key == null) {
            // A blank user id previously dropped the write silently — the
            // in-session override still collapsed the group, so QA saw
            // "works in-session, lost across restart" (Alpha85 #409).
            slockLogWarn(
                LogTag,
                "members_agent_group_collapse_write skipped " +
                    "reason=blank_scope userIdBlank=${userId.isBlank()} machineKeyBlank=${machineKey.isBlank()} " +
                    "collapsed=$collapsed"
            )
            return
        }
        runCatching { store.putString(key, collapsed.toString()) }
            .onFailure { error ->
                slockLogWarn(
                    LogTag,
                    "members_agent_group_collapse_write failed " +
                        "scope=${scopeDigest(userId)} machineKey=$machineKey " +
                        "collapsed=$collapsed error=${error::class.simpleName}"
                )
            }
            .onSuccess {
                logger(
                    "members_agent_group_collapse_write " +
                        "scope=${scopeDigest(userId)} machineKey=$machineKey collapsed=$collapsed"
                )
            }
    }

    private fun storageKey(userId: String, machineKey: String): String? {
        val user = userId.trim().takeIf { it.isNotBlank() } ?: return null
        val machine = machineKey.trim().takeIf { it.isNotBlank() } ?: return null
        return "$StoragePrefix:$user:$MachineGroupSuffix:$machine"
    }

    // Scope evidence without credentials: presence + short digest of the user
    // id so paired write/read logs prove the same account scope without
    // printing the id itself.
    private fun scopeDigest(userId: String): String {
        val trimmed = userId.trim()
        if (trimmed.isBlank()) return "blank"
        return "len${trimmed.length}:${trimmed.hashCode().toUInt().toString(16).takeLast(6)}"
    }

    private companion object {
        const val LogTag = "SlockMembersCollapse"
        const val StoragePrefix = "slock:sidebarCollapsed"
        const val MachineGroupSuffix = "agent-machine-group"
    }
}
