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
)

internal fun artifactSummaryKey(serverId: String, target: ArtifactLinkTarget): String = "$serverId\u0000${target.key}"

/** The answers kept for [targets] in [serverId] (expired ones included until a new answer replaces them). */
fun SharedArtifactSummaryState.answersFor(serverId: String, targets: List<ArtifactLinkTarget>): Map<String, ArtifactSummaryAnswer> =
    buildMap {
        val kept = this@answersFor.entries
        for (target in targets) kept[artifactSummaryKey(serverId, target)]?.let { put(target.key, it.answer) }
    }

/**
 * Artifact summaries for the messages on screen (the KMP mirror of web `objectSummaryLoader.ts`):
 *
 * - Batching: every [request] within [batchWindowMillis] goes out as one request per Server, each
 *   reference once, at most [OBJECT_SUMMARIES_MAX_REFS] a request. A reference already asked and not
 *   answered yet is not asked again.
 * - Cache: an answer is kept per (Server, reference): a summary [okTtlMillis], `unavailable`
 *   [unavailableTtlMillis], the oldest dropped past [maxEntries]. In memory only (answers are per
 *   viewer, `no-store`), dropped on [clearAll] (sign-out, account or environment switch). An expired
 *   answer is still shown until its new one lands.
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
    /** Fixtures only: answers already kept. */
    initialState: SharedArtifactSummaryState = SharedArtifactSummaryState(),
) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<SharedArtifactSummaryState> = mutableState.asStateFlow()

    private val lock = SynchronizedObject()
    /** Asked and not answered yet (pending or sent), by [artifactSummaryKey]. */
    private val inFlight = mutableSetOf<String>()
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
        val entries = mutableState.value.entries
        val schedule =
            synchronized(lock) {
                var added = false
                for (target in targets) {
                    val key = artifactSummaryKey(serverId, target)
                    val kept = entries[key]
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
        if (!schedule) return
        scope.launch {
            delay(batchWindowMillis)
            flush()
        }
    }

    /** Drops every answer (sign-out, account or environment switch). An answer on its way is not kept. */
    fun clearAll() {
        synchronized(lock) {
            inFlight.clear()
            pending.clear()
        }
        mutableState.update { SharedArtifactSummaryState(generation = it.generation + 1) }
    }

    private fun flush() {
        val batches =
            synchronized(lock) {
                flushScheduled = false
                val taken = pending.map { (server, refs) -> server to refs.values.toList() }
                pending.clear()
                taken
            }
        for ((serverId, targets) in batches) {
            targets.chunked(OBJECT_SUMMARIES_MAX_REFS).forEach { chunk -> scope.launch { send(serverId, chunk) } }
        }
    }

    private suspend fun send(serverId: String, targets: List<ArtifactLinkTarget>) {
        val generation = mutableState.value.generation
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
        val answers = response?.takeIf { it.isSuccessful }?.let { parseArtifactSummariesAnswer(it.bodyText) }
        release(serverId, targets)
        if (answers == null) {
            diagnosticsLogger("artifact_summaries_failed refs=${targets.size} code=${response?.code ?: -1}")
            return
        }
        val now = nowMillis()
        mutableState.update { state ->
            if (state.generation != generation) return@update state
            val entries = LinkedHashMap(state.entries)
            for (target in targets) {
                // A reference the answer leaves out: unavailable (web loader).
                val answer = answers[target.key] ?: ArtifactSummaryAnswer.Unavailable
                val ttl = if (answer is ArtifactSummaryAnswer.Ok) okTtlMillis else unavailableTtlMillis
                val key = artifactSummaryKey(serverId, target)
                entries.remove(key)
                entries[key] = ArtifactSummaryEntry(answer, now + ttl)
            }
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
            state.copy(entries = entries)
        }
    }

    private fun release(serverId: String, targets: List<ArtifactLinkTarget>) {
        synchronized(lock) { targets.forEach { inFlight.remove(artifactSummaryKey(serverId, it)) } }
    }
}
