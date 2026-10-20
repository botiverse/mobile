package build.raft.app.data

@kotlin.native.concurrent.ThreadLocal
internal actual object RaftStoreConfinement {
    private var depth = 0

    actual fun enter() {
        depth += 1
    }

    actual fun exit() {
        depth -= 1
    }

    actual fun isConfined(): Boolean = depth > 0

    actual fun currentThreadLabel(): String = "native"
}
