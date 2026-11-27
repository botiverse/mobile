package build.raft.app.thread

import build.raft.app.ReplyCacheProvenance
import build.raft.app.ReplyResolutionScope
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadReplyCacheIdentityTransitionAnchor
import build.raft.app.get
import build.raft.app.threadPayloadOwnerScopeId
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How [SharedThreadDataSource] captures the identity a request runs under, moved out of it unchanged; the data
 * source forwards to it. Sole owner of the capture sequence and of the last captured cache owner ids; the
 * provenance itself and its lock belong to [provenanceOwner].
 */
internal class ThreadReplyIdentityCapture(
    private val provenanceOwner: ThreadReplyCacheProvenanceOwner,
    private val messageStore: SharedThreadMessageStore?,
    private val replyCacheIdentityAuthority: suspend () -> ThreadReplyCacheIdentitySnapshot?,
    private val replyCacheIdentityTransitionWaiter:
        (suspend (ThreadReplyCacheIdentityTransitionAnchor?) -> Boolean)?,
    private val replyCacheIdentitySourceCapturedHook: () -> (suspend () -> Unit)?,
) {
    private val replyCacheProvenance: StateFlow<ReplyCacheProvenance?> get() = provenanceOwner.replyCacheProvenanceState
    private val onReplyCacheIdentitySourceCapturedForTest: (suspend () -> Unit)? get() = replyCacheIdentitySourceCapturedHook()

    internal val cacheEnvironmentId = MutableStateFlow("")
    internal val cacheServerId = MutableStateFlow("")
    internal val cachePrincipalId = MutableStateFlow("")
    internal val cachePayloadOwnerScopeId = MutableStateFlow("")
    private val replyCacheCaptureSequence = atomic(0L)

    internal suspend fun readReplyResolutionScope(): ReplyResolutionScope? {
        val captureSequence = replyCacheCaptureSequence.incrementAndGet()
        var transitionAttempts = 0
        while (true) {
            var source: ThreadReplyCacheIdentitySnapshot? = null
            var sampleAttempts = 0
            while (source == null && sampleAttempts < 4) {
                sampleAttempts += 1
                val candidate = readReplyCacheIdentitySource()
                onReplyCacheIdentitySourceCapturedForTest?.invoke()
                val confirmed = readReplyCacheIdentitySource()
                if (candidate == confirmed) {
                    source = confirmed
                    break
                }
            }
            if (source != null) {
                val provenance = publishReplyCacheProvenance(source, captureSequence)
                cacheEnvironmentId.value = provenance.environmentId
                cacheServerId.value = provenance.serverId
                cachePrincipalId.value = provenance.principalId
                cachePayloadOwnerScopeId.value = provenance.threadPayloadOwnerScopeId.orEmpty()
                return ReplyResolutionScope(
                    session = source.session,
                    provenance = provenance,
                    sharedMessageStore = messageStore != null
                )
            }

            // A durable session can become visible before the provider-owned auth-revision collector
            // publishes its matching immutable context or before this shell's selected-server owner
            // converges. Retire the old token before waiting so stale page producers stay zero-write,
            // then pass that retired authority as the only license for an A→B owner transition. Stable
            // hybrids, environment mismatches, and missing authority remain fail-closed.
            val transitionAnchor =
                replyCacheProvenance.value?.let { current ->
                    ThreadReplyCacheIdentityTransitionAnchor(
                        environmentId = current.environmentId,
                        principalId = current.principalId,
                        authGeneration = current.authGeneration,
                        selectionGeneration = current.selectionGeneration,
                    )
                }
            clearReplyCacheProvenance(captureSequence)
            val shouldRetry =
                transitionAttempts < 4 &&
                    replyCacheIdentityTransitionWaiter?.invoke(transitionAnchor) == true
            if (!shouldRetry) return null
            transitionAttempts += 1
        }
    }

    private suspend fun readReplyCacheIdentitySource(): ThreadReplyCacheIdentitySnapshot? =
        replyCacheIdentityAuthority()

    private fun publishReplyCacheProvenance(
        source: ThreadReplyCacheIdentitySnapshot,
        captureSequence: Long
    ): ReplyCacheProvenance = provenanceOwner.publishReplyCacheProvenance(source, captureSequence)

    private fun clearReplyCacheProvenance(captureSequence: Long) {
        provenanceOwner.clearReplyCacheProvenance(captureSequence)
        cacheEnvironmentId.value = ""
        cacheServerId.value = ""
        cachePrincipalId.value = ""
        cachePayloadOwnerScopeId.value = ""
    }
}
