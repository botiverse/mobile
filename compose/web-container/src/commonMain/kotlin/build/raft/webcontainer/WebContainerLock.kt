package build.raft.webcontainer

/** Lightweight re-entrant cross-platform mutual exclusion lock (zero external dependencies). */
internal interface WebContainerLock {
    fun <T> withLock(block: () -> T): T
}

internal expect fun createWebContainerLock(): WebContainerLock
