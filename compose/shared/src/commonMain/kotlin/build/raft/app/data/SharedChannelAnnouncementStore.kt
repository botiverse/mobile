package build.raft.app.data

import build.raft.app.data.realtime.RealtimeLocalEvents
import build.raft.app.model.ActivityChanged
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Channel announcements' store (web `packages/web/src/store/channelAnnouncementStore.ts`, #14039):
 * each shown channel's announcement as this person sees it, for the strip above the timeline and
 * the Announcement row in the channel details, which both read this one slice.
 *
 * Its own slice, not the channel row: `GET channels` (the list) doesn't carry it and a new version
 * changes the announcement without changing any channel row. Read from
 * `GET channels/:id/announcement`; written back from the answer of `PATCH channels/:id` (set,
 * replace, remove). Read again (web `useChannelAnnouncement`):
 *  - when the channel row changes (`channel:updated`: setting, replacing or removing it changes
 *    the row), and on a reconnect that could not replay what it missed;
 *  - on `activity:changed` for its server (a new version, for those who follow the Artifact; and
 *    the person's own open of it, which marks it seen);
 *  - when its change line arrives in the channel (`message:new` with the notice);
 *  - while shown, every [CHANNEL_ANNOUNCEMENT_POLL_MILLIS] and when the app comes back (the UI asks).
 *
 * There is no announcement event on the wire (botiverse/slock 6a848d0a: the server emits nothing
 * of its own for a set or a new version); these are the events the web reads it again on.
 */

/** How often a shown channel reads its announcement again (web `CHANNEL_ANNOUNCEMENT_POLL_MS`). */
const val CHANNEL_ANNOUNCEMENT_POLL_MILLIS: Long = 60_000L

/** One channel's announcement, in one server. */
data class ChannelAnnouncementKey(val serverId: String, val channelId: String)

/**
 * What the store knows of one channel's announcement: [read] false until the first answer (the UI
 * shows nothing then); then [announcement], null for none.
 */
data class ChannelAnnouncementSlice(
    val read: Boolean,
    val announcement: RaftChannelAnnouncement?,
) {
    companion object {
        val Unread = ChannelAnnouncementSlice(read = false, announcement = null)
    }
}

/** A set, replace or remove under way or just finished ([id] tells one attempt from the next). */
data class ChannelAnnouncementMutation(
    val id: Long,
    /** The Artifact being set; null: a removal. */
    val artifactId: String?,
    val status: ChannelAnnouncementMutationStatus,
)

sealed interface ChannelAnnouncementMutationStatus {
    data object Running : ChannelAnnouncementMutationStatus

    data object Succeeded : ChannelAnnouncementMutationStatus

    /** The server's refusal or a failure; the announcement shown is unchanged. [error] carries the trace id. */
    data class Failed(val error: ScreenError) : ChannelAnnouncementMutationStatus
}

data class ChannelAnnouncementState(
    /** By channel: the announcement, null for none; absent until read. */
    val byChannel: Map<ChannelAnnouncementKey, RaftChannelAnnouncement?> = emptyMap(),
    val mutations: Map<ChannelAnnouncementKey, ChannelAnnouncementMutation> = emptyMap(),
)

sealed interface ChannelAnnouncementIntent {
    /** Read it (again). Coalesced per channel: one request at a time, and one more after it when asked meanwhile. */
    data class Load(
        val key: ChannelAnnouncementKey,
        val reason: String,
        val priority: DataRequestPriority,
    ) : ChannelAnnouncementIntent

    /** Set or replace ([artifactId]) or remove (null). Ignored while one is running for that channel. */
    data class Change(val key: ChannelAnnouncementKey, val artifactId: String?) : ChannelAnnouncementIntent

    /** The UI has taken a finished mutation's outcome. */
    data class ConsumeMutation(val key: ChannelAnnouncementKey, val id: Long) : ChannelAnnouncementIntent

    /** A realtime event reached a page that shows [key]: read it again when the event may have changed it. */
    data class Realtime(
        val key: ChannelAnnouncementKey,
        val eventServerId: String,
        val event: RealtimeEvent,
    ) : ChannelAnnouncementIntent

    /** Signed out, or another account: nothing of the last one is kept. */
    data object Reset : ChannelAnnouncementIntent
}

/**
 * Why [event] (for [eventServerId]) means [key]'s announcement may have changed; null when it
 * can't have. The web's triggers (see the file comment), on mobile's realtime events.
 */
internal fun channelAnnouncementRealtimeReloadReason(
    key: ChannelAnnouncementKey,
    eventServerId: String,
    event: RealtimeEvent,
): String? {
    if (eventServerId != key.serverId) return null
    return when (event) {
        is RealtimeRefreshRequired ->
            when {
                event.scope != RealtimeRefreshScope.Home -> null
                event.serverId != null && event.serverId.value != key.serverId -> null
                event.channelId?.value == key.channelId -> "channel_updated"
                event.channelId == null && event.reason == RealtimeLocalEvents.CatchUpRequired -> "catch_up"
                else -> null
            }
        is ActivityChanged -> if (event.serverId.value == key.serverId) "activity_changed" else null
        is MessageNew ->
            if (event.message.channelId?.value == key.channelId && channelAnnouncementNoticeOf(event.message.metadata) != null) {
                "notice_posted"
            } else {
                null
            }
        else -> null
    }
}

internal class SharedChannelAnnouncementStore(
    private val repository: ChannelAnnouncementRepository,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ChannelAnnouncementState())
    val state: StateFlow<ChannelAnnouncementState> = mutableState.asStateFlow()

    private val lock = SynchronizedObject()
    private val generations = mutableMapOf<ChannelAnnouncementKey, Long>()
    private val loading = mutableSetOf<ChannelAnnouncementKey>()
    private val loadAgain = mutableSetOf<ChannelAnnouncementKey>()
    private var epoch = 0L
    private var nextMutationId = 0L

    /** [key]'s slice alone, re-emitted only when it changes. */
    fun slice(key: ChannelAnnouncementKey): Flow<ChannelAnnouncementSlice> =
        state
            .map { current ->
                if (key in current.byChannel) ChannelAnnouncementSlice(read = true, announcement = current.byChannel[key]) else ChannelAnnouncementSlice.Unread
            }
            .distinctUntilChanged()

    /** [key]'s mutation alone, re-emitted only when it changes. */
    fun mutation(key: ChannelAnnouncementKey): Flow<ChannelAnnouncementMutation?> =
        state.map { it.mutations[key] }.distinctUntilChanged()

    fun current(key: ChannelAnnouncementKey): ChannelAnnouncementSlice {
        val current = state.value
        return if (key in current.byChannel) ChannelAnnouncementSlice(read = true, announcement = current.byChannel[key]) else ChannelAnnouncementSlice.Unread
    }

    fun dispatch(intent: ChannelAnnouncementIntent) {
        when (intent) {
            is ChannelAnnouncementIntent.Load -> load(intent.key, intent.reason, intent.priority)
            is ChannelAnnouncementIntent.Change -> change(intent.key, intent.artifactId)
            is ChannelAnnouncementIntent.ConsumeMutation ->
                mutableState.update { current ->
                    val held = current.mutations[intent.key]
                    if (held == null || held.id != intent.id || held.status == ChannelAnnouncementMutationStatus.Running) {
                        current
                    } else {
                        current.copy(mutations = current.mutations - intent.key)
                    }
                }
            is ChannelAnnouncementIntent.Realtime -> {
                val reason = channelAnnouncementRealtimeReloadReason(intent.key, intent.eventServerId, intent.event) ?: return
                load(intent.key, reason, DataRequestPriority.Background)
            }
            ChannelAnnouncementIntent.Reset -> {
                synchronized(lock) {
                    epoch += 1
                    generations.clear()
                    loadAgain.clear()
                }
                mutableState.value = ChannelAnnouncementState()
            }
        }
    }

    private fun nextGeneration(key: ChannelAnnouncementKey): Long {
        val next = (generations[key] ?: 0L) + 1L
        generations[key] = next
        return next
    }

    /** Applies an answer still the newest for its channel, from before no reset. */
    private fun applyIfCurrent(key: ChannelAnnouncementKey, generation: Long, startedEpoch: Long, announcement: RaftChannelAnnouncement?): Boolean {
        val current = synchronized(lock) { generations[key] == generation && epoch == startedEpoch }
        if (!current) return false
        mutableState.update { state ->
            if (key in state.byChannel && state.byChannel[key] == announcement) state else state.copy(byChannel = state.byChannel + (key to announcement))
        }
        return true
    }

    private fun load(key: ChannelAnnouncementKey, reason: String, priority: DataRequestPriority) {
        val start =
            synchronized(lock) {
                if (key in loading) {
                    loadAgain += key
                    false
                } else {
                    loading += key
                    true
                }
            }
        if (!start) return
        scope.launch {
            try {
                var why = reason
                while (true) {
                    val (generation, startedEpoch) = synchronized(lock) {
                        loadAgain -= key
                        nextGeneration(key) to epoch
                    }
                    when (val result = repository.read(ServerId(key.serverId), ChannelId(key.channelId), priority)) {
                        is DataResult.Content -> applyIfCurrent(key, generation, startedEpoch, result.data.announcement)
                        // A failure keeps what is shown (web: `() => {}`).
                        else -> logger("channel_announcement_load_failed channel=${key.channelId} reason=$why result=${result::class.simpleName}")
                    }
                    val again = synchronized(lock) { key in loadAgain }
                    if (!again) break
                    why = "coalesced"
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                synchronized(lock) {
                    loading -= key
                    loadAgain -= key
                }
            }
        }
    }

    private fun change(key: ChannelAnnouncementKey, artifactId: String?) {
        val started =
            synchronized(lock) {
                if (mutableState.value.mutations[key]?.status == ChannelAnnouncementMutationStatus.Running) return
                nextMutationId += 1
                Triple(nextMutationId, nextGeneration(key), epoch)
            }
        val (id, generation, startedEpoch) = started
        mutableState.update { it.copy(mutations = it.mutations + (key to ChannelAnnouncementMutation(id, artifactId, ChannelAnnouncementMutationStatus.Running))) }
        scope.launch {
            val status =
                when (val result = repository.update(ServerId(key.serverId), ChannelId(key.channelId), artifactId)) {
                    is DataResult.Content -> {
                        applyIfCurrent(key, generation, startedEpoch, result.data.announcement)
                        ChannelAnnouncementMutationStatus.Succeeded
                    }
                    is DataResult.Error -> ChannelAnnouncementMutationStatus.Failed(result.error)
                    else -> ChannelAnnouncementMutationStatus.Failed(ScreenError("channel_announcement_failed", retryable = true))
                }
            if (status is ChannelAnnouncementMutationStatus.Failed) {
                logger("channel_announcement_change_failed channel=${key.channelId} code=${status.error.code} traceId=${status.error.traceId}")
            }
            mutableState.update { state ->
                if (state.mutations[key]?.id != id) state else state.copy(mutations = state.mutations + (key to ChannelAnnouncementMutation(id, artifactId, status)))
            }
        }
    }
}
