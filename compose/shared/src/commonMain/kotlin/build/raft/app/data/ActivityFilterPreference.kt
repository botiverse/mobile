package build.raft.app.data

/**
 * The filter last chosen in Activity's Filter menu, kept per server on this device (web
 * `store/activityFilterPreference.ts`, slock #11383, stdrc option A): coming back to the
 * server, switching to it or restarting the app shows the same list. Only the menu's four
 * choices are kept; a blank server id keeps nothing, and anything else stored (an old or
 * hand-edited value) reads as All. Choosing All removes the key, as the web does.
 */
class ActivityFilterPreferenceRepository(private val store: SharedPreferenceStore) {
    fun read(serverId: String?): InboxFilter {
        val key = key(serverId) ?: return InboxFilter.All
        return parseActivityFilterChoice(runCatching { store.getString(key) }.getOrNull())
    }

    fun write(serverId: String?, filter: InboxFilter) {
        val key = key(serverId) ?: return
        // Storage full or blocked: the choice holds until the app restarts.
        runCatching { store.putString(key, filter.takeUnless { it == InboxFilter.All }?.preferenceValue()) }
    }

    private fun key(serverId: String?): String? =
        serverId?.trim()?.takeIf { it.isNotEmpty() }?.let { "$KeyPrefix$it" }

    companion object {
        /** The web's localStorage prefix, so the two read alike in a diagnostics dump. */
        const val KeyPrefix = "raft.activity.filter."
    }
}

/** The stored form of a choice: the web's `ActivityFilterChoice` strings. */
internal fun InboxFilter.preferenceValue(): String =
    when (this) {
        InboxFilter.All -> "all"
        InboxFilter.Unread -> "unread"
        InboxFilter.Mentions -> "mentions"
        InboxFilter.Pinned -> "pinned"
    }

/** web `parseActivityFilterChoice`: one of the four, else All. */
internal fun parseActivityFilterChoice(value: String?): InboxFilter =
    InboxFilter.entries.firstOrNull { it.preferenceValue() == value } ?: InboxFilter.All
