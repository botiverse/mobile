package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A session generation for client caches that moves when someone logs in or the session is cleared,
 * never on a silent token refresh. Keying caches on the access token made every refresh look like a
 * new session: the create form lost its runtime list, the computer list was wiped mid-dialog (the
 * dialog reloaded its v2 form and dropped a typed API key), HuaTuo #raft-mobile-reconcile cf5e3b7d /
 * 17e2cca3, task #147.
 *
 * It records the auth store's own event generation, so several collectors may feed it the same
 * event without moving it twice.
 */
internal class SharedAuthLoginGeneration {
    private val lock = SynchronizedObject()
    private var value = 0L

    fun current(): String = synchronized(lock) { value.toString(16) }

    fun onAuthSessionEvent(event: AuthSessionEvent) {
        if (event is AuthSessionEvent.Authenticated || event is AuthSessionEvent.Cleared) {
            synchronized(lock) { if (event.generation > value) value = event.generation }
        }
    }
}
