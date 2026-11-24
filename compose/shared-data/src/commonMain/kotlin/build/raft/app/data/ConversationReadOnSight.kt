package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The conversations a page is reading on sight right now: shown, at its live tail, and sending
 * its visible reads ([build.raft.app.SharedThreadStore]'s visible read). A live message there is
 * read on arrival (web `liveActivityUnread` `readOnArrival`); anywhere else it counts, as the
 * server counts it.
 *
 * The shell's selected conversation is not enough for that: it stays selected while its page is
 * covered by another page opened from it, scrolled up from the tail, held unread, or still
 * loading, and in all of those the page sends no read, so the server keeps counting what
 * Activity had shown as read (`activity:inplace_check` `unread=0/N`, trigger `thread_reply`).
 *
 * Keyed by the message stream channel (a reply thread's own channel, a channel's or DM's id) and
 * by the page that reads it, so one page leaving does not end another's read of the same channel.
 */
@RaftCoreInternalApi class ConversationReadOnSight {
    private val lock = SynchronizedObject()
    private val readingByOwner = mutableMapOf<Any, String>()

    /** [owner] reads [streamChannelId] on sight from now on (replacing what it read before). */
    fun show(owner: Any, streamChannelId: String) {
        val channelId = streamChannelId.trim().takeIf { it.isNotEmpty() } ?: return hide(owner)
        synchronized(lock) { readingByOwner[owner] = channelId }
    }

    /** [owner] no longer reads anything on sight. */
    fun hide(owner: Any) {
        synchronized(lock) { readingByOwner.remove(owner) }
    }

    fun isReading(streamChannelId: String): Boolean =
        synchronized(lock) { streamChannelId in readingByOwner.values }

    /** The stream channels read on sight now. */
    fun readingChannels(): Set<String> = synchronized(lock) { readingByOwner.values.toSet() }
}

/** The process' pages (every shell window): what [SharedActivityStore] asks in the app. */
@RaftCoreInternalApi val raftConversationReadOnSight: ConversationReadOnSight = ConversationReadOnSight()
