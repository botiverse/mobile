package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One instance per authenticated account/environment/server lifetime. */
internal class SharedAnnouncementStore(
    private val client: RaftAnnouncementsRemindersApiClient,
    private val isCurrentOwner: () -> Boolean,
) {
    private val mutableAnnouncement = MutableStateFlow<AnnouncementItem?>(null)
    val announcement = mutableAnnouncement.asStateFlow()
    private var requestGeneration = 0L
    private var closed = false
    private val dismissed = mutableSetOf<String>()

    suspend fun refresh() {
        if (closed || !isCurrentOwner()) return
        val generation = ++requestGeneration
        val result = client.activeAnnouncements()
        if (closed || !isCurrentOwner() || generation != requestGeneration) return
        // A refresh failure must not erase an already readable announcement.
        if (result is DataResult.Content) {
            mutableAnnouncement.value = result.data.items.firstOrNull { it.id !in dismissed }
        }
    }

    /** Clear synchronously so double taps and pre-dismiss requests cannot reopen it. */
    fun dismiss(id: String): Boolean {
        if (closed || !isCurrentOwner() || mutableAnnouncement.value?.id != id) return false
        ++requestGeneration
        dismissed += id
        mutableAnnouncement.value = null
        return true
    }

    suspend fun persistDismissal(id: String) {
        if (!closed && isCurrentOwner() && id in dismissed) client.dismissAnnouncement(id)
    }

    fun close() {
        closed = true
        ++requestGeneration
        mutableAnnouncement.value = null
    }
}
