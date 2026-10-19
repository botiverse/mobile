package build.raft.app.data

internal actual object SlockStoreConfinement {
    private val depth = ThreadLocal<IntArray>()

    private fun counter(): IntArray = depth.get() ?: IntArray(1).also(depth::set)

    actual fun enter() {
        counter()[0] += 1
    }

    actual fun exit() {
        counter()[0] -= 1
    }

    actual fun isConfined(): Boolean = (depth.get()?.get(0) ?: 0) > 0

    actual fun currentThreadLabel(): String = Thread.currentThread().name
}
