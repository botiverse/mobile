package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ChannelAuthorityUpdated
import build.raft.app.model.ChannelCapabilities
import build.raft.app.model.ChannelId
import build.raft.app.model.ChannelViewerAuthority
import build.raft.app.model.ChannelViewerAuthorityView
import build.raft.app.model.allows
import build.raft.app.model.canManageChannel
import build.raft.app.model.knownCapabilities
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The four wire fields a per-viewer channel HTTP answer carries (`GET /api/channels`,
 * `GET /api/channels/:id`, `POST /api/channels`, botiverse/slock #17055). Broadcast
 * `channel:updated` rows, PATCH answers, and DM rows omit them.
 */
@RaftCoreInternalApi val CHANNEL_AUTHORITY_WIRE_FIELDS: List<String> =
    listOf(
        "channelRole",
        "channelAdminBasis",
        "channelCapabilities",
        "channelAuthorityRevision",
    )

/** One channel's viewer authority key, scoped to its server. */
@RaftCoreInternalApi data class ChannelAuthorityKey(
    val serverId: String,
    val channelId: String,
)

/** One parsed channel answer row handed to [SharedChannelAuthorityStore.writeAnswers]. */
@RaftCoreInternalApi data class ChannelAuthorityAnswerRow(
    val channelId: String,
    val authority: ChannelViewerAuthority?,
)

/**
 * Monotonic request-start token captured BEFORE sending a per-viewer channel HTTP request
 * (`GET /api/channels`, `GET /api/channels/:id`, `POST /api/channels`).
 *
 * - [observedSeq] orders overlapping requests: an older request settling later never overwrites
 *   an authority stored from a request started after it.
 * - [globalEpoch] and [serverEpoch] fence against logout / account switches and server resets:
 *   an answer that started before a reset is dropped when it settles afterward.
 */
@RaftCoreInternalApi data class ChannelAuthorityRequestToken(
    val serverId: String,
    val observedSeq: Long,
    val globalEpoch: Long,
    val serverEpoch: Long,
)

@RaftCoreInternalApi data class ChannelAuthorityStoreState(
    val byChannel: Map<ChannelAuthorityKey, ChannelViewerAuthority> = emptyMap(),
) {
    fun authorityOf(serverId: String?, channelId: String?): ChannelViewerAuthority? {
        val key = channelAuthorityKeyOrNull(serverId, channelId) ?: return null
        return byChannel[key]
    }

    fun viewOf(serverId: String?, channelId: String?): ChannelViewerAuthorityView =
        authorityOf(serverId, channelId)?.let(ChannelViewerAuthorityView::Known)
            ?: ChannelViewerAuthorityView.Loading

    fun forServer(serverId: String?): Map<String, ChannelViewerAuthority> {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        if (byChannel.isEmpty()) return emptyMap()
        return buildMap {
            byChannel.forEach { (key, authority) ->
                if (key.serverId == cleanServerId) {
                    put(key.channelId, authority)
                }
            }
        }
    }
}

@RaftCoreInternalApi fun channelAuthorityKeyOrNull(serverId: String?, channelId: String?): ChannelAuthorityKey? {
    val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cleanChannel = channelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return ChannelAuthorityKey(serverId = cleanServer, channelId = cleanChannel)
}

/** Web `authorityAllows(view, capability)`: `true` only when known and explicitly allowed. */
@RaftCoreInternalApi fun authorityAllows(
    view: ChannelViewerAuthorityView,
    capability: (ChannelCapabilities) -> Boolean?,
): Boolean = view.allows(capability)

/** Web `knownCapabilities(view)`: what the server said yes/no to; empty (all null) while loading. */
@RaftCoreInternalApi fun knownCapabilities(view: ChannelViewerAuthorityView): ChannelCapabilities =
    view.knownCapabilities()

/** Web `CHANNEL_MANAGE_ENTRY_CAPABILITIES.some(...)`: `false` while loading. */
@RaftCoreInternalApi fun authorityCanManageChannel(view: ChannelViewerAuthorityView): Boolean =
    view.canManageChannel()

private val channelAuthorityJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * Extracts the viewer's channel authority from a raw JSON channel object, or `null` when it
 * carries none (a `channel:updated` broadcast row, a PATCH answer, or a DM row): the server
 * always attaches a `channelCapabilities` object to per-viewer channel answers (slock #17055).
 */
@RaftCoreInternalApi fun readChannelAuthority(row: JsonObject): ChannelViewerAuthority? {
    val capsElement = row["channelCapabilities"] as? JsonObject ?: return null
    val capabilities =
        runCatching {
            channelAuthorityJson.decodeFromJsonElement(WireChannelCapabilities.serializer(), capsElement).toModel()
        }.getOrNull() ?: return null
    val role =
        (row["channelRole"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val adminBasis =
        (row["channelAdminBasis"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val revision =
        (row["channelAuthorityRevision"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.longOrNull
    return ChannelViewerAuthority(
        role = role,
        adminBasis = adminBasis,
        capabilities = capabilities,
        revision = revision,
    )
}

/**
 * Dedicated store of the viewer's per-channel authority (web `channelAuthorityStore.ts` +
 * `channelAuthorityFetch.ts`, botiverse/slock #17055 & #17097).
 *
 * Authority is keyed by `(serverId, channelId)` and written ONLY by per-viewer HTTP reads
 * (`GET /api/channels`, `GET /api/channels/:id`, `POST /api/channels`), ordered by the monotonic
 * `observedSeq` captured when each request started. Broadcast `channel:updated` rows carry none of
 * these fields and can never set or erase stored authority.
 *
 * When realtime `channel:authority-updated` arrives for a held channel with a revision strictly
 * above the stored revision (or when no revision / null revision is stored), the store deduplicates
 * by highest in-flight revision and re-reads `GET /api/channels/:id`.
 *
 * Server switches / lost server access clear both stored authority and in-flight read slots for
 * that server while bumping its reset epoch so stale in-flight reads settling later neither write
 * stale authority nor clear a newer read's in-flight slot (Kirby, #17055 review).
 */
@RaftCoreInternalApi class SharedChannelAuthorityStore(
    private val scope: CoroutineScope? = null,
    private val fetchChannelAuthority: (suspend (serverId: String, channelId: String) -> ChannelViewerAuthority?)? = null,
    private val isChannelHeld: (serverId: String, channelId: String) -> Boolean = { _, _ -> false },
    private val onAuthorityRefreshed: (suspend (serverId: String, channelId: String, authority: ChannelViewerAuthority) -> Unit)? = null,
    private val logger: (String) -> Unit = {},
) {
    private data class StoredAuthority(
        val authority: ChannelViewerAuthority,
        val observedSeq: Long,
    )

    private data class InFlightChangeRead(
        val readId: Long,
        val revision: Long,
    )

    private val lock = SynchronizedObject()
    private val storedByChannel = linkedMapOf<ChannelAuthorityKey, StoredAuthority>()
    private val firstReads = mutableMapOf<ChannelAuthorityKey, Long>()
    private val changeReads = mutableMapOf<ChannelAuthorityKey, InFlightChangeRead>()
    private val serverEpochs = mutableMapOf<String, Long>()
    private var globalEpoch: Long = 0L
    private var seqCounter: Long = 0L
    private var nextReadId: Long = 0L

    private val mutableState = MutableStateFlow(ChannelAuthorityStoreState())
    val state: StateFlow<ChannelAuthorityStoreState> = mutableState.asStateFlow()

    /** Allocates the next monotonic request-start clock tick. */
    fun nextObservedSeq(): Long = synchronized(lock) { ++seqCounter }

    /**
     * Captures the request-start clock tick AND current reset epochs for [serverId]. Pass the
     * returned token to [writeAnswers] once the HTTP answer arrives so answers that raced a server
     * reset, logout, or newer request are refused.
     */
    fun captureRequestToken(serverId: String): ChannelAuthorityRequestToken =
        synchronized(lock) { captureRequestTokenLocked(serverId.trim()) }

    private fun captureRequestTokenLocked(cleanServerId: String): ChannelAuthorityRequestToken =
        ChannelAuthorityRequestToken(
            serverId = cleanServerId,
            observedSeq = ++seqCounter,
            globalEpoch = globalEpoch,
            serverEpoch = serverEpochs[cleanServerId] ?: 0L,
        )

    /**
     * Stores what per-viewer answers said for [serverId] as of [observedSeq]. Rows without
     * authority (`authority == null`) are skipped, answers older than the stored `observedSeq` are
     * rejected, and equal authority data preserves the stored instance.
     */
    fun writeAnswers(
        serverId: String,
        rows: Collection<ChannelAuthorityAnswerRow>,
        observedSeq: Long,
    ): Boolean {
        val cleanServerId = serverId.trim().takeIf { it.isNotEmpty() } ?: return false
        if (rows.isEmpty()) return false
        return synchronized(lock) {
            writeAnswersLocked(cleanServerId, rows, observedSeq)
        }
    }

    /**
     * Stores answers guarded by [token]: if a global [clear] or [resetServer] for [token.serverId]
     * happened since [token] was captured, the stale answers are dropped.
     */
    fun writeAnswers(
        token: ChannelAuthorityRequestToken,
        rows: Collection<ChannelAuthorityAnswerRow>,
    ): Boolean {
        val cleanServerId = token.serverId.trim().takeIf { it.isNotEmpty() } ?: return false
        if (rows.isEmpty()) return false
        return synchronized(lock) {
            if (token.globalEpoch != globalEpoch) return@synchronized false
            if (token.serverEpoch != (serverEpochs[cleanServerId] ?: 0L)) return@synchronized false
            writeAnswersLocked(cleanServerId, rows, token.observedSeq)
        }
    }

    /** Convenience writer for a list of wire channel rows (`GET /api/channels`). */
    fun writeWireChannelAnswers(
        token: ChannelAuthorityRequestToken,
        channels: Collection<HomeChannelWireDto>,
    ): Boolean {
        if (channels.isEmpty()) return false
        return writeAnswers(
            token = token,
            rows = channels.map { wire -> ChannelAuthorityAnswerRow(wire.id, wire.readChannelAuthority()) },
        )
    }

    /** Convenience writer for a single channel answer (`GET /api/channels/:id`, `POST /api/channels`). */
    fun writeSingleAnswer(
        token: ChannelAuthorityRequestToken,
        channelId: ChannelId,
        authority: ChannelViewerAuthority?,
    ): Boolean {
        if (authority == null) return false
        return writeAnswers(
            token = token,
            rows = listOf(ChannelAuthorityAnswerRow(channelId.value, authority)),
        )
    }

    private fun writeAnswersLocked(
        cleanServerId: String,
        rows: Collection<ChannelAuthorityAnswerRow>,
        observedSeq: Long,
    ): Boolean {
        var nextPublicByChannel: MutableMap<ChannelAuthorityKey, ChannelViewerAuthority>? = null
        var anyAccepted = false
        for (row in rows) {
            val authority = row.authority ?: continue
            val key = channelAuthorityKeyOrNull(cleanServerId, row.channelId) ?: continue
            val stored = storedByChannel[key]
            if (stored != null && stored.observedSeq > observedSeq) continue
            if (stored != null && stored.observedSeq == observedSeq && stored.authority == authority) continue
            // Preserve the existing authority object reference when the content is equal (web parity).
            val preservedAuthority =
                if (stored != null && stored.authority == authority) stored.authority else authority
            storedByChannel[key] = StoredAuthority(authority = preservedAuthority, observedSeq = observedSeq)
            anyAccepted = true
            val currentPublic = mutableState.value.byChannel[key]
            if (currentPublic !== preservedAuthority) {
                if (nextPublicByChannel == null) {
                    nextPublicByChannel = LinkedHashMap(mutableState.value.byChannel)
                }
                nextPublicByChannel[key] = preservedAuthority
            }
        }
        if (nextPublicByChannel != null) {
            mutableState.value = ChannelAuthorityStoreState(byChannel = nextPublicByChannel)
        }
        return anyAccepted
    }

    /**
     * Whether a `channel:authority-updated` at [revision] says more than what is stored for
     * `(serverId, channelId)`: nothing stored, no membership row stored (`revision == null` is
     * below every number), or a lower revision. Equal or higher means already known (e.g. a
     * redelivered outbox event).
     */
    fun authorityRevisionIsNewer(serverId: String, channelId: String, revision: Long): Boolean {
        val key = channelAuthorityKeyOrNull(serverId, channelId) ?: return false
        return synchronized(lock) { authorityRevisionIsNewerLocked(key, revision) }
    }

    private fun authorityRevisionIsNewerLocked(key: ChannelAuthorityKey, revision: Long): Boolean {
        val stored = storedByChannel[key] ?: return true
        val currentRevision = stored.authority.revision
        return currentRevision == null || currentRevision < revision
    }

    fun authorityOf(serverId: String?, channelId: String?): ChannelViewerAuthority? =
        mutableState.value.authorityOf(serverId, channelId)

    fun viewOf(serverId: String?, channelId: String?): ChannelViewerAuthorityView =
        mutableState.value.viewOf(serverId, channelId)

    fun observe(serverId: String?, channelId: String?): Flow<ChannelViewerAuthorityView> {
        val key = channelAuthorityKeyOrNull(serverId, channelId)
        return state
            .map { current ->
                if (key == null) {
                    ChannelViewerAuthorityView.Loading
                } else {
                    current.byChannel[key]?.let(ChannelViewerAuthorityView::Known)
                        ?: ChannelViewerAuthorityView.Loading
                }
            }
            .distinctUntilChanged()
    }

    fun observeServer(serverId: String?): Flow<Map<String, ChannelViewerAuthority>> {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        return state
            .map { current ->
                if (cleanServerId == null) emptyMap() else current.forServer(cleanServerId)
            }
            .distinctUntilChanged()
    }

    /**
     * A shown channel whose authority is not known yet: read `GET /api/channels/:id` once. One
     * request in flight per `(serverId, channelId)`; a failed read is not retried automatically
     * until the channel is shown again.
     */
    fun requestChannelAuthority(serverId: String, channelId: String): Boolean {
        val coroutineScope = scope ?: return false
        val fetcher = fetchChannelAuthority ?: return false
        val key = channelAuthorityKeyOrNull(serverId, channelId) ?: return false
        val (readId, requestToken) =
            synchronized(lock) {
                if (key in firstReads || key in storedByChannel) {
                    null
                } else {
                    val id = ++nextReadId
                    firstReads[key] = id
                    id to captureRequestTokenLocked(key.serverId)
                }
            } ?: return false
        coroutineScope.launch {
            try {
                val authority = fetcher(key.serverId, key.channelId)
                if (authority != null) {
                    val accepted = writeSingleAnswer(requestToken, ChannelId(key.channelId), authority)
                    if (accepted) {
                        onAuthorityRefreshed?.invoke(key.serverId, key.channelId, authority)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                logger(
                    "channel_authority_first_read_failed serverId=${key.serverId} " +
                        "channelId=${key.channelId} error=${throwable::class.simpleName ?: "Throwable"}"
                )
            } finally {
                synchronized(lock) {
                    // Only free this read's own slot: after a server reset a newer read may hold it (Kirby, #17055).
                    if (firstReads[key] == readId) {
                        firstReads.remove(key)
                    }
                }
            }
        }
        return true
    }

    /**
     * Handles realtime `channel:authority-updated` (`{ channelId, channelRole, authorityRevision }`):
     * the viewer's role changed. Carries no capabilities, so when the channel is held on [serverId]
     * and [revision] is strictly above the stored revision (or stored revision is null/absent),
     * re-reads `GET /api/channels/:id` once per revision (deduplicating redeliveries and revisions
     * at or below an already in-flight refresh). Channels not held on [serverId] are ignored (the
     * event is sent to the viewer's user room across all their servers).
     */
    fun onChannelAuthorityUpdated(serverId: String, event: ChannelAuthorityUpdated): Boolean =
        refetchChannelAuthorityAfterChange(
            serverId = serverId,
            channelId = event.channelId.value,
            revision = event.authorityRevision,
        )

    fun refetchChannelAuthorityAfterChange(
        serverId: String,
        channelId: String,
        revision: Long,
    ): Boolean {
        val coroutineScope = scope ?: return false
        val fetcher = fetchChannelAuthority ?: return false
        val key = channelAuthorityKeyOrNull(serverId, channelId) ?: return false
        val alreadyStored = synchronized(lock) { key in storedByChannel }
        val held = alreadyStored || isChannelHeld(key.serverId, key.channelId)
        if (!held) return false
        val (readId, requestToken) =
            synchronized(lock) {
                if (!authorityRevisionIsNewerLocked(key, revision)) {
                    null
                } else {
                    val inFlight = changeReads[key]
                    if (inFlight != null && inFlight.revision >= revision) {
                        null
                    } else {
                        val id = ++nextReadId
                        changeReads[key] = InFlightChangeRead(readId = id, revision = revision)
                        id to captureRequestTokenLocked(key.serverId)
                    }
                }
            } ?: return false
        coroutineScope.launch {
            try {
                val authority = fetcher(key.serverId, key.channelId)
                if (authority != null) {
                    val accepted = writeSingleAnswer(requestToken, ChannelId(key.channelId), authority)
                    if (accepted) {
                        onAuthorityRefreshed?.invoke(key.serverId, key.channelId, authority)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                logger(
                    "channel_authority_refresh_failed serverId=${key.serverId} " +
                        "channelId=${key.channelId} revision=$revision " +
                        "error=${throwable::class.simpleName ?: "Throwable"}"
                )
            } finally {
                synchronized(lock) {
                    // Only free this read's own slot: after a server reset or higher revision a newer read may hold it.
                    if (changeReads[key]?.readId == readId) {
                        changeReads.remove(key)
                    }
                }
            }
        }
        return true
    }

    /**
     * Clears stored authority and in-flight read slots for [serverId], and bumps its reset epoch so
     * in-flight reads from before the reset cannot write stale data or clear a newer read's slot.
     * Called on server switch away from [serverId] and on lost server access.
     */
    fun resetServer(serverId: String) {
        val cleanServerId = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            serverEpochs[cleanServerId] = (serverEpochs[cleanServerId] ?: 0L) + 1L
            var removed = false
            val storedIter = storedByChannel.entries.iterator()
            while (storedIter.hasNext()) {
                if (storedIter.next().key.serverId == cleanServerId) {
                    storedIter.remove()
                    removed = true
                }
            }
            firstReads.keys.removeAll { it.serverId == cleanServerId }
            changeReads.keys.removeAll { it.serverId == cleanServerId }
            if (removed) {
                val next = mutableState.value.byChannel.filterKeys { it.serverId != cleanServerId }
                mutableState.value = ChannelAuthorityStoreState(byChannel = next)
            }
        }
    }

    /**
     * Clears every server's stored authority and in-flight read slots and bumps the global reset
     * epoch (sign-out, account switch, or environment switch).
     */
    fun clear() {
        synchronized(lock) {
            globalEpoch += 1L
            storedByChannel.clear()
            firstReads.clear()
            changeReads.clear()
            serverEpochs.clear()
            if (mutableState.value.byChannel.isNotEmpty()) {
                mutableState.value = ChannelAuthorityStoreState()
            }
        }
    }
}
