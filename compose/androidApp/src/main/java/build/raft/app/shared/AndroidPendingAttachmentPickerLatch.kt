package build.raft.app.shared

/**
 * Tracks a single in-flight Android attachment/camera picker launch so an abnormal dismissal
 * (where an OEM ROM or Activity trampoline never delivers an ActivityResult) can be recovered
 * safely on Activity resume without clobbering a real result or a newer picker launch.
 */
internal class AndroidPendingAttachmentPickerLatch<T : Any> {
    private var currentToken: Long = 0L
    private var currentPending: T? = null
    private var leftActivityWhilePending: Boolean = false

    val isBusy: Boolean
        get() = currentPending != null

    val activePending: T?
        get() = currentPending

    /** Starts tracking [pending] and returns its launch token, or null if another launch is active. */
    fun begin(pending: T): Long? {
        if (currentPending != null) return null
        val token = ++currentToken
        currentPending = pending
        leftActivityWhilePending = false
        return token
    }

    /** Records that the host Activity left the foreground while a picker launch was active. */
    fun onHostPausedOrStopped() {
        if (currentPending != null) {
            leftActivityWhilePending = true
        }
    }

    /**
     * Returns the active launch token when the host resumed after leaving for the picker,
     * signaling that a post-resume orphan check should be scheduled; null otherwise.
     */
    fun resumeRecoveryTokenOrNull(): Long? =
        if (currentPending != null && leftActivityWhilePending) currentToken else null

    /** Consumes and clears the active pending launch (normal result, launch error, or teardown). */
    fun consume(): T? {
        val pending = currentPending ?: return null
        currentPending = null
        leftActivityWhilePending = false
        return pending
    }

    /**
     * Recovers an orphaned launch only if [expectedToken] is still the active pending launch and
     * the host previously paused/stopped while waiting for it. A stale check posted for an earlier
     * launch is a no-op once that launch completed or a newer launch started.
     */
    fun recoverIfOrphaned(expectedToken: Long): T? {
        val pending = currentPending ?: return null
        if (currentToken != expectedToken || !leftActivityWhilePending) return null
        currentPending = null
        leftActivityWhilePending = false
        return pending
    }
}
