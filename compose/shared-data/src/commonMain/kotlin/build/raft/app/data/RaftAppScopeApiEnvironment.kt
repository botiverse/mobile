package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * One process-wide [SharedApiEnvironmentStore] over the app-scope API environment key-value store.
 *
 * The shared store provider and the environment indicator read the same instance, so the indicator
 * can show the active environment without building the store provider (and with it opening the
 * database), and still follows a Developer environment switch made through the provider.
 */
@RaftCoreInternalApi class RaftAppScopeApiEnvironmentHolder(
    private val createBinding: () -> RaftSharedKeyValueStoreBinding,
) {
    private val lock = SynchronizedObject()
    private var binding: RaftSharedKeyValueStoreBinding? = null
    private var store: SharedApiEnvironmentStore? = null

    /** The binding backing [store]; created together with it on first use. */
    fun binding(): RaftSharedKeyValueStoreBinding =
        synchronized(lock) {
            ensureCreated()
            checkNotNull(binding)
        }

    fun store(): SharedApiEnvironmentStore =
        synchronized(lock) {
            ensureCreated()
            checkNotNull(store)
        }

    fun reloadIfLoaded() {
        synchronized(lock) {
            store?.reload()
        }
    }

    private fun ensureCreated() {
        if (store != null) return
        val created = createBinding()
        binding = created
        store = SharedApiEnvironmentStore(created.store)
    }
}

@RaftCoreInternalApi object RaftAppScopeApiEnvironment {
    @Volatile
    private var holder = newHolder()

    private fun newHolder() =
        RaftAppScopeApiEnvironmentHolder {
            RaftSharedKeyValueStoreProvider.create(
                namespace = SharedApiEnvironmentStore.Namespace,
                storageName = "API environment",
            )
        }

    /** Forgets the process store so the next read binds the currently installed key-value store. */
    @RaftCoreInternalApi fun resetForTests() {
        holder = newHolder()
    }

    fun binding(): RaftSharedKeyValueStoreBinding = holder.binding()

    fun store(): SharedApiEnvironmentStore = holder.store()

    fun reloadIfLoaded() {
        holder.reloadIfLoaded()
    }
}

/** Re-reads the process-wide [SharedApiEnvironmentStore] from storage if it has already been loaded. */
fun reloadRaftAppScopeApiEnvironmentIfLoaded() {
    RaftAppScopeApiEnvironment.reloadIfLoaded()
}
