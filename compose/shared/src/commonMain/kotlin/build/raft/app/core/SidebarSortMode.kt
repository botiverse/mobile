package build.raft.app

import kotlinx.serialization.Serializable

@Serializable
enum class RaftSidebarSortMode(val wireValue: String) {
    Manual("manual"),
    Recent("recent"),
    Az("az");

    companion object {
        fun fromWireValue(value: String?): RaftSidebarSortMode =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: Manual
    }
}
