package build.raft.app

import kotlinx.coroutines.delay

internal const val THREAD_SERVER_SLUG_MAX_ATTEMPTS = 10
internal const val THREAD_SERVER_SLUG_RETRY_DELAY_MS = 500L

/**
 * task #1153: the captured session snapshot can precede server-roster
 * population, leaving the server slug null and silently disabling quoted
 * permalink cards for the whole thread session. Re-invokes [capture] for a
 * bounded window until the slug resolves.
 *
 * [capture] is suspending because the producer-context capture itself
 * suspends, so [attempts] is a retry count, not a strict wall-clock bound.
 * Runs inside the caller's coroutine: cancellation by the enclosing
 * LaunchedEffect stops the loop between attempts and during [sleep].
 */
internal suspend fun resolveThreadServerSlugWithRetry(
    attempts: Int = THREAD_SERVER_SLUG_MAX_ATTEMPTS,
    sleep: suspend (Long) -> Unit = { delay(it) },
    capture: suspend () -> String?,
): String? {
    var retries = 0
    while (retries < attempts) {
        retries++
        sleep(THREAD_SERVER_SLUG_RETRY_DELAY_MS)
        val slug = capture()
        if (slug != null) return slug
    }
    return null
}
