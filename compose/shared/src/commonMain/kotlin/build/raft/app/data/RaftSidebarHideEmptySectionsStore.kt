package build.raft.app.data

/**
 * Web "Display > Hide Empty Sections" sidebar preference
 * (packages/web/src/components/layout/sidebarEmptySectionVisibility.ts). Like
 * web it is a single device-local display choice — not per server and not
 * synced through the sidebar-order API — stored under web's localStorage key.
 *
 * Web defaults it off in the browser and on in the desktop shell. Mobile
 * defaults it on: the phone sidebar has always hidden an empty Pinned / Joint
 * Channels section, and keeping that look until the user opts out avoids
 * surprise empty headers.
 */
const val RaftSidebarHideEmptySectionsDefault: Boolean = true

internal const val RaftSidebarHideEmptySectionsStorageKey = "slock:sidebarHideEmptySections"

internal interface RaftSidebarHideEmptySectionsStore {
    fun read(): Boolean
    fun write(hide: Boolean)
}

internal class KeyValueRaftSidebarHideEmptySectionsStore(
    private val store: SharedPreferenceStore
) : RaftSidebarHideEmptySectionsStore {

    override fun read(): Boolean =
        runCatching {
            when (store.getString(RaftSidebarHideEmptySectionsStorageKey)) {
                "true" -> true
                "false" -> false
                else -> RaftSidebarHideEmptySectionsDefault
            }
        }.getOrDefault(RaftSidebarHideEmptySectionsDefault)

    override fun write(hide: Boolean) {
        runCatching { store.putString(RaftSidebarHideEmptySectionsStorageKey, hide.toString()) }
    }
}
