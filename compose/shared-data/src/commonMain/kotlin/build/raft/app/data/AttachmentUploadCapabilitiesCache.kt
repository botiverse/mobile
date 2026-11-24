package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Default freshness window for cached upload capabilities (plan limits change rarely). */
@RaftCoreInternalApi const val ATTACHMENT_UPLOAD_CAPABILITIES_TTL_MS: Long = 5L * 60L * 1000L

/**
 * Per-authority (account + server + environment) cache of `attachments/upload-capabilities`.
 *
 * Picking an attachment must not wait on this request: on a weak network it can take many
 * seconds (DNS + connect + TLS), and the composer chip is admitted before it is needed. Callers
 * use [peek] on the pick path (never suspends, never touches the network) and [getOrFetch] from the
 * background upload task. The composer prefetches on open so the first pick usually hits the cache.
 * Only successful results are cached; a failed probe is asked again by the next caller.
 */
@RaftCoreInternalApi class AttachmentUploadCapabilitiesCache(
    private val nowMillis: () -> Long,
    private val ttlMillis: Long = ATTACHMENT_UPLOAD_CAPABILITIES_TTL_MS,
) {
    private data class Entry(
        val key: String,
        val capabilities: AttachmentUploadCapabilities,
        val fetchedAtMillis: Long,
    )

    /** One fetch in flight for one authority; the callers waiting on it read [answer]. */
    private class Probe {
        val answer = CompletableDeferred<DataResult<AttachmentUploadCapabilities>>()
    }

    /** The fetching caller went away before an answer; a waiter fetches itself. */
    private class FetcherGone : CancellationException("upload capability fetcher cancelled")

    /** What a caller of [getOrFetch] does, decided under [lock]. */
    private sealed interface Step {
        class Cached(val capabilities: AttachmentUploadCapabilities) : Step

        class Wait(val probe: Probe) : Step

        class Fetch(val probe: Probe) : Step
    }

    private val lock = SynchronizedObject()
    private var entry: Entry? = null
    private val probes = mutableMapOf<String, Probe>()

    /** Fresh cached value for [authority], or null. Never suspends. */
    fun peek(authority: RaftExpectedAuthBinding): AttachmentUploadCapabilities? =
        synchronized(lock) { freshEntry(keyFor(authority)) }?.capabilities

    /**
     * Cached value if fresh, otherwise [fetch], once for every caller waiting at the same time: they
     * all take that fetch's answer, a failure too (the network bounds it at the 30 s of a control
     * call), instead of each sending the probe again after it in turn (web slock #15743: a probe that
     * runs out of time no longer queues the later uploads behind it). A caller that comes after the
     * answer asks afresh. If the fetching caller is cancelled, the callers waiting on it do not
     * inherit that: the first of them fetches again. A waiter's own cancellation only ends its wait.
     */
    suspend fun getOrFetch(
        authority: RaftExpectedAuthBinding,
        fetch: suspend () -> DataResult<AttachmentUploadCapabilities>,
    ): DataResult<AttachmentUploadCapabilities> {
        val key = keyFor(authority)
        while (true) {
            val step =
                synchronized(lock) {
                    val fresh = freshEntry(key)
                    val inFlight = probes[key]
                    when {
                        fresh != null -> Step.Cached(fresh.capabilities)
                        inFlight != null -> Step.Wait(inFlight)
                        else -> Step.Fetch(Probe().also { started -> probes[key] = started })
                    }
                }
            val probe =
                when (step) {
                    is Step.Cached -> return DataResult.Content(step.capabilities)
                    is Step.Wait ->
                        try {
                            return step.probe.answer.await()
                        } catch (cancellation: CancellationException) {
                            // This waiter's own cancellation rethrows here; otherwise the fetcher went away.
                            currentCoroutineContext().ensureActive()
                            continue
                        }
                    is Step.Fetch -> step.probe
                }
            try {
                // A composer upload's capability probe (`control_ms`): only a real fetch counts, never a
                // cache hit or the wait for another caller's fetch.
                val result = raftUploadControlCall { fetch() }
                synchronized(lock) {
                    if (result is DataResult.Content) {
                        entry = Entry(key, result.data, nowMillis())
                    }
                    if (probes[key] === probe) probes.remove(key)
                }
                probe.answer.complete(result)
                return result
            } catch (cancellation: CancellationException) {
                synchronized(lock) { if (probes[key] === probe) probes.remove(key) }
                probe.answer.completeExceptionally(FetcherGone())
                throw cancellation
            } catch (error: Throwable) {
                synchronized(lock) { if (probes[key] === probe) probes.remove(key) }
                probe.answer.completeExceptionally(error)
                throw error
            }
        }
    }

    fun clear() {
        synchronized(lock) { entry = null }
    }

    private fun freshEntry(key: String): Entry? {
        val current = entry ?: return null
        if (current.key != key) return null
        val age = nowMillis() - current.fetchedAtMillis
        return current.takeIf { age in 0L until ttlMillis }
    }

    private fun keyFor(authority: RaftExpectedAuthBinding): String =
        "${authority.environmentId}|${authority.serverId}|${authority.accountId}"
}
