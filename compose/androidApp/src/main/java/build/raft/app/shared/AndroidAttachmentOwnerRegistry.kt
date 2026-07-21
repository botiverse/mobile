package build.raft.app.shared

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal data class AndroidAttachmentOwnerLease(
    val shellInstanceId: String,
    val scopeId: String,
    val authEpoch: Long,
    val principalKey: String,
    val generation: Long
)

internal fun interface AndroidAttachmentRetirementHook {
    /** Runs under registry write lease + scope gate. Returned IO action runs after both locks release. */
    fun invalidateLocked(): (() -> Unit)?
}

internal class AndroidAttachmentOwnerRegistration(
    private val closeAction: () -> Unit
) {
    fun close() = closeAction()
}

/** Lock order: registry read/write lease -> scope gate. Target locks are never nested with either. */
internal class AndroidAttachmentOwnerRegistry {
    private val registryLock = ReentrantReadWriteLock(true)
    private val scopes = ConcurrentHashMap<String, ScopeState>()
    private val currentScopeByShell = mutableMapOf<String, String>()
    private val tombstones = mutableSetOf<String>()
    private var currentAuthEpoch: Long? = null
    private var retiredThroughAuthEpoch: Long = Long.MIN_VALUE

    fun bind(lease: AndroidAttachmentOwnerLease) {
        val cleanup = mutableListOf<() -> Unit>()
        registryLock.write {
            if (lease.scopeId in tombstones || lease.authEpoch <= retiredThroughAuthEpoch) return@write
            val epoch = currentAuthEpoch
            if (epoch != null && lease.authEpoch < epoch) return@write
            if (epoch != null && lease.authEpoch > epoch) {
                currentScopeByShell.clear()
                scopes.values.forEach { state ->
                    tombstones += state.lease.scopeId
                    state.retireLocked(cleanup)
                }
            }
            currentAuthEpoch = lease.authEpoch
            val previousScopeId = currentScopeByShell.put(lease.shellInstanceId, lease.scopeId)
            if (previousScopeId != null && previousScopeId != lease.scopeId) {
                tombstones += previousScopeId
                scopes[previousScopeId]?.retireLocked(cleanup)
            }
            val state = scopes.computeIfAbsent(lease.scopeId) { ScopeState(lease) }
            synchronized(state.gate) {
                if (state.lease != lease) {
                    tombstones += lease.scopeId
                    state.retireLocked(cleanup)
                    currentScopeByShell.remove(lease.shellInstanceId, lease.scopeId)
                }
            }
        }
        cleanup.forEach { it() }
    }

    fun retireShell(shellInstanceId: String) {
        val cleanup = mutableListOf<() -> Unit>()
        registryLock.write {
            currentScopeByShell.remove(shellInstanceId)?.let { scopeId ->
                tombstones += scopeId
                scopes[scopeId]?.retireLocked(cleanup)
            }
        }
        cleanup.forEach { it() }
    }

    fun retireAccount() {
        val cleanup = mutableListOf<() -> Unit>()
        registryLock.write {
            currentAuthEpoch?.let { retiredThroughAuthEpoch = maxOf(retiredThroughAuthEpoch, it) }
            currentScopeByShell.clear()
            scopes.values.forEach { state ->
                tombstones += state.lease.scopeId
                state.retireLocked(cleanup)
            }
            currentAuthEpoch = null
        }
        cleanup.forEach { it() }
    }

    fun admit(lease: AndroidAttachmentOwnerLease): Boolean =
        registryLock.read {
            val state = scopes[lease.scopeId] ?: return@read false
            synchronized(state.gate) { state.currentLocked(lease) }
        }

    fun <T> runIfCurrent(lease: AndroidAttachmentOwnerLease, block: () -> T): T? =
        registryLock.read {
            val state = scopes[lease.scopeId] ?: return@read null
            synchronized(state.gate) {
                if (!state.currentLocked(lease)) return@synchronized null
                block()
            }
        }

    fun <T> registerOperation(
        lease: AndroidAttachmentOwnerLease,
        operationId: String,
        hook: AndroidAttachmentRetirementHook,
        block: () -> T
    ): Pair<T, AndroidAttachmentOwnerRegistration>? =
        registryLock.read {
            val state = scopes[lease.scopeId] ?: return@read null
            synchronized(state.gate) {
                if (!state.currentLocked(lease)) return@synchronized null
                val hookKey = "$operationId:${System.identityHashCode(hook)}"
                state.hooks[hookKey] = hook
                val value = try {
                    block()
                } catch (throwable: Throwable) {
                    state.hooks.remove(hookKey)
                    throw throwable
                }
                value to AndroidAttachmentOwnerRegistration {
                    registryLock.read {
                        synchronized(state.gate) { state.hooks.remove(hookKey) }
                    }
                }
            }
        }

    private class ScopeState(val lease: AndroidAttachmentOwnerLease) {
        val gate = Any()
        val hooks = linkedMapOf<String, AndroidAttachmentRetirementHook>()
        var retired = false

        fun currentLocked(expected: AndroidAttachmentOwnerLease): Boolean = !retired && lease == expected

        fun retireLocked(cleanup: MutableList<() -> Unit>) {
            synchronized(gate) {
                if (retired) return
                retired = true
                hooks.values.mapNotNullTo(cleanup) { it.invalidateLocked() }
                hooks.clear()
            }
        }
    }
}

internal val ProcessAndroidAttachmentOwners = AndroidAttachmentOwnerRegistry()
