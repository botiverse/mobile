package build.raft.webcontainer

import platform.Foundation.NSRecursiveLock

internal actual fun createWebContainerLock(): WebContainerLock =
    object : WebContainerLock {
        private val lock = NSRecursiveLock()

        override fun <T> withLock(block: () -> T): T {
            lock.lock()
            return try {
                block()
            } finally {
                lock.unlock()
            }
        }
    }
