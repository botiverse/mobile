package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Disk-cached raw `summaries[]` entry for stale-while-revalidate (#proj-mobile:#294). */
@Serializable
internal data class PersistedArtifactSummaryRecord(
    val rawEntryJson: String,
)

internal val ArtifactSummaryResourceSpec: RaftOfflineResourceSpec<String, PersistedArtifactSummaryRecord> =
    RaftOfflineResourceSpec(
        name = "artifact_summary",
        version = 1,
        serializer = PersistedArtifactSummaryRecord.serializer(),
        paramsId = { refKey -> refKey },
        maxEntriesPerAccount = 500,
        maxBytesPerEntry = 64 * 1024,
        maxAgeMillis = 7L * 24 * 60 * 60 * 1000L,
    )

/** web `objectSummaryLoader.ts`: gather lookups this long into one request. */
const val ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS: Long = 10L
/** A summary is kept this long. */
const val ARTIFACT_SUMMARY_OK_TTL_MILLIS: Long = 60_000L
/** `unavailable` is kept this long (it may become visible: shared, published). */
const val ARTIFACT_SUMMARY_UNAVAILABLE_TTL_MILLIS: Long = 15_000L
/** At most this many answers kept. */
const val ARTIFACT_SUMMARY_MAX_ENTRIES: Int = 1_000

/** A kept answer and when it stops counting. */
data class ArtifactSummaryEntry(val answer: ArtifactSummaryAnswer, val expiresAtMillis: Long)

/** By [artifactSummaryKey]: one viewer's answers, per Server and reference. */
data class SharedArtifactSummaryState(
    val entries: Map<String, ArtifactSummaryEntry> = emptyMap(),
    /** Moves on [SharedArtifactSummaryStore.clearAll]: an answer asked for before it is not kept. */
    val generation: Int = 0,
    /**
     * By [artifactSummaryKey]: references whose last request failed (web loader `error`), until
     * they are asked again. Nothing is kept for them: [answersFor] reports
     * [ArtifactSummaryAnswer.Failed] where no answer is kept, so a card isn't left loading.
     */
    val failed: Set<String> = emptySet(),
)

internal fun artifactSummaryKey(serverId: String, target: ArtifactLinkTarget): String = "$serverId\u0000${target.key}"

/**
 * The answers kept for [targets] in [serverId] (expired ones included until a new answer replaces
 * them); with none kept, [ArtifactSummaryAnswer.Failed] when its last request failed. A target
 * missing from the map is still loading (or not asked yet).
 */
fun SharedArtifactSummaryState.answersFor(serverId: String, targets: List<ArtifactLinkTarget>): Map<String, ArtifactSummaryAnswer> =
    buildMap {
        val kept = this@answersFor.entries
        for (target in targets) {
            val key = artifactSummaryKey(serverId, target)
            val answer = kept[key]?.answer ?: ArtifactSummaryAnswer.Failed.takeIf { key in failed }
            answer?.let { put(target.key, it) }
        }
    }

/**
 * Artifact summaries for the messages on screen (the KMP mirror of web `objectSummaryLoader.ts`):
 *
 * - Batching: every [request] within [batchWindowMillis] goes out as one request per Server, each
 *   reference once, at most [OBJECT_SUMMARIES_MAX_REFS] a request. A reference already asked and not
 *   answered yet is not asked again.
 * - Cache: an answer is kept per (Server, reference): a summary [okTtlMillis], `unavailable`
 *   [unavailableTtlMillis], the oldest dropped past [maxEntries]. Display-only summaries are also
 *   cached per (environment, account, Server) via [RaftOfflineResource] for cold-start
 *   stale-while-revalidate (#proj-mobile:#294), dropped in memory on [clearAll] (sign-out, account
 *   or environment switch) and [dropServer] (server access loss). An expired answer is still shown
 *   until its new one lands.
 * - Errors: a failed request keeps nothing, so the next [request] asks again; its links stay plain.
 */
class SharedArtifactSummaryStore internal constructor(
    private val api: RaftArtifactSummariesApi,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val batchWindowMillis: Long = ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS,
    private val okTtlMillis: Long = ARTIFACT_SUMMARY_OK_TTL_MILLIS,
    private val unavailableTtlMillis: Long = ARTIFACT_SUMMARY_UNAVAILABLE_TTL_MILLIS,
    private val maxEntries: Int = ARTIFACT_SUMMARY_MAX_ENTRIES,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val offlineResource: RaftOfflineResource<String, PersistedArtifactSummaryRecord>? = null,
    private val currentAccountId: () -> String? = { null },
    /** Fixtures only: answers already kept. */
    initialState: SharedArtifactSummaryState = SharedArtifactSummaryState(),
) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<SharedArtifactSummaryState> = mutableState.asStateFlow()

    private val lock = SynchronizedObject()
    /** Asked and not answered yet (pending or sent), by [artifactSummaryKey]. */
    private val inFlight = mutableSetOf<String>()
    /** Disk hydrations already checked in this session generation, by [artifactSummaryKey]. */
    private val diskHydrationAttempted = mutableSetOf<String>()
    /** Bumped per server by [dropServer] so in-flight responses for a lost server are rejected. */
    private val serverGenerations = HashMap<String, Long>()
    /** Not sent yet, per Server, in order. */
    private val pending = LinkedHashMap<String, LinkedHashMap<String, ArtifactLinkTarget>>()
    private var flushScheduled = false

    /** One message's answers, emitted only when they change: the selector a row collects. */
    fun answersFlow(serverId: String, targets: List<ArtifactLinkTarget>): Flow<Map<String, ArtifactSummaryAnswer>> =
        state.map { it.answersFor(serverId, targets) }.distinctUntilChanged()

    /** Asks for every one of [targets] in [serverId] that has no current answer and isn't asked yet. */
    fun request(serverId: String, targets: List<ArtifactLinkTarget>) {
        if (serverId.isBlank() || targets.isEmpty()) return
        val now = nowMillis()
        val accountIdForDisk = currentAccountId()?.trim()?.takeIf { it.isNotEmpty() }
        val toHydrate = mutableListOf<ArtifactLinkTarget>()
        var hydrateGeneration = 0
        var hydrateServerGeneration = 0L
        val schedule =
            synchronized(lock) {
                val stateSnapshot = mutableState.value
                val entries = stateSnapshot.entries
                hydrateGeneration = stateSnapshot.generation
                hydrateServerGeneration = serverGenerations[serverId] ?: 0L
                var added = false
                for (target in targets) {
                    val key = artifactSummaryKey(serverId, target)
                    val kept = entries[key]
                    if (kept == null && offlineResource != null && accountIdForDisk != null && diskHydrationAttempted.add(key)) {
                        toHydrate += target
                    }
                    if (kept != null && kept.expiresAtMillis > now) continue
                    if (!inFlight.add(key)) continue
                    pending.getOrPut(serverId) { LinkedHashMap() }[target.key] = target
                    added = true
                }
                if (added && !flushScheduled) {
                    flushScheduled = true
                    true
                } else {
                    false
                }
            }
        if (toHydrate.isNotEmpty() && offlineResource != null && accountIdForDisk != null) {
            scope.launch {
                hydrateFromDisk(
                    serverId = serverId,
                    accountId = accountIdForDisk,
                    targets = toHydrate,
                    generation = hydrateGeneration,
                    serverGeneration = hydrateServerGeneration,
                )
            }
        }
        // Asked again: no longer failed (loading until its answer lands).
        val asked = targets.map { artifactSummaryKey(serverId, it) }
        if (mutableState.value.failed.any { it in asked }) mutableState.update { it.copy(failed = it.failed - asked.toSet()) }
        if (!schedule) return
        scope.launch {
            delay(batchWindowMillis)
            flush()
        }
    }

    private suspend fun hydrateFromDisk(
        serverId: String,
        accountId: String,
        targets: List<ArtifactLinkTarget>,
        generation: Int,
        serverGeneration: Long,
    ) {
        val resource = offlineResource ?: return
        val hydrated = LinkedHashMap<String, ArtifactSummaryAnswer>()
        for (target in targets) {
            val diskKey = resource.key(accountId, serverId, target.key) ?: continue
            val record = runCatchingPreservingCancellation { resource.read(diskKey) }.getOrNull() ?: continue
            val parsed = parsePersistedArtifactSummaryEntry(record.rawEntryJson) ?: continue
            hydrated[artifactSummaryKey(serverId, target)] = parsed
        }
        if (hydrated.isEmpty()) return
        synchronized(lock) {
            if ((serverGenerations[serverId] ?: 0L) != serverGeneration) return
            mutableState.update { state ->
                if (state.generation != generation) return@update state
                var changed = false
                val entries = LinkedHashMap(state.entries)
                hydrated.forEach { (key, answer) ->
                    // Never overwrite a live in-memory entry; disk copy enters stale so network revalidates.
                    if (key !in entries) {
                        entries[key] = ArtifactSummaryEntry(answer = answer, expiresAtMillis = Long.MIN_VALUE)
                        changed = true
                    }
                }
                if (!changed) return@update state
                while (entries.size > maxEntries) entries.remove(entries.keys.first())
                state.copy(entries = entries)
            }
        }
    }

    /**
     * Expires [serverId]'s answers (`activity:changed`): still shown, but the next [request] for them
     * asks again.
     */
    fun invalidateServer(serverId: String) {
        mutableState.update { state ->
            val prefix = "$serverId\u0000"
            if (state.entries.keys.none { it.startsWith(prefix) }) return@update state
            state.copy(entries = state.entries.mapValues { (key, entry) -> if (key.startsWith(prefix)) entry.copy(expiresAtMillis = Long.MIN_VALUE) else entry })
        }
    }

    /**
     * Patches the reader's own Follow/Unfollow answer into every kept summary of [artifactId] in
     * [serverId] (pinned and unpinned) and marks them stale so the next read revalidates (web
     * `setArtifactFollowingHere` -> `invalidateObjectSummary("artifact", id)`, slock #16357).
     */
    fun patchFollowing(serverId: String, artifactId: String, following: Boolean) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val canonical = artifactCanonicalId(artifactId)
        val prefix = "$cleanServer\u0000"
        synchronized(lock) {
            mutableState.update { state ->
                var changed = false
                val nextEntries =
                    state.entries.mapValues { (key, entry) ->
                        if (!key.startsWith(prefix)) return@mapValues entry
                        val ok = entry.answer as? ArtifactSummaryAnswer.Ok ?: return@mapValues entry
                        if (artifactCanonicalId(ok.summary.id) != canonical) return@mapValues entry
                        changed = true
                        entry.copy(
                            answer = ArtifactSummaryAnswer.Ok(ok.summary.copy(following = following)),
                            expiresAtMillis = Long.MIN_VALUE,
                        )
                    }
                if (changed) state.copy(entries = nextEntries) else state
            }
        }
    }

    /** Drops one server's kept summaries and fences any in-flight responses (server access lost). */
    fun dropServer(serverId: String) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val prefix = "$cleanServer\u0000"
        synchronized(lock) {
            serverGenerations[cleanServer] = (serverGenerations[cleanServer] ?: 0L) + 1L
            inFlight.removeAll { it.startsWith(prefix) }
            diskHydrationAttempted.removeAll { it.startsWith(prefix) }
            pending.remove(cleanServer)
            mutableState.update { state ->
                val nextEntries = state.entries.filterKeys { !it.startsWith(prefix) }
                val nextFailed = state.failed.filterNotTo(LinkedHashSet()) { it.startsWith(prefix) }
                if (nextEntries.size == state.entries.size && nextFailed.size == state.failed.size) {
                    state
                } else {
                    state.copy(entries = nextEntries, failed = nextFailed)
                }
            }
        }
    }

    /**
     * Asks again for [target] in [serverId] now, whatever is kept (the Artifact viewer opening: a
     * 403/404 must replace its screen). The kept answer stays shown until the new one lands.
     */
    fun refresh(serverId: String, target: ArtifactLinkTarget) {
        val key = artifactSummaryKey(serverId, target)
        mutableState.update { state ->
            val entry = state.entries[key] ?: return@update state
            state.copy(entries = state.entries + (key to entry.copy(expiresAtMillis = Long.MIN_VALUE)))
        }
        request(serverId, listOf(target))
    }

    /** Whether [target]'s answer in [serverId] is current (asked for and answered, not expired). */
    fun isFresh(serverId: String, target: ArtifactLinkTarget): Boolean =
        (mutableState.value.entries[artifactSummaryKey(serverId, target)]?.expiresAtMillis ?: Long.MIN_VALUE) > nowMillis()

    /** Drops every answer (sign-out, account or environment switch). An answer on its way is not kept. */
    fun clearAll() {
        synchronized(lock) {
            inFlight.clear()
            diskHydrationAttempted.clear()
            pending.clear()
            mutableState.update { SharedArtifactSummaryState(generation = it.generation + 1) }
        }
    }

    private fun flush() {
        val batches =
            synchronized(lock) {
                flushScheduled = false
                val generation = mutableState.value.generation
                val taken =
                    pending.map { (server, refs) ->
                        Triple(server, refs.values.toList(), generation to (serverGenerations[server] ?: 0L))
                    }
                pending.clear()
                taken
            }
        for ((serverId, targets, tokens) in batches) {
            val (generation, serverGeneration) = tokens
            targets.chunked(OBJECT_SUMMARIES_MAX_REFS).forEach { chunk ->
                scope.launch { send(serverId, chunk, generation, serverGeneration) }
            }
        }
    }

    private suspend fun send(
        serverId: String,
        targets: List<ArtifactLinkTarget>,
        generation: Int,
        serverGeneration: Long,
    ) {
        val resource = offlineResource
        val accountIdAtStart = currentAccountId()?.trim()?.takeIf { it.isNotEmpty() }
        val fetchStartedAt = resource?.fetchStarted()
        val response =
            try {
                api.summaries(serverId, targets)
            } catch (cancellation: CancellationException) {
                release(serverId, targets)
                throw cancellation
            } catch (error: Throwable) {
                diagnosticsLogger("artifact_summaries_error refs=${targets.size} error=${error::class.simpleName}")
                null
            }
        val answersWithRaw = response?.takeIf { it.isSuccessful }?.let { parseArtifactSummariesAnswerWithRawEntries(it.bodyText) }
        release(serverId, targets)
        if (answersWithRaw == null) {
            diagnosticsLogger("artifact_summaries_failed refs=${targets.size} code=${response?.code ?: -1}")
            synchronized(lock) {
                if ((serverGenerations[serverId] ?: 0L) != serverGeneration) return
                mutableState.update { state ->
                    if (state.generation != generation) state else state.copy(failed = state.failed + targets.map { artifactSummaryKey(serverId, it) })
                }
            }
            return
        }
        val now = nowMillis()
        var accepted = false
        synchronized(lock) {
            if ((serverGenerations[serverId] ?: 0L) != serverGeneration) return
            mutableState.update { state ->
                if (state.generation != generation) return@update state
                accepted = true
                val entries = LinkedHashMap(state.entries)
                for (target in targets) {
                    // A reference the answer leaves out: unavailable (web loader).
                    val answer = answersWithRaw[target.key]?.first ?: ArtifactSummaryAnswer.Unavailable
                    val ttl = if (answer is ArtifactSummaryAnswer.Ok) okTtlMillis else unavailableTtlMillis
                    val key = artifactSummaryKey(serverId, target)
                    entries.remove(key)
                    entries[key] = ArtifactSummaryEntry(answer, now + ttl)
                }
                while (entries.size > maxEntries) entries.remove(entries.keys.first())
                val answered = targets.map { artifactSummaryKey(serverId, it) }.toSet()
                state.copy(entries = entries, failed = if (state.failed.isEmpty()) state.failed else state.failed - answered)
            }
        }
        if (accepted && resource != null && accountIdAtStart != null && fetchStartedAt != null) {
            for (target in targets) {
                val diskKey = resource.key(accountIdAtStart, serverId, target.key, mintedAt = fetchStartedAt) ?: continue
                val pair = answersWithRaw[target.key]
                val raw = pair?.second
                if (raw != null) {
                    runCatchingPreservingCancellation {
                        resource.write(diskKey, PersistedArtifactSummaryRecord(raw), startedAt = fetchStartedAt)
                    }
                } else {
                    runCatchingPreservingCancellation {
                        resource.removeIfNotSuperseded(diskKey, startedAt = fetchStartedAt)
                    }
                }
            }
        }
    }

    private fun release(serverId: String, targets: List<ArtifactLinkTarget>) {
        synchronized(lock) { targets.forEach { inFlight.remove(artifactSummaryKey(serverId, it)) } }
    }
}
