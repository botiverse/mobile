package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * How the Computers page shows its list on this device, kept like the List / Cards choice
 * ([ComputersRackView]): the grouping (web `store/computersRackFilterPreference.ts`
 * `raft_computers_rack_group`) and the sort (web `store/computersRackSortPreference.ts`
 * `raft_computers_rack_sort`). Per device, not per account or server. The search and the Status
 * and Runtime filters are what someone looks for now, so they are never stored (see
 * `RaftComputersRackToolbarStore`).
 */

/** Web `ComputersRackGroupBy`, in the menu's order (`COMPUTERS_RACK_GROUP_BYS`: none, prefix, provider). */
internal enum class ComputersRackGroupBy(val storageKey: String) {
    None("none"),
    Prefix("prefix"),
    Provider("provider");

    companion object {
        val Default: ComputersRackGroupBy = None

        /** Web `readStoredGroup`: one of the choices, else no grouping. */
        fun fromStorageKey(value: String?): ComputersRackGroupBy = entries.firstOrNull { it.storageKey == value } ?: Default
    }
}

/** Web `ComputersRackSortDirection`. */
internal enum class ComputersRackSortDirection(val wire: String) {
    Asc("asc"),
    Desc("desc"),
}

/** Web `ComputersRackSortKey`: `name`, `disk`, `memory` or `remaining:<provider>`. */
internal sealed interface ComputersRackSortKey {
    val wire: String

    data object Name : ComputersRackSortKey {
        override val wire = "name"
    }

    data object Disk : ComputersRackSortKey {
        override val wire = "disk"
    }

    data object Memory : ComputersRackSortKey {
        override val wire = "memory"
    }

    /** What [provider]'s accounts have left (web `remainingSortKey`). */
    data class Remaining(val provider: RuntimeAccountUsageProvider) : ComputersRackSortKey {
        override val wire: String get() = "remaining:${provider.wire}"
    }

    companion object {
        /** Web `isSortKey`: one of the keys this build knows, else null. */
        fun fromWire(value: String?): ComputersRackSortKey? =
            when (value) {
                Name.wire -> Name
                Disk.wire -> Disk
                Memory.wire -> Memory
                else -> RuntimeAccountUsageProvider.entries.firstOrNull { value == "remaining:${it.wire}" }?.let(::Remaining)
            }
    }
}

/** Web `ComputersRackSort`. */
internal data class ComputersRackSort(val key: ComputersRackSortKey, val direction: ComputersRackSortDirection)

private val rackSortJson = Json { ignoreUnknownKeys = true }

/** Web `JSON.stringify(sort)`: `{"key":"disk","direction":"asc"}`. */
internal fun ComputersRackSort.toStorageValue(): String =
    buildJsonObject {
        put("key", key.wire)
        put("direction", direction.wire)
    }.toString()

/** Web `computersRackSortPreference.ts` `parse`: an old, hand-edited or unknown value reads as no sort (null). */
internal fun parseComputersRackSort(raw: String?): ComputersRackSort? {
    if (raw.isNullOrEmpty()) return null
    val value = runCatching { rackSortJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    val key = ComputersRackSortKey.fromWire((value["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content) ?: return null
    val direction =
        ComputersRackSortDirection.entries.firstOrNull {
            it.wire == (value["direction"] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
        } ?: return null
    return ComputersRackSort(key, direction)
}

/**
 * The Computers page's stored choices: Group, Sort and (through [KeyValueComputersRackViewPreferenceRepository],
 * the same key the page already uses) View. Storage that fails to read reads as the defaults;
 * storage that fails to write keeps the choice for this session only (the toolbar store holds it).
 */
internal class KeyValueComputersRackPreferenceRepository(
    private val store: SharedPreferenceStore,
) : ComputersRackViewPreferenceRepository by KeyValueComputersRackViewPreferenceRepository(store) {
    fun readGroupBy(): ComputersRackGroupBy =
        ComputersRackGroupBy.fromStorageKey(runCatching { store.getString(KEY_GROUP) }.getOrNull())

    fun writeGroupBy(groupBy: ComputersRackGroupBy) {
        runCatching { store.putString(KEY_GROUP, groupBy.storageKey) }
    }

    /** The stored sort, or null for the default (Name A to Z, `raftRackEffectiveSort`). */
    fun readSort(): ComputersRackSort? = parseComputersRackSort(runCatching { store.getString(KEY_SORT) }.getOrNull())

    fun writeSort(sort: ComputersRackSort) {
        runCatching { store.putString(KEY_SORT, sort.toStorageValue()) }
    }

    internal companion object {
        const val KEY_GROUP = "computers:rackGroup"
        const val KEY_SORT = "computers:rackSort"
    }
}
