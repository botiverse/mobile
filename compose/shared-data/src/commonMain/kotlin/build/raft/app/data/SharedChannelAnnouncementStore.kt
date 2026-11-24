package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ActivityChanged
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/*
 * Channel announcements' store (web `packages/web/src/store/channelAnnouncementStore.ts`, #14039):
 * each shown channel's announcement as this person sees it, for the strip above the timeline and
 * the Announcement row in the channel details, which both read this one slice.
 *
 * Its own slice, not the channel row: `GET channels` (the list) doesn't carry it and a new version
 * changes the announcement without changing any channel row. Read from
 * `GET channels/:id/announcement`; written back from the answer of `PATCH channels/:id` (set,
 * replace, remove).
 *
 * When it is read: on events only, never on a timer (web `useChannelAnnouncement`, botiverse/slock
 * #16289 at 4bb09d45d; the numbers are the rows of that PR's trigger table). "Held" is the answer
 * last applied (a read or our own change), "last read" when it was applied.
 *  1. A page opens the channel ([ChannelAnnouncementIntent.Open]): read, unless an answer is held
 *     and was applied less than [CHANNEL_ANNOUNCEMENT_OPEN_FRESH_MILLIS] ago. One read at a time
 *     per channel.
 *  2-4. The announcement the channel's row names: `channel:updated` carries the row as stored, its
 *     `announcementArtifactId` included, after every `PATCH channels/:id`; the change line a set,
 *     replace or remove posts (`message:new` with the notice) names it too. Kept for every channel
 *     (the shell's realtime owner feeds the store whichever page is open, as the web's socket bridge
 *     feeds its channel rows). An open channel reads when the id named (none included) is not the
 *     held one, or nothing is held; another change of the row (a rename, messages, unread counts,
 *     activity) or a frame naming nothing never reads.
 *  5. Such a change, or its Artifact viewed (7), while a read is in flight that predates it: one
 *     more read after that one ends.
 *  6. No timer.
 *  7-8. Its Artifact left in the in-app viewer ([ChannelAnnouncementIntent.ArtifactViewed]: it is
 *     seen now), while the app is in front: at once; another Artifact: nothing.
 *  9-10. `activity:changed` of its server (a new version, for those who follow the Artifact), while
 *     the app is in front: only if the last read is [CHANNEL_ANNOUNCEMENT_STALE_MILLIS] old or more.
 *     Another server's: nothing.
 *  11. The page in front again ([ChannelAnnouncementIntent.Visible]): the same 5-minute gate.
 *  12. Its server's socket back ([ChannelAnnouncementIntent.Reconnected]): one read, however recent
 *     the last one (a `channel:updated` sent while it was down is not replayed). After a drop the app
 *     did not cause (a deploy drops every client at once) the read first waits a random moment under
 *     [CHANNEL_ANNOUNCEMENT_RECONNECT_JITTER_MILLIS], as the web's reconnect reload does; a read asked
 *     after the socket came back and answered meanwhile makes it needless.
 *  13. An answer dropped (older than a newer read or change, or from before a reset) is no read.
 *  14. [ChannelAnnouncementIntent.Reset] drops held answers, read times, reads in flight and queued.
 *
 * Where mobile differs, and why: `activity:changed` carries no Artifact id (`{ serverId, source }`),
 * so it is always the server-wide one; the web's own-Artifact invalidation is its viewer's read of
 * the latest version, and mobile's viewer is a web page of its own, so 7 runs when the viewer leaves
 * the Artifact. The web reads the row of a single-channel read too; nothing here reads that row, so
 * `channel:updated` and the change line are the row. 12 comes from the app's freshness signal (a new
 * socket connection of the server): the wait only after a drop the app did not cause
 * (RaftRealtimeConnectionCauses.kt); a connection the app opened (back in the foreground after it
 * closed the socket in the background) reads at once, and a page shown before the socket's first
 * connection reads once more when it connects. A server switch needs no reset: a key names its
 * server, so no answer can land on another server's channel.
 */

/** A channel opened again within this of its last read is not read again (web `CHANNEL_ANNOUNCEMENT_OPEN_FRESH_MS`). */
const val CHANNEL_ANNOUNCEMENT_OPEN_FRESH_MILLIS: Long = 30_000L

/** The page in front again, or `activity:changed`, reads only when the last read is at least this old (web `CHANNEL_ANNOUNCEMENT_STALE_MS`). */
const val CHANNEL_ANNOUNCEMENT_STALE_MILLIS: Long = 5 * 60_000L

/**
 * After a drop the app did not cause, the reconnect read waits a uniform random time below this (web
 * socketBridge `RECONNECT_RELOAD_JITTER_MS_DEFAULT`): every client a deploy dropped reconnects within
 * seconds, and their reads spread out instead of landing in the same second.
 */
const val CHANNEL_ANNOUNCEMENT_RECONNECT_JITTER_MILLIS: Long = 20_000L

/** One channel's announcement, in one server. */
data class ChannelAnnouncementKey(val serverId: String, val channelId: String)

/**
 * What the store knows of one channel's announcement: [read] false until the first answer (the UI
 * shows nothing then); then [announcement], null for none, and [unavailable] when a sharing server
 * on a joint channel has pinning off (botiverse/slock #16305).
 */
data class ChannelAnnouncementSlice(
    val read: Boolean,
    val announcement: RaftChannelAnnouncement?,
    val unavailable: ChannelAnnouncementUnavailableReason? = null,
) {
    companion object {
        val Unread = ChannelAnnouncementSlice(read = false, announcement = null, unavailable = null)
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
    /** By channel: why it can't have one now, from the same answer; absent when it can (or an older server). */
    val unavailableByChannel: Map<ChannelAnnouncementKey, ChannelAnnouncementUnavailableReason> = emptyMap(),
    val mutations: Map<ChannelAnnouncementKey, ChannelAnnouncementMutation> = emptyMap(),
)

sealed interface ChannelAnnouncementIntent {
    /**
     * A page shows [key] (web: `useChannelAnnouncement` mounts): rule 1, then the announcement its
     * row names (rule 3). Counted until the matching [Close]: the strip and the details row may show
     * one channel at once.
     */
    data class Open(val key: ChannelAnnouncementKey) : ChannelAnnouncementIntent

    data class Close(val key: ChannelAnnouncementKey) : ChannelAnnouncementIntent

    /** The page showing [key] is in front again: the app back from the background, or the page uncovered (rule 11). */
    data class Visible(val key: ChannelAnnouncementKey) : ChannelAnnouncementIntent

    /**
     * The realtime socket of [key]'s server joined its rooms again while a page shows [key] (rule 12).
     * [afterDrop]: it came back by itself after a drop the app did not cause, so the read waits first.
     */
    data class Reconnected(val key: ChannelAnnouncementKey, val afterDrop: Boolean = false) : ChannelAnnouncementIntent

    /** The in-app Artifact viewer left [artifactId] of [serverId]: the person has seen it (rules 7 and 8). */
    data class ArtifactViewed(val serverId: String, val artifactId: String) : ChannelAnnouncementIntent

    /** An accepted realtime event of [serverId], whichever page is open (rules 2-4, 9 and 10). */
    data class Realtime(val serverId: String, val event: RealtimeEvent) : ChannelAnnouncementIntent

    /** Set or replace ([artifactId]) or remove (null). Ignored while one is running for that channel. */
    data class Change(val key: ChannelAnnouncementKey, val artifactId: String?) : ChannelAnnouncementIntent

    /** The UI has taken a finished mutation's outcome. */
    data class ConsumeMutation(val key: ChannelAnnouncementKey, val id: Long) : ChannelAnnouncementIntent

    /** Signed out, or another account: nothing of the last one is kept (rule 14). */
    data object Reset : ChannelAnnouncementIntent
}

@RaftCoreInternalApi class SharedChannelAnnouncementStore(
    private val repository: ChannelAnnouncementRepository,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    /** Whether the app is in front (the web's page visibility): `activity:changed` and a viewed Artifact read only then. */
    private val foreground: StateFlow<Boolean> = RaftProcessForegroundGate.foreground,
    /** Monotonic: the 30-second and 5-minute gates must not follow wall-clock changes. */
    private val nowMillis: () -> Long = ::raftForegroundGateNowMillis,
    /** The wait before a read after a drop ([ChannelAnnouncementIntent.Reconnected.afterDrop]), a fresh one each time. */
    private val reconnectJitterMillis: () -> Long = { Random.nextLong(CHANNEL_ANNOUNCEMENT_RECONNECT_JITTER_MILLIS) },
) {
    private val mutableState = MutableStateFlow(ChannelAnnouncementState())
    val state: StateFlow<ChannelAnnouncementState> = mutableState.asStateFlow()

    private val lock = SynchronizedObject()

    /** Every answer older than the latest read or change of its channel is dropped. */
    private val generations = mutableMapOf<ChannelAnnouncementKey, Long>()

    /** The read in flight per channel, by number: an older read ending after a reset leaves a newer one alone. */
    private val inFlight = mutableMapOf<ChannelAnnouncementKey, Long>()

    /** What the channel's row named when its read in flight was asked (null: nothing). */
    private val askedFor = mutableMapOf<ChannelAnnouncementKey, RaftChannelRowAnnouncement?>()

    /** Channels with a change newer than their read in flight: one more read when it ends. */
    private val dirty = mutableSetOf<ChannelAnnouncementKey>()

    /** When an answer was last applied ([nowMillis]). */
    private val lastReadAt = mutableMapOf<ChannelAnnouncementKey, Long>()

    /** The announcement each channel's row last named; absent: nothing said yet. */
    private val rowNames = mutableMapOf<ChannelAnnouncementKey, RaftChannelRowAnnouncement>()

    /** How many pages show each channel. */
    private val shown = mutableMapOf<ChannelAnnouncementKey, Int>()
    /** Reads waiting out the jitter after a drop, per channel, with the last read number from before it. */
    private val waitingReconnects = mutableMapOf<ChannelAnnouncementKey, WaitingReconnect>()
    private var nextReadId = 0L
    private var epoch = 0L
    private var nextMutationId = 0L

    /** [key]'s slice alone, re-emitted only when it changes. */
    fun slice(key: ChannelAnnouncementKey): Flow<ChannelAnnouncementSlice> =
        state
            .map { current ->
                if (key in current.byChannel) {
                    ChannelAnnouncementSlice(
                        read = true,
                        announcement = current.byChannel[key],
                        unavailable = current.unavailableByChannel[key],
                    )
                } else {
                    ChannelAnnouncementSlice.Unread
                }
            }
            .distinctUntilChanged()

    /** [key]'s mutation alone, re-emitted only when it changes. */
    fun mutation(key: ChannelAnnouncementKey): Flow<ChannelAnnouncementMutation?> =
        state.map { it.mutations[key] }.distinctUntilChanged()

    fun current(key: ChannelAnnouncementKey): ChannelAnnouncementSlice {
        val current = state.value
        return if (key in current.byChannel) {
            ChannelAnnouncementSlice(
                read = true,
                announcement = current.byChannel[key],
                unavailable = current.unavailableByChannel[key],
            )
        } else {
            ChannelAnnouncementSlice.Unread
        }
    }

    fun dispatch(intent: ChannelAnnouncementIntent) {
        when (intent) {
            is ChannelAnnouncementIntent.Open -> open(intent.key)
            is ChannelAnnouncementIntent.Close -> close(intent.key)
            is ChannelAnnouncementIntent.Visible ->
                if (isShown(intent.key) && !readWithin(intent.key, CHANNEL_ANNOUNCEMENT_STALE_MILLIS)) load(intent.key, "visible")
            // However recent the last read: what was pushed while the socket was down is lost.
            is ChannelAnnouncementIntent.Reconnected ->
                when {
                    !isShown(intent.key) -> Unit
                    intent.afterDrop -> reconnectAfterJitter(intent.key)
                    else -> load(intent.key, "reconnect")
                }
            is ChannelAnnouncementIntent.ArtifactViewed ->
                if (foreground.value) {
                    shownKeys { it.serverId == intent.serverId && heldArtifactId(it) == intent.artifactId }
                        .forEach { load(it, "artifact_viewed", again = true) }
                }
            is ChannelAnnouncementIntent.Realtime -> realtime(intent.serverId, intent.event)
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
            ChannelAnnouncementIntent.Reset -> {
                val cancelled =
                    synchronized(lock) {
                        val jobs = waitingReconnects.values.map { it.job }
                        waitingReconnects.clear()
                        epoch += 1
                        generations.clear()
                        inFlight.clear()
                        askedFor.clear()
                        dirty.clear()
                        lastReadAt.clear()
                        rowNames.clear()
                        mutableState.value = ChannelAnnouncementState()
                        jobs
                    }
                cancelled.forEach { it.cancel() }
            }
        }
    }

    private fun open(key: ChannelAnnouncementKey) {
        val named =
            synchronized(lock) {
                shown[key] = (shown[key] ?: 0) + 1
                rowNames[key]
            }
        if (!(isHeld(key) && readWithin(key, CHANNEL_ANNOUNCEMENT_OPEN_FRESH_MILLIS))) {
            load(key, "open", DataRequestPriority.Visible)
        }
        // The row as it is now: a change that landed while the channel was closed.
        readIfRowDiffers(key, named)
    }

    private fun close(key: ChannelAnnouncementKey) {
        val cancelled =
            synchronized(lock) {
                val count = (shown[key] ?: return) - 1
                if (count > 0) {
                    shown[key] = count
                    null
                } else {
                    shown.remove(key)
                    // No page shows it: nothing reads it when the socket comes back.
                    waitingReconnects.remove(key)?.job
                }
            }
        cancelled?.cancel()
    }

    /**
     * The socket came back by itself after a drop: one read of [key] after a random wait, then as the
     * immediate one (sharing a read in flight), held while the app is in the background as the web holds
     * its reconnect reload while hidden. A second drop meanwhile changes nothing; a read asked after this
     * one and answered while it waits makes it needless ([load]); so does the page closing, or a reset.
     */
    private fun reconnectAfterJitter(key: ChannelAnnouncementKey) {
        synchronized(lock) {
            if ((shown[key] ?: 0) <= 0 || key in waitingReconnects) return
        }
        val wait = reconnectJitterMillis().coerceIn(0L, CHANNEL_ANNOUNCEMENT_RECONNECT_JITTER_MILLIS)
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(wait)
                    foreground.first { it }
                    val expectedEpoch =
                        synchronized(lock) {
                            val waiting = waitingReconnects[key]
                            if (waiting == null || waiting.job !== coroutineContext[Job]) {
                                null
                            } else {
                                waitingReconnects.remove(key)
                                if ((shown[key] ?: 0) > 0 && epoch == waiting.epoch) waiting.epoch else null
                            }
                        }
                    if (expectedEpoch != null) load(key, "reconnect_after_drop", expectedEpoch = expectedEpoch)
                } finally {
                    synchronized(lock) {
                        if (waitingReconnects[key]?.job === coroutineContext[Job]) {
                            waitingReconnects.remove(key)
                        }
                    }
                }
            }
        val registered =
            synchronized(lock) {
                if ((shown[key] ?: 0) <= 0 || key in waitingReconnects) {
                    false
                } else {
                    waitingReconnects[key] = WaitingReconnect(job, readsBefore = nextReadId, epoch = epoch)
                    true
                }
            }
        if (registered) job.start() else job.cancel()
    }

    private fun realtime(serverId: String, event: RealtimeEvent) {
        when (event) {
            is RealtimeRefreshRequired -> {
                if (event.reason != RealtimeSocketEvents.ChannelUpdated) return
                val named = event.channelAnnouncement ?: return
                val channelId = event.channelId?.value ?: return
                rowNamed(ChannelAnnouncementKey(event.serverId?.value ?: serverId, channelId), named)
            }
            is MessageNew -> {
                val notice = channelAnnouncementNoticeOf(event.message.metadata) ?: return
                val channelId = event.message.channelId?.value ?: return
                // A removal names the Artifact it removed: the row names none now.
                val named = notice.artifactId.takeUnless { notice.change == ChannelAnnouncementChangeKind.Removed }
                rowNamed(ChannelAnnouncementKey(serverId, channelId), RaftChannelRowAnnouncement(named))
            }
            // Comes often on a busy server: a shown channel reads at most once per 5 minutes.
            is ActivityChanged -> {
                if (!foreground.value) return
                shownKeys { it.serverId == event.serverId.value }
                    .filterNot { readWithin(it, CHANNEL_ANNOUNCEMENT_STALE_MILLIS) }
                    .forEach { load(it, "activity_changed") }
            }
            else -> Unit
        }
    }

    /** [key]'s row names [named]: kept, and read if it changed while a page shows the channel. */
    private fun rowNamed(key: ChannelAnnouncementKey, named: RaftChannelRowAnnouncement) {
        val evaluate =
            synchronized(lock) {
                val before = rowNames.put(key, named)
                before != named && (shown[key] ?: 0) > 0
            }
        if (evaluate) readIfRowDiffers(key, named)
    }

    /**
     * The row names [named] (null: nothing): read when that is not the held announcement (none held
     * counts as different), or, with a read in flight, when the row named another one as it was asked.
     */
    private fun readIfRowDiffers(key: ChannelAnnouncementKey, named: RaftChannelRowAnnouncement?) {
        if (named == null) return
        val read =
            synchronized(lock) {
                if (key in inFlight) {
                    if (askedFor[key] != named) dirty += key
                    false
                } else {
                    val held = mutableState.value.byChannel
                    key !in held || held[key]?.artifactId != named.artifactId
                }
            }
        if (read) load(key, "row_changed")
    }

    /**
     * Reads [key]'s announcement, one request at a time per channel; a failure keeps what is shown.
     * [again]: the trigger is newer than a read in flight (a change), so one more read follows that
     * one instead of sharing its answer.
     */
    private fun load(
        key: ChannelAnnouncementKey,
        reason: String,
        priority: DataRequestPriority = DataRequestPriority.Background,
        again: Boolean = false,
        expectedEpoch: Long? = null,
    ) {
        val started =
            synchronized(lock) {
                if (expectedEpoch != null && epoch != expectedEpoch) return
                if (key in inFlight) {
                    if (again) dirty += key
                    return
                }
                nextReadId += 1
                inFlight[key] = nextReadId
                askedFor[key] = rowNames[key]
                Triple(nextReadId, nextGeneration(key), epoch)
            }
        val (readId, generation, startedEpoch) = started
        scope.launch {
            var followUp = false
            try {
                when (val result = repository.read(ServerId(key.serverId), ChannelId(key.channelId), priority)) {
                    is DataResult.Content ->
                        if (applyIfCurrent(key, generation, startedEpoch, result.data)) {
                            // Asked after the socket came back: the read waiting out its jitter is needless.
                            cancelWaitingReconnectIfSuperseded(key, readId)
                        }
                    // A failure keeps what is shown (web: `() => {}`).
                    else -> logger("channel_announcement_load_failed channel=${key.channelId} reason=$reason result=${result::class.simpleName}")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                synchronized(lock) {
                    // A reset since: a newer read (or none) is the channel's now.
                    if (inFlight[key] == readId) {
                        inFlight.remove(key)
                        askedFor.remove(key)
                        followUp = dirty.remove(key)
                    }
                }
            }
            if (followUp) load(key, "changed_meanwhile")
        }
    }

    private fun change(key: ChannelAnnouncementKey, artifactId: String?) {
        val started =
            synchronized(lock) {
                if (mutableState.value.mutations[key]?.status == ChannelAnnouncementMutationStatus.Running) return
                nextMutationId += 1
                nextReadId += 1
                ChangeStart(nextMutationId, nextReadId, nextGeneration(key), epoch)
            }
        val (id, requestId, generation, startedEpoch) = started
        mutableState.update { it.copy(mutations = it.mutations + (key to ChannelAnnouncementMutation(id, artifactId, ChannelAnnouncementMutationStatus.Running))) }
        scope.launch {
            val status =
                when (val result = repository.update(ServerId(key.serverId), ChannelId(key.channelId), artifactId)) {
                    is DataResult.Content -> {
                        // Our own update's answer names the announcement on the row as well (web: it patches the row).
                        if (applyIfCurrent(key, generation, startedEpoch, result.data)) {
                            synchronized(lock) { rowNames[key] = RaftChannelRowAnnouncement(result.data.announcement?.artifactId) }
                            cancelWaitingReconnectIfSuperseded(key, requestId)
                        }
                        ChannelAnnouncementMutationStatus.Succeeded
                    }
                    is DataResult.Error -> {
                        // Refused as disabled (a sharing server turned it off since the last read):
                        // read again at once so details shows why instead of a pin that can't work (slock #16305).
                        if (
                            result.error.serverCode == CHANNEL_ANNOUNCEMENT_DISABLED_CODE ||
                            result.error.code == CHANNEL_ANNOUNCEMENT_DISABLED_CODE
                        ) {
                            load(key, "disabled_refusal", again = true)
                        }
                        ChannelAnnouncementMutationStatus.Failed(result.error)
                    }
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

    private fun cancelWaitingReconnectIfSuperseded(key: ChannelAnnouncementKey, requestId: Long) {
        val cancelled =
            synchronized(lock) {
                val waiting = waitingReconnects[key]
                if (waiting != null && requestId > waiting.readsBefore) {
                    waitingReconnects.remove(key)
                    waiting.job
                } else {
                    null
                }
            }
        cancelled?.cancel()
    }

    /** A read waiting out its jitter, the last read number before it, and the reset epoch it belonged to. */
    private class WaitingReconnect(val job: Job, val readsBefore: Long, val epoch: Long)

    private data class ChangeStart(val id: Long, val requestId: Long, val generation: Long, val epoch: Long)

    private fun nextGeneration(key: ChannelAnnouncementKey): Long {
        val next = (generations[key] ?: 0L) + 1L
        generations[key] = next
        return next
    }

    /** Applies an answer still the newest for its channel and from before no reset; only then is it a read (rule 13). */
    private fun applyIfCurrent(
        key: ChannelAnnouncementKey,
        generation: Long,
        startedEpoch: Long,
        read: ChannelAnnouncementRead,
    ): Boolean {
        synchronized(lock) {
            if (generations[key] != generation || epoch != startedEpoch) return false
            lastReadAt[key] = nowMillis()
            mutableState.update { state ->
                if (
                    key in state.byChannel &&
                    state.byChannel[key] == read.announcement &&
                    state.unavailableByChannel[key] == read.unavailable
                ) {
                    state
                } else {
                    val nextUnavailable =
                        if (read.unavailable != null) {
                            state.unavailableByChannel + (key to read.unavailable)
                        } else {
                            state.unavailableByChannel - key
                        }
                    state.copy(
                        byChannel = state.byChannel + (key to read.announcement),
                        unavailableByChannel = nextUnavailable,
                    )
                }
            }
        }
        return true
    }

    private fun isShown(key: ChannelAnnouncementKey): Boolean = synchronized(lock) { (shown[key] ?: 0) > 0 }

    private fun shownKeys(predicate: (ChannelAnnouncementKey) -> Boolean): List<ChannelAnnouncementKey> = synchronized(lock) { shown.keys.filter(predicate) }

    private fun isHeld(key: ChannelAnnouncementKey): Boolean = key in mutableState.value.byChannel

    private fun heldArtifactId(key: ChannelAnnouncementKey): String? = mutableState.value.byChannel[key]?.artifactId

    /** Whether an answer for [key] was applied within [millis]. */
    private fun readWithin(key: ChannelAnnouncementKey, millis: Long): Boolean {
        val at = synchronized(lock) { lastReadAt[key] } ?: return false
        return nowMillis() - at < millis
    }
}
