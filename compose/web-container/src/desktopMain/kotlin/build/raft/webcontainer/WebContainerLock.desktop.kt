package build.raft.webcontainer

internal actual fun createWebContainerLock(): WebContainerLock =
    object : WebContainerLock {
        private val monitor = Any()

        override fun <T> withLock(block: () -> T): T = synchronized(monitor, block)
    }
