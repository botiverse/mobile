package build.raft.app.network

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Single-flight serialization for the curl runtime install (task #252): a
 * cold-start request burst must not fan out concurrent KNOI/configure calls.
 * Exactly one caller runs [install] while the rest wait on the lock and then
 * observe its outcome via [isInstalled]; a failed install releases the lock
 * so the next request retries.
 */
internal class CurlRuntimeInstallGate(
    private val isInstalled: () -> Boolean
) {
    private val lock = SynchronizedObject()

    fun ensure(install: () -> Boolean): Boolean {
        if (isInstalled()) return true
        return synchronized(lock) {
            if (isInstalled()) true else install()
        }
    }
}
