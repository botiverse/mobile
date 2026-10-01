package build.raft.app

import androidx.compose.runtime.mutableStateOf
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * task #58 composition-removal case — fixture-side controller for
 * [MemberDetailRemovalProbe]. Lives in the gated `visualFixtures` source set
 * so production builds never ship a mutable handle into the detail page.
 *
 * Contract (per review scope):
 *  - one controller per run token; register before launching the host and
 *    unregister in finally so a stale command can never reach a later host;
 *  - [setRemoved] is called from the test thread; the boolean is Compose
 *    state read on the composition path — the flip is applied by the real
 *    recomposition scheduler, so removal completion is proven by the
 *    dispose_save log line, not by this call returning;
 *  - the controller itself never touches the registry, generation, or the
 *    scroll key — the remove/re-add gap leaves all persistence invariants
 *    intact.
 */
public object MemberDetailRemovalFixtureBridge {
    private val lock = SynchronizedObject()
    private val controllers = mutableMapOf<String, RemovalController>()

    public fun register(token: String) {
        val controller = RemovalController()
        synchronized(lock) { controllers[token] = controller }
        MemberDetailRemovalProbe.register(token, controller)
    }

    public fun setRemoved(token: String, removed: Boolean) {
        val controller =
            synchronized(lock) { controllers[token] }
                ?: error("no removal controller registered for token $token")
        controller.state.value = removed
    }

    public fun isRemoved(token: String): Boolean =
        synchronized(lock) { controllers[token] }?.state?.value
            ?: error("no removal controller registered for token $token")

    public fun unregister(token: String) {
        MemberDetailRemovalProbe.unregister(token)
        synchronized(lock) { controllers.remove(token) }
    }

    private class RemovalController : MemberDetailRemovalController {
        val state = mutableStateOf(false)
        override val removed get() = state
    }
}
