package build.raft.app.data

import platform.Foundation.NSThread

@kotlin.native.concurrent.ThreadLocal
actual object SlockStoreConfinement {
    private var depth = 0

    actual fun enter() {
        depth += 1
    }

    actual fun exit() {
        depth -= 1
    }

    actual fun isConfined(): Boolean = depth > 0

    actual fun currentThreadLabel(): String =
        if (NSThread.isMainThread) "main" else NSThread.currentThread.name?.takeIf { it.isNotEmpty() } ?: "background"
}
